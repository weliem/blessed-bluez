package blessed;

import blessed.bluez.BluezDevice;
import blessed.bluez.BluezGattCharacteristic;
import blessed.bluez.BluezGattDescriptor;
import blessed.bluez.BluezGattService;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.DBusListType;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static blessed.BluetoothGattCharacteristic.*;

public class BluetoothPeripheral {

    private static final String TAG = BluetoothPeripheral.class.getSimpleName();

    // Keep pointer to raw device object
    private final BluezDevice device;
    private final String deviceName;
    private final String deviceAddress;
    private boolean isBonded = false;
    private boolean manualBonding = false;
    private long connectTimestamp;
    private boolean isPairing;
    private boolean isRetrying;
    private byte[] currentWriteBytes;

    // Keep track of all native services/characteristics/descriptors
    private final Map<String, BluezGattService> serviceMap = new ConcurrentHashMap<>();
    private final Map<String, BluezGattCharacteristic> characteristicMap = new ConcurrentHashMap<>();
    private final Map<String, BluezGattDescriptor> descriptorMap = new ConcurrentHashMap<>();

    // Keep track of all translated services
    private List<BluetoothGattService> mServices;

    // Internal handler
    private final Handler handler;
    private final Handler bleHandler;

    // Variables for service discovery timer
    private final Handler timeoutHandler;
    private Runnable timeoutRunnable;
    private static final int SERVICE_DISCOVERY_TIMEOUT_IN_MS = 10000;

    private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();
    private boolean commandQueueBusy;
    private int nrTries;
    private static final int MAX_TRIES = 2;

    public static final String CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    /**
     * A GATT operation completed successfully
     */
    public static final int GATT_SUCCESS = 0;

    /**
     * Generic error, could be anything
     */
    public static final int GATT_ERROR = 133;

    /**
     * The connection has timed out
     */
    public static final int GATT_CONN_TIMEOUT = 8;

    /**
     * GATT read operation is not permitted
     */
    public static final int GATT_READ_NOT_PERMITTED = 2;

    /**
     * GATT write operation is not permitted
     */
    public static final int GATT_WRITE_NOT_PERMITTED = 3;

    /**
     * Insufficient authentication for a given operation
     */
    public static final int GATT_INSUFFICIENT_AUTHENTICATION = 5;

    /**
     * The given request is not supported
     */
    public static final int GATT_REQUEST_NOT_SUPPORTED = 6;

    /**
     * Insufficient encryption for a given operation
     */
    public static final int GATT_INSUFFICIENT_ENCRYPTION = 15;

    /**
     * The connection was terminated by the peripheral
     */
    public static final int GATT_CONN_TERMINATE_PEER_USER = 19;

    /**
     * Authentication failed
     */
    public static final int GATT_AUTH_FAIL = 137;

    private final Set<UUID> notifyingCharacteristics = new HashSet<>();
    private ConnectionState state;
    private final InternalCallback listener;
    private BluetoothPeripheralCallback peripheralCallback;
    private boolean serviceDiscoveryCompleted = false;

    private final GattCallback gattCallback = new GattCallback() {
        @Override
        public void onConnectionStateChanged(ConnectionState connectionState, int status) {
            switch (connectionState) {
                case Connected:
                    state = ConnectionState.Connected;
                    isBonded = isPaired();
                    HBLogger.i(TAG, String.format("Connected to %s (%s)", getAddress(), isBonded ? "BONDED" : "BOND_NONE"));
                    if (listener != null) {
                        listener.connected(BluetoothPeripheral.this);
                    }
                    break;
                case Disconnected:
                    if(state== ConnectionState.Connecting) {
                        if (listener != null) {
                            state = ConnectionState.Disconnected;
                            listener.connectFailed(BluetoothPeripheral.this);
                            completeDisconnect(false);
                        }
                    } else {
                        state = ConnectionState.Disconnected;
                        if(!serviceDiscoveryCompleted) {
                            listener.serviceDiscoveryFailed(BluetoothPeripheral.this);
                        }
                        completeDisconnect(true);
                    }
                    break;
                case Connecting:
                    state = ConnectionState.Connecting;
                    if (status == GATT_ERROR) {
                        HBLogger.i(TAG,String.format("Connection failed with status '%s'", statusToString(status)));
                        completeDisconnect(false);
                        if (listener != null) {
                            listener.disconnected(BluetoothPeripheral.this);
                        }
                    }
                    break;
                case Disconnecting:
                    state = ConnectionState.Disconnecting;
                    commandQueue.clear();
                    commandQueueBusy = false;
                    break;
                default:
                    HBLogger.e(TAG, "unhandled connection state");
                    break;
            }
        }

        @Override
        public void onNotifySet(final BluetoothGattCharacteristic characteristic, final boolean enabled) {
            // Keep track of which characteristics are notifying
            if (enabled) {
                notifyingCharacteristics.add(characteristic.getUuid());
            } else {
                notifyingCharacteristics.remove(characteristic.getUuid());
            }

            peripheralCallback.onNotificationStateUpdate(BluetoothPeripheral.this, characteristic, GATT_SUCCESS);
            completedCommand();
        }

        @Override
        public void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status) {
            // Do some checks first
            final BluetoothGattCharacteristic parentCharacteristic = descriptor.getCharacteristic();
            if (status != GATT_SUCCESS) {
                HBLogger.i(TAG,String.format("ERROR: Write descriptor failed device: %s, characteristic: %s", getAddress(), parentCharacteristic.getUuid()));
            }
            peripheralCallback.onDescriptorWrite(BluetoothPeripheral.this, new byte[0], descriptor, status);
            completedCommand();
        }

        @Override
        public void onCharacteristicRead(BluetoothGattCharacteristic characteristic, int status) {
            if (status != GATT_SUCCESS) {
                    HBLogger.e(TAG,String.format(Locale.ENGLISH, "ERROR: Read failed for characteristic: %s, status %d", characteristic.getUuid(), status));
            }

            // Just complete the command. The actual value will come in through onCharacteristicChanged
            completedCommand();
        }

        @Override
        public void onCharacteristicChanged(@NotNull final byte[] value, @NotNull final BluetoothGattCharacteristic characteristic) {
            peripheralCallback.onCharacteristicUpdate(BluetoothPeripheral.this, value, characteristic, GATT_SUCCESS);
        }

        @Override
        public void onCharacteristicWrite(@NotNull final BluetoothGattCharacteristic characteristic, final int status) {
            // Perform some checks on the status field
            if (status != GATT_SUCCESS) {
                    HBLogger.e(TAG,String.format("ERROR: Write failed characteristic: %s, status %s", characteristic.getUuid(), statusToString(status)));
            }

            peripheralCallback.onCharacteristicWrite(BluetoothPeripheral.this, currentWriteBytes, characteristic, status);
            completedCommand();
        }

        @Override
        public void onPaired() {
            HBLogger.i(TAG, "Pairing (bonding) succeeded");
//            if(getName().startsWith("PDL") || getName().startsWith("Philips health band")) disconnect();
            peripheralCallback.onBondingSucceeded(BluetoothPeripheral.this);
        }

        @Override
        public void onPairingFailed() {
            HBLogger.i(TAG, "Pairing failed");
            peripheralCallback.onBondingFailed(BluetoothPeripheral.this);
        }

        @Override
        public void onServicesDiscovered(List<BluetoothGattService> services, int status) {
            serviceDiscoveryCompleted = true;
            HBLogger.i(TAG,String.format( "Discovered %d services", services.size()));
            peripheralCallback.onServicesDiscovered(BluetoothPeripheral.this);
        }
    };

    /*
     * PUBLIC HBDeviceAdapter Interface Methods
     */
    public BluetoothPeripheral(BluezDevice bluezDevice, String deviceName, String deviceAddress, InternalCallback listener, BluetoothPeripheralCallback peripheralCallback) {
        this.device = bluezDevice;
        this.deviceAddress = deviceAddress;
        this.deviceName = deviceName;
        this.listener = listener;
        this.peripheralCallback = peripheralCallback;
        this.handler = new Handler(TAG + deviceAddress);
        this.bleHandler = new Handler("BLE-" + deviceAddress);
        this.timeoutHandler = new Handler(TAG + " serviceDiscovery " + deviceAddress);
        BluezSignalHandler.getInstance().addDevice(deviceAddress, this);
    }

    void setPeripheralCallback(BluetoothPeripheralCallback peripheralCallback) {
        this.peripheralCallback = peripheralCallback;
    }

    public void connect() {
        // Do the connect
        gattCallback.onConnectionStateChanged(ConnectionState.Connecting, GATT_SUCCESS);
        try {
            HBLogger.i(TAG, String.format("Connecting to '%s' (%s)", deviceName, deviceAddress));
            connectTimestamp = System.currentTimeMillis();
            device.connect();
        } catch (DBusExecutionException e) {
            boolean bluezConnectionstate;
            try {
                bluezConnectionstate =  device.isConnected();
            } catch (Exception e2) {
                bluezConnectionstate = false;
            }

            HBLogger.e(TAG, String.format("Connect exception, dbusexecutionexception (%s %s)", state == ConnectionState.Connected ? "connected" : "not connected", bluezConnectionstate ? "connected" : "not connected"));
            HBLogger.e(TAG, e.getMessage());

            // Unregister handler only if we are not connected. A connected event may have already been received!
            if(state != ConnectionState.Connected) {
                cleanupAfterFailedConnect();
            }
        } catch (BluezAlreadyConnectedException e) {
            HBLogger.e(TAG, "Connect exception: already connected");
            gattCallback.onConnectionStateChanged(ConnectionState.Connected, GATT_SUCCESS);
        } catch (BluezNotReadyException e) {
            HBLogger.e(TAG, "Connect exception: not ready");
            HBLogger.e(TAG, e.getMessage());
            cleanupAfterFailedConnect();
        } catch (BluezFailedException e) {
            HBLogger.e(TAG, "Connect exception: connect failed");
            HBLogger.e(TAG, e.getMessage());
            cleanupAfterFailedConnect();
        } catch (BluezInProgressException e) {
            HBLogger.e(TAG, "Connect exception: in progress");
            HBLogger.e(TAG, e.getMessage());
            cleanupAfterFailedConnect();
        }
    }

    private void cleanupAfterFailedConnect() {
        BluezSignalHandler.getInstance().removeDevice(deviceAddress);
        handler.stop();
        timeoutHandler.stop();
        gattCallback.onConnectionStateChanged(ConnectionState.Disconnected, GATT_ERROR);
    }

    public void disconnect() {
        device.disconnect();
    }

    private void completeDisconnect(boolean notify) {
        // Do some cleanup
        bleHandler.stop();
        commandQueue.clear();
        commandQueueBusy = false;

        if (listener != null && notify) {
            listener.disconnected(BluetoothPeripheral.this);
        }
    }

    /**
     * Read a characteristic of the connected device.
     * The command contains information about the service and characteristic to read.
     *
     * @param characteristic Specifies the characteristic to read.
     */
    public boolean readCharacteristic(final BluetoothGattCharacteristic characteristic) {
        // Make sure we are still connected
        if(state != ConnectionState.Connected) {
            gattCallback.onCharacteristicRead(characteristic, GATT_ERROR);
            return false;
        }

        // Check if characteristic is valid
        if (characteristic == null) {
            HBLogger.e(TAG, "characteristic is 'null', ignoring read request");
            return false;
        }

        // Check if this characteristic actually has READ property
        if ((characteristic.getProperties() & PROPERTY_READ) == 0) {
            HBLogger.i(TAG,"ERROR: Characteristic cannot be read");
            return false;
        }

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.getUuid());
        if (nativeCharacteristic == null) {
            HBLogger.e(TAG, "ERROR: Native characteristic is null");
            gattCallback.onCharacteristicRead(characteristic, GATT_ERROR);
            return false;
        }

        // All in order, do the read
        boolean result = commandQueue.add(() -> {
            if(state == ConnectionState.Connected) {
                try {
                    HBLogger.i(TAG,String.format("reading characteristic <%s>", nativeCharacteristic.getUuid()));
                    nativeCharacteristic.readValue(new HashMap<>());
                    gattCallback.onCharacteristicRead(characteristic, GATT_SUCCESS);
                } catch (BluezFailedException | BluezInvalidOffsetException | BluezInProgressException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_ERROR);
                    HBLogger.e(TAG, e);
                } catch (BluezNotPermittedException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_READ_NOT_PERMITTED);
                    HBLogger.e(TAG, e);
                } catch (BluezNotAuthorizedException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_INSUFFICIENT_AUTHENTICATION);
                    HBLogger.e(TAG, e);
                } catch (BluezNotSupportedException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_REQUEST_NOT_SUPPORTED);
                    HBLogger.e(TAG, e);
                } catch (DBusExecutionException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_ERROR);
                    HBLogger.e(TAG, "ERROR: " + e.getMessage());
                } catch (Exception e) {
                    HBLogger.e(TAG, "ERROR: " + e.getMessage());
                }
            }
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG,"ERROR: Could not enqueue read characteristic command");
        }
        return result;
    }

    /**
     * Write a value of a characteristic of the connected device
     * The command contains information about the service, characteristic and value to write.
     *
     * @param characteristic Specifies which service, characteristic and value to write.
     */
    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic, final byte[] value,  final int writeType) {
        // Make sure we are still connected
        if(state != ConnectionState.Connected) {
            gattCallback.onCharacteristicWrite(characteristic, GATT_ERROR);
            return false;
        }

        // Check if characteristic is valid
        if (characteristic == null) {
            HBLogger.e(TAG, "characteristic is 'null', ignoring write request");
            return false;
        }

        // Check if byte array is valid
        if (value == null) {
            HBLogger.e(TAG, "value to write is 'null', ignoring write request");
            return false;
        }

        // Copy the value to avoid race conditions
        final byte[] bytesToWrite = copyOf(value);

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.getUuid());
        if (nativeCharacteristic == null) {
            HBLogger.e(TAG, "ERROR: Native characteristic is null");
            return false;
        }

        // Check if this characteristic actually supports this writeType
        int writeProperty;
        switch (writeType) {
            case WRITE_TYPE_DEFAULT:
                writeProperty = PROPERTY_WRITE;
                break;
            case WRITE_TYPE_NO_RESPONSE:
                writeProperty = PROPERTY_WRITE_NO_RESPONSE;
                break;
            case WRITE_TYPE_SIGNED:
                writeProperty = PROPERTY_SIGNED_WRITE;
                break;
            default:
                writeProperty = 0;
                break;
        }
        if ((characteristic.getProperties() & writeProperty) == 0) {
            HBLogger.i(TAG,String.format(Locale.ENGLISH, "ERROR: Characteristic cannot be written with this writeType : %d", writeType));
            return false;
        }

        // All in order, do the write
        boolean result = commandQueue.add(() -> {
            if(state == ConnectionState.Connected) {
                try {
                    // Perform the write
                    currentWriteBytes = bytesToWrite;
                    HBLogger.i(TAG,String.format("writing <%s> to characteristic <%s>", bytes2String(bytesToWrite), nativeCharacteristic.getUuid()));
                    HashMap<String, Object> options = new HashMap<>();
                    options.put("type", writeType == WRITE_TYPE_DEFAULT ? "request" : "command");
                    nativeCharacteristic.writeValue(bytesToWrite, options);

                    // Since there is no callback nor characteristic update event for when a write is completed, we can consider this command done
                    gattCallback.onCharacteristicWrite(characteristic, GATT_SUCCESS);
                } catch (DBusExecutionException | BluezNotSupportedException | BluezFailedException | BluezInProgressException | BluezInvalidValueLengthException e) {
                    gattCallback.onCharacteristicWrite(characteristic, GATT_ERROR);
                } catch (BluezNotPermittedException e) {
                    gattCallback.onCharacteristicWrite(characteristic, GATT_WRITE_NOT_PERMITTED);
                } catch (BluezNotAuthorizedException e) {
                    gattCallback.onCharacteristicWrite(characteristic, GATT_INSUFFICIENT_AUTHENTICATION);
                } catch (Exception e) {
                    gattCallback.onCharacteristicWrite(characteristic, GATT_ERROR);
                    HBLogger.e(TAG, "ERROR: " + e.getMessage());
                }
            }
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG,"ERROR: Could not enqueue write characteristic command");
        }
        return result;
    }

    // A Handler is passed in because notifies are async so need to post on the handler for the HBDevice this adapter is tied to
    public boolean setNotify(BluetoothGattCharacteristic characteristic, boolean enable) {
        // Make sure we are still connected
        if(state != ConnectionState.Connected) {
            gattCallback.onNotifySet(characteristic, false);
            return false;
        }

        // Check if characteristic is valid
        if (characteristic == null) {
            HBLogger.e(TAG, "characteristic is 'null', ignoring setNotify request");
            return false;
        }

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.getUuid());
        if (nativeCharacteristic == null) {
            HBLogger.e(TAG, "ERROR: Native characteristic is null");
            gattCallback.onNotifySet(characteristic, false );
            return false;
        }

        // Check if characteristic has NOTIFY or INDICATE properties and set the correct byte value to be written
        byte[] value;
        int properties = characteristic.getProperties();
        if ((properties & PROPERTY_NOTIFY) > 0) {
            value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        } else if ((properties & PROPERTY_INDICATE) > 0) {
            value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        } else {
            HBLogger.i(TAG,String.format("ERROR: Characteristic %s does not have notify of indicate property", characteristic.getUuid()));
            return false;
        }

        // All in order, do the set notify
        boolean result = commandQueue.add(() -> {
            if(state == ConnectionState.Connected) {
                try {
                    if (enable) {
                        HBLogger.i(TAG,String.format("startNotify for characteristic <%s>", nativeCharacteristic.getUuid()));
                        boolean isNotifying = nativeCharacteristic.isNotifying();
//                HBLogger.i(TAG,String.format("Currently notifying %s", isNotifying));
                        if (isNotifying) {
                            // Already notifying, ignoring command
                            gattCallback.onNotifySet(characteristic, true);
                        } else {
                            nativeCharacteristic.startNotify();
                        }
                    } else {
                        HBLogger.i(TAG,String.format("stopNotify for characteristic <%s>", nativeCharacteristic.getUuid()));
                        nativeCharacteristic.stopNotify();
                    }
                } catch (BluezFailedException | BluezInProgressException | BluezNotPermittedException | BluezNotSupportedException e) {
                    HBLogger.e(TAG, "ERROR: Notify failed");
                } catch (Exception e) {
                    gattCallback.onNotifySet(characteristic, false);
                    HBLogger.e(TAG, "ERROR: " + e.getMessage());
                }
            }
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG,"ERROR: Could not enqueue set notify characteristic command");
        }
        return result;
    }

    /*
     * PRIVATE METHODS
     */
    private void servicesResolved() {
        // Make sure we are connected
        if(state != ConnectionState.Connected) {
            HBLogger.e(TAG, "Services resolved but not connected");
            return;
        }

        // Make sure we start with an empty slate
        clearMaps();

        // Process all service, characteristics and descriptors
        if (device != null) {
            // Get all native services
            List<BluezGattService> gattServices = device.getGattServices();

            // Build list of HBService including the linked characteristics and descriptors
            mServices = new ArrayList<>();
            gattServices.forEach(service -> {
                mServices.add(mapBluezGattServiceToBluetoothGattService(service));
            });

            gattCallback.onServicesDiscovered(mServices, GATT_SUCCESS);
        }
    }

    private void clearMaps() {
        serviceMap.clear();
        characteristicMap.clear();
        descriptorMap.clear();
    }

    public static final String BLUEZ_CHARACTERISTIC_INTERFACE = "org.bluez.GattCharacteristic1";
    private static final String PROPERTY_NOTIFYING = "Notifying";
    private static final String PROPERTY_VALUE = "Value";
    private static final String PROPERTY_SERVICES_RESOLVED = "ServicesResolved";
    private static final String PROPERTY_CONNECTED = "Connected";
    private static final String PROPERTY_PAIRED = "Paired";

    private final AbstractPropertiesChangedHandler propertiesChangedHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(Properties.PropertiesChanged propertiesChanged) {
            if (propertiesChanged.getInterfaceName().equals(BLUEZ_CHARACTERISTIC_INTERFACE)) {
                String path = propertiesChanged.getPath();

                propertiesChanged.getPropertiesChanged().forEach((s, value) -> {
                    // See what the update is about
                    if (s.equalsIgnoreCase(PROPERTY_NOTIFYING)) {
                        boolean isNotifying = (Boolean) value.getValue();
                        BluetoothGattCharacteristic bluetoothGattCharacteristic = getCharacteristicFromPath(path);
                        if(bluetoothGattCharacteristic != null) {
                            gattCallback.onNotifySet(bluetoothGattCharacteristic, isNotifying);
                        }
                    } else if (s.equalsIgnoreCase(PROPERTY_VALUE)) {
                        if (value.getType() instanceof DBusListType) {
                            if (value.getValue() instanceof byte[]) {
                                byte[] byteVal = (byte[]) value.getValue();
                                byte[] valueCopy = copyOf(byteVal);
                                BluetoothGattCharacteristic bluetoothGattCharacteristic = getCharacteristicFromPath(path);
                                if(bluetoothGattCharacteristic != null) {
                                    gattCallback.onCharacteristicChanged(valueCopy, bluetoothGattCharacteristic);
                                }
                            }
                        }
                    }
                });
            } else if (propertiesChanged.getInterfaceName().startsWith("org.bluez")) {
//                        System.out.println("devicehandler: handle path " + propertiesChanged.getPath());
//                        System.out.println("interface: " + propertiesChanged.getInterfaceName());
//                        System.out.println("changed " + propertiesChanged.getPropertiesChanged().keySet());
//                        System.out.println("props removed " + propertiesChanged.getPropertiesRemoved());

                propertiesChanged.getPropertiesChanged().forEach((s, variant) -> {
//                    if (!s.equalsIgnoreCase("RSSI")) {
                    handlePropertyChange(s, variant);
//                    }
                });

            }
        }
    };

    private void handlePropertyChange(String key, Variant value) {
//        System.out.println("Changed key " + key);
//        System.out.println("Changed variant type " + value.getType());
//        System.out.println("Changed variant sig " + value.getSig());
//        System.out.println("Changed variant value " + value.getValue());

        if (device != null) {
            if (key.equalsIgnoreCase(PROPERTY_SERVICES_RESOLVED) && value.getValue().equals(true)) {
                cancelServiceDiscoveryTimer();
                servicesResolved();
            } else if (key.equalsIgnoreCase(PROPERTY_SERVICES_RESOLVED) && value.getValue().equals(false)) {
                HBLogger.i(TAG, String.format("ServicesResolved is false (%s)", deviceName));
                gattCallback.onConnectionStateChanged(ConnectionState.Disconnecting, GATT_SUCCESS);
            } else if (key.equalsIgnoreCase(PROPERTY_CONNECTED) && value.getValue().equals(false)) {
                HBLogger.i(TAG, String.format("Connected is false (%s)", deviceName));

                // Clean up
                cancelServiceDiscoveryTimer();
                BluezSignalHandler.getInstance().removeDevice(deviceAddress);
                gattCallback.onConnectionStateChanged(ConnectionState.Disconnected, GATT_SUCCESS);
                handler.stop();
                timeoutHandler.stop();
            } else if (key.equalsIgnoreCase(PROPERTY_CONNECTED) && value.getValue().equals(true)) {
                long timePassed = System.currentTimeMillis() - connectTimestamp;
                HBLogger.i(TAG, String.format("Connected to '%s' (%s) in %.1fs", deviceName, isPaired() ? "BONDED" : "BOND_NONE", timePassed / 1000.0f));
                gattCallback.onConnectionStateChanged(ConnectionState.Connected, GATT_SUCCESS);
                startServiceDiscoveryTimer();
            } else if (key.equalsIgnoreCase(PROPERTY_PAIRED) && value.getValue().equals(true)) {
                isBonded = true;
                gattCallback.onPaired();
            } else if (key.equalsIgnoreCase(PROPERTY_PAIRED) && value.getValue().equals(false)) {
                gattCallback.onPairingFailed();
            } else if (key.equalsIgnoreCase("RSSI")) {
                Short rssi = (Short) value.getValue();
 //               HBLogger.i(TAG, String.format("RSSI %d", rssi));
            }
        }
    }

    void handleSignal(Properties.PropertiesChanged propertiesChanged) {
        propertiesChangedHandler.handle(propertiesChanged);
    }


    /**
     * The current command has been completed, move to the next command in the queue (if any)
     */
    private void completedCommand() {
//        HBLogger.i(TAG, "Command completed");
        isRetrying = false;
        commandQueue.poll();
        commandQueueBusy = false;
        nextCommand();
    }

    /**
     * Retry the current command. Typically used when a read/write fails and triggers a bonding procedure
     */
    private void retryCommand() {
        commandQueueBusy = false;
        Runnable currentCommand = commandQueue.peek();
        if (currentCommand != null) {
            if (nrTries >= MAX_TRIES) {
                // Max retries reached, give up on this one and proceed
                HBLogger.w(TAG,"Max number of tries reached, not retrying operation anymore ");
                commandQueue.poll();
            } else {
                isRetrying = true;
            }
        }
        nextCommand();
    }

    /**
     * Execute the next command in the subscribe queue.
     * A queue is used because the calls have to be executed sequentially.
     * If the read or write fails, the next command in the queue is executed.
     */
    private void nextCommand() {
        synchronized (this) {
            // Check if we are still connected
            if (state != ConnectionState.Connected) {
                HBLogger.i(TAG,String.format("Device %s is not connected, clearing command queue", getAddress()));
                commandQueue.clear();
                commandQueueBusy = false;
                return;
            }

            // If there is still a command being executed then bail out
            if (commandQueueBusy) {
                return;
            }

            // Execute the next command in the queue
            final Runnable bluetoothCommand = commandQueue.peek();
            if (bluetoothCommand != null) {
                commandQueueBusy = true;
                if (!isRetrying) {
                    nrTries = 0;
                }

                bleHandler.post(() -> {
                    try {
                        bluetoothCommand.run();
                    } catch (Exception ex) {
                        HBLogger.w(TAG,String.format("ERROR: Command exception for device '%s'", getName()));
                        ex.printStackTrace();
                        completedCommand();
                    }
                });
            }
        }
    }

    private BluezGattCharacteristic getBluezGattCharacteristic(UUID characteristicUUID) {
        BluezGattCharacteristic characteristic = null;

        for (BluezGattCharacteristic gattCharacteristic : characteristicMap.values()) {
            if (characteristicUUID.toString().equalsIgnoreCase(gattCharacteristic.getUuid())) {
                characteristic = gattCharacteristic;
            }
        }
        return characteristic;
    }

    private BluetoothGattCharacteristic getBluetoothGattCharacteristic(BluezGattCharacteristic bluezGattCharacteristic) {
        UUID characteristicUUID = UUID.fromString(bluezGattCharacteristic.getUuid());
        for (BluetoothGattService service : mServices) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics())
                if (characteristic.getUuid().equals(characteristicUUID)) {
                    return characteristic;
                }
        }
        return null;
    }


    public List<BluetoothGattService> getServices() {
        return mServices;
    }

    public BluetoothGattService getService(UUID serviceUUID) {
        for (BluetoothGattService service : mServices) {
            if (service.getUuid().equals(serviceUUID)) {
                return service;
            }
        }
        return null;
    }

    /**
     * Method to get a characteristic by its UUID
     * @param serviceUUID The service UUID the characteristic should be part of
     * @param characteristicUUID the characteristic UUID
     * @return the BluetoothGattCharacteristic matching the serviceUUID and characteristicUUID
     */
    public BluetoothGattCharacteristic getCharacteristic(UUID serviceUUID, UUID characteristicUUID) {
        BluetoothGattService service = getService(serviceUUID);
        if(service != null) {
            return service.getCharacteristic(characteristicUUID);
        } else {
            return null;
        }
    }

    public String getName() {
        return deviceName;
    };

    public String getAddress() {
        return deviceAddress;
    }

    public ConnectionState getConnectionState() {
        return state;
    }

    public boolean isPaired() {
        isBonded = device.isPaired();
        return isBonded;
    }

    public boolean isNotifying(BluetoothGattCharacteristic characteristic) {
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.getUuid());
        if (nativeCharacteristic == null) {
            HBLogger.e(TAG, "ERROR: Native characteristic is null");
            return false;
        }
        return nativeCharacteristic.isNotifying();
    }


    public boolean pair() {
        HBLogger.i(TAG, String.format("Pairing with '%s' (%s)", deviceName, deviceAddress));
        manualBonding = true;
        connectTimestamp = System.currentTimeMillis();
        boolean result = false;
        try {
            device.pair();
            return true;
        } catch (BluezInvalidArgumentsException e) {
            HBLogger.e(TAG, "Pair exception: invalid argument");
        } catch (BluezFailedException e) {
            HBLogger.e(TAG, "Pair exception: failed");
        } catch (BluezAuthenticationFailedException e) {
            HBLogger.e(TAG, "Pair exception: authentication failed");
        } catch (BluezAlreadyExistsException e) {
            HBLogger.e(TAG, "Pair exception: already exists");
        } catch (BluezAuthenticationCanceledException e) {
            HBLogger.e(TAG, "Pair exception: authentication canceled");
        } catch (BluezAuthenticationRejectedException e) {
            HBLogger.e(TAG, "Pair exception: authentication rejected");
        } catch (BluezAuthenticationTimeoutException e) {
            HBLogger.e(TAG, "Pair exception: authentication timeout");
        } catch (BluezConnectionAttemptFailedException e) {
            HBLogger.e(TAG, "Pair exception: connection attempt failed");
        } catch (DBusExecutionException e) {
            if(e.getMessage().equalsIgnoreCase("No reply within specified time")) {
                HBLogger.e(TAG, "Pairing timeout");
            } else {
                HBLogger.e(TAG, e.getMessage());
            }
        }

        // Clean up after failed pairing
        if(device.isConnected()) {
            device.disconnect();
        } else {
            cleanupAfterFailedConnect();
        }

        return result;
    }


    public Map<Integer, byte[]> getManufacturerData() {
        try {
            Map<Integer, byte[]> result = new HashMap<>();

            // Convert manufacturer data
            final Map<UInt16, byte[]> deviceManufacturerData = device.getManufacturerData();
            if(deviceManufacturerData != null) {
                deviceManufacturerData.forEach((key, value) -> {
                    result.put(key.intValue(), value);
                });
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private BluetoothGattCharacteristic getCharacteristicFromPath(String path) {
        BluezGattCharacteristic characteristic = characteristicMap.get(path);
        if(characteristic == null) return null;

        BluetoothGattCharacteristic bluetoothGattCharacteristic = getBluetoothGattCharacteristic(characteristic);
        if (bluetoothGattCharacteristic == null) {
            HBLogger.e(TAG, String.format("can't find characteristic with path %s", path));
        }
        return bluetoothGattCharacteristic;
    }

    private void startServiceDiscoveryTimer() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }

        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                HBLogger.e(TAG, String.format("Service Discovery timeout, disconnecting '%s'", device.getName()));

                // Disconnecting doesn't work so do it ourselves
                cancelServiceDiscoveryTimer();
                cleanupAfterFailedConnect();
            }
        };

        timeoutHandler.postDelayed(timeoutRunnable, SERVICE_DISCOVERY_TIMEOUT_IN_MS);
    }

    private void cancelServiceDiscoveryTimer() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private BluetoothGattDescriptor mapBluezGattDescriptorToHBDescriptor(BluezGattDescriptor descriptor) {
        // TODO What is permission?
        return new BluetoothGattDescriptor(UUID.fromString(descriptor.getUuid()), 0);
    }


    /**
     * "broadcast"
     * *         "read"
     * *         "write-without-response"
     * *         "write"
     * *         "notify"
     * *         "indicate"
     * *         "authenticated-signed-writes"
     * *         "reliable-write"
     * *         "writable-auxiliaries"
     * *         "encrypt-read"
     * *         "encrypt-write"
     * *         "encrypt-authenticated-read"
     * *         "encrypt-authenticated-write"
     * *         "secure-read" (Server only)
     * *         "secure-write" (Server only)
     *
     * @param flags array of characteristic flags
     * @return flags as an int
     */
    private int mapFlagsToProperty(ArrayList<String> flags) {
        int result = 0;
        if (flags.contains("read")) {
            result = result + PROPERTY_READ;
        }
        if (flags.contains("write-without-response")) {
            result = result + BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        }
        if (flags.contains("write")) {
            result = result + PROPERTY_WRITE;
        }
        if (flags.contains("notify")) {
            result = result + BluetoothGattCharacteristic.PROPERTY_NOTIFY;
        }
        if (flags.contains("indicate")) {
            result = result + BluetoothGattCharacteristic.PROPERTY_INDICATE;
        }
        if (flags.contains("authenticated-signed-writes")) {
            result = result + BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE;
        }

        // TODO add missing values
        return result;
    }

    private BluetoothGattCharacteristic mapBluezGattCharacteristicToBluetoothGattCharacteristic(BluezGattCharacteristic bluetoothGattCharacteristic) {
        int properties = mapFlagsToProperty(bluetoothGattCharacteristic.getFlags());
        BluetoothGattCharacteristic BluetoothGattCharacteristic = new BluetoothGattCharacteristic(UUID.fromString(bluetoothGattCharacteristic.getUuid()), properties, 0);
        // TODO sort our permissions

        // Get all descriptors for this characteristic
        List<BluezGattDescriptor> descriptors = bluetoothGattCharacteristic.getGattDescriptors();

        // Process all descriptors
        if (descriptors != null) {
            descriptors.forEach(descriptor -> {
                descriptorMap.put(descriptor.getDbusPath(), descriptor);
                BluetoothGattCharacteristic.addDescriptor(mapBluezGattDescriptorToHBDescriptor(descriptor));
            });
        }

        return BluetoothGattCharacteristic;
    }

    private BluetoothGattService mapBluezGattServiceToBluetoothGattService(BluezGattService service) {
        // Build up internal services map
        serviceMap.put(service.getDbusPath(), service);

        // Create HBService object
        BluetoothGattService hbService = new BluetoothGattService(UUID.fromString(service.getUuid()));
        hbService.setDevice(this);

        // Get all characteristics for this service
        List<BluezGattCharacteristic> characteristics = service.getGattCharacteristics();

        // Process all characteristics
        if (characteristics != null) {
            characteristics.forEach(bluetoothGattCharacteristic -> {
                // Build up internal characteristic map
                characteristicMap.put(bluetoothGattCharacteristic.getDbusPath(), bluetoothGattCharacteristic);

                // Create BluetoothGattCharacteristic
                BluetoothGattCharacteristic characteristic = mapBluezGattCharacteristicToBluetoothGattCharacteristic(bluetoothGattCharacteristic);
                characteristic.setService(hbService);
                hbService.addCharacteristic(characteristic);
            });
        }

        return hbService;
    }

    /**
     * Converts byte array to hex string
     *
     * @param bytes the byte array to convert
     * @return String representing the byte array as a HEX string
     */
    private static String bytes2String(final byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String statusToString(final int error) {
        switch (error) {
            case GATT_SUCCESS:
                return "SUCCESS";
            case GATT_CONN_TIMEOUT:
                return "GATT CONN TIMEOUT";  // Connection timed out
            case GATT_CONN_TERMINATE_PEER_USER:
                return "GATT CONN TERMINATE PEER USER";
            case GATT_ERROR:
                return "GATT ERROR"; // Device not reachable
            case GATT_AUTH_FAIL:
                return "GATT AUTH FAIL";  // Device needs to be bonded
            default:
                return "UNKNOWN (" + error + ")";
        }
    }

    /**
     * Safe copying of a byte array.
     * @param source the byte array to copy, can be null
     * @return non-null byte array that is a copy of source
     */
    private byte[] copyOf(byte[] source) {
        if (source == null) return new byte[0];
        final int sourceLength = source.length;
        final byte[] copy = new byte[sourceLength];
        System.arraycopy(source, 0, copy, 0, sourceLength);
        return copy;
    }
}


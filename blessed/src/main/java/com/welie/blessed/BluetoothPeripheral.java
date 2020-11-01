package com.welie.blessed;

import com.welie.blessed.bluez.BluezDevice;
import com.welie.blessed.bluez.BluezGattCharacteristic;
import com.welie.blessed.bluez.BluezGattDescriptor;
import com.welie.blessed.bluez.BluezGattService;
import com.welie.blessed.internal.GattCallback;
import com.welie.blessed.internal.Handler;
import com.welie.blessed.internal.InternalCallback;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.DBusListType;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.welie.blessed.BluetoothGattCharacteristic.*;

/**
 * Represents a Bluetooth BLE peripheral
 */
public final class BluetoothPeripheral {
    private static final String TAG = BluetoothPeripheral.class.getSimpleName();
    public static final String ERROR_NATIVE_CHARACTERISTIC_IS_NULL = "ERROR: Native characteristic is null";
    private final Logger logger = LoggerFactory.getLogger(TAG);

    @NotNull
    private final BluetoothCentral central;

    @Nullable
    private BluezDevice device;

    @Nullable
    private String deviceName;

    @NotNull
    private final String deviceAddress;

    @Nullable
    private byte[] currentWriteBytes;

    @NotNull
    private final Handler callBackHandler;

    @NotNull
    private final InternalCallback listener;

    @Nullable
    private BluetoothPeripheralCallback peripheralCallback;

    @NotNull
    protected final Map<String, BluezGattService> serviceMap = new ConcurrentHashMap<>();

    @NotNull
    protected final Map<String, BluezGattCharacteristic> characteristicMap = new ConcurrentHashMap<>();

    @NotNull
    protected final Map<String, BluezGattDescriptor> descriptorMap = new ConcurrentHashMap<>();

    @NotNull
    private List<@NotNull BluetoothGattService> services = new ArrayList<>();

    @Nullable
    private Handler timeoutHandler;

    @Nullable
    private Runnable timeoutRunnable;

    @NotNull
    private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();

    @Nullable
    private Handler queueHandler;

    private volatile boolean commandQueueBusy = false;
    private int nrTries;
    private boolean isBonded = false;
    private boolean manualBonding = false;
    private long connectTimestamp;
    private boolean isRetrying;
    private volatile int state = STATE_DISCONNECTED;
    private boolean serviceDiscoveryCompleted = false;

    // Numeric constants
    private static final int MAX_TRIES = 2;
    private static final int SERVICE_DISCOVERY_TIMEOUT_IN_MS = 10000;

    // Bluez interface names
    static final String BLUEZ_CHARACTERISTIC_INTERFACE = "org.bluez.GattCharacteristic1";
    static final String BLUEZ_DEVICE_INTERFACE = "org.bluez.Device1";

    // Bluez Characteristic properties
    static final String PROPERTY_NOTIFYING = "Notifying";
    static final String PROPERTY_VALUE = "Value";

    // Bluez Device properties
    static final String PROPERTY_SERVICES_RESOLVED = "ServicesResolved";
    static final String PROPERTY_CONNECTED = "Connected";
    static final String PROPERTY_PAIRED = "Paired";
    static final String PROPERTY_SERVICE_UUIDS = "UUIDs";
    static final String PROPERTY_NAME = "Name";
    static final String PROPERTY_ADDRESS = "Address";
    static final String PROPERTY_RSSI = "RSSI";
    static final String PROPERTY_MANUFACTURER_DATA = "ManufacturerData";
    static final String PROPERTY_SERVICE_DATA = "ServiceData";

    /**
     * The peripheral is in disconnected state
     */
    @SuppressWarnings("WeakerAccess")
    public static final int STATE_DISCONNECTED = 0;

    /**
     * The peripheral is in connecting state
     */
    @SuppressWarnings("WeakerAccess")
    public static final int STATE_CONNECTING = 1;

    /**
     * The peripheral is in connected state
     */
    @SuppressWarnings("WeakerAccess")
    public static final int STATE_CONNECTED = 2;

    /**
     * The peripheral is in disconnecting state
     */
    @SuppressWarnings("WeakerAccess")
    public static final int STATE_DISCONNECTING = 3;

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

    /**
     * Indicates the remote device is not bonded (paired).
     * <p>There is no shared link key with the remote device, so communication
     * (if it is allowed at all) will be unauthenticated and unencrypted.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int BOND_NONE = 10;

    /**
     * Indicates bonding (pairing) is in progress with the remote device.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int BOND_BONDING = 11;

    /**
     * Indicates the remote device is bonded (paired).
     * <p>A shared link keys exists locally for the remote device, so
     * communication can be authenticated and encrypted.
     * <p><i>Being bonded (paired) with a remote device does not necessarily
     * mean the device is currently connected. It just means that the pending
     * procedure was completed at some earlier time, and the link key is still
     * stored locally, ready to use on the next connection.
     * </i>
     */
    @SuppressWarnings("WeakerAccess")
    public static final int BOND_BONDED = 12;

    // GattCallback will deal with managing low-level callbacks
    final GattCallback gattCallback = new GattCallback() {
        @Override
        public void onConnectionStateChanged(int connectionState, int status) {
            // TODO process the status field as well
            int previousState = state;
            state = connectionState;

            switch (connectionState) {
                case STATE_CONNECTED:
                    isBonded = isPaired();
                    listener.connected(BluetoothPeripheral.this);
                    break;
                case STATE_DISCONNECTED:
                    if (previousState == STATE_CONNECTING) {
                        listener.connectFailed(BluetoothPeripheral.this);
                        completeDisconnect(false);
                    } else {
                        if (!serviceDiscoveryCompleted) {
//                            if (isBonded) {
//                                // Assume we lost the bond
//                                if (peripheralCallback != null) {
//                                    callBackHandler.post(() -> peripheralCallback.onBondLost(BluetoothPeripheral.this));
//                                }
//                            }
                            listener.serviceDiscoveryFailed(BluetoothPeripheral.this);
                        }
                        completeDisconnect(true);
                    }
                    break;
                case STATE_CONNECTING:
                    break;
                case STATE_DISCONNECTING:
                    commandQueue.clear();
                    commandQueueBusy = false;
                    break;
                default:
                    logger.error("unhandled connection state");
                    break;
            }
        }

        @Override
        public void onNotifySet(final BluetoothGattCharacteristic characteristic, final boolean enabled) {
            if (peripheralCallback != null) {
                callBackHandler.post(() -> peripheralCallback.onNotificationStateUpdate(BluetoothPeripheral.this, characteristic, GATT_SUCCESS));
            }
            completedCommand();
        }

        @Override
        public void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status) {
            // Do some checks first
            final BluetoothGattCharacteristic parentCharacteristic = descriptor.getCharacteristic();
            if (status != GATT_SUCCESS) {
                logger.info(String.format("ERROR: Write descriptor failed device: %s, characteristic: %s", getAddress(), parentCharacteristic.getUuid()));
            }

            if (peripheralCallback != null) {
                callBackHandler.post(() -> peripheralCallback.onDescriptorWrite(BluetoothPeripheral.this, new byte[0], descriptor, status));
            }
            completedCommand();
        }

        @Override
        public void onCharacteristicRead(BluetoothGattCharacteristic characteristic, int status) {
            if (status != GATT_SUCCESS) {
                logger.error(String.format(Locale.ENGLISH, "ERROR: Read failed for characteristic: %s, status %d", characteristic.getUuid(), status));
            }

            // Just complete the command. The actual value will come in through onCharacteristicChanged
            completedCommand();
        }

        @Override
        public void onCharacteristicChanged(@NotNull final byte[] value, @NotNull final BluetoothGattCharacteristic characteristic) {
            if (peripheralCallback != null) {
                callBackHandler.post(() -> peripheralCallback.onCharacteristicUpdate(BluetoothPeripheral.this, value, characteristic, GATT_SUCCESS));
            }
        }

        @Override
        public void onCharacteristicWrite(@NotNull final BluetoothGattCharacteristic characteristic, final int status) {
            // Perform some checks on the status field
            if (status != GATT_SUCCESS) {
                logger.error(String.format("ERROR: Write failed characteristic: %s, status %s", characteristic.getUuid(), statusToString(status)));
            }

            if (peripheralCallback != null) {
                callBackHandler.post(() -> peripheralCallback.onCharacteristicWrite(BluetoothPeripheral.this, currentWriteBytes, characteristic, status));
            }
            completedCommand();
        }

        @Override
        public void onPairingStarted() {
            logger.info("pairing (bonding) started");
            if (peripheralCallback != null) {
                callBackHandler.post(() -> peripheralCallback.onBondingStarted(BluetoothPeripheral.this));
            }
        }

        @Override
        public void onPaired() {
            logger.info("pairing (bonding) succeeded");
            if (peripheralCallback != null) {
                callBackHandler.post(() -> peripheralCallback.onBondingSucceeded(BluetoothPeripheral.this));
            }
        }

        @Override
        public void onPairingFailed() {
            logger.info("pairing failed");
            if (peripheralCallback != null) {
                callBackHandler.post(() -> peripheralCallback.onBondingFailed(BluetoothPeripheral.this));
            }
        }

        @Override
        public void onServicesDiscovered(List<BluetoothGattService> services) {
            serviceDiscoveryCompleted = true;
            logger.info(String.format("discovered %d services for '%s' (%s)", services.size(), getName(), getAddress()));
            if (peripheralCallback != null) {
                callBackHandler.post(() -> peripheralCallback.onServicesDiscovered(BluetoothPeripheral.this));
            }

            // Let Central know as well so it can start scanning again if needed
            listener.servicesDiscovered(BluetoothPeripheral.this);
        }
    };

    public BluetoothPeripheral(@NotNull BluetoothCentral central, @Nullable BluezDevice bluezDevice, @Nullable String deviceName, @NotNull String deviceAddress, @NotNull InternalCallback listener, @Nullable BluetoothPeripheralCallback peripheralCallback, @NotNull Handler callBackHandler) {
        this.central = Objects.requireNonNull(central, "no valid central provided");
        this.device = bluezDevice;
        this.deviceName = deviceName;
        this.deviceAddress = Objects.requireNonNull(deviceAddress, "no valid address provided");
        this.listener = Objects.requireNonNull(listener, "no valid listener provided");
        this.peripheralCallback = peripheralCallback;
        this.callBackHandler = Objects.requireNonNull(callBackHandler, "no callbackhandler provided");
    }

    void setPeripheralCallback(@NotNull final BluetoothPeripheralCallback peripheralCallback) {
        this.peripheralCallback = Objects.requireNonNull(peripheralCallback, "no valid peripheral callback provided");
    }

    public void connect() {
        Objects.requireNonNull(device, "device is null");

        // Do the connect
        gattCallback.onConnectionStateChanged(STATE_CONNECTING, GATT_SUCCESS);

        try {
            logger.info(String.format("connecting to '%s' (%s)", deviceName, deviceAddress));
            BluezSignalHandler.getInstance().addPeripheral(deviceAddress, this);
            queueHandler = new Handler("BLE-" + deviceAddress);
            timeoutHandler = new Handler(TAG + " serviceDiscovery " + deviceAddress);
            connectTimestamp = System.currentTimeMillis();
            device.connect();
        } catch (DBusExecutionException e) {
            logger.error(e.getMessage());

            // Unregister handler only if we are not connected. A connected event may have already been received!
            if (state != STATE_CONNECTED) {
                cleanupAfterFailedConnect();
            }
        } catch (BluezAlreadyConnectedException e) {
            logger.error("connect exception: already connected");
            gattCallback.onConnectionStateChanged(STATE_CONNECTED, GATT_SUCCESS);
        } catch (BluezNotReadyException e) {
            logger.error("connect exception: not ready");
            logger.error(e.getMessage());
            cleanupAfterFailedConnect();
        } catch (BluezFailedException e) {
            logger.error("connect exception: connect failed");
            logger.error(e.getMessage());
            cleanupAfterFailedConnect();
        } catch (BluezInProgressException e) {
            logger.error("connect exception: in progress");
            logger.error(e.getMessage());
            cleanupAfterFailedConnect();
        }
    }

    private void cleanupAfterFailedConnect() {
        BluezSignalHandler.getInstance().removePeripheral(deviceAddress);
        if (timeoutHandler != null) timeoutHandler.stop();
        timeoutHandler = null;
        gattCallback.onConnectionStateChanged(STATE_DISCONNECTED, GATT_ERROR);
    }

    /**
     * Cancel an active or pending connection.
     * <p>
     * This operation is asynchronous and you will receive a callback on onDisconnectedPeripheral.
     */
    public void cancelConnection() {
        central.cancelConnection(this);
    }

    /**
     * Internal disconnect method that does the real disconnect
     */
    void disconnectBluezDevice() {
        logger.info(String.format("force disconnect '%s' (%s)", getName(), getAddress()));
        gattCallback.onConnectionStateChanged(STATE_DISCONNECTING, GATT_SUCCESS);
        if (device != null) {
            device.disconnect();
        }
    }

    private void completeDisconnect(boolean notify) {
        // Do some cleanup
        if (queueHandler != null) {
            queueHandler.stop();
        }
        queueHandler = null;
        commandQueue.clear();
        commandQueueBusy = false;

        if (notify) {
            listener.disconnected(BluetoothPeripheral.this);
        }
    }

    /**
     * Read the value of a characteristic.
     *
     * <p>The characteristic must support reading it, otherwise the operation will not be enqueued.
     *
     * <p>{@link BluetoothPeripheralCallback#onCharacteristicUpdate(BluetoothPeripheral, byte[], BluetoothGattCharacteristic, int)}   will be triggered as a result of this call.
     *
     * @param characteristic Specifies the characteristic to read.
     * @return true if the operation was enqueued, false if the characteristic does not support reading or the characteristic was invalid
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean readCharacteristic(@NotNull final BluetoothGattCharacteristic characteristic) {
        Objects.requireNonNull(characteristic, "characteristic is 'null', ignoring read request");

        // Make sure we are still connected
        if (state != STATE_CONNECTED) {
            gattCallback.onCharacteristicRead(characteristic, GATT_ERROR);
            return false;
        }

        // Check if this characteristic actually has READ property
        if (!characteristic.supportsReading()) {
            logger.error("characteristic does not have read property");
            return false;
        }

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.service.getUuid(), characteristic.getUuid());
        if (nativeCharacteristic == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            gattCallback.onCharacteristicRead(characteristic, GATT_ERROR);
            return false;
        }

        // All in order, do the read
        boolean result = commandQueue.add(() -> {
            if (state == STATE_CONNECTED) {
                try {
                    logger.info(String.format("reading characteristic <%s>", nativeCharacteristic.getUuid()));
                    nativeCharacteristic.readValue(new HashMap<>());
                    gattCallback.onCharacteristicRead(characteristic, GATT_SUCCESS);
                } catch (BluezFailedException | BluezInvalidOffsetException | BluezInProgressException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_ERROR);
                    logger.error(e.toString());
                } catch (BluezNotPermittedException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_READ_NOT_PERMITTED);
                    logger.error(e.toString());
                } catch (BluezNotAuthorizedException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_INSUFFICIENT_AUTHENTICATION);
                    logger.error(e.toString());
                } catch (BluezNotSupportedException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_REQUEST_NOT_SUPPORTED);
                    logger.error(e.toString());
                } catch (DBusExecutionException e) {
                    gattCallback.onCharacteristicRead(characteristic, GATT_ERROR);
                    logger.error("ERROR: " + e.getMessage());
                } catch (Exception e) {
                    logger.error("ERROR: " + e.getMessage());
                }
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.error("ERROR: Could not enqueue read characteristic command");
        }
        return result;
    }

    /**
     * Write a value to a characteristic using the specified write type.
     *
     * <p>All parameters must have a valid value in order for the operation
     * to be enqueued. If the characteristic does not support writing with the specified writeType, the operation will not be enqueued.
     *
     * <p>{@link BluetoothPeripheralCallback#onCharacteristicWrite(BluetoothPeripheral, byte[], BluetoothGattCharacteristic, int)} will be triggered as a result of this call.
     *
     * @param characteristic the characteristic to write to
     * @param value          the byte array to write
     * @param writeType      the write type to use when writing. Must be WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE or WRITE_TYPE_SIGNED
     * @return true if a write operation was succesfully enqueued, otherwise false
     */
    @SuppressWarnings({"UnusedReturnValue", "unused"})
    public boolean writeCharacteristic(@NotNull final BluetoothGattCharacteristic characteristic, @NotNull final byte[] value, final int writeType) {
        Objects.requireNonNull(characteristic, "no valid characteristic provided");
        Objects.requireNonNull(value, "no valid value provided");

        // Make sure we are still connected
        if (state != STATE_CONNECTED) {
            gattCallback.onCharacteristicWrite(characteristic, GATT_ERROR);
            return false;
        }

        // Copy the value to avoid race conditions
        final byte[] bytesToWrite = copyOf(value);

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.service.getUuid(),characteristic.getUuid());
        if (nativeCharacteristic == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
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
            logger.error(String.format(Locale.ENGLISH, "ERROR: Characteristic cannot be written with this writeType : %d", writeType));
            return false;
        }

        // All in order, do the write
        boolean result = commandQueue.add(() -> {
            if (state == STATE_CONNECTED) {
                try {
                    // Perform the write
                    currentWriteBytes = bytesToWrite;
                    logger.info(String.format("writing <%s> to characteristic <%s>", bytes2String(bytesToWrite), nativeCharacteristic.getUuid()));
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
                    logger.error("ERROR: " + e.getMessage());
                }
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.error("ERROR: Could not enqueue write characteristic command");
        }
        return result;
    }

    /**
     * Set the notification state of a characteristic to 'on' or 'off'. The characteristic must support notifications or indications.
     *
     * <p>{@link BluetoothPeripheralCallback#onNotificationStateUpdate(BluetoothPeripheral, BluetoothGattCharacteristic, int)} will be triggered as a result of this call.
     *
     * @param characteristic the characteristic to turn notification on/off for
     * @param enable         true for setting notification on, false for turning it off
     * @return true if the operation was enqueued, false if the characteristic doesn't support notification or indications or
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean setNotify(@NotNull final BluetoothGattCharacteristic characteristic, boolean enable) {
        Objects.requireNonNull(characteristic, "no valid characteristic provided");

        // Make sure we are still connected
        if (state != STATE_CONNECTED) {
            gattCallback.onNotifySet(characteristic, false);
            return false;
        }

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.service.getUuid(), characteristic.getUuid());
        if (nativeCharacteristic == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            gattCallback.onNotifySet(characteristic, false);
            return false;
        }

        // Check if characteristic has NOTIFY or INDICATE properties and set the correct byte value to be written
        if (!characteristic.supportsNotifying()) {
            logger.info(String.format("ERROR: Characteristic %s does not have notify of indicate property", characteristic.getUuid()));
            return false;
        }

        // All in order, do the set notify
        boolean result = commandQueue.add(() -> {
            if (state == STATE_CONNECTED) {
                try {
                    if (enable) {
                        logger.info(String.format("setNotify for characteristic <%s>", nativeCharacteristic.getUuid()));
                        boolean isNotifying = nativeCharacteristic.isNotifying();
                        if (isNotifying) {
                            // Already notifying, ignoring command
                            logger.info("already notifying");
                            gattCallback.onNotifySet(characteristic, true);
                        } else {
                            nativeCharacteristic.startNotify();
                        }
                    } else {
                        logger.info(String.format("stopNotify for characteristic <%s>", nativeCharacteristic.getUuid()));
                        nativeCharacteristic.stopNotify();
                    }
                } catch (BluezFailedException | BluezInProgressException | BluezNotPermittedException | BluezNotSupportedException e) {
                    logger.error("ERROR: Notify failed");
                } catch (Exception e) {
                    gattCallback.onNotifySet(characteristic, false);
                    logger.error("ERROR: " + e.getMessage());
                }
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.error("ERROR: Could not enqueue set notify characteristic command");
        }
        return result;
    }

    /**
     * Read the RSSI for a connected peripheral
     * onReadRemoteRssi(BluetoothPeripheral, int, int) will be triggered as a result of this call.
     */
    @SuppressWarnings("unused")
    public void readRemoteRssi() {
        try {
            logger.info(String.format("reading rssi for '%s'", deviceName));
            Short rssi = Objects.requireNonNull(device).getRssi();
            if (peripheralCallback != null && rssi != null) {
                callBackHandler.post(() -> peripheralCallback.onReadRemoteRssi(BluetoothPeripheral.this, rssi, GATT_SUCCESS));
            }
            completedCommand();
        } catch (DBusExecutionException e) {
            if (e.getMessage().equalsIgnoreCase("No such property 'RSSI'")) {
                logger.error("rssi not available when not scanning");
            } else {
                logger.error("reading rssi failed: " + e.getMessage());
            }
        }
    }

    /*
     * PRIVATE METHODS
     */
    private void servicesResolved() {
        // Make sure we are connected
        if (state != STATE_CONNECTED) {
            logger.error("Services resolved but not connected");
            return;
        }

        // Make sure we start with an empty slate
        clearMaps();

        // Process all service, characteristics and descriptors
        if (device != null) {
            // Build list of services including the linked characteristics and descriptors
            services.clear();
            List<BluezGattService> gattServices = device.getGattServices();
            gattServices.forEach(service -> services.add(mapBluezGattServiceToBluetoothGattService(service)));

            gattCallback.onServicesDiscovered(services);
        }
    }

    private void clearMaps() {
        serviceMap.clear();
        characteristicMap.clear();
        descriptorMap.clear();
    }

    void handleSignal(Properties.PropertiesChanged propertiesChanged) {
        propertiesChangedHandler.handle(propertiesChanged);
    }

    private final AbstractPropertiesChangedHandler propertiesChangedHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(Properties.PropertiesChanged propertiesChanged) {
            switch (propertiesChanged.getInterfaceName()) {
                case BLUEZ_CHARACTERISTIC_INTERFACE:
                    BluetoothGattCharacteristic bluetoothGattCharacteristic = getCharacteristicFromPath(propertiesChanged.getPath());
                    if (bluetoothGattCharacteristic == null) return;

                    propertiesChanged.getPropertiesChanged().forEach((key, value) -> handlePropertyChangedForCharacteristic(bluetoothGattCharacteristic, key, value));
                    break;
                case BLUEZ_DEVICE_INTERFACE:
                    propertiesChanged.getPropertiesChanged().forEach((key, value) -> handlePropertyChangeForDevice(key, value));
                    break;
                default:
                    break;
            }
        }
    };

    private void handlePropertyChangedForCharacteristic(BluetoothGattCharacteristic bluetoothGattCharacteristic, String propertyName, Variant<?> value) {
        switch (propertyName) {
            case PROPERTY_NOTIFYING:
                boolean isNotifying = (Boolean) value.getValue();
                gattCallback.onNotifySet(bluetoothGattCharacteristic, isNotifying);
                break;
            case PROPERTY_VALUE:
                if (value.getType() instanceof DBusListType) {
                    if (value.getValue() instanceof byte[]) {
                        byte[] byteVal = (byte[]) value.getValue();
                        gattCallback.onCharacteristicChanged(byteVal, bluetoothGattCharacteristic);
                    } else {
                        logger.error("got VALUE update that is not byte array");
                    }
                } else {
                    logger.error("got unknown type for VALUE update");
                }
                break;
            default:
                logger.error(String.format("Unhandled characteristic property change %s", propertyName));
        }
    }

    private void handlePropertyChangeForDevice(String propertyName, Variant<?> value) {
        switch (propertyName) {
            case PROPERTY_SERVICES_RESOLVED:
                if (value.getValue().equals(true)) {
                    cancelServiceDiscoveryTimer();
                    servicesResolved();
                } else {
                    logger.debug(String.format("servicesResolved is false (%s)", deviceName));
                }
                break;
            case PROPERTY_CONNECTED:
                if (value.getValue().equals(true)) {
                    long timePassed = System.currentTimeMillis() - connectTimestamp;
                    logger.info(String.format("connected to '%s' (%s) in %.1fs", deviceName, isPaired() ? "BONDED" : "BOND_NONE", timePassed / 1000.0f));
                    gattCallback.onConnectionStateChanged(STATE_CONNECTED, GATT_SUCCESS);
                    startServiceDiscoveryTimer();
                } else {
                    logger.info(String.format("disconnected '%s' (%s)", deviceName, deviceAddress));

                    // Clean up
                    cancelServiceDiscoveryTimer();
                    BluezSignalHandler.getInstance().removePeripheral(deviceAddress);
                    gattCallback.onConnectionStateChanged(STATE_DISCONNECTED, GATT_SUCCESS);
                    if (timeoutHandler != null) timeoutHandler.stop();
                    timeoutHandler = null;
                }
                break;
            case PROPERTY_PAIRED:
                if (value.getValue().equals(true)) {
                    isBonded = true;
                    gattCallback.onPaired();
                } else {
                    gattCallback.onPairingFailed();
                }
                break;
            default:
                // Ignore other properties
        }
    }

    /**
     * The current command has been completed, move to the next command in the queue (if any)
     */
    private void completedCommand() {
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
                logger.warn("max number of tries reached, not retrying operation anymore ");
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
            if (state != STATE_CONNECTED) {
                logger.info(String.format("device %s is not connected, clearing command queue", getAddress()));
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

                if (queueHandler != null) {
                    queueHandler.post(() -> {
                        try {
                            bluetoothCommand.run();
                        } catch (Exception ex) {
                            logger.warn(String.format("ERROR: Command exception for device '%s'", getName()));
                            ex.printStackTrace();
                            completedCommand();
                        }
                    });
                }
            }
        }
    }

    private @Nullable BluezGattCharacteristic getBluezGattCharacteristic(@NotNull UUID serviceUUID, @NotNull UUID characteristicUUID) {
        Objects.requireNonNull(serviceUUID, "no valid service UUID provided");
        Objects.requireNonNull(serviceUUID, "no valid characteristic UUID provided");

        BluezGattCharacteristic characteristic = null;
        for (BluezGattCharacteristic gattCharacteristic : characteristicMap.values()) {
            if (characteristicUUID.toString().equalsIgnoreCase(gattCharacteristic.getUuid())) {
                if (gattCharacteristic.getService().getUuid().equalsIgnoreCase(serviceUUID.toString())) {
                    characteristic = gattCharacteristic;
                }
            }
        }
        return characteristic;
    }

    private @Nullable BluetoothGattCharacteristic getBluetoothGattCharacteristic(@NotNull BluezGattCharacteristic bluezGattCharacteristic) {
        Objects.requireNonNull(bluezGattCharacteristic, "no valid characteristic provided");

        UUID characteristicUUID = UUID.fromString(bluezGattCharacteristic.getUuid());
        for (BluetoothGattService service : services) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics())
                if (characteristic.getUuid().equals(characteristicUUID)) {
                    return characteristic;
                }
        }
        return null;
    }


    /**
     * Get the services supported by the connected bluetooth peripheral.
     * Only services that are also supported by {@link BluetoothCentral} are included.
     *
     * @return Supported services.
     */
    public @NotNull List<BluetoothGattService> getServices() {
        return services;
    }

    /**
     * Get the BluetoothGattService object for a service UUID.
     *
     * @param serviceUUID the UUID of the service
     * @return the BluetoothGattService object for the service UUID or null if the peripheral does not have a service with the specified UUID
     */
    public @Nullable BluetoothGattService getService(@NotNull UUID serviceUUID) {
        Objects.requireNonNull(serviceUUID, "no valid service UUID provided");

        for (BluetoothGattService service : services) {
            if (service.getUuid().equals(serviceUUID)) {
                return service;
            }
        }
        return null;
    }

    /**
     * Method to get a characteristic by its UUID
     *
     * @param serviceUUID        The service UUID the characteristic should be part of
     * @param characteristicUUID the characteristic UUID
     * @return the BluetoothGattCharacteristic matching the serviceUUID and characteristicUUID
     */
    public @Nullable BluetoothGattCharacteristic getCharacteristic(@NotNull UUID serviceUUID, @NotNull UUID characteristicUUID) {
        Objects.requireNonNull(serviceUUID, "no valid service UUID provided");
        Objects.requireNonNull(characteristicUUID, "no valid characteristic provided");

        BluetoothGattService service = getService(serviceUUID);
        if (service != null) {
            return service.getCharacteristic(characteristicUUID);
        } else {
            return null;
        }
    }

    /**
     * Get the name of the bluetooth peripheral.
     *
     * @return name of the bluetooth peripheral
     */
    public @Nullable String getName() {
        return deviceName;
    }

    void setName(@Nullable String name) {
        deviceName = name;
    }

    /**
     * Get the mac address of the bluetooth peripheral.
     *
     * @return Address of the bluetooth peripheral
     */
    public @NotNull String getAddress() {
        return deviceAddress;
    }

    /**
     * Returns the connection state of the peripheral.
     *
     * <p>Possible values for the connection state are:
     * {@link #STATE_CONNECTED},
     * {@link #STATE_CONNECTING},
     * {@link #STATE_DISCONNECTED},
     * {@link #STATE_DISCONNECTING}.
     *
     * @return the connection state.
     */
    public int getState() {
        return state;
    }

    boolean isPaired() {
        boolean result = isBonded;
        try {
            isBonded = device.isPaired();
            result = isBonded;
        } catch (Exception e) {
            logger.error(e.toString());
        }
        return result;
    }

    /**
     * Get the bond state of the bluetooth peripheral.
     *
     * <p>Possible values for the bond state are:
     * {@link #BOND_NONE},
     * {@link #BOND_BONDING},
     * {@link #BOND_BONDED}.
     *
     * @return returns the bond state
     */
    public int getBondState() {
        if (isPaired()) {
            return BOND_BONDED;
        } else {
            return BOND_NONE;
        }
    }

    /**
     * Boolean to indicate if the specified characteristic is currently notifying or indicating.
     *
     * @param characteristic the characteristic to check
     * @return true if the characteristic is notifying or indicating, false if it is not
     */
    public boolean isNotifying(@NotNull BluetoothGattCharacteristic characteristic) {
        Objects.requireNonNull(characteristic, "no valid characteristic provided");

        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.service.getUuid(), characteristic.getUuid());
        if (nativeCharacteristic == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            return false;
        }
        return nativeCharacteristic.isNotifying();
    }

    /**
     * Create a bond with the peripheral.
     * <p>
     * The bonding command will be enqueued and you will
     * receive updates via the {@link BluetoothPeripheralCallback}.
     *
     * @return true if bonding was started/enqueued, false if not
     */
    public boolean createBond() {
        logger.info(String.format("Pairing with '%s' (%s)", deviceName, deviceAddress));
        manualBonding = true;
        connectTimestamp = System.currentTimeMillis();
        boolean result = false;
        try {
            if (device != null) {
                device.pair();
            }
            return true;
        } catch (BluezInvalidArgumentsException e) {
            logger.error("Pair exception: invalid argument");
        } catch (BluezFailedException e) {
            logger.error("Pair exception: failed");
        } catch (BluezAuthenticationFailedException e) {
            logger.error("Pair exception: authentication failed");
        } catch (BluezAlreadyExistsException e) {
            logger.error("Pair exception: already exists");
        } catch (BluezAuthenticationCanceledException e) {
            logger.error("Pair exception: authentication canceled");
        } catch (BluezAuthenticationRejectedException e) {
            logger.error("Pair exception: authentication rejected");
        } catch (BluezAuthenticationTimeoutException e) {
            logger.error("Pair exception: authentication timeout");
        } catch (BluezConnectionAttemptFailedException e) {
            logger.error("Pair exception: connection attempt failed");
        } catch (DBusExecutionException e) {
            if (e.getMessage().equalsIgnoreCase("No reply within specified time")) {
                logger.error("Pairing timeout");
            } else {
                logger.error(e.getMessage());
            }
        }

        // Clean up after failed pairing
        if (device.isConnected()) {
            device.disconnect();
        } else {
            cleanupAfterFailedConnect();
        }

        return result;
    }

    @Nullable
    private BluetoothGattCharacteristic getCharacteristicFromPath(@NotNull String path) {
        Objects.requireNonNull(path, "no valid path provided");

        BluezGattCharacteristic characteristic = characteristicMap.get(path);
        if (characteristic == null) return null;

        BluetoothGattCharacteristic bluetoothGattCharacteristic = getBluetoothGattCharacteristic(characteristic);
        if (bluetoothGattCharacteristic == null) {
            logger.error(String.format("can't find characteristic with path %s", path));
        }
        return bluetoothGattCharacteristic;
    }

    private void startServiceDiscoveryTimer() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }

        timeoutRunnable = () -> {
            logger.error(String.format("Service Discovery timeout, disconnecting '%s'", device.getName()));

            // Disconnecting doesn't work so do it ourselves
            cancelServiceDiscoveryTimer();
            cleanupAfterFailedConnect();
        };
        if (timeoutHandler != null) {
            timeoutHandler.postDelayed(timeoutRunnable, SERVICE_DISCOVERY_TIMEOUT_IN_MS);
        }
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

    private BluetoothGattCharacteristic mapBluezGattCharacteristicToBluetoothGattCharacteristic(BluezGattCharacteristic bluezGattCharacteristic) {
        int properties = mapFlagsToProperty(bluezGattCharacteristic.getFlags());
        BluetoothGattCharacteristic bluetoothGattCharacteristic = new BluetoothGattCharacteristic(UUID.fromString(bluezGattCharacteristic.getUuid()), properties, 0);
        // TODO sort our permissions

        // Get all descriptors for this characteristic
        List<BluezGattDescriptor> descriptors = bluezGattCharacteristic.getGattDescriptors();

        // Process all descriptors
        if (descriptors != null) {
            descriptors.forEach(descriptor -> {
                descriptorMap.put(descriptor.getDbusPath(), descriptor);
                bluetoothGattCharacteristic.addDescriptor(mapBluezGattDescriptorToHBDescriptor(descriptor));
            });
        }

        return bluetoothGattCharacteristic;
    }

    private BluetoothGattService mapBluezGattServiceToBluetoothGattService(@NotNull BluezGattService service) {
        // Build up internal services map
        serviceMap.put(service.getDbusPath(), service);

        // Create BluetoothGattService object
        BluetoothGattService bluetoothGattService = new BluetoothGattService(UUID.fromString(service.getUuid()));
        bluetoothGattService.setPeripheral(this);

        // Get all characteristics for this service
        List<BluezGattCharacteristic> characteristics = service.getGattCharacteristics();

        // Process all characteristics
        if (characteristics != null) {
            characteristics.forEach(bluetoothGattCharacteristic -> {
                // Build up internal characteristic map
                characteristicMap.put(bluetoothGattCharacteristic.getDbusPath(), bluetoothGattCharacteristic);

                // Create BluetoothGattCharacteristic
                BluetoothGattCharacteristic characteristic = mapBluezGattCharacteristicToBluetoothGattCharacteristic(bluetoothGattCharacteristic);
                characteristic.setService(bluetoothGattService);
                bluetoothGattService.addCharacteristic(characteristic);
            });
        }

        return bluetoothGattService;
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
            case GATT_INSUFFICIENT_ENCRYPTION:
                return "GATT INSUFFICIENT ENCRYPTION";
            default:
                return "UNKNOWN (" + error + ")";
        }
    }

    /**
     * Safe copying of a byte array.
     *
     * @param source the byte array to copy, can be null
     * @return non-null byte array that is a copy of source
     */
    @NotNull
    private byte[] copyOf(@Nullable byte[] source) {
        if (source == null) return new byte[0];
        final int sourceLength = source.length;
        final byte[] copy = new byte[sourceLength];
        System.arraycopy(source, 0, copy, 0, sourceLength);
        return copy;
    }

    public @Nullable BluezDevice getDevice() {
        return device;
    }

    public void setDevice(@NotNull BluezDevice device) {
        this.device = Objects.requireNonNull(device, "no valid device supplied");
    }
}


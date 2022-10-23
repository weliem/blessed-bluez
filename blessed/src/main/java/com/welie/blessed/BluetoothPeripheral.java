package com.welie.blessed;

import com.welie.blessed.bluez.BluezDevice;
import com.welie.blessed.bluez.BluezGattCharacteristic;
import com.welie.blessed.bluez.BluezGattDescriptor;
import com.welie.blessed.bluez.BluezGattService;
import com.welie.blessed.internal.GattCallback;
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
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.welie.blessed.BluetoothBytesParser.bytes2String;
import static com.welie.blessed.BluetoothCommandStatus.*;
import static com.welie.blessed.BluetoothGattCharacteristic.*;
import static com.welie.blessed.ConnectionState.*;

/**
 * Represents a Bluetooth BLE peripheral
 */
public final class BluetoothPeripheral {
    private static final String TAG = BluetoothPeripheral.class.getSimpleName();
    private final Logger logger = LoggerFactory.getLogger(TAG);

    private static final String ERROR_NATIVE_CHARACTERISTIC_IS_NULL = "ERROR: Native characteristic is null";
    private static final String ERROR_NATIVE_DESCRIPTOR_IS_NULL = "ERROR: Native descriptor is null";
    private static final String NO_VALID_SERVICE_UUID_PROVIDED = "no valid service UUID provided";
    private static final String NO_VALID_CHARACTERISTIC_UUID_PROVIDED = "no valid characteristic UUID provided";
    private static final String NO_VALID_CHARACTERISTIC_PROVIDED = "no valid characteristic provided";
    private static final String NO_VALID_DESCRIPTOR_PROVIDED = "no valid descriptor provided";
    private static final String NO_VALID_WRITE_TYPE_PROVIDED = "no valid writeType provided";
    private static final String NO_VALID_VALUE_PROVIDED = "no valid value provided";
    private static final String ENQUEUE_ERROR = "ERROR: Could not enqueue command";

    @NotNull
    private final BluetoothCentralManager central;

    @Nullable
    private BluezDevice device;

    @NotNull
    private String deviceName;

    @NotNull
    private final String deviceAddress;

    @Nullable
    private byte[] currentWriteBytes;

    @NotNull
    private final Handler callBackHandler;

    @NotNull
    private final InternalCallback listener;

    @NotNull
    private BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback.NULL();

    @NotNull
    protected final Map<String, BluezGattService> serviceMap = new ConcurrentHashMap<>();

    @NotNull
    protected final Map<String, BluezGattCharacteristic> characteristicMap = new ConcurrentHashMap<>();

    @NotNull
    protected final Map<String, BluezGattDescriptor> descriptorMap = new ConcurrentHashMap<>();

    @NotNull
    protected List<@NotNull BluetoothGattService> services = new ArrayList<>();

    @Nullable
    private ScheduledFuture<?> timeoutFuture;

    @NotNull
    private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();

    @Nullable
    private Handler queueHandler;

    @Nullable
    private Handler signalHandler;

    @NotNull
    private final Set<BluetoothGattCharacteristic> notifyingCharacteristics = new HashSet<>();

    private volatile boolean commandQueueBusy = false;
    private int nrTries;
    private boolean isBonded = false;
    private boolean manualBonding = false;
    private volatile boolean bondingInProgress = false;
    private long connectTimestamp;
    private boolean isRetrying;
    private volatile ConnectionState state = DISCONNECTED;
    private volatile boolean serviceDiscoveryCompleted = false;

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
    static final String PROPERTY_ADDRESS_TYPE = "AddressType";
    static final String PROPERTY_RSSI = "RSSI";
    static final String PROPERTY_MANUFACTURER_DATA = "ManufacturerData";
    static final String PROPERTY_SERVICE_DATA = "ServiceData";

    // GattCallback will deal with managing low-level callbacks
    final GattCallback gattCallback = new GattCallback() {
        @Override
        public void onConnectionStateChanged(@NotNull final ConnectionState connectionState, @NotNull final BluetoothCommandStatus status) {
            final ConnectionState previousState = state;
            state = connectionState;

            if (status == COMMAND_SUCCESS) {
                switch (connectionState) {
                    case CONNECTED:
                        successfullyConnected();
                        break;
                    case DISCONNECTED:
                        successfullyDisconnected(status, previousState);
                        break;
                    case CONNECTING:
                        logger.info("peripheral is connecting");
                        break;
                    case DISCONNECTING:
                        logger.info("peripheral is disconnecting");
                        break;
                    default:
                        logger.error("unhandled connection state");
                        break;
                }
            } else {
                connectionStateChangeUnsuccessful(status, previousState, connectionState);
            }
        }

        private void successfullyConnected() {
            final long timePassed = System.currentTimeMillis() - connectTimestamp;
            isBonded = isPaired();
            logger.info(String.format("connected to '%s' (%s) in %.1fs", deviceName, isBonded ? "BONDED" : "BOND_NONE", timePassed / 1000.0f));
        }

        private void successfullyDisconnected(@NotNull final BluetoothCommandStatus status, @NotNull final ConnectionState previousState) {
            if (!serviceDiscoveryCompleted) {
//                            if (isBonded) {
//                                // Assume we lost the bond
//                                if (peripheralCallback != null) {
//                                    callBackHandler.post(() -> peripheralCallback.onBondLost(BluetoothPeripheral.this));
//                                }
//                            }
                listener.serviceDiscoveryFailed(BluetoothPeripheral.this);
            }
            completeDisconnect(true, status);
        }

        void connectionStateChangeUnsuccessful(@NotNull final BluetoothCommandStatus status, @NotNull final ConnectionState previousState, @NotNull final ConnectionState newState) {
            if (previousState == CONNECTING) {
                completeDisconnect(false, status);
                logger.error(String.format("connection failed with status '%s'", status));
                listener.connectFailed(BluetoothPeripheral.this, status);
            } else if (previousState == CONNECTED && newState == DISCONNECTED && !serviceDiscoveryCompleted) {
                completeDisconnect(false, status);
                logger.error(String.format("connection failed with status '%s' during service discovery", status));
                listener.connectFailed(BluetoothPeripheral.this, status);
            } else {
                if (newState == DISCONNECTED) {
                    logger.error(String.format("disconnected with status '%s'", status));
                }
                completeDisconnect(true, status);
            }
        }

        @Override
        public void onNotificationStateUpdate(@NotNull final BluetoothGattCharacteristic characteristic, @NotNull final BluetoothCommandStatus status) {
            if (status != COMMAND_SUCCESS) {
                logger.error(String.format("set notify failed with status '%s'", status));
            }

            callBackHandler.post(() -> peripheralCallback.onNotificationStateUpdate(BluetoothPeripheral.this, characteristic, status));
            completedCommand();
        }


        @Override
        public void onDescriptorRead(@NotNull BluetoothGattDescriptor descriptor, @NotNull byte[] value, @NotNull BluetoothCommandStatus status) {
            // Do some checks first
            final BluetoothGattCharacteristic parentCharacteristic = descriptor.getCharacteristic();
            if (status != COMMAND_SUCCESS) {
                logger.info(String.format("ERROR: Read descriptor failed device: %s, characteristic: %s", getAddress(), parentCharacteristic.getUuid()));
            }

            callBackHandler.post(() -> peripheralCallback.onDescriptorRead(BluetoothPeripheral.this, value, descriptor, status));
            completedCommand();
        }

        @Override
        public void onDescriptorWrite(@NotNull final BluetoothGattDescriptor descriptor, @NotNull final BluetoothCommandStatus status) {
            // Do some checks first
            final BluetoothGattCharacteristic parentCharacteristic = descriptor.getCharacteristic();
            if (status != COMMAND_SUCCESS) {
                logger.info(String.format("ERROR: Write descriptor failed device: %s, characteristic: %s", getAddress(), parentCharacteristic.getUuid()));
            }

            callBackHandler.post(() -> peripheralCallback.onDescriptorWrite(BluetoothPeripheral.this, new byte[0], descriptor, status));
            completedCommand();
        }

        @Override
        public void onCharacteristicRead(@NotNull final BluetoothGattCharacteristic characteristic, @NotNull final BluetoothCommandStatus status) {
            if (status != COMMAND_SUCCESS) {
                logger.error(String.format(Locale.ENGLISH, "read failed for characteristic: %s, status '%s'", characteristic.getUuid(), status));
                // Propagate error so it can be handled
                callBackHandler.post(() -> peripheralCallback.onCharacteristicUpdate(BluetoothPeripheral.this, new byte[0], characteristic, status));
            }

            // Just complete the command. The actual value will come in through onCharacteristicChanged
            completedCommand();
        }

        @Override
        public void onCharacteristicChanged(@NotNull final byte[] value, @NotNull final BluetoothGattCharacteristic characteristic) {
            callBackHandler.post(() -> peripheralCallback.onCharacteristicUpdate(BluetoothPeripheral.this, value, characteristic, COMMAND_SUCCESS));
        }

        @Override
        public void onCharacteristicWrite(@NotNull final BluetoothGattCharacteristic characteristic, @NotNull final BluetoothCommandStatus status) {
            if (status != COMMAND_SUCCESS) {
                logger.error(String.format("write failed for characteristic: %s, status '%s'", characteristic.getUuid(), status));
            }

            callBackHandler.post(() -> peripheralCallback.onCharacteristicWrite(BluetoothPeripheral.this, currentWriteBytes, characteristic, status));
            completedCommand();
        }

        @Override
        public void onPairingStarted() {
            logger.info("bonding started");
            bondingInProgress = true;
            callBackHandler.post(() -> peripheralCallback.onBondingStarted(BluetoothPeripheral.this));
        }

        @Override
        public void onPaired() {
            logger.info("bonding succeeded");
            callBackHandler.post(() -> peripheralCallback.onBondingSucceeded(BluetoothPeripheral.this));
        }

        @Override
        public void onPairingFailed() {
            logger.info("bonding failed");
            callBackHandler.post(() -> peripheralCallback.onBondingFailed(BluetoothPeripheral.this));
        }

        @Override
        public void onServicesDiscovered(@NotNull final List<BluetoothGattService> services) {
            serviceDiscoveryCompleted = true;
            logger.info(String.format("discovered %d services for '%s' (%s)", services.size(), getName(), getAddress()));

            // We are now fully connected and service discovery was successful, so let Central know
            listener.connected(BluetoothPeripheral.this);

            callBackHandler.post(() -> peripheralCallback.onServicesDiscovered(BluetoothPeripheral.this, services));

            // Let Central know as well so it can start scanning again if needed
            listener.servicesDiscovered(BluetoothPeripheral.this);
        }

        private void completeDisconnect(final boolean notify, @NotNull final BluetoothCommandStatus status) {
            // Empty the queue
            commandQueue.clear();
            commandQueueBusy = false;

            // Cleanup handlers
            queueHandler.shutdown();
            queueHandler = null;
            signalHandler.shutdown();
            signalHandler = null;

            if (notify) {
                listener.disconnected(BluetoothPeripheral.this, status);
            }

            BluezSignalHandler.getInstance().removePeripheral(deviceAddress);
        }
    };

    public BluetoothPeripheral(@NotNull final BluetoothCentralManager central, @Nullable final BluezDevice bluezDevice, @Nullable final String deviceName, @NotNull final String deviceAddress, @NotNull final InternalCallback listener, @Nullable final BluetoothPeripheralCallback peripheralCallback, @NotNull final Handler callBackHandler) {
        this.central = Objects.requireNonNull(central, "no valid central provided");
        this.device = bluezDevice;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.deviceAddress = Objects.requireNonNull(deviceAddress, "no valid address provided");
        this.listener = Objects.requireNonNull(listener, "no valid listener provided");
        if (peripheralCallback != null) {
            this.peripheralCallback = peripheralCallback;
        }
        this.callBackHandler = Objects.requireNonNull(callBackHandler, "no callbackhandler provided");
    }

    void setPeripheralCallback(@NotNull final BluetoothPeripheralCallback peripheralCallback) {
        this.peripheralCallback = Objects.requireNonNull(peripheralCallback, "no valid peripheral callback provided");
    }

    public void connect() {
        Objects.requireNonNull(device, "device is null");

        // Do the connect
        gattCallback.onConnectionStateChanged(CONNECTING, COMMAND_SUCCESS);

        try {
            logger.info(String.format("connecting to '%s' (%s)", deviceName, deviceAddress));
            queueHandler = new Handler(deviceAddress + "-queue");
            signalHandler = new Handler(deviceAddress + "-signal");
            BluezSignalHandler.getInstance().addPeripheral(deviceAddress, this);
            connectTimestamp = System.currentTimeMillis();
            device.connect();
        } catch (DBusExecutionException e) {
            logger.error(e.getMessage());

            // Unregister handler only if we are not connected. A connected event may have already been received!
            if (state != CONNECTED) {
                gattCallback.onConnectionStateChanged(DISCONNECTED, DBUS_EXECUTION_EXCEPTION);
            }
        } catch (BluezAlreadyConnectedException e) {
            logger.error("connect exception: already connected");
            gattCallback.onConnectionStateChanged(CONNECTED, CONNECTION_ALREADY_EXISTS);
            serviceDiscoveryCompleted = true;
            listener.connected(BluetoothPeripheral.this);
        } catch (BluezNotReadyException e) {
            logger.error("connect exception: not ready");
            logger.error(e.getMessage());
            gattCallback.onConnectionStateChanged(DISCONNECTED, BLUEZ_NOT_READY);
        } catch (BluezFailedException e) {
            logger.error("connect exception: connect failed");
            logger.error(e.getMessage());
            gattCallback.onConnectionStateChanged(DISCONNECTED, CONNECTION_FAILED_ESTABLISHMENT);
        } catch (BluezInProgressException e) {
            logger.error("connect exception: in progress");
            logger.error(e.getMessage());
            gattCallback.onConnectionStateChanged(DISCONNECTED, BLUEZ_OPERATION_IN_PROGRESS);
        }
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
        gattCallback.onConnectionStateChanged(DISCONNECTING, COMMAND_SUCCESS);
        if (device != null) {
            device.disconnect();
        }
    }

    /**
     * Read the value of a characteristic.
     *
     * <p>The characteristic must support reading it, otherwise the operation will not be enqueued.
     *
     * @param serviceUUID        the service UUID the characteristic belongs to
     * @param characteristicUUID the characteristic's UUID
     * @return true if the operation was enqueued, false if the characteristic does not support reading or the characteristic was not found
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean readCharacteristic(@NotNull final UUID serviceUUID, @NotNull final UUID characteristicUUID) {
        Objects.requireNonNull(serviceUUID, NO_VALID_SERVICE_UUID_PROVIDED);
        Objects.requireNonNull(characteristicUUID, NO_VALID_CHARACTERISTIC_PROVIDED);

        final BluetoothGattCharacteristic characteristic = getCharacteristic(serviceUUID, characteristicUUID);
        if (characteristic != null) {
            return readCharacteristic(characteristic);
        }
        return false;
    }

    /**
     * Read the value of a characteristic.
     *
     * <p>The characteristic must support reading it, otherwise the operation will not be enqueued.
     *
     * <p>{@link BluetoothPeripheralCallback#onCharacteristicUpdate(BluetoothPeripheral, byte[], BluetoothGattCharacteristic, BluetoothCommandStatus)}   will be triggered as a result of this call.
     *
     * @param characteristic Specifies the characteristic to read.
     * @return true if the operation was enqueued, false if the characteristic does not support reading or the characteristic was invalid
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean readCharacteristic(@NotNull final BluetoothGattCharacteristic characteristic) {
        Objects.requireNonNull(characteristic, "characteristic is 'null', ignoring read request");

        // Make sure we are still connected
        if (state != CONNECTED) {
            gattCallback.onCharacteristicRead(characteristic, NOT_CONNECTED);
            return false;
        }

        // Check if this characteristic actually has READ property
        if (!characteristic.supportsReading()) {
            return false;
        }

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.service.getUuid(), characteristic.getUuid());
        if (nativeCharacteristic == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            return false;
        }

        // All in order, do the read
        return enqueue(() -> {
            if (state == CONNECTED) {
                try {
                    logger.info(String.format("reading characteristic <%s>", nativeCharacteristic.getUuid()));
                    nativeCharacteristic.readValue(new HashMap<>());
                    gattCallback.onCharacteristicRead(characteristic, COMMAND_SUCCESS);
                } catch (BluezInProgressException e) {
                    gattCallback.onCharacteristicRead(characteristic, BLUEZ_OPERATION_IN_PROGRESS);
                } catch (BluezInvalidOffsetException e) {
                    gattCallback.onCharacteristicRead(characteristic, INVALID_OFFSET);
                } catch (BluezFailedException e) {
                    gattCallback.onCharacteristicRead(characteristic, BLUEZ_OPERATION_FAILED);
                } catch (BluezNotPermittedException e) {
                    gattCallback.onCharacteristicRead(characteristic, READ_NOT_PERMITTED);
                } catch (BluezNotAuthorizedException e) {
                    gattCallback.onCharacteristicRead(characteristic, INSUFFICIENT_AUTHENTICATION);
                } catch (BluezNotSupportedException e) {
                    gattCallback.onCharacteristicRead(characteristic, REQUEST_NOT_SUPPORTED);
                } catch (DBusExecutionException e) {
                    gattCallback.onCharacteristicRead(characteristic, DBUS_EXECUTION_EXCEPTION);
                    logger.error(e.toString());
                } catch (Exception e) {
                    gattCallback.onCharacteristicRead(characteristic, BLUEZ_OPERATION_FAILED);
                    logger.error(e.toString());
                }
            }
        });
    }

    /**
     * Read the value of a characteristic.
     *
     * <p>The characteristic must support reading it, otherwise the operation will not be enqueued.
     *
     * @param serviceUUID        the service UUID the characteristic belongs to
     * @param characteristicUUID the characteristic's UUID
     * @param value              the byte array to write
     * @param writeType          the write type to use when writing. Must be WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE or WRITE_TYPE_SIGNED
     * @return true if a write operation was successfully enqueued, otherwise false
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean writeCharacteristic(@NotNull final UUID serviceUUID, @NotNull final UUID characteristicUUID, @NotNull final byte[] value, @NotNull final WriteType writeType) {
        Objects.requireNonNull(serviceUUID, NO_VALID_SERVICE_UUID_PROVIDED);
        Objects.requireNonNull(characteristicUUID, NO_VALID_CHARACTERISTIC_UUID_PROVIDED);
        Objects.requireNonNull(value, NO_VALID_VALUE_PROVIDED);
        Objects.requireNonNull(writeType, NO_VALID_WRITE_TYPE_PROVIDED);

        final BluetoothGattCharacteristic characteristic = getCharacteristic(serviceUUID, characteristicUUID);
        if (characteristic != null) {
            return writeCharacteristic(characteristic, value, writeType);
        }
        return false;
    }

    /**
     * Write a value to a characteristic using the specified write type.
     *
     * <p>All parameters must have a valid value in order for the operation
     * to be enqueued. If the characteristic does not support writing with the specified writeType, the operation will not be enqueued.
     *
     * <p>{@link BluetoothPeripheralCallback#onCharacteristicWrite(BluetoothPeripheral, byte[], BluetoothGattCharacteristic, BluetoothCommandStatus)} will be triggered as a result of this call.
     *
     * @param characteristic the characteristic to write to
     * @param value          the byte array to write
     * @param writeType      the write type to use when writing. Must be WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE or WRITE_TYPE_SIGNED
     * @return true if a write operation was successfully enqueued, otherwise false
     */
    @SuppressWarnings({"UnusedReturnValue", "unused"})
    public boolean writeCharacteristic(@NotNull final BluetoothGattCharacteristic characteristic, @NotNull final byte[] value, @NotNull final WriteType writeType) {
        Objects.requireNonNull(characteristic, NO_VALID_CHARACTERISTIC_PROVIDED);
        Objects.requireNonNull(value, NO_VALID_VALUE_PROVIDED);
        Objects.requireNonNull(writeType, NO_VALID_WRITE_TYPE_PROVIDED);

        // Make sure we are still connected
        if (state != CONNECTED) {
            return false;
        }

        // Copy the value to avoid race conditions
        final byte[] bytesToWrite = copyOf(value);
        if (bytesToWrite.length == 0) {
            logger.error("value byte array is empty, ignoring write request");
            return false;
        }

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.service.getUuid(), characteristic.getUuid());
        if (nativeCharacteristic == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            return false;
        }

        // Check if this characteristic actually supports this writeType
        if (!characteristic.supportsWriteType(writeType)) {
            logger.error(String.format(Locale.ENGLISH, "characteristic cannot be written with this writeType : %s", writeType));
            return false;
        }

        // All in order, do the write
        return enqueue(() -> {
            if (state == CONNECTED) {
                try {
                    currentWriteBytes = bytesToWrite;
                    logger.info(String.format("writing %s <%s> to characteristic <%s>", writeType, bytes2String(bytesToWrite), nativeCharacteristic.getUuid()));
                    HashMap<String, Object> options = new HashMap<>();
                    options.put("type", writeType == WriteType.WITH_RESPONSE ? "request" : "command");
                    nativeCharacteristic.writeValue(bytesToWrite, options);

                    // Since there is no callback nor characteristic update event for when a write is completed, we can consider this command done
                    gattCallback.onCharacteristicWrite(characteristic, COMMAND_SUCCESS);
                } catch (BluezInProgressException e) {
                    gattCallback.onCharacteristicWrite(characteristic, BLUEZ_OPERATION_IN_PROGRESS);
                } catch (BluezNotPermittedException e) {
                    gattCallback.onCharacteristicWrite(characteristic, WRITE_NOT_PERMITTED);
                } catch (BluezNotAuthorizedException e) {
                    gattCallback.onCharacteristicWrite(characteristic, INSUFFICIENT_AUTHORIZATION);
                } catch (DBusExecutionException e) {
                    gattCallback.onCharacteristicWrite(characteristic, DBUS_EXECUTION_EXCEPTION);
                } catch (BluezNotSupportedException e) {
                    gattCallback.onCharacteristicWrite(characteristic, REQUEST_NOT_SUPPORTED);
                } catch (BluezFailedException e) {
                    gattCallback.onCharacteristicWrite(characteristic, BLUEZ_OPERATION_FAILED);
                } catch (BluezInvalidValueLengthException e) {
                    gattCallback.onCharacteristicWrite(characteristic, INVALID_ATTRIBUTE_VALUE_LENGTH);
                } catch (Exception e) {
                    gattCallback.onCharacteristicWrite(characteristic, BLUEZ_OPERATION_FAILED);
                    logger.error(e.getMessage());
                }
            }
        });
    }

    public boolean readDescriptor(@NotNull final BluetoothGattDescriptor descriptor) {
        Objects.requireNonNull(descriptor, NO_VALID_DESCRIPTOR_PROVIDED);
        final BluetoothGattCharacteristic characteristic = descriptor.characteristic;
        Objects.requireNonNull(characteristic, "descriptor does not have a valid characteristic");
        Objects.requireNonNull(characteristic.getService(), "characteristic does not have valid service");

        // Make sure we are still connected
        if (state != CONNECTED) {
            return false;
        }

        final BluezGattDescriptor nativeDescriptor = getBluezGattDescriptor(characteristic.service.getUuid(), characteristic.getUuid(), descriptor.getUuid());
        if (nativeDescriptor == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            return false;
        }

        return enqueue(() -> {
            if (state == CONNECTED) {
                try {
                    HashMap<String, Object> options = new HashMap<>();
                    final byte[] result = nativeDescriptor.readValue(options);
                    gattCallback.onDescriptorRead(descriptor, result, COMMAND_SUCCESS);
                } catch (Exception e) {
                    logger.error(e.getMessage());
                    gattCallback.onDescriptorRead(descriptor, new byte[0], BLUEZ_OPERATION_FAILED);
                }
            }
        });
    }

    public boolean writeDescriptor(@NotNull final BluetoothGattDescriptor descriptor, @NotNull final byte[] value) {
        Objects.requireNonNull(descriptor, NO_VALID_DESCRIPTOR_PROVIDED);
        final BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        Objects.requireNonNull(value, NO_VALID_VALUE_PROVIDED);

        // Make sure we are still connected
        if (state != CONNECTED) {
            return false;
        }

        final BluezGattDescriptor nativeDescriptor = getBluezGattDescriptor(characteristic.service.getUuid(), characteristic.getUuid(), descriptor.getUuid());
        if (nativeDescriptor == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            return false;
        }

        return enqueue(() -> {
            if (state == CONNECTED) {
                try {
                    HashMap<String, Object> options = new HashMap<>();
                    nativeDescriptor.writeValue(value, options);
                    gattCallback.onDescriptorWrite(descriptor, COMMAND_SUCCESS);
                } catch (Exception e) {
                    logger.error(e.getMessage());
                    gattCallback.onDescriptorWrite(descriptor, BLUEZ_OPERATION_FAILED);
                }
            }
        });
    }

    /**
     * Set the notification state of a characteristic to 'on' or 'off'. The characteristic must support notifications or indications.
     *
     * @param serviceUUID        the service UUID the characteristic belongs to
     * @param characteristicUUID the characteristic's UUID
     * @param enable             true for setting notification on, false for turning it off
     * @return true if the operation was enqueued, false the characteristic could not be found or does not support notifications
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean setNotify(@NotNull final UUID serviceUUID, @NotNull final UUID characteristicUUID, final boolean enable) {
        Objects.requireNonNull(serviceUUID, NO_VALID_SERVICE_UUID_PROVIDED);
        Objects.requireNonNull(characteristicUUID, NO_VALID_CHARACTERISTIC_PROVIDED);

        final BluetoothGattCharacteristic characteristic = getCharacteristic(serviceUUID, characteristicUUID);
        if (characteristic != null) {
            return setNotify(characteristic, enable);
        }
        return false;
    }

    /**
     * Set the notification state of a characteristic to 'on' or 'off'. The characteristic must support notifications or indications.
     *
     * <p>{@link BluetoothPeripheralCallback#onNotificationStateUpdate(BluetoothPeripheral, BluetoothGattCharacteristic, BluetoothCommandStatus)} will be triggered as a result of this call.
     *
     * @param characteristic the characteristic to turn notification on/off for
     * @param enable         true for setting notification on, false for turning it off
     * @return true if the operation was enqueued, false if the characteristic doesn't support notification or indications or
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean setNotify(@NotNull final BluetoothGattCharacteristic characteristic, final boolean enable) {
        Objects.requireNonNull(characteristic, NO_VALID_CHARACTERISTIC_PROVIDED);

        // Make sure we are still connected
        if (state != CONNECTED) {
            gattCallback.onNotificationStateUpdate(characteristic, NOT_CONNECTED);
            return false;
        }

        // Check if we have the native characteristic
        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.service.getUuid(), characteristic.getUuid());
        if (nativeCharacteristic == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            return false;
        }

        // Check if characteristic has NOTIFY or INDICATE properties and set the correct byte value to be written
        if (!characteristic.supportsNotifying()) {
            logger.info(String.format("characteristic %s does not have notify of indicate property", characteristic.getUuid()));
            return false;
        }

        // All in order, do the set notify
        return enqueue(() -> {
            if (state == CONNECTED) {
                try {
                    if (enable) {
                        logger.info(String.format("setNotify for characteristic <%s>", nativeCharacteristic.getUuid()));
                        boolean isNotifying = nativeCharacteristic.isNotifying();
                        if (isNotifying) {
                            // Already notifying, ignoring command
                            logger.info("already notifying");
                            notifyingCharacteristics.add(characteristic);
                            gattCallback.onNotificationStateUpdate(characteristic, COMMAND_SUCCESS);
                        } else {
                            nativeCharacteristic.startNotify();
                        }
                    } else {
                        logger.info(String.format("stopNotify for characteristic <%s>", nativeCharacteristic.getUuid()));
                        nativeCharacteristic.stopNotify();
                    }
                } catch (BluezNotPermittedException e) {
                    gattCallback.onNotificationStateUpdate(characteristic, WRITE_NOT_PERMITTED);
                } catch (BluezFailedException e) {
                    gattCallback.onNotificationStateUpdate(characteristic, BLUEZ_OPERATION_FAILED);
                } catch (BluezInProgressException e) {
                    gattCallback.onNotificationStateUpdate(characteristic, BLUEZ_OPERATION_IN_PROGRESS);
                } catch (BluezNotSupportedException e) {
                    gattCallback.onNotificationStateUpdate(characteristic, REQUEST_NOT_SUPPORTED);
                } catch (Exception e) {
                    gattCallback.onNotificationStateUpdate(characteristic, BLUEZ_OPERATION_FAILED);
                    logger.error(e.getMessage());
                }
            }
        });
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
            if (rssi != null) {
                callBackHandler.post(() -> peripheralCallback.onReadRemoteRssi(BluetoothPeripheral.this, rssi, COMMAND_SUCCESS));
            }
            completedCommand();
        } catch (DBusExecutionException e) {
            if (e.getMessage().equalsIgnoreCase("No such property 'RSSI'")) {
                logger.error("rssi not available when not scanning");
            } else {
                logger.error(String.format("reading rssi failed: %s", e.getMessage()));
            }
        }
    }

    /*
     * PRIVATE METHODS
     */
    private void servicesResolved() {
        // Make sure we are connected
        if (state != CONNECTED) {
            logger.error("Services resolved but not connected");
            return;
        }

        // Process all service, characteristics and descriptors to build a shadow gatt tree
        if (device != null) {
            clearMaps();
            final List<BluezGattService> gattServices = device.getGattServices();
            gattServices.forEach(service -> services.add(mapBluezGattServiceToBluetoothGattService(service)));
            gattCallback.onServicesDiscovered(Collections.unmodifiableList(services));
        }
    }

    private void clearMaps() {
        services.clear();
        serviceMap.clear();
        characteristicMap.clear();
        descriptorMap.clear();
    }

    void handleSignal(@NotNull final Properties.PropertiesChanged propertiesChanged) {
        if (signalHandler != null) {
            signalHandler.post(() -> propertiesChangedHandler.handle(propertiesChanged));
        }
    }

    private final AbstractPropertiesChangedHandler propertiesChangedHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(@NotNull final Properties.PropertiesChanged propertiesChanged) {
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

        private void handlePropertyChangedForCharacteristic(@NotNull final BluetoothGattCharacteristic bluetoothGattCharacteristic, @NotNull final String propertyName, @NotNull final Variant<?> value) {
            switch (propertyName) {
                case PROPERTY_NOTIFYING:
                    final boolean isNotifying = (Boolean) value.getValue();
                    if (isNotifying) {
                        notifyingCharacteristics.add(bluetoothGattCharacteristic);
                    } else {
                        notifyingCharacteristics.remove(bluetoothGattCharacteristic);
                    }
                    logger.info(String.format("characteristic '%s' %s", bluetoothGattCharacteristic.getUuid(), isNotifying ? "is notifying" : "stopped notifying"));
                    gattCallback.onNotificationStateUpdate(bluetoothGattCharacteristic, COMMAND_SUCCESS);
                    break;
                case PROPERTY_VALUE:
                    if (value.getType() instanceof DBusListType) {
                        if (value.getValue() instanceof byte[]) {
                            final byte[] byteArray = (byte[]) value.getValue();
                            if (byteArray != null) {
                                gattCallback.onCharacteristicChanged(byteArray, bluetoothGattCharacteristic);
                            }
                        }
                    }
                    break;
                default:
                    logger.error(String.format("Unhandled characteristic property change %s", propertyName));
            }
        }

        private void handlePropertyChangeForDevice(@NotNull final String propertyName, @NotNull final Variant<?> value) {
            switch (propertyName) {
                case PROPERTY_SERVICES_RESOLVED:
                    cancelServiceDiscoveryTimer();
                    if (value.getValue().equals(true)) {
                        logger.info("service discovery completed");
                        // If we are bonding, we postpone calling servicesResolved
                        if (!(manualBonding || bondingInProgress)) {
                            servicesResolved();
                        }
                    } else {
                        // Services_Resolved false will be followed by Connected == false, so no action needed
                        logger.debug(String.format("servicesResolved is false (%s)", deviceName));
                    }
                    break;
                case PROPERTY_CONNECTED:
                    if (value.getValue().equals(true)) {
                        // Getting connected can only be GATT_SUCCESS
                        gattCallback.onConnectionStateChanged(CONNECTED, COMMAND_SUCCESS);
                        startServiceDiscoveryTimer();
                    } else {
                        logger.info(String.format("disconnected '%s' (%s)", deviceName, deviceAddress));

                        // Cancel service discovery timer, just in case it was started
                        cancelServiceDiscoveryTimer();

                        // Determine if this disconnect was intentional or not
                        if (state == DISCONNECTING) {
                            // We were intentionally disconnecting, so this was expected
                            gattCallback.onConnectionStateChanged(DISCONNECTED, COMMAND_SUCCESS);
                        } else {
                            // The connection was lost for some other reason
                            gattCallback.onConnectionStateChanged(DISCONNECTED, REMOTE_USER_TERMINATED_CONNECTION);
                        }
                    }
                    break;
                case PROPERTY_PAIRED:
                    if (value.getValue().equals(true)) {
                        isBonded = true;
                        gattCallback.onPaired();
                        if (manualBonding || bondingInProgress) {
                            // We are now done with createBond, so call servicesResolved
                            servicesResolved();
                            manualBonding = false;
                            bondingInProgress = false;
                        }
                    } else {
                        gattCallback.onPairingFailed();
                    }
                    break;
                default:
                    // Ignore other properties
            }
        }
    };

    /**
     * Enqueue a runnable to the command queue
     *
     * @param command a Runnable containing a command
     * @return true if the command was successfully enqueued, otherwise false
     */
    private boolean enqueue(Runnable command) {
        final boolean result = commandQueue.add(command);
        if (result) {
            nextCommand();
        } else {
            logger.error(ENQUEUE_ERROR);
        }
        return result;
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
        final Runnable currentCommand = commandQueue.peek();
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
            if (state != CONNECTED) {
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

    @Nullable
    private BluezGattDescriptor getBluezGattDescriptor(@NotNull final UUID serviceUUID, @NotNull final UUID characteristicUUID, @NotNull final UUID descriptorUUID) {
        Objects.requireNonNull(serviceUUID, NO_VALID_SERVICE_UUID_PROVIDED);
        Objects.requireNonNull(characteristicUUID, "no valid characteristic UUID provided");
        Objects.requireNonNull(descriptorUUID, "no valid descriptor UUID provided");

        BluezGattDescriptor descriptor = null;
        BluezGattCharacteristic characteristic = getBluezGattCharacteristic(serviceUUID, characteristicUUID);
        if (characteristic != null) {
            descriptor = characteristic.getGattDescriptorByUuid(descriptorUUID);
        }
        return descriptor;
    }

    @Nullable
    private BluezGattCharacteristic getBluezGattCharacteristic(@NotNull final UUID serviceUUID, @NotNull final UUID characteristicUUID) {
        Objects.requireNonNull(serviceUUID, NO_VALID_SERVICE_UUID_PROVIDED);
        Objects.requireNonNull(characteristicUUID, "no valid characteristic UUID provided");

        BluezGattCharacteristic characteristic = null;
        for (BluezGattCharacteristic gattCharacteristic : characteristicMap.values()) {
            if (characteristicUUID.equals(gattCharacteristic.getUuid())) {
                if (gattCharacteristic.getService().getUuid().equals(serviceUUID)) {
                    characteristic = gattCharacteristic;
                }
            }
        }
        return characteristic;
    }

    @Nullable
    private BluetoothGattCharacteristic getBluetoothGattCharacteristic(@NotNull final BluezGattCharacteristic bluezGattCharacteristic) {
        Objects.requireNonNull(bluezGattCharacteristic, NO_VALID_CHARACTERISTIC_PROVIDED);

        final UUID characteristicUUID = bluezGattCharacteristic.getUuid();
        final UUID serviceUUID = bluezGattCharacteristic.getService().getUuid();
        return getCharacteristic(serviceUUID, characteristicUUID);
    }


    /**
     * Get the services supported by the connected bluetooth peripheral.
     * Only services that are also supported by {@link BluetoothCentralManager} are included.
     *
     * @return Supported services.
     */
    @NotNull
    public List<BluetoothGattService> getServices() {
        return Collections.unmodifiableList(services);
    }

    /**
     * Get the BluetoothGattService object for a service UUID.
     *
     * @param serviceUUID the UUID of the service
     * @return the BluetoothGattService object for the service UUID or null if the peripheral does not have a service with the specified UUID
     */
    @Nullable
    public  BluetoothGattService getService(@NotNull final UUID serviceUUID) {
        Objects.requireNonNull(serviceUUID, NO_VALID_SERVICE_UUID_PROVIDED);

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
    @Nullable
    public BluetoothGattCharacteristic getCharacteristic(@NotNull final UUID serviceUUID, @NotNull final UUID characteristicUUID) {
        Objects.requireNonNull(serviceUUID, NO_VALID_SERVICE_UUID_PROVIDED);
        Objects.requireNonNull(characteristicUUID, NO_VALID_CHARACTERISTIC_PROVIDED);

        final BluetoothGattService service = getService(serviceUUID);
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
    @NotNull
    public String getName() {
        return deviceName;
    }

    void setName(@Nullable final String name) {
        deviceName = name == null ? "" : name;
    }

    /**
     * Get the mac address of the bluetooth peripheral.
     *
     * @return Address of the bluetooth peripheral
     */
    @NotNull
    public String getAddress() {
        return deviceAddress;
    }

    /**
     * Returns the connection state of the peripheral.
     *
     * @return the connection state.
     */
    @NotNull
    public ConnectionState getState() {
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
     * @return returns the bond state
     */
    @NotNull
    public BondState getBondState() {
        if (isPaired()) {
            return BondState.BONDED;
        } else {
            return BondState.NONE;
        }
    }

    /**
     * Boolean to indicate if the specified characteristic is currently notifying or indicating.
     *
     * @param characteristic the characteristic to check
     * @return true if the characteristic is notifying or indicating, false if it is not
     */
    public boolean isNotifying(@NotNull final BluetoothGattCharacteristic characteristic) {
        Objects.requireNonNull(characteristic, NO_VALID_CHARACTERISTIC_PROVIDED);

        final BluezGattCharacteristic nativeCharacteristic = getBluezGattCharacteristic(characteristic.service.getUuid(), characteristic.getUuid());
        if (nativeCharacteristic == null) {
            logger.error(ERROR_NATIVE_CHARACTERISTIC_IS_NULL);
            return false;
        }
        return nativeCharacteristic.isNotifying();
    }

    /**
     * Get all notifying characteristics
     *
     * @return the set of notifying characteristics
     */
    @NotNull
    public Set<BluetoothGattCharacteristic> getNotifyingCharacteristics() {
        return Collections.unmodifiableSet(notifyingCharacteristics);
    }

    /**
     * Create a bond with the peripheral.
     * <p>
     * The bonding command will be enqueued and you will
     * receive updates via the {@link BluetoothPeripheralCallback}.
     *
     * @param peripheralCallback the peripheral callback to use
     * @return true if bonding was started/enqueued, false if not
     */
    public boolean createBond(@NotNull final BluetoothPeripheralCallback peripheralCallback) {
        Objects.requireNonNull(peripheralCallback, "peripheral callback not valid");
        Objects.requireNonNull(device, "device is null");

        setPeripheralCallback(peripheralCallback);
        boolean result = false;
        BluetoothCommandStatus status;
        try {
            if (state == DISCONNECTED) {
                BluezSignalHandler.getInstance().addPeripheral(deviceAddress, this);
                queueHandler = new Handler(deviceAddress + "-queue");
            }

            if (device.isPaired()) {
                // Bluez will hang if we call pair on already paired devices, so connect instead
                logger.error("device already bonded, connecting instead");
                connect();
            } else {
                manualBonding = true;
                logger.info(String.format("pairing with '%s' (%s)", deviceName, deviceAddress));
                connectTimestamp = System.currentTimeMillis();
                device.pair();
            }

            return true;
        } catch (BluezInvalidArgumentsException e) {
            logger.error("Pair exception: invalid argument");
            status = INVALID_COMMAND_PARAMETERS;
        } catch (BluezFailedException e) {
            logger.error("Pair exception: failed");
            status = BLUEZ_OPERATION_FAILED;
        } catch (BluezAuthenticationFailedException e) {
            logger.error("Pair exception: authentication failed");
            status = AUTHENTICATION_FAILURE;
        } catch (BluezAlreadyExistsException e) {
            logger.error("Pair exception: already exists");
            status = CONNECTION_ALREADY_EXISTS;
        } catch (BluezAuthenticationCanceledException e) {
            logger.error("Pair exception: authentication canceled");
            status = CONNECTION_REJECTED_SECURITY_REASONS;
        } catch (BluezAuthenticationRejectedException e) {
            logger.error("Pair exception: authentication rejected");
            status = CONNECTION_REJECTED_SECURITY_REASONS;
        } catch (BluezAuthenticationTimeoutException e) {
            logger.error("Pair exception: authentication timeout");
            status = CONNECTION_REJECTED_SECURITY_REASONS;
        } catch (BluezConnectionAttemptFailedException e) {
            logger.error("Pair exception: connection attempt failed");
            status = CONNECTION_FAILED_ESTABLISHMENT;
        } catch (DBusExecutionException e) {
            status = DBUS_EXECUTION_EXCEPTION;
            if (e.getMessage().equalsIgnoreCase("No reply within specified time")) {
                logger.error("Pairing timeout");
            } else {
                logger.error(e.getMessage());
            }
        } catch (BluezInProgressException e) {
            status = BLUEZ_OPERATION_IN_PROGRESS;
            e.printStackTrace();
        }

        // Clean up after failed pairing
        if (device.isConnected()) {
            device.disconnect();
        } else {
            gattCallback.onConnectionStateChanged(DISCONNECTED, status);
        }

        return result;
    }

    @Nullable
    private BluetoothGattCharacteristic getCharacteristicFromPath(@NotNull final String path) {
        Objects.requireNonNull(path, "no valid path provided");

        final BluezGattCharacteristic characteristic = characteristicMap.get(path);
        if (characteristic == null) return null;

        final BluetoothGattCharacteristic bluetoothGattCharacteristic = getBluetoothGattCharacteristic(characteristic);
        if (bluetoothGattCharacteristic == null) {
            logger.error(String.format("can't find characteristic with path %s", path));
        }
        return bluetoothGattCharacteristic;
    }

    private void startServiceDiscoveryTimer() {
        cancelServiceDiscoveryTimer();

        // Disconnecting doesn't work so do it ourselves
        final Runnable timeoutRunnable = () -> {
            String deviceName = (device != null) ? device.getName() : deviceAddress;
            logger.error(String.format("Service Discovery timeout, disconnecting '%s'", deviceName));

            // Disconnecting doesn't work so do it ourselves
            cancelServiceDiscoveryTimer();
            gattCallback.onConnectionStateChanged(DISCONNECTED, CONNECTION_FAILED_ESTABLISHMENT);
        };
        if (queueHandler != null) {
            timeoutFuture = queueHandler.postDelayed(timeoutRunnable, SERVICE_DISCOVERY_TIMEOUT_IN_MS);
        }
    }

    private void cancelServiceDiscoveryTimer() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    private BluetoothGattDescriptor mapBluezGattDescriptorToBluetoothGattDescriptor(final BluezGattDescriptor descriptor) {
        // TODO What is permission?
        return new BluetoothGattDescriptor(descriptor.getUuid(), 0);
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
    private int mapFlagsToProperty(@NotNull final List<String> flags) {
        Objects.requireNonNull(flags, "flags list not valid");

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

    private BluetoothGattCharacteristic mapBluezGattCharacteristicToBluetoothGattCharacteristic(final BluezGattCharacteristic bluezGattCharacteristic) {
        int properties = mapFlagsToProperty(bluezGattCharacteristic.getFlags());
        final BluetoothGattCharacteristic bluetoothGattCharacteristic = new BluetoothGattCharacteristic(bluezGattCharacteristic.getUuid(), properties);

        // Get all descriptors for this characteristic
        final List<BluezGattDescriptor> descriptors = bluezGattCharacteristic.getGattDescriptors();

        // Process all descriptors
        descriptors.forEach(descriptor -> {
            descriptorMap.put(descriptor.getDbusPath(), descriptor);
            bluetoothGattCharacteristic.addDescriptor(mapBluezGattDescriptorToBluetoothGattDescriptor(descriptor));
        });

        return bluetoothGattCharacteristic;
    }

    private BluetoothGattService mapBluezGattServiceToBluetoothGattService(@NotNull BluezGattService service) {
        // Build up internal services map
        serviceMap.put(service.getDbusPath(), service);

        // Create BluetoothGattService object
        final BluetoothGattService bluetoothGattService = new BluetoothGattService(service.getUuid());
        bluetoothGattService.setPeripheral(this);

        // Get all characteristics for this service
        final List<BluezGattCharacteristic> characteristics = service.getGattCharacteristics();

        // Process all characteristics
        characteristics.forEach(bluetoothGattCharacteristic -> {
            // Build up internal characteristic map
            characteristicMap.put(bluetoothGattCharacteristic.getDbusPath(), bluetoothGattCharacteristic);

            // Create BluetoothGattCharacteristic
            BluetoothGattCharacteristic characteristic = mapBluezGattCharacteristicToBluetoothGattCharacteristic(bluetoothGattCharacteristic);
            characteristic.setService(bluetoothGattService);
            bluetoothGattService.addCharacteristic(characteristic);
        });

        return bluetoothGattService;
    }

    /**
     * Safe copying of a byte array.
     *
     * @param source the byte array to copy, can be null
     * @return non-null byte array that is a copy of source
     */
    @NotNull
    private byte[] copyOf(@Nullable final byte[] source) {
        return (source == null) ? new byte[0] : Arrays.copyOf(source, source.length);
    }

    public @Nullable BluezDevice getDevice() {
        return device;
    }

    public void setDevice(@NotNull final BluezDevice device) {
        this.device = Objects.requireNonNull(device, "no valid device supplied");
    }
}


package com.welie.blessed.bluez;

import org.bluez.GattCharacteristic1;
import org.bluez.GattDescriptor1;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.Map.Entry;

/**
 * Wrapper class which represents a GATT characteristic on a remote device.
 *
 * @author hypfvieh
 *
 */
public class BluezGattCharacteristic extends AbstractBluetoothObject {

    @NotNull
    private final GattCharacteristic1 gattCharacteristic;

    @NotNull
    private final BluezGattService gattService;
    private UUID uuid;

    private final Map<UUID, BluezGattDescriptor> descriptorByUuid = new LinkedHashMap<>();

    public BluezGattCharacteristic(GattCharacteristic1 _gattCharacteristic, BluezGattService _service, String _dbusPath, DBusConnection _dbusConnection) {
        super(BluezDeviceType.GATT_CHARACTERISTIC, _dbusConnection, _dbusPath);

        gattCharacteristic = _gattCharacteristic;
        gattService = _service;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Class<? extends DBusInterface> getInterfaceClass() {
        return GattCharacteristic1.class;
    }

    /**
     * Re-queries the GattCharacteristics from the device.
     */
    public void refreshGattCharacteristics() {
        descriptorByUuid.clear();

        Set<String> findNodes = DbusHelper.findNodes(getDbusConnection(), getDbusPath());
        Map<String, GattDescriptor1> remoteObjects = getRemoteObjects(findNodes, getDbusPath(), GattDescriptor1.class);
        for (Entry<String, GattDescriptor1> entry : remoteObjects.entrySet()) {
            BluezGattDescriptor btDescriptor = new BluezGattDescriptor(entry.getValue(), this, entry.getKey(), getDbusConnection());
            descriptorByUuid.put(btDescriptor.getUuid(), btDescriptor);
        }
    }

    /**
     * Get the currently available GATT descriptors.<br>
     * Will issue a query if {@link #refreshGattCharacteristics()} wasn't called before.
     * @return List, maybe empty but never null
     */
    @NotNull
    public List<BluezGattDescriptor> getGattDescriptors() {
        if (descriptorByUuid.isEmpty()) {
            refreshGattCharacteristics();
        }
        return new ArrayList<>(descriptorByUuid.values());
    }

    /**
     * Return the {@link BluezGattDescriptor} object for the given UUID.
     * @param _uuid uuid
     * @return maybe null if not found
     */
    @Nullable
    public BluezGattDescriptor getGattDescriptorByUuid(UUID _uuid) {
        if (descriptorByUuid.isEmpty()) {
            refreshGattCharacteristics();
        }
        return descriptorByUuid.get(_uuid);
    }

    /**
     * Write value to the GATT characteristic register.<br>
     * Supported options:<br>
     * <pre>
     * "offset": uint16 offset
     * "device": Object Device (Server only)
     * </pre>
     * @param _value value to write
     * @param _options options to use
     * @throws BluezFailedException on failure if operation failed
     * @throws BluezInProgressException when operation already in progress if operation is already in progress
     * @throws BluezNotPermittedException if operation is not permitted
     * @throws BluezNotAuthorizedException when not authorized if not authorized
     * @throws BluezNotSupportedException when operation not supported if not supported
     * @throws BluezInvalidValueLengthException when length of value is invalid
     */
    public void writeValue(byte[] _value, Map<String, Object> _options) throws BluezFailedException, BluezInProgressException, BluezNotPermittedException, BluezNotAuthorizedException, BluezNotSupportedException, BluezInvalidValueLengthException {
        gattCharacteristic.WriteValue(_value, optionsToVariantMap(_options));
    }

    /**
     * Read a value from the GATT characteristics register.<br>
     * Supported options:<br>
     * <pre>
     * "offset": uint16 offset
     * "device": Object Device (Server only)
     * </pre>
     * @param _options options to use
     * @return byte array, maybe null
     * @throws BluezFailedException on failure if anything failed
     * @throws BluezInProgressException when operation already in progress if already in progress
     * @throws BluezNotPermittedException if not permitted
     * @throws BluezNotAuthorizedException when not authorized if not authorized
     * @throws BluezNotSupportedException when operation not supported if not supported
     * @throws BluezInvalidOffsetException if offset is invalid
     */
    public byte[] readValue(Map<String, Object> _options) throws BluezFailedException, BluezInProgressException, BluezNotPermittedException, BluezNotAuthorizedException, BluezNotSupportedException, BluezInvalidOffsetException {
        return gattCharacteristic.ReadValue(optionsToVariantMap(_options));
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * 128-bit characteristic UUID.
     * </p>
     * @return uuid, maybe null
     */
    public UUID getUuid() {
        if(uuid == null) {
            uuid = UUID.fromString(getTyped("UUID", String.class));
        }
        return uuid;
    }

    /**
     * Returns the {@link BluezGattService} object which provides this {@link BluezGattCharacteristic}.
     * @return GattService, maybe null
     */
    public BluezGattService getService() {
        return gattService;
    }

    /**
     * Get the raw {@link GattCharacteristic1} object behind this wrapper.
     * @return {@link GattCharacteristic1}, maybe null
     */
    public GattCharacteristic1 getRawGattCharacteristic() {
        return gattCharacteristic;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * The cached value of the characteristic. This property<br>
     * gets updated only after a successful read request and<br>
     * when a notification or indication is received, upon<br>
     * which a PropertiesChanged signal will be emitted.
     * </p>
     * @return cached characteristics value, maybe null
     */
    public byte[] getValue() {
        List<?> typed = getTyped("Value", ArrayList.class);
        if (typed != null) {
            return byteListToByteArray(typed);
        }
        return new byte[0];
    }

    /**
     * From bluez Documentation:<br>
     * True, if notifications or indications on this characteristic are currently enabled.
     * @return maybe null if feature is not supported
     */
    public Boolean isNotifying() {
        return getTyped("Notifying", Boolean.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Defines how the characteristic value can be used. See<br>
     * Core spec "Table 3.5: Characteristic Properties bit<br>
     * field", and "Table 3.8: Characteristic Extended<br>
     * Properties bit field".
     * <br>
     * </p>
     * <pre>
     * Allowed values:
     *         "broadcast"
     *         "read"
     *         "write-without-response"
     *         "write"
     *         "notify"
     *         "indicate"
     *         "authenticated-signed-writes"
     *         "reliable-write"
     *         "writable-auxiliaries"
     *         "encrypt-read"
     *         "encrypt-write"
     *         "encrypt-authenticated-read"
     *         "encrypt-authenticated-write"
     *         "secure-read" (Server only)
     *         "secure-write" (Server only)
     * </pre>
     * @return string, maybe null
     */
    @SuppressWarnings("unchecked")
    public List<String> getFlags() {
        List<String> typed = getTyped("Flags", ArrayList.class);
        if (typed != null) {
            return typed;
        }
        return Collections.emptyList();
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Starts a notification session from this characteristic
     * if it supports value notifications or indications.
     * <br>
     * </p>
     * @throws BluezFailedException on failure if operation failed
     * @throws BluezInProgressException when operation already in progress if operation already in progress
     * @throws BluezNotSupportedException when operation not supported if operation is not supported
     * @throws BluezNotPermittedException when the operation is not permitted
     */
    public void startNotify() throws BluezFailedException, BluezInProgressException, BluezNotSupportedException, BluezNotPermittedException {
        gattCharacteristic.StartNotify();
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * This method will cancel any previous StartNotify
     * transaction. Note that notifications from a
     * characteristic are shared between sessions thus
     * calling StopNotify will release a single session.
     * <br>
     * </p>
     * @throws BluezFailedException on failure on any error
     */
    public void stopNotify() throws BluezFailedException {
        gattCharacteristic.StopNotify();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [gattCharacteristic=" + gattCharacteristic
                + ", gattService=" + gattService.getDbusPath() + ", getBluetoothType()="
                + getBluetoothType().name() + ", getDbusPath()=" + getDbusPath() + "]";
    }
}

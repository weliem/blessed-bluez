package com.welie.blessed.bluez;

import org.bluez.GattDescriptor1;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Wrapper class which represents a GATT descriptor on a remote device.
 *
 * @author hypfvieh
 */
public class BluezGattDescriptor extends AbstractBluetoothObject {

    private final GattDescriptor1 descriptor;
    private final BluezGattCharacteristic characteristicWrapper;

    public BluezGattDescriptor(GattDescriptor1 _descriptor, BluezGattCharacteristic _characteristicWrapper, String _dbusPath, DBusConnection _dbusConnection) {
        super(BluezDeviceType.GATT_DESCRIPTOR, _dbusConnection, _dbusPath);
        characteristicWrapper = _characteristicWrapper;
        descriptor = _descriptor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Class<? extends DBusInterface> getInterfaceClass() {
        return GattDescriptor1.class;
    }

    /**
     * Write value to the GATT descriptor register.<br>
     * Supported options:<br>
     * <pre>
     * "offset": uint16 offset
     * "device": Object Device (Server only)
     * </pre>
     *
     * @param _value   value to write
     * @param _options options to use
     * @throws BluezFailedException             on failure if anything failed
     * @throws BluezInProgressException         when operation already in progress if operation in progress
     * @throws BluezNotPermittedException       if operation not permitted
     * @throws BluezNotAuthorizedException      when not authorized if not authorized
     * @throws BluezNotSupportedException       when operation not supported if not supported
     * @throws BluezInvalidValueLengthException when length of value is invalid
     */
    public void writeValue(byte[] _value, Map<String, Object> _options) throws BluezFailedException, BluezInProgressException, BluezNotPermittedException, BluezNotAuthorizedException, BluezNotSupportedException, BluezInvalidValueLengthException {
        descriptor.WriteValue(_value, optionsToVariantMap(_options));
    }

    /**
     * Read a value from the GATT descriptor register.<br>
     * Supported options:<br>
     * <pre>
     * "offset": uint16 offset
     * "device": Object Device (Server only)
     * </pre>
     *
     * @param _options options to use
     * @return byte array, maybe null
     * @throws BluezFailedException        on failure if anything failed
     * @throws BluezInProgressException    when operation already in progress if operation in progress
     * @throws BluezNotPermittedException  if operation not permitted
     * @throws BluezNotAuthorizedException when not authorized if not authorized
     * @throws BluezNotSupportedException  when operation not supported if not supported
     */
    public byte[] readValue(Map<String, Object> _options) throws BluezFailedException, BluezInProgressException, BluezNotPermittedException, BluezNotAuthorizedException, BluezNotSupportedException {
        return descriptor.ReadValue(optionsToVariantMap(_options));
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * 128-bit descriptor UUID.
     * </p>
     *
     * @return uuid, maybe null
     */
    public UUID getUuid() {
        return UUID.fromString(getTyped("UUID", String.class));
    }

    /**
     * Get the {@link BluezGattCharacteristic} instance behind this {@link BluezGattDescriptor} object.
     *
     * @return {@link BluezGattCharacteristic}, maybe null
     */
    public BluezGattCharacteristic getCharacteristic() {
        return characteristicWrapper;
    }

    /**
     * Get the raw {@link GattDescriptor1} object behind this wrapper.
     *
     * @return {@link GattDescriptor1}, maybe null
     */
    public GattDescriptor1 getRawCharacteric() {
        return descriptor;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * The cached value of the descriptor. This property<br>
     * gets updated only after a successful read request, upon<br>
     * which a PropertiesChanged signal will be emitted.
     * </p>
     *
     * @return byte array, not null
     */
    @NotNull
    public byte[] getValue() {
        List<?> typed = getTyped("Value", ArrayList.class);
        if (typed != null) {
            return byteListToByteArray(typed);
        }
        return new byte[0];
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Defines how the descriptor value can be used.<br>
     * </p>
     * <i>Possible values:</i>
     * <pre>
     *      "read"
     *      "write"
     *      "encrypt-read"
     *      "encrypt-write"
     *      "encrypt-authenticated-read"
     *      "encrypt-authenticated-write"
     *      "secure-read" (Server Only)
     *      "secure-write" (Server Only)
     * </pre>
     *
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

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [descriptor=" + descriptor + ", characteristicWrapper="
                + characteristicWrapper.getDbusPath() + ", getBluetoothType()="
                + getBluetoothType().name() + ", getDbusPath()=" + getDbusPath() + "]";
    }
}

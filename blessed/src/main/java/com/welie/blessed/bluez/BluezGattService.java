package com.welie.blessed.bluez;

import org.bluez.GattCharacteristic1;
import org.bluez.GattService1;
import org.bluez.exceptions.BluezNotImplementedException;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.Map.Entry;

/**
 * Wrapper class which represents a GATT service provided by a remote device.
 * @author hypfvieh
 *
 */
public class BluezGattService extends AbstractBluetoothObject {

    private final GattService1 service;
    private final BluezDevice device;

    private final Map<UUID, BluezGattCharacteristic> characteristicByUuid = new LinkedHashMap<>();

    public BluezGattService(GattService1 _service, BluezDevice _device, String _dbusPath, DBusConnection _dbusConnection) {
        super(BluezDeviceType.GATT_SERVICE, _dbusConnection, _dbusPath);
        service = _service;
        device = _device;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Class<? extends DBusInterface> getInterfaceClass() {
        return GattService1.class;
    }

    /**
     * Re-queries the GattCharacteristics from the device.
     */
    public void refreshGattCharacteristics() {
        characteristicByUuid.clear();

        Set<String> findNodes = DbusHelper.findNodes(getDbusConnection(), getDbusPath());
        Map<String, GattCharacteristic1> remoteObjects = getRemoteObjects(findNodes, getDbusPath(), GattCharacteristic1.class);
        for (Entry<String, GattCharacteristic1> entry : remoteObjects.entrySet()) {
            BluezGattCharacteristic bluetoothGattCharacteristics = new BluezGattCharacteristic(entry.getValue(), this, entry.getKey(), getDbusConnection());
            characteristicByUuid.put(bluetoothGattCharacteristics.getUuid(), bluetoothGattCharacteristics);
        }
    }

    /**
     * Get the currently available GATT characteristics.<br>
     * Will issue a query if {@link #refreshGattCharacteristics()} wasn't called before.
     * @return List, maybe empty but never null
     */
    @NotNull
    public List<BluezGattCharacteristic> getGattCharacteristics() {
        if (characteristicByUuid.isEmpty()) {
            refreshGattCharacteristics();
        }
        return new ArrayList<>(characteristicByUuid.values());
    }

    /**
     * Return the {@link BluezGattCharacteristic} object for the given UUID.
     * @param uuid uuid
     * @return maybe null if not found
     */
    public BluezGattCharacteristic getGattCharacteristicByUuid(UUID uuid) {
        if (characteristicByUuid.isEmpty()) {
            refreshGattCharacteristics();
        }
        return characteristicByUuid.get(uuid);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * 128-bit service UUID.
     * </p>
     * @return uuid, maybe null
     */
    public UUID getUuid() {
        return UUID.fromString(getTyped("UUID", String.class));
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Indicates whether or not this GATT service is a<br>
     * primary service. If false, the service is secondary.
     * </p>
     * @return maybe null if feature is not supported
     */
    public Boolean isPrimary() {
        return getTyped("Primary", Boolean.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * <b>Not implemented</b><br>
     * Array of object paths representing the included<br>
     * services of this service.
     * </p>
     * @return object array, maybe null
     * @throws BluezNotImplementedException always - because currently not supported
     */
    public Object[] getIncludes() throws BluezNotImplementedException {
        throw new BluezNotImplementedException("Feature not yet implemented");
    }

    /**
     * Get the raw {@link GattService1} object.
     * @return {@link GattService1}, maybe null
     */
    public GattService1 getService() {
        return service;
    }

    /**
     * Get the {@link BluezDevice} instance which is providing this {@link BluezGattService}.
     * @return {@link BluezDevice}, maybe null
     */
    public BluezDevice getDevice() {
        return device;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [service=" + service + ", device=" + device.getDbusPath()
            + ", getBluetoothType()=" + getBluetoothType().name() + ", getDbusPath()=" + getDbusPath() + "]";
    }



}

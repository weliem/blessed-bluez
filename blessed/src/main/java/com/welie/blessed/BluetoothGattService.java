package com.welie.blessed;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a Bluetooth Gatt service
 */
public class BluetoothGattService {

    /**
     * The remote device his service is associated with.
     * This applies to client applications only.
     *
     */
    protected BluetoothPeripheral peripheral;

    /**
     * The UUID of this service.
     *
     */
    protected UUID uuid;


    /**
     * List of characteristics included in this service.
     */
    protected List<BluetoothGattCharacteristic> characteristics;


    /**
     * Create a new BluetoothGattService.
     *
     * @param uuid The UUID for this service
     */
    public BluetoothGattService(@NotNull UUID uuid) {
        Objects.requireNonNull(uuid, "no valid UUID supplied");
        peripheral = null;
        this.uuid = uuid;
        characteristics = new ArrayList<>();
    }

    /**
     * Returns the device associated with this service.
     *
     */
    @Nullable BluetoothPeripheral getPeripheral() {
        return peripheral;
    }

    /**
     * Returns the device associated with this service.
     *
     */
    void setPeripheral(@NotNull BluetoothPeripheral device) {
        Objects.requireNonNull(device, "no valid peripheral specified");
        peripheral = device;
    }

    /**
     * Add a characteristic to this service.
     *
     * @param characteristic The characteristics to be added
     * @return true, if the characteristic was added to the service
     */
    public boolean addCharacteristic(@NotNull BluetoothGattCharacteristic characteristic) {
        Objects.requireNonNull(characteristic, "no valid characteristic supplied");
        characteristic.setService(this);
        return characteristics.add(characteristic);
    }

    /**
     * Returns the UUID of this service
     *
     * @return UUID of this service
     */
    public @NotNull UUID getUuid() {
        return uuid;
    }

    /**
     * Returns a list of characteristics included in this service.
     *
     * @return Characteristics included in this service
     */
    public @NotNull List<BluetoothGattCharacteristic> getCharacteristics() {
        return characteristics;
    }

    /**
     * Returns a characteristic with a given UUID out of the list of
     * characteristics offered by this service.
     *
     * <p>This is a convenience function to allow access to a given characteristic
     * without enumerating over the list returned by {@link #getCharacteristics}
     * manually.
     *
     * <p>If a remote service offers multiple characteristics with the same
     * UUID, the first instance of a characteristic with the given UUID
     * is returned.
     *
     * @param uuid the UUID of the characteristic
     * @return GATT characteristic object or null if no characteristic with the given UUID was
     * found.
     */
    public @Nullable BluetoothGattCharacteristic getCharacteristic(@NotNull UUID uuid) {
        Objects.requireNonNull(uuid, "no valid uuid supplied");

        for (BluetoothGattCharacteristic characteristic : characteristics) {
            if (uuid.equals(characteristic.getUuid())) {
                return characteristic;
            }
        }
        return null;
    }
}

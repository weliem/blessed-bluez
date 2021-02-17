package com.welie.blessed;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Represents a Bluetooth Gatt service
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class BluetoothGattService {

    /**
     * The remote device his service is associated with.
     * This applies to client applications only.
     *
     */
    @Nullable
    protected BluetoothPeripheral peripheral = null;

    /**
     * The UUID of this service.
     *
     */
    @NotNull
    protected final UUID uuid;


    /**
     * List of characteristics included in this service.
     */
    protected final List<BluetoothGattCharacteristic> characteristics = new ArrayList<>();

    /**
     * Create a new BluetoothGattService.
     *
     * @param uuid The UUID for this service
     */
    public BluetoothGattService(@NotNull UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid, "no valid UUID supplied");
    }

    /**
     * Returns the peripheral associated with this service.
     *
     * @return the peripheral associated with this service
     */
    public @Nullable BluetoothPeripheral getPeripheral() {
        return peripheral;
    }

    /**
     * Sets the peripheral associated with this service.
     *
     */
    void setPeripheral(@NotNull BluetoothPeripheral device) {
        peripheral = Objects.requireNonNull(device, "no valid peripheral specified");
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
        return Collections.unmodifiableList(characteristics);
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

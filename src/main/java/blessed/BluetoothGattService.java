package blessed;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BluetoothGattService {

    /**
     * The remote device his service is associated with.
     * This applies to client applications only.
     *
     * @hide
     */
    protected BluetoothPeripheral mDevice;

    /**
     * The UUID of this service.
     *
     * @hide
     */
    protected UUID mUuid;


    /**
     * List of characteristics included in this service.
     */
    protected List<BluetoothGattCharacteristic> mCharacteristics;


    /**
     * Create a new BluetoothGattService.
     *
     * @param uuid The UUID for this service
     */
    public BluetoothGattService(UUID uuid) {
        mDevice = null;
        mUuid = uuid;
        mCharacteristics = new ArrayList<BluetoothGattCharacteristic>();
    }

    /**
     * Returns the device associated with this service.
     *
     */
    BluetoothPeripheral getDevice() {
        return mDevice;
    }

    /**
     * Returns the device associated with this service.
     *
     */
    void setDevice(BluetoothPeripheral device) {
        mDevice = device;
    }

    /**
     * Add a characteristic to this service.
     *
     * @param characteristic The characteristics to be added
     * @return true, if the characteristic was added to the service
     */
    public boolean addCharacteristic(BluetoothGattCharacteristic characteristic) {
        mCharacteristics.add(characteristic);
        characteristic.setService(this);
        return true;
    }

    /**
     * Returns the UUID of this service
     *
     * @return UUID of this service
     */
    public UUID getUuid() {
        return mUuid;
    }

    /**
     * Returns a list of characteristics included in this service.
     *
     * @return Characteristics included in this service
     */
    public List<BluetoothGattCharacteristic> getCharacteristics() {
        return mCharacteristics;
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
     * @return GATT characteristic object or null if no characteristic with the given UUID was
     * found.
     */
    public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        for (BluetoothGattCharacteristic characteristic : mCharacteristics) {
            if (uuid.equals(characteristic.getUuid())) {
                return characteristic;
            }
        }
        return null;
    }
}

package blessed;

import java.util.UUID;

public class BluetoothGattDescriptor {
    /**
     * Value used to enable notification for a client configuration descriptor
     */
    public static final byte[] ENABLE_NOTIFICATION_VALUE = {0x01, 0x00};

    /**
     * Value used to enable indication for a client configuration descriptor
     */
    public static final byte[] ENABLE_INDICATION_VALUE = {0x02, 0x00};

    /**
     * Value used to disable notifications or indicatinos
     */
    public static final byte[] DISABLE_NOTIFICATION_VALUE = {0x00, 0x00};

    /**
     * Descriptor read permission
     */
    public static final int PERMISSION_READ = 0x01;

    /**
     * Descriptor permission: Allow encrypted read operations
     */
    public static final int PERMISSION_READ_ENCRYPTED = 0x02;

    /**
     * Descriptor permission: Allow reading with man-in-the-middle protection
     */
    public static final int PERMISSION_READ_ENCRYPTED_MITM = 0x04;

    /**
     * Descriptor write permission
     */
    public static final int PERMISSION_WRITE = 0x10;

    /**
     * Descriptor permission: Allow encrypted writes
     */
    public static final int PERMISSION_WRITE_ENCRYPTED = 0x20;

    /**
     * Descriptor permission: Allow encrypted writes with man-in-the-middle
     * protection
     */
    public static final int PERMISSION_WRITE_ENCRYPTED_MITM = 0x40;

    /**
     * Descriptor permission: Allow signed write operations
     */
    public static final int PERMISSION_WRITE_SIGNED = 0x80;

    /**
     * Descriptor permission: Allow signed write operations with
     * man-in-the-middle protection
     */
    public static final int PERMISSION_WRITE_SIGNED_MITM = 0x100;

    /**
     * The UUID of this descriptor.
     *
     * @hide
     */
    protected UUID mUuid;

    /**
     * Permissions for this descriptor
     *
     * @hide
     */
    protected int mPermissions;

    /**
     * Back-reference to the characteristic this descriptor belongs to.
     *
     * @hide
     */
    protected BluetoothGattCharacteristic mCharacteristic;

    /**
     * Create a new BluetoothGattDescriptor.
     *
     * @param uuid The UUID for this descriptor
     * @param permissions Permissions for this descriptor
     */
    public BluetoothGattDescriptor(UUID uuid, int permissions) {
        initDescriptor(null, uuid, permissions);
    }

    /**
     * Create a new BluetoothGattDescriptor.
     *
     * @param characteristic The characteristic this descriptor belongs to
     * @param uuid The UUID for this descriptor
     * @param permissions Permissions for this descriptor
     */
    public BluetoothGattDescriptor(BluetoothGattCharacteristic characteristic, UUID uuid, int instance, int permissions) {
        initDescriptor(characteristic, uuid, permissions);
    }

    private void initDescriptor(BluetoothGattCharacteristic characteristic, UUID uuid, int permissions) {
        mCharacteristic = characteristic;
        mUuid = uuid;
        mPermissions = permissions;
    }


    /**
     * Returns the characteristic this descriptor belongs to.
     *
     * @return The characteristic.
     */
    public BluetoothGattCharacteristic getCharacteristic() {
        return mCharacteristic;
    }

    /**
     * Set the back-reference to the associated characteristic
     *
     */
    void setCharacteristic(BluetoothGattCharacteristic characteristic) {
        mCharacteristic = characteristic;
    }

    /**
     * Returns the UUID of this descriptor.
     *
     * @return UUID of this descriptor
     */
    public UUID getUuid() {
        return mUuid;
    }

    /**
     * Returns the permissions for this descriptor.
     *
     * @return Permissions of this descriptor
     */
    public int getPermissions() {
        return mPermissions;
    }

}


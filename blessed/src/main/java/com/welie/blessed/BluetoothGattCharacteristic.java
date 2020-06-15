package com.welie.blessed;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a Bluetooth GATT Characteristic
 *
 * <p>A GATT characteristic is a basic data element used to construct a GATT service,
 * {@link BluetoothGattService}. The characteristic contains a value as well as
 * additional information and optional GATT descriptors, {@link BluetoothGattDescriptor}.
 */
public class BluetoothGattCharacteristic {

    /**
     * Characteristic proprty: Characteristic is broadcastable.
     */
    public static final int PROPERTY_BROADCAST = 0x01;

    /**
     * Characteristic property: Characteristic is readable.
     */
    public static final int PROPERTY_READ = 0x02;

    /**
     * Characteristic property: Characteristic can be written without response.
     */
    public static final int PROPERTY_WRITE_NO_RESPONSE = 0x04;

    /**
     * Characteristic property: Characteristic can be written.
     */
    public static final int PROPERTY_WRITE = 0x08;

    /**
     * Characteristic property: Characteristic supports notification
     */
    public static final int PROPERTY_NOTIFY = 0x10;

    /**
     * Characteristic property: Characteristic supports indication
     */
    public static final int PROPERTY_INDICATE = 0x20;

    /**
     * Characteristic property: Characteristic supports write with signature
     */
    public static final int PROPERTY_SIGNED_WRITE = 0x40;

    /**
     * Characteristic property: Characteristic has extended properties
     */
    public static final int PROPERTY_EXTENDED_PROPS = 0x80;

    /**
     * Characteristic read permission
     */
    public static final int PERMISSION_READ = 0x01;

    /**
     * Characteristic permission: Allow encrypted read operations
     */
    public static final int PERMISSION_READ_ENCRYPTED = 0x02;

    /**
     * Characteristic permission: Allow reading with man-in-the-middle protection
     */
    public static final int PERMISSION_READ_ENCRYPTED_MITM = 0x04;

    /**
     * Characteristic write permission
     */
    public static final int PERMISSION_WRITE = 0x10;

    /**
     * Characteristic permission: Allow encrypted writes
     */
    public static final int PERMISSION_WRITE_ENCRYPTED = 0x20;

    /**
     * Characteristic permission: Allow encrypted writes with man-in-the-middle
     * protection
     */
    public static final int PERMISSION_WRITE_ENCRYPTED_MITM = 0x40;

    /**
     * Characteristic permission: Allow signed write operations
     */
    public static final int PERMISSION_WRITE_SIGNED = 0x80;

    /**
     * Characteristic permission: Allow signed write operations with
     * man-in-the-middle protection
     */
    public static final int PERMISSION_WRITE_SIGNED_MITM = 0x100;

    /**
     * Write characteristic, requesting acknoledgement by the remote device
     */
    public static final int WRITE_TYPE_DEFAULT = 0x02;

    /**
     * Write characteristic without requiring a response by the remote device
     */
    public static final int WRITE_TYPE_NO_RESPONSE = 0x01;

    /**
     * Write characteristic including authentication signature
     */
    public static final int WRITE_TYPE_SIGNED = 0x04;

    /**
     * Characteristic value format type uint8
     */
    public static final int FORMAT_UINT8 = 0x11;

    /**
     * Characteristic value format type uint16
     */
    public static final int FORMAT_UINT16 = 0x12;

    /**
     * Characteristic value format type uint32
     */
    public static final int FORMAT_UINT32 = 0x14;

    /**
     * Characteristic value format type sint8
     */
    public static final int FORMAT_SINT8 = 0x21;

    /**
     * Characteristic value format type sint16
     */
    public static final int FORMAT_SINT16 = 0x22;

    /**
     * Characteristic value format type sint32
     */
    public static final int FORMAT_SINT32 = 0x24;

    /**
     * Characteristic value format type sfloat (16-bit float)
     */
    public static final int FORMAT_SFLOAT = 0x32;

    /**
     * Characteristic value format type float (32-bit float)
     */
    public static final int FORMAT_FLOAT = 0x34;


    /**
     * The UUID of this characteristic.
     *
     * @hide
     */
    protected UUID mUuid;

    /**
     * Characteristic properties.
     *
     * @hide
     */
    protected int mProperties;

    /**
     * Characteristic permissions.
     *
     * @hide
     */
    protected int mPermissions;

    /**
     * Back-reference to the service this characteristic belongs to.
     *
     * @hide
     */
    protected BluetoothGattService mService;


    /**
     * List of descriptors included in this characteristic.
     */
    protected List<BluetoothGattDescriptor> mDescriptors;

    /**
     * Create a new BluetoothGattCharacteristic.
     *
     * @param uuid The UUID for this characteristic
     * @param properties Properties of this characteristic
     * @param permissions Permissions for this characteristic
     */
    public BluetoothGattCharacteristic(UUID uuid, int properties, int permissions) {
        initCharacteristic(null, uuid, 0, properties, permissions);
    }


    private void initCharacteristic(BluetoothGattService service,
                                    UUID uuid, int instanceId,
                                    int properties, int permissions) {
        mUuid = uuid;
        mProperties = properties;
        mPermissions = permissions;
        mService = service;
        mDescriptors = new ArrayList<BluetoothGattDescriptor>();
    }


    /**
     * Adds a descriptor to this characteristic.
     *
     * @param descriptor Descriptor to be added to this characteristic.
     * @return true, if the descriptor was added to the characteristic
     */
    public boolean addDescriptor(BluetoothGattDescriptor descriptor) {
        mDescriptors.add(descriptor);
        descriptor.setCharacteristic(this);
        return true;
    }


    /**
     * Returns the service this characteristic belongs to.
     *
     * @return The asscociated service
     */
    public BluetoothGattService getService() {
        return mService;
    }

    /**
     * Sets the service associated with this device.
     *
     */
    void setService(BluetoothGattService service) {
        mService = service;
    }

    /**
     * Returns the UUID of this characteristic
     *
     * @return UUID of this characteristic
     */
    public UUID getUuid() {
        return mUuid;
    }


    /**
     * Returns the properties of this characteristic.
     *
     * <p>The properties contain a bit mask of property flags indicating
     * the features of this characteristic.
     *
     * @return Properties of this characteristic
     */
    public int getProperties() {
        return mProperties;
    }

    /**
     * Returns the permissions for this characteristic.
     *
     * @return Permissions of this characteristic
     */
    public int getPermissions() {
        return mPermissions;
    }

    /**
     * Returns a list of descriptors for this characteristic.
     *
     * @return Descriptors for this characteristic
     */
    public List<BluetoothGattDescriptor> getDescriptors() {
        return mDescriptors;
    }

    /**
     * Returns a descriptor with a given UUID out of the list of
     * descriptors for this characteristic.
     *
     * @param uuid the UUID of the descriptor
     * @return GATT descriptor object or null if no descriptor with the given UUID was found.
     */
    public BluetoothGattDescriptor getDescriptor(UUID uuid) {
        for (BluetoothGattDescriptor descriptor : mDescriptors) {
            if (descriptor.getUuid().equals(uuid)) {
                return descriptor;
            }
        }
        return null;
    }
}

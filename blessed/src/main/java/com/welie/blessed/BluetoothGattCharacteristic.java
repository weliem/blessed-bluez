package com.welie.blessed;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Represents a Bluetooth GATT Characteristic
 *
 * <p>A GATT characteristic is a basic data element used to construct a GATT service,
 * {@link BluetoothGattService}. The characteristic contains a value as well as
 * additional information and optional GATT descriptors, {@link BluetoothGattDescriptor}.
 */

@SuppressWarnings({"unused", "UnusedReturnValue"})
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

    public enum WriteType {
        /**
         * Write with response (aka write request)
         */
        WITH_RESPONSE,

        /**
         * Write without response (aka write command)
         */
        WITHOUT_RESPONSE
    }

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
     */
    @NotNull
    protected final UUID uuid;

    /**
     * Characteristic properties.
     *
     */
    protected final int properties;

    /**
     * Back-reference to the service this characteristic belongs to.
     *
     */
    @Nullable
    protected BluetoothGattService service;

    /**
     * List of descriptors included in this characteristic.
     */
    protected final List<BluetoothGattDescriptor> descriptors = new ArrayList<>();

    /**
     * Create a new BluetoothGattCharacteristic.
     *
     * @param uuid The UUID for this characteristic
     * @param properties Properties of this characteristic
     */
    public BluetoothGattCharacteristic(@NotNull UUID uuid, int properties) {
        this.uuid = Objects.requireNonNull(uuid, "no valid UUID supplied");
        this.properties = properties;
    }

    /**
     * Adds a descriptor to this characteristic.
     *
     * @param descriptor Descriptor to be added to this characteristic.
     * @return true, if the descriptor was added to the characteristic
     */
    public boolean addDescriptor(@NotNull BluetoothGattDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "no valid descriptor supplied");
        descriptor.setCharacteristic(this);
        return descriptors.add(descriptor);
    }

    /**
     * Returns the service this characteristic belongs to.
     *
     * @return The asscociated service
     */
    public @Nullable BluetoothGattService getService() {
        return service;
    }

    /**
     * Sets the service associated with this device.
     *
     */
    void setService(@NotNull BluetoothGattService service) {
        this.service = Objects.requireNonNull(service, "no valid service supplied");
    }

    /**
     * Returns the UUID of this characteristic
     *
     * @return UUID of this characteristic
     */
    public @NotNull UUID getUuid() {
        return uuid;
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
        return properties;
    }

    /**
     * Returns a list of descriptors for this characteristic.
     *
     * @return Descriptors for this characteristic
     */
    public @NotNull List<BluetoothGattDescriptor> getDescriptors() {
        return Collections.unmodifiableList(descriptors);
    }

    /**
     * Returns a descriptor with a given UUID out of the list of
     * descriptors for this characteristic.
     *
     * @param uuid the UUID of the descriptor
     * @return GATT descriptor object or null if no descriptor with the given UUID was found.
     */
    public @Nullable BluetoothGattDescriptor getDescriptor(@NotNull UUID uuid) {
        Objects.requireNonNull(uuid, "no valid uuid supplied");

        for (BluetoothGattDescriptor descriptor : descriptors) {
            if (descriptor.getUuid().equals(uuid)) {
                return descriptor;
            }
        }
        return null;
    }

    public boolean supportsReading() {
        return (properties & PROPERTY_READ) > 0;
    }

    public boolean supportsWritingWithResponse() {
        return (properties & PROPERTY_WRITE) > 0;
    }

    public boolean supportsWritingWithoutResponse() {
        return (properties & PROPERTY_WRITE_NO_RESPONSE) > 0;
    }

    public boolean supportsNotifying() {
        return (((properties & PROPERTY_NOTIFY) > 0) || ((properties & PROPERTY_INDICATE) > 0));
    }

    public boolean supportsWriteType(WriteType writeType) {
        int writeProperty;
        switch (writeType) {
            case WITH_RESPONSE:
                writeProperty = PROPERTY_WRITE;
                break;
            case WITHOUT_RESPONSE:
                writeProperty = PROPERTY_WRITE_NO_RESPONSE;
                break;
            default:
                writeProperty = -1;
                break;
        }
        return (getProperties() & writeProperty) != 0;
    }
}

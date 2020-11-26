package com.welie.blessed;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a Bluetooth Gatt descriptor
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class BluetoothGattDescriptor {
    /**
     * Value used to enable notification for a client configuration descriptor
     */
    protected static final byte[] ENABLE_NOTIFICATION_VALUE = {0x01, 0x00};

    /**
     * Value used to enable indication for a client configuration descriptor
     */
    protected static final byte[] ENABLE_INDICATION_VALUE = {0x02, 0x00};

    /**
     * Value used to disable notifications or indications
     */
    protected static final byte[] DISABLE_NOTIFICATION_VALUE = {0x00, 0x00};

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
     */
    @NotNull
    protected final UUID uuid;

    /**
     * Permissions for this descriptor
     *
     */
    protected final int permissions;

    /**
     * Back-reference to the characteristic this descriptor belongs to.
     *
     */
    @Nullable
    protected BluetoothGattCharacteristic characteristic;

    /**
     * Create a new BluetoothGattDescriptor.
     *
     * @param uuid The UUID for this descriptor
     * @param permissions Permissions for this descriptor
     */
    public BluetoothGattDescriptor(@NotNull UUID uuid, int permissions) {
        this.uuid = Objects.requireNonNull(uuid, "no valid UUID supplied");
        this.permissions = permissions;
    }

    /**
     * Returns the characteristic this descriptor belongs to.
     *
     * @return The characteristic.
     */
    public @Nullable BluetoothGattCharacteristic getCharacteristic() {
        return characteristic;
    }

    /**
     * Set the back-reference to the associated characteristic
     *
     */
    void setCharacteristic(@NotNull BluetoothGattCharacteristic characteristic) {
        this.characteristic = Objects.requireNonNull(characteristic, "no valid characteristic supplied");
    }

    /**
     * Returns the UUID of this descriptor.
     *
     * @return UUID of this descriptor
     */
    public @NotNull UUID getUuid() {
        return uuid;
    }

    /**
     * Returns the permissions for this descriptor.
     *
     * @return Permissions of this descriptor
     */
    public int getPermissions() {
        return permissions;
    }
}


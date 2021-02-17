package com.welie.blessed;

import org.jetbrains.annotations.NotNull;

@SuppressWarnings("unused")
public enum BluetoothCommandStatus {

    // Note that most of these error codes correspond to the ATT error codes as defined in the Bluetooth Standard, Volume 3, Part F, 3.4.1 Error handling p1491)
    // See https://www.bluetooth.org/docman/handlers/downloaddoc.ashx?doc_id=478726,

    /**
     * Success
     */
    COMMAND_SUCCESS(0x00),

    /**
     * The attribute handle given was not valid on this server.
     */
    INVALID_HANDLE(0x01),

    /**
     * The attribute cannot be read.
     */
    READ_NOT_PERMITTED(0x02),

    /**
     * The attribute cannot be written.
     */
    WRITE_NOT_PERMITTED(0x03),

    /**
     * The attribute requires authentication before it can be read or written.
     */
    INSUFFICIENT_AUTHENTICATION(0x05),

    /**
     * Attribute server does not support the request received from the client.
     */
    REQUEST_NOT_SUPPORTED(0x06),

    /**
     * Offset specified was past the end of the attribute.
     */
    INVALID_OFFSET(0x07),

    /**
     * The attribute requires authorization before it can be read or written.
     */
    INSUFFICIENT_AUTHORIZATION(0x08),

    /**
     * No attribute found within the given attri- bute handle range.
     */
    ATTRIBUTE_NOT_FOUND(0x0A),

    /**
     * The Encryption Key Size used for encrypting this link is insufficient.
     */
    INSUFFICIENT_ENCRYPTION_KEY_SIZE(0x0C),

    /**
     * The attribute value length is invalid for the operation.
     */
    INVALID_ATTRIBUTE_VALUE_LENGTH(0x0D),

    /**
     * A connection was rejected due to security requirements not being fulfilled, like authentication or pairing.
     */
    CONNECTION_REJECTED_SECURITY_REASONS(0x0E),

    /**
     * The attribute requires encryption before it can be read or written.
     */
    INSUFFICIENT_ENCRYPTION(0x0F),

    /**
     * The attribute type is not a supported grouping attribute as defined by a higher layer specification.
     */
    UNSUPPORTED_GROUP_TYPE(0x10),

    /**
     * Insufficient Resources to complete the request.
     */
    INSUFFICIENT_RESOURCES(0x11),

    /**
     * At least one of the HCI command parameters is invalid.
     */
    INVALID_COMMAND_PARAMETERS(0x12),

    /**
     * The attribute parameter value was not allowed
     */
    VALUE_NOT_ALLOWED(0x13),

    /**
     * Pairing or authentication failed due to incorrect results in the pairing or authentication procedure. This could be due to an incorrect PIN or Link Key.
     */
    AUTHENTICATION_FAILURE(0x05),

    /**
     * A connection to this device already exists and multiple connections to the same device are not permitted.
     */
    CONNECTION_ALREADY_EXISTS(0x0B),

    /**
     * The user on the remote device terminated the connection.
     */
    REMOTE_USER_TERMINATED_CONNECTION(0x13),

    /**
     * The LL initiated a connection but the connection has failed to be established.
     */
    CONNECTION_FAILED_ESTABLISHMENT(0x3E),

    //
    // (0x80 to 0x9F) - Application error code defined by a higher layer specification.
    //

    /**
     * Operation is already in progress
     */
    BLUEZ_OPERATION_IN_PROGRESS(0x80),

    /**
     * Bluez was not ready to execute the command
     */
    BLUEZ_NOT_READY(0x81),

    /**
     * Bluez operation failed
     */
    BLUEZ_OPERATION_FAILED(0x85),

    /**
     * Peripheral is not connected
     */
    NOT_CONNECTED(0x84),

    /**
     * A DBUS execution exception occurred
     */
    DBUS_EXECUTION_EXCEPTION(0x90),

    /**
     * Unknown status
     *
     * Should not ever happen
     */
    UNKNOWN_STATUS(0xFFFF);

    BluetoothCommandStatus(int value) {
        this.value = value;
    }

    private final int value;

    public int getValue() {
        return value;
    }

    @NotNull
    public static BluetoothCommandStatus fromValue(int value) {
        for (BluetoothCommandStatus type : values()) {
            if (type.getValue() == value)
                return type;
        }
        return UNKNOWN_STATUS;
    }
}

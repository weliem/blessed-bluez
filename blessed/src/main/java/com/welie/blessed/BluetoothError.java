package com.welie.blessed;

public enum BluetoothError {

    // Note that most of these error codes correspond to the ATT error codes as defined in the Bluetooth Standard, Volume 3, Part F, 3.4.1 Error handling p1491)
    // See https://www.bluetooth.org/docman/handlers/downloaddoc.ashx?doc_id=478726,

    // The attribute handle given was not valid on this server.
    INVALID_HANDLE(0x01),

    // The attribute cannot be read.
    READ_NOT_PERMITTED(0x02),

    // The attribute cannot be written.
    WRITE_NOT_PERMITTED(0x03),

    // The attribute PDU was invalid.
    INVALID_PDU(0x04),

    // The attribute requires authentication before it can be read or written.
    INSUFFICIENT_AUTHENTICATION(0x05),

    // Attribute server does not support the request received from the client.
    REQUEST_NOT_SUPPORTED(0x06),

    // Offset specified was past the end of the attribute.
    INVALID_OFFSET(0x07),

    // The attribute requires authorization before it can be read or written.
    INSUFFICIENT_AUTHORIZATION(0x08),

    // Too many prepare writes have been queued.
    PREPARE_QUEUE_FULL(0x09),

    // No attribute found within the given attri- bute handle range.
    ATTRIBUTE_NOT_FOUND(0x0A),

    // The attribute cannot be read using the ATT_READ_BLOB_REQ PDU.
    ATTRIBUTE_NOT_LONG(0x0B),

    // The Encryption Key Size used for encrypting this link is insufficient.
    INSUFFICIENT_ENCRYPTION_KEY_SIZE(0x0C),

    // The attribute value length is invalid for the operation.
    INVALID_ATTRIBUTE_VALUE_LENGTH(0x0D),

    // The attribute request that was requested has encountered an error that was unlikely, and therefore could not be completed as requested.
    UNLIKELY_ERROR(0x0E),

    // The attribute requires encryption before it can be read or written.
    INSUFFICIENT_ENCRYPTION(0x0F),

    // The attribute type is not a supported grouping attribute as defined by a higher layer specification.
    UNSUPPORTED_GROUP_TYPE(0x10),

    // Insufficient Resources to complete the request.
    INSUFFICIENT_RESOURCES(0x11),

    // The server requests the client to redis- cover the database.
    DATABASE_OUT_OF_SYNC(0x12),

    // The attribute parameter value was not allowed
    VALUE_NOT_ALLOWED(0x13);

    // (0x80 – 0x9F) - Application error code defined by a higher layer specification.
    // (0xE0 – 0xFF) - Common profile and service error codes defined in Core Specification Supplement, Part B.

    BluetoothError(int value)
    {
        this.value = value;
    }

    private final int value;

    public int getValue()
    {
        return value;
    }

    public static BluetoothError fromValue(int value) {
        for(BluetoothError type : values()) {
            if(type.getValue() == value)
                return type;
        }
        return null;
    }
}

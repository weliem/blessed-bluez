package testapp;

/**
 * Enum that contains all measurement units as specified here:
 * https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.characteristic.weight_measurement.xml
 */
public enum WeightUnit {
    Unknown,
    Kilograms,
    Pounds,
    Stones;
}

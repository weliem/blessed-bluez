package com.welie.blessed;

import java.util.Arrays;
import java.util.Map;

import static com.welie.blessed.BluetoothBytesParser.bytes2String;

@SuppressWarnings("unused")
public class ScanResult {
    private final long timestampNanos;
    private final String name;
    private final String address;
    private final String[] uuids;
    private final int rssi;
    private final Map<Integer, byte[]> manufacturerData;
    private final Map<String, byte[]> serviceData;

    public ScanResult(String deviceName, String deviceAddress, String[] uuids, int rssi, Map<Integer, byte[]> manufacturerData, Map<String, byte[]> serviceData ) {
        this.timestampNanos = System.nanoTime();
        this.name = deviceName;
        this.address = deviceAddress;
        this.uuids = uuids;
        this.rssi = rssi;
        this.manufacturerData = manufacturerData;
        this.serviceData = serviceData;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }
    public String getName() {
        return name;
    }
    public String getAddress() {
        return address;
    }
    public String[] getUuids() {
        return uuids;
    }
    public Map<Integer, byte[]> getManufacturerData() {
        return manufacturerData;
    }

    @Override
    public String toString() {
        return "ScanResult{" +
                "timestampNanos=" + timestampNanos +
                ", name='" + name + '\'' +
                ", address='" + address + '\'' +
                ", uuids=" + Arrays.toString(uuids) +
                ", rssi=" + rssi +
                ", manufacturerData=" + manufacturerDataToString() +
                ", serviceData=" + serviceData +
                '}';
    }

    private String manufacturerDataToString() {
        if (manufacturerData == null) return "null";
        StringBuilder result = new StringBuilder("[");
        manufacturerData.forEach((code, bytes) -> result.append(String.format("0x%04x->0x%s,",code , bytes2String(bytes))));
        result.deleteCharAt(result.length() - 1);
        result.append("]");
        return result.toString();
    }
}

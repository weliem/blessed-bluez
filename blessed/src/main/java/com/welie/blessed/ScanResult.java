package com.welie.blessed;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Map;

import static com.welie.blessed.BluetoothBytesParser.bytes2String;

@SuppressWarnings("unused")
public class ScanResult {
    private long timestampNanos;
    private final String name;
    private final String address;
    private final String[] uuids;
    private int rssi;
    private Map<Integer, byte[]> manufacturerData;
    private Map<String, byte[]> serviceData;

    public ScanResult(String deviceName, @NotNull String deviceAddress, String[] uuids, int rssi, Map<Integer, byte[]> manufacturerData, Map<String, byte[]> serviceData) {
        this.name = deviceName;
        this.address = deviceAddress;
        this.uuids = uuids;
        this.rssi = rssi;
        setManufacturerData(manufacturerData);
        setServiceData(serviceData);
        stamp();
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public String getName() {
        return name;
    }

    public @NotNull String getAddress() {
        return address;
    }

    public String[] getUuids() {
        return uuids;
    }

    public Map<Integer, byte[]> getManufacturerData() {
        return manufacturerData;
    }

    public Map<String, byte[]> getServiceData() {
        return serviceData;
    }

    public void setRssi(int rssi) {
        this.rssi = rssi;
    }

    public void setManufacturerData(Map<Integer, byte[]> manufacturerData) {
        this.manufacturerData = (manufacturerData != null && manufacturerData.isEmpty()) ? null : manufacturerData;
    }

    public void setServiceData(Map<String, byte[]> serviceData) {
        this.serviceData = (serviceData != null && serviceData.isEmpty()) ? null : serviceData;
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
                ", serviceData=" + serviceDataToString() +
                '}';
    }

    private String manufacturerDataToString() {
        if (manufacturerData == null || manufacturerData.isEmpty()) return "null";
        StringBuilder result = new StringBuilder("[");
        manufacturerData.forEach((code, bytes) -> result.append(String.format("0x%04x->0x%s,", code, bytes2String(bytes))));
        result.deleteCharAt(result.length() - 1);
        result.append("]");
        return result.toString();
    }

    private String serviceDataToString() {
        if (serviceData == null || serviceData.isEmpty()) return "null";
        StringBuilder result = new StringBuilder("[");
        serviceData.forEach((uuid, bytes) -> result.append(String.format("%s->0x%s,", uuid, bytes2String(bytes))));
        result.deleteCharAt(result.length() - 1);
        result.append("]");
        return result.toString();
    }

    public void stamp() {
        this.timestampNanos = System.nanoTime();
    }
}

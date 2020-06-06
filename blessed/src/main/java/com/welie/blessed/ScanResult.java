package com.welie.blessed;

import java.util.Arrays;
import java.util.Map;

public class ScanResult {
    private final String name;
    private final String address;
    private final String[] uuids;
    private final int rssi;
    private final Map<Integer, byte[]> manufacturerData;
    private final Map<String, byte[]> serviceData;

    public ScanResult(String deviceName, String deviceAddress, String[] uuids, int rssi, Map<Integer, byte[]> manufacturerData, Map<String, byte[]> serviceData ) {
        this.name = deviceName;
        this.address = deviceAddress;
        this.uuids = uuids;
        this.rssi = rssi;
        this.manufacturerData = manufacturerData;
        this.serviceData = serviceData;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
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
                "name='" + name + '\'' +
                ", address='" + address + '\'' +
                ", uuids=" + Arrays.toString(uuids) +
                ", rssi=" + rssi +
                ", manufacturerData=" + manufacturerData +
                ", serviceData=" + serviceData +
                '}';
    }
}

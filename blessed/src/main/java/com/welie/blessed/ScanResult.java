package com.welie.blessed;

import java.util.Arrays;
import java.util.Map;

public class ScanResult {
    private final String name;
    private final String address;
    private final String[] uuids;
    private final int rssi;
    private final Map<Integer, byte[]> manufacturerData;

    public ScanResult(String deviceName, String deviceAddress, String[] uuids, int rssi, Map<Integer, byte[]> manufacturerData ) {
        this.name = deviceName;
        this.address = deviceAddress;
        this.uuids = uuids;
        this.rssi = rssi;
        this.manufacturerData = manufacturerData;
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
                '}';
    }
}

package com.welie.blessed;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.welie.blessed.BluetoothBytesParser.bytes2String;


public class ScanResult {
    private long timestampNanos;
    private final String name;
    private final String address;
    private final @NotNull List<UUID> uuids;
    private int rssi;
    private Map<@NotNull Integer, byte[]> manufacturerData;
    private Map<@NotNull String, byte[]> serviceData;

    public ScanResult(@Nullable String deviceName, @NotNull String deviceAddress, @NotNull List<@NotNull UUID> uuids, int rssi, @NotNull Map<@NotNull Integer, byte[]> manufacturerData, @NotNull Map<@NotNull String, byte[]> serviceData) {
        this.name = deviceName;
        this.address = Objects.requireNonNull(deviceAddress, "no valid address supplied");
        this.uuids = Objects.requireNonNull(uuids, "no valid uuids supplied");
        this.rssi = rssi;
        setManufacturerData(manufacturerData);
        setServiceData(serviceData);
        stamp();
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public @Nullable String getName() {
        return name;
    }

    public @NotNull String getAddress() {
        return address;
    }

    public @NotNull List<UUID> getUuids() {
        return uuids;
    }

    public @NotNull Map<Integer, byte[]> getManufacturerData() {
        return manufacturerData;
    }

    public @NotNull Map<String, byte[]> getServiceData() {
        return serviceData;
    }

    public void setRssi(int rssi) {
        this.rssi = rssi;
    }

    public void setManufacturerData(@NotNull Map<@NotNull Integer, byte[]> manufacturerData) {
        this.manufacturerData = Objects.requireNonNull(manufacturerData, "no valid manufacturer data supplied");
    }

    public void setServiceData(@NotNull Map<@NotNull String, byte[]> serviceData) {
        this.serviceData = Objects.requireNonNull(serviceData, "no valid service data supplied");
    }

    @Override
    public String toString() {
        return "ScanResult{" +
                "timestampNanos=" + timestampNanos +
                ", name='" + name + '\'' +
                ", address='" + address + '\'' +
                ", uuids=" + uuids +
                ", rssi=" + rssi +
                ", manufacturerData=" + manufacturerDataToString() +
                ", serviceData=" + serviceDataToString() +
                '}';
    }

    private String manufacturerDataToString() {
        if (manufacturerData == null || manufacturerData.isEmpty()) return "[]";
        StringBuilder result = new StringBuilder("[");
        manufacturerData.forEach((code, bytes) -> result.append(String.format("0x%04x->0x%s,", code, bytes2String(bytes))));
        result.deleteCharAt(result.length() - 1);
        result.append("]");
        return result.toString();
    }

    private String serviceDataToString() {
        if (serviceData == null || serviceData.isEmpty()) return "[]";
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

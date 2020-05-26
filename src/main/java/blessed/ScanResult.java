package blessed;

public class ScanResult {
    private String name;
    private String address;
    private String[] uuids;
    private int rssi;

    public ScanResult(String deviceName, String deviceAddress, String[] uuids, int rssi) {
        this.name = deviceName;
        this.address = deviceAddress;
        this.uuids = uuids;
        this.rssi = rssi;
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
}

package blessed;

public class ScanResult {
    private String name;
    private String address;
    private String[] uuids;

    public ScanResult(String deviceName, String deviceAddress, String[] uuids) {
        this.name = deviceName;
        this.address = deviceAddress;
        this.uuids = uuids;
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

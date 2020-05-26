package testapp;

import blessed.*;


public class BluetoothHandler {
    private static final String TAG = BluetoothCentral.class.getSimpleName();

    private BluetoothCentral central;

    private BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {
        @Override
        public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
            super.onConnectedPeripheral(peripheral);
        }

        @Override
        public void onDiscoveredPeripheral(final BluetoothPeripheral peripheral, final ScanResult scanResult) {
            HBLogger.i(TAG, String.format("Found %s", peripheral.getName()));
            central.stopScanning();
        }
    };

    public BluetoothHandler() {

        central = new BluetoothCentral(bluetoothCentralCallback, new Handler("testapp.BluetoothHandler"));

        central.scanForPeripherals();
    }
}

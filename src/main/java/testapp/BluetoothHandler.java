package testapp;

import com.welie.blessed.*;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

import static com.welie.blessed.BluetoothPeripheral.GATT_SUCCESS;


public class BluetoothHandler {
    private static final String TAG = BluetoothCentral.class.getSimpleName();

    private final BluetoothCentral central;
    private final Handler handler = new Handler("testapp.BluetoothHandler");

    // UUIDs for the Blood Pressure service (BLP)
    private static final UUID BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb");
    private static final UUID BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A35-0000-1000-8000-00805f9b34fb");

    // UUIDs for the Health Thermometer service (HTS)
    private static final UUID HTS_SERVICE_UUID = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb");
    private static final UUID TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A1C-0000-1000-8000-00805f9b34fb");
    private static final UUID PNP_ID_CHARACTERISTIC_UUID = UUID.fromString("00002A50-0000-1000-8000-00805f9b34fb");

    // UUIDs for the Heart Rate service (HRS)
    private static final UUID HRS_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb");
    private static final UUID HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb");

    // UUIDs for the Device Information service (DIS)
    private static final UUID DIS_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb");
    private static final UUID MANUFACTURER_NAME_CHARACTERISTIC_UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb");
    private static final UUID MODEL_NUMBER_CHARACTERISTIC_UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb");

    // UUIDs for the Current Time service (CTS)
    private static final UUID CTS_SERVICE_UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb");
    private static final UUID CURRENT_TIME_CHARACTERISTIC_UUID = UUID.fromString("00002A2B-0000-1000-8000-00805f9b34fb");

    // UUIDs for the Battery Service (BAS)
    private static final UUID BTS_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb");

    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {
        @Override
        public void onServicesDiscovered(@NotNull BluetoothPeripheral peripheral) {
            HBLogger.i(TAG, "services discovered, starting initialization");

            // Read manufacturer and model number from the Device Information Service
            if(peripheral.getService(DIS_SERVICE_UUID) != null) {
                peripheral.readCharacteristic(peripheral.getCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID));
                peripheral.readCharacteristic(peripheral.getCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID));
            }

            // Turn on notifications for Blood Pressure Service
            if(peripheral.getService(BLP_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID), true);
            }

            // Turn on notification for Health Thermometer Service
            if(peripheral.getService(HTS_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(HTS_SERVICE_UUID, TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID), true);
            }
        }

        @Override
        public void onNotificationStateUpdate(@NotNull BluetoothPeripheral peripheral, @NotNull BluetoothGattCharacteristic characteristic, int status) {
            if( status == GATT_SUCCESS) {
                if(peripheral.isNotifying(characteristic)) {
                    HBLogger.i(TAG, String.format("SUCCESS: Notify set to 'on' for %s", characteristic.getUuid()));
                } else {
                    HBLogger.i(TAG, String.format("SUCCESS: Notify set to 'off' for %s", characteristic.getUuid()));
                }
            } else {
                HBLogger.e(TAG, String.format("ERROR: Changing notification state failed for %s", characteristic.getUuid()));
            }
        }

        @Override
        public void onCharacteristicUpdate(@NotNull BluetoothPeripheral peripheral, byte[] value, @NotNull BluetoothGattCharacteristic characteristic, int status) {
 //           HBLogger.i(TAG, String.format("Received %s", bytes2String(value)));
            UUID characteristicUUID = characteristic.getUuid();
            BluetoothBytesParser parser = new BluetoothBytesParser(value);

            if(characteristicUUID.equals(MANUFACTURER_NAME_CHARACTERISTIC_UUID)) {
                String manufacturer = parser.getStringValue(0);
                HBLogger.i(TAG, String.format("Received manufacturer: %s", manufacturer));
            }
            else if(characteristicUUID.equals(MODEL_NUMBER_CHARACTERISTIC_UUID)) {
                String modelNumber = parser.getStringValue(0);
                HBLogger.i(TAG, String.format("Received modelnumber: %s", modelNumber));
            }
            else if(characteristicUUID.equals(TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID)) {
                TemperatureMeasurement measurement = new TemperatureMeasurement(value);
                HBLogger.i(TAG, measurement.toString());
                peripheral.disconnect();
            }
            else if(characteristicUUID.equals(BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID)) {
                BloodPressureMeasurement measurement = new BloodPressureMeasurement(value);
                HBLogger.i(TAG, measurement.toString());
                peripheral.disconnect();
            }
        }

        @Override
        public void onCharacteristicWrite(@NotNull BluetoothPeripheral peripheral, byte[] value, @NotNull BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(peripheral, value, characteristic, status);
        }

        @Override
        public void onDescriptorRead(@NotNull BluetoothPeripheral peripheral, byte[] value, @NotNull BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(peripheral, value, descriptor, status);
        }

        @Override
        public void onDescriptorWrite(@NotNull BluetoothPeripheral peripheral, byte[] value, @NotNull BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(peripheral, value, descriptor, status);
        }

        @Override
        public void onBondingStarted(@NotNull BluetoothPeripheral peripheral) {
            super.onBondingStarted(peripheral);
        }

        @Override
        public void onBondingSucceeded(@NotNull BluetoothPeripheral peripheral) {
            super.onBondingSucceeded(peripheral);
        }

        @Override
        public void onBondingFailed(@NotNull BluetoothPeripheral peripheral) {
            super.onBondingFailed(peripheral);
        }

        @Override
        public void onBondLost(@NotNull BluetoothPeripheral peripheral) {
            super.onBondLost(peripheral);
        }

        @Override
        public void onReadRemoteRssi(@NotNull BluetoothPeripheral peripheral, int rssi, int status) {
            super.onReadRemoteRssi(peripheral, rssi, status);
        }
    };

    private final BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {
        @Override
        public void onConnectedPeripheral(@NotNull BluetoothPeripheral peripheral) {
            super.onConnectedPeripheral(peripheral);
 //           HBLogger.i(TAG, "connected peripheral");
 //           startScanning();
        }

        @Override
        public void onConnectionFailed(@NotNull BluetoothPeripheral peripheral, int status) {
            super.onConnectionFailed(peripheral, status);
            handler.postDelayed(() -> startScanning(), 30000L);
        }

        @Override
        public void onDisconnectedPeripheral(@NotNull BluetoothPeripheral peripheral, int status) {
            super.onDisconnectedPeripheral(peripheral, status);
            HBLogger.i(TAG, "disconnected peripheral");
            handler.postDelayed(() -> startScanning(), 30000L);
        }

        @Override
        public void onDiscoveredPeripheral(final @NotNull BluetoothPeripheral peripheral, final @NotNull ScanResult scanResult) {
//            HBLogger.i(TAG, String.format("Found %s (%s)", peripheral.getName(), peripheral.getAddress()));
 //           HBLogger.i(TAG, scanResult.toString());
            if (peripheral.getName() != null && (peripheral.getName().startsWith("IH") || peripheral.getName().startsWith("TAID"))) {
                central.stopScanning();
                central.connectPeripheral(peripheral, peripheralCallback);
            }
        }
    };

    public BluetoothHandler() {

        central = new BluetoothCentral(bluetoothCentralCallback, handler);

        startScanning();
    }

    void startScanning() {
        central.scanForPeripherals();
    }
}

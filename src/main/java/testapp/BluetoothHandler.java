package testapp;

import com.welie.blessed.*;
import com.welie.blessed.internal.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.Calendar;
import java.util.Date;
import java.util.UUID;
import java.util.logging.Logger;

import static com.welie.blessed.BluetoothGattCharacteristic.PROPERTY_WRITE;
import static com.welie.blessed.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
import static com.welie.blessed.BluetoothPeripheral.GATT_SUCCESS;

public class BluetoothHandler {
    private static final String TAG = BluetoothHandler.class.getSimpleName();
    private final Logger logger = Logger.getLogger(TAG);
    private final BluetoothCentral central;
    private final Handler handler = new Handler("testapp.BluetoothHandler");
    private Runnable timeoutRunnable;
    private boolean justBonded = false;

    // UUIDs for the Blood Pressure service (BLP)
    private static final UUID BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb");
    private static final UUID BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A35-0000-1000-8000-00805f9b34fb");

    // UUIDs for the Health Thermometer service (HTS)
    private static final UUID HTS_SERVICE_UUID = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb");
    private static final UUID TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A1C-0000-1000-8000-00805f9b34fb");

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

    // UUIDs for the Pulse Oximeter Service (PLX)
    public static final UUID PLX_SERVICE_UUID = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb");
    private static final UUID PLX_SPOT_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a5e-0000-1000-8000-00805f9b34fb");
    private static final UUID PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb");

    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {
        @Override
        public void onServicesDiscovered(@NotNull BluetoothPeripheral peripheral) {
            // Read manufacturer and model number from the Device Information Service
            if (peripheral.getService(DIS_SERVICE_UUID) != null) {
                peripheral.readCharacteristic(peripheral.getCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID));
                peripheral.readCharacteristic(peripheral.getCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID));
            }

            // Turn on notifications for Current Time Service
            if (peripheral.getService(CTS_SERVICE_UUID) != null) {
                BluetoothGattCharacteristic currentTimeCharacteristic = peripheral.getCharacteristic(CTS_SERVICE_UUID, CURRENT_TIME_CHARACTERISTIC_UUID);
                peripheral.setNotify(currentTimeCharacteristic, true);

                // If it has the write property we write the current time
                if ((currentTimeCharacteristic.getProperties() & PROPERTY_WRITE) > 0) {
                    BluetoothBytesParser parser = new BluetoothBytesParser();
                    parser.setCurrentTime(Calendar.getInstance());
                    peripheral.writeCharacteristic(currentTimeCharacteristic, parser.getValue(), WRITE_TYPE_DEFAULT);
                }
            }

            // Turn on notifications for Blood Pressure Service
            if (peripheral.getService(BLP_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID), true);
            }

            // Turn on notification for Health Thermometer Service
            if (peripheral.getService(HTS_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(HTS_SERVICE_UUID, TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID), true);
            }

            // Turn on notification for Pulse Oximeter Service
            if (peripheral.getService(PLX_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(PLX_SERVICE_UUID, PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID), true);
                peripheral.setNotify(peripheral.getCharacteristic(PLX_SERVICE_UUID, PLX_SPOT_MEASUREMENT_CHAR_UUID), true);
            }

            // Turn on notification for Heart Rate  Service
            if (peripheral.getService(HRS_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(HRS_SERVICE_UUID, HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID), true);
            }
        }

        @Override
        public void onNotificationStateUpdate(@NotNull BluetoothPeripheral peripheral, @NotNull BluetoothGattCharacteristic characteristic, int status) {
            if (status == GATT_SUCCESS) {
                if (peripheral.isNotifying(characteristic)) {
                    logger.info(String.format("SUCCESS: Notify set to 'on' for %s", characteristic.getUuid()));

                    // If we just bonded wit the A&D 651BLE, issue a disconnect to finish the pairing process
                    if (justBonded && peripheral.getName().contains("651BLE")) {
                        peripheral.cancelConnection();
                        justBonded = false;
                    }
                } else {
                    logger.info(String.format("SUCCESS: Notify set to 'off' for %s", characteristic.getUuid()));
                }
            } else {
                logger.severe(String.format("ERROR: Changing notification state failed for %s", characteristic.getUuid()));
            }
        }

        @Override
        public void onCharacteristicUpdate(@NotNull BluetoothPeripheral peripheral, byte[] value, @NotNull BluetoothGattCharacteristic characteristic, int status) {
            UUID characteristicUUID = characteristic.getUuid();
            BluetoothBytesParser parser = new BluetoothBytesParser(value);

            if (characteristicUUID.equals(MANUFACTURER_NAME_CHARACTERISTIC_UUID)) {
                String manufacturer = parser.getStringValue(0);
                logger.info(String.format("Received manufacturer: %s", manufacturer));
            } else if (characteristicUUID.equals(MODEL_NUMBER_CHARACTERISTIC_UUID)) {
                String modelNumber = parser.getStringValue(0);
                logger.info(String.format("Received modelnumber: %s", modelNumber));
            } else if (characteristicUUID.equals(TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID)) {
                TemperatureMeasurement measurement = new TemperatureMeasurement(value);
                logger.info(measurement.toString());
                startDisconnectTimer(peripheral);
            } else if (characteristicUUID.equals(BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID)) {
                BloodPressureMeasurement measurement = new BloodPressureMeasurement(value);
                logger.info(measurement.toString());
                startDisconnectTimer(peripheral);
            } else if (characteristicUUID.equals(PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID)) {
                PulseOximeterContinuousMeasurement measurement = new PulseOximeterContinuousMeasurement(value);
                logger.info(measurement.toString());
            } else if (characteristicUUID.equals(PLX_SPOT_MEASUREMENT_CHAR_UUID)) {
                PulseOximeterSpotMeasurement measurement = new PulseOximeterSpotMeasurement(value);
                logger.info(measurement.toString());
                startDisconnectTimer(peripheral);
            } else if (characteristicUUID.equals(HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID)) {
                HeartRateMeasurement measurement = new HeartRateMeasurement(value);
                logger.info(measurement.toString());
            } else if (characteristicUUID.equals(CURRENT_TIME_CHARACTERISTIC_UUID)) {
                Date currentTime = parser.getDateTime();
                logger.info(String.format("Received device time: %s", currentTime));
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
            justBonded = true;
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
            logger.info(String.format("rssi is %d", rssi));
        }
    };

    public void startDisconnectTimer(final BluetoothPeripheral peripheral) {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }

        this.timeoutRunnable = peripheral::cancelConnection;
        handler.postDelayed(timeoutRunnable, 2000L);
    }

    private final BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {
        @Override
        public void onConnectedPeripheral(@NotNull BluetoothPeripheral peripheral) {
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
            logger.info("disconnected peripheral");
            handler.postDelayed(() -> startScanning(), 30000L);
        }

        @Override
        public void onDiscoveredPeripheral(final @NotNull BluetoothPeripheral peripheral, final @NotNull ScanResult scanResult) {
            logger.info(scanResult.toString());
            central.stopScan();
            central.connectPeripheral(peripheral, peripheralCallback);
        }
    };

    public BluetoothHandler() {

        logger.info("initializing BluetoothCentral");
        central = new BluetoothCentral(bluetoothCentralCallback);
        central.setPinCodeForPeripheral("B0:49:5F:01:20:8F", "635227");
        startScanning();
    }

    void startScanning() {
        central.scanForPeripheralsWithServices(new UUID[]{HTS_SERVICE_UUID, PLX_SERVICE_UUID, BLP_SERVICE_UUID, HRS_SERVICE_UUID});
//        central.scanForPeripheralsWithNames(new String[]{"Nonin"});
//        central.scanForPeripheralsWithAddresses(new String[]{"C0:26:DF:01:F2:72"});
//        central.scanForPeripherals();
//
//        BluetoothPeripheral peripheral = central.getPeripheral("C0:26:DF:01:F2:72");
//        central.autoConnectPeripheral(peripheral, peripheralCallback);
    }
}

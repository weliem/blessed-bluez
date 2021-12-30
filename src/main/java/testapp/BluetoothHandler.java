package testapp;

import com.welie.blessed.*;
import com.welie.blessed.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ScheduledFuture;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.welie.blessed.BluetoothCommandStatus.COMMAND_SUCCESS;
import static com.welie.blessed.BluetoothCentralManager.SCANOPTION_NO_NULL_NAMES;
import static com.welie.blessed.BluetoothGattCharacteristic.*;


public class BluetoothHandler {
    private static final String TAG = BluetoothHandler.class.getSimpleName();
    private final Logger logger = LoggerFactory.getLogger(TAG);
    private final BluetoothCentralManager central;
    private final Handler handler = new Handler("testapp.BluetoothHandler");
    private boolean justBonded = false;
    private static final List<String> blackList = new ArrayList<>();

    @Nullable
    private ScheduledFuture<?> timeoutFuture;

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

    // UUIDs for the Weight Scale Service (WSS)
    public static final UUID WSS_SERVICE_UUID = UUID.fromString("0000181D-0000-1000-8000-00805f9b34fb");
    private static final UUID WSS_MEASUREMENT_CHAR_UUID = UUID.fromString("00002A9D-0000-1000-8000-00805f9b34fb");

    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {
        @Override
        public void onServicesDiscovered(@NotNull final BluetoothPeripheral peripheral, @NotNull final List<BluetoothGattService> services) {
            peripheral.readCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID);
            peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID);

            BluetoothGattCharacteristic currentTimeCharacteristic = peripheral.getCharacteristic(CTS_SERVICE_UUID, CURRENT_TIME_CHARACTERISTIC_UUID);
            if (currentTimeCharacteristic != null) {
                peripheral.setNotify(currentTimeCharacteristic, true);

                // If it has the write property we write the current time
                if (currentTimeCharacteristic.supportsWritingWithResponse()) {
                    BluetoothBytesParser parser = new BluetoothBytesParser();
                    parser.setCurrentTime(Calendar.getInstance());
                    peripheral.writeCharacteristic(currentTimeCharacteristic, parser.getValue(), WriteType.WITH_RESPONSE);
                }
            }

            peripheral.readCharacteristic(BTS_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID);
            peripheral.setNotify(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, true);
            peripheral.setNotify(HTS_SERVICE_UUID, TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID, true);
            peripheral.setNotify(PLX_SERVICE_UUID, PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID, true);
            peripheral.setNotify(PLX_SERVICE_UUID, PLX_SPOT_MEASUREMENT_CHAR_UUID, true);
            peripheral.setNotify(HRS_SERVICE_UUID, HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID, true);
            peripheral.setNotify(WSS_SERVICE_UUID, WSS_MEASUREMENT_CHAR_UUID, true);
        }

        @Override
        public void onNotificationStateUpdate(@NotNull BluetoothPeripheral peripheral, @NotNull BluetoothGattCharacteristic characteristic, BluetoothCommandStatus status) {
            if (status == COMMAND_SUCCESS) {
                final boolean isNotifying = peripheral.isNotifying(characteristic);
                logger.info(String.format("SUCCESS: Notify set to '%s' for %s", isNotifying, characteristic.getUuid()));
                if (isNotifying) {
                    // If we just bonded wit the A&D 651BLE, issue a disconnect to finish the pairing process
                    String peripheralName = peripheral.getName() == null ? "" : peripheral.getName();
                    if (justBonded && isANDPeripheral(peripheralName)) {
                        peripheral.cancelConnection();
                        justBonded = false;
                    }

                    if (isANDPeripheral(peripheralName)) {
                        startDisconnectTimer(peripheral);
                    }
                } else {
                    // Apparently we are turning off notifications as part of a controlled disconnect
                    if (peripheral.getNotifyingCharacteristics().isEmpty()) {
                        peripheral.cancelConnection();
                    }
                }
            } else {
                logger.error(String.format("ERROR: Changing notification state failed for %s", characteristic.getUuid()));
            }
        }

        @Override
        public void onCharacteristicUpdate(@NotNull BluetoothPeripheral peripheral, byte[] value, @NotNull BluetoothGattCharacteristic characteristic, BluetoothCommandStatus status) {
            final UUID characteristicUUID = characteristic.getUuid();
            final BluetoothBytesParser parser = new BluetoothBytesParser(value);

            // Deal with errors
            if (status != COMMAND_SUCCESS) {
                logger.error(String.format("command failed with status %s", status));
                return;
            }

            if (characteristicUUID.equals(MANUFACTURER_NAME_CHARACTERISTIC_UUID)) {
                String manufacturer = parser.getStringValue(0);
                logger.info(String.format("Received manufacturer: '%s'", manufacturer));
            } else if (characteristicUUID.equals(MODEL_NUMBER_CHARACTERISTIC_UUID)) {
                String modelNumber = parser.getStringValue(0);
                logger.info(String.format("Received modelnumber: '%s'", modelNumber));
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
            } else if (characteristicUUID.equals(WSS_MEASUREMENT_CHAR_UUID)) {
                WeightMeasurement measurement = new WeightMeasurement(value);
                logger.info(measurement.toString());
                startDisconnectTimer(peripheral);
            } else if (characteristicUUID.equals(CURRENT_TIME_CHARACTERISTIC_UUID)) {
                Date currentTime = parser.getDateTime();
                logger.info(String.format("Received device time: %s", currentTime));
            } else if (characteristicUUID.equals(BATTERY_LEVEL_CHARACTERISTIC_UUID)) {
                int batteryLevel = parser.getIntValue(FORMAT_UINT8);
                logger.info(String.format("battery level %d", batteryLevel));
            }
        }

        @Override
        public void onBondingStarted(@NotNull BluetoothPeripheral peripheral) {
            logger.info("bonding started");
        }

        @Override
        public void onBondingSucceeded(@NotNull BluetoothPeripheral peripheral) {
            logger.info("bonding succeeded");
            justBonded = true;
        }

        @Override
        public void onBondingFailed(@NotNull BluetoothPeripheral peripheral) {
            logger.info("bonding failed");
        }

        @Override
        public void onBondLost(@NotNull BluetoothPeripheral peripheral) {
            logger.info("bond lost");
        }

        @Override
        public void onReadRemoteRssi(@NotNull BluetoothPeripheral peripheral, int rssi, BluetoothCommandStatus status) {
            logger.info(String.format("rssi is %d", rssi));
        }
    };

    private boolean isANDPeripheral(String peripheralName) {
        return peripheralName.contains("352BLE") || peripheralName.contains("651BLE");
    }

    public void startDisconnectTimer(final BluetoothPeripheral peripheral) {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }

        Runnable timeoutRunnable = () -> turnOffAllNotifications(peripheral);
        timeoutFuture = handler.postDelayed(timeoutRunnable, 2000L);
    }

    private void turnOffAllNotifications(@NotNull final BluetoothPeripheral peripheral) {
        // Turn off notifications for all characteristics that are notifying
        // We do this because Bluez remembers notification state between connections but peripherals don't
        peripheral.getNotifyingCharacteristics().forEach(characteristic -> peripheral.setNotify(characteristic, false));
    }

    private final BluetoothCentralManagerCallback bluetoothCentralManagerCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onConnectedPeripheral(@NotNull BluetoothPeripheral peripheral) {
            logger.info("connected peripheral");
        }

        @Override
        public void onConnectionFailed(@NotNull BluetoothPeripheral peripheral, @NotNull BluetoothCommandStatus status) {
            logger.info(String.format("connection failed with status %s",status));
            final String peripheralAddress = peripheral.getAddress();
            handler.postDelayed(() -> blackList.remove(peripheralAddress), 2000L);
        }

        @Override
        public void onDisconnectedPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull BluetoothCommandStatus status) {
            logger.info("disconnected peripheral");
            final String peripheralAddress = peripheral.getAddress();
            handler.postDelayed(() -> {
                logger.info(String.format("removing '%s' from blacklist", peripheralAddress));
                blackList.remove(peripheralAddress);
            }, 40000L);
        }

        @Override
        public void onDiscoveredPeripheral(final @NotNull BluetoothPeripheral peripheral, final @NotNull ScanResult scanResult) {
            // See if this device is on the blacklist
            final String peripheralAddress = peripheral.getAddress();
            final boolean blacklisted = blackList.contains(peripheralAddress);
            if (blacklisted) return;

            // Not blacklisted so put it on the blacklist and connect to it
            blackList.add(peripheralAddress);
            logger.info(scanResult.toString());
            central.connectPeripheral(peripheral, peripheralCallback);
        }
    };

    public BluetoothHandler() {
        logger.info("initializing BluetoothCentral");
        central = new BluetoothCentralManager(bluetoothCentralManagerCallback, new HashSet<>(Collections.singletonList(SCANOPTION_NO_NULL_NAMES)));
        central.setPinCodeForPeripheral("B0:49:5F:01:20:8F", "635227");
        central.setRssiThreshold(-120);
        startScanning();
    }

    void startScanning() {
        central.scanForPeripheralsWithServices(new UUID[]{WSS_SERVICE_UUID, HTS_SERVICE_UUID, PLX_SERVICE_UUID, BLP_SERVICE_UUID, HRS_SERVICE_UUID});
    }
}

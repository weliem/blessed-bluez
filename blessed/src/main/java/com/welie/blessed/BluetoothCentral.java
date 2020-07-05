package com.welie.blessed;

import com.welie.blessed.bluez.*;
import com.welie.blessed.internal.Handler;
import com.welie.blessed.internal.InternalCallback;
import org.bluez.Adapter1;
import org.bluez.AgentManager1;
import org.bluez.Device1;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.DBusMap;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.handlers.AbstractInterfacesAddedHandler;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.welie.blessed.BluetoothPeripheral.*;
import static java.lang.System.exit;

/**
 * Represents a Bluetooth Central object
 */
public class BluetoothCentral {
    private static final String TAG = BluetoothCentral.class.getSimpleName();
    private final Logger logger = Logger.getLogger(TAG);
    private DBusConnection dbusConnection;
    private BluezAdapter adapter;
    private final BluetoothCentralCallback bluetoothCentralCallback;
    private final Handler callBackHandler;
    private final Handler timeoutHandler = new Handler(this.getClass().getSimpleName());
    private final Handler queueHandler = new Handler("CentralQueue");
    private Runnable timeoutRunnable;
    private volatile boolean isScanning = false;
    private volatile boolean isPowered = false;
    private volatile boolean isStoppingScan = false;
    private volatile boolean autoScanActive = false;
    private volatile boolean normalScanActive = false;
    private volatile boolean commandQueueBusy;
    private final Map<DiscoveryFilter, Object> scanFilters = new EnumMap<>(DiscoveryFilter.class);
    private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();
    private String currentCommand;
    private String currentDeviceAddress;
    private final Map<String, BluetoothPeripheral> connectedPeripherals = new ConcurrentHashMap<>();
    private final Map<String, BluetoothPeripheral> unconnectedPeripherals = new ConcurrentHashMap<>();
    private final Map<String, BluezDevice> scannedBluezDevices = new ConcurrentHashMap<>();
    private final Map<String, BluetoothPeripheral> scannedPeripherals = new ConcurrentHashMap<>();
    private final Map<String, ScanResult> scanResultCache = new ConcurrentHashMap<>();
    private @NotNull String[] scanPeripheralNames = new String[0];
    private @NotNull String[] scanPeripheralAddresses = new String[0];
    private @NotNull String[] scanUUIDs = new String[0];
    private final List<String> reconnectPeripheralAddresses = new ArrayList<>();
    private final Map<String, BluetoothPeripheralCallback> reconnectCallbacks = new ConcurrentHashMap<>();
    private final Map<String, String> pinCodes = new ConcurrentHashMap<>();
    private BluezAgentManager bluetoothAgentManager = null;
    private Set<String> scanOptions = new HashSet<>();

    private static final int ADDRESS_LENGTH = 17;
    private static final short DISCOVERY_RSSI_THRESHOLD = -70;

    // Scan in intervals. Make sure it is less than 10seconds to avoid issues with Bluez internal scanning
    private static final long SCAN_WINDOW = TimeUnit.SECONDS.toMillis(6);
    private final long SCAN_INTERVAL = TimeUnit.SECONDS.toMillis(8);

    // Bluez Adapter property strings
    private static final String PROPERTY_DISCOVERING = "Discovering";
    private static final String PROPERTY_POWERED = "Powered";

    // Bluez interface names
    private static final String BLUEZ_PATH = "/org/bluez";
    private static final String BLUEZ_ADAPTER_INTERFACE = "org.bluez.Adapter1";

    private static final String ENQUEUE_ERROR = "ERROR: Could not enqueue stop scanning command";

    // Scan options
    public static final String SCANOPTION_NO_NULL_NAMES = "ScanOption.NoNullNames";

    private final InternalCallback internalCallback = new InternalCallback() {
        @Override
        public void connected(final BluetoothPeripheral peripheral) {
            final String peripheralAddress = peripheral.getAddress();
            connectedPeripherals.put(peripheralAddress, peripheral);
            unconnectedPeripherals.remove(peripheralAddress);
            scannedPeripherals.remove(peripheralAddress);

            completeConnectOrDisconnectCommand(peripheralAddress);

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onConnectedPeripheral(peripheral);
                }
            });
        }

        @Override
        public void servicesDiscovered(final BluetoothPeripheral peripheral) {
            restartScannerIfNeeded();
            logger.info("service discovery succeeded");
        }

        @Override
        public void serviceDiscoveryFailed(final BluetoothPeripheral peripheral) {
            logger.info("Service discovery failed");
            if (peripheral.isPaired()) {
                callBackHandler.postDelayed(() -> removeDevice(peripheral), 200L);
            }
        }

        @Override
        public void connectFailed(final BluetoothPeripheral peripheral) {
            final String peripheralAddress = peripheral.getAddress();
            connectedPeripherals.remove(peripheralAddress);
            unconnectedPeripherals.remove(peripheralAddress);
            scannedPeripherals.remove(peripheralAddress);

            // Complete the 'connect' command if this was the device we were connecting
            completeConnectOrDisconnectCommand(peripheralAddress);

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onConnectionFailed(peripheral, 0);
                }
            });

            restartScannerIfNeeded();
        }

        @Override
        public void disconnected(final BluetoothPeripheral peripheral) {
            final String peripheralAddress = peripheral.getAddress();
            connectedPeripherals.remove(peripheralAddress);
            unconnectedPeripherals.remove(peripheralAddress);
            scannedPeripherals.remove(peripheralAddress);

            completeConnectOrDisconnectCommand(peripheralAddress);

            // Remove unbonded devices from DBus to make setting notifications work on reconnection (Bluez issue)
            if (!peripheral.isPaired()) {
                removeDevice(peripheral);
            }

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onDisconnectedPeripheral(peripheral, 0);
                }
            });

            restartScannerIfNeeded();
        }
    };

    private void restartScannerIfNeeded() {
        if (autoScanActive || normalScanActive) {
            startScanning();
        }
    }

    private void completeConnectOrDisconnectCommand(String deviceAddress) {
        // Complete the 'connect' command if this was the device we were connecting
        if (currentCommand.equalsIgnoreCase(PROPERTY_CONNECTED) && deviceAddress.equalsIgnoreCase(currentDeviceAddress)) {
            completedCommand();
        }
    }

    /**
     * Construct a new BluetoothCentral object
     *
     * @param bluetoothCentralCallback the callback to call for updates
     */
    public BluetoothCentral(@NotNull BluetoothCentralCallback bluetoothCentralCallback, @Nullable Set<String> scanOptions) {
        this.bluetoothCentralCallback = bluetoothCentralCallback;
        this.callBackHandler = new Handler("Central-callBackHandler");
        if (scanOptions != null) this.scanOptions = scanOptions;

        try {
            // Connect to the DBus
            dbusConnection = DBusConnection.newConnection(DBusConnection.DBusBusType.SYSTEM);
            if (!chooseAdapter()) exit(0);
            setupPairingAgent();
            BluezSignalHandler.createInstance(dbusConnection).addCentral(this);
            registerInterfaceAddedHandler(interfacesAddedHandler);
        } catch (DBusException e) {
            e.printStackTrace();
        }
    }

    private boolean chooseAdapter() {
        // Find all adapters and pick one if there are more than one
        List<BluezAdapter> adapters = findBluetoothAdapters();
        if (!adapters.isEmpty()) {
            logger.info(String.format("found %d bluetooth adapter(s)", adapters.size()));

            // Take the adapter with the highest number
            adapter = adapters.get(adapters.size() - 1);
            logger.info("using adapter " + adapter.getDeviceName());

            // Make sure the adapter is powered on
            isPowered = adapter.isPowered();
            if (!isPowered) {
                logger.info("adapter not on, so turning it on now");
                adapterOn();
            }
            return true;
        }

        logger.severe("no bluetooth adaptors found");
        return false;
    }

    private void setupPairingAgent() throws BluezInvalidArgumentsException, BluezAlreadyExistsException, BluezDoesNotExistException {
        // Setup pairing agent
        PairingAgent agent = new PairingAgent("/test/agent", dbusConnection, new PairingDelegate() {
            @Override
            public String requestPassCode(String deviceAddress) {
                logger.info(String.format("received passcode request for %s", deviceAddress));

                // See if we have a pass code for this device
                String passCode = pinCodes.get(deviceAddress);

                // If we don't have one try "000000"
                if (passCode == null) {
                    logger.info("No passcode available for this device, trying 000000");
                    passCode = "000000";
                }
                logger.info(String.format("sending passcode %s", passCode));
                return passCode;
            }

            @Override
            public void onPairingStarted(String deviceAddress) {
                BluetoothPeripheral peripheral = getPeripheral(deviceAddress);
                if (peripheral != null) {
                    peripheral.gattCallback.onPairingStarted();
                }
            }
        });

        BluezAgentManager agentManager = getBluetoothAgentManager();
        if (agentManager != null) {
//                The capability parameter can have the values
//                "DisplayOnly", "DisplayYesNo", "KeyboardOnly",
//                "NoInputNoOutput" and "KeyboardDisplay" which
//                reflects the input and output capabilities of the
//                agent.
            agentManager.registerAgent(agent, "KeyboardOnly");
            agentManager.requestDefaultAgent(agent);
        }
    }


    private BluezAgentManager getBluetoothAgentManager() {
        if (bluetoothAgentManager == null) {
            AgentManager1 agentManager1 = DbusHelper.getRemoteObject(dbusConnection, "/org/bluez", AgentManager1.class);
            if (agentManager1 != null) {
                bluetoothAgentManager = new BluezAgentManager(agentManager1, adapter, agentManager1.getObjectPath(), dbusConnection);
            }
        }
        return bluetoothAgentManager;
    }

    private void registerInterfaceAddedHandler(@NotNull AbstractInterfacesAddedHandler handler) throws DBusException {
        dbusConnection.addSigHandler(handler.getImplementationClass(), handler);
    }

    /**
     * Scan for any peripheral that is advertising.
     */
    @SuppressWarnings("unused")
    public void scanForPeripherals() {
        initScanFilters();
        normalScanActive = true;
        startScanning();
    }

    /**
     * Scan for peripherals that advertise at least one of the specified service UUIDs.
     *
     * @param serviceUUIDs an array of service UUIDs
     */
    @SuppressWarnings("unused")
    public void scanForPeripheralsWithServices(final UUID[] serviceUUIDs) {
        initScanFilters();
        scanUUIDs = convertUUIDArrayToStringArray(serviceUUIDs);
        normalScanActive = true;
        startScanning();
    }

    /**
     * Scan for peripherals with advertisement names containing any of the specified peripheral names.
     * <p>
     * Substring matching is used so only a partial peripheral names has to be supplied.
     *
     * @param peripheralNames array of partial peripheral names
     */
    @SuppressWarnings("unused")
    public void scanForPeripheralsWithNames(final String[] peripheralNames) {
        initScanFilters();
        scanPeripheralNames = peripheralNames;
        normalScanActive = true;
        startScanning();
    }

    /**
     * Scan for peripherals that have any of the specified peripheral mac addresses.
     *
     * @param peripheralAddresses array of peripheral mac addresses to scan for
     */
    @SuppressWarnings("unused")
    public void scanForPeripheralsWithAddresses(final String[] peripheralAddresses) {
        initScanFilters();
        scanPeripheralAddresses = peripheralAddresses;
        normalScanActive = true;
        startScanning();
    }

    /**
     * Stop scanning for peripherals.
     */
    public void stopScan() {
        normalScanActive = false;
        stopScanning();
    }

    private void initScanFilters() {
        scanPeripheralNames = new String[0];
        scanPeripheralAddresses = new String[0];
        scanUUIDs = new String[0];
        scanFilters.clear();
        setBasicFilters();
    }

    private void setBasicFilters() {
        scanFilters.put(DiscoveryFilter.Transport, DiscoveryTransport.LE);
        scanFilters.put(DiscoveryFilter.RSSI, DISCOVERY_RSSI_THRESHOLD);
        scanFilters.put(DiscoveryFilter.DuplicateData, true);
    }

    private String[] convertUUIDArrayToStringArray(final UUID[] uuidArray) {
        // Convert UUID array to string array
        ArrayList<String> uuidStrings = new ArrayList<>();
        if (uuidArray != null) {
            for (UUID uuid : uuidArray) {
                uuidStrings.add(uuid.toString());
            }
        }
        return uuidStrings.toArray(new String[0]);
    }

    private boolean notAllowedByFilter(ScanResult scanResult) {
        // Check if peripheral name filter is set
        if (scanPeripheralNames.length > 0) {
            for (String name : scanPeripheralNames) {
                if (scanResult.getName() != null && scanResult.getName().contains(name)) {
                    return false;
                }
            }
            return true;
        }

        // Check if peripheral address filter is set
        if (scanPeripheralAddresses.length > 0) {
            for (String name : scanPeripheralAddresses) {
                if (scanResult.getAddress() != null && scanResult.getAddress().equals(name)) {
                    return false;
                }
            }
            return true;
        }

        // Check if peripheral uuid filter is set
        if (scanUUIDs.length > 0) {
            Set<String> scanResultUUIDs = new HashSet<>(Arrays.asList(scanResult.getUuids()));
            for (String uuid : scanUUIDs) {
                if (scanResultUUIDs.contains(uuid)) {
                    return false;
                }
            }
            return true;
        }

        // No filter set
        return false;
    }

    private void onFoundReconnectionPeripheral(BluetoothPeripheral peripheral) {
        final String peripheralAddress = peripheral.getAddress();
        final BluetoothPeripheralCallback peripheralCallback = reconnectCallbacks.get(peripheralAddress);

        logger.info(String.format("found peripheral to autoconnect '%s'", peripheralAddress));
        autoScanActive = false;
        stopScanning();

        reconnectPeripheralAddresses.remove(peripheralAddress);
        reconnectCallbacks.remove(peripheralAddress);
        unconnectedPeripherals.remove(peripheralAddress);

        // Make sure we have a valid BluezDevice object and refresh the name
        if (peripheral.device == null) {
            peripheral.device = getDeviceByAddress(adapter, peripheralAddress);
            peripheral.deviceName = peripheral.device.getName();
        }

        connectPeripheral(peripheral, peripheralCallback);

        if (!reconnectPeripheralAddresses.isEmpty()) {
            autoScanActive = true;
            startScanning();
        } else if (normalScanActive) {
            startScanning();
        }
    }

    private void onScanResult(BluetoothPeripheral peripheral, ScanResult scanResult) {
        // Check first if we are autoconnecting to this peripheral
        if (reconnectPeripheralAddresses.contains(scanResult.getAddress())) {
            onFoundReconnectionPeripheral(peripheral);
            return;
        }

        if (normalScanActive && isScanning && !isStoppingScan) {
            // Implement SCANOPTION_NO_NULL_NAMES
            if (scanOptions.contains(SCANOPTION_NO_NULL_NAMES) && (scanResult.getName() == null)) return;

            if (notAllowedByFilter(scanResult)) return;

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onDiscoveredPeripheral(peripheral, scanResult);
                } else {
                    logger.severe("bluetoothCentralCallback is null");
                }
            });
        }
    }

    private final AbstractInterfacesAddedHandler interfacesAddedHandler = new AbstractInterfacesAddedHandler() {
        @Override
        public void handle(ObjectManager.InterfacesAdded interfacesAdded) {
            final String path = interfacesAdded.getPath();
            interfacesAdded.getInterfaces().forEach((key, value) -> {
                if (key.equalsIgnoreCase(BLUEZ_DEVICE_INTERFACE)) {
                    handleInterfaceAddedForDevice(path, value);
                }
            });
        }
    };

    private void handleInterfaceAddedForDevice(String path, Map<String, Variant<?>> value) {
        final String deviceAddress;
        final String deviceName;
        final int rssi;
        ArrayList<String> serviceUUIDs = null;

        // Grab address
        if ((value.get(PROPERTY_ADDRESS) != null) && (value.get(PROPERTY_ADDRESS).getValue() instanceof String)) {
            deviceAddress = (String) value.get(PROPERTY_ADDRESS).getValue();
        } else {
            // There MUST be an address, so if not bail out...
            return;
        }

        // Get the device
        final BluezDevice device = getDeviceByAddress(adapter, deviceAddress);
        if (device == null) {
            // Something is very wrong, don't handle this signal
            return;
        }

        // Grab name
        if ((value.get(PROPERTY_NAME) != null) && (value.get(PROPERTY_NAME).getValue() instanceof String)) {
            deviceName = (String) value.get(PROPERTY_NAME).getValue();
        } else {
            deviceName = null;
        }

        // Grab service UUIDs
        if ((value.get(PROPERTY_SERVICE_UUIDS) != null) && (value.get(PROPERTY_SERVICE_UUIDS).getValue() instanceof ArrayList)) {
            serviceUUIDs = (ArrayList) value.get(PROPERTY_SERVICE_UUIDS).getValue();
        }

        // Grab RSSI
        if ((value.get(PROPERTY_RSSI) != null) && (value.get(PROPERTY_RSSI).getValue() instanceof Short)) {
            rssi = (Short) value.get(PROPERTY_RSSI).getValue();
        } else {
            rssi = -100;
        }

        // Convert the service UUIDs
        final String[] finalServiceUUIDs;
        if (serviceUUIDs != null) {
            finalServiceUUIDs = serviceUUIDs.toArray(new String[0]);
        } else {
            finalServiceUUIDs = null;
        }

        // Get manufacturer data
        final Map<Integer, byte[]> manufacturerData = new HashMap<>();
        if ((value.get(PROPERTY_MANUFACTURER_DATA) != null) && (value.get(PROPERTY_MANUFACTURER_DATA).getValue() instanceof Map)) {
            final DBusMap<UInt16, Variant<byte[]>> mdata = (DBusMap) value.get(PROPERTY_MANUFACTURER_DATA).getValue();
            mdata.forEach((k, v) -> manufacturerData.put(k.intValue(), v.getValue()));
        }

        // Get service data
        final Map<String, byte[]> serviceData = new HashMap<>();
        if ((value.get(PROPERTY_SERVICE_DATA) != null) && (value.get(PROPERTY_SERVICE_DATA).getValue() instanceof Map)) {
            final DBusMap<String, Variant<byte[]>> sdata = (DBusMap) value.get(PROPERTY_SERVICE_DATA).getValue();
            sdata.forEach((k, v) -> serviceData.put(k, v.getValue()));
        }

        // Create ScanResult
        final ScanResult scanResult = new ScanResult(deviceName, deviceAddress, finalServiceUUIDs, rssi, manufacturerData, serviceData);
        final BluetoothPeripheral peripheral = getPeripheral(deviceAddress);
        scanResultCache.put(deviceAddress, scanResult);
        onScanResult(peripheral, scanResult);
    }

    private final AbstractPropertiesChangedHandler propertiesChangedHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(Properties.PropertiesChanged propertiesChanged) {
            switch (propertiesChanged.getInterfaceName()) {
                case BLUEZ_DEVICE_INTERFACE:
                    // If we are not scanning, we ignore device propertiesChanged
                    if ((!isScanning) || isStoppingScan) return;

                    // Get the BluezDevice object
                    final BluezDevice bluezDevice = getDeviceByPath(adapter, propertiesChanged.getPath());
                    if (bluezDevice == null) return;

                    // Handle the propertiesChanged object
                    handlePropertiesChangedForDeviceWhenScanning(bluezDevice, propertiesChanged.getPropertiesChanged());
                    break;
                case BLUEZ_ADAPTER_INTERFACE:
                    propertiesChanged.getPropertiesChanged().forEach((propertyName, value) -> handlePropertiesChangedForAdapter(propertyName, value));
                    break;
                default:
            }
        }
    };

    void handleSignal(Properties.PropertiesChanged propertiesChanged) {
        propertiesChangedHandler.handle(propertiesChanged);
    }

    @SuppressWarnings("unchecked")
    private void handlePropertiesChangedForDeviceWhenScanning(@NotNull BluezDevice bluezDevice, Map<String, Variant<?>> propertiesChanged) {
        final String deviceAddress = bluezDevice.getAddress();
        ScanResult scanResult = getScanResult(deviceAddress);

        // See if we have a cached scanResult, if not create a new one
        if (scanResult == null) {
            scanResult = getScanResultFromDevice(bluezDevice);
            if (scanResult == null) return;
            scanResultCache.put(deviceAddress, scanResult);
        }

        // Update the scanResult
        Set<String> keys = propertiesChanged.keySet();
        if (keys.contains(PROPERTY_RSSI)) {
            scanResult.setRssi((Short) propertiesChanged.get(PROPERTY_RSSI).getValue());
        }

        if (keys.contains(PROPERTY_MANUFACTURER_DATA)) {
            final Map<Integer, byte[]> manufacturerData = new HashMap<>();
            final DBusMap<UInt16, Variant<byte[]>> mdata = (DBusMap<UInt16, Variant<byte[]>>) propertiesChanged.get(PROPERTY_MANUFACTURER_DATA).getValue();
            mdata.forEach((k, v) -> manufacturerData.put(k.intValue(), v.getValue()));
            scanResult.setManufacturerData(manufacturerData);
        }

        if (keys.contains(PROPERTY_SERVICE_DATA)) {
            final Map<String, byte[]> serviceData = new HashMap<>();
            final DBusMap<String, Variant<byte[]>> sdata = (DBusMap<String, Variant<byte[]>>) propertiesChanged.get(PROPERTY_SERVICE_DATA).getValue();
            sdata.forEach((k, v) -> serviceData.put(k, v.getValue()));
            scanResult.setServiceData(serviceData);
        }

        final BluetoothPeripheral peripheral = getPeripheral(deviceAddress);
        onScanResult(peripheral, scanResult);
    }

    @Nullable
    private ScanResult getScanResultFromDevice(@NotNull BluezDevice bluezDevice) {
        final String deviceName;
        final String[] serviceUUIDs;
        final int rssi;
        final Map<Integer, byte[]> manufacturerData;
        final Map<String, byte[]> serviceData;
        try {
            deviceName = bluezDevice.getName();
            serviceUUIDs = bluezDevice.getUuids();
            rssi = bluezDevice.getRssi();
            manufacturerData = bluezDevice.getManufacturerData();
            serviceData = bluezDevice.getServiceData();
        } catch (Exception e) {
            return null;
        }
        return new ScanResult(deviceName, bluezDevice.getAddress(), serviceUUIDs, rssi, manufacturerData, serviceData);
    }

    private void handlePropertiesChangedForAdapter(String propertyName, Variant<?> value) {
        switch (propertyName) {
            case PROPERTY_DISCOVERING:
                isScanning = (Boolean) value.getValue();
                if (isScanning) isStoppingScan = false;
                logger.info(String.format("scan %s", isScanning ? "started" : "stopped"));

                if (!isScanning) {
                    // Clear the cached BluezDevices, BluetoothPeripherals and ScanResults
                    scannedPeripherals.clear();
                    scannedBluezDevices.clear();
                    scanResultCache.clear();
                }
                if (currentCommand.equalsIgnoreCase(PROPERTY_DISCOVERING)) {
                    callBackHandler.postDelayed(this::completedCommand, 300L);
                }
                break;
            case PROPERTY_POWERED:
                isPowered = (Boolean) value.getValue();
                logger.info(String.format("powered %s", isPowered ? "on" : "off"));

                if (currentCommand.equalsIgnoreCase(PROPERTY_POWERED)) {
                    callBackHandler.postDelayed(this::completedCommand, 200L);
                }
                break;
            default:
        }
    }

    private void setScanFilter(@NotNull Map<DiscoveryFilter, Object> filter) throws BluezInvalidArgumentsException, BluezNotReadyException, BluezNotSupportedException, BluezFailedException {
        Map<String, Variant<?>> filters = new LinkedHashMap<>();
        for (Map.Entry<DiscoveryFilter, Object> entry : filter.entrySet()) {
            if (!entry.getKey().getValueClass().isInstance(entry.getValue())) {
                throw new BluezInvalidArgumentsException("Filter value not of required type " + entry.getKey().getValueClass());
            }
            if (entry.getValue() instanceof Enum<?>) {
                filters.put(entry.getKey().name(), new Variant<>(entry.getValue().toString()));
            } else {
                filters.put(entry.getKey().name(), new Variant<>(entry.getValue()));
            }
        }
        adapter.setDiscoveryFilter(filters);
    }

    /*
     * Start a continuous scan with scan filters set to find all devices.
     * This will try to start a scan even if one is running already
     */
    private void startScanning() {
        // Make sure the adapter is on
        if (!isPowered) return;

        boolean result = commandQueue.add(() -> {

            // Just in case, set isStoppingScan to false
            isStoppingScan = false;

            // If we are already scanning then complete the command immediately
            isScanning = adapter.isDiscovering();
            if (isScanning) {
                //               logger.info(TAG, "Already scanning so completing command");
                completedCommand();
                return;
            }

            // Set scan filter. We have to do this before every scan since Bluez doesn't remember this
            try {
                setScanFilter(scanFilters);
            } catch (BluezInvalidArgumentsException | BluezNotReadyException | BluezFailedException | BluezNotSupportedException e) {
                logger.severe("Error setting scan filer");
                logger.severe(e.toString());
            }

            // Start the discovery
            try {
                //               logger.info(TAG, "Trying to start scanning");
                currentCommand = PROPERTY_DISCOVERING;
                adapter.startDiscovery();
                startScanTimer();
            } catch (BluezFailedException e) {
                logger.severe("Could not start discovery (failed)");
                completedCommand();
            } catch (BluezNotReadyException e) {
                logger.severe("Could not start discovery (not ready)");
                completedCommand();
            } catch (DBusExecutionException e) {
                // Still need to see what this could be
                logger.severe("Error starting scanner");
                logger.severe(e.getMessage());
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.severe(ENQUEUE_ERROR);
        }

    }

    /*
     * Stop the scanner
     */
    private void stopScanning() {
        // Make sure the adapter is on
        if (!isPowered) return;

        // Set flag to true in order to stop sending scan results
        isStoppingScan = true;

        boolean result = commandQueue.add(() -> {
            // Check if we are scanning
            isScanning = adapter.isDiscovering();
            if (!isScanning) {
                completedCommand();
                return;
            }

            // Stop the discovery
            try {
                currentCommand = PROPERTY_DISCOVERING;
                cancelTimeoutTimer();
                adapter.stopDiscovery();
            } catch (BluezNotReadyException e) {
                logger.severe("Could not stop discovery (not ready)");
                completedCommand();
            } catch (BluezFailedException e) {
                logger.severe("Could not stop discovery (failed)");
                completedCommand();
            } catch (BluezNotAuthorizedException e) {
                logger.severe("Could not stop discovery (not authorized)");
                completedCommand();
            } catch (DBusExecutionException e) {
                // Usually this is the exception "No discovery started"
                logger.severe(e.getMessage());
                if (e.getMessage().equalsIgnoreCase("No discovery started")) {
                    logger.severe("Could not stop scan, because we are not scanning!");
                    isStoppingScan = false;
                    isScanning = false;   // This shouldn't be needed but seems it is...
                } else if (e.getMessage().equalsIgnoreCase("Operation already in progress")) {
                    logger.severe("a stopDiscovery is in progress");
                }
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.severe(ENQUEUE_ERROR);
        }
    }


    private void startScanTimer() {
        cancelTimeoutTimer();

        this.timeoutRunnable = () -> {
            stopScanning();

            // Introduce gap between continuous scans
            try {
                Thread.sleep(SCAN_INTERVAL - SCAN_WINDOW);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            startScanning();
        };
        timeoutHandler.postDelayed(timeoutRunnable, SCAN_WINDOW);
    }

    /**
     * Cancel the scan timeout timer
     */
    private void cancelTimeoutTimer() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    /**
     * Turn on the Bluetooth adaptor
     */
    @SuppressWarnings("unused")
    public void adapterOn() {
        boolean result = commandQueue.add(() -> {

            if (!adapter.isPowered()) {
                logger.info("Turning on adapter");
                currentCommand = PROPERTY_POWERED;
                adapter.setPowered(true);
            } else {
                // If it is already on we won't receive a callback so just complete the command
                logger.info("Adapter already on");
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.severe(ENQUEUE_ERROR);
        }
    }


    /**
     * Turn off the Bluetooth adaptor
     */
    @SuppressWarnings("unused")
    public void adapterOff() {
        boolean result = commandQueue.add(() -> {
            if (adapter.isPowered()) {
                logger.info("Turning off adapter");
                currentCommand = PROPERTY_POWERED;
                adapter.setPowered(false);
            } else {
                // If it is already off we won't receive a callback so just complete the command
                logger.info("Adapter already off");
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.severe(ENQUEUE_ERROR);
        }
    }

    /**
     * Connect to a known peripheral immediately. The peripheral must have been found by scanning for this call to succeed. This method will time out in max 30 seconds on most phones and in 5 seconds on Samsung phones.
     * If the peripheral is already connected, no connection attempt will be made. This method is asynchronous and there can be only one outstanding connect.
     *
     * @param peripheral         BLE peripheral to connect with
     * @param peripheralCallback the peripheral callback to use
     */
    public void connectPeripheral(final BluetoothPeripheral peripheral, final BluetoothPeripheralCallback peripheralCallback) {
        // Make sure peripheral is valid
        if (peripheral == null) {
            logger.info("no valid peripheral specified, aborting connection");
            return;
        }
        peripheral.setPeripheralCallback(peripheralCallback);

        // Check if we are already connected
        if (connectedPeripherals.containsKey(peripheral.getAddress())) {
            logger.warning(String.format("WARNING: Already connected to %s'", peripheral.getAddress()));
            return;
        }

        // Check if we already have an outstanding connection request for this peripheral
        if (unconnectedPeripherals.containsKey(peripheral.getAddress())) {
            logger.warning(String.format("WARNING: Already connecting to %s'", peripheral.getAddress()));
            return;
        }

        // Make sure we have BluezDevice
        if (peripheral.device == null) {
            logger.warning(String.format("WARNING: Peripheral '%s' doesn't have Bluez device", peripheral.getAddress()));
            return;
        }

        // Some adapters have issues with (dis)connecting while scanning, so stop scan first
        stopScanning();

        unconnectedPeripherals.put(peripheral.getAddress(), peripheral);
        boolean result = commandQueue.add(() -> {
            currentDeviceAddress = peripheral.getAddress();
            currentCommand = PROPERTY_CONNECTED;
            peripheral.connect();
        });

        if (result) {
            nextCommand();
        } else {
            logger.severe(ENQUEUE_ERROR);
        }
    }

    /**
     * Automatically connect to a peripheral when it is advertising. It is not necessary to scan for the peripheral first. This call is asynchronous and will not time out.
     *
     * @param peripheral         the peripheral
     * @param peripheralCallback the peripheral callback to use
     * @return true if all arguments were valid, otherwise false
     */
    @SuppressWarnings("UnusedReturnValue,unused")
    public boolean autoConnectPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull BluetoothPeripheralCallback peripheralCallback) {
        final String peripheralAddress = peripheral.getAddress();
        if (reconnectPeripheralAddresses.contains(peripheralAddress)) return false;

        reconnectPeripheralAddresses.add(peripheralAddress);
        reconnectCallbacks.put(peripheralAddress, peripheralCallback);
        unconnectedPeripherals.put(peripheralAddress, peripheral);

        startAutoConnectScan();
        return true;
    }

    /**
     * Autoconnect to a batch of peripherals.
     * <p>
     * Use this function to autoConnect to a batch of peripherals, instead of calling autoConnect on each of them.
     *
     * @param batch the map of peripherals and their callbacks to autoconnect to
     */
    @SuppressWarnings("unused")
    public void autoConnectPeripheralsBatch(Map<BluetoothPeripheral, BluetoothPeripheralCallback> batch) {
        for (Map.Entry<BluetoothPeripheral, BluetoothPeripheralCallback> entry : batch.entrySet()) {
            String peripheralAddress = entry.getKey().getAddress();
            reconnectPeripheralAddresses.add(peripheralAddress);
            reconnectCallbacks.put(peripheralAddress, entry.getValue());
            unconnectedPeripherals.put(peripheralAddress, entry.getKey());
        }

        if (!reconnectPeripheralAddresses.isEmpty()) {
            startAutoConnectScan();
        }
    }

    private void startAutoConnectScan() {
        autoScanActive = true;
        if (!isScanning) {
            setBasicFilters();
            startScanning();
        }
    }

    /**
     * Cancel an active or pending connection for a peripheral.
     *
     * @param peripheral the peripheral
     */
    @SuppressWarnings("unused")
    public void cancelConnection(final BluetoothPeripheral peripheral) {
        if (peripheral == null) return;

        if (peripheral.getState() == STATE_CONNECTED) {
            // Some adapters have issues with (dis)connecting while scanning, so stop scan first
            stopScanning();

            // Queue the low level disconnect
            boolean result = commandQueue.add(() -> {
                currentDeviceAddress = peripheral.getAddress();
                currentCommand = PROPERTY_CONNECTED;
                peripheral.disconnectBluezDevice();
            });

            if (result) {
                nextCommand();
            } else {
                logger.severe(ENQUEUE_ERROR);
            }
            return;
        }

        // We might be autoconnecting to this peripheral
        String peripheralAddress = peripheral.getAddress();
        if (reconnectPeripheralAddresses.contains(peripheralAddress)) {
            reconnectPeripheralAddresses.remove(peripheralAddress);
            reconnectCallbacks.remove(peripheralAddress);

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onDisconnectedPeripheral(peripheral, 0);
                }
            });
        }
    }

    /**
     * Remove bond for a peripheral
     *
     * @param peripheralAddress the address of the peripheral
     */
    @SuppressWarnings("unused")
    public boolean removeBond(String peripheralAddress) {
        BluezDevice bluezDevice = getDeviceByAddress(adapter, peripheralAddress);
        if (bluezDevice == null) return false;
        return removeDevice(bluezDevice);
    }

    /**
     * Get the list of connected peripherals.
     *
     * @return list of connected peripherals
     */
    @SuppressWarnings("unused")
    public List<BluetoothPeripheral> getConnectedPeripherals() {
        return new ArrayList<>(connectedPeripherals.values());
    }

    /**
     * Get a peripheral object matching the specified mac address.
     *
     * @param peripheralAddress mac address
     * @return a BluetoothPeripheral object matching the specified mac address or null if it was not found
     */
    @SuppressWarnings("unused")
    public BluetoothPeripheral getPeripheral(String peripheralAddress) {
        if (!checkBluetoothAddress(peripheralAddress)) {
            logger.severe(String.format("%s is not a valid address. Make sure all alphabetic characters are uppercase.", peripheralAddress));
            return null;
        }

        if (scannedPeripherals.containsKey(peripheralAddress)) {
            return scannedPeripherals.get(peripheralAddress);
        } else if (connectedPeripherals.containsKey(peripheralAddress)) {
            return connectedPeripherals.get(peripheralAddress);
        } else if (unconnectedPeripherals.containsKey(peripheralAddress)) {
            return unconnectedPeripherals.get(peripheralAddress);
        } else {
            BluezDevice bluezDevice = scannedBluezDevices.get(getPath(adapter, peripheralAddress));
            BluetoothPeripheral bluetoothPeripheral = new BluetoothPeripheral(this, bluezDevice, bluezDevice != null ? bluezDevice.getName() : null, peripheralAddress, internalCallback, null, callBackHandler);
            scannedPeripherals.put(peripheralAddress, bluetoothPeripheral);
            return bluetoothPeripheral;
        }
    }

    private ScanResult getScanResult(String peripheralAddress) {
        return scanResultCache.get(peripheralAddress);
    }

    /**
     * Set a fixed PIN code for a peripheral that asks fir a PIN code during bonding.
     * <p>
     * This PIN code will be used to programmatically bond with the peripheral when it asks for a PIN code.
     * Note that this only works for devices with a fixed PIN code.
     *
     * @param peripheralAddress the address of the peripheral
     * @param pin               the 6 digit PIN code as a string, e.g. "123456"
     * @return true if the pin code and peripheral address are valid and stored internally
     */
    @SuppressWarnings("unused")
    public boolean setPinCodeForPeripheral(String peripheralAddress, String pin) {
        if (!checkBluetoothAddress(peripheralAddress)) {
            logger.severe(String.format("%s is not a valid address. Make sure all alphabetic characters are uppercase.", peripheralAddress));
            return false;
        }

        if (pin == null) {
            logger.severe("pin code is null");
            return false;
        }

        if (pin.length() != 6) {
            logger.severe(String.format("%s is not 6 digits long", pin));
            return false;
        }

        pinCodes.put(peripheralAddress, pin);
        return true;
    }

    /**
     * Validate a String Bluetooth address, such as "00:43:A8:23:10:F0"
     * <p>Alphabetic characters must be uppercase to be valid.
     *
     * @param address Bluetooth address as string
     * @return true if the address is valid, false otherwise
     */
    private boolean checkBluetoothAddress(String address) {
        if (address == null || address.length() != ADDRESS_LENGTH) {
            return false;
        }
        for (int i = 0; i < ADDRESS_LENGTH; i++) {
            char c = address.charAt(i);
            switch (i % 3) {
                case 0:
                case 1:
                    if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
                        // hex character, OK
                        break;
                    }
                    return false;
                case 2:
                    if (c == ':') {
                        break;  // OK
                    }
                    return false;
            }
        }
        return true;
    }


    /**
     * The current command has been completed, move to the next command in the queue (if any)
     */
    private void completedCommand() {
        commandQueue.poll();
        commandQueueBusy = false;
        currentCommand = "";
        nextCommand();
    }

    /**
     * Execute the next command in the subscribe queue.
     * A queue is used because the calls have to be executed sequentially.
     * If the read or write fails, the next command in the queue is executed.
     */
    private void nextCommand() {
        synchronized (this) {
            // If there is still a command being executed then bail out
            if (commandQueueBusy) {
                return;
            }

            // Execute the next command in the queue
            final Runnable bluetoothCommand = commandQueue.peek();
            if (bluetoothCommand != null) {
                commandQueueBusy = true;

                queueHandler.post(() -> {
                    try {
                        bluetoothCommand.run();
                    } catch (Exception ex) {
                        logger.warning("ERROR: Command exception for central");
                        logger.warning(ex.getMessage());
                        completedCommand();
                    }
                });
            }
        }
    }

    private @NotNull List<BluezAdapter> findBluetoothAdapters() {
        final Map<String, BluezAdapter> bluetoothAdaptersByAdapterName = new LinkedHashMap<>();

        Set<String> nodes = DbusHelper.findNodes(dbusConnection, BLUEZ_PATH);
        for (String hci : nodes) {
            Adapter1 adapter1 = DbusHelper.getRemoteObject(dbusConnection, BLUEZ_PATH + "/" + hci, Adapter1.class);
            if (adapter1 != null) {
                BluezAdapter bluetoothAdapter = new BluezAdapter(adapter1, BLUEZ_PATH + "/" + hci, dbusConnection);
                bluetoothAdaptersByAdapterName.put(hci, bluetoothAdapter);
            }
        }

        return new ArrayList<>(bluetoothAdaptersByAdapterName.values());
    }

    private @Nullable BluezDevice getDeviceByPath(@NotNull BluezAdapter adapter, @NotNull String devicePath) {
        BluezDevice bluezDevice = scannedBluezDevices.get(devicePath);
        if (bluezDevice == null) {
            Device1 device = DbusHelper.getRemoteObject(dbusConnection, devicePath, Device1.class);
            if (device != null) {
                bluezDevice = new BluezDevice(device, adapter, devicePath, dbusConnection);
                scannedBluezDevices.put(devicePath, bluezDevice);
            }
        }
        return bluezDevice;
    }

    private @Nullable BluezDevice getDeviceByAddress(BluezAdapter adapter, String deviceAddress) {
        return getDeviceByPath(adapter, getPath(adapter, deviceAddress));
    }

    @NotNull
    private String getPath(BluezAdapter adapter, String deviceAddress) {
        return adapter.getDbusPath() + "/dev_" + deviceAddress.replace(":", "_");
    }

    /*
     * Function to clean up device from Bluetooth cache
     */
    protected void removeDevice(@NotNull final BluetoothPeripheral peripheral) {
        BluezDevice bluetoothDevice = getDeviceByAddress(adapter, peripheral.getAddress());
        if (bluetoothDevice == null) return;

        boolean isBonded = peripheral.isPaired();
        logger.info(String.format("removing peripheral %s (%s)", peripheral.getAddress(), isBonded ? "BONDED" : "BOND_NONE"));
        removeDevice(bluetoothDevice);
    }

    private boolean removeDevice(BluezDevice bluetoothDevice) {
        if (adapter == null) {
            return false;
        }

        try {
            Device1 rawDevice = bluetoothDevice.getRawDevice();
            if (rawDevice != null) {
                adapter.removeDevice(rawDevice);
                return true;
            }
            return false;
        } catch (BluezFailedException | BluezInvalidArgumentsException e) {
            logger.severe("Error removing device");
            return false;
        }
    }
}

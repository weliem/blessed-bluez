package com.welie.blessed;

import com.welie.blessed.bluez.*;
import org.bluez.Adapter1;
import org.bluez.Device1;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.handlers.AbstractInterfacesAddedHandler;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static com.welie.blessed.BluetoothPeripheral.*;

public class BluetoothCentral {
    private static final String TAG = BluetoothCentral.class.getSimpleName();
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
    private int scanCounter = 0;
    private final Map<DiscoveryFilter, Object> scanFilters = new EnumMap<>(DiscoveryFilter.class);
    private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();
    private String currentCommand;
    private String currentDeviceAddress;
    private final Map<String, BluetoothPeripheral> connectedPeripherals = new ConcurrentHashMap<>();
    private final Map<String, BluetoothPeripheral> unconnectedPeripherals = new ConcurrentHashMap<>();
    private @NotNull String[] scanPeripheralNames = new String[0];
    private @NotNull String[] scanPeripheralAddresses = new String[0];
    private final List<String> reconnectPeripheralAddresses = new ArrayList<>();
    private final Map<String, BluetoothPeripheralCallback> reconnectCallbacks = new ConcurrentHashMap<>();

    private static final int ADDRESS_LENGTH = 17;
    private static final short DISCOVERY_RSSI_THRESHOLD = -70;

    // Scan in intervals. Make sure it is less than 10seconds to avoid issues with Bluez internal scanning
    private static final long SCAN_INTERNAL = TimeUnit.SECONDS.toMillis(8);

    // Bluez Adapter property strings
    private static final String PROPERTY_DISCOVERING = "Discovering";
    private static final String PROPERTY_POWERED = "Powered";

    // Bluez interface names
    private static final String BLUEZ_ADAPTER_INTERFACE = "org.bluez.Adapter1";

    private static final String ENQUEUE_ERROR = "ERROR: Could not enqueue stop scanning command";

    private final InternalCallback internalCallback = new InternalCallback() {
        @Override
        public void connected(final BluetoothPeripheral device) {
            final String deviceAddress = device.getAddress();
            connectedPeripherals.put(deviceAddress, device);
            unconnectedPeripherals.remove(deviceAddress);

            // Complete the 'connect' command if this was the device we were connecting
            if (currentCommand.equalsIgnoreCase(PROPERTY_CONNECTED) && deviceAddress.equalsIgnoreCase(currentDeviceAddress)) {
                completedCommand();
            }

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onConnectedPeripheral(device);
                }
            });
        }

        @Override
        public void servicesDiscovered(final BluetoothPeripheral device) {
            HBLogger.i(TAG, "service discovery succeeded");
        }

        @Override
        public void serviceDiscoveryFailed(final BluetoothPeripheral device) {
            HBLogger.i(TAG, "Service discovery failed");
        }

        @Override
        public void connectFailed(final BluetoothPeripheral device) {
            final String deviceAddress = device.getAddress();
            connectedPeripherals.remove(deviceAddress);
            unconnectedPeripherals.remove(deviceAddress);

            // Complete the 'connect' command if this was the device we were connecting
            if (currentCommand.equalsIgnoreCase(PROPERTY_CONNECTED) && deviceAddress.equalsIgnoreCase(currentDeviceAddress)) {
                completedCommand();
            }

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onConnectionFailed(device, 0);
                }
            });
        }

        @Override
        public void disconnected(final BluetoothPeripheral device) {
            final String deviceAddress = device.getAddress();
            connectedPeripherals.remove(deviceAddress);
            unconnectedPeripherals.remove(deviceAddress);

            // Remove unbonded devices from DBsus to make setting notifications work on reconnection (Bluez issue)
            if (!device.isPaired()) {
                removeDevice(device);
            }

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onDisconnectedPeripheral(device, 0);
                }
            });
        }
    };

    public BluetoothCentral(@NotNull BluetoothCentralCallback bluetoothCentralCallback, @Nullable Handler handler) {
        this.bluetoothCentralCallback = bluetoothCentralCallback;
        this.callBackHandler = (handler != null) ? handler : new Handler("Central-callBackHandler");

        try {
            // Connect to the DBus
            dbusConnection = DBusConnection.newConnection(DBusConnection.DBusBusType.SYSTEM);

            // Find all adapters and pick one if there are more than one
            List<BluezAdapter> adapters = scanForBluetoothAdapters();
            if (!adapters.isEmpty()) {
                HBLogger.i(TAG, String.format("found %d bluetooth adapter(s)", adapters.size()));

                // Take the adapter with the highest number
                adapter = adapters.get(adapters.size() - 1);
                HBLogger.i(TAG, "using adapter " + adapter.getDeviceName());

                // Make sure the adapter is powered on
                isPowered = adapter.isPowered();
                if (!isPowered) {
                    HBLogger.i(TAG, "adapter not on, so turning it on now");
                    adapterOn();
                }
            } else {
                HBLogger.e(TAG, "no bluetooth adaptors found");
                return;
            }

            BluezSignalHandler.createInstance(dbusConnection).addCentral(this);
            registerInterfaceAddedHandler(interfacesAddedHandler);
        } catch (DBusException e) {
            e.printStackTrace();
        }
    }

    private void registerInterfaceAddedHandler(@NotNull AbstractInterfacesAddedHandler handler) throws DBusException {
        dbusConnection.addSigHandler(handler.getImplementationClass(), handler);
    }

    @SuppressWarnings("unused")
    public void scanForPeripherals() {
        initScanFilters();
        normalScanActive = true;
        startScanning();
    }

    @SuppressWarnings("unused")
    public void scanForPeripheralsWithServices(final UUID[] serviceUUIDs) {
        initScanFilters();
        String[] scanUUIDs = convertUUIDArrayToStringArray(serviceUUIDs);
        if (scanUUIDs.length > 0) {
            scanFilters.put(DiscoveryFilter.UUIDs, scanUUIDs);
        }
        normalScanActive = true;
        startScanning();
    }

    @SuppressWarnings("unused")
    public void scanForPeripheralsWithNames(final String[] peripheralNames) {
        initScanFilters();
        scanPeripheralNames = peripheralNames;
        normalScanActive = true;
        startScanning();
    }

    @SuppressWarnings("unused")
    public void scanForPeripheralsWithAddresses(final String[] peripheralAddresses) {
        initScanFilters();
        scanPeripheralAddresses = peripheralAddresses;
        normalScanActive = true;
        startScanning();
    }

    public void stopScan() {
        normalScanActive = false;
        stopScanning();
    }

    private void initScanFilters() {
        scanPeripheralNames = new String[0];
        scanPeripheralAddresses = new String[0];
        scanFilters.clear();
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
        return false;
    }

    private void onFoundReconnectionPeripheral(BluetoothPeripheral peripheral) {
        final String deviceAddress = peripheral.getAddress();
        final BluetoothPeripheralCallback peripheralCallback = reconnectCallbacks.get(deviceAddress);

        HBLogger.i(TAG, String.format("found peripheral to autoconnect '%s'", deviceAddress));
        autoScanActive = false;
        stopScanning();

        reconnectPeripheralAddresses.remove(deviceAddress);
        reconnectCallbacks.remove(deviceAddress);
        unconnectedPeripherals.remove(deviceAddress);

        connectPeripheral(peripheral, peripheralCallback);

        if (reconnectPeripheralAddresses.size() > 0) {
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
            if (notAllowedByFilter(scanResult)) return;

            callBackHandler.post(() -> {
                if (bluetoothCentralCallback != null) {
                    bluetoothCentralCallback.onDiscoveredPeripheral(peripheral, scanResult);
                } else {
                    HBLogger.e(TAG, "bluetoothCentralCallback is null");
                }
            });
        }
    }

    private final AbstractInterfacesAddedHandler interfacesAddedHandler = new AbstractInterfacesAddedHandler() {
        @Override
        public void handle(ObjectManager.InterfacesAdded interfacesAdded) {
            interfacesAdded.getInterfaces().forEach((key, value) -> {
                if (key.equalsIgnoreCase(BLUEZ_DEVICE_INTERFACE)) {
                    handleInterfaceAddedForDevice(value);
                }
            });
        }
    };

    private void handleInterfaceAddedForDevice(Map<String, Variant<?>> value) {
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
        if (device == null) return;

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

        // Create ScanResult
        final ScanResult scanResult = new ScanResult(deviceName, deviceAddress, finalServiceUUIDs, rssi, device.getManufacturerData(), device.getServiceData());
        final BluetoothPeripheral peripheral = new BluetoothPeripheral(device, deviceName, deviceAddress, internalCallback, null, callBackHandler);
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
                    handlePropertiesChangedForDeviceWhenScanning(bluezDevice, propertiesChanged);
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

    private void handlePropertiesChangedForDeviceWhenScanning(@NotNull BluezDevice bluezDevice, Properties.PropertiesChanged propertiesChanged) {
        // Since we are scanning any property change is an indication that we are seeing a device
        final String deviceAddress;
        final String deviceName;
        final String[] serviceUUIDs;
        final int rssi;
        final Map<Integer, byte[]> manufacturerData;
        final Map<String, byte[]> serviceData;
        try {
            deviceAddress = bluezDevice.getAddress();
            deviceName = bluezDevice.getName();
            serviceUUIDs = bluezDevice.getUuids();
            rssi = bluezDevice.getRssi();
            manufacturerData = bluezDevice.getManufacturerData();
            serviceData = bluezDevice.getServiceData();
        } catch (Exception e) {
            return;
        }

        final ScanResult scanResult = new ScanResult(deviceName, deviceAddress, serviceUUIDs, rssi, manufacturerData, serviceData);
        final BluetoothPeripheral peripheral = new BluetoothPeripheral(bluezDevice, deviceName, deviceAddress, internalCallback, null, callBackHandler);
        onScanResult(peripheral, scanResult);
    }

    private void handlePropertiesChangedForAdapter(String propertyName, Variant<?> value) {
        switch (propertyName) {
            case PROPERTY_DISCOVERING:
                isScanning = (Boolean) value.getValue();
                if (isScanning) isStoppingScan = false;
                HBLogger.i(TAG, String.format("scan %s", isScanning ? "started" : "stopped"));

                if (currentCommand.equalsIgnoreCase(PROPERTY_DISCOVERING)) {
                    callBackHandler.postDelayed(this::completedCommand, 200L);
                }
                break;
            case PROPERTY_POWERED:
                isPowered = (Boolean) value.getValue();
                HBLogger.i(TAG, String.format("powered %s", isPowered ? "on" : "off"));

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
                completedCommand();
                return;
            }

            // Set scan filter. We have to do this before every scan since Bluez doesn't remember this
            try {
                setScanFilter(scanFilters);
            } catch (BluezInvalidArgumentsException | BluezNotReadyException | BluezFailedException | BluezNotSupportedException e) {
                HBLogger.e(TAG, "Error setting scan filer");
                HBLogger.e(TAG, e);
            }

            // Start the discovery
            try {
                HBLogger.i(TAG, "Starting scan");
                currentCommand = PROPERTY_DISCOVERING;
                adapter.startDiscovery();
                scanCounter++;
                startScanTimer();
            } catch (BluezFailedException e) {
                HBLogger.e(TAG, "Could not start discovery (failed)");
                completedCommand();
            } catch (BluezNotReadyException e) {
                HBLogger.e(TAG, "Could not start discovery (not ready)");
                completedCommand();
            } catch (DBusExecutionException e) {
                // Still need to see what this could be
                HBLogger.e(TAG, "Error starting scanner");
                HBLogger.e(TAG, e.getMessage());
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG, ENQUEUE_ERROR);
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
                HBLogger.i(TAG, "Stopping scan");
                currentCommand = PROPERTY_DISCOVERING;
                cancelTimeoutTimer();
                adapter.stopDiscovery();
            } catch (BluezNotReadyException e) {
                HBLogger.e(TAG, "Could not stop discovery (not ready)");
                completedCommand();
            } catch (BluezFailedException e) {
                HBLogger.e(TAG, "Could not stop discovery (failed)");
                completedCommand();
            } catch (BluezNotAuthorizedException e) {
                HBLogger.e(TAG, "Could not stop discovery (not authorized)");
                completedCommand();
            } catch (DBusExecutionException e) {
                // Usually this is the exception "No discovery started"
                HBLogger.e(TAG, e.getMessage());
                if (e.getMessage().equalsIgnoreCase("No discovery started")) {
                    HBLogger.e(TAG, "Could not stop scan, because we are not scanning!");
                    isStoppingScan = false;
                    isScanning = false;   // This shouldn't be needed but seems it is...
                } else if (e.getMessage().equalsIgnoreCase("Operation already in progress")) {
                    HBLogger.e(TAG, "a stopDiscovery is in progress");
                }
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG, ENQUEUE_ERROR);
        }
    }

    private void startScanTimer() {
        cancelTimeoutTimer();

        this.timeoutRunnable = () -> {
            HBLogger.i(TAG, String.format("scanning timeout, stopping scan (%d)", scanCounter));
            stopScanning();
            startScanning();
        };
        timeoutHandler.postDelayed(timeoutRunnable, SCAN_INTERNAL);
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

    @SuppressWarnings("unused")
    public void adapterOn() {
        boolean result = commandQueue.add(() -> {

            if (!adapter.isPowered()) {
                HBLogger.i(TAG, "Turning on adapter");
                currentCommand = PROPERTY_POWERED;
                adapter.setPowered(true);
            } else {
                // If it is already on we won't receive a callback so just complete the command
                HBLogger.i(TAG, "Adapter already on");
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG, ENQUEUE_ERROR);
        }
    }

    @SuppressWarnings("unused")
    public void adapterOff() {
        boolean result = commandQueue.add(() -> {
            if (adapter.isPowered()) {
                HBLogger.i(TAG, "Turning off adapter");
                currentCommand = PROPERTY_POWERED;
                adapter.setPowered(false);
            } else {
                // If it is already off we won't receive a callback so just complete the command
                HBLogger.i(TAG, "Adapter already off");
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG, ENQUEUE_ERROR);
        }
    }

    public void connectPeripheral(final BluetoothPeripheral peripheral, final BluetoothPeripheralCallback peripheralCallback) {
        // Make sure peripheral is valid
        if (peripheral == null) {
            HBLogger.i(TAG, "no valid peripheral specified, aborting connection");
            return;
        }
        peripheral.setPeripheralCallback(peripheralCallback);

        // Check if we are already connected
        if (connectedPeripherals.containsKey(peripheral.getAddress())) {
            HBLogger.w(TAG, String.format("WARNING: Already connected to %s'", peripheral.getAddress()));
            return;
        }

        // Check if we already have an outstanding connection request for this peripheral
        if (unconnectedPeripherals.containsKey(peripheral.getAddress())) {
            HBLogger.w(TAG, String.format("WARNING: Already connecting to %s'", peripheral.getAddress()));
            return;
        }

        unconnectedPeripherals.put(peripheral.getAddress(), peripheral);
        boolean result = commandQueue.add(() -> {
            currentDeviceAddress = peripheral.getAddress();
            currentCommand = PROPERTY_CONNECTED;
            peripheral.connect();
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG, ENQUEUE_ERROR);
        }
    }

    public boolean autoConnectPeripheral(BluetoothPeripheral peripheral, BluetoothPeripheralCallback peripheralCallback) {
        final String deviceAddress = peripheral.getAddress();
        if (reconnectPeripheralAddresses.contains(deviceAddress)) return false;

        reconnectPeripheralAddresses.add(deviceAddress);
        reconnectCallbacks.put(deviceAddress, peripheralCallback);
        unconnectedPeripherals.put(deviceAddress, peripheral);

        if (!isScanning) {
            scanFilters.put(DiscoveryFilter.Transport, DiscoveryTransport.LE);
            scanFilters.put(DiscoveryFilter.RSSI, DISCOVERY_RSSI_THRESHOLD);
            scanFilters.put(DiscoveryFilter.DuplicateData, true);
            autoScanActive = true;
            startScanning();
        }
        return true;
    }

    @SuppressWarnings("unused")
    public void cancelConnection(final BluetoothPeripheral peripheral) {
        if (peripheral.getConnectionState() == ConnectionState.Connected) {
            currentDeviceAddress = peripheral.getAddress();
            peripheral.disconnect();
        }
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
            HBLogger.e(TAG, String.format("%s is not a valid address. Make sure all alphabetic characters are uppercase.", peripheralAddress));
            return null;
        }

        if (connectedPeripherals.containsKey(peripheralAddress)) {
            return connectedPeripherals.get(peripheralAddress);
        } else if (unconnectedPeripherals.containsKey(peripheralAddress)) {
            return unconnectedPeripherals.get(peripheralAddress);
        } else {
            return new BluetoothPeripheral(null, null, peripheralAddress, internalCallback, null, callBackHandler);
        }
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
                        HBLogger.w(TAG, "ERROR: Command exception for central");
                        completedCommand();
                    }
                });
            }
        }
    }

    private List<BluezAdapter> scanForBluetoothAdapters() {
        final Map<String, BluezAdapter> bluetoothAdaptersByAdapterName = new LinkedHashMap<>();

        Set<String> scanObjectManager = DbusHelper.findNodes(dbusConnection, "/org/bluez");
        for (String hci : scanObjectManager) {
            Adapter1 adapter1 = DbusHelper.getRemoteObject(dbusConnection, "/org/bluez/" + hci, Adapter1.class);
            if (adapter1 != null) {
                BluezAdapter bluetoothAdapter = new BluezAdapter(adapter1, "/org/bluez/" + hci, dbusConnection);
                bluetoothAdaptersByAdapterName.put(hci, bluetoothAdapter);
            }
        }

        return new ArrayList<>(bluetoothAdaptersByAdapterName.values());
    }

    private @Nullable BluezDevice getDeviceByPath(@NotNull BluezAdapter adapter, @NotNull String devicePath) {
        Device1 device = DbusHelper.getRemoteObject(dbusConnection, devicePath, Device1.class);
        if (device != null) {
            return new BluezDevice(device, adapter, devicePath, dbusConnection);
        }
        return null;
    }

    private @Nullable BluezDevice getDeviceByAddress(BluezAdapter adapter, String deviceAddress) {
        String devicePath = adapter.getDbusPath() + "/dev_" + deviceAddress.replace(":", "_");
        return getDeviceByPath(adapter, devicePath);
    }

    /*
     * Function to clean up device from Bluetooth cache
     */
    protected void removeDevice(@NotNull final BluetoothPeripheral device) {
        BluezDevice bluetoothDevice = getDeviceByAddress(adapter, device.getAddress());
        if (bluetoothDevice == null) return;

        boolean isBonded = device.isPaired();
        HBLogger.i(TAG, String.format("removing device %s (%s)", device.getAddress(), isBonded ? "BONDED" : "BOND_NONE"));
        if (adapter != null) {
            try {
                Device1 rawDevice = bluetoothDevice.getRawDevice();
                if (rawDevice != null) {
                    adapter.removeDevice(rawDevice);
                }
            } catch (BluezFailedException | BluezInvalidArgumentsException e) {
                HBLogger.e(TAG, "Error removing device");
            }
        }
    }
}

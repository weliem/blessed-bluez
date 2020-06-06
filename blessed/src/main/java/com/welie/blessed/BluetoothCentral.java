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

public class BluetoothCentral {

    private static final String TAG = BluetoothCentral.class.getSimpleName();

    private BluetoothCentralCallback bluetoothCentralCallback;
    private Handler callBackHandler;
    private DBusConnection dbusConnection;
    private BluezAdapter adapter;
    private volatile boolean isScanning = false;
    private volatile boolean isPowered = false;
    private volatile boolean isStoppingScan = false;
    private final Map<DiscoveryFilter, Object> scanFilters = new EnumMap<>(DiscoveryFilter.class);
    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);

    // Command queue
    private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();
    private boolean commandQueueBusy;
    private final Handler queueHandler = new Handler("CentralQueue");
    private String currentCommand;
    private String currentDeviceAddress;

    // Bluez property strings
    private static final String DISCOVERING = "Discovering";
    private static final String POWERED = "Powered";
    private static final String CONNECTED = "Connected";
    private static final String SERVICES_RESOLVED = "ServicesResolved";
    private static final String PAIRED = "Paired";
    private static final String SERVICE_UUIDS = "UUIDs";
    private static final String NAME = "Name";
    private static final String ADDRESS = "Address";
    private static final String RSSI = "RSSI";
    private static final String MANUFACTURER_DATA = "ManufacturerData";
    private static final String SERVICE_DATA = "ServiceData";


    static final String DBUS_BUSNAME = "org.freedesktop.DBus";
    static final String BLUEZ_DBUS_BUSNAME = "org.bluez";
    static final String BLUEZ_DEVICE_INTERFACE = "org.bluez.Device1";
    static final String BLUEZ_ADAPTER_INTERFACE = "org.bluez.Adapter1";
    static final String BLUEZ_GATT_INTERFACE = "org.bluez.GattManager1";

    // Strings
    private static final String ENQUEUE_ERROR = "ERROR: Could not enqueue stop scanning command";

    private final Object connectLock = new Object();
    private final Map<String, BluetoothPeripheral> connectedPeripherals = new ConcurrentHashMap<>();
    private final Map<String, BluetoothPeripheral> unconnectedPeripherals = new ConcurrentHashMap<>();
    private static final int MAX_CONNECTED_PERIPHERALS = 7;
    private static final int ADDRESS_LENGTH = 17;

    private final InternalCallback internalCallback = new InternalCallback() {

        @Override
        public void connected(BluetoothPeripheral device) {

            // Do some administration work
            connectedPeripherals.put(device.getAddress(), device);
            unconnectedPeripherals.remove(device.getAddress());

            if (connectedPeripherals.size() == MAX_CONNECTED_PERIPHERALS) {
                HBLogger.w(TAG, "maximum amount (7) of connected peripherals reached");
            }

            HBLogger.i(TAG, String.format("connected devices: %d", connectedPeripherals.size()));

            // Inform the listener that we are now connected
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (bluetoothCentralCallback != null) {
                        bluetoothCentralCallback.onConnectedPeripheral(device);
                    }
                }
            });
        }

        @Override
        public void servicesDiscovered(BluetoothPeripheral device) {
            // TODO , is this needed?
            HBLogger.i(TAG, "service discovery succeeded");
        }

        @Override
        public void serviceDiscoveryFailed(BluetoothPeripheral device) {
            HBLogger.i(TAG, "Service discovery failed");
        }

        @Override
        public void connectFailed(BluetoothPeripheral device) {
            HBLogger.e(TAG, String.format("ERROR: Connection to %s failed", device.getAddress()));

            // Remove it from the connected peripherals map, in case it still got there
            connectedPeripherals.remove(device.getAddress());
            unconnectedPeripherals.remove(device.getAddress());

            HBLogger.i(TAG, String.format("connected devices: %d", connectedPeripherals.size()));

            // Inform the handler that the connection failed
            if (bluetoothCentralCallback != null) {
                bluetoothCentralCallback.onConnectionFailed(device, 0);
            } else {
                HBLogger.e(TAG, "ERROR: no callback for 'connectFailed' registered");
            }
        }

        @Override
        public void disconnected(BluetoothPeripheral device) {
            String deviceAddress = device.getAddress();

            // Remove it from the connected peripherals map
            connectedPeripherals.remove(deviceAddress);
            unconnectedPeripherals.remove(deviceAddress);

            HBLogger.i(TAG, String.format("connected devices: %d", connectedPeripherals.size()));

            // Remove unbonded devices to make setting notifications work (Bluez issue)
            if (!device.isPaired()) {
                removeDevice(device);
            }

            // Trigger callback
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (bluetoothCentralCallback != null) {
                        bluetoothCentralCallback.onDisconnectedPeripheral(device, 0);
                    }
                }
            });
        }
    };

    public BluetoothCentral(@NotNull BluetoothCentralCallback bluetoothCentralCallback, @Nullable Handler handler) {
        try {
            // Process arguments
            this.bluetoothCentralCallback = bluetoothCentralCallback;
            this.callBackHandler = (handler != null) ? handler : new Handler("Central-callBackHandler");

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

    public void scanForPeripherals() {
        // Setup scan filter
        short rssiTreshold = -70;
        scanFilters.put(DiscoveryFilter.Transport, DiscoveryTransport.LE);
        scanFilters.put(DiscoveryFilter.RSSI, rssiTreshold);
        scanFilters.put(DiscoveryFilter.DuplicateData, true);

        // Start scan
        startScanning();
    }

    public void scanForPeripheralsWithServices(final UUID[] serviceUUIDs) {
        // Convert UUID array to string array
        ArrayList<String> uuidStrings = new ArrayList<>();
        if (serviceUUIDs != null) {
            for (UUID uuid : serviceUUIDs) {
                uuidStrings.add(uuid.toString());
            }
        }
        String[] scanUUIDs = uuidStrings.toArray(new String[0]);

        // Setup scan filter
        short rssiTreshold = -70;
        scanFilters.put(DiscoveryFilter.Transport, DiscoveryTransport.LE);
        scanFilters.put(DiscoveryFilter.RSSI, rssiTreshold);
        scanFilters.put(DiscoveryFilter.DuplicateData, true);
        if (scanUUIDs.length > 0) {
            scanFilters.put(DiscoveryFilter.UUIDs, scanUUIDs);
        }

        // Start scan
        startScanning();
    }

    private void onScanResult(BluetoothPeripheral peripheral, ScanResult scanResult) {
        if (isScanning && !isStoppingScan) {
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
            Map<String, Map<String, Variant<?>>> interfaces = interfacesAdded.getInterfaces();
            interfaces.forEach((key, value) -> {
                if (key.equalsIgnoreCase(BLUEZ_DEVICE_INTERFACE)) {
                    final String deviceAddress;
                    final String deviceName;
                    final int rssi;
                    ArrayList<String> serviceUUIDs = null;

                    // Grab address
                    if (value.get(ADDRESS).getValue() instanceof String) {
                        deviceAddress = (String) value.get(ADDRESS).getValue();
                    } else {
                        deviceAddress = null;
                    }

                    // Get the device
                    final BluezDevice device = getDeviceByAddress(adapter, deviceAddress);
                    if (device == null) return;

                    // Grab name
                    if (value.get(NAME) != null) {
                        if (value.get(NAME).getValue() instanceof String) {
                            deviceName = (String) value.get(NAME).getValue();
                        } else {
                            deviceName = null;
                        }
                    } else {
                        deviceName = null;
                    }

                    // Grab service UUIDs
                    if ((value.get(SERVICE_UUIDS) != null) && (value.get(SERVICE_UUIDS).getValue() instanceof ArrayList)) {
                        serviceUUIDs = (ArrayList) value.get(SERVICE_UUIDS).getValue();
                    }

                    if ((value.get(RSSI) != null) && (value.get(RSSI).getValue() instanceof Short)) {
                        rssi = (Short) value.get(RSSI).getValue();
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
            });
        }
    };

    private final AbstractPropertiesChangedHandler propertiesChangedHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(Properties.PropertiesChanged propertiesChanged) {
            switch (propertiesChanged.getInterfaceName()) {
                case BLUEZ_DEVICE_INTERFACE:
                    final BluezDevice bluezDevice = getDeviceByPath(adapter, propertiesChanged.getPath());
                    if (bluezDevice == null) return;

                    if ((!isScanning) || isStoppingScan) {
                        handlePropertiesChangedForDeviceWhenNotScanning(bluezDevice, propertiesChanged);
                        return;
                    }
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

    private void handlePropertiesChangedForDeviceWhenScanning(BluezDevice bluezDevice, Properties.PropertiesChanged propertiesChanged) {
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

    private void handlePropertiesChangedForDeviceWhenNotScanning(BluezDevice bluezDevice, Properties.PropertiesChanged propertiesChanged) {
        try {
            final String deviceAddress = bluezDevice.getAddress();
            if (!deviceAddress.equalsIgnoreCase(currentDeviceAddress)) return;

            propertiesChanged.getPropertiesChanged().forEach((s, value) -> {
                if (value.getValue() instanceof Boolean &&
                        ((s.equalsIgnoreCase(CONNECTED) && currentCommand.equalsIgnoreCase(CONNECTED)) ||
                                (s.equalsIgnoreCase(PAIRED) && currentCommand.equalsIgnoreCase(PAIRED)))) {
                    completedCommand();
                }
            });
        } catch (Exception e) {
            // Ignore this exception, the device is probably removed already
        }
    }

    private void handlePropertiesChangedForAdapter(String propertyName, Variant<?> value) {
        switch (propertyName) {
            case DISCOVERING:
                isScanning = (Boolean) value.getValue();
                if (isScanning) isStoppingScan = false;
                HBLogger.i(TAG, String.format("scan %s", isScanning ? "started" : "stopped"));
                if (currentCommand.equalsIgnoreCase(DISCOVERING)) {
                    callBackHandler.postDelayed(this::completedCommand, 200L);
                }
                break;
            case POWERED:
                isPowered = (Boolean) value.getValue();
                HBLogger.i(TAG, String.format("powered %s", isPowered ? "on" : "off"));

                // Complete the command and add a delay if needed
                long delay = isPowered ? 0 : 4 * MINUTE;
                callBackHandler.postDelayed(() -> {
                    if (currentCommand.equalsIgnoreCase(POWERED)) completedCommand();
                }, delay);
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
    public void startScanning() {
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
                currentCommand = DISCOVERING;
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
    public void stopScanning() {
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
                currentCommand = DISCOVERING;
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

    private int scanCounter = 0;
    private final Handler timeoutHandler = new Handler(this.getClass().getSimpleName());
    private Runnable timeoutRunnable;

    // Cycle the adapter power when threshold is reached
    private static final int CYCLE_ADAPTER_THRESHOLD = 3600;

    // Scan in intervals. Make sure it is less than 10seconds to avoid issues with Bluez internal scanning
    private static final long SCAN_INTERNAL = TimeUnit.SECONDS.toMillis(8);

    private void startScanTimer() {
        // Cancel runnable if it exists
        cancelTimeoutTimer();

        // Prepare runnable
        this.timeoutRunnable = () -> {
            HBLogger.i(TAG, String.format("Scanning timeout, stopping scan (%d)", scanCounter));
            stopScanning();
            startScanning();

            // See if we need to cycle the adapter
//            if (scanCounter >= CYCLE_ADAPTER_THRESHOLD) {
//                scanCounter = 0;
//                adapterOff();
//                adapterOn();
//            }
        };

        // Start timer
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

    public void adapterOn() {
        boolean result = commandQueue.add(() -> {

            if (!adapter.isPowered()) {
                HBLogger.i(TAG, "Turning on adapter");
                currentCommand = POWERED;
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

    public void adapterOff() {
        boolean result = commandQueue.add(() -> {
            if (adapter.isPowered()) {
                HBLogger.i(TAG, "Turning off adapter");
                currentCommand = POWERED;
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
            currentCommand = CONNECTED;
            peripheral.connect();
        });

        if (result) {
            nextCommand();
        } else {
            HBLogger.e(TAG, ENQUEUE_ERROR);
        }
    }

    public void cancelConnection(final BluetoothPeripheral peripheral) {
        if (peripheral.getConnectionState() == ConnectionState.Connected) {
            currentDeviceAddress = peripheral.getAddress();
            peripheral.disconnect();
        }
    }

    /**
     * Get a peripheral object matching the specified mac address.
     *
     * @param peripheralAddress mac address
     * @return a BluetoothPeripheral object matching the specified mac address or null if it was not found
     */
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
     * Get the list of connected peripherals.
     *
     * @return list of connected peripherals
     */
    public List<BluetoothPeripheral> getConnectedPeripherals() {
        return new ArrayList<>(connectedPeripherals.values());
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

    private BluezDevice getDeviceByPath(@NotNull BluezAdapter adapter, @NotNull String devicePath) {
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
    protected void removeDevice(final BluetoothPeripheral device) {
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

    public boolean isScanning() {
        return isScanning;
    }
}

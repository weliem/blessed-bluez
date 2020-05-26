package blessed;

import blessed.bluez.*;
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
    private static final int SECOND = 1000;
    private static final int MINUTE = 60 * SECOND;
    private boolean init = false;
    private boolean connecting = false;

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

    // Strings
    private static final String ENQUEUE_ERROR = "ERROR: Could not enqueue stop scanning command";

    private final Object connectLock = new Object();
    private final Map<String, BluetoothPeripheral> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, BluetoothPeripheral> unconnectedDevices = new ConcurrentHashMap<>();
    private static final int MAX_CONNECTED_PERIPHERALS = 7;

    private final InternalCallback internalCallback = new InternalCallback() {

        @Override
        public void connected(BluetoothPeripheral device) {

            // Do some administration work
            connectedDevices.put(device.getAddress(), device);
            if(connectedDevices.size() == MAX_CONNECTED_PERIPHERALS) {
                HBLogger.w(TAG,"maximum amount (7) of connected peripherals reached");
            }
//            HBLogger.i(TAG, String.format("Connected devices: %d", connectedDevices.size()));

            // Remove peripheral from unconnected peripherals map if it is there
            if(unconnectedDevices.get(device.getAddress()) != null) {
                unconnectedDevices.remove(device.getAddress());
            }

            // Inform the listener that we are now connected
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    bluetoothCentralCallback.onConnectedPeripheral(device);
                }
            });
        }

        @Override
        public void servicesDiscovered(BluetoothPeripheral device) {
            // Continue scanning
            BluetoothCentral.this.connecting = false;
            HBLogger.i(TAG,"Resuming scan (connected)");
            if(!isScanning()) {
                findSupportedDevices();
            }
        }

        @Override
        public void serviceDiscoveryFailed(BluetoothPeripheral device) {
            HBLogger.i(TAG, "Service discovery failed");
            if(device.isPaired()) {
                // If this happens to a Omron BPM it usually means the bond is lost so remove the device
                if(device.getName().startsWith("BLEsmart_")) {
                    callBackHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            HBLogger.i(TAG, "Removing device because bond lost");
                            removeDevice(device);
                        }
                    }, 1000L);
                }
            }
        }

        @Override
        public void connectFailed(BluetoothPeripheral device) {
            HBLogger.e(TAG,String.format("ERROR: Connection to %s failed", device.getAddress()));

            // Remove it from the connected peripherals map, in case it still got there
            connectedDevices.remove(device.getAddress());
//            HBLogger.i(TAG, String.format("Connected devices: %d", connectedDevices.size()));

            // Remove from unconnected devices list
            if (unconnectedDevices.get(device.getAddress()) != null) {
                unconnectedDevices.remove(device.getAddress());
            }

            // Continue scanning
            BluetoothCentral.this.connecting = false;
            if(!isScanning()) {
                HBLogger.i(TAG,"Resuming scan");
                findSupportedDevices();
            }

            // Inform the handler that the connection failed
            if (bluetoothCentralCallback != null) {
                bluetoothCentralCallback.onConnectionFailed(device, 0);
            } else {
                HBLogger.e(TAG,"ERROR: No connection listener for 'connectFailed' callback");
            }
        }

        @Override
        public void disconnected(BluetoothPeripheral device) {
            String deviceAddress = device.getAddress();

            // Remove it from the connected peripherals map
            connectedDevices.remove(deviceAddress);
//            HBLogger.i(TAG, String.format("Connected devices: %d", connectedDevices.size()));

            // Make sure it is also not in unconnected peripherals map
            if(unconnectedDevices.get(deviceAddress) != null) {
                unconnectedDevices.remove(deviceAddress);
            }

            // Trigger callback
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    bluetoothCentralCallback.onDisconnectedPeripheral(device, 0);
                }
            });

            // Remove unbonded devices to make setting notifications work (Bluez issue)
            callBackHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if(!device.isPaired()) {
                        removeDevice(device);
                    }
                }
            }, 1000L);

            // In some cases, like when service discovery fails we may still need to start scanning
            BluetoothCentral.this.connecting = false;
            if(!isScanning()) {
                HBLogger.i(TAG,"Resuming scan");
                findSupportedDevices();
            }
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
                HBLogger.i(TAG, String.format("Found %d bluetooth adapter(s)", adapters.size()));

                // Take the adapter with the highest number
                adapter = adapters.get(adapters.size()-1);
                HBLogger.i(TAG, "Using adapter " + adapter.getDeviceName());

                // Make sure the adapter is powered on
                isPowered = adapter.isPowered();
                if(!isPowered) {
                    HBLogger.i(TAG, "Adapter not on, so turning it on now");
                    adapterOn();
                }
            } else {
                HBLogger.e(TAG, "No bluetooth adaptors found");
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
        if(serviceUUIDs != null) {
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
        if(scanUUIDs.length > 0) {
            scanFilters.put(DiscoveryFilter.UUIDs, scanUUIDs);
        }

        // Start scan
        startScanning();
    }

    private final AbstractInterfacesAddedHandler interfacesAddedHandler = new AbstractInterfacesAddedHandler() {
        @Override
        public void handle(ObjectManager.InterfacesAdded interfacesAdded) {
            Map<String, Map<String, Variant<?>>> interfaces = interfacesAdded.getInterfaces();
            interfaces.forEach((key, value) -> {
                if(key.equalsIgnoreCase("org.bluez.Device1")) {
                    final String deviceAddress;
                    final String deviceName;
                    final int rssi;
                    ArrayList<String> serviceUUIDs = null;

                    // Grab address
                    if(value.get(ADDRESS).getValue() instanceof String) {
                        deviceAddress = (String) value.get(ADDRESS).getValue();
                    } else {
                        deviceAddress = null;
                    }

                    // Get the device
                    final BluezDevice device = getDeviceByAddress(adapter, deviceAddress);

                    // Grab name
                    if(value.get(NAME) != null) {
                        if (value.get(NAME).getValue() instanceof String) {
                            deviceName = (String) value.get(NAME).getValue();
                        } else {
                            deviceName = null;
                        }
                    } else {
                        deviceName = null;
                    }

                    // Grab service UUIDs
                    if((value.get(SERVICE_UUIDS) != null) && (value.get(SERVICE_UUIDS).getValue() instanceof ArrayList)) {
                        serviceUUIDs = (ArrayList) value.get(SERVICE_UUIDS).getValue();
                    }

                    if((value.get("Rssi") != null) && (value.get("Rssi").getValue() instanceof Short)) {
                        rssi = (Short) value.get("Rssi").getValue();
                    } else {
                        rssi = -100;
                    }


                    // Don't proceed if deviceAddress or deviceName are null
                    if(deviceAddress == null || deviceName == null) return;

                    // Convert the service UUIDs
                    final String[] finalServiceUUIDs;
                    if(serviceUUIDs != null) {
                        finalServiceUUIDs = serviceUUIDs.toArray(new String[serviceUUIDs.size()]);
                    } else {
                        finalServiceUUIDs = null;
                    }

                    // Create ScanResult
                    final ScanResult scanResult = new ScanResult(deviceName, deviceAddress, finalServiceUUIDs, rssi);
                    final BluetoothPeripheral peripheral = new BluetoothPeripheral(device, deviceName, deviceAddress, internalCallback, null);

                    // Propagate found device
                    callBackHandler.post(() -> {
                        if(bluetoothCentralCallback != null) {
                            bluetoothCentralCallback.onDiscoveredPeripheral(peripheral, scanResult);
                        } else {
                            HBLogger.e(TAG, "central is null");
                        }
                    });
                }
            });
        }
    };

    private final AbstractPropertiesChangedHandler propertiesChangedHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(Properties.PropertiesChanged propertiesChanged) {

            // See if this property is for a device
            if (propertiesChanged.getInterfaceName().equals("org.bluez.Device1")) {

                // Make sure the propertiesChanged is not empty. Note that we also get called because of propertiesRemoved.
                if (propertiesChanged.getPropertiesChanged().isEmpty()) return;

                // Make sure we are still scanning before handling this propertyChanged event
                if ((!isScanning) || isStoppingScan) {
 //                   handleDeviceSignalWhenNotScanning(propertiesChanged);
                    return;
                }

                // Make sure we ignore some events that are not indicators for new devices found
                propertiesChanged.getPropertiesChanged().forEach((s, value) -> {
                    if (s.equalsIgnoreCase(CONNECTED) ||
                            s.equalsIgnoreCase(SERVICES_RESOLVED) ||
                            s.equalsIgnoreCase(PAIRED)) {
                        return;
                    }
                });

                // Get the device object
                final BluezDevice foundDevice = getDeviceByPath(adapter, propertiesChanged.getPath());
                if (foundDevice == null) return;

                // Get device properties
                final String deviceAddress;
                final String deviceName;
                final String[] serviceUUIDs;
                final int rssi;
                try {
                    deviceAddress = foundDevice.getAddress();
                    deviceName = foundDevice.getName();
                    serviceUUIDs = foundDevice.getUuids();
                    rssi = foundDevice.getRssi();
                } catch (Exception e) {
                    return;
                }

                // We are not interested in devices without a name or address
                if (deviceName == null || deviceAddress == null) return;

                // Propagate found device
                // Create ScanResult
                final ScanResult scanResult = new ScanResult(deviceName, deviceAddress, serviceUUIDs, rssi);
                final BluetoothPeripheral peripheral = new BluetoothPeripheral(foundDevice, deviceName, deviceAddress, internalCallback, null);
                callBackHandler.post(() -> {
                    if(bluetoothCentralCallback != null) {
                        bluetoothCentralCallback.onDiscoveredPeripheral(peripheral, scanResult);
                    } else {
                        HBLogger.e(TAG, "central is null");
                    }
                });
            } else if (propertiesChanged.getInterfaceName().equals("org.bluez.Adapter1")) {
                propertiesChanged.getPropertiesChanged().forEach((s, value) -> {
                    if (s.equalsIgnoreCase(DISCOVERING) && value.getValue() instanceof Boolean) {
                        isScanning = (Boolean) value.getValue();
                        if (isScanning) isStoppingScan = false;
                        HBLogger.i(TAG, String.format("Scan %s", isScanning ? "started" : "stopped"));
                        if (currentCommand.equalsIgnoreCase(DISCOVERING)) completedCommand();
                    } else if (s.equalsIgnoreCase(POWERED) && value.getValue() instanceof Boolean) {
                        isPowered = (Boolean) value.getValue();
                        HBLogger.i(TAG, String.format("Powered %s", isPowered ? "on" : "off"));

                        // Complete the command and add a delay if needed
                        int delay = isPowered ? 0 : 4 * MINUTE;
                        callBackHandler.postDelayed(() -> {
                            if (currentCommand.equalsIgnoreCase(POWERED)) completedCommand();
                        }, delay);
                    }
                });
            }
        }
    };

    void handleSignal(Properties.PropertiesChanged propertiesChanged) {
        propertiesChangedHandler.handle(propertiesChanged);
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
        if(!isPowered) return;

        boolean result = commandQueue.add(() -> {

            // Just in case, set isStoppingScan to false
            isStoppingScan = false;

            // If we are already scanning then complete the command immediately
            isScanning = adapter.isDiscovering();
            if(isScanning) {
                completedCommand();
                return;
            }

            // Set scan filter. We have to do this before every scan since Bluez doesn't remember this
            try {
                setScanFilter(scanFilters);
            } catch (BluezInvalidArgumentsException | BluezNotReadyException | BluezFailedException | BluezNotSupportedException e) {
                HBLogger.e(TAG,"Error setting scan filer");
                HBLogger.e(TAG, e);
            }

            // Start the discovery
            try {
                currentCommand = DISCOVERING;
                adapter.startDiscovery();
            } catch (BluezFailedException e) {
                HBLogger.e(TAG, "Could not start discovery (failed)");
                completedCommand();
            } catch (BluezNotReadyException e) {
                HBLogger.e(TAG, "Could not start discovery (not ready)");
                completedCommand();
            } catch (DBusExecutionException e) {
                // Still need to see what this could be
                HBLogger.e(TAG,"Error starting scanner");
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

    public void findSupportedDevices() {
        startScanning();
        startScanTimer();
        if(adapter.isPowered()) {
            scanCounter++;
        }
    }

    /*
     * Stop the scanner
     */
    public void stopScanning() {
        // Make sure the adapter is on
        if(!isPowered) return;

        boolean result = commandQueue.add(() -> {
            // Check if we are scanning
            isScanning = adapter.isDiscovering();
            if(!isScanning) {
                completedCommand();
                return;
            }

            // Set flag to true in order to stop sending scan results
            isStoppingScan = true;

            // Stop the discovery
            try {
                currentCommand = DISCOVERING;
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
                if(e.getMessage().equalsIgnoreCase("No discovery started")) {
                    HBLogger.e(TAG,"Could not stop scan, because we are not scanning!");
                    isStoppingScan = false;
                    isScanning = false;   // This shouldn't be needed but seems it is...
                } else if(e.getMessage().equalsIgnoreCase("Operation already in progress")) {
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
    private static final long SCAN_INTERNAL = 8000L;

    private void startScanTimer() {
        // Cancel runnable if it exists
        cancelTimeoutTimer();

        // Prepare runnable
        this.timeoutRunnable = new Runnable() {
            @Override
            public void run() {

                HBLogger.i(TAG,String.format("Scanning timeout, stopping scan (%d)", scanCounter));
                stopScanning();

                // See if we need to cycle the adapter
                if(scanCounter >= CYCLE_ADAPTER_THRESHOLD) {
                    scanCounter = 0;
                    adapterOff();
                    adapterOn();
                }

                // Restart the scan and timer
                findSupportedDevices();
            }
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
 //       isPowered = false;
        boolean result = commandQueue.add(() -> {

            if(!adapter.isPowered()) {
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
            if(adapter.isPowered()) {
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

    public void connectPeripheral(BluetoothPeripheral peripheral, BluetoothPeripheralCallback peripheralCallback) {
        synchronized (connectLock) {

            // Make sure peripheral is valid
            if (peripheral == null) {
                HBLogger.i(TAG,"no valid peripheral specified, aborting connection");
                return;
            }

            // Check if we are already connected
            if (connectedDevices.containsKey(peripheral.getAddress())) {
                HBLogger.w(TAG,String.format("WARNING: Already connected to %s'", peripheral.getAddress()));
                return;
            }

            // Check if we already have an outstanding connection request for this peripheral
            if (unconnectedDevices.containsKey(peripheral.getAddress())) {
                HBLogger.w(TAG,String.format("WARNING: Already connecting to %s'", peripheral.getAddress()));
                return;
            }

            // Make sure only 1 connection is in progress at any time
            if(connecting) {
                HBLogger.w(TAG,String.format("WARNING: Connection in progress, aborting connection to %s", peripheral.getAddress()));
                return;
            }

            if (!unconnectedDevices.containsKey(peripheral.getAddress())) {
                peripheral.setPeripheralCallback(peripheralCallback);
                unconnectedDevices.put(peripheral.getAddress(), peripheral);

                HBLogger.i(TAG,"Pausing scan");
                stopScanning();
                connecting = true;
                peripheral.connect();
            } else {
                HBLogger.e(TAG,String.format("WARNING: Already connected with %s", peripheral.getAddress()));
            }
        }
    }

    public void cancelConnection(final BluetoothPeripheral peripheral) {
        if (peripheral.getConnectionState() == ConnectionState.Connected) {
            peripheral.disconnect();
        }
    }

    /**
     * The current command has been completed, move to the next command in the queue (if any)
     */
    private void completedCommand() {
        commandQueue.poll();
        commandQueueBusy = false;
        currentCommand = "";
        currentDeviceAddress = "";
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
        if(bluetoothDevice == null) return;

        boolean isBonded = device.isPaired();
        HBLogger.i(TAG, String.format("Removing device %s (%s)", device.getAddress(), isBonded ? "BONDED" : "BOND_NONE"));
        if(adapter != null) {
            try {
                Device1 rawDevice = bluetoothDevice.getRawDevice();
                if(rawDevice != null) {
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

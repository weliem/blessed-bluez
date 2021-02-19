package com.welie.blessed;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.handlers.AbstractInterfacesAddedHandler;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.welie.blessed.BluetoothCentralManager.BLUEZ_ADAPTER_INTERFACE;
import static com.welie.blessed.BluetoothPeripheral.BLUEZ_CHARACTERISTIC_INTERFACE;
import static com.welie.blessed.BluetoothPeripheral.BLUEZ_DEVICE_INTERFACE;

@SuppressWarnings("UnusedReturnValue")
class BluezSignalHandler {
    private static final String TAG = BluezSignalHandler.class.getSimpleName();
    private static final Logger logger = LoggerFactory.getLogger(TAG);

    private static BluezSignalHandler instance = null;
    private DBusConnection dbusConnection;

    private final Map<String, BluetoothPeripheral> peripheralsMap = new ConcurrentHashMap<>();
    private final List<BluetoothCentralManager> centralList = new ArrayList<>();

    static synchronized BluezSignalHandler createInstance(@NotNull DBusConnection dbusConnection) {
        Objects.requireNonNull(dbusConnection, "no valid dbusconnection provided");

        if (instance == null) {
            instance = new BluezSignalHandler(dbusConnection);
        }
        return instance;
    }

    public static synchronized BluezSignalHandler getInstance() {
        if (instance == null) {
            logger.error("Using getInstance when no BluezSignalHandler has been created");
        }
        return instance;
    }

    protected final AbstractPropertiesChangedHandler signalHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(final Properties.PropertiesChanged propertiesChanged) {
            // Make sure the propertiesChanged is not empty. Note that we also get called because of propertiesRemoved.
            if (propertiesChanged.getPropertiesChanged().isEmpty()) return;

            // If it came from device or adapter, send it to all centrals
            String interfaceName = propertiesChanged.getInterfaceName();
            if (interfaceName.equals(BLUEZ_DEVICE_INTERFACE) || interfaceName.equals(BLUEZ_ADAPTER_INTERFACE)) {
                for (BluetoothCentralManager central : centralList) {
                    central.handleSignal(propertiesChanged);
                }
            }

            // Check if there are any peripherals at all
            if (peripheralsMap.isEmpty()) return;

            // If it came from a device or characteristic, send it to the right peripheral
            if (interfaceName.equals(BLUEZ_DEVICE_INTERFACE) || interfaceName.equals(BLUEZ_CHARACTERISTIC_INTERFACE)) {
                final String path = propertiesChanged.getPath();
                final Set<String> peripherals = peripheralsMap.keySet();
                for (final String peripheralAddress : peripherals) {
                    if (path.contains(peripheralAddress)) {
                        peripheralsMap.get(peripheralAddress).handleSignal(propertiesChanged);
                    }
                }
            }
        }
    };

    protected final AbstractInterfacesAddedHandler interfacesAddedHandler = new AbstractInterfacesAddedHandler() {
        @Override
        public void handle(final ObjectManager.InterfacesAdded interfacesAdded) {
            final String path = interfacesAdded.getPath();
            interfacesAdded.getInterfaces().forEach((key, value) -> {
                if (key.equalsIgnoreCase(BLUEZ_DEVICE_INTERFACE)) {
                    for (BluetoothCentralManager central : centralList) {
                        central.handleInterfaceAddedForDevice(path, value);
                    }
                }
            });
        }
    };

    private BluezSignalHandler(@NotNull final DBusConnection dBusConnection) {
        Objects.requireNonNull(dBusConnection, "no valid dbusconnection provided");

        try {
            this.dbusConnection = dBusConnection;
            registerPropertiesChangedHandler(signalHandler);
            registerInterfacesAddedHandler(interfacesAddedHandler);
        } catch (DBusException e) {
            logger.error("Error registering scan property handler");
            logger.error(e.toString());
        }
    }

    private void registerPropertiesChangedHandler(@NotNull final AbstractPropertiesChangedHandler handler) throws DBusException {
        dbusConnection.addSigHandler(handler.getImplementationClass(), handler);
    }

    private void registerInterfacesAddedHandler(@NotNull final AbstractInterfacesAddedHandler handler) throws DBusException {
        dbusConnection.addSigHandler(handler.getImplementationClass(), handler);
    }

    void addPeripheral(@NotNull final String peripheralAddress, @NotNull final BluetoothPeripheral peripheral) {
        Objects.requireNonNull(peripheralAddress, "no valid address provided");
        Objects.requireNonNull(peripheral, "no valid peripheral provided");

        String deviceAddressString = peripheralAddress.replace(":", "_");
        peripheralsMap.put(deviceAddressString, peripheral);
    }

    void removePeripheral(@NotNull final String peripheralAddress) {
        Objects.requireNonNull(peripheralAddress, "no valid address provided");

        String deviceAddressString = peripheralAddress.replace(":", "_");
        peripheralsMap.remove(deviceAddressString);
    }

    void addCentral(@NotNull final BluetoothCentralManager central) {
        Objects.requireNonNull(central, "no valid central provided");
        centralList.add(central);
    }
}

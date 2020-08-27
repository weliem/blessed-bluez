package com.welie.blessed;

import com.welie.blessed.internal.Handler;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class BluezSignalHandler {
    private static final String TAG = BluezSignalHandler.class.getSimpleName();
    private static final Logger logger = LoggerFactory.getLogger(TAG);

    private static BluezSignalHandler instance = null;
    private DBusConnection dbusConnection;
    private final Handler handler = new Handler("BluezSignalHandler");

    private final Map<String, BluetoothPeripheral> peripheralsMap = new ConcurrentHashMap<>();
    private final List<BluetoothCentral> centralList = new ArrayList<>();

    static synchronized BluezSignalHandler createInstance(DBusConnection dbusConnection) {
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

    private final AbstractPropertiesChangedHandler signalHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(final Properties.PropertiesChanged propertiesChanged) {
            // Make sure the propertiesChanged is not empty. Note that we also get called because of propertiesRemoved.
            if (propertiesChanged.getPropertiesChanged().isEmpty()) return;
            logger.debug(propertiesChanged.toString());

            // Send the signal to all centrals
            for (BluetoothCentral central : centralList) {
                central.handleSignal(propertiesChanged);
            }

            handler.post(() -> {
                // If it came from a device, send it to the right peripheral
                final String path = propertiesChanged.getPath();
                final Set<String> peripherals = peripheralsMap.keySet();
                for (String peripheralAddress : peripherals) {
                    if (path.contains(peripheralAddress)) {
                        peripheralsMap.get(peripheralAddress).handleSignal(propertiesChanged);
                    }
                }
            });
        }
    };

    private BluezSignalHandler(DBusConnection dBusConnection) {
        try {
            this.dbusConnection = dBusConnection;
            registerPropertyHandler(signalHandler);
        } catch (DBusException e) {
            logger.error("Error registering scan property handler");
            logger.error(e.toString());
        }
    }

    private void registerPropertyHandler(AbstractPropertiesChangedHandler handler) throws DBusException {
        dbusConnection.addSigHandler(handler.getImplementationClass(), handler);
    }

    void addPeripheral(@NotNull String peripheralAddress, @NotNull BluetoothPeripheral peripheral) {
        Objects.requireNonNull(peripheralAddress, "no valid address provided");
        Objects.requireNonNull(peripheral, "no valid peripheral provided");

        String deviceAddressString = peripheralAddress.replace(":", "_");
        peripheralsMap.put(deviceAddressString, peripheral);
    }

    void removePeripheral(@NotNull String peripheralAddress) {
        Objects.requireNonNull(peripheralAddress, "no valid address provided");

        String deviceAddressString = peripheralAddress.replace(":", "_");
        peripheralsMap.remove(deviceAddressString);
    }

    void addCentral(@NotNull BluetoothCentral central) {
        Objects.requireNonNull(central,"no valid central provided");
        centralList.add(central);
    }
}

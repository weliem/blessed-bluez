package com.welie.blessed;

import com.welie.blessed.internal.Handler;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.Properties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class BluezSignalHandler {
    private static final String TAG = BluezSignalHandler.class.getSimpleName();
    private static final Logger logger = Logger.getLogger(TAG);

    private static BluezSignalHandler instance = null;
    private DBusConnection dbusConnection;
    private final Handler handler = new Handler("BluezSignalHandler");

    private final Map<String, BluetoothPeripheral> devicesMap = new ConcurrentHashMap<>();
    private final List<BluetoothCentral> centralList = new ArrayList<>();

    static synchronized BluezSignalHandler createInstance(DBusConnection dbusConnection) {
        if (instance == null) {
            instance = new BluezSignalHandler(dbusConnection);
        }
        return instance;
    }

    public static synchronized BluezSignalHandler getInstance() {
        if (instance == null) {
            logger.severe("Using getInstance when no BluezSignalHandler has been created");
        }
        return instance;
    }

    private final AbstractPropertiesChangedHandler signalHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(final Properties.PropertiesChanged propertiesChanged) {
            // Make sure the propertiesChanged is not empty. Note that we also get called because of propertiesRemoved.
            if (propertiesChanged.getPropertiesChanged().isEmpty()) return;

            handler.post(() -> {
                // Send the signal to all centrals
                for (BluetoothCentral central : centralList) {
                    central.handleSignal(propertiesChanged);
                }

                // If it came from a device, send it to the right peripheral
                String path = propertiesChanged.getPath();
                Set<String> devices = devicesMap.keySet();
                for (String deviceAddress : devices) {
                    if (path.contains(deviceAddress)) {
                        devicesMap.get(deviceAddress).handleSignal(propertiesChanged);
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
            logger.severe("Error registering scan property handler");
            logger.severe(e.toString());
        }
    }

    private void registerPropertyHandler(AbstractPropertiesChangedHandler handler) throws DBusException {
        dbusConnection.addSigHandler(handler.getImplementationClass(), handler);
    }

    void addDevice(String deviceAddress, BluetoothPeripheral peripheral) {
        String deviceAddressString = deviceAddress.replace(":", "_");
        devicesMap.put(deviceAddressString, peripheral);
    }

    void removeDevice(String deviceAddress) {
        String deviceAddressString = deviceAddress.replace(":", "_");
        devicesMap.remove(deviceAddressString);
    }

    void addCentral(BluetoothCentral central) {
        centralList.add(central);
    }
}

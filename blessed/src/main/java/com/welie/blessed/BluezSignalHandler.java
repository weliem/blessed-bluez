package com.welie.blessed;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.Properties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BluezSignalHandler {
    private static final String TAG = BluezSignalHandler.class.getSimpleName();

    private static BluezSignalHandler instance = null;
    private DBusConnection dbusConnection;

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
            HBLogger.e(TAG, "Using getInstance when no BluezSignalHandler has been created");
        }
        return instance;
    }

    private final AbstractPropertiesChangedHandler signalHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(Properties.PropertiesChanged propertiesChanged) {
            // Send the signal to all centrals
            for(BluetoothCentral central : centralList) {
                  central.handleSignal(propertiesChanged);
            }

            // Send to device adapters if it is for a device
            String path = propertiesChanged.getPath();
            Set<String> devices = devicesMap.keySet();

            for(String deviceAddress : devices) {
                if(path.contains(deviceAddress)) {
                    devicesMap.get(deviceAddress).handleSignal(propertiesChanged);
                }
            }
        }
    };


    private BluezSignalHandler(DBusConnection dBusConnection) {
        try {
            this.dbusConnection = dBusConnection;
            registerPropertyHandler(signalHandler);
        } catch (DBusException e) {
            HBLogger.e(TAG,"Error registering scan property handler");
            HBLogger.e(TAG, e);
        }
    }

    private void registerPropertyHandler(AbstractPropertiesChangedHandler handler) throws DBusException {
        dbusConnection.addSigHandler(handler.getImplementationClass(), handler);
    }


    void addDevice(String deviceAddress, BluetoothPeripheral peripheral) {
        String deviceAddressString = deviceAddress.replace(":","_");
        devicesMap.put(deviceAddressString, peripheral);
        HBLogger.i(TAG, String.format("Registered %d peripherals", devicesMap.size()));
    }


    void removeDevice(String deviceAddress) {
        String deviceAddressString = deviceAddress.replace(":","_");
        devicesMap.remove(deviceAddressString);
        HBLogger.i(TAG, String.format("Registered %d peripherals", devicesMap.size()));
    }


    void addCentral(BluetoothCentral central) {
        centralList.add(central);
    }
}

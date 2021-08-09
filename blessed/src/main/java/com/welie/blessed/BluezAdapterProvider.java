package com.welie.blessed;

import com.welie.blessed.bluez.BluezAdapter;
import com.welie.blessed.bluez.DbusHelper;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

class BluezAdapterProvider {

    private static final String TAG = BluezAdapterProvider.class.getSimpleName();
    private final Logger logger = LoggerFactory.getLogger(TAG);

    public final @Nullable DBusConnection dBusConnection;
    public final @Nullable BluezAdapter adapter;

    public BluezAdapterProvider() {
        DBusConnection tempConnection;
        try {
            tempConnection = DBusConnection.newConnection(DBusConnection.DBusBusType.SYSTEM);

            // Make sure the thread pool is 1, so that we are sure that message arrive in the order they were sent
            tempConnection.changeThreadCount((byte) 1);
        } catch (DBusException e) {
            logger.error("Dbus connection failed", e);
            dBusConnection = null;
            adapter = null;
            return;
        }
        dBusConnection = tempConnection;
        this.adapter = chooseBluezAdapter(DbusHelper.findBluezAdapters(dBusConnection));
        BluezSignalHandler.createInstance(dBusConnection);
    }

    /**
     * Pick the adapter with the highest index, if there are more than one
     *
     * @param adapters list of adapters
     * @return the chosen BluezAdapter or null if adapters was empty
     */
    private @Nullable BluezAdapter chooseBluezAdapter(@NotNull List<@NotNull BluezAdapter> adapters) {
        Objects.requireNonNull(adapters);
        if (adapters.isEmpty()) return null;

        adapters.sort(Comparator.comparing(BluezAdapter::getDeviceName));
        return adapters.get(adapters.size() - 1);
    }
}

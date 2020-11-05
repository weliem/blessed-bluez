package com.welie.blessed.bluez;

import org.bluez.Adapter1;
import org.bluez.AgentManager1;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.Introspectable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Various DBUS related helper methods.
 * @author hypfvieh
 *
 */
public final class DbusHelper {

    private static final Logger LOGGER = Logger.getLogger(DbusHelper.class.getSimpleName());
    private static final String BLUEZ_PATH = "/org/bluez";

    private DbusHelper() {

    }

    /**
     * Find all &lt;node&gt;-Elements in DBUS Introspection XML and extracts the value of the 'name' attribute.
     * @param _connection the dbus connection
     * @param _path dbus-path-to-introspect
     * @return Set of String, maybe empty but never null
     */
    public static Set<String> findNodes(DBusConnection _connection, String _path) {
        Set<String> foundNodes = new LinkedHashSet<>();
        if (_connection == null || _path.length() == 0) {
            return foundNodes;
        }
        try {
            Introspectable remoteObject = _connection.getRemoteObject("org.bluez", _path, Introspectable.class);
            String introspect = remoteObject.Introspect();
            Document doc = XmlHelper.parseXmlString(introspect);
            NodeList nodes = XmlHelper.applyXpathExpressionToDocument("/node/node", doc);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element) {
                    Element elem = (Element) nodes.item(i);
                    foundNodes.add(elem.getAttribute("name"));
                }
            }
            return foundNodes;
        } catch (DBusException _ex) {
            LOGGER.info(String.format("Exception while search DBus %s", _ex));
        } catch (IOException _ex) {
            LOGGER.severe(String.format("Exception while applying Xpath to introspection result: %s", _ex));
        } catch (Exception _ex) {
            LOGGER.severe(String.format("Critical error while reading DBUS response (maybe no bluetoothd daemon running?): %s", _ex));
        }
        return foundNodes;
    }

    /**
     * Creates an java object from a bluez dbus response.
     * @param _connection Dbus connection to use
     * @param _path dbus request path
     * @param _objClass interface class to use
     * @param <T> some class/interface implementing/extending {@link DBusInterface}
     * @return the created object or null on error
     */
    public static <T extends DBusInterface> T getRemoteObject(DBusConnection _connection, String _path, Class<T> _objClass) {
        try {
            return _connection.getRemoteObject("org.bluez", _path, _objClass);
        } catch (DBusException _ex) {
            LOGGER.severe(String.format("Error while converting dbus response to object: %s", _ex));
        }
        return null;
    }

    public static @NotNull List<@NotNull BluezAdapter> findBluezAdapters(DBusConnection _connection) {
        final Map<String, BluezAdapter> bluetoothAdaptersByAdapterName = new LinkedHashMap<>();

        Set<String> nodes = DbusHelper.findNodes(_connection, BLUEZ_PATH);
        for (String hci : nodes) {
            Adapter1 adapter1 = DbusHelper.getRemoteObject(_connection, BLUEZ_PATH + "/" + hci, Adapter1.class);
            if (adapter1 != null) {
                BluezAdapter bluetoothAdapter = new BluezAdapter(adapter1, BLUEZ_PATH + "/" + hci, _connection);
                bluetoothAdaptersByAdapterName.put(hci, bluetoothAdapter);
            }
        }

        return new ArrayList<>(bluetoothAdaptersByAdapterName.values());
    }

    public static @Nullable BluezAgentManager getBluezAgentManager(DBusConnection _connection) {
        BluezAgentManager bluetoothAgentManager = null;
        AgentManager1 agentManager1 = DbusHelper.getRemoteObject(_connection, BLUEZ_PATH, AgentManager1.class);
        if (agentManager1 != null) {
            bluetoothAgentManager = new BluezAgentManager(agentManager1, agentManager1.getObjectPath(), _connection);
        }
        return bluetoothAgentManager;
    }
}

package com.welie.blessed.bluez;

import org.bluez.AgentManager1;
import org.bluez.exceptions.BluezAlreadyExistsException;
import org.bluez.exceptions.BluezDoesNotExistException;
import org.bluez.exceptions.BluezInvalidArgumentsException;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.interfaces.DBusInterface;

public class BluezAgentManager extends AbstractBluetoothObject  {

    private final AgentManager1 rawAgentManager;

    public BluezAgentManager(AgentManager1 _agentManager, String _dbusPath, DBusConnection _dbusConnection) {
        super(BluezDeviceType.AGENT_MANAGER, _dbusConnection, _dbusPath);
        rawAgentManager = _agentManager;
    }

    @Override
    protected Class<? extends DBusInterface> getInterfaceClass() {
        return AgentManager1.class;
    }

    /**
     * <b>From bluez documentation:</b><br>
     * <br>
     * This registers an agent handler.<br>
     * <br>
     * The object path defines the path of the agent<br>
     * that will be called when user input is needed.<br>
     * <br>
     * Every application can register its own agent and<br>
     * for all actions triggered by that application its<br>
     * agent is used.<br>
     * <br>
     * It is not required by an application to register<br>
     * an agent. If an application does chooses to not<br>
     * register an agent, the default agent is used. This<br>
     * is on most cases a good idea. Only application<br>
     * like a pairing wizard should register their own<br>
     * agent.<br>
     * <br>
     * An application can only register one agent. Multiple<br>
     * agents per application is not supported.<br>
     * <br>
     * The capability parameter can have the values<br>
     * "DisplayOnly", "DisplayYesNo", "KeyboardOnly",<br>
     * "NoInputNoOutput" and "KeyboardDisplay" which<br>
     * reflects the input and output capabilities of the<br>
     * agent.<br>
     * <br>
     * If an empty string is used it will fallback to<br>
     * "KeyboardDisplay".<br>
     * <br>
     *
     * @param bluetoothAgent
     * @param _capability
     *
     * @throws BluezInvalidArgumentsException when argument is invalid
     * @throws BluezAlreadyExistsException when item already exists
     */
    public void registerAgent(PairingAgent bluetoothAgent, String _capability) throws BluezInvalidArgumentsException, BluezAlreadyExistsException {
        rawAgentManager.RegisterAgent(new DBusPath(bluetoothAgent.getDbusPath()), _capability);
    }


    /**
     * <b>From bluez documentation:</b><br>
     * <br>
     * This unregisters the agent that has been previously<br>
     * registered. The object path parameter must match the<br>
     * same value that has been used on registration.<br>
     * <br>
     *
     * @param bluetoothAgent
     *
     * @throws BluezDoesNotExistException when item does not exist
     */
    public void unregisterAgent(PairingAgent bluetoothAgent) throws BluezDoesNotExistException {
        rawAgentManager.UnregisterAgent(new DBusPath(bluetoothAgent.getDbusPath()));
    }

    /**
     * <b>From bluez documentation:</b><br>
     * <br>
     * This requests is to make the application agent<br>
     * the default agent. The application is required<br>
     * to register an agent.<br>
     * <br>
     * Special permission might be required to become<br>
     * the default agent.<br>
     * <br>
     *
     * @param bluetoothAgent
     *
     * @throws BluezDoesNotExistException when item does not exist
     */
    public void requestDefaultAgent(PairingAgent bluetoothAgent) throws BluezDoesNotExistException {
        rawAgentManager.RequestDefaultAgent((new DBusPath(bluetoothAgent.getDbusPath())));
    }
}

package com.welie.blessed.bluez;

import com.welie.blessed.HBLogger;
import org.bluez.Agent1;
import org.bluez.exceptions.BluezCanceledException;
import org.bluez.exceptions.BluezRejectedException;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.UInt32;

public class PairingAgent extends AbstractBluetoothObject implements Agent1 {

    private static String TAG = "Agent";
    private PairingDelegate pairingDelegate;

    // Constructor for creating a new agent. This will create a new object on the DBus
    public PairingAgent(String _dbusPath, DBusConnection _dbusConnection, PairingDelegate pairingDelegate) {
        super(BluezDeviceType.AGENT, _dbusConnection, _dbusPath);
        this.pairingDelegate = pairingDelegate;

         try {
            _dbusConnection.exportObject(_dbusPath, this);
        } catch (DBusException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected Class<? extends DBusInterface> getInterfaceClass() {
        return Agent1.class;
    }

    public void Release() {

    }

    public String RequestPinCode(DBusPath _device) throws BluezRejectedException, BluezCanceledException {
        HBLogger.i(TAG,"called agent RequestPinCode");
        return null;
    }

    public void DisplayPinCode(DBusPath _device, String _pincode) throws BluezRejectedException, BluezCanceledException {
        HBLogger.i(TAG,"called agent DisplayPinCode");
    }

    public UInt32 RequestPasskey(DBusPath _device) throws BluezRejectedException, BluezCanceledException {
        // Ask delegate for passkey
        String passKeyString = pairingDelegate.requestPassCode(path2deviceAddress(_device));
        UInt32 passkey = new UInt32(passKeyString);
        return passkey;
    }

    public void DisplayPasskey(DBusPath _device, UInt32 _passkey, UInt16 _entered) {
        HBLogger.i(TAG,"called agent DisplayPasskey");
    }

    public void RequestConfirmation(DBusPath _device, UInt32 _passkey) throws BluezRejectedException, BluezCanceledException {
        HBLogger.i(TAG,"called agent RequestConfirmation");
    }

    public void RequestAuthorization(DBusPath _device) throws BluezRejectedException, BluezCanceledException {
        HBLogger.i(TAG,"called agent RequestAuthorization");
    }

    public void AuthorizeService(DBusPath _device, String _uuid) throws BluezRejectedException, BluezCanceledException {
        HBLogger.i(TAG,"called agent AuthorizeService");
    }

    public void Cancel() {

    }

    public boolean isRemote() {
        return false;
    }

    public String getObjectPath() {
        return getDbusPath();
    }


    String path2deviceAddress(DBusPath device) {
        String[] pathElements = device.getPath().split("/");
        String deviceName = pathElements[pathElements.length-1];
        String deviceAddress = deviceName.substring(4).replace("_", ":");
        return  deviceAddress;
    }
}

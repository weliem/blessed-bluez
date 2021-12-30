package com.welie.blessed.bluez;

import org.bluez.Device1;
import org.bluez.GattService1;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.DBusMap;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.UInt32;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.Map.Entry;

/**
 * Wrapper class which represents a remote bluetooth device.
 * @author hypfvieh
 *
 */
public class BluezDevice extends AbstractBluetoothObject {

    private final Device1 rawdevice;
    private final BluezAdapter adapter;

    private final Map<UUID, BluezGattService> servicesByUuid = new LinkedHashMap<>();

    public BluezDevice(Device1 _device, BluezAdapter _adapter, String _dbusPath, DBusConnection _dbusConnection) {
        super(BluezDeviceType.DEVICE, _dbusConnection, _dbusPath);
        rawdevice = _device;
        adapter = _adapter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Class<? extends DBusInterface> getInterfaceClass() {
        return Device1.class;
    }

    /**
     * Return the list of available {@link BluezGattService}s.<br>
     * Will start a query if no list was gathered before.<br>
     * To re-scan for services use {@link #refreshGattServices()}.
     * @return List, maybe empty but never null
     */
    public List<BluezGattService> getGattServices() {
        if (servicesByUuid.isEmpty()) {
            refreshGattServices();
        }

        return new ArrayList<>(servicesByUuid.values());
    }

    /**
     * Re-queries the list of available {@link BluezGattService}'s on this device.
     */
    public void refreshGattServices() {
        servicesByUuid.clear();

        Set<String> findNodes = DbusHelper.findNodes(getDbusConnection(), getDbusPath());
        Map<String, GattService1> remoteObjects = getRemoteObjects(findNodes, getDbusPath(), GattService1.class);
        for (Entry<String, GattService1> entry : remoteObjects.entrySet()) {
            BluezGattService bluetoothGattService = new BluezGattService(entry.getValue(), this, entry.getKey(), getDbusConnection());
            servicesByUuid.put(bluetoothGattService.getUuid(), bluetoothGattService);
        }
    }

    /**
     * Get the given {@link BluezGattService} instance by UUID.
     * @param uuid uuid
     * @return {@link BluezGattService}, maybe null if not found
     */
    public BluezGattService getGattServiceByUuid(UUID uuid) {
        if (servicesByUuid.isEmpty()) {
            refreshGattServices();
        }
        return servicesByUuid.get(uuid);
    }

    /**
     * Get {@link BluezAdapter} object where this {@link BluezDevice} object belongs to.
     * @return adapter
     */
    public BluezAdapter getAdapter() {
        return adapter;
    }


    /**
     * Get the raw {@link Device1} object wrapped by this {@link BluezDevice} object.
     * @return device
     */
    public Device1 getRawDevice() {
        return rawdevice;
    }

    /**
     * True if incoming connections are rejected, false otherwise.
     * @return maybe null if feature is not supported
     */
    public Boolean isBlocked() {
        return getTyped("Blocked", Boolean.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * If set to true any incoming connections from the<br>
     * device will be immediately rejected. Any device<br>
     * drivers will also be removed and no new ones will<br>
     * be probed as long as the device is blocked
     * </p>
     * @param _blocked set blocked status
     */
    public void setBlocked(Boolean _blocked) {
        setTyped("Blocked", _blocked);
    }

    /**
     * True if the remote device is trusted, false otherwise.
     * @return maybe null if feature is not supported
     */
    public Boolean isTrusted() {
        return getTyped("Trusted", Boolean.class);
    }

    /**
     * Set to true to trust the connected device, or to false if you don't.<br>
     * Default is false.
     * @param _trusted set trusted
     */
    public void setTrusted(boolean _trusted) {
        setTyped("Trusted", _trusted);
    }

    /**
     * The current name alias for the remote device.
     * @return alias name
     */
    public String getAlias() {
        return getTyped("Alias", String.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * The name alias for the remote device. The alias can<br>
     * be used to have a different friendly name for the<br>
     * remote device.<br>
     * In case no alias is set, it will return the remote<br>
     * device name. Setting an empty string as alias will<br>
     * convert it back to the remote device name.
     * </p>
     * @param _alias alias name to set
     */
    public void setAlias(String _alias) {
        setTyped("Alias", _alias);
    }

    /**
     * The Advertising Data Flags of the remote device.<br>
     * <b>EXPERIMENTAL</b>
     *
     * @return byte array maybe null
     */
    public byte[] getAdvertisingFlags() {
        List<?> typed = getTyped("AdvertisingFlags", ArrayList.class);
        if (typed != null) {
            return byteListToByteArray(typed);
        }
        return null;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * List of 128-bit UUIDs that represents the available
     * remote services.
     * </p>
     * @return string array of UUIDs, maybe null
     */
    @NotNull
    public List<@NotNull UUID> getUuids() {
        List<@NotNull UUID> result = new ArrayList<>();
        try {
            List<String> typed = getTyped("UUIDs", ArrayList.class);
            if (typed != null) {
                typed.stream().map(UUID::fromString).forEach(result::add);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * True if device is connected, false otherwise.
     * @return maybe null if feature is not supported
     */
    public Boolean isConnected() {
        return getTyped("Connected", Boolean.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Set to true if the device only supports the pre-2.1<br>
     * pairing mechanism. This property is useful during<br>
     * device discovery to anticipate whether legacy or<br>
     * simple pairing will occur if pairing is initiated.<br>
     * Note that this property can exhibit false-positives<br>
     * in the case of Bluetooth 2.1 (or newer) devices that<br>
     * have disabled Extended Inquiry Response support.
     * </p>
     * @return maybe null if feature is not supported
     */
    public Boolean isLegacyPairing() {
        return getTyped("LegacyPairing", Boolean.class);
    }

    /**
     * True if the device is currently paired with another device. False otherwise.
     * @return boolean, maybe null
     */
    public Boolean isPaired() {
        return getTyped("Paired", Boolean.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Indicate whether or not service discovery has been
     * resolved.
     * </p>
     * @return maybe null if feature is not supported
     */
    public Boolean isServicesResolved() {
        return getTyped("ServicesResolved", Boolean.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Service advertisement data. Keys are the UUIDs in
     * string format followed by its byte array value.
     * </p>
     * @return map of string/bytearray, maybe null
     */
    public @NotNull Map<@NotNull String, byte[]> getServiceData() {
        Map<@NotNull String, byte[]> result = new HashMap<>();
        try {
            return getTyped("ServiceData", DBusMap.class);
        } catch (Exception e) {
            return result;
        }
    }

    public Map<String, byte[]> getAdvertisementData() {
        return getTyped("AdvertisingData", DBusMap.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Manufacturer specific advertisement data. Keys are
     * 16 bits Manufacturer ID followed by its byte array
     * value.
     * </p>
     * @return map of uint16/bytearray, maybe null
     */
    public @NotNull Map<Integer, byte[]> getManufacturerData() {
        Map<Integer, byte[]> result = new HashMap<>();

        try {
            // Convert manufacturer data
            final Map<UInt16, byte[]> deviceManufacturerData = getTyped("ManufacturerData", DBusMap.class);
            if(deviceManufacturerData != null) {
                deviceManufacturerData.forEach((key, value) -> result.put(key.intValue(), value));
            }
            return result;
        } catch (Exception e) {
            return result;
        }
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Received Signal Strength Indicator of the remote
     * device (inquiry or advertising).
     * </p>
     * @return short, maybe null
     */
    public Short getRssi() {
        Short rssi = null;
        try {
            rssi = getTyped("RSSI", Short.class);
        } catch (DBusExecutionException ignored) {
        }
        return rssi;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Advertised transmitted power level (inquiry or
     * advertising).
     * </p>
     * @return short, maybe null
     */
    public Short getTxPower() {
        return getTyped("TxPower", Short.class);
    }

    /**
     * Returns the remote devices bluetooth (MAC) address.
     * @return mac address, maybe null
     */
    public String getAddress() {
        String address = null;
        try {
            address = getTyped("Address", String.class);
        } catch (DBusExecutionException ignored) {
        }
        return address;
    }

    /**
     * Returns address type (public/random) of the remote devices
     * @return address type, maybe null
     */
    public String getAddressType() {
        String addressType = null;
        try {
            addressType = getTyped("AddressType", String.class);
        } catch (DBusExecutionException ignored) {
        }
        return addressType;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Proposed icon name according to the freedesktop.org
     * icon naming specification.
     * </p>
     * @return icon name, maybe null
     */
    public String getIcon() {
        return getTyped("Icon", String.class);
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * Remote Device ID information in modalias format
     * used by the kernel and udev.
     * </p>
     * @return modalias string, maybe null
     */
    public String getModAlias() {
        return getTyped("Modalias", String.class);
    }

    /**
     * Get the bluetooth device name.<br>
     * This may fail if you not connected to the device, or if the device does not support this operation.<br>
     * If no name could be retrieved, the alias will be used.<br><br>
     *
     * <b>From bluez Documentation:</b>
     * <p>
     * The Bluetooth remote name. This value can not be<br>
     * changed. Use the Alias property instead.<br>
     * This value is only present for completeness. It is<br>
     * better to always use the Alias property when<br>
     * displaying the devices name.<br>
     * If the Alias property is unset, it will reflect<br>
     * this value which makes it more convenient.
     * </p>
     * @return name, maybe null
     */
    public String getName() {
        String name = null;
        try {
            name = getTyped("Name", String.class);
        } catch (DBusExecutionException ignored) {
        }
        return name;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * External appearance of device, as found on GAP service.
     * </p>
     * @return integer, maybe null
     */
    public Integer getAppearance() {
        UInt16 typed = getTyped("Appearance", UInt16.class);
        return typed != null ? typed.intValue() : null;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * The Bluetooth class of device of the remote device.
     * </p>
     * @return integer, maybe null
     */
    public Integer getBluetoothClass() {
        UInt32 typed = getTyped("Class", UInt32.class);
        return typed != null ? typed.intValue() : null;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * This is a generic method to connect any profiles<br>
     * the remote device supports that can be connected<br>
     * to and have been flagged as auto-connectable on<br>
     * our side. If only subset of profiles is already<br>
     * connected it will try to connect currently disconnected
     * ones.
     * </p>
     */
    public void connect() throws BluezFailedException, BluezAlreadyConnectedException, BluezNotReadyException, BluezInProgressException {
        rawdevice.Connect();
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * This method gracefully disconnects all connected<br>
     * profiles and then terminates low-level ACL connection.<br>
     * ACL connection will be terminated even if some profiles<br>
     * were not disconnected properly e.g. due to misbehaving<br>
     * device.<br><br>
     * This method can be also used to cancel a preceding<br>
     * Connect call before a reply to it has been received.
     * </p>
     * @return true if disconnected false otherwise
     */
    public boolean disconnect() {
        try {
            rawdevice.Disconnect();
            return true;
        } catch (BluezNotConnectedException ignored) {
        }
        return !isConnected();
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * This method connects a specific profile of this<br>
     * device. The UUID provided is the remote service<br>
     * UUID for the profile.
     * </p>
     *
     * @param _uuid profile uuid
     * @return true if connected to given profile, false otherwise
     */
    public boolean connectProfile(UUID _uuid) {
        try {
            rawdevice.ConnectProfile(_uuid.toString());
            return true;
        } catch (BluezFailedException | BluezInProgressException | BluezInvalidArgumentsException
                | BluezNotAvailableException | BluezNotReadyException _ex) {
            return false;
        }
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * This method disconnects a specific profile of<br>
     * this device. The profile needs to be registered<br>
     * client profile.<br><br>
     * There is no connection tracking for a profile, so<br>
     * as long as the profile is registered this will always<br>
     * succeed.
     * </p>
     *
     * @param _uuid profile uuid
     * @return true if profile disconnected, false otherwise
     */
    public boolean disconnectProfile(UUID _uuid) {
        try {
            rawdevice.DisconnectProfile(_uuid.toString());
        } catch (BluezFailedException | BluezInProgressException | BluezInvalidArgumentsException
                | BluezNotSupportedException _ex) {
            return false;
        }

        return true;
    }

    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * This method will connect to the remote device,<br>
     * initiate pairing and then retrieve all SDP records<br>
     * (or GATT primary services).<br><br>
     * If the application has registered its own agent,<br>
     * then that specific agent will be used. Otherwise<br>
     * it will use the default agent.<br><br>
     * Only for applications like a pairing wizard it<br>
     * would make sense to have its own agent. In almost<br>
     * all other cases the default agent will handle<br>
     * this just fine.<br><br>
     * In case there is no application agent and also<br>
     * no default agent present, this method will fail.
     * </p>
     */
    public void pair() throws BluezInProgressException, BluezInvalidArgumentsException, BluezFailedException, BluezAuthenticationFailedException, BluezAlreadyExistsException, BluezAuthenticationCanceledException, BluezAuthenticationRejectedException, BluezAuthenticationTimeoutException, BluezConnectionAttemptFailedException {
        rawdevice.Pair();
    }


    /**
     * <b>From bluez Documentation:</b>
     * <p>
     * This method can be used to cancel a pairing<br>
     * operation initiated by the Pair method.
     * </p>
     * @return true if cancel succeeds, false otherwise
     */
    public boolean cancelPairing() {
        try {
            rawdevice.CancelPairing();
            return true;
        } catch (BluezDoesNotExistException | BluezFailedException _ex) {
            return false;
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [device=" + rawdevice + ", adapter=" + adapter.getDbusPath() + ", getBluetoothType()=" + getBluetoothType().name() + ", getDbusPath()=" + getDbusPath() + "]";
    }
}

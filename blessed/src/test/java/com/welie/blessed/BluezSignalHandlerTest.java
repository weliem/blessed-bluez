package com.welie.blessed;

import org.freedesktop.dbus.DBusMap;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;

import java.util.*;

import static com.welie.blessed.BluetoothPeripheral.*;
import static com.welie.blessed.BluetoothPeripheral.BLUEZ_DEVICE_INTERFACE;


@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BluezSignalHandlerTest {

    @Mock
    DBusConnection dBusConnection;

    @Mock
    BluetoothCentralManager central;

    @Mock
    BluetoothPeripheral peripheral;

    private static final UUID BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb");
    private static final String DUMMY_MAC_ADDRESS_BLP = "12:34:56:65:43:21";
    private static final String DUMMY_MAC_ADDRESS_PATH_BLP = "/org/bluez/hci0/dev_" + DUMMY_MAC_ADDRESS_BLP.replace(":", "_");
    private static final String DUMMY_PERIPHERAL_NAME_BLP = "Beurer BM57";

    @Test
    void When_a_central_registers_then_it_will_receive_interfaceAdded_signals_synchronously() throws DBusException {
        // Given
        BluezSignalHandler signalHandler = BluezSignalHandler.createInstance(dBusConnection);

        // When
        signalHandler.addCentral(central);
        signalHandler.interfacesAddedHandler.handle(getInterfacesAddedNewBlpDevice());

        // Then
        verify(central).handleInterfaceAddedForDevice(any(), anyMap());
    }

    @Test
    void When_a_central_registers_then_it_will_receive_propertiesChanged_signals_synchronously() throws DBusException {
        // Given
        BluezSignalHandler signalHandler = BluezSignalHandler.createInstance(dBusConnection);

        // When
        signalHandler.addCentral(central);
        signalHandler.signalHandler.handle(getPropertiesChangedSignalWhileScanning());

        // Then
        verify(central).handleSignal(any());
    }

    @Test
    void When_a_peripheral_registers_then_it_will_receive_propertiesChanged_signals_from_itself_synchronously() throws DBusException {
        // Given
        BluezSignalHandler signalHandler = BluezSignalHandler.createInstance(dBusConnection);

        // When
        signalHandler.addPeripheral(DUMMY_MAC_ADDRESS_BLP, peripheral);
        signalHandler.signalHandler.handle(getPropertiesChangedSignalWhileScanning());

        // Then
        verify(peripheral).handleSignal(any());
    }

    @Test
    void When_a_peripheral_registers_then_it_will_NOT_receive_interfaceAdded_signals() throws DBusException {
        // Given
        BluezSignalHandler signalHandler = BluezSignalHandler.createInstance(dBusConnection);

        // When
        signalHandler.addPeripheral(DUMMY_MAC_ADDRESS_BLP, peripheral);
        signalHandler.interfacesAddedHandler.handle(getInterfacesAddedNewBlpDevice());

        // Then
        verify(peripheral, never()).handleSignal(any());
    }

    @Test
    void When_a_peripheral_registers_then_it_will_receive_propertiesChange_signals_from_characteristics_of_the_peripheral() throws DBusException {
        // Given
        BluezSignalHandler signalHandler = BluezSignalHandler.createInstance(dBusConnection);

        // When
        signalHandler.addPeripheral(DUMMY_MAC_ADDRESS_BLP, peripheral);
        String path = "/org/bluez/hci0/dev_" + DUMMY_MAC_ADDRESS_BLP.replace(":", "_") + "/service0014/char0015";
        signalHandler.signalHandler.handle(getPropertiesChangedSignalCharacteristicUpdate(path, new byte[]{0x01}));

        // Then
        verify(peripheral).handleSignal(any());
    }

    @Test
    void When_a_peripheral_registers_then_it_will_NOT_receive_propertiesChange_signals_from_characteristics_from_other_peripheral() throws DBusException {
        // Given
        BluezSignalHandler signalHandler = BluezSignalHandler.createInstance(dBusConnection);

        // When
        signalHandler.addPeripheral(DUMMY_MAC_ADDRESS_BLP, peripheral);
        String path = "/org/bluez/hci0/dev_" + "12:12:12:12:12:12".replace(":", "_") + "/service0014/char0015";
        signalHandler.signalHandler.handle(getPropertiesChangedSignalCharacteristicUpdate(path, new byte[]{0x01}));

        // Then
        verify(peripheral, never()).handleSignal(any());
    }

    @NotNull
    private ObjectManager.InterfacesAdded getInterfacesAddedNewBlpDevice() throws DBusException {
        String objectPath = "/";
        DBusPath dBusPath = new DBusPath("/org/bluez/hci0/dev_00_11_22_33_44_55");
        HashMap<String, Map<String, Variant<?>>> interfaceAddedMap = new HashMap<>();
        Map<String, Variant<?>> interfaceMap = new HashMap<>();

        interfaceMap.put(PROPERTY_ADDRESS, new Variant<>(DUMMY_MAC_ADDRESS_BLP));
        interfaceMap.put(PROPERTY_NAME, new Variant<>(DUMMY_PERIPHERAL_NAME_BLP));
        interfaceMap.put(PROPERTY_CONNECTED, new Variant<>(false));
        interfaceMap.put(PROPERTY_SERVICES_RESOLVED, new Variant<>(false));
        interfaceMap.put(PROPERTY_PAIRED, new Variant<>(false));
        interfaceMap.put(PROPERTY_RSSI, new Variant<>(new Short("-50")));
        ArrayList<String> uuids = new ArrayList<>();
        uuids.add(BLP_SERVICE_UUID.toString());
        interfaceMap.put(PROPERTY_SERVICE_UUIDS, new Variant<>(uuids, "as"));

        // Build Manufacturer Data
        Object[][] testObjects = new Object[1][2];
        testObjects[0][0] = new UInt16(41);
        testObjects[0][1] = new Variant<>(new byte[]{0x10,0x20}, "ay");
        DBusMap<UInt16, byte[]> manufacturerData = new DBusMap<>(testObjects);
        interfaceMap.put(PROPERTY_MANUFACTURER_DATA, new Variant<>(manufacturerData, "a{qv}" ));

        // Build Service Data
        Map<String, Variant<?>> serviceData = new HashMap<>();
        serviceData.put(BLP_SERVICE_UUID.toString(), new Variant<>(new byte[]{0x54, 0x12}));
        interfaceMap.put(PROPERTY_SERVICE_DATA, new Variant<>(convertStringHashMapToDBusMap(serviceData), "a{sv}"));

        DBusMap<String, Variant<?>> finalInterfacesAddedMap = convertStringHashMapToDBusMap(interfaceMap);
        interfaceAddedMap.put(BLUEZ_DEVICE_INTERFACE, finalInterfacesAddedMap);
        return new ObjectManager.InterfacesAdded(objectPath,dBusPath, interfaceAddedMap);
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalWhileScanning() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_ADDRESS, new Variant<>(DUMMY_MAC_ADDRESS_BLP));
        propertiesChanged.put(PROPERTY_RSSI, new Variant<>(new Short("-32")));
        Object[][] testObjects = new Object[1][2];
        testObjects[0][0] = new UInt16(100);
        testObjects[0][1] = new Variant<>(new byte[]{0x10,0x20}, "ay");
        DBusMap<UInt16, byte[]> manufacturerData = new DBusMap<>(testObjects);
        propertiesChanged.put(PROPERTY_MANUFACTURER_DATA, new Variant<>(manufacturerData, "a{qv}" ));
        Map<String, Variant<?>> serviceData = new HashMap<>();
        serviceData.put(BLP_SERVICE_UUID.toString(), new Variant<>(new byte[]{0x44, 0x55}));
        propertiesChanged.put(PROPERTY_SERVICE_DATA, new Variant<>(convertStringHashMapToDBusMap(serviceData), "a{sv}"));
        String dBusPath = DUMMY_MAC_ADDRESS_PATH_BLP;
        return new Properties.PropertiesChanged(dBusPath, BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }


    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalCharacteristicUpdate(String path, byte[] value) throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_VALUE, new Variant<>(value, "ay"));
        return new Properties.PropertiesChanged(path, BLUEZ_CHARACTERISTIC_INTERFACE, propertiesChanged,new ArrayList() );
    }

    private DBusMap<String, Variant<?>> convertStringHashMapToDBusMap(Map<String, Variant<?>> source) {
        Objects.requireNonNull(source);
        Object[][] result = new Object[source.size()][2];
        int index = 0;
        for (Map.Entry<String, Variant<?>> entry : source.entrySet() ) {
            result[index][0]= entry.getKey();
            result[index][1] = entry.getValue();
            index++;
        }
        return new DBusMap<>(result);
    }
}

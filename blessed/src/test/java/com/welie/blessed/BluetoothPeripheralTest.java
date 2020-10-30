package com.welie.blessed;

import com.welie.blessed.bluez.BluezDevice;
import com.welie.blessed.bluez.BluezGattCharacteristic;
import com.welie.blessed.internal.Handler;
import com.welie.blessed.internal.InternalCallback;
import org.bluez.exceptions.BluezAlreadyConnectedException;
import org.bluez.exceptions.BluezFailedException;
import org.bluez.exceptions.BluezInProgressException;
import org.bluez.exceptions.BluezNotReadyException;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.welie.blessed.BluetoothGattCharacteristic.PROPERTY_INDICATE;
import static com.welie.blessed.BluetoothGattCharacteristic.PROPERTY_READ;
import static com.welie.blessed.BluetoothPeripheral.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BluetoothPeripheralTest {

    @Mock
    DBusConnection dBusConnection;

    @Mock
    BluezDevice bluezDevice;

    @Mock
    BluetoothCentral central;

    @Mock
    InternalCallback internalCallback;

    @Mock
    BluetoothPeripheralCallback peripheralCallback;

    Handler callbackHandler = new Handler("BluetoothPeripheralTest-callback");

    private static final String DUMMY_MAC_ADDRESS_BLP = "12:34:56:65:43:21";
    private static final String DUMMY_PERIPHERAL_NAME_BLP = "Beurer BM57";
    private static final UUID BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb");
    private static final UUID BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A35-0000-1000-8000-00805f9b34fb");

    @Test
    void When_creating_a_peripheral_with_null_for_the_central_parameter_then_a_NPE_is_thrown() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothPeripheral peripheral = new BluetoothPeripheral(null, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, DUMMY_MAC_ADDRESS_BLP, internalCallback, peripheralCallback, callbackHandler);
        });
    }

    @Test
    void When_creating_a_peripheral_with_null_for_the_deviceAddress_parameter_then_a_NPE_is_thrown() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothPeripheral peripheral = new BluetoothPeripheral(central, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, null, internalCallback, peripheralCallback, callbackHandler);
        });
    }

    @Test
    void When_creating_a_peripheral_with_null_for_the_internalCallback_parameter_then_a_NPE_is_thrown() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothPeripheral peripheral = new BluetoothPeripheral(central, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, DUMMY_MAC_ADDRESS_BLP, null, peripheralCallback, callbackHandler);
        });
    }

    @Test
    void When_creating_a_peripheral_with_null_for_the_handler_parameter_then_a_NPE_is_thrown() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothPeripheral peripheral = new BluetoothPeripheral(central, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, DUMMY_MAC_ADDRESS_BLP, internalCallback, peripheralCallback, null);
        });
    }

    @Test
    void Given_a_peripheral_when_connect_is_called_then_a_connection_is_attempted() throws BluezFailedException, BluezAlreadyConnectedException, BluezNotReadyException, BluezInProgressException {
        // Given
        BluetoothPeripheral peripheral = getPeripheral();

        // When
        peripheral.connect();

        // Then
        verify(bluezDevice).connect();
    }

    @Test
    void Given_a_connected_peripheral_when_connect_is_called_then_connected_is_called() throws BluezFailedException, BluezAlreadyConnectedException, BluezNotReadyException, BluezInProgressException {
        // Given
        BluetoothPeripheral peripheral = getPeripheral();

        doThrow(new BluezAlreadyConnectedException("already connected"))
                .when(bluezDevice)
                .connect();

        // When
        peripheral.connect();

        // Then
        verify(internalCallback).connected(peripheral);
    }

    @Test
    void Given_a_disconnected_peripheral_when_connect_is_called_and_bluez_not_ready_then_connectionFailed_is_called() throws BluezFailedException, BluezAlreadyConnectedException, BluezNotReadyException, BluezInProgressException {
        // Given
        BluetoothPeripheral peripheral = getPeripheral();

        doThrow(new BluezNotReadyException("not ready"))
                .when(bluezDevice)
                .connect();

        // When
        peripheral.connect();

        // Then
        verify(internalCallback).connectFailed(peripheral);
        assertEquals(STATE_DISCONNECTED, peripheral.getState());
    }

    // TODO, consider simply doing nothing when this happens....
    @Test
    void Given_a_disconnected_peripheral_when_connect_is_called_and_connectionInProgress_then_connectionFailed_is_called() throws BluezFailedException, BluezAlreadyConnectedException, BluezNotReadyException, BluezInProgressException {
        // Given
        BluetoothPeripheral peripheral = getPeripheral();

        doThrow(new BluezInProgressException("connection in progress"))
                .when(bluezDevice)
                .connect();

        // When
        peripheral.connect();

        // Then
        verify(internalCallback).connectFailed(peripheral);
        assertEquals(STATE_DISCONNECTED, peripheral.getState());
    }

    @Test
    void Given_a_disconnected_peripheral_when_connect_is_called_and_connectionFailed_then_connectionFailed_is_called() throws BluezFailedException, BluezAlreadyConnectedException, BluezNotReadyException, BluezInProgressException {
        // Given
        BluetoothPeripheral peripheral = getPeripheral();

        doThrow(new BluezFailedException("connect failed"))
                .when(bluezDevice)
                .connect();

        // When
        peripheral.connect();

        // Then
        verify(internalCallback).connectFailed(peripheral);
        assertEquals(STATE_DISCONNECTED, peripheral.getState());
    }

    @Test
    void Given_a_disconnected_peripheral_when_connect_is_called_and_dbusException_then_connectionFailed_is_called() throws BluezFailedException, BluezAlreadyConnectedException, BluezNotReadyException, BluezInProgressException {
        // Given
        BluetoothPeripheral peripheral = getPeripheral();

        doThrow(new DBusExecutionException("dbus exception"))
                .when(bluezDevice)
                .connect();

        // When
        peripheral.connect();

        // Then
        verify(internalCallback).connectFailed(peripheral);
        assertEquals(STATE_DISCONNECTED, peripheral.getState());
    }

    @Test
    void Given_connecting_a_peripheral_when_connected_signal_comes_in_then_a_connected_is_called() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();

        // Then
        verify(internalCallback).connected(peripheral);
        assertEquals(STATE_CONNECTED, peripheral.getState());
    }

    @Test
    void Given_a_connected_peripheral_when_disconnecting_then_disconnect_is_called() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();

        // When
        peripheral.disconnectBluezDevice();

        // Then
        verify(bluezDevice).disconnect();
        assertEquals(STATE_DISCONNECTING, peripheral.getState());
    }

    @Test
    void Given_a_connected_peripheral_when_disconnected_signal_comes_in_then_disconnect_is_sent() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();

        // When
        peripheral.disconnectBluezDevice();
        Thread.sleep(10);
        peripheral.handleSignal(getPropertiesChangedSignalDisconnected());

        // Then
        verify(internalCallback).disconnected(peripheral);
        assertEquals(STATE_DISCONNECTED, peripheral.getState());
    }

    @Test
    void Given_a_connected_peripheral_when_readCharacteristic_is_called_then_a_read_is_done() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ + PROPERTY_INDICATE,0 );
        BluezGattCharacteristic bluezGattCharacteristic = mock(BluezGattCharacteristic.class);
        when(bluezGattCharacteristic.getDbusPath()).thenReturn("/org/bluez/hci0/characteristic/" + BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID.toString());
        when(bluezGattCharacteristic.getUuid()).thenReturn(BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID.toString());
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);

        // When
        peripheral.readCharacteristic(characteristic);

        // Then
        verify(bluezGattCharacteristic).readValue(anyMap());
    }

    @NotNull
    private BluetoothPeripheral getConnectedPeripheral() throws InterruptedException, DBusException {
        // Given
        BluetoothPeripheral peripheral = getPeripheral();

        // When
        peripheral.connect();

        Thread.sleep(10);
        peripheral.handleSignal(getPropertiesChangedSignalConnected());
        return peripheral;
    }

    @NotNull
    private BluetoothPeripheral getPeripheral() {
        BluezSignalHandler.createInstance(dBusConnection);
        return new BluetoothPeripheral(central, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, DUMMY_MAC_ADDRESS_BLP, internalCallback, peripheralCallback, callbackHandler);
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalConnected() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_CONNECTED, new Variant<>(true));
        return new Properties.PropertiesChanged("/org/bluez/hci0", BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalDisconnected() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_CONNECTED, new Variant<>(false));
        return new Properties.PropertiesChanged("/org/bluez/hci0", BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }
}

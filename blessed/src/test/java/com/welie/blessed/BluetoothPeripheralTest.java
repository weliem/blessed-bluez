package com.welie.blessed;

import com.welie.blessed.bluez.BluezDevice;
import com.welie.blessed.bluez.BluezGattCharacteristic;
import com.welie.blessed.bluez.BluezGattService;
import com.welie.blessed.internal.Handler;
import com.welie.blessed.internal.InternalCallback;
import org.bluez.exceptions.*;
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

import java.util.*;

import static com.welie.blessed.BluetoothGattCharacteristic.*;
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
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(bluezGattCharacteristic).readValue(anyMap());
    }

    @Test
    void Given_a_connected_peripheral_when_a_characteristic_is_read_then_onCharacteristicUpdate_is_called() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        peripheral.services.add(characteristic.service);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        when(bluezGattCharacteristic.getDbusPath()).thenReturn("/org/bluez/hci0/dev_C0_26_DF_01_F2_72/service0014/char0015");
        when(bluezGattCharacteristic.getUuid()).thenReturn(BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID.toString());
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);
        byte[] value = new byte[]{0x01, 0x02};
        peripheral.handleSignal(getPropertiesChangedSignalCharacteristicUpdate(bluezGattCharacteristic.getDbusPath(), characteristic, value));
        Thread.sleep(50);

        // Then
        verify(peripheralCallback).onCharacteristicUpdate(peripheral, value, characteristic, GATT_SUCCESS);
    }

    @Test
    void Given_a_connected_peripheral_when_readCharacteristic_is_called_twice_then_a_read_is_done_twice() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);

        // When
        peripheral.readCharacteristic(characteristic);
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(bluezGattCharacteristic, times(2)).readValue(anyMap());
    }

    @Test
    void Given_a_disconnected_peripheral_when_readCharacteristic_is_called_then_no_read_is_done() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = mock(BluezGattCharacteristic.class);
        when(bluezGattCharacteristic.getDbusPath()).thenReturn("/org/bluez/hci0/characteristic/" + BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID.toString());
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(bluezGattCharacteristic, never()).readValue(anyMap());
    }

    @Test
    void Given_a_connected_peripheral_and_not_readable_characteristic_when_readCharacteristic_is_called_then_no_read_is_done() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_NOTIFY);
        BluezGattCharacteristic bluezGattCharacteristic = mock(BluezGattCharacteristic.class);
        when(bluezGattCharacteristic.getDbusPath()).thenReturn("/org/bluez/hci0/characteristic/" + BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID.toString());
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(bluezGattCharacteristic, never()).readValue(anyMap());
    }

    @Test
    void Given_a_connected_peripheral_when_readCharacteristic_is_called_with_not_existing_characteristic_then_a_read_is_not_called() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = mock(BluezGattCharacteristic.class);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(bluezGattCharacteristic, never()).readValue(anyMap());
    }

    @Test
    void Given_a_connected_peripheral_when_readCharacteristic_is_called_and_BluezFailedException_occurs_then_onCharacteristicUpdate_is_called_with_GATT_ERROR() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);
        when(bluezGattCharacteristic.readValue(anyMap())).thenThrow(BluezFailedException.class);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(peripheralCallback).onCharacteristicUpdate(peripheral,new byte[0], characteristic, GATT_ERROR);
    }

    @Test
    void Given_a_connected_peripheral_when_readCharacteristic_is_called_and_BluezNotPermittedException_occurs_then_onCharacteristicUpdate_is_called_with_GATT_READ_NOT_PERMITTED() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);
        when(bluezGattCharacteristic.readValue(anyMap())).thenThrow(BluezNotPermittedException.class);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(peripheralCallback).onCharacteristicUpdate(peripheral,new byte[0], characteristic, GATT_READ_NOT_PERMITTED);
    }

    @Test
    void Given_a_connected_peripheral_when_readCharacteristic_is_called_and_BluezNotAuthorizedException_occurs_then_onCharacteristicUpdate_is_called_with_GATT_INSUFFICIENT_AUTHENTICATION() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);
        when(bluezGattCharacteristic.readValue(anyMap())).thenThrow(BluezNotAuthorizedException.class);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(peripheralCallback).onCharacteristicUpdate(peripheral,new byte[0], characteristic, GATT_INSUFFICIENT_AUTHENTICATION);
    }

    @Test
    void Given_a_connected_peripheral_when_readCharacteristic_is_called_and_BluezNotSupportedException_occurs_then_onCharacteristicUpdate_is_called_with_GATT_REQUEST_NOT_SUPPORTED() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);
        when(bluezGattCharacteristic.readValue(anyMap())).thenThrow(BluezNotSupportedException.class);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(peripheralCallback).onCharacteristicUpdate(peripheral,new byte[0], characteristic, GATT_REQUEST_NOT_SUPPORTED);
    }

    @Test
    void Given_a_connected_peripheral_when_readCharacteristic_is_called_and_DBusExecutionException_occurs_then_onCharacteristicUpdate_is_called_with_GATT_ERROR() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_READ);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);
        when(bluezGattCharacteristic.readValue(anyMap())).thenThrow(DBusExecutionException.class);

        // When
        peripheral.readCharacteristic(characteristic);
        Thread.sleep(10);

        // Then
        verify(peripheralCallback).onCharacteristicUpdate(peripheral,new byte[0], characteristic, GATT_ERROR);
    }

    @Test
    void Given_a_connected_peripheral_when_writeCharacteristic_with_WRITE_TYPE_DEFAULT_is_called_then_a_write_is_done() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_WRITE);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);

        // When
        byte[] value = new byte[]{0x01,0x02,0x03};
        peripheral.writeCharacteristic(characteristic, value, WRITE_TYPE_DEFAULT);
        Thread.sleep(10);

        // Then
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(bluezGattCharacteristic).writeValue(valueCaptor.capture(),mapCaptor.capture());
        assertEquals("request", mapCaptor.getValue().get("type"));
        assertTrue(Arrays.equals(value, valueCaptor.getValue()));
    }

    @Test
    void Given_a_connected_peripheral_when_writeCharacteristic_with_WRITE_TYPE_NO_RESPONSE_is_called_then_a_write_is_done() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_WRITE_NO_RESPONSE);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);

        // When
        byte[] value = new byte[]{0x01,0x02,0x03};
        peripheral.writeCharacteristic(characteristic, value, WRITE_TYPE_NO_RESPONSE);
        Thread.sleep(10);

        // Then
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(bluezGattCharacteristic).writeValue(valueCaptor.capture(),mapCaptor.capture());
        assertEquals("command", mapCaptor.getValue().get("type"));
        assertTrue(Arrays.equals(value, valueCaptor.getValue()));
    }

    @Test
    void Given_a_connected_peripheral_when_writeCharacteristic_is_called_and_BluezNotPermittedException_occurs_then_onCharacteristicWrite_is_called_with_GATT_WRITE_NOT_PERMITTED() throws DBusException, InterruptedException {
        // Given
        BluetoothPeripheral peripheral = getConnectedPeripheral();
        BluetoothGattCharacteristic characteristic = getBluetoothGattCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, PROPERTY_WRITE);
        BluezGattCharacteristic bluezGattCharacteristic = getBluezGattCharacteristic();
        peripheral.characteristicMap.put(bluezGattCharacteristic.getDbusPath(), bluezGattCharacteristic);
        doThrow(new BluezNotPermittedException("not permitted"))
                .when(bluezGattCharacteristic)
                .writeValue(any(), anyMap());

        // When
        byte[] value = new byte[]{0x01,0x02,0x03};
        peripheral.writeCharacteristic(characteristic, value, WRITE_TYPE_DEFAULT);
        Thread.sleep(10);

        // Then
        ArgumentCaptor<BluetoothPeripheral> peripheralCaptor = ArgumentCaptor.forClass(BluetoothPeripheral.class);
        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<BluetoothGattCharacteristic> characteristicCaptor = ArgumentCaptor.forClass(BluetoothGattCharacteristic.class);
        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(peripheralCallback).onCharacteristicWrite(peripheralCaptor.capture(),valueCaptor.capture(), characteristicCaptor.capture(), statusCaptor.capture());
        assertEquals(GATT_WRITE_NOT_PERMITTED, statusCaptor.getValue());
        assertTrue(Arrays.equals(value, valueCaptor.getValue()));
    }

    @NotNull
    private BluezGattCharacteristic getBluezGattCharacteristic() {
        BluezGattCharacteristic bluezGattCharacteristic = mock(BluezGattCharacteristic.class);
        BluezGattService bluezGattService = mock(BluezGattService.class);
        when(bluezGattCharacteristic.getDbusPath()).thenReturn("/org/bluez/hci0/characteristic/" + BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID.toString());
        when(bluezGattCharacteristic.getUuid()).thenReturn(BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID.toString());
        when(bluezGattCharacteristic.getService()).thenReturn(bluezGattService);
        when(bluezGattService.getUuid()).thenReturn(BLP_SERVICE_UUID.toString());
        return bluezGattCharacteristic;
    }

    @NotNull
    private BluetoothGattCharacteristic getBluetoothGattCharacteristic(UUID serviceUUID, UUID characteristicUUID, int properties) {
        BluetoothGattService service = new BluetoothGattService(serviceUUID);
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(characteristicUUID, properties,0 );
        characteristic.setService(service);
        service.addCharacteristic(characteristic);
        return characteristic;
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
        return new Properties.PropertiesChanged("/org/bluez/hci0/dev_C0_26_DF_01_F2_72", BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalDisconnected() throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_CONNECTED, new Variant<>(false));
        return new Properties.PropertiesChanged("/org/bluez/hci0/dev_C0_26_DF_01_F2_72", BLUEZ_DEVICE_INTERFACE, propertiesChanged,new ArrayList() );
    }

    @NotNull
    private Properties.PropertiesChanged getPropertiesChangedSignalCharacteristicUpdate(String path, BluetoothGattCharacteristic characteristic, byte[] value) throws DBusException {
        Map<String, Variant<?>> propertiesChanged = new HashMap<>();
        propertiesChanged.put(PROPERTY_VALUE, new Variant<>(value, "ay"));
        return new Properties.PropertiesChanged(path, BLUEZ_CHARACTERISTIC_INTERFACE, propertiesChanged,new ArrayList() );
    }
}

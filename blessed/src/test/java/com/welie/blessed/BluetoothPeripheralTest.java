package com.welie.blessed;

import com.welie.blessed.bluez.BluezDevice;
import com.welie.blessed.internal.Handler;
import com.welie.blessed.internal.InternalCallback;
import org.bluez.exceptions.BluezAlreadyConnectedException;
import org.bluez.exceptions.BluezFailedException;
import org.bluez.exceptions.BluezInProgressException;
import org.bluez.exceptions.BluezNotReadyException;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @BeforeEach
    void setup() {
    }

    private static final String DUMMY_MAC_ADDRESS_BLP = "12:34:56:65:43:21";
    private static final String DUMMY_PERIPHERAL_NAME_BLP = "Beurer BM57";

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
    }

    @NotNull
    private BluetoothPeripheral getPeripheral() {
        BluezSignalHandler.createInstance(dBusConnection);
        return new BluetoothPeripheral(central, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, DUMMY_MAC_ADDRESS_BLP, internalCallback, peripheralCallback, callbackHandler);
    }
}

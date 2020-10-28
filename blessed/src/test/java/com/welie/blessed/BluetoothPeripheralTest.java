package com.welie.blessed;

import com.welie.blessed.bluez.BluezDevice;
import com.welie.blessed.internal.Handler;
import com.welie.blessed.internal.InternalCallback;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public class BluetoothPeripheralTest {

    @Mock
    BluezDevice bluezDevice;

    @Mock
    BluetoothCentral central;

    @Mock
    InternalCallback internalCallback;

    @Mock
    BluetoothPeripheralCallback peripheralCallback;
    @BeforeEach
    void setup() {
    }

    private static final String DUMMY_MAC_ADDRESS_BLP = "12:34:56:65:43:21";
    private static final String DUMMY_PERIPHERAL_NAME_BLP = "Beurer BM57";

    @Test
    void When_creating_a_peripheral_with_null_for_the_central_parameter_then_a_NPE_is_thrown() {
        Handler callbackHandler = new Handler("BluetoothPeripheralTest-callback");
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothPeripheral peripheral = new BluetoothPeripheral(null, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, DUMMY_MAC_ADDRESS_BLP, internalCallback, peripheralCallback, callbackHandler);
        });
        callbackHandler.stop();
    }

    @Test
    void When_creating_a_peripheral_with_null_for_the_deviceAddress_parameter_then_a_NPE_is_thrown() {
        Handler callbackHandler = new Handler("BluetoothPeripheralTest-callback");
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothPeripheral peripheral = new BluetoothPeripheral(central, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, null, internalCallback, peripheralCallback, callbackHandler);
        });
        callbackHandler.stop();
    }

    @Test
    void When_creating_a_peripheral_with_null_for_the_internalCallback_parameter_then_a_NPE_is_thrown() {
        Handler callbackHandler = new Handler("BluetoothPeripheralTest-callback");
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothPeripheral peripheral = new BluetoothPeripheral(central, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, DUMMY_MAC_ADDRESS_BLP, null, peripheralCallback, callbackHandler);
        });
        callbackHandler.stop();
    }

    @Test
    void When_creating_a_peripheral_with_null_for_the_handler_parameter_then_a_NPE_is_thrown() {
        Handler callbackHandler = new Handler("BluetoothPeripheralTest-callback");
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothPeripheral peripheral = new BluetoothPeripheral(central, bluezDevice, DUMMY_PERIPHERAL_NAME_BLP, DUMMY_MAC_ADDRESS_BLP, internalCallback, peripheralCallback, null);
        });
        callbackHandler.stop();
    }
}

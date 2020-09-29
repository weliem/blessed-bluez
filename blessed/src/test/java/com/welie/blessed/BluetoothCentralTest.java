package com.welie.blessed;

import com.welie.blessed.bluez.BluezAdapter;
import com.welie.blessed.bluez.DiscoveryFilter;
import com.welie.blessed.bluez.DiscoveryTransport;
import org.bluez.exceptions.BluezFailedException;
import org.bluez.exceptions.BluezInvalidArgumentsException;
import org.bluez.exceptions.BluezNotReadyException;
import org.bluez.exceptions.BluezNotSupportedException;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.welie.blessed.BluetoothCentral.DISCOVERY_RSSI_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BluetoothCentralTest {
    BluetoothCentral central;

    @Mock
    BluetoothCentralCallback callback;

    @Mock
    BluezAdapter bluezAdapter;

    @BeforeEach
    void setup() {
 //       when(bluezAdapter.isPowered()).thenReturn(true);
//        when(bluezAdapter.getDeviceName()).thenReturn("/org/bluez/hci0");
//        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);
    }

    @Test
    void constructorTestNotNullCallback() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            BluetoothCentral central = new BluetoothCentral(null, null, null);
        });
    }

    @Test
    void ifAdapterIsOffThenTurnItOnTest() throws InterruptedException {
        // Given
        when(bluezAdapter.isPowered()).thenReturn(false);

        // When
        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        Thread.sleep(10);

        // Then
        verify(bluezAdapter).setPowered(true);
    }

    @Test
    void ifScanForPeripheralsIsCalledThenTheAnUnfilteredScanIsStarted() throws InterruptedException, BluezFailedException, BluezNotReadyException, BluezNotSupportedException, BluezInvalidArgumentsException {
        // Given
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        central.scanForPeripherals();

        // scanForPeripherals is async so wait a little bit
        Thread.sleep(100);

        // Then

        // Verify scan filters
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(bluezAdapter).setDiscoveryFilter(captor.capture());
        Map<String, Variant<?>> filterMap = captor.getValue();
        assertEquals(3, filterMap.size());

        String transport = (String) filterMap.get("Transport").getValue();
        assertEquals("le", transport );

        Boolean duplicateData = (Boolean) filterMap.get("DuplicateData").getValue();
        assertTrue(duplicateData);

        Short rssi = (Short) filterMap.get("RSSI").getValue();
        assertEquals(DISCOVERY_RSSI_THRESHOLD, (int) rssi);

        // Verify that no name, mac addresses or service UUID filters are set
        assertEquals(0, central.scanUUIDs.length);
        assertEquals(0, central.scanPeripheralAddresses.length);
        assertEquals(0, central.scanPeripheralNames.length);

        // Verify that scan is really started
        verify(bluezAdapter).startDiscovery();
    }
}

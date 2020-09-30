package com.welie.blessed;

import com.welie.blessed.bluez.BluezAdapter;
import org.bluez.exceptions.BluezFailedException;
import org.bluez.exceptions.BluezInvalidArgumentsException;
import org.bluez.exceptions.BluezNotReadyException;
import org.bluez.exceptions.BluezNotSupportedException;
import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static com.welie.blessed.BluetoothCentral.DISCOVERY_RSSI_THRESHOLD;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BluetoothCentralTest {
    BluetoothCentral central;

    @Mock
    BluetoothCentralCallback callback;

    @Mock
    BluezAdapter bluezAdapter;

    private static final UUID BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb");
    private static final String DUMMY_MAC_ADDRESS = "12:34:56:65:43:21";
    private static final String DUMMY_PERIPHERAL_NAME = "Polar H7";

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

        // Then : Verify scan filters
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

        // Then : Verify that no name, mac addresses or service UUID filters are set
        assertEquals(0, central.scanServiceUUIDs.length);
        assertEquals(0, central.scanPeripheralAddresses.length);
        assertEquals(0, central.scanPeripheralNames.length);

        // Then : Verify that scan is really started
        verify(bluezAdapter).startDiscovery();
    }

    @Test
    void ifScanForPeripheralsWithServicesIsCalledThenTheAFilteredScanIsStarted() throws InterruptedException, BluezFailedException, BluezNotReadyException, BluezNotSupportedException, BluezInvalidArgumentsException {
        // Given
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        central.scanForPeripheralsWithServices(new UUID[]{BLP_SERVICE_UUID});

        // scanForPeripheralsWithServices is async so wait a little bit
        Thread.sleep(100);

        // Then : Verify scan filters
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

        // Then : Verify that no name, mac addresses or service UUID filters are set
        assertEquals(1, central.scanServiceUUIDs.length);
        assertEquals(BLP_SERVICE_UUID, central.scanServiceUUIDs[0]);
        assertEquals(0, central.scanPeripheralAddresses.length);
        assertEquals(0, central.scanPeripheralNames.length);

        // Then : Verify that scan is really started
        verify(bluezAdapter).startDiscovery();
    }

    @Test
    void ifScanForPeripheralsWithServicesIsCalledWithBadArgumentsThenExceptionsAreThrown() {
        // Given
        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        assertThrows(NullPointerException.class, ()-> {
                central.scanForPeripheralsWithServices(null);
        });
        assertThrows(IllegalArgumentException.class, ()-> {
            central.scanForPeripheralsWithServices(new UUID[0]);
        });
    }

    @Test
    void ifScanForPeripheralsWithAddressesIsCalledThenTheAFilteredScanIsStarted() throws InterruptedException, BluezFailedException, BluezNotReadyException, BluezNotSupportedException, BluezInvalidArgumentsException {
        // Given
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        central.scanForPeripheralsWithAddresses(new String[]{DUMMY_MAC_ADDRESS});

        // scanForPeripheralsWithServices is async so wait a little bit
        Thread.sleep(100);

        // Then : Verify scan filters
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

        // Then : Verify that no name, mac addresses or service UUID filters are set
        assertEquals(0, central.scanServiceUUIDs.length);
        assertEquals(1, central.scanPeripheralAddresses.length);
        assertEquals(DUMMY_MAC_ADDRESS, central.scanPeripheralAddresses[0]);
        assertEquals(0, central.scanPeripheralNames.length);

        // Then : Verify that scan is really started
        verify(bluezAdapter).startDiscovery();
    }

    @Test
    void ifScanForPeripheralsWithAddressesIsCalledWithBadArgumentsThenExceptionsAreThrown() {
        // Given
        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        assertThrows(NullPointerException.class, ()-> {
            central.scanForPeripheralsWithAddresses(null);
        });
        assertThrows(IllegalArgumentException.class, ()-> {
            central.scanForPeripheralsWithAddresses(new String[0]);
        });
    }

    @Test
    void ifScanForPeripheralsWithNamesIsCalledThenTheAFilteredScanIsStarted() throws InterruptedException, BluezFailedException, BluezNotReadyException, BluezNotSupportedException, BluezInvalidArgumentsException {
        // Given
        when(bluezAdapter.isDiscovering()).thenReturn(false);
        when(bluezAdapter.isPowered()).thenReturn(true);
        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        // When
        central.scanForPeripheralsWithNames(new String[]{DUMMY_PERIPHERAL_NAME});

        // scanForPeripheralsWithServices is async so wait a little bit
        Thread.sleep(100);

        // Then : Verify scan filters
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

        // Then : Verify that no name, mac addresses or service UUID filters are set
        assertEquals(0, central.scanServiceUUIDs.length);
        assertEquals(0, central.scanPeripheralAddresses.length);
        assertEquals(1, central.scanPeripheralNames.length);
        assertEquals(DUMMY_PERIPHERAL_NAME, central.scanPeripheralNames[0]);

        // Then : Verify that scan is really started
        verify(bluezAdapter).startDiscovery();
    }

    @Test
    void ifScanForPeripheralsWithNamesIsCalledWithBadArgumentsThenExceptionsAreThrown() {
        // Given
        central = new BluetoothCentral(callback, Collections.emptySet(), bluezAdapter);

        assertThrows(NullPointerException.class, ()-> {
            central.scanForPeripheralsWithNames(null);
        });
        assertThrows(IllegalArgumentException.class, ()-> {
            central.scanForPeripheralsWithNames(new String[0]);
        });
    }
}

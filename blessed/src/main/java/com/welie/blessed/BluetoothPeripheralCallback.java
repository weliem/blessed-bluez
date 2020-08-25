package com.welie.blessed;


import org.jetbrains.annotations.NotNull;

/**
 * Callbacks for BluetoothPeripheral operations
 */
public abstract class BluetoothPeripheralCallback {

    /**
     * Callback invoked when the list of remote services, characteristics and descriptors
     * for the remote device have been updated, ie new services have been discovered.
     *
     * @param peripheral the peripheral
     */
    public void onServicesDiscovered(@NotNull final BluetoothPeripheral peripheral) {}

    /**
     * Callback invoked when the notification state of a characteristic has changed.
     *
     * <p>Use {@link BluetoothPeripheral#isNotifying} to get the current notification state of the characteristic
     *
     * @param peripheral the peripheral
     * @param characteristic the characteristic for which the notification state changed
     * @param status GATT status code
     */
    public void onNotificationStateUpdate(@NotNull final BluetoothPeripheral peripheral, @NotNull final BluetoothGattCharacteristic characteristic, final int status) {}

    /**
     * Callback invoked as the result of a characteristic read operation or notification
     *
     * <p>The value byte array is a threadsafe copy of the byte array contained in the characteristic.
     *
     * @param peripheral the peripheral
     * @param value the new value received
     * @param characteristic the characteristic for which the new value was received
     * @param status GATT status code
     */
    public void onCharacteristicUpdate(@NotNull final BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull final BluetoothGattCharacteristic characteristic, final int status) {}

    /**
     * Callback indicating the result of a characteristic write operation.
     *
     * <p>The value byte array is a threadsafe copy of the byte array contained in the characteristic.
     *
     * @param peripheral the peripheral
     * @param value the value to be written
     * @param characteristic the characteristic written to
     * @param status GATT status code
     */
    public void onCharacteristicWrite(@NotNull final BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull final BluetoothGattCharacteristic characteristic, final int status) {}

    /**
     * Callback invoked as the result of a descriptor read operation
     *
     * @param peripheral the peripheral
     * @param value the read value
     * @param descriptor the descriptor that was read
     * @param status GATT status code
     */
    public void onDescriptorRead(@NotNull final BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull final BluetoothGattDescriptor descriptor, final int status) {}

    /**
     * Callback invoked as the result of a descriptor write operation.
     * This callback is not called for the Client Characteristic Configuration descriptor. Instead the {@link BluetoothPeripheralCallback#onNotificationStateUpdate(BluetoothPeripheral, BluetoothGattCharacteristic, int)} will be called
     *
     * @param peripheral the peripheral
     * @param value the value that to be written
     * @param descriptor the descriptor written to
     * @param status the GATT status code
     */
    public void onDescriptorWrite(@NotNull final BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull final BluetoothGattDescriptor descriptor, final int status) {}

    /**
     * Callback invoked when a bonding process is started
     *
     * @param peripheral the peripheral
     */
    public void onBondingStarted(@NotNull final BluetoothPeripheral peripheral) {}

    /**
     * Callback invoked when a bonding process has succeeded
     *
     * @param peripheral the peripheral
     */
    public void onBondingSucceeded(@NotNull final BluetoothPeripheral peripheral) {}

    /**
     * Callback invoked when a bonding process has failed
     *
     * @param peripheral the peripheral
     */
    public void onBondingFailed(@NotNull final BluetoothPeripheral peripheral) {}

    /**
     * Callback invoked when a bond has been lost and the peripheral is not bonded anymore.
     *
     * @param peripheral the peripheral
     */
    public void onBondLost(@NotNull final BluetoothPeripheral peripheral) {}

    /**
     * Callback invoked as the result of a read RSSI operation
     *
     * @param peripheral the peripheral
     * @param rssi the RSSI value
     * @param status GATT status code
     */
    public void onReadRemoteRssi(@NotNull final BluetoothPeripheral peripheral, int rssi, int status) {}
}

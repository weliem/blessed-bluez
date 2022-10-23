package com.welie.blessed.internal;

import com.welie.blessed.*;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public abstract class GattCallback {
    public abstract void onConnectionStateChanged(ConnectionState connectionState, @NotNull BluetoothCommandStatus status);

    public abstract void onCharacteristicRead(@NotNull BluetoothGattCharacteristic characteristic, @NotNull BluetoothCommandStatus status);

    public abstract void onCharacteristicChanged(@NotNull byte[] value, @NotNull BluetoothGattCharacteristic characteristic);

    public abstract void onCharacteristicWrite(@NotNull BluetoothGattCharacteristic characteristic, @NotNull BluetoothCommandStatus status);

    public abstract void onDescriptorRead(@NotNull BluetoothGattDescriptor descriptor, @NotNull byte[] value, @NotNull BluetoothCommandStatus status);

    public abstract void onDescriptorWrite(@NotNull BluetoothGattDescriptor descriptor, @NotNull BluetoothCommandStatus status);

    public abstract void onNotificationStateUpdate(@NotNull BluetoothGattCharacteristic characteristic, @NotNull BluetoothCommandStatus status);

    public abstract void onServicesDiscovered(@NotNull List<@NotNull BluetoothGattService> services);

    public abstract void onPairingStarted();

    public abstract void onPaired();

    public abstract void onPairingFailed();

}

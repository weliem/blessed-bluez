package com.welie.blessed.internal;

import com.welie.blessed.BluetoothGattCharacteristic;
import com.welie.blessed.BluetoothGattDescriptor;
import com.welie.blessed.BluetoothGattService;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public abstract class GattCallback {
    public abstract void onConnectionStateChanged(int connectionState, int status);

    public abstract void onCharacteristicRead(@NotNull BluetoothGattCharacteristic characteristic, int status);

    public abstract void onCharacteristicChanged(@NotNull byte[] value, @NotNull BluetoothGattCharacteristic indication);

    public abstract void onCharacteristicWrite(@NotNull BluetoothGattCharacteristic characteristic, int status);

    public abstract void onDescriptorWrite(@NotNull BluetoothGattDescriptor descriptor, int status);

    public abstract void onNotificationStateUpdate(@NotNull BluetoothGattCharacteristic characteristic, int status);

    public abstract void onServicesDiscovered(@NotNull List<@NotNull BluetoothGattService> services);

    public abstract void onPairingStarted();

    public abstract void onPaired();

    public abstract void onPairingFailed();

}

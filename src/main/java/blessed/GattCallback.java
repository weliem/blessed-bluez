package blessed;

import java.util.List;

public abstract class GattCallback {
    public abstract void onConnectionStateChanged(ConnectionState connectionState, int status);

    public abstract void onCharacteristicRead(BluetoothGattCharacteristic characteristic, int status);

    public abstract void onCharacteristicChanged(byte[] value, BluetoothGattCharacteristic indication);

    public abstract void onCharacteristicWrite(BluetoothGattCharacteristic characteristic, int status);

    public abstract void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status);

    public abstract void onNotifySet(BluetoothGattCharacteristic characteristic, boolean enabled);

    public abstract void onServicesDiscovered(List<BluetoothGattService> services, int status);

    public abstract void onPaired();

    public abstract void onPairingFailed();

}

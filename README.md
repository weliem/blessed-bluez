# BLESSED for Bluez - BLE made easy

[![](https://jitpack.io/v/weliem/blessed-bluez.svg)](https://jitpack.io/#weliem/blessed-bluez)
[![Downloads](https://jitpack.io/v/weliem/blessed-bluez/month.svg)](https://jitpack.io/#weliem/blessed-bluez)
[![Total alerts](https://img.shields.io/lgtm/alerts/g/weliem/blessed-bluez.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/weliem/blessed-bluez/alerts/)
[![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/weliem/blessed-bluez.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/weliem/blessed-bluez/context:java)

BLESSED-for-Bluez is a Bluetooth Low Energy (BLE) library for Bluez 5.50 and higher, that makes working with BLE using Bluez very easy. It completely hides the DBus messaging needed to use Bluez and provides a CoreBluetooth-like object oriented interface. This library uses the [DBus-Java library](https://github.com/hypfvieh/dbus-java) and parts of the [Bluez-DBus library](https://github.com/hypfvieh/bluez-dbus) for the communication with the DBus and Bluez functionality.


The library consists of 3 core classes and 2 callback abstract classes:
1. `BluetoothCentralManager`, and it companion abstract class `BluetoothCentralManagerCallback`
2. `BluetoothPeripheral`, and it's companion abstract class `BluetoothPeripheralCallback`
3. `BluetoothBytesParser`

The `BluetoothCentralManager` class is used to scan for devices and manage connections. The `BluetoothPeripheral` class represent the peripheral and wraps all GATT related peripheral functionality. The `BluetoothBytesParser` class is a utility class that makes parsing byte arrays easy.

The BLESSED library was inspired by CoreBluetooth on iOS and provides the same level of abstraction. If you already have developed using CoreBluetooth you can very easily port your code to Linux using this library. It has been tested on Ubuntu 18/19/20 and Raspberry Pi's.
## Installation

The library is available on Jitpack and uses Logback for logging:

```groovy
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation "ch.qos.logback:logback-core:+"
    implementation "ch.qos.logback:logback-classic:+"
    implementation "com.github.weliem.blessed-bluez:blessed:0.61"
}
```

## Scanning

There are 4 different scanning methods:

```java
public void scanForPeripherals()
public void scanForPeripheralsWithServices(UUID[] serviceUUIDs)
public void scanForPeripheralsWithNames(String[] peripheralNames)
public void scanForPeripheralsWithAddresses(String[] peripheralAddresses)
```

They all work in the same way and take an array of either service UUIDs, peripheral names or mac addresses. So in order to setup a scan for a device with the Bloodpressure service and connect to it, you do:

```java
private final BluetoothCentralManagerCallback bluetoothCentralManagerCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            central.stopScan();
            central.connectPeripheral(peripheral, peripheralCallback);
        }
};

// Create BluetoothCentralManager
BluetoothCentralManager central = new BluetoothCentralManager(bluetoothCentralManagerCallback);

// Define blood pressure service UUID
UUID BLOODPRESSURE_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb");

// Scan for peripherals with a certain service UUID
central.scanForPeripheralsWithServices(new UUID[]{BLOODPRESSURE_SERVICE_UUID});
```
**Note** Only 1 of these 4 types of scans can be active at one time! So call `stopScan()` before calling another scan.

## Connecting to devices

There are 3 ways to connect to a device:
```java
public void connectPeripheral(BluetoothPeripheral peripheral, BluetoothPeripheralCallback peripheralCallback)
public void autoConnectPeripheral(BluetoothPeripheral peripheral, BluetoothPeripheralCallback peripheralCallback)
public void autoConnectPeripheralsBatch(Map<BluetoothPeripheral, BluetoothPeripheralCallback> batch) 
```

The method `connectPeripheral` will try to immediately connect to a device that has already been found using a scan. This method will time out after 30-60 seconds or less depending on the device manufacturer. 

The method `autoConnectPeripheral` is for re-connecting to known devices for which you already know the device's mac address. The BLESSED will automatically connect to the device when it sees it in its internal scan. So you can issue the autoConnect command and the device will be connected whenever it is found. 

The method `autoConnectPeripheralsBatch` is for re-connecting to a multiple peripherals in one go. 

If you know the mac address of your peripheral you can obtain a `BluetoothPeripheral` object using:
```java
BluetoothPeripheral peripheral = central.getPeripheral("CF:A9:BA:D9:62:9E");
```

After issuing a connect call, you will receive one of the following callbacks:
```java
public void onConnectedPeripheral(BluetoothPeripheral peripheral)
public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status)
public void onDisconnectedPeripheral(BluetoothPeripheral peripheral, BluetoothCommandStatus status)
```

If you want to disconnect a device, or cancel an autoConnect on a device, you call `cancelConnection`.

## Service discovery

The Bluez will automatically do the service discovery for you and once it is completed you will receive the following callback:

```java
public void onServicesDiscovered(BluetoothPeripheral peripheral)
```
In order to get the services you can use methods like `getServices()` or `getService(UUID)`. In order to get hold of characteristics you can call `getCharacteristic(UUID)` on the BluetoothGattService object or call `getCharacteristic()` on the BluetoothPeripheral object.

This callback is the proper place to start enabling notifications or read/write characteristics.

## Reading and writing

Reading and writing to characteristics is done using the following methods:

```java
public boolean readCharacteristic(BluetoothGattCharacteristic characteristic)
public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, WriteType writeType)
```

Both methods are asynchronous and will be queued up. So you can just issue as many read/write operations as you like without waiting for each of them to complete. You will receive a callback once the result of the operation is available.
For read operations you will get a callback on:

```java
public void onCharacteristicUpdate(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, BluetoothCommandStatus status)
```
If you want to write to a characteristic, you need to provide a `value` and a `WriteType`. The `WriteType` is either `WITH_RESPONSE` or `WITHOUT_RESPONSE`. If the write type you specify is not supported by the characteristic you will see an error in your log. For write operations you will get a callback on:
```java
public void onCharacteristicWrite(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, BluetoothCommandStatus status)

```

In these callbacks, the *value* parameter is the threadsafe byte array that was received.

## Turning notifications on/off

BLESSED provides a convenience method `setNotify` to turn notifications/indications on or off. It will perform all the necessary operations like writing to the Client Characteristic Configuration descriptor for you. So all you need to do is:

```java
// See if this peripheral has the Current Time service
if(peripheral.getService(CTS_SERVICE_UUID) != null) {
     BluetoothGattCharacteristic currentTimeCharacteristic = peripheral.getCharacteristic(CTS_SERVICE_UUID, CURRENT_TIME_CHARACTERISTIC_UUID);
     peripheral.setNotify(currentTimeCharacteristic, true);
}
```

Since this is an asynchronous operation you will receive a callback that indicates success or failure. You can use the method `isNotifying` to check if the characteristic is currently notifying or not:

```java
@Override
public void onNotificationStateUpdate(BluetoothPeripheral peripheral, BluetoothGattCharacteristic characteristic, int status) {
     if( status == GATT_SUCCESS) {
          if(peripheral.isNotifying(characteristic)) {
               Log.i(TAG, String.format("SUCCESS: Notify set to 'on' for %s", characteristic.getUuid()));
          } else {
               Log.i(TAG, String.format("SUCCESS: Notify set to 'off' for %s", characteristic.getUuid()));
          }
     } else {
          Log.e(TAG, String.format("ERROR: Changing notification state failed for %s", characteristic.getUuid()));
     }
}
```
When notifications arrive you will receive a callback on:

```java
public void onCharacteristicUpdate(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic)
```

## Bonding
BLESSED handles bonding for you and will make sure all bonding variants work smoothly. During the process of bonding, you will be informed of the process via a number of callbacks:

```java
public void onBondingStarted(final BluetoothPeripheral peripheral)
public void onBondingSucceeded(final BluetoothPeripheral peripheral)
public void onBondingFailed(final BluetoothPeripheral peripheral) 
public void onBondLost(final BluetoothPeripheral peripheral) 
```
In most cases, the peripheral will initiate bonding either at the time of connection, or when trying to read/write protected characteristics. However, if you want you can also initiate bonding yourself by calling `createBond` on a peripheral. There are two ways to do this:
* Calling `createBond` when not yet connected to a peripheral. In this case, a connection is made and bonding is requested.
* Calling `createBond` when already connected to a peripheral. In this case, only the bond is created.

It is also possible to remove a bond by calling `removeBond`. 

Lastly, it is also possible to automatically issue a PIN code when pairing. Use the method `setPinCodeForPeripheral` to register a 6 digit PIN code. Once bonding starts, BLESSED will automatically issue the PIN code and the UI dialog to enter the PIN code will not appear anymore.


## Example application

An example application is provided in the repo. It shows how to connect to Blood Pressure meters, Weight scales, Heart Rate monitors, Pulse Oximeters and Thermometers, read the data and show it in the log.


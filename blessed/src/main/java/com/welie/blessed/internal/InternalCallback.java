/*
 * Copyright (c) Koninklijke Philips N.V., 2017.
 * All rights reserved.
 */

package com.welie.blessed.internal;

import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCommandStatus;
import com.welie.blessed.BluetoothPeripheral;
import org.jetbrains.annotations.NotNull;

/**
 * Interface between {@link BluetoothCentralManager} and a {@link BluetoothPeripheral}.
 *
 * The {@link BluetoothPeripheral} sends status updates to {@link BluetoothCentralManager}.
 */
public interface InternalCallback {

    /**
     * {@link BluetoothPeripheral} has successfully connected.
     *
     * @param peripheral {@link BluetoothPeripheral} that connected.
     */
    void connected(@NotNull final BluetoothPeripheral peripheral);

    /**
     * Connecting with {@link BluetoothPeripheral} has failed.
     *
     * @param peripheral {@link BluetoothPeripheral} of which connect failed.
     * @param status the status of the operation
     */
    void connectFailed(@NotNull final BluetoothPeripheral peripheral, @NotNull final BluetoothCommandStatus status);

    /**
     * {@link BluetoothPeripheral} has disconnected.
     *
     * @param peripheral {@link BluetoothPeripheral} that disconnected.
     * @param status the status of the operation
     */
    void disconnected(@NotNull final BluetoothPeripheral peripheral, @NotNull final BluetoothCommandStatus status);

    void servicesDiscovered(@NotNull final BluetoothPeripheral peripheral);

    void serviceDiscoveryFailed(@NotNull final BluetoothPeripheral peripheral);
}

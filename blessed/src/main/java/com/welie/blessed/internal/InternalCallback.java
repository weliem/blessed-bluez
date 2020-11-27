/*
 * Copyright (c) Koninklijke Philips N.V., 2017.
 * All rights reserved.
 */

package com.welie.blessed.internal;

import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothConnectionChangeStatus;
import com.welie.blessed.BluetoothPeripheral;
import org.jetbrains.annotations.NotNull;

/**
 * Interface between {@link BluetoothCentral} and a {@link BluetoothPeripheral}.
 * <p/>
 * The {@link BluetoothPeripheral} sends status updates to {@link BluetoothCentral}.
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
     */
    void connectFailed(@NotNull final BluetoothPeripheral peripheral, final BluetoothConnectionChangeStatus status);

    /**
     * {@link BluetoothPeripheral} has disconnected.
     *
     * @param peripheral {@link BluetoothPeripheral} that disconnected.
     */
    void disconnected(@NotNull final BluetoothPeripheral peripheral, final BluetoothConnectionChangeStatus status);

    void servicesDiscovered(@NotNull final BluetoothPeripheral peripheral);

    void serviceDiscoveryFailed(@NotNull final BluetoothPeripheral peripheral);
}

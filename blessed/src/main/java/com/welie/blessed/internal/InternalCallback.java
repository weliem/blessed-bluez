/*
 * Copyright (c) Koninklijke Philips N.V., 2017.
 * All rights reserved.
 */

package com.welie.blessed.internal;

import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothPeripheral;

/**
 * Interface between {@link BluetoothCentral} and a {@link BluetoothPeripheral}.
 * <p/>
 * The {@link BluetoothPeripheral} sends status updates to {@link BluetoothCentral}.
 */
public interface InternalCallback {

    /**
     * {@link BluetoothPeripheral} has successfully connected.
     *
     * @param device {@link BluetoothPeripheral} that connected.
     */
    void connected(final BluetoothPeripheral device);

    /**
     * Connecting with {@link BluetoothPeripheral} has failed.
     *
     * @param device {@link BluetoothPeripheral} of which connect failed.
     */
    void connectFailed(final BluetoothPeripheral device);

    /**
     * {@link BluetoothPeripheral} has disconnected.
     *
     * @param device {@link BluetoothPeripheral} that disconnected.
     */
    void disconnected(final BluetoothPeripheral device);

    void servicesDiscovered(final BluetoothPeripheral device);

    void serviceDiscoveryFailed(final BluetoothPeripheral device);
}

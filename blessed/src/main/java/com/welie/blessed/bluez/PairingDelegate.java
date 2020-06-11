package com.welie.blessed.bluez;

public interface PairingDelegate {

    String requestPassCode(String deviceAddress);
    void onPairingStarted(String deviceAddress);
}

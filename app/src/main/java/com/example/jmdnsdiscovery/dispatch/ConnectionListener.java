package com.example.jmdnsdiscovery.dispatch;

import com.example.jmdnsdiscovery.protocol.Link;

public interface ConnectionListener {

    /**
     * Called when transport discovered new device and established connection with it.
     * @param link connection object to discovered device
     */
    void linkConnected(Link link);

    /**
     * Called when connection to device is closed explicitly from either side
     * or because device is out of range.
     * @param link connection object to disconnected device
     */
    void linkDisconnected(Link link);

    /**
     * Called when new data frame is received from remote device.
     * @param link connection object for the device
     * @param frameData frame data received from remote device
     */
    void linkDidReceiveFrame(Link link, byte[] frameData);
}

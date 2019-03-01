package com.example.jmdnsdiscovery.protocol;

import java.net.InetAddress;

public interface WifiResolver {
    interface Listener{
        void onBonjourServiceResolved(String name, String address, int port);
    }

    void start(InetAddress address, int port);
    void startPublishOnly(InetAddress address, int port);
    void startResolveOnly(InetAddress address);
    void stop();
}

package com.example.jmdnsdiscovery.protocol;

import android.content.Context;

import com.example.jmdnsdiscovery.dispatch.ConnectionListener;
import com.example.jmdnsdiscovery.dispatch.DispatchQueue;

public class MeshManager {
    public static DispatchQueue queue;

    public static WifiTransPort configMesh(int appId, String nodeId, ConnectionListener connectionListener, Context context) {
        queue = new DispatchQueue();
        WifiTransPort wifiTransPort = new WifiTransPort(appId, nodeId, connectionListener, queue, context);
        return wifiTransPort;
    }
}

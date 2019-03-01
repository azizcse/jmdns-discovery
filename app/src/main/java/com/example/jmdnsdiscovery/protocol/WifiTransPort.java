package com.example.jmdnsdiscovery.protocol;

import android.content.Context;

import com.example.jmdnsdiscovery.dispatch.DispatchQueue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;


public class WifiTransPort implements WifiDetector.Listener,WifiResolver.Listener,NsdServer.Listener, Transport{

    enum Mode {NONE, WIFI, HOTSPOT}

    Mode mode = Mode.NONE;

    private int appId;
    private String nodeId;
    private Context context;
    private DispatchQueue queue;
    private String serviceType;
    private WifiResolver wifiResolver;
    private NsdServer server;
    private boolean running;
    private boolean restarting;
    private WifiDetector wifiDetector;
    private List<NsdLink> links = new ArrayList<>();
    private DispatchQueue listenerQueue;

    public WifiTransPort(int appId, String nodeId, DispatchQueue listenerQueue, Context context) {
        this.queue = new DispatchQueue();
        this.appId = appId;
        this.nodeId = nodeId;
        this.context = context.getApplicationContext();
        this.listenerQueue = listenerQueue;
        this.serviceType = "_mesh" + appId + "._tcp.";
        wifiDetector = new WifiDetector(this, queue, context);
        wifiResolver = new JmdResolver(serviceType, nodeId,this,queue, context);
        server = new NsdServer(nodeId,this, queue);
    }

    @Override
    public void onBonjourServiceResolved(String name, String address, int port) {
        if (!running)
            return;

        try {

            for (NsdLink link : links) {
                if (link.getNodeId().equals(name))
                    return;
            }

            //   logDark("Resolved Node > " + name);

            server.connect(nodeId, InetAddress.getByName(address), port);
        } catch (NumberFormatException ex) {
            return;
        } catch (UnknownHostException ex) {
            return;
        }
    }

    @Override
    public void onServerAccepting(InetAddress address, int port) {
        if (!running)
            return;

        wifiResolver.start(address, port);
    }

    @Override
    public void onServerError(InetAddress address) {

    }

    @Override
    public void linkConnected(NsdLink link) {
        if (!running) {
            link.disconnect();
            return;
        }

        links.add(link);

        listenerQueue.dispatch(new Runnable() {
            @Override
            public void run() {
                //listener.transportLinkConnected(NsdTransport.this, link);
            }
        });
    }

    @Override
    public void linkDisconnected(NsdLink link) {

    }

    @Override
    public void linkDidReceiveFrame(NsdLink link, byte[] frameData) {

    }

    /********************************************************************/
    /************************ Transport callback**************************/
    /********************************************************************/

    private void startInternal() {
        if (running)
            return;
        running = true;
        wifiDetector.start();
    } // startInternal()

    private void stopInternal() {
        if (!running)
            return;
        running = false;
        wifiDetector.stop();
    }

    private void forceStopInternal() {
        if (!running)
            return;
        running = false;
        wifiDetector.stop();
        if (mode == Mode.NONE || mode == Mode.WIFI) {
            onWifiDisabled();
        } else if (mode == Mode.HOTSPOT) {
            onHotspotDisabled();
        }
    }

    private void restartInternal() {
        if (restarting) {
            return;
        }
        restarting = true;
        wifiDetector.stop();
        if (mode == Mode.NONE || mode == Mode.WIFI) {
            onWifiDisabled();
        } else if (mode == Mode.HOTSPOT) {
            onHotspotDisabled();
        }
    }


    @Override
    public void start() {
        queue.dispatch(new Runnable() {
            @Override
            public void run() {
                startInternal();
            }
        });
    }

    @Override
    public void stop() {
        queue.dispatch(new Runnable() {
            @Override
            public void run() {
                stopInternal();
            }
        });
    }

    @Override
    public void forceStop() {
        queue.dispatch(new Runnable() {
            @Override
            public void run() {
                forceStopInternal();
            }
        });
    }

    @Override
    public void restart() {
        queue.dispatch(new Runnable() {
            @Override
            public void run() {
                restartInternal();
            }
        });
    }
    /****************************************************/
    /******************** Wifi detector listener ********/
    /****************************************************/

    @Override
    public void onWifiEnabled(InetAddress address) {
        if (mode == Mode.HOTSPOT) {
            onWifiDisabled();
        }

        server.startAccepting(address);
        mode = Mode.WIFI;
    }

    @Override
    public void onWifiDisabled() {
        //server.stopAccepting();
        wifiResolver.stop();
        mode = Mode.NONE;

        if (restarting) {
            restarting = false;
            wifiDetector.start();
        }
    }

    @Override
    public void onHotspotEnabled(InetAddress address) {
        if (mode == Mode.WIFI) {
            onHotspotDisabled();
        }

        server.startAccepting(address);
        mode = Mode.HOTSPOT;

    }

    @Override
    public void onHotspotDisabled() {
        //server.stopAccepting();
        wifiResolver.stop();
        mode = Mode.NONE;

        if (restarting) {
            restarting = false;
            wifiDetector.start();
        }
    }
}

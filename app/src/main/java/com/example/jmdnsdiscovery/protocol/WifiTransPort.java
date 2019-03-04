package com.example.jmdnsdiscovery.protocol;

import android.content.Context;

import com.example.jmdnsdiscovery.AppLog;
import com.example.jmdnsdiscovery.dispatch.ConnectionListener;
import com.example.jmdnsdiscovery.dispatch.DispatchQueue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;


public class WifiTransPort implements WifiDetector.Listener, WifiResolver.Listener, NsdServer.Listener, Transport {

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
    private ConnectionListener connectionListener;

    public WifiTransPort(int appId, String nodeId, ConnectionListener connectionListener, DispatchQueue listenerQueue, Context context) {
        this.queue = new DispatchQueue();
        this.appId = appId;
        this.nodeId = nodeId;
        this.context = context.getApplicationContext();
        this.listenerQueue = listenerQueue;
        this.connectionListener = connectionListener;
        this.serviceType = "_mesh" + appId + "._tcp.";

        wifiDetector = new WifiDetector(this, queue, context);
        wifiResolver = new JmdResolver(serviceType, nodeId, this, queue, context);
        server = new NsdServer(nodeId, this, queue);
    }

    /********************************************************************/
    /************************ Transport callback*************************/
    /********************************************************************/

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

    private void startInternal() {
        AppLog.v("NsdTransport StartInternal: " + running);
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
        AppLog.v("NsdTransport ForceStopInternal: " + running);
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
        AppLog.v("NsdTransport Restarting " + restarting);
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


    /****************************************************/
    /******************** Wifi detector listener ********/
    /****************************************************/

    @Override
    public void onWifiEnabled(InetAddress address) {
       AppLog.v("bnj wifi enabled {}", address.toString());

        if (mode == Mode.HOTSPOT) {
            onWifiDisabled();
        }

        server.startAccepting(address);
        mode = Mode.WIFI;
    }

    @Override
    public void onWifiDisabled() {
        AppLog.v("WiFi Disabled");

        server.stopAccepting();
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
        AppLog.v("Hotspot Disabled");
        server.stopAccepting();
        wifiResolver.stop();
        mode = Mode.NONE;

        if (restarting) {
            restarting = false;
            wifiDetector.start();
        }
    }

    /********************************************************************/
    /************************ End Wifi detector listener*****************/
    /********************************************************************/

    @Override
    public void onBonjourServiceResolved(String name, String address, int port) {
        if (!running)
            return;

       AppLog.v("To Resolve Node  Name =" + name+" address ="+address+" port ="+port);

        try {

            for (NsdLink link : links) {
                if (link.getNodeId().equals(name))
                    return;
            }

            //   logDark("Resolved Node > " + name);
            AppLog.v("Connect with server.......");
            server.connect(nodeId, InetAddress.getByName(address), port);
        } catch (NumberFormatException ex) {
            AppLog.v("bnj failed to parse link nodeId "+ name);
            return;
        } catch (UnknownHostException ex) {
            AppLog.v("bnj failed to parse link address "+ address);
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
        if (!running)
            return;

        wifiResolver.startResolveOnly(address);
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
                /**
                 * Callback to UI
                 */
                connectionListener.linkConnected(link);
                AppLog.v("Wifi link connected...........");
            }
        });
    }

    @Override
    public void linkDisconnected(NsdLink link) {
        // Queue.
        links.remove(link);

        listenerQueue.dispatch(new Runnable() {
            @Override
            public void run() {
                AppLog.v("Wifi link dis connected.............");
                connectionListener.linkDisconnected(link);
            }
        });
    }

    @Override
    public void linkDidReceiveFrame(NsdLink link, byte[] frameData) {
        if (!running)
            return;

        listenerQueue.dispatch(new Runnable() {
            @Override
            public void run() {
                connectionListener.linkDidReceiveFrame(link, frameData);
            }
        });
    }

}

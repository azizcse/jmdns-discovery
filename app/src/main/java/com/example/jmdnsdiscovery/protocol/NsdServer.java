package com.example.jmdnsdiscovery.protocol;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.jmdns.impl.DNSRecord;

public class NsdServer {
    public interface Listener {
        void onServerAccepting(InetAddress address, int port);

        void onServerError(InetAddress address);

        void linkConnected(NsdLink link);

        void linkDisconnected(NsdLink link);

        void linkDidReceiveFrame(NsdLink link, byte[] frameData);
    }

    private volatile boolean accepting;

    private String nodeId;
    private String nodeJson;
    private Listener listener;
    Executor queue;

    private InetAddress serverAddress;
    ServerSocket serverSocket;

    private List<NsdLink> linksConnecting = new ArrayList<>();


    public NsdServer(String nodeId, Listener listener, Executor queue) {
        this.nodeId = nodeId;
        this.listener = listener;
        this.queue = queue;
    }

    public void startAccepting(final InetAddress address) {
        if (accepting)
            return;

        synchronized (this) {
            accepting = true;
        }

        serverAddress = address;

        try {
            serverSocket = new ServerSocket(0, 50, address);
        } catch (IOException ex) {
            queue.execute(new Runnable() {
                @Override
                public void run() {
                    listener.onServerError(serverAddress);
                }
            });

            return;
        }

        serverAddress = ((InetSocketAddress) serverSocket.getLocalSocketAddress()).getAddress();
        final InetAddress localAddress = serverAddress;
        final int port = serverSocket.getLocalPort();

        queue.execute(new Runnable() {
            @Override
            public void run() {
                listener.onServerAccepting(localAddress, port);
            }
        });

        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                accept();
            }
        });
        acceptThread.setName("NsdServer Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void accept() {
        // Accept thread.

        while (true) {
            synchronized (this) {
                if (!accepting)
                    break;
            }

            Socket socket;

            try {
                socket = serverSocket.accept();
            } catch (IOException ex) {
                //Logger.warn("nsd accept() failed: {}", ex);
                continue;
            }

            final NsdLink link = new NsdLink(this, socket);
            queue.execute(new Runnable() {
                @Override
                public void run() {
                    linkConnecting(link);
                    link.connect();
                }
            });
        } // while
    } // accept

    public void connect(String nodeId, InetAddress address, int port) {
        NsdLink link = new NsdLink(this, nodeId, address, port);
        linkConnecting(link);
        link.connect();
    }

    void linkDisconnected(NsdLink link, boolean wasConnected) {
        // Queue.
        linksConnecting.remove(link);

        if (wasConnected) {
            listener.linkDisconnected(link);
        }
    }

    //region Links
    private void linkConnecting(NsdLink link) {
        // Queue.
        linksConnecting.add(link);
    }


    void linkDidReceiveFrame(NsdLink link, byte[] frameData) {
        // Queue.
        listener.linkDidReceiveFrame(link, frameData);
    }

}

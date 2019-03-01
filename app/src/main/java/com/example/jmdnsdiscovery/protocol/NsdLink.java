package com.example.jmdnsdiscovery.protocol;

import android.util.Log;

import com.example.jmdnsdiscovery.dispatch.SerialExecutorService;
import com.example.jmdnsdiscovery.protobuf.Frames;
import com.example.jmdnsdiscovery.util.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class NsdLink {
    public enum State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    private boolean client;
    private NsdServer server;
    private Socket socket;
    private String nodeId;

    private InetAddress host;
    private int port;

    // Changed only from input thread.
    private State state = State.CONNECTING;

    private InputStream inputStream;
    private volatile OutputStream outputStream;

    private ScheduledThreadPoolExecutor pool;
    private ExecutorService outputExecutor;

    public NsdLink(NsdServer server, Socket socket) {
        // Any thread
        super();
        this.client = false;
        this.server = server;
        this.socket = socket;
        this.host = socket.getInetAddress();
        this.port = socket.getPort();

        configureOutput();
    }



    public NsdLink(NsdServer server, String nodeId, InetAddress host, int port){
        this.client = true;
        this.server = server;
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;

        configureOutput();
    }

    private void configureOutput() {
        pool = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName("NsdLink " + this.hashCode() + " Output");
                thread.setDaemon(true);
                return thread;
            }
        });

        outputExecutor = new SerialExecutorService(pool);
    }

    public String getNodeId(){
        return nodeId;
    }

    @Override
    public String toString() {
        return (client ? "c" : "s")
                + "link"
                + " nodeId " + nodeId
                + " " + host.toString()
                + ":" + port;
    }

    void connect() {
        // Queue
        Thread inputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectImpl();
            }
        });
        inputThread.setName("NsdLink " + this.hashCode() + " Input");
        inputThread.setDaemon(true);
        inputThread.start();
    } // connect

    private void connectImpl() {
        // Input thread.
        if (client) {
            try {
                Log.e("JMDNS_LOG","Socket connect host="+host+" port ="+port);


                this.socket = new Socket(host, port);
            } catch (IOException ex) {
                notifyDisconnect();
                return;
            }
        }

        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(Config.bnjTimeoutInterval);
        } catch (SocketException ex) {
        }

        if (!connectStreams()) {
            notifyDisconnect();
            return;
        }

        sendHelloFrame();

        pool.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (state != State.CONNECTED)
                    return;

                sendHeartbeat();
            }
        }, 0, Config.bnjHeartbeatInterval, TimeUnit.MILLISECONDS);

        inputLoop();
    } // connectImpl


    private void notifyDisconnect() {
        // Input thread
        try {
            if (socket != null)
                socket.close();
        } catch (IOException e) {
        }

        final boolean wasConnected = (this.state == State.CONNECTED);
        this.state = State.DISCONNECTED;

        //outputExecutor.close();
        outputExecutor.shutdown();

        pool.shutdown();

        server.queue.execute(new Runnable() {
            @Override
            public void run() {
                server.linkDisconnected(NsdLink.this, wasConnected);
            }
        });
    }

    private boolean connectStreams() {
        // Input thread.
        try {
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
        } catch (IOException ex) {
            return false;
        }

        //Logger.debug("bt retrieved streams device '{}' {}", device.getName(), device.getAddress());

        return true;
    }

    private void sendHelloFrame() {

    }

    private void sendHeartbeat() {

    }

    private void inputLoop() {
        // Input thread.
        final int bufferSize = 4096;
        ByteBuf inputData = Unpooled.buffer(bufferSize);
        inputData.order(ByteOrder.BIG_ENDIAN);

        try {
            int len;
            while (true) {
                inputData.ensureWritable(bufferSize, true);
                len = inputStream.read(
                        inputData.array(),
                        inputData.writerIndex(),
                        bufferSize);
                if (len <= 0)
                    break;

                inputData.writerIndex(inputData.writerIndex() + len);

                if (!formFrames(inputData))
                    break;

                inputData.discardReadBytes();
                inputData.capacity(inputData.writerIndex() + bufferSize);
            } // while
        } catch (InterruptedIOException ex) {
            try {
                inputStream.close();
            } catch (IOException ioex) {
            }

            notifyDisconnect();
            return;
        } catch (Exception ex) {
            try {
                inputStream.close();
            } catch (IOException ioex) {
            }

            notifyDisconnect();
            return;
        }
        notifyDisconnect();
    } // inputLoop

    private boolean formFrames(ByteBuf inputData) {
        final int headerSize = 4;

        while (true) {
            if (inputData.readableBytes() < headerSize)
                break;

            inputData.markReaderIndex();
            int frameSize = inputData.readInt();

            if (frameSize > Config.frameSizeMax) {
                return false;
            }

            if (inputData.readableBytes() < frameSize) {
                inputData.resetReaderIndex();
                break;
            }

            final Frames.Frame frame;

            {
                final byte[] frameBody = new byte[frameSize];
                inputData.readBytes(frameBody, 0, frameSize);

                try {
                    frame = Frames.Frame.parseFrom(frameBody);
                } catch (Exception ex) {
                    continue;
                }
            }

            if (this.state == State.CONNECTING) {
                if (frame.getKind() != Frames.Frame.Kind.HELLO)
                    continue;

                this.nodeId = frame.getHello().getNodeId();
                this.state = State.CONNECTED;

                server.queue.execute(new Runnable() {
                    @Override
                    public void run() {
                        //server.linkConnected(NsdLink.this);
                    }
                });

                continue;
            }

            if (frame.getKind() == Frames.Frame.Kind.PAYLOAD) {
                if (!frame.hasPayload() || !frame.getPayload().hasPayload())
                    continue;

                final byte[] frameData = frame.getPayload().getPayload().toByteArray();
                if (frameData.length == 0)
                    continue;

                server.queue.execute(new Runnable() {
                    @Override
                    public void run() {
                       server.linkDidReceiveFrame(NsdLink.this, frameData);
                    }
                });

                continue;
            }

            if (frame.getKind() == Frames.Frame.Kind.HEARTBEAT) {
                continue;
            }
        } // while

        return true;
    } // formFrames

}

package com.example.jmdnsdiscovery.protocol;

import android.util.Log;

import com.example.jmdnsdiscovery.AppLog;
import com.example.jmdnsdiscovery.dispatch.SerialExecutorService;
import com.example.jmdnsdiscovery.util.Config;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import protobuf.Frames;
import protobuf.Frames.Message;
import protobuf.Frames.Frame;
import protobuf.Frames.Hello;


public class NsdLink implements Link {
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
    private boolean shouldCloseWhenOutputIsEmpty = false;
    private Queue<Frame> outputQueue = new LinkedList<>();

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


    public NsdLink(NsdServer server, String nodeId, InetAddress host, int port) {
        super();
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


    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void disconnect() {
        outputExecutor.execute(new Runnable() {
            @Override
            public void run() {
                shouldCloseWhenOutputIsEmpty = true;
                writeNextFrame();
            }
        });
    }

    @Override
    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    @Override
    public void sendFrame(byte[] frameData) {

        Message.Builder message = Message.newBuilder();
        message.setMessage(ByteString.copyFrom(frameData));

        Frame.Builder builder = Frame.newBuilder();
        builder.setKind(Frames.Kind.MESSAGE);
        builder.setMessage(message);

        final Frame frame = builder.build();
        sendLinkFrame(frame);
    }

    void sendLinkFrame(final Frame frame) {
        if (state != State.CONNECTED)
            return;

        enqueueFrame(frame);
    }

    @Override
    public String toString() {
        return (client ? "c" : "s")
                + "link"
                + " nodeId " + nodeId
                + " " + host.toString()
                + ":" + port;
    }

    private void enqueueFrame(final Frames.Frame frame) {
        // Any thread.
        outputExecutor.execute(new Runnable() {
            @Override
            public void run() {
                outputQueue.add(frame);
                writeNextFrame();
            }
        });
    }


    private void writeNextFrame() {
        // Output thread.
        if (state == State.DISCONNECTED) {
            outputQueue.clear();
            return;
        }

        byte[] frameBytes;

        Frames.Frame frame = outputQueue.poll();
        if (frame == null) {
            if (shouldCloseWhenOutputIsEmpty) {
                try {
                    outputStream.close();
                    socket.close();
                } catch (IOException e) {
                }
            }

            //Logger.debug("nsd link outputQueue empty");
            return;
        }

        frameBytes = frame.toByteArray();


        if (!writeFrameBytes(frameBytes)) {
            outputQueue.clear();
            return;
        }

        outputExecutor.execute(new Runnable() {
            @Override
            public void run() {
                writeNextFrame();
            }
        });
    }


    private boolean writeFrameBytes(byte[] frameBytes) {
        // Output thread.
        ByteBuffer header = ByteBuffer.allocate(4);
        header.order(ByteOrder.BIG_ENDIAN);
        header.putInt(frameBytes.length);

        try {
            outputStream.write(header.array());
            outputStream.write(frameBytes);
            outputStream.flush();
        } catch (IOException ex) {
            try {
                outputStream.close();
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return false;
        }

        return true;
    } // writeFrame

    void connect() {
        // Queue
        Thread inputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectImpl();
            }
        });
        inputThread.setName("wifi " + this.hashCode() + " Input");
        inputThread.setDaemon(true);
        inputThread.start();
    } // connect

    private void connectImpl() {
        // Input thread.
        if (client) {
            try {
                AppLog.v("Socket connect host=" + host + " port =" + port);


                this.socket = new Socket(host, port);
            } catch (IOException ex) {
                AppLog.v("nsd link connect failed to host =" + host + " port =" + port);
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
            AppLog.v("nsd link streams get failed {}");
            return false;
        }

        //Logger.debug("bt retrieved streams device '{}' {}", device.getName(), device.getAddress());

        return true;
    }

    private void sendHelloFrame() {
        Hello.Builder builder = Hello.newBuilder();
        builder.setNodeId(server.getNodeId());
        Frame.Builder frame = Frame.newBuilder();
        frame.setKind(Frames.Kind.HELLO);
        frame.setHelloMsg(builder);
        final Frame msg = frame.build();
        enqueueFrame(msg);
    }

    private void sendHeartbeat() {
        Frames.HeartBit.Builder heartBit = Frames.HeartBit.newBuilder();
        Frame.Builder frame = Frame.newBuilder();
        frame.setKind(Frames.Kind.HEARTBEAT);
        frame.setBitMsg(heartBit);
        Frame message = frame.build();
        enqueueFrame(message);
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
            ex.printStackTrace();
            AppLog.v("NSD_timeout", ex.toString());
            try {
                inputStream.close();
            } catch (IOException ioex) {
            }

            notifyDisconnect();
            return;
        } catch (Exception ex) {
            AppLog.v("nsd input read failed: {}");
            try {
                inputStream.close();
            } catch (IOException ioex) {
            }

            notifyDisconnect();
            return;
        }
        AppLog.v("nsd input read end");
        notifyDisconnect();
    } // inputLoop

    private boolean formFrames(ByteBuf inputData) {
        final int headerSize = 4;

        while (true) {
            if (inputData.readableBytes() < headerSize) {
                AppLog.v("Readable string leass then header");
                break;
            }

            inputData.markReaderIndex();
            int frameSize = inputData.readInt();

            if (frameSize > Config.frameSizeMax) {
                AppLog.v("Readable string max size");
                return false;
            }

            if (inputData.readableBytes() < frameSize) {
                AppLog.v("inputData.readableBytes() < frameSize =" + frameSize);
                inputData.resetReaderIndex();
                break;
            }

            final Frame frame;

            {
                final byte[] frameBody = new byte[frameSize];
                inputData.readBytes(frameBody, 0, frameSize);

                try {
                    frame = Frame.parseFrom(frameBody);
                } catch (Exception ex) {
                    AppLog.v("Frame parse exception");
                    continue;
                }
            }

            if (this.state == State.CONNECTING) {
                if (frame.getKind() != Frames.Kind.HELLO)
                    continue;

                this.nodeId = frame.getHelloMsg().getNodeId();
                this.state = State.CONNECTED;

                AppLog.v("Hello frame received...............");
                server.queue.execute(new Runnable() {
                    @Override
                    public void run() {
                        server.linkConnected(NsdLink.this);
                    }
                });

                continue;
            }

            if (frame.getKind() == Frames.Kind.MESSAGE) {
                if (frame.getMessage() == null)
                    continue;

                final byte[] frameData = frame.getMessage().toByteArray();
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

            if (frame.getKind() == Frames.Kind.HEARTBEAT) {
                //AppLog.v("Heart bit received");
                continue;
            }
        } // while

        return true;
    } // formFrames

}

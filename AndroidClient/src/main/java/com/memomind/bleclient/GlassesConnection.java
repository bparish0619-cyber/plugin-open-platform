package com.memomind.bleclient;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Phone-facing GM command link (BLUETOOTH_DEVELOPER_GUIDE.md) over one of two
 * transports, chosen by the device's Bluetooth type:
 * <ul>
 *   <li><b>Classic SPP</b> for Classic-only devices: an RFCOMM byte stream to
 *       the GM command service 0x7033 (legacy fallback 0x1101), the transport
 *       the official App prefers. Frames are recovered with
 *       {@link GmCodec.StreamFramer}.</li>
 *   <li><b>BLE GATT</b> otherwise: discovers the service containing
 *       0x2021/0x2022, subscribes to the uplink before writing, and writes one
 *       complete GM frame per characteristic write within the negotiated MTU.</li>
 * </ul>
 * Both share the request state machine: requests are serialized so messages
 * never interleave, and fragments wait for the empty flow-control response.
 * All state is confined to one handler thread.
 */
public final class GlassesConnection {
    /** GM command downlink: phone writes complete GM frames here. */
    public static final UUID DOWNLINK = uuid16(0x2021);
    /** GM response/event uplink: glasses notify complete GM frames here. */
    public static final UUID UPLINK = uuid16(0x2022);
    /** Client Characteristic Configuration Descriptor that enables notifications. */
    public static final UUID CCCD = uuid16(0x2902);
    /** Classic SPP (RFCOMM) GM command service, preferred by the official App. */
    public static final UUID SPP_COMMAND = uuid16(0x7033);
    /** Generic Serial Port Profile UUID; the App's legacy command fallback. */
    public static final UUID SPP_LEGACY = uuid16(0x1101);

    /** Per-step wait for a write callback or response before failing the request. */
    static final long TIMEOUT_MS = 5000;
    /** Largest ATT MTU Android can request; the peer may grant less. */
    static final int REQUESTED_MTU = 517;

    /** Connection events for the UI. Called on the connection thread. */
    public interface Listener {
        /** Human-readable link state; ready is true once commands can be sent. */
        void onState(String state, boolean ready);
        /** One diagnostic line (TX/RX hex, state changes, errors). */
        void onLog(String line);
        /** Messages not matched to a pending request, such as 0F/29 plugin messages. */
        void onUnsolicited(GmCodec.Message message);
    }

    /** Outcome of one request. Called on the connection thread. */
    public interface ResponseCallback {
        void onResponse(GmCodec.Message response);
        void onError(String error);
    }

    /** One queued logical GM message and its progress through the write/ACK cycle. */
    private final class Request {
        final String label;
        final int service;
        final int command;
        final byte[] body;
        final ResponseCallback callback;
        /** GM event ID; echoed in the response for correlation. */
        int event;
        /** Physical frames sized for the current MTU. */
        List<byte[]> frames;
        /** Index of the frame currently being written. */
        int index;
        /** The platform reported the ATT write of frames[index] complete. */
        boolean writeDone;
        /** The glasses sent the empty flow-control response for frames[index]. */
        boolean flowAck;
        /** Final, non-flow-control response; set once received. */
        GmCodec.Message response;

        Request(String label, int service, int command, byte[] body, ResponseCallback callback) {
            this.label = label;
            this.service = service;
            this.command = command;
            this.body = body;
            this.callback = callback;
        }

        boolean lastFrame() {
            return index == frames.size() - 1;
        }
    }

    private final Context context;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("gm-ble");
    private final Handler handler;
    private final ArrayDeque<Request> queue = new ArrayDeque<Request>();
    private final GmCodec.Reassembler reassembler = new GmCodec.Reassembler();
    private final GmCodec.StreamFramer framer = new GmCodec.StreamFramer();

    // All fields below are touched only on the handler thread.
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic downlink;
    /** Open Classic SPP socket, or null when using BLE or disconnected. */
    private BluetoothSocket socket;
    private OutputStream socketOut;
    /** Bumped on every connect/close so threads from an old link are ignored. */
    private int linkGeneration;
    /** Negotiated ATT MTU; 23 is the Bluetooth default before negotiation. */
    private int mtu = 23;
    /** True once the uplink CCCD write succeeded; requests wait for it. */
    private boolean ready;
    /** Next GM event ID, cycling 1..255. One request is in flight, so IDs never collide. */
    private int nextEvent = 1;
    /** The request currently on the air, or null. */
    private Request current;

    private final Runnable timeout = new Runnable() {
        public void run() {
            if (current != null) finish("timeout after " + TIMEOUT_MS + " ms (operation may still have executed)");
        }
    };

    public GlassesConnection(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /**
     * Connects using the transport the device supports: Classic SPP for
     * Classic-only devices (an LE connection to a Classic address can only
     * time out), BLE GATT for everything else.
     */
    public void connect(final BluetoothDevice device) {
        handler.post(new Runnable() {
            public void run() {
                closeLink("reconnecting");
                int type;
                try {
                    type = device.getType();
                } catch (SecurityException error) {
                    listener.onState("Bluetooth permission denied", false);
                    return;
                }
                if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                    connectSpp(device);
                } else {
                    connectGatt(device);
                }
            }
        });
    }

    /**
     * BLE: autoConnect=false gives a direct, fast attempt; if the official App
     * already holds an LE link, Android shares it.
     */
    private void connectGatt(BluetoothDevice device) {
        listener.onState("Connecting to " + device.getAddress() + " (BLE)", false);
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException error) {
            listener.onState("Bluetooth permission denied", false);
            return;
        }
        if (gatt == null) listener.onState("Connection could not start", false);
    }

    /**
     * Classic: opens an RFCOMM socket to the GM command service on a worker
     * thread (connect blocks for up to ~12 s), trying 0x7033 then 0x1101.
     * The glasses accept one command connection, so this fails while the
     * official App holds it.
     */
    private void connectSpp(final BluetoothDevice device) {
        final int generation = linkGeneration;
        listener.onState("Connecting to " + device.getAddress() + " (Classic SPP)", false);
        new Thread(new Runnable() {
            public void run() {
                String failures = "";
                for (UUID uuid : new UUID[] {SPP_COMMAND, SPP_LEGACY}) {
                    BluetoothSocket candidate = null;
                    try {
                        candidate = device.createRfcommSocketToServiceRecord(uuid);
                        candidate.connect();
                        onSppConnected(generation, candidate, uuid);
                        return;
                    } catch (IOException | SecurityException error) {
                        failures += " " + uuid.toString().substring(4, 8) + ": " + error.getMessage() + ";";
                        closeQuietly(candidate);
                    }
                }
                final String detail = failures;
                handler.post(new Runnable() {
                    public void run() {
                        if (generation != linkGeneration) return;
                        listener.onLog("SPP connect failed:" + detail);
                        listener.onState("Could not open the glasses' command channel. Force-stop the MemoMind App "
                                + "(it holds the channel while connected), then tap the glasses again.", false);
                    }
                });
            }
        }, "gm-spp-connect").start();
    }

    /** Worker thread: hands the connected socket to the handler thread, or closes it if stale. */
    private void onSppConnected(final int generation, final BluetoothSocket connected, final UUID uuid) {
        handler.post(new Runnable() {
            public void run() {
                if (generation != linkGeneration) {
                    closeQuietly(connected);
                    return;
                }
                try {
                    socketOut = connected.getOutputStream();
                    startReader(generation, connected.getInputStream());
                } catch (IOException error) {
                    closeQuietly(connected);
                    listener.onState("SPP stream error: " + error.getMessage(), false);
                    return;
                }
                socket = connected;
                ready = true;
                listener.onLog("SPP connected to service " + uuid);
                listener.onState("Ready (Classic SPP)", true);
                startNext();
            }
        });
    }

    /**
     * Reader thread: blocks on the socket and posts each chunk to the handler
     * thread, where the framer splits it into frames. Ends when the socket closes.
     */
    private void startReader(final int generation, final InputStream in) {
        new Thread(new Runnable() {
            public void run() {
                byte[] buffer = new byte[1024];
                try {
                    while (true) {
                        int count = in.read(buffer);
                        if (count < 0) break;
                        final byte[] chunk = Arrays.copyOf(buffer, count);
                        handler.post(new Runnable() {
                            public void run() {
                                if (generation == linkGeneration) onStreamData(chunk);
                            }
                        });
                    }
                } catch (IOException ignored) {
                    // Socket closed or link lost; handled below.
                }
                handler.post(new Runnable() {
                    public void run() {
                        if (generation != linkGeneration) return;
                        closeLink("link lost");
                        listener.onState("Disconnected (Classic link closed)", false);
                    }
                });
            }
        }, "gm-spp-reader").start();
    }

    /** Splits stream bytes into frames and feeds them to the shared uplink path. */
    private void onStreamData(byte[] chunk) {
        List<byte[]> frames = framer.accept(chunk);
        int dropped = framer.takeDropped();
        if (dropped > 0) {
            listener.onLog("RX skipped " + dropped + " bytes not forming a GM frame; chunk: "
                    + GmCodec.hex(Arrays.copyOf(chunk, Math.min(chunk.length, 64))));
        }
        for (byte[] frame : frames) onUplinkFrame(frame);
    }

    /** Reassembles one physical uplink frame (BLE notification or SPP frame). */
    private void onUplinkFrame(byte[] frame) {
        GmCodec.Message message;
        try {
            message = reassembler.accept(frame);
        } catch (IllegalArgumentException error) {
            listener.onLog("RX dropped (" + error.getMessage() + "): " + GmCodec.hex(frame));
            return;
        }
        if (message != null) onMessage(message);
    }

    private static void closeQuietly(BluetoothSocket target) {
        if (target == null) return;
        try {
            target.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    /** Closes the link and fails any queued requests. */
    public void disconnect() {
        handler.post(new Runnable() {
            public void run() {
                closeLink("disconnected by user");
                listener.onState("Disconnected", false);
            }
        });
    }

    /** Disconnects and stops the connection thread; call from onDestroy. */
    public void shutdown() {
        disconnect();
        thread.quitSafely();
    }

    /** Queue one GM request; the callback runs on the connection thread. */
    public void send(final String label, final int service, final int command, final byte[] body,
                     final ResponseCallback callback) {
        handler.post(new Runnable() {
            public void run() {
                Request request = new Request(label, service, command, body, callback);
                if (!ready) {
                    callback.onError("not connected");
                    return;
                }
                queue.add(request);
                startNext();
            }
        });
    }

    /** Starts the next queued request if the link is idle and ready. */
    private void startNext() {
        if (current != null || queue.isEmpty() || !ready) return;
        Request request = queue.poll();
        request.event = nextEvent;
        nextEvent = nextEvent == 0xFF ? 1 : nextEvent + 1;
        // SPP carries full 512-byte frames. On BLE the ATT value limit is MTU - 3
        // (opcode + handle); GM frames never exceed 512 bytes.
        int frameLimit = socket != null ? GmCodec.MAX_FRAME : Math.min(GmCodec.MAX_FRAME, mtu - 3);
        try {
            request.frames = GmCodec.frames(request.event, request.service, request.command, request.body, frameLimit);
        } catch (IllegalArgumentException error) {
            request.callback.onError(error.getMessage());
            startNext();
            return;
        }
        current = request;
        listener.onLog("TX " + request.label + " event=" + request.event + " frames=" + request.frames.size()
                + " limit=" + frameLimit);
        writeFrame();
    }

    /**
     * Writes frames[index]. SPP: a blocking stream write, complete when it
     * returns. BLE: Write With Response; the pre-API-33 write call is used
     * because the app compiles against API 23, and it remains supported.
     */
    @SuppressWarnings("deprecation")
    private void writeFrame() {
        Request request = current;
        byte[] frame = request.frames.get(request.index);
        request.writeDone = false;
        request.flowAck = false;
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, TIMEOUT_MS);
        listener.onLog("  > " + GmCodec.hex(frame));
        if (socket != null) {
            try {
                socketOut.write(frame);
                socketOut.flush();
            } catch (IOException error) {
                finish("SPP write failed: " + error.getMessage());
                return;
            }
            onFrameWritten(BluetoothGatt.GATT_SUCCESS);
            return;
        }
        boolean started;
        try {
            downlink.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            downlink.setValue(frame);
            started = gatt.writeCharacteristic(downlink);
        } catch (SecurityException error) {
            started = false;
        }
        if (!started) finish("characteristic write could not start");
    }

    /** Write completion (ATT callback or SPP write); link-level only, not a GM business ACK. */
    private void onFrameWritten(int status) {
        Request request = current;
        if (request == null) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            finish("ATT write failed, GATT status " + status);
            return;
        }
        request.writeDone = true;
        advance();
    }

    /**
     * Routes a reassembled uplink message: a match on event/service/command is
     * either the empty flow-control reply between fragments or the final
     * response; anything else (e.g. 0F/29 plugin messages) is unsolicited.
     */
    private void onMessage(GmCodec.Message message) {
        Request request = current;
        listener.onLog("RX " + String.format("%02X/%02X", message.service, message.command)
                + " event=" + message.event + " " + GmCodec.describe(message));
        if (request != null && message.event == request.event && message.service == request.service
                && message.command == request.command) {
            if (!request.lastFrame() && message.body.length == 0) {
                request.flowAck = true;
            } else {
                request.response = message;
            }
            advance();
            return;
        }
        listener.onUnsolicited(message);
    }

    /**
     * Moves the current request forward once both halves of a step are done.
     * A final response is only delivered after its write callback, because
     * Android allows one outstanding GATT operation and the next request's
     * write would otherwise fail.
     */
    private void advance() {
        Request request = current;
        if (request == null || !request.writeDone) return;
        if (request.response != null) {
            finish(null);
        } else if (!request.lastFrame() && request.flowAck) {
            request.index++;
            writeFrame();
        }
    }

    /** Complete the current request with its response, or fail it with error. */
    private void finish(String error) {
        Request request = current;
        current = null;
        handler.removeCallbacks(timeout);
        if (error == null) {
            request.callback.onResponse(request.response);
        } else {
            listener.onLog("ERR " + request.label + ": " + error);
            request.callback.onError(error);
        }
        startNext();
    }

    /** Tears down the link (either transport) and every piece of per-connection state. */
    private void closeLink(String reason) {
        ready = false;
        linkGeneration++;
        reassembler.reset();
        framer.reset();
        handler.removeCallbacks(timeout);
        if (current != null) {
            Request request = current;
            current = null;
            request.callback.onError(reason);
        }
        while (!queue.isEmpty()) queue.poll().callback.onError(reason);
        downlink = null;
        mtu = 23;
        // Closing the socket also unblocks and ends the reader thread.
        closeQuietly(socket);
        socket = null;
        socketOut = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
                // Permission revoked; the stack releases the client on process exit.
            }
            gatt = null;
        }
    }

    /** Ignores late callbacks from a GATT client that was already closed. */
    private boolean isCurrent(BluetoothGatt candidate) {
        return candidate == gatt;
    }

    /**
     * After discovery: finds the service holding both GM characteristics (its
     * UUID differs between firmware representations, so it is not hardcoded),
     * then enables uplink notifications before any write.
     */
    private void setUp(BluetoothGatt candidate) {
        BluetoothGattCharacteristic up = null;
        BluetoothGattCharacteristic down = null;
        for (BluetoothGattService service : candidate.getServices()) {
            BluetoothGattCharacteristic d = service.getCharacteristic(DOWNLINK);
            BluetoothGattCharacteristic u = service.getCharacteristic(UPLINK);
            if (d != null && u != null) {
                down = d;
                up = u;
                listener.onLog("GM control service " + service.getUuid());
                break;
            }
        }
        if (down == null) {
            closeLink("unsupported device");
            listener.onState("Unsupported: no service with 0x2021/0x2022 characteristics", false);
            return;
        }
        int props = up.getProperties();
        BluetoothGattDescriptor cccd = up.getDescriptor(CCCD);
        if ((props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0
                || cccd == null) {
            closeLink("unsupported device");
            listener.onState("Unsupported: 0x2022 has no notify/CCCD", false);
            return;
        }
        downlink = down;
        boolean ok;
        try {
            ok = candidate.setCharacteristicNotification(up, true);
            cccd.setValue((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            ok = ok && candidate.writeDescriptor(cccd);
        } catch (SecurityException error) {
            ok = false;
        }
        if (!ok) {
            closeLink("subscribe failed");
            listener.onState("Could not subscribe to 0x2022", false);
            return;
        }
        listener.onState("Subscribing to notifications", false);
    }

    /**
     * Binder-thread GATT callbacks. Each one copies what it needs and posts to
     * the handler thread. Setup order: connect -> MTU -> discover -> CCCD -> ready.
     */
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            handler.post(new Runnable() {
                public void run() {
                    if (!isCurrent(g)) return;
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        listener.onState("Connected; negotiating MTU", false);
                        boolean requested;
                        try {
                            requested = g.requestMtu(REQUESTED_MTU);
                            if (!requested) requested = g.discoverServices();
                        } catch (SecurityException error) {
                            requested = false;
                        }
                        if (!requested) {
                            closeLink("setup failed");
                            listener.onState("Setup failed", false);
                        }
                    } else {
                        closeLink("link lost");
                        listener.onState("Disconnected (" + gattStatus(status) + ")", false);
                    }
                }
            });
        }

        @Override
        public void onMtuChanged(final BluetoothGatt g, final int value, final int status) {
            handler.post(new Runnable() {
                public void run() {
                    if (!isCurrent(g)) return;
                    if (status == BluetoothGatt.GATT_SUCCESS) mtu = value;
                    listener.onLog("MTU " + mtu + " (GM frame limit " + Math.min(GmCodec.MAX_FRAME, mtu - 3) + ")");
                    listener.onState("Discovering services", false);
                    boolean ok;
                    try {
                        ok = g.discoverServices();
                    } catch (SecurityException error) {
                        ok = false;
                    }
                    if (!ok) {
                        closeLink("discovery failed");
                        listener.onState("Service discovery failed", false);
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            handler.post(new Runnable() {
                public void run() {
                    if (!isCurrent(g)) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeLink("discovery failed");
                        listener.onState("Service discovery failed (" + status + ")", false);
                        return;
                    }
                    setUp(g);
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor descriptor, final int status) {
            handler.post(new Runnable() {
                public void run() {
                    if (!isCurrent(g) || ready || downlink == null) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeLink("subscribe failed");
                        listener.onState("CCCD write failed (" + status + ")", false);
                        return;
                    }
                    ready = true;
                    listener.onState("Ready", true);
                    startNext();
                }
            });
        }

        @Override
        public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic characteristic,
                                          final int status) {
            handler.post(new Runnable() {
                public void run() {
                    if (isCurrent(g)) onFrameWritten(status);
                }
            });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            onNotification(g, characteristic, characteristic.getValue());
        }

        // API 33+ overload; not in the compile SDK, so no @Override. Overriding it
        // avoids relying on the deprecated shared characteristic value.
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            onNotification(g, characteristic, value);
        }

        private void onNotification(final BluetoothGatt g, BluetoothGattCharacteristic characteristic,
                                    byte[] value) {
            if (!UPLINK.equals(characteristic.getUuid())) return;
            final byte[] copy = value == null ? new byte[0] : value.clone();
            handler.post(new Runnable() {
                public void run() {
                    // On BLE each notification is exactly one physical frame.
                    if (isCurrent(g)) onUplinkFrame(copy);
                }
            });
        }
    };

    /** Explains the common connection failure codes seen in the field. */
    static String gattStatus(int status) {
        switch (status) {
            case 0: return "normal";
            case 8: return "status 8: supervision timeout, out of range";
            case 19: return "status 19: glasses closed the link";
            case 22: return "status 22: phone closed the link";
            case 133: return "status 133: glasses not reachable; wake them or keep them near the phone";
            case 147: return "status 147: connection timeout; no BLE answer at this address";
            default: return "GATT status " + status;
        }
    }

    /** Expands a 16-bit Bluetooth UUID onto the Bluetooth base UUID. */
    private static UUID uuid16(int value) {
        return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", value));
    }
}

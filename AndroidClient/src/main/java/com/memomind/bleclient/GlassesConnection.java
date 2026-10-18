package com.memomind.bleclient;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * Phone-facing GM command link over BLE GATT (BLUETOOTH_DEVELOPER_GUIDE.md).
 *
 * Discovers the service containing 0x2021/0x2022, subscribes to the uplink
 * before writing, writes one complete GM frame per characteristic write within
 * the negotiated MTU, waits for the empty flow-control response between
 * fragments, and serializes requests so messages never interleave. All state
 * is confined to one handler thread.
 */
public final class GlassesConnection {
    public static final UUID DOWNLINK = uuid16(0x2021);
    public static final UUID UPLINK = uuid16(0x2022);
    public static final UUID CCCD = uuid16(0x2902);

    static final long TIMEOUT_MS = 5000;
    static final int REQUESTED_MTU = 517;

    public interface Listener {
        void onState(String state, boolean ready);
        void onLog(String line);
        /** Messages not matched to a pending request, such as 0F/29 plugin messages. */
        void onUnsolicited(GmCodec.Message message);
    }

    public interface ResponseCallback {
        void onResponse(GmCodec.Message response);
        void onError(String error);
    }

    private final class Request {
        final String label;
        final int service;
        final int command;
        final byte[] body;
        final ResponseCallback callback;
        int event;
        List<byte[]> frames;
        int index;
        boolean writeDone;
        boolean flowAck;
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

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic downlink;
    private int mtu = 23;
    private boolean ready;
    private int nextEvent = 1;
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

    public void connect(final BluetoothDevice device) {
        handler.post(new Runnable() {
            public void run() {
                closeGatt("reconnecting");
                listener.onState("Connecting to " + device.getAddress(), false);
                try {
                    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                } catch (SecurityException error) {
                    listener.onState("Bluetooth permission denied", false);
                    return;
                }
                if (gatt == null) listener.onState("Connection could not start", false);
            }
        });
    }

    public void disconnect() {
        handler.post(new Runnable() {
            public void run() {
                closeGatt("disconnected by user");
                listener.onState("Disconnected", false);
            }
        });
    }

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

    private void startNext() {
        if (current != null || queue.isEmpty() || !ready) return;
        Request request = queue.poll();
        request.event = nextEvent;
        nextEvent = nextEvent == 0xFF ? 1 : nextEvent + 1;
        int frameLimit = Math.min(GmCodec.MAX_FRAME, mtu - 3);
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

    @SuppressWarnings("deprecation")
    private void writeFrame() {
        Request request = current;
        byte[] frame = request.frames.get(request.index);
        request.writeDone = false;
        request.flowAck = false;
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, TIMEOUT_MS);
        listener.onLog("  > " + GmCodec.hex(frame));
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

    private void closeGatt(String reason) {
        ready = false;
        reassembler.reset();
        handler.removeCallbacks(timeout);
        if (current != null) {
            Request request = current;
            current = null;
            request.callback.onError(reason);
        }
        while (!queue.isEmpty()) queue.poll().callback.onError(reason);
        downlink = null;
        mtu = 23;
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

    private boolean isCurrent(BluetoothGatt candidate) {
        return candidate == gatt;
    }

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
            closeGatt("unsupported device");
            listener.onState("Unsupported: no service with 0x2021/0x2022 characteristics", false);
            return;
        }
        int props = up.getProperties();
        BluetoothGattDescriptor cccd = up.getDescriptor(CCCD);
        if ((props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0
                || cccd == null) {
            closeGatt("unsupported device");
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
            closeGatt("subscribe failed");
            listener.onState("Could not subscribe to 0x2022", false);
            return;
        }
        listener.onState("Subscribing to notifications", false);
    }

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
                            closeGatt("setup failed");
                            listener.onState("Setup failed", false);
                        }
                    } else {
                        closeGatt("link lost");
                        listener.onState("Disconnected (GATT status " + status + ")", false);
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
                        closeGatt("discovery failed");
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
                        closeGatt("discovery failed");
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
                        closeGatt("subscribe failed");
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
                    if (!isCurrent(g)) return;
                    GmCodec.Message message;
                    try {
                        message = reassembler.accept(copy);
                    } catch (IllegalArgumentException error) {
                        listener.onLog("RX dropped (" + error.getMessage() + "): " + GmCodec.hex(copy));
                        return;
                    }
                    if (message != null) onMessage(message);
                }
            });
        }
    };

    private static UUID uuid16(int value) {
        return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", value));
    }
}

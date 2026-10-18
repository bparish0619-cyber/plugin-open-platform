package com.memomind.bleclient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * GM framing and TLV codec for the public business protocol.
 *
 * <p>Wire layout of one physical frame (GlassSDK/docs/PROTOCOL.md):
 * <pre>
 * head:u8  logical_length:u24  event:u8  service:u8  command:u8  body...  sum:u16
 * </pre>
 * head is 0xFA for the first frame and 1, 2, ... for continuations. Every
 * frame repeats the logical length (all TLV bytes + 9) and event/service/
 * command, and carries its own additive checksum. All integers are
 * big-endian. The body is a TLV stream that may straddle frames.
 *
 * <p>Mirrors GlassSDK/docs/examples/bluetooth_wire.py. Pure Java so it can be
 * tested on a desktop JVM. No GMP installer operations.
 */
public final class GmCodec {
    /** Largest logical message the reference client accepts (80 KiB). */
    public static final int MAX_LOGICAL = 80 * 1024;
    /** Firmware physical frame ceiling. */
    public static final int MAX_FRAME = 512;
    /** Bytes before the body: head, 24-bit length, event, service, command. */
    public static final int HEADER = 7;
    /** Header plus the two checksum bytes. */
    public static final int ENVELOPE = 9;
    /** Head byte of the first frame of a message. */
    public static final int FIRST_HEAD = 0xFA;
    /** Keep below the App/firmware continuation-head wrap difference. */
    public static final int MAX_FRAMES = 239;

    // TLV types: type:u8, length:u24, value.
    public static final int TLV_BYTES = 0x01;
    public static final int TLV_STRING = 0x02;
    public static final int TLV_INT32 = 0x03;
    public static final int TLV_JSON = 0x05;
    public static final int TLV_STATUS = 0x06;
    public static final int TLV_INT16 = 0x07;
    public static final int TLV_INT8 = 0x08;

    // Services and commands used by the app (DEVICE_BUSINESS_PROTOCOL.md, PROTOCOL.md).
    public static final int SERVICE_SYSTEM = 0x01;
    public static final int SERVICE_NOTIFICATION = 0x05;
    public static final int SERVICE_PLUGIN = 0x0F;
    public static final int CMD_HEARTBEAT = 0x01;
    public static final int CMD_DEVICE_INFO = 0x02;
    public static final int CMD_DEVICE_STATUS = 0x06;
    public static final int CMD_SCREEN_HEIGHT = 0x0A;
    public static final int CMD_OPTICAL_DISTANCE = 0x0B;
    public static final int CMD_BRIGHTNESS = 0x0E;
    public static final int CMD_AUTO_BRIGHTNESS = 0x0F;
    public static final int CMD_NOTIFICATION_PUSH = 0x01;
    public static final int CMD_APP_MESSAGE = 0x28;
    public static final int CMD_APP_MESSAGE_UPLINK = 0x29;

    static final Charset UTF8 = Charset.forName("UTF-8");

    private GmCodec() {}

    /** Encodes one TLV: type, 24-bit big-endian length, value. */
    public static byte[] tlv(int type, byte[] value) {
        if (value.length > MAX_LOGICAL - 13) {
            throw new IllegalArgumentException("TLV exceeds logical limit");
        }
        byte[] out = new byte[4 + value.length];
        out[0] = (byte) type;
        putU24(out, 1, value.length);
        System.arraycopy(value, 0, out, 4, value.length);
        return out;
    }

    public static byte[] int8(int value) {
        if (value < 0 || value > 0xFF) throw new IllegalArgumentException("INT8 out of range");
        return tlv(TLV_INT8, new byte[] {(byte) value});
    }

    public static byte[] int16(int value) {
        if (value < 0 || value > 0xFFFF) throw new IllegalArgumentException("INT16 out of range");
        return tlv(TLV_INT16, new byte[] {(byte) (value >> 8), (byte) value});
    }

    public static byte[] string(String value) {
        return tlv(TLV_STRING, value.getBytes(UTF8));
    }

    public static byte[] json(String value) {
        return tlv(TLV_JSON, value.getBytes(UTF8));
    }

    /** Joins TLVs into one body. */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    /** Service 0x0F command 0x28 body: INT16 channel, BYTES data. */
    public static byte[] applicationBody(int channel, byte[] payload) {
        if (payload.length == 0) {
            throw new IllegalArgumentException("running-plugin message payload must be nonempty");
        }
        return concat(int16(channel), tlv(TLV_BYTES, payload));
    }

    /** Web Bridge channel 2 payload: id:u8 x y w h:u16 border:u8 radius:u8 utf8. */
    public static byte[] webBridgeText(int id, int x, int y, int width, int height,
                                       int border, int radius, String text) {
        byte[] utf8 = text.getBytes(UTF8);
        byte[] out = new byte[11 + utf8.length];
        out[0] = (byte) id;
        putU16(out, 1, x);
        putU16(out, 3, y);
        putU16(out, 5, width);
        putU16(out, 7, height);
        out[9] = (byte) border;
        out[10] = (byte) radius;
        System.arraycopy(utf8, 0, out, 11, utf8.length);
        return out;
    }

    /**
     * Split one logical message into complete physical frames, each with its own
     * header and additive checksum. frameBytes is the validated write limit.
     */
    public static List<byte[]> frames(int event, int service, int command, byte[] body, int frameBytes) {
        if (frameBytes < 10 || frameBytes > MAX_FRAME) {
            throw new IllegalArgumentException("frameBytes must be 10..512");
        }
        if (body.length + ENVELOPE > MAX_LOGICAL) {
            throw new IllegalArgumentException("logical packet too large");
        }
        int size = frameBytes - ENVELOPE;
        int count = Math.max(1, (body.length + size - 1) / size);
        if (count > MAX_FRAMES) {
            throw new IllegalArgumentException("use smaller business messages or a larger negotiated MTU");
        }
        int logical = body.length + ENVELOPE;
        List<byte[]> result = new ArrayList<byte[]>(count);
        for (int i = 0; i < count; i++) {
            int start = i * size;
            int length = Math.min(size, body.length - start);
            byte[] raw = new byte[HEADER + length + 2];
            raw[0] = (byte) (i == 0 ? FIRST_HEAD : i);
            putU24(raw, 1, logical);
            raw[4] = (byte) event;
            raw[5] = (byte) service;
            raw[6] = (byte) command;
            System.arraycopy(body, start, raw, HEADER, length);
            putU16(raw, raw.length - 2, checksum(raw, raw.length - 2));
            result.add(raw);
        }
        return result;
    }

    /** Additive sum of the first length bytes, modulo 65536. */
    static int checksum(byte[] data, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) sum += data[i] & 0xFF;
        return sum & 0xFFFF;
    }

    /** One parsed physical frame. */
    public static final class Frame {
        public final int head;
        public final int logical;
        public final int event;
        public final int service;
        public final int command;
        public final byte[] chunk;

        Frame(int head, int logical, int event, int service, int command, byte[] chunk) {
            this.head = head;
            this.logical = logical;
            this.event = event;
            this.service = service;
            this.command = command;
            this.chunk = chunk;
        }
    }

    /** Parse one complete physical frame, not an arbitrary stream read. */
    public static Frame parseFrame(byte[] packet) {
        if (packet.length < ENVELOPE || packet.length > MAX_FRAME) {
            throw new IllegalArgumentException("invalid physical size " + packet.length);
        }
        int logical = u24(packet, 1);
        if (logical < packet.length || logical > MAX_LOGICAL) {
            throw new IllegalArgumentException("invalid logical size " + logical);
        }
        if (checksum(packet, packet.length - 2) != u16(packet, packet.length - 2)) {
            throw new IllegalArgumentException("bad additive checksum");
        }
        int head = packet[0] & 0xFF;
        if (head != FIRST_HEAD && (head < 1 || head > MAX_FRAMES)) {
            throw new IllegalArgumentException("invalid fragment head " + head);
        }
        byte[] chunk = new byte[packet.length - ENVELOPE];
        System.arraycopy(packet, HEADER, chunk, 0, chunk.length);
        return new Frame(head, logical, packet[4] & 0xFF, packet[5] & 0xFF, packet[6] & 0xFF, chunk);
    }

    /** A complete logical message. */
    public static final class Message {
        public final int event;
        public final int service;
        public final int command;
        public final byte[] body;

        public Message(int event, int service, int command, byte[] body) {
            this.event = event;
            this.service = service;
            this.command = command;
            this.body = body;
        }
    }

    /**
     * Reassembles ordered physical frames into logical messages. A new first
     * frame replaces an incomplete message. Call reset() on disconnect.
     */
    public static final class Reassembler {
        private Frame first;
        private int expectedHead;
        private ByteArrayOutputStream body;

        public void reset() {
            first = null;
            body = null;
        }

        /** Returns the completed message, or null while more frames are needed. */
        public Message accept(byte[] packet) {
            Frame frame;
            try {
                frame = parseFrame(packet);
            } catch (IllegalArgumentException error) {
                reset();
                throw error;
            }
            if (frame.head == FIRST_HEAD) {
                first = frame;
                expectedHead = 1;
                body = new ByteArrayOutputStream();
            } else {
                if (first == null) {
                    throw new IllegalArgumentException("continuation without first frame");
                }
                if (frame.head != expectedHead || frame.logical != first.logical
                        || frame.event != first.event || frame.service != first.service
                        || frame.command != first.command) {
                    reset();
                    throw new IllegalArgumentException("out-of-order fragment or identity changed");
                }
                expectedHead++;
            }
            body.write(frame.chunk, 0, frame.chunk.length);
            int expected = first.logical - ENVELOPE;
            if (body.size() > expected) {
                reset();
                throw new IllegalArgumentException("oversized logical body");
            }
            if (body.size() < expected) return null;
            Message message = new Message(first.event, first.service, first.command, body.toByteArray());
            reset();
            return message;
        }
    }

    /** Reassemble a supplied sequence of complete, ordered physical frames. */
    public static byte[] reassemble(List<byte[]> packets) {
        if (packets.isEmpty()) throw new IllegalArgumentException("missing frames");
        Reassembler reassembler = new Reassembler();
        for (int i = 0; i < packets.size(); i++) {
            Message message = reassembler.accept(packets.get(i));
            if (message != null) {
                if (i != packets.size() - 1) {
                    throw new IllegalArgumentException("extra fragment after complete message");
                }
                return message.body;
            }
        }
        throw new IllegalArgumentException("incomplete logical body");
    }

    /** One parsed TLV. */
    public static final class Tlv {
        public final int type;
        public final byte[] value;

        Tlv(int type, byte[] value) {
            this.type = type;
            this.value = value;
        }

        public long unsigned() {
            long result = 0;
            for (byte b : value) result = (result << 8) | (b & 0xFF);
            return result;
        }

        public String text() {
            int length = value.length;
            if (type == TLV_JSON) {
                while (length > 0 && value[length - 1] == 0) length--;
            }
            return new String(value, 0, length, UTF8);
        }
    }

    /** Parses a reassembled body; rejects truncation and wrong integer widths. */
    public static List<Tlv> parseTlvs(byte[] body) {
        List<Tlv> result = new ArrayList<Tlv>();
        int pos = 0;
        while (pos < body.length) {
            if (body.length - pos < 4) throw new IllegalArgumentException("truncated TLV header");
            int type = body[pos] & 0xFF;
            int size = u24(body, pos + 1);
            pos += 4;
            if (size > body.length - pos) throw new IllegalArgumentException("truncated TLV value");
            int width = integerWidth(type);
            if (width != 0 && size != width) {
                throw new IllegalArgumentException("invalid integer/status width");
            }
            byte[] value = new byte[size];
            System.arraycopy(body, pos, value, 0, size);
            result.add(new Tlv(type, value));
            pos += size;
        }
        return result;
    }

    private static int integerWidth(int type) {
        switch (type) {
            case TLV_INT32: return 4;
            case TLV_STATUS: return 1;
            case TLV_INT16: return 2;
            case TLV_INT8: return 1;
            default: return 0;
        }
    }

    /** System STATUS TLV namespace (PROTOCOL.md, system service). */
    public static String systemStatusName(int status) {
        switch (status) {
            case 0: return "Success";
            case 1: return "Generic error";
            case 2: return "Invalid command";
            case 3: return "Invalid parameter";
            case 4: return "Out of memory";
            case 5: return "Timeout";
            case 6: return "Not supported";
            case 7: return "Busy";
            case 8: return "Error; retry immediately";
            default: return "Unknown";
        }
    }

    /** Plugin INT8 status namespace (GM_PLUGIN_E*). */
    public static String pluginStatusName(int status) {
        switch (status) {
            case 0: return "GM_PLUGIN_OK";
            case 1: return "GM_PLUGIN_EINVAL";
            case 2: return "GM_PLUGIN_ENOTSUP";
            case 3: return "GM_PLUGIN_EBUSY";
            case 4: return "GM_PLUGIN_ENOMEM";
            case 5: return "GM_PLUGIN_EIO";
            case 6: return "GM_PLUGIN_EPERM";
            case 7: return "GM_PLUGIN_ESTATE";
            case 8: return "GM_PLUGIN_EVERSION";
            case 9: return "GM_PLUGIN_EFULL";
            default: return "Unknown";
        }
    }

    /** Human-readable TLV summary; namespaces chosen by TLV type and service. */
    public static String describe(Message message) {
        if (message.body.length == 0) return "(empty)";
        List<Tlv> tlvs;
        try {
            tlvs = parseTlvs(message.body);
        } catch (IllegalArgumentException error) {
            return "malformed TLVs: " + error.getMessage() + " body=" + hex(message.body);
        }
        StringBuilder out = new StringBuilder();
        for (Tlv tlv : tlvs) {
            if (out.length() > 0) out.append("; ");
            switch (tlv.type) {
                case TLV_STATUS:
                    out.append("STATUS ").append(tlv.unsigned()).append(" (")
                            .append(systemStatusName((int) tlv.unsigned())).append(')');
                    break;
                case TLV_INT8:
                    out.append("INT8 ").append(tlv.unsigned());
                    if (message.service == SERVICE_PLUGIN && message.command == CMD_APP_MESSAGE) {
                        out.append(" (").append(pluginStatusName((int) tlv.unsigned())).append(')');
                    }
                    break;
                case TLV_INT16:
                    out.append("INT16 ").append(tlv.unsigned());
                    break;
                case TLV_INT32:
                    out.append("INT32 ").append(tlv.unsigned());
                    break;
                case TLV_STRING:
                    out.append("STRING ").append(tlv.text());
                    break;
                case TLV_JSON:
                    out.append("JSON ").append(tlv.text());
                    break;
                case TLV_BYTES:
                    out.append("BYTES[").append(tlv.value.length).append("] ").append(hex(tlv.value));
                    break;
                default:
                    out.append("type ").append(tlv.type).append(' ').append(hex(tlv.value));
            }
        }
        return out.toString();
    }

    /** Space-separated uppercase hex, for the log. */
    public static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02X", data[i] & 0xFF));
        }
        return out.toString();
    }

    static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    static int u24(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 16) | ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
    }

    static void putU16(byte[] out, int offset, int value) {
        if (value < 0 || value > 0xFFFF) throw new IllegalArgumentException("u16 out of range");
        out[offset] = (byte) (value >> 8);
        out[offset + 1] = (byte) value;
    }

    static void putU24(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >> 16);
        out[offset + 1] = (byte) (value >> 8);
        out[offset + 2] = (byte) value;
    }
}

package com.memomind.bleclient;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Desktop JVM checks for GmCodec. Run via ./build.sh test.
 * Argument: vector file produced by tools/export_vectors.py.
 */
public final class GmCodecTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        goldenVectors();
        fragmentation();
        malformedInput();
        reassemblerRecovery();
        describeNamespaces();
        streamFraming();
        int vectors = args.length > 0 ? referenceVectors(args[0]) : 0;
        System.out.println("PASS: " + checks + " checks, " + vectors + " reference vectors from bluetooth_wire.py");
    }

    private static void goldenVectors() {
        // Brightness 5 and application message "ABC" from PROTOCOL.md / HUD_PROTOCOL.md.
        expectHex(GmCodec.frames(1, 1, 14, GmCodec.int8(5), 512).get(0), "fa00000e01010e08000001050126");
        expectHex(GmCodec.frames(1, 15, 40, GmCodec.applicationBody(1, "ABC".getBytes(GmCodec.UTF8)), 512).get(0),
                "fa000016010f2807000002000101000003414243021c");
        // Heartbeat: nine-byte envelope with no TLVs.
        expect(GmCodec.frames(1, 1, 1, new byte[0], 512).get(0).length == 9, "heartbeat is 9 bytes");
        // Web Bridge text "Hi" payload from bluetooth_wire.py.
        expectHex(GmCodec.webBridgeText(1, 10, 20, 200, 40, 0, 0, "Hi"), "01000a001400c8002800004869");
    }

    private static void fragmentation() {
        byte[] body = GmCodec.applicationBody(1, repeat((byte) 'A', 600));
        List<byte[]> frames = GmCodec.frames(1, 15, 40, body, 512);
        expect(frames.size() == 2 && frames.get(0).length == 512 && frames.get(1).length == 116, "512/116 split");
        expectHex(Arrays.copyOfRange(frames.get(0), 510, 512), "7f31");
        expectHex(Arrays.copyOfRange(frames.get(1), 114, 116), "1bd1");
        expect(Arrays.equals(GmCodec.reassemble(frames), body), "reassemble 600-byte body");
        // Minimum-MTU writes: 20-byte frames carry 11 body bytes each.
        List<byte[]> small = GmCodec.frames(1, 15, 40, body, 20);
        for (byte[] frame : small) expect(frame.length <= 20, "frame within 20-byte write");
        expect(Arrays.equals(GmCodec.reassemble(small), body), "reassemble 20-byte frames");
        rejects(new Runnable() {
            public void run() { GmCodec.frames(1, 15, 40, new byte[11 * 239 + 1], 20); }
        });
        rejects(new Runnable() {
            public void run() { GmCodec.frames(1, 1, 1, new byte[0], 9); }
        });
    }

    private static void malformedInput() {
        final List<byte[]> fragmented = GmCodec.frames(1, 15, 40, GmCodec.applicationBody(1, repeat((byte) 'A', 600)), 512);
        rejects(new Runnable() { public void run() { GmCodec.parseFrame(new byte[] {(byte) 0xFA}); } });
        final byte[] damaged = fragmented.get(0).clone();
        damaged[damaged.length - 1] ^= 1;
        rejects(new Runnable() { public void run() { GmCodec.parseFrame(damaged); } });
        rejects(new Runnable() { public void run() { GmCodec.reassemble(fragmented.subList(0, 1)); } });
        rejects(new Runnable() {
            public void run() {
                List<byte[]> reversed = new ArrayList<byte[]>(fragmented);
                java.util.Collections.reverse(reversed);
                GmCodec.reassemble(reversed);
            }
        });
        rejects(new Runnable() {
            public void run() {
                List<byte[]> extra = new ArrayList<byte[]>(fragmented);
                extra.add(fragmented.get(1));
                GmCodec.reassemble(extra);
            }
        });
        rejects(new Runnable() { public void run() { GmCodec.parseTlvs(unhex("08000002ff")); } });
        rejects(new Runnable() { public void run() { GmCodec.parseTlvs(unhex("080000020001")); } });
        rejects(new Runnable() { public void run() { GmCodec.parseTlvs(unhex("0800")); } });
        rejects(new Runnable() { public void run() { GmCodec.applicationBody(1, new byte[0]); } });
        rejects(new Runnable() { public void run() { GmCodec.int8(256); } });
    }

    private static void reassemblerRecovery() {
        GmCodec.Reassembler reassembler = new GmCodec.Reassembler();
        List<byte[]> fragmented = GmCodec.frames(2, 15, 41, GmCodec.applicationBody(3, repeat((byte) 'B', 600)), 512);
        expect(reassembler.accept(fragmented.get(0)) == null, "first fragment pending");
        // A new first frame replaces the incomplete message.
        byte[] heartbeatAck = GmCodec.frames(3, 1, 1, GmCodec.tlv(GmCodec.TLV_STATUS, new byte[] {0}), 512).get(0);
        GmCodec.Message message = reassembler.accept(heartbeatAck);
        expect(message != null && message.event == 3 && message.service == 1 && message.command == 1, "replacement message");
        // The stale continuation must now be rejected, not appended.
        final GmCodec.Reassembler stale = reassembler;
        final byte[] continuation = fragmented.get(1);
        rejects(new Runnable() { public void run() { stale.accept(continuation); } });
        // Reset clears partial state after disconnect.
        reassembler.accept(fragmented.get(0));
        reassembler.reset();
        rejects(new Runnable() { public void run() { stale.accept(continuation); } });
        // Empty flow-control response parses as a complete empty message.
        GmCodec.Message empty = reassembler.accept(GmCodec.frames(4, 15, 40, new byte[0], 512).get(0));
        expect(empty != null && empty.body.length == 0, "empty flow-control frame");
    }

    private static void describeNamespaces() {
        GmCodec.Message system = new GmCodec.Message(1, 1, 14, GmCodec.tlv(GmCodec.TLV_STATUS, new byte[] {3}));
        expect(GmCodec.describe(system).equals("STATUS 3 (Invalid parameter)"), "system namespace");
        GmCodec.Message plugin = new GmCodec.Message(1, 15, 40,
                GmCodec.concat(GmCodec.int8(7), GmCodec.tlv(GmCodec.TLV_INT32, new byte[4])));
        expect(GmCodec.describe(plugin).equals("INT8 7 (GM_PLUGIN_ESTATE); INT32 0"), "plugin namespace");
        GmCodec.Message info = new GmCodec.Message(1, 1, 2, GmCodec.string("{\"pver\":1}"));
        expect(GmCodec.describe(info).equals("STRING {\"pver\":1}"), "STRING JSON");
    }

    /** Classic SPP delivers a byte stream; StreamFramer must recover physical frames. */
    private static void streamFraming() {
        byte[] heartbeat = GmCodec.frames(1, 1, 1, GmCodec.tlv(GmCodec.TLV_STATUS, new byte[] {0}), 512).get(0);
        byte[] body = GmCodec.applicationBody(2, repeat((byte) 'C', 600));
        List<byte[]> big = GmCodec.frames(2, 15, 41, body, 512);
        byte[] empty = GmCodec.frames(3, 15, 40, new byte[0], 512).get(0);
        byte[] stream = GmCodec.concat(heartbeat, big.get(0), big.get(1), empty);

        // Every split point, one byte at a time, and the whole stream at once.
        for (int chunk : new int[] {1, 7, 100, stream.length}) {
            GmCodec.StreamFramer framer = new GmCodec.StreamFramer();
            List<byte[]> out = new ArrayList<byte[]>();
            for (int i = 0; i < stream.length; i += chunk) {
                out.addAll(framer.accept(Arrays.copyOfRange(stream, i, Math.min(stream.length, i + chunk))));
            }
            expect(out.size() == 4, "four frames at chunk " + chunk);
            expect(Arrays.equals(out.get(0), heartbeat) && Arrays.equals(out.get(1), big.get(0))
                    && Arrays.equals(out.get(2), big.get(1)) && Arrays.equals(out.get(3), empty), "frames intact");
            expect(framer.takeDropped() == 0, "nothing dropped at chunk " + chunk);
            List<byte[]> pair = new ArrayList<byte[]>();
            pair.add(out.get(1));
            pair.add(out.get(2));
            expect(Arrays.equals(GmCodec.reassemble(pair), body), "stream frames reassemble");
        }

        // Garbage before a frame (or an unknown outer envelope) is skipped and counted.
        GmCodec.StreamFramer framer = new GmCodec.StreamFramer();
        List<byte[]> out = framer.accept(GmCodec.concat(new byte[] {0x00, 0x55, 0x00, 0x00}, heartbeat));
        expect(out.size() == 1 && Arrays.equals(out.get(0), heartbeat), "resync after garbage");
        expect(framer.takeDropped() == 4, "garbage bytes counted");
        expect(framer.takeDropped() == 0, "dropped count resets");

        // A false header announcing a long frame stalls only until enough bytes
        // arrive to fail its checksum; then every real frame is recovered.
        framer = new GmCodec.StreamFramer();
        out = framer.accept(new byte[] {(byte) 0xFA, 0x00, (byte) 0xFA, 0x00});
        expect(out.isEmpty(), "false header waits");
        for (int i = 0; i < 40; i++) out.addAll(framer.accept(heartbeat));
        expect(out.size() == 40, "all frames recovered after false header: " + out.size());
        expect(framer.takeDropped() == 4, "false header bytes counted");

        // A corrupted checksum is dropped; the next good frame still parses.
        byte[] bad = heartbeat.clone();
        bad[bad.length - 1] ^= 1;
        out = framer.accept(GmCodec.concat(bad, heartbeat));
        expect(out.size() == 1 && Arrays.equals(out.get(0), heartbeat), "skip corrupted frame");
        expect(framer.takeDropped() > 0, "corruption counted");

        // A partial frame waits for more bytes; reset() discards it.
        expect(framer.accept(Arrays.copyOfRange(heartbeat, 0, 5)).isEmpty(), "partial frame pending");
        framer.reset();
        expect(framer.accept(heartbeat).size() == 1, "reset clears partial frame");
    }

    private static int referenceVectors(String path) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
        int count = 0;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] field = line.split("\t");
                int service = Integer.parseInt(field[1]);
                int command = Integer.parseInt(field[2]);
                byte[] body = field[3].equals("-") ? new byte[0] : unhex(field[3]);
                int limit = Integer.parseInt(field[4]);
                String[] expected = field[5].split(",");
                List<byte[]> actual = GmCodec.frames(1, service, command, body, limit);
                expect(actual.size() == expected.length, field[0] + " frame count at " + limit);
                for (int i = 0; i < expected.length; i++) expectHex(actual.get(i), expected[i]);
                expect(Arrays.equals(GmCodec.reassemble(actual), body), field[0] + " reassemble at " + limit);
                GmCodec.parseTlvs(body);
                count++;
            }
        } finally {
            reader.close();
        }
        expect(count > 0, "reference vectors present");
        return count;
    }

    private static byte[] repeat(byte value, int count) {
        byte[] out = new byte[count];
        Arrays.fill(out, value);
        return out;
    }

    static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static void expectHex(byte[] actual, String expected) {
        String got = GmCodec.hex(actual).replace(" ", "").toLowerCase();
        expect(got.equals(expected), "expected " + expected + " got " + got);
    }

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable action) {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("malformed input accepted");
    }
}

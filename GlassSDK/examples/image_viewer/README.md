# Pixel Image Viewer — native FrameBuffer receiver

Custom pair with `PhoneSDK/examples/image-viewer`. The phone caches numbered image slots, crops/pads without scaling to 600×350 and sends the selected
GRAY_4 frame, using raw LZ4 when smaller. The glasses decompress the pixel bytes
through the Host LZ4 extension, verify size/hash, then copy to FrameBuffer. They
never decode JPEG/PNG, resize or resample the source image.

Build from the repository root:

```sh
python3 GlassSDK/build.py build --example image_viewer
```

Outputs: `GlassSDK/build-host/.build/image_viewer/image_viewer.gmp` and matching
`.review.json` / `.review-source.enc`. Select **Pixel Image Viewer** on both
sides in Desktop Studio. Both IDs are `com.memomind.example.image-viewer`, with
protocol `gm.pixel-image-viewer` 1.1 (Glass version 2, phone 0.1.3 and later). See the phone README for exact package paths,
run commands, verification evidence and outstanding Studio/device checks.

## Protocol 1.1 (backward compatible with raw 1.0)

Request channel `0x4956` (18774), response channel `0x4957` (18775). All numeric
fields are unsigned **little endian**. Every request starts with 12 bytes:

| Offset | Bytes | Value |
| --- | --- | --- |
| 0 | 1 | Version = 1 |
| 1 | 1 | Operation |
| 2 | 2 | Sequence |
| 4 | 4 | Nonzero random transfer session |
| 8 | 4 | Image byte offset |

| Operation | Sequence / offset | Body | Success |
| --- | --- | --- | --- |
| HELLO = 1 | 0 / 0 | None | Display geometry and max chunk size |
| BEGIN = 2 | 1 / 0 | width:u16 = 600, height:u16 = 350, frame FNV-1a:u32 | Allocate/reuse frame; reset receiver |
| BEGIN_LZ4 = 5 | 1 / 0 | width:u16, height:u16, raw frame FNV-1a:u32, compressed_size:u32, decoded_size:u32 | Validate fixed 600×350, decoded size 105000, compressed size 1..104999; allocate frame and compressed input |
| CHUNK = 3 | 2 onward / next offset | 1–4096 raw GRAY_4 bytes | Copy raw/encoded bytes; advance offset and sequence |
| COMMIT = 4 | next sequence / payload size | None | Decode if LZ4, verify raw length and FNV-1a; submit full frame |

ACK is exactly 20 bytes: version at 0, request operation OR 0x80 at 1, echoed
sequence at 2, echoed session at 4, next receiver offset at 8, status:u8 at 12,
feature flags at 13 (bit 0 = Host LZ4 safe decoder available), width:u16 at 14, height:u16 at 16, max chunk bytes:u16 at 18.

Statuses: 0 = accepted (COMMIT means FrameBuffer submission succeeded),
1 = complete image received but presentation deferred for call UI,
2 = malformed/checksum error, 3 = allocation failed, 4 = FrameBuffer failed,
5 = wrong session/sequence/offset or inactive transfer,
6 = requested LZ4 without a Host decoder. Invalid version, missing
header, zero session and oversized envelopes are dropped without reading fields
outside the received buffer. The caller times out honestly.

HELLO and the 12-byte envelope retain wire version 1. New senders inspect ACK
feature bit 0 and use BEGIN_LZ4 only when supported and smaller than raw. Raw
BEGIN retains its original 8-byte body. CHUNK offsets and COMMIT length count
**encoded** bytes for LZ4, raw bytes otherwise. The LZ4 format is one independent
raw block, with no frame wrapper or embedded size prefix. The protocol supplies
both sizes; decoded size must always be 105000. This is lossless with respect to
the phone's packed GRAY_4 frame. Incompressible input or older Hosts use raw.

FNV-1a uses initial 2166136261, XOR each byte then multiply by 16777619 modulo
2^32. It detects accidental corruption; it is not authentication.

The phone sends one request at a time, registers its waiter before sending,
waits up to 5 seconds for a matching business ACK, and retries the same packet
at most twice on ACK timeout. Permission/transport errors are surfaced directly.
The glasses cache the most recent successful reply, keyed by session, operation,
sequence, request length and request hash. A lost BEGIN/CHUNK/COMMIT ACK does not
append twice or present twice. Unexpected/out-of-order data is rejected. A user
retry begins a new random session and sends the entire frame.

## Rendering, memory and lifecycle

- Start requires 600×350 GRAY_4 and validates the public Host ABI/function pointers.
- One retained Host frame allocation is exactly 105,000 bytes. LZ4 also needs a
  temporary compressed-input allocation of 1..104999 bytes. Peak requested heap
  is less than 210000 bytes, excluding allocator overhead. Allocation failure
  returns ACK 3 and releases the partial transfer. Raw transfers need one buffer.
- COMMIT invokes Host `decompress_safe` with exactly 105000 output capacity,
  releases encoded input, and rejects errors, unexpected decoded sizes or hash
  mismatches before locking FrameBuffer. No JPEG/PNG decoder is embedded.
- Static state is small. The per-function stack check runs as part of GMP build.
- No framebuffer writes occur until COMMIT verifies all 105,000 decoded bytes.
- Lock each Host-provided slice, respect its y/height/stride, copy raw packed
  bytes, unlock earlier slices with `present=false`, final slice with `true`.
  Never call LVGL while locked. A submission error returns ACK 4; physical
  panel/transport failures still need device testing.
- A valid BEGIN replaces the retained previous image. Incomplete replacements
  are not drawn; the physical display remains unchanged. Once BEGIN overwrites
  the single frame, the previous image cannot be restored after a call/suspend
  unless the phone resends it.
- While call UI is active, receive/ACK without touching pixels. Keep a complete
  image and restore it on the next loop after the overlay ends. Subscribe to
  the optional system-event extension; older firmware needs manual resend after
  an overlay because it cannot notify the plugin.
- Disconnect, suspend and 30 seconds of receive inactivity discard incomplete
  images. Completed images survive suspend/resume within one visible lifecycle.
  Stop/unload release all frame and compressed memory and unsubscribe; restart requires resend.
- Original files are never stored on glasses. Bluetooth messages go through the
  App Bridge; this plugin does not implement raw GATT or installation traffic.

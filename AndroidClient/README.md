# MemoMind BLE Client for Android

A minimal native Android app that talks to MemoMind glasses over the
**public GM business protocol on BLE GATT**, without the official App or the
H5 Bridge. It implements the contracts in
[BLUETOOTH_DEVELOPER_GUIDE.md](../GlassSDK/docs/BLUETOOTH_DEVELOPER_GUIDE.md),
[PROTOCOL.md](../GlassSDK/docs/PROTOCOL.md),
[DEVICE_BUSINESS_PROTOCOL.md](../GlassSDK/docs/DEVICE_BUSINESS_PROTOCOL.md), and
[HUD_PROTOCOL.md](../GlassSDK/docs/HUD_PROTOCOL.md).

It does **not** install or launch GMP plugins (that transport is withheld and
owned by the official App), stream microphone audio (BLE recording is not
enabled in current firmware), or drive the HOGP gateway.

## Features

| Area | GM service / command | Response shown |
| --- | --- | --- |
| Heartbeat | `01/01` | STATUS |
| Device information / status | `01/02`, `01/06` | STRING JSON |
| Brightness 0–10, auto brightness | `01/0E`, `01/0F` | STATUS |
| Vertical position 0–8, optical distance 0–8 | `01/0A`, `01/0B` | STATUS |
| Detailed notification | `05/01` (INT8 style 1 + JSON) | STATUS |
| HUD text / clear | `0F/28`, Web Bridge channels 2 and 1 | INT8 plugin status + INT32 |

HUD text requires a compatible **Web Bridge glasses plugin already running**
(started through the official App). Text is drawn as object 1 in a 300×120 box
at (10, 10). Without that plugin the glasses return a nonzero plugin status.

## Install

Prebuilt APK: [`dist/memomind-ble-client-0.1.0.apk`](dist/memomind-ble-client-0.1.0.apk)
(minSdk 23 / Android 6.0, targetSdk 34, debug-signed).

```sh
adb install -r AndroidClient/dist/memomind-ble-client-0.1.0.apk
```

Or copy the file to the phone and allow installation from unknown sources.

The [Android client workflow](../.github/workflows/android-client.yml) runs the
codec tests and builds the APK on GitHub Actions for every pull request and
`main` push that touches `AndroidClient/`; download it from the run's
**memomind-ble-client-apk** artifact. Each run signs with a fresh debug key,
so uninstall a previously installed build before installing a newer one.

## Use

1. Tap **Scan**, grant Bluetooth and location permissions. On Android 11 and
   earlier, system Location must be on for BLE scan results.
2. Select the glasses. The app connects, requests MTU 517, discovers the
   service containing `0x2021`/`0x2022`, and subscribes to `0x2022` before any
   write. Controls enable when the state reads **Ready**.
3. Send commands. The latest response appears under **Device**; every TX/RX
   frame is in the hex **Log** (newest first).

## Protocol behavior

- The control service is found by its characteristics, not a hardcoded service
  UUID or handle; the discovered service UUID is logged.
- Each write is one complete GM physical frame within `min(512, MTU − 3)`
  bytes. Larger messages are fragmented; the app waits for the write callback
  and the firmware's empty flow-control response before the next fragment.
- Requests are serialized (no interleaving) and correlated by event ID,
  service, and command. Unmatched uplink messages, such as `0F/29` plugin
  messages, are logged as unsolicited.
- Each request times out after 5 s and is not retried; a lost reply does not
  prove the operation did not execute.
- On disconnect the app discards partial frames, pending requests, and the
  write limit, and rediscovers and resubscribes on the next connection.

## Build

The build uses Ubuntu/Debian packages instead of Gradle and the Google SDK
manager:

```sh
sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 openjdk-21-jdk-headless python3
AndroidClient/build.sh          # codec tests + APK
AndroidClient/build.sh test     # codec tests only (desktop JVM)
AndroidClient/build.sh apk      # APK only
```

The APK is written to `AndroidClient/dist/`. Signing uses
`~/.android/debug.keystore` (created if missing). Set `KEYSTORE` or
`ANDROID_JAR` to override. The sources follow the standard
`src/main/{AndroidManifest.xml,java,res}` layout, so they can be imported into
an Android Studio/Gradle project.

`build.sh test` checks `GmCodec` against the reference codec: every example in
[`bluetooth_wire.py`](../GlassSDK/docs/examples/bluetooth_wire.py) at 20, 64, 244, and
512-byte frame limits, the fragmented 600-byte vector, and malformed-frame,
TLV, and reassembly-recovery cases.

## Code map

| File | Responsibility |
| --- | --- |
| `GmCodec.java` | Framing, fragmentation, checksums, reassembly, TLVs, status namespaces |
| `GlassesConnection.java` | GATT discovery, subscription, MTU, write pacing, request correlation, disconnect cleanup |
| `MainActivity.java` | Permissions, scanning, controls, log |

## Verification status

Verified: codec vectors on a desktop JVM; APK packaging, v1/v2/v3 signature
verification, and manifest contents. **Not verified:** running on a phone or
against real glasses. The firmware-side gaps listed in the developer guide still
apply: MFi-enabled builds may not expose this GATT service, and long uplink
notifications (device information, status) can exceed small MTUs. Record the
firmware version, phone OS, and negotiated MTU when testing on hardware.

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

## Install

Download `memomind-ble-client-<version>.apk` from the repository's
[Releases](../../../releases) page (tags `android-client-v*`) and open it on the
phone, allowing installation from unknown sources. Or, with USB debugging:

```sh
adb install -r memomind-ble-client-0.2.1.apk
```

Requires Android 6.0 (API 23) or later with Bluetooth LE. Releases signed with
a CI debug key cannot be installed over a differently signed build; uninstall
the old one first. See [CHANGELOG.md](CHANGELOG.md) for changes.

## Use

1. Tap **Find glasses** and allow **Nearby devices** (Android 12+) or
   **Location** (Android 6-11).
2. Pick the glasses. Each row shows the name, where it was found, the
   Bluetooth type, the address, and signal strength:

   | Tag | Meaning |
   | --- | --- |
   | `[connected]` | The phone already has a link, usually the MemoMind App's. Select it; Android shares the link. |
   | `[paired]` | Bonded in system Bluetooth settings. Must be powered on and in range. |
   | `[nearby]` | Advertising now (BLE scan result). |

3. The app connects, requests MTU 517, finds the service containing
   `0x2021`/`0x2022`, and subscribes to `0x2022` before any write. Controls
   enable when the state reads **Ready**.
4. Send commands. The latest response appears under **Device**; every TX/RX
   frame is in the hex **Log** (newest first).

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

## Troubleshooting: glasses not in the list

Check the **Log** after a scan: `SCAN known devices` and `SCAN finished` lines
give the counts of connected, paired, and advertising devices.

| Symptom | Cause | Fix |
| --- | --- | --- |
| Glasses work in the MemoMind App but are not listed as nearby | Connected glasses stop advertising | Use the `[connected]` or `[paired]` row; or close the App / turn Bluetooth off and on so the glasses advertise again |
| No devices at all on Android 6–11 | Location is off; Android drops all BLE scan results | Turn on Location (the app opens the setting) |
| "Permission denied" | Nearby devices / Location denied | Settings > Apps > MemoMind BLE Client > Permissions |
| "Android allows 5 scans per 30 s" | Android throttles repeated scans and returns nothing | Wait for the countdown |
| Glasses listed as `Classic only` | No BLE on this link | This client needs BLE GATT; the official App may use Classic SPP |
| Connected, then "Unsupported: no service with 0x2021/0x2022" | Firmware does not expose the GM GATT service (for example MFi-enabled builds) | Not fixable in the app; record the firmware version |
| "status 133" on connect | Glasses asleep or out of range | Wake the glasses, keep them close, retry |

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
sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 openjdk-21-jdk-headless python3 curl
AndroidClient/build.sh          # codec tests + APK
AndroidClient/build.sh test     # codec tests only (desktop JVM)
AndroidClient/build.sh apk      # APK only
```

The APK is written to `AndroidClient/dist/` (not committed). Java compiles
against the API 23 stubs; the manifest links against API 34 framework
resources, which `build.sh` downloads once (137 MB, Robolectric `android-all`
from Maven Central, SHA-256 pinned) into `~/.cache/memomind-ble-client/`. Set
`FRAMEWORK_JAR` to use an existing copy, such as an SDK `android-34/android.jar`.

Signing uses `~/.android/debug.keystore` (created if missing) unless `KEYSTORE`,
`KEYSTORE_PASS`, `KEY_ALIAS`, and `KEY_PASS` are set.

`build.sh test` checks `GmCodec` against the reference codec: every example in
[`bluetooth_wire.py`](../GlassSDK/docs/examples/bluetooth_wire.py) at 20, 64, 244, and
512-byte frame limits, the fragmented 600-byte vector, and malformed-frame,
TLV, and reassembly-recovery cases.

## CI and releases

- [android-client.yml](../.github/workflows/android-client.yml) runs the tests and
  builds the APK for pull requests and `main` pushes touching `AndroidClient/`,
  uploading it as the **memomind-ble-client-apk** artifact.
- [android-release.yml](../.github/workflows/android-release.yml) builds the APK
  and publishes a GitHub release `android-client-v<version>` (from
  `VERSION_NAME` in `build.sh`) with the matching [CHANGELOG](CHANGELOG.md)
  section. Running it again for the same version replaces the APK.

To release: bump `VERSION_NAME`/`VERSION_CODE` in `build.sh`, add a CHANGELOG
section, and merge. Then either open **Actions > Android client release > Run
workflow** on `main` (the workflow creates the tag), or push the tag yourself:

```sh
git tag android-client-v0.2.1 origin/main && git push origin android-client-v0.2.1
```

For installable upgrades across releases, add a stable signing key as
repository secrets: `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.keystore`),
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
Without them, each build is signed with a fresh debug key.

## Code map

| File | Responsibility |
| --- | --- |
| `GmCodec.java` | Framing, fragmentation, checksums, reassembly, TLVs, status namespaces |
| `GlassesConnection.java` | GATT discovery, subscription, MTU, write pacing, request correlation, disconnect cleanup |
| `MainActivity.java` | Permissions, device discovery, controls, log |
| `res/mipmap*/ic_launcher.xml`, `res/drawable/ic_launcher_*.xml` | Vector launcher icon: adaptive (Android 8+, with themed-icon layer) and round fallback (Android 6-7) |
| `GmCodecTest.java` | Desktop JVM codec tests against `bluetooth_wire.py` vectors |

## Verification status

Verified: codec vectors on a desktop JVM; APK packaging, v1/v2/v3 signature
verification, and manifest contents (including `neverForLocation`). **Not
verified:** running on a phone or against real glasses. The firmware-side gaps
listed in the developer guide still apply: MFi-enabled builds may not expose
this GATT service, and long uplink notifications (device information, status)
can exceed small MTUs. Record the firmware version, phone OS, and negotiated
MTU when testing on hardware.

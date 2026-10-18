# Changelog

## 0.2.1 - 2026-10-18

- App icon: smart glasses with HUD lines on a deep-blue background. Adaptive
  icon on Android 8+ (fits every launcher mask, with a monochrome layer for
  Android 13 themed icons) and a round vector icon on Android 6-7.

## 0.2.0 - 2026-10-18

### Fixed: glasses missing from the device list
- Lists glasses that are already connected to the phone (for example by the
  MemoMind App) or paired in Bluetooth settings. Connected glasses usually stop
  advertising, so a BLE scan alone never showed them.
- Android 12+: scanning no longer depends on the Location toggle
  (`BLUETOOTH_SCAN` is declared `neverForLocation`; location permission is
  only requested on Android 11 and earlier).
- Android 6-11: detects Location turned off, which silently empties BLE scans,
  and opens Location settings.
- Scans for 15 s in low-latency mode instead of 10 s in the default low-power
  mode, and blocks the sixth scan start within 30 s, which Android would
  silently return empty.
- Uses the phone's cached device name when the advertisement has none, and
  shows each device's source (connected, paired, nearby), type (BLE, BLE+Classic,
  Classic only), and signal strength.
- Logs scan diagnostics and explains scan and connection error codes.

### Other
- Source code comments throughout the app.
- Releases are published from `android-client-v*` tags by GitHub Actions; the
  APK is no longer committed to the repository.

## 0.1.0 - 2026-10-17

- First version: BLE GM command link, heartbeat, device info/status, display
  settings, notifications, and Web Bridge HUD text/clear.

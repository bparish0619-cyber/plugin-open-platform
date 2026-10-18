package com.memomind.bleclient;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Single-screen UI: find the glasses, connect, and send public GM business
 * commands. The layout is built in code so the app needs no layout resources.
 *
 * Device discovery combines three sources, because glasses that are already
 * connected to the official MemoMind App usually stop advertising and never
 * appear in a BLE scan:
 * <ol>
 *   <li>devices the phone already has a GATT connection to ("connected"),</li>
 *   <li>devices bonded in system Bluetooth settings ("paired"),</li>
 *   <li>devices advertising nearby (BLE scan results).</li>
 * </ol>
 *
 * Threading: UI work runs on the main thread ({@link #ui}). GlassesConnection
 * callbacks arrive on its own thread and are posted back here.
 */
public final class MainActivity extends Activity implements GlassesConnection.Listener {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    /** Scan length; long enough for slow advertising intervals, short enough to save battery. */
    private static final long SCAN_MS = 15000;
    /** Android (7+) silently returns no results after 5 scan starts within 30 s. */
    private static final int MAX_SCAN_STARTS = 5;
    private static final long SCAN_WINDOW_MS = 30000;
    private static final int MAX_LOG_LINES = 300;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** One list button per device address, so later sightings update rather than duplicate. */
    private final Map<String, Button> deviceButtons = new HashMap<String, Button>();
    private final Map<String, String> deviceSources = new HashMap<String, String>();
    private final ArrayDeque<Long> scanStarts = new ArrayDeque<Long>();
    private final ArrayDeque<String> logLines = new ArrayDeque<String>();
    /** Controls that only make sense while the GM link is ready. */
    private final List<View> commandViews = new ArrayList<View>();

    private GlassesConnection connection;
    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private int scanResults;
    private int notificationId = 1;

    private TextView stateView;
    private TextView responseView;
    private TextView logView;
    private LinearLayout deviceList;
    private Button scanButton;
    private EditText notifyTitle;
    private EditText notifyContent;
    private EditText hudText;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        connection = new GlassesConnection(this, this);
        setContentView(buildUi());
        setCommandsEnabled(false);
        if (adapter == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            stateView.setText("This device does not support Bluetooth LE");
            scanButton.setEnabled(false);
        }
    }

    @Override
    protected void onDestroy() {
        stopScan();
        connection.shutdown();
        super.onDestroy();
    }

    // ---- Layout ----

    /** Builds the whole screen: link controls, device list, command sections, and log. */
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        stateView = text("Not connected", 18);
        root.addView(stateView);

        LinearLayout linkRow = row();
        scanButton = button("Find glasses", new View.OnClickListener() {
            public void onClick(View v) {
                if (scanning) stopScan(); else startScanWithPermissions();
            }
        });
        linkRow.addView(scanButton, weight());
        linkRow.addView(button("Disconnect", new View.OnClickListener() {
            public void onClick(View v) { connection.disconnect(); }
        }), weight());
        root.addView(linkRow);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList);

        // Read-only queries: safe to send at any time.
        root.addView(header("Device"));
        LinearLayout queries = row();
        queries.addView(command(button("Heartbeat", sender("Heartbeat",
                GmCodec.SERVICE_SYSTEM, GmCodec.CMD_HEARTBEAT, null))), weight());
        queries.addView(command(button("Info", sender("Device info",
                GmCodec.SERVICE_SYSTEM, GmCodec.CMD_DEVICE_INFO, null))), weight());
        queries.addView(command(button("Status", sender("Device status",
                GmCodec.SERVICE_SYSTEM, GmCodec.CMD_DEVICE_STATUS, null))), weight());
        root.addView(queries);
        responseView = text("", 14);
        responseView.setTextIsSelectable(true);
        root.addView(responseView);

        // Native display settings: one INT8 TLV each, ranges from DEVICE_BUSINESS_PROTOCOL.md.
        root.addView(header("Display settings"));
        root.addView(setting("Brightness", 10, 5, GmCodec.CMD_BRIGHTNESS));
        root.addView(setting("Vertical position", 8, 4, GmCodec.CMD_SCREEN_HEIGHT));
        root.addView(setting("Optical distance", 8, 4, GmCodec.CMD_OPTICAL_DISTANCE));
        LinearLayout auto = row();
        auto.addView(command(button("Auto brightness on", sender("Auto brightness on",
                GmCodec.SERVICE_SYSTEM, GmCodec.CMD_AUTO_BRIGHTNESS, GmCodec.int8(1)))), weight());
        auto.addView(command(button("Auto brightness off", sender("Auto brightness off",
                GmCodec.SERVICE_SYSTEM, GmCodec.CMD_AUTO_BRIGHTNESS, GmCodec.int8(0)))), weight());
        root.addView(auto);

        root.addView(header("Notification"));
        notifyTitle = input("Title", "MemoMind BLE Client");
        notifyContent = input("Content", "Hello from Android");
        root.addView(command(notifyTitle));
        root.addView(command(notifyContent));
        root.addView(command(button("Send notification", new View.OnClickListener() {
            public void onClick(View v) { sendNotification(); }
        })));

        // Web Bridge drawing goes to a running glasses plugin, not to firmware services.
        root.addView(header("HUD text (requires running Web Bridge plugin)"));
        hudText = input("Text", "Hello glasses");
        root.addView(command(hudText));
        LinearLayout hud = row();
        hud.addView(command(button("Show text", new View.OnClickListener() {
            public void onClick(View v) {
                // Object 1 in a 300x120 box at (10,10); reusing ID 1 replaces the previous text.
                byte[] payload = GmCodec.webBridgeText(1, 10, 10, 300, 120, 0, 0, hudText.getText().toString());
                send("Web Bridge text", GmCodec.SERVICE_PLUGIN, GmCodec.CMD_APP_MESSAGE,
                        GmCodec.applicationBody(2, payload));
            }
        })), weight());
        // Channel 1 clear carries one compatibility byte; empty payloads are rejected.
        hud.addView(command(button("Clear", sender("Web Bridge clear", GmCodec.SERVICE_PLUGIN,
                GmCodec.CMD_APP_MESSAGE, GmCodec.applicationBody(1, new byte[] {0})))), weight());
        root.addView(hud);

        root.addView(header("Log"));
        logView = text("", 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    /** A labelled slider plus "Set" button that sends one INT8 system-service setting. */
    private LinearLayout setting(final String label, int max, int initial, final int command) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView title = text(label + ": " + initial, 14);
        final SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(initial);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                title.setText(label + ": " + value);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout line = row();
        line.addView(command(bar), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
        line.addView(command(button("Set", new View.OnClickListener() {
            public void onClick(View v) {
                int value = bar.getProgress();
                send(label + " " + value, GmCodec.SERVICE_SYSTEM, command, GmCodec.int8(value));
            }
        })), weight());
        box.addView(title);
        box.addView(line);
        return box;
    }

    // ---- Commands ----

    /** Service 0x05 command 0x01: INT8 style 1 (detailed) followed by the notification JSON. */
    private void sendNotification() {
        long now = System.currentTimeMillis();
        // App-local seconds: wall clock adjusted by the zone offset (DEVICE_BUSINESS_PROTOCOL.md).
        long ts = (now + TimeZone.getDefault().getOffset(now)) / 1000;
        JSONObject json = new JSONObject();
        try {
            json.put("id", notificationId++);
            json.put("a", "MemoMind BLE Client");
            json.put("type", 0);
            json.put("ts", ts);
            json.put("c", notifyContent.getText().toString());
            json.put("ti", notifyTitle.getText().toString());
            json.put("pkg_name", getPackageName());
        } catch (JSONException error) {
            onLog("ERR notification JSON: " + error.getMessage());
            return;
        }
        send("Notification", GmCodec.SERVICE_NOTIFICATION, GmCodec.CMD_NOTIFICATION_PUSH,
                GmCodec.concat(GmCodec.int8(1), GmCodec.json(json.toString())));
    }

    /** Click listener that sends a fixed request; a null body means no TLVs. */
    private View.OnClickListener sender(final String label, final int service, final int command, final byte[] body) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                send(label, service, command, body == null ? new byte[0] : body);
            }
        };
    }

    /** Queues a request and shows its decoded response (or error) under "Device". */
    private void send(final String label, int service, int command, byte[] body) {
        connection.send(label, service, command, body, new GlassesConnection.ResponseCallback() {
            public void onResponse(final GmCodec.Message response) {
                final String summary = GmCodec.describe(response);
                ui.post(new Runnable() {
                    public void run() { responseView.setText(label + ": " + summary); }
                });
            }

            public void onError(final String error) {
                ui.post(new Runnable() {
                    public void run() { responseView.setText(label + " failed: " + error); }
                });
            }
        });
    }

    // ---- Permissions and preconditions ----

    /**
     * Android 12+ uses the "Nearby devices" permissions. BLUETOOTH_SCAN is
     * declared neverForLocation in the manifest, so no location permission or
     * Location toggle is needed there. Android 6-11 require fine location for
     * any BLE scan result.
     */
    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"};
        }
        return new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }

    /** Checks every precondition in order, prompting for the first one missing, then scans. */
    private void startScanWithPermissions() {
        List<String> missing = new ArrayList<String>();
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        if (!adapter.isEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
            } catch (SecurityException error) {
                stateView.setText("Bluetooth permission denied");
            }
            return;
        }
        // On Android 6-11 the system drops every BLE scan result while Location is off.
        if (Build.VERSION.SDK_INT < 31 && !locationEnabled()) {
            stateView.setText("Turn on Location, then tap Find glasses again (Android 11 and earlier need it for BLE scans)");
            onLog("SCAN blocked: Location services off");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        startScan();
    }

    private boolean locationEnabled() {
        LocationManager location = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return location != null && (location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_PERMISSIONS) return;
        for (int i = 0; i < results.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) {
                stateView.setText("Permission denied: " + permissions[i]
                        + ". Grant it in Settings > Apps > MemoMind BLE Client > Permissions.");
                onLog("SCAN blocked: denied " + permissions[i]);
                return;
            }
        }
        startScanWithPermissions();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQUEST_ENABLE_BT) return;
        if (result == RESULT_OK) startScanWithPermissions(); else stateView.setText("Bluetooth is off");
    }

    // ---- Discovery ----

    /** Lists connected and paired devices immediately, then runs a low-latency BLE scan. */
    private void startScan() {
        // Respect Android's scan-start limit; exceeding it yields a silent, empty scan.
        long now = SystemClock.elapsedRealtime();
        while (!scanStarts.isEmpty() && now - scanStarts.peekFirst() > SCAN_WINDOW_MS) scanStarts.pollFirst();
        if (scanStarts.size() >= MAX_SCAN_STARTS) {
            long wait = (SCAN_WINDOW_MS - (now - scanStarts.peekFirst())) / 1000 + 1;
            stateView.setText("Android allows 5 scans per 30 s; try again in " + wait + " s");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            stateView.setText("Bluetooth LE scanner unavailable");
            return;
        }
        deviceButtons.clear();
        deviceSources.clear();
        deviceList.removeAllViews();
        scanResults = 0;
        listKnownDevices();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        try {
            // No service filter: the glasses' advertised services are not documented.
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException error) {
            stateView.setText("Bluetooth scan permission denied");
            return;
        }
        scanStarts.addLast(now);
        scanning = true;
        scanButton.setText("Stop scan");
        stateView.setText("Scanning... Glasses already connected to the MemoMind App appear as [connected] or [paired].");
        onLog("SCAN started: SDK " + Build.VERSION.SDK_INT + ", low latency, " + (SCAN_MS / 1000) + " s");
        ui.postDelayed(scanTimeout, SCAN_MS);
    }

    /**
     * Adds devices that may not be advertising: an existing GATT link (for
     * example the official App's) or a system bond. Android shares one link
     * between GATT clients, so connecting to these works without advertising.
     */
    private void listKnownDevices() {
        try {
            List<BluetoothDevice> connected = manager.getConnectedDevices(BluetoothProfile.GATT);
            for (BluetoothDevice device : connected) addDevice(device, null, "connected", null);
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) addDevice(device, null, "paired", null);
            }
            onLog("SCAN known devices: " + connected.size() + " connected, "
                    + (bonded == null ? 0 : bonded.size()) + " paired");
        } catch (SecurityException error) {
            onLog("SCAN known devices unavailable: " + error.getMessage());
        }
    }

    private final Runnable scanTimeout = new Runnable() {
        public void run() { stopScan(); }
    };

    private void stopScan() {
        ui.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        scanButton.setText("Find glasses");
        try {
            if (scanner != null && adapter.isEnabled()) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
            // Permission revoked while scanning; the scan ends with the process.
        }
        onLog("SCAN finished: " + scanResults + " advertisements, " + deviceButtons.size() + " devices listed");
        if (stateView.getText().toString().startsWith("Scanning")) {
            stateView.setText(deviceButtons.isEmpty()
                    ? "No devices found. See README troubleshooting."
                    : "Select your glasses");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, final ScanResult result) {
            ui.post(new Runnable() {
                public void run() {
                    // Results queued before stopScan() must not refill a cleared list.
                    if (!scanning) return;
                    scanResults++;
                    String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                    addDevice(result.getDevice(), name, "nearby", result.getRssi() + " dBm");
                }
            });
        }

        @Override
        public void onScanFailed(final int error) {
            ui.post(new Runnable() {
                public void run() {
                    scanning = false;
                    ui.removeCallbacks(scanTimeout);
                    scanButton.setText("Find glasses");
                    stateView.setText("Scan failed: " + scanError(error));
                    onLog("SCAN failed " + error + ": " + scanError(error));
                }
            });
        }
    };

    private static String scanError(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED: return "a scan is already running";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "registration failed; toggle Bluetooth off and on";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR: return "internal Bluetooth error";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED: return "BLE scanning unsupported";
            default: return "error " + code;
        }
    }

    /**
     * Adds or updates one device row. Named devices go first so the glasses
     * are easy to spot among anonymous beacons.
     */
    private void addDevice(final BluetoothDevice device, String advertisedName, String source, String rssi) {
        String address = device.getAddress();
        String name = advertisedName != null ? advertisedName : cachedName(device);
        String sources = deviceSources.get(address);
        if (sources == null) {
            sources = source;
        } else if (!sources.contains(source)) {
            sources = sources + ", " + source;
        }
        deviceSources.put(address, sources);
        String label = (name == null ? "(unnamed)" : name) + "  [" + sources + "]  " + typeName(device)
                + "\n" + address + (rssi == null ? "" : "  " + rssi);

        Button entry = deviceButtons.get(address);
        if (entry != null) {
            entry.setText(label);
            return;
        }
        entry = button(label, new View.OnClickListener() {
            public void onClick(View v) {
                stopScan();
                deviceList.removeAllViews();
                deviceButtons.clear();
                connection.connect(device);
            }
        });
        entry.setAllCaps(false);
        deviceButtons.put(address, entry);
        if (name == null) deviceList.addView(entry); else deviceList.addView(entry, 0);
    }

    /** System-cached name; needs BLUETOOTH_CONNECT on Android 12+. */
    private static String cachedName(BluetoothDevice device) {
        try {
            return device.getName();
        } catch (SecurityException error) {
            return null;
        }
    }

    /** Classic-only devices cannot use this BLE GATT client. */
    private static String typeName(BluetoothDevice device) {
        try {
            switch (device.getType()) {
                case BluetoothDevice.DEVICE_TYPE_LE: return "BLE";
                case BluetoothDevice.DEVICE_TYPE_DUAL: return "BLE+Classic";
                case BluetoothDevice.DEVICE_TYPE_CLASSIC: return "Classic only";
                default: return "type unknown";
            }
        } catch (SecurityException error) {
            return "type unknown";
        }
    }

    // ---- GlassesConnection.Listener (called on the connection thread) ----

    public void onState(final String state, final boolean ready) {
        ui.post(new Runnable() {
            public void run() {
                stateView.setText(state);
                setCommandsEnabled(ready);
            }
        });
        onLog("STATE " + state);
    }

    /** Thread-safe: appends to the newest-first log on the main thread. */
    public void onLog(final String line) {
        ui.post(new Runnable() {
            public void run() {
                logLines.addFirst(line);
                while (logLines.size() > MAX_LOG_LINES) logLines.removeLast();
                StringBuilder out = new StringBuilder();
                for (String entry : logLines) out.append(entry).append('\n');
                logView.setText(out);
            }
        });
    }

    public void onUnsolicited(GmCodec.Message message) {
        onLog(String.format("EVENT %02X/%02X (unsolicited)", message.service, message.command));
    }

    // ---- View helpers ----

    private void setCommandsEnabled(boolean enabled) {
        for (View view : commandViews) view.setEnabled(enabled);
    }

    /** Registers a view as a command control, enabled only while the link is ready. */
    private <T extends View> T command(T view) {
        commandViews.add(view);
        return view;
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        return view;
    }

    private TextView header(String value) {
        TextView view = text(value, 16);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(16), 0, dp(4));
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private EditText input(String hint, String value) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value);
        view.setInputType(InputType.TYPE_CLASS_TEXT);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

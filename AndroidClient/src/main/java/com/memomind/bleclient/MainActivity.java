package com.memomind.bleclient;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Scan, connect, and send public GM business commands to MemoMind glasses. */
public final class MainActivity extends Activity implements GlassesConnection.Listener {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private static final long SCAN_MS = 10000;
    private static final int MAX_LOG_LINES = 300;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, ScanResult> found = new LinkedHashMap<String, ScanResult>();
    private final ArrayDeque<String> logLines = new ArrayDeque<String>();
    private final List<View> commandViews = new ArrayList<View>();

    private GlassesConnection connection;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
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
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
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

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        stateView = text("Not connected", 18);
        root.addView(stateView);

        LinearLayout linkRow = row();
        scanButton = button("Scan", new View.OnClickListener() {
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

        root.addView(header("HUD text (requires running Web Bridge plugin)"));
        hudText = input("Text", "Hello glasses");
        root.addView(command(hudText));
        LinearLayout hud = row();
        hud.addView(command(button("Show text", new View.OnClickListener() {
            public void onClick(View v) {
                byte[] payload = GmCodec.webBridgeText(1, 10, 10, 300, 120, 0, 0, hudText.getText().toString());
                send("Web Bridge text", GmCodec.SERVICE_PLUGIN, GmCodec.CMD_APP_MESSAGE,
                        GmCodec.applicationBody(2, payload));
            }
        })), weight());
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

    private View.OnClickListener sender(final String label, final int service, final int command, final byte[] body) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                send(label, service, command, body == null ? new byte[0] : body);
            }
        };
    }

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

    // ---- Scanning and permissions ----

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }

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
        startScan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_PERMISSIONS) return;
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                stateView.setText("Bluetooth/location permission denied; scanning is unavailable");
                return;
            }
        }
        startScanWithPermissions();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQUEST_ENABLE_BT) return;
        if (result == RESULT_OK) startScan(); else stateView.setText("Bluetooth is off");
    }

    private void startScan() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            stateView.setText("Bluetooth LE scanner unavailable");
            return;
        }
        found.clear();
        deviceList.removeAllViews();
        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException error) {
            stateView.setText("Bluetooth scan permission denied");
            return;
        }
        scanning = true;
        scanButton.setText("Stop scan");
        stateView.setText("Scanning... (enable Location if no devices appear)");
        ui.postDelayed(scanTimeout, SCAN_MS);
    }

    private final Runnable scanTimeout = new Runnable() {
        public void run() { stopScan(); }
    };

    private void stopScan() {
        ui.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        scanButton.setText("Scan");
        try {
            if (scanner != null && adapter.isEnabled()) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
            // Permission revoked while scanning; the scan ends with the process.
        }
        if (stateView.getText().toString().startsWith("Scanning")) {
            stateView.setText(found.isEmpty() ? "No devices found" : "Select your glasses");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, final ScanResult result) {
            ui.post(new Runnable() {
                public void run() { addResult(result); }
            });
        }

        @Override
        public void onScanFailed(final int error) {
            ui.post(new Runnable() {
                public void run() {
                    scanning = false;
                    scanButton.setText("Scan");
                    stateView.setText("Scan failed (" + error + ")");
                }
            });
        }
    };

    private void addResult(ScanResult result) {
        final BluetoothDevice device = result.getDevice();
        String address = device.getAddress();
        boolean isNew = !found.containsKey(address);
        found.put(address, result);
        if (!isNew) return;
        String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
        Button entry = button((name == null ? "(unnamed)" : name) + "  " + address + "  " + result.getRssi() + " dBm",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        stopScan();
                        deviceList.removeAllViews();
                        connection.connect(device);
                    }
                });
        entry.setAllCaps(false);
        // Named devices first; the glasses advertise a name.
        if (name == null) deviceList.addView(entry); else deviceList.addView(entry, 0);
    }

    // ---- GlassesConnection.Listener (connection thread) ----

    public void onState(final String state, final boolean ready) {
        ui.post(new Runnable() {
            public void run() {
                stateView.setText(state);
                setCommandsEnabled(ready);
            }
        });
        onLog("STATE " + state);
    }

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

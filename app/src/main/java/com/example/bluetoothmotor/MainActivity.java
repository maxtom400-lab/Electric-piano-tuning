package com.example.bluetoothmotor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long SCAN_MS = 10_000L;
    private static final long HEARTBEAT_MS = 10 * 60 * 1000L;
    private static final int PIANO_SCAN_SAMPLES_REQUIRED = 3;
    private static final int SAMPLE_RATE = 44100;
    private static final int PITCH_BUFFER_SIZE = 4096;
    private static final double MIN_PIANO_FREQ = 27.5;
    private static final double MAX_PIANO_FREQ = 4186.0;
    private static final double TUNER_DIAL_CENTS_RANGE = 200.0;
    private static final float TUNER_DIAL_DEGREES_RANGE = 270f;
    private static final float TUNER_DIAL_DEGREES_PER_CENT =
            (float) (TUNER_DIAL_DEGREES_RANGE / TUNER_DIAL_CENTS_RANGE);
    private static final double AUTO_TOLERANCE_CENTS = 3.0;
    private static final int AUTO_MAX_STEPS = 40;
    private static final int AUTO_NOTE_WINDOW_SEMITONES = 2;

    private enum Direction {
        NONE,
        LEFT,
        RIGHT
    }

    private static final String TARGET_NAME = "JUXUN-88888888";
    private static final String TARGET_ADDRESS = "DE:AB:BD:EA:2F:DE";

    private static final UUID WRITE_UUID =
            UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY_UUID =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String CMD_INIT = "AF010203040506FF";
    private static final String CMD_LEFT = "A1010100000003321F";
    private static final String CMD_STOP = "A10102000000031F";
    private static final String CMD_RIGHT = "A1020100000003321F";
    private static final String CMD_SPEED_50 = "A1080132";
    private static final String CMD_SPEED_30 = "A108011E";
    private static final String CMD_MODE_JOG = "A10302011F";
    private static final String CMD_MODE_LOCK = "A10303011F";
    private static final String CMD_SPEED_STEP = CMD_SPEED_50;
    private static final String PREFS_NAME = "max_piano_tuner";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (gatt != null && writeCharacteristic != null) {
                sendCommand("HEARTBEAT", CMD_INIT);
                handler.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothDevice targetDevice;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private boolean scanning;
    private boolean connectAfterScan;
    private AlertDialog motorDialog;

    private TextView statusText;
    private TextView notifyText;
    private TextView pitchText;
    private TextView targetText;
    private TextView autoText;
    private SteampunkTunerView tunerView;
    private PianoKeyPlayer pianoKeyPlayer;
    private TextView logText;
    private EditText rawInput;
    private EditText repeatInput;
    private Runnable activeMotionRunnable;
    private int commandGeneration;
    private String pendingFreshLabel;
    private String pendingFreshHex;
    private long pendingFreshMotionDelayMs;
    private int pendingFreshRepeatCount;
    private long pendingFreshRepeatIntervalMs;
    private int pendingFreshSpeedCount;
    private long pendingFreshSpeedStartMs;
    private long pendingFreshSpeedIntervalMs;
    private long pendingFreshStopExtraMs;
    private boolean reconnectAfterDisconnect;
    private boolean pendingReplayVendorLr;
    private boolean replayVendorLrAfterInitAck;
    private Direction currentDirection = Direction.NONE;
    private Direction blockedDirection = Direction.NONE;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean listening;
    private volatile double latestFrequency;
    private volatile int latestMidi;
    private volatile double latestCents;
    private volatile long latestPitchMs;
    private volatile double latestStableFrequency;
    private volatile int latestStableMidi;
    private volatile double latestStableCents;
    private volatile long latestStablePitchMs;
    private int selectedTargetMidi = 60;
    private long suppressPitchUpdateUntilMs;
    private long suppressRangeAlarmUntilMs;
    private double pitchCandidateCents;
    private int pitchCandidateFrames;
    private long pitchCandidateStartedMs;
    private boolean autoTuning;
    private int autoGeneration;
    private int autoTargetMidi;
    private double autoTargetFrequency;
    private double autoProbeFrequency;
    private Direction autoRaiseDirection = Direction.NONE;
    private Direction autoLowerDirection = Direction.NONE;
    private int autoStepCount;
    private int autoSpeedPercent = 50;
    private int autoProbeMs = 350;
    private int autoPulseLongMs = 450;
    private int autoPulseMediumMs = 250;
    private int autoPulseShortMs = 120;
    private int autoSettleMs = 1400;
    private int autoMaxStepsSetting = AUTO_MAX_STEPS;
    private double autoToleranceCents = AUTO_TOLERANCE_CENTS;
    private boolean keyPreviewEnabled;
    private long lastRangeAlarmVibrateMs;
    private AlertDialog pianoScanDialog;
    private TextView pianoScanStatusText;
    private TextView pianoScanProgressText;
    private boolean pianoScanActive;
    private int pianoScanMidi = 21;
    private int pianoScanCount;
    private long pianoScanLastRecordMs;
    private int pianoScanSampleCount;
    private String pianoScanQuality = "Waiting";
    private final boolean[] pianoScanRecorded = new boolean[109];
    private final double[] pianoScanFrequency = new double[109];
    private final double[] pianoScanCents = new double[109];
    private final double[] pianoScanSampleFrequency = new double[PIANO_SCAN_SAMPLES_REQUIRED];
    private final double[] pianoScanSampleCents = new double[PIANO_SCAN_SAMPLES_REQUIRED];
    private final double[] pianoTuningOffsetCents = new double[109];
    private boolean pianoTuningComputed;

    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null || device.getAddress() == null) {
                return;
            }

            String name = device.getName();
            boolean addressMatch = TARGET_ADDRESS.equalsIgnoreCase(device.getAddress());
            boolean nameMatch = name != null && TARGET_NAME.equalsIgnoreCase(name);
            if (!addressMatch && !nameMatch) {
                return;
            }

            targetDevice = device;
            runOnUiThread(() -> {
                setStatus("Found motor");
                appendLog("Found target " + deviceLabel(device));
                if (connectAfterScan) {
                    connectAfterScan = false;
                    stopScan();
                    showMotorDialog("Connecting to motor...");
                    connectTarget();
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                scanning = false;
                connectAfterScan = false;
                dismissMotorDialog();
                setStatus("Scan failed: " + errorCode);
            });
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLogSafe("GATT connected, discovering services");
                runOnUiThread(() -> showMotorDialog("Discovering motor service..."));
                runOnUiThread(() -> setStatus("Connected, discovering services"));
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (MainActivity.this.gatt == gatt) {
                    MainActivity.this.gatt = null;
                    writeCharacteristic = null;
                }
                gatt.close();
                appendLogSafe("GATT disconnected, status=" + status);
                runOnUiThread(() -> stopHeartbeat());
                runOnUiThread(() -> dismissMotorDialog());
                runOnUiThread(() -> setStatus("Disconnected"));
                if (reconnectAfterDisconnect) {
                    reconnectAfterDisconnect = false;
                    handler.postDelayed(() -> openGattConnection("Reconnecting "), 650L);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattCharacteristic write = null;
            BluetoothGattCharacteristic notify = null;
            for (BluetoothGattService service : gatt.getServices()) {
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    if (WRITE_UUID.equals(characteristic.getUuid())) {
                        write = characteristic;
                    }
                    if (NOTIFY_UUID.equals(characteristic.getUuid())) {
                        notify = characteristic;
                    }
                }
            }

            writeCharacteristic = write;
            BluetoothGattCharacteristic notifyCharacteristic = notify;
            runOnUiThread(() -> {
                if (writeCharacteristic == null) {
                    dismissMotorDialog();
                    setStatus("Connected, but FFE2 write characteristic not found");
                } else {
                    setStatus("Ready: FFE2 write selected");
                }
            });

            if (notifyCharacteristic != null) {
                enableNotifications(gatt, notifyCharacteristic);
            } else {
                appendLogSafe("FFE1 notify characteristic not found");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            appendLogSafe("CCCD write status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS && CCCD_UUID.equals(descriptor.getUuid())) {
                sendCommandFromGattCallback("INIT", CMD_INIT);
                scheduleCommand("AUTO JOG", CMD_MODE_JOG, 180L);
                scheduleCommand("AUTO SPEED" + autoSpeedPercent, speedCommandForPercent(autoSpeedPercent), 360L);
                runOnUiThread(() -> startHeartbeat());
                runOnUiThread(() -> dismissMotorDialog());
                runOnUiThread(() -> setStatus("Connected: auto ready sent"));
                if (pendingFreshHex != null) {
                    String label = pendingFreshLabel;
                    String hex = pendingFreshHex;
                    long motionDelayMs = pendingFreshMotionDelayMs;
                    int repeatCount = pendingFreshRepeatCount;
                    long repeatIntervalMs = pendingFreshRepeatIntervalMs;
                    int speedCount = pendingFreshSpeedCount;
                    long speedStartMs = pendingFreshSpeedStartMs;
                    long speedIntervalMs = pendingFreshSpeedIntervalMs;
                    long stopExtraMs = pendingFreshStopExtraMs;
                    pendingFreshLabel = null;
                    pendingFreshHex = null;
                    pendingFreshMotionDelayMs = 0L;
                    pendingFreshRepeatCount = 1;
                    pendingFreshRepeatIntervalMs = 0L;
                    pendingFreshSpeedCount = 0;
                    pendingFreshSpeedStartMs = 0L;
                    pendingFreshSpeedIntervalMs = 0L;
                    pendingFreshStopExtraMs = 50L;
                    for (int i = 0; i < speedCount; i++) {
                        scheduleCommand(label + " SPEED " + (i + 1) + "/" + speedCount,
                                CMD_SPEED_STEP,
                                speedStartMs + i * speedIntervalMs);
                    }
                    for (int i = 0; i < repeatCount; i++) {
                        scheduleCommand(label + " FRESH " + (i + 1) + "/" + repeatCount,
                                hex,
                                motionDelayMs + i * repeatIntervalMs);
                    }
                    scheduleCommand(label + " FRESH STOP",
                            CMD_STOP,
                            motionDelayMs + repeatCount * repeatIntervalMs + stopExtraMs);
                }
                replayVendorLrAfterInitAck = pendingReplayVendorLr;
            }
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status
        ) {
            appendLogSafe("Write done status=" + status);
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic
        ) {
            byte[] value = characteristic.getValue();
            String hex = toHex(value);
            if (isInitAck(value) && replayVendorLrAfterInitAck) {
                replayVendorLrAfterInitAck = false;
                pendingReplayVendorLr = false;
                scheduleVendorLrReplay(5675L);
            }
            handleLimitNotification(value);
            runOnUiThread(() -> {
                notifyText.setText("Notify: " + hex);
                appendLog("Notify " + characteristic.getUuid() + " = " + hex);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        pianoKeyPlayer = new PianoKeyPlayer(this);
        loadPianoScanData();
        loadCalibrationSettings();

        setContentView(buildContentView());
        requestNeededPermissions();
        if (hasAudioPermission()) {
            startPitchListening();
        }
    }

    @Override
    protected void onDestroy() {
        stopAutoTune(false);
        stopHeartbeat();
        stopPitchListening();
        stopScan();
        disconnectGatt();
        if (pianoKeyPlayer != null) {
            pianoKeyPlayer.release();
            pianoKeyPlayer = null;
        }
        super.onDestroy();
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, 0);
        root.setBackgroundColor(Color.rgb(231, 204, 161));
        scrollView.addView(root);

        statusText = text("", 1, Color.TRANSPARENT, false);
        pitchText = text("", 1, Color.TRANSPARENT, false);
        targetText = text("", 1, Color.TRANSPARENT, false);
        autoText = text("", 1, Color.TRANSPARENT, false);
        notifyText = text("", 1, Color.TRANSPARENT, false);
        logText = text("", 1, Color.TRANSPARENT, false);

        tunerView = new SteampunkTunerView(this, this::selectTargetMidi, this::showControlPopup);
        root.addView(tunerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(860)
        ));

        selectTargetMidi(selectedTargetMidi);
        keyPreviewEnabled = true;

        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_UP));
        return scrollView;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        view.setPadding(0, dp(6), 0, dp(6));
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView label(String value) {
        return text(value, 14, Color.rgb(58, 72, 80), true);
    }

    private EditText editText(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setFilters(new InputFilter[]{new InputFilter.AllCaps()});
        return input;
    }

    private LinearLayout row(View... children) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(4), 0, dp(4));
        for (View child : children) {
            row.addView(child);
        }
        return row;
    }

    private Button smallButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(Color.rgb(45, 31, 20));
        button.setBackgroundColor(Color.rgb(219, 198, 166));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        button.setLayoutParams(params);
        return button;
    }

    @SuppressLint("ClickableViewAccessibility")
    private Button holdButton(String text, String label, String hex) {
        Button button = smallButton(text, v -> {
        });
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    startHoldMotion(label, hex);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    stopHoldMotion(label);
                    return true;
                default:
                    return true;
            }
        });
        return button;
    }

    private Button popupButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(18);
        button.setAllCaps(false);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.rgb(58, 38, 24));
        button.setBackgroundColor(Color.rgb(218, 178, 111));
        button.setMinHeight(dp(58));
        button.setElevation(dp(6));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, dp(8));
        button.setLayoutParams(params);
        return button;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showControlPopup() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(18), dp(22), dp(18));
        panel.setBackgroundColor(Color.rgb(232, 198, 139));

        Button connect = popupButton("Connect Board");
        connect.setOnClickListener(v -> connectOnce());
        panel.addView(connect);

        Button left = popupButton("HOLD LEFT");
        left.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    startHoldMotion("LEFT", CMD_LEFT);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    stopHoldMotion("LEFT");
                    return true;
                default:
                    return true;
            }
        });
        panel.addView(left);

        Button right = popupButton("HOLD RIGHT");
        right.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    startHoldMotion("RIGHT", CMD_RIGHT);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    stopHoldMotion("RIGHT");
                    return true;
                default:
                    return true;
            }
        });
        panel.addView(right);

        Button record88 = popupButton("Record 88 Keys");
        record88.setOnClickListener(v -> showPianoScanDialog());
        panel.addView(record88);

        Button auto = popupButton("Auto Tune Selected Key");
        auto.setOnClickListener(v -> showAutoSafetyDialog());
        panel.addView(auto);

        Button stopAuto = popupButton("Stop Auto Tune");
        stopAuto.setOnClickListener(v -> stopAutoTune(true));
        panel.addView(stopAuto);

        Button calibration = popupButton("Calibration");
        calibration.setOnClickListener(v -> showCalibrationDialog());
        panel.addView(calibration);

        Button about = popupButton("About");
        about.setOnClickListener(v -> showAboutDialogClean());
        panel.addView(about);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Motor Control")
                .setView(panel)
                .create();
        dialog.setOnDismissListener(d -> stopAllCommands());
        dialog.show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage("The motor is a 12V gear motor with a speed of 90 RPM. On the app's tuning dial, the 12 o'clock position (zero mark) indicates correct pitch; the range extends 170 degrees to the left and 170 degrees to the right, representing the full tuning span. The app will trigger an alarm if these limits are exceeded, so please adjust carefully—I accept no liability for broken strings.\n\nTORY HIGH SCHOOL MAX.YING 2026.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAboutDialogClean() {
        new AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage("The motor is a 12V gear motor with a speed of 90 RPM. On the app's tuning dial, the 12 o'clock position (zero mark) indicates correct pitch; the range extends 170 degrees to the left and 170 degrees to the right, representing the full tuning span. The app will trigger an alarm if these limits are exceeded, so please adjust carefully - I accept no liability for broken strings.\n\nTORY HIGH SCHOOL MAX.YING 2026.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAutoSafetyDialog() {
        String note = noteName(selectedTargetMidi);
        new AlertDialog.Builder(this)
                .setTitle("Auto Tune Safety")
                .setMessage("Before starting automatic tuning:\n\n"
                        + "1. The piano rib bracket is locked and cannot slide.\n"
                        + "2. Left and right limit switches have been tested.\n"
                        + "3. Manual HOLD LEFT and HOLD RIGHT work correctly.\n"
                        + "4. The selected key is " + note + ". Play this key until the pitch display is stable.\n\n"
                        + "Auto mode sends short motor pulses. Stop immediately if the mechanism moves unexpectedly.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start Auto Tune", (dialog, which) -> startAutoTune())
                .show();
    }

    private void showCalibrationDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setBackgroundColor(Color.rgb(232, 198, 139));

        EditText speed = numberEditText(autoSpeedPercent);
        EditText probe = numberEditText(autoProbeMs);
        EditText pulseLong = numberEditText(autoPulseLongMs);
        EditText pulseMedium = numberEditText(autoPulseMediumMs);
        EditText pulseShort = numberEditText(autoPulseShortMs);
        EditText settle = numberEditText(autoSettleMs);
        EditText maxSteps = numberEditText(autoMaxStepsSetting);
        EditText tolerance = decimalEditText(autoToleranceCents);

        panel.addView(label("Speed percent (1-100)"));
        panel.addView(speed);
        panel.addView(label("Probe pulse ms"));
        panel.addView(probe);
        panel.addView(label("Long pulse ms (>25 cents)"));
        panel.addView(pulseLong);
        panel.addView(label("Medium pulse ms (10-25 cents)"));
        panel.addView(pulseMedium);
        panel.addView(label("Short pulse ms (<10 cents)"));
        panel.addView(pulseShort);
        panel.addView(label("Settle time after pulse ms"));
        panel.addView(settle);
        panel.addView(label("Max auto steps"));
        panel.addView(maxSteps);
        panel.addView(label("Done tolerance cents"));
        panel.addView(tolerance);

        new AlertDialog.Builder(this)
                .setTitle("Calibration")
                .setView(panel)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Defaults", (dialog, which) -> {
                    resetCalibrationDefaults();
                    saveCalibrationSettings();
                    toast("Calibration defaults restored");
                })
                .setPositiveButton("Save", (dialog, which) -> {
                    autoSpeedPercent = clampInt(parseInt(speed, autoSpeedPercent), 1, 100);
                    autoProbeMs = clampInt(parseInt(probe, autoProbeMs), 80, 1500);
                    autoPulseLongMs = clampInt(parseInt(pulseLong, autoPulseLongMs), 80, 1500);
                    autoPulseMediumMs = clampInt(parseInt(pulseMedium, autoPulseMediumMs), 60, 1000);
                    autoPulseShortMs = clampInt(parseInt(pulseShort, autoPulseShortMs), 40, 800);
                    autoSettleMs = clampInt(parseInt(settle, autoSettleMs), 500, 5000);
                    autoMaxStepsSetting = clampInt(parseInt(maxSteps, autoMaxStepsSetting), 3, 120);
                    autoToleranceCents = clampDouble(parseDouble(tolerance, autoToleranceCents), 0.5, 20.0);
                    saveCalibrationSettings();
                    toast("Calibration saved");
                })
                .show();
    }

    private EditText numberEditText(int value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(String.valueOf(value));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private EditText decimalEditText(double value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(String.format(Locale.US, "%.1f", value));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return input;
    }

    private void showPianoScanDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setBackgroundColor(Color.rgb(232, 198, 139));

        pianoScanStatusText = text("", 16, Color.rgb(45, 31, 20), true);
        pianoScanProgressText = text("", 13, Color.rgb(82, 56, 34), false);
        panel.addView(pianoScanStatusText);
        panel.addView(pianoScanProgressText);

        Button start = popupButton("Start / Resume Recording");
        start.setOnClickListener(v -> startPianoScan());
        panel.addView(start);

        Button prev = smallButton("Previous", v -> movePianoScanKey(-1));
        Button next = smallButton("Next", v -> movePianoScanKey(1));
        panel.addView(row(prev, next));

        Button compute = popupButton("Compute Tuning");
        compute.setOnClickListener(v -> computePianoTuningModel());
        panel.addView(compute);

        Button reset = popupButton("Reset Recording");
        reset.setOnClickListener(v -> resetPianoScan());
        panel.addView(reset);

        updatePianoScanDialog();
        pianoScanDialog = new AlertDialog.Builder(this)
                .setTitle("88-Key Recording")
                .setView(panel)
                .setPositiveButton("Close", null)
                .create();
        pianoScanDialog.setOnDismissListener(dialog -> pianoScanActive = false);
        pianoScanDialog.show();
    }

    private void startPianoScan() {
        if (!hasAudioPermission()) {
            requestNeededPermissions();
            return;
        }
        if (!listening) {
            startPitchListening();
        }
        if (pianoScanMidi < 21 || pianoScanMidi > 108) {
            pianoScanMidi = firstUnrecordedMidi();
        }
        pianoScanActive = true;
        resetPianoScanSamples("Waiting");
        selectTargetMidi(pianoScanMidi, false);
        updatePianoScanDialog();
    }

    private void resetPianoScan() {
        pianoScanActive = false;
        pianoScanMidi = 21;
        pianoScanCount = 0;
        pianoTuningComputed = false;
        resetPianoScanSamples("Waiting");
        for (int midi = 21; midi <= 108; midi++) {
            pianoScanRecorded[midi] = false;
            pianoScanFrequency[midi] = 0;
            pianoScanCents[midi] = 0;
            pianoTuningOffsetCents[midi] = 0;
        }
        savePianoScanData();
        selectTargetMidi(pianoScanMidi, false);
        updatePianoScanDialog();
    }

    private void movePianoScanKey(int delta) {
        pianoScanMidi = Math.max(21, Math.min(108, pianoScanMidi + delta));
        pianoScanActive = true;
        resetPianoScanSamples("Waiting");
        selectTargetMidi(pianoScanMidi, false);
        updatePianoScanDialog();
    }

    private void updatePianoScanDialog() {
        if (pianoScanStatusText == null || pianoScanProgressText == null) {
            return;
        }
        String state = pianoScanActive ? "Listening" : "Paused";
        String recorded = pianoScanRecorded[pianoScanMidi] ? "recorded" : "waiting";
        pianoScanStatusText.setText(String.format(Locale.US,
                "%s: play %s (%d/88, %s, %s)",
                state,
                noteName(pianoScanMidi),
                pianoScanMidi - 20,
                recorded,
                pianoScanQuality
        ));
        String model = pianoTuningComputed
                ? String.format(Locale.US, "Tuning map ready: %+.1f cents for %s",
                pianoTuningOffsetCents[pianoScanMidi],
                noteName(pianoScanMidi))
                : "Record all keys before computing";
        pianoScanProgressText.setText(String.format(Locale.US,
                "Recorded %d of 88 keys. %s.\nEach key needs %d stable samples; the median is saved automatically.",
                pianoScanCount,
                model,
                PIANO_SCAN_SAMPLES_REQUIRED
        ));
    }

    private int firstUnrecordedMidi() {
        for (int midi = 21; midi <= 108; midi++) {
            if (!pianoScanRecorded[midi]) {
                return midi;
            }
        }
        return 108;
    }

    private void handlePianoScanPitch(double frequency, int detectedMidi, double centsToTarget) {
        if (!pianoScanActive || pianoScanMidi < 21 || pianoScanMidi > 108) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - pianoScanLastRecordMs < 650L) {
            return;
        }
        if (Math.abs(detectedMidi - pianoScanMidi) > 1) {
            resetPianoScanSamples("Wrong key: heard " + noteName(detectedMidi));
            updatePianoScanDialog();
            return;
        }
        if (Math.abs(centsToTarget) > 150.0) {
            resetPianoScanSamples("Pitch too far: " + String.format(Locale.US, "%+.0f cents", centsToTarget));
            updatePianoScanDialog();
            return;
        }
        pianoScanSampleFrequency[pianoScanSampleCount] = frequency;
        pianoScanSampleCents[pianoScanSampleCount] = centsToTarget;
        pianoScanSampleCount++;
        pianoScanLastRecordMs = now;
        pianoScanQuality = String.format(Locale.US, "Sample %d/%d",
                pianoScanSampleCount,
                PIANO_SCAN_SAMPLES_REQUIRED);
        if (pianoScanSampleCount < PIANO_SCAN_SAMPLES_REQUIRED) {
            updatePianoScanDialog();
            return;
        }
        if (!pianoScanRecorded[pianoScanMidi]) {
            pianoScanRecorded[pianoScanMidi] = true;
            pianoScanCount++;
        }
        pianoScanFrequency[pianoScanMidi] = median3(pianoScanSampleFrequency);
        pianoScanCents[pianoScanMidi] = median3(pianoScanSampleCents);
        pianoScanQuality = String.format(Locale.US, "Recorded OK: %.2f Hz, %+.1f cents",
                pianoScanFrequency[pianoScanMidi],
                pianoScanCents[pianoScanMidi]);
        savePianoScanData();
        appendLog(String.format(Locale.US, "Recorded %s %.2f Hz %.1f cents",
                noteName(pianoScanMidi), pianoScanFrequency[pianoScanMidi], pianoScanCents[pianoScanMidi]));
        if (pianoScanMidi < 108) {
            pianoScanMidi++;
            resetPianoScanSamples("Waiting");
            selectTargetMidi(pianoScanMidi, false);
        } else {
            pianoScanActive = false;
            resetPianoScanSamples("Complete");
        }
        updatePianoScanDialog();
    }

    private void updatePianoScanNoPitch(float level) {
        if (!pianoScanActive || pianoScanSampleCount > 0) {
            return;
        }
        updatePianoScanWaitingQuality(level);
    }

    private void updatePianoScanUnstable(float level) {
        if (!pianoScanActive || pianoScanSampleCount > 0) {
            return;
        }
        updatePianoScanWaitingQuality(level);
    }

    private void updatePianoScanWaitingQuality(float level) {
        String next = level < 0.045f ? "Too quiet" : "Unstable pitch";
        if (!next.equals(pianoScanQuality)) {
            pianoScanQuality = next;
            updatePianoScanDialog();
        }
    }

    private void resetPianoScanSamples(String quality) {
        pianoScanSampleCount = 0;
        pianoScanQuality = quality;
        for (int i = 0; i < PIANO_SCAN_SAMPLES_REQUIRED; i++) {
            pianoScanSampleFrequency[i] = 0;
            pianoScanSampleCents[i] = 0;
        }
    }

    private double median3(double[] values) {
        double a = values[0];
        double b = values[1];
        double c = values[2];
        if (a > b) {
            double t = a;
            a = b;
            b = t;
        }
        if (b > c) {
            double t = b;
            b = c;
            c = t;
        }
        if (a > b) {
            b = a;
        }
        return b;
    }

    private void computePianoTuningModel() {
        if (pianoScanCount < 88) {
            toast(String.format(Locale.US, "Record all 88 keys first (%d/88)", pianoScanCount));
            updatePianoScanDialog();
            return;
        }
        for (int midi = 21; midi <= 108; midi++) {
            double x = (midi - 64.5) / 43.5;
            pianoTuningOffsetCents[midi] = 18.0 * x * x * x + 4.0 * x;
        }
        pianoTuningComputed = true;
        savePianoScanData();
        toast("Tuning map ready");
        selectTargetMidi(selectedTargetMidi, false);
        updatePianoScanDialog();
    }

    private void savePianoScanData() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putInt("scan_count", pianoScanCount);
        editor.putInt("scan_midi", pianoScanMidi);
        editor.putBoolean("tuning_computed", pianoTuningComputed);
        for (int midi = 21; midi <= 108; midi++) {
            editor.putBoolean("rec_" + midi, pianoScanRecorded[midi]);
            editor.putString("freq_" + midi, String.format(Locale.US, "%.6f", pianoScanFrequency[midi]));
            editor.putString("cents_" + midi, String.format(Locale.US, "%.6f", pianoScanCents[midi]));
            editor.putString("offset_" + midi, String.format(Locale.US, "%.6f", pianoTuningOffsetCents[midi]));
        }
        editor.apply();
    }

    private void loadPianoScanData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        pianoScanCount = prefs.getInt("scan_count", 0);
        pianoScanMidi = prefs.getInt("scan_midi", 21);
        pianoTuningComputed = prefs.getBoolean("tuning_computed", false);
        int counted = 0;
        for (int midi = 21; midi <= 108; midi++) {
            pianoScanRecorded[midi] = prefs.getBoolean("rec_" + midi, false);
            pianoScanFrequency[midi] = parseStoredDouble(prefs, "freq_" + midi);
            pianoScanCents[midi] = parseStoredDouble(prefs, "cents_" + midi);
            pianoTuningOffsetCents[midi] = parseStoredDouble(prefs, "offset_" + midi);
            if (pianoScanRecorded[midi]) {
                counted++;
            }
        }
        pianoScanCount = counted;
        pianoScanMidi = Math.max(21, Math.min(108, pianoScanMidi));
    }

    private void loadCalibrationSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        autoSpeedPercent = clampInt(prefs.getInt("auto_speed_percent", autoSpeedPercent), 1, 100);
        autoProbeMs = clampInt(prefs.getInt("auto_probe_ms", autoProbeMs), 80, 1500);
        autoPulseLongMs = clampInt(prefs.getInt("auto_pulse_long_ms", autoPulseLongMs), 80, 1500);
        autoPulseMediumMs = clampInt(prefs.getInt("auto_pulse_medium_ms", autoPulseMediumMs), 60, 1000);
        autoPulseShortMs = clampInt(prefs.getInt("auto_pulse_short_ms", autoPulseShortMs), 40, 800);
        autoSettleMs = clampInt(prefs.getInt("auto_settle_ms", autoSettleMs), 500, 5000);
        autoMaxStepsSetting = clampInt(prefs.getInt("auto_max_steps", autoMaxStepsSetting), 3, 120);
        autoToleranceCents = clampDouble(parseStoredDouble(prefs, "auto_tolerance_cents", autoToleranceCents), 0.5, 20.0);
    }

    private void saveCalibrationSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putInt("auto_speed_percent", autoSpeedPercent);
        editor.putInt("auto_probe_ms", autoProbeMs);
        editor.putInt("auto_pulse_long_ms", autoPulseLongMs);
        editor.putInt("auto_pulse_medium_ms", autoPulseMediumMs);
        editor.putInt("auto_pulse_short_ms", autoPulseShortMs);
        editor.putInt("auto_settle_ms", autoSettleMs);
        editor.putInt("auto_max_steps", autoMaxStepsSetting);
        editor.putString("auto_tolerance_cents", String.format(Locale.US, "%.3f", autoToleranceCents));
        editor.apply();
    }

    private void resetCalibrationDefaults() {
        autoSpeedPercent = 50;
        autoProbeMs = 350;
        autoPulseLongMs = 450;
        autoPulseMediumMs = 250;
        autoPulseShortMs = 120;
        autoSettleMs = 1400;
        autoMaxStepsSetting = AUTO_MAX_STEPS;
        autoToleranceCents = AUTO_TOLERANCE_CENTS;
    }

    private double parseStoredDouble(SharedPreferences prefs, String key) {
        return parseStoredDouble(prefs, key, 0);
    }

    private double parseStoredDouble(SharedPreferences prefs, String key, double fallback) {
        try {
            return Double.parseDouble(prefs.getString(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int parseInt(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double parseDouble(EditText input, double fallback) {
        try {
            return Double.parseDouble(input.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String speedCommandForPercent(int percent) {
        return String.format(Locale.US, "A10801%02X", clampInt(percent, 1, 100));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String value) {
        statusText.setText(value);
    }

    private void showMotorDialog(String message) {
        if (isFinishing()) {
            return;
        }
        if (motorDialog == null) {
            motorDialog = new AlertDialog.Builder(this)
                    .setTitle("Motor Connection")
                    .setMessage(message)
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        connectAfterScan = false;
                        stopScan();
                        disconnectGatt();
                    })
                    .create();
        } else {
            motorDialog.setMessage(message);
        }
        if (!motorDialog.isShowing()) {
            motorDialog.show();
        }
    }

    private void dismissMotorDialog() {
        if (motorDialog != null && motorDialog.isShowing()) {
            motorDialog.dismiss();
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        if (gatt != null && writeCharacteristic != null) {
            appendLog("Heartbeat scheduled every 10 minutes");
            handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS);
        }
    }

    private void stopHeartbeat() {
        handler.removeCallbacks(heartbeatRunnable);
    }

    private void appendLogSafe(String value) {
        runOnUiThread(() -> appendLog(value));
    }

    private void appendLog(String value) {
        String line = String.format(Locale.US, "%tT  %s\n", System.currentTimeMillis(), value);
        logText.append(line);
    }

    private int readRepeat() {
        try {
            return Math.max(1, Math.min(100, Integer.parseInt(repeatInput.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (adapter == null || scanner == null) {
            toast("BLE not available");
            return;
        }
        if (!hasScanPermission()) {
            requestNeededPermissions();
            return;
        }

        stopScan();
        targetDevice = null;
        scanning = true;
        setStatus("Searching motor");
        appendLog("Scan started");
        scanner.startScan(scanCallback);
        handler.postDelayed(this::stopScan, SCAN_MS);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanner != null && scanning && hasScanPermission()) {
            scanner.stopScan(scanCallback);
            scanning = false;
            appendLog("Scan stopped");
            if (targetDevice == null) {
                if (connectAfterScan) {
                    connectAfterScan = false;
                    dismissMotorDialog();
                    toast("Motor not found");
                }
                setStatus("Motor not found");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void connectTarget() {
        if (!hasConnectPermission()) {
            requestNeededPermissions();
            return;
        }
        if (targetDevice == null) {
            if (adapter == null) {
                toast("Scan first");
                return;
            }
            targetDevice = adapter.getRemoteDevice(TARGET_ADDRESS);
        }

        if (gatt != null) {
            reconnectAfterDisconnect = true;
            requestGattDisconnect();
            return;
        }
        openGattConnection("Connecting ");
    }

    private void connectOnce() {
        if (gatt != null) {
            appendLog("Already connected or connecting");
            return;
        }
        showMotorDialog("Searching for motor...");
        if (targetDevice == null) {
            stopScan();
            connectAfterScan = true;
            startScan();
            return;
        }
        connectTarget();
    }

    @SuppressLint("MissingPermission")
    private void openGattConnection(String prefix) {
        if (!hasConnectPermission()) {
            requestNeededPermissions();
            return;
        }
        if (targetDevice == null) {
            toast("Scan first");
            return;
        }
        setStatus(prefix + "motor");
        appendLog(prefix + deviceLabel(targetDevice));
        gatt = targetDevice.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission")
    private void disconnectGatt() {
        reconnectAfterDisconnect = false;
        stopHeartbeat();
        requestGattDisconnect();
    }

    @SuppressLint("MissingPermission")
    private void requestGattDisconnect() {
        stopHeartbeat();
        BluetoothGatt currentGatt = gatt;
        if (currentGatt != null && hasConnectPermission()) {
            appendLog("Disconnect requested");
            currentGatt.disconnect();
            handler.postDelayed(() -> {
                if (gatt == currentGatt) {
                    appendLog("Disconnect timeout, closing GATT");
                    currentGatt.close();
                    gatt = null;
                    writeCharacteristic = null;
                    if (reconnectAfterDisconnect) {
                        reconnectAfterDisconnect = false;
                        openGattConnection("Reconnecting ");
                    }
                }
            }, 1500L);
        } else {
            gatt = null;
            writeCharacteristic = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (!hasConnectPermission()) {
            return;
        }
        boolean enabled = gatt.setCharacteristicNotification(characteristic, true);
        appendLogSafe("Notify enable=" + enabled);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean started = gatt.writeDescriptor(descriptor);
            appendLogSafe("CCCD write started=" + started);
        }
    }

    private void sendMotion(String label, String hex) {
        beginCommandSequence();
        if (!prepareMotion(label, hex)) {
            return;
        }
        sendCommand(label, hex);
        scheduleCommand(label + " repeat", hex, 130);
    }

    private void startHoldMotion(String label, String hex) {
        beginCommandSequence();
        if (!prepareMotion(label, hex)) {
            return;
        }
        sendCommand(label, hex);
        activeMotionRunnable = new Runnable() {
            @Override
            public void run() {
                sendCommand(label + " hold", hex);
                handler.postDelayed(this, 130);
            }
        };
        handler.postDelayed(activeMotionRunnable, 130);
    }

    private void stopHoldMotion(String label) {
        cancelScheduledCommands();
        currentDirection = Direction.NONE;
        sendCommand(label + " release STOP", CMD_STOP);
    }

    private void pulseMotion(String label, String hex, long durationMs) {
        beginCommandSequence();
        if (!prepareMotion(label, hex)) {
            return;
        }
        sendCommand(label, hex);
        int generation = commandGeneration;
        activeMotionRunnable = new Runnable() {
            @Override
            public void run() {
                if (generation != commandGeneration) {
                    return;
                }
                sendCommand(label + " pulse", hex);
                handler.postDelayed(this, 130);
            }
        };
        handler.postDelayed(activeMotionRunnable, 130);
        handler.postDelayed(() -> {
            if (generation == commandGeneration) {
                cancelScheduledCommands();
                currentDirection = Direction.NONE;
                sendCommand(label + " pulse STOP", CMD_STOP);
            }
        }, durationMs);
    }

    private void primeMode() {
        beginCommandSequence();
        for (int i = 0; i < 6; i++) {
            int step = i + 1;
            scheduleCommand("PRIME " + step + "/6", CMD_STOP, i * 1000L);
        }
    }

    private void sourceReady() {
        beginCommandSequence();
        sendCommand("JOG", CMD_MODE_JOG);
        sendCommand("SPEED" + autoSpeedPercent, speedCommandForPercent(autoSpeedPercent));
    }

    private void primeThenPulse(String label, String hex) {
        beginCommandSequence();
        for (int i = 0; i < 6; i++) {
            int step = i + 1;
            scheduleCommand("PRIME " + step + "/6", CMD_STOP, i * 1000L);
        }
        scheduleRunnable(() -> pulseMotion(label, hex, 1000), 6500L);
    }

    private void warmThenPulse(String label, String hex) {
        beginCommandSequence();
        scheduleSpeedSteps(20, 0L);
        scheduleRunnable(() -> pulseMotion(label, hex, 1000), 3800L);
    }

    private void vendorSpeed30Pulse(String label, String hex) {
        beginCommandSequence();
        sendCommand(label + " INIT", CMD_INIT);
        for (int i = 0; i < 20; i++) {
            int step = i + 1;
            scheduleCommand(label + " SPEED30 " + step + "/20", CMD_SPEED_STEP, 1000L + i * 180L);
        }
        long motionStart = 1000L + 20L * 180L + 380L;
        for (int i = 0; i < 8; i++) {
            int frame = i + 1;
            scheduleCommand(label + " frame " + frame + "/8", hex, motionStart + i * 138L);
        }
        scheduleCommand(label + " STOP", CMD_STOP, motionStart + 8L * 138L + 50L);
    }

    private void vendorExactSpeed30Pulse(String label, String hex) {
        beginCommandSequence();
        sendCommand(label + " INIT", CMD_INIT);
        long speedStart = 11_400L;
        for (int i = 0; i < 20; i++) {
            int step = i + 1;
            scheduleCommand(label + " SPEED30 " + step + "/20", CMD_SPEED_STEP, speedStart + i * 180L);
        }
        long motionStart = speedStart + 20L * 180L + 380L;
        for (int i = 0; i < 8; i++) {
            int frame = i + 1;
            scheduleCommand(label + " frame " + frame + "/8", hex, motionStart + i * 138L);
        }
        scheduleCommand(label + " STOP", CMD_STOP, motionStart + 8L * 138L + 50L);
    }

    private void vendorMinimalSpeed50Pulse(String label, String hex) {
        beginCommandSequence();
        sendCommand(label + " INIT", CMD_INIT);
        scheduleCommand(label + " ONCE", hex, 4520L);
        scheduleCommand(label + " STOP", CMD_STOP, 6250L);
    }

    private void freshConnectPulse(String label, String hex) {
        freshConnectPulse(label, hex, 900L);
    }

    private void freshConnectPulse(String label, String hex, long motionDelayMs) {
        freshConnectSequence(label, hex, motionDelayMs, 1, 0L);
    }

    private void freshConnectBurst(String label, String hex) {
        freshConnectSequence(label, hex, 4670L, 8, 138L);
    }

    private void freshConnectSequence(String label, String hex, long motionDelayMs, int repeatCount, long repeatIntervalMs) {
        freshConnectSequence(label, hex, motionDelayMs, repeatCount, repeatIntervalMs, 0, 0L, 0L, 50L);
    }

    private void freshConnectSpeedBurst(String label, String hex) {
        freshConnectSequence(label, hex, 4980L, 8, 138L, 20, 1000L, 180L, 50L);
    }

    private void freshConnectStorePulse(String label, String hex) {
        freshConnectSequence(label, hex, 1510L, 2, 130L, 0, 0L, 0L, 1450L);
    }

    private void liveBurst(String label, String hex) {
        beginCommandSequence();
        if (!prepareMotion(label, hex)) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            scheduleCommand(label + " " + (i + 1) + "/8", hex, i * 138L);
        }
        scheduleCommand(label + " STOP", CMD_STOP, 8L * 138L + 50L);
        scheduleRunnable(() -> currentDirection = Direction.NONE, 8L * 138L + 80L);
    }

    private void liveStorePulse(String label, String hex) {
        beginCommandSequence();
        if (!prepareMotion(label, hex)) {
            return;
        }
        sendCommand(label + " 1/2", hex);
        scheduleCommand(label + " 2/2", hex, 130L);
        scheduleCommand(label + " STOP", CMD_STOP, 1600L);
        scheduleRunnable(() -> currentDirection = Direction.NONE, 1650L);
    }

    private void freshReplayVendorLr() {
        beginCommandSequence();
        if (targetDevice == null && adapter != null) {
            targetDevice = adapter.getRemoteDevice(TARGET_ADDRESS);
        }
        if (targetDevice == null) {
            toast("Scan first");
            return;
        }
        pendingReplayVendorLr = true;
        appendLog("Replay vendor LR queued");
        if (gatt != null) {
            reconnectAfterDisconnect = true;
            requestGattDisconnect();
        } else {
            scheduleRunnable(() -> openGattConnection("Connecting "), 650L);
        }
    }

    private void scheduleVendorLrReplay(long firstDelayMs) {
        scheduleCommand("REPLAY LEFT 1/2", CMD_LEFT, firstDelayMs);
        scheduleCommand("REPLAY LEFT 2/2", CMD_LEFT, firstDelayMs + 130L);
        scheduleCommand("REPLAY LEFT STOP", CMD_STOP, firstDelayMs + 1379L);
        scheduleCommand("REPLAY RIGHT 1/2", CMD_RIGHT, firstDelayMs + 2675L);
        scheduleCommand("REPLAY RIGHT 2/2", CMD_RIGHT, firstDelayMs + 2806L);
        scheduleCommand("REPLAY RIGHT STOP", CMD_STOP, firstDelayMs + 4474L);
    }

    private void freshConnectSequence(
            String label,
            String hex,
            long motionDelayMs,
            int repeatCount,
            long repeatIntervalMs,
            int speedCount,
            long speedStartMs,
            long speedIntervalMs,
            long stopExtraMs
    ) {
        beginCommandSequence();
        if (targetDevice == null && adapter != null) {
            targetDevice = adapter.getRemoteDevice(TARGET_ADDRESS);
        }
        if (targetDevice == null) {
            toast("Scan first");
            return;
        }
        pendingFreshLabel = label;
        pendingFreshHex = hex;
        pendingFreshMotionDelayMs = motionDelayMs;
        pendingFreshRepeatCount = Math.max(1, repeatCount);
        pendingFreshRepeatIntervalMs = Math.max(0L, repeatIntervalMs);
        pendingFreshSpeedCount = Math.max(0, speedCount);
        pendingFreshSpeedStartMs = Math.max(0L, speedStartMs);
        pendingFreshSpeedIntervalMs = Math.max(0L, speedIntervalMs);
        pendingFreshStopExtraMs = Math.max(50L, stopExtraMs);
        appendLog(label + " fresh reconnect queued");
        if (gatt != null) {
            reconnectAfterDisconnect = true;
            requestGattDisconnect();
        } else {
            scheduleRunnable(() -> openGattConnection("Connecting "), 650L);
        }
    }

    private boolean prepareMotion(String label, String hex) {
        Direction direction = directionForHex(hex);
        if (direction == Direction.NONE) {
            return true;
        }
        if (blockedDirection == direction) {
            String message = directionName(direction) + " limit is active; use opposite direction";
            appendLog(message);
            setStatus(message);
            toast(message);
            return false;
        }
        currentDirection = direction;
        appendLog(label + " direction=" + directionName(direction));
        return true;
    }

    private Direction directionForHex(String hex) {
        String clean = hex == null ? "" : hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        if (CMD_LEFT.equalsIgnoreCase(clean)) {
            return Direction.LEFT;
        }
        if (CMD_RIGHT.equalsIgnoreCase(clean)) {
            return Direction.RIGHT;
        }
        return Direction.NONE;
    }

    private void handleLimitNotification(byte[] data) {
        if (data == null || data.length < 3 || (data[0] & 0xFF) != 0xA1) {
            return;
        }

        int channel = data[1] & 0xFF;
        int state = data[2] & 0xFF;
        Direction limitDirection;
        if (channel == 0x01) {
            limitDirection = Direction.LEFT;
        } else if (channel == 0x02) {
            limitDirection = Direction.RIGHT;
        } else {
            return;
        }

        if (state == 0x01) {
            onLimitActive(limitDirection);
        } else if (state == 0x02) {
            onLimitReleased(limitDirection);
        }
    }

    private void onLimitActive(Direction limitDirection) {
        blockedDirection = limitDirection;
        String message = directionName(limitDirection) + " limit active";
        appendLogSafe(message);
        runOnUiThread(() -> setStatus(message));
        if (currentDirection != Direction.NONE) {
            cancelScheduledCommands();
            currentDirection = Direction.NONE;
            sendCommand("LIMIT STOP", CMD_STOP);
        }
        if (autoTuning) {
            autoTuning = false;
            autoGeneration++;
            runOnUiThread(() -> autoText.setText("Auto: stopped by limit"));
        }
    }

    private void onLimitReleased(Direction limitDirection) {
        if (blockedDirection == limitDirection) {
            blockedDirection = Direction.NONE;
        }
        appendLogSafe(directionName(limitDirection) + " limit released");
    }

    private String directionName(Direction direction) {
        if (direction == Direction.LEFT) {
            return "Left";
        }
        if (direction == Direction.RIGHT) {
            return "Right";
        }
        return "None";
    }

    private void beginCommandSequence() {
        cancelScheduledCommands();
        commandGeneration++;
    }

    private void stopAllCommands() {
        cancelScheduledCommands();
        currentDirection = Direction.NONE;
        pendingFreshLabel = null;
        pendingFreshHex = null;
        pendingFreshMotionDelayMs = 0L;
        pendingFreshRepeatCount = 1;
        pendingFreshRepeatIntervalMs = 0L;
        pendingFreshSpeedCount = 0;
        pendingFreshSpeedStartMs = 0L;
        pendingFreshSpeedIntervalMs = 0L;
        pendingFreshStopExtraMs = 50L;
        pendingReplayVendorLr = false;
        replayVendorLrAfterInitAck = false;
        reconnectAfterDisconnect = false;
        sendCommand("STOP", CMD_STOP);
    }

    private void cancelScheduledCommands() {
        if (activeMotionRunnable != null) {
            handler.removeCallbacks(activeMotionRunnable);
            activeMotionRunnable = null;
        }
        commandGeneration++;
    }

    private void scheduleRunnable(Runnable runnable, long delayMs) {
        int generation = commandGeneration;
        handler.postDelayed(() -> {
            if (generation == commandGeneration) {
                runnable.run();
            }
        }, delayMs);
    }

    private void scheduleCommand(String label, String hex, long delayMs) {
        int generation = commandGeneration;
        handler.postDelayed(() -> {
            if (generation == commandGeneration) {
                sendCommand(label, hex);
            }
        }, delayMs);
    }

    private void scheduleSpeedSteps(int count, long startDelayMs) {
        int safeCount = Math.max(1, Math.min(100, count));
        for (int i = 0; i < safeCount; i++) {
            int step = i + 1;
            scheduleCommand(
                    "SPEED " + step + "/" + safeCount,
                    CMD_SPEED_STEP,
                    startDelayMs + i * 180L
            );
        }
    }

    private void sendSpeedSteps(int count) {
        beginCommandSequence();
        scheduleSpeedSteps(count, 0L);
    }

    @SuppressLint("MissingPermission")
    private void sendCommand(String label, String rawHex) {
        if (gatt == null || writeCharacteristic == null) {
            toast("Connect first");
            return;
        }
        if (!hasConnectPermission()) {
            requestNeededPermissions();
            return;
        }

        byte[] payload;
        try {
            payload = parseHex(rawHex);
        } catch (IllegalArgumentException ex) {
            toast(ex.getMessage());
            return;
        }

        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        writeCharacteristic.setValue(payload);
        boolean started = gatt.writeCharacteristic(writeCharacteristic);
        appendLog(label + " -> " + toHex(payload) + " started=" + started);
    }

    @SuppressLint("MissingPermission")
    private void sendCommandFromGattCallback(String label, String rawHex) {
        if (gatt == null || writeCharacteristic == null || !hasConnectPermission()) {
            return;
        }
        byte[] payload;
        try {
            payload = parseHex(rawHex);
        } catch (IllegalArgumentException ex) {
            appendLogSafe(ex.getMessage());
            return;
        }

        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        writeCharacteristic.setValue(payload);
        boolean started = gatt.writeCharacteristic(writeCharacteristic);
        appendLogSafe(label + " -> " + toHex(payload) + " started=" + started);
    }

    private byte[] parseHex(String value) {
        String clean = value.replace("0x", "")
                .replace("0X", "")
                .replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() == 0) {
            throw new IllegalArgumentException("Empty hex");
        }
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex length must be even");
        }

        byte[] data = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            data[i / 2] = (byte) Integer.parseInt(clean.substring(i, i + 2), 16);
        }
        return data;
    }

    private String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (byte b : data) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return builder.toString();
    }

    private boolean isInitAck(byte[] data) {
        return data != null
                && data.length >= 3
                && (data[0] & 0xFF) == 0xAF
                && (data[1] & 0xFF) == 0xA6
                && (data[2] & 0xFF) == 0x01;
    }

    @SuppressLint("MissingPermission")
    private void startPitchListening() {
        if (listening) {
            return;
        }
        if (!hasAudioPermission()) {
            requestNeededPermissions();
            return;
        }

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBuffer, PITCH_BUFFER_SIZE * 2);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            toast("Microphone init failed");
            return;
        }

        listening = true;
        audioRecord.startRecording();
        audioThread = new Thread(() -> {
            short[] buffer = new short[PITCH_BUFFER_SIZE];
            while (listening && audioRecord != null) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 1024) {
                    double frequency = detectPitchYin(buffer, read);
                    float level = computeLevel(buffer, read);
                    if (frequency > 0) {
                        updatePitch(frequency, level, buffer, read);
                    } else if (tunerView != null) {
                        runOnUiThread(() -> {
                            tunerView.setLevel(level);
                            updatePianoScanNoPitch(level);
                        });
                    }
                }
            }
        }, "MAX-Pitch");
        audioThread.start();
    }

    private void stopPitchListening() {
        listening = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
        audioThread = null;
    }

    private float computeLevel(short[] buffer, int size) {
        double sum = 0;
        for (int i = 0; i < size; i++) {
            double v = buffer[i] / 32768.0;
            sum += v * v;
        }
        return (float) Math.min(1.0, Math.sqrt(sum / Math.max(1, size)) * 8.0);
    }

    private double detectPitchYin(short[] input, int size) {
        int n = Math.min(size, PITCH_BUFFER_SIZE);
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += input[i];
        }
        mean /= n;

        double rms = 0;
        double[] samples = new double[n];
        for (int i = 0; i < n; i++) {
            samples[i] = (input[i] - mean) / 32768.0;
            rms += samples[i] * samples[i];
        }
        rms = Math.sqrt(rms / n);
        if (rms < 0.01) {
            return 0;
        }

        int tauMin = Math.max(2, (int) (SAMPLE_RATE / MAX_PIANO_FREQ));
        int tauMax = Math.min(n - 2, (int) (SAMPLE_RATE / MIN_PIANO_FREQ));
        double[] yin = new double[tauMax + 1];
        for (int tau = 1; tau <= tauMax; tau++) {
            double sum = 0;
            for (int i = 0; i < n - tau; i++) {
                double delta = samples[i] - samples[i + tau];
                sum += delta * delta;
            }
            yin[tau] = sum;
        }

        double runningSum = 0;
        int bestTau = -1;
        for (int tau = 1; tau <= tauMax; tau++) {
            runningSum += yin[tau];
            yin[tau] = runningSum == 0 ? 1 : yin[tau] * tau / runningSum;
            if (tau >= tauMin && yin[tau] < 0.12) {
                while (tau + 1 <= tauMax && yin[tau + 1] < yin[tau]) {
                    tau++;
                }
                bestTau = tau;
                break;
            }
        }

        if (bestTau < 0) {
            double best = 1.0;
            for (int tau = tauMin; tau <= tauMax; tau++) {
                if (yin[tau] < best) {
                    best = yin[tau];
                    bestTau = tau;
                }
            }
            if (best > 0.35) {
                return 0;
            }
        }

        double betterTau = bestTau;
        if (bestTau > 1 && bestTau < tauMax) {
            double left = yin[bestTau - 1];
            double center = yin[bestTau];
            double right = yin[bestTau + 1];
            double divisor = 2 * (2 * center - left - right);
            if (Math.abs(divisor) > 1e-9) {
                betterTau = bestTau + (right - left) / divisor;
            }
        }

        double frequency = SAMPLE_RATE / betterTau;
        return frequency >= MIN_PIANO_FREQ && frequency <= MAX_PIANO_FREQ ? frequency : 0;
    }

    private void updatePitch(double frequency, float level, short[] buffer, int size) {
        int midi = nearestMidi(frequency);
        int targetMidi = selectedTargetMidi > 0 ? selectedTargetMidi : midi;
        double target = targetFrequencyForMidi(targetMidi);
        double cents = centsBetween(frequency, target);
        boolean outOfRange = Math.abs(cents) > TUNER_DIAL_CENTS_RANGE;
        boolean pitchReady = isStablePianoPitch(cents, level);
        boolean alarmReady = outOfRange && pitchReady && System.currentTimeMillis() >= suppressRangeAlarmUntilMs;
        latestFrequency = frequency;
        latestMidi = midi;
        latestCents = cents;
        latestPitchMs = System.currentTimeMillis();
        if (pitchReady) {
            latestStableFrequency = frequency;
            latestStableMidi = midi;
            latestStableCents = cents;
            latestStablePitchMs = latestPitchMs;
        }
        String note = noteName(targetMidi);
        runOnUiThread(() -> {
            pitchText.setText(String.format(Locale.US, "Pitch: %s  %.1f Hz", note, frequency));
            targetText.setText(String.format(Locale.US, "Target: %s %.1f Hz   %.1f cents", note, target, cents));
            if (!pitchReady) {
                tunerView.setLevel(level);
                updatePianoScanUnstable(level);
            } else if (alarmReady) {
                tunerView.setOutOfRangePitch(note, frequency, target, cents, level, buffer, size);
                triggerPitchRangeAlarm();
            } else if (outOfRange) {
                tunerView.setLevel(level);
            } else {
                tunerView.setPitch(note, frequency, target, cents, level, buffer, size);
            }
            if (pitchReady) {
                handlePianoScanPitch(frequency, midi, cents);
            }
        });
    }

    private boolean isStablePianoPitch(double cents, float level) {
        long now = System.currentTimeMillis();
        if (now < suppressPitchUpdateUntilMs || level < 0.045f) {
            pitchCandidateFrames = 0;
            return false;
        }
        if (Math.abs(cents - pitchCandidateCents) > 35.0 || now - pitchCandidateStartedMs > 850L) {
            pitchCandidateCents = cents;
            pitchCandidateFrames = 1;
            pitchCandidateStartedMs = now;
            return false;
        }
        pitchCandidateCents = pitchCandidateCents * 0.65 + cents * 0.35;
        pitchCandidateFrames++;
        return pitchCandidateFrames >= 4 && now - pitchCandidateStartedMs >= 220L;
    }

    private void triggerPitchRangeAlarm() {
        long now = System.currentTimeMillis();
        if (now - lastRangeAlarmVibrateMs < 900) {
            return;
        }
        lastRangeAlarmVibrateMs = now;
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(
                    new long[]{0, 70, 80, 120},
                    new int[]{0, 170, 0, 220},
                    -1
            ));
        } else {
            vibrator.vibrate(new long[]{0, 70, 80, 120}, -1);
        }
    }

    private void selectTargetMidi(int midi) {
        selectTargetMidi(midi, true);
    }

    private void selectTargetMidi(int midi, boolean playPreview) {
        selectedTargetMidi = Math.max(21, Math.min(108, midi));
        suppressPitchUpdateUntilMs = System.currentTimeMillis() + 850L;
        suppressRangeAlarmUntilMs = suppressPitchUpdateUntilMs + 250L;
        pitchCandidateFrames = 0;
        String note = noteName(selectedTargetMidi);
        double target = targetFrequencyForMidi(selectedTargetMidi);
        if (targetText != null) {
            targetText.setText(String.format(Locale.US, "Target: %s %.1f Hz", note, target));
        }
        if (tunerView != null) {
            tunerView.setSelectedMidi(selectedTargetMidi);
        }
        if (playPreview && keyPreviewEnabled && pianoKeyPlayer != null) {
            suppressPitchUpdateUntilMs = System.currentTimeMillis() + 900L;
            suppressRangeAlarmUntilMs = suppressPitchUpdateUntilMs + 250L;
            pitchCandidateFrames = 0;
            pianoKeyPlayer.play(selectedTargetMidi);
        }
        if (logText != null) {
            appendLog("Selected target " + note + " " + String.format(Locale.US, "%.1f Hz", target));
        }
    }

    private int nearestMidi(double frequency) {
        int midi = (int) Math.round(69 + 12 * Math.log(frequency / 440.0) / Math.log(2));
        return Math.max(21, Math.min(108, midi));
    }

    private double frequencyForMidi(int midi) {
        return 440.0 * Math.pow(2, (midi - 69) / 12.0);
    }

    private double targetFrequencyForMidi(int midi) {
        double base = frequencyForMidi(midi);
        if (!pianoTuningComputed || midi < 21 || midi > 108) {
            return base;
        }
        return base * Math.pow(2.0, pianoTuningOffsetCents[midi] / 1200.0);
    }

    private double centsBetween(double frequency, double target) {
        return 1200.0 * Math.log(frequency / target) / Math.log(2);
    }

    private String noteName(int midi) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int octave = midi / 12 - 1;
        return names[Math.floorMod(midi, 12)] + octave;
    }

    private boolean hasFreshPitch() {
        return latestFrequency > 0 && System.currentTimeMillis() - latestPitchMs < 2500;
    }

    private boolean hasFreshStablePitchForTarget(int midi) {
        return latestStableFrequency > 0
                && System.currentTimeMillis() - latestStablePitchMs < 2500
                && Math.abs(latestStableMidi - midi) <= AUTO_NOTE_WINDOW_SEMITONES;
    }

    private void startAutoTune() {
        if (!hasAudioPermission()) {
            requestNeededPermissions();
            return;
        }
        if (!listening) {
            startPitchListening();
        }
        if (gatt == null || writeCharacteristic == null) {
            toast("Connect Bluetooth first");
            return;
        }
        int targetMidi = selectedTargetMidi > 0 ? selectedTargetMidi : latestMidi;
        if (!hasFreshStablePitchForTarget(targetMidi)) {
            toast("Play the selected key until pitch is stable");
            autoText.setText("Auto: waiting for stable selected key");
            return;
        }

        sourceReady();
        autoTuning = true;
        autoGeneration++;
        autoStepCount = 0;
        autoTargetMidi = targetMidi;
        autoTargetFrequency = targetFrequencyForMidi(autoTargetMidi);
        autoProbeFrequency = latestStableFrequency;
        autoRaiseDirection = Direction.NONE;
        autoLowerDirection = Direction.NONE;
        autoText.setText("Auto: probing left direction");
        appendLog("Auto target " + noteName(autoTargetMidi) + " " + autoTargetFrequency);
        autoPulse(Direction.LEFT, autoProbeMs);
        scheduleAuto(this::finishAutoProbe, autoProbeMs + autoSettleMs);
    }

    private void finishAutoProbe() {
        if (!autoTuning) {
            return;
        }
        if (!hasFreshStablePitchForTarget(autoTargetMidi)) {
            autoText.setText("Auto: play selected key again");
            scheduleAuto(this::finishAutoProbe, 1000);
            return;
        }

        double before = centsBetween(autoProbeFrequency, autoTargetFrequency);
        double after = centsBetween(latestStableFrequency, autoTargetFrequency);
        double delta = after - before;
        if (delta < -0.5) {
            autoLowerDirection = Direction.LEFT;
            autoRaiseDirection = Direction.RIGHT;
            autoText.setText("Auto: left lowers, right raises");
        } else if (delta > 0.5) {
            autoRaiseDirection = Direction.LEFT;
            autoLowerDirection = Direction.RIGHT;
            autoText.setText("Auto: left raises, right lowers");
        } else {
            autoLowerDirection = Direction.LEFT;
            autoRaiseDirection = Direction.RIGHT;
            autoText.setText("Auto: weak probe, using left lowers");
        }
        scheduleAuto(this::autoAdjustLoop, 700);
    }

    private void autoAdjustLoop() {
        if (!autoTuning) {
            return;
        }
        if (++autoStepCount > autoMaxStepsSetting) {
            autoText.setText("Auto: stopped, max steps");
            stopAutoTune(true);
            return;
        }
        if (!hasFreshStablePitchForTarget(autoTargetMidi)) {
            autoText.setText("Auto: waiting for stable key strike");
            scheduleAuto(this::autoAdjustLoop, 1000);
            return;
        }

        double cents = centsBetween(latestStableFrequency, autoTargetFrequency);
        if (Math.abs(cents) <= autoToleranceCents) {
            autoText.setText(String.format(Locale.US, "Auto: done %.1f cents", cents));
            stopAutoTune(true);
            return;
        }

        Direction direction = cents < 0 ? autoRaiseDirection : autoLowerDirection;
        if (direction == Direction.NONE) {
            autoText.setText("Auto: direction unknown");
            stopAutoTune(true);
            return;
        }

        int duration = Math.abs(cents) > 25 ? autoPulseLongMs : Math.abs(cents) > 10 ? autoPulseMediumMs : autoPulseShortMs;
        autoText.setText(String.format(Locale.US, "Auto: %.1f cents, pulse %s", cents, directionName(direction)));
        if (!autoPulse(direction, duration)) {
            stopAutoTune(true);
            return;
        }
        scheduleAuto(this::autoAdjustLoop, duration + autoSettleMs);
    }

    private boolean autoPulse(Direction direction, int durationMs) {
        String hex = direction == Direction.LEFT ? CMD_LEFT : CMD_RIGHT;
        cancelScheduledCommands();
        if (!prepareMotion("AUTO " + directionName(direction), hex)) {
            return false;
        }
        sendCommand("AUTO " + directionName(direction), hex);
        int generation = autoGeneration;
        handler.postDelayed(() -> {
            if (generation == autoGeneration) {
                currentDirection = Direction.NONE;
                sendCommand("AUTO STOP", CMD_STOP);
            }
        }, durationMs);
        return true;
    }

    private void scheduleAuto(Runnable runnable, long delayMs) {
        int generation = autoGeneration;
        handler.postDelayed(() -> {
            if (autoTuning && generation == autoGeneration) {
                runnable.run();
            }
        }, delayMs);
    }

    private void stopAutoTune(boolean sendStop) {
        autoTuning = false;
        autoGeneration++;
        currentDirection = Direction.NONE;
        if (autoText != null) {
            autoText.setText("Auto: idle");
        }
        if (sendStop && gatt != null && writeCharacteristic != null) {
            sendCommand("AUTO STOP", CMD_STOP);
        }
    }

    private String deviceLabel(BluetoothDevice device) {
        String name = hasConnectPermission() ? device.getName() : null;
        return ((name == null || name.length() == 0) ? "(no name)" : name) + " / " + device.getAddress();
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        permissions.add(Manifest.permission.RECORD_AUDIO);

        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && hasAudioPermission()) {
            startPitchListening();
        }
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private static class TunerDisplayView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private String note = "--";
        private double frequency;
        private double target;
        private double cents;
        private float level;
        private final short[] wave = new short[256];

        TunerDisplayView(Context context) {
            super(context);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        void setLevel(float value) {
            level = value;
            invalidate();
        }

        void setPitch(String note, double frequency, double target, double cents, float level, short[] buffer, int size) {
            this.note = note;
            this.frequency = frequency;
            this.target = target;
            this.cents = Math.max(-TUNER_DIAL_CENTS_RANGE, Math.min(TUNER_DIAL_CENTS_RANGE, cents));
            this.level = level;
            int step = Math.max(1, size / wave.length);
            for (int i = 0; i < wave.length; i++) {
                wave[i] = buffer[Math.min(size - 1, i * step)];
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            canvas.drawColor(Color.WHITE);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(32, 32, 32));
            canvas.drawRect(0, 0, w, dpLocal(44), paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dpLocal(22));
            canvas.drawText("MAX钢琴调律", dpLocal(18), dpLocal(30), paint);

            int y = dpLocal(56);
            paint.setColor(Color.rgb(160, 160, 160));
            canvas.drawRect(dpLocal(8), y, w - dpLocal(8), y + dpLocal(24), paint);
            paint.setColor(Color.rgb(0, 168, 30));
            canvas.drawRect(dpLocal(8), y, dpLocal(8) + (w - dpLocal(16)) * level, y + dpLocal(24), paint);

            drawScope(canvas, dpLocal(8), dpLocal(96), w - dpLocal(16), dpLocal(94), true);
            drawScope(canvas, dpLocal(8), dpLocal(212), w - dpLocal(16), dpLocal(94), false);
            drawNote(canvas, w, dpLocal(380));
            drawKeyboard(canvas, dpLocal(6), dpLocal(452), w - dpLocal(12), dpLocal(38));
            drawReadout(canvas, w, dpLocal(540));
            drawMeter(canvas, w, h - dpLocal(34));
        }

        private void drawScope(Canvas canvas, int x, int y, int width, int height, boolean waveform) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(10, 10, 10));
            canvas.drawRect(x, y, x + width, y + height, paint);
            paint.setColor(Color.rgb(32, 32, 32));
            paint.setStrokeWidth(1);
            for (int gx = x; gx < x + width; gx += dpLocal(8)) {
                canvas.drawLine(gx, y, gx, y + height, paint);
            }
            for (int gy = y; gy < y + height; gy += dpLocal(8)) {
                canvas.drawLine(x, gy, x + width, gy, paint);
            }

            if (waveform) {
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(dpLocal(3));
                float mid = y + height / 2f;
                float lastX = x;
                float lastY = mid;
                for (int i = 0; i < wave.length; i++) {
                    float px = x + width * i / (float) (wave.length - 1);
                    float py = mid - (wave[i] / 32768f) * height * 0.45f;
                    canvas.drawLine(lastX, lastY, px, py, paint);
                    lastX = px;
                    lastY = py;
                }
            } else {
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(dpLocal(2));
                float center = (float) (x + width / 2.0 + width * cents / 120.0);
                canvas.drawLine(center, y + dpLocal(4), center, y + height - dpLocal(4), paint);
                paint.setColor(Color.rgb(30, 30, 170));
                for (int i = -4; i <= 4; i++) {
                    float px = center + i * dpLocal(12);
                    canvas.drawLine(px, y + dpLocal(10), px, y + height - dpLocal(8), paint);
                }
            }
        }

        private void drawNote(Canvas canvas, int width, int baseline) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.BLACK);
            paint.setTextSize(dpLocal(78));
            canvas.drawText(note, width / 2f, baseline, paint);
            paint.setTextSize(dpLocal(20));
            paint.setColor(Color.rgb(0, 42, 210));
            canvas.drawText("自动", width / 2f, baseline + dpLocal(26), paint);
            paint.setTextSize(dpLocal(56));
            paint.setColor(Color.BLACK);
            canvas.drawText("‹", width * 0.22f, baseline - dpLocal(10), paint);
            canvas.drawText("›", width * 0.78f, baseline - dpLocal(10), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawKeyboard(Canvas canvas, int x, int y, int width, int height) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRect(x, y, x + width, y + height, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(1);
            int whites = 52;
            float keyW = width / (float) whites;
            for (int i = 0; i <= whites; i++) {
                float px = x + i * keyW;
                canvas.drawLine(px, y, px, y + height, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < whites; i++) {
                int mod = i % 7;
                if (mod != 2 && mod != 6) {
                    float px = x + (i + 0.68f) * keyW;
                    canvas.drawRect(px, y, px + keyW * 0.55f, y + height * 0.62f, paint);
                }
            }
        }

        private void drawReadout(Canvas canvas, int width, int baseline) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            paint.setTextSize(dpLocal(28));
            paint.setTextAlign(Paint.Align.LEFT);
            if (frequency <= 0 || target <= 0) {
                canvas.drawText("-- Hz", dpLocal(8), baseline, paint);
                paint.setTextSize(dpLocal(16));
                canvas.drawText("base: -- Hz", dpLocal(8), baseline + dpLocal(22), paint);
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setTextSize(dpLocal(28));
                canvas.drawText("-- ¢", width - dpLocal(8), baseline, paint);
                paint.setTextSize(dpLocal(24));
                canvas.drawText("-- Hz", width - dpLocal(8), baseline + dpLocal(30), paint);
                paint.setTextAlign(Paint.Align.LEFT);
                return;
            }
            canvas.drawText(String.format(Locale.US, "%.1fHz", frequency), dpLocal(8), baseline, paint);
            paint.setTextSize(dpLocal(16));
            canvas.drawText(String.format(Locale.US, "base: %.1fHz", target), dpLocal(8), baseline + dpLocal(22), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(dpLocal(28));
            canvas.drawText(String.format(Locale.US, "%+.1f¢", cents), width - dpLocal(8), baseline, paint);
            paint.setTextSize(dpLocal(24));
            canvas.drawText(String.format(Locale.US, "%+.1fHz", frequency - target), width - dpLocal(8), baseline + dpLocal(30), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawMeter(Canvas canvas, int width, int bottom) {
            int cx = width / 2;
            int radius = Math.min(width / 2 - dpLocal(28), dpLocal(190));
            int cy = bottom;
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpLocal(3));
            paint.setColor(Color.BLACK);
            canvas.drawArc(arc, 180, 180, false, paint);
            paint.setTextSize(dpLocal(18));
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            for (int value = -50; value <= 50; value += 10) {
                double angle = Math.toRadians(270 + value * 1.8);
                float x1 = (float) (cx + Math.cos(angle) * (radius - dpLocal(14)));
                float y1 = (float) (cy + Math.sin(angle) * (radius - dpLocal(14)));
                float x2 = (float) (cx + Math.cos(angle) * radius);
                float y2 = (float) (cy + Math.sin(angle) * radius);
                canvas.drawLine(x1, y1, x2, y2, paint);
                if (value % 20 == 0 || value == 0) {
                    float tx = (float) (cx + Math.cos(angle) * (radius - dpLocal(34)));
                    float ty = (float) (cy + Math.sin(angle) * (radius - dpLocal(34)));
                    canvas.drawText(String.valueOf(Math.abs(value)), tx, ty, paint);
                }
            }
            double needleAngle = Math.toRadians(270 + cents * 1.8);
            paint.setStrokeWidth(dpLocal(8));
            paint.setColor(Color.rgb(220, 20, 20));
            canvas.drawLine(cx, cy, (float) (cx + Math.cos(needleAngle) * (radius - dpLocal(26))),
                    (float) (cy + Math.sin(needleAngle) * (radius - dpLocal(26))), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            canvas.drawCircle(cx, cy, dpLocal(18), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private int dpLocal(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }

    private interface NoteSelectionListener {
        void onNoteSelected(int midi);
    }

    private static class PianoKeyPlayer {
        private static final int OUTPUT_RATE = 44100;
        private static final int MAX_PREVIEW_MS = 1700;
        private static final int FADE_OUT_MS = 80;
        private static final int[] SAMPLE_MIDI = {
                60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72
        };
        private static final int[] SAMPLE_RES = {
                R.raw.piano_c,
                R.raw.piano_cs,
                R.raw.piano_d,
                R.raw.piano_ds,
                R.raw.piano_e,
                R.raw.piano_f,
                R.raw.piano_fs,
                R.raw.piano_g,
                R.raw.piano_gs,
                R.raw.piano_a,
                R.raw.piano_as,
                R.raw.piano_b,
                R.raw.piano_c2
        };

        private final WavSample[] samples = new WavSample[SAMPLE_RES.length];
        private int playGeneration;

        PianoKeyPlayer(Context context) {
            for (int i = 0; i < SAMPLE_RES.length; i++) {
                samples[i] = loadWav(context, SAMPLE_RES[i]);
            }
        }

        void play(int midi) {
            WavSample sample = null;
            int sampleMidi = 60;
            int bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < samples.length; i++) {
                if (samples[i] == null) {
                    continue;
                }
                int distance = Math.abs(SAMPLE_MIDI[i] - (60 + Math.floorMod(midi, 12)));
                if (midi == 108 && SAMPLE_MIDI[i] == 72) {
                    distance = 0;
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    sample = samples[i];
                    sampleMidi = SAMPLE_MIDI[i];
                }
            }
            if (sample == null) {
                return;
            }
            int generation = ++playGeneration;
            WavSample selectedSample = sample;
            int selectedSampleMidi = sampleMidi;
            new Thread(() -> playOnThread(generation, selectedSample, selectedSampleMidi, midi),
                    "piano-key-preview").start();
        }

        void release() {
            playGeneration++;
        }

        private void playOnThread(int generation, WavSample sample, int sampleMidi, int targetMidi) {
            int minBuffer = AudioTrack.getMinBufferSize(
                    OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBuffer <= 0) {
                return;
            }
            AudioTrack track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, 4096),
                    AudioTrack.MODE_STREAM
            );
            short[] out = new short[1024];
            double pitchRatio = Math.pow(2.0, (targetMidi - sampleMidi) / 12.0);
            double step = pitchRatio * sample.sampleRate / OUTPUT_RATE;
            int maxFrames = OUTPUT_RATE * MAX_PREVIEW_MS / 1000;
            int fadeFrames = OUTPUT_RATE * FADE_OUT_MS / 1000;
            double pos = 0.0;
            int frame = 0;
            try {
                track.play();
                while (generation == playGeneration && pos < sample.values.length - 2 && frame < maxFrames) {
                    int count = 0;
                    while (count < out.length && pos < sample.values.length - 2 && frame < maxFrames) {
                        int index = (int) pos;
                        double frac = pos - index;
                        double value = sample.values[index] * (1.0 - frac) + sample.values[index + 1] * frac;
                        double envelope = 1.0;
                        int remaining = maxFrames - frame;
                        if (remaining < fadeFrames) {
                            envelope = Math.max(0.0, remaining / (double) fadeFrames);
                        }
                        out[count++] = (short) Math.max(Short.MIN_VALUE,
                                Math.min(Short.MAX_VALUE, value * envelope * 0.82));
                        pos += step;
                        frame++;
                    }
                    if (count > 0) {
                        track.write(out, 0, count);
                    }
                }
            } finally {
                track.pause();
                track.flush();
                track.release();
            }
        }

        private WavSample loadWav(Context context, int resId) {
            try (InputStream input = context.getResources().openRawResource(resId)) {
                byte[] data = readAllBytes(input);
                if (data.length < 44 || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') {
                    return null;
                }
                int channels = 1;
                int sampleRate = OUTPUT_RATE;
                int bitsPerSample = 16;
                int dataOffset = -1;
                int dataSize = 0;
                int offset = 12;
                while (offset + 8 <= data.length) {
                    String id = new String(data, offset, 4);
                    int size = littleInt(data, offset + 4);
                    int chunkData = offset + 8;
                    if ("fmt ".equals(id) && chunkData + 16 <= data.length) {
                        channels = littleShort(data, chunkData + 2);
                        sampleRate = littleInt(data, chunkData + 4);
                        bitsPerSample = littleShort(data, chunkData + 14);
                    } else if ("data".equals(id)) {
                        dataOffset = chunkData;
                        dataSize = Math.min(size, data.length - chunkData);
                        break;
                    }
                    offset = chunkData + size + (size & 1);
                }
                if (dataOffset < 0 || bitsPerSample != 16 || channels < 1) {
                    return null;
                }
                int frames = dataSize / (channels * 2);
                short[] values = new short[frames];
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int ch = 0; ch < channels; ch++) {
                        int pos = dataOffset + (i * channels + ch) * 2;
                        sum += (short) littleShort(data, pos);
                    }
                    values[i] = (short) (sum / channels);
                }
                return new WavSample(values, sampleRate);
            } catch (IOException | RuntimeException ex) {
                return null;
            }
        }

        private static byte[] readAllBytes(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }

        private static int littleShort(byte[] data, int offset) {
            return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
        }

        private static int littleInt(byte[] data, int offset) {
            return (data[offset] & 0xff)
                    | ((data[offset + 1] & 0xff) << 8)
                    | ((data[offset + 2] & 0xff) << 16)
                    | ((data[offset + 3] & 0xff) << 24);
        }

        private static class WavSample {
            final short[] values;
            final int sampleRate;

            WavSample(short[] values, int sampleRate) {
                this.values = values;
                this.sampleRate = sampleRate;
            }
        }
    }

    private static class SteampunkTunerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final RectF keyboardBounds = new RectF();
        private final RectF leftGroupBounds = new RectF();
        private final RectF rightGroupBounds = new RectF();
        private final RectF menuBounds = new RectF();
        private final RectF skinDst = new RectF();
        private final RectF selectedKeyBounds = new RectF();
        private final RectF overlayDst = new RectF();
        private final RectF keyboardImageDst = new RectF();
        private final Rect bitmapSrc = new Rect();
        private final short[] wave = new short[192];
        private final NoteSelectionListener selectionListener;
        private final Runnable menuListener;
        private static final float BLACK_TOP = 0f;
        private static final float BLACK_BOTTOM = 178f;
        private static final float MIDDLE_DIAL_RADIUS_RATIO = 318f / 390f;
        private static final float INNER_DIAL_RADIUS_RATIO = 269f / 390f;
        private static final float[] BLACK_LEFT = {
                56f, 169f, 237f, 343f, 421f, 487f, 604f, 672f, 778f, 856f, 914f, 1037f,
                1106f, 1220f, 1290f, 1356f, 1468f, 1529f, 1652f, 1713f, 1787f, 1902f,
                1971f, 2086f, 2156f, 2222f, 2334f, 2403f, 2517f, 2587f, 2653f, 2770f,
                2830f, 2953f, 3014f, 3088f
        };
        private static final float[] BLACK_RIGHT = {
                77f, 198f, 267f, 381f, 451f, 517f, 633f, 702f, 816f, 886f, 943f, 1067f,
                1136f, 1250f, 1320f, 1386f, 1498f, 1567f, 1681f, 1751f, 1817f, 1932f,
                2001f, 2116f, 2186f, 2252f, 2364f, 2433f, 2547f, 2617f, 2683f, 2799f,
                2868f, 2982f, 3052f, 3120f
        };
        private static final int[] BLACK_MIDI = {
                22, 25, 27, 30, 32, 34, 37, 39, 42, 44, 46, 49,
                51, 54, 56, 58, 61, 63, 66, 68, 70, 73, 75, 78,
                80, 82, 85, 87, 90, 92, 94, 97, 99, 102, 104, 106
        };
        private final Bitmap skinBitmap;
        private final Bitmap centerBitmap;
        private final Bitmap topKeyBitmap;
        private final Bitmap needleBitmap;
        private final Bitmap rotatingDialBitmap;
        private final Bitmap rotatingMiddleBitmap;
        private final Bitmap rotatingInnerBitmap;
        private final Bitmap fullKeyboardBitmap;
        private final Bitmap pressBlackBitmap;
        private final Bitmap pressWhiteBitmap;
        private final Bitmap pressWhiteLeftBitmap;
        private final Bitmap pressWhiteRightBitmap;
        private String note = "--";
        private double frequency;
        private double target;
        private double cents;
        private float displayCents;
        private float targetCents;
        private float level;
        private int selectedMidi = 60;
        private int groupStartMidi = 60;
        private float keyboardScrollPx;
        private float keyboardDragStartScrollPx;
        private float gearRotationDegrees;
        private float touchDownX;
        private float touchDownY;
        private boolean keyboardDragMoved;
        private boolean keyboardScrollInitialized;
        private long lastFrameMs;
        private long rangeAlarmStartedMs;
        private long rangeAlarmUntilMs;

        SteampunkTunerView(Context context, NoteSelectionListener selectionListener, Runnable menuListener) {
            super(context);
            this.selectionListener = selectionListener;
            this.menuListener = menuListener;
            skinBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.max_style_skin);
            centerBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.center_circle);
            topKeyBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.top_key);
            needleBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.needle_pointer);
            rotatingDialBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.rotating_dial);
            rotatingMiddleBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.dial_middle_q);
            rotatingInnerBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.dial_inner_q2);
            fullKeyboardBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.full_keyboard);
            pressBlackBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.key_black_pressed);
            pressWhiteBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.key_white_pressed);
            pressWhiteLeftBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.key_white_left_pressed);
            pressWhiteRightBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.key_white_right_pressed);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        void setSelectedMidi(int midi) {
            selectedMidi = Math.max(21, Math.min(108, midi));
            groupStartMidi = groupStartForMidi(selectedMidi);
            note = noteNameLocal(selectedMidi);
            cents = 0;
            targetCents = 0;
            displayCents = 0;
            invalidate();
        }

        void setLevel(float value) {
            level = value;
            invalidate();
        }

        void setPitch(String note, double frequency, double target, double cents, float level, short[] buffer, int size) {
            this.note = note;
            this.frequency = frequency;
            this.target = target;
            this.cents = Math.max(-TUNER_DIAL_CENTS_RANGE, Math.min(TUNER_DIAL_CENTS_RANGE, cents));
            targetCents = (float) this.cents;
            this.level = level;
            int step = Math.max(1, size / wave.length);
            for (int i = 0; i < wave.length; i++) {
                wave[i] = buffer[Math.min(size - 1, i * step)];
            }
            invalidate();
        }

        void setOutOfRangePitch(String note, double frequency, double target, double cents, float level, short[] buffer, int size) {
            this.note = note;
            this.frequency = frequency;
            this.target = target;
            this.cents = Math.max(-TUNER_DIAL_CENTS_RANGE, Math.min(TUNER_DIAL_CENTS_RANGE, cents));
            targetCents = cents < 0 ? (float) -TUNER_DIAL_CENTS_RANGE : (float) TUNER_DIAL_CENTS_RANGE;
            this.level = level;
            int step = Math.max(1, size / wave.length);
            for (int i = 0; i < wave.length; i++) {
                wave[i] = buffer[Math.min(size - 1, i * step)];
            }
            long now = System.currentTimeMillis();
            if (now > rangeAlarmUntilMs) {
                rangeAlarmStartedMs = now;
            }
            rangeAlarmUntilMs = now + 1400L;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            updateAnimationClock();
            int w = getWidth();
            int h = getHeight();
            if (skinBitmap != null) {
                drawSkinnedTuner(canvas, w, h);
                drawRangeAlarm(canvas, w, h);
                if (getWindowVisibility() == VISIBLE) {
                    postInvalidateOnAnimation();
                }
                return;
            }
            drawWood(canvas, w, h);
            drawHeader(canvas, w);
            int cx = w / 2;
            int cy = dpLocal(245);
            int r = Math.min(w / 2 - dpLocal(30), dpLocal(205));
            drawDial(canvas, cx, cy, r);
            drawOctaveKeyboard(canvas, dpLocal(18), dpLocal(500), w - dpLocal(36), dpLocal(332));
            drawRangeAlarm(canvas, w, h);
            if (getWindowVisibility() == VISIBLE) {
                postInvalidateOnAnimation();
            }
        }

        private void drawSkinnedTuner(Canvas canvas, int w, int h) {
            canvas.drawColor(Color.rgb(229, 198, 142));
            float skinAspect = skinBitmap.getWidth() / (float) skinBitmap.getHeight();
            float drawW = Math.min(w, h * skinAspect);
            float drawH = drawW / skinAspect;
            skinDst.set((w - drawW) / 2f, 0, (w + drawW) / 2f, drawH);
            canvas.drawBitmap(skinBitmap, null, skinDst, paint);

            float left = skinDst.left;
            float top = skinDst.top;
            float sw = skinDst.width();
            float sh = skinDst.height();
            float dialCx = left + sw * 0.50f;
            float dialCy = top + sh * 0.285f;
            menuBounds.set(left + sw * 0.035f, top + sh * 0.028f, left + sw * 0.145f, top + sh * 0.080f);
            leftGroupBounds.set(left + sw * 0.00f, top + sh * 0.574f, left + sw * 0.17f, top + sh * 0.636f);
            rightGroupBounds.set(left + sw * 0.83f, top + sh * 0.574f, left + sw * 1.00f, top + sh * 0.636f);
            keyboardBounds.set(left, top + sh * 0.636f, left + sw, top + sh * 0.965f);

            drawRotatingDialLayer(canvas, dialCx, dialCy, sw * 0.465f);
            drawSkinnedFullKeyboard(canvas);
            drawSkinnedKeyHighlight(canvas);
            drawSkinnedGroupLabel(canvas, left, top, sw, sh);
            drawSkinnedTopNeedle(canvas, dialCx, dialCy, sw * 0.58f);
            drawSkinnedTopKey(canvas, dialCx, dialCy, sw * 0.58f);
            drawSkinnedCenter(canvas, dialCx, dialCy + dpLocal(10), sw * 0.162f);
        }

        private void drawRotatingDialLayer(Canvas canvas, float cx, float cy, float radius) {
            paint.setAlpha(255);
            float layerCy = cy + dpLocal(7);
            float pitchRotation = -displayCents * TUNER_DIAL_DEGREES_PER_CENT;
            drawRotatingBitmap(canvas, rotatingDialBitmap, cx, layerCy, radius, pitchRotation);
            drawRotatingBitmap(canvas, rotatingMiddleBitmap, cx, layerCy, radius * MIDDLE_DIAL_RADIUS_RATIO,
                    -pitchRotation - gearRotationDegrees * 0.155f);
            drawRotatingBitmap(canvas, rotatingInnerBitmap, cx, layerCy, radius * INNER_DIAL_RADIUS_RATIO,
                    pitchRotation + gearRotationDegrees * 0.29f);
        }

        private void drawRotatingBitmap(Canvas canvas, Bitmap bitmap, float cx, float cy, float radius, float degrees) {
            if (bitmap == null) {
                return;
            }
            overlayDst.set(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.save();
            canvas.rotate(degrees, cx, cy);
            canvas.drawBitmap(bitmap, null, overlayDst, paint);
            canvas.restore();
        }

        private void drawSkinnedGroupLabel(Canvas canvas, float left, float top, float sw, float sh) {
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(sw * 0.050f);
            paint.setColor(Color.argb(110, 255, 244, 202));
            canvas.drawRoundRect(left + sw * 0.12f, top + sh * 0.578f, left + sw * 0.88f, top + sh * 0.626f,
                    sw * 0.018f, sw * 0.018f, paint);
            paint.setColor(Color.rgb(70, 43, 25));
            canvas.drawText("Octave Group: " + octaveGroupName(groupStartMidi), left + sw * 0.50f, top + sh * 0.612f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawSkinnedFullKeyboard(Canvas canvas) {
            if (fullKeyboardBitmap == null || keyboardBounds.width() <= 0) {
                return;
            }
            float scale = keyboardImageScale();
            float imageW = fullKeyboardBitmap.getWidth() * scale;
            if (!keyboardScrollInitialized) {
                keyboardScrollPx = clampKeyboardScroll(imageXForMidi(selectedMidi) * scale - keyboardBounds.width() * 0.45f);
                keyboardScrollInitialized = true;
            }
            keyboardImageDst.set(keyboardBounds.left - keyboardScrollPx, keyboardBounds.top,
                    keyboardBounds.left - keyboardScrollPx + imageW, keyboardBounds.bottom);
            paint.setAlpha(255);
            int save = canvas.save();
            canvas.clipRect(keyboardBounds);
            canvas.drawBitmap(fullKeyboardBitmap, null, keyboardImageDst, paint);
            canvas.restoreToCount(save);
        }

        private float keyboardImageScale() {
            if (fullKeyboardBitmap == null || keyboardBounds.height() <= 0) {
                return 1f;
            }
            return keyboardBounds.height() / fullKeyboardBitmap.getHeight();
        }

        private float keyboardMaxScroll() {
            if (fullKeyboardBitmap == null) {
                return 0f;
            }
            return Math.max(0f, fullKeyboardBitmap.getWidth() * keyboardImageScale() - keyboardBounds.width());
        }

        private float clampKeyboardScroll(float value) {
            return Math.max(0f, Math.min(keyboardMaxScroll(), value));
        }

        private float screenToKeyboardImageX(float screenX) {
            return (screenX - keyboardImageDst.left) / keyboardImageScale();
        }

        private float screenToKeyboardImageY(float screenY) {
            return (screenY - keyboardBounds.top) / keyboardImageScale();
        }

        private float imageXForMidi(int midi) {
            if (isBlackMidi(midi)) {
                int index = blackIndexForMidi(midi);
                return index >= 0 ? (BLACK_LEFT[index] + BLACK_RIGHT[index]) / 2f : 0f;
            }
            int whiteIndex = whiteIndexForMidi(midi);
            if (whiteIndex < 0) {
                return 0f;
            }
            float[] bounds = whiteBoundsForIndex(whiteIndex);
            return (bounds[0] + bounds[1]) / 2f;
        }

        private int blackIndexForMidi(int midi) {
            for (int i = 0; i < BLACK_MIDI.length; i++) {
                if (BLACK_MIDI[i] == midi) {
                    return i;
                }
            }
            return -1;
        }

        private boolean isBlackMidi(int midi) {
            int pc = Math.floorMod(midi, 12);
            return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
        }

        private int whiteIndexForMidi(int midi) {
            if (midi < 21 || midi > 108 || isBlackMidi(midi)) {
                return -1;
            }
            int index = 0;
            for (int m = 21; m <= 108; m++) {
                if (!isBlackMidi(m)) {
                    if (m == midi) {
                        return index;
                    }
                    index++;
                }
            }
            return -1;
        }

        private int midiForWhiteIndex(int index) {
            int count = 0;
            for (int m = 21; m <= 108; m++) {
                if (!isBlackMidi(m)) {
                    if (count == index) {
                        return m;
                    }
                    count++;
                }
            }
            return -1;
        }

        private float[] whiteBoundsForIndex(int whiteIndex) {
            int midi = midiForWhiteIndex(whiteIndex);
            float left = whiteIndex == 0 ? 0f : boundaryBetweenWhiteMidi(midiForWhiteIndex(whiteIndex - 1), midi);
            float right = whiteIndex >= 51 ? fullKeyboardBitmap.getWidth()
                    : boundaryBetweenWhiteMidi(midi, midiForWhiteIndex(whiteIndex + 1));
            return new float[]{left, right};
        }

        private float boundaryBetweenWhiteMidi(int leftMidi, int rightMidi) {
            if (isBlackMidi(leftMidi + 1)) {
                return blackCenterForMidi(leftMidi + 1);
            }
            float prev = blackCenterForMidi(leftMidi - 1);
            float next = blackCenterForMidi(rightMidi + 1);
            if (prev >= 0f && next >= 0f) {
                return (prev + next) / 2f;
            }
            if (prev >= 0f && fullKeyboardBitmap != null) {
                return prev + (fullKeyboardBitmap.getWidth() - prev) * 0.44f;
            }
            return fullKeyboardBitmap == null ? 0f : fullKeyboardBitmap.getWidth();
        }

        private float blackCenterForMidi(int midi) {
            int index = blackIndexForMidi(midi);
            return index >= 0 ? (BLACK_LEFT[index] + BLACK_RIGHT[index]) / 2f : -1f;
        }

        private void drawSkinnedKeyboardLabels(Canvas canvas) {
            if (keyboardBounds.width() <= 0) {
                return;
            }
            int[] whiteOffsets = {0, 2, 4, 5, 7, 9, 11};
            float whiteW = keyboardBounds.width() / 7f;
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(whiteW * 0.30f);
            for (int i = 0; i < whiteOffsets.length; i++) {
                int midi = groupStartMidi + whiteOffsets[i];
                paint.setColor(midi == selectedMidi ? Color.rgb(84, 54, 28) : Color.rgb(74, 50, 32));
                canvas.drawText(noteNameLocal(midi), keyboardBounds.left + whiteW * (i + 0.5f),
                        keyboardBounds.bottom - keyboardBounds.height() * 0.045f, paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawSkinnedMenuButton(Canvas canvas) {
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(235, 244, 222, 177));
            canvas.drawCircle(menuBounds.centerX(), menuBounds.centerY(), menuBounds.width() * 0.48f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, menuBounds.width() * 0.055f));
            paint.setColor(Color.rgb(94, 61, 34));
            float mx1 = menuBounds.left + menuBounds.width() * 0.24f;
            float mx2 = menuBounds.right - menuBounds.width() * 0.24f;
            canvas.drawLine(mx1, menuBounds.top + menuBounds.height() * 0.33f, mx2, menuBounds.top + menuBounds.height() * 0.33f, paint);
            canvas.drawLine(mx1, menuBounds.top + menuBounds.height() * 0.50f, mx2, menuBounds.top + menuBounds.height() * 0.50f, paint);
            canvas.drawLine(mx1, menuBounds.top + menuBounds.height() * 0.67f, mx2, menuBounds.top + menuBounds.height() * 0.67f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawSkinnedCenter(Canvas canvas, float cx, float cy, float radius) {
            paint.setAlpha(255);
            paint.setShader(null);
            if (centerBitmap != null) {
                overlayDst.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawBitmap(centerBitmap, null, overlayDst, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.rgb(238, 215, 159));
            paint.setTextSize(radius * 0.36f);
            canvas.drawText(String.format(Locale.US, "%+.1f", cents), cx, cy - radius * 0.18f, paint);
            paint.setTextSize(radius * 0.66f);
            canvas.drawText(noteNameLocal(selectedMidi), cx, cy + radius * 0.48f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawSkinnedTopNeedle(Canvas canvas, float cx, float cy, float radius) {
            if (needleBitmap != null) {
                paint.setAlpha(255);
                float width = radius * 0.14f;
                float height = width * needleBitmap.getHeight() / (float) needleBitmap.getWidth();
                float bottom = cy + radius * 0.02f - dpLocal(5);
                overlayDst.set(cx - width / 2f, bottom - height, cx + width / 2f, bottom);
                canvas.drawBitmap(needleBitmap, null, overlayDst, paint);
                return;
            }
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(4f, radius * 0.018f));
            paint.setColor(Color.argb(180, 255, 238, 164));
            canvas.drawLine(cx, cy - dpLocal(5), cx, cy - radius * 0.85f - dpLocal(5), paint);
            paint.setStrokeWidth(Math.max(2f, radius * 0.008f));
            paint.setColor(Color.rgb(95, 57, 27));
            canvas.drawLine(cx, cy - dpLocal(5), cx, cy - radius * 0.82f - dpLocal(5), paint);
        }

        private void drawSkinnedTopKey(Canvas canvas, float cx, float cy, float radius) {
            if (topKeyBitmap == null) {
                return;
            }
            paint.setAlpha(255);
            float width = radius * 0.30f;
            float height = width * topKeyBitmap.getHeight() / (float) topKeyBitmap.getWidth();
            float keyCenterY = cy - radius * 0.82f;
            overlayDst.set(cx - width / 2f, keyCenterY - height * 0.62f, cx + width / 2f, keyCenterY + height * 0.38f);
            canvas.drawBitmap(topKeyBitmap, null, overlayDst, paint);
        }

        private void drawSkinnedKeyHighlight(Canvas canvas) {
            if (fullKeyboardBitmap == null || keyboardBounds.width() <= 0) {
                return;
            }
            float scale = keyboardImageScale();
            if (isBlackMidi(selectedMidi)) {
                int index = blackIndexForMidi(selectedMidi);
                if (index < 0 || pressBlackBitmap == null) {
                    return;
                }
                float left = keyboardImageDst.left + BLACK_LEFT[index] * scale;
                float right = keyboardImageDst.left + BLACK_RIGHT[index] * scale;
                float top = keyboardBounds.top + BLACK_TOP * scale;
                float bottom = keyboardBounds.top + BLACK_BOTTOM * scale;
                overlayDst.set(left, top, right, bottom);
                drawPressedKeyBitmap(canvas, pressBlackBitmap, overlayDst, overlayDst);
                return;
            }
            int whiteIndex = whiteIndexForMidi(selectedMidi);
            if (whiteIndex < 0) {
                return;
            }
            Bitmap keyBitmap = selectedMidi == 21 ? pressWhiteLeftBitmap
                    : selectedMidi == 108 ? pressWhiteRightBitmap
                    : pressWhiteBitmap;
            if (keyBitmap == null) {
                return;
            }
            float[] bounds = whiteBoundsForIndex(whiteIndex);
            float left = keyboardImageDst.left + bounds[0] * scale;
            float right = keyboardImageDst.left + bounds[1] * scale;
            float keyWidth = Math.max(1f, right - left);
            float height = keyWidth * keyBitmap.getHeight() / (float) keyBitmap.getWidth();
            float bottom = keyboardBounds.bottom - scale * 54f;
            overlayDst.set(left, bottom - height, right, bottom);
            selectedKeyBounds.set(left, keyboardBounds.top, right, keyboardBounds.bottom);
            drawPressedKeyBitmap(canvas, keyBitmap, overlayDst, selectedKeyBounds);
        }

        private void drawPressedKeyBitmap(Canvas canvas, Bitmap bitmap, RectF dst, RectF clip) {
            if (!RectF.intersects(dst, keyboardBounds)) {
                return;
            }
            paint.setAlpha(255);
            int save = canvas.save();
            canvas.clipRect(clip);
            canvas.clipRect(keyboardBounds);
            canvas.drawBitmap(bitmap, null, dst, paint);
            canvas.restoreToCount(save);
        }

        private int whiteIndexForOffset(int offset) {
            switch (offset) {
                case 0:
                    return 0;
                case 2:
                    return 1;
                case 4:
                    return 2;
                case 5:
                    return 3;
                case 7:
                    return 4;
                case 9:
                    return 5;
                case 11:
                    return 6;
                default:
                    return -1;
            }
        }

        private void updateAnimationClock() {
            long now = System.currentTimeMillis();
            if (lastFrameMs == 0) {
                lastFrameMs = now;
                return;
            }
            long delta = Math.min(80, Math.max(0, now - lastFrameMs));
            lastFrameMs = now;
            gearRotationDegrees = (gearRotationDegrees + delta * 0.045f) % 360f;
            displayCents += (targetCents - displayCents) * Math.min(1f, delta / 260f);
        }

        private void drawRangeAlarm(Canvas canvas, int w, int h) {
            long now = System.currentTimeMillis();
            if (now > rangeAlarmUntilMs || rangeAlarmStartedMs == 0) {
                return;
            }
            float elapsed = Math.max(0f, now - rangeAlarmStartedMs);
            float heartbeat = 0.45f + 0.55f * (float) Math.pow(Math.abs(Math.sin(elapsed / 135f)), 1.7);
            float fadeOut = Math.min(1f, Math.max(0f, (rangeAlarmUntilMs - now) / 380f));
            int alpha = Math.min(230, Math.max(0, Math.round(210f * heartbeat * fadeOut)));
            int edge = Color.argb(alpha, 255, 0, 0);
            int clear = Color.argb(0, 255, 0, 0);
            float band = Math.min(dpLocal(100), Math.min(w, h) * 0.28f);

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, band, 0, edge, clear, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, band, h, paint);
            paint.setShader(new LinearGradient(w, 0, w - band, 0, edge, clear, Shader.TileMode.CLAMP));
            canvas.drawRect(w - band, 0, w, h, paint);
            paint.setShader(new LinearGradient(0, 0, 0, band, edge, clear, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, band, paint);
            paint.setShader(new LinearGradient(0, h, 0, h - band, edge, clear, Shader.TileMode.CLAMP));
            canvas.drawRect(0, h - band, w, h, paint);
            paint.setShader(null);
            paint.setAlpha(255);
            if (now <= rangeAlarmUntilMs) {
                postInvalidateDelayed(33);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX();
                touchDownY = event.getY();
                keyboardDragStartScrollPx = keyboardScrollPx;
                keyboardDragMoved = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (keyboardBounds.contains(touchDownX, touchDownY)) {
                    float dx = event.getX() - touchDownX;
                    keyboardScrollPx = clampKeyboardScroll(keyboardDragStartScrollPx - dx);
                    if (Math.abs(dx) > dpLocal(4)) {
                        keyboardDragMoved = true;
                    }
                    invalidate();
                }
                return true;
            }
            if (event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                return true;
            }
            float x = event.getX();
            float y = event.getY();
            if (!keyboardDragMoved && menuBounds.contains(x, y)) {
                menuListener.run();
                return true;
            }
            if (keyboardDragMoved) {
                return true;
            }
            if (keyboardBounds.contains(x, y)) {
                int midi = midiAtKeyboardPosition(x, y);
                if (midi > 0) {
                    selectedMidi = midi;
                    selectionListener.onNoteSelected(midi);
                    invalidate();
                }
                return true;
            }
            return true;
        }

        private void changeGroup(int deltaGroups) {
            int group = Math.round((groupStartMidi - 24) / 12f);
            int next = Math.max(0, Math.min(6, group + deltaGroups));
            if (next == group) {
                return;
            }
            groupStartMidi = 24 + next * 12;
            selectedMidi = groupStartMidi;
            selectionListener.onNoteSelected(selectedMidi);
            invalidate();
        }

        private void drawWood(Canvas canvas, int w, int h) {
            canvas.drawColor(Color.rgb(229, 198, 142));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpLocal(1));
            for (int y = 0; y < h; y += dpLocal(18)) {
                int tone = 150 + (y / Math.max(1, dpLocal(18))) % 45;
                paint.setColor(Color.argb(55, tone, 101, 48));
                canvas.drawLine(0, y, w, y + dpLocal(10), paint);
            }
            for (int x = dpLocal(24); x < w; x += dpLocal(82)) {
                paint.setColor(Color.argb(35, 120, 80, 38));
                canvas.drawLine(x, 0, x + dpLocal(12), h, paint);
            }
        }

        private void drawHeader(Canvas canvas, int w) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(90, 65, 39, 22));
            canvas.drawRoundRect(dpLocal(18), dpLocal(12), w - dpLocal(18), dpLocal(62), dpLocal(18), dpLocal(18), paint);
            menuBounds.set(dpLocal(24), dpLocal(18), dpLocal(62), dpLocal(56));
            paint.setColor(Color.argb(230, 255, 247, 222));
            canvas.drawCircle(menuBounds.centerX(), menuBounds.centerY(), dpLocal(18), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpLocal(3));
            paint.setColor(Color.rgb(82, 52, 31));
            float mx1 = menuBounds.left + dpLocal(10);
            float mx2 = menuBounds.right - dpLocal(10);
            canvas.drawLine(mx1, menuBounds.top + dpLocal(12), mx2, menuBounds.top + dpLocal(12), paint);
            canvas.drawLine(mx1, menuBounds.top + dpLocal(19), mx2, menuBounds.top + dpLocal(19), paint);
            canvas.drawLine(mx1, menuBounds.top + dpLocal(26), mx2, menuBounds.top + dpLocal(26), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dpLocal(20));
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("MAX\u94a2\u7434\u8c03\u5f8b", dpLocal(76), dpLocal(45), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(dpLocal(13));
            canvas.drawText("A=440.0", w - dpLocal(42), dpLocal(43), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawDial(Canvas canvas, int cx, int cy, int r) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(92, 48, 30));
            canvas.drawCircle(cx, cy, r + dpLocal(18), paint);
            paint.setColor(Color.rgb(181, 121, 65));
            canvas.drawCircle(cx, cy, r + dpLocal(10), paint);
            paint.setColor(Color.rgb(245, 214, 128));
            canvas.drawCircle(cx, cy, r, paint);
            canvas.save();
            canvas.rotate(-displayCents * 1.8f, cx, cy);
            drawColorRing(canvas, cx, cy, r);
            drawTicks(canvas, cx, cy, r);
            drawGears(canvas, cx, cy, r);
            canvas.restore();
            drawNeedle(canvas, cx, cy, r);
            drawCenter(canvas, cx, cy);
        }

        private void drawColorRing(Canvas canvas, int cx, int cy, int r) {
            int[] colors = {
                    Color.rgb(219, 85, 76), Color.rgb(238, 142, 72), Color.rgb(240, 201, 72),
                    Color.rgb(116, 189, 94), Color.rgb(77, 177, 174), Color.rgb(89, 144, 214),
                    Color.rgb(142, 103, 202), Color.rgb(221, 103, 154)
            };
            oval.set(cx - r + dpLocal(24), cy - r + dpLocal(24), cx + r - dpLocal(24), cy + r - dpLocal(24));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpLocal(42));
            for (int i = 0; i < 32; i++) {
                paint.setColor(colors[i % colors.length]);
                canvas.drawArc(oval, -90 + i * 11.25f, 10.5f, false, paint);
            }
        }

        private void drawTicks(Canvas canvas, int cx, int cy, int r) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.rgb(48, 35, 23));
            for (int value = -50; value <= 50; value += 5) {
                double angle = angleForCents(value);
                int len = value % 10 == 0 ? dpLocal(18) : dpLocal(10);
                float x1 = (float) (cx + Math.cos(angle) * (r - dpLocal(30)));
                float y1 = (float) (cy + Math.sin(angle) * (r - dpLocal(30)));
                float x2 = (float) (cx + Math.cos(angle) * (r - dpLocal(30) - len));
                float y2 = (float) (cy + Math.sin(angle) * (r - dpLocal(30) - len));
                paint.setStrokeWidth(value % 10 == 0 ? dpLocal(2) : dpLocal(1));
                canvas.drawLine(x1, y1, x2, y2, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dpLocal(11));
            for (int value = -50; value <= 50; value += 10) {
                double angle = angleForCents(value);
                float tx = (float) (cx + Math.cos(angle) * (r - dpLocal(66)));
                float ty = (float) (cy + Math.sin(angle) * (r - dpLocal(66))) + dpLocal(4);
                canvas.drawText(value == 0 ? "0" : (value > 0 ? "+" + value : String.valueOf(value)), tx, ty, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(247, 223, 135));
            float topX = cx;
            float topY = cy - r + dpLocal(22);
            android.graphics.Path marker = new android.graphics.Path();
            marker.moveTo(topX, topY + dpLocal(18));
            marker.lineTo(topX - dpLocal(10), topY);
            marker.lineTo(topX + dpLocal(10), topY);
            marker.close();
            canvas.drawPath(marker, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawGears(Canvas canvas, int cx, int cy, int r) {
            int[] colors = {Color.rgb(70, 156, 217), Color.rgb(239, 110, 86), Color.rgb(99, 194, 113),
                    Color.rgb(222, 175, 57), Color.rgb(150, 101, 206)};
            for (int i = 0; i < 18; i++) {
                double angle = Math.toRadians(i * 20);
                float gx = (float) (cx + Math.cos(angle) * (r * 0.45));
                float gy = (float) (cy + Math.sin(angle) * (r * 0.45));
                float phase = gearRotationDegrees * (i % 2 == 0 ? 1f : -1.25f) + i * 9f;
                drawGear(canvas, gx, gy, dpLocal(10 + (i % 3) * 2), colors[i % colors.length], phase);
            }
        }

        private void drawGear(Canvas canvas, float gx, float gy, int radius, int color, float phase) {
            canvas.save();
            canvas.rotate(phase, gx, gy);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpLocal(3));
            paint.setColor(color);
            for (int tooth = 0; tooth < 10; tooth++) {
                double a = Math.toRadians(tooth * 36);
                canvas.drawLine(
                        (float) (gx + Math.cos(a) * radius * 0.82f),
                        (float) (gy + Math.sin(a) * radius * 0.82f),
                        (float) (gx + Math.cos(a) * radius * 1.30f),
                        (float) (gy + Math.sin(a) * radius * 1.30f),
                        paint
                );
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(gx, gy, radius, paint);
            paint.setColor(Color.rgb(70, 48, 34));
            canvas.drawCircle(gx, gy, Math.max(dpLocal(3), radius / 3f), paint);
            canvas.restore();
        }

        private void drawNeedle(Canvas canvas, int cx, int cy, int r) {
            double angle = angleForCents(0);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpLocal(8));
            paint.setColor(Color.rgb(255, 236, 163));
            canvas.drawLine(cx, cy, (float) (cx + Math.cos(angle) * (r - dpLocal(28))),
                    (float) (cy + Math.sin(angle) * (r - dpLocal(28))), paint);
            paint.setStrokeWidth(dpLocal(2));
            paint.setColor(Color.rgb(91, 60, 34));
            canvas.drawLine(cx, cy, (float) (cx + Math.cos(angle) * (r - dpLocal(22))),
                    (float) (cy + Math.sin(angle) * (r - dpLocal(22))), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(241, 206, 104));
            canvas.drawCircle(cx, cy - r + dpLocal(16), dpLocal(10), paint);
        }

        private double angleForCents(double value) {
            return Math.toRadians(-90 + value * 1.8);
        }

        private void drawCenter(Canvas canvas, int cx, int cy) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(50, 45, 37));
            canvas.drawCircle(cx, cy, dpLocal(66), paint);
            paint.setColor(Color.rgb(232, 208, 155));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dpLocal(24));
            canvas.drawText(String.format(Locale.US, "%+.1f", cents), cx, cy - dpLocal(16), paint);
            paint.setTextSize(dpLocal(42));
            canvas.drawText(note, cx, cy + dpLocal(32), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawControls(Canvas canvas, int w, int top) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(92, 50, 31));
            canvas.drawCircle(dpLocal(58), top + dpLocal(34), dpLocal(12), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpLocal(4));
            canvas.drawLine(dpLocal(58), top + dpLocal(46), dpLocal(58), top + dpLocal(94), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dpLocal(18));
            paint.setColor(Color.rgb(42, 32, 22));
            canvas.drawText("AUTO", dpLocal(102), top + dpLocal(30), paint);
            canvas.drawText("STEP", dpLocal(102), top + dpLocal(60), paint);
            canvas.drawText("LOCK", dpLocal(102), top + dpLocal(90), paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dpLocal(46));
            paint.setColor(Color.rgb(92, 68, 40));
            canvas.drawText("\u2699", w * 0.50f, top + dpLocal(70), paint);
            paint.setTextSize(dpLocal(34));
            canvas.drawText("\u266B", w * 0.73f, top + dpLocal(70), paint);
            canvas.drawText("\u266A", w * 0.86f, top + dpLocal(70), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawCurvePanel(Canvas canvas, int x, int y, int width, int height) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(205, 255, 247, 222));
            canvas.drawRoundRect(x, y, x + width, y + height, dpLocal(10), dpLocal(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpLocal(2));
            paint.setColor(Color.rgb(130, 78, 42));
            float lastX = x + dpLocal(12);
            float lastY = y + height - dpLocal(20);
            for (int i = 0; i < 72; i++) {
                float px = x + dpLocal(12) + i * (width - dpLocal(24)) / 71f;
                float py = (float) (y + height - dpLocal(18) - Math.log1p(i) / Math.log(72) * height * 0.58);
                canvas.drawLine(lastX, lastY, px, py, paint);
                lastX = px;
                lastY = py;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dpLocal(22));
            paint.setColor(Color.rgb(128, 72, 43));
            canvas.drawText("\u266A", x + width * 0.28f, y + dpLocal(38), paint);
        }

        private void drawOctaveKeyboard(Canvas canvas, int x, int y, int width, int height) {
            int labelH = dpLocal(36);
            int keyTop = y + labelH;
            int keyH = height - labelH - dpLocal(8);
            keyboardBounds.set(x, keyTop, x + width, keyTop + keyH);
            leftGroupBounds.set(x, y, x + dpLocal(44), y + labelH);
            rightGroupBounds.set(x + width - dpLocal(44), y, x + width, y + labelH);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(190, 239, 204, 148));
            canvas.drawRect(x, y, x + width, y + height, paint);
            paint.setColor(Color.rgb(91, 54, 31));
            paint.setTextSize(dpLocal(17));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("<", x + dpLocal(22), y + dpLocal(25), paint);
            canvas.drawText(">", x + width - dpLocal(22), y + dpLocal(25), paint);
            canvas.drawText("Octave Group: " + octaveGroupName(groupStartMidi), x + width / 2f, y + dpLocal(25), paint);

            int[] whiteOffsets = {0, 2, 4, 5, 7, 9, 11};
            String[] whiteNames = {"C", "D", "E", "F", "G", "A", "B"};
            float whiteW = width / 7f;
            for (int i = 0; i < 7; i++) {
                int midi = groupStartMidi + whiteOffsets[i];
                float left = x + i * whiteW;
                boolean selected = midi == selectedMidi;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(selected ? Color.rgb(255, 224, 118) : Color.rgb(246, 238, 213));
                canvas.drawRoundRect(left + dpLocal(1), keyTop, left + whiteW - dpLocal(1), keyTop + keyH,
                        dpLocal(6), dpLocal(6), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpLocal(1));
                paint.setColor(Color.rgb(70, 48, 34));
                canvas.drawRoundRect(left + dpLocal(1), keyTop, left + whiteW - dpLocal(1), keyTop + keyH,
                        dpLocal(6), dpLocal(6), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(dpLocal(17));
                paint.setColor(Color.rgb(60, 40, 24));
                canvas.drawText(whiteNames[i] + octaveForMidi(midi), left + whiteW / 2f, keyTop + keyH - dpLocal(18), paint);
            }

            int[] blackAfterWhite = {0, 1, 3, 4, 5};
            int[] blackOffsets = {1, 3, 6, 8, 10};
            for (int i = 0; i < blackOffsets.length; i++) {
                int midi = groupStartMidi + blackOffsets[i];
                float bw = whiteW * 0.58f;
                float bh = keyH * 0.62f;
                float left = x + (blackAfterWhite[i] + 1) * whiteW - bw / 2f;
                boolean selected = midi == selectedMidi;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(selected ? Color.rgb(255, 210, 91) : Color.rgb(45, 38, 31));
                canvas.drawRoundRect(left, keyTop, left + bw, keyTop + bh, dpLocal(5), dpLocal(5), paint);
                paint.setColor(Color.argb(90, 255, 255, 255));
                canvas.drawRect(left + bw * 0.58f, keyTop + dpLocal(8), left + bw * 0.72f, keyTop + bh - dpLocal(10), paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private int midiAtKeyboardPosition(float px, float py) {
            if (fullKeyboardBitmap == null || keyboardBounds.width() <= 0) {
                return -1;
            }
            float imageX = screenToKeyboardImageX(px);
            float imageY = screenToKeyboardImageY(py);
            if (imageX < 0 || imageX > fullKeyboardBitmap.getWidth()) {
                return -1;
            }
            if (imageY >= BLACK_TOP && imageY <= BLACK_BOTTOM) {
                for (int i = 0; i < BLACK_MIDI.length; i++) {
                    if (imageX >= BLACK_LEFT[i] && imageX <= BLACK_RIGHT[i]) {
                        return BLACK_MIDI[i];
                    }
                }
            }
            for (int i = 0; i < 52; i++) {
                float[] bounds = whiteBoundsForIndex(i);
                if (imageX >= bounds[0] && imageX <= bounds[1]) {
                    return midiForWhiteIndex(i);
                }
            }
            return -1;
        }

        private int groupStartForMidi(int midi) {
            int start = midi - Math.floorMod(midi, 12);
            return Math.max(24, Math.min(96, start));
        }

        private String octaveGroupName(int startMidi) {
            String start = noteNameLocal(startMidi);
            if (startMidi == 60) {
                return start + " - Middle C";
            }
            return start + " - " + noteNameLocal(startMidi + 11);
        }

        private String noteNameLocal(int midi) {
            String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
            return names[Math.floorMod(midi, 12)] + octaveForMidi(midi);
        }

        private int octaveForMidi(int midi) {
            return midi / 12 - 1;
        }

        private int dpLocal(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}

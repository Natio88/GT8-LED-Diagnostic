package com.example.gt8leddiag;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 1001;
    private CameraManager cameraManager;
    private LinearLayout cameraList;
    private TextView reportView;
    private final StringBuilder report = new StringBuilder();
    private final Map<String, Boolean> torchState = new HashMap<>();
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager.TorchCallback torchCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildUi();
        startCameraThread();
        registerTorchCallback();
        requestCameraPermissionIfNeeded();
        scanCameras();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("GT8 LED Diagnostic");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView intro = new TextView(this);
        intro.setText("Tests every flash unit Android exposes. Watch the rear module while using TORCH and SINGLE FLASH. If two physical LEDs are grouped as one flash unit by Realme, Android cannot select them independently.");
        intro.setTextSize(15);
        intro.setPadding(0, dp(8), 0, dp(12));
        root.addView(intro, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button rescan = button("Rescan");
        rescan.setOnClickListener(v -> scanCameras());
        actions.addView(rescan, weightWrap());
        Button allOff = button("All OFF");
        allOff.setOnClickListener(v -> allTorchesOff());
        actions.addView(allOff, weightWrap());
        root.addView(actions, matchWrap());

        cameraList = new LinearLayout(this);
        cameraList.setOrientation(LinearLayout.VERTICAL);
        root.addView(cameraList, matchWrap());

        TextView reportLabel = new TextView(this);
        reportLabel.setText("Diagnostic report");
        reportLabel.setTextSize(20);
        reportLabel.setPadding(0, dp(18), 0, dp(4));
        root.addView(reportLabel, matchWrap());

        reportView = new TextView(this);
        reportView.setTextSize(12);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(reportView, matchWrap());

        Button copy = button("Copy report");
        copy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("GT8 LED Diagnostic", reportView.getText()));
            toast("Report copied");
        });
        root.addView(copy, matchWrap());

        setContentView(scroll);
    }

    private void requestCameraPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION) scanCameras();
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("LedDiagCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void registerTorchCallback() {
        torchCallback = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String cameraId, boolean enabled) {
                torchState.put(cameraId, enabled);
                appendEvent("Torch callback: camera " + cameraId + " -> " + (enabled ? "ON" : "OFF"));
            }

            @Override
            public void onTorchModeUnavailable(String cameraId) {
                appendEvent("Torch callback: camera " + cameraId + " unavailable");
            }

            @Override
            public void onTorchStrengthLevelChanged(String cameraId, int newStrengthLevel) {
                if (Build.VERSION.SDK_INT >= 33) {
                    appendEvent("Torch strength callback: camera " + cameraId + " -> " + newStrengthLevel);
                }
            }
        };
        cameraManager.registerTorchCallback(torchCallback, cameraHandler);
    }

    private void scanCameras() {
        cameraList.removeAllViews();
        report.setLength(0);
        report.append("GT8 LED Diagnostic v1.0\n")
              .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
              .append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
              .append("Camera permission: ").append(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "granted" : "not granted").append("\n\n");

        try {
            String[] ids = cameraManager.getCameraIdList();
            report.append("Public camera IDs: ").append(Arrays.toString(ids)).append("\n");
            for (String id : ids) addCameraCard(id);
            reportView.setText(report.toString());
        } catch (Exception e) {
            addError("Camera scan failed", e);
        }
    }

    private void addCameraCard(String id) throws CameraAccessException {
        CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
        Boolean hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        Set<String> physicalIds = Collections.emptySet();
        if (Build.VERSION.SDK_INT >= 28) physicalIds = c.getPhysicalCameraIds();

        Integer maxStrength = null;
        Integer defaultStrength = null;
        if (Build.VERSION.SDK_INT >= 33) {
            maxStrength = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            defaultStrength = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL);
        }

        String facingText = facingName(facing);
        String focalText = focals == null ? "unknown" : Arrays.toString(focals) + " mm";
        boolean logical = Build.VERSION.SDK_INT >= 28 && contains(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);

        report.append("\nCamera ").append(id).append("\n")
              .append("  Facing: ").append(facingText).append("\n")
              .append("  Flash available: ").append(hasFlash).append("\n")
              .append("  Focal lengths: ").append(focalText).append("\n")
              .append("  Logical multi-camera: ").append(logical).append("\n")
              .append("  Physical IDs: ").append(physicalIds).append("\n");
        if (Build.VERSION.SDK_INT >= 33) {
            report.append("  Torch strength max/default: ").append(maxStrength).append('/').append(defaultStrength).append("\n");
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cp = matchWrap();
        cp.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(cp);
        card.setBackgroundColor(0xffeeeeee);

        TextView header = new TextView(this);
        header.setText("Camera " + id + " — " + facingText);
        header.setTextSize(19);
        card.addView(header, matchWrap());

        TextView info = new TextView(this);
        info.setText("Flash: " + hasFlash + "\nFocal: " + focalText + "\nPhysical IDs: " + physicalIds +
                (Build.VERSION.SDK_INT >= 33 ? "\nTorch strength max/default: " + maxStrength + "/" + defaultStrength : ""));
        info.setTextSize(13);
        info.setPadding(0, dp(4), 0, dp(8));
        card.addView(info, matchWrap());

        if (Boolean.TRUE.equals(hasFlash)) {
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            Button on = button("Torch ON");
            on.setOnClickListener(v -> setTorch(id, true));
            buttons.addView(on, weightWrap());
            Button off = button("Torch OFF");
            off.setOnClickListener(v -> setTorch(id, false));
            buttons.addView(off, weightWrap());
            card.addView(buttons, matchWrap());

            if (Build.VERSION.SDK_INT >= 33 && maxStrength != null && maxStrength > 1) {
                TextView strengthLabel = new TextView(this);
                strengthLabel.setText("Torch strength: " + defaultStrength + " / " + maxStrength);
                card.addView(strengthLabel, matchWrap());
                SeekBar seek = new SeekBar(this);
                seek.setMax(maxStrength - 1);
                seek.setProgress(Math.max(0, (defaultStrength == null ? 1 : defaultStrength) - 1));
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        strengthLabel.setText("Torch strength: " + (p + 1) + " / " + (s.getMax() + 1));
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {
                        setTorchStrength(id, s.getProgress() + 1);
                    }
                });
                card.addView(seek, matchWrap());
            }

            Button single = button("Fire SINGLE camera flash");
            single.setOnClickListener(v -> fireSingleFlash(id));
            card.addView(single, matchWrap());

            TextView watch = new TextView(this);
            watch.setText("Tip: record the rear module with another phone in slow-motion while pressing the buttons. Compare TORCH vs SINGLE camera flash.");
            watch.setTextSize(12);
            watch.setPadding(0, dp(6), 0, 0);
            card.addView(watch, matchWrap());
        }

        cameraList.addView(card);
    }

    private void setTorch(String id, boolean enabled) {
        try {
            cameraManager.setTorchMode(id, enabled);
            appendEvent("Requested camera " + id + " torch " + (enabled ? "ON" : "OFF"));
            toast("Camera " + id + " torch " + (enabled ? "ON" : "OFF"));
        } catch (Exception e) {
            addError("Torch request failed for camera " + id, e);
        }
    }

    private void setTorchStrength(String id, int strength) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            cameraManager.turnOnTorchWithStrengthLevel(id, strength);
            appendEvent("Requested camera " + id + " torch strength " + strength);
        } catch (Exception e) {
            addError("Torch strength failed for camera " + id, e);
        }
    }

    private void allTorchesOff() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    try { cameraManager.setTorchMode(id, false); } catch (Exception ignored) {}
                }
            }
            appendEvent("All exposed torches requested OFF");
        } catch (Exception e) {
            addError("All OFF failed", e);
        }
    }

    private void fireSingleFlash(String id) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissionIfNeeded();
            toast("Camera permission is required for SINGLE flash test");
            return;
        }
        allTorchesOff();
        appendEvent("Opening camera " + id + " for SINGLE flash capture");
        try {
            cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    doSingleFlashCapture(camera);
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    appendEvent("Camera " + id + " disconnected");
                    camera.close();
                }
                @Override public void onError(CameraDevice camera, int error) {
                    appendEvent("Camera " + id + " open error: " + error);
                    camera.close();
                }
            }, cameraHandler);
        } catch (Exception e) {
            addError("Could not open camera " + id, e);
        }
    }

    private void doSingleFlashCapture(CameraDevice camera) {
        ImageReader reader = null;
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(camera.getId());
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
            Size chosen = chooseSmallSize(sizes);
            reader = ImageReader.newInstance(chosen.getWidth(), chosen.getHeight(), ImageFormat.JPEG, 2);
            final ImageReader finalReader = reader;
            reader.setOnImageAvailableListener(r -> {
                android.media.Image image = null;
                try { image = r.acquireLatestImage(); }
                finally { if (image != null) image.close(); }
            }, cameraHandler);

            List<android.view.Surface> outputs = Collections.singletonList(reader.getSurface());
            ImageReader readerForClose = reader;
            camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    try {
                        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        b.addTarget(finalReader.getSurface());
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
                        session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
                                Integer state = result.get(android.hardware.camera2.CaptureResult.FLASH_STATE);
                                appendEvent("SINGLE flash capture completed on camera " + camera.getId() + "; FLASH_STATE=" + flashStateName(state));
                                toastUi("SINGLE flash fired; reported state: " + flashStateName(state));
                                closeSessionLater(s, camera, readerForClose);
                            }

                            @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, android.hardware.camera2.CaptureFailure failure) {
                                appendEvent("SINGLE flash capture failed on camera " + camera.getId() + "; reason=" + failure.getReason());
                                closeSessionLater(s, camera, readerForClose);
                            }
                        }, cameraHandler);
                    } catch (Exception e) {
                        addError("SINGLE flash capture request failed", e);
                        try { session.close(); } catch (Exception ignored) {}
                        try { camera.close(); } catch (Exception ignored) {}
                        try { readerForClose.close(); } catch (Exception ignored) {}
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    appendEvent("Capture session configuration failed on camera " + camera.getId());
                    try { session.close(); } catch (Exception ignored) {}
                    try { camera.close(); } catch (Exception ignored) {}
                    try { readerForClose.close(); } catch (Exception ignored) {}
                }
            }, cameraHandler);
        } catch (Exception e) {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            try { camera.close(); } catch (Exception ignored) {}
            addError("SINGLE flash setup failed", e);
        }
    }

    private void closeSessionLater(CameraCaptureSession session, CameraDevice camera, ImageReader reader) {
        cameraHandler.postDelayed(() -> {
            try { session.close(); } catch (Exception ignored) {}
            try { camera.close(); } catch (Exception ignored) {}
            try { reader.close(); } catch (Exception ignored) {}
        }, 300);
    }

    private Size chooseSmallSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        List<Size> list = new ArrayList<>(Arrays.asList(sizes));
        list.sort(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
        for (Size s : list) if (s.getWidth() >= 640 && s.getHeight() >= 480) return s;
        return list.get(0);
    }

    private String flashStateName(Integer state) {
        if (state == null) return "unknown/null";
        switch (state) {
            case 0: return "UNAVAILABLE";
            case 1: return "CHARGING";
            case 2: return "READY";
            case 3: return "FIRED";
            case 4: return "PARTIAL";
            default: return String.valueOf(state);
        }
    }

    private String facingName(Integer facing) {
        if (facing == null) return "unknown";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "FRONT";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "BACK";
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "EXTERNAL";
        return String.valueOf(facing);
    }

    private boolean contains(int[] values, int target) {
        if (values == null) return false;
        for (int v : values) if (v == target) return true;
        return false;
    }

    private void appendEvent(String text) {
        synchronized (report) {
            report.append("EVENT: ").append(text).append("\n");
        }
        runOnUiThread(() -> reportView.setText(report.toString()));
    }

    private void addError(String prefix, Exception e) {
        String msg = prefix + ": " + e.getClass().getSimpleName() + " — " + e.getMessage();
        appendEvent(msg);
        toastUi(msg);
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    private void toastUi(String msg) { runOnUiThread(() -> toast(msg)); }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        allTorchesOff();
        if (torchCallback != null) {
            try { cameraManager.unregisterTorchCallback(torchCallback); } catch (Exception ignored) {}
        }
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }
}

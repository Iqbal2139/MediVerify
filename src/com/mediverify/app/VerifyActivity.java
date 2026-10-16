package com.mediverify.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.FaceDetector;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mediverify.app.data.Db;
import com.mediverify.app.data.DoseLog;
import com.mediverify.app.data.Med;
import com.mediverify.app.data.Prefs;
import com.mediverify.app.data.Schedule;
import com.mediverify.app.reminder.Reminders;
import com.mediverify.app.ui.Charts;
import com.mediverify.app.ui.Fmt;
import com.mediverify.app.ui.Ui;
import com.mediverify.app.vision.FrameAnalyzer;
import com.mediverify.app.vision.Img;
import com.mediverify.app.vision.PillProfile;
import com.mediverify.app.vision.PillScan;
import com.mediverify.app.vision.VerificationSession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Live, multi-step camera verification of medication intake (or enrolment of a pill's reference
 * signature when started with EXTRA_ENROLL).
 */
@SuppressWarnings("deprecation")
public final class VerifyActivity extends Activity implements Camera.PreviewCallback, TextureView.SurfaceTextureListener {
    public static final String EXTRA_MED = "med";
    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_ENROLL = "enroll";
    public static final String EXTRA_NAME = "name";
    public static final String RESULT_PROFILE = "profile";
    public static final String RESULT_PHOTO = "photo";
    private static final int REQ_CAMERA = 31;

    private Med med;
    private long slot;
    private boolean enroll;
    private VerificationSession session;
    private Prefs prefs;

    // camera
    private Camera camera;
    private boolean front;
    private int dispOrient;
    private int pw, ph;
    private TextureView texture;
    private boolean surfaceReady;
    private FrameLayout camBox, camArea;
    private int uprightW = 3, uprightH = 4;

    // analysis
    private HandlerThread worker;
    private Handler workerH;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private int imgW, imgH;
    private int[] mapY, mapUV;
    private Img img;
    private int[] argb;
    private Bitmap faceBmp;
    private FaceDetector detector;
    private final FaceDetector.Face[] faces = new FaceDetector.Face[1];
    private float circleCx, circleCy, circleR;
    private volatile boolean done;

    // UI
    private OverlayView overlay;
    private TextView instruction, hint, stepTitle, btnSkip, btnManual, statusMsg;
    private LinearLayout stepsBar;
    private String photoPill, photoMouth;
    private long startWall;
    private ToneGenerator tone;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enroll = getIntent().getBooleanExtra(EXTRA_ENROLL, false);
        long medId = getIntent().getLongExtra(EXTRA_MED, -1);
        med = medId > 0 ? Db.get(this).med(medId) : null;
        if (!enroll && med == null) {
            med = pickNextPendingMed();
            if (med == null) {
                android.widget.Toast.makeText(this, "Tidak ada jadwal obat untuk diverifikasi", android.widget.Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        if (enroll && med == null) {
            med = new Med();
            med.name = getIntent().getStringExtra(EXTRA_NAME);
            if (med.name == null) med.name = "Obat";
        }
        slot = getIntent().getLongExtra(EXTRA_SLOT, -1);
        if (!enroll && slot <= 0) slot = nearestSlot(med);

        VerificationSession.Config cfg = new VerificationSession.Config();
        cfg.enroll = enroll;
        cfg.expectedCount = enroll ? 1 : Math.max(1, med.pillsPerDose);
        cfg.ref = PillProfile.parse(med.refProfile);
        cfg.declColor = med.color;
        cfg.declShape = med.shape;
        cfg.requireWater = med.requireWater;
        session = new VerificationSession(cfg);
        startWall = System.currentTimeMillis();

        worker = new HandlerThread("mv-analysis");
        worker.start();
        workerH = new Handler(worker.getLooper());
        try {
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
        } catch (RuntimeException e) {
            tone = null;
        }
        buildUi();

        if (!enroll) {
            DoseLog existing = Db.get(this).resolution(med.id, slot);
            if (existing != null && existing.isTaken()) {
                new AlertDialog.Builder(this)
                        .setTitle("Dosis sudah tercatat")
                        .setMessage(med.name + " jadwal " + Fmt.time(slot) + " sudah tercatat diminum pada "
                                + Fmt.time(existing.takenAt) + " (" + DoseLog.statusLabel(existing.status) + ").\n\n"
                                + "Verifikasi ulang akan menggantikan catatan tersebut.")
                        .setPositiveButton("Verifikasi ulang", null)
                        .setNegativeButton("Kembali", (d, w) -> finish())
                        .setCancelable(false)
                        .show();
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private Med pickNextPendingMed() {
        long now = System.currentTimeMillis();
        long today = Schedule.dayStart(now);
        List<Med> meds = Db.get(this).meds(false);
        Map<String, DoseLog> logs = Db.get(this).resolutions(today, Schedule.addDays(today, 1));
        Schedule.Slot best = null;
        for (Schedule.Slot s : Schedule.slotsForDay(meds, today, logs)) {
            if (s.log != null) continue;
            if (best == null || Math.abs(s.time - now) < Math.abs(best.time - now)) best = s;
        }
        if (best == null) return null;
        getIntent().putExtra(EXTRA_SLOT, best.time);
        return best.med;
    }

    /** Nearest unresolved slot of this medication within +-12 h, otherwise now. */
    private long nearestSlot(Med m) {
        long now = System.currentTimeMillis();
        long today = Schedule.dayStart(now);
        java.util.ArrayList<Med> one = new java.util.ArrayList<>();
        one.add(m);
        Map<String, DoseLog> logs = Db.get(this).resolutions(Schedule.addDays(today, -1), Schedule.addDays(today, 2));
        long best = -1;
        for (int d = -1; d <= 1; d++) {
            for (Schedule.Slot s : Schedule.slotsForDay(one, Schedule.addDays(today, d), logs)) {
                if (s.log != null && s.log.isTaken()) continue;
                if (Math.abs(s.time - now) > 12 * 3600000L) continue;
                if (best < 0 || Math.abs(s.time - now) < Math.abs(best - now)) best = s.time;
            }
        }
        return best > 0 ? best : now;
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        Ui.statusBar(this, 0xFF0F172A, true);
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(0xFF0F172A);

        LinearLayout top = Ui.hbox(this);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
        TextView close = Ui.text(this, "✕", 20, Color.WHITE, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.ripple(Ui.round(0, Ui.dp(this, 22)), 0x33FFFFFF));
        close.setOnClickListener(v -> onBackPressed());
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout titles = Ui.vbox(this);
        stepTitle = Ui.text(this, "", 12, 0xFF5EEAD4, true);
        stepTitle.setLetterSpacing(0.08f);
        titles.addView(stepTitle);
        String sub = enroll ? "Rekam referensi: " + med.name
                : med.name + " • " + med.doseLabel() + " • jadwal " + Fmt.time(slot);
        TextView medTv = Ui.text(this, sub, 14, Color.WHITE, true);
        medTv.setSingleLine(true);
        medTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(medTv);
        LinearLayout.LayoutParams tlp = Ui.lpWeight(1);
        tlp.leftMargin = Ui.dp(this, 6);
        top.addView(titles, tlp);
        root.addView(top, Ui.lpMW());

        stepsBar = Ui.hbox(this);
        stepsBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 10));
        root.addView(stepsBar, Ui.lpMW());

        camArea = new FrameLayout(this);
        camBox = new FrameLayout(this);
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        camBox.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new OverlayView(this);
        camBox.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        statusMsg = Ui.text(this, "Menyiapkan kamera…", 14, Color.WHITE, false);
        statusMsg.setGravity(Gravity.CENTER);
        camBox.addView(statusMsg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        camArea.addView(camBox, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        camArea.addOnLayoutChangeListener((v, l, t, r, btm, ol, ot, or, ob) -> fitCamera());
        root.addView(camArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout panel = Ui.vbox(this);
        panel.setBackground(Ui.round(Color.WHITE, Ui.dp(this, 22)));
        int p = Ui.dp(this, 18);
        panel.setPadding(p, p, p, p);
        instruction = Ui.h2(this, "");
        panel.addView(instruction, Ui.lpMW());
        hint = Ui.text(this, "", 14, Ui.INK2, true);
        hint.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        hint.setBackground(Ui.round(0xFFF1F5F9, Ui.dp(this, 12)));
        Ui.add(panel, hint, 10);
        LinearLayout btns = Ui.hbox(this);
        btnManual = Ui.button(this, "Konfirmasi manual", Ui.BTN_GHOST);
        btnManual.setTextSize(13);
        btnManual.setOnClickListener(v -> confirmManual());
        btns.addView(btnManual, Ui.lpWeight(1));
        btnSkip = Ui.button(this, "Lewati", Ui.BTN_SECONDARY);
        btnSkip.setTextSize(13);
        btnSkip.setOnClickListener(v -> workerH.post(() -> session.skipDrink(SystemClock.elapsedRealtime())));
        LinearLayout.LayoutParams sl = Ui.lpWeight(1);
        sl.leftMargin = Ui.dp(this, 8);
        btns.addView(btnSkip, sl);
        Ui.add(panel, btns, 10);
        btnManual.setVisibility(!enroll && prefs.allowManual() ? View.VISIBLE : View.INVISIBLE);
        btnSkip.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams plp = Ui.lpMW();
        plp.topMargin = -Ui.dp(this, 16);
        root.addView(panel, plp);
        setContentView(root);
        renderState(session.step, session.instruction, session.hint, session.hintLevel);
    }

    private void fitCamera() {
        int aw = camArea.getWidth(), ah = camArea.getHeight();
        if (aw <= 0 || ah <= 0) return;
        int w = aw, h = Math.round(aw * uprightH / (float) uprightW);
        if (h > ah) {
            h = ah;
            w = Math.round(ah * uprightW / (float) uprightH);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) camBox.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.CENTER;
            camBox.post(() -> camBox.setLayoutParams(lp));
        }
    }

    private String stepName(VerificationSession.Step s) {
        if (enroll) return "REKAM REFERENSI OBAT";
        switch (s) {
            case PILL: return "LANGKAH 1 • IDENTIFIKASI OBAT";
            case LIVENESS: return "LANGKAH 2 • PEMERIKSAAN WAJAH";
            case TO_MOUTH: return "LANGKAH 3 • OBAT KE MULUT";
            case DRINK: return "LANGKAH 4 • MINUM AIR";
            case MOUTH: return med.requireWater ? "LANGKAH 5 • CEK MULUT" : "LANGKAH 4 • CEK MULUT";
            case DONE: return "SELESAI";
            default: return "GAGAL";
        }
    }

    private void renderSteps(VerificationSession.Step cur) {
        stepsBar.removeAllViews();
        String[] names;
        VerificationSession.Step[] steps;
        if (enroll) {
            names = new String[]{"Rekam obat"};
            steps = new VerificationSession.Step[]{VerificationSession.Step.PILL};
        } else if (med.requireWater) {
            names = new String[]{"Obat", "Wajah", "Ke mulut", "Minum", "Mulut"};
            steps = new VerificationSession.Step[]{VerificationSession.Step.PILL, VerificationSession.Step.LIVENESS,
                    VerificationSession.Step.TO_MOUTH, VerificationSession.Step.DRINK, VerificationSession.Step.MOUTH};
        } else {
            names = new String[]{"Obat", "Wajah", "Ke mulut", "Mulut"};
            steps = new VerificationSession.Step[]{VerificationSession.Step.PILL, VerificationSession.Step.LIVENESS,
                    VerificationSession.Step.TO_MOUTH, VerificationSession.Step.MOUTH};
        }
        for (int i = 0; i < steps.length; i++) {
            boolean isDone = cur == VerificationSession.Step.DONE || steps[i].ordinal() < cur.ordinal();
            boolean active = steps[i] == cur;
            LinearLayout c = Ui.vbox(this);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            View bar = new View(this);
            bar.setBackground(Ui.round(isDone ? Ui.SUCCESS : active ? 0xFF5EEAD4 : 0x33FFFFFF, Ui.dp(this, 3)));
            c.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 5)));
            TextView t = Ui.text(this, (isDone ? "✓ " : "") + names[i], 11, active ? Color.WHITE : 0x99FFFFFF, active);
            t.setGravity(Gravity.CENTER);
            Ui.add(c, t, 4);
            LinearLayout.LayoutParams lp = Ui.lpWeight(1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 4);
            stepsBar.addView(c, lp);
        }
    }

    private VerificationSession.Step renderedStep;

    private void renderState(VerificationSession.Step step, String ins, String h, int level) {
        if (step != renderedStep) {
            renderedStep = step;
            renderSteps(step);
            stepTitle.setText(stepName(step));
            btnSkip.setVisibility(step == VerificationSession.Step.DRINK ? View.VISIBLE : View.INVISIBLE);
        }
        instruction.setText(ins);
        hint.setText(h == null || h.isEmpty() ? "Mengikuti instruksi…" : h);
        int bg, fg;
        switch (level) {
            case VerificationSession.LEVEL_OK: bg = Ui.SUCCESS_BG; fg = 0xFF047857; break;
            case VerificationSession.LEVEL_WARN: bg = Ui.WARN_BG; fg = 0xFFB45309; break;
            case VerificationSession.LEVEL_DANGER: bg = Ui.DANGER; fg = Color.WHITE; break;
            default: bg = 0xFFF1F5F9; fg = Ui.INK2; break;
        }
        hint.setBackground(Ui.round(bg, Ui.dp(this, 12)));
        hint.setTextColor(fg);
    }

    // ------------------------------------------------------------------ camera

    @Override
    protected void onResume() {
        super.onResume();
        if (!done && surfaceReady && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        done = true;
        closeCamera();
        if (worker != null) worker.quitSafely();
        if (tone != null) tone.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (surfaceReady) openCamera();
            } else {
                statusMsg.setVisibility(View.VISIBLE);
                statusMsg.setText("Izin kamera ditolak.\nVerifikasi visual membutuhkan kamera.\nAktifkan izin kamera di Pengaturan aplikasi.");
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surfaceReady = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceReady = false;
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    private void openCamera() {
        if (camera != null || done) return;
        int n = Camera.getNumberOfCameras();
        int id = -1;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < n; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                id = i;
                break;
            }
        }
        if (id < 0 && n > 0) id = 0;
        if (id < 0) {
            cameraError("Perangkat tidak memiliki kamera.");
            return;
        }
        try {
            Camera.getCameraInfo(id, info);
            front = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
            camera = Camera.open(id);
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            int deg = rot == Surface.ROTATION_90 ? 90 : rot == Surface.ROTATION_180 ? 180 : rot == Surface.ROTATION_270 ? 270 : 0;
            if (front) dispOrient = (360 - (info.orientation + deg) % 360) % 360;
            else dispOrient = (info.orientation - deg + 360) % 360;
            camera.setDisplayOrientation(dispOrient);
            Camera.Parameters params = camera.getParameters();
            Camera.Size best = null;
            double bestScore = Double.MAX_VALUE;
            for (Camera.Size s : params.getSupportedPreviewSizes()) {
                double aspectPenalty = Math.abs(s.width / (double) s.height - 4.0 / 3.0) * 1500;
                double sizePenalty = Math.abs(s.width - 640) + Math.abs(s.height - 480);
                if (s.height < 360) sizePenalty += 2000;
                double score = aspectPenalty + sizePenalty;
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            if (best != null) params.setPreviewSize(best.width, best.height);
            params.setPreviewFormat(ImageFormat.NV21);
            List<String> fm = params.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            List<String> wb = params.getSupportedWhiteBalance();
            if (wb != null && wb.contains(Camera.Parameters.WHITE_BALANCE_AUTO)) params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                params = camera.getParameters();
            }
            Camera.Size ps = camera.getParameters().getPreviewSize();
            pw = ps.width;
            ph = ps.height;
            configureAnalysis();
            camera.setPreviewTexture(texture.getSurfaceTexture());
            int bufSize = pw * ph * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            for (int i = 0; i < 3; i++) camera.addCallbackBuffer(new byte[bufSize]);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            statusMsg.setVisibility(View.GONE);
        } catch (Exception e) {
            closeCamera();
            cameraError("Kamera tidak dapat dibuka (" + e.getClass().getSimpleName() + ").\nTutup aplikasi lain yang memakai kamera lalu coba lagi.");
        }
    }

    private void cameraError(String msg) {
        statusMsg.setVisibility(View.VISIBLE);
        statusMsg.setText(msg);
    }

    private void closeCamera() {
        Camera c = camera;
        camera = null;
        if (c != null) {
            try {
                c.setPreviewCallbackWithBuffer(null);
                c.stopPreview();
            } catch (RuntimeException ignored) {
            }
            c.release();
        }
    }

    /** Builds the lookup table that maps analysis pixels (upright, as displayed) to NV21 offsets. */
    private void configureAnalysis() {
        int uw = dispOrient % 180 == 0 ? pw : ph;
        int uh = dispOrient % 180 == 0 ? ph : pw;
        uprightW = uw;
        uprightH = uh;
        fitCamera();
        int step = Math.max(1, Math.round(uw / 240f));
        int w = (uw / step) & ~1;
        int h = uh / step;
        final int[] my = new int[w * h], muv = new int[w * h];
        int frame = pw * ph;
        for (int oy = 0; oy < h; oy++) {
            for (int ox = 0; ox < w; ox++) {
                int ux = Math.min(uw - 1, ox * step + step / 2), uy = Math.min(uh - 1, oy * step + step / 2);
                int mx, mY;
                switch (dispOrient) {
                    case 90: mx = uy; mY = ph - 1 - ux; break;
                    case 180: mx = pw - 1 - ux; mY = ph - 1 - uy; break;
                    case 270: mx = pw - 1 - uy; mY = ux; break;
                    default: mx = ux; mY = uy; break;
                }
                int sx = front ? pw - 1 - mx : mx;
                int sy = mY;
                sx = Math.max(0, Math.min(pw - 1, sx));
                sy = Math.max(0, Math.min(ph - 1, sy));
                my[oy * w + ox] = sy * pw + sx;
                muv[oy * w + ox] = frame + (sy >> 1) * pw + (sx & ~1);
            }
        }
        final int fw = w, fh = h;
        workerH.post(() -> {
            imgW = fw;
            imgH = fh;
            mapY = my;
            mapUV = muv;
            img = new Img(fw, fh);
            argb = new int[fw * fh];
            if (faceBmp != null) faceBmp.recycle();
            faceBmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.RGB_565);
            detector = new FaceDetector(fw, fh, 1);
            circleCx = fw / 2f;
            circleCy = fh * 0.45f;
            circleR = fw * 0.36f;
        });
        overlay.imgW = w;
        overlay.imgH = h;
        overlay.circleCx = w / 2f;
        overlay.circleCy = h * 0.45f;
        overlay.circleR = w * 0.36f;
    }

    @Override
    public void onPreviewFrame(final byte[] data, final Camera cam) {
        if (data == null) return;
        if (busy || done || workerH == null) {
            cam.addCallbackBuffer(data);
            return;
        }
        busy = true;
        final int fpw = pw, fph = ph;
        final boolean ffront = front;
        final int fdisp = dispOrient;
        workerH.post(() -> {
            try {
                if (mapY != null && data.length >= fpw * fph * 3 / 2) analyze(data, fpw, fph, ffront, fdisp);
            } catch (RuntimeException e) {
                // keep the session alive on unexpected analysis errors
            } finally {
                busy = false;
                main.post(() -> {
                    if (camera == cam) cam.addCallbackBuffer(data);
                });
            }
        });
    }

    private void toRgb(byte[] nv) {
        int[] px = img.px;
        for (int i = 0; i < px.length; i++) {
            int y = (nv[mapY[i]] & 0xff) - 16;
            if (y < 0) y = 0;
            int uvi = mapUV[i];
            int v = (nv[uvi] & 0xff) - 128;
            int u = (nv[uvi + 1] & 0xff) - 128;
            int y1192 = 1192 * y;
            int r = y1192 + 1634 * v;
            int g = y1192 - 833 * v - 400 * u;
            int b = y1192 + 2066 * u;
            r = r < 0 ? 0 : (r > 262143 ? 262143 : r);
            g = g < 0 ? 0 : (g > 262143 ? 262143 : g);
            b = b < 0 ? 0 : (b > 262143 ? 262143 : b);
            px[i] = ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
        }
    }

    private VerificationSession.FaceObs detectFace() {
        if (detector == null) return null;
        int[] px = img.px;
        for (int i = 0; i < px.length; i++) argb[i] = 0xFF000000 | px[i];
        faceBmp.setPixels(argb, 0, imgW, 0, 0, imgW, imgH);
        int n;
        try {
            n = detector.findFaces(faceBmp, faces);
        } catch (RuntimeException e) {
            return null;
        }
        if (n <= 0 || faces[0] == null || faces[0].confidence() < 0.3f) return null;
        PointF mid = new PointF();
        faces[0].getMidPoint(mid);
        return new VerificationSession.FaceObs(mid.x, mid.y, faces[0].eyesDistance());
    }

    private void analyze(byte[] nv, int fpw, int fph, boolean ffront, int fdisp) {
        if (session.isFinished()) return;
        toRgb(nv);
        long t = SystemClock.elapsedRealtime();
        PillScan scan = null;
        VerificationSession.FaceObs face = null;
        if (session.needsPalmScan()) scan = FrameAnalyzer.scanPalm(img, circleCx, circleCy, circleR);
        if (session.needsFace()) face = detectFace();
        session.onFrame(t, img, scan, face, (float) (Math.PI * circleR * circleR));
        VerificationSession.Capture cap = session.pollCapture();
        if (cap == VerificationSession.Capture.PILL) photoPill = saveEvidence(nv, fpw, fph, ffront, fdisp, "obat");
        else if (cap == VerificationSession.Capture.MOUTH) photoMouth = saveEvidence(nv, fpw, fph, ffront, fdisp, "mulut");
        final VerificationSession.Alert alert = session.pollAlert();
        final VerificationSession.Step step = session.step;
        final String ins = session.instruction, h = session.hint;
        final int level = session.hintLevel;
        final float prog = session.holdProgress;
        final PillScan fs = session.lastScan;
        final int pc = session.lastPillClass;
        final VerificationSession.FaceObs ff = session.lastFace;
        final FrameAnalyzer.MouthStats ms = session.lastMouth;
        final boolean arrow = step == VerificationSession.Step.LIVENESS && ff != null && h != null && !h.startsWith("Wajah terdeteksi");
        final boolean finished = session.isFinished();
        main.post(() -> {
            if (done && !finished) return;
            overlay.step = step;
            overlay.progress = prog;
            overlay.level = level;
            overlay.scan = step == VerificationSession.Step.PILL ? fs : null;
            overlay.pillClass = pc;
            overlay.face = ff;
            overlay.hasMouth = ms != null && ms.valid;
            if (ms != null) {
                overlay.mx0 = ms.x0;
                overlay.my0 = ms.y0;
                overlay.mx1 = ms.x1;
                overlay.my1 = ms.y1;
            }
            overlay.liveDir = session.liveDirection;
            overlay.showArrow = arrow;
            overlay.invalidate();
            renderState(step, ins, h, level);
            handleAlert(alert);
            if (finished) onSessionFinished();
        });
    }

    private void handleAlert(VerificationSession.Alert a) {
        if (a == VerificationSession.Alert.NONE) return;
        Vibrator vib = getSystemService(Vibrator.class);
        if (a == VerificationSession.Alert.WRONG_PILL || a == VerificationSession.Alert.DOSE_MISMATCH) {
            if (vib != null) vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 150, 400, 150, 400}, -1));
            if (tone != null) tone.startTone(ToneGenerator.TONE_SUP_ERROR, 800);
        } else if (a == VerificationSession.Alert.SCREEN) {
            if (vib != null) vib.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
            new AlertDialog.Builder(this)
                    .setTitle("Layar terdeteksi")
                    .setMessage("Kamera sepertinya diarahkan ke layar HP/monitor. Tunjukkan obat fisik yang asli di telapak tangan. "
                            + "Catatan ini akan disertakan pada hasil verifikasi.")
                    .setPositiveButton("Mengerti", null)
                    .show();
        }
    }

    private String saveEvidence(byte[] nv, int fpw, int fph, boolean ffront, int fdisp, String tag) {
        try {
            YuvImage yuv = new YuvImage(nv, ImageFormat.NV21, fpw, fph, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, fpw, fph), 90, bos);
            byte[] jpg = bos.toByteArray();
            Bitmap raw = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            if (raw == null) return null;
            Matrix m = new Matrix();
            if (ffront) m.postScale(-1, 1);
            m.postRotate(fdisp);
            Bitmap up = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            Bitmap out = up.copy(Bitmap.Config.ARGB_8888, true);
            if (up != raw) up.recycle();
            raw.recycle();
            Canvas cv = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float bar = out.getHeight() * 0.075f;
            p.setColor(0xAA0F172A);
            cv.drawRect(0, out.getHeight() - bar, out.getWidth(), out.getHeight(), p);
            p.setColor(Color.WHITE);
            p.setTextSize(bar * 0.42f);
            cv.drawText("MediVerify • " + med.name + " • " + Fmt.dateTime(System.currentTimeMillis()) + " • " + tag,
                    bar * 0.3f, out.getHeight() - bar * 0.33f, p);
            File dir = new File(getFilesDir(), "evidence");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, Fmt.stamp(System.currentTimeMillis()) + "_" + (enroll ? "ref" : (med.id + "")) + "_" + tag + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 85, fo);
            }
            out.recycle();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ finishing

    private boolean resultShown;

    private void onSessionFinished() {
        if (resultShown) return;
        resultShown = true;
        done = true;
        closeCamera();
        if (enroll) {
            if (session.step == VerificationSession.Step.DONE && session.sessionProfile != null) {
                Intent r = new Intent();
                r.putExtra(RESULT_PROFILE, session.sessionProfile.serialize());
                r.putExtra(RESULT_PHOTO, photoPill);
                setResult(RESULT_OK, r);
                android.widget.Toast.makeText(this, "Referensi visual obat berhasil direkam", android.widget.Toast.LENGTH_SHORT).show();
            }
            finish();
            return;
        }
        DoseLog l = baseLog();
        int st = session.status();
        l.pillScore = session.pillScore;
        l.ingestScore = session.ingestionScore();
        l.totalScore = session.totalScore();
        l.detectedCount = session.detectedCount;
        l.photoPill = photoPill;
        l.photoMouth = photoMouth;
        l.wrongAlerts = session.wrongPillAlerts + session.doseAlerts;
        l.durationMs = session.elapsedMs();
        StringBuilder notes = new StringBuilder();
        notes.append(session.config().ref != null ? "Dicocokkan dengan foto referensi obat. " : "Dicocokkan dengan warna/bentuk yang didaftarkan (belum ada foto referensi). ");
        for (String n : session.notes) notes.append(n).append(". ");
        if (session.step == VerificationSession.Step.FAILED && !session.failReason.isEmpty()) notes.append(session.failReason).append(". ");
        l.notes = notes.toString().trim();
        if (st == VerificationSession.STATUS_VERIFIED || st == VerificationSession.STATUS_REVIEW) {
            l.status = st == VerificationSession.STATUS_VERIFIED ? DoseLog.VERIFIED : DoseLog.REVIEW;
            recordTaken(l);
        } else {
            Db.get(this).insertAttempt(l);
        }
        showResult(l);
    }

    private DoseLog baseLog() {
        DoseLog l = new DoseLog();
        l.medId = med.id;
        l.medName = med.name;
        l.slotTime = slot;
        l.takenAt = System.currentTimeMillis();
        return l;
    }

    private void recordTaken(DoseLog l) {
        Db db = Db.get(this);
        DoseLog prev = db.resolution(l.medId, l.slotTime);
        db.recordResolution(l);
        if (prev == null || !prev.isTaken()) {
            db.adjustStock(med.id, -Math.max(1, med.pillsPerDose));
            Med fresh = db.med(med.id);
            if (fresh != null) Reminders.notifyLowStock(this, fresh);
        }
        Reminders.dismiss(this, l.medId, l.slotTime);
    }

    private void confirmManual() {
        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi manual")
                .setMessage("Catat bahwa " + med.name + " (" + med.doseLabel() + ") sudah diminum TANPA verifikasi kamera?\n\n"
                        + "Catatan manual ditandai terpisah dan tidak dihitung sebagai dosis terverifikasi visual pada rekap.")
                .setPositiveButton("Ya, sudah diminum", (d, w) -> {
                    done = true;
                    closeCamera();
                    DoseLog l = baseLog();
                    l.status = DoseLog.MANUAL;
                    l.durationMs = System.currentTimeMillis() - startWall;
                    l.wrongAlerts = session.wrongPillAlerts + session.doseAlerts;
                    l.notes = "Dikonfirmasi manual oleh pengguna tanpa verifikasi kamera."
                            + (l.wrongAlerts > 0 ? " Sebelumnya muncul " + l.wrongAlerts + " peringatan obat/dosis." : "");
                    recordTaken(l);
                    resultShown = true;
                    showResult(l);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (resultShown || enroll) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Hentikan verifikasi?")
                .setMessage("Dosis belum tercatat. Anda bisa memverifikasi lagi dari beranda.")
                .setPositiveButton("Hentikan", (d, w) -> {
                    done = true;
                    if (session.wrongPillAlerts + session.doseAlerts > 0) {
                        DoseLog l = baseLog();
                        l.pillScore = session.pillScore;
                        l.wrongAlerts = session.wrongPillAlerts + session.doseAlerts;
                        l.durationMs = session.elapsedMs();
                        StringBuilder sb = new StringBuilder("Verifikasi dihentikan pengguna. ");
                        for (String n : session.notes) sb.append(n).append(". ");
                        l.notes = sb.toString().trim();
                        Db.get(this).insertAttempt(l);
                    }
                    finish();
                })
                .setNegativeButton("Lanjutkan", null)
                .show();
    }

    private void showResult(DoseLog l) {
        Ui.statusBar(this, Color.WHITE, false);
        Ui.Screen s = new Ui.Screen(this, "Hasil verifikasi", false);
        LinearLayout body = s.body;
        boolean taken = l.isTaken();
        int color = Ui.statusColor(l.status);

        LinearLayout hero = Ui.card(this);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        if (l.status != DoseLog.MANUAL) {
            Charts.Ring ring = new Charts.Ring(this);
            ring.set(Math.max(0, l.totalScore), color, "skor");
            hero.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)));
        }
        String head;
        switch (l.status) {
            case DoseLog.VERIFIED: head = "✓ Minum obat terverifikasi"; break;
            case DoseLog.REVIEW: head = "Tercatat — perlu ditinjau"; break;
            case DoseLog.MANUAL: head = "Tercatat (konfirmasi manual)"; break;
            default: head = "Verifikasi belum berhasil"; break;
        }
        TextView ht = Ui.h1(this, head);
        ht.setTextColor(color);
        ht.setGravity(Gravity.CENTER);
        Ui.add(hero, ht, 12);
        TextView mt = Ui.body(this, med.name + " • " + med.doseLabel());
        mt.setGravity(Gravity.CENTER);
        Ui.add(hero, mt, 4);
        if (taken) {
            long late = (l.takenAt - l.slotTime) / 60000L;
            int win = prefs.onTimeMinutes();
            String timing = Math.abs(late) <= win ? "Tepat waktu (jadwal " + Fmt.time(l.slotTime) + ")"
                    : late > 0 ? "Terlambat " + Fmt.duration(late) + " dari jadwal " + Fmt.time(l.slotTime)
                    : "Lebih awal " + Fmt.duration(-late) + " dari jadwal " + Fmt.time(l.slotTime);
            TextView tt = Ui.chip(this, timing, Math.abs(late) <= win ? Ui.SUCCESS_BG : Ui.WARN_BG,
                    Math.abs(late) <= win ? 0xFF047857 : 0xFFB45309);
            LinearLayout.LayoutParams lp = Ui.lpWW();
            lp.topMargin = Ui.dp(this, 10);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            hero.addView(tt, lp);
        }
        body.addView(hero, Ui.lpMW());

        if (l.status != DoseLog.MANUAL) {
            LinearLayout det = Ui.card(this);
            det.addView(Ui.label(this, "Rincian pemeriksaan"));
            addCheck(det, "Identifikasi obat", session.pillScore >= 0 ? "kemiripan " + Math.round(session.pillScore) + "% • " + session.detectedCount + " tablet" : "tidak lolos", session.pillScore >= 68);
            addCheck(det, "Wajah asli (gerak acak)", session.livenessPassed ? "lolos" : "tidak lolos", session.livenessPassed);
            addCheck(det, "Obat dimasukkan ke mulut", session.toMouthCredit >= 1 ? "terlihat" : session.toMouthCredit > 0 ? "gerakan terdeteksi" : "tidak terdeteksi", session.toMouthCredit > 0);
            if (med.requireWater) addCheck(det, "Minum air", session.drinkDone ? "terdeteksi" : session.drinkSkipped ? "dilewati" : "tidak terdeteksi", session.drinkDone);
            addCheck(det, "Mulut kosong (obat ditelan)", session.mouthPassed ? "terkonfirmasi" : "tidak terkonfirmasi", session.mouthPassed);
            if (session.screenSuspected) addCheck(det, "Deteksi layar/monitor", "pola layar terdeteksi", false);
            TextView sc = Ui.caption(this, "Skor obat " + (l.pillScore < 0 ? "–" : Math.round(l.pillScore)) + " • Skor penelanan "
                    + Math.round(Math.max(0, l.ingestScore)) + " • Durasi " + (l.durationMs / 1000) + " detik");
            Ui.add(det, sc, 10);
            Ui.add(body, det, 12);
        }
        if (l.photoPill != null || l.photoMouth != null) {
            LinearLayout ph = Ui.card(this);
            ph.addView(Ui.label(this, "Foto bukti"));
            LinearLayout row = Ui.hbox(this);
            addThumb(row, l.photoPill, "Obat");
            addThumb(row, l.photoMouth, "Mulut");
            Ui.add(ph, row, 8);
            Ui.add(body, ph, 12);
        }
        if (!l.notes.isEmpty()) {
            LinearLayout nc = Ui.card(this);
            nc.addView(Ui.label(this, "Catatan"));
            Ui.add(nc, Ui.body(this, l.notes), 6);
            Ui.add(body, nc, 12);
        }
        if (!taken) {
            TextView retry = Ui.button(this, "Coba lagi", Ui.BTN_PRIMARY);
            retry.setOnClickListener(v -> {
                Intent i = new Intent(this, VerifyActivity.class);
                i.putExtra(EXTRA_MED, med.id);
                i.putExtra(EXTRA_SLOT, slot);
                startActivity(i);
                finish();
            });
            Ui.add(body, retry, 16);
            if (prefs.allowManual()) {
                TextView man = Ui.button(this, "Konfirmasi manual", Ui.BTN_SECONDARY);
                man.setOnClickListener(v -> confirmManual());
                Ui.add(body, man, 10);
            }
        }
        TextView fin = Ui.button(this, "Selesai", taken ? Ui.BTN_PRIMARY : Ui.BTN_GHOST);
        fin.setOnClickListener(v -> finish());
        Ui.add(body, fin, taken ? 16 : 6);
        setContentView(s.root);
    }

    private void addCheck(LinearLayout parent, String label, String value, boolean ok) {
        LinearLayout row = Ui.hbox(this);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        TextView ic = Ui.text(this, ok ? "✓" : "✕", 14, Color.WHITE, true);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(ok ? Ui.SUCCESS : Ui.DANGER, Ui.dp(this, 12)));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        LinearLayout col = Ui.vbox(this);
        col.addView(Ui.text(this, label, 14, Ui.INK, true));
        col.addView(Ui.caption(this, value));
        LinearLayout.LayoutParams lp = Ui.lpWeight(1);
        lp.leftMargin = Ui.dp(this, 12);
        row.addView(col, lp);
        parent.addView(row, Ui.lpMW());
    }

    private void addThumb(LinearLayout row, String path, String label) {
        if (path == null) return;
        LinearLayout c = Ui.vbox(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        Bitmap bm = BitmapFactory.decodeFile(path, o);
        if (bm != null) iv.setImageBitmap(bm);
        iv.setBackground(Ui.round(0xFFE2E8F0, Ui.dp(this, 12)));
        iv.setClipToOutline(true);
        c.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 170)));
        c.addView(Ui.caption(this, label));
        LinearLayout.LayoutParams lp = Ui.lpWW();
        lp.rightMargin = Ui.dp(this, 10);
        row.addView(c, lp);
    }
}

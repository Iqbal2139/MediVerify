package com.mediverify.app.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Multi-step, time-based verification state machine. Fed with one analysed camera frame at a time;
 * contains no Android code so it can be unit tested.
 *
 * Steps: PILL (pill on palm matches the prescription) -> LIVENESS (face found + random head move,
 * defeats printed photos) -> TO_MOUTH (pill brought to the mouth) -> DRINK (drinking water)
 * -> MOUTH (open mouth, pill no longer visible) -> DONE.
 */
public final class VerificationSession {

    public enum Step { PILL, LIVENESS, TO_MOUTH, DRINK, MOUTH, DONE, FAILED }

    public enum Capture { NONE, PILL, MOUTH }

    public enum Alert { NONE, WRONG_PILL, DOSE_MISMATCH, SCREEN }

    public static final int LEVEL_INFO = 0, LEVEL_OK = 1, LEVEL_WARN = 2, LEVEL_DANGER = 3;

    public static final class Config {
        public int expectedCount = 1;
        public PillProfile ref;
        public int declColor = -1, declShape = -1;
        public boolean requireWater = true;
        public boolean enroll = false;
        public long seed = System.nanoTime();
    }

    public static final class FaceObs {
        public boolean present;
        public float mx, my, ed;

        public FaceObs() {}

        public FaceObs(float mx, float my, float ed) {
            this.present = true;
            this.mx = mx;
            this.my = my;
            this.ed = ed;
        }
    }

    // ---- timing constants (ms)
    static final long PILL_HOLD = 1500, ALERT_HOLD = 2000, ENROLL_HOLD = 1200;
    static final long LIVE_TIMEOUT_HINT = 12000, TO_MOUTH_TIMEOUT = 25000, DRINK_HINT = 20000;
    static final long MOUTH_HOLD = 900, FACE_GAP_MAX = 10000, DRINK_GAP_MAX = 12000;
    static final long SESSION_TIMEOUT = 5 * 60 * 1000;

    private final Config cfg;
    private final Random rnd;

    // ---- public state for the UI
    public Step step = Step.PILL;
    public String instruction = "";
    public String hint = "";
    public int hintLevel = LEVEL_INFO;
    public float holdProgress;
    public PillScan lastScan;
    public FaceObs lastFace;
    public FrameAnalyzer.MouthStats lastMouth;
    /** Classification of the current frame's pills, for drawing boxes: 1 match, 2 unsure, 3 wrong. */
    public int lastPillClass;
    public int liveDirection; // -1 = left, +1 = right

    private Capture pendingCapture = Capture.NONE;
    private Alert pendingAlert = Alert.NONE;

    // ---- results
    public float pillScore = -1; // 0..100
    public int detectedCount;
    public boolean livenessPassed;
    public float toMouthCredit; // 0..1
    public boolean drinkDone, drinkSkipped;
    public boolean mouthPassed;
    public boolean screenSuspected;
    public int wrongPillAlerts, doseAlerts, continuityBreaks;
    public String failReason = "";
    public PillProfile sessionProfile; // average profile measured during PILL step (or enrolment result)
    public final List<String> notes = new ArrayList<>();

    // ---- internals
    private long t0 = -1, lastT;
    private int curClass = 0;
    private long classSince;
    private int classFrames;
    private boolean alertRaisedForEpisode;
    private final List<PillProfile> holdProfiles = new ArrayList<>();
    private final List<Float> holdScores = new ArrayList<>();
    private int screenFrames, frames;
    // enrolment
    private final List<PillProfile> enrollSamples = new ArrayList<>();
    private long lastEnrollSampleT;
    // face / mouth
    private long stepStart, lastFaceT = -1, pillDoneT;
    private FrameAnalyzer.PillColor pillColor;
    private FrameAnalyzer.MouthStats baseMouth;
    private float baseNear; // baseline pill-colour ratio in the enlarged mouth area
    private final List<FrameAnalyzer.MouthStats> baseSamples = new ArrayList<>();
    private final List<Float> baseNearSamples = new ArrayList<>();
    private float baseX;
    private int moveFrames;
    private boolean pillSeenNearMouth, occludedNearMouth;
    private int nearFrames, clearFrames, occFrames;
    private long occSince = -1, faceLostAt = -1;
    private long openSince = -1;
    private int openFrames;

    public VerificationSession(Config cfg) {
        this.cfg = cfg;
        this.rnd = new Random(cfg.seed);
        liveDirection = rnd.nextBoolean() ? -1 : 1;
        setPillInstruction();
    }

    public Config config() {
        return cfg;
    }

    public boolean needsPalmScan() {
        return step == Step.PILL;
    }

    public boolean needsFace() {
        return step == Step.LIVENESS || step == Step.TO_MOUTH || step == Step.DRINK || step == Step.MOUTH;
    }

    public Capture pollCapture() {
        Capture c = pendingCapture;
        pendingCapture = Capture.NONE;
        return c;
    }

    public Alert pollAlert() {
        Alert a = pendingAlert;
        pendingAlert = Alert.NONE;
        return a;
    }

    public boolean isFinished() {
        return step == Step.DONE || step == Step.FAILED;
    }

    private void setPillInstruction() {
        if (cfg.enroll) {
            instruction = "Letakkan " + cfg.expectedCount + " tablet di tengah telapak tangan, di dalam lingkaran";
        } else {
            instruction = "Tunjukkan " + cfg.expectedCount + " tablet obat di telapak tangan, di dalam lingkaran";
        }
    }

    private void hint(String h, int level) {
        hint = h;
        hintLevel = level;
    }

    private void go(Step s, long t) {
        step = s;
        stepStart = t;
        holdProgress = 0;
        switch (s) {
            case LIVENESS:
                instruction = "Arahkan wajah ke kamera (mulut tertutup)";
                baseSamples.clear();
                baseNearSamples.clear();
                baseMouth = null;
                moveFrames = 0;
                break;
            case TO_MOUTH:
                instruction = "Ambil obat dari telapak tangan dan masukkan ke mulut";
                pillSeenNearMouth = false;
                occludedNearMouth = false;
                nearFrames = clearFrames = occFrames = 0;
                break;
            case DRINK:
                instruction = "Minum air dari gelas atau botol untuk menelan obat";
                occSince = -1;
                faceLostAt = -1;
                occFrames = 0;
                break;
            case MOUTH:
                instruction = "Buka mulut lebar-lebar & julurkan lidah ke kamera";
                openSince = -1;
                openFrames = 0;
                break;
            default:
                break;
        }
    }

    /** User pressed "Lewati" during the drinking step. */
    public void skipDrink(long t) {
        if (step != Step.DRINK) return;
        drinkSkipped = true;
        notes.add("Langkah minum air dilewati oleh pengguna");
        go(Step.MOUTH, t);
    }

    public void cancel(String reason) {
        if (isFinished()) return;
        failReason = reason;
        step = Step.FAILED;
    }

    /**
     * Feeds one frame. scan must be non-null while needsPalmScan(); face may be null if no face was
     * found (or not requested).
     */
    public void onFrame(long t, Img img, PillScan scan, FaceObs face, float circleArea) {
        if (isFinished()) return;
        if (t0 < 0) {
            t0 = t;
            stepStart = t;
        }
        lastT = t;
        frames++;
        if (t - t0 > SESSION_TIMEOUT) {
            failReason = "Waktu verifikasi habis (5 menit)";
            step = Step.FAILED;
            return;
        }
        switch (step) {
            case PILL:
                lastScan = scan;
                if (cfg.enroll) onEnrollFrame(t, scan, circleArea);
                else onPillFrame(t, scan, circleArea);
                break;
            case LIVENESS:
            case TO_MOUTH:
            case DRINK:
            case MOUTH:
                onFaceFrame(t, img, face);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ PILL

    private int classify(PillScan s, float circleArea, List<PillProfile> out, float[] scoreOut) {
        out.clear();
        int count = 0;
        float minScore = 1f;
        for (PillScan.Blob bl : s.blobs) {
            PillProfile p = PillProfile.fromBlob(s, bl, circleArea);
            int c = 1;
            if (cfg.ref != null && cfg.ref.relSize > 0) {
                c = Math.max(1, Math.min(4, Math.round(p.relSize / cfg.ref.relSize)));
            }
            p.count = c;
            count += c;
            float sc;
            if (cfg.ref != null) sc = c > 1 ? p.colorSimilarity(cfg.ref) : p.similarity(cfg.ref);
            else sc = p.declaredMatch(cfg.declColor, cfg.declShape);
            minScore = Math.min(minScore, sc);
            out.add(p);
        }
        scoreOut[0] = minScore;
        float matchT = cfg.ref != null ? 0.68f : 0.60f;
        float wrongT = cfg.ref != null ? 0.45f : 0.35f;
        if (minScore < wrongT) return 3; // wrong pill
        if (minScore < matchT) return 2; // unsure
        if (count != cfg.expectedCount) return 4; // right pill, wrong amount
        return 1;
    }

    private final List<PillProfile> tmpProfiles = new ArrayList<>();

    private void onPillFrame(long t, PillScan s, float circleArea) {
        if (s == null) return;
        if (s.screenScore > 0.80f) {
            screenFrames++;
            if (screenFrames >= 6 && !screenSuspected) {
                screenSuspected = true;
                pendingAlert = Alert.SCREEN;
                notes.add("Pola layar (moire) terdeteksi pada tahap obat");
            }
        } else if (screenFrames > 0) {
            screenFrames--;
        }
        if (s.meanLuma < 35) {
            setClass(0, t);
            lastPillClass = 0;
            hint("Terlalu gelap. Cari tempat yang lebih terang", LEVEL_WARN);
            return;
        }
        if (!s.palmPresent) {
            setClass(0, t);
            lastPillClass = 0;
            hint("Telapak tangan belum terlihat di lingkaran", LEVEL_INFO);
            return;
        }
        if (s.blobs.isEmpty()) {
            setClass(0, t);
            lastPillClass = 0;
            hint("Telapak tangan terdeteksi, tetapi obat belum terlihat", LEVEL_WARN);
            return;
        }
        float[] sc = new float[1];
        int cls = classify(s, circleArea, tmpProfiles, sc);
        lastPillClass = cls == 4 ? 2 : cls;
        int cnt = 0;
        for (PillProfile p : tmpProfiles) cnt += p.count;
        detectedCount = cnt;
        setClass(cls, t);
        long held = t - classSince;
        switch (cls) {
            case 1:
                holdProfiles.addAll(tmpProfiles);
                holdScores.add(sc[0]);
                holdProgress = Math.min(1f, held / (float) PILL_HOLD);
                hint("Obat cocok (" + Math.round(sc[0] * 100) + "%) — tahan sebentar…", LEVEL_OK);
                if (held >= PILL_HOLD && classFrames >= 5) {
                    float sum = 0;
                    for (float f : holdScores) sum += f;
                    pillScore = 100f * sum / holdScores.size();
                    sessionProfile = PillProfile.average(holdProfiles);
                    pillColor = new FrameAnalyzer.PillColor(sessionProfile.rawR, sessionProfile.rawG, sessionProfile.rawB);
                    pendingCapture = Capture.PILL;
                    pillDoneT = t;
                    hint("Obat terverifikasi ✓", LEVEL_OK);
                    go(Step.LIVENESS, t);
                }
                break;
            case 2:
                holdProgress = 0;
                hint("Belum yakin (" + Math.round(sc[0] * 100) + "%). Dekatkan & pastikan cahaya cukup", LEVEL_WARN);
                break;
            case 3:
                holdProgress = 0;
                hint("OBAT TIDAK COCOK dengan resep — JANGAN DIMINUM", LEVEL_DANGER);
                if (held >= ALERT_HOLD && classFrames >= 5 && !alertRaisedForEpisode) {
                    alertRaisedForEpisode = true;
                    wrongPillAlerts++;
                    pendingAlert = Alert.WRONG_PILL;
                    notes.add("Peringatan obat salah (kemiripan " + Math.round(sc[0] * 100) + "%)");
                }
                break;
            case 4:
                holdProgress = 0;
                hint("Jumlah tablet terdeteksi " + cnt + ", resep " + cfg.expectedCount + " tablet", LEVEL_DANGER);
                if (held >= ALERT_HOLD && classFrames >= 5 && !alertRaisedForEpisode) {
                    alertRaisedForEpisode = true;
                    doseAlerts++;
                    pendingAlert = Alert.DOSE_MISMATCH;
                    notes.add("Peringatan dosis: terdeteksi " + cnt + " tablet, resep " + cfg.expectedCount);
                }
                break;
            default:
                break;
        }
    }

    private void setClass(int cls, long t) {
        if (cls != curClass) {
            curClass = cls;
            classSince = t;
            classFrames = 0;
            alertRaisedForEpisode = false;
            holdProfiles.clear();
            holdScores.clear();
        }
        classFrames++;
    }

    private void onEnrollFrame(long t, PillScan s, float circleArea) {
        if (s == null) return;
        lastPillClass = 0;
        if (s.meanLuma < 35) {
            setClass(0, t);
            hint("Terlalu gelap. Cari tempat yang lebih terang", LEVEL_WARN);
            return;
        }
        if (!s.palmPresent) {
            setClass(0, t);
            hint("Telapak tangan belum terlihat di lingkaran", LEVEL_INFO);
            return;
        }
        if (s.blobs.size() != cfg.expectedCount) {
            setClass(0, t);
            hint(s.blobs.isEmpty() ? "Obat belum terlihat. Gunakan latar telapak tangan"
                    : "Terlihat " + s.blobs.size() + " objek, harap pisahkan " + cfg.expectedCount + " tablet", LEVEL_WARN);
            return;
        }
        List<PillProfile> ps = new ArrayList<>();
        for (PillScan.Blob bl : s.blobs) ps.add(PillProfile.fromBlob(s, bl, circleArea));
        PillProfile cur = PillProfile.average(ps);
        cur.count = 1;
        // stability check versus the running hold average
        boolean stable = holdProfiles.isEmpty() || cur.similarity(PillProfile.average(holdProfiles)) > 0.80f;
        if (!stable) {
            curClass = -1; // force reset
        }
        setClass(1, t);
        lastPillClass = 1;
        holdProfiles.add(cur);
        long held = t - classSince;
        holdProgress = Math.min(1f, held / (float) ENROLL_HOLD);
        hint("Merekam ciri obat " + (enrollSamples.size() + 1) + "/3 — tahan…", LEVEL_OK);
        if (held >= ENROLL_HOLD && classFrames >= 4 && t - lastEnrollSampleT > 700) {
            PillProfile sample = PillProfile.average(holdProfiles);
            sample.count = 1;
            enrollSamples.add(sample);
            lastEnrollSampleT = t;
            if (enrollSamples.size() == 1) pendingCapture = Capture.PILL;
            curClass = -1;
            holdProgress = 0;
            if (enrollSamples.size() >= 3) {
                sessionProfile = PillProfile.average(enrollSamples);
                sessionProfile.count = 1;
                pillScore = 100;
                detectedCount = cfg.expectedCount;
                hint("Referensi obat tersimpan ✓", LEVEL_OK);
                step = Step.DONE;
            } else {
                hint("Bagus! Geser/putar sedikit tangan Anda", LEVEL_OK);
            }
        }
    }

    // ------------------------------------------------------------------ FACE steps

    private void onFaceFrame(long t, Img img, FaceObs f) {
        boolean has = f != null && f.present && f.ed > 4;
        lastFace = has ? f : null;
        if (has) {
            if (lastFaceT >= 0 && step != Step.LIVENESS) {
                long gap = t - lastFaceT;
                long max = step == Step.DRINK ? DRINK_GAP_MAX : FACE_GAP_MAX;
                if (gap > max) {
                    continuityBreaks++;
                    notes.add("Wajah hilang " + (gap / 1000) + " detik, pemeriksaan wajah diulang");
                    hint("Wajah sempat hilang terlalu lama — ulangi pemeriksaan wajah", LEVEL_WARN);
                    lastFaceT = t;
                    go(Step.LIVENESS, t);
                    return;
                }
            }
            lastFaceT = t;
            lastMouth = FrameAnalyzer.analyzeMouth(img, f.mx, f.my, f.ed, 1f, pillColor);
        } else {
            lastMouth = null;
        }
        switch (step) {
            case LIVENESS:
                liveness(t, img, f, has);
                break;
            case TO_MOUTH:
                toMouth(t, img, f, has);
                break;
            case DRINK:
                drink(t, has);
                break;
            case MOUTH:
                mouth(t, has);
                break;
            default:
                break;
        }
    }

    private void liveness(long t, Img img, FaceObs f, boolean has) {
        if (pillDoneT > 0 && lastFaceT < 0 && t - pillDoneT > 25000 && !notes.contains("Jeda lama antara tahap obat dan wajah")) {
            notes.add("Jeda lama antara tahap obat dan wajah");
        }
        if (!has) {
            hint("Wajah belum terdeteksi. Pegang HP setinggi wajah, lepas masker/kacamata gelap", LEVEL_INFO);
            holdProgress = 0;
            moveFrames = 0;
            return;
        }
        if (baseSamples.size() < 4) {
            baseSamples.add(lastMouth);
            FrameAnalyzer.MouthStats near = FrameAnalyzer.analyzeMouth(img, f.mx, f.my, f.ed, 1.9f, pillColor);
            baseNearSamples.add(near.pill);
            baseX = (baseX * (baseSamples.size() - 1) + f.mx) / baseSamples.size();
            holdProgress = baseSamples.size() / 8f;
            hint("Wajah terdeteksi — tetap diam sebentar", LEVEL_OK);
            if (baseSamples.size() == 4) {
                baseMouth = averageMouth(baseSamples);
                float s = 0;
                for (float v : baseNearSamples) s += v;
                baseNear = s / baseNearSamples.size();
            }
            return;
        }
        String dir = liveDirection < 0 ? "KIRI" : "KANAN";
        instruction = "Gerakkan kepala perlahan ke " + dir + " (sesuai arah panah)";
        float shift = (f.mx - baseX) / img.w;
        if (shift * liveDirection > 0.09f) moveFrames++;
        else moveFrames = Math.max(0, moveFrames - 1);
        holdProgress = 0.5f + Math.min(0.5f, Math.max(0f, shift * liveDirection) / 0.09f * 0.5f);
        if (moveFrames >= 2) {
            livenessPassed = true;
            hint("Wajah asli terkonfirmasi ✓", LEVEL_OK);
            go(Step.TO_MOUTH, t);
            return;
        }
        if (t - stepStart > LIVE_TIMEOUT_HINT) hint("Gerakkan kepala lebih jauh ke " + dir.toLowerCase(), LEVEL_WARN);
        else hint("Ikuti arah panah", LEVEL_INFO);
    }

    private static FrameAnalyzer.MouthStats averageMouth(List<FrameAnalyzer.MouthStats> l) {
        FrameAnalyzer.MouthStats a = new FrameAnalyzer.MouthStats();
        int n = 0;
        for (FrameAnalyzer.MouthStats m : l) {
            if (m == null || !m.valid) continue;
            n++;
            a.dark += m.dark;
            a.red += m.red;
            a.pill += m.pill;
            for (int i = 0; i < 27; i++) a.hist[i] += m.hist[i];
        }
        if (n == 0) return a;
        a.valid = true;
        a.dark /= n;
        a.red /= n;
        a.pill /= n;
        for (int i = 0; i < 27; i++) a.hist[i] /= n;
        return a;
    }

    private void toMouth(long t, Img img, FaceObs f, boolean has) {
        long el = t - stepStart;
        if (has) {
            FrameAnalyzer.MouthStats near = FrameAnalyzer.analyzeMouth(img, f.mx, f.my, f.ed, 1.9f, pillColor);
            float occ = baseMouth != null && lastMouth != null && lastMouth.valid
                    ? FrameAnalyzer.histDistance(lastMouth.hist, baseMouth.hist) : 0f;
            boolean pillNear = near.pill - baseNear > 0.025f;
            boolean occluded = occ > 0.35f;
            if (pillNear) {
                nearFrames++;
                if (nearFrames >= 2) pillSeenNearMouth = true;
            } else nearFrames = 0;
            if (occluded) {
                occFrames++;
                if (occFrames >= 2) occludedNearMouth = true;
            } else occFrames = 0;
            boolean clear = !pillNear && !occluded;
            if ((pillSeenNearMouth || occludedNearMouth) && clear) clearFrames++;
            else clearFrames = 0;
            holdProgress = pillSeenNearMouth || occludedNearMouth ? 0.5f + Math.min(0.5f, clearFrames / 4f) : 0.15f;
            if (pillSeenNearMouth && clearFrames >= 2) {
                toMouthCredit = 1f;
                hint("Obat masuk ke mulut ✓", LEVEL_OK);
                go(cfg.requireWater ? Step.DRINK : Step.MOUTH, t);
                return;
            }
            if (!pillSeenNearMouth && occludedNearMouth && clearFrames >= 2 && el > 1500) {
                toMouthCredit = pillColor != null && pillColor.isColoured() ? 0.5f : 0.7f;
                notes.add("Gerakan tangan ke mulut terdeteksi, obat tidak terlihat jelas");
                hint("Gerakan ke mulut terdeteksi ✓", LEVEL_OK);
                go(cfg.requireWater ? Step.DRINK : Step.MOUTH, t);
                return;
            }
            if (pillSeenNearMouth) hint("Obat terlihat di dekat mulut…", LEVEL_OK);
            else hint("Dekatkan obat ke bibir, tetap menghadap kamera", LEVEL_INFO);
        } else {
            hint("Usahakan wajah tetap terlihat kamera", LEVEL_INFO);
        }
        if (el > TO_MOUTH_TIMEOUT) {
            toMouthCredit = 0f;
            notes.add("Gerakan memasukkan obat ke mulut tidak terdeteksi kamera");
            go(cfg.requireWater ? Step.DRINK : Step.MOUTH, t);
        }
    }

    private void drink(long t, boolean has) {
        long el = t - stepStart;
        if (has) {
            if (faceLostAt > 0) {
                long gap = t - faceLostAt;
                faceLostAt = -1;
                if (gap >= 700 && gap <= DRINK_GAP_MAX && el > 1000) {
                    drinkDone = true;
                    hint("Minum air terdeteksi ✓", LEVEL_OK);
                    go(Step.MOUTH, t);
                    return;
                }
            }
            float occ = baseMouth != null && lastMouth != null && lastMouth.valid
                    ? FrameAnalyzer.histDistance(lastMouth.hist, baseMouth.hist) : 0f;
            if (occ > 0.35f) {
                if (occSince < 0) occSince = t;
                occFrames++;
            } else {
                occSince = -1;
                occFrames = 0;
            }
            holdProgress = occSince < 0 ? 0.1f : Math.min(1f, (t - occSince) / 900f);
            if (occSince >= 0 && t - occSince >= 900 && occFrames >= 3) {
                drinkDone = true;
                hint("Minum air terdeteksi ✓", LEVEL_OK);
                go(Step.MOUTH, t);
                return;
            }
            hint(el > DRINK_HINT ? "Tidak terdeteksi? Tekan \"Lewati\" bila obat tidak perlu air"
                    : "Dekatkan gelas ke mulut dan minum", el > DRINK_HINT ? LEVEL_WARN : LEVEL_INFO);
        } else {
            if (faceLostAt < 0) faceLostAt = t;
            holdProgress = 0.5f;
            hint("Sedang minum… kembali menghadap kamera setelah selesai", LEVEL_INFO);
        }
    }

    private void mouth(long t, boolean has) {
        if (!has || lastMouth == null || !lastMouth.valid || baseMouth == null) {
            openSince = -1;
            openFrames = 0;
            holdProgress = 0;
            hint("Hadapkan wajah ke kamera", LEVEL_INFO);
            return;
        }
        float dDark = lastMouth.dark - baseMouth.dark;
        float dRed = lastMouth.red - baseMouth.red;
        boolean open = dDark > 0.07f || (dRed > 0.12f && lastMouth.dark > 0.03f) || (lastMouth.dark > 0.18f && dDark > 0.04f);
        boolean pillVisible = pillColor != null && pillColor.isColoured() && lastMouth.pill - baseMouth.pill > 0.03f;
        if (open && pillVisible) {
            openSince = -1;
            openFrames = 0;
            holdProgress = 0;
            hint("Obat masih terlihat di mulut — telan dahulu dengan air", LEVEL_DANGER);
            return;
        }
        if (open) {
            if (openSince < 0) openSince = t;
            openFrames++;
            holdProgress = Math.min(1f, (t - openSince) / (float) MOUTH_HOLD);
            hint("Mulut terbuka — tahan…", LEVEL_OK);
            if (t - openSince >= MOUTH_HOLD && openFrames >= 3) {
                mouthPassed = true;
                pendingCapture = Capture.MOUTH;
                hint("Mulut bersih, obat sudah ditelan ✓", LEVEL_OK);
                step = Step.DONE;
            }
        } else {
            openSince = -1;
            openFrames = 0;
            holdProgress = 0;
            hint("Buka mulut lebih lebar", LEVEL_INFO);
        }
    }

    // ------------------------------------------------------------------ result

    public static final int STATUS_VERIFIED = 1, STATUS_REVIEW = 2, STATUS_FAILED = 3;

    /** Ingestion score 0..100. */
    public float ingestionScore() {
        float s = 0;
        if (livenessPassed) s += 25;
        s += 25 * toMouthCredit;
        if (!cfg.requireWater || drinkDone) s += 15;
        if (mouthPassed) s += 35;
        return s;
    }

    public float totalScore() {
        float pill = Math.max(0, pillScore);
        float total = 0.45f * pill + 0.55f * ingestionScore();
        if (screenSuspected) total -= 15;
        total -= 7 * Math.min(2, continuityBreaks);
        return Math.max(0, Math.min(100, total));
    }

    public int status() {
        if (step != Step.DONE) return STATUS_FAILED;
        float total = totalScore();
        if (total >= 80 && pillScore >= 68 && livenessPassed && mouthPassed && !screenSuspected) return STATUS_VERIFIED;
        if (total >= 55 && pillScore > 0) return STATUS_REVIEW;
        return STATUS_FAILED;
    }

    public long elapsedMs() {
        return t0 < 0 ? 0 : lastT - t0;
    }

    public int frameCount() {
        return frames;
    }
}

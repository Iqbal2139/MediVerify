import com.mediverify.app.vision.*;
import java.util.Random;

public class VisionTest {
    static Random rnd = new Random(1);
    static int W = 240, H = 320;
    static float CX = 120, CY = 150, R = 85;

    static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    static int col(double r, double g, double b, double[] light) {
        double n = rnd.nextGaussian() * 4;
        return (clamp(r * light[0] + n) << 16) | (clamp(g * light[1] + n) << 8) | clamp(b * light[2] + n);
    }

    /** palm with pills. pills: {cx, cy, a, b, angle, r,g,b} */
    static Img palm(double[][] pills, double[] light) {
        Img im = new Img(W, H);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double dx = (x - CX) / 95.0, dy = (y - CY) / 120.0;
            boolean inPalm = dx * dx + dy * dy < 1;
            int c = inPalm ? col(205 - 20 * dy, 150 - 15 * dy, 125 - 10 * dy, light) : col(70, 90, 120, light);
            for (double[] p : pills) {
                double ca = Math.cos(p[4]), sa = Math.sin(p[4]);
                double ux = (x - p[0]) * ca + (y - p[1]) * sa, uy = -(x - p[0]) * sa + (y - p[1]) * ca;
                double e = (ux * ux) / (p[2] * p[2]) + (uy * uy) / (p[3] * p[3]);
                if (e < 1) c = col(p[5] * (1 - 0.15 * e), p[6] * (1 - 0.15 * e), p[7] * (1 - 0.15 * e), light);
            }
            im.px[y * W + x] = c;
        }
        return im;
    }

    static double[] NEUTRAL = {1, 1, 1}, WARM = {1.12, 1.0, 0.78}, COOL = {0.85, 0.97, 1.15}, DIM = {0.6, 0.6, 0.6};
    static double[] WHITE_OVAL = {110, 140, 13, 7, 0.4, 240, 238, 232};
    static double[] YELLOW_ROUND = {120, 150, 9, 9, 0, 235, 205, 60};
    static double[] PINK_ROUND = {120, 150, 9, 9, 0, 235, 140, 170};

    static float area() { return (float) (Math.PI * R * R); }

    static PillProfile profile(Img im) {
        PillScan s = FrameAnalyzer.scanPalm(im, CX, CY, R);
        if (s.blobs.isEmpty()) return null;
        return PillProfile.fromBlob(s, s.blobs.get(0), area());
    }

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "PASS " : "FAIL ") + msg);
        if (!ok) failures++;
    }
    static int failures = 0;

    public static void main(String[] a) {
        // --- detection
        PillScan s = FrameAnalyzer.scanPalm(palm(new double[][]{WHITE_OVAL}, NEUTRAL), CX, CY, R);
        check(s.palmPresent && s.blobs.size() == 1, "white oval detected: palm=" + s.palmPresent + " blobs=" + s.blobs.size() + " ratio=" + s.palmRatio + " screen=" + s.screenScore);
        if (!s.blobs.isEmpty()) System.out.println("   elong=" + s.blobs.get(0).elongation + " area=" + s.blobs.get(0).area);
        s = FrameAnalyzer.scanPalm(palm(new double[][]{}, NEUTRAL), CX, CY, R);
        check(s.palmPresent && s.blobs.isEmpty(), "empty palm -> no blobs (" + s.blobs.size() + ")");
        s = FrameAnalyzer.scanPalm(palm(new double[][]{PINK_ROUND}, NEUTRAL), CX, CY, R);
        check(s.blobs.size() == 1, "pink pill not swallowed by skin mask (" + s.blobs.size() + ")");
        s = FrameAnalyzer.scanPalm(palm(new double[][]{YELLOW_ROUND, {150, 130, 9, 9, 0, 235, 205, 60}}, NEUTRAL), CX, CY, R);
        check(s.blobs.size() == 2, "two yellow pills counted (" + s.blobs.size() + ")");
        Img bg = new Img(W, H);
        for (int i = 0; i < bg.px.length; i++) bg.px[i] = col(70, 90, 120, NEUTRAL);
        s = FrameAnalyzer.scanPalm(bg, CX, CY, R);
        check(!s.palmPresent, "background only -> no palm");

        // --- lighting robustness
        PillProfile refW = profile(palm(new double[][]{WHITE_OVAL}, NEUTRAL));
        PillProfile refY = profile(palm(new double[][]{YELLOW_ROUND}, NEUTRAL));
        for (double[] L : new double[][]{NEUTRAL, WARM, COOL, DIM}) {
            PillProfile w = profile(palm(new double[][]{WHITE_OVAL}, L));
            PillProfile y = profile(palm(new double[][]{YELLOW_ROUND}, L));
            float sww = w.similarity(refW), swy = w.similarity(refY), syy = y.similarity(refY), syw = y.similarity(refW);
            check(sww > 0.75 && syy > 0.75 && swy < 0.5 && syw < 0.5,
                String.format("light %s: same W %.2f Y %.2f | cross W~Y %.2f Y~W %.2f", java.util.Arrays.toString(L), sww, syy, swy, syw));
        }
        check(refW.colorCategory() == 0, "white category=" + PillProfile.COLORS[refW.colorCategory()] + " shape=" + PillProfile.SHAPES[refW.shapeCategory()]);
        check(refY.colorCategory() == 1, "yellow category=" + PillProfile.COLORS[refY.colorCategory()] + " shape=" + PillProfile.SHAPES[refY.shapeCategory()]);
        PillProfile pk = profile(palm(new double[][]{PINK_ROUND}, NEUTRAL));
        System.out.println("   pink category=" + PillProfile.COLORS[pk.colorCategory()]);
        PillProfile rt = PillProfile.parse(refW.serialize());
        check(rt != null && Math.abs(rt.similarity(refW) - 1) < 0.02, "serialize roundtrip");

        // --- full session (verify mode, with reference)
        VerificationSession.Config cfg = new VerificationSession.Config();
        cfg.ref = refY;
        cfg.expectedCount = 1;
        cfg.seed = 42;
        VerificationSession vs = new VerificationSession(cfg);
        long t = 0;
        Img pillImg = palm(new double[][]{YELLOW_ROUND}, WARM);
        for (int i = 0; i < 20 && vs.step == VerificationSession.Step.PILL; i++, t += 150)
            vs.onFrame(t, pillImg, FrameAnalyzer.scanPalm(pillImg, CX, CY, R), null, area());
        check(vs.step == VerificationSession.Step.LIVENESS, "pill step passed, score=" + vs.pillScore + " hint=" + vs.hint);
        check(vs.pollCapture() == VerificationSession.Capture.PILL, "pill capture requested");

        int dir = vs.liveDirection;
        // face frames: closed mouth
        float fx = 120, fy = 120, ed = 50;
        for (int i = 0; i < 6; i++, t += 150) vs.onFrame(t, face(fx, fy, ed, 0, false), null, new VerificationSession.FaceObs(fx, fy, ed), area());
        for (int i = 0; i < 4 && vs.step == VerificationSession.Step.LIVENESS; i++, t += 150) {
            float nx = fx + dir * 30;
            vs.onFrame(t, face(nx, fy, ed, 0, false), null, new VerificationSession.FaceObs(nx, fy, ed), area());
        }
        check(vs.step == VerificationSession.Step.TO_MOUTH, "liveness passed (" + vs.step + ") " + vs.hint);
        // pill near mouth then gone
        for (int i = 0; i < 3; i++, t += 150) vs.onFrame(t, face(fx, fy, ed, 0, true), null, new VerificationSession.FaceObs(fx, fy, ed), area());
        for (int i = 0; i < 4 && vs.step == VerificationSession.Step.TO_MOUTH; i++, t += 150) vs.onFrame(t, face(fx, fy, ed, 0, false), null, new VerificationSession.FaceObs(fx, fy, ed), area());
        check(vs.step == VerificationSession.Step.DRINK && vs.toMouthCredit == 1f, "to-mouth passed (" + vs.step + ", credit " + vs.toMouthCredit + ")");
        // drink: face lost 2s then back
        for (int i = 0; i < 3; i++, t += 150) vs.onFrame(t, face(fx, fy, ed, 0, false), null, new VerificationSession.FaceObs(fx, fy, ed), area());
        for (int i = 0; i < 14; i++, t += 150) vs.onFrame(t, bg, null, null, area());
        vs.onFrame(t, face(fx, fy, ed, 0, false), null, new VerificationSession.FaceObs(fx, fy, ed), area()); t += 150;
        check(vs.step == VerificationSession.Step.MOUTH, "drink passed (" + vs.step + ")");
        // mouth open with pill still inside -> must not pass
        for (int i = 0; i < 8; i++, t += 150) vs.onFrame(t, face(fx, fy, ed, 2, false), null, new VerificationSession.FaceObs(fx, fy, ed), area());
        check(vs.step == VerificationSession.Step.MOUTH, "pill still in mouth blocks pass: " + vs.hint);
        for (int i = 0; i < 10 && vs.step == VerificationSession.Step.MOUTH; i++, t += 150) vs.onFrame(t, face(fx, fy, ed, 1, false), null, new VerificationSession.FaceObs(fx, fy, ed), area());
        check(vs.step == VerificationSession.Step.DONE, "mouth open passed (" + vs.step + ")");
        System.out.println("   pill=" + vs.pillScore + " ingest=" + vs.ingestionScore() + " total=" + vs.totalScore() + " status=" + vs.status() + " notes=" + vs.notes);
        check(vs.status() == VerificationSession.STATUS_VERIFIED, "status verified");

        // --- wrong pill raises alert
        VerificationSession w = new VerificationSession(cfg);
        Img wrongImg = palm(new double[][]{WHITE_OVAL}, NEUTRAL);
        VerificationSession.Alert seen = VerificationSession.Alert.NONE;
        for (int i = 0; i < 25; i++) {
            w.onFrame(i * 150L, wrongImg, FrameAnalyzer.scanPalm(wrongImg, CX, CY, R), null, area());
            VerificationSession.Alert al = w.pollAlert();
            if (al != VerificationSession.Alert.NONE) seen = al;
        }
        check(w.step == VerificationSession.Step.PILL && seen == VerificationSession.Alert.WRONG_PILL, "wrong pill blocked + alert (" + seen + ")");
        // --- dose mismatch
        VerificationSession d = new VerificationSession(cfg);
        Img two = palm(new double[][]{YELLOW_ROUND, {150, 120, 9, 9, 0, 235, 205, 60}}, NEUTRAL);
        seen = VerificationSession.Alert.NONE;
        for (int i = 0; i < 25; i++) {
            d.onFrame(i * 150L, two, FrameAnalyzer.scanPalm(two, CX, CY, R), null, area());
            VerificationSession.Alert al = d.pollAlert();
            if (al != VerificationSession.Alert.NONE) seen = al;
        }
        check(d.step == VerificationSession.Step.PILL && seen == VerificationSession.Alert.DOSE_MISMATCH, "dose mismatch alert (" + seen + ", count " + d.detectedCount + ")");
        // --- declared (no ref)
        VerificationSession.Config c2 = new VerificationSession.Config();
        c2.declColor = 0; c2.declShape = 1;
        VerificationSession dv = new VerificationSession(c2);
        Img wi = palm(new double[][]{WHITE_OVAL}, COOL);
        for (int i = 0; i < 20; i++) dv.onFrame(i * 150L, wi, FrameAnalyzer.scanPalm(wi, CX, CY, R), null, area());
        check(dv.step == VerificationSession.Step.LIVENESS, "declared white oval accepted (" + dv.hint + ")");
        // --- enrol
        VerificationSession.Config ce = new VerificationSession.Config();
        ce.enroll = true;
        VerificationSession en = new VerificationSession(ce);
        for (int i = 0; i < 60 && !en.isFinished(); i++) {
            Img im = palm(new double[][]{{110 + (i % 5), 140, 13, 7, 0.4 + i * 0.02, 240, 238, 232}}, NEUTRAL);
            en.onFrame(i * 150L, im, FrameAnalyzer.scanPalm(im, CX, CY, R), null, area());
        }
        check(en.step == VerificationSession.Step.DONE && en.sessionProfile.similarity(refW) > 0.85, "enrolment done, sim to ref " + (en.sessionProfile == null ? -1 : en.sessionProfile.similarity(refW)));

        // --- screen moire
        Img scr = palm(new double[][]{YELLOW_ROUND}, NEUTRAL);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((y / 2) % 2 == 0) {
            int c = scr.px[y * W + x];
            scr.px[y * W + x] = (clamp(Img.r(c) * 0.75) << 16) | (clamp(Img.g(c) * 0.75) << 8) | clamp(Img.b(c) * 0.75);
        }
        float ss = FrameAnalyzer.scanPalm(scr, CX, CY, R).screenScore;
        float sn = FrameAnalyzer.scanPalm(palm(new double[][]{YELLOW_ROUND}, NEUTRAL), CX, CY, R).screenScore;
        check(ss > 0.8 && sn < 0.5, "screen score striped=" + ss + " normal=" + sn);
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILURES");
    }

    /** Face image: skin oval, eyes, mouth state 0 closed, 1 open (dark + tongue), 2 open with yellow pill; pillNear draws pill by lips. */
    static Img face(float fx, float fy, float ed, int mouth, boolean pillNear) {
        Img im = new Img(W, H);
        float mcx = fx, mcy = fy + 1.1f * ed;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double dx = (x - fx) / (ed * 1.3), dy = (y - (fy + 0.5 * ed)) / (ed * 1.8);
            int c = dx * dx + dy * dy < 1 ? col(200, 150, 125, NEUTRAL) : col(60, 70, 80, NEUTRAL);
            double mx = (x - mcx) / (ed * 0.45), my = (y - mcy) / (ed * (mouth == 0 ? 0.08 : 0.3));
            if (mx * mx + my * my < 1) {
                if (mouth == 0) c = col(170, 70, 80, NEUTRAL);
                else {
                    double inner = mx * mx + my * my;
                    c = inner < 0.6 ? (y > mcy ? col(200, 80, 95, NEUTRAL) : col(30, 15, 15, NEUTRAL)) : col(170, 70, 80, NEUTRAL);
                    if (mouth == 2 && Math.abs(x - mcx) < 5 && Math.abs(y - mcy - 3) < 5) c = col(235, 205, 60, NEUTRAL);
                }
            }
            if (pillNear && Math.abs(x - (mcx + 12)) < 8 && Math.abs(y - mcy) < 8) c = col(235, 205, 60, NEUTRAL);
            im.px[y * W + x] = c;
        }
        return im;
    }
}

package com.mediverify.app.vision;

import java.util.List;
import java.util.Locale;

/**
 * Lighting-normalised visual signature of a pill. Colour is white-balanced against the palm the
 * pill lies on (the palm acts as a grey card), so the same pill gives a similar signature under
 * warm or cool light.
 */
public final class PillProfile {
    /** Canonical palm colour all frames are normalised to. */
    static final float SKIN_R = 190f, SKIN_G = 140f, SKIN_B = 115f;

    public static final String[] COLORS = {"Putih", "Kuning", "Oranye", "Merah", "Merah Muda", "Cokelat",
            "Hijau", "Biru", "Ungu", "Hitam/Gelap"};
    public static final String[] SHAPES = {"Bulat", "Oval/Kaplet", "Kapsul"};

    public float rn, gn, bright, elong, relSize;
    /** Raw (non-normalised) mean colour seen in this session, for tracking the pill near the mouth. */
    public float rawR, rawG, rawB;
    public int count;

    public PillProfile() {}

    public PillProfile(float rn, float gn, float bright, float elong, float relSize) {
        this.rn = rn;
        this.gn = gn;
        this.bright = bright;
        this.elong = elong;
        this.relSize = relSize;
    }

    /** Builds a profile from one blob of a scan. circleArea = pi*r^2 of the target circle. */
    public static PillProfile fromBlob(PillScan s, PillScan.Blob bl, float circleArea) {
        PillProfile p = new PillProfile();
        float gr = SKIN_R / Math.max(20f, s.palmR);
        float gg = SKIN_G / Math.max(20f, s.palmG);
        float gb = SKIN_B / Math.max(20f, s.palmB);
        float r = Math.min(255f, bl.r * gr), g = Math.min(255f, bl.g * gg), b = Math.min(255f, bl.b * gb);
        float sum = Math.max(1f, r + g + b);
        p.rn = r / sum;
        p.gn = g / sum;
        p.bright = sum / (3f * 255f);
        p.elong = bl.elongation;
        p.relSize = bl.area / Math.max(1f, circleArea);
        p.rawR = bl.r;
        p.rawG = bl.g;
        p.rawB = bl.b;
        p.count = 1;
        return p;
    }

    /** Area-weighted average of several profiles. */
    public static PillProfile average(List<PillProfile> list) {
        PillProfile a = new PillProfile();
        if (list.isEmpty()) return a;
        float wsum = 0;
        for (PillProfile p : list) {
            float w = Math.max(1e-4f, p.relSize);
            a.rn += p.rn * w;
            a.gn += p.gn * w;
            a.bright += p.bright * w;
            a.elong += p.elong * w;
            a.rawR += p.rawR * w;
            a.rawG += p.rawG * w;
            a.rawB += p.rawB * w;
            a.relSize += p.relSize;
            a.count += p.count;
            wsum += w;
        }
        a.rn /= wsum;
        a.gn /= wsum;
        a.bright /= wsum;
        a.elong /= wsum;
        a.rawR /= wsum;
        a.rawG /= wsum;
        a.rawB /= wsum;
        a.relSize /= list.size();
        a.count = Math.round(a.count / (float) list.size());
        return a;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 0..1 similarity to an enrolled reference. */
    public float similarity(PillProfile ref) {
        float dc = (float) Math.hypot(rn - ref.rn, gn - ref.gn);
        float sc = clamp01(1f - dc / 0.075f);
        float sb = clamp01(1f - Math.abs(bright - ref.bright) / 0.28f);
        float se = clamp01(1f - Math.abs((float) Math.log(Math.max(1f, elong) / Math.max(1f, ref.elong))) / 0.45f);
        float ss = ref.relSize > 0 && relSize > 0
                ? clamp01(1f - Math.abs((float) Math.log(relSize / ref.relSize)) / 0.9f) : 0.5f;
        return 0.47f * sc + 0.18f * sb + 0.22f * se + 0.13f * ss;
    }

    /** Colour-only similarity (used when several pills touch and merge into one blob). */
    public float colorSimilarity(PillProfile ref) {
        float dc = (float) Math.hypot(rn - ref.rn, gn - ref.gn);
        float sc = clamp01(1f - dc / 0.075f);
        float sb = clamp01(1f - Math.abs(bright - ref.bright) / 0.28f);
        return (0.47f * sc + 0.18f * sb) / 0.65f;
    }

    /** Colour category of the normalised colour (index into COLORS). */
    public int colorCategory() {
        float bsum = bright * 3f * 255f;
        float r = rn * bsum, g = gn * bsum, b = (1f - rn - gn) * bsum;
        float[] hsv = new float[3];
        Img.hsv(Math.round(Math.min(255, r)), Math.round(Math.min(255, g)), Math.round(Math.min(255, Math.max(0, b))), hsv);
        float h = hsv[0], s = hsv[1], v = hsv[2];
        if (v < 0.25f) return 9;
        if (s < 0.20f) return v > 0.55f ? 0 : 9;
        if (h < 15f || h >= 345f) return s < 0.45f ? 4 : 3;
        if (h < 40f) return v < 0.55f ? 5 : 2;
        if (h < 70f) return s < 0.30f ? 0 : 1;
        if (h < 170f) return 6;
        if (h < 255f) return 7;
        if (h < 300f) return 8;
        return 4;
    }

    public int shapeCategory() {
        if (elong < 1.35f) return 0;
        if (elong < 2.1f) return 1;
        return 2;
    }

    /** Coarse 0..1 match against the declared colour/shape when no reference photo exists. */
    public float declaredMatch(int colorIdx, int shapeIdx) {
        int c = colorCategory();
        float cs;
        if (colorIdx < 0) cs = 0.6f;
        else if (c == colorIdx) cs = 1f;
        else if (near(c, colorIdx)) cs = 0.6f;
        else cs = 0f;
        float ss;
        int sh = shapeCategory();
        if (shapeIdx < 0) ss = 0.6f;
        else if (sh == shapeIdx) ss = 1f;
        else if (Math.abs(sh - shapeIdx) == 1) ss = 0.5f;
        else ss = 0.1f;
        return 0.62f * cs + 0.38f * ss;
    }

    private static boolean near(int a, int b) {
        int[][] pairs = {{0, 1}, {1, 2}, {2, 5}, {3, 4}, {4, 8}, {7, 8}, {2, 3}, {5, 9}};
        for (int[] p : pairs) if ((p[0] == a && p[1] == b) || (p[0] == b && p[1] == a)) return true;
        return false;
    }

    public String serialize() {
        return String.format(Locale.US, "%.5f;%.5f;%.5f;%.4f;%.6f;%.1f;%.1f;%.1f", rn, gn, bright, elong, relSize,
                rawR, rawG, rawB);
    }

    public static PillProfile parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] p = s.split(";");
            PillProfile r = new PillProfile(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                    Float.parseFloat(p[3]), Float.parseFloat(p[4]));
            if (p.length >= 8) {
                r.rawR = Float.parseFloat(p[5]);
                r.rawG = Float.parseFloat(p[6]);
                r.rawB = Float.parseFloat(p[7]);
            }
            r.count = 1;
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

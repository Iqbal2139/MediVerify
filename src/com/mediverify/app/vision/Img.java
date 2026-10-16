package com.mediverify.app.vision;

/** Upright RGB frame (0xRRGGBB packed), oriented exactly like the on-screen preview. */
public final class Img {
    public final int w, h;
    public final int[] px;

    public Img(int w, int h) {
        this.w = w;
        this.h = h;
        this.px = new int[w * h];
    }

    public Img(int w, int h, int[] px) {
        this.w = w;
        this.h = h;
        this.px = px;
    }

    public static int r(int c) { return (c >> 16) & 0xff; }
    public static int g(int c) { return (c >> 8) & 0xff; }
    public static int b(int c) { return c & 0xff; }

    /** HSV with h in [0,360), s and v in [0,1]. */
    public static void hsv(int r, int g, int b, float[] out) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        float v = max / 255f;
        float d = max - min;
        float s = max == 0 ? 0f : d / max;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = 60f * (((g - b) / d) % 6f);
        else if (max == g) h = 60f * (((b - r) / d) + 2f);
        else h = 60f * (((r - g) / d) + 4f);
        if (h < 0) h += 360f;
        out[0] = h;
        out[1] = s;
        out[2] = v;
    }

    public static int luma(int c) {
        return (77 * r(c) + 150 * g(c) + 29 * b(c)) >> 8;
    }
}

package com.mediverify.app.vision;

import java.util.ArrayList;
import java.util.List;

/** Result of analysing the palm area for pills in one frame. */
public final class PillScan {
    public boolean palmPresent;
    /** Fraction of the target circle covered by palm skin. */
    public float palmRatio;
    public int palmPixels;
    /** Mean palm colour, used for colour normalisation (white balance). */
    public float palmR, palmG, palmB;
    public final List<Blob> blobs = new ArrayList<>();
    /** Periodic banding / moire score of the region (0..1). High = probably a screen. */
    public float screenScore;
    /** Mean luminance inside the circle (0..255). */
    public float meanLuma;

    public static final class Blob {
        public int area;
        public float cx, cy;
        public int minX, minY, maxX, maxY;
        /** Ratio major/minor axis from second moments (1 = round). */
        public float elongation;
        /** area / bounding box area. */
        public float fill;
        /** Jumlah piksel tepi (perimeter) objek */
        public int perimeter;
        /** Rasio kebulatan objek: 4*pi*area / (perimeter^2). Bulat sempurna = 1.0 */
        public float circularity;
        public float r, g, b;
    }

    public int totalBlobArea() {
        int a = 0;
        for (Blob bl : blobs) a += bl.area;
        return a;
    }
}

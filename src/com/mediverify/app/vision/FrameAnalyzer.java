package com.mediverify.app.vision;

/**
 * Pure-Java image analysis used by the verification flow. No Android dependencies so it can be
 * unit-tested on a desktop JVM.
 */
public final class FrameAnalyzer {
    private FrameAnalyzer() {}

    // ---------------------------------------------------------------- palm & pill detection

    /**
     * Finds pills lying on a palm inside the circle (cx, cy, rad). Pills are detected as compact
     * non-skin "holes" fully enclosed by palm skin. Skin is first found with a fixed YCbCr rule and
     * then refined adaptively around the palm's own mean chroma, so pink/orange pills are not
     * swallowed by the skin mask.
     */
    public static PillScan scanPalm(Img img, float cx, float cy, float rad) {
        PillScan s = new PillScan();
        final int w = img.w, h = img.h;
        int x0 = Math.max(0, (int) Math.floor(cx - rad));
        int x1 = Math.min(w - 1, (int) Math.ceil(cx + rad));
        int y0 = Math.max(0, (int) Math.floor(cy - rad));
        int y1 = Math.min(h - 1, (int) Math.ceil(cy + rad));
        if (x1 <= x0 || y1 <= y0) return s;
        final int bw = x1 - x0 + 1, bh = y1 - y0 + 1, n = bw * bh;
        final float r2 = rad * rad;
        final float inner = Math.max(1f, rad - 2.5f);
        final float inner2 = inner * inner;

        byte[] state = new byte[n]; // 0 = outside circle, 1 = skin, 2 = non-skin
        int[] yy = new int[n], cbs = new int[n], crs = new int[n];
        
        // Primitive arrays untuk Map-Reduce (mengakomodasi thread-safety tanpa objek)
        int[] row_roi = new int[bh], row_rule = new int[bh];
        long[] row_sy = new long[bh], row_scb = new long[bh], row_scr = new long[bh], row_luma = new long[bh];
        
        // Multi-core parallel processing
        java.util.stream.IntStream.range(0, bh).parallel().forEach(j -> {
            int py = y0 + j;
            float dy = py - cy;
            for (int i = 0; i < bw; i++) {
                int pxx = x0 + i;
                float dx = pxx - cx;
                if (dx * dx + dy * dy > r2) continue;
                int k = j * bw + i;
                int c = img.px[py * w + pxx];
                int r = Img.r(c), g = Img.g(c), b = Img.b(c);
                int Y = (77 * r + 150 * g + 29 * b) >> 8;
                int Cb = 128 + ((-43 * r - 85 * g + 128 * b) >> 8);
                int Cr = 128 + ((128 * r - 107 * g - 21 * b) >> 8);
                yy[k] = Y;
                cbs[k] = Cb;
                crs[k] = Cr;
                state[k] = 2;
                row_roi[j]++;
                row_luma[j] += Y;
                if (Cb >= 77 && Cb <= 127 && Cr >= 133 && Cr <= 173 && Y > 40) {
                    row_rule[j]++;
                    row_sy[j] += Y;
                    row_scb[j] += Cb;
                    row_scr[j] += Cr;
                }
            }
        });
        
        // Reduce hasil dari semua thread
        int roi = 0, rule = 0;
        long sy = 0, scb = 0, scr = 0, lumaSum = 0;
        for (int j = 0; j < bh; j++) {
            roi += row_roi[j]; rule += row_rule[j];
            sy += row_sy[j]; scb += row_scb[j]; scr += row_scr[j]; lumaSum += row_luma[j];
        }
        if (roi == 0) return s;
        s.meanLuma = lumaSum / (float) roi;
        s.screenScore = screenScore(img, x0, y0, bw, bh, cx, cy, rad);
        if (rule < roi * 0.18f) {
            s.palmRatio = rule / (float) roi;
            return s;
        }
        float mY = sy / (float) rule, mCb = scb / (float) rule, mCr = scr / (float) rule;

        // Adaptive skin pass (Multi-core parallel processing)
        int[] row_skin = new int[bh];
        long[] row_pr = new long[bh], row_pg = new long[bh], row_pb = new long[bh];
        
        java.util.stream.IntStream.range(0, bh).parallel().forEach(j -> {
            for (int i = 0; i < bw; i++) {
                int k = j * bw + i;
                if (state[k] == 0) continue;
                boolean isSkin = Math.abs(cbs[k] - mCb) <= 15 && Math.abs(crs[k] - mCr) <= 15
                        && yy[k] >= 0.40f * mY && yy[k] <= Math.max(1.6f * mY, mY + 40);
                if (isSkin) {
                    state[k] = 1; // Thread-safe karena k (index) unik per thread i,j
                    row_skin[j]++;
                    int c = img.px[(y0 + j) * w + x0 + i];
                    row_pr[j] += Img.r(c);
                    row_pg[j] += Img.g(c);
                    row_pb[j] += Img.b(c);
                }
            }
        });
        
        int skin = 0;
        long pr = 0, pg = 0, pb = 0;
        for (int j = 0; j < bh; j++) {
            skin += row_skin[j];
            pr += row_pr[j]; pg += row_pg[j]; pb += row_pb[j];
        }
        s.palmPixels = skin;
        s.palmRatio = skin / (float) roi;
        if (skin == 0) return s;
        s.palmR = pr / (float) skin;
        s.palmG = pg / (float) skin;
        s.palmB = pb / (float) skin;
        s.palmPresent = s.palmRatio >= 0.30f;
        if (!s.palmPresent) return s;

        // Connected components of non-skin pixels (4-connectivity)
        int[] label = new int[n];
        int[] queue = new int[n];
        int minArea = Math.max(8, (int) (roi * 0.0012f));
        int maxArea = (int) (roi * 0.10f);
        int next = 1;
        for (int start = 0; start < n; start++) {
            if (state[start] != 2 || label[start] != 0) continue;
            int lab = next++;
            int head = 0, tail = 0;
            queue[tail++] = start;
            label[start] = lab;
            boolean touches = false;
            long sx = 0, syy = 0, sxx = 0, sYY = 0, sxy = 0, cr = 0, cg = 0, cb = 0;
            int area = 0, perimeter = 0, mnx = Integer.MAX_VALUE, mny = Integer.MAX_VALUE, mxx = -1, mxy = -1;
            while (head < tail) {
                int k = queue[head++];
                int i = k % bw, j = k / bw;
                int ax = x0 + i, ay = y0 + j;
                float dx = ax - cx, dy = ay - cy;
                if (dx * dx + dy * dy >= inner2 || i == 0 || j == 0 || i == bw - 1 || j == bh - 1) touches = true;
                
                // Cek piksel tepi untuk menghitung perimeter objek
                int edges = 0;
                if (i == 0 || state[k - 1] != 2) edges++;
                if (i == bw - 1 || state[k + 1] != 2) edges++;
                if (j == 0 || state[k - bw] != 2) edges++;
                if (j == bh - 1 || state[k + bw] != 2) edges++;
                if (edges > 0) perimeter++;

                area++;
                sx += ax;
                syy += ay;
                sxx += (long) ax * ax;
                sYY += (long) ay * ay;
                sxy += (long) ax * ay;
                int c = img.px[ay * w + ax];
                cr += Img.r(c);
                cg += Img.g(c);
                cb += Img.b(c);
                if (ax < mnx) mnx = ax;
                if (ay < mny) mny = ay;
                if (ax > mxx) mxx = ax;
                if (ay > mxy) mxy = ay;
                if (i > 0) tail = push(state, label, queue, tail, k - 1, lab);
                if (i < bw - 1) tail = push(state, label, queue, tail, k + 1, lab);
                if (j > 0) tail = push(state, label, queue, tail, k - bw, lab);
                if (j < bh - 1) tail = push(state, label, queue, tail, k + bw, lab);
            }
            if (touches || area < minArea || area > maxArea) continue;
            float circularity = (float) (4.0 * Math.PI * area / (perimeter * perimeter));
            // Tolak objek yang bentuknya terlalu berantakan (bukan bulat/oval)
            if (circularity < 0.25f) continue;

            float mx = sx / (float) area, my = syy / (float) area;
            float a = sxx / (float) area - mx * mx;
            float c2 = sYY / (float) area - my * my;
            float bq = sxy / (float) area - mx * my;
            float tr = (a + c2) / 2f;
            float disc = (float) Math.sqrt(((a - c2) / 2f) * ((a - c2) / 2f) + bq * bq);
            float l1 = tr + disc, l2 = Math.max(0.05f, tr - disc);
            float elong = (float) Math.sqrt(l1 / l2);
            float minor = 4f * (float) Math.sqrt(l2);
            float fill = area / (float) ((mxx - mnx + 1) * (mxy - mny + 1));
            if (fill < 0.35f || minor < 2.2f || elong > 6f) continue;
            PillScan.Blob bl = new PillScan.Blob();
            bl.area = area;
            bl.cx = mx;
            bl.cy = my;
            bl.minX = mnx;
            bl.minY = mny;
            bl.maxX = mxx;
            bl.maxY = mxy;
            bl.elongation = elong;
            bl.fill = fill;
            bl.perimeter = perimeter;
            bl.circularity = circularity;
            bl.r = cr / (float) area;
            bl.g = cg / (float) area;
            bl.b = cb / (float) area;
            s.blobs.add(bl);
        }
        return s;
    }

    private static int push(byte[] state, int[] label, int[] queue, int tail, int k, int lab) {
        if (state[k] == 2 && label[k] == 0) {
            label[k] = lab;
            queue[tail++] = k;
        }
        return tail;
    }

    /**
     * Detects strong periodic banding (moire) that appears when a phone/monitor screen is filmed.
     * Returns 0..1 (normalised autocorrelation peak) or 0 when the residual amplitude is tiny.
     */
    static float screenScore(Img img, int x0, int y0, int bw, int bh, float cx, float cy, float rad) {
        float half = rad * 0.7f;
        int ax0 = Math.max(x0, (int) (cx - half)), ax1 = Math.min(x0 + bw - 1, (int) (cx + half));
        int ay0 = Math.max(y0, (int) (cy - half)), ay1 = Math.min(y0 + bh - 1, (int) (cy + half));
        int cw = ax1 - ax0 + 1, ch = ay1 - ay0 + 1;
        if (cw < 24 || ch < 24) return 0f;
        float[] rows = new float[ch], cols = new float[cw];
        for (int y = ay0; y <= ay1; y++) {
            for (int x = ax0; x <= ax1; x++) {
                float l = Img.luma(img.px[y * img.w + x]);
                rows[y - ay0] += l;
                cols[x - ax0] += l;
            }
        }
        for (int i = 0; i < ch; i++) rows[i] /= cw;
        for (int i = 0; i < cw; i++) cols[i] /= ch;
        return Math.max(periodicity(rows), periodicity(cols));
    }

    static float periodicity(float[] p) {
        int n = p.length;
        float[] res = new float[n];
        int win = 4;
        double energy = 0;
        for (int i = 0; i < n; i++) {
            float s = 0;
            int c = 0;
            for (int k = Math.max(0, i - win); k <= Math.min(n - 1, i + win); k++) {
                s += p[k];
                c++;
            }
            res[i] = p[i] - s / c;
            energy += res[i] * res[i];
        }
        double std = Math.sqrt(energy / n);
        if (std < 2.0 || energy <= 0) return 0f;
        float best = 0;
        for (int lag = 2; lag <= Math.min(8, n / 4); lag++) {
            double acc = 0;
            for (int i = 0; i + lag < n; i++) acc += res[i] * res[i + lag];
            float v = (float) (acc / energy * n / (n - lag));
            if (v > best) best = v;
        }
        return Math.max(0f, Math.min(1f, best));
    }

    // ---------------------------------------------------------------- mouth analysis

    /** Colour matcher for the pill seen during this session (same lighting as the face steps). */
    public static final class PillColor {
        public final float rn, gn, bright, sat;

        public PillColor(float r, float g, float b) {
            float sum = Math.max(1f, r + g + b);
            rn = r / sum;
            gn = g / sum;
            bright = sum / 3f;
            float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            sat = mx <= 0 ? 0 : (mx - mn) / mx;
        }

        public boolean isColoured() {
            return sat >= 0.25f;
        }

        public boolean matches(int r, int g, int b) {
            int sum = r + g + b;
            if (sum < 45) return false;
            float prn = r / (float) sum, pgn = g / (float) sum;
            float d = (float) Math.hypot(prn - rn, pgn - gn);
            float br = (sum / 3f) / Math.max(1f, bright);
            if (isColoured()) return d < 0.045f && br > 0.45f && br < 1.7f;
            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            float s = mx == 0 ? 0 : (mx - mn) / (float) mx;
            return d < 0.03f && s < 0.25f && br > 0.7f && br < 1.4f;
        }
    }

    public static final class MouthStats {
        public boolean valid;
        public int n;
        public float dark, red, pill;
        public final float[] hist = new float[27];
        public int x0, y0, x1, y1;
    }

    /**
     * Statistics of the mouth region estimated from the face (eye midpoint + eye distance).
     * scale enlarges the region (1 = mouth only).
     */
    public static MouthStats analyzeMouth(Img img, float mx, float my, float ed, float scale, PillColor pc) {
        MouthStats m = new MouthStats();
        float cy = my + 1.10f * ed;
        float hw = 0.55f * ed * scale, hh = 0.38f * ed * scale;
        int x0 = Math.max(0, (int) (mx - hw)), x1 = Math.min(img.w - 1, (int) (mx + hw));
        int y0 = Math.max(0, (int) (cy - hh)), y1 = Math.min(img.h - 1, (int) (cy + hh));
        m.x0 = x0;
        m.y0 = y0;
        m.x1 = x1;
        m.y1 = y1;
        if (x1 - x0 < 4 || y1 - y0 < 3) return m;
        float[] hsv = new float[3];
        int n = 0, dark = 0, red = 0, pill = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int c = img.px[y * img.w + x];
                int r = Img.r(c), g = Img.g(c), b = Img.b(c);
                Img.hsv(r, g, b, hsv);
                n++;
                if (hsv[2] < 0.22f) dark++;
                else if ((hsv[0] <= 22f || hsv[0] >= 330f) && hsv[1] > 0.30f && hsv[2] > 0.22f) red++;
                if (pc != null && pc.matches(r, g, b)) pill++;
                int bin = (r * 3 / 256) * 9 + (g * 3 / 256) * 3 + (b * 3 / 256);
                m.hist[bin]++;
            }
        }
        m.n = n;
        m.valid = n > 0;
        if (n > 0) {
            m.dark = dark / (float) n;
            m.red = red / (float) n;
            m.pill = pill / (float) n;
            for (int i = 0; i < 27; i++) m.hist[i] /= n;
        }
        return m;
    }

    /** Chi-square distance between two normalised histograms (0 = identical, 1 = disjoint). */
    public static float histDistance(float[] a, float[] b) {
        float d = 0;
        for (int i = 0; i < a.length; i++) {
            float s = a[i] + b[i];
            if (s > 0) d += (a[i] - b[i]) * (a[i] - b[i]) / s;
        }
        return d / 2f;
    }
}

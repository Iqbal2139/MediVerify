package com.mediverify.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Small custom-drawn charts. */
public final class Charts {
    private Charts() {}

    /** Circular progress ring with a centred percentage. */
    public static final class Ring extends View {
        private float value = -1; // 0..100, -1 = no data
        private int color = Ui.PRIMARY, track = 0xFFE2E8F0, textColor = Ui.INK;
        private String caption = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public Ring(Context c) {
            super(c);
        }

        public Ring set(float v, int color, String caption) {
            this.value = v;
            this.color = color;
            this.caption = caption;
            invalidate();
            return this;
        }

        public Ring colors(int track, int text) {
            this.track = track;
            this.textColor = text;
            invalidate();
            return this;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            float stroke = s * 0.10f;
            r.set((w - s) / 2 + stroke / 2, (h - s) / 2 + stroke / 2, (w + s) / 2 - stroke / 2, (h + s) / 2 - stroke / 2);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(track);
            cv.drawArc(r, 0, 360, false, p);
            if (value > 0) {
                p.setColor(color);
                cv.drawArc(r, -90, 360f * Math.min(100, value) / 100f, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(textColor);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(s * 0.24f);
            String t = value < 0 ? "–" : Math.round(value) + "%";
            float cy = h / 2 + (caption.isEmpty() ? s * 0.08f : s * 0.02f);
            cv.drawText(t, w / 2, cy, p);
            if (!caption.isEmpty()) {
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(s * 0.10f);
                p.setAlpha(170);
                cv.drawText(caption, w / 2, cy + s * 0.15f, p);
                p.setAlpha(255);
            }
        }
    }

    /** Vertical bar chart of percentages (one bar per day). value -1 = no scheduled dose. */
    public static final class Bars extends View {
        private float[] values = new float[0];
        private String[] labels = new String[0];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public Bars(Context c) {
            super(c);
        }

        public void set(float[] v, String[] l) {
            values = v;
            labels = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = values.length;
            if (n == 0) return;
            float w = getWidth(), h = getHeight();
            float labelH = Ui.dp(getContext(), 18);
            float top = Ui.dp(getContext(), 16);
            float chartH = h - labelH - top;
            float slot = w / n;
            float bw = Math.min(slot * 0.62f, Ui.dp(getContext(), 26));
            p.setStrokeWidth(Math.max(1, Ui.dp(getContext(), 1)));
            p.setColor(0xFFE2E8F0);
            for (int g = 0; g <= 2; g++) {
                float y = top + chartH * g / 2f;
                cv.drawLine(0, y, w, y, p);
            }
            p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2;
                float v = values[i];
                if (v >= 0) {
                    float bh = Math.max(Ui.dp(getContext(), 3), chartH * Math.min(100, v) / 100f);
                    r.set(cx - bw / 2, top + chartH - bh, cx + bw / 2, top + chartH);
                    p.setColor(v >= 80 ? Ui.SUCCESS : v >= 50 ? Ui.WARN : Ui.DANGER);
                    cv.drawRoundRect(r, bw / 3, bw / 3, p);
                    if (n <= 10) {
                        p.setColor(Ui.MUTED);
                        p.setTextSize(Ui.dp(getContext(), 9.5f));
                        cv.drawText(Math.round(v) + "", cx, top + chartH - bh - Ui.dp(getContext(), 3), p);
                    }
                } else {
                    p.setColor(0xFFF1F5F9);
                    r.set(cx - bw / 2, top + chartH - Ui.dp(getContext(), 3), cx + bw / 2, top + chartH);
                    cv.drawRoundRect(r, 4, 4, p);
                }
                if (i < labels.length && labels[i] != null && (n <= 10 || i % 5 == 0 || i == n - 1)) {
                    p.setColor(Ui.MUTED);
                    p.setTextSize(Ui.dp(getContext(), 10));
                    cv.drawText(labels[i], cx, h - Ui.dp(getContext(), 4), p);
                }
            }
        }
    }
}

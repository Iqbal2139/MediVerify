package com.mediverify.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import com.mediverify.app.ui.Ui;
import com.mediverify.app.vision.PillScan;
import com.mediverify.app.vision.VerificationSession;

/** Draws the guide shapes and live detection feedback on top of the camera preview. */
final class OverlayView extends View {
    // analysis image size (overlay coordinates are scaled from it)
    int imgW = 240, imgH = 320;
    float circleCx, circleCy, circleR; // image coords
    VerificationSession.Step step = VerificationSession.Step.PILL;
    float progress;
    int level;
    PillScan scan;
    int pillClass;
    VerificationSession.FaceObs face;
    int mx0, my0, mx1, my1; // mouth rect (image coords)
    boolean hasMouth;
    int liveDir;
    boolean showArrow;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private long t0 = System.currentTimeMillis();

    OverlayView(Context c) {
        super(c);
    }

    private float sx() {
        return getWidth() / (float) imgW;
    }

    private float sy() {
        return getHeight() / (float) imgH;
    }

    private int levelColor() {
        switch (level) {
            case VerificationSession.LEVEL_OK: return Ui.SUCCESS;
            case VerificationSession.LEVEL_WARN: return Ui.WARN;
            case VerificationSession.LEVEL_DANGER: return Ui.DANGER;
            default: return Color.WHITE;
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float sx = sx(), sy = sy();
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, w, h, Path.Direction.CW);
        if (step == VerificationSession.Step.PILL) {
            float cx = circleCx * sx, cy = circleCy * sy, rr = circleR * sx;
            path.addCircle(cx, cy, rr, Path.Direction.CW);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x8C0F172A);
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 3));
            p.setColor(0x66FFFFFF);
            cv.drawCircle(cx, cy, rr, p);
            p.setColor(levelColor());
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dp(getContext(), 5));
            r.set(cx - rr, cy - rr, cx + rr, cy + rr);
            if (progress > 0) cv.drawArc(r, -90, 360 * progress, false, p);
            if (scan != null) {
                for (PillScan.Blob b : scan.blobs) {
                    int col = pillClass == 1 ? Ui.SUCCESS : pillClass == 3 ? Ui.DANGER : Ui.WARN;
                    p.setColor(col);
                    p.setStrokeWidth(Ui.dp(getContext(), 2.5f));
                    float pad = 3;
                    r.set((b.minX - pad) * sx, (b.minY - pad) * sy, (b.maxX + 1 + pad) * sx, (b.maxY + 1 + pad) * sy);
                    cv.drawRoundRect(r, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), p);
                }
            }
        } else if (step != VerificationSession.Step.DONE && step != VerificationSession.Step.FAILED) {
            float ow = w * 0.62f, oh = h * 0.56f;
            float cx = w / 2, cy = h * 0.46f;
            r.set(cx - ow / 2, cy - oh / 2, cx + ow / 2, cy + oh / 2);
            path.addOval(r, Path.Direction.CW);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x660F172A);
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 3));
            p.setColor(face != null ? levelColor() : 0x99FFFFFF);
            cv.drawOval(r, p);
            if (face != null) {
                // eyes
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xCCFFFFFF);
                float ex = face.mx * sx, ey = face.my * sy, ed = face.ed * sx / 2;
                cv.drawCircle(ex - ed, ey, Ui.dp(getContext(), 3), p);
                cv.drawCircle(ex + ed, ey, Ui.dp(getContext(), 3), p);
                if (hasMouth) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Ui.dp(getContext(), 2));
                    p.setColor(levelColor());
                    r.set(mx0 * sx, my0 * sy, (mx1 + 1) * sx, (my1 + 1) * sy);
                    cv.drawRoundRect(r, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), p);
                }
            }
            if (showArrow) {
                float phase = ((System.currentTimeMillis() - t0) % 1200) / 1200f;
                float ax = w / 2 + liveDir * (w * 0.18f + w * 0.08f * phase);
                float ay = h * 0.46f;
                float s = Ui.dp(getContext(), 26);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.PRIMARY_SOFT);
                Path a = new Path();
                a.moveTo(ax + liveDir * s, ay);
                a.lineTo(ax - liveDir * s * 0.2f, ay - s);
                a.lineTo(ax - liveDir * s * 0.2f, ay + s);
                a.close();
                cv.drawPath(a, p);
                postInvalidateOnAnimation();
            }
            if (progress > 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x55FFFFFF);
                float bw = w * 0.5f, bh = Ui.dp(getContext(), 6);
                r.set(w / 2 - bw / 2, h - Ui.dp(getContext(), 20), w / 2 + bw / 2, h - Ui.dp(getContext(), 20) + bh);
                cv.drawRoundRect(r, bh / 2, bh / 2, p);
                p.setColor(levelColor());
                r.right = r.left + bw * Math.min(1f, progress);
                cv.drawRoundRect(r, bh / 2, bh / 2, p);
            }
        }
    }
}

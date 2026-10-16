package com.mediverify.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Capsule icon tinted with the pill's declared colour. */
public final class PillIconView extends View {
    private int colorIdx;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public PillIconView(Context c, int colorIdx) {
        super(c);
        this.colorIdx = colorIdx;
    }

    public void setColorIdx(int idx) {
        colorIdx = idx;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.PRIMARY_LIGHT);
        cv.drawCircle(w / 2, h / 2, s / 2, p);
        cv.save();
        cv.rotate(-40, w / 2, h / 2);
        float len = s * 0.62f, th = s * 0.26f;
        r.set(w / 2 - len / 2, h / 2 - th / 2, w / 2 + len / 2, h / 2 + th / 2);
        int main = colorIdx >= 0 ? Ui.pillSwatch(colorIdx) : Ui.PRIMARY;
        if (colorIdx == 0) main = 0xFFFFFFFF;
        cv.save();
        cv.clipRect(r.left, r.top, w / 2, r.bottom);
        p.setColor(main);
        cv.drawRoundRect(r, th / 2, th / 2, p);
        cv.restore();
        cv.save();
        cv.clipRect(w / 2, r.top, r.right, r.bottom);
        p.setColor(colorIdx == 0 ? 0xFFE2E8F0 : 0xFFFFFFFF);
        cv.drawRoundRect(r, th / 2, th / 2, p);
        cv.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, s * 0.025f));
        p.setColor(Ui.PRIMARY_DARK);
        cv.drawRoundRect(r, th / 2, th / 2, p);
        cv.drawLine(w / 2, r.top, w / 2, r.bottom, p);
        cv.restore();
    }
}

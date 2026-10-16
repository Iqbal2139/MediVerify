package com.mediverify.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small programmatic design system (no XML layouts, no support libraries). */
public final class Ui {
    private Ui() {}

    public static final int PRIMARY = 0xFF0D9488;
    public static final int PRIMARY_DARK = 0xFF0F766E;
    public static final int PRIMARY_LIGHT = 0xFFF0FDFA;
    public static final int PRIMARY_SOFT = 0xFFCCFBF1;
    public static final int INK = 0xFF0F172A;
    public static final int INK2 = 0xFF334155;
    public static final int MUTED = 0xFF64748B;
    public static final int FAINT = 0xFF94A3B8;
    public static final int BORDER = 0xFFE2E8F0;
    public static final int BG = 0xFFF8FAFC;
    public static final int CARD = 0xFFFFFFFF;
    public static final int SUCCESS = 0xFF10B981;
    public static final int SUCCESS_BG = 0xFFECFDF5;
    public static final int WARN = 0xFFF59E0B;
    public static final int WARN_BG = 0xFFFFFBEB;
    public static final int DANGER = 0xFFEF4444;
    public static final int DANGER_BG = 0xFFFEF2F2;
    public static final int INFO = 0xFF3B82F6;
    public static final int INFO_BG = 0xFFEFF6FF;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static void statusBar(Activity a, int color, boolean lightIcons) {
        Window w = a.getWindow();
        w.setStatusBarColor(color);
        w.setNavigationBarColor(Color.WHITE);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        if (lightIcons) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(flags);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable roundStroke(int color, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = round(color, radiusPx);
        g.setStroke(strokePx, strokeColor);
        return g;
    }

    public static Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    public static TextView h1(Context c, CharSequence s) {
        TextView t = text(c, s, 22, INK, true);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    public static TextView h2(Context c, CharSequence s) {
        TextView t = text(c, s, 17, INK, true);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    public static TextView body(Context c, CharSequence s) {
        return text(c, s, 14, INK2, false);
    }

    public static TextView caption(Context c, CharSequence s) {
        return text(c, s, 12, MUTED, false);
    }

    public static TextView label(Context c, CharSequence s) {
        TextView t = text(c, s, 11, MUTED, true);
        t.setLetterSpacing(0.06f);
        t.setAllCaps(true);
        return t;
    }

    public static final int BTN_PRIMARY = 0, BTN_SECONDARY = 1, BTN_DANGER = 2, BTN_GHOST = 3, BTN_LIGHT = 4;

    public static TextView button(Context c, CharSequence s, int style) {
        TextView b = text(c, s, 15, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        int padH = dp(c, 18), padV = dp(c, 13);
        b.setPadding(padH, padV, padH, padV);
        float r = dp(c, 14);
        int bg, fg, rip;
        GradientDrawable g;
        switch (style) {
            case BTN_SECONDARY:
                g = roundStroke(Color.WHITE, r, dp(c, 1.5f), PRIMARY);
                fg = PRIMARY;
                rip = 0x330D9488;
                break;
            case BTN_DANGER:
                g = round(DANGER, r);
                fg = Color.WHITE;
                rip = 0x55FFFFFF;
                break;
            case BTN_GHOST:
                g = round(0x00000000, r);
                fg = PRIMARY;
                rip = 0x220D9488;
                break;
            case BTN_LIGHT:
                g = round(PRIMARY_LIGHT, r);
                fg = PRIMARY_DARK;
                rip = 0x330D9488;
                break;
            default:
                g = round(PRIMARY, r);
                fg = Color.WHITE;
                rip = 0x55FFFFFF;
                break;
        }
        bg = 0;
        b.setTextColor(fg);
        b.setBackground(ripple(g, rip));
        b.setClickable(true);
        b.setFocusable(true);
        if (bg != 0) b.setBackgroundColor(bg);
        return b;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(roundStroke(CARD, dp(c, 18), dp(c, 1), BORDER));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout.LayoutParams lpMW() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams lpWW() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams lpWeight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** Adds child with top margin (dp). */
    public static <T extends View> T add(LinearLayout parent, T child, float topDp) {
        LinearLayout.LayoutParams lp = parent.getOrientation() == LinearLayout.VERTICAL ? lpMW() : lpWW();
        if (parent.getOrientation() == LinearLayout.VERTICAL) lp.topMargin = dp(parent.getContext(), topDp);
        else lp.leftMargin = dp(parent.getContext(), topDp);
        parent.addView(child, lp);
        return child;
    }

    public static <T extends View> T add(LinearLayout parent, T child, LinearLayout.LayoutParams lp) {
        parent.addView(child, lp);
        return child;
    }

    public static View space(Context c, float dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, dp), dp(c, dp)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    public static TextView chip(Context c, CharSequence s, int bg, int fg) {
        TextView t = text(c, s, 11.5f, fg, true);
        t.setBackground(round(bg, dp(c, 20)));
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        return t;
    }

    public static EditText input(Context c, String hint, String value, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(INK);
        e.setHintTextColor(FAINT);
        e.setInputType(inputType == 0 ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS : inputType);
        e.setSingleLine(true);
        e.setBackground(roundStroke(Color.WHITE, dp(c, 12), dp(c, 1), BORDER));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return e;
    }

    /** A labelled field container. */
    public static LinearLayout field(Context c, String label, View input) {
        LinearLayout l = vbox(c);
        TextView t = text(c, label, 13, INK2, true);
        l.addView(t, lpMW());
        LinearLayout.LayoutParams lp = lpMW();
        lp.topMargin = dp(c, 6);
        l.addView(input, lp);
        return l;
    }

    /** A clickable value box that looks like an input (for pickers). */
    public static TextView selectBox(Context c, String value) {
        TextView t = text(c, value, 15, INK, false);
        t.setBackground(ripple(roundStroke(Color.WHITE, dp(c, 12), dp(c, 1), BORDER), 0x220D9488));
        t.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
        t.setCompoundDrawablePadding(dp(c, 8));
        t.setClickable(true);
        return t;
    }

    /** Standard screen: coloured header with back button and a scrolling body. */
    public static final class Screen {
        public final LinearLayout root, header, body;
        public final ScrollView scroll;
        public final TextView title;

        public Screen(final Activity a, String titleText, boolean back) {
            root = vbox(a);
            root.setBackgroundColor(BG);
            header = hbox(a);
            header.setBackgroundColor(Color.WHITE);
            header.setPadding(dp(a, 8), dp(a, 10), dp(a, 16), dp(a, 10));
            if (back) {
                TextView b = text(a, "←", 22, INK, true);
                b.setGravity(Gravity.CENTER);
                b.setBackground(ripple(round(0, dp(a, 20)), 0x22000000));
                b.setOnClickListener(v -> a.onBackPressed());
                header.addView(b, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));
            } else {
                header.addView(space(a, 8));
            }
            title = h2(a, titleText);
            LinearLayout.LayoutParams tl = lpWeight(1);
            tl.leftMargin = dp(a, 4);
            header.addView(title, tl);
            root.addView(header, lpMW());
            root.addView(divider(a));
            scroll = new ScrollView(a);
            scroll.setFillViewport(true);
            body = vbox(a);
            int p = dp(a, 16);
            body.setPadding(p, p, p, dp(a, 32));
            scroll.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    public static int statusColor(int status) {
        switch (status) {
            case com.mediverify.app.data.DoseLog.VERIFIED: return SUCCESS;
            case com.mediverify.app.data.DoseLog.REVIEW: return WARN;
            case com.mediverify.app.data.DoseLog.MANUAL: return INFO;
            case com.mediverify.app.data.DoseLog.MISSED: return DANGER;
            case com.mediverify.app.data.DoseLog.ATTEMPT_FAILED: return DANGER;
            case com.mediverify.app.data.DoseLog.SKIPPED: return FAINT;
            default: return MUTED;
        }
    }

    public static int statusBg(int status) {
        switch (status) {
            case com.mediverify.app.data.DoseLog.VERIFIED: return SUCCESS_BG;
            case com.mediverify.app.data.DoseLog.REVIEW: return WARN_BG;
            case com.mediverify.app.data.DoseLog.MANUAL: return INFO_BG;
            case com.mediverify.app.data.DoseLog.MISSED:
            case com.mediverify.app.data.DoseLog.ATTEMPT_FAILED: return DANGER_BG;
            default: return 0xFFF1F5F9;
        }
    }

    /** Swatch colour for PillProfile.COLORS entries. */
    public static int pillSwatch(int idx) {
        int[] c = {0xFFF8FAFC, 0xFFFDE047, 0xFFFB923C, 0xFFEF4444, 0xFFF9A8D4, 0xFF92400E, 0xFF22C55E, 0xFF3B82F6,
                0xFFA855F7, 0xFF1F2937};
        return idx >= 0 && idx < c.length ? c[idx] : 0xFFE2E8F0;
    }
}

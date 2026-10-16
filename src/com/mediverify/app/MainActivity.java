package com.mediverify.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mediverify.app.data.Db;
import com.mediverify.app.data.Prefs;
import com.mediverify.app.reminder.Reminders;
import com.mediverify.app.ui.Ui;

public final class MainActivity extends Activity {
    public static final String EXTRA_TAB = "tab";
    private static final int REQ_NOTIF = 41;

    int tab = 0;
    private FrameLayout content;
    private LinearLayout nav;
    Prefs prefs;
    Db db;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        db = Db.get(this);
        Ui.statusBar(this, Ui.BG, false);
        tab = getIntent().getIntExtra(EXTRA_TAB, b != null ? b.getInt("tab", 0) : 0);
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(Ui.divider(this));
        nav = Ui.hbox(this);
        nav.setBackgroundColor(Color.WHITE);
        nav.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        root.addView(nav, Ui.lpMW());
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotif()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.setBool("askednotif", true);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra(EXTRA_TAB)) tab = intent.getIntExtra(EXTRA_TAB, 0);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            db.reconcileMissed(System.currentTimeMillis(), prefs.graceMinutes(), 7);
        } catch (RuntimeException ignored) {
        }
        Reminders.rescheduleAll(this);
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        render();
    }

    void select(int t) {
        tab = t;
        render();
    }

    void render() {
        renderNav();
        content.removeAllViews();
        View v;
        switch (tab) {
            case 1: v = MedsTab.build(this); break;
            case 2: v = RecapTab.build(this); break;
            case 3: v = ProfileTab.build(this); break;
            default: v = HomeTab.build(this); break;
        }
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void renderNav() {
        nav.removeAllViews();
        String[] icons = {"🏠", "💊", "📊", "👤"};
        String[] names = {"Beranda", "Obat", "Rekap", "Profil"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            boolean sel = i == tab;
            LinearLayout item = Ui.vbox(this);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            TextView ic = Ui.text(this, icons[i], 18, sel ? Ui.PRIMARY : Ui.FAINT, false);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(sel ? Ui.round(Ui.PRIMARY_LIGHT, Ui.dp(this, 14)) : null);
            ic.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 2));
            item.addView(ic, Ui.lpWW());
            TextView tv = Ui.text(this, names[i], 11.5f, sel ? Ui.PRIMARY : Ui.MUTED, sel);
            tv.setGravity(Gravity.CENTER);
            item.addView(tv, Ui.lpWW());
            item.setBackground(Ui.ripple(Ui.round(0, Ui.dp(this, 12)), 0x110D9488));
            item.setOnClickListener(v -> select(idx));
            nav.addView(item, Ui.lpWeight(1));
        }
    }

    /** Scrolling page with a big title used by every tab. */
    LinearLayout page(ScrollView[] holder, String title, String subtitle) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = Ui.vbox(this);
        int p = Ui.dp(this, 18);
        body.setPadding(p, Ui.dp(this, 20), p, Ui.dp(this, 28));
        if (title != null) {
            body.addView(Ui.h1(this, title));
            if (subtitle != null) Ui.add(body, Ui.caption(this, subtitle), 2);
        }
        sv.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder[0] = sv;
        return body;
    }

    /** Warning banners for anything that would stop reminders from working. Returns null when all good. */
    View permissionBanner(boolean includeBattery) {
        LinearLayout box = Ui.vbox(this);
        boolean any = false;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && !nm.areNotificationsEnabled()) {
            any = true;
            box.addView(banner("Notifikasi dimatikan", "Pengingat obat tidak akan muncul.", "Aktifkan", v -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startSafe(i);
                }
            }));
        }
        AlarmManager am = getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT >= 31 && am != null && !am.canScheduleExactAlarms()) {
            any = true;
            Ui.add(box, banner("Alarm tepat waktu belum diizinkan", "Pengingat bisa terlambat beberapa menit.", "Izinkan", v ->
                    startSafe(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())))), any ? 8 : 0);
        }
        if (Build.VERSION.SDK_INT >= 34 && nm != null && !nm.canUseFullScreenIntent()) {
            any = true;
            Ui.add(box, banner("Alarm layar penuh belum diizinkan", "Alarm tidak tampil di layar kunci.", "Izinkan", v ->
                    startSafe(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())))), 8);
        }
        if (includeBattery) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                any = true;
                Ui.add(box, banner("Optimasi baterai aktif", "Beberapa HP menunda alarm aplikasi. Kecualikan MediVerify.", "Atur", v ->
                        startSafe(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())))), 8);
            }
        }
        return any ? box : null;
    }

    void startSafe(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private View banner(String title, String text, String action, View.OnClickListener l) {
        LinearLayout b = Ui.hbox(this);
        b.setBackground(Ui.roundStroke(Ui.WARN_BG, Ui.dp(this, 14), Ui.dp(this, 1), 0xFFFDE68A));
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        LinearLayout col = Ui.vbox(this);
        col.addView(Ui.text(this, "⚠ " + title, 14, 0xFF92400E, true));
        col.addView(Ui.text(this, text, 12.5f, 0xFFB45309, false));
        b.addView(col, Ui.lpWeight(1));
        TextView btn = Ui.button(this, action, Ui.BTN_LIGHT);
        btn.setTextSize(13);
        btn.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        btn.setOnClickListener(l);
        b.addView(btn, Ui.lpWW());
        return b;
    }
}

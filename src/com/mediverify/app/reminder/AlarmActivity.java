package com.mediverify.app.reminder;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mediverify.app.VerifyActivity;
import com.mediverify.app.data.Db;
import com.mediverify.app.data.Med;
import com.mediverify.app.data.Prefs;
import com.mediverify.app.ui.Fmt;
import com.mediverify.app.ui.PillIconView;
import com.mediverify.app.ui.Ui;

/** Full-screen ringing alarm shown over the lock screen. */
public final class AlarmActivity extends Activity {
    private MediaPlayer player;
    private Vibrator vibrator;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long medId, slot;
    private int idx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.statusBar(this, Ui.PRIMARY_DARK, true);

        medId = getIntent().getLongExtra(Reminders.EXTRA_MED, -1);
        slot = getIntent().getLongExtra(Reminders.EXTRA_SLOT, System.currentTimeMillis());
        idx = getIntent().getIntExtra(Reminders.EXTRA_IDX, 0);
        Med m = Db.get(this).med(medId);
        if (m == null) {
            finish();
            return;
        }

        LinearLayout root = Ui.vbox(this);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Ui.PRIMARY_DARK);
        int p = Ui.dp(this, 28);
        root.setPadding(p, Ui.dp(this, 60), p, p);

        TextView clock = Ui.text(this, Fmt.time(slot), 64, Color.WHITE, false);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setGravity(Gravity.CENTER);
        root.addView(clock, Ui.lpMW());
        TextView sub = Ui.text(this, "WAKTUNYA MINUM OBAT", 14, 0xCCFFFFFF, true);
        sub.setLetterSpacing(0.15f);
        sub.setGravity(Gravity.CENTER);
        Ui.add(root, sub, 4);

        LinearLayout card = Ui.vbox(this);
        card.setBackground(Ui.round(0x22FFFFFF, Ui.dp(this, 24)));
        card.setPadding(p, p, p, p);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        PillIconView icon = new PillIconView(this, m.color);
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84)));
        TextView name = Ui.text(this, m.name, 26, Color.WHITE, true);
        name.setGravity(Gravity.CENTER);
        Ui.add(card, name, 14);
        TextView dose = Ui.text(this, m.doseLabel(), 16, 0xE6FFFFFF, false);
        dose.setGravity(Gravity.CENTER);
        Ui.add(card, dose, 4);
        if (!m.instructions.isEmpty()) {
            TextView ins = Ui.text(this, m.instructions, 14, 0xCCFFFFFF, false);
            ins.setGravity(Gravity.CENTER);
            Ui.add(card, ins, 6);
        }
        LinearLayout.LayoutParams clp = Ui.lpMW();
        clp.topMargin = Ui.dp(this, 36);
        root.addView(card, clp);

        LinearLayout spacer = new LinearLayout(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView verify = Ui.button(this, "📷  Verifikasi minum obat", Ui.BTN_LIGHT);
        verify.setTextSize(17);
        verify.setOnClickListener(v -> {
            stopRinging();
            Reminders.dismiss(this, medId, slot);
            Intent i = new Intent(this, VerifyActivity.class);
            i.putExtra(VerifyActivity.EXTRA_MED, medId);
            i.putExtra(VerifyActivity.EXTRA_SLOT, slot);
            startActivity(i);
            finish();
        });
        root.addView(verify, Ui.lpMW());
        TextView snooze = Ui.button(this, "Tunda " + new Prefs(this).snoozeMinutes() + " menit", Ui.BTN_GHOST);
        snooze.setTextColor(Color.WHITE);
        snooze.setOnClickListener(v -> {
            stopRinging();
            Reminders.dismiss(this, medId, slot);
            Reminders.scheduleSnooze(this, medId, slot, idx);
            finish();
        });
        Ui.add(root, snooze, 10);
        setContentView(root);
        startRinging();
        h.postDelayed(this::stopRinging, 120000);
    }

    private void startRinging() {
        Prefs prefs = new Prefs(this);
        if (prefs.sound()) {
            try {
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                player.setDataSource(this, u);
                player.setLooping(true);
                player.prepare();
                player.start();
            } catch (Exception e) {
                player = null;
            }
        }
        if (prefs.vibrate()) {
            vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 500, 700, 500}, 0));
            }
        }
    }

    private void stopRinging() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
            player = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        stopRinging();
        super.onDestroy();
    }
}

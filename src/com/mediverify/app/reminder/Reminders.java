package com.mediverify.app.reminder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import com.mediverify.app.MainActivity;
import com.mediverify.app.R;
import com.mediverify.app.VerifyActivity;
import com.mediverify.app.data.Db;
import com.mediverify.app.data.DoseLog;
import com.mediverify.app.data.Med;
import com.mediverify.app.data.Prefs;
import com.mediverify.app.data.Schedule;
import com.mediverify.app.ui.Fmt;

import java.util.List;

public final class Reminders {
    private Reminders() {}

    public static final String ACTION_REMIND = "com.mediverify.app.action.REMIND";
    public static final String ACTION_SNOOZE = "com.mediverify.app.action.SNOOZE";
    public static final String ACTION_SNOOZE_FIRE = "com.mediverify.app.action.SNOOZE_FIRE";
    public static final String ACTION_MISSED = "com.mediverify.app.action.MISSED";
    public static final String EXTRA_MED = "med";
    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_IDX = "idx";

    public static final String CH_REMIND = "dose_reminder_v2";
    public static final String CH_ALERT = "dose_alert";
    public static final String CH_INFO = "info";

    public static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel r = new NotificationChannel(CH_REMIND, "Pengingat minum obat", NotificationManager.IMPORTANCE_HIGH);
        r.setDescription("Alarm jadwal minum obat");
        r.enableVibration(true);
        r.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
        Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarm == null) alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        r.setSound(alarm, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        r.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        r.setBypassDnd(true);
        nm.createNotificationChannel(r);
        NotificationChannel a = new NotificationChannel(CH_ALERT, "Dosis terlewat & peringatan", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Dosis terlewat, obat salah, dan eskalasi ke caregiver");
        nm.createNotificationChannel(a);
        NotificationChannel i = new NotificationChannel(CH_INFO, "Informasi", NotificationManager.IMPORTANCE_DEFAULT);
        i.setDescription("Stok obat menipis dan informasi lain");
        nm.createNotificationChannel(i);
    }

    static int rc(long medId, int idx) {
        return (int) ((medId % 1000000L) * 32 + idx);
    }

    public static int notifId(long medId, long slot) {
        return (int) (((medId * 1_000_003L) ^ (slot / 60000L)) & 0x3fffffff) + 100;
    }

    private static PendingIntent receiverPi(Context c, String action, int code, long medId, long slot, int idx) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(action);
        i.putExtra(EXTRA_MED, medId);
        i.putExtra(EXTRA_SLOT, slot);
        i.putExtra(EXTRA_IDX, idx);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static PendingIntent verifyPi(Context c, long medId, long slot) {
        Intent i = new Intent(c, VerifyActivity.class);
        i.putExtra(VerifyActivity.EXTRA_MED, medId);
        i.putExtra(VerifyActivity.EXTRA_SLOT, slot);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, notifId(medId, slot), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean canExact(AlarmManager am) {
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    static void setAlarm(Context c, long at, PendingIntent op, boolean alarmClock) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            if (!canExact(am)) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
            } else if (alarmClock) {
                Intent show = new Intent(c, MainActivity.class);
                PendingIntent showPi = PendingIntent.getActivity(c, 1, show, PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), op);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
        }
    }

    /** Schedules the next reminder for every active medication time. Safe to call often. */
    public static void rescheduleAll(Context c) {
        long now = System.currentTimeMillis();
        for (Med m : Db.get(c).meds(true)) {
            List<int[]> times = m.timeList();
            for (int i = 0; i < 32; i++) {
                if (!m.active || i >= times.size()) {
                    cancel(c, m.id, i);
                    continue;
                }
                scheduleNext(c, m, i, now + 1000);
            }
        }
    }

    static void scheduleNext(Context c, Med m, int idx, long from) {
        long next = Schedule.nextOccurrence(m, idx, from);
        if (next < 0) {
            cancel(c, m.id, idx);
            return;
        }
        setAlarm(c, next, receiverPi(c, ACTION_REMIND, rc(m.id, idx), m.id, next, idx), true);
    }

    public static void cancel(Context c, long medId, int idx) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACTION_REMIND);
        PendingIntent pi = PendingIntent.getBroadcast(c, rc(medId, idx), i, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    public static void cancelAll(Context c, long medId) {
        for (int i = 0; i < 32; i++) cancel(c, medId, i);
    }

    static void scheduleMissedCheck(Context c, long medId, long slot, int idx) {
        int grace = new Prefs(c).graceMinutes();
        setAlarm(c, slot + grace * 60000L + 5000, receiverPi(c, ACTION_MISSED, rc(medId, idx) + 3_000_000, medId, slot, idx), false);
    }

    static void scheduleSnooze(Context c, long medId, long slot, int idx) {
        int min = new Prefs(c).snoozeMinutes();
        setAlarm(c, System.currentTimeMillis() + min * 60000L,
                receiverPi(c, ACTION_SNOOZE_FIRE, rc(medId, idx) + 6_000_000, medId, slot, idx), true);
    }

    /** Fires a real reminder for an existing slot in ~60 s (used by the settings "test" button). */
    public static void testReminder(Context c, long medId, long slot, int idx) {
        setAlarm(c, System.currentTimeMillis() + 60000L,
                receiverPi(c, ACTION_SNOOZE_FIRE, rc(medId, idx) + 6_000_000, medId, slot, idx), true);
    }

    public static void showReminder(Context c, Med m, long slot, int idx, boolean snoozed) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        int id = notifId(m.id, slot);
        Intent full = new Intent(c, AlarmActivity.class);
        full.putExtra(EXTRA_MED, m.id);
        full.putExtra(EXTRA_SLOT, slot);
        full.putExtra(EXTRA_IDX, idx);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(c, id, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = (snoozed ? "⏰ Pengingat ulang: " : "⏰ Waktunya minum ") + m.name;
        String text = m.doseLabel() + " • jadwal " + Fmt.time(slot)
                + (m.instructions.isEmpty() ? "" : " • " + m.instructions) + ". Verifikasi dengan kamera.";
        Notification.Builder b = new Notification.Builder(c, CH_REMIND)
                .setSmallIcon(R.drawable.ic_stat_pill)
                .setColor(0xFF0D9488)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOngoing(false)
                .setWhen(slot)
                .setShowWhen(true)
                .setContentIntent(verifyPi(c, m.id, slot))
                .setFullScreenIntent(fullPi, true)
                .addAction(new Notification.Action.Builder(null, "Verifikasi sekarang", verifyPi(c, m.id, slot)).build())
                .addAction(new Notification.Action.Builder(null, "Tunda " + new Prefs(c).snoozeMinutes() + " menit",
                        receiverPi(c, ACTION_SNOOZE, id, m.id, slot, idx)).build());
        try {
            nm.notify(id, b.build());
        } catch (SecurityException ignored) {
        }
    }

    public static void dismiss(Context c, long medId, long slot) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(notifId(medId, slot));
    }

    /** Called at slot + grace period. */
    static void onMissedCheck(Context c, long medId, long slot) {
        Db db = Db.get(c);
        Med m = db.med(medId);
        if (m == null || !m.active) return;
        if (db.resolution(medId, slot) != null) return;
        DoseLog l = new DoseLog();
        l.medId = medId;
        l.medName = m.name;
        l.slotTime = slot;
        l.status = DoseLog.MISSED;
        l.notes = "Tidak ada konfirmasi minum obat hingga batas toleransi " + new Prefs(c).graceMinutes() + " menit";
        db.recordResolution(l);
        dismiss(c, medId, slot);
        notifyMissed(c, m, slot);
    }

    static void notifyMissed(Context c, Med m, long slot) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Prefs p = new Prefs(c);
        Intent open = new Intent(c, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_TAB, 2);
        PendingIntent openPi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = m.name + " (" + m.doseLabel() + ") jadwal " + Fmt.time(slot) + " belum diminum.";
        Notification.Builder b = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_pill)
                .setColor(0xFFEF4444)
                .setContentTitle("Dosis terlewat")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openPi);
        String phone = p.caregiverPhone().trim();
        if (p.notifyCaregiver() && !phone.isEmpty()) {
            String patient = p.patientName().isEmpty() ? "Pasien" : p.patientName();
            String msg = "[MediVerify] " + patient + " melewatkan dosis " + m.name + " (" + m.doseLabel() + ") jadwal "
                    + Fmt.time(slot) + ", " + Fmt.date(slot) + ". Mohon diingatkan.";
            Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)));
            sms.putExtra("sms_body", msg);
            sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent smsPi = PendingIntent.getActivity(c, notifId(m.id, slot) + 1, sms,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String cg = p.caregiverName().isEmpty() ? "caregiver" : p.caregiverName();
            b.addAction(new Notification.Action.Builder(null, "Kabari " + cg + " (SMS)", smsPi).build());
            String wa = phone.replaceAll("[^0-9]", "");
            if (wa.startsWith("0")) wa = "62" + wa.substring(1);
            Intent waI = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + wa + "?text=" + Uri.encode(msg)));
            waI.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent waPi = PendingIntent.getActivity(c, notifId(m.id, slot) + 2, waI,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "WhatsApp", waPi).build());
        }
        try {
            nm.notify(notifId(m.id, slot) + 7, b.build());
        } catch (SecurityException ignored) {
        }
    }

    public static void notifyLowStock(Context c, Med m) {
        if (m.stock < 0) return;
        int perDay = m.pillsPerDose * m.dosesPerDay();
        if (m.stock > perDay * 3) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String text = "Sisa " + m.stock + " " + m.form.toLowerCase() + " " + m.name + " (± " + (perDay == 0 ? 0 : m.stock / perDay)
                + " hari). Segera tebus resep.";
        Notification.Builder b = new Notification.Builder(c, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat_pill)
                .setColor(0xFFF59E0B)
                .setContentTitle("Stok obat menipis")
                .setContentText(text)
                .setAutoCancel(true);
        try {
            nm.notify((int) (m.id % 100000) + 50, b.build());
        } catch (SecurityException ignored) {
        }
    }
}

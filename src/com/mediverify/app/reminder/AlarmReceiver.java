package com.mediverify.app.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mediverify.app.data.Db;
import com.mediverify.app.data.Med;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        long medId = intent.getLongExtra(Reminders.EXTRA_MED, -1);
        long slot = intent.getLongExtra(Reminders.EXTRA_SLOT, -1);
        int idx = intent.getIntExtra(Reminders.EXTRA_IDX, 0);
        Db db = Db.get(c);
        try {
            switch (action) {
                case Reminders.ACTION_REMIND: {
                    Med m = db.med(medId);
                    if (m == null || !m.active) return;
                    // Schedule the following occurrence first so the chain never breaks.
                    Reminders.scheduleNext(c, m, idx, slot + 60000);
                    if (db.resolution(medId, slot) == null) {
                        Reminders.showReminder(c, m, slot, idx, false);
                        Reminders.scheduleMissedCheck(c, medId, slot, idx);
                    }
                    break;
                }
                case Reminders.ACTION_SNOOZE:
                    Reminders.dismiss(c, medId, slot);
                    Reminders.scheduleSnooze(c, medId, slot, idx);
                    break;
                case Reminders.ACTION_SNOOZE_FIRE: {
                    Med m = db.med(medId);
                    if (m != null && m.active && db.resolution(medId, slot) == null) {
                        Reminders.showReminder(c, m, slot, idx, true);
                    }
                    break;
                }
                case Reminders.ACTION_MISSED:
                    Reminders.onMissedCheck(c, medId, slot);
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            // never crash the receiver; make sure future alarms still exist
            Reminders.rescheduleAll(c);
        }
    }
}

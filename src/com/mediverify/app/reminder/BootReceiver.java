package com.mediverify.app.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mediverify.app.data.Db;
import com.mediverify.app.data.Prefs;

/** Re-arms all reminders after reboot, app update or clock/timezone change. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            Db.get(c).reconcileMissed(System.currentTimeMillis(), new Prefs(c).graceMinutes(), 7);
        } catch (RuntimeException ignored) {
        }
        Reminders.rescheduleAll(c);
    }
}

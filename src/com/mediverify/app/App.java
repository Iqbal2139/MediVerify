package com.mediverify.app;

import android.app.Application;

import com.mediverify.app.reminder.Reminders;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Reminders.createChannels(this);
    }
}

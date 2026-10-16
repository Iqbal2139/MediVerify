package com.mediverify.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("mediverify", Context.MODE_PRIVATE);
    }

    private String s(String k, String d) { return p.getString(k, d); }
    private int i(String k, int d) { return p.getInt(k, d); }
    private boolean b(String k, boolean d) { return p.getBoolean(k, d); }

    public String patientName() { return s("pname", ""); }
    public String patientAge() { return s("page", ""); }
    public String patientGender() { return s("pgender", ""); }
    public String rmNumber() { return s("prm", ""); }
    public String doctor() { return s("pdoctor", ""); }
    public String caregiverName() { return s("cgname", ""); }
    public String caregiverPhone() { return s("cgphone", ""); }
    public int graceMinutes() { return i("grace", 60); }
    public int onTimeMinutes() { return i("ontime", 30); }
    public int snoozeMinutes() { return i("snooze", 10); }
    public boolean sound() { return b("sound", true); }
    public boolean vibrate() { return b("vibrate", true); }
    public boolean allowManual() { return b("manual", true); }
    public boolean notifyCaregiver() { return b("notifycg", true); }
    public boolean onboarded() { return b("onboarded", false); }
    public boolean askedNotif() { return b("askednotif", false); }

    public void setProfile(String name, String age, String gender, String rm, String doctor, String cgName, String cgPhone) {
        p.edit().putString("pname", name).putString("page", age).putString("pgender", gender).putString("prm", rm)
                .putString("pdoctor", doctor).putString("cgname", cgName).putString("cgphone", cgPhone).apply();
    }

    public void setInt(String k, int v) { p.edit().putInt(k, v).apply(); }
    public void setBool(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }
}

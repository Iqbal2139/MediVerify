package com.mediverify.app.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Fmt {
    private Fmt() {}

    public static final Locale ID = new Locale("id", "ID");

    public static String time(long t) {
        return new SimpleDateFormat("HH:mm", ID).format(new Date(t));
    }

    public static String date(long t) {
        return new SimpleDateFormat("d MMM yyyy", ID).format(new Date(t));
    }

    public static String dateLong(long t) {
        return new SimpleDateFormat("EEEE, d MMMM yyyy", ID).format(new Date(t));
    }

    public static String dateTime(long t) {
        return new SimpleDateFormat("d MMM yyyy, HH:mm", ID).format(new Date(t));
    }

    public static String dayShort(long t) {
        return new SimpleDateFormat("EEE", ID).format(new Date(t));
    }

    public static String iso(long t) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.US).format(new Date(t));
    }

    public static String stamp(long t) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(t));
    }

    public static String pct(float v) {
        if (v < 0) return "–";
        return Math.round(v) + "%";
    }

    public static String duration(long minutes) {
        long m = Math.abs(minutes);
        if (m < 60) return m + " menit";
        return (m / 60) + " jam " + (m % 60) + " menit";
    }
}

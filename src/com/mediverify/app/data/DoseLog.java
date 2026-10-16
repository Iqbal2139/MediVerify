package com.mediverify.app.data;

public final class DoseLog {
    public static final int VERIFIED = 1;   // taken, visual verification passed
    public static final int REVIEW = 2;     // taken, visual verification partial (needs review)
    public static final int MANUAL = 3;     // taken, confirmed manually without camera
    public static final int MISSED = 4;     // not taken within grace period
    public static final int SKIPPED = 5;    // patient deliberately skipped
    public static final int ATTEMPT_FAILED = 6; // a verification attempt that failed / wrong pill

    public long id;
    public long medId;
    public String medName = "";
    public long slotTime;
    public int status;
    public long takenAt;
    public float pillScore = -1, ingestScore = -1, totalScore = -1;
    public int detectedCount;
    public String photoPill, photoMouth;
    public String notes = "";
    public int wrongAlerts;
    public long durationMs;

    public boolean isTaken() {
        return status == VERIFIED || status == REVIEW || status == MANUAL;
    }

    public boolean resolvesSlot() {
        return status != ATTEMPT_FAILED;
    }

    public static String statusLabel(int s) {
        switch (s) {
            case VERIFIED: return "Terverifikasi";
            case REVIEW: return "Perlu ditinjau";
            case MANUAL: return "Konfirmasi manual";
            case MISSED: return "Terlewat";
            case SKIPPED: return "Dilewati";
            case ATTEMPT_FAILED: return "Verifikasi gagal";
            default: return "Menunggu";
        }
    }

    /** Minutes late relative to schedule (negative = early). */
    public long minutesLate() {
        return (takenAt - slotTime) / 60000L;
    }
}

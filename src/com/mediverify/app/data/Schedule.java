package com.mediverify.app.data;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Pure scheduling / adherence logic (no Android dependencies). */
public final class Schedule {
    private Schedule() {}

    public static final class Slot {
        public Med med;
        public long time;
        public DoseLog log; // resolving log or null
        public int timeIndex;
    }

    public static String key(long medId, long slot) {
        return medId + ":" + slot;
    }

    public static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long addDays(long dayStart, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    public static long at(long dayStart, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static boolean activeOn(Med m, long dayStart) {
        if (!m.active) return false;
        if (m.startDate > 0 && dayStart < dayStart(m.startDate)) return false;
        if (m.endDate > 0 && dayStart > dayStart(m.endDate)) return false;
        return true;
    }

    /** Slot counts only if it is not earlier than the moment the medication was added. */
    static boolean slotCounts(Med m, long slot) {
        return m.createdAt <= 0 || slot >= m.createdAt - 60000L;
    }

    public static List<Slot> slotsForDay(List<Med> meds, long dayStart, Map<String, DoseLog> logs) {
        List<Slot> out = new ArrayList<>();
        for (Med m : meds) {
            if (!activeOn(m, dayStart)) continue;
            List<int[]> times = m.timeList();
            for (int i = 0; i < times.size(); i++) {
                Slot s = new Slot();
                s.med = m;
                s.timeIndex = i;
                s.time = at(dayStart, times.get(i)[0], times.get(i)[1]);
                if (!slotCounts(m, s.time) && (logs == null || !logs.containsKey(key(m.id, s.time)))) continue;
                s.log = logs == null ? null : logs.get(key(m.id, s.time));
                out.add(s);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.time, b.time));
        return out;
    }

    /** Next scheduled time (>= from) for a given medication time index, or -1. */
    public static long nextOccurrence(Med m, int timeIndex, long from) {
        List<int[]> times = m.timeList();
        if (timeIndex >= times.size() || !m.active) return -1;
        int[] hm = times.get(timeIndex);
        long day = dayStart(from);
        for (int d = 0; d < 400; d++) {
            long ds = addDays(day, d);
            if (m.endDate > 0 && ds > dayStart(m.endDate)) return -1;
            if (!activeOn(m, ds)) continue;
            long t = at(ds, hm[0], hm[1]);
            if (t >= from) return t;
        }
        return -1;
    }

    public static final class Stats {
        public int scheduled, taken, verified, review, manual, missed, skipped, onTime, late, pending;
        public int wrongAlerts;

        public float adherence() {
            return scheduled == 0 ? -1f : 100f * taken / scheduled;
        }

        public float verifiedRate() {
            return scheduled == 0 ? -1f : 100f * verified / scheduled;
        }

        public float onTimeRate() {
            return taken == 0 ? -1f : 100f * onTime / taken;
        }
    }

    /**
     * Adherence statistics over [fromDay, toDayExclusive). Slots whose grace period has not ended
     * and that are not yet resolved are "pending" and excluded from the denominator.
     */
    public static Stats stats(List<Med> meds, Map<String, DoseLog> logs, long fromDay, long toDayExclusive, long now,
                              int graceMin, int onTimeMin, Long onlyMed) {
        Stats st = new Stats();
        for (long d = fromDay; d < toDayExclusive; d = addDays(d, 1)) {
            if (d > now) break;
            for (Slot s : slotsForDay(meds, d, logs)) {
                if (onlyMed != null && s.med.id != onlyMed) continue;
                if (s.time > now && s.log == null) continue;
                if (s.log != null) {
                    st.wrongAlerts += s.log.wrongAlerts;
                    switch (s.log.status) {
                        case DoseLog.VERIFIED: st.verified++; break;
                        case DoseLog.REVIEW: st.review++; break;
                        case DoseLog.MANUAL: st.manual++; break;
                        case DoseLog.MISSED: st.missed++; break;
                        case DoseLog.SKIPPED: st.skipped++; break;
                        default: break;
                    }
                    if (s.log.isTaken()) {
                        st.taken++;
                        if (Math.abs(s.log.takenAt - s.time) <= onTimeMin * 60000L) st.onTime++;
                        else st.late++;
                    }
                    st.scheduled++;
                } else if (s.time + graceMin * 60000L < now) {
                    st.missed++;
                    st.scheduled++;
                } else {
                    st.pending++;
                }
            }
        }
        return st;
    }
}

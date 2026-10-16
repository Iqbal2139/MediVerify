package com.mediverify.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Db extends SQLiteOpenHelper {
    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "mediverify.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE meds (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, dose TEXT, form TEXT, pills INTEGER,"
                + " color INTEGER, shape INTEGER, instr TEXT, times TEXT, start_date INTEGER, end_date INTEGER, stock INTEGER,"
                + " active INTEGER, water INTEGER, ref TEXT, ref_photo TEXT, created INTEGER)");
        db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, med_id INTEGER, med_name TEXT, slot INTEGER,"
                + " status INTEGER, taken_at INTEGER, pill_score REAL, ingest_score REAL, total_score REAL, count INTEGER,"
                + " photo_pill TEXT, photo_mouth TEXT, notes TEXT, wrong INTEGER, duration INTEGER)");
        db.execSQL("CREATE INDEX idx_logs_slot ON logs(med_id, slot)");
        db.execSQL("CREATE INDEX idx_logs_time ON logs(slot)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    }

    // ------------------------------------------------------------------ meds

    private static Med readMed(Cursor c) {
        Med m = new Med();
        m.id = c.getLong(0);
        m.name = c.getString(1);
        m.dose = c.getString(2);
        m.form = c.getString(3);
        m.pillsPerDose = c.getInt(4);
        m.color = c.getInt(5);
        m.shape = c.getInt(6);
        m.instructions = c.getString(7);
        m.times = c.getString(8);
        m.startDate = c.getLong(9);
        m.endDate = c.getLong(10);
        m.stock = c.getInt(11);
        m.active = c.getInt(12) != 0;
        m.requireWater = c.getInt(13) != 0;
        m.refProfile = c.getString(14);
        m.refPhoto = c.getString(15);
        m.createdAt = c.getLong(16);
        if (m.name == null) m.name = "";
        if (m.dose == null) m.dose = "";
        if (m.form == null) m.form = "Tablet";
        if (m.instructions == null) m.instructions = "";
        return m;
    }

    private static final String MED_COLS = "id,name,dose,form,pills,color,shape,instr,times,start_date,end_date,stock,active,water,ref,ref_photo,created";

    public List<Med> meds(boolean includeInactive) {
        List<Med> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + MED_COLS + " FROM meds"
                + (includeInactive ? "" : " WHERE active=1") + " ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) out.add(readMed(c));
        }
        return out;
    }

    public Med med(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + MED_COLS + " FROM meds WHERE id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readMed(c) : null;
        }
    }

    public long saveMed(Med m) {
        ContentValues v = new ContentValues();
        v.put("name", m.name);
        v.put("dose", m.dose);
        v.put("form", m.form);
        v.put("pills", m.pillsPerDose);
        v.put("color", m.color);
        v.put("shape", m.shape);
        v.put("instr", m.instructions);
        v.put("times", m.times);
        v.put("start_date", m.startDate);
        v.put("end_date", m.endDate);
        v.put("stock", m.stock);
        v.put("active", m.active ? 1 : 0);
        v.put("water", m.requireWater ? 1 : 0);
        v.put("ref", m.refProfile);
        v.put("ref_photo", m.refPhoto);
        if (m.id > 0) {
            getWritableDatabase().update("meds", v, "id=?", new String[]{String.valueOf(m.id)});
        } else {
            if (m.createdAt <= 0) m.createdAt = System.currentTimeMillis();
            v.put("created", m.createdAt);
            m.id = getWritableDatabase().insert("meds", null, v);
        }
        return m.id;
    }

    public void deleteMed(long id) {
        getWritableDatabase().delete("meds", "id=?", new String[]{String.valueOf(id)});
    }

    public void adjustStock(long medId, int delta) {
        getWritableDatabase().execSQL("UPDATE meds SET stock = MAX(0, stock + ?) WHERE id=? AND stock >= 0",
                new Object[]{delta, medId});
    }

    // ------------------------------------------------------------------ logs

    private static final String LOG_COLS = "id,med_id,med_name,slot,status,taken_at,pill_score,ingest_score,total_score,count,photo_pill,photo_mouth,notes,wrong,duration";

    private static DoseLog readLog(Cursor c) {
        DoseLog l = new DoseLog();
        l.id = c.getLong(0);
        l.medId = c.getLong(1);
        l.medName = c.getString(2);
        l.slotTime = c.getLong(3);
        l.status = c.getInt(4);
        l.takenAt = c.getLong(5);
        l.pillScore = c.getFloat(6);
        l.ingestScore = c.getFloat(7);
        l.totalScore = c.getFloat(8);
        l.detectedCount = c.getInt(9);
        l.photoPill = c.getString(10);
        l.photoMouth = c.getString(11);
        l.notes = c.getString(12);
        l.wrongAlerts = c.getInt(13);
        l.durationMs = c.getLong(14);
        if (l.medName == null) l.medName = "";
        if (l.notes == null) l.notes = "";
        return l;
    }

    private static ContentValues logValues(DoseLog l) {
        ContentValues v = new ContentValues();
        v.put("med_id", l.medId);
        v.put("med_name", l.medName);
        v.put("slot", l.slotTime);
        v.put("status", l.status);
        v.put("taken_at", l.takenAt);
        v.put("pill_score", l.pillScore);
        v.put("ingest_score", l.ingestScore);
        v.put("total_score", l.totalScore);
        v.put("count", l.detectedCount);
        v.put("photo_pill", l.photoPill);
        v.put("photo_mouth", l.photoMouth);
        v.put("notes", l.notes);
        v.put("wrong", l.wrongAlerts);
        v.put("duration", l.durationMs);
        return v;
    }

    public DoseLog log(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + LOG_COLS + " FROM logs WHERE id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readLog(c) : null;
        }
    }

    /** Resolving log for a slot (taken/missed/skipped), or null. */
    public DoseLog resolution(long medId, long slot) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + LOG_COLS + " FROM logs WHERE med_id=? AND slot=? AND status<>"
                + DoseLog.ATTEMPT_FAILED + " ORDER BY id DESC LIMIT 1", new String[]{String.valueOf(medId), String.valueOf(slot)})) {
            return c.moveToFirst() ? readLog(c) : null;
        }
    }

    /**
     * Records a slot resolution. An existing resolution for the same slot is replaced, except that a
     * MISSED record never overwrites a dose that was already taken.
     */
    public long recordResolution(DoseLog l) {
        DoseLog old = resolution(l.medId, l.slotTime);
        if (old != null) {
            if (old.isTaken() && !l.isTaken()) return old.id;
            getWritableDatabase().update("logs", logValues(l), "id=?", new String[]{String.valueOf(old.id)});
            l.id = old.id;
            return old.id;
        }
        l.id = getWritableDatabase().insert("logs", null, logValues(l));
        return l.id;
    }

    public long insertAttempt(DoseLog l) {
        l.status = DoseLog.ATTEMPT_FAILED;
        l.id = getWritableDatabase().insert("logs", null, logValues(l));
        return l.id;
    }

    public void deleteLog(long id) {
        getWritableDatabase().delete("logs", "id=?", new String[]{String.valueOf(id)});
    }

    /** Map of resolving logs keyed by Schedule.key(medId, slot) for slots in [from, to). */
    public Map<String, DoseLog> resolutions(long from, long to) {
        Map<String, DoseLog> map = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + LOG_COLS + " FROM logs WHERE slot>=? AND slot<? AND status<>"
                + DoseLog.ATTEMPT_FAILED + " ORDER BY id ASC", new String[]{String.valueOf(from), String.valueOf(to)})) {
            while (c.moveToNext()) {
                DoseLog l = readLog(c);
                String k = Schedule.key(l.medId, l.slotTime);
                DoseLog prev = map.get(k);
                if (prev == null || !prev.isTaken() || l.isTaken()) map.put(k, l);
            }
        }
        return map;
    }

    /** All logs (including failed attempts) whose slot is in [from, to), newest first. */
    public List<DoseLog> logs(long from, long to, boolean includeAttempts) {
        List<DoseLog> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + LOG_COLS + " FROM logs WHERE slot>=? AND slot<?"
                + (includeAttempts ? "" : " AND status<>" + DoseLog.ATTEMPT_FAILED)
                + " ORDER BY slot DESC, id DESC", new String[]{String.valueOf(from), String.valueOf(to)})) {
            while (c.moveToNext()) out.add(readLog(c));
        }
        return out;
    }

    public int countWrongAlerts(long from, long to) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(wrong),0) FROM logs WHERE slot>=? AND slot<?",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Inserts MISSED records for past, unresolved slots of the last `days` days. Returns them. */
    public List<DoseLog> reconcileMissed(long now, int graceMin, int days) {
        List<DoseLog> created = new ArrayList<>();
        List<Med> meds = meds(false);
        long today = Schedule.dayStart(now);
        long from = Schedule.addDays(today, -days);
        Map<String, DoseLog> logs = resolutions(from, Schedule.addDays(today, 1));
        for (long d = from; d <= today; d = Schedule.addDays(d, 1)) {
            for (Schedule.Slot s : Schedule.slotsForDay(meds, d, logs)) {
                if (s.log != null) continue;
                if (s.time + graceMin * 60000L >= now) continue;
                DoseLog l = new DoseLog();
                l.medId = s.med.id;
                l.medName = s.med.name;
                l.slotTime = s.time;
                l.status = DoseLog.MISSED;
                l.notes = "Tidak ada konfirmasi minum obat hingga batas toleransi " + graceMin + " menit";
                recordResolution(l);
                logs.put(Schedule.key(l.medId, l.slotTime), l);
                created.add(l);
            }
        }
        return created;
    }
}

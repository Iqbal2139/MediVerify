package com.mediverify.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import com.mediverify.app.data.Db;
import com.mediverify.app.data.DoseLog;
import com.mediverify.app.data.Med;
import com.mediverify.app.data.Prefs;
import com.mediverify.app.data.Schedule;
import com.mediverify.app.ui.Fmt;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PDF / CSV / HL7 FHIR exports of the adherence record. */
final class ReportExporter {
    private ReportExporter() {}

    static File outFile(Context c, String ext) {
        File dir = new File(c.getCacheDir(), "exports");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 86400000L) f.delete();
        return new File(dir, "MediVerify_Kepatuhan_" + Fmt.stamp(System.currentTimeMillis()) + "." + ext);
    }

    /** Slots in the range with their status, including unresolved past slots (= missed). */
    static final class Row {
        String med;
        long slot;
        DoseLog log;
        String status;
    }

    static List<Row> rows(Context c, long from, long to) {
        Db db = Db.get(c);
        Prefs p = new Prefs(c);
        long now = System.currentTimeMillis();
        List<Med> meds = db.meds(true);
        Map<String, DoseLog> logs = db.resolutions(from, to);
        List<Row> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (long d = from; d < to && d <= now; d = Schedule.addDays(d, 1)) {
            for (Schedule.Slot s : Schedule.slotsForDay(meds, d, logs)) {
                if (s.time > now && s.log == null) continue;
                Row r = new Row();
                r.med = s.med.name;
                r.slot = s.time;
                r.log = s.log;
                if (s.log != null) r.status = DoseLog.statusLabel(s.log.status);
                else if (s.time + p.graceMinutes() * 60000L < now) r.status = "Terlewat";
                else r.status = "Menunggu";
                seen.add(Schedule.key(s.med.id, s.time));
                out.add(r);
            }
        }
        // logs of deleted medications or changed schedules
        for (DoseLog l : logs.values()) {
            if (seen.contains(Schedule.key(l.medId, l.slotTime))) continue;
            Row r = new Row();
            r.med = l.medName;
            r.slot = l.slotTime;
            r.log = l;
            r.status = DoseLog.statusLabel(l.status);
            out.add(r);
        }
        Collections.sort(out, (x, y) -> Long.compare(x.slot, y.slot));
        return out;
    }

    // ------------------------------------------------------------------ PDF

    private static final int W = 595, H = 842, M = 40;

    private static final class Pdf {
        final PdfDocument doc = new PdfDocument();
        PdfDocument.Page page;
        Canvas cv;
        float y;
        int n;
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), bold = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint();

        Pdf() {
            text.setTextSize(9.5f);
            text.setColor(0xFF334155);
            bold.setTextSize(9.5f);
            bold.setColor(0xFF0F172A);
            bold.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            line.setColor(0xFFE2E8F0);
            line.setStrokeWidth(0.8f);
            newPage();
        }

        void newPage() {
            if (page != null) finishPage();
            n++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, n).create());
            cv = page.getCanvas();
            y = M;
        }

        void finishPage() {
            Paint f = new Paint(text);
            f.setTextSize(7.5f);
            f.setColor(0xFF94A3B8);
            cv.drawText("MediVerify • Laporan kepatuhan minum obat • dibuat " + Fmt.dateTime(System.currentTimeMillis()), M, H - 22, f);
            f.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("Halaman " + n, W - M, H - 22, f);
            doc.finishPage(page);
        }

        void ensure(float h) {
            if (y + h > H - 50) newPage();
        }
    }

    static File pdf(Context c, long from, long to) throws IOException {
        Prefs p = new Prefs(c);
        Db db = Db.get(c);
        long now = System.currentTimeMillis();
        List<Med> meds = db.meds(true);
        Map<String, DoseLog> logs = db.resolutions(from, to);
        Schedule.Stats st = Schedule.stats(meds, logs, from, to, now, p.graceMinutes(), p.onTimeMinutes(), null);
        Pdf d = new Pdf();
        Canvas cv = d.cv;

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setTextSize(17);
        title.setColor(0xFF0F766E);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint band = new Paint();
        band.setColor(0xFF0D9488);
        cv.drawRect(0, 0, W, 6, band);
        cv.drawText("Laporan Kepatuhan Minum Obat", M, d.y + 12, title);
        d.y += 30;
        cv.drawText("Periode " + Fmt.date(from) + " – " + Fmt.date(Math.min(now, to - 1)) + "   •   Dicetak " + Fmt.dateTime(now), M, d.y, d.text);
        d.y += 20;

        String[][] info = {
                {"Nama pasien", nz(p.patientName())}, {"Usia / JK", nz(p.patientAge()) + (p.patientAge().isEmpty() ? "" : " th") + " / " + nz(p.patientGender())},
                {"No. rekam medis", nz(p.rmNumber())}, {"Dokter", nz(p.doctor())},
                {"Caregiver", nz(p.caregiverName()) + (p.caregiverPhone().isEmpty() ? "" : " (" + p.caregiverPhone() + ")")}};
        for (String[] kv : info) {
            cv.drawText(kv[0], M, d.y, d.text);
            cv.drawText(kv[1], M + 110, d.y, d.bold);
            d.y += 14;
        }
        d.y += 8;
        cv.drawLine(M, d.y, W - M, d.y, d.line);
        d.y += 18;

        // summary boxes
        String[][] box = {
                {Fmt.pct(st.adherence()), "Kepatuhan", st.taken + "/" + st.scheduled + " dosis"},
                {Fmt.pct(st.verifiedRate()), "Terverifikasi kamera", st.verified + " dosis"},
                {Fmt.pct(st.onTimeRate()), "Tepat waktu", "±" + p.onTimeMinutes() + " menit"},
                {String.valueOf(st.missed), "Terlewat", st.skipped + " dilewati"}};
        float bw = (W - 2 * M - 30) / 4f;
        Paint boxBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxBg.setColor(0xFFF0FDFA);
        Paint big = new Paint(title);
        big.setTextSize(18);
        for (int i = 0; i < 4; i++) {
            float x = M + i * (bw + 10);
            cv.drawRoundRect(x, d.y, x + bw, d.y + 58, 8, 8, boxBg);
            cv.drawText(box[i][0], x + 10, d.y + 24, big);
            cv.drawText(box[i][1], x + 10, d.y + 38, d.bold);
            cv.drawText(box[i][2], x + 10, d.y + 50, d.text);
        }
        d.y += 74;
        cv.drawText("Rincian: " + st.verified + " terverifikasi visual, " + st.review + " perlu ditinjau, " + st.manual
                + " konfirmasi manual, " + db.countWrongAlerts(from, to) + " peringatan obat/dosis salah.", M, d.y, d.text);
        d.y += 22;

        // per medication
        cv.drawText("Kepatuhan per obat", M, d.y, title(d));
        d.y += 16;
        float[] mc = {M, M + 190, M + 290, M + 360, M + 430};
        header(d, mc, new String[]{"Obat", "Dosis", "Diminum", "Terverifikasi", "Kepatuhan"});
        for (Med m : meds) {
            Schedule.Stats ms = Schedule.stats(meds, logs, from, to, now, p.graceMinutes(), p.onTimeMinutes(), m.id);
            if (ms.scheduled == 0) continue;
            d.ensure(14);
            cv = d.cv;
            d.cv.drawText(trim(m.name, 34), mc[0], d.y, d.text);
            d.cv.drawText(trim(m.doseLabel(), 18), mc[1], d.y, d.text);
            d.cv.drawText(ms.taken + "/" + ms.scheduled, mc[2], d.y, d.text);
            d.cv.drawText(String.valueOf(ms.verified), mc[3], d.y, d.text);
            d.cv.drawText(Fmt.pct(ms.adherence()), mc[4], d.y, d.bold);
            d.y += 14;
        }
        d.y += 14;

        // detail log
        d.ensure(40);
        d.cv.drawText("Catatan per jadwal", M, d.y, title(d));
        d.y += 16;
        float[] lc = {M, M + 110, M + 270, M + 380, M + 440};
        header(d, lc, new String[]{"Jadwal", "Obat", "Status", "Diminum", "Skor"});
        for (Row r : rows(c, from, to)) {
            d.ensure(14);
            if (d.y == M) header(d, lc, new String[]{"Jadwal", "Obat", "Status", "Diminum", "Skor"});
            d.cv.drawText(Fmt.dateTime(r.slot), lc[0], d.y, d.text);
            d.cv.drawText(trim(r.med, 28), lc[1], d.y, d.text);
            Paint sp = new Paint(d.bold);
            int s = r.log != null ? r.log.status : (r.status.equals("Terlewat") ? DoseLog.MISSED : 0);
            sp.setColor(s == DoseLog.VERIFIED ? 0xFF047857 : s == DoseLog.MISSED ? 0xFFDC2626 : s == DoseLog.REVIEW ? 0xFFB45309 : 0xFF334155);
            d.cv.drawText(r.status, lc[2], d.y, sp);
            d.cv.drawText(r.log != null && r.log.isTaken() ? Fmt.time(r.log.takenAt) : "–", lc[3], d.y, d.text);
            d.cv.drawText(r.log != null && r.log.totalScore >= 0 ? String.valueOf(Math.round(r.log.totalScore)) : "–", lc[4], d.y, d.text);
            d.y += 13;
        }
        d.y += 16;
        d.ensure(40);
        Paint small = new Paint(d.text);
        small.setTextSize(8);
        small.setColor(0xFF64748B);
        d.cv.drawText("Kepatuhan = dosis diminum / dosis terjadwal yang sudah lewat batas toleransi " + p.graceMinutes() + " menit.", M, d.y, small);
        d.y += 11;
        d.cv.drawText("Terverifikasi kamera = identifikasi obat + pemeriksaan wajah + obat ke mulut + mulut kosong lolos (skor total ≥ 80).", M, d.y, small);
        d.y += 11;
        d.cv.drawText("Verifikasi visual adalah alat bantu dan tidak menggantikan pengawasan tenaga kesehatan.", M, d.y, small);
        d.finishPage();
        File f = outFile(c, "pdf");
        try (FileOutputStream fo = new FileOutputStream(f)) {
            d.doc.writeTo(fo);
        } finally {
            d.doc.close();
        }
        return f;
    }

    private static Paint title(Pdf d) {
        Paint t = new Paint(d.bold);
        t.setTextSize(12);
        t.setColor(0xFF0F766E);
        return t;
    }

    private static void header(Pdf d, float[] cols, String[] names) {
        Paint bg = new Paint();
        bg.setColor(0xFFF1F5F9);
        d.cv.drawRect(M - 4, d.y - 10, W - M + 4, d.y + 4, bg);
        for (int i = 0; i < cols.length; i++) d.cv.drawText(names[i], cols[i], d.y, d.bold);
        d.y += 16;
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    // ------------------------------------------------------------------ CSV

    static File csv(Context c, long from, long to) throws IOException {
        File f = outFile(c, "csv");
        List<DoseLog> all = Db.get(c).logs(from, to, true);
        Collections.reverse(all);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write('﻿');
            w.write("jadwal;obat;status;waktu_minum;terlambat_menit;skor_obat;skor_menelan;skor_total;jumlah_tablet;peringatan;durasi_detik;catatan\n");
            for (DoseLog l : all) {
                w.write(Fmt.dateTime(l.slotTime) + ";" + q(l.medName) + ";" + DoseLog.statusLabel(l.status) + ";"
                        + (l.takenAt > 0 ? Fmt.dateTime(l.takenAt) : "") + ";" + (l.isTaken() ? l.minutesLate() : "") + ";"
                        + sc(l.pillScore) + ";" + sc(l.ingestScore) + ";" + sc(l.totalScore) + ";" + l.detectedCount + ";"
                        + l.wrongAlerts + ";" + (l.durationMs / 1000) + ";" + q(l.notes) + "\n");
            }
        }
        return f;
    }

    private static String sc(float v) {
        return v < 0 ? "" : String.valueOf(Math.round(v));
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    // ------------------------------------------------------------------ HL7 FHIR R4

    static File fhir(Context c, long from, long to) throws IOException {
        try {
            Prefs p = new Prefs(c);
            Db db = Db.get(c);
            Map<Long, Med> meds = new HashMap<>();
            for (Med m : db.meds(true)) meds.put(m.id, m);
            JSONObject bundle = new JSONObject();
            bundle.put("resourceType", "Bundle");
            bundle.put("type", "collection");
            bundle.put("timestamp", Fmt.iso(System.currentTimeMillis()));
            JSONArray entries = new JSONArray();
            JSONObject patient = new JSONObject();
            patient.put("resourceType", "Patient");
            patient.put("id", "patient-1");
            if (!p.rmNumber().isEmpty()) {
                patient.put("identifier", new JSONArray().put(new JSONObject().put("type", new JSONObject().put("text", "No. Rekam Medis"))
                        .put("value", p.rmNumber())));
            }
            if (!p.patientName().isEmpty()) patient.put("name", new JSONArray().put(new JSONObject().put("text", p.patientName())));
            if (p.patientGender().equals("Laki-laki")) patient.put("gender", "male");
            else if (p.patientGender().equals("Perempuan")) patient.put("gender", "female");
            if (!p.caregiverName().isEmpty() || !p.caregiverPhone().isEmpty()) {
                JSONObject contact = new JSONObject();
                contact.put("relationship", new JSONArray().put(new JSONObject().put("text", "Caregiver")));
                if (!p.caregiverName().isEmpty()) contact.put("name", new JSONObject().put("text", p.caregiverName()));
                if (!p.caregiverPhone().isEmpty()) contact.put("telecom", new JSONArray().put(new JSONObject().put("system", "phone").put("value", p.caregiverPhone())));
                patient.put("contact", new JSONArray().put(contact));
            }
            entries.put(new JSONObject().put("fullUrl", "urn:uuid:patient-1").put("resource", patient));
            for (DoseLog l : db.logs(from, to, false)) {
                JSONObject ms = new JSONObject();
                ms.put("resourceType", "MedicationStatement");
                ms.put("id", "dose-" + l.id);
                boolean taken = l.isTaken();
                ms.put("status", taken ? "completed" : "not-taken");
                if (!taken) ms.put("statusReason", new JSONArray().put(new JSONObject().put("text", DoseLog.statusLabel(l.status))));
                ms.put("medicationCodeableConcept", new JSONObject().put("text", l.medName));
                ms.put("subject", new JSONObject().put("reference", "urn:uuid:patient-1"));
                ms.put("effectiveDateTime", Fmt.iso(taken ? l.takenAt : l.slotTime));
                ms.put("dateAsserted", Fmt.iso(l.takenAt > 0 ? l.takenAt : l.slotTime));
                Med m = meds.get(l.medId);
                if (m != null) {
                    JSONObject dosage = new JSONObject();
                    dosage.put("text", m.doseLabel() + ", jam " + m.times.replace(",", ", ") + (m.instructions.isEmpty() ? "" : ", " + m.instructions));
                    dosage.put("timing", new JSONObject().put("repeat", new JSONObject().put("frequency", m.dosesPerDay()).put("period", 1).put("periodUnit", "d")));
                    dosage.put("doseAndRate", new JSONArray().put(new JSONObject().put("doseQuantity",
                            new JSONObject().put("value", m.pillsPerDose).put("unit", m.form.toLowerCase()))));
                    ms.put("dosage", new JSONArray().put(dosage));
                }
                StringBuilder note = new StringBuilder();
                note.append("Jadwal ").append(Fmt.dateTime(l.slotTime)).append(". Status MediVerify: ").append(DoseLog.statusLabel(l.status)).append('.');
                if (l.totalScore >= 0) note.append(" Skor verifikasi visual ").append(Math.round(l.totalScore))
                        .append("/100 (obat ").append(Math.round(Math.max(0, l.pillScore))).append(", menelan ")
                        .append(Math.round(Math.max(0, l.ingestScore))).append(").");
                if (!l.notes.isEmpty()) note.append(' ').append(l.notes);
                ms.put("note", new JSONArray().put(new JSONObject().put("text", note.toString())));
                entries.put(new JSONObject().put("fullUrl", "urn:uuid:dose-" + l.id).put("resource", ms));
            }
            bundle.put("entry", entries);
            File f = outFile(c, "json");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(bundle.toString(2));
            }
            return f;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }
}

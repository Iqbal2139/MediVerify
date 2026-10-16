package com.mediverify.app;

import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mediverify.app.data.DoseLog;
import com.mediverify.app.data.Med;
import com.mediverify.app.data.Schedule;
import com.mediverify.app.ui.Charts;
import com.mediverify.app.ui.Fmt;
import com.mediverify.app.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RecapTab {
    private RecapTab() {}

    static int period = 7; // days

    static View build(final MainActivity a) {
        ScrollView[] h = new ScrollView[1];
        LinearLayout body = a.page(h, "Rekap kepatuhan", "Ringkasan minum obat & hasil verifikasi visual");
        long now = System.currentTimeMillis();
        long today = Schedule.dayStart(now);
        long from = Schedule.addDays(today, -(period - 1));
        long to = Schedule.addDays(today, 1);
        List<Med> allMeds = a.db.meds(true);
        Map<String, DoseLog> logs = a.db.resolutions(from, to);
        int grace = a.prefs.graceMinutes(), onTime = a.prefs.onTimeMinutes();

        // period selector
        LinearLayout seg = Ui.hbox(a);
        seg.setBackground(Ui.round(0xFFF1F5F9, Ui.dp(a, 14)));
        seg.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        int[] ps = {7, 30, 90};
        String[] pl = {"7 hari", "30 hari", "90 hari"};
        for (int i = 0; i < 3; i++) {
            final int d = ps[i];
            boolean sel = d == period;
            TextView t = Ui.text(a, pl[i], 13.5f, sel ? Ui.PRIMARY_DARK : Ui.MUTED, sel);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
            if (sel) t.setBackground(Ui.round(Color.WHITE, Ui.dp(a, 11)));
            t.setOnClickListener(v -> {
                period = d;
                a.render();
            });
            seg.addView(t, Ui.lpWeight(1));
        }
        Ui.add(body, seg, 16);

        Schedule.Stats st = Schedule.stats(allMeds, logs, from, to, now, grace, onTime, null);
        int wrong = a.db.countWrongAlerts(from, to);

        LinearLayout hero = Ui.card(a);
        LinearLayout hr = Ui.hbox(a);
        Charts.Ring ring = new Charts.Ring(a);
        float adh = st.adherence();
        int col = adh < 0 ? Ui.FAINT : adh >= 80 ? Ui.SUCCESS : adh >= 50 ? Ui.WARN : Ui.DANGER;
        ring.set(adh, col, "kepatuhan");
        hr.addView(ring, new LinearLayout.LayoutParams(Ui.dp(a, 116), Ui.dp(a, 116)));
        LinearLayout hc = Ui.vbox(a);
        String verdict = adh < 0 ? "Belum ada data" : adh >= 80 ? "Kepatuhan baik" : adh >= 50 ? "Kepatuhan sedang" : "Kepatuhan rendah";
        TextView vt = Ui.h2(a, verdict);
        vt.setTextColor(col);
        hc.addView(vt);
        Ui.add(hc, Ui.caption(a, st.taken + " dari " + st.scheduled + " dosis diminum"
                + (st.pending > 0 ? " • " + st.pending + " menunggu" : "")), 4);
        Ui.add(hc, Ui.caption(a, "Ambang kepatuhan baik ≥ 80% dosis"), 2);
        LinearLayout.LayoutParams hl = Ui.lpWeight(1);
        hl.leftMargin = Ui.dp(a, 16);
        hr.addView(hc, hl);
        hero.addView(hr, Ui.lpMW());
        Ui.add(body, hero, 14);

        // stat grid
        String[][] cells = {
                {Fmt.pct(st.verifiedRate()), "Terverifikasi kamera", st.verified + " dosis"},
                {Fmt.pct(st.onTimeRate()), "Tepat waktu (±" + onTime + " mnt)", st.onTime + " dari " + st.taken},
                {String.valueOf(st.missed), "Dosis terlewat", st.skipped + " dilewati sengaja"},
                {String.valueOf(wrong), "Peringatan obat/dosis salah", st.review + " perlu ditinjau • " + st.manual + " manual"}};
        int[] colors = {Ui.SUCCESS, Ui.PRIMARY, Ui.DANGER, Ui.WARN};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = Ui.hbox(a);
            for (int c = 0; c < 2; c++) {
                String[] cell = cells[r * 2 + c];
                LinearLayout cc = Ui.card(a);
                TextView big = Ui.text(a, cell[0], 24, colors[r * 2 + c], true);
                cc.addView(big);
                cc.addView(Ui.text(a, cell[1], 12.5f, Ui.INK2, true));
                cc.addView(Ui.caption(a, cell[2]));
                LinearLayout.LayoutParams lp = Ui.lpWeight(1);
                if (c > 0) lp.leftMargin = Ui.dp(a, 10);
                row.addView(cc, lp);
            }
            Ui.add(body, row, 10);
        }

        // daily chart
        LinearLayout chartCard = Ui.card(a);
        chartCard.addView(Ui.label(a, "Kepatuhan harian (%)"));
        int days = Math.min(period, 30);
        float[] vals = new float[days];
        String[] labs = new String[days];
        for (int i = 0; i < days; i++) {
            long d = Schedule.addDays(today, -(days - 1 - i));
            Schedule.Stats ds = Schedule.stats(allMeds, logs, d, Schedule.addDays(d, 1), now, grace, onTime, null);
            vals[i] = ds.scheduled == 0 ? -1 : ds.adherence();
            labs[i] = days <= 7 ? Fmt.dayShort(d) : String.valueOf(android.text.format.DateFormat.format("d", d));
        }
        Charts.Bars bars = new Charts.Bars(a);
        bars.set(vals, labs);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 150));
        bl.topMargin = Ui.dp(a, 8);
        chartCard.addView(bars, bl);
        if (period > 30) Ui.add(chartCard, Ui.caption(a, "Grafik menampilkan 30 hari terakhir."), 4);
        Ui.add(body, chartCard, 12);

        // per medication
        LinearLayout perMed = Ui.card(a);
        perMed.addView(Ui.label(a, "Per obat"));
        boolean anyMed = false;
        for (Med m : allMeds) {
            Schedule.Stats ms = Schedule.stats(allMeds, logs, from, to, now, grace, onTime, m.id);
            if (ms.scheduled == 0 && ms.pending == 0) continue;
            anyMed = true;
            LinearLayout row = Ui.hbox(a);
            row.setPadding(0, Ui.dp(a, 10), 0, 0);
            LinearLayout c = Ui.vbox(a);
            c.addView(Ui.text(a, m.name, 14, Ui.INK, true));
            c.addView(Ui.caption(a, ms.taken + "/" + ms.scheduled + " diminum • " + ms.verified + " terverifikasi • " + ms.missed + " terlewat"));
            row.addView(c, Ui.lpWeight(1));
            float v = ms.adherence();
            row.addView(Ui.chip(a, Fmt.pct(v), v < 0 ? 0xFFF1F5F9 : v >= 80 ? Ui.SUCCESS_BG : v >= 50 ? Ui.WARN_BG : Ui.DANGER_BG,
                    v < 0 ? Ui.MUTED : v >= 80 ? 0xFF047857 : v >= 50 ? 0xFFB45309 : Ui.DANGER));
            perMed.addView(row, Ui.lpMW());
        }
        if (!anyMed) Ui.add(perMed, Ui.caption(a, "Belum ada data pada periode ini."), 8);
        Ui.add(body, perMed, 12);

        // export
        LinearLayout ex = Ui.card(a);
        ex.addView(Ui.label(a, "Ekspor untuk dokter / caregiver"));
        LinearLayout er = Ui.hbox(a);
        TextView pdf = Ui.button(a, "📄 PDF", Ui.BTN_PRIMARY);
        pdf.setOnClickListener(v -> share(a, "pdf", from, to));
        er.addView(pdf, Ui.lpWeight(1));
        TextView csv = Ui.button(a, "CSV", Ui.BTN_SECONDARY);
        csv.setOnClickListener(v -> share(a, "csv", from, to));
        LinearLayout.LayoutParams cl = Ui.lpWeight(1);
        cl.leftMargin = Ui.dp(a, 8);
        er.addView(csv, cl);
        TextView fhir = Ui.button(a, "HL7 FHIR", Ui.BTN_SECONDARY);
        fhir.setOnClickListener(v -> share(a, "json", from, to));
        LinearLayout.LayoutParams fl = Ui.lpWeight(1);
        fl.leftMargin = Ui.dp(a, 8);
        er.addView(fhir, fl);
        Ui.add(ex, er, 10);
        Ui.add(ex, Ui.caption(a, "Laporan berisi periode " + Fmt.date(from) + " – " + Fmt.date(now) + "."), 8);
        Ui.add(body, ex, 12);

        // history
        Ui.add(body, Ui.h2(a, "Riwayat"), 22);
        List<DoseLog> hist = a.db.logs(from, to, true);
        if (hist.isEmpty()) Ui.add(body, Ui.caption(a, "Belum ada riwayat."), 8);
        int shown = 0;
        for (final DoseLog l : hist) {
            if (shown++ >= 80) break;
            LinearLayout row = Ui.hbox(a);
            row.setBackground(Ui.ripple(Ui.roundStroke(Color.WHITE, Ui.dp(a, 14), Ui.dp(a, 1), Ui.BORDER), 0x110D9488));
            row.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
            View dot = new View(a);
            dot.setBackground(Ui.round(Ui.statusColor(l.status), Ui.dp(a, 5)));
            row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(a, 10), Ui.dp(a, 10)));
            LinearLayout c = Ui.vbox(a);
            c.addView(Ui.text(a, l.medName, 14, Ui.INK, true));
            String sub = "Jadwal " + Fmt.dateTime(l.slotTime);
            if (l.isTaken()) sub += " • diminum " + Fmt.time(l.takenAt);
            if (l.totalScore >= 0) sub += " • skor " + Math.round(l.totalScore);
            c.addView(Ui.caption(a, sub));
            LinearLayout.LayoutParams lp = Ui.lpWeight(1);
            lp.leftMargin = Ui.dp(a, 12);
            row.addView(c, lp);
            row.addView(Ui.chip(a, DoseLog.statusLabel(l.status), Ui.statusBg(l.status), Ui.statusColor(l.status)));
            row.setOnClickListener(v -> {
                Intent i = new Intent(a, LogDetailActivity.class);
                i.putExtra(LogDetailActivity.EXTRA_LOG, l.id);
                a.startActivity(i);
            });
            Ui.add(body, row, 8);
        }
        return h[0];
    }

    private static void share(MainActivity a, String kind, long from, long to) {
        try {
            File f;
            String mime;
            if (kind.equals("pdf")) {
                f = ReportExporter.pdf(a, from, to);
                mime = "application/pdf";
            } else if (kind.equals("csv")) {
                f = ReportExporter.csv(a, from, to);
                mime = "text/csv";
            } else {
                f = ReportExporter.fhir(a, from, to);
                mime = "application/fhir+json";
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, ExportProvider.uriFor(a, f));
            send.putExtra(Intent.EXTRA_SUBJECT, "Laporan kepatuhan minum obat MediVerify");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(send, "Bagikan / simpan laporan"));
        } catch (Exception e) {
            Toast.makeText(a, "Gagal membuat laporan: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

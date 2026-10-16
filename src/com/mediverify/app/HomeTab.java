package com.mediverify.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mediverify.app.data.DoseLog;
import com.mediverify.app.data.Med;
import com.mediverify.app.data.Schedule;
import com.mediverify.app.ui.Charts;
import com.mediverify.app.ui.Fmt;
import com.mediverify.app.ui.PillIconView;
import com.mediverify.app.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class HomeTab {
    private HomeTab() {}

    static View build(final MainActivity a) {
        ScrollView[] h = new ScrollView[1];
        String name = a.prefs.patientName().trim();
        LinearLayout body = a.page(h, name.isEmpty() ? "Halo 👋" : "Halo, " + firstName(name) + " 👋",
                Fmt.dateLong(System.currentTimeMillis()));
        long now = System.currentTimeMillis();
        long today = Schedule.dayStart(now);
        List<Med> meds = a.db.meds(false);
        Map<String, DoseLog> logs = a.db.resolutions(Schedule.addDays(today, -30), Schedule.addDays(today, 1));
        List<Schedule.Slot> slots = Schedule.slotsForDay(meds, today, logs);
        int grace = a.prefs.graceMinutes();

        View warn = a.permissionBanner(false);
        if (warn != null) Ui.add(body, warn, 14);

        if (meds.isEmpty()) {
            LinearLayout c = Ui.card(a);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            PillIconView ic = new PillIconView(a, 1);
            c.addView(ic, new LinearLayout.LayoutParams(Ui.dp(a, 72), Ui.dp(a, 72)));
            TextView t = Ui.h2(a, "Belum ada jadwal obat");
            t.setGravity(Gravity.CENTER);
            Ui.add(c, t, 12);
            TextView d = Ui.body(a, "Tambahkan obat beserta jam minumnya. Rekam juga foto referensi obat agar verifikasi kamera bisa mengenali obat Anda.");
            d.setGravity(Gravity.CENTER);
            Ui.add(c, d, 6);
            TextView b = Ui.button(a, "+ Tambah obat pertama", Ui.BTN_PRIMARY);
            b.setOnClickListener(v -> a.startActivity(new Intent(a, MedEditActivity.class)));
            Ui.add(c, b, 16);
            if (a.prefs.patientName().isEmpty()) {
                TextView p = Ui.button(a, "Lengkapi profil pasien & caregiver", Ui.BTN_GHOST);
                p.setOnClickListener(v -> a.select(3));
                Ui.add(c, p, 6);
            }
            Ui.add(body, c, 16);
            Ui.add(body, howItWorks(a), 14);
            return h[0];
        }

        // ---- hero: next pending dose
        Schedule.Slot next = null;
        for (Schedule.Slot s : slots) {
            if (s.log != null) continue;
            if (s.time + grace * 60000L < now) continue;
            next = s;
            break;
        }
        LinearLayout hero = Ui.vbox(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0F766E, 0xFF0D9488, 0xFF14B8A6});
        g.setCornerRadius(Ui.dp(a, 22));
        hero.setBackground(g);
        int p = Ui.dp(a, 18);
        hero.setPadding(p, p, p, p);
        if (next != null) {
            final Schedule.Slot ns = next;
            long diff = (next.time - now) / 60000L;
            String when = diff > 0 ? "dalam " + Fmt.duration(diff) : diff >= -a.prefs.onTimeMinutes() ? "sekarang" : "terlambat " + Fmt.duration(-diff);
            TextView lab = Ui.text(a, "DOSIS BERIKUTNYA • " + when.toUpperCase(Fmt.ID), 11.5f, 0xCCFFFFFF, true);
            lab.setLetterSpacing(0.06f);
            hero.addView(lab);
            LinearLayout row = Ui.hbox(a);
            LinearLayout col = Ui.vbox(a);
            TextView tm = Ui.text(a, Fmt.time(next.time), 40, Color.WHITE, true);
            col.addView(tm);
            col.addView(Ui.text(a, next.med.name, 18, Color.WHITE, true));
            col.addView(Ui.text(a, next.med.doseLabel() + (next.med.instructions.isEmpty() ? "" : " • " + next.med.instructions), 13, 0xDDFFFFFF, false));
            row.addView(col, Ui.lpWeight(1));
            PillIconView pi = new PillIconView(a, next.med.color);
            row.addView(pi, new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 64)));
            Ui.add(hero, row, 6);
            TextView vb = Ui.button(a, "📷  Verifikasi minum obat", Ui.BTN_LIGHT);
            vb.setOnClickListener(v -> verify(a, ns));
            Ui.add(hero, vb, 14);
        } else {
            TextView lab = Ui.text(a, "HARI INI", 11.5f, 0xCCFFFFFF, true);
            hero.addView(lab);
            boolean allTaken = !slots.isEmpty();
            for (Schedule.Slot s : slots) if (s.log == null || !s.log.isTaken()) allTaken = false;
            Ui.add(hero, Ui.text(a, slots.isEmpty() ? "Tidak ada jadwal obat hari ini"
                    : allTaken ? "Semua obat hari ini sudah diminum 🎉" : "Tidak ada dosis yang menunggu", 20, Color.WHITE, true), 6);
            Ui.add(hero, Ui.text(a, "Pengingat berikutnya akan berbunyi sesuai jadwal.", 13, 0xDDFFFFFF, false), 4);
        }
        Ui.add(body, hero, 16);

        // ---- today summary
        Schedule.Stats st = Schedule.stats(meds, logs, today, Schedule.addDays(today, 1), now, grace, a.prefs.onTimeMinutes(), null);
        Schedule.Stats wk = Schedule.stats(meds, logs, Schedule.addDays(today, -6), Schedule.addDays(today, 1), now, grace, a.prefs.onTimeMinutes(), null);
        LinearLayout sum = Ui.hbox(a);
        LinearLayout c1 = Ui.card(a);
        c1.setGravity(Gravity.CENTER_HORIZONTAL);
        Charts.Ring r1 = new Charts.Ring(a);
        r1.set(st.adherence(), Ui.PRIMARY, "hari ini");
        c1.addView(r1, new LinearLayout.LayoutParams(Ui.dp(a, 92), Ui.dp(a, 92)));
        TextView t1 = Ui.caption(a, st.taken + " dari " + (st.scheduled) + " dosis");
        t1.setGravity(Gravity.CENTER);
        Ui.add(c1, t1, 6);
        sum.addView(c1, Ui.lpWeight(1));
        LinearLayout c2 = Ui.card(a);
        c2.setGravity(Gravity.CENTER_HORIZONTAL);
        Charts.Ring r2 = new Charts.Ring(a);
        r2.set(wk.adherence(), wk.adherence() >= 80 || wk.adherence() < 0 ? Ui.SUCCESS : wk.adherence() >= 50 ? Ui.WARN : Ui.DANGER, "7 hari");
        c2.addView(r2, new LinearLayout.LayoutParams(Ui.dp(a, 92), Ui.dp(a, 92)));
        TextView t2 = Ui.caption(a, "Terverifikasi kamera " + Fmt.pct(wk.verifiedRate()));
        t2.setGravity(Gravity.CENTER);
        Ui.add(c2, t2, 6);
        LinearLayout.LayoutParams l2 = Ui.lpWeight(1);
        l2.leftMargin = Ui.dp(a, 12);
        sum.addView(c2, l2);
        Ui.add(body, sum, 14);

        // ---- schedule list
        LinearLayout head = Ui.hbox(a);
        head.addView(Ui.h2(a, "Jadwal hari ini"), Ui.lpWeight(1));
        Ui.add(body, head, 22);
        if (slots.isEmpty()) {
            Ui.add(body, Ui.caption(a, "Tidak ada obat terjadwal hari ini."), 8);
        }
        for (Schedule.Slot s : slots) Ui.add(body, slotRow(a, s, now, grace), 10);

        // missed in last days needing attention
        List<Schedule.Slot> missed = new ArrayList<>();
        for (int d = -2; d < 0; d++) {
            for (Schedule.Slot s : Schedule.slotsForDay(meds, Schedule.addDays(today, d), logs)) {
                if (s.log != null && s.log.status == DoseLog.MISSED) missed.add(s);
            }
        }
        if (!missed.isEmpty()) {
            Ui.add(body, Ui.h2(a, "Terlewat 2 hari terakhir"), 22);
            for (Schedule.Slot s : missed) Ui.add(body, slotRow(a, s, now, grace), 10);
        }
        Ui.add(body, howItWorks(a), 22);
        return h[0];
    }

    private static String firstName(String n) {
        String[] parts = n.split("\\s+");
        for (String s : parts) {
            String l = s.toLowerCase(Fmt.ID).replace(".", "");
            if (l.equals("bpk") || l.equals("bapak") || l.equals("ibu") || l.equals("sdr") || l.equals("sdri") || l.equals("tn") || l.equals("ny")) continue;
            return s;
        }
        return n;
    }

    static void verify(MainActivity a, Schedule.Slot s) {
        Intent i = new Intent(a, VerifyActivity.class);
        i.putExtra(VerifyActivity.EXTRA_MED, s.med.id);
        i.putExtra(VerifyActivity.EXTRA_SLOT, s.time);
        a.startActivity(i);
    }

    private static View slotRow(final MainActivity a, final Schedule.Slot s, long now, int grace) {
        LinearLayout row = Ui.hbox(a);
        row.setBackground(Ui.ripple(Ui.roundStroke(Color.WHITE, Ui.dp(a, 16), Ui.dp(a, 1), Ui.BORDER), 0x110D9488));
        row.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        LinearLayout tcol = Ui.vbox(a);
        tcol.setGravity(Gravity.CENTER_HORIZONTAL);
        tcol.addView(Ui.text(a, Fmt.time(s.time), 17, Ui.INK, true));
        if (Schedule.dayStart(s.time) != Schedule.dayStart(now)) tcol.addView(Ui.caption(a, Fmt.dayShort(s.time)));
        row.addView(tcol, new LinearLayout.LayoutParams(Ui.dp(a, 58), LinearLayout.LayoutParams.WRAP_CONTENT));
        PillIconView ic = new PillIconView(a, s.med.color);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40));
        il.leftMargin = Ui.dp(a, 4);
        row.addView(ic, il);
        LinearLayout col = Ui.vbox(a);
        col.addView(Ui.text(a, s.med.name, 15, Ui.INK, true));
        col.addView(Ui.caption(a, s.med.doseLabel() + (s.med.instructions.isEmpty() ? "" : " • " + s.med.instructions)));
        LinearLayout.LayoutParams cl = Ui.lpWeight(1);
        cl.leftMargin = Ui.dp(a, 12);
        row.addView(col, cl);
        String label;
        int bg, fg;
        if (s.log != null) {
            label = DoseLog.statusLabel(s.log.status);
            if (s.log.isTaken()) label = "✓ " + Fmt.time(s.log.takenAt);
            bg = Ui.statusBg(s.log.status);
            fg = Ui.statusColor(s.log.status);
            if (s.log.status == DoseLog.VERIFIED) fg = 0xFF047857;
        } else if (s.time + grace * 60000L < now) {
            label = "Terlewat";
            bg = Ui.DANGER_BG;
            fg = Ui.DANGER;
        } else if (s.time <= now + 15 * 60000L) {
            label = "Minum sekarang";
            bg = Ui.PRIMARY;
            fg = Color.WHITE;
        } else {
            label = "Menunggu";
            bg = 0xFFF1F5F9;
            fg = Ui.MUTED;
        }
        row.addView(Ui.chip(a, label, bg, fg), Ui.lpWW());
        row.setOnClickListener(v -> onSlotClick(a, s));
        return row;
    }

    private static void onSlotClick(final MainActivity a, final Schedule.Slot s) {
        if (s.log != null && s.log.isTaken()) {
            Intent i = new Intent(a, LogDetailActivity.class);
            i.putExtra(LogDetailActivity.EXTRA_LOG, s.log.id);
            a.startActivity(i);
            return;
        }
        List<String> items = new ArrayList<>();
        items.add("📷 Verifikasi dengan kamera");
        items.add("⏭ Tandai dilewati (tidak diminum)");
        if (s.log != null) items.add("ℹ Lihat catatan");
        new AlertDialog.Builder(a)
                .setTitle(s.med.name + " • " + Fmt.time(s.time))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) verify(a, s);
                    else if (which == 1) {
                        DoseLog l = new DoseLog();
                        l.medId = s.med.id;
                        l.medName = s.med.name;
                        l.slotTime = s.time;
                        l.takenAt = System.currentTimeMillis();
                        l.status = DoseLog.SKIPPED;
                        l.notes = "Ditandai dilewati oleh pengguna";
                        a.db.recordResolution(l);
                        com.mediverify.app.reminder.Reminders.dismiss(a, s.med.id, s.time);
                        a.render();
                    } else if (s.log != null) {
                        Intent i = new Intent(a, LogDetailActivity.class);
                        i.putExtra(LogDetailActivity.EXTRA_LOG, s.log.id);
                        a.startActivity(i);
                    }
                })
                .show();
    }

    static View howItWorks(MainActivity a) {
        LinearLayout c = Ui.card(a);
        c.setBackground(Ui.roundStroke(Ui.PRIMARY_LIGHT, Ui.dp(a, 18), Ui.dp(a, 1), Ui.PRIMARY_SOFT));
        c.addView(Ui.label(a, "Cara kerja verifikasi"));
        String[] steps = {
                "1. Tunjukkan obat di telapak tangan — dicocokkan dengan foto referensi (warna dinormalisasi terhadap warna kulit, bentuk, ukuran & jumlah tablet).",
                "2. Pemeriksaan wajah dengan gerakan acak (kiri/kanan) untuk memastikan orang asli, bukan foto.",
                "3. Obat terlihat dibawa ke mulut, lalu minum air.",
                "4. Buka mulut — obat tidak boleh terlihat lagi.",
                "Foto bukti & skor disimpan di riwayat untuk dokter/caregiver."};
        for (String s : steps) Ui.add(c, Ui.text(a, s, 13, 0xFF115E59, false), 6);
        return c;
    }
}

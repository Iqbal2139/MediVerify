package com.mediverify.app;

import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mediverify.app.data.Med;
import com.mediverify.app.ui.Fmt;
import com.mediverify.app.ui.PillIconView;
import com.mediverify.app.ui.Ui;
import com.mediverify.app.vision.PillProfile;

import java.util.List;

final class MedsTab {
    private MedsTab() {}

    static View build(final MainActivity a) {
        ScrollView[] h = new ScrollView[1];
        LinearLayout body = a.page(h, "Obat saya", "Resep, jadwal minum, dan foto referensi visual");
        TextView add = Ui.button(a, "+ Tambah obat", Ui.BTN_PRIMARY);
        add.setOnClickListener(v -> a.startActivity(new Intent(a, MedEditActivity.class)));
        Ui.add(body, add, 16);
        List<Med> meds = a.db.meds(true);
        if (meds.isEmpty()) {
            TextView t = Ui.body(a, "Belum ada obat. Tekan \"Tambah obat\" untuk memasukkan resep dari dokter.");
            t.setGravity(Gravity.CENTER);
            Ui.add(body, t, 24);
        }
        for (final Med m : meds) {
            LinearLayout c = Ui.card(a);
            c.setBackground(Ui.ripple(Ui.roundStroke(m.active ? Color.WHITE : 0xFFF8FAFC, Ui.dp(a, 18), Ui.dp(a, 1), Ui.BORDER), 0x110D9488));
            LinearLayout row = Ui.hbox(a);
            row.addView(new PillIconView(a, m.color), new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
            LinearLayout col = Ui.vbox(a);
            col.addView(Ui.text(a, m.name, 16, m.active ? Ui.INK : Ui.FAINT, true));
            col.addView(Ui.caption(a, m.doseLabel() + (m.instructions.isEmpty() ? "" : " • " + m.instructions)));
            LinearLayout.LayoutParams cl = Ui.lpWeight(1);
            cl.leftMargin = Ui.dp(a, 12);
            row.addView(col, cl);
            if (!m.active) row.addView(Ui.chip(a, "Nonaktif", 0xFFF1F5F9, Ui.MUTED));
            c.addView(row, Ui.lpMW());

            LinearLayout times = Ui.hbox(a);
            for (int[] t : m.timeList()) {
                LinearLayout.LayoutParams lp = Ui.lpWW();
                lp.rightMargin = Ui.dp(a, 6);
                times.addView(Ui.chip(a, String.format(java.util.Locale.US, "⏰ %02d:%02d", t[0], t[1]), Ui.PRIMARY_LIGHT, Ui.PRIMARY_DARK), lp);
            }
            Ui.add(c, times, 10);

            StringBuilder info = new StringBuilder();
            if (m.color >= 0) info.append(PillProfile.COLORS[m.color]);
            if (m.shape >= 0) info.append(info.length() > 0 ? " • " : "").append(PillProfile.SHAPES[m.shape]);
            if (m.stock >= 0) info.append(info.length() > 0 ? " • " : "").append("Stok ").append(m.stock);
            if (m.endDate > 0) info.append(info.length() > 0 ? " • " : "").append("s.d. ").append(Fmt.date(m.endDate));
            if (info.length() > 0) Ui.add(c, Ui.caption(a, info.toString()), 8);

            TextView ref = m.refProfile != null
                    ? Ui.chip(a, "✓ Foto referensi visual terekam", Ui.SUCCESS_BG, 0xFF047857)
                    : Ui.chip(a, "⚠ Belum ada foto referensi — akurasi verifikasi lebih rendah", Ui.WARN_BG, 0xFFB45309);
            LinearLayout.LayoutParams rl = Ui.lpWW();
            rl.topMargin = Ui.dp(a, 8);
            c.addView(ref, rl);
            c.setOnClickListener(v -> {
                Intent i = new Intent(a, MedEditActivity.class);
                i.putExtra(MedEditActivity.EXTRA_ID, m.id);
                a.startActivity(i);
            });
            Ui.add(body, c, 12);
        }
        return h[0];
    }
}

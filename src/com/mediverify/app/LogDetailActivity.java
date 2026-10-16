package com.mediverify.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mediverify.app.data.Db;
import com.mediverify.app.data.DoseLog;
import com.mediverify.app.data.Prefs;
import com.mediverify.app.ui.Fmt;
import com.mediverify.app.ui.Ui;

import java.io.File;

public final class LogDetailActivity extends Activity {
    public static final String EXTRA_LOG = "log";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBar(this, Color.WHITE, false);
        final DoseLog l = Db.get(this).log(getIntent().getLongExtra(EXTRA_LOG, -1));
        if (l == null) {
            finish();
            return;
        }
        Ui.Screen s = new Ui.Screen(this, "Detail catatan", true);
        LinearLayout body = s.body;

        LinearLayout c = Ui.card(this);
        c.addView(Ui.h1(this, l.medName));
        TextView chip = Ui.chip(this, DoseLog.statusLabel(l.status), Ui.statusBg(l.status), Ui.statusColor(l.status));
        LinearLayout.LayoutParams cl = Ui.lpWW();
        cl.topMargin = Ui.dp(this, 8);
        c.addView(chip, cl);
        row(c, "Jadwal", Fmt.dateLong(l.slotTime) + ", " + Fmt.time(l.slotTime));
        if (l.isTaken() || l.status == DoseLog.ATTEMPT_FAILED || l.status == DoseLog.SKIPPED) {
            row(c, l.isTaken() ? "Diminum" : "Dicatat", Fmt.dateTime(l.takenAt));
        }
        if (l.isTaken()) {
            long late = l.minutesLate();
            int win = new Prefs(this).onTimeMinutes();
            row(c, "Ketepatan waktu", Math.abs(late) <= win ? "Tepat waktu" : late > 0 ? "Terlambat " + Fmt.duration(late) : "Lebih awal " + Fmt.duration(-late));
        }
        if (l.totalScore >= 0) {
            row(c, "Skor total verifikasi", Math.round(l.totalScore) + " / 100");
            row(c, "Skor identifikasi obat", l.pillScore < 0 ? "–" : Math.round(l.pillScore) + " / 100");
            row(c, "Skor proses menelan", l.ingestScore < 0 ? "–" : Math.round(l.ingestScore) + " / 100");
            row(c, "Jumlah tablet terdeteksi", String.valueOf(l.detectedCount));
        }
        if (l.wrongAlerts > 0) row(c, "Peringatan obat/dosis salah", l.wrongAlerts + " kali");
        if (l.durationMs > 0) row(c, "Durasi verifikasi", (l.durationMs / 1000) + " detik");
        body.addView(c, Ui.lpMW());

        if (!l.notes.isEmpty()) {
            LinearLayout n = Ui.card(this);
            n.addView(Ui.label(this, "Catatan sistem"));
            Ui.add(n, Ui.body(this, l.notes), 6);
            Ui.add(body, n, 12);
        }
        if (l.photoPill != null || l.photoMouth != null) {
            LinearLayout p = Ui.card(this);
            p.addView(Ui.label(this, "Foto bukti (ketuk untuk memperbesar)"));
            LinearLayout r = Ui.hbox(this);
            thumb(r, l.photoPill, "Obat di telapak tangan");
            thumb(r, l.photoMouth, "Mulut setelah menelan");
            Ui.add(p, r, 8);
            Ui.add(body, p, 12);
        }
        TextView del = Ui.button(this, "Hapus catatan ini", Ui.BTN_GHOST);
        del.setTextColor(Ui.DANGER);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Hapus catatan?")
                .setMessage("Catatan dan foto buktinya akan dihapus permanen. Gunakan hanya untuk memperbaiki catatan yang salah.")
                .setPositiveButton("Hapus", (d, w) -> {
                    if (l.photoPill != null) new File(l.photoPill).delete();
                    if (l.photoMouth != null) new File(l.photoMouth).delete();
                    Db.get(this).deleteLog(l.id);
                    finish();
                })
                .setNegativeButton("Batal", null).show());
        Ui.add(body, del, 20);
        setContentView(s.root);
    }

    private void row(LinearLayout parent, String k, String v) {
        LinearLayout r = Ui.hbox(this);
        r.setPadding(0, Ui.dp(this, 10), 0, 0);
        r.addView(Ui.caption(this, k), Ui.lpWeight(1));
        TextView tv = Ui.text(this, v, 14, Ui.INK, true);
        tv.setGravity(android.view.Gravity.END);
        r.addView(tv, Ui.lpWeight(1.3f));
        parent.addView(r, Ui.lpMW());
    }

    private void thumb(LinearLayout row, final String path, String label) {
        if (path == null || !new File(path).exists()) return;
        LinearLayout c = Ui.vbox(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        Bitmap bm = BitmapFactory.decodeFile(path, o);
        if (bm != null) iv.setImageBitmap(bm);
        iv.setClipToOutline(true);
        iv.setBackground(Ui.round(0xFFE2E8F0, Ui.dp(this, 12)));
        iv.setOnClickListener(v -> {
            Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ImageView big = new ImageView(this);
            big.setScaleType(ImageView.ScaleType.FIT_CENTER);
            big.setBackgroundColor(Color.BLACK);
            Bitmap full = BitmapFactory.decodeFile(path);
            if (full != null) big.setImageBitmap(full);
            big.setOnClickListener(x -> d.dismiss());
            d.setContentView(big, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            d.show();
        });
        c.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 140), Ui.dp(this, 185)));
        TextView t = Ui.caption(this, label);
        c.addView(t);
        LinearLayout.LayoutParams lp = Ui.lpWW();
        lp.rightMargin = Ui.dp(this, 10);
        row.addView(c, lp);
    }
}

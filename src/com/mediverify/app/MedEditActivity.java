package com.mediverify.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.mediverify.app.data.Db;
import com.mediverify.app.data.Med;
import com.mediverify.app.data.Schedule;
import com.mediverify.app.reminder.Reminders;
import com.mediverify.app.ui.Fmt;
import com.mediverify.app.ui.PillIconView;
import com.mediverify.app.ui.Ui;
import com.mediverify.app.vision.PillProfile;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class MedEditActivity extends Activity {
    public static final String EXTRA_ID = "id";
    private static final int REQ_ENROLL = 51;

    private Med med;
    private boolean isNew;
    private EditText name, dose, instr, stock;
    private TextView formBox, countBox, colorBox, shapeBox, startBox, endBox;
    private LinearLayout timesBox, refBox;
    private Switch water, active;
    private final List<int[]> times = new ArrayList<>();
    private PillIconView icon;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBar(this, Color.WHITE, false);
        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        med = id > 0 ? Db.get(this).med(id) : null;
        isNew = med == null;
        if (isNew) {
            med = new Med();
            med.startDate = Schedule.dayStart(System.currentTimeMillis());
            med.times = "08:00";
        }
        if (b != null) {
            med.refProfile = b.getString("ref", med.refProfile);
            med.refPhoto = b.getString("refPhoto", med.refPhoto);
        }
        times.addAll(med.timeList());
        build();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("ref", med.refProfile);
        out.putString("refPhoto", med.refPhoto);
    }

    private void build() {
        Ui.Screen s = new Ui.Screen(this, isNew ? "Tambah obat" : "Ubah obat", true);
        LinearLayout body = s.body;

        LinearLayout top = Ui.hbox(this);
        icon = new PillIconView(this, med.color);
        top.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        TextView tip = Ui.caption(this, "Isi sesuai resep dokter. Jam minum akan menjadi alarm pengingat.");
        LinearLayout.LayoutParams tl = Ui.lpWeight(1);
        tl.leftMargin = Ui.dp(this, 12);
        top.addView(tip, tl);
        body.addView(top, Ui.lpMW());

        name = Ui.input(this, "Contoh: Amlodipine", med.name, 0);
        Ui.add(body, Ui.field(this, "Nama obat *", name), 16);

        LinearLayout r1 = Ui.hbox(this);
        dose = Ui.input(this, "500 mg", med.dose, InputType.TYPE_CLASS_TEXT);
        r1.addView(Ui.field(this, "Kekuatan / dosis", dose), Ui.lpWeight(1));
        formBox = Ui.selectBox(this, med.form + "  ▾");
        formBox.setOnClickListener(v -> pick("Bentuk sediaan", new String[]{"Tablet", "Kapsul", "Kaplet"}, i -> {
            med.form = new String[]{"Tablet", "Kapsul", "Kaplet"}[i];
            formBox.setText(med.form + "  ▾");
            if (med.shape < 0) {
                med.shape = i == 1 ? 2 : i == 2 ? 1 : -1;
                shapeBox.setText(shapeText());
            }
        }));
        LinearLayout.LayoutParams fl = Ui.lpWeight(1);
        fl.leftMargin = Ui.dp(this, 10);
        r1.addView(Ui.field(this, "Bentuk", formBox), fl);
        Ui.add(body, r1, 14);

        countBox = Ui.selectBox(this, med.pillsPerDose + " " + med.form.toLowerCase(Locale.ROOT) + " sekali minum  ▾");
        countBox.setOnClickListener(v -> pick("Jumlah per sekali minum", new String[]{"1", "2", "3", "4"}, i -> {
            med.pillsPerDose = i + 1;
            countBox.setText(med.pillsPerDose + " " + med.form.toLowerCase(Locale.ROOT) + " sekali minum  ▾");
        }));
        Ui.add(body, Ui.field(this, "Jumlah per minum", countBox), 14);

        // times
        LinearLayout tHead = Ui.hbox(this);
        tHead.addView(Ui.text(this, "Jam minum (alarm) *", 13, Ui.INK2, true), Ui.lpWeight(1));
        TextView preset = Ui.text(this, "Pola cepat ▾", 13, Ui.PRIMARY, true);
        preset.setOnClickListener(v -> pick("Frekuensi", new String[]{"1x sehari (08:00)", "1x sehari malam (20:00)",
                "2x sehari (08:00, 20:00)", "3x sehari (07:00, 13:00, 19:00)", "4x sehari (06:00, 12:00, 18:00, 22:00)"}, i -> {
            times.clear();
            int[][][] p = {{{8, 0}}, {{20, 0}}, {{8, 0}, {20, 0}}, {{7, 0}, {13, 0}, {19, 0}}, {{6, 0}, {12, 0}, {18, 0}, {22, 0}}};
            for (int[] t : p[i]) times.add(new int[]{t[0], t[1]});
            renderTimes();
        }));
        tHead.addView(preset);
        Ui.add(body, tHead, 16);
        timesBox = Ui.vbox(this);
        Ui.add(body, timesBox, 6);
        renderTimes();

        instr = Ui.input(this, "Contoh: sesudah makan", med.instructions, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout insField = Ui.field(this, "Aturan minum", instr);
        LinearLayout chips = Ui.hbox(this);
        for (final String c : new String[]{"Sebelum makan", "Sesudah makan", "Saat makan", "Sebelum tidur"}) {
            TextView ch = Ui.chip(this, c, 0xFFF1F5F9, Ui.INK2);
            ch.setOnClickListener(v -> instr.setText(c));
            LinearLayout.LayoutParams lp = Ui.lpWW();
            lp.rightMargin = Ui.dp(this, 6);
            chips.addView(ch, lp);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        Ui.add(insField, hs, 8);
        Ui.add(body, insField, 16);

        // appearance
        LinearLayout r2 = Ui.hbox(this);
        colorBox = Ui.selectBox(this, colorText());
        colorBox.setOnClickListener(v -> pick("Warna obat", PillProfile.COLORS, i -> {
            med.color = i;
            colorBox.setText(colorText());
            icon.setColorIdx(i);
        }));
        r2.addView(Ui.field(this, "Warna", colorBox), Ui.lpWeight(1));
        shapeBox = Ui.selectBox(this, shapeText());
        shapeBox.setOnClickListener(v -> pick("Bentuk fisik", PillProfile.SHAPES, i -> {
            med.shape = i;
            shapeBox.setText(shapeText());
        }));
        LinearLayout.LayoutParams sl = Ui.lpWeight(1);
        sl.leftMargin = Ui.dp(this, 10);
        r2.addView(Ui.field(this, "Bentuk fisik", shapeBox), sl);
        Ui.add(body, r2, 16);

        // reference
        LinearLayout refCard = Ui.card(this);
        refCard.setBackground(Ui.roundStroke(Ui.PRIMARY_LIGHT, Ui.dp(this, 16), Ui.dp(this, 1), Ui.PRIMARY_SOFT));
        refCard.addView(Ui.text(this, "Foto referensi visual", 15, Ui.INK, true));
        Ui.add(refCard, Ui.caption(this, "Rekam 1 tablet di telapak tangan dengan kamera depan. Aplikasi menyimpan ciri warna (dinormalisasi terhadap kulit), bentuk, dan ukuran obat untuk dicocokkan setiap kali minum."), 4);
        refBox = Ui.vbox(this);
        Ui.add(refCard, refBox, 10);
        renderRef();
        Ui.add(body, refCard, 18);

        water = new Switch(this);
        water.setText("Wajib terdeteksi minum air saat verifikasi");
        water.setTextColor(Ui.INK2);
        water.setChecked(med.requireWater);
        Ui.add(body, water, 16);

        LinearLayout r3 = Ui.hbox(this);
        startBox = Ui.selectBox(this, Fmt.date(med.startDate > 0 ? med.startDate : System.currentTimeMillis()));
        startBox.setOnClickListener(v -> pickDate(med.startDate > 0 ? med.startDate : System.currentTimeMillis(), t -> {
            med.startDate = t;
            startBox.setText(Fmt.date(t));
        }));
        r3.addView(Ui.field(this, "Mulai", startBox), Ui.lpWeight(1));
        endBox = Ui.selectBox(this, med.endDate > 0 ? Fmt.date(med.endDate) : "Tanpa batas");
        endBox.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Tanggal selesai")
                .setItems(new String[]{"Pilih tanggal…", "Tanpa batas (obat rutin)", "3 hari", "5 hari", "7 hari", "14 hari", "30 hari"}, (d, i) -> {
                    long base = med.startDate > 0 ? med.startDate : Schedule.dayStart(System.currentTimeMillis());
                    if (i == 0) pickDate(med.endDate > 0 ? med.endDate : base, t -> {
                        med.endDate = t;
                        endBox.setText(Fmt.date(t));
                    });
                    else if (i == 1) {
                        med.endDate = 0;
                        endBox.setText("Tanpa batas");
                    } else {
                        int[] days = {0, 0, 3, 5, 7, 14, 30};
                        med.endDate = Schedule.addDays(Schedule.dayStart(base), days[i] - 1);
                        endBox.setText(Fmt.date(med.endDate));
                    }
                }).show());
        LinearLayout.LayoutParams el = Ui.lpWeight(1);
        el.leftMargin = Ui.dp(this, 10);
        r3.addView(Ui.field(this, "Selesai", endBox), el);
        Ui.add(body, r3, 16);

        stock = Ui.input(this, "Opsional", med.stock >= 0 ? String.valueOf(med.stock) : "", InputType.TYPE_CLASS_NUMBER);
        Ui.add(body, Ui.field(this, "Stok saat ini (" + med.form.toLowerCase(Locale.ROOT) + ")", stock), 16);

        if (!isNew) {
            active = new Switch(this);
            active.setText("Obat aktif (pengingat menyala)");
            active.setTextColor(Ui.INK2);
            active.setChecked(med.active);
            Ui.add(body, active, 16);
        }

        TextView save = Ui.button(this, "Simpan", Ui.BTN_PRIMARY);
        save.setOnClickListener(v -> save());
        Ui.add(body, save, 24);
        if (!isNew) {
            TextView del = Ui.button(this, "Hapus obat", Ui.BTN_GHOST);
            del.setTextColor(Ui.DANGER);
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Hapus " + med.name + "?")
                    .setMessage("Pengingat dihentikan. Riwayat minum obat tetap tersimpan di rekap.")
                    .setPositiveButton("Hapus", (d, w) -> {
                        Reminders.cancelAll(this, med.id);
                        Db.get(this).deleteMed(med.id);
                        finish();
                    })
                    .setNegativeButton("Batal", null).show());
            Ui.add(body, del, 8);
        }
        setContentView(s.root);
    }

    private String colorText() {
        return (med.color >= 0 ? PillProfile.COLORS[med.color] : "Pilih warna") + "  ▾";
    }

    private String shapeText() {
        return (med.shape >= 0 ? PillProfile.SHAPES[med.shape] : "Pilih bentuk") + "  ▾";
    }

    private void renderTimes() {
        timesBox.removeAllViews();
        java.util.Collections.sort(times, (x, y) -> x[0] * 60 + x[1] - (y[0] * 60 + y[1]));
        LinearLayout row = null;
        for (int i = 0; i < times.size(); i++) {
            if (i % 3 == 0) {
                row = Ui.hbox(this);
                LinearLayout.LayoutParams lp = Ui.lpMW();
                if (i > 0) lp.topMargin = Ui.dp(this, 8);
                timesBox.addView(row, lp);
            }
            final int idx = i;
            final int[] t = times.get(i);
            LinearLayout chip = Ui.hbox(this);
            chip.setBackground(Ui.roundStroke(Ui.PRIMARY_LIGHT, Ui.dp(this, 12), Ui.dp(this, 1), Ui.PRIMARY_SOFT));
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
            TextView tv = Ui.text(this, String.format(Locale.US, "%02d:%02d", t[0], t[1]), 16, Ui.PRIMARY_DARK, true);
            tv.setOnClickListener(v -> new TimePickerDialog(this, (tp, hh, mm) -> {
                t[0] = hh;
                t[1] = mm;
                renderTimes();
            }, t[0], t[1], true).show());
            chip.addView(tv, Ui.lpWeight(1));
            TextView x = Ui.text(this, "✕", 14, Ui.MUTED, true);
            x.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 6), 0);
            x.setOnClickListener(v -> {
                times.remove(idx);
                renderTimes();
            });
            chip.addView(x);
            LinearLayout.LayoutParams lp = Ui.lpWeight(1);
            if (i % 3 > 0) lp.leftMargin = Ui.dp(this, 8);
            row.addView(chip, lp);
        }
        TextView add = Ui.button(this, "+ Tambah jam", Ui.BTN_LIGHT);
        add.setTextSize(13);
        add.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        add.setOnClickListener(v -> new TimePickerDialog(this, (tp, hh, mm) -> {
            for (int[] e : times) if (e[0] == hh && e[1] == mm) return;
            times.add(new int[]{hh, mm});
            renderTimes();
        }, 12, 0, true).show());
        LinearLayout.LayoutParams al = Ui.lpWW();
        al.topMargin = Ui.dp(this, 8);
        timesBox.addView(add, al);
    }

    private void renderRef() {
        refBox.removeAllViews();
        PillProfile p = PillProfile.parse(med.refProfile);
        if (p != null) {
            LinearLayout row = Ui.hbox(this);
            if (med.refPhoto != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap bm = BitmapFactory.decodeFile(med.refPhoto, o);
                if (bm != null) iv.setImageBitmap(bm);
                iv.setClipToOutline(true);
                iv.setBackground(Ui.round(0xFFE2E8F0, Ui.dp(this, 10)));
                row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 84)));
            }
            LinearLayout col = Ui.vbox(this);
            col.addView(Ui.text(this, "✓ Referensi terekam", 14, 0xFF047857, true));
            col.addView(Ui.caption(this, "Terdeteksi: " + PillProfile.COLORS[p.colorCategory()] + ", " + PillProfile.SHAPES[p.shapeCategory()]
                    + String.format(Locale.US, " (rasio panjang %.1f)", p.elong)));
            LinearLayout.LayoutParams cl = Ui.lpWeight(1);
            cl.leftMargin = Ui.dp(this, 12);
            row.addView(col, cl);
            refBox.addView(row, Ui.lpMW());
        }
        TextView rec = Ui.button(this, p == null ? "📷  Rekam foto referensi" : "Rekam ulang", p == null ? Ui.BTN_PRIMARY : Ui.BTN_SECONDARY);
        rec.setOnClickListener(v -> {
            Intent i = new Intent(this, VerifyActivity.class);
            i.putExtra(VerifyActivity.EXTRA_ENROLL, true);
            i.putExtra(VerifyActivity.EXTRA_NAME, name.getText().toString().trim().isEmpty() ? "Obat baru" : name.getText().toString().trim());
            startActivityForResult(i, REQ_ENROLL);
        });
        Ui.add(refBox, rec, p == null ? 0 : 10);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENROLL && resultCode == RESULT_OK && data != null) {
            med.refProfile = data.getStringExtra(VerifyActivity.RESULT_PROFILE);
            String photo = data.getStringExtra(VerifyActivity.RESULT_PHOTO);
            if (photo != null) med.refPhoto = photo;
            PillProfile p = PillProfile.parse(med.refProfile);
            if (p != null) {
                if (med.color < 0) {
                    med.color = p.colorCategory();
                    colorBox.setText(colorText());
                    icon.setColorIdx(med.color);
                }
                if (med.shape < 0) {
                    med.shape = p.shapeCategory();
                    shapeBox.setText(shapeText());
                }
            }
            renderRef();
        }
    }

    interface IntCb {
        void on(int i);
    }

    interface LongCb {
        void on(long t);
    }

    private void pick(String title, String[] items, IntCb cb) {
        new AlertDialog.Builder(this).setTitle(title).setItems(items, (d, i) -> cb.on(i)).show();
    }

    private void pickDate(long init, LongCb cb) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(init);
        new DatePickerDialog(this, (dp, y, m, d) -> {
            Calendar r = Calendar.getInstance();
            r.clear();
            r.set(y, m, d, 0, 0, 0);
            cb.on(r.getTimeInMillis());
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void save() {
        String n = name.getText().toString().trim();
        if (n.isEmpty()) {
            name.setError("Nama obat wajib diisi");
            name.requestFocus();
            return;
        }
        if (times.isEmpty()) {
            Toast.makeText(this, "Tambahkan minimal satu jam minum", Toast.LENGTH_SHORT).show();
            return;
        }
        if (med.endDate > 0 && med.startDate > 0 && Schedule.dayStart(med.endDate) < Schedule.dayStart(med.startDate)) {
            Toast.makeText(this, "Tanggal selesai sebelum tanggal mulai", Toast.LENGTH_SHORT).show();
            return;
        }
        med.name = n;
        med.dose = dose.getText().toString().trim();
        med.instructions = instr.getText().toString().trim();
        med.times = Med.formatTimes(times);
        med.requireWater = water.isChecked();
        if (active != null) med.active = active.isChecked();
        String st = stock.getText().toString().trim();
        try {
            med.stock = st.isEmpty() ? -1 : Math.max(0, Integer.parseInt(st));
        } catch (NumberFormatException e) {
            med.stock = -1;
        }
        Db.get(this).saveMed(med);
        Reminders.rescheduleAll(this);
        if (med.refProfile == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Tersimpan")
                    .setMessage("Pengingat " + med.name + " aktif pada " + med.times.replace(",", ", ")
                            + ".\n\nDisarankan merekam foto referensi agar verifikasi kamera dapat membedakan obat ini dari obat lain.")
                    .setPositiveButton("Rekam sekarang", (d, w) -> {
                        isNew = false;
                        Intent i = new Intent(this, VerifyActivity.class);
                        i.putExtra(VerifyActivity.EXTRA_ENROLL, true);
                        i.putExtra(VerifyActivity.EXTRA_NAME, med.name);
                        startActivityForResult(i, REQ_ENROLL);
                        getIntent().putExtra(EXTRA_ID, med.id);
                        pendingSaveAfterEnroll = true;
                    })
                    .setNegativeButton("Nanti", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            Toast.makeText(this, "Tersimpan. Pengingat aktif.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private boolean pendingSaveAfterEnroll;

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingSaveAfterEnroll && med.refProfile != null) {
            pendingSaveAfterEnroll = false;
            Db.get(this).saveMed(med);
            Toast.makeText(this, "Referensi visual tersimpan", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}

package com.mediverify.app;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.mediverify.app.data.DoseLog;
import com.mediverify.app.data.Med;
import com.mediverify.app.data.Schedule;
import com.mediverify.app.reminder.Reminders;
import com.mediverify.app.ui.Fmt;
import com.mediverify.app.ui.Ui;

import java.util.List;
import java.util.Map;

final class ProfileTab {
    private ProfileTab() {}

    static View build(final MainActivity a) {
        ScrollView[] h = new ScrollView[1];
        LinearLayout body = a.page(h, "Profil & pengaturan", null);

        LinearLayout pc = Ui.card(a);
        pc.addView(Ui.label(a, "Data pasien"));
        final EditText name = Ui.input(a, "Nama lengkap", a.prefs.patientName(), 0);
        Ui.add(pc, Ui.field(a, "Nama pasien", name), 10);
        LinearLayout r = Ui.hbox(a);
        final EditText age = Ui.input(a, "Tahun", a.prefs.patientAge(), InputType.TYPE_CLASS_NUMBER);
        r.addView(Ui.field(a, "Usia", age), Ui.lpWeight(1));
        final TextView gender = Ui.selectBox(a, a.prefs.patientGender().isEmpty() ? "Pilih  ▾" : a.prefs.patientGender() + "  ▾");
        final String[] g = {a.prefs.patientGender()};
        gender.setOnClickListener(v -> new AlertDialog.Builder(a).setItems(new String[]{"Laki-laki", "Perempuan"}, (d, i) -> {
            g[0] = i == 0 ? "Laki-laki" : "Perempuan";
            gender.setText(g[0] + "  ▾");
        }).show());
        LinearLayout.LayoutParams gl = Ui.lpWeight(1);
        gl.leftMargin = Ui.dp(a, 10);
        r.addView(Ui.field(a, "Jenis kelamin", gender), gl);
        Ui.add(pc, r, 10);
        final EditText rm = Ui.input(a, "Opsional", a.prefs.rmNumber(), InputType.TYPE_CLASS_TEXT);
        Ui.add(pc, Ui.field(a, "No. rekam medis", rm), 10);
        final EditText doc = Ui.input(a, "Opsional", a.prefs.doctor(), 0);
        Ui.add(pc, Ui.field(a, "Dokter penanggung jawab", doc), 10);
        Ui.add(body, pc, 16);

        LinearLayout cc = Ui.card(a);
        cc.addView(Ui.label(a, "Caregiver / keluarga"));
        Ui.add(cc, Ui.caption(a, "Dihubungi lewat SMS/WhatsApp saat dosis terlewat."), 4);
        final EditText cg = Ui.input(a, "Nama caregiver", a.prefs.caregiverName(), 0);
        Ui.add(cc, Ui.field(a, "Nama", cg), 10);
        final EditText phone = Ui.input(a, "08xxxxxxxxxx", a.prefs.caregiverPhone(), InputType.TYPE_CLASS_PHONE);
        Ui.add(cc, Ui.field(a, "No. HP", phone), 10);
        Ui.add(body, cc, 12);

        TextView save = Ui.button(a, "Simpan profil", Ui.BTN_PRIMARY);
        save.setOnClickListener(v -> {
            a.prefs.setProfile(name.getText().toString().trim(), age.getText().toString().trim(), g[0],
                    rm.getText().toString().trim(), doc.getText().toString().trim(), cg.getText().toString().trim(),
                    phone.getText().toString().trim());
            Toast.makeText(a, "Profil tersimpan", Toast.LENGTH_SHORT).show();
        });
        Ui.add(body, save, 12);

        View warn = a.permissionBanner(true);
        if (warn != null) Ui.add(body, warn, 18);

        LinearLayout sc = Ui.card(a);
        sc.addView(Ui.label(a, "Pengingat & verifikasi"));
        addPicker(a, sc, "Batas toleransi sebelum dianggap terlewat", "grace", a.prefs.graceMinutes(), new int[]{30, 60, 90, 120, 180});
        addPicker(a, sc, "Rentang \"tepat waktu\" (±)", "ontime", a.prefs.onTimeMinutes(), new int[]{15, 30, 45, 60});
        addPicker(a, sc, "Durasi tunda (snooze)", "snooze", a.prefs.snoozeMinutes(), new int[]{5, 10, 15, 30});
        addSwitch(a, sc, "Bunyi alarm", "sound", a.prefs.sound());
        addSwitch(a, sc, "Getar", "vibrate", a.prefs.vibrate());
        addSwitch(a, sc, "Izinkan konfirmasi manual (tanpa kamera)", "manual", a.prefs.allowManual());
        addSwitch(a, sc, "Tawarkan kirim pesan ke caregiver saat dosis terlewat", "notifycg", a.prefs.notifyCaregiver());
        Ui.add(body, sc, 18);

        TextView test = Ui.button(a, "🔔  Tes pengingat (1 menit lagi)", Ui.BTN_SECONDARY);
        test.setOnClickListener(v -> testAlarm(a));
        Ui.add(body, test, 14);

        LinearLayout about = Ui.card(a);
        about.addView(Ui.label(a, "Tentang MediVerify"));
        Ui.add(about, Ui.body(a, "Versi 2.0 • Semua data (jadwal, riwayat, foto bukti) disimpan hanya di perangkat ini dan tidak dikirim ke server."), 6);
        Ui.add(about, Ui.caption(a, "Verifikasi visual bersifat alat bantu kepatuhan, bukan pengganti pengawasan tenaga kesehatan. "
                + "Hasil berstatus \"Perlu ditinjau\" sebaiknya diperiksa caregiver melalui foto bukti."), 6);
        Ui.add(body, about, 14);
        return h[0];
    }

    private static void addSwitch(MainActivity a, LinearLayout parent, String label, final String key, boolean value) {
        Switch s = new Switch(a);
        s.setText(label);
        s.setTextColor(Ui.INK2);
        s.setTextSize(14);
        s.setChecked(value);
        s.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        s.setOnCheckedChangeListener((b, c) -> a.prefs.setBool(key, c));
        Ui.add(parent, s, 8);
    }

    private static void addPicker(final MainActivity a, LinearLayout parent, String label, final String key, int value, final int[] opts) {
        LinearLayout row = Ui.hbox(a);
        row.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        row.addView(Ui.text(a, label, 14, Ui.INK2, false), Ui.lpWeight(1));
        final TextView v = Ui.chip(a, value + " menit ▾", Ui.PRIMARY_LIGHT, Ui.PRIMARY_DARK);
        row.addView(v);
        row.setOnClickListener(x -> {
            String[] items = new String[opts.length];
            for (int i = 0; i < opts.length; i++) items[i] = opts[i] + " menit";
            new AlertDialog.Builder(a).setTitle(label).setItems(items, (d, i) -> {
                a.prefs.setInt(key, opts[i]);
                v.setText(opts[i] + " menit ▾");
            }).show();
        });
        Ui.add(parent, row, 4);
    }

    private static void testAlarm(MainActivity a) {
        long now = System.currentTimeMillis();
        long today = Schedule.dayStart(now);
        List<Med> meds = a.db.meds(false);
        if (meds.isEmpty()) {
            Toast.makeText(a, "Tambahkan obat terlebih dahulu", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, DoseLog> logs = a.db.resolutions(today, Schedule.addDays(today, 2));
        Schedule.Slot pick = null;
        for (int d = 0; d <= 1 && pick == null; d++) {
            for (Schedule.Slot s : Schedule.slotsForDay(meds, Schedule.addDays(today, d), logs)) {
                if (s.log == null && s.time + a.prefs.graceMinutes() * 60000L > now) {
                    pick = s;
                    break;
                }
            }
        }
        if (pick == null) {
            Toast.makeText(a, "Tidak ada dosis menunggu untuk dites", Toast.LENGTH_SHORT).show();
            return;
        }
        Reminders.testReminder(a, pick.med.id, pick.time, pick.timeIndex);
        Toast.makeText(a, "Pengingat " + pick.med.name + " (" + Fmt.time(pick.time) + ") akan berbunyi ±1 menit lagi. Kunci layar untuk mencoba alarm layar penuh.", Toast.LENGTH_LONG).show();
    }
}

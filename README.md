# MediVerify v2.0 — kode sumber

Aplikasi Android (Java murni, tanpa library pihak ketiga) — minSdk 26 (Android 8.0), targetSdk 34.

Struktur:
- `src/com/mediverify/app/vision/` — inti verifikasi visual (Java murni, bisa diuji di PC):
  - `FrameAnalyzer` — segmentasi kulit adaptif (YCbCr), deteksi pil sebagai "lubang" non-kulit di telapak, deteksi pola layar (moire), statistik area mulut.
  - `PillProfile` — ciri obat yang dinormalisasi terhadap warna telapak (white-balance), bentuk (elongasi), ukuran relatif; skor kemiripan.
  - `VerificationSession` — mesin status 5 langkah berbasis waktu: Obat → Wajah (gerak acak) → Ke mulut → Minum → Cek mulut.
- `test/VisionTest.java` — uji otomatis dengan citra sintetis (pencahayaan netral/hangat/dingin/redup, obat salah, dosis salah, enrolmen, layar).
- `data/` — SQLite (obat & log dosis), logika jadwal & statistik kepatuhan.
- `reminder/` — AlarmManager (exact/alarm clock), notifikasi, alarm layar penuh, snooze, deteksi dosis terlewat, BOOT_COMPLETED.
- `VerifyActivity` — kamera (android.hardware.Camera + TextureView), FaceDetector Android, foto bukti ber-watermark.
- `ReportExporter` — PDF, CSV, HL7 FHIR R4 (Bundle Patient + MedicationStatement).

Menjalankan uji visi di PC:
    javac -d out src/com/mediverify/app/vision/*.java test/VisionTest.java && java -cp out VisionTest

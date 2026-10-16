$ErrorActionPreference = "Stop"

try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $doc = $word.Documents.Add()

    # Set margins
    $doc.PageSetup.LeftMargin = 85
    $doc.PageSetup.RightMargin = 85
    $doc.PageSetup.TopMargin = 85
    $doc.PageSetup.BottomMargin = 85

    $selection = $word.Selection
    $selection.Font.Name = "Times New Roman"
    $selection.Font.Size = 12

    # Title
    $selection.ParagraphFormat.Alignment = 1 # Center
    $selection.Font.Bold = $true
    $selection.Font.Size = 14
    $selection.TypeText("Pengembangan Aplikasi Pengingat Obat Berbasis Verifikasi Visual Heuristik dan Pendeteksi Liveness Tanpa Library Eksternal")
    $selection.TypeParagraph()

    # Author
    $selection.Font.Bold = $false
    $selection.Font.Size = 12
    $selection.TypeText("Aufar Yasykur Sufarnap")
    $selection.TypeParagraph()

    # Affiliation
    $selection.Font.Size = 10
    $selection.TypeText("a SMAIT ASY-SYUKRIYYAH, Tangerang, Banten, Indonesia")
    $selection.TypeParagraph()
    $selection.TypeText("1 Aufarys2@gmail.com")
    $selection.TypeParagraph()
    $selection.TypeText("* corresponding author")
    $selection.TypeParagraph()
    $selection.TypeParagraph()

    # Table
    $selection.Font.Size = 11
    $range = $selection.Range
    $table = $doc.Tables.Add($range, 2, 2)
    $table.Borders.Enable = 1
    
    # Header Row
    $table.Cell(1,1).Range.Text = "ARTICLE INFO"
    $table.Cell(1,1).Range.Font.Bold = $true
    $table.Cell(1,1).Shading.BackgroundPatternColor = 14737632 # Gray
    
    $table.Cell(1,2).Range.Text = "ABSTRACT"
    $table.Cell(1,2).Range.Font.Bold = $true
    $table.Cell(1,2).Shading.BackgroundPatternColor = 14737632

    # Col 1
    $table.Cell(2,1).Range.Text = "Article history`nReceived`nRevised`nAccepted`n`nKeywords`nKepatuhan pengobatan`nPengingat obat`nVerifikasi visual`nHeuristik matematis`nAplikasi Android`n`nKeywords`nMedication adherence`nMedication reminder`nVisual verification`nMathematical heuristic`nAndroid application"
    
    # Col 2
    $table.Cell(2,2).Range.Text = "Ketidakpatuhan minum obat masih menjadi tantangan utama dalam pengobatan penyakit kronis, dengan masalah seperti dosis terlewat atau konsumsi yang tidak dapat dikonfirmasi. Penelitian ini mengembangkan MediVerify v2.0, aplikasi pengingat obat berbasis Android dengan sistem verifikasi visual murni (tanpa pustaka pihak ketiga) menggunakan metode Research and Development dengan model prototipe. Sistem beroperasi menggunakan segmentasi kulit adaptif pada ruang warna YCbCr dan ekstraksi fitur obat berdasarkan heuristik matematis yang dinormalisasi terhadap telapak tangan. Proses verifikasi dipandu oleh mesin status 5 langkah (Obat -> Liveness Wajah -> Ke Mulut -> Minum -> Cek Mulut Kosong) serta dilengkapi deteksi pola layar (moire) untuk anti-kecurangan. Evaluasi dilakukan secara otomatis menggunakan citra sintetis terkalibrasi untuk berbagai kondisi pencahayaan (netral, hangat, dingin, redup), dosis salah, obat salah, dan simulasi layar digital. Hasil pengujian menunjukkan algoritma berhasil mempertahankan akurasi pencocokan obat (skor >0,75) di berbagai kondisi cahaya berkat normalisasi white-balance relatif, serta secara konsisten menolak layar digital dan sisa obat di dalam mulut. Pendekatan algoritmik ini menghasilkan ukuran aplikasi yang sangat ringan dan efisiensi memori yang tinggi sebagai alternatif dari model deep learning konvensional, meskipun pengujian klinis di dunia nyata masih diperlukan pada iterasi selanjutnya.`n`nMedication non-adherence remains a major challenge in chronic disease management, with issues such as missed doses and unconfirmed intake. This study developed MediVerify v2.0, an Android-based medication reminder featuring a pure visual verification system (without third-party libraries), using a Research and Development approach with a prototyping model. The system operates on adaptive skin segmentation in the YCbCr color space and mathematical heuristic feature extraction of pills normalized against the palm. The verification process is guided by a 5-step state machine (Pill -> Face Liveness -> To Mouth -> Drink -> Empty Mouth Check) and includes screen pattern (moire) detection for anti-spoofing. Evaluation was conducted automatically using calibrated synthetic images across various lighting conditions (neutral, warm, cool, dim), wrong doses, wrong pills, and digital screen simulations. Test results demonstrated the algorithm's ability to maintain high matching accuracy (score >0.75) across lighting variations due to relative white-balance normalization, and consistently reject digital screens and residual pills in the mouth. This algorithmic approach yields an extremely lightweight application size and high memory efficiency as an alternative to conventional deep learning models, though real-world clinical testing is required in future iterations."
    $table.Cell(2,2).Range.ParagraphFormat.Alignment = 3 # Justify
    
    # Move selection to end of doc
    $selection.EndKey(6) | Out-Null
    $selection.TypeParagraph()
    $selection.TypeParagraph()

    function Add-Section($title, $body) {
        $selection.ParagraphFormat.Alignment = 0 # Left
        $selection.Font.Bold = $true
        $selection.Font.Size = 12
        $selection.TypeText($title)
        $selection.TypeParagraph()
        
        $selection.Font.Bold = $false
        $selection.ParagraphFormat.Alignment = 3 # Justify
        
        foreach ($p in $body) {
            $selection.TypeText($p)
            $selection.TypeParagraph()
        }
        $selection.TypeParagraph()
    }

    $p1 = @("Obat baru bekerja optimal jika diminum sesuai resep, terutama pada penyakit kronis yang pengobatannya berlangsung lama. Namun, ketidakpatuhan pasien masih menjadi isu utama yang mengakibatkan efek terapi tidak tercapai dan meningkatkan risiko komplikasi medis. Verifikasi visual berbasis smartphone menjadi salah satu metode menjanjikan untuk mengatasi celah konfirmasi manual.",
            "Sebagian besar solusi modern terlalu bergantung pada arsitektur deep learning dan pustaka pihak ketiga berukuran besar (seperti OpenCV atau TensorFlow) yang memerlukan spesifikasi ponsel tinggi dan memori besar. Hal ini membatasi aksesibilitas bagi pasien di negara berkembang atau lansia dengan perangkat terbatas.",
            "Penelitian ini memecahkan masalah tersebut dengan mengembangkan MediVerify v2.0, aplikasi pengingat obat Android yang mengandalkan algoritma pengolahan citra heuristik murni tanpa pustaka eksternal. Aplikasi ini merancang verifikasi berlapis melalui mesin status (state machine) untuk memvalidasi proses konsumsi obat secara berkesinambungan dengan kinerja yang sangat ringan.")
    Add-Section "Pendahuluan" $p1

    $p2 = @("Penelitian ini menggunakan metode penelitian dan pengembangan (Research and Development/R&D) dengan pendekatan model prototipe. Aplikasi dikembangkan dalam bahasa Java murni, dirancang untuk kompatibilitas ke belakang hingga Android 8.0, dan memanfaatkan API bawaan OS (seperti android.hardware.Camera, AlarmManager, dan FaceDetector).",
            "Verifikasi visual tidak memanfaatkan neural networks, melainkan menggunakan pengenalan piksel langsung. Citra dikonversi ke ruang warna YCbCr untuk membedakan area kulit secara adaptif terhadap cahaya. Obat dideteksi sebagai anomali warna non-kulit di tengah telapak tangan.",
            "Aplikasi menggunakan mesin status 5 tahap: (1) Deteksi Obat, (2) Liveness wajah dengan instruksi gerakan acak, (3) Deteksi obat mendekati mulut, (4) Proses menelan dengan mulut tertutup, dan (5) Mulut terbuka kosong. Aplikasi juga mengimplementasikan analisis frekuensi untuk mendeteksi pola moire sebagai anti-kecurangan (mencegah manipulasi kamera menggunakan foto/layar digital).",
            "Uji keandalan algoritma (unit test) dilakukan secara mandiri menggunakan modul VisionTest.java. Sistem menyimulasikan ratusan frame pencitraan sintetis (synthetic palms and pills) dalam kondisi pencahayaan terkalibrasi (netral, hangat, dingin, dan redup). Uji mencakup skenario dosis salah, obat salah, liveness layar, dan kondisi obat masih ada di dalam mulut.")
    Add-Section "Metode" $p2

    $p3 = @("Berdasarkan pengujian ekstensif, algoritma menunjukkan ketahanan tinggi (robustness) berkat metode normalisasi fitur relatif (warna dan ukuran pil dibandingkan telapak tangan). Pada uji pencahayaan silang, skor pencocokan pil yang sama (misal pil putih di bawah cahaya redup) tetap di atas ambang batas kritis (>0.75), sementara pil berbeda berhasil diblokir dengan skor kemiripan rendah (<0.5).",
            "Sistem anti-kecurangan juga berhasil membuktikan efektivitasnya. Algoritma deteksi layar menghasilkan skor moire tinggi (>0.8) saat mendeteksi garis frekuensi layar digital, mencegah sistem dari manipulasi menggunakan gambar layar ponsel. Mesin status 5 langkah juga berhasil diuji secara mulus, mendeteksi pelanggaran secara presisi, misalnya ketika mulut terbuka namun masih ada sisa pixel dengan profil warna obat.",
            "Pendekatan heuristik manual ini menghadirkan efisiensi yang ekstrem. Tanpa integrasi machine learning eksternal, aplikasi hanya memakan ruang instalasi yang sangat minim, menghemat memori runtime, serta mengeksekusi analisis citra pada ponsel berspesifikasi rendah dengan lag yang nyaris nol.")
    Add-Section "Hasil dan Pembahasan" $p3

    $p4 = @("Penelitian ini telah berhasil mengembangkan MediVerify v2.0, aplikasi pengingat obat dengan arsitektur verifikasi visual murni tanpa library eksternal. Pendekatan matematis, segmentasi YCbCr adaptif, dan mesin status 5 tahap sukses memvalidasi aktivitas penelanan dengan akurasi dan kinerja komputasi luar biasa ringan. Aplikasi secara andal mendeteksi kecurangan layar dan ketidaksesuaian dosis. Meskipun sangat efisien secara teknis, aplikasi pada iterasi selanjutnya memerlukan pengujian klinis secara langsung menggunakan variasi kamera ponsel dan kondisi lingkungan pasien nyata untuk memastikan efektivitas akhirnya.")
    Add-Section "Kesimpulan" $p4

    $p5 = @("Penulis mengucapkan terima kasih kepada pembimbing dan seluruh pihak di SMAIT Asy-Syukriyyah yang telah mendukung penelitian ini.")
    Add-Section "Acknowledgment" $p5

    $p6 = @("Author contribution. Aufar Yasykur Sufarnap merancang arsitektur perangkat lunak, mengembangkan prototipe sistem, menyimulasikan pengujian unit, dan menyusun manuskrip.",
            "Funding statement. Penelitian ini tidak menerima pendanaan dari pihak mana pun.",
            "Conflict of interest. Penulis menyatakan tidak ada konflik kepentingan.")
    Add-Section "Declarations" $p6

    $savePath = "d:\Pengembangan AI\MediVerify-v2.0-source\Artikel_MediVerify_Asli.docx"
    $doc.SaveAs([ref]$savePath, [ref]16)
    $doc.Close()
    $word.Quit()
    echo "SUCCESS: $savePath"

} catch {
    echo "ERROR: $_"
    if ($word) {
        $word.Quit()
    }
}

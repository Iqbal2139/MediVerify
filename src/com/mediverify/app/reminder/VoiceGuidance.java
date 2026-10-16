package com.mediverify.app.reminder;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * Utilitas Text-To-Speech (TTS) untuk memandu pasien (khususnya lansia) 
 * selama proses 5 langkah verifikasi visual tanpa harus melihat layar terus-menerus.
 */
public class VoiceGuidance implements TextToSpeech.OnInitListener {
    
    private TextToSpeech tts;
    private boolean isReady = false;

    public VoiceGuidance(Context context) {
        tts = new TextToSpeech(context, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Gunakan bahasa Indonesia
            int result = tts.setLanguage(new Locale("id", "ID"));
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isReady = true;
            }
        }
    }

    /** Membacakan instruksi tanpa menghentikan suara yang sedang berjalan. */
    public void speak(String text) {
        if (isReady && text != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    /** Instruksi spesifik untuk state machine verifikasi */
    public void instructPill() {
        speak("Silakan tunjukkan obat Anda di tengah telapak tangan.");
    }

    public void instructLiveness(int direction) {
        if (direction < 0) {
            speak("Bagus. Sekarang gerakkan wajah Anda sedikit ke kiri.");
        } else {
            speak("Bagus. Sekarang gerakkan wajah Anda sedikit ke kanan.");
        }
    }

    public void instructToMouth() {
        speak("Mendekati mulut. Silakan masukkan obat.");
    }

    public void instructDrink() {
        speak("Silakan minum air untuk menelan obat.");
    }

    public void instructCheckMouth() {
        speak("Mohon buka mulut Anda lebar-lebar untuk memastikan obat sudah tertelan.");
    }

    public void instructSuccess() {
        speak("Terima kasih. Obat berhasil diverifikasi.");
    }
    
    public void instructFailed() {
        speak("Verifikasi gagal. Obat tidak sesuai atau tidak terdeteksi. Silakan coba lagi.");
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}

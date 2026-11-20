package com.chitour.familycast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * قراءة الترجمة بصوت (وقراءة ملخصات المشاهد المحذوفة).
 * mode: 0 متوقفة • 1 تقرأ مع خفض صوت الفيلم أثناء القراءة • 2 تقرأ بدل صوت الفيلم (كتم دائم)
 */
class SubReader(ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    @Volatile var speaking = false
        private set
    var mode = 0
        set(v) { field = v; if (v == 0) stop() }
    private var last = ""

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            ready = st == TextToSpeech.SUCCESS
            tts?.setSpeechRate(1.1f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { speaking = true } }
                override fun onDone(id: String?) { main.post { speaking = false } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { main.post { speaking = false } }
            })
        }
    }

    private fun clean(t: String) = t.replace(Regex("<[^>]+>"), "").replace(Regex("""\{[^}]*\}"""), "")
        .replace(Regex("""^[-–\s]+"""), "").replace('\n', ' ').trim()

    fun speak(text: String, force: Boolean = false) {
        if ((mode == 0 && !force) || !ready) return
        val t = clean(text)
        if (t.isBlank() || (t == last && !force)) return
        last = t
        try {
            tts?.language = if (Util.isArabic(t)) Locale("ar") else Locale.ENGLISH
            tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "c" + System.nanoTime())
        } catch (e: Exception) { }
    }

    fun arabicOk(): Boolean = try { (tts?.isLanguageAvailable(Locale("ar")) ?: -1) >= TextToSpeech.LANG_AVAILABLE } catch (e: Exception) { false }

    /** مستوى صوت الفيلم المناسب الآن */
    fun filmVolume(): Float = when { mode == 2 -> 0f; mode == 1 && speaking -> 0.25f; else -> 1f }

    fun stop() { try { tts?.stop() } catch (e: Exception) { }; speaking = false; last = "" }
    fun shutdown() { try { tts?.shutdown() } catch (e: Exception) { } }

    companion object {
        val names = listOf("متوقفة", "مع خفض صوت الفيلم", "بدل صوت الفيلم")
    }
}

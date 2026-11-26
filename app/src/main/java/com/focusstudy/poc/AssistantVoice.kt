package com.focusstudy.poc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * صوت المساعد: إدخال صوتي ونطق عبر خدمتي أندرويد المدمجتين المجانيتين فقط
 * ([SpeechRecognizer] و[TextToSpeech]) — بلا API مدفوع.
 *
 * ملاحظة: التعرف الصوتي الافتراضي على معظم الأجهزة خدمة سحابية (Google)،
 * فقد يُرسَل الصوت إلى خدمة التعرف لتحويله إلى نص — التحويل المحلي غير مضمون.
 *
 * النطق بالعربية المصرية (ar-EG) مع رجوع تلقائي إلى العربية العامة (ar) إن لم
 * تتوفر أصوات مصرية على الجهاز. التعرف الصوتي يحتاج إذن RECORD_AUDIO، ويُطلب
 * عند أول ضغطة على الميكروفون فقط، وليس عند فتح الشاشة.
 */
class AssistantVoice(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val egyptian = engine.setLanguage(Locale("ar", "EG"))
                if (egyptian == TextToSpeech.LANG_MISSING_DATA ||
                    egyptian == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine.setLanguage(Locale("ar"))
                }
                ttsReady = true
                pendingText?.let { text ->
                    pendingText = null
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-utterance")
                }
            }
        }
    }

    /** ينطق الرد؛ إن لم يكن المحرك جاهزًا بعد، يُنطق فور جهوزيته. */
    fun speak(text: String) {
        if (text.isBlank()) return
        ensureTts()
        val engine = tts ?: return
        if (ttsReady) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-utterance")
        } else {
            pendingText = text
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingText = null
    }

    companion object {
        fun isRecognitionAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }
}

/** مستمع التعرف الصوتي: يحوّل الكلام إلى نص عبر محرك أندرويد المدمج. */
class AssistantSpeechRecognizer(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onPartial: (String) -> Unit = {},
    private val onError: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit
) {
    private val recognizer: SpeechRecognizer? =
        runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onListeningChanged(true)
        override fun onBeginningOfSpeech() = onListeningChanged(true)
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = onListeningChanged(false)
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let { onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            onListeningChanged(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) onResult(text) else onError("ما سمعتش حاجة واضحة، جرّب تاني.")
        }

        override fun onError(error: Int) {
            onListeningChanged(false)
            onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "ما سمعتش حاجة واضحة، جرّب تاني."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "انتهى وقت الاستماع من غير كلام."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "الميكروفون محتاج إذن من إعدادات التطبيق."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "التعرف الصوتي محتاج إنترنت لحظيًا — جرّب الكتابة بدلًا منه."
                    else -> "تعذّر الاستماع، جرّب تاني أو اكتب رسالتك."
                }
            )
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun start() {
        val engine = recognizer ?: run {
            onError("التعرف الصوتي مش متاح على الجهاز ده.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching {
            engine.setRecognitionListener(listener)
            engine.startListening(intent)
        }.onFailure { onError("تعذّر تشغيل الميكروفون، جرّب تاني.") }
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
    }
}

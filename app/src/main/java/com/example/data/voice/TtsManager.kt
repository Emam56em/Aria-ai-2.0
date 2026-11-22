package com.example.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.core.localization.AppLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false

    private val _speakingMessageId = MutableStateFlow<Long?>(null)
    val speakingMessageId: StateFlow<Long?> = _speakingMessageId.asStateFlow()

    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val utteranceCounter = AtomicLong(1L)

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        _speakingMessageId.value = null
                        utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _speakingMessageId.value = null
                        utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _speakingMessageId.value = null
                        utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        _speakingMessageId.value = null
                        utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                    }
                })
            } else {
                Log.w(TAG, "TextToSpeech initialization failed with status $status")
            }
        }
    }

    fun speak(messageId: Long, text: String, preferredLanguage: AppLanguage) {
        val engine = tts ?: return
        if (!isInitialized || text.isBlank()) return

        if (_speakingMessageId.value == messageId) {
            stop()
            return
        }

        applyLanguageForText(engine, text, preferredLanguage)
        _speakingMessageId.value = messageId
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, messageId.toString())
    }

    /**
     * Speaks [text] and suspends until playback finishes (or [timeoutMs] elapses),
     * preventing microphone capture from picking up Aria's own voice response.
     */
    suspend fun speakAndAwait(
        text: String,
        preferredLanguage: AppLanguage,
        messageId: Long = -utteranceCounter.getAndIncrement(),
        timeoutMs: Long = 12_000L
    ) {
        val engine = tts ?: return
        if (!isInitialized || text.isBlank()) return

        val utteranceId = "aria_voice_${utteranceCounter.getAndIncrement()}"
        val deferred = CompletableDeferred<Unit>()
        pendingUtterances[utteranceId] = deferred

        applyLanguageForText(engine, text, preferredLanguage)
        _speakingMessageId.value = messageId
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            pendingUtterances.remove(utteranceId)
            _speakingMessageId.value = null
            return
        }

        try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingUtterances.remove(utteranceId)
            if (_speakingMessageId.value == messageId) {
                _speakingMessageId.value = null
            }
        }
    }

    private fun applyLanguageForText(
        engine: TextToSpeech,
        text: String,
        preferredLanguage: AppLanguage
    ) {
        val hasBengaliScript = text.any { it in '\u0980'..'\u09FF' }
        val locale = if (hasBengaliScript || preferredLanguage == AppLanguage.BENGALI) {
            Locale.forLanguageTag("bn-BD")
        } else {
            Locale.US
        }

        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
    }

    fun stop() {
        tts?.stop()
        _speakingMessageId.value = null
        pendingUtterances.values.forEach { it.complete(Unit) }
        pendingUtterances.clear()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}

package com.example.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.core.localization.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VoiceRecognitionEvent {
    data object Idle : VoiceRecognitionEvent
    data object Listening : VoiceRecognitionEvent
    data class PartialResult(val text: String, val rmsDb: Float) : VoiceRecognitionEvent
    data class FinalResult(val text: String) : VoiceRecognitionEvent
    data class Error(val errorCode: Int, val isNoMatch: Boolean, val isUnavailable: Boolean) : VoiceRecognitionEvent
}

class VoiceInputManager(private val context: Context) {

    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null

    private val _events = MutableStateFlow<VoiceRecognitionEvent>(VoiceRecognitionEvent.Idle)
    val events: StateFlow<VoiceRecognitionEvent> = _events.asStateFlow()

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening(language: AppLanguage) {
        if (!isRecognitionAvailable()) {
            _events.value = VoiceRecognitionEvent.Error(
                errorCode = -1,
                isNoMatch = false,
                isUnavailable = true
            )
            return
        }

        cancelAndDestroy()

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _events.value = VoiceRecognitionEvent.Listening
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    val current = _events.value
                    val text = (current as? VoiceRecognitionEvent.PartialResult)?.text.orEmpty()
                    if (current is VoiceRecognitionEvent.Listening || current is VoiceRecognitionEvent.PartialResult) {
                        _events.value = VoiceRecognitionEvent.PartialResult(text = text, rmsDb = rmsdB)
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error code=$error")
                    val currentPartial = (_events.value as? VoiceRecognitionEvent.PartialResult)?.text.orEmpty().trim()
                    val noMatch = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (noMatch && currentPartial.isNotEmpty()) {
                        _events.value = VoiceRecognitionEvent.FinalResult(currentPartial)
                    } else {
                        _events.value = VoiceRecognitionEvent.Error(
                            errorCode = error,
                            isNoMatch = noMatch,
                            isUnavailable = false
                        )
                    }
                    destroyRecognizerOnly()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull().orEmpty().trim()
                    val currentPartial = (_events.value as? VoiceRecognitionEvent.PartialResult)?.text.orEmpty().trim()
                    val resolved = best.ifEmpty { currentPartial }
                    if (resolved.isNotEmpty()) {
                        _events.value = VoiceRecognitionEvent.FinalResult(resolved)
                    } else {
                        _events.value = VoiceRecognitionEvent.Error(
                            errorCode = SpeechRecognizer.ERROR_NO_MATCH,
                            isNoMatch = true,
                            isUnavailable = false
                        )
                    }
                    destroyRecognizerOnly()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull().orEmpty().trim()
                    if (partial.isNotEmpty()) {
                        val currentRms = (_events.value as? VoiceRecognitionEvent.PartialResult)?.rmsDb ?: 2f
                        _events.value = VoiceRecognitionEvent.PartialResult(
                            text = partial,
                            rmsDb = currentRms
                        )
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.speechLocaleTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.speechLocaleTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            _events.value = VoiceRecognitionEvent.Listening
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognizer", e)
            _events.value = VoiceRecognitionEvent.Error(
                errorCode = -2,
                isNoMatch = false,
                isUnavailable = true
            )
        }
    }

    /**
     * Signals the recognizer that speech input finished so Android emits final onResults().
     * Does NOT immediately destroy the recognizer binder; teardown occurs after callbacks fire
     * or via [cancelAndDestroy].
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping speech recognizer", e)
        }
    }

    fun cancelAndDestroy() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling speech recognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    private fun destroyRecognizerOnly() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speech recognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    fun resetState() {
        cancelAndDestroy()
        _events.value = VoiceRecognitionEvent.Idle
    }

    companion object {
        private const val TAG = "VoiceInputManager"
    }
}

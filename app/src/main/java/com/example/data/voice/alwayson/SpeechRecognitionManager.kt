package com.example.data.voice.alwayson

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.core.localization.AppLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SpeechTurnResult {
    data class Success(
        val bestTranscript: String,
        val alternatives: List<String> = listOf(bestTranscript)
    ) : SpeechTurnResult

    data object NoMatch : SpeechTurnResult
    data object PermissionDenied : SpeechTurnResult
    data object MicrophoneBusy : SpeechTurnResult
    data object Unavailable : SpeechTurnResult
    data class Error(val errorCode: Int, val message: String) : SpeechTurnResult
}

/**
 * Bounded single-turn speech recognition manager.
 * - Never keeps Android [SpeechRecognizer] open as an unlimited always-on listener.
 * - Runs short, timeout-bounded sessions for (a) offline-preferred wake-phrase verification or
 *   (b) command transcription in Bengali (bn-BD) and English (en-US), then immediately destroys
 *   the recognizer so the microphone is safely released.
 */
class SpeechRecognitionManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRecognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    suspend fun recognizeSingleTurn(
        language: AppLanguage,
        preferOffline: Boolean,
        timeoutMs: Long = 7_500L
    ): SpeechTurnResult {
        if (!isAvailable()) {
            return SpeechTurnResult.Unavailable
        }

        val deferred = CompletableDeferred<SpeechTurnResult>()

        withContext(Dispatchers.Main.immediate) {
            destroyActiveOnMain()
            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                activeRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onResults(results: Bundle?) {
                        val matches = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                        destroyActiveOnMain()
                        if (matches.isNotEmpty()) {
                            deferred.complete(
                                SpeechTurnResult.Success(
                                    bestTranscript = matches.first(),
                                    alternatives = matches
                                )
                            )
                        } else {
                            deferred.complete(SpeechTurnResult.NoMatch)
                        }
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "SpeechRecognizer turn error code=$error")
                        destroyActiveOnMain()
                        val result = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechTurnResult.NoMatch
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechTurnResult.PermissionDenied
                            SpeechRecognizer.ERROR_AUDIO,
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechTurnResult.MicrophoneBusy
                            else -> SpeechTurnResult.Error(error, "Speech recognition error ($error)")
                        }
                        deferred.complete(result)
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.speechLocaleTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.speechLocaleTag)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    if (preferOffline) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }
                recognizer.startListening(intent)
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing microphone permission for SpeechRecognizer", e)
                destroyActiveOnMain()
                deferred.complete(SpeechTurnResult.PermissionDenied)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start bounded SpeechRecognizer turn", e)
                destroyActiveOnMain()
                deferred.complete(SpeechTurnResult.Unavailable)
            }
        }

        val completed = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }

        if (completed == null) {
            cancelSession()
            return SpeechTurnResult.NoMatch
        }
        return completed
    }

    fun cancelSession() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyActiveOnMain()
        } else {
            mainHandler.post { destroyActiveOnMain() }
        }
    }

    private fun destroyActiveOnMain() {
        try {
            activeRecognizer?.stopListening()
            activeRecognizer?.cancel()
            activeRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error tearing down SpeechRecognizer", e)
        } finally {
            activeRecognizer = null
        }
    }

    companion object {
        private const val TAG = "SpeechRecognitionMgr"
    }
}

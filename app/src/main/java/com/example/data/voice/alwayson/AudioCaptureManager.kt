package com.example.data.voice.alwayson

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

sealed interface AudioCaptureOutcome {
    data object StoppedNormally : AudioCaptureOutcome
    data object PermissionDenied : AudioCaptureOutcome
    data class MicrophoneInterrupted(val reason: String) : AudioCaptureOutcome
    data class Error(val reason: String) : AudioCaptureOutcome
}

/**
 * Manages safe, zero-persistence local microphone PCM capture via [AudioRecord].
 * - Never records or writes audio files to disk unless explicitly enabled by user (disabled by default).
 * - Safely acquires, reads, and releases hardware microphone resources.
 */
class AudioCaptureManager(
    context: Context,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val frameSizeSamples: Int = DEFAULT_FRAME_SAMPLES
) {
    private val appContext = context.applicationContext

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isCapturingFlag: Boolean = false

    // Privacy guarantee: never persist raw microphone audio on disk unless explicitly opted in.
    @Volatile
    var saveAudioEnabled: Boolean = false
        private set

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isCapturing(): Boolean = isCapturingFlag

    @SuppressLint("MissingPermission")
    suspend fun captureLoop(
        onFrame: suspend (ShortArray, Int) -> Boolean
    ): AudioCaptureOutcome = withContext(Dispatchers.IO) {
        if (!hasMicrophonePermission()) {
            return@withContext AudioCaptureOutcome.PermissionDenied
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            return@withContext AudioCaptureOutcome.Error("AudioRecord buffer size unavailable ($minBufferBytes).")
        }

        val targetBufferBytes = max(minBufferBytes * 2, frameSizeSamples * 2 * 4)
        var recorder: AudioRecord? = null

        try {
            recorder = createAudioRecord(targetBufferBytes)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                safeReleaseRecorder(recorder)
                return@withContext AudioCaptureOutcome.MicrophoneInterrupted("AudioRecord failed to initialize.")
            }

            audioRecord = recorder
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                safeReleaseRecorder(recorder)
                audioRecord = null
                return@withContext AudioCaptureOutcome.MicrophoneInterrupted("Microphone is busy or unavailable.")
            }

            isCapturingFlag = true
            val buffer = ShortArray(frameSizeSamples)
            var consecutiveZeroReads = 0

            while (isActive && isCapturingFlag) {
                val read = recorder.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        consecutiveZeroReads = 0
                        val shouldContinue = onFrame(buffer, read)
                        if (!shouldContinue) {
                            return@withContext AudioCaptureOutcome.StoppedNormally
                        }
                    }
                    read == 0 -> {
                        consecutiveZeroReads++
                        if (consecutiveZeroReads >= MAX_ZERO_READS) {
                            return@withContext AudioCaptureOutcome.MicrophoneInterrupted(
                                "Microphone input yielded empty frames (background/screen-off restriction or busy mic)."
                            )
                        }
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        return@withContext AudioCaptureOutcome.MicrophoneInterrupted(
                            "Microphone stream interrupted (code=$read)."
                        )
                    }
                    else -> {
                        return@withContext AudioCaptureOutcome.Error("AudioRecord read error ($read).")
                    }
                }
            }
            AudioCaptureOutcome.StoppedNormally
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            Log.w(TAG, "Microphone SecurityException during capture", se)
            AudioCaptureOutcome.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "AudioCaptureManager failed", e)
            AudioCaptureOutcome.MicrophoneInterrupted(e.localizedMessage ?: "Microphone interrupted.")
        } finally {
            isCapturingFlag = false
            safeReleaseRecorder(recorder ?: audioRecord)
            audioRecord = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSizeBytes: Int): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes
        )
    }

    fun releaseMicrophone() {
        isCapturingFlag = false
        safeReleaseRecorder(audioRecord)
        audioRecord = null
    }

    private fun safeReleaseRecorder(recorder: AudioRecord?) {
        if (recorder == null) return
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        try {
            recorder.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_FRAME_SAMPLES = 512 // ~32ms frames at 16 kHz
        private const val MAX_ZERO_READS = 25
    }
}

package com.example.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Full-duplex audio I/O gateway for Gemini Live bidirectional PCM streaming:
 * - Captures 16 kHz mono 16-bit PCM with AcousticEchoCanceler & NoiseSuppressor when available.
 * - Plays 24 kHz mono 16-bit PCM responses through a buffered AudioTrack jitter queue.
 * - Exposes live input/output RMS levels for reactive listening/speaking waveforms.
 * - Supports instant playback flush on user barge-in interruptions.
 */
interface LiveAudioGateway {
    val inputLevel: StateFlow<Float>
    val outputLevel: StateFlow<Float>
    val isEchoCancelerActive: StateFlow<Boolean>

    fun startAudio(
        inputSampleRateHz: Int = 16_000,
        outputSampleRateHz: Int = 24_000,
        onInputChunk: suspend (ByteArray) -> Unit,
        onAudioError: (String) -> Unit
    ): Boolean

    suspend fun enqueueOutputAudio(pcmBytes: ByteArray, sampleRateHz: Int = 24_000)
    fun interruptOutputPlayback()
    fun setMuted(muted: Boolean)
    fun stopAudio()
}

class AndroidLiveAudioGateway(
    context: Context
) : LiveAudioGateway {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val _inputLevel = MutableStateFlow(0f)
    override val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)
    override val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

    private val _isEchoCancelerActive = MutableStateFlow(false)
    override val isEchoCancelerActive: StateFlow<Boolean> = _isEchoCancelerActive.asStateFlow()

    private var ioScope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    @Volatile
    private var isMutedFlag = false

    @Volatile
    private var active = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var audioModeChanged = false

    private var currentOutputSampleRateHz: Int = 24_000
    private var outputQueue: Channel<Pair<ByteArray, Int>> = Channel(capacity = 512)

    @SuppressLint("MissingPermission")
    override fun startAudio(
        inputSampleRateHz: Int,
        outputSampleRateHz: Int,
        onInputChunk: suspend (ByteArray) -> Unit,
        onAudioError: (String) -> Unit
    ): Boolean {
        stopAudio()

        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onAudioError("Microphone permission (RECORD_AUDIO) is required.")
            return false
        }

        currentOutputSampleRateHz = outputSampleRateHz
        outputQueue = Channel(capacity = 512)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ioScope = scope

        requestCommunicationAudioModeAndFocus()

        val record = createBestAudioRecord(inputSampleRateHz)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            abandonCommunicationAudioModeAndFocus()
            onAudioError("Unable to initialize microphone hardware (${inputSampleRateHz}Hz PCM).")
            return false
        }
        audioRecord = record

        attachAudioEffects(record.audioSessionId)

        val track = createAudioTrack(outputSampleRateHz)
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            releaseEffects()
            runCatching { record.release() }
            audioRecord = null
            abandonCommunicationAudioModeAndFocus()
            onAudioError("Unable to initialize speaker playback (${outputSampleRateHz}Hz PCM).")
            return false
        }
        audioTrack = track

        active = true

        playbackJob = scope.launch {
            runPlaybackLoop()
        }

        captureJob = scope.launch {
            runCaptureLoop(
                record = record,
                sampleRateHz = inputSampleRateHz,
                onInputChunk = onInputChunk,
                onAudioError = onAudioError
            )
        }

        return true
    }

    override suspend fun enqueueOutputAudio(pcmBytes: ByteArray, sampleRateHz: Int) {
        if (!active || pcmBytes.isEmpty()) return
        outputQueue.send(pcmBytes to sampleRateHz)
    }

    override fun interruptOutputPlayback() {
        while (true) {
            val drained = outputQueue.tryReceive()
            if (!drained.isSuccess) break
        }
        val track = audioTrack
        if (track != null) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
                track.play()
            }.onFailure { e ->
                Log.w(TAG, "Failed flushing AudioTrack during barge-in", e)
            }
        }
        _outputLevel.value = 0f
    }

    override fun setMuted(muted: Boolean) {
        isMutedFlag = muted
        if (muted) {
            _inputLevel.value = 0f
        }
    }

    override fun stopAudio() {
        active = false
        captureJob?.cancel()
        captureJob = null
        playbackJob?.cancel()
        playbackJob = null
        runCatching { outputQueue.close() }

        releaseEffects()

        audioRecord?.let { rec ->
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            }
            runCatching { rec.release() }
        }
        audioRecord = null

        audioTrack?.let { trk ->
            runCatching {
                if (trk.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    trk.pause()
                }
                trk.flush()
                trk.stop()
            }
            runCatching { trk.release() }
        }
        audioTrack = null

        ioScope = null
        _inputLevel.value = 0f
        _outputLevel.value = 0f
        _isEchoCancelerActive.value = false
        abandonCommunicationAudioModeAndFocus()
    }

    @SuppressLint("MissingPermission")
    private fun createBestAudioRecord(sampleRateHz: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val bufferSize = maxOf(minBuf * 2, 1280 * 4)

        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        for (source in sources) {
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }.getOrNull()

            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    private fun createAudioTrack(sampleRateHz: Int): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val bufferSize = maxOf(minBuf * 2, 4800 * 2)

        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        releaseEffects()
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    echoCanceler = this
                    _isEchoCancelerActive.value = enabled
                }
            }.onFailure {
                _isEchoCancelerActive.value = false
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    noiseSuppressor = this
                }
            }
        }
    }

    private fun releaseEffects() {
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
    }

    private suspend fun runCaptureLoop(
        record: AudioRecord,
        sampleRateHz: Int,
        onInputChunk: suspend (ByteArray) -> Unit,
        onAudioError: (String) -> Unit
    ) {
        // 40 ms chunks at 16 kHz mono 16-bit PCM = 640 samples = 1280 bytes
        val frameSamples = (sampleRateHz * 40) / 1000
        val shortBuffer = ShortArray(frameSamples)
        val byteBuffer = ByteArray(frameSamples * 2)
        val silentChunk = ByteArray(byteBuffer.size)
        var wasMuted = isMutedFlag
        var silenceTailRemaining = 0
        var zeroReadCount = 0

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onAudioError("Microphone failed to enter recording state.")
                return
            }

            while (active && ioScope?.isActive == true) {
                val readCount = record.read(shortBuffer, 0, frameSamples)
                if (readCount > 0) {
                    zeroReadCount = 0
                    val mutedNow = isMutedFlag
                    if (!mutedNow) {
                        silenceTailRemaining = 0
                        var sumSquares = 0.0
                        for (i in 0 until readCount) {
                            val s = shortBuffer[i].toInt()
                            sumSquares += (s * s).toDouble()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        val rms = sqrt(sumSquares / readCount).toFloat()
                        _inputLevel.value = (rms / 6500f).coerceIn(0f, 1f)
                        val chunk = byteBuffer.copyOf(readCount * 2)
                        onInputChunk(chunk)
                    } else {
                        _inputLevel.value = 0f
                        // Send a brief ~240ms silence tail right when muted so server VAD closes any active speech cleanly.
                        if (!wasMuted) {
                            silenceTailRemaining = 6
                        }
                        if (silenceTailRemaining > 0) {
                            silenceTailRemaining--
                            onInputChunk(silentChunk)
                        } else {
                            delay(20L)
                        }
                    }
                    wasMuted = mutedNow
                } else if (readCount == AudioRecord.ERROR_INVALID_OPERATION ||
                    readCount == AudioRecord.ERROR_DEAD_OBJECT
                ) {
                    onAudioError("Microphone hardware stream was interrupted.")
                    return
                } else {
                    zeroReadCount++
                    if (zeroReadCount > 40) {
                        onAudioError("Microphone stopped delivering audio frames.")
                        return
                    }
                    delay(15L)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord capture error", e)
            onAudioError(e.localizedMessage ?: "Audio capture error.")
        } finally {
            _inputLevel.value = 0f
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
        }
    }

    private suspend fun runPlaybackLoop() {
        try {
            audioTrack?.play()
            for ((pcmBytes, sampleRateHz) in outputQueue) {
                if (!active) break

                if (sampleRateHz != currentOutputSampleRateHz && sampleRateHz > 0) {
                    currentOutputSampleRateHz = sampleRateHz
                    audioTrack?.let { old ->
                        runCatching { old.pause(); old.flush(); old.stop(); old.release() }
                    }
                    audioTrack = createAudioTrack(sampleRateHz)?.also { it.play() }
                }

                val track = audioTrack ?: continue
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { track.play() }
                }

                _outputLevel.value = computePcm16RmsNormalized(pcmBytes)
                var offset = 0
                while (offset < pcmBytes.size && active) {
                    val written = track.write(pcmBytes, offset, pcmBytes.size - offset)
                    if (written > 0) {
                        offset += written
                    } else if (written < 0) {
                        Log.w(TAG, "AudioTrack write error code=$written")
                        break
                    }
                }
                if (outputQueue.isEmpty) {
                    _outputLevel.value = 0f
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback error", e)
        } finally {
            _outputLevel.value = 0f
        }
    }

    private fun computePcm16RmsNormalized(bytes: ByteArray): Float {
        if (bytes.size < 2) return 0f
        val samples = bytes.size / 2
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += (sample * sample).toDouble()
            i += 2
        }
        val rms = sqrt(sumSquares / samples).toFloat()
        return (rms / 6500f).coerceIn(0f, 1f)
    }

    private fun requestCommunicationAudioModeAndFocus() {
        val am = audioManager ?: return
        runCatching {
            previousAudioMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            audioModeChanged = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            runCatching { am.requestAudioFocus(req) }
        }
    }

    private fun abandonCommunicationAudioModeAndFocus() {
        val am = audioManager ?: return
        if (audioModeChanged) {
            runCatching { am.mode = previousAudioMode }
            audioModeChanged = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { req ->
                runCatching { am.abandonAudioFocusRequest(req) }
            }
            focusRequest = null
        }
    }

    companion object {
        private const val TAG = "AndroidLiveAudioGateway"
    }
}

package com.example.data.voice.alwayson

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class VadFrameAnalysis(
    val rmsAmplitude: Double,
    val dbfs: Double,
    val zeroCrossingRate: Float,
    val noiseFloorRms: Double,
    val isSpeechActive: Boolean,
    val speechStarted: Boolean,
    val speechEnded: Boolean
)

/**
 * Lightweight local Voice Activity Detector (VAD) operating on 16 kHz mono PCM16 frames.
 * Uses an adaptive noise-floor estimator and speech-band zero-crossing rate checks to
 * avoid triggering wake-word verification on steady background noise or brief clicks.
 */
class VoiceActivityDetector(
    private val sampleRateHz: Int = 16_000,
    private val minAbsoluteRmsThreshold: Double = 420.0,
    private val speechSnrMultiplier: Double = 2.6,
    private val minConsecutiveSpeechFrames: Int = 4,
    private val silenceHangoverFrames: Int = 14
) {
    private var noiseFloorRms: Double = 200.0
    private var activeSpeech: Boolean = false
    private var consecutiveSpeechCount: Int = 0
    private var consecutiveSilenceCount: Int = 0

    fun reset() {
        noiseFloorRms = 200.0
        activeSpeech = false
        consecutiveSpeechCount = 0
        consecutiveSilenceCount = 0
    }

    fun processFrame(samples: ShortArray, length: Int = samples.size): VadFrameAnalysis {
        if (length <= 0 || samples.isEmpty()) {
            return VadFrameAnalysis(
                rmsAmplitude = 0.0,
                dbfs = -90.0,
                zeroCrossingRate = 0f,
                noiseFloorRms = noiseFloorRms,
                isSpeechActive = activeSpeech,
                speechStarted = false,
                speechEnded = false
            )
        }

        val count = length.coerceAtMost(samples.size)
        var sumSquares = 0.0
        var zeroCrossings = 0
        var prevSign = samples[0] >= 0

        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSquares += s * s
            val sign = samples[i] >= 0
            if (i > 0 && sign != prevSign) {
                zeroCrossings++
            }
            prevSign = sign
        }

        val rms = sqrt(sumSquares / count.toDouble())
        val dbfs = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -90.0
        val zcr = zeroCrossings.toFloat() / max(1, count - 1).toFloat()

        // Typical human voiced/unvoiced speech at 16 kHz falls roughly between ZCR 0.015 and 0.42.
        val dynamicThreshold = max(minAbsoluteRmsThreshold, noiseFloorRms * speechSnrMultiplier)
        val frameLooksLikeSpeech = rms >= dynamicThreshold && zcr in 0.012f..0.48f

        var speechStarted = false
        var speechEnded = false

        if (frameLooksLikeSpeech) {
            consecutiveSilenceCount = 0
            consecutiveSpeechCount++
            if (!activeSpeech && consecutiveSpeechCount >= minConsecutiveSpeechFrames) {
                activeSpeech = true
                speechStarted = true
            }
        } else {
            consecutiveSpeechCount = 0
            // Slowly adapt noise floor only during non-speech frames.
            noiseFloorRms = (noiseFloorRms * 0.94 + rms * 0.06).coerceIn(80.0, 2200.0)
            if (activeSpeech) {
                consecutiveSilenceCount++
                if (consecutiveSilenceCount >= silenceHangoverFrames) {
                    activeSpeech = false
                    speechEnded = true
                }
            }
        }

        return VadFrameAnalysis(
            rmsAmplitude = rms,
            dbfs = dbfs,
            zeroCrossingRate = zcr,
            noiseFloorRms = noiseFloorRms,
            isSpeechActive = activeSpeech,
            speechStarted = speechStarted,
            speechEnded = speechEnded
        )
    }

    fun frameDurationMs(sampleCount: Int): Long {
        return ((sampleCount.toDouble() / sampleRateHz.toDouble()) * 1000.0).toLong()
    }
}

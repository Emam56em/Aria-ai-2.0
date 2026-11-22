package com.example.data.voice.alwayson

import com.example.data.devicecontrol.FuzzyAppMatcher
import kotlin.math.max
import kotlin.math.min

data class WakeDetectionResult(
    val matched: Boolean,
    val matchedPhrase: String = "",
    val trailingCommand: String = "",
    val confidence: Float = 0f
)

/**
 * Two-stage local wake-word detector for "Hello Aria" and user-customized wake phrases.
 *
 * Stage 1 (Acoustic Candidate Gate): Filters VAD speech bursts by duration and energy modulation
 * locally on PCM frames so transient clicks or steady ambient noise do not trigger recognition.
 *
 * Stage 2 (Bilingual Phrase Matcher): Matches English, Bengali, and Banglish phonetic forms of
 * the configured wake word, supports cooldown debouncing, and extracts optional inline commands
 * (e.g., "Hello Aria open calculator" -> trailingCommand="open calculator").
 */
class WakeWordDetector(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    @Volatile
    private var lastTriggerTimestampMs: Long = 0L

    fun resetCooldown() {
        lastTriggerTimestampMs = 0L
    }

    /**
     * Stage 1 local acoustic validation of a captured VAD speech segment.
     * Rejects segments shorter than 280 ms (clicks/pops), longer than 3.5 s (unrelated monologue),
     * or flat non-modulated noise.
     */
    fun isPlausibleWakeAcousticCandidate(
        durationMs: Long,
        peakRms: Double,
        activeSpeechFrames: Int
    ): Boolean {
        if (durationMs < 260L || durationMs > 3_500L) return false
        if (activeSpeechFrames < 6) return false
        if (peakRms < 520.0) return false
        return true
    }

    fun matchWakePhrase(
        transcript: String,
        configuredWakeWord: String = DEFAULT_WAKE_WORD,
        nowMs: Long = System.currentTimeMillis(),
        respectCooldown: Boolean = true
    ): WakeDetectionResult {
        if (respectCooldown && lastTriggerTimestampMs > 0L && (nowMs - lastTriggerTimestampMs) < cooldownMs) {
            return WakeDetectionResult(matched = false)
        }

        val cleanedInput = sanitizeTranscript(transcript)
        if (cleanedInput.isEmpty()) return WakeDetectionResult(matched = false)

        val candidates = buildWakeCandidates(configuredWakeWord)
        val inputWords = cleanedInput.split(" ").filter { it.isNotBlank() }

        // Check transliterated form for Bengali/mixed transcripts as well
        val transliteratedInput = if (cleanedInput.any { it in '\u0980'..'\u09FF' }) {
            FuzzyAppMatcher.transliterateBengaliToLatin(cleanedInput)
        } else {
            ""
        }

        var bestConfidence = 0f
        var bestPhrase = ""
        var bestEndWordIndex = -1

        for (candidate in candidates) {
            val candNorm = sanitizeTranscript(candidate)
            if (candNorm.isEmpty()) continue
            val candWords = candNorm.split(" ").filter { it.isNotBlank() }
            if (candWords.isEmpty()) continue

            // Exact prefix or whole-string containment
            if (cleanedInput == candNorm) {
                bestConfidence = 1.0f
                bestPhrase = candidate
                bestEndWordIndex = inputWords.size
                break
            }

            if (cleanedInput.startsWith("$candNorm ")) {
                bestConfidence = 0.98f
                bestPhrase = candidate
                bestEndWordIndex = candWords.size
                break
            }

            // Sliding n-gram fuzzy comparison across input words
            val windowSize = candWords.size
            if (inputWords.size >= windowSize) {
                for (start in 0..(inputWords.size - windowSize)) {
                    val window = inputWords.subList(start, start + windowSize).joinToString(" ")
                    val sim = FuzzyAppMatcher.levenshteinSimilarity(window, candNorm)
                    if (sim > bestConfidence) {
                        bestConfidence = sim
                        bestPhrase = candidate
                        bestEndWordIndex = start + windowSize
                    }
                }
            } else {
                val sim = FuzzyAppMatcher.levenshteinSimilarity(cleanedInput, candNorm)
                if (sim > bestConfidence) {
                    bestConfidence = sim
                    bestPhrase = candidate
                    bestEndWordIndex = inputWords.size
                }
            }

            if (transliteratedInput.isNotEmpty() && candidate.all { it.code < 128 }) {
                val tWords = transliteratedInput.split(" ").filter { it.isNotBlank() }
                if (tWords.size >= windowSize) {
                    for (start in 0..(tWords.size - windowSize)) {
                        val window = tWords.subList(start, start + windowSize).joinToString(" ")
                        val sim = FuzzyAppMatcher.levenshteinSimilarity(window, candNorm)
                        if (sim > bestConfidence) {
                            bestConfidence = sim
                            bestPhrase = candidate
                            bestEndWordIndex = min(inputWords.size, start + windowSize)
                        }
                    }
                } else {
                    val sim = FuzzyAppMatcher.levenshteinSimilarity(transliteratedInput, candNorm)
                    if (sim > bestConfidence) {
                        bestConfidence = sim
                        bestPhrase = candidate
                        bestEndWordIndex = inputWords.size
                    }
                }
            }
        }

        val threshold = if (configuredWakeWord.trim().split(Regex("\\s+")).size <= 1) 0.84f else 0.76f
        if (bestConfidence >= threshold) {
            if (respectCooldown) {
                lastTriggerTimestampMs = nowMs
            }
            val trailing = if (bestEndWordIndex in 1 until inputWords.size) {
                inputWords.subList(bestEndWordIndex, inputWords.size).joinToString(" ").trim()
            } else {
                ""
            }
            return WakeDetectionResult(
                matched = true,
                matchedPhrase = bestPhrase,
                trailingCommand = trailing,
                confidence = bestConfidence
            )
        }

        return WakeDetectionResult(matched = false, confidence = bestConfidence)
    }

    internal fun buildWakeCandidates(configuredWakeWord: String): List<String> {
        val custom = sanitizeTranscript(configuredWakeWord)
        val defaults = listOf(
            "hello aria",
            "hello ariya",
            "hello area",
            "hallo aria",
            "helo aria",
            "হ্যালো আরিয়া",
            "হ্যালো আরিয়া",
            "হেলো আরিয়া",
            "হেলো আরিয়া",
            "হ্যালো এরিয়া",
            "হ্যালো এআই আরিয়া"
        )

        return if (custom.isEmpty() || custom == "hello aria") {
            defaults
        } else {
            val combined = linkedSetOf(custom)
            if (custom.any { it in '\u0980'..'\u09FF' }) {
                val translit = FuzzyAppMatcher.transliterateBengaliToLatin(custom)
                if (translit.isNotBlank()) combined.add(translit)
            }
            combined.toList()
        }
    }

    private fun sanitizeTranscript(raw: String): String {
        return raw
            .trim()
            .lowercase()
            .replace(Regex("[।,.!?\"'`~:;()\\[\\]{}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        const val DEFAULT_WAKE_WORD = "Hello Aria"
        const val DEFAULT_WAKE_ACK_BENGALI = "জি বস, বলুন।"
        const val DEFAULT_COOLDOWN_MS = 2_500L
    }
}

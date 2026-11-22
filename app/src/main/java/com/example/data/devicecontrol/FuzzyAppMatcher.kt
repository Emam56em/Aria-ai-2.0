package com.example.data.devicecontrol

import kotlin.math.max
import kotlin.math.min

data class AppMatchResult(
    val app: InstalledAppInfo,
    val score: Float
)

/**
 * Bilingual (English & Bengali) fuzzy matcher for installed Android apps.
 * Combines normalized substring/token matching, curated Bengali-English app aliases,
 * Bengali-to-Latin phonetic transliteration, and Levenshtein similarity.
 */
object FuzzyAppMatcher {

    private val fillerTokens = setOf(
        "open", "launch", "start", "run", "app", "application", "the", "please", "pls",
        "খোলো", "খুলুন", "খুলে", "দাও", "খোল", "চালু", "করো", "কর", "ওপেন", "অ্যাপ", "অ্যাপটি", "টা"
    )

    private val bengaliAliasMap: Map<String, List<String>> = mapOf(
        "ক্যালকুলেটর" to listOf("calculator", "calc"),
        "ক্যামেরা" to listOf("camera", "cam"),
        "ম্যাপস" to listOf("maps", "google maps", "map"),
        "ম্যাপ" to listOf("maps", "map"),
        "ইউটিউব" to listOf("youtube", "yt"),
        "হোয়াটসঅ্যাপ" to listOf("whatsapp"),
        "হোয়াটস" to listOf("whatsapp"),
        "ফেসবুক" to listOf("facebook", "fb"),
        "মেসেঞ্জার" to listOf("messenger"),
        "ক্রোম" to listOf("chrome"),
        "ব্রাউজার" to listOf("browser", "chrome", "firefox", "edge"),
        "জিমেইল" to listOf("gmail", "mail"),
        "ইমেইল" to listOf("gmail", "email", "mail"),
        "মেইল" to listOf("gmail", "mail"),
        "সেটিংস" to listOf("settings", "setting"),
        "সেটিং" to listOf("settings", "setting"),
        "ফোন" to listOf("phone", "dialer", "contacts"),
        "ডায়ালার" to listOf("phone", "dialer"),
        "কন্টাক্টস" to listOf("contacts"),
        "মেসেজ" to listOf("messages", "messaging", "sms"),
        "এসএমএস" to listOf("messages", "messaging", "sms"),
        "ক্যালেন্ডার" to listOf("calendar"),
        "ঘড়ি" to listOf("clock", "alarm"),
        "অ্যালার্ম" to listOf("clock", "alarm"),
        "গ্যালারি" to listOf("gallery", "photos"),
        "ফটোস" to listOf("photos", "gallery"),
        "স্পটিফাই" to listOf("spotify"),
        "টেলিগ্রাম" to listOf("telegram"),
        "ইনস্টাগ্রাম" to listOf("instagram"),
        "প্লে স্টোর" to listOf("play store", "google play"),
        "ফাইলস" to listOf("files", "file manager"),
        "নোটস" to listOf("notes", "keep"),
        "ম্যাজিক" to listOf("magic")
    )

    private val shortcutKeywords: Map<SupportedAppId, List<String>> = mapOf(
        SupportedAppId.CALCULATOR to listOf("calculator", "calc", "ক্যালকুলেটর"),
        SupportedAppId.SETTINGS to listOf("settings", "setting", "সেটিংস", "সেটিং"),
        SupportedAppId.CALENDAR to listOf("calendar", "ক্যালেন্ডার"),
        SupportedAppId.CAMERA to listOf("camera", "ক্যামেরা"),
        SupportedAppId.CLOCK to listOf("clock", "alarm", "ঘড়ি", "অ্যালার্ম"),
        SupportedAppId.BROWSER to listOf("browser", "ব্রাউজার"),
        SupportedAppId.MESSAGES to listOf("messages", "message", "sms", "মেসেজ", "এসএমএস"),
        SupportedAppId.MAPS to listOf("maps", "map", "ম্যাপস", "ম্যাপ"),
        SupportedAppId.PHONE to listOf("phone", "dialer", "ফোন", "ডায়ালার")
    )

    fun extractTargetQuery(rawCommand: String): String {
        val cleaned = rawCommand
            .trim()
            .replace(Regex("[।,!?\"'`]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.lowercase() !in fillerTokens }
            .joinToString(" ")
            .trim()
        return cleaned
    }

    fun matchSupportedShortcut(query: String): SupportedAppId? {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return null
        return shortcutKeywords.entries.firstOrNull { (_, words) ->
            words.any { word ->
                val normWord = normalize(word)
                normalized == normWord ||
                    normalized.split(" ").contains(normWord) ||
                    levenshteinSimilarity(normalized, normWord) >= 0.85f
            }
        }?.key
    }

    fun filterApps(query: String, installedApps: List<InstalledAppInfo>): List<InstalledAppInfo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return installedApps

        val expandedQueries = expandQueryTerms(trimmed)
        return installedApps
            .mapNotNull { app ->
                val score = scoreApp(expandedQueries, app)
                if (score >= 0.55f) AppMatchResult(app, score) else null
            }
            .sortedWith(
                compareByDescending<AppMatchResult> { it.score }
                    .thenBy { it.app.appName.lowercase() }
            )
            .map { it.app }
    }

    fun findBestMatch(
        query: String,
        installedApps: List<InstalledAppInfo>,
        minScore: Float = 0.62f
    ): AppMatchResult? {
        val cleaned = extractTargetQuery(query).ifBlank { query.trim() }
        if (cleaned.isEmpty() || installedApps.isEmpty()) return null

        val expandedQueries = expandQueryTerms(cleaned)
        return installedApps
            .map { app -> AppMatchResult(app, scoreApp(expandedQueries, app)) }
            .filter { it.score >= minScore }
            .maxWithOrNull(
                compareBy<AppMatchResult> { it.score }
                    .thenByDescending { !it.app.isSystemApp }
                    .thenBy { -it.app.appName.length }
            )
    }

    internal fun expandQueryTerms(rawQuery: String): List<String> {
        val norm = normalize(rawQuery)
        val terms = linkedSetOf<String>()
        if (norm.isNotEmpty()) terms.add(norm)

        // Check curated Bengali alias matches
        for ((bnKey, aliases) in bengaliAliasMap) {
            if (norm == bnKey || norm.contains(bnKey) || levenshteinSimilarity(norm, bnKey) >= 0.80f) {
                terms.addAll(aliases.map(::normalize))
            }
        }

        // Add phonetic Latin transliteration if the query contains Bengali script
        if (norm.any { it in '\u0980'..'\u09FF' }) {
            val transliterated = transliterateBengaliToLatin(norm)
            if (transliterated.length >= 2) {
                terms.add(transliterated)
            }
        }

        return terms.toList()
    }

    private fun scoreApp(queries: List<String>, app: InstalledAppInfo): Float {
        val appNameNorm = normalize(app.appName)
        val pkgNorm = app.packageName.lowercase()
        val pkgTail = pkgNorm.substringAfterLast('.')
        val appWords = appNameNorm.split(" ").filter { it.isNotBlank() }

        var best = 0f
        for (q in queries) {
            if (q.isEmpty()) continue
            val candidateScore = when {
                pkgNorm == q -> 1.0f
                appNameNorm == q -> 0.99f
                pkgTail == q -> 0.95f
                appNameNorm.startsWith(q) -> 0.92f
                appWords.any { it == q } -> 0.90f
                appWords.any { it.startsWith(q) } -> 0.86f
                appNameNorm.contains(q) -> 0.82f
                pkgNorm.contains(q) -> 0.78f
                else -> {
                    val wholeSim = levenshteinSimilarity(q, appNameNorm)
                    val wordSim = appWords.maxOfOrNull { levenshteinSimilarity(q, it) } ?: 0f
                    val pkgSim = levenshteinSimilarity(q, pkgTail)
                    max(wholeSim, max(wordSim, pkgSim * 0.95f))
                }
            }
            if (candidateScore > best) best = candidateScore
        }
        return best
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("[._\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
    }

    internal fun transliterateBengaliToLatin(input: String): String {
        val normalizedInput = input
            .replace("\u09A1\u09BC", "\u09DC") // ড় -> ড় (precomposed)
            .replace("\u09A2\u09BC", "\u09DD") // ঢ় -> ঢ় (precomposed)
            .replace("\u09AF\u09BC", "\u09DF") // য় -> য় (precomposed)
        val sb = StringBuilder(normalizedInput.length * 2)
        for (ch in normalizedInput) {
            val mapped = when (ch) {
                'অ' -> "o"
                'আ', 'া' -> "a"
                'ই', 'ঈ', 'ি', 'ী' -> "i"
                'উ', 'ঊ', 'ু', 'ূ' -> "u"
                'ঋ', 'ৃ' -> "ri"
                'এ', 'ে' -> "e"
                'ঐ', 'ৈ' -> "oi"
                'ও', 'ো' -> "o"
                'ঔ', 'ৌ' -> "ou"
                'ক' -> "k"
                'খ' -> "kh"
                'গ' -> "g"
                'ঘ' -> "gh"
                'ঙ', 'ং' -> "ng"
                'চ' -> "ch"
                'ছ' -> "chh"
                'জ', 'য' -> "j"
                'ঝ' -> "jh"
                'ট', 'ত', 'ৎ' -> "t"
                'ঠ', 'থ' -> "th"
                'ড', 'দ' -> "d"
                'ঢ', 'ধ' -> "dh"
                'ণ', 'ন' -> "n"
                'প' -> "p"
                'ফ' -> "f"
                'ব' -> "b"
                'ভ' -> "bh"
                'ম' -> "m"
                'র', '\u09DC', '\u09DD' -> "r"
                'ল' -> "l"
                'শ', 'ষ', 'স' -> "s"
                'হ' -> "h"
                '\u09DF' -> "y"
                '্', 'ঁ', '\u09BC' -> ""
                ' ' -> " "
                in 'a'..'z', in '0'..'9' -> ch.toString()
                else -> ""
            }
            sb.append(mapped)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    internal fun levenshteinSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = max(a.length, b.length)
        val dist = levenshteinDistance(a, b)
        return (1f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}

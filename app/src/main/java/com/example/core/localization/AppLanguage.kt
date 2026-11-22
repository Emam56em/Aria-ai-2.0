package com.example.core.localization

enum class AppLanguage(
    val code: String,
    val nativeLabel: String,
    val shortBadge: String,
    val speechLocaleTag: String
) {
    ENGLISH(
        code = "en",
        nativeLabel = "English",
        shortBadge = "EN",
        speechLocaleTag = "en-US"
    ),
    BENGALI(
        code = "bn",
        nativeLabel = "বাংলা",
        shortBadge = "বাংলা",
        speechLocaleTag = "bn-BD"
    );

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

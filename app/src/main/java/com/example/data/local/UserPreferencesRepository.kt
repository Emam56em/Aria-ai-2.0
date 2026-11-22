package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.localization.AppLanguage
import com.example.data.ai.GeminiModelOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ariaDataStore: DataStore<Preferences> by preferencesDataStore(name = "aria_preferences")

data class UserPreferences(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val selectedModel: GeminiModelOption = GeminiModelOption.GEMINI_3_8_FLASH,
    val autoSpeakReplies: Boolean = false,
    val alwaysOnListeningEnabled: Boolean = false,
    val wakeWord: String = DEFAULT_WAKE_WORD,
    val alwaysOnVoiceResponseEnabled: Boolean = true,
    val resumeMonitoringAfterReply: Boolean = true
) {
    companion object {
        const val DEFAULT_WAKE_WORD = "Hello Aria"
    }
}

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("app_language")
        val MODEL = stringPreferencesKey("gemini_model")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak_replies")
        val ALWAYS_ON_ENABLED = booleanPreferencesKey("always_on_listening_enabled")
        val WAKE_WORD = stringPreferencesKey("always_on_wake_word")
        val ALWAYS_ON_VOICE_RESPONSE = booleanPreferencesKey("always_on_voice_response")
        val RESUME_MONITORING_AFTER_REPLY = booleanPreferencesKey("resume_monitoring_after_reply")
    }

    val preferencesFlow: Flow<UserPreferences> = context.ariaDataStore.data.map { prefs ->
        val savedWakeWord = prefs[Keys.WAKE_WORD]?.trim().orEmpty()
        UserPreferences(
            language = AppLanguage.fromCode(prefs[Keys.LANGUAGE]),
            selectedModel = GeminiModelOption.fromId(prefs[Keys.MODEL]),
            autoSpeakReplies = prefs[Keys.AUTO_SPEAK] ?: false,
            alwaysOnListeningEnabled = prefs[Keys.ALWAYS_ON_ENABLED] ?: false,
            wakeWord = savedWakeWord.ifBlank { UserPreferences.DEFAULT_WAKE_WORD },
            alwaysOnVoiceResponseEnabled = prefs[Keys.ALWAYS_ON_VOICE_RESPONSE] ?: true,
            resumeMonitoringAfterReply = prefs[Keys.RESUME_MONITORING_AFTER_REPLY] ?: true
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = language.code
        }
    }

    suspend fun setModel(model: GeminiModelOption) {
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.MODEL] = model.modelId
        }
    }

    suspend fun setAutoSpeakReplies(enabled: Boolean) {
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.AUTO_SPEAK] = enabled
        }
    }

    suspend fun setAlwaysOnListeningEnabled(enabled: Boolean) {
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.ALWAYS_ON_ENABLED] = enabled
        }
    }

    suspend fun setWakeWord(wakeWord: String) {
        val sanitized = wakeWord.trim().ifBlank { UserPreferences.DEFAULT_WAKE_WORD }
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.WAKE_WORD] = sanitized
        }
    }

    suspend fun setAlwaysOnVoiceResponseEnabled(enabled: Boolean) {
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.ALWAYS_ON_VOICE_RESPONSE] = enabled
        }
    }

    suspend fun setResumeMonitoringAfterReply(enabled: Boolean) {
        context.ariaDataStore.edit { prefs ->
            prefs[Keys.RESUME_MONITORING_AFTER_REPLY] = enabled
        }
    }
}

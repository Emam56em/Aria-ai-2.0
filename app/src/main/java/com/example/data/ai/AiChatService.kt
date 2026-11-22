package com.example.data.ai

import android.util.Log
import com.example.core.localization.AppLanguage
import com.example.data.local.MessageEntity
import com.example.data.local.MessageRole
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

enum class GeminiModelOption(
    val modelId: String,
    val label: String
) {
    GEMINI_3_8_FLASH("gemini-3.8-flash", "Gemini 3.8 Flash"),
    GEMINI_3_5_FLASH_LITE("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite");

    companion object {
        fun fromId(id: String?): GeminiModelOption =
            entries.firstOrNull { it.modelId == id } ?: GEMINI_3_8_FLASH
    }
}

sealed interface AiStreamEvent {
    data class Partial(val accumulatedText: String) : AiStreamEvent
    data class Completed(val fullText: String) : AiStreamEvent
}

interface AiChatService {
    fun sendMessageStream(
        prompt: String,
        history: List<MessageEntity>,
        language: AppLanguage,
        modelOption: GeminiModelOption
    ): Flow<AiStreamEvent>
}

class FirebaseAiChatService : AiChatService {

    private val flashModel: GenerativeModel by lazy {
        createModel(GeminiModelOption.GEMINI_3_8_FLASH.modelId)
    }

    private val flashLiteModel: GenerativeModel by lazy {
        createModel(GeminiModelOption.GEMINI_3_5_FLASH_LITE.modelId)
    }

    private fun createModel(modelId: String): GenerativeModel {
        return Firebase.ai.generativeModel(
            modelName = modelId,
            generationConfig = generationConfig {
                temperature = 0.7f
                topP = 0.95f
            },
            systemInstruction = content {
                text(
                    """
                    You are Aria AI 2.0 (আরিয়া এআই ২.০), a warm, helpful, intelligent native Android AI assistant.
                    You are fluent in both Bengali (বাংলা) and English.
                    - Match the language of the user's message naturally (reply in Bengali when addressed in Bengali or Banglish when natural, and in English when addressed in English, unless asked to translate or switch).
                    - Be concise, accurate, and well-structured for mobile reading.
                    - You also collaborate with Aria's modular Android device control system (which supports toggling the device flashlight and opening supported apps like Settings, Calculator, Calendar, Camera, Clock, Phone, Browser, Messages, and Maps). When a user asks to perform one of those supported actions, confirm concisely and helpfully.
                    """.trimIndent()
                )
            }
        )
    }

    private fun selectModel(option: GeminiModelOption): GenerativeModel = when (option) {
        GeminiModelOption.GEMINI_3_8_FLASH -> flashModel
        GeminiModelOption.GEMINI_3_5_FLASH_LITE -> flashLiteModel
    }

    override fun sendMessageStream(
        prompt: String,
        history: List<MessageEntity>,
        language: AppLanguage,
        modelOption: GeminiModelOption
    ): Flow<AiStreamEvent> = flow {
        val model = selectModel(modelOption)
        val chatHistory: List<Content> = history
            .filter { !it.isError && it.content.isNotBlank() }
            .takeLast(20)
            .map { msg ->
                val role = if (msg.role == MessageRole.USER) "user" else "model"
                content(role) { text(msg.content) }
            }

        val chat = model.startChat(history = chatHistory)
        val effectivePrompt = if (language == AppLanguage.BENGALI && prompt.none { it in '\u0980'..'\u09FF' }) {
            // Keep natural language detection while hinting preferred UI language if ambiguous
            prompt
        } else {
            prompt
        }

        val accumulated = StringBuilder()
        try {
            withTimeout(60_000L) {
                chat.sendMessageStream(effectivePrompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(AiStreamEvent.Partial(accumulated.toString()))
                    }
                }
            }
            val finalText = accumulated.toString().trim()
            if (finalText.isBlank()) {
                throw EmptyAiResponseException()
            }
            emit(AiStreamEvent.Completed(finalText))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini streaming timed out", e)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini streaming failed", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "FirebaseAiChatService"
    }
}

class EmptyAiResponseException : IllegalStateException("Gemini returned an empty response.")

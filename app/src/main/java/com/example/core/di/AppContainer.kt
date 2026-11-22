package com.example.core.di

import android.content.Context
import com.example.data.ai.AiChatService
import com.example.data.ai.FirebaseAiChatService
import com.example.data.devicecontrol.AppLauncherController
import com.example.data.devicecontrol.DeviceControlRegistry
import com.example.data.devicecontrol.FlashlightController
import com.example.data.local.AriaDatabase
import com.example.data.local.ConversationRepository
import com.example.data.local.RoomConversationRepository
import com.example.data.local.UserPreferencesRepository
import com.example.data.voice.GeminiLiveSessionClient
import com.example.data.voice.TtsManager
import com.example.data.voice.VoiceInputManager
import com.example.data.voice.alwayson.AudioSessionManager

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: AriaDatabase by lazy {
        AriaDatabase.getInstance(appContext)
    }

    val conversationRepository: ConversationRepository by lazy {
        RoomConversationRepository(database.conversationDao())
    }

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(appContext)
    }

    val aiChatService: AiChatService by lazy {
        FirebaseAiChatService()
    }

    val deviceControlRegistry: DeviceControlRegistry by lazy {
        DeviceControlRegistry(
            flashlight = FlashlightController(appContext),
            appLauncher = AppLauncherController(appContext)
        )
    }

    val voiceInputManager: VoiceInputManager by lazy {
        VoiceInputManager(appContext)
    }

    val liveSessionClient: GeminiLiveSessionClient by lazy {
        GeminiLiveSessionClient(appContext)
    }

    val ttsManager: TtsManager by lazy {
        TtsManager(appContext)
    }

    val audioSessionManager: AudioSessionManager by lazy {
        AudioSessionManager(
            context = appContext,
            preferencesRepository = preferencesRepository,
            conversationRepository = conversationRepository,
            aiChatService = aiChatService,
            deviceControlRegistry = deviceControlRegistry,
            ttsManager = ttsManager
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: AppContainer? = null

        fun getInstance(context: Context): AppContainer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppContainer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

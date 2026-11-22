package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.localization.AppLanguage
import com.example.data.ai.AiChatService
import com.example.data.ai.AiStreamEvent
import com.example.data.ai.GeminiModelOption
import com.example.data.devicecontrol.AppLauncherController
import com.example.data.devicecontrol.ConfirmationDecision
import com.example.data.devicecontrol.DeviceControlRegistry
import com.example.data.devicecontrol.FlashlightController
import com.example.data.devicecontrol.SensitiveActionCategory
import com.example.data.devicecontrol.SensitiveActionDetector
import com.example.data.local.ConversationEntity
import com.example.data.local.ConversationRepository
import com.example.data.local.MessageEntity
import com.example.data.local.MessageRole
import com.example.data.local.UserPreferences
import com.example.data.local.UserPreferencesRepository
import com.example.data.voice.TtsManager
import com.example.data.voice.alwayson.AudioSessionManager
import com.example.data.voice.alwayson.BatteryOptimizationHelper
import com.example.data.voice.alwayson.SpeechTurnResult
import com.example.data.voice.alwayson.VoiceActivityDetector
import com.example.data.voice.alwayson.VoiceMonitorStatus
import com.example.data.voice.alwayson.VoiceMonitoringService
import com.example.data.voice.alwayson.WakeWordDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlwaysOnVoiceRobolectricTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    // 1. Hello Aria wake-word detection (English, Bengali, custom wake word, false-positive & VAD rejection)
    @Test
    fun `wake word detector recognizes English, Bengali, custom wake words and rejects false positives`() {
        val detector = WakeWordDetector(cooldownMs = 0L)

        val enExact = detector.matchWakePhrase("Hello Aria")
        assertTrue(enExact.matched)
        assertEquals("", enExact.trailingCommand)

        val enInline = detector.matchWakePhrase("Hello Aria turn on the flashlight")
        assertTrue(enInline.matched)
        assertEquals("turn on the flashlight", enInline.trailingCommand)

        val bnExact = detector.matchWakePhrase("হ্যালো আরিয়া")
        assertTrue(bnExact.matched)

        val bnInline = detector.matchWakePhrase("হেলো আরিয়া ক্যালকুলেটর খোলো")
        assertTrue(bnInline.matched)
        assertTrue(bnInline.trailingCommand.contains("ক্যালকুলেটর"))

        // False-positive rejection on unrelated speech
        assertFalse(detector.matchWakePhrase("Hello world how are you today").matched)
        assertFalse(detector.matchWakePhrase("Good morning everyone").matched)

        // Custom wake word support
        assertTrue(detector.matchWakePhrase("Hey Nova", configuredWakeWord = "Hey Nova").matched)
        assertFalse(detector.matchWakePhrase("Hello Aria", configuredWakeWord = "Hey Nova").matched)
    }

    @Test
    fun `vad and wake detector reject ambient noise and brief clicks while accepting speech`() {
        val vad = VoiceActivityDetector()
        val detector = WakeWordDetector()

        // Steady low-level ambient noise (amplitude 120) must NOT trigger speech start
        val quietNoise = ShortArray(512) { idx -> if (idx % 2 == 0) 110 else -110 }
        var quietTriggered = false
        repeat(12) {
            if (vad.processFrame(quietNoise).speechStarted) quietTriggered = true
        }
        assertFalse("Steady low noise should not activate speech VAD", quietTriggered)

        // Synthetic 250Hz speech-like sine wave (~16 zero crossings / 512 samples, RMS ~ 4200)
        val speechFrame = ShortArray(512) { i ->
            (6000.0 * sin(2.0 * Math.PI * 250.0 * i / 16000.0)).toInt().toShort()
        }
        var speechDetected = false
        repeat(6) {
            if (vad.processFrame(speechFrame).isSpeechActive) speechDetected = true
        }
        assertTrue("Speech-band signal should activate VAD", speechDetected)

        // Acoustic plausibility rejects short <200ms clicks, accepts ~900ms modulated burst
        assertFalse(detector.isPlausibleWakeAcousticCandidate(durationMs = 120L, peakRms = 4000.0, activeSpeechFrames = 3))
        assertTrue(detector.isPlausibleWakeAcousticCandidate(durationMs = 900L, peakRms = 4200.0, activeSpeechFrames = 14))
    }

    // 2 & 3 & 10. Bengali & English voice commands, Gemini AI integration, TTS, and sensitive confirmation
    @Test
    fun `wake turn responds with Ji Bos Bolun, executes Bengali flashlight command, and calls Gemini with TTS`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        registerBackCameraWithFlash()

        val prefsRepo = UserPreferencesRepository(app)
        prefsRepo.setLanguage(AppLanguage.BENGALI)
        prefsRepo.setAlwaysOnVoiceResponseEnabled(true)

        val convRepo = FakeConversationRepository()
        val fakeAi = FakeAiChatService("ঢাকা বাংলাদেশের রাজধানী।")
        val registry = DeviceControlRegistry(FlashlightController(app), AppLauncherController(app))
        val spokenOutputs = mutableListOf<Pair<String, AppLanguage>>()

        val sessionManager = AudioSessionManager(
            context = app,
            preferencesRepository = prefsRepo,
            conversationRepository = convRepo,
            aiChatService = fakeAi,
            deviceControlRegistry = registry,
            ttsManager = TtsManager(app),
            externalSpeechRecognizer = { _, _ ->
                SpeechTurnResult.Success("টর্চ চালু করো")
            },
            externalSpeaker = { text, lang ->
                spokenOutputs.add(text to lang)
            }
        )

        // Wake word detected -> responds "জি বস, বলুন।" -> captures "টর্চ চালু করো" -> enables flashlight
        sessionManager.handleWakeWordTriggered()
        assertEquals("জি বস, বলুন।", spokenOutputs.firstOrNull()?.first)
        assertTrue("Flashlight should be ON after Bengali voice command", registry.flashlightState.value.isEnabled)
        assertTrue(spokenOutputs.any { it.first.contains("ফ্ল্যাশলাইট চালু") })

        // English Gemini query turn with conversational context
        prefsRepo.setLanguage(AppLanguage.ENGLISH)
        sessionManager.handleWakeWordTriggered(injectedCommandTranscript = "What is the capital of Bangladesh?")
        assertEquals(1, fakeAi.callCount)
        assertTrue("Gemini should receive prior conversation history", fakeAi.lastHistorySize >= 2)
        assertEquals("ঢাকা বাংলাদেশের রাজধানী।", sessionManager.state.value.lastReply)
        assertTrue(spokenOutputs.any { it.first == "ঢাকা বাংলাদেশের রাজধানী।" })
        // Wake lock must never remain held after turn completes
        assertFalse(sessionManager.state.value.isWakeLockHeld)

        registry.release()
    }

    // 4 & 5 & 7 & 8. Background app listening, screen-off behavior, incoming call interruption & recovery, battery lock release
    @Test
    fun `continuous listening supports background apps, screen-off state, call interruption recovery, and releases wake lock`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val prefsRepo = UserPreferencesRepository(app)
        val convRepo = FakeConversationRepository()
        val fakeAi = FakeAiChatService("Sure, I heard you while in another app.")
        val registry = DeviceControlRegistry(FlashlightController(app), AppLauncherController(app))

        val sessionManager = AudioSessionManager(
            context = app,
            preferencesRepository = prefsRepo,
            conversationRepository = convRepo,
            aiChatService = fakeAi,
            deviceControlRegistry = registry,
            ttsManager = TtsManager(app),
            externalSpeaker = { _, _ -> }
        )

        sessionManager.setAppForegroundState(false)
        sessionManager.onScreenStateChanged(false)
        sessionManager.startMonitoring(serviceRunning = true)

        assertEquals(VoiceMonitorStatus.MONITORING, sessionManager.state.value.status)
        assertFalse(sessionManager.state.value.isAppInForeground)
        assertFalse(sessionManager.state.value.isScreenOn)
        // Must NEVER hold wake lock while passively monitoring
        assertFalse(sessionManager.state.value.isWakeLockHeld)

        // Simulate incoming phone call interruption -> must release mic & enter INTERRUPTED
        sessionManager.onAudioInterruptionStarted()
        assertEquals(VoiceMonitorStatus.INTERRUPTED, sessionManager.state.value.status)

        // Call ends -> must recover back to MONITORING automatically
        sessionManager.onAudioInterruptionEnded()
        assertEquals(VoiceMonitorStatus.MONITORING, sessionManager.state.value.status)

        // Battery optimization helper works cleanly without restricted permissions
        assertFalse(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(app))
        assertNotNull(BatteryOptimizationHelper.createBatteryOptimizationSettingsIntent().action)

        sessionManager.stopMonitoring()
        assertEquals(VoiceMonitorStatus.IDLE, sessionManager.state.value.status)
        registry.release()
    }

    // 6 & 9. Microphone permission denial & VoiceMonitoringService Start/Pause/Resume/Stop controls
    @Test
    fun `service denies start without mic permission and handles start pause resume stop when granted`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val deniedStart = VoiceMonitoringService.start(app)
        assertFalse("Service start must fail cleanly when RECORD_AUDIO is denied", deniedStart)

        // Grant permission and exercise Start / Pause / Resume / Stop on VoiceMonitoringService
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(VoiceMonitoringService::class.java).create()

        controller.withIntent(Intent(app, VoiceMonitoringService::class.java).apply {
            action = VoiceMonitoringService.ACTION_START
        }).startCommand(0, 1)
        val service = controller.get()
        val notifManager = app.getSystemService(NotificationManager::class.java)
        assertTrue(
            "Foreground service should post persistent listening notification",
            shadowOf(notifManager).allNotifications.isNotEmpty()
        )
        assertEquals(
            VoiceMonitorStatus.MONITORING,
            com.example.core.di.AppContainer.getInstance(app).audioSessionManager.state.value.status
        )

        controller.withIntent(Intent(app, VoiceMonitoringService::class.java).apply {
            action = VoiceMonitoringService.ACTION_PAUSE
        }).startCommand(0, 2)
        assertEquals(
            VoiceMonitorStatus.PAUSED,
            com.example.core.di.AppContainer.getInstance(app).audioSessionManager.state.value.status
        )

        controller.withIntent(Intent(app, VoiceMonitoringService::class.java).apply {
            action = VoiceMonitoringService.ACTION_RESUME
        }).startCommand(0, 3)
        assertEquals(
            VoiceMonitorStatus.MONITORING,
            com.example.core.di.AppContainer.getInstance(app).audioSessionManager.state.value.status
        )

        controller.withIntent(Intent(app, VoiceMonitoringService::class.java).apply {
            action = VoiceMonitoringService.ACTION_STOP
        }).startCommand(0, 4)
        assertEquals(
            VoiceMonitorStatus.IDLE,
            com.example.core.di.AppContainer.getInstance(app).audioSessionManager.state.value.status
        )
        assertFalse(
            com.example.core.di.AppContainer.getInstance(app).audioSessionManager.state.value.isServiceRunning
        )
        service.onDestroy()
    }

    // 4 & 10. Sensitive action confirmation gate (sending messages & making payments)
    @Test
    fun `sensitive actions require explicit confirmation before forwarding to Gemini`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val prefsRepo = UserPreferencesRepository(app)
        val convRepo = FakeConversationRepository()
        val fakeAi = FakeAiChatService("Confirmed and prepared.")
        val registry = DeviceControlRegistry(FlashlightController(app), AppLauncherController(app))

        val sessionManager = AudioSessionManager(
            context = app,
            preferencesRepository = prefsRepo,
            conversationRepository = convRepo,
            aiChatService = fakeAi,
            deviceControlRegistry = registry,
            ttsManager = TtsManager(app),
            externalSpeaker = { _, _ -> }
        )

        // Payment request in Bengali -> must pause for confirmation and NOT call Gemini yet
        val detectedPayment = SensitiveActionDetector.detect("বিকাশে ৫০০ টাকা পাঠাও")
        assertEquals(SensitiveActionCategory.PAYMENT, detectedPayment?.category)

        sessionManager.processRecognizedCommand("বিকাশে ৫০০ টাকা পাঠাও")
        assertEquals(VoiceMonitorStatus.AWAITING_CONFIRMATION, sessionManager.state.value.status)
        assertNotNull(sessionManager.state.value.pendingSensitiveAction)
        assertEquals(0, fakeAi.callCount)

        // Cancel via voice ("না") -> must clear pending action without calling Gemini
        sessionManager.processRecognizedCommand("না")
        assertNull(sessionManager.state.value.pendingSensitiveAction)
        assertEquals(0, fakeAi.callCount)

        // Messaging request -> confirm -> forwards to Gemini
        sessionManager.processRecognizedCommand("Send message to Rahim saying I will be late")
        assertEquals(VoiceMonitorStatus.AWAITING_CONFIRMATION, sessionManager.state.value.status)
        assertEquals(ConfirmationDecision.CONFIRMED, SensitiveActionDetector.parseConfirmation("Yes confirm"))
        sessionManager.processRecognizedCommand("Yes")
        assertNull(sessionManager.state.value.pendingSensitiveAction)
        assertEquals(1, fakeAi.callCount)

        registry.release()
    }

    private fun registerBackCameraWithFlash() {
        val cameraManager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(characteristics).set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadowOf(characteristics).set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        shadowOf(cameraManager).addCamera("0", characteristics)
    }

    private class FakeAiChatService(private val reply: String) : AiChatService {
        var callCount: Int = 0
        var lastHistorySize: Int = 0

        override fun sendMessageStream(
            prompt: String,
            history: List<MessageEntity>,
            language: AppLanguage,
            modelOption: GeminiModelOption
        ): Flow<AiStreamEvent> = flow {
            callCount++
            lastHistorySize = history.size
            emit(AiStreamEvent.Partial(reply))
            emit(AiStreamEvent.Completed(reply))
        }
    }

    private class FakeConversationRepository : ConversationRepository {
        private val conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
        private val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
        private var nextConvId = 1L
        private var nextMsgId = 1L

        override fun observeConversations(): Flow<List<ConversationEntity>> = conversations

        override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = messages

        override suspend fun getMessagesOnce(conversationId: Long): List<MessageEntity> =
            messages.value.filter { it.conversationId == conversationId }

        override suspend fun createConversation(title: String, languageCode: String): Long {
            val id = nextConvId++
            val now = System.currentTimeMillis()
            conversations.value = conversations.value + ConversationEntity(
                id = id,
                title = title,
                languageCode = languageCode,
                createdAt = now,
                updatedAt = now
            )
            return id
        }

        override suspend fun updateConversationTitle(conversationId: Long, title: String) {}

        override suspend fun addMessage(
            conversationId: Long,
            role: MessageRole,
            content: String,
            isError: Boolean,
            actionTag: String?
        ): Long {
            val id = nextMsgId++
            messages.value = messages.value + MessageEntity(
                id = id,
                conversationId = conversationId,
                role = role,
                content = content,
                timestamp = System.currentTimeMillis(),
                isError = isError,
                actionTag = actionTag
            )
            return id
        }

        override suspend fun deleteConversation(conversationId: Long) {}
        override suspend fun clearAllConversations() {
            conversations.value = emptyList()
            messages.value = emptyList()
        }
    }
}

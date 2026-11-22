package com.example.data.voice.alwayson

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.core.localization.AppLanguage
import com.example.data.ai.AiChatService
import com.example.data.ai.AiStreamEvent
import com.example.data.devicecontrol.DeviceActionResult
import com.example.data.devicecontrol.DeviceCommand
import com.example.data.devicecontrol.DeviceControlRegistry
import com.example.data.devicecontrol.LocalCommandParser
import com.example.data.devicecontrol.ConfirmationDecision
import com.example.data.devicecontrol.SensitiveActionCategory
import com.example.data.devicecontrol.SensitiveActionDetector
import com.example.data.devicecontrol.SensitiveActionRequest
import com.example.data.local.ConversationRepository
import com.example.data.local.MessageRole
import com.example.data.local.UserPreferences
import com.example.data.local.UserPreferencesRepository
import com.example.data.voice.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VoiceMonitorStatus {
    IDLE,
    MONITORING,
    PAUSED,
    WAKE_DETECTED,
    CAPTURING_COMMAND,
    AWAITING_CONFIRMATION,
    THINKING,
    SPEAKING,
    INTERRUPTED,
    PERMISSION_DENIED,
    ERROR;

    val isMicrophoneActivelyUsed: Boolean
        get() = this == MONITORING || this == WAKE_DETECTED || this == CAPTURING_COMMAND

    val isServiceActiveState: Boolean
        get() = this != IDLE && this != PERMISSION_DENIED && this != ERROR
}

data class VoiceMonitorState(
    val status: VoiceMonitorStatus = VoiceMonitorStatus.IDLE,
    val wakeWord: String = WakeWordDetector.DEFAULT_WAKE_WORD,
    val isServiceRunning: Boolean = false,
    val isScreenOn: Boolean = true,
    val isAppInForeground: Boolean = true,
    val hasMicPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isBatteryOptimizationIgnored: Boolean = false,
    val lastTranscript: String? = null,
    val lastReply: String? = null,
    val lastWakeTimestampMs: Long? = null,
    val pendingSensitiveAction: SensitiveActionRequest? = null,
    val activeConversationId: Long? = null,
    val isWakeLockHeld: Boolean = false,
    val statusMessageEn: String = "Always-On listening is off.",
    val statusMessageBn: String = "অলওয়েজ-অন লিসেনিং বন্ধ আছে।"
)

/**
 * Coordinates microphone ownership, local VAD + wake-word detection, bounded speech recognition,
 * sensitive-action confirmation, Gemini/device-control execution, TTS output, and automatic
 * recovery from calls or temporary audio interruptions.
 */
class AudioSessionManager(
    context: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val conversationRepository: ConversationRepository,
    private val aiChatService: AiChatService,
    private val deviceControlRegistry: DeviceControlRegistry,
    private val ttsManager: TtsManager,
    private val audioCaptureManager: AudioCaptureManager = AudioCaptureManager(context),
    private val speechRecognitionManager: SpeechRecognitionManager = SpeechRecognitionManager(context),
    private val vad: VoiceActivityDetector = VoiceActivityDetector(),
    private val wakeWordDetector: WakeWordDetector = WakeWordDetector(),
    private val externalSpeechRecognizer: (suspend (AppLanguage, Boolean) -> SpeechTurnResult)? = null,
    private val externalSpeaker: (suspend (String, AppLanguage) -> Unit)? = null
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    private var turnWakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var monitoringJob: Job? = null
    private var interruptedByAudioFocus = false

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<VoiceMonitorState> = _state.asStateFlow()

    private fun buildInitialState(): VoiceMonitorState {
        return VoiceMonitorState(
            status = VoiceMonitorStatus.IDLE,
            hasMicPermission = hasMicrophonePermission(),
            hasNotificationPermission = hasNotificationPermission(),
            isBatteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(appContext),
            isScreenOn = BatteryOptimizationHelper.isDeviceInteractive(appContext)
        )
    }

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshEnvironmentState() {
        _state.value = _state.value.copy(
            hasMicPermission = hasMicrophonePermission(),
            hasNotificationPermission = hasNotificationPermission(),
            isBatteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(appContext)
        )
    }

    fun setAppForegroundState(inForeground: Boolean) {
        _state.value = _state.value.copy(isAppInForeground = inForeground)
    }

    fun setActiveConversationId(conversationId: Long?) {
        _state.value = _state.value.copy(activeConversationId = conversationId)
    }

    fun onScreenStateChanged(screenOn: Boolean) {
        _state.value = _state.value.copy(isScreenOn = screenOn)
    }

    fun startMonitoring(serviceRunning: Boolean = true) {
        refreshEnvironmentState()
        if (!hasMicrophonePermission()) {
            stopWithPermissionDenied()
            return
        }

        if (monitoringJob?.isActive == true && _state.value.status == VoiceMonitorStatus.MONITORING) {
            _state.value = _state.value.copy(isServiceRunning = serviceRunning)
            return
        }

        monitoringJob?.cancel()
        vad.reset()
        wakeWordDetector.resetCooldown()
        interruptedByAudioFocus = false

        _state.value = _state.value.copy(
            status = VoiceMonitorStatus.MONITORING,
            isServiceRunning = serviceRunning,
            statusMessageEn = "Listening locally for \"${_state.value.wakeWord}\"...",
            statusMessageBn = "\"${_state.value.wakeWord}\" শোনার জন্য লোকাল মনিটরিং চলছে..."
        )

        monitoringJob = scope.launch {
            runMonitoringLoop()
        }
    }

    fun pauseMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        audioCaptureManager.releaseMicrophone()
        speechRecognitionManager.cancelSession()
        releaseTurnWakeLock()
        abandonTransientFocus()

        _state.value = _state.value.copy(
            status = VoiceMonitorStatus.PAUSED,
            statusMessageEn = "Continuous listening is paused.",
            statusMessageBn = "নিরবচ্ছিন্ন শোনা সাময়িকভাবে বিরতিতে আছে।"
        )
    }

    fun resumeMonitoring(serviceRunning: Boolean = _state.value.isServiceRunning) {
        startMonitoring(serviceRunning = serviceRunning)
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        audioCaptureManager.releaseMicrophone()
        speechRecognitionManager.cancelSession()
        ttsManager.stop()
        releaseTurnWakeLock()
        abandonTransientFocus()
        interruptedByAudioFocus = false

        _state.value = _state.value.copy(
            status = VoiceMonitorStatus.IDLE,
            isServiceRunning = false,
            pendingSensitiveAction = null,
            statusMessageEn = "Always-On listening is stopped.",
            statusMessageBn = "অলওয়েজ-অন লিসেনিং বন্ধ করা হয়েছে।"
        )
    }

    fun stopWithPermissionDenied() {
        monitoringJob?.cancel()
        monitoringJob = null
        audioCaptureManager.releaseMicrophone()
        speechRecognitionManager.cancelSession()
        releaseTurnWakeLock()

        _state.value = _state.value.copy(
            status = VoiceMonitorStatus.PERMISSION_DENIED,
            isServiceRunning = false,
            hasMicPermission = false,
            statusMessageEn = "Microphone permission (RECORD_AUDIO) is required to listen.",
            statusMessageBn = "শোনার জন্য মাইক্রোফোন অনুমতি (RECORD_AUDIO) প্রয়োজন।"
        )
    }

    fun reportServiceStartBlocked(reasonEn: String, reasonBn: String) {
        monitoringJob?.cancel()
        monitoringJob = null
        audioCaptureManager.releaseMicrophone()
        releaseTurnWakeLock()

        _state.value = _state.value.copy(
            status = VoiceMonitorStatus.ERROR,
            isServiceRunning = false,
            statusMessageEn = reasonEn,
            statusMessageBn = reasonBn
        )
    }

    fun onAudioInterruptionStarted(reasonEn: String = "Microphone busy (call or another app).", reasonBn: String = "কল বা অন্য অ্যাপের কারণে মাইক্রোফোন সাময়িকভাবে ব্যস্ত।") {
        val currentStatus = _state.value.status
        if (currentStatus == VoiceMonitorStatus.IDLE || currentStatus == VoiceMonitorStatus.PAUSED) return
        interruptedByAudioFocus = true
        audioCaptureManager.releaseMicrophone()
        speechRecognitionManager.cancelSession()
        releaseTurnWakeLock()

        _state.value = _state.value.copy(
            status = VoiceMonitorStatus.INTERRUPTED,
            statusMessageEn = reasonEn,
            statusMessageBn = reasonBn
        )
    }

    fun onAudioInterruptionEnded() {
        if (!interruptedByAudioFocus) return
        interruptedByAudioFocus = false
        if (_state.value.isServiceRunning && _state.value.status == VoiceMonitorStatus.INTERRUPTED) {
            startMonitoring(serviceRunning = true)
        }
    }

    fun isPhoneCallOrCommActive(): Boolean {
        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private suspend fun runMonitoringLoop() {
        var backoffMs = 2_000L

        while (scope.isActive && _state.value.isServiceRunning) {
            val prefs = preferencesRepository.preferencesFlow.first()
            _state.value = _state.value.copy(wakeWord = prefs.wakeWord)

            if (!hasMicrophonePermission()) {
                stopWithPermissionDenied()
                return
            }

            if (isPhoneCallOrCommActive() || interruptedByAudioFocus) {
                audioCaptureManager.releaseMicrophone()
                _state.value = _state.value.copy(
                    status = VoiceMonitorStatus.INTERRUPTED,
                    statusMessageEn = "Paused during active call or audio communication.",
                    statusMessageBn = "কল বা অডিও যোগাযোগের সময় শোনা বিরতিতে আছে।"
                )
                delay(2_000L)
                continue
            }

            _state.value = _state.value.copy(
                status = VoiceMonitorStatus.MONITORING,
                statusMessageEn = if (_state.value.isScreenOn) {
                    "Listening locally for \"${prefs.wakeWord}\"..."
                } else {
                    "Screen off • Monitoring locally where Android permits..."
                },
                statusMessageBn = if (_state.value.isScreenOn) {
                    "\"${prefs.wakeWord}\" শোনার জন্য লোকাল মনিটরিং চলছে..."
                } else {
                    "স্ক্রিন অফ • অ্যান্ড্রয়েডের অনুমতি অনুযায়ী লোকাল মনিটরিং চলছে..."
                }
            )

            vad.reset()
            var candidateSegmentReady = false
            var segmentStartMs = 0L
            var peakRms = 0.0
            var speechFrames = 0

            val captureOutcome = audioCaptureManager.captureLoop { samples, count ->
                if (!scope.isActive || _state.value.status != VoiceMonitorStatus.MONITORING) {
                    return@captureLoop false
                }
                if (isPhoneCallOrCommActive() || interruptedByAudioFocus) {
                    return@captureLoop false
                }

                val analysis = vad.processFrame(samples, count)
                if (analysis.speechStarted) {
                    segmentStartMs = System.currentTimeMillis()
                    peakRms = analysis.rmsAmplitude
                    speechFrames = 1
                } else if (analysis.isSpeechActive) {
                    if (analysis.rmsAmplitude > peakRms) peakRms = analysis.rmsAmplitude
                    speechFrames++
                    val elapsed = System.currentTimeMillis() - segmentStartMs
                    // Cap single candidate burst to 2.6 seconds so local verification stays snappy.
                    if (elapsed >= 2_400L) {
                        candidateSegmentReady = true
                        return@captureLoop false
                    }
                } else if (analysis.speechEnded) {
                    val durationMs = System.currentTimeMillis() - segmentStartMs
                    if (wakeWordDetector.isPlausibleWakeAcousticCandidate(durationMs, peakRms, speechFrames)) {
                        candidateSegmentReady = true
                        return@captureLoop false
                    }
                    segmentStartMs = 0L
                    peakRms = 0.0
                    speechFrames = 0
                }
                true
            }

            if (_state.value.status == VoiceMonitorStatus.PAUSED || _state.value.status == VoiceMonitorStatus.IDLE) {
                return
            }

            when (captureOutcome) {
                AudioCaptureOutcome.PermissionDenied -> {
                    stopWithPermissionDenied()
                    return
                }
                is AudioCaptureOutcome.MicrophoneInterrupted -> {
                    val screenNoteEn = if (!_state.value.isScreenOn) {
                        "Screen-off microphone access was paused by Android or another app. Waiting to recover..."
                    } else {
                        "Microphone temporarily interrupted. Recovering automatically..."
                    }
                    val screenNoteBn = if (!_state.value.isScreenOn) {
                        "স্ক্রিন বন্ধ থাকায় অ্যান্ড্রয়েড বা অন্য অ্যাপ মাইক্রোফোন থামিয়েছে। পুনরায় চেষ্টা চলছে..."
                    } else {
                        "মাইক্রোফোন সাময়িকভাবে বিচ্ছিন্ন হয়েছে। স্বয়ংক্রিয়ভাবে পুনরুদ্ধার হচ্ছে..."
                    }
                    _state.value = _state.value.copy(
                        status = VoiceMonitorStatus.INTERRUPTED,
                        statusMessageEn = screenNoteEn,
                        statusMessageBn = screenNoteBn
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                    continue
                }
                is AudioCaptureOutcome.Error -> {
                    _state.value = _state.value.copy(
                        status = VoiceMonitorStatus.ERROR,
                        statusMessageEn = captureOutcome.reason,
                        statusMessageBn = captureOutcome.reason
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                    continue
                }
                AudioCaptureOutcome.StoppedNormally -> {
                    backoffMs = 2_000L
                }
            }

            if (!candidateSegmentReady || !_state.value.isServiceRunning) continue

            // Stage 2: Briefly verify whether the spoken burst contained the wake word.
            audioCaptureManager.releaseMicrophone()
            val wakeTurn = runSpeechTurn(language = prefs.language, preferOffline = true)
            if (wakeTurn is SpeechTurnResult.Success) {
                val matchedResult = wakeTurn.alternatives.firstNotNullOfOrNull { alt ->
                    val res = wakeWordDetector.matchWakePhrase(alt, prefs.wakeWord)
                    if (res.matched) res else null
                }
                if (matchedResult != null) {
                    handleWakeWordTriggered(
                        prefs = prefs,
                        trailingCommand = matchedResult.trailingCommand
                    )
                    if (!prefs.resumeMonitoringAfterReply) {
                        pauseMonitoring()
                        return
                    }
                }
            } else if (wakeTurn is SpeechTurnResult.PermissionDenied) {
                stopWithPermissionDenied()
                return
            }
        }
    }

    /**
     * Handles a confirmed wake-word detection:
     * 1. Acquires a short bounded partial wake lock for the active turn only.
     * 2. Responds naturally with "জি বস, বলুন।" (unless user disabled voice responses).
     * 3. Captures the user's command (if not already spoken inline after the wake word).
     * 4. Executes local command or Gemini AI turn, saves to Room, and speaks reply.
     * 5. Releases wake lock and returns to monitoring according to [UserPreferences.resumeMonitoringAfterReply].
     */
    suspend fun handleWakeWordTriggered(
        prefs: UserPreferences? = null,
        trailingCommand: String = "",
        injectedCommandTranscript: String? = null
    ) {
        val currentPrefs = prefs ?: preferencesRepository.preferencesFlow.first()
        audioCaptureManager.releaseMicrophone()
        acquireTurnWakeLock()
        requestTransientFocus()

        try {
            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(
                status = VoiceMonitorStatus.WAKE_DETECTED,
                lastWakeTimestampMs = now,
                lastReply = WakeWordDetector.DEFAULT_WAKE_ACK_BENGALI,
                statusMessageEn = WakeWordDetector.DEFAULT_WAKE_ACK_BENGALI,
                statusMessageBn = WakeWordDetector.DEFAULT_WAKE_ACK_BENGALI
            )

            // Speak "জি বস, বলুন।" and wait before opening the command microphone.
            if (trailingCommand.isBlank() && injectedCommandTranscript.isNullOrBlank()) {
                if (currentPrefs.alwaysOnVoiceResponseEnabled) {
                    speakOutput(WakeWordDetector.DEFAULT_WAKE_ACK_BENGALI, AppLanguage.BENGALI)
                }
            }

            val commandText = when {
                !injectedCommandTranscript.isNullOrBlank() -> injectedCommandTranscript.trim()
                trailingCommand.isNotBlank() -> trailingCommand.trim()
                else -> {
                    _state.value = _state.value.copy(
                        status = VoiceMonitorStatus.CAPTURING_COMMAND,
                        statusMessageEn = "Listening for your command...",
                        statusMessageBn = "আপনার নির্দেশ শুনছি..."
                    )
                    when (val cmdTurn = runSpeechTurn(language = currentPrefs.language, preferOffline = false)) {
                        is SpeechTurnResult.Success -> cmdTurn.bestTranscript
                        SpeechTurnResult.PermissionDenied -> {
                            stopWithPermissionDenied()
                            return
                        }
                        else -> ""
                    }
                }
            }

            if (commandText.isBlank()) {
                return
            }

            processRecognizedCommand(commandText, currentPrefs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Error handling wake-word turn", e)
            _state.value = _state.value.copy(
                status = VoiceMonitorStatus.ERROR,
                statusMessageEn = e.localizedMessage ?: "Voice assistant turn failed.",
                statusMessageBn = e.localizedMessage ?: "ভয়েস কমান্ড প্রক্রিয়া করা যায়নি।"
            )
        } finally {
            abandonTransientFocus()
            releaseTurnWakeLock()
        }
    }

    /**
     * Routes a recognized voice command through:
     * 1. Pending sensitive action confirmation gate (if waiting for Yes/No).
     * 2. Sensitive action detection (messages, payments, calls) -> requests explicit confirmation.
     * 3. Local device control (`Flashlight`, `Calculator`, `Maps`, `Camera`, `All Apps`).
     * 4. Gemini AI (`FirebaseAiChatService`) with Room conversational history.
     */
    suspend fun processRecognizedCommand(
        rawCommand: String,
        prefsOverride: UserPreferences? = null
    ) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        val prefs = prefsOverride ?: preferencesRepository.preferencesFlow.first()
        val pendingSensitive = _state.value.pendingSensitiveAction

        // 1. Resolve pending sensitive confirmation if active
        if (pendingSensitive != null) {
            when (SensitiveActionDetector.parseConfirmation(command)) {
                ConfirmationDecision.CONFIRMED -> {
                    _state.value = _state.value.copy(pendingSensitiveAction = null)
                    executeConfirmedOrStandardTurn(
                        prompt = pendingSensitive.originalPrompt,
                        prefs = prefs
                    )
                    return
                }
                ConfirmationDecision.CANCELLED -> {
                    _state.value = _state.value.copy(pendingSensitiveAction = null)
                    val cancelReply = if (prefs.language == AppLanguage.BENGALI) {
                        "ঠিক আছে, সংবেদনশীল কাজটি বাতিল করা হয়েছে।"
                    } else {
                        "Understood, cancelled that action."
                    }
                    persistAndSpeakAssistantTurn(command, cancelReply, prefs, actionTag = null)
                    return
                }
                ConfirmationDecision.UNDECIDED -> {
                    // Fall through: user might be issuing a new request or re-prompt confirmation
                    _state.value = _state.value.copy(pendingSensitiveAction = null)
                }
            }
        }

        // 2. Check if this command is a sensitive action requiring confirmation
        val sensitive = SensitiveActionDetector.detect(command)
        if (sensitive != null) {
            _state.value = _state.value.copy(
                status = VoiceMonitorStatus.AWAITING_CONFIRMATION,
                pendingSensitiveAction = sensitive,
                lastTranscript = command
            )
            val confirmPrompt = buildConfirmationPrompt(sensitive, prefs.language)
            persistAndSpeakAssistantTurn(command, confirmPrompt, prefs, actionTag = null)
            return
        }

        // 3 & 4. Standard local device control or Gemini turn
        executeConfirmedOrStandardTurn(prompt = command, prefs = prefs)
    }

    suspend fun resolvePendingSensitiveAction(confirm: Boolean) {
        val pending = _state.value.pendingSensitiveAction ?: return
        val prefs = preferencesRepository.preferencesFlow.first()
        _state.value = _state.value.copy(pendingSensitiveAction = null)

        if (confirm) {
            executeConfirmedOrStandardTurn(pending.originalPrompt, prefs)
        } else {
            val cancelReply = if (prefs.language == AppLanguage.BENGALI) {
                "ঠিক আছে, অনুরোধটি বাতিল করা হয়েছে।"
            } else {
                "Cancelled as requested."
            }
            persistAndSpeakAssistantTurn(
                userText = if (prefs.language == AppLanguage.BENGALI) "বাতিল" else "Cancel",
                assistantText = cancelReply,
                prefs = prefs,
                actionTag = null
            )
        }
    }

    private suspend fun executeConfirmedOrStandardTurn(
        prompt: String,
        prefs: UserPreferences
    ) {
        _state.value = _state.value.copy(
            status = VoiceMonitorStatus.THINKING,
            lastTranscript = prompt,
            statusMessageEn = "Processing: \"$prompt\"",
            statusMessageBn = "প্রক্রিয়া করা হচ্ছে: \"$prompt\""
        )

        val localCommand = LocalCommandParser.parse(prompt)
        if (localCommand != null) {
            val cachedApps = if (localCommand is DeviceCommand.OpenAppByName) {
                deviceControlRegistry.loadAllLaunchableApps()
            } else {
                null
            }
            val result = deviceControlRegistry.execute(localCommand, cachedApps)
            val reply = result.localizedMessage(prefs.language)
            val tag = (result as? DeviceActionResult.Success)?.actionTag ?: localCommand.toActionTag()
            persistAndSpeakAssistantTurn(prompt, reply, prefs, actionTag = tag)
            return
        }

        val conversationId = ensureConversationId(prompt, prefs.language)
        val history = conversationRepository.getMessagesOnce(conversationId)
        conversationRepository.addMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = prompt
        )

        var replyText = ""
        var isError = false
        try {
            aiChatService.sendMessageStream(
                prompt = prompt,
                history = history,
                language = prefs.language,
                modelOption = prefs.selectedModel
            ).collect { event ->
                if (event is AiStreamEvent.Completed) {
                    replyText = event.fullText
                } else if (event is AiStreamEvent.Partial && replyText.isEmpty()) {
                    replyText = event.accumulatedText
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini voice turn failed", e)
            isError = true
            replyText = if (prefs.language == AppLanguage.BENGALI) {
                "দুঃখিত, এই মুহূর্তে জেমিনি থেকে উত্তর পাওয়া যায়নি।"
            } else {
                "Sorry, I couldn't reach Gemini right now."
            }
        }

        val finalReply = replyText.trim().ifBlank {
            if (prefs.language == AppLanguage.BENGALI) "কোনো উত্তর পাওয়া যায়নি।" else "No response received."
        }
        conversationRepository.addMessage(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = finalReply,
            isError = isError
        )

        _state.value = _state.value.copy(lastReply = finalReply)
        if (prefs.alwaysOnVoiceResponseEnabled) {
            _state.value = _state.value.copy(
                status = VoiceMonitorStatus.SPEAKING,
                statusMessageEn = "Speaking response...",
                statusMessageBn = "উত্তর বলা হচ্ছে..."
            )
            speakOutput(finalReply, prefs.language)
        }
    }

    private suspend fun persistAndSpeakAssistantTurn(
        userText: String,
        assistantText: String,
        prefs: UserPreferences,
        actionTag: String?
    ) {
        val conversationId = ensureConversationId(userText, prefs.language)
        conversationRepository.addMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userText
        )
        conversationRepository.addMessage(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = assistantText,
            actionTag = actionTag
        )
        _state.value = _state.value.copy(lastReply = assistantText)
        if (prefs.alwaysOnVoiceResponseEnabled) {
            _state.value = _state.value.copy(
                status = VoiceMonitorStatus.SPEAKING,
                statusMessageEn = "Speaking response...",
                statusMessageBn = "উত্তর বলা হচ্ছে..."
            )
            speakOutput(assistantText, prefs.language)
        }
        val pending = _state.value.pendingSensitiveAction
        if (pending != null) {
            _state.value = _state.value.copy(
                status = VoiceMonitorStatus.AWAITING_CONFIRMATION,
                statusMessageEn = assistantText,
                statusMessageBn = assistantText
            )
        } else if (_state.value.isServiceRunning && _state.value.status == VoiceMonitorStatus.SPEAKING) {
            _state.value = _state.value.copy(
                status = VoiceMonitorStatus.MONITORING,
                statusMessageEn = assistantText,
                statusMessageBn = assistantText
            )
        }
    }

    private suspend fun ensureConversationId(titleSource: String, language: AppLanguage): Long {
        val currentId = _state.value.activeConversationId
        if (currentId != null) return currentId
        val created = conversationRepository.createConversation(
            title = titleSource,
            languageCode = language.code
        )
        _state.value = _state.value.copy(activeConversationId = created)
        return created
    }

    private suspend fun runSpeechTurn(language: AppLanguage, preferOffline: Boolean): SpeechTurnResult {
        externalSpeechRecognizer?.let { return it(language, preferOffline) }
        return speechRecognitionManager.recognizeSingleTurn(
            language = language,
            preferOffline = preferOffline
        )
    }

    private suspend fun speakOutput(text: String, language: AppLanguage) {
        externalSpeaker?.let {
            it(text, language)
            return
        }
        ttsManager.speakAndAwait(text = text, preferredLanguage = language)
    }

    private fun buildConfirmationPrompt(
        request: SensitiveActionRequest,
        language: AppLanguage
    ): String {
        return when (language) {
            AppLanguage.BENGALI -> when (request.category) {
                SensitiveActionCategory.PAYMENT ->
                    "পেমেন্ট বা টাকা পাঠানোর অনুরোধ শনাক্ত হয়েছে (\"${request.originalPrompt}\")। এগিয়ে যেতে \"নিশ্চিত\" বলুন বা কনফার্ম চাপুন, অথবা বাতিল করুন।"
                SensitiveActionCategory.MESSAGING ->
                    "মেসেজ পাঠানোর আগে আপনার অনুমতি প্রয়োজন (\"${request.originalPrompt}\")। পাঠাতে চাইলে \"হ্যাঁ\" বলুন অথবা বাতিল করুন।"
                SensitiveActionCategory.CALLING ->
                    "কল করার আগে আপনার নিশ্চিতকরণ প্রয়োজন (\"${request.originalPrompt}\")। চালিয়ে যেতে \"হ্যাঁ\" বলুন অথবা বাতিল করুন।"
            }
            AppLanguage.ENGLISH -> when (request.category) {
                SensitiveActionCategory.PAYMENT ->
                    "Payment request detected (\"${request.originalPrompt}\"). Please say \"Confirm\" or tap Confirm to proceed, or Cancel."
                SensitiveActionCategory.MESSAGING ->
                    "Confirmation required before sending a message (\"${request.originalPrompt}\"). Say \"Yes\" to continue or \"Cancel\"."
                SensitiveActionCategory.CALLING ->
                    "Confirmation required before placing a call (\"${request.originalPrompt}\"). Say \"Yes\" to proceed or \"Cancel\"."
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireTurnWakeLock() {
        val pm = powerManager ?: return
        runCatching {
            val lock = turnWakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                turnWakeLock = this
            }
            if (!lock.isHeld) {
                lock.acquire(MAX_TURN_WAKE_LOCK_MS)
            }
            _state.value = _state.value.copy(isWakeLockHeld = lock.isHeld)
        }.onFailure { e ->
            Log.w(TAG, "Unable to acquire partial wake lock", e)
        }
    }

    private fun releaseTurnWakeLock() {
        runCatching {
            val lock = turnWakeLock
            if (lock != null && lock.isHeld) {
                lock.release()
            }
            _state.value = _state.value.copy(isWakeLockHeld = false)
        }.onFailure { e ->
            Log.w(TAG, "Error releasing partial wake lock", e)
            _state.value = _state.value.copy(isWakeLockHeld = false)
        }
    }

    private fun requestTransientFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        onAudioInterruptionStarted()
                    } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                        onAudioInterruptionEnded()
                    }
                }
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonTransientFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    companion object {
        private const val TAG = "AudioSessionManager"
        private const val WAKE_LOCK_TAG = "AriaAI:VoiceTurnWakeLock"
        private const val MAX_TURN_WAKE_LOCK_MS = 20_000L
    }
}

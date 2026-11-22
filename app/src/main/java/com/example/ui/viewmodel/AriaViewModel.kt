package com.example.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.di.AppContainer
import com.example.core.localization.AppLanguage
import com.example.core.localization.stringsFor
import com.example.data.accessibility.AccessibilityQuickAction
import com.example.data.accessibility.AccessibilityServiceState
import com.example.data.accessibility.AriaAccessibilityBridge
import com.example.data.ai.AiChatService
import com.example.data.ai.AiStreamEvent
import com.example.data.ai.EmptyAiResponseException
import com.example.data.ai.GeminiModelOption
import com.example.data.devicecontrol.DeviceActionResult
import com.example.data.devicecontrol.DeviceCommand
import com.example.data.devicecontrol.DeviceControlRegistry
import com.example.data.devicecontrol.FlashlightState
import com.example.data.devicecontrol.FuzzyAppMatcher
import com.example.data.devicecontrol.InstalledAppInfo
import com.example.data.devicecontrol.LocalCommandParser
import com.example.data.devicecontrol.SensitiveActionDetector
import com.example.data.devicecontrol.SupportedAppId
import com.example.data.devicecontrol.SupportedAppInfo
import com.example.data.local.ConversationEntity
import com.example.data.local.ConversationRepository
import com.example.data.local.MessageEntity
import com.example.data.local.MessageRole
import com.example.data.local.UserPreferences
import com.example.data.local.UserPreferencesRepository
import com.example.data.voice.GeminiLiveSessionClient
import com.example.data.voice.LiveConversationUiState
import com.example.data.voice.LiveSessionState
import com.example.data.voice.TtsManager
import com.example.data.voice.VoiceInputManager
import com.example.data.voice.VoiceRecognitionEvent
import com.example.data.voice.alwayson.AudioSessionManager
import com.example.data.voice.alwayson.VoiceMonitorState
import com.example.data.voice.alwayson.VoiceMonitorStatus
import com.example.data.voice.alwayson.VoiceMonitoringService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AiGenerationState {
    data object Idle : AiGenerationState
    data object Loading : AiGenerationState
    data class Streaming(val partialText: String) : AiGenerationState
    data class Error(val message: String, val failedPrompt: String) : AiGenerationState
}

enum class AppListFilter {
    ALL,
    USER_ONLY,
    SYSTEM_ONLY
}

@OptIn(ExperimentalCoroutinesApi::class)
class AriaViewModel(
    private val appContext: Context,
    private val conversationRepository: ConversationRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val aiChatService: AiChatService,
    private val deviceControlRegistry: DeviceControlRegistry,
    private val voiceInputManager: VoiceInputManager,
    private val liveSessionClient: GeminiLiveSessionClient,
    private val ttsManager: TtsManager,
    private val audioSessionManager: AudioSessionManager
) : ViewModel() {

    val userPreferences: StateFlow<UserPreferences> = preferencesRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences()
        )

    val conversations: StateFlow<List<ConversationEntity>> = conversationRepository
        .observeConversations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _activeConversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else conversationRepository.observeMessages(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _generationState = MutableStateFlow<AiGenerationState>(AiGenerationState.Idle)
    val generationState: StateFlow<AiGenerationState> = _generationState.asStateFlow()

    private val _inputDraft = MutableStateFlow("")
    val inputDraft: StateFlow<String> = _inputDraft.asStateFlow()

    val flashlightState: StateFlow<FlashlightState> = deviceControlRegistry.flashlightState

    private val _supportedApps = MutableStateFlow<List<SupportedAppInfo>>(emptyList())
    val supportedApps: StateFlow<List<SupportedAppInfo>> = _supportedApps.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _allAppsSearchQuery = MutableStateFlow("")
    val allAppsSearchQuery: StateFlow<String> = _allAppsSearchQuery.asStateFlow()

    private val _allAppsFilter = MutableStateFlow(AppListFilter.ALL)
    val allAppsFilter: StateFlow<AppListFilter> = _allAppsFilter.asStateFlow()

    val filteredInstalledApps: StateFlow<List<InstalledAppInfo>> = combine(
        _installedApps,
        _allAppsSearchQuery,
        _allAppsFilter
    ) { apps, query, filter ->
        val categoryFiltered = when (filter) {
            AppListFilter.ALL -> apps
            AppListFilter.USER_ONLY -> apps.filter { !it.isSystemApp }
            AppListFilter.SYSTEM_ONLY -> apps.filter { it.isSystemApp }
        }
        FuzzyAppMatcher.filterApps(query, categoryFiltered)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val accessibilityState: StateFlow<AccessibilityServiceState> = AriaAccessibilityBridge.state

    val voiceEvents: StateFlow<VoiceRecognitionEvent> = voiceInputManager.events
    val liveSessionState: StateFlow<LiveSessionState> = liveSessionClient.state
    val liveConversationState: StateFlow<LiveConversationUiState> = liveSessionClient.liveConversationState
    val speakingMessageId: StateFlow<Long?> = ttsManager.speakingMessageId
    val voiceMonitorState: StateFlow<VoiceMonitorState> = audioSessionManager.state

    private val _statusNotice = MutableStateFlow<String?>(null)
    val statusNotice: StateFlow<String?> = _statusNotice.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var resumeAlwaysOnAfterLive: Boolean = false

    init {
        refreshDeviceControls()
        refreshInstalledApps()
        refreshAccessibilityStatus()
        refreshVoiceMonitorEnvironment()
        observeVoiceToLiveBridge()
        observeAlwaysOnConversationSync()
    }

    private fun observeAlwaysOnConversationSync() {
        viewModelScope.launch {
            audioSessionManager.state.collect { monitorState ->
                val bgConvId = monitorState.activeConversationId
                if (_activeConversationId.value == null && bgConvId != null) {
                    _activeConversationId.value = bgConvId
                }
            }
        }
    }

    private fun observeVoiceToLiveBridge() {
        viewModelScope.launch {
            voiceInputManager.events.collect { event ->
                when (event) {
                    VoiceRecognitionEvent.Idle -> liveSessionClient.resetToReady()
                    VoiceRecognitionEvent.Listening -> liveSessionClient.updateListeningProgress("", 2f)
                    is VoiceRecognitionEvent.PartialResult ->
                        liveSessionClient.updateListeningProgress(event.text, event.rmsDb)
                    is VoiceRecognitionEvent.FinalResult ->
                        liveSessionClient.markProcessing(event.text)
                    is VoiceRecognitionEvent.Error -> {
                        val strings = stringsFor(userPreferences.value.language)
                        val msg = when {
                            event.isUnavailable -> strings.speechNotAvailable
                            event.isNoMatch -> strings.speechNoMatch
                            else -> strings.speechNoMatch
                        }
                        liveSessionClient.markError(msg)
                    }
                }
            }
        }
    }

    fun updateInputDraft(value: String) {
        _inputDraft.value = value
    }

    fun clearStatusNotice() {
        _statusNotice.value = null
    }

    fun dismissError() {
        if (_generationState.value is AiGenerationState.Error) {
            _generationState.value = AiGenerationState.Idle
        }
    }

    fun selectConversation(conversationId: Long) {
        activeGenerationJob?.cancel()
        _generationState.value = AiGenerationState.Idle
        _activeConversationId.value = conversationId
        audioSessionManager.setActiveConversationId(conversationId)
        ttsManager.stop()
    }

    fun startNewChat() {
        activeGenerationJob?.cancel()
        _generationState.value = AiGenerationState.Idle
        _activeConversationId.value = null
        audioSessionManager.setActiveConversationId(null)
        ttsManager.stop()
    }

    fun sendMessage(rawPrompt: String = _inputDraft.value) {
        val trimmed = rawPrompt.trim()
        if (trimmed.isEmpty()) return
        if (_generationState.value is AiGenerationState.Loading ||
            _generationState.value is AiGenerationState.Streaming
        ) {
            return
        }

        _inputDraft.value = ""
        _generationState.value = AiGenerationState.Loading

        activeGenerationJob = viewModelScope.launch {
            val prefs = userPreferences.value
            val lang = prefs.language

            val conversationId = _activeConversationId.value ?: run {
                val createdId = conversationRepository.createConversation(
                    title = trimmed,
                    languageCode = lang.code
                )
                _activeConversationId.value = createdId
                audioSessionManager.setActiveConversationId(createdId)
                createdId
            }

            if (SensitiveActionDetector.detect(trimmed) != null ||
                audioSessionManager.state.value.pendingSensitiveAction != null
            ) {
                audioSessionManager.processRecognizedCommand(trimmed, prefs)
                _generationState.value = AiGenerationState.Idle
                return@launch
            }

            val existingHistory = conversationRepository.getMessagesOnce(conversationId)
            conversationRepository.addMessage(
                conversationId = conversationId,
                role = MessageRole.USER,
                content = trimmed
            )

            // Check if user requested a modular local device action (Flashlight / Open any installed App)
            val localCommand = LocalCommandParser.parse(trimmed)
            if (localCommand != null) {
                val cachedApps = if (localCommand is DeviceCommand.OpenAppByName && _installedApps.value.isEmpty()) {
                    val scanned = deviceControlRegistry.loadAllLaunchableApps()
                    _installedApps.value = scanned
                    scanned
                } else {
                    _installedApps.value.takeIf { it.isNotEmpty() }
                }

                val actionResult = deviceControlRegistry.execute(localCommand, cachedApps)
                val replyText = actionResult.localizedMessage(lang)
                val tag = when (actionResult) {
                    is DeviceActionResult.Success -> actionResult.actionTag
                    else -> localCommand.toActionTag()
                }
                val msgId = conversationRepository.addMessage(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = replyText,
                    isError = actionResult is DeviceActionResult.Failure,
                    actionTag = tag
                )
                _generationState.value = AiGenerationState.Idle
                if (prefs.autoSpeakReplies) {
                    ttsManager.speak(msgId, replyText, lang)
                }
                return@launch
            }

            requestGeminiReply(
                conversationId = conversationId,
                prompt = trimmed,
                historyBeforePrompt = existingHistory,
                prefs = prefs
            )
        }
    }

    fun retryLastFailed() {
        val errorState = _generationState.value as? AiGenerationState.Error ?: return
        val conversationId = _activeConversationId.value ?: return
        val prefs = userPreferences.value

        _generationState.value = AiGenerationState.Loading
        activeGenerationJob = viewModelScope.launch {
            val fullHistory = conversationRepository.getMessagesOnce(conversationId)
            val historyBeforePrompt = if (fullHistory.isNotEmpty() &&
                fullHistory.last().role == MessageRole.USER &&
                fullHistory.last().content == errorState.failedPrompt
            ) {
                fullHistory.dropLast(1)
            } else {
                fullHistory
            }
            requestGeminiReply(
                conversationId = conversationId,
                prompt = errorState.failedPrompt,
                historyBeforePrompt = historyBeforePrompt,
                prefs = prefs
            )
        }
    }

    private suspend fun requestGeminiReply(
        conversationId: Long,
        prompt: String,
        historyBeforePrompt: List<MessageEntity>,
        prefs: UserPreferences
    ) {
        val strings = stringsFor(prefs.language)
        if (!hasNetworkConnection()) {
            _generationState.value = AiGenerationState.Error(
                message = strings.errorNetwork,
                failedPrompt = prompt
            )
            return
        }

        try {
            var finalReply = ""
            aiChatService.sendMessageStream(
                prompt = prompt,
                history = historyBeforePrompt,
                language = prefs.language,
                modelOption = prefs.selectedModel
            ).collect { event ->
                when (event) {
                    is AiStreamEvent.Partial -> {
                        _generationState.value = AiGenerationState.Streaming(event.accumulatedText)
                    }
                    is AiStreamEvent.Completed -> {
                        finalReply = event.fullText
                    }
                }
            }

            if (finalReply.isBlank()) {
                _generationState.value = AiGenerationState.Error(
                    message = strings.errorEmptyResponse,
                    failedPrompt = prompt
                )
            } else {
                val savedId = conversationRepository.addMessage(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = finalReply
                )
                _generationState.value = AiGenerationState.Idle
                if (prefs.autoSpeakReplies) {
                    ttsManager.speak(savedId, finalReply, prefs.language)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI generation timeout", e)
            _generationState.value = AiGenerationState.Error(
                message = strings.errorTimeout,
                failedPrompt = prompt
            )
        } catch (e: EmptyAiResponseException) {
            Log.e(TAG, "AI empty response", e)
            _generationState.value = AiGenerationState.Error(
                message = strings.errorEmptyResponse,
                failedPrompt = prompt
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI generation failed", e)
            val detail = e.localizedMessage?.takeIf { it.isNotBlank() } ?: strings.errorGeneric
            _generationState.value = AiGenerationState.Error(
                message = detail,
                failedPrompt = prompt
            )
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                _activeConversationId.value = null
                _generationState.value = AiGenerationState.Idle
            }
            conversationRepository.deleteConversation(conversationId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            activeGenerationJob?.cancel()
            _activeConversationId.value = null
            _generationState.value = AiGenerationState.Idle
            conversationRepository.clearAllConversations()
            val strings = stringsFor(userPreferences.value.language)
            _statusNotice.value = strings.historyCleared
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            preferencesRepository.setLanguage(language)
        }
    }

    fun toggleLanguage() {
        val next = if (userPreferences.value.language == AppLanguage.ENGLISH) {
            AppLanguage.BENGALI
        } else {
            AppLanguage.ENGLISH
        }
        setLanguage(next)
    }

    fun setModel(model: GeminiModelOption) {
        viewModelScope.launch {
            preferencesRepository.setModel(model)
        }
    }

    fun setAutoSpeakReplies(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoSpeakReplies(enabled)
            if (!enabled) ttsManager.stop()
        }
    }

    fun toggleSpeakMessage(message: MessageEntity) {
        ttsManager.speak(
            messageId = message.id,
            text = message.content,
            preferredLanguage = userPreferences.value.language
        )
    }

    fun refreshDeviceControls() {
        _supportedApps.value = deviceControlRegistry.getSupportedApps()
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            try {
                _installedApps.value = deviceControlRegistry.loadAllLaunchableApps()
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun updateAllAppsSearchQuery(query: String) {
        _allAppsSearchQuery.value = query
    }

    fun updateAllAppsFilter(filter: AppListFilter) {
        _allAppsFilter.value = filter
    }

    fun launchInstalledApp(app: InstalledAppInfo) {
        val result = deviceControlRegistry.execute(
            DeviceCommand.OpenPackage(
                packageName = app.packageName,
                displayName = app.appName
            )
        )
        if (result !is DeviceActionResult.Success) {
            _statusNotice.value = result.localizedMessage(userPreferences.value.language)
        }
    }

    fun toggleFlashlight() {
        val result = deviceControlRegistry.execute(DeviceCommand.ToggleFlashlight)
        _statusNotice.value = result.localizedMessage(userPreferences.value.language)
    }

    fun launchSupportedApp(appId: SupportedAppId) {
        val result = deviceControlRegistry.execute(DeviceCommand.OpenApp(appId))
        if (result !is DeviceActionResult.Success) {
            _statusNotice.value = result.localizedMessage(userPreferences.value.language)
        }
    }

    fun executeActionTag(tag: String) {
        val command = DeviceCommand.fromActionTag(tag) ?: return
        val result = deviceControlRegistry.execute(
            command = command,
            cachedInstalledApps = _installedApps.value.takeIf { it.isNotEmpty() }
        )
        _statusNotice.value = result.localizedMessage(userPreferences.value.language)
    }

    fun refreshAccessibilityStatus() {
        AriaAccessibilityBridge.refreshSystemStatus(appContext)
    }

    fun triggerAuthorizedAccessibilityAction(action: AccessibilityQuickAction) {
        val strings = stringsFor(userPreferences.value.language)
        val status = AriaAccessibilityBridge.state.value
        if (!status.isServiceConnected) {
            _statusNotice.value = strings.accessibilityRequiredNotice
            return
        }
        val executed = AriaAccessibilityBridge.performAuthorizedGlobalAction(action)
        _statusNotice.value = if (executed) {
            strings.accessibilityActionSuccess
        } else {
            strings.accessibilityActionFailed
        }
    }

    fun refreshLiveCapabilities() {
        liveSessionClient.refreshCapabilityState()
    }

    fun refreshVoiceMonitorEnvironment() {
        audioSessionManager.refreshEnvironmentState()
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        audioSessionManager.setAppForegroundState(inForeground)
    }

    fun startAlwaysOnListening() {
        viewModelScope.launch {
            preferencesRepository.setAlwaysOnListeningEnabled(true)
            VoiceMonitoringService.start(appContext)
        }
    }

    fun pauseAlwaysOnListening() {
        VoiceMonitoringService.pause(appContext)
    }

    fun resumeAlwaysOnListening() {
        VoiceMonitoringService.resume(appContext)
    }

    fun stopAlwaysOnListening() {
        viewModelScope.launch {
            preferencesRepository.setAlwaysOnListeningEnabled(false)
            VoiceMonitoringService.stop(appContext)
        }
    }

    fun restartAlwaysOnListening() {
        viewModelScope.launch {
            preferencesRepository.setAlwaysOnListeningEnabled(true)
            VoiceMonitoringService.restart(appContext)
        }
    }

    fun updateWakeWord(wakeWord: String) {
        viewModelScope.launch {
            preferencesRepository.setWakeWord(wakeWord)
            val strings = stringsFor(userPreferences.value.language)
            _statusNotice.value = strings.wakeWordSavedNotice
        }
    }

    fun setAlwaysOnVoiceResponse(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAlwaysOnVoiceResponseEnabled(enabled)
        }
    }

    fun setResumeMonitoringAfterReply(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setResumeMonitoringAfterReply(enabled)
        }
    }

    fun resolveSensitiveAction(confirm: Boolean) {
        viewModelScope.launch {
            audioSessionManager.resolvePendingSensitiveAction(confirm)
        }
    }

    fun startVoiceListening() {
        if (liveSessionClient.liveConversationState.value.isSessionActive) {
            liveSessionClient.endConversation()
        }
        voiceInputManager.startListening(userPreferences.value.language)
    }

    fun stopVoiceListening() {
        voiceInputManager.stopListening()
    }

    fun resetVoiceSession() {
        voiceInputManager.resetState()
        liveSessionClient.resetToReady()
    }

    fun startLiveConversation() {
        // Release dictation / TTS / Always-On mic cleanly before opening full-duplex Live audio
        voiceInputManager.resetState()
        ttsManager.stop()

        val monitor = audioSessionManager.state.value
        if (monitor.isServiceRunning && monitor.status != VoiceMonitorStatus.PAUSED) {
            resumeAlwaysOnAfterLive = true
            VoiceMonitoringService.pause(appContext)
        } else {
            resumeAlwaysOnAfterLive = false
        }

        val prefs = userPreferences.value
        val lang = prefs.language
        val strings = stringsFor(lang)
        val recentHistory = messages.value

        liveSessionClient.startLiveConversation(
            language = lang,
            recentHistory = recentHistory,
            onCommitUserTurn = { spokenText ->
                persistLiveTranscriptTurn(
                    role = MessageRole.USER,
                    text = spokenText,
                    defaultTitle = strings.liveDefaultChatTitle,
                    lang = lang
                )
            },
            onCommitAssistantTurn = { replyText ->
                persistLiveTranscriptTurn(
                    role = MessageRole.ASSISTANT,
                    text = replyText,
                    defaultTitle = strings.liveDefaultChatTitle,
                    lang = lang
                )
            },
            onSessionEnded = {
                if (resumeAlwaysOnAfterLive) {
                    resumeAlwaysOnAfterLive = false
                    if (audioSessionManager.state.value.isServiceRunning) {
                        VoiceMonitoringService.resume(appContext)
                    }
                }
            }
        )
    }

    fun endLiveConversation() {
        liveSessionClient.endConversation()
    }

    fun toggleLiveMute() {
        liveSessionClient.toggleMute()
    }

    private suspend fun persistLiveTranscriptTurn(
        role: MessageRole,
        text: String,
        defaultTitle: String,
        lang: AppLanguage
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val convId = _activeConversationId.value ?: run {
            val initialTitle = if (role == MessageRole.USER) {
                trimmed.take(48)
            } else {
                defaultTitle
            }
            val createdId = conversationRepository.createConversation(
                title = initialTitle,
                languageCode = lang.code
            )
            _activeConversationId.value = createdId
            audioSessionManager.setActiveConversationId(createdId)
            createdId
        }

        conversationRepository.addMessage(
            conversationId = convId,
            role = role,
            content = trimmed
        )
    }

    private fun hasNetworkConnection(): Boolean {
        return try {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceInputManager.cancelAndDestroy()
        liveSessionClient.release()
        ttsManager.stop()
        deviceControlRegistry.release()
    }

    companion object {
        private const val TAG = "AriaViewModel"

        fun factory(context: Context, container: AppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AriaViewModel(
                        appContext = context.applicationContext,
                        conversationRepository = container.conversationRepository,
                        preferencesRepository = container.preferencesRepository,
                        aiChatService = container.aiChatService,
                        deviceControlRegistry = container.deviceControlRegistry,
                        voiceInputManager = container.voiceInputManager,
                        liveSessionClient = container.liveSessionClient,
                        ttsManager = container.ttsManager,
                        audioSessionManager = container.audioSessionManager
                    ) as T
                }
            }
        }
    }
}

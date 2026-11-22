package com.example.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.core.localization.AppLanguage
import com.example.data.local.MessageEntity
import com.example.data.local.MessageRole
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerSetupComplete
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SlidingWindow
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Audio specification aligned with Gemini Live API bidirectional PCM streaming.
 */
data class LiveAudioSpec(
    val inputSampleRateHz: Int = 16_000,
    val outputSampleRateHz: Int = 24_000,
    val channelConfigIn: Int = AudioFormat.CHANNEL_IN_MONO,
    val channelConfigOut: Int = AudioFormat.CHANNEL_OUT_MONO,
    val audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val inputMimeType: String = "audio/pcm;rate=16000",
    val modelId: String = DEFAULT_LIVE_MODEL_ID
) {
    companion object {
        const val DEFAULT_LIVE_MODEL_ID = "gemini-3.1-flash-live-preview"
    }
}

enum class LiveConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

enum class LiveTurnPhase {
    IDLE,
    LISTENING,
    SPEAKING,
    INTERRUPTED
}

data class LiveTranscriptEntry(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class LiveConversationUiState(
    val spec: LiveAudioSpec = LiveAudioSpec(),
    val connectionStatus: LiveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
    val turnPhase: LiveTurnPhase = LiveTurnPhase.IDLE,
    val hasMicPermission: Boolean = false,
    val isMuted: Boolean = false,
    val isEchoCancelerActive: Boolean = false,
    val inputAudioLevel: Float = 0f,
    val outputAudioLevel: Float = 0f,
    val liveUserPartial: String = "",
    val liveAssistantPartial: String = "",
    val transcriptEntries: List<LiveTranscriptEntry> = emptyList(),
    val reconnectAttempt: Int = 0,
    val errorMessage: String? = null
) {
    val isSessionActive: Boolean
        get() = connectionStatus == LiveConnectionStatus.CONNECTING ||
            connectionStatus == LiveConnectionStatus.CONNECTED ||
            connectionStatus == LiveConnectionStatus.RECONNECTING
}

/**
 * Legacy compatibility projection kept for UI components/tests observing LiveSessionState.
 */
sealed interface LiveSessionState {
    data object Idle : LiveSessionState
    data class Ready(val spec: LiveAudioSpec, val hasMicPermission: Boolean) : LiveSessionState
    data class Listening(val partialTranscript: String, val rmsLevel: Float) : LiveSessionState
    data class ProcessingTurn(val transcript: String) : LiveSessionState
    data class Error(val message: String) : LiveSessionState
}

sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    data class AudioChunk(val pcmBytes: ByteArray, val sampleRateHz: Int) : LiveServerEvent
    data class InputTranscript(val text: String) : LiveServerEvent
    data class OutputTranscript(val text: String) : LiveServerEvent
    data object Interrupted : LiveServerEvent
    data object TurnComplete : LiveServerEvent
    data class SessionResumption(val handle: String?, val resumable: Boolean) : LiveServerEvent
    data class GoAway(val timeLeftMs: Long?) : LiveServerEvent
}

interface LiveTransportSession {
    fun incomingEvents(): Flow<LiveServerEvent>
    suspend fun sendAudioPcm(pcmBytes: ByteArray, mimeType: String)
    suspend fun sendTextPrompt(text: String)
    suspend fun close()
}

fun interface LiveTransportFactory {
    suspend fun connect(
        spec: LiveAudioSpec,
        language: AppLanguage,
        recentHistory: List<MessageEntity>,
        resumptionHandle: String?
    ): LiveTransportSession
}

class FirebaseLiveTransportFactory : LiveTransportFactory {

    @OptIn(PublicPreviewAPI::class)
    override suspend fun connect(
        spec: LiveAudioSpec,
        language: AppLanguage,
        recentHistory: List<MessageEntity>,
        resumptionHandle: String?
    ): LiveTransportSession {
        val generationConfig = liveGenerationConfig {
            responseModality = ResponseModality.AUDIO
            speechConfig = SpeechConfig(voice = Voice("Aoede"))
            inputAudioTranscription = AudioTranscriptionConfig()
            outputAudioTranscription = AudioTranscriptionConfig()
            contextWindowCompression = ContextWindowCompressionConfig(
                triggerTokens = 24_000,
                slidingWindow = SlidingWindow(targetTokens = 16_000)
            )
            realtimeInputConfig = RealtimeInputConfig.builder()
                .setActivityHandling(RealtimeInputConfig.ActivityHandling.INTERRUPT)
                .build()
        }

        val liveModel = Firebase.ai.liveModel(
            modelName = spec.modelId,
            generationConfig = generationConfig,
            systemInstruction = content {
                text(buildBilingualLiveSystemInstruction(language, recentHistory))
            }
        )

        val session = withTimeout(30_000L) {
            if (!resumptionHandle.isNullOrBlank()) {
                try {
                    liveModel.connect(SessionResumptionConfig(resumptionHandle))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "Resumption connect failed, opening fresh session", e)
                    liveModel.connect()
                }
            } else {
                liveModel.connect()
            }
        }

        return FirebaseLiveTransportSession(session)
    }

    companion object {
        private const val TAG = "FirebaseLiveTransport"

        fun buildBilingualLiveSystemInstruction(
            language: AppLanguage,
            recentHistory: List<MessageEntity>
        ): String {
            val preferred = if (language == AppLanguage.BENGALI) {
                "Bengali (বাংলা) and English"
            } else {
                "English and Bengali (বাংলা)"
            }
            val historySnippet = recentHistory
                .filter { !it.isError && it.content.isNotBlank() }
                .takeLast(8)
                .joinToString("\n") { msg ->
                    val speaker = if (msg.role == MessageRole.USER) "User" else "Aria"
                    "$speaker: ${msg.content.take(260)}"
                }

            return buildString {
                append(
                    """
                    You are Aria AI 2.0 (আরিয়া এআই ২.০), a warm, intelligent, natural-sounding real-time voice assistant.
                    You speak both Bengali (বাংলা) and English fluently, including natural Banglish when spoken by the user.
                    - Respond in the exact language the user speaks in (if they speak Bengali, reply naturally in Bengali; if English, reply in English).
                    - Keep spoken responses clear, conversational, concise, and pleasant to hear. Avoid markdown symbols, bullet clutter, or reading formatting punctuation aloud.
                    - Default preferred conversational language: $preferred.
                    - Pause immediately if the user interrupts while you are speaking.
                    """.trimIndent()
                )
                if (historySnippet.isNotBlank()) {
                    append("\n\nRecent conversation context for continuity:\n")
                    append(historySnippet)
                }
            }
        }
    }
}

@OptIn(PublicPreviewAPI::class)
private class FirebaseLiveTransportSession(
    private val liveSession: LiveSession
) : LiveTransportSession {

    override fun incomingEvents(): Flow<LiveServerEvent> {
        return liveSession.receive().transform { message ->
            for (event in mapServerContentList(message)) {
                emit(event)
            }
        }
    }

    private fun mapServerContentList(message: com.google.firebase.ai.type.LiveServerMessage): List<LiveServerEvent> {
        return when (message) {
            is LiveServerSetupComplete -> listOf(LiveServerEvent.SetupComplete)
            is LiveServerGoAway -> listOf(LiveServerEvent.GoAway(message.timeLeft?.inWholeMilliseconds))
            is LiveSessionResumptionUpdate -> listOf(
                LiveServerEvent.SessionResumption(
                    handle = message.newHandle,
                    resumable = message.resumable ?: false
                )
            )
            is LiveServerContent -> buildList {
                message.inputTranscription?.text?.takeIf { it.isNotEmpty() }?.let {
                    add(LiveServerEvent.InputTranscript(it))
                }
                message.outputTranscription?.text?.takeIf { it.isNotEmpty() }?.let {
                    add(LiveServerEvent.OutputTranscript(it))
                }
                if (message.interrupted) {
                    add(LiveServerEvent.Interrupted)
                    return@buildList
                }
                val parts = message.content?.parts.orEmpty()
                for (part in parts) {
                    when (part) {
                        is InlineDataPart -> {
                            if (part.mimeType.startsWith("audio/", ignoreCase = true) &&
                                part.inlineData.isNotEmpty()
                            ) {
                                val rate = parseSampleRate(part.mimeType) ?: 24_000
                                add(LiveServerEvent.AudioChunk(part.inlineData, rate))
                            }
                        }
                        is TextPart -> {
                            if (!part.isThought && message.outputTranscription == null && part.text.isNotBlank()) {
                                add(LiveServerEvent.OutputTranscript(part.text))
                            }
                        }
                    }
                }
                if (message.turnComplete || message.generationComplete) {
                    add(LiveServerEvent.TurnComplete)
                }
            }
            else -> emptyList()
        }
    }

    override suspend fun sendAudioPcm(pcmBytes: ByteArray, mimeType: String) {
        if (liveSession.isClosed()) return
        liveSession.sendAudioRealtime(InlineData(pcmBytes, mimeType))
    }

    override suspend fun sendTextPrompt(text: String) {
        if (liveSession.isClosed()) return
        liveSession.send(text, turnComplete = true)
    }

    override suspend fun close() {
        runCatching { liveSession.stopReceiving() }
        runCatching { liveSession.close() }
    }

    private fun parseSampleRate(mimeType: String): Int? {
        val match = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE).find(mimeType) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }
}

/**
 * Full-featured GeminiLiveSessionClient implementing real-time two-way audio conversation
 * over Firebase AI Logic's Gemini Live API (`Firebase.ai.liveModel`), with App Check attestation,
 * AudioRecord/AudioTrack streaming, echo-cancelled barge-in, automatic reconnection,
 * mute/unmute, and live transcript persistence.
 */
interface LiveVoiceSessionClient {
    val state: StateFlow<LiveSessionState>
    val liveUiState: StateFlow<LiveConversationUiState>
    val audioSpec: LiveAudioSpec
    fun refreshCapabilityState()
    fun updateListeningProgress(transcript: String, rmsDb: Float)
    fun markProcessing(transcript: String)
    fun markError(message: String)
    fun resetToReady()
    fun onIncomingAudioChunk(pcmBytes: ByteArray)
    fun closeSession()
}

class GeminiLiveSessionClient(
    private val context: Context,
    override val audioSpec: LiveAudioSpec = LiveAudioSpec(),
    private val transportFactory: LiveTransportFactory = FirebaseLiveTransportFactory(),
    private val audioGateway: LiveAudioGateway = AndroidLiveAudioGateway(context)
) : LiveVoiceSessionClient {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _liveUiState = MutableStateFlow(
        LiveConversationUiState(
            spec = audioSpec,
            hasMicPermission = hasMicrophonePermission()
        )
    )
    val liveConversationState: StateFlow<LiveConversationUiState> = _liveUiState.asStateFlow()
    override val liveUiState: StateFlow<LiveConversationUiState> = liveConversationState

    private val _state = MutableStateFlow<LiveSessionState>(
        LiveSessionState.Ready(
            spec = audioSpec,
            hasMicPermission = hasMicrophonePermission()
        )
    )
    override val state: StateFlow<LiveSessionState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var senderJob: Job? = null
    private var levelJob: Job? = null
    private var currentTransport: LiveTransportSession? = null
    private var outgoingAudioChannel: Channel<ByteArray>? = null

    @Volatile
    private var shouldStayConnected = false

    @Volatile
    private var aiTurnStreaming = false

    @Volatile
    private var latestResumptionHandle: String? = null

    private val userTranscriptBuffer = StringBuilder()
    private val assistantTranscriptBuffer = StringBuilder()
    private var nextEntryId = 1L

    private var onCommitUserTurn: (suspend (String) -> Unit)? = null
    private var onCommitAssistantTurn: (suspend (String) -> Unit)? = null
    private var onSessionEndedCallback: (() -> Unit)? = null

    init {
        levelJob = scope.launch {
            combine(
                audioGateway.inputLevel,
                audioGateway.outputLevel,
                audioGateway.isEchoCancelerActive
            ) { inLvl, outLvl, aecActive ->
                Triple(inLvl, outLvl, aecActive)
            }.collect { (inLvl, outLvl, aecActive) ->
                val cur = _liveUiState.value
                val derivedPhase = when {
                    !cur.isSessionActive -> LiveTurnPhase.IDLE
                    outLvl > 0.01f -> LiveTurnPhase.SPEAKING
                    cur.turnPhase == LiveTurnPhase.SPEAKING && !aiTurnStreaming -> LiveTurnPhase.LISTENING
                    else -> cur.turnPhase
                }
                _liveUiState.value = cur.copy(
                    inputAudioLevel = if (cur.isMuted) 0f else inLvl,
                    outputAudioLevel = outLvl,
                    isEchoCancelerActive = aecActive,
                    turnPhase = derivedPhase
                )
            }
        }
    }

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startLiveConversation(
        language: AppLanguage,
        recentHistory: List<MessageEntity> = emptyList(),
        onCommitUserTurn: suspend (String) -> Unit = {},
        onCommitAssistantTurn: suspend (String) -> Unit = {},
        onSessionEnded: () -> Unit = {}
    ) {
        val hasMic = hasMicrophonePermission()
        if (!hasMic) {
            val err = if (language == AppLanguage.BENGALI) {
                "লাইভ কথোপকথনের জন্য মাইক্রোফোন অনুমতি (RECORD_AUDIO) প্রয়োজন।"
            } else {
                "Microphone permission (RECORD_AUDIO) is required for Live Conversation."
            }
            _liveUiState.value = _liveUiState.value.copy(
                connectionStatus = LiveConnectionStatus.ERROR,
                turnPhase = LiveTurnPhase.IDLE,
                hasMicPermission = false,
                errorMessage = err
            )
            _state.value = LiveSessionState.Error(err)
            return
        }

        if (shouldStayConnected && _liveUiState.value.isSessionActive) {
            return
        }

        this.onCommitUserTurn = onCommitUserTurn
        this.onCommitAssistantTurn = onCommitAssistantTurn
        this.onSessionEndedCallback = onSessionEnded

        sessionJob?.cancel()
        latestResumptionHandle = null
        userTranscriptBuffer.clear()
        assistantTranscriptBuffer.clear()
        shouldStayConnected = true

        _liveUiState.value = LiveConversationUiState(
            spec = audioSpec,
            connectionStatus = LiveConnectionStatus.CONNECTING,
            turnPhase = LiveTurnPhase.IDLE,
            hasMicPermission = true,
            isMuted = false,
            transcriptEntries = emptyList(),
            reconnectAttempt = 0,
            errorMessage = null
        )

        sessionJob = scope.launch {
            runLiveSessionLoop(language = language, recentHistory = recentHistory)
        }
    }

    fun toggleMute(): Boolean {
        val nextMuted = !_liveUiState.value.isMuted
        setMuted(nextMuted)
        return nextMuted
    }

    fun setMuted(muted: Boolean) {
        audioGateway.setMuted(muted)
        _liveUiState.value = _liveUiState.value.copy(
            isMuted = muted,
            inputAudioLevel = if (muted) 0f else _liveUiState.value.inputAudioLevel
        )
    }

    fun endConversation() {
        shouldStayConnected = false
        val sessionToClose = currentTransport
        currentTransport = null

        audioGateway.stopAudio()
        senderJob?.cancel()
        senderJob = null
        runCatching { outgoingAudioChannel?.close() }
        outgoingAudioChannel = null
        sessionJob?.cancel()
        sessionJob = null

        scope.launch {
            flushPendingUserTurn()
            flushPendingAssistantTurn()
            runCatching { sessionToClose?.close() }
            _liveUiState.value = _liveUiState.value.copy(
                connectionStatus = LiveConnectionStatus.DISCONNECTED,
                turnPhase = LiveTurnPhase.IDLE,
                isMuted = false,
                inputAudioLevel = 0f,
                outputAudioLevel = 0f,
                liveUserPartial = "",
                liveAssistantPartial = "",
                reconnectAttempt = 0
            )
            _state.value = LiveSessionState.Ready(
                spec = audioSpec,
                hasMicPermission = hasMicrophonePermission()
            )
            onSessionEndedCallback?.invoke()
        }
    }

    private suspend fun runLiveSessionLoop(
        language: AppLanguage,
        recentHistory: List<MessageEntity>
    ) {
        var attempt = 0

        while (shouldStayConnected && scope.isActive) {
            var transport: LiveTransportSession? = null
            var hardwareStarted = false
            try {
                _liveUiState.value = _liveUiState.value.copy(
                    connectionStatus = if (attempt == 0) {
                        LiveConnectionStatus.CONNECTING
                    } else {
                        LiveConnectionStatus.RECONNECTING
                    },
                    reconnectAttempt = attempt,
                    errorMessage = null
                )

                transport = transportFactory.connect(
                    spec = audioSpec,
                    language = language,
                    recentHistory = recentHistory,
                    resumptionHandle = latestResumptionHandle
                )
                currentTransport = transport

                val audioChannel = Channel<ByteArray>(
                    capacity = 64,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
                outgoingAudioChannel = audioChannel

                val hardwareOk = audioGateway.startAudio(
                    inputSampleRateHz = audioSpec.inputSampleRateHz,
                    outputSampleRateHz = audioSpec.outputSampleRateHz,
                    onInputChunk = { pcmBytes ->
                        audioChannel.trySend(pcmBytes)
                    },
                    onAudioError = { msg ->
                        Log.e(TAG, "Hardware audio error: $msg")
                        _liveUiState.value = _liveUiState.value.copy(
                            connectionStatus = LiveConnectionStatus.ERROR,
                            turnPhase = LiveTurnPhase.IDLE,
                            errorMessage = msg
                        )
                        shouldStayConnected = false
                        scope.launch { runCatching { transport?.close() } }
                    }
                )
                hardwareStarted = hardwareOk
                if (!hardwareOk) {
                    shouldStayConnected = false
                    break
                }

                // Apply current mute state cleanly to newly started audio hardware
                audioGateway.setMuted(_liveUiState.value.isMuted)

                attempt = 0
                _liveUiState.value = _liveUiState.value.copy(
                    connectionStatus = LiveConnectionStatus.CONNECTED,
                    turnPhase = LiveTurnPhase.LISTENING,
                    reconnectAttempt = 0,
                    errorMessage = null
                )
                _state.value = LiveSessionState.Listening("", 2f)

                senderJob?.cancel()
                senderJob = scope.launch {
                    for (chunk in audioChannel) {
                        if (!shouldStayConnected || !isActive) break
                        try {
                            transport.sendAudioPcm(chunk, audioSpec.inputMimeType)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed sending live audio chunk", e)
                            break
                        }
                    }
                }

                var reconnectRequestedByGoAway = false

                transport.incomingEvents().collect { event ->
                    when (event) {
                        LiveServerEvent.SetupComplete -> {
                            aiTurnStreaming = false
                            _liveUiState.value = _liveUiState.value.copy(
                                connectionStatus = LiveConnectionStatus.CONNECTED,
                                turnPhase = LiveTurnPhase.LISTENING
                            )
                        }
                        is LiveServerEvent.AudioChunk -> {
                            aiTurnStreaming = true
                            flushPendingUserTurn()
                            _liveUiState.value = _liveUiState.value.copy(
                                turnPhase = LiveTurnPhase.SPEAKING
                            )
                            audioGateway.enqueueOutputAudio(event.pcmBytes, event.sampleRateHz)
                        }
                        is LiveServerEvent.InputTranscript -> {
                            userTranscriptBuffer.append(event.text)
                            _liveUiState.value = _liveUiState.value.copy(
                                liveUserPartial = userTranscriptBuffer.toString().trim()
                            )
                            _state.value = LiveSessionState.Listening(
                                partialTranscript = userTranscriptBuffer.toString().trim(),
                                rmsLevel = _liveUiState.value.inputAudioLevel * 12f
                            )
                        }
                        is LiveServerEvent.OutputTranscript -> {
                            aiTurnStreaming = true
                            flushPendingUserTurn()
                            assistantTranscriptBuffer.append(event.text)
                            _liveUiState.value = _liveUiState.value.copy(
                                turnPhase = LiveTurnPhase.SPEAKING,
                                liveAssistantPartial = assistantTranscriptBuffer.toString().trim()
                            )
                        }
                        LiveServerEvent.Interrupted -> {
                            aiTurnStreaming = false
                            audioGateway.interruptOutputPlayback()
                            flushPendingUserTurn()
                            flushPendingAssistantTurn()
                            _liveUiState.value = _liveUiState.value.copy(
                                turnPhase = LiveTurnPhase.LISTENING,
                                outputAudioLevel = 0f
                            )
                        }
                        LiveServerEvent.TurnComplete -> {
                            aiTurnStreaming = false
                            flushPendingUserTurn()
                            flushPendingAssistantTurn()
                            val stillPlayingAudio = audioGateway.outputLevel.value > 0.01f
                            _liveUiState.value = _liveUiState.value.copy(
                                turnPhase = if (stillPlayingAudio) {
                                    LiveTurnPhase.SPEAKING
                                } else {
                                    LiveTurnPhase.LISTENING
                                }
                            )
                        }
                        is LiveServerEvent.SessionResumption -> {
                            if (event.resumable && !event.handle.isNullOrBlank()) {
                                latestResumptionHandle = event.handle
                            }
                        }
                        is LiveServerEvent.GoAway -> {
                            Log.i(TAG, "Received LiveServerGoAway; scheduling seamless reconnect")
                            reconnectRequestedByGoAway = true
                        }
                    }
                }

                if (!shouldStayConnected) break

                if (reconnectRequestedByGoAway) {
                    attempt = 1
                    continue
                }

                // Stream closed unexpectedly while still active -> reconnect
                attempt++
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    val closedMsg = if (language == AppLanguage.BENGALI) {
                        "লাইভ সংযোগ বিচ্ছিন্ন হয়েছে। আবার চেষ্টা করতে Start চাপুন।"
                    } else {
                        "Live voice connection closed. Tap Start Live Conversation to reconnect."
                    }
                    _liveUiState.value = _liveUiState.value.copy(
                        connectionStatus = LiveConnectionStatus.ERROR,
                        turnPhase = LiveTurnPhase.IDLE,
                        errorMessage = closedMsg
                    )
                    _state.value = LiveSessionState.Error(closedMsg)
                    shouldStayConnected = false
                    break
                }
                delay(attempt * 1_000L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini Live connection timed out", e)
                attempt++
                if (attempt > MAX_RECONNECT_ATTEMPTS || !shouldStayConnected) {
                    val msg = if (language == AppLanguage.BENGALI) {
                        "লাইভ সংযোগে সময় শেষ হয়েছে। ইন্টারনেট পরীক্ষা করে আবার চেষ্টা করুন।"
                    } else {
                        "Live connection timed out. Check your internet and try again."
                    }
                    _liveUiState.value = _liveUiState.value.copy(
                        connectionStatus = LiveConnectionStatus.ERROR,
                        turnPhase = LiveTurnPhase.IDLE,
                        errorMessage = msg
                    )
                    _state.value = LiveSessionState.Error(msg)
                    shouldStayConnected = false
                    break
                }
                delay(attempt * 1_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Live session error", e)
                attempt++
                val isAppCheckOrAuth = e.message?.contains("403") == true ||
                    e.message?.contains("AppCheck", ignoreCase = true) == true
                if (isAppCheckOrAuth || attempt > MAX_RECONNECT_ATTEMPTS || !shouldStayConnected) {
                    val detail = e.localizedMessage?.takeIf { it.isNotBlank() } ?: if (language == AppLanguage.BENGALI) {
                        "জেমিনি লাইভ সেশন সংযোগ করা যায়নি।"
                    } else {
                        "Unable to maintain Gemini Live voice session."
                    }
                    _liveUiState.value = _liveUiState.value.copy(
                        connectionStatus = LiveConnectionStatus.ERROR,
                        turnPhase = LiveTurnPhase.IDLE,
                        errorMessage = detail
                    )
                    _state.value = LiveSessionState.Error(detail)
                    shouldStayConnected = false
                    break
                }
                _liveUiState.value = _liveUiState.value.copy(
                    connectionStatus = LiveConnectionStatus.RECONNECTING,
                    reconnectAttempt = attempt
                )
                delay((attempt * 1_000L).coerceAtMost(4_000L))
            } finally {
                senderJob?.cancel()
                senderJob = null
                runCatching { outgoingAudioChannel?.close() }
                outgoingAudioChannel = null
                if (hardwareStarted) {
                    audioGateway.stopAudio()
                }
                runCatching { transport?.close() }
                if (currentTransport === transport) {
                    currentTransport = null
                }
            }
        }

        if (!shouldStayConnected && _liveUiState.value.connectionStatus != LiveConnectionStatus.ERROR) {
            flushPendingUserTurn()
            flushPendingAssistantTurn()
            _liveUiState.value = _liveUiState.value.copy(
                connectionStatus = LiveConnectionStatus.DISCONNECTED,
                turnPhase = LiveTurnPhase.IDLE,
                inputAudioLevel = 0f,
                outputAudioLevel = 0f
            )
            onSessionEndedCallback?.invoke()
        }
    }

    private suspend fun flushPendingUserTurn() {
        val text = userTranscriptBuffer.toString().trim()
        userTranscriptBuffer.clear()
        if (text.isEmpty()) return

        val entry = LiveTranscriptEntry(
            id = nextEntryId++,
            isUser = true,
            text = text
        )
        _liveUiState.value = _liveUiState.value.copy(
            liveUserPartial = "",
            transcriptEntries = (_liveUiState.value.transcriptEntries + entry).takeLast(50)
        )
        runCatching { onCommitUserTurn?.invoke(text) }
    }

    private suspend fun flushPendingAssistantTurn() {
        val text = assistantTranscriptBuffer.toString().trim()
        assistantTranscriptBuffer.clear()
        if (text.isEmpty()) return

        val entry = LiveTranscriptEntry(
            id = nextEntryId++,
            isUser = false,
            text = text
        )
        _liveUiState.value = _liveUiState.value.copy(
            liveAssistantPartial = "",
            transcriptEntries = (_liveUiState.value.transcriptEntries + entry).takeLast(50)
        )
        runCatching { onCommitAssistantTurn?.invoke(text) }
    }

    override fun refreshCapabilityState() {
        val hasMic = hasMicrophonePermission()
        _liveUiState.value = _liveUiState.value.copy(hasMicPermission = hasMic)
        if (!_liveUiState.value.isSessionActive) {
            _state.value = LiveSessionState.Ready(
                spec = audioSpec,
                hasMicPermission = hasMic
            )
        }
    }

    override fun updateListeningProgress(transcript: String, rmsDb: Float) {
        if (_liveUiState.value.isSessionActive) return
        _state.value = LiveSessionState.Listening(
            partialTranscript = transcript,
            rmsLevel = rmsDb.coerceIn(0f, 12f)
        )
    }

    override fun markProcessing(transcript: String) {
        if (_liveUiState.value.isSessionActive) return
        _state.value = LiveSessionState.ProcessingTurn(transcript)
    }

    override fun markError(message: String) {
        if (_liveUiState.value.isSessionActive) return
        _state.value = LiveSessionState.Error(message)
    }

    override fun resetToReady() {
        if (_liveUiState.value.isSessionActive) return
        refreshCapabilityState()
    }

    override fun onIncomingAudioChunk(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        scope.launch {
            audioGateway.enqueueOutputAudio(pcmBytes, audioSpec.outputSampleRateHz)
        }
    }

    override fun closeSession() {
        endConversation()
        _state.value = LiveSessionState.Idle
    }

    fun release() {
        closeSession()
        levelJob?.cancel()
        levelJob = null
    }

    companion object {
        private const val TAG = "GeminiLiveSessionClient"
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }
}

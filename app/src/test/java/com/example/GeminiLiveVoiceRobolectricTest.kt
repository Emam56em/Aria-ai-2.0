package com.example

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.core.localization.AppLanguage
import com.example.data.local.MessageRole
import com.example.data.voice.GeminiLiveSessionClient
import com.example.data.voice.LiveAudioGateway
import com.example.data.voice.LiveAudioSpec
import com.example.data.voice.LiveConnectionStatus
import com.example.data.voice.LiveServerEvent
import com.example.data.voice.LiveTransportFactory
import com.example.data.voice.LiveTransportSession
import com.example.data.voice.LiveTurnPhase
import com.example.data.voice.VoiceInputManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiLiveVoiceRobolectricTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `missing RECORD_AUDIO permission enters error state without starting session`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val audioGateway = FakeLiveAudioGateway()
        val transport = FakeLiveTransportSession()
        var connectCalled = false
        val client = GeminiLiveSessionClient(
            context = app,
            transportFactory = LiveTransportFactory { _, _, _, _ ->
                connectCalled = true
                transport
            },
            audioGateway = audioGateway
        )

        client.startLiveConversation(language = AppLanguage.BENGALI)

        val ui = client.liveConversationState.value
        assertEquals(LiveConnectionStatus.ERROR, ui.connectionStatus)
        assertFalse(ui.hasMicPermission)
        assertFalse(ui.isSessionActive)
        assertFalse(connectCalled)
        assertFalse(audioGateway.started)
        client.release()
    }

    @Test
    fun `bidirectional streaming sends 16khz pcm, plays 24khz audio, updates waveform, and commits bilingual transcripts`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audioGateway = FakeLiveAudioGateway()
        val transport = FakeLiveTransportSession()
        val committedTurns = mutableListOf<Pair<MessageRole, String>>()
        var sessionEndedCount = 0

        val client = GeminiLiveSessionClient(
            context = app,
            transportFactory = LiveTransportFactory { spec, _, _, _ ->
                assertEquals(LiveAudioSpec.DEFAULT_LIVE_MODEL_ID, spec.modelId)
                transport
            },
            audioGateway = audioGateway
        )

        client.startLiveConversation(
            language = AppLanguage.BENGALI,
            onCommitUserTurn = { committedTurns.add(MessageRole.USER to it) },
            onCommitAssistantTurn = { committedTurns.add(MessageRole.ASSISTANT to it) },
            onSessionEnded = { sessionEndedCount++ }
        )

        waitUntil { client.liveConversationState.value.connectionStatus == LiveConnectionStatus.CONNECTED }
        transport.emitEvent(LiveServerEvent.SetupComplete)
        waitUntil { client.liveConversationState.value.turnPhase == LiveTurnPhase.LISTENING }

        // Simulate microphone PCM frame capture -> sent to Live session with 16kHz MIME
        val fakeMicPcm = ByteArray(1280) { 0x12 }
        audioGateway.emitMicFrame(fakeMicPcm, level = 0.55f)
        waitUntil { transport.sentAudioFrames.isNotEmpty() }
        assertEquals("audio/pcm;rate=16000", transport.sentAudioFrames.first().second)

        // Mute stops audio forwarding & resets mic level
        assertTrue(client.toggleMute())
        assertTrue(client.liveConversationState.value.isMuted)
        assertEquals(0f, client.liveConversationState.value.inputAudioLevel, 0.001f)
        assertFalse(client.toggleMute())
        assertFalse(client.liveConversationState.value.isMuted)

        // Simulate Bengali user transcript + Aria 24kHz voice reply + TurnComplete
        transport.emitEvent(LiveServerEvent.InputTranscript("আজকে ঢাকার আবহাওয়া "))
        transport.emitEvent(LiveServerEvent.InputTranscript("কেমন?"))
        waitUntil { client.liveConversationState.value.liveUserPartial.contains("কেমন?") }

        val replyPcm = ByteArray(960) { 0x24 }
        transport.emitEvent(LiveServerEvent.AudioChunk(replyPcm, 24_000))
        transport.emitEvent(LiveServerEvent.OutputTranscript("ঢাকায় আজ আবহাওয়া বেশ সুন্দর ও রোদেলা।"))
        waitUntil {
            client.liveConversationState.value.turnPhase == LiveTurnPhase.SPEAKING &&
                client.liveConversationState.value.outputAudioLevel > 0.1f &&
                audioGateway.playedChunks.isNotEmpty()
        }

        transport.emitEvent(LiveServerEvent.TurnComplete)
        audioGateway.finishPlayback()
        waitUntil {
            committedTurns.size == 2 &&
                client.liveConversationState.value.turnPhase == LiveTurnPhase.LISTENING
        }

        assertEquals(MessageRole.USER, committedTurns[0].first)
        assertTrue(committedTurns[0].second.contains("আজকে ঢাকার আবহাওয়া"))
        assertEquals(MessageRole.ASSISTANT, committedTurns[1].first)
        assertTrue(committedTurns[1].second.contains("ঢাকায় আজ আবহাওয়া"))
        assertEquals(2, client.liveConversationState.value.transcriptEntries.size)

        client.endConversation()
        waitUntil {
            client.liveConversationState.value.connectionStatus == LiveConnectionStatus.DISCONNECTED &&
                sessionEndedCount >= 1
        }
        assertFalse(client.liveConversationState.value.isSessionActive)
        client.release()
    }

    @Test
    fun `barge in interrupted event flushes playback immediately and returns to listening`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audioGateway = FakeLiveAudioGateway()
        val transport = FakeLiveTransportSession()
        val committed = mutableListOf<Pair<MessageRole, String>>()

        val client = GeminiLiveSessionClient(
            context = app,
            transportFactory = LiveTransportFactory { _, _, _, _ -> transport },
            audioGateway = audioGateway
        )

        client.startLiveConversation(
            language = AppLanguage.ENGLISH,
            onCommitUserTurn = { committed.add(MessageRole.USER to it) },
            onCommitAssistantTurn = { committed.add(MessageRole.ASSISTANT to it) }
        )
        waitUntil { client.liveConversationState.value.connectionStatus == LiveConnectionStatus.CONNECTED }

        transport.emitEvent(LiveServerEvent.AudioChunk(ByteArray(640) { 0x33 }, 24_000))
        transport.emitEvent(LiveServerEvent.OutputTranscript("Here is a long explanation that you can interrupt"))
        waitUntil { client.liveConversationState.value.turnPhase == LiveTurnPhase.SPEAKING }

        // User speaks while Aria is talking -> server sends Interrupted
        transport.emitEvent(LiveServerEvent.Interrupted)
        waitUntil {
            audioGateway.interruptedCount == 1 &&
                client.liveConversationState.value.turnPhase == LiveTurnPhase.LISTENING
        }

        assertEquals(0f, client.liveConversationState.value.outputAudioLevel, 0.001f)
        assertTrue(client.liveConversationState.value.isSessionActive)
        assertEquals(1, committed.size)

        client.endConversation()
        waitUntil { client.liveConversationState.value.connectionStatus == LiveConnectionStatus.DISCONNECTED }
        client.release()
    }

    @Test
    fun `goAway and transient failure trigger session resumption reconnection automatically`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audioGateway = FakeLiveAudioGateway()
        val firstSession = FakeLiveTransportSession()
        val secondSession = FakeLiveTransportSession()
        val thirdSession = FakeLiveTransportSession()
        val receivedHandles = mutableListOf<String?>()
        var connectAttempt = 0

        val client = GeminiLiveSessionClient(
            context = app,
            transportFactory = LiveTransportFactory { _, _, _, resumptionHandle ->
                receivedHandles.add(resumptionHandle)
                when (++connectAttempt) {
                    1 -> firstSession
                    2 -> throw IllegalStateException("Temporary network blip")
                    else -> if (resumptionHandle == "resume-token-42") secondSession else thirdSession
                }
            },
            audioGateway = audioGateway
        )

        client.startLiveConversation(language = AppLanguage.ENGLISH)
        waitUntil { client.liveConversationState.value.connectionStatus == LiveConnectionStatus.CONNECTED }

        // Receive resumption token then GoAway -> closes first stream cleanly and reconnects
        firstSession.emitEvent(LiveServerEvent.SessionResumption("resume-token-42", resumable = true))
        delay(50)
        firstSession.emitEvent(LiveServerEvent.GoAway(timeLeftMs = 1_000L))
        firstSession.closeStream()

        // Attempt 2 fails transiently, attempt 3 succeeds with stored resumption handle
        waitUntil(timeoutMs = 8_000L) {
            connectAttempt >= 3 &&
                client.liveConversationState.value.connectionStatus == LiveConnectionStatus.CONNECTED
        }

        assertEquals(null, receivedHandles[0])
        assertEquals("resume-token-42", receivedHandles[1])
        assertEquals("resume-token-42", receivedHandles[2])

        client.endConversation()
        waitUntil { client.liveConversationState.value.connectionStatus == LiveConnectionStatus.DISCONNECTED }
        client.release()
    }

    @Test
    fun `voiceInputManager stopListening keeps state ready for final callbacks until reset`() {
        val manager = VoiceInputManager(app)
        // Calling stopListening on idle manager must be safe and not throw or clobber state
        manager.stopListening()
        assertNotNull(manager.events.value)
        manager.cancelAndDestroy()
    }

    private suspend fun waitUntil(timeoutMs: Long = 4_000L, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) {
                delay(20L)
            }
        }
    }

    private class FakeLiveAudioGateway : LiveAudioGateway {
        private val _inputLevel = MutableStateFlow(0f)
        override val inputLevel: StateFlow<Float> = _inputLevel

        private val _outputLevel = MutableStateFlow(0f)
        override val outputLevel: StateFlow<Float> = _outputLevel

        private val _isEchoCancelerActive = MutableStateFlow(true)
        override val isEchoCancelerActive: StateFlow<Boolean> = _isEchoCancelerActive

        var started = false
        var isMutedFlag = false
        var interruptedCount = 0
        val playedChunks = mutableListOf<Pair<ByteArray, Int>>()
        private var inputChunkCallback: (suspend (ByteArray) -> Unit)? = null

        override fun startAudio(
            inputSampleRateHz: Int,
            outputSampleRateHz: Int,
            onInputChunk: suspend (ByteArray) -> Unit,
            onAudioError: (String) -> Unit
        ): Boolean {
            started = true
            isMutedFlag = false
            inputChunkCallback = onInputChunk
            return true
        }

        suspend fun emitMicFrame(pcm: ByteArray, level: Float) {
            if (!isMutedFlag) {
                _inputLevel.value = level
                inputChunkCallback?.invoke(pcm)
            }
        }

        override suspend fun enqueueOutputAudio(pcmBytes: ByteArray, sampleRateHz: Int) {
            playedChunks.add(pcmBytes to sampleRateHz)
            _outputLevel.value = 0.65f
        }

        fun finishPlayback() {
            _outputLevel.value = 0f
        }

        override fun interruptOutputPlayback() {
            interruptedCount++
            _outputLevel.value = 0f
        }

        override fun setMuted(muted: Boolean) {
            isMutedFlag = muted
            if (muted) _inputLevel.value = 0f
        }

        override fun stopAudio() {
            started = false
            _inputLevel.value = 0f
            _outputLevel.value = 0f
            inputChunkCallback = null
        }
    }

    private class FakeLiveTransportSession : LiveTransportSession {
        private val incomingFlow = MutableSharedFlow<LiveServerEvent>(extraBufferCapacity = 32)
        private val closedSignal = MutableStateFlow(false)
        val sentAudioFrames = mutableListOf<Pair<ByteArray, String>>()

        override fun incomingEvents(): Flow<LiveServerEvent> = kotlinx.coroutines.flow.flow {
            try {
                incomingFlow.collect { event ->
                    if (closedSignal.value) {
                        throw StreamClosedSignal()
                    }
                    emit(event)
                }
            } catch (_: StreamClosedSignal) {
                // Stream ended normally after GoAway or close()
            }
        }

        private class StreamClosedSignal : RuntimeException()

        suspend fun emitEvent(event: LiveServerEvent) {
            incomingFlow.emit(event)
        }

        fun closeStream() {
            closedSignal.value = true
            incomingFlow.tryEmit(LiveServerEvent.SetupComplete)
        }

        override suspend fun sendAudioPcm(pcmBytes: ByteArray, mimeType: String) {
            sentAudioFrames.add(pcmBytes to mimeType)
        }

        override suspend fun sendTextPrompt(text: String) {}

        override suspend fun close() {
            closedSignal.value = true
        }
    }
}

package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.localization.LocalAppStrings
import com.example.data.voice.LiveConnectionStatus
import com.example.data.voice.LiveConversationUiState
import com.example.data.voice.LiveSessionState
import com.example.data.voice.LiveTranscriptEntry
import com.example.data.voice.LiveTurnPhase
import com.example.data.voice.VoiceRecognitionEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLiveSheet(
    hasMicPermission: Boolean,
    voiceEvent: VoiceRecognitionEvent,
    liveState: LiveSessionState,
    liveConversationState: LiveConversationUiState,
    onRequestMicPermission: (startLiveAfterGrant: Boolean) -> Unit,
    onStartLiveConversation: () -> Unit,
    onEndLiveConversation: () -> Unit,
    onToggleLiveMute: () -> Unit,
    onStartDictationListening: () -> Unit,
    onStopDictationListening: () -> Unit,
    onSendDictationTranscript: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDictationFallback by remember { mutableStateOf(false) }

    val isDictating = voiceEvent is VoiceRecognitionEvent.Listening ||
        voiceEvent is VoiceRecognitionEvent.PartialResult
    val dictatedText = when (voiceEvent) {
        is VoiceRecognitionEvent.PartialResult -> voiceEvent.text
        is VoiceRecognitionEvent.FinalResult -> voiceEvent.text
        else -> ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Text(
                text = strings.liveVoiceTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = strings.liveVoiceSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Connection Status & Microphone Status pills
            StatusBadgeRow(
                hasMicPermission = hasMicPermission,
                liveState = liveConversationState
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Animated Listening & Speaking Audio-Reactive Visualizer
            LiveVoiceVisualizer(
                liveState = liveConversationState,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_voice_visualizer")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Live Transcript Section
            LiveTranscriptCard(
                entries = liveConversationState.transcriptEntries,
                userPartial = liveConversationState.liveUserPartial,
                assistantPartial = liveConversationState.liveAssistantPartial,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_transcript_card")
            )

            if (!liveConversationState.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .testTag("live_error_banner")
                ) {
                    Text(
                        text = liveConversationState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Controls
            if (!hasMicPermission) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = strings.micPermissionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = strings.micPermissionRationale,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { onRequestMicPermission(true) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("grant_mic_permission_button")
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.grantPermission)
                        }
                    }
                }
            } else if (!liveConversationState.isSessionActive) {
                Button(
                    onClick = onStartLiveConversation,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("live_start_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = strings.liveStartConversation,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onToggleLiveMute,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (liveConversationState.isMuted) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                            } else {
                                Color.Transparent
                            },
                            contentColor = if (liveConversationState.isMuted) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("live_mute_button")
                    ) {
                        Icon(
                            imageVector = if (liveConversationState.isMuted) {
                                Icons.Filled.MicOff
                            } else {
                                Icons.Filled.Mic
                            },
                            contentDescription = if (liveConversationState.isMuted) {
                                strings.liveUnmuteMic
                            } else {
                                strings.liveMuteMic
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (liveConversationState.isMuted) {
                                strings.liveUnmuteMic
                            } else {
                                strings.liveMuteMic
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onEndLiveConversation,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(52.dp)
                            .testTag("live_end_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = strings.liveEndConversation
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.liveEndConversation,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Architecture / format footer
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.livePipelineTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.livePipelineAudioFormat,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Collapsible quick text-dictation fallback
            if (hasMicPermission && !liveConversationState.isSessionActive) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = { showDictationFallback = !showDictationFallback },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("toggle_dictation_fallback_button")
                ) {
                    Text(
                        text = if (showDictationFallback) {
                            strings.liveHideQuickDictation
                        } else {
                            strings.liveQuickDictationToggle
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showDictationFallback) {
                            Icons.Outlined.ExpandLess
                        } else {
                            Icons.Outlined.ExpandMore
                        },
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = showDictationFallback) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            val dictationStatus = when {
                                isDictating && dictatedText.isNotBlank() -> dictatedText
                                isDictating -> strings.listeningNow
                                voiceEvent is VoiceRecognitionEvent.Error && voiceEvent.isUnavailable ->
                                    strings.speechNotAvailable
                                voiceEvent is VoiceRecognitionEvent.Error -> strings.speechNoMatch
                                dictatedText.isNotBlank() -> dictatedText
                                liveState is LiveSessionState.ProcessingTurn && liveState.transcript.isNotBlank() ->
                                    liveState.transcript
                                else -> strings.tapMicToSpeak
                            }
                            Text(
                                text = dictationStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (voiceEvent is VoiceRecognitionEvent.Error) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isDictating) {
                                    OutlinedButton(
                                        onClick = onStopDictationListening,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("stop_listening_button")
                                    ) {
                                        Icon(Icons.Filled.Stop, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(strings.stopListening)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = onStartDictationListening,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("start_listening_button")
                                    ) {
                                        Icon(Icons.Filled.Mic, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(strings.startDictation)
                                    }
                                }

                                if (dictatedText.isNotBlank()) {
                                    Button(
                                        onClick = {
                                            onSendDictationTranscript(dictatedText)
                                            onDismiss()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("send_voice_button")
                                    ) {
                                        Icon(Icons.Filled.Send, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(strings.sendVoiceToChat)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StatusBadgeRow(
    hasMicPermission: Boolean,
    liveState: LiveConversationUiState
) {
    val strings = LocalAppStrings.current

    val (connLabel, connColor) = when (liveState.connectionStatus) {
        LiveConnectionStatus.DISCONNECTED -> strings.liveConnDisconnected to MaterialTheme.colorScheme.onSurfaceVariant
        LiveConnectionStatus.CONNECTING -> strings.liveConnConnecting to MaterialTheme.colorScheme.secondary
        LiveConnectionStatus.CONNECTED -> strings.liveConnConnected to MaterialTheme.colorScheme.tertiary
        LiveConnectionStatus.RECONNECTING -> strings.liveConnReconnecting to MaterialTheme.colorScheme.secondary
        LiveConnectionStatus.ERROR -> strings.liveConnError to MaterialTheme.colorScheme.error
    }

    val (micLabel, micColor) = when {
        !hasMicPermission -> strings.alwaysOnStatusDenied to MaterialTheme.colorScheme.error
        liveState.isMuted -> strings.liveMicMutedStatus to MaterialTheme.colorScheme.secondary
        liveState.isSessionActive -> strings.liveMicActiveStatus to MaterialTheme.colorScheme.primary
        else -> strings.liveMicActiveStatus to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        StatusPill(
            label = connLabel,
            accent = connColor,
            showSpinner = liveState.connectionStatus == LiveConnectionStatus.CONNECTING ||
                liveState.connectionStatus == LiveConnectionStatus.RECONNECTING,
            modifier = Modifier
                .weight(1f)
                .testTag("live_connection_status")
        )
        StatusPill(
            label = micLabel,
            accent = micColor,
            showSpinner = false,
            modifier = Modifier
                .weight(1f)
                .testTag("live_mic_status")
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    accent: Color,
    showSpinner: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.14f),
        modifier = modifier.border(
            width = 1.dp,
            color = accent.copy(alpha = 0.45f),
            shape = RoundedCornerShape(50)
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = accent,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveVoiceVisualizer(
    liveState: LiveConversationUiState,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val isSpeaking = liveState.turnPhase == LiveTurnPhase.SPEAKING && liveState.isSessionActive
    val isListening = liveState.turnPhase == LiveTurnPhase.LISTENING && liveState.isSessionActive && !liveState.isMuted
    val isConnecting = liveState.connectionStatus == LiveConnectionStatus.CONNECTING ||
        liveState.connectionStatus == LiveConnectionStatus.RECONNECTING

    val smoothedInput by animateFloatAsState(
        targetValue = if (isListening) liveState.inputAudioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = 420f),
        label = "smoothed_input"
    )
    val smoothedOutput by animateFloatAsState(
        targetValue = if (isSpeaking) liveState.outputAudioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = 380f),
        label = "smoothed_output"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "live_waveform")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath_scale"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val idleRingColor = MaterialTheme.colorScheme.outline

    val activeColor = when {
        isSpeaking -> tertiaryColor
        isListening -> primaryColor
        isConnecting -> secondaryColor
        else -> idleRingColor
    }

    val stateCaption = when {
        liveState.connectionStatus == LiveConnectionStatus.CONNECTING -> strings.liveConnConnecting
        liveState.connectionStatus == LiveConnectionStatus.RECONNECTING -> strings.liveConnReconnecting
        isSpeaking -> strings.liveAriaSpeaking
        liveState.isSessionActive && liveState.isMuted -> strings.liveMicMutedStatus
        isListening -> strings.listeningNow
        else -> strings.tapMicToSpeak
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        activeColor.copy(alpha = if (liveState.isSessionActive) 0.16f else 0.06f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = activeColor.copy(alpha = if (liveState.isSessionActive) 0.5f else 0.25f),
                shape = RoundedCornerShape(22.dp)
            )
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
        ) {
            if (isSpeaking) {
                // Animated Speaking Waveform driven by real output PCM RMS
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                        .testTag("speaking_waveform_canvas")
                ) {
                    val barCount = 24
                    val spacing = size.width / (barCount * 1.8f)
                    val barWidth = spacing * 0.9f
                    val totalBarsWidth = barCount * barWidth + (barCount - 1) * spacing
                    val startX = (size.width - totalBarsWidth) / 2f
                    val centerY = size.height / 2f
                    val energy = (0.18f + smoothedOutput * 1.45f).coerceIn(0.18f, 1f)

                    for (i in 0 until barCount) {
                        val normPos = (i.toFloat() / (barCount - 1)) * 2f - 1f
                        val envelope = 1f - (abs(normPos) * 0.55f)
                        val wave = 0.45f + 0.55f * abs(sin(wavePhase + i * 0.48f))
                        val barH = (size.height * 0.92f * energy * envelope * wave)
                            .coerceAtLeast(10f)
                        val x = startX + i * (barWidth + spacing)
                        drawRoundRect(
                            color = if (i % 2 == 0) tertiaryColor else primaryColor,
                            topLeft = Offset(x, centerY - barH / 2f),
                            size = Size(barWidth, barH),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            } else {
                // Listening / Idle / Connecting Orb Canvas driven by real input PCM RMS
                Canvas(
                    modifier = Modifier
                        .size(110.dp)
                        .testTag("listening_orb_canvas")
                ) {
                    val baseRadius = size.minDimension * 0.29f
                    val levelBoost = if (isListening) smoothedInput * (size.minDimension * 0.22f) else 0f
                    val animatedRadius = baseRadius * (if (liveState.isSessionActive) breathScale else 1f) + levelBoost

                    drawCircle(
                        color = activeColor.copy(alpha = if (isListening) 0.22f else 0.10f),
                        radius = (animatedRadius * 1.32f).coerceAtMost(size.minDimension / 2f)
                    )
                    drawCircle(
                        color = activeColor.copy(alpha = if (isListening) 0.45f else 0.22f),
                        radius = animatedRadius.coerceAtMost(size.minDimension * 0.46f),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
                AriaBrandLogo(
                    size = 56.dp,
                    isAnimated = isListening || isConnecting
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isSpeaking) {
                Icon(
                    imageVector = Icons.Outlined.RecordVoiceOver,
                    contentDescription = null,
                    tint = tertiaryColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = stateCaption,
                style = MaterialTheme.typography.titleSmall,
                color = if (liveState.isSessionActive) activeColor else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }

        if (isSpeaking) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = strings.liveBargeInHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LiveTranscriptCard(
    entries: List<LiveTranscriptEntry>,
    userPartial: String,
    assistantPartial: String,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val listState = rememberLazyListState()

    val totalCount = entries.size +
        (if (userPartial.isNotBlank()) 1 else 0) +
        (if (assistantPartial.isNotBlank()) 1 else 0)

    LaunchedEffect(totalCount, userPartial, assistantPartial) {
        if (totalCount > 0) {
            listState.animateScrollToItem((totalCount - 1).coerceAtLeast(0))
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            shape = RoundedCornerShape(18.dp)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = strings.liveTranscriptTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (totalCount == 0) {
                Text(
                    text = strings.liveTranscriptEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp, horizontal = 8.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 180.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        TranscriptLine(
                            speakerLabel = if (entry.isUser) strings.youLabel else strings.ariaLabel,
                            text = entry.text,
                            isUser = entry.isUser,
                            isPartial = false
                        )
                    }
                    if (userPartial.isNotBlank()) {
                        item(key = "user_partial") {
                            TranscriptLine(
                                speakerLabel = strings.youLabel,
                                text = userPartial,
                                isUser = true,
                                isPartial = true
                            )
                        }
                    }
                    if (assistantPartial.isNotBlank()) {
                        item(key = "assistant_partial") {
                            TranscriptLine(
                                speakerLabel = strings.ariaLabel,
                                text = assistantPartial,
                                isUser = false,
                                isPartial = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptLine(
    speakerLabel: String,
    text: String,
    isUser: Boolean,
    isPartial: Boolean
) {
    val labelColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = speakerLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = labelColor
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isPartial) 0.78f else 1f)
        )
    }
}

package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.localization.AppLanguage
import com.example.core.localization.LocalAppStrings
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.voice.LiveConversationUiState
import com.example.data.voice.LiveSessionState
import com.example.data.voice.VoiceRecognitionEvent
import com.example.data.voice.alwayson.VoiceMonitorState
import com.example.data.voice.alwayson.VoiceMonitorStatus
import com.example.ui.components.AriaBrandLogo
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.ConversationDrawerContent
import com.example.ui.components.StreamingAssistantBubble
import com.example.ui.components.VoiceLiveSheet
import com.example.ui.viewmodel.AiGenerationState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    language: AppLanguage,
    conversations: List<ConversationEntity>,
    activeConversationId: Long?,
    messages: List<MessageEntity>,
    inputDraft: String,
    generationState: AiGenerationState,
    voiceEvent: VoiceRecognitionEvent,
    liveSessionState: LiveSessionState,
    liveConversationState: LiveConversationUiState,
    voiceMonitorState: VoiceMonitorState,
    speakingMessageId: Long?,
    statusNotice: String?,
    onUpdateDraft: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onRetryFailed: () -> Unit,
    onDismissError: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onNewChat: () -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleSpeakMessage: (MessageEntity) -> Unit,
    onExecuteActionTag: (String) -> Unit,
    onStartLiveConversation: () -> Unit,
    onEndLiveConversation: () -> Unit,
    onToggleLiveMute: () -> Unit,
    onStartVoiceListening: () -> Unit,
    onStopVoiceListening: () -> Unit,
    onResetVoiceSession: () -> Unit,
    onRefreshLiveCapabilities: () -> Unit,
    onStopAlwaysOn: () -> Unit,
    onResolveSensitiveAction: (Boolean) -> Unit,
    onClearStatusNotice: () -> Unit,
    onNavigateControls: () -> Unit,
    onNavigateAllApps: () -> Unit,
    onNavigateAlwaysOn: () -> Unit,
    onNavigatePermissions: () -> Unit,
    onNavigateSettings: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showVoiceSheet by remember { mutableStateOf(false) }
    var startLiveOnPermissionGrant by remember { mutableStateOf(true) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        onRefreshLiveCapabilities()
        if (granted) {
            if (startLiveOnPermissionGrant) {
                onStartLiveConversation()
            } else {
                onStartVoiceListening()
            }
        }
    }

    LaunchedEffect(statusNotice) {
        if (!statusNotice.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusNotice)
            onClearStatusNotice()
        }
    }

    val isGenerating = generationState is AiGenerationState.Loading ||
        generationState is AiGenerationState.Streaming

    LaunchedEffect(messages.size, generationState) {
        if (messages.isNotEmpty() || isGenerating) {
            val targetIndex = (messages.size + if (isGenerating) 1 else 0 - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                ConversationDrawerContent(
                    conversations = conversations,
                    activeConversationId = activeConversationId,
                    onNewChat = {
                        onNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onSelectConversation = { id ->
                        onSelectConversation(id)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteConversation = onDeleteConversation,
                    onOpenControls = {
                        scope.launch { drawerState.close() }
                        onNavigateControls()
                    },
                    onOpenAllApps = {
                        scope.launch { drawerState.close() }
                        onNavigateAllApps()
                    },
                    onOpenAlwaysOn = {
                        scope.launch { drawerState.close() }
                        onNavigateAlwaysOn()
                    },
                    onOpenPermissions = {
                        scope.launch { drawerState.close() }
                        onNavigatePermissions()
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onNavigateSettings()
                    }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = strings.openMenu
                            )
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AriaBrandLogo(size = 32.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = strings.appName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = strings.appSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(50))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable { onToggleLanguage() }
                                .testTag("language_toggle_pill")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = language.shortBadge,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        IconButton(
                            onClick = onNavigateControls,
                            modifier = Modifier.testTag("top_controls_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = strings.phoneControls
                            )
                        }

                        IconButton(
                            onClick = onNewChat,
                            modifier = Modifier.testTag("top_new_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddComment,
                                contentDescription = strings.newChat
                            )
                        }
                    }
                )
            },
            bottomBar = {
                ChatComposerBar(
                    draft = inputDraft,
                    isGenerating = isGenerating,
                    onDraftChange = onUpdateDraft,
                    onSend = { onSendMessage(inputDraft) },
                    onMicClick = {
                        hasMicPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        onRefreshLiveCapabilities()
                        showVoiceSheet = true
                    }
                )
            }
        ) { innerPadding ->
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 820.dp)
                ) {
                    val isAlwaysOnActive = voiceMonitorState.isServiceRunning &&
                        voiceMonitorState.status.isServiceActiveState
                    AnimatedVisibility(visible = isAlwaysOnActive) {
                        AlwaysOnIndicatorBanner(
                            language = language,
                            monitorState = voiceMonitorState,
                            onOpenAlwaysOn = onNavigateAlwaysOn,
                            onStopAlwaysOn = onStopAlwaysOn
                        )
                    }

                    val pendingSensitive = voiceMonitorState.pendingSensitiveAction
                    AnimatedVisibility(visible = pendingSensitive != null) {
                        if (pendingSensitive != null) {
                            SensitiveActionConfirmBanner(
                                prompt = pendingSensitive.originalPrompt,
                                onConfirm = { onResolveSensitiveAction(true) },
                                onCancel = { onResolveSensitiveAction(false) }
                            )
                        }
                    }

                    AnimatedVisibility(visible = generationState is AiGenerationState.Error) {
                        val err = generationState as? AiGenerationState.Error
                        if (err != null) {
                            ErrorBanner(
                                message = err.message,
                                onRetry = onRetryFailed,
                                onDismiss = onDismissError
                            )
                        }
                    }

                    if (messages.isEmpty() && !isGenerating) {
                        WelcomeEmptyState(
                            onSuggestionClick = onSendMessage,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                ChatMessageBubble(
                                    message = msg,
                                    isSpeaking = speakingMessageId == msg.id,
                                    onCopy = { text ->
                                        copyToClipboard(context, text)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(strings.copiedNotice)
                                        }
                                    },
                                    onToggleSpeak = onToggleSpeakMessage,
                                    onExecuteActionTag = onExecuteActionTag
                                )
                            }

                            if (isGenerating) {
                                item(key = "streaming_indicator") {
                                    val partial = (generationState as? AiGenerationState.Streaming)?.partialText
                                    StreamingAssistantBubble(partialText = partial)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showVoiceSheet) {
        VoiceLiveSheet(
            hasMicPermission = hasMicPermission,
            voiceEvent = voiceEvent,
            liveState = liveSessionState,
            liveConversationState = liveConversationState,
            onRequestMicPermission = { startLive ->
                startLiveOnPermissionGrant = startLive
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onStartLiveConversation = onStartLiveConversation,
            onEndLiveConversation = onEndLiveConversation,
            onToggleLiveMute = onToggleLiveMute,
            onStartDictationListening = onStartVoiceListening,
            onStopDictationListening = onStopVoiceListening,
            onSendDictationTranscript = { transcript ->
                onSendMessage(transcript)
                onResetVoiceSession()
            },
            onDismiss = {
                if (liveConversationState.isSessionActive) {
                    onEndLiveConversation()
                }
                onResetVoiceSession()
                showVoiceSheet = false
            }
        )
    }
}

@Composable
private fun WelcomeEmptyState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        AriaBrandLogo(size = 88.dp, isAnimated = true)
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = strings.welcomeTitle,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.welcomeSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp)
        )
        Spacer(modifier = Modifier.height(26.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StarterPromptCard(
                    title = strings.suggestionOneTitle,
                    prompt = strings.suggestionOnePrompt,
                    icon = Icons.Outlined.Translate,
                    onClick = { onSuggestionClick(strings.suggestionOnePrompt) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("starter_chip_1")
                )
                StarterPromptCard(
                    title = strings.suggestionTwoTitle,
                    prompt = strings.suggestionTwoPrompt,
                    icon = Icons.Outlined.AutoAwesome,
                    onClick = { onSuggestionClick(strings.suggestionTwoPrompt) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("starter_chip_2")
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StarterPromptCard(
                    title = strings.suggestionThreeTitle,
                    prompt = strings.suggestionThreePrompt,
                    icon = Icons.Outlined.FlashlightOn,
                    onClick = { onSuggestionClick(strings.suggestionThreePrompt) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("starter_chip_3")
                )
                StarterPromptCard(
                    title = strings.suggestionFourTitle,
                    prompt = strings.suggestionFourPrompt,
                    icon = Icons.Outlined.Calculate,
                    onClick = { onSuggestionClick(strings.suggestionFourPrompt) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("starter_chip_4")
                )
            }
        }
    }
}

@Composable
private fun StarterPromptCard(
    title: String,
    prompt: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag("retry_error_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(strings.retry)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = strings.dismiss,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatComposerBar(
    draft: String,
    isGenerating: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit
) {
    val strings = LocalAppStrings.current

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 820.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(26.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mic_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = strings.voiceInput,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = {
                        Text(
                            text = strings.inputPlaceholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank() && !isGenerating) onSend()
                        }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field")
                )

                val canSend = draft.isNotBlank() && !isGenerating
                Button(
                    onClick = onSend,
                    enabled = canSend,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = strings.sendMessage,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Aria Reply", text))
}

@Composable
private fun AlwaysOnIndicatorBanner(
    language: AppLanguage,
    monitorState: VoiceMonitorState,
    onOpenAlwaysOn: () -> Unit,
    onStopAlwaysOn: () -> Unit
) {
    val strings = LocalAppStrings.current
    val isPaused = monitorState.status == VoiceMonitorStatus.PAUSED
    val accent = if (isPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.14f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .border(width = 1.dp, color = accent.copy(alpha = 0.45f), shape = RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenAlwaysOn)
            .testTag("chat_always_on_indicator")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Outlined.Hearing else Icons.Filled.Mic,
                contentDescription = strings.alwaysOnStatusTitle,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPaused) strings.alwaysOnMicPausedPill else strings.alwaysOnMicActivePill,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
                Text(
                    text = if (language == AppLanguage.BENGALI) monitorState.statusMessageBn else monitorState.statusMessageEn,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                onClick = onStopAlwaysOn,
                modifier = Modifier.testTag("chat_stop_always_on_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(strings.stopListening, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SensitiveActionConfirmBanner(
    prompt: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val strings = LocalAppStrings.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.secondary,
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("sensitive_confirm_banner")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.sensitiveConfirmTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("sensitive_confirm_button")
                ) {
                    Text(strings.sensitiveConfirmButton)
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("sensitive_cancel_button")
                ) {
                    Text(strings.sensitiveCancelButton)
                }
            }
        }
    }
}

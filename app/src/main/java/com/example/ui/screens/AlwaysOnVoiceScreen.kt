package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.localization.AppLanguage
import com.example.core.localization.LocalAppStrings
import com.example.data.local.UserPreferences
import com.example.data.voice.alwayson.BatteryOptimizationHelper
import com.example.data.voice.alwayson.VoiceMonitorState
import com.example.data.voice.alwayson.VoiceMonitorStatus
import com.example.data.voice.alwayson.WakeWordDetector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlwaysOnVoiceScreen(
    preferences: UserPreferences,
    monitorState: VoiceMonitorState,
    statusNotice: String?,
    onStartListening: () -> Unit,
    onPauseListening: () -> Unit,
    onResumeListening: () -> Unit,
    onStopListening: () -> Unit,
    onRestartService: () -> Unit,
    onSaveWakeWord: (String) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onToggleVoiceResponse: (Boolean) -> Unit,
    onToggleReturnToMonitoring: (Boolean) -> Unit,
    onRefreshEnvironment: () -> Unit,
    onClearStatusNotice: () -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var wakeWordDraft by remember(preferences.wakeWord) { mutableStateOf(preferences.wakeWord) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotifPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        onRefreshEnvironment()
        if (granted) {
            onStartListening()
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
        onRefreshEnvironment()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasNotifPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                onRefreshEnvironment()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(statusNotice) {
        if (!statusNotice.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusNotice)
            onClearStatusNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("always_on_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = strings.alwaysOnTitle,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = strings.alwaysOnSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
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
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(innerPadding)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .navigationBarsPadding()
            ) {
                // 1. Live Status & Start/Pause/Stop Controls Card
                item {
                    val isMicActive = monitorState.status.isMicrophoneActivelyUsed
                    val isServiceActive = monitorState.isServiceRunning && monitorState.status.isServiceActiveState
                    val isPaused = monitorState.status == VoiceMonitorStatus.PAUSED
                    val isBengali = preferences.language == AppLanguage.BENGALI

                    val statusLabel = when (monitorState.status) {
                        VoiceMonitorStatus.MONITORING,
                        VoiceMonitorStatus.WAKE_DETECTED,
                        VoiceMonitorStatus.CAPTURING_COMMAND,
                        VoiceMonitorStatus.AWAITING_CONFIRMATION,
                        VoiceMonitorStatus.THINKING,
                        VoiceMonitorStatus.SPEAKING -> strings.alwaysOnStatusActive
                        VoiceMonitorStatus.PAUSED -> strings.alwaysOnStatusPaused
                        VoiceMonitorStatus.INTERRUPTED -> strings.alwaysOnStatusInterrupted
                        VoiceMonitorStatus.PERMISSION_DENIED -> strings.alwaysOnStatusDenied
                        VoiceMonitorStatus.IDLE, VoiceMonitorStatus.ERROR -> strings.alwaysOnStatusOff
                    }

                    val statusColor = when (monitorState.status) {
                        VoiceMonitorStatus.MONITORING,
                        VoiceMonitorStatus.WAKE_DETECTED,
                        VoiceMonitorStatus.CAPTURING_COMMAND,
                        VoiceMonitorStatus.THINKING,
                        VoiceMonitorStatus.SPEAKING -> MaterialTheme.colorScheme.tertiary
                        VoiceMonitorStatus.PAUSED,
                        VoiceMonitorStatus.INTERRUPTED,
                        VoiceMonitorStatus.AWAITING_CONFIRMATION -> MaterialTheme.colorScheme.secondary
                        VoiceMonitorStatus.PERMISSION_DENIED,
                        VoiceMonitorStatus.ERROR -> MaterialTheme.colorScheme.error
                        VoiceMonitorStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    val pulseTransition = rememberInfiniteTransition(label = "mic_indicator_pulse")
                    val pulseScale by pulseTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isMicActive) 1.16f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "mic_pulse"
                    )

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = statusColor.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(22.dp)
                            )
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .scale(pulseScale)
                                        .clip(CircleShape)
                                        .background(statusColor.copy(alpha = 0.16f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Mic,
                                        contentDescription = strings.alwaysOnStatusTitle,
                                        tint = statusColor,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.alwaysOnStatusTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = statusColor,
                                        modifier = Modifier.testTag("always_on_status_label")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = if (isBengali) monitorState.statusMessageBn else monitorState.statusMessageEn,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (!monitorState.lastTranscript.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "\"${monitorState.lastTranscript}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (!isServiceActive) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = {
                                            if (!hasMicPermission) {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                onStartListening()
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("always_on_enable_button")
                                    ) {
                                        Icon(Icons.Filled.Mic, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(strings.alwaysOnEnableButton)
                                    }

                                    if (monitorState.status == VoiceMonitorStatus.ERROR ||
                                        monitorState.status == VoiceMonitorStatus.INTERRUPTED
                                    ) {
                                        OutlinedButton(
                                            onClick = onRestartService,
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("always_on_restart_button")
                                        ) {
                                            Icon(Icons.Filled.Refresh, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(strings.alwaysOnRestartButton)
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isPaused) {
                                        Button(
                                            onClick = onResumeListening,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("always_on_resume_button")
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(strings.alwaysOnResumeButton)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = onPauseListening,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("always_on_pause_button")
                                        ) {
                                            Icon(Icons.Filled.Pause, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(strings.alwaysOnPauseButton)
                                        }
                                    }

                                    Button(
                                        onClick = onStopListening,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("always_on_stop_button")
                                    ) {
                                        Icon(Icons.Filled.Stop, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(strings.alwaysOnDisableButton)
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Wake Word Card ("Hello Aria" + custom wake word)
                item {
                    VoiceCard(
                        icon = Icons.Outlined.Hearing,
                        title = strings.wakeWordCardTitle,
                        subtitle = strings.wakeWordCardDesc
                    ) {
                        Text(
                            text = strings.wakeAckPreview,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = wakeWordDraft,
                            onValueChange = { wakeWordDraft = it },
                            label = { Text(strings.wakeWordInputLabel) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wake_word_input")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { onSaveWakeWord(wakeWordDraft) },
                                enabled = wakeWordDraft.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("save_wake_word_button")
                            ) {
                                Text(strings.wakeWordSave)
                            }
                            TextButton(
                                onClick = {
                                    wakeWordDraft = WakeWordDetector.DEFAULT_WAKE_WORD
                                    onSaveWakeWord(WakeWordDetector.DEFAULT_WAKE_WORD)
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("reset_wake_word_button")
                            ) {
                                Text(strings.wakeWordReset)
                            }
                        }
                    }
                }

                // 3. Language & Spoken Response Settings
                item {
                    VoiceCard(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = strings.voiceSectionTitle,
                        subtitle = strings.languageSectionDesc
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AppLanguage.entries.forEach { lang ->
                                val selected = preferences.language == lang
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { onSelectLanguage(lang) }
                                        .testTag("always_on_lang_${lang.code}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Translate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = lang.nativeLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        ToggleSettingRow(
                            title = strings.alwaysOnVoiceResponseTitle,
                            description = strings.alwaysOnVoiceResponseDesc,
                            checked = preferences.alwaysOnVoiceResponseEnabled,
                            onCheckedChange = onToggleVoiceResponse,
                            testTag = "always_on_tts_switch"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ToggleSettingRow(
                            title = strings.alwaysOnReturnToMonitorTitle,
                            description = strings.alwaysOnReturnToMonitorDesc,
                            checked = preferences.resumeMonitoringAfterReply,
                            onCheckedChange = onToggleReturnToMonitoring,
                            testTag = "always_on_resume_switch"
                        )
                    }
                }

                // 4. Permissions Status Card (Microphone & Persistent Notification)
                item {
                    VoiceCard(
                        icon = Icons.Outlined.Notifications,
                        title = strings.permMicTitle,
                        subtitle = strings.permMicDesc
                    ) {
                        StatusBadgeRow(
                            label = "RECORD_AUDIO",
                            badge = if (hasMicPermission) strings.permissionGranted else strings.permissionNotGranted,
                            badgeColor = if (hasMicPermission) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusBadgeRow(
                            label = "POST_NOTIFICATIONS",
                            badge = if (hasNotifPermission) strings.permissionGranted else strings.permissionNotGranted,
                            badgeColor = if (hasNotifPermission) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (!hasMicPermission) {
                                Button(
                                    onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("always_on_grant_mic")
                                ) {
                                    Text(strings.grantPermission)
                                }
                            }
                            if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                OutlinedButton(
                                    onClick = { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("always_on_grant_notif")
                                ) {
                                    Text(strings.alwaysOnAllowNotifications)
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            BatteryOptimizationHelper.createAppDetailsSettingsIntent(context)
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("always_on_app_settings")
                            ) {
                                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.permOpenAppSettings)
                            }
                        }
                    }
                }

                // 5. Battery Optimization & Background Limits
                item {
                    val isUnrestricted = monitorState.isBatteryOptimizationIgnored
                    VoiceCard(
                        icon = Icons.Outlined.BatteryChargingFull,
                        title = strings.alwaysOnBatteryTitle,
                        subtitle = strings.alwaysOnBatteryDesc
                    ) {
                        StatusBadgeRow(
                            label = strings.alwaysOnBatteryTitle,
                            badge = if (isUnrestricted) strings.alwaysOnBatteryUnrestricted else strings.alwaysOnBatteryOptimized,
                            badgeColor = if (isUnrestricted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        BatteryOptimizationHelper.createBatteryOptimizationSettingsIntent()
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("open_battery_settings_button")
                        ) {
                            Text(strings.alwaysOnOpenBatterySettings)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = strings.alwaysOnBackgroundTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = strings.alwaysOnBackgroundDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 6. Privacy & Security Guarantee Card
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(18.dp)
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = strings.alwaysOnPrivacyTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = strings.alwaysOnPrivacyDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun StatusBadgeRow(
    label: String,
    badge: String,
    badgeColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = badgeColor.copy(alpha = 0.16f)
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

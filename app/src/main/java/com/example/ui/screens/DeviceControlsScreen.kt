package com.example.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccessAlarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.localization.LocalAppStrings
import com.example.data.devicecontrol.FlashlightState
import com.example.data.devicecontrol.SupportedAppId
import com.example.data.devicecontrol.SupportedAppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlsScreen(
    flashlightState: FlashlightState,
    supportedApps: List<SupportedAppInfo>,
    installedAppsCount: Int,
    statusNotice: String?,
    onToggleFlashlight: () -> Unit,
    onLaunchApp: (SupportedAppId) -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenPermissions: () -> Unit,
    onClearStatusNotice: () -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val snackbarHostState = remember { SnackbarHostState() }

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
                        modifier = Modifier.testTag("controls_back_button")
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
                            text = strings.controlsScreenTitle,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = strings.controlsScreenSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                item {
                    FlashlightControlCard(
                        flashlightState = flashlightState,
                        onToggle = onToggleFlashlight
                    )
                }

                // Direct navigation cards to All Installed Apps & Permissions Dashboard
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NavigationHubCard(
                            icon = Icons.Outlined.Apps,
                            title = "${strings.browseAllAppsCta} ($installedAppsCount)",
                            subtitle = strings.browseAllAppsDesc,
                            onClick = onOpenAllApps,
                            modifier = Modifier.testTag("controls_open_all_apps_card")
                        )
                        NavigationHubCard(
                            icon = Icons.Outlined.Security,
                            title = strings.openPermissionsCta,
                            subtitle = strings.openPermissionsDesc,
                            onClick = onOpenPermissions,
                            modifier = Modifier.testTag("controls_open_permissions_card")
                        )
                    }
                }

                item {
                    Column {
                        Text(
                            text = strings.supportedAppsTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.supportedAppsSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    val rows = supportedApps.chunked(3)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEach { rowApps ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                rowApps.forEach { appInfo ->
                                    SupportedAppTile(
                                        appInfo = appInfo,
                                        onClick = { onLaunchApp(appInfo.appId) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(3 - rowApps.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                item {
                    Column {
                        Text(
                            text = strings.futureControlsTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.futureControlsSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FutureCapabilityCard(
                            icon = Icons.Outlined.AccessAlarm,
                            title = strings.futureAlarmTitle,
                            description = strings.futureAlarmDesc
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FutureCapabilityCard(
                            icon = Icons.Outlined.MusicNote,
                            title = strings.futureMediaTitle,
                            description = strings.futureMediaDesc
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationHubCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FlashlightControlCard(
    flashlightState: FlashlightState,
    onToggle: () -> Unit
) {
    val strings = LocalAppStrings.current
    val isActive = flashlightState.isSupported && flashlightState.isEnabled

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isActive) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(enabled = flashlightState.isSupported, onClick = onToggle)
            .testTag("flashlight_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    contentDescription = strings.flashlightTitle,
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.flashlightTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                val subtitle = when {
                    !flashlightState.isSupported -> strings.flashlightUnavailable
                    isActive -> strings.flashlightOn
                    else -> strings.flashlightOff
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Switch(
                checked = isActive,
                onCheckedChange = { onToggle() },
                enabled = flashlightState.isSupported,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                    checkedTrackColor = MaterialTheme.colorScheme.tertiary
                ),
                modifier = Modifier.testTag("flashlight_switch")
            )
        }
    }
}

@Composable
private fun SupportedAppTile(
    appInfo: SupportedAppInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val (label, icon) = when (appInfo.appId) {
        SupportedAppId.SETTINGS -> strings.appSettingsName to Icons.Outlined.Settings
        SupportedAppId.PHONE -> strings.appPhoneName to Icons.Outlined.Phone
        SupportedAppId.CALCULATOR -> strings.appCalculatorName to Icons.Outlined.Calculate
        SupportedAppId.CALENDAR -> strings.appCalendarName to Icons.Outlined.CalendarMonth
        SupportedAppId.CAMERA -> strings.appCameraName to Icons.Outlined.CameraAlt
        SupportedAppId.CLOCK -> strings.appClockName to Icons.Outlined.AccessAlarm
        SupportedAppId.BROWSER -> strings.appBrowserName to Icons.Outlined.Public
        SupportedAppId.MESSAGES -> strings.appMessagesName to Icons.Outlined.Message
        SupportedAppId.MAPS -> strings.appMapsName to Icons.Outlined.Map
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (appInfo.isAvailable) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(
                    alpha = if (appInfo.isAvailable) 0.7f else 0.3f
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .testTag("app_tile_${appInfo.appId.tagValue}")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (appInfo.isAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (appInfo.isAvailable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FutureCapabilityCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
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
        }
    }
}

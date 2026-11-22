package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.localization.LocalAppStrings
import com.example.data.devicecontrol.DeviceCommand
import com.example.data.devicecontrol.SupportedAppId
import com.example.data.local.MessageEntity
import com.example.data.local.MessageRole

@Composable
fun ChatMessageBubble(
    message: MessageEntity,
    isSpeaking: Boolean,
    onCopy: (String) -> Unit,
    onToggleSpeak: (MessageEntity) -> Unit,
    onExecuteActionTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            AriaBrandLogo(
                size = 32.dp,
                isAnimated = false,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            val bubbleShape = if (isUser) {
                RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
            } else {
                RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
            }

            if (isUser) {
                Box(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            shape = bubbleShape
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            } else {
                Surface(
                    shape = bubbleShape,
                    color = if (message.isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    tonalElevation = 2.dp,
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = if (message.isError) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                        },
                        shape = bubbleShape
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (message.isError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }

                        val tag = message.actionTag
                        if (!tag.isNullOrBlank()) {
                            val label = resolveActionChipLabel(tag)
                            if (label != null) {
                                AssistChip(
                                    onClick = { onExecuteActionTag(tag) },
                                    label = { Text(label) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Bolt,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        leadingIconContentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .testTag("message_action_chip")
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            IconButton(
                                onClick = { onCopy(message.content) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("copy_message_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = strings.copyText,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onToggleSpeak(message) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("speak_message_button")
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                                    contentDescription = if (isSpeaking) strings.stopSpeaking else strings.speakReply,
                                    tint = if (isSpeaking) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
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
private fun resolveActionChipLabel(tag: String): String? {
    val strings = LocalAppStrings.current
    return when (val cmd = DeviceCommand.fromActionTag(tag)) {
        is DeviceCommand.SetFlashlight -> if (cmd.enabled) strings.actionTurnOnFlash else strings.actionTurnOffFlash
        DeviceCommand.ToggleFlashlight -> strings.flashlightTitle
        is DeviceCommand.OpenApp -> {
            val appName = when (cmd.appId) {
                SupportedAppId.SETTINGS -> strings.appSettingsName
                SupportedAppId.PHONE -> strings.appPhoneName
                SupportedAppId.CALCULATOR -> strings.appCalculatorName
                SupportedAppId.CALENDAR -> strings.appCalendarName
                SupportedAppId.CAMERA -> strings.appCameraName
                SupportedAppId.CLOCK -> strings.appClockName
                SupportedAppId.BROWSER -> strings.appBrowserName
                SupportedAppId.MESSAGES -> strings.appMessagesName
                SupportedAppId.MAPS -> strings.appMapsName
            }
            "${strings.actionOpenAppPrefix} $appName"
        }
        is DeviceCommand.OpenPackage -> "${strings.actionOpenAppPrefix} ${cmd.displayName ?: cmd.packageName.substringAfterLast('.')}"
        is DeviceCommand.OpenAppByName -> "${strings.actionOpenAppPrefix} ${cmd.query}"
        null -> null
    }
}

@Composable
fun StreamingAssistantBubble(
    partialText: String?,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val transition = rememberInfiniteTransition(label = "typing_dots")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        AriaBrandLogo(
            size = 32.dp,
            isAnimated = true,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))

        val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = shape
                )
        ) {
            if (partialText.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(((dotAlpha + index * 0.2f) % 1f).coerceIn(0.25f, 1f))
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = strings.thinkingLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = partialText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

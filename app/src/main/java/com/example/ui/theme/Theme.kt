package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AriaDarkColorScheme = darkColorScheme(
    primary = AriaCyan,
    onPrimary = ObsidianBg,
    primaryContainer = AriaCyanContainer,
    onPrimaryContainer = AriaOnCyanContainer,
    secondary = AriaViolet,
    onSecondary = ObsidianBg,
    secondaryContainer = AriaVioletContainer,
    onSecondaryContainer = AriaOnVioletContainer,
    tertiary = AriaEmerald,
    onTertiary = ObsidianBg,
    tertiaryContainer = AriaEmeraldContainer,
    onTertiaryContainer = TextPrimary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = MidnightSurface,
    onSurface = TextPrimary,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = MidnightSurface,
    surfaceContainer = MidnightSurfaceVariant,
    surfaceContainerHigh = ElevatedCardSurface,
    outline = SubtleBorder,
    outlineVariant = SubtleBorder.copy(alpha = 0.5f),
    error = AriaCoralError,
    onError = ObsidianBg,
    errorContainer = AriaErrorContainer,
    onErrorContainer = AriaOnErrorContainer
)

private val AriaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AriaTheme(
    isBengali: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AriaDarkColorScheme,
        typography = buildAriaTypography(isBengali = isBengali),
        shapes = AriaShapes,
        content = content
    )
}

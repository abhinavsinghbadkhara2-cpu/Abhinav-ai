package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val AbhinavDarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = TextPrimary,
    primaryContainer = NavyCardElevated,
    onPrimaryContainer = ElectricCyan,
    secondary = VibrantPurple,
    onSecondary = TextPrimary,
    secondaryContainer = ColorTokens.PurpleContainer,
    onSecondaryContainer = SoftLavender,
    tertiary = ElectricCyan,
    onTertiary = NavyDeep,
    background = NavyDeep,
    onBackground = TextPrimary,
    surface = NavySurface,
    onSurface = TextPrimary,
    surfaceVariant = NavyCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = NavyDeep,
    surfaceContainerLow = NavySurface,
    surfaceContainer = NavyCard,
    surfaceContainerHigh = NavyCardElevated,
    outline = NavyOutline,
    outlineVariant = NavyOutline.copy(alpha = 0.5f),
    error = ErrorRed,
    onError = TextPrimary,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorRed
)

private object ColorTokens {
    val PurpleContainer = androidx.compose.ui.graphics.Color(0xFF281D4D)
}

val AbhinavShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AbhinavAiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AbhinavDarkColorScheme,
        typography = AbhinavTypography,
        shapes = AbhinavShapes,
        content = content
    )
}

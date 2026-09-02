package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SarnasColorScheme = darkColorScheme(
    primary = AccentGold,
    onPrimary = DarkBackground,
    primaryContainer = AccentGold.copy(alpha = 0.15f),
    onPrimaryContainer = AccentGoldLight,
    secondary = AccentCyan,
    onSecondary = DarkBackground,
    secondaryContainer = AccentCyan.copy(alpha = 0.15f),
    onSecondaryContainer = AccentCyan,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = AccentRose,
    onError = TextPrimary
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SarnasColorScheme,
        typography = Typography,
        content = content
    )
}

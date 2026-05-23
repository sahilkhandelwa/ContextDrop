package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = PrimaryAccent,
    onPrimary = OnPrimaryAccent,
    background = NeutralLightBackground,
    onBackground = OnBackgroundDark,
    surface = NeutralLightSurface,
    onSurface = OnSurfaceDark,
    surfaceVariant = NeutralLightSurfaceVariant,
    onSurfaceVariant = OnSurfaceVariantMuted,
    outline = NeutralLightBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Force Light/White UI
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}

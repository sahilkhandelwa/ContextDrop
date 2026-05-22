package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryElegant,
    onPrimary = OnPrimaryElegant,
    primaryContainer = PrimaryContainerElegant,
    onPrimaryContainer = OnPrimaryContainerElegant,
    background = ElegantBackground,
    onBackground = Color(0xFFE6E1E5),
    surface = ElegantSurface,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = ElegantSurfaceVariant,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = ElegantOutline
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force Dark UI
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

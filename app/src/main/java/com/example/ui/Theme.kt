package com.example.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium Developer-Centric Dark Palette (Zinc/Slate)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFF4F4F5),          // Zinc 100
    onPrimary = Color(0xFF09090B),        // Zinc 950
    primaryContainer = Color(0xFF27272A), // Zinc 800
    onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFFA1A1AA),        // Zinc 400
    onSecondary = Color(0xFF09090B),
    background = Color(0xFF09090B),       // Zinc 950 (Deep Slate Pitch)
    onBackground = Color(0xFFFAFAFA),     // Zinc 50
    surface = Color(0xFF18181B),          // Zinc 900
    onSurface = Color(0xFFF4F4F5),        // Zinc 100
    surfaceVariant = Color(0xFF27272A),   // Zinc 800
    onSurfaceVariant = Color(0xFFD4D4D8), // Zinc 300
    outline = Color(0xFF3F3F46),          // Zinc 700
    error = Color(0xFFEF4444)             // Red 500
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6366F1),          // ContextDrop Purple
    onPrimary = Color(0xFFFAFAFA),        // Zinc 50
    primaryContainer = Color(0xFFEEF2FF), // Very light Indigo/Purple
    onPrimaryContainer = Color(0xFF6366F1),
    secondary = Color(0xFF71717A),        // Zinc 500
    onSecondary = Color(0xFFFAFAFA),
    background = Color(0xFFFAFAFA),       // Zinc 50
    onBackground = Color(0xFF09090B),     // Zinc 950
    surface = Color(0xFFFFFFFF),          // White
    onSurface = Color(0xFF09090B),
    surfaceVariant = Color(0xFFF4F4F5),   // Zinc 100
    onSurfaceVariant = Color(0xFF27272A),
    outline = Color(0xFFE4E4E7),
    error = Color(0xFFEF4444)
)

@Composable
fun ContextDropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

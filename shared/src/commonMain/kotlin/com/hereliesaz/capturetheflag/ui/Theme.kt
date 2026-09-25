package com.hereliesaz.capturetheflag.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Monochrome. The only color in this game is the one you bring. */
private val Ink = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF0A0A0A),
    secondary = Color(0xFF8A8A8A),
    onSecondary = Color(0xFF0A0A0A),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFFFFFF),
    onError = Color(0xFF000000),
)

@Composable
fun CtfTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Ink, content = content)

package com.abodenestly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Material 3 mapped onto the DESIGN tokens. Dynamic colour is not used so both clients look the same. */
@Composable
fun AbodeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val tokens = if (dark) Tokens.Dark else Tokens.Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = tokens.accent, onPrimary = tokens.bg, background = tokens.bg, onBackground = tokens.text,
            surface = tokens.surface, onSurface = tokens.text, surfaceVariant = tokens.surface2,
            onSurfaceVariant = tokens.text2, outline = tokens.line, error = tokens.danger,
            primaryContainer = tokens.accentBg, onPrimaryContainer = tokens.text,
        )
    } else {
        lightColorScheme(
            primary = tokens.accent, onPrimary = tokens.surface, background = tokens.bg, onBackground = tokens.text,
            surface = tokens.surface, onSurface = tokens.text, surfaceVariant = tokens.surface2,
            onSurfaceVariant = tokens.text2, outline = tokens.line, error = tokens.danger,
            primaryContainer = tokens.accentBg, onPrimaryContainer = tokens.text,
        )
    }
    CompositionLocalProvider(LocalTokens provides tokens) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

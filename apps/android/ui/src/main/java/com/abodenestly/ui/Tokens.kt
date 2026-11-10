package com.abodenestly.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** DESIGN A3 colour tokens, light / dark. */
@Immutable
data class Tokens(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val text2: Color,
    val line: Color,
    val accent: Color,
    val accentBg: Color,
    val warn: Color,
    val danger: Color,
) {
    companion object {
        val Light = Tokens(
            bg = Color(0xFFFAF8F5), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF1EDE7),
            text = Color(0xFF1C1A18), text2 = Color(0xFF6B655E), line = Color(0xFFE4DFD7),
            accent = Color(0xFF2F6B5A), accentBg = Color(0xFFE3F0EB), warn = Color(0xFFB8741A), danger = Color(0xFFB3402F),
        )
        val Dark = Tokens(
            bg = Color(0xFF121110), surface = Color(0xFF1C1A18), surface2 = Color(0xFF262320),
            text = Color(0xFFF2EFEA), text2 = Color(0xFFA8A197), line = Color(0xFF2E2B27),
            accent = Color(0xFF6FB79F), accentBg = Color(0xFF1E3A32), warn = Color(0xFFE0A449), danger = Color(0xFFE06A58),
        )

        /** 4-dp grid (DESIGN A5). */
        val ScreenPadding = 16.dp
        val CardRadius = 12.dp
        val MinTouchTarget = 48.dp
    }
}

val LocalTokens = staticCompositionLocalOf { Tokens.Light }

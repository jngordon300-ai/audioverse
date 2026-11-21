package com.jaygordonai.audioverse.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** AudioVerse dark look (from the UI mockup): near-black ground, one amber accent. */
object AV {
    val Ground = Color(0xFF0E0F12)
    val Surface = Color(0xFF181A1F)
    val SurfaceHigh = Color(0xFF22252C)
    val Line = Color(0xFF2A2D35)
    val Text = Color(0xFFF2F2F0)
    val TextMuted = Color(0xFFA3A7B0)   // 8:1 on Ground
    val Amber = Color(0xFFF5A524)
    val OnAmber = Color(0xFF1A1205)
    val Good = Color(0xFF7BD88F)
    val Warn = Color(0xFFFFB4A8)
}

private val Scheme = darkColorScheme(
    primary = AV.Amber,
    onPrimary = AV.OnAmber,
    secondary = AV.Amber,
    onSecondary = AV.OnAmber,
    background = AV.Ground,
    onBackground = AV.Text,
    surface = AV.Surface,
    onSurface = AV.Text,
    surfaceVariant = AV.SurfaceHigh,
    onSurfaceVariant = AV.TextMuted,
    outline = AV.Line,
    error = AV.Warn
)

@Composable
fun AudioVerseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

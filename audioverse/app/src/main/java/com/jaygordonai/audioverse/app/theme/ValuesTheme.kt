package com.jaygordonai.audioverse.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "VALUES" editorial look: cream paper, espresso ink, gold rules, serif display over sans body.
 *
 * The design uses Bodoni Moda / Montserrat / Great Vibes. Until those are bundled under
 * res/font, the platform serif / sans-serif / cursive families stand in.
 */
object Values {
    val Paper = Color(0xFFF5EEDF)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1A140E)
    val InkBody = Color(0xFF3D3426)
    val InkMuted = Color(0xFF5C4E3A)
    val Rule = Color(0xFFD9CBB0)
    val Gold = Color(0xFFB08A3E)      // rules and borders (decorative)
    val GoldText = Color(0xFF8A6A2A)  // gold that passes 4.5:1 on Paper and Card
    val GoldOnInk = Color(0xFFE3C27A) // gold for labels on the Ink band
    val PaperOnInk = Color(0xFFE8DFCC)
    val Pass = Color(0xFF2E6B3A)
    val Fail = Color(0xFF9E2A1F)

    val Display: FontFamily = FontFamily.Serif
    val Body: FontFamily = FontFamily.SansSerif
    val Script: FontFamily = FontFamily.Cursive

    /** Small uppercase, letter-spaced label (masthead lines, status tags). */
    val Kicker = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 3.sp)
}

private val ValuesColors = lightColorScheme(
    primary = Values.Ink,
    onPrimary = Values.Paper,
    secondary = Values.GoldText,
    onSecondary = Values.Card,
    background = Values.Paper,
    onBackground = Values.Ink,
    surface = Values.Card,
    onSurface = Values.Ink,
    surfaceVariant = Values.Paper,
    onSurfaceVariant = Values.InkMuted,
    outline = Values.Gold,
    outlineVariant = Values.Rule,
    error = Values.Fail,
    onError = Values.Card,
)

private val ValuesTypography = Typography(
    displayLarge = TextStyle(fontFamily = Values.Display, fontWeight = FontWeight.ExtraBold, fontSize = 60.sp, lineHeight = 56.sp, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontFamily = Values.Display, fontStyle = FontStyle.Italic, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontFamily = Values.Display, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Values.Display, fontSize = 18.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Values.Body, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Values.Body, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Values.Body, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Values.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 2.sp),
    labelMedium = TextStyle(fontFamily = Values.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 2.sp),
    labelSmall = TextStyle(fontFamily = Values.Body, fontSize = 10.sp, letterSpacing = 2.sp),
)

// The editorial pages are square-cornered throughout.
private val ValuesShapes = Shapes(
    extraSmall = RoundedCornerShape(0),
    small = RoundedCornerShape(0),
    medium = RoundedCornerShape(0),
    large = RoundedCornerShape(0),
    extraLarge = RoundedCornerShape(0),
)

@Composable
fun ValuesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ValuesColors, typography = ValuesTypography, shapes = ValuesShapes, content = content)
}

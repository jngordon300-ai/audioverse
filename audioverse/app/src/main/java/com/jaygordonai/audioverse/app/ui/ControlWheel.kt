package com.jaygordonai.audioverse.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaygordonai.audioverse.app.theme.AV
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * AudioVerse control wheel: a rotary ring with four press zones and a centre button.
 *
 *  - Drag around the ring: one tick per 15 degrees (clockwise = +1), with a haptic click.
 *  - Tap the ring: top = Menu, right = Next, bottom = Play/Pause, left = Previous.
 *  - Tap the centre: Select.
 *
 * All five actions are also exposed as accessibility actions for TalkBack.
 */
@Composable
fun ControlWheel(
    modifier: Modifier = Modifier,
    size: Dp = 236.dp,
    centerLabel: String = "",
    onTick: (Int) -> Unit,
    onMenu: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onCenter: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val tick by rememberUpdatedState(onTick)
    val menu by rememberUpdatedState(onMenu)
    val next by rememberUpdatedState(onNext)
    val prev by rememberUpdatedState(onPrevious)
    val playPause by rememberUpdatedState(onPlayPause)
    val center by rememberUpdatedState(onCenter)
    var pressedZone by remember { mutableStateOf(Zone.NONE) }
    val measurer = rememberTextMeasurer()

    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = "Control wheel. Turn to scroll or change volume."
                customActions = listOf(
                    CustomAccessibilityAction("Play or pause") { playPause(); true },
                    CustomAccessibilityAction("Next track") { next(); true },
                    CustomAccessibilityAction("Previous track") { prev(); true },
                    CustomAccessibilityAction("Menu") { menu(); true },
                    CustomAccessibilityAction("Select") { center(); true },
                    CustomAccessibilityAction("Turn clockwise") { tick(1); true },
                    CustomAccessibilityAction("Turn counter-clockwise") { tick(-1); true }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val outer = this.size.width / 2f
                    val inner = outer * INNER_RATIO
                    val start = down.position - c
                    val r = hypot(start.x, start.y)
                    if (r > outer) return@awaitEachGesture

                    val zone = if (r < inner) Zone.CENTER else zoneFor(start)
                    pressedZone = zone
                    var lastAngle = atan2(start.y, start.x)
                    var accumulated = 0f
                    var travelled = 0f
                    var scrolled = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (zone == Zone.CENTER) continue
                        val p = change.position - c
                        val a = atan2(p.y, p.x)
                        var delta = a - lastAngle
                        if (delta > PI) delta -= (2 * PI).toFloat()
                        if (delta < -PI) delta += (2 * PI).toFloat()
                        lastAngle = a
                        accumulated += delta
                        travelled += abs(delta)
                        while (accumulated >= STEP) { accumulated -= STEP; tick(1); scrolled = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        while (accumulated <= -STEP) { accumulated += STEP; tick(-1); scrolled = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        if (travelled > TAP_SLOP) pressedZone = Zone.NONE
                        change.consume()
                    }
                    if (!scrolled && travelled <= TAP_SLOP) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        when (zone) {
                            Zone.CENTER -> center()
                            Zone.TOP -> menu()
                            Zone.RIGHT -> next()
                            Zone.BOTTOM -> playPause()
                            Zone.LEFT -> prev()
                            Zone.NONE -> Unit
                        }
                    }
                    pressedZone = Zone.NONE
                }
            }
    ) {
        Canvas(Modifier.size(size)) { drawWheel(pressedZone, centerLabel, measurer) }
    }
}

private enum class Zone { NONE, CENTER, TOP, RIGHT, BOTTOM, LEFT }

private const val INNER_RATIO = 0.36f
private const val STEP = 0.2617994f                 // 15 degrees per tick (PI / 12)
private const val TAP_SLOP = 0.12f                  // radians of travel still counted as a tap

/** Screen coordinates: y grows downward, so "top" is around -90 degrees. */
private fun zoneFor(v: Offset): Zone {
    val deg = Math.toDegrees(atan2(v.y, v.x).toDouble())
    return when {
        deg >= -135 && deg < -45 -> Zone.TOP
        deg >= -45 && deg < 45 -> Zone.RIGHT
        deg >= 45 && deg < 135 -> Zone.BOTTOM
        else -> Zone.LEFT
    }
}

private fun DrawScope.drawWheel(pressed: Zone, centerLabel: String, measurer: TextMeasurer) {
    val c = center
    val outer = size.minDimension / 2f
    val inner = outer * INNER_RATIO
    val mid = (outer + inner) / 2f

    // Ring
    drawCircle(AV.Surface, radius = outer, center = c)
    drawCircle(AV.Line, radius = outer, center = c, style = Stroke(width = 2.dp.toPx()))
    if (pressed in setOf(Zone.TOP, Zone.RIGHT, Zone.BOTTOM, Zone.LEFT)) {
        val startAngle = when (pressed) { Zone.TOP -> 225f; Zone.RIGHT -> 315f; Zone.BOTTOM -> 45f; else -> 135f }
        drawArc(
            AV.SurfaceHigh, startAngle, 90f, useCenter = true,
            topLeft = Offset(c.x - outer, c.y - outer),
            size = androidx.compose.ui.geometry.Size(outer * 2, outer * 2)
        )
    }

    // Centre button
    drawCircle(if (pressed == Zone.CENTER) AV.SurfaceHigh else AV.Ground, radius = inner, center = c)
    drawCircle(AV.Line, radius = inner, center = c, style = Stroke(width = 2.dp.toPx()))

    val ink = AV.TextMuted
    // Top: MENU
    val menuText = measurer.measure("MENU", TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp))
    drawText(menuText, topLeft = Offset(c.x - menuText.size.width / 2f, c.y - mid - menuText.size.height / 2f))

    val s = 9.dp.toPx()
    // Right: next (triangle + bar)
    drawTriangle(Offset(c.x + mid - s * 0.6f, c.y), s, pointRight = true, color = ink)
    drawRect(ink, topLeft = Offset(c.x + mid + s * 0.45f, c.y - s), size = androidx.compose.ui.geometry.Size(s * 0.3f, s * 2))
    // Left: previous
    drawRect(ink, topLeft = Offset(c.x - mid - s * 0.75f, c.y - s), size = androidx.compose.ui.geometry.Size(s * 0.3f, s * 2))
    drawTriangle(Offset(c.x - mid + s * 0.6f, c.y), s, pointRight = false, color = ink)
    // Bottom: play / pause
    drawTriangle(Offset(c.x - s * 0.7f, c.y + mid), s * 0.85f, pointRight = true, color = ink)
    drawRect(ink, topLeft = Offset(c.x + s * 0.45f, c.y + mid - s * 0.85f), size = androidx.compose.ui.geometry.Size(s * 0.28f, s * 1.7f))
    drawRect(ink, topLeft = Offset(c.x + s * 0.95f, c.y + mid - s * 0.85f), size = androidx.compose.ui.geometry.Size(s * 0.28f, s * 1.7f))

    if (centerLabel.isNotEmpty()) {
        val t = measurer.measure(centerLabel, TextStyle(color = AV.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f))
    }
}

private fun DrawScope.drawTriangle(centerPt: Offset, s: Float, pointRight: Boolean, color: androidx.compose.ui.graphics.Color) {
    val path = Path().apply {
        if (pointRight) {
            moveTo(centerPt.x - s * 0.6f, centerPt.y - s)
            lineTo(centerPt.x + s * 0.6f, centerPt.y)
            lineTo(centerPt.x - s * 0.6f, centerPt.y + s)
        } else {
            moveTo(centerPt.x + s * 0.6f, centerPt.y - s)
            lineTo(centerPt.x - s * 0.6f, centerPt.y)
            lineTo(centerPt.x + s * 0.6f, centerPt.y + s)
        }
        close()
    }
    drawPath(path, color)
}

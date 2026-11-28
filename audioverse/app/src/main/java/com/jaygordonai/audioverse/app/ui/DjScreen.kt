package com.jaygordonai.audioverse.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaygordonai.audioverse.app.dj.DeckId
import com.jaygordonai.audioverse.app.dj.DeckUi
import com.jaygordonai.audioverse.app.dj.DjMath
import com.jaygordonai.audioverse.app.dj.DjUi
import com.jaygordonai.audioverse.app.dj.DjViewModel
import com.jaygordonai.audioverse.app.dj.SamplerSynth
import com.jaygordonai.audioverse.app.dj.Waveform
import com.jaygordonai.audioverse.app.player.PlayerUi
import com.jaygordonai.audioverse.app.player.Track
import com.jaygordonai.audioverse.app.theme.AV
import com.jaygordonai.audioverse.dsp.CrossfadeCurves
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor

private val DeckBlue = Color(0xFF4FA3FF)
private val DeckRed = Color(0xFFFF5C7A)
private val Bass = Color(0xFF3D7BFF)
private val Panel2 = Color(0xFF121418)
private val CueColors = listOf(
    Color(0xFFFF4D4D), Color(0xFFFF9F1C), Color(0xFFFFE14D), Color(0xFF5BE37D),
    Color(0xFF34D2E0), Color(0xFF4F7BFF), Color(0xFFB36BFF), Color(0xFFFF6BD6)
)
private fun deckColor(id: DeckId) = if (id == DeckId.A) DeckBlue else DeckRed

/**
 * AudioVerse DJ, landscape: scrolling waveforms on top; Deck A | Mixer | Deck B; browser and
 * sampler slide up from the bottom. Forces landscape and hides the system bars while open.
 */
@Composable
fun DjScreen(s: PlayerUi, vm: DjViewModel, onImmersive: (Boolean) -> Unit = {}, onExit: () -> Unit = {}) {
    val d by vm.ui.collectAsStateWithLifecycle()
    var drawer by remember { mutableStateOf<String?>(null) } // "BROWSER" | "SAMPLER"
    val activity = LocalContext.current as? Activity
    val view = LocalView.current

    DisposableEffect(Unit) {
        onImmersive(true)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            onImmersive(false)
        }
    }
    // Keep the screen on while a deck plays (the decks aren't on the lock screen).
    DisposableEffect(d.anyPlaying) {
        view.keepScreenOn = d.anyPlaying
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(d.message) { if (d.message != null) { kotlinx.coroutines.delay(3000); vm.clearMessage() } }
    BackHandler { if (drawer != null) drawer = null else onExit() }

    Box(Modifier.fillMaxSize().background(AV.Ground)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TopBar(d, drawer, onExit = onExit, onDrawer = { drawer = if (drawer == it) null else it; if (it == "SAMPLER") vm.warmSampler() }, onClearCues = vm::toggleClearCues)
            WaveLane(d.a, DeckId.A, Modifier.fillMaxWidth().height(40.dp))
            WaveLane(d.b, DeckId.B, Modifier.fillMaxWidth().height(40.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DeckPanel(d.a, DeckId.A, d.clearCues, vm, Modifier.weight(1f).fillMaxHeight()) { drawer = "BROWSER" }
                MixerPanel(d, vm, Modifier.weight(0.95f).fillMaxHeight())
                DeckPanel(d.b, DeckId.B, d.clearCues, vm, Modifier.weight(1f).fillMaxHeight()) { drawer = "BROWSER" }
            }
        }
        if (drawer != null) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { drawer = null })
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f)) {
                if (drawer == "BROWSER") BrowserPanel(s, onLoad = { id, t -> vm.load(id, t) })
                else SamplerPanel(d.samplerVolume, onPad = vm::pad, onVolume = vm::setSamplerVolume)
            }
        }
    }
}

// ---------------------------------------------------------------- Top bar

@Composable
private fun TopBar(d: DjUi, drawer: String?, onExit: () -> Unit, onDrawer: (String) -> Unit, onClearCues: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("‹ EXIT", AV.TextMuted, onClick = onExit)
        Text("AUDIOVERSE DJ", color = AV.Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val msg = d.message
            if (msg != null) Text(msg, color = AV.Warn, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (d.a.limiting || d.b.limiting) Text("LIMITER ACTIVE", color = AV.Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Pill(if (d.clearCues) "DELETE CUES: ON" else "DELETE CUES", if (d.clearCues) AV.Warn else AV.TextMuted, onClick = onClearCues)
        Pill("BROWSER", if (drawer == "BROWSER") AV.Amber else AV.Text, onClick = { onDrawer("BROWSER") })
        Pill("SAMPLER", if (drawer == "SAMPLER") AV.Amber else AV.Text, onClick = { onDrawer("SAMPLER") })
    }
}

@Composable
private fun Pill(label: String, color: Color, active: Boolean = false, fill: Color? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp))
            .background(fill ?: if (active) color.copy(alpha = 0.22f) else AV.Surface)
            .border(1.dp, if (active) color else AV.Line, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (fill != null) Color.Black else color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

// ---------------------------------------------------------------- Waveforms

/** Scrolling waveform: 8 s window, playhead in the middle, beat grid, cues and loop. */
@Composable
private fun WaveLane(deck: DeckUi, id: DeckId, modifier: Modifier) {
    val color = deckColor(id)
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Panel2).border(1.dp, AV.Line, RoundedCornerShape(6.dp))) {
        val wf = deck.waveform
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val mid = h / 2
            val windowMs = 8000f
            val start = deck.positionMs - windowMs / 2
            fun xOf(ms: Float) = (ms - start) / windowMs * w
            // loop region
            val li = deck.loopIn; val lo = deck.loopOut
            if (li != null && lo != null) drawRect(
                (if (deck.loopActive) AV.Good else AV.TextMuted).copy(alpha = 0.18f),
                topLeft = Offset(xOf(li.toFloat()), 0f), size = Size(xOf(lo.toFloat()) - xOf(li.toFloat()), h)
            )
            if (wf != null) {
                val step = 2f
                var x = 0f
                while (x < w) {
                    val ms = start + x / w * windowMs
                    val i = (ms * Waveform.FPS / 1000f).toInt()
                    if (i in wf.peaks.indices) {
                        val p = wf.peaks[i]; val b = wf.bass[i]
                        val c = lerp(AV.Amber, Bass, b.coerceIn(0f, 1f))
                        drawLine(c, Offset(x, mid - p * mid * 0.95f), Offset(x, mid + p * mid * 0.95f), strokeWidth = step)
                    }
                    x += step
                }
                // beat grid
                if (deck.bpm > 0) {
                    val beat = 60_000f / deck.bpm
                    var k = floor((start - deck.firstBeatMs) / beat)
                    while (true) {
                        val t = deck.firstBeatMs + k * beat
                        if (t > start + windowMs) break
                        val bar = ((k.toInt() % 4) + 4) % 4 == 0
                        drawLine(Color.White.copy(alpha = if (bar) 0.55f else 0.18f), Offset(xOf(t), 0f), Offset(xOf(t), if (bar) h else h * 0.22f), strokeWidth = if (bar) 2f else 1f)
                        k += 1f
                    }
                }
            }
            // hot cues
            deck.cues.forEachIndexed { i, c -> if (c != null) { val x = xOf(c.toFloat()); drawLine(CueColors[i], Offset(x, 0f), Offset(x, h), strokeWidth = 3f) } }
            // playhead
            drawLine(color, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 3f)
        }
        val label = when {
            deck.track == null -> "Deck ${id.name}: load a track"
            deck.track.live -> "Live stream · no waveform or BPM"
            deck.analyzing -> "Analyzing waveform and BPM…"
            wf == null -> "No waveform for this source"
            else -> null
        }
        if (label != null) Text(label, color = AV.TextMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
        Text(id.name, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 2.dp))
    }
}

// ---------------------------------------------------------------- Deck

@Composable
private fun DeckPanel(deck: DeckUi, id: DeckId, clearCues: Boolean, vm: DjViewModel, modifier: Modifier, onLoad: () -> Unit) {
    val color = deckColor(id)
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(AV.Surface).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header: title, BPM, remaining
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(deck.track?.title ?: "Deck ${id.name}", color = AV.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(deck.error ?: deck.track?.artist ?: "Tap LOAD", color = if (deck.error != null) AV.Warn else AV.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (deck.bpm > 0) String.format(Locale.US, "%.1f", deck.liveBpm) else "--.-", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (deck.track?.live == true) "LIVE" else "-" + formatTime((deck.durationMs - deck.positionMs).coerceAtLeast(0)),
                    color = AV.TextMuted, fontSize = 10.sp
                )
            }
            Spacer(Modifier.width(6.dp))
            Pill("LOAD", AV.Amber, onClick = onLoad)
        }
        Overview(deck, color) { vm.seek(id, it) }
        // Platter + transport + pitch
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val mirror = id == DeckId.B
            if (!mirror) Transport(deck, id, vm, color)
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                JogWheel(deck, color, onJog = { vm.jog(id, it) }, onRelease = { vm.jogRelease(id) })
            }
            VFader(
                value = ((deck.tempo - 1f) / DjMath.TEMPO_RANGE).coerceIn(-1f, 1f) * -0.5f + 0.5f,
                onChange = { v -> vm.setTempo(id, 1f + (0.5f - v) * 2f * DjMath.TEMPO_RANGE) },
                onReset = { vm.setTempo(id, 1f) },
                color = color, centre = true,
                label = tempoText(deck.tempo),
                modifier = Modifier.width(30.dp).fillMaxHeight(),
                description = "Deck ${id.name} tempo"
            )
            if (mirror) Transport(deck, id, vm, color)
        }
        // Hot cues
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until 8) {
                val set = deck.cues.getOrNull(i) != null
                Box(
                    Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (set) CueColors[i].copy(alpha = if (clearCues) 0.35f else 0.85f) else Panel2)
                        .border(1.dp, CueColors[i].copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .clickable { vm.hotCue(id, i) }
                        .semantics { contentDescription = "Hot cue ${i + 1}" + if (set) " set" else " empty" },
                    contentAlignment = Alignment.Center
                ) { Text("${i + 1}", color = if (set) Color.Black else AV.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        // Loops
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("IN", AV.Text, onClick = { vm.loopIn(id) }, modifier = Modifier.weight(1f))
            Pill("OUT", AV.Text, onClick = { vm.loopOut(id) }, modifier = Modifier.weight(1f))
            for (b in listOf(1, 2, 4, 8)) Pill("$b", AV.Good, active = deck.loopActive && deck.loopBeats == b && deck.loopIn != null, onClick = { vm.autoLoop(id, b) }, modifier = Modifier.weight(1f))
            Pill(if (deck.loopActive) "LOOP ON" else "LOOP", AV.Good, active = deck.loopActive, onClick = { vm.toggleLoop(id) }, modifier = Modifier.weight(1.6f))
        }
    }
}

@Composable
private fun Transport(deck: DeckUi, id: DeckId, vm: DjViewModel, color: Color) {
    Column(Modifier.width(54.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        RoundKey("SYNC", color, active = false) { vm.sync(id) }
        RoundKey("CUE", AV.Amber, active = false) { vm.cue(id) }
        RoundKey(if (deck.isPlaying) "❚❚" else "▶", AV.Good, active = deck.isPlaying) { vm.playPause(id) }
    }
}

@Composable
private fun RoundKey(label: String, color: Color, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp))
            .background(if (active) color.copy(alpha = 0.3f) else Panel2)
            .border(1.5.dp, color, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

/** Whole-track overview; tap or drag to seek. */
@Composable
private fun Overview(deck: DeckUi, color: Color, onSeek: (Float) -> Unit) {
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(3.dp)).background(Panel2)
            .pointerInput(Unit) {
                detectTapGestures { seek(it.x / size.width) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> seek(change.position.x / size.width) }
            }
    ) {
        val w = size.width; val h = size.height
        val wf = deck.waveform
        if (wf != null && wf.peaks.isNotEmpty()) {
            val cols = (w / 2).toInt().coerceAtLeast(1)
            val per = wf.peaks.size.toFloat() / cols
            for (c in 0 until cols) {
                var m = 0f
                val a = (c * per).toInt(); val b = ((c + 1) * per).toInt().coerceAtMost(wf.peaks.size)
                for (i in a until b) if (wf.peaks[i] > m) m = wf.peaks[i]
                drawLine(AV.TextMuted.copy(alpha = 0.6f), Offset(c * 2f, h / 2 - m * h / 2), Offset(c * 2f, h / 2 + m * h / 2), strokeWidth = 2f)
            }
        }
        if (deck.durationMs > 0) {
            val x = deck.positionMs.toFloat() / deck.durationMs * w
            drawRect(color.copy(alpha = 0.25f), size = Size(x, h))
            drawLine(Color.White, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
            deck.cues.forEachIndexed { i, c -> if (c != null) { val cx = c.toFloat() / deck.durationMs * w; drawLine(CueColors[i], Offset(cx, 0f), Offset(cx, h * 0.4f), strokeWidth = 3f) } }
        }
    }
}

/** Platter: rotates at 33⅓ rpm with the track; drag around the centre to scrub (paused) or bend (playing). */
@Composable
private fun JogWheel(deck: DeckUi, color: Color, onJog: (Float) -> Unit, onRelease: () -> Unit) {
    val jog by rememberUpdatedState(onJog)
    val release by rememberUpdatedState(onRelease)
    var held by remember { mutableStateOf(false) }
    Canvas(
        Modifier.fillMaxHeight().aspectRatio(1f)
            .semantics { contentDescription = "Jog wheel" }
            .pointerInput(Unit) {
                var last = 0f
                detectDragGestures(
                    onDragStart = { p -> held = true; last = atan2(p.y - size.height / 2f, p.x - size.width / 2f) },
                    onDragEnd = { held = false; release() },
                    onDragCancel = { held = false; release() }
                ) { change, _ ->
                    val p = change.position
                    val a = atan2(p.y - size.height / 2f, p.x - size.width / 2f)
                    var delta = Math.toDegrees((a - last).toDouble()).toFloat()
                    if (delta > 180) delta -= 360f
                    if (delta < -180) delta += 360f
                    last = a
                    if (abs(delta) > 0.2f) jog(delta)
                }
            }
    ) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF0B0C0F), r, c)
        drawCircle(if (held) color else color.copy(alpha = 0.55f), r - 2, c, style = Stroke(width = 4f))
        for (k in 1..3) drawCircle(Color.White.copy(alpha = 0.05f), r * (0.45f + k * 0.15f), c, style = Stroke(width = 1f))
        drawCircle(AV.SurfaceHigh, r * 0.32f, c)
        val angle = (deck.positionMs % 1800L) / 1800f * 360f
        rotate(angle, c) {
            drawLine(Color.White, Offset(c.x, c.y - r * 0.92f), Offset(c.x, c.y - r * 0.55f), strokeWidth = 5f)
        }
        // level ring
        drawArc(AV.Good.copy(alpha = 0.8f), -90f, 360f * deck.level.coerceIn(0f, 1f), false, topLeft = Offset(c.x - r * 0.26f, c.y - r * 0.26f), size = Size(r * 0.52f, r * 0.52f), style = Stroke(width = 4f))
    }
}

// ---------------------------------------------------------------- Mixer

@Composable
private fun MixerPanel(d: DjUi, vm: DjViewModel, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(AV.Surface).border(1.dp, AV.Line, RoundedCornerShape(10.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Strip(d.a, DeckId.A, vm, Modifier.weight(1f).fillMaxHeight())
            ChannelFader(d.a, DeckId.A, vm, Modifier.width(34.dp).fillMaxHeight())
            ChannelFader(d.b, DeckId.B, vm, Modifier.width(34.dp).fillMaxHeight())
            Strip(d.b, DeckId.B, vm, Modifier.weight(1f).fillMaxHeight())
        }
        // Crossfader
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", color = DeckBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Slider(
                value = d.fader, onValueChange = vm::setFader, modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(thumbColor = AV.Text, activeTrackColor = AV.Line, inactiveTrackColor = AV.Line)
            )
            Text("B", color = DeckRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("◀ ${d.fadeSeconds}s", DeckBlue, active = d.fading && d.fader > 0.5f, onClick = { vm.autoFade(DeckId.A) }, modifier = Modifier.weight(1f))
            val curves = listOf(CrossfadeCurves.Curve.CONSTANT_POWER to "SMOOTH", CrossfadeCurves.Curve.LINEAR to "LINEAR", CrossfadeCurves.Curve.SHARP_CUT to "CUT")
            val next = curves[(curves.indexOfFirst { it.first == d.curve } + 1).mod(curves.size)].first
            Pill(curves.firstOrNull { it.first == d.curve }?.second ?: "CURVE", AV.Text, onClick = { vm.setCurve(next) }, modifier = Modifier.weight(1f))
            Pill("${d.fadeSeconds}s", AV.TextMuted, onClick = { vm.setFadeSeconds(when (d.fadeSeconds) { 4 -> 8; 8 -> 16; 16 -> 32; else -> 4 }) }, modifier = Modifier.weight(0.7f))
            Pill("${d.fadeSeconds}s ▶", DeckRed, active = d.fading && d.fader <= 0.5f, onClick = { vm.autoFade(DeckId.B) }, modifier = Modifier.weight(1f))
        }
    }
}

/** GAIN / HIGH / MID / LOW / FILTER knobs and the ECHO button for one deck. */
@Composable
private fun Strip(deck: DeckUi, id: DeckId, vm: DjViewModel, modifier: Modifier) {
    val color = deckColor(id)
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
        Knob("GAIN", deck.gainDb / 12f, color, bipolar = true, onChange = { vm.setGain(id, it * 12f) })
        Knob("HIGH", eqNorm(deck.eqHigh), color, bipolar = true, onChange = { vm.setEq(id, 'H', eqDb(it)) })
        Knob("MID", eqNorm(deck.eqMid), color, bipolar = true, onChange = { vm.setEq(id, 'M', eqDb(it)) })
        Knob("LOW", eqNorm(deck.eqLow), color, bipolar = true, onChange = { vm.setEq(id, 'L', eqDb(it)) })
        Knob(filterLabel(deck.filter), deck.filter, AV.Amber, bipolar = true, onChange = { vm.setFilter(id, it) })
        Pill("ECHO", AV.Amber, active = deck.echo, onClick = { vm.toggleEcho(id) })
    }
}

/** EQ knob: centre = 0 dB, right = +6 dB, left = kill (−26 dB). */
private fun eqNorm(db: Float) = if (db >= 0) db / DjMath.EQ_MAX else db / -DjMath.EQ_MIN
private fun eqDb(n: Float) = if (n >= 0) n * DjMath.EQ_MAX else n * -DjMath.EQ_MIN
private fun filterLabel(f: Float) = when { f < -0.02f -> "LPF"; f > 0.02f -> "HPF"; else -> "FILTER" }

@Composable
private fun ChannelFader(deck: DeckUi, id: DeckId, vm: DjViewModel, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        // meter
        Canvas(Modifier.width(5.dp).fillMaxHeight().padding(vertical = 12.dp)) {
            val h = size.height
            drawRect(Panel2, size = size)
            val l = (deck.level * deck.trim).coerceIn(0f, 1f)
            drawRect(if (deck.limiting) AV.Warn else AV.Good, topLeft = Offset(0f, h * (1 - l)), size = Size(size.width, h * l))
        }
        VFader(
            value = deck.trim, onChange = { vm.setTrim(id, it) }, onReset = { vm.setTrim(id, 1f) },
            color = deckColor(id), centre = false, label = "${(deck.trim * 100).toInt()}",
            modifier = Modifier.weight(1f).fillMaxHeight(), description = "Deck ${id.name} volume"
        )
    }
}

/** Rotary knob: drag up/down; double-tap to centre. [value] is −1..1 (bipolar) or 0..1. */
@Composable
private fun Knob(label: String, value: Float, color: Color, bipolar: Boolean, size: Dp = 30.dp, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val v by rememberUpdatedState(value)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(size)
                .semantics { contentDescription = "$label knob" }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(if (bipolar) 0f else 0.5f) }) }
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(onDragStart = { acc = v }) { _, dy ->
                        acc = (acc - dy / 160f * (if (bipolar) 2f else 1f)).coerceIn(if (bipolar) -1f else 0f, 1f)
                        // soft detent at centre
                        change(if (bipolar && abs(acc) < 0.04f) 0f else acc)
                    }
                }
        ) {
            val r = this.size.minDimension / 2
            val c = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Panel2, r, c)
            val start = 135f; val sweep = 270f
            drawArc(AV.Line, start, sweep, false, topLeft = Offset(3f, 3f), size = Size(this.size.width - 6, this.size.height - 6), style = Stroke(width = 4f))
            val norm = if (bipolar) (value + 1f) / 2f else value
            val from = if (bipolar) start + sweep / 2 else start
            val to = start + sweep * norm.coerceIn(0f, 1f)
            drawArc(color, minOf(from, to), abs(to - from), false, topLeft = Offset(3f, 3f), size = Size(this.size.width - 6, this.size.height - 6), style = Stroke(width = 4f))
            rotate(to + 90f, c) { drawLine(AV.Text, Offset(c.x, c.y - r * 0.15f), Offset(c.x, c.y - r * 0.75f), strokeWidth = 3f) }
        }
        Text(label, color = AV.TextMuted, fontSize = 8.sp)
    }
}

/** Vertical fader. [value] 0 (bottom) … 1 (top). Double-tap resets. */
@Composable
private fun VFader(value: Float, onChange: (Float) -> Unit, onReset: () -> Unit, color: Color, centre: Boolean, label: String, modifier: Modifier, description: String) {
    val change by rememberUpdatedState(onChange)
    val reset by rememberUpdatedState(onReset)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = description }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { ch, _ -> change((1f - ch.position.y / size.height).coerceIn(0f, 1f)) }
                    }
            ) {
                val w = size.width; val h = size.height
                drawRoundRect(Panel2, topLeft = Offset(w / 2 - 3, 0f), size = Size(6f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
                if (centre) drawLine(AV.TextMuted, Offset(w * 0.15f, h / 2), Offset(w * 0.85f, h / 2), strokeWidth = 1f)
                val y = (1f - value.coerceIn(0f, 1f)) * (h - 16f) + 8f
                drawRoundRect(color, topLeft = Offset(w * 0.1f, y - 7f), size = Size(w * 0.8f, 14f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
                drawLine(Color.Black, Offset(w * 0.1f, y), Offset(w * 0.9f, y), strokeWidth = 2f)
            }
        }
        Text(label, color = AV.TextMuted, fontSize = 9.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Browser & sampler

/** Songs / Playlists / Radio with search; each row loads to deck A or B. */
@Composable
private fun BrowserPanel(s: PlayerUi, onLoad: (DeckId, Track) -> Unit) {
    var source by remember { mutableStateOf("SONGS") }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val list: List<Track> = remember(source, q, s.tracks, s.stations, s.playlists) {
        when (source) {
            "SONGS" -> s.tracks
            "FAVS" -> s.playlists.flatMap { p -> p.entries }.distinctBy { it.id }.map { e -> s.tracks.firstOrNull { it.id == e.id } ?: e.toTrack() }
            else -> s.stations.filter { it.playable }.map { it.toTrack() }
        }.filter { it.matches(q) }
    }
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(AV.Surface)
            .border(1.dp, AV.Line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .clickable(enabled = false) {}
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((k, l) in listOf("SONGS" to "Songs", "FAVS" to "Playlists", "RADIO" to "Radio")) Pill(l, AV.Amber, active = source == k, onClick = { source = k })
            Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp)).background(Panel2).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search title, artist, album, folder", color = AV.TextMuted, fontSize = 12.sp)
                BasicTextField(
                    value = query, onValueChange = { query = it.take(60) }, singleLine = true,
                    textStyle = TextStyle(color = AV.Text, fontSize = 13.sp), cursorBrush = SolidColor(AV.Amber),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("${list.size}", color = AV.TextMuted, fontSize = 11.sp)
        }
        if (source == "SONGS" && !s.hasPermission) Text("Allow music access in Library first.", color = AV.TextMuted, fontSize = 12.sp)
        if (list.isEmpty()) Text("Nothing here.", color = AV.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Start)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(list, key = { i, t -> "$i:${t.id}" }) { _, t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("A", DeckBlue, onClick = { onLoad(DeckId.A, t) })
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = AV.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), color = AV.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (t.live) "LIVE" else formatTime(t.durationMs), color = AV.TextMuted, fontSize = 11.sp)
                    Pill("B", DeckRed, onClick = { onLoad(DeckId.B, t) })
                }
            }
        }
    }
}

@Composable
private fun SamplerPanel(volume: Float, onPad: (String) -> Unit, onVolume: (Float) -> Unit) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(AV.Surface)
            .border(1.dp, AV.Line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .clickable(enabled = false) {}
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SAMPLER", color = AV.Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.width(12.dp))
            Text("VOL", color = AV.TextMuted, fontSize = 11.sp)
            Slider(value = volume, onValueChange = onVolume, modifier = Modifier.width(180.dp))
            Text("Sounds made in the app · royalty-free", color = AV.TextMuted, fontSize = 11.sp)
        }
        for (row in SamplerSynth.pads.chunked(4)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { i, name ->
                    val c = CueColors[(SamplerSynth.pads.indexOf(name)) % CueColors.size]
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.18f))
                            .border(1.5.dp, c, RoundedCornerShape(10.dp))
                            .pointerInput(name) { detectTapGestures(onPress = { onPad(name) }) },
                        contentAlignment = Alignment.Center
                    ) { Text(name, color = c, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
            }
        }
    }
}

private fun tempoText(t: Float): String {
    val p = (t - 1f) * 100f
    return if (abs(p) < 0.05f) "0%" else String.format(Locale.US, "%+.1f%%", p)
}

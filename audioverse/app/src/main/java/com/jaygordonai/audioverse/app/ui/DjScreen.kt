package com.jaygordonai.audioverse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaygordonai.audioverse.app.dj.DeckId
import com.jaygordonai.audioverse.app.dj.DeckUi
import com.jaygordonai.audioverse.app.dj.DjMath
import com.jaygordonai.audioverse.app.dj.DjViewModel
import com.jaygordonai.audioverse.app.player.PlayerUi
import com.jaygordonai.audioverse.app.player.Track
import com.jaygordonai.audioverse.app.theme.AV
import com.jaygordonai.audioverse.dsp.CrossfadeCurves
import java.util.Locale

/** AudioVerse DJ: two decks and a crossfader. Songs from the phone or live stations. */
@Composable
fun DjScreen(s: PlayerUi, vm: DjViewModel) {
    val d by vm.ui.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<DeckId?>(null) }

    // Keep the screen on while a deck plays (the decks aren't on the lock screen).
    val view = LocalView.current
    DisposableEffect(d.anyPlaying) {
        view.keepScreenOn = d.anyPlaying
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("DJ", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AV.Text, modifier = Modifier.padding(top = 16.dp))
        Text("Two decks · your EQ, ReplayGain and limiter on both", color = AV.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeckCard("A", d.a, vm, DeckId.A, Modifier.weight(1f)) { picking = DeckId.A }
            DeckCard("B", d.b, vm, DeckId.B, Modifier.weight(1f)) { picking = DeckId.B }
        }

        Spacer(Modifier.height(14.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Crossfader", color = AV.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val (ga, gb) = CrossfadeCurves.gains(d.fader, d.curve)
                Text("A ${pct(ga)} · B ${pct(gb)}", color = AV.TextMuted, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", color = if (d.fader < 0.5f) AV.Amber else AV.TextMuted, fontWeight = FontWeight.Bold)
                Slider(
                    value = d.fader, onValueChange = vm::setFader, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = AV.Text, activeTrackColor = AV.Line, inactiveTrackColor = AV.Line)
                )
                Text("B", color = if (d.fader > 0.5f) AV.Amber else AV.TextMuted, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.autoFade(DeckId.A) }, modifier = Modifier.weight(1f)) { Text("← Fade to A", color = AV.Amber) }
                OutlinedButton(onClick = { vm.autoFade(DeckId.B) }, modifier = Modifier.weight(1f)) { Text("Fade to B →", color = AV.Amber) }
            }
            Text(if (d.fading) "Blending…" else "Fade time", color = AV.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            ChipRow(listOf(4, 8, 16, 32).map { it.toString() to "${it}s" }, d.fadeSeconds.toString()) { vm.setFadeSeconds(it.toInt()) }
            Text("Curve", color = AV.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            ChipRow(
                listOf(
                    CrossfadeCurves.Curve.CONSTANT_POWER.name to "Smooth blend",
                    CrossfadeCurves.Curve.LINEAR.name to "Linear",
                    CrossfadeCurves.Curve.SHARP_CUT.name to "Scratch cut"
                ),
                d.curve.name
            ) { vm.setCurve(CrossfadeCurves.Curve.valueOf(it)) }
        }
        Text(
            "Tip: load the next song on the idle deck, press Play, then Fade. The deck that fades out stops by itself.",
            color = AV.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp)
        )
    }

    picking?.let { id -> LoadDialog(s, onPick = { vm.load(id, it); picking = null }, onClose = { picking = null }) }
}

@Composable
private fun DeckCard(label: String, deck: DeckUi, vm: DjViewModel, id: DeckId, modifier: Modifier, onLoad: () -> Unit) {
    Column(
        modifier.background(AV.Surface, RoundedCornerShape(14.dp)).border(1.dp, if (deck.isPlaying) AV.Amber else AV.Line, RoundedCornerShape(14.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DECK $label", color = AV.Amber, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (deck.limiting) Text("LIMIT", color = AV.Warn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        val t = deck.track
        Text(t?.title ?: "Empty", color = AV.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t?.artist ?: "Load a song or station", color = AV.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        deck.error?.let { Text(it, color = AV.Warn, fontSize = 11.sp, maxLines = 2) }

        val live = t?.live == true
        if (!live && deck.durationMs > 0) {
            Slider(
                value = (deck.positionMs.toFloat() / deck.durationMs).coerceIn(0f, 1f),
                onValueChange = { vm.seek(id, it) },
                colors = SliderDefaults.colors(thumbColor = AV.Text, activeTrackColor = AV.Amber, inactiveTrackColor = AV.Line)
            )
            Text(
                "${formatTime(deck.positionMs)} · −${formatTime((deck.durationMs - deck.positionMs).coerceAtLeast(0))}",
                color = AV.TextMuted, fontSize = 11.sp
            )
        } else if (live) {
            Text("● LIVE", color = AV.Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = { vm.playPause(id) }, enabled = t != null, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AV.Amber, contentColor = AV.OnAmber)
            ) { Text(if (deck.isPlaying) "Pause" else "Play", fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallText("Load", onLoad)
            if (!live) SmallText("Cue") { vm.cue(id) }
            if (!live) SmallText("−5s") { vm.nudge(id, -5_000) }
            if (!live) SmallText("+5s") { vm.nudge(id, 5_000) }
        }

        if (!live) {
            Text("Tempo ${tempoText(deck.tempo)}", color = AV.TextMuted, fontSize = 11.sp)
            Slider(
                value = deck.tempo, onValueChange = { vm.setTempo(id, it) },
                valueRange = (1f - DjMath.TEMPO_RANGE)..(1f + DjMath.TEMPO_RANGE),
                colors = SliderDefaults.colors(thumbColor = AV.Text, activeTrackColor = AV.Line, inactiveTrackColor = AV.Line)
            )
            if (deck.tempo != 1f) SmallText("Reset tempo") { vm.setTempo(id, 1f) }
        }
        Text("Level ${pct(deck.trim)}", color = AV.TextMuted, fontSize = 11.sp)
        Slider(
            value = deck.trim, onValueChange = { vm.setTrim(id, it) },
            colors = SliderDefaults.colors(thumbColor = AV.Text, activeTrackColor = AV.Amber, inactiveTrackColor = AV.Line)
        )
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(AV.Surface, RoundedCornerShape(14.dp)).border(1.dp, AV.Line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

/** Pick a phone song (from the library or a playlist) or a live station for a deck. */
@Composable
private fun LoadDialog(s: PlayerUi, onPick: (Track) -> Unit, onClose: () -> Unit) {
    var source by remember { mutableStateOf("SONGS") }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val list: List<Track> = remember(source, q, s.tracks, s.stations, s.playlists) {
        when (source) {
            "SONGS" -> s.tracks
            "FAVS" -> s.playlists.flatMap { p -> p.entries }.distinctBy { it.id }.map { e -> s.tracks.firstOrNull { it.id == e.id } ?: e.toTrack() }
            else -> s.stations.filter { it.playable }.map { it.toTrack() }
        }.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Load deck") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipRow(listOf("SONGS" to "Songs", "FAVS" to "Playlists", "RADIO" to "Radio"), source) { source = it }
                OutlinedTextField(value = query, onValueChange = { query = it.take(60) }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                if (source == "SONGS" && !s.hasPermission) Text("Allow music access in Library first.", color = AV.TextMuted, fontSize = 12.sp)
                if (list.isEmpty()) Text("Nothing to load here.", color = AV.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Start)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(list, key = { i, t -> "$i:${t.id}" }) { _, t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(t) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = AV.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.artist, color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (t.live) "LIVE" else formatTime(t.durationMs), color = AV.TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

private fun pct(v: Float) = "${(v * 100).toInt()}%"
private fun tempoText(t: Float): String {
    val p = (t - 1f) * 100f
    return if (kotlin.math.abs(p) < 0.05f) "0%" else String.format(Locale.US, "%+.1f%%", p)
}

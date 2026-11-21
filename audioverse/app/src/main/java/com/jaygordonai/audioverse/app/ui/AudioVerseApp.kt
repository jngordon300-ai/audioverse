package com.jaygordonai.audioverse.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaygordonai.audioverse.app.gate.GateScreen
import com.jaygordonai.audioverse.app.gate.GateViewModel
import com.jaygordonai.audioverse.app.player.PlayerUi
import com.jaygordonai.audioverse.app.player.PlayerViewModel
import com.jaygordonai.audioverse.app.player.Screen
import com.jaygordonai.audioverse.app.player.Track
import com.jaygordonai.audioverse.app.theme.AV
import com.jaygordonai.audioverse.app.theme.ValuesTheme
import com.jaygordonai.audioverse.app.video.VideoEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AudioVerseApp(vm: PlayerViewModel, gateVm: () -> GateViewModel, videoVm: () -> VideoEditorViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult(it) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        vm.onPermissionResult(granted)
    }

    Surface(color = AV.Ground, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (s.screen) {
                    Screen.LIBRARY -> LibraryScreen(s, vm, onGrant = { launcher.launch(permission) })
                    Screen.NOW_PLAYING -> NowPlayingScreen(s, vm)
                    Screen.VIDEO -> VideoScreen(videoVm())
                    Screen.SOUND -> SoundScreen(s, vm)
                    Screen.DIAGNOSTICS -> ValuesTheme { GateScreen(gateVm()) }
                }
            }
            if (s.screen != Screen.NOW_PLAYING && s.screen != Screen.VIDEO && s.nowPlaying != null) MiniPlayer(s, vm)
            BottomNav(s.screen, vm::show)
        }
    }
}

// ---------------------------------------------------------------- Library

@Composable
private fun LibraryScreen(s: PlayerUi, vm: PlayerViewModel, onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Listen", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AV.Text, modifier = Modifier.padding(top = 16.dp))
        Text(
            if (s.tracks.isEmpty()) "Your music library" else "${s.tracks.size} tracks on this phone",
            color = AV.TextMuted, fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        when {
            !s.hasPermission -> PermissionCard(onGrant)
            s.loading -> Text("Reading your library…", color = AV.TextMuted)
            s.tracks.isEmpty() -> Text("No music files found on this phone.", color = AV.TextMuted)
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(s.highlighted) {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.none { it.index == s.highlighted } ||
                        visible.lastOrNull()?.index == s.highlighted
                    ) listState.animateScrollToItem((s.highlighted - 3).coerceAtLeast(0))
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(s.tracks, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t, highlighted = i == s.highlighted, playing = s.nowPlaying?.id == t.id) { vm.playFrom(i) }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    ControlWheel(
                        size = 190.dp,
                        centerLabel = "PLAY",
                        onTick = vm::wheelTick,
                        onMenu = vm::menu,
                        onNext = vm::next,
                        onPrevious = vm::previous,
                        onPlayPause = vm::playPause,
                        onCenter = vm::wheelCenter
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(AV.Surface, RoundedCornerShape(16.dp)).border(1.dp, AV.Line, RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Allow access to your music", color = AV.Text, fontWeight = FontWeight.SemiBold)
        Text(
            "AudioVerse asks for music and audio files only. It cannot see your photos, videos or documents, and nothing leaves your phone.",
            color = AV.TextMuted, fontSize = 13.sp
        )
        Button(onClick = onGrant) { Text("Allow music access") }
    }
}

@Composable
private fun TrackRow(t: Track, highlighted: Boolean, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) AV.SurfaceHigh else AV.Ground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (playing) AV.Amber else AV.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.artist} · ${t.album}", color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(t.durationMs), color = AV.TextMuted, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- Now Playing

@Composable
private fun NowPlayingScreen(s: PlayerUi, vm: PlayerViewModel) {
    val t = s.nowPlaying
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Artwork(t, Modifier.fillMaxWidth(0.62f).aspectRatio(1f))
        Spacer(Modifier.height(14.dp))
        Text(t?.title ?: "Nothing playing", color = AV.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t?.let { "${it.artist} · ${it.album}" } ?: "Pick a track in Listen", color = AV.TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        var dragging by remember { mutableStateOf<Float?>(null) }
        val fraction = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let(vm::seekTo); dragging = null },
            colors = SliderDefaults.colors(thumbColor = AV.Text, activeTrackColor = AV.Amber, inactiveTrackColor = AV.Line)
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(s.positionMs), color = AV.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(formatTime(s.durationMs), color = AV.TextMuted, fontSize = 12.sp)
        }
        Text(
            s.volumeText ?: s.error ?: soundSummary(s),
            color = if (s.error != null && s.volumeText == null) AV.Warn else AV.TextMuted,
            fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)
        )
        Spacer(Modifier.weight(1f))
        ControlWheel(
            centerLabel = if (s.isPlaying) "PAUSE" else "PLAY",
            onTick = vm::wheelTick,
            onMenu = vm::menu,
            onNext = vm::next,
            onPrevious = vm::previous,
            onPlayPause = vm::playPause,
            onCenter = vm::wheelCenter
        )
        Spacer(Modifier.height(12.dp))
    }
}

private fun soundSummary(s: PlayerUi): String {
    val parts = mutableListOf<String>()
    parts += when (s.sound.replayGain.name) { "OFF" -> "ReplayGain off"; "ALBUM" -> "Album gain"; else -> "Track gain" }
    parts += if (s.sound.eqEnabled && s.sound.eqDb.any { it != 0f }) "EQ on" else "EQ flat"
    if (s.sound.limiterEnabled) parts += if (s.limiterGrDb < -0.1f) "Limiter ${"%.1f".format(s.limiterGrDb)} dB" else "Limiter ready"
    return parts.joinToString(" · ") + "  ·  wheel = volume"
}

@Composable
private fun Artwork(t: Track?, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, t?.id) {
        value = t?.let {
            withContext(Dispatchers.IO) {
                try { context.contentResolver.loadThumbnail(it.uri, Size(720, 720), null) } catch (e: Exception) { null }
            }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(AV.Surface).border(1.dp, AV.Line, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.size(28.dp).background(AV.Amber, RoundedCornerShape(14.dp)))
        }
    }
}

// ---------------------------------------------------------------- Mini player + nav

@Composable
private fun MiniPlayer(s: PlayerUi, vm: PlayerViewModel) {
    val t = s.nowPlaying ?: return
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AV.Surface)
            .clickable { vm.show(Screen.NOW_PLAYING) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, color = AV.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = vm::playPause) { Text(if (s.isPlaying) "Pause" else "Play", color = AV.Amber) }
    }
}

@Composable
private fun BottomNav(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(containerColor = AV.Ground, tonalElevation = 0.dp) {
        listOf(
            Screen.LIBRARY to "Listen",
            Screen.NOW_PLAYING to "Playing",
            Screen.VIDEO to "Video",
            Screen.SOUND to "Sound",
            Screen.DIAGNOSTICS to "Tests"
        ).forEach { (screen, label) ->
            NavigationBarItem(
                selected = current == screen,
                onClick = { onSelect(screen) },
                icon = { Box(Modifier.size(6.dp).background(if (current == screen) AV.Amber else AV.Line, RoundedCornerShape(3.dp))) },
                label = { Text(label, fontSize = 12.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = AV.Amber, unselectedTextColor = AV.TextMuted,
                    indicatorColor = AV.Surface
                )
            )
        }
    }
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

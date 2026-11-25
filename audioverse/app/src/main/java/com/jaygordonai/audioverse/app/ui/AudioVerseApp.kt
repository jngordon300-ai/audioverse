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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
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
import com.jaygordonai.audioverse.app.library.PlaylistOps
import com.jaygordonai.audioverse.app.player.LibGroup
import com.jaygordonai.audioverse.app.player.LibTab
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
fun AudioVerseApp(
    vm: PlayerViewModel,
    gateVm: () -> GateViewModel,
    videoVm: () -> VideoEditorViewModel,
    iptvVm: () -> com.jaygordonai.audioverse.app.iptv.IptvViewModel,
    djVm: () -> com.jaygordonai.audioverse.app.dj.DjViewModel
) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult(it) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        vm.onPermissionResult(granted)
    }

    // Full-screen video (Music TV or a phone video) hides the bars.
    var immersive by remember { mutableStateOf(false) }
    LaunchedEffect(s.screen) { if (s.screen != Screen.VIDEO) immersive = false }

    Surface(color = AV.Ground, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(if (immersive) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (s.screen) {
                    Screen.LIBRARY -> LibraryScreen(s, vm, onGrant = { launcher.launch(permission) })
                    Screen.RADIO -> RadioScreen(s, vm)
                    Screen.NOW_PLAYING -> NowPlayingScreen(s, vm)
                    Screen.DJ -> DjScreen(s, djVm())
                    Screen.VIDEO -> VideoHub(s, videoVm(), iptvVm(), onImmersive = { immersive = it }, onModeHandled = vm::consumeVideoModeRequest, onAddFolder = vm::addFolder)
                    Screen.SOUND -> SoundScreen(s, vm)
                    Screen.DIAGNOSTICS -> ValuesTheme { GateScreen(gateVm()) }
                }
            }
            if (!immersive) {
                if (s.screen != Screen.NOW_PLAYING && s.screen != Screen.VIDEO && s.screen != Screen.DJ && s.nowPlaying != null) MiniPlayer(s, vm)
                BottomNav(s.screen, vm::show)
            }
        }
    }
    s.addTarget?.let { AddToPlaylistDialog(it, s, vm) }
}

// ---------------------------------------------------------------- Shared bits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChipRow(labels: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels, key = { it.first }) { (key, label) ->
            FilterChip(
                selected = key == selected,
                onClick = { onSelect(key) },
                label = { Text(label, fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AV.Surface, labelColor = AV.TextMuted,
                    selectedContainerColor = AV.Amber, selectedLabelColor = AV.OnAmber
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = key == selected,
                    borderColor = AV.Line, selectedBorderColor = AV.Amber
                )
            )
        }
    }
}

@Composable
private fun AddToPlaylistDialog(t: com.jaygordonai.audioverse.app.player.Track, s: PlayerUi, vm: PlayerViewModel) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.askAddToPlaylist(null) },
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.title, color = AV.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                s.playlists.forEach { p ->
                    val inIt = p.entries.any { it.id == t.id }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !inIt) { vm.addTo(p.id, t); vm.askAddToPlaylist(null) }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text(p.name, color = if (inIt) AV.TextMuted else AV.Text, modifier = Modifier.weight(1f))
                        Text(if (inIt) "added" else "${p.entries.size}", color = AV.TextMuted, fontSize = 12.sp)
                    }
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it.take(PlaylistOps.MAX_NAME) },
                    singleLine = true, label = { Text("New playlist") }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { vm.createPlaylist(newName, thenAdd = t); vm.askAddToPlaylist(null) }) {
                Text("Create & add")
            }
        },
        dismissButton = { TextButton(onClick = { vm.askAddToPlaylist(null) }) { Text("Close") } }
    )
}

// ---------------------------------------------------------------- Library

@Composable
private fun LibraryScreen(s: PlayerUi, vm: PlayerViewModel, onGrant: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var managingFolders by remember { mutableStateOf(false) }
    var managingServer by remember { mutableStateOf(false) }
    val openPlaylist = if (s.libTab == LibTab.PLAYLISTS) s.group?.toLongOrNull()?.let { id -> s.playlists.firstOrNull { it.id == id } } else null
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AV.Text, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::rescan, enabled = s.scanning == null) { Text("Rescan", color = AV.Amber) }
            TextButton(onClick = { managingFolders = true }) { Text("Folders", color = AV.Amber) }
            TextButton(onClick = { managingServer = true }) {
                Text(if (s.server.configured) (if (s.server.error == null) "Server ●" else "Server !") else "Server", color = if (s.server.error != null) AV.Warn else AV.Amber)
            }
        }
        Text(
            s.scanning ?: if (s.tracks.isEmpty()) "Your music, playlists and saved stations"
            else "${s.tracks.size} tracks" + (if (s.folders.isNotEmpty()) " · ${s.folders.size} folders" else "") + " · ${s.playlists.size} playlists",
            color = if (s.scanning != null) AV.Amber else AV.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        ChipRow(LibTab.entries.map { it.name to it.label }, s.libTab.name) { vm.setLibTab(LibTab.valueOf(it)) }
        Spacer(Modifier.height(8.dp))

        if (s.group != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.openGroup(null) }) { Text("‹ All ${s.libTab.label.lowercase()}", color = AV.Amber) }
                Spacer(Modifier.weight(1f))
                if (openPlaylist != null && openPlaylist.id != PlaylistOps.FAVORITES_ID) {
                    TextButton(onClick = { vm.deletePlaylist(openPlaylist.id) }) { Text("Delete playlist", color = AV.Warn) }
                }
            }
            Text(openPlaylist?.name ?: s.group, color = AV.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        val needsLibrary = s.libTab != LibTab.PLAYLISTS
        when {
            needsLibrary && !s.hasPermission && s.tracks.isEmpty() -> Column {
                PermissionCard(onGrant)
                TextButton(onClick = { managingFolders = true }) { Text("Or add a folder / USB drive instead", color = AV.Amber) }
            }
            needsLibrary && s.loading -> Text("Reading your library…", color = AV.TextMuted)
            s.libTab != LibTab.SONGS && s.group == null -> {
                LazyColumn(Modifier.weight(1f)) {
                    if (s.libTab == LibTab.PLAYLISTS) item {
                        TextButton(onClick = { creating = true }) { Text("+ New playlist", color = AV.Amber) }
                    }
                    items(s.groups, key = { it.key }) { g -> GroupRow(g) { vm.openGroup(g.key) } }
                    if (s.groups.isEmpty()) item { Text("Nothing here yet.", color = AV.TextMuted) }
                }
            }
            s.visible.isEmpty() -> Text(
                if (openPlaylist != null) "Empty. Tap + on any song or station to add it here." else "No music files found on this phone.",
                color = AV.TextMuted, modifier = Modifier.padding(top = 8.dp)
            )
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(s.highlighted) {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.none { it.index == s.highlighted } ||
                        visible.lastOrNull()?.index == s.highlighted
                    ) listState.animateScrollToItem((s.highlighted - 3).coerceAtLeast(0))
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(s.visible, key = { i, t -> "$i:${t.id}" }) { i, t ->
                        TrackRow(
                            t, highlighted = i == s.highlighted, playing = s.nowPlaying?.id == t.id,
                            onClick = { vm.playFrom(i) },
                            trailing = {
                                if (openPlaylist != null) {
                                    SmallText("↑") { vm.moveInOpenPlaylist(i, -1) }
                                    SmallText("↓") { vm.moveInOpenPlaylist(i, 1) }
                                    SmallText("✕") { vm.removeFromOpenPlaylist(i) }
                                } else {
                                    SmallText("+") { vm.askAddToPlaylist(t) }
                                }
                            }
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    ControlWheel(
                        size = 170.dp,
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
    if (managingFolders) FoldersDialog(s, vm, onClose = { managingFolders = false })
    if (managingServer) ServerDialog(s, vm, onClose = { managingServer = false })
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(PlaylistOps.MAX_NAME) }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.createPlaylist(name); creating = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } }
        )
    }
}

/** Folders from the phone, an SD card or a USB drive (e.g. a Samsung T5), picked one at a time. */
@Composable
private fun FoldersDialog(s: PlayerUi, vm: PlayerViewModel, onClose: () -> Unit) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addFolder) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Music & video folders") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Plug in your drive, tap Add folder and choose its Music (or any) folder. AudioVerse reads only the folders you pick, " +
                        "finds songs (MP3, FLAC, Opus, M4A, WAV…) and videos, and rescans when a drive is plugged in.",
                    color = AV.TextMuted, fontSize = 12.sp
                )
                if (s.folders.isEmpty()) Text("No folders yet.", color = AV.TextMuted, fontSize = 13.sp)
                s.folders.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = AV.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (f.connected) "${f.tracks} songs · ${f.videos} videos" else "Not connected: plug the drive in, then Rescan",
                                color = if (f.connected) AV.TextMuted else AV.Warn, fontSize = 11.sp
                            )
                        }
                        SmallText("✕") { vm.removeFolder(f.uri) }
                    }
                }
                s.scanning?.let { Text(it, color = AV.Amber, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { pickFolder.launch(null) }) { Text("+ Add folder") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
}

@Composable
private fun GroupRow(g: LibGroup, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(g.title, color = AV.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("${g.count}  ›", color = AV.TextMuted, fontSize = 12.sp)
    }
}

@Composable
internal fun SmallText(label: String, onClick: () -> Unit) {
    Text(
        label, color = AV.Amber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    )
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
private fun TrackRow(t: Track, highlighted: Boolean, playing: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
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
        Text(if (t.live) "LIVE" else formatTime(t.durationMs), color = if (t.live) AV.Amber else AV.TextMuted, fontSize = 12.sp)
        trailing()
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
        Text(
            t?.let { if (it.live) it.artist else "${it.artist} · ${it.album}" } ?: "Pick a song in Library or a station in Radio",
            color = AV.TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (t != null) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.toggleFavorite(t) }) {
                Text(if (PlaylistOps.contains(s.playlists, PlaylistOps.FAVORITES_ID, t.id)) "♥ Favorite" else "♡ Favorite", color = AV.Amber)
            }
            TextButton(onClick = { vm.askAddToPlaylist(t) }) { Text("+ Playlist", color = AV.Amber) }
        }
        Spacer(Modifier.height(4.dp))
        if (t?.live == true) {
            Text("● LIVE", color = AV.Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                s.streamTitle ?: "Song titles show here when the station sends them",
                color = if (s.streamTitle != null) AV.Text else AV.TextMuted,
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
            )
        } else LiveOrSeek(s, vm)
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

@Composable
private fun LiveOrSeek(s: PlayerUi, vm: PlayerViewModel) {
    Column {
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
        value = t?.takeIf { !it.live }?.let {
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
            Text(
                if (t.live) "● LIVE · " + (s.streamTitle ?: t.artist) else t.artist,
                color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (t.live) TextButton(onClick = vm::next) { Text("Next", color = AV.TextMuted) }
        TextButton(onClick = vm::playPause) { Text(if (s.isPlaying) "Pause" else "Play", color = AV.Amber) }
    }
}

@Composable
private fun BottomNav(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(containerColor = AV.Ground, tonalElevation = 0.dp) {
        listOf(
            Screen.LIBRARY to "Library",
            Screen.RADIO to "Radio",
            Screen.NOW_PLAYING to "Playing",
            Screen.DJ to "DJ",
            Screen.VIDEO to "Video",
            Screen.SOUND to "Sound"
        ).forEach { (screen, label) ->
            NavigationBarItem(
                selected = current == screen,
                onClick = { onSelect(screen) },
                icon = { Box(Modifier.size(6.dp).background(if (current == screen) AV.Amber else AV.Line, RoundedCornerShape(3.dp))) },
                label = { Text(label, fontSize = 11.sp, maxLines = 1) },
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

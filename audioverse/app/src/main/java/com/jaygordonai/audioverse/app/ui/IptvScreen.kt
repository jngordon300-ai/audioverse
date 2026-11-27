package com.jaygordonai.audioverse.app.ui

import android.graphics.Color as AColor
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.jaygordonai.audioverse.app.iptv.IptvChannel
import com.jaygordonai.audioverse.app.iptv.IptvUi
import com.jaygordonai.audioverse.app.iptv.IptvViewModel
import com.jaygordonai.audioverse.app.iptv.M3uParser
import com.jaygordonai.audioverse.app.theme.AV

private const val ALL = "\u0000all"

/** IPTV: your M3U playlists. [full] is the full-screen state owned by VideoHub. */
@Composable
fun IptvScreen(vm: IptvViewModel, full: Boolean, onFullscreen: (Boolean) -> Unit) {
    val s by vm.ui.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Long?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onBackgrounded() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.onBackgrounded() }
    }

    s.pendingImport?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.confirmImport(false) },
            title = { Text("Import IPTV links?") },
            text = {
                Text(
                    "\"${p.name}\" has " + listOfNotNull(
                        p.playlists.takeIf { it > 0 }?.let { "$it playlist link(s)" },
                        p.channels.takeIf { it > 0 }?.let { "$it channel(s)" }
                    ).joinToString(" and ") + ". Save them on this phone (encrypted)?"
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmImport(true) }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { vm.confirmImport(false) }) { Text("Cancel") } }
        )
    }
    s.notice?.let { n ->
        androidx.compose.runtime.LaunchedEffect(n) { kotlinx.coroutines.delay(4_000); vm.clearNotice() }
    }

    if (full && s.playing != null) {
        TvPlayer(vm, onFullscreen, Modifier.fillMaxSize().background(Color.Black))
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TV", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AV.Text)
                Text(
                    s.notice ?: if (s.sources.isEmpty()) "Your IPTV playlists (M3U)" else "${s.total} channels" + if (s.truncated) " (first ${M3uParser.DEFAULT_LIMIT})" else "",
                    color = if (s.notice != null) AV.Amber else AV.TextMuted, fontSize = 13.sp
                )
            }
            TextButton(onClick = { adding = true }) { Text("+ Add", color = AV.Amber) }
        }

        val mine = s.sources.filter { !it.preset }
        val free = s.sources.filter { it.preset }
        Spacer(Modifier.height(6.dp))
        if (mine.isNotEmpty()) {
            Text("Your playlists", color = AV.TextMuted, fontSize = 11.sp)
            ChipRow(mine.map { it.id.toString() to it.name }, s.selectedId?.toString() ?: "") { vm.select(it.toLong()) }
        }
        if (s.freeListsOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Free channels (no login, third-party)", color = AV.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(top = 4.dp))
                TextButton(onClick = { vm.setFreeLists(false) }) { Text("Turn off", color = AV.TextMuted, fontSize = 11.sp) }
            }
            ChipRow(free.map { it.id.toString() to it.name }, s.selectedId?.toString() ?: "") { vm.select(it.toLong()) }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp).background(AV.Surface, RoundedCornerShape(12.dp)).border(1.dp, AV.Line, RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                Text("Free channel lists", color = AV.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Transmission TV (our curated lineup) plus six public playlists (IPTV-Org, Free-TV, Pluto TV US, Samsung TV Plus US). All third-party and unofficial: " +
                        "AudioVerse doesn't host or vouch for them, channels come and go, and loading them shows your IP address to the list and stream hosts.",
                    color = AV.TextMuted, fontSize = 12.sp
                )
                TextButton(onClick = { vm.setFreeLists(true) }) { Text("Turn on free lists", color = AV.Amber) }
            }
        }

        val current = s.sources.firstOrNull { it.id == s.selectedId }
        if (current == null) {
            Spacer(Modifier.height(10.dp))
            if (mine.isEmpty()) AddSourceCard(vm, onDone = {})
            else Text("Pick a playlist above.", color = AV.TextMuted)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    current.id == com.jaygordonai.audioverse.app.iptv.TransmissionTv.ID ->
                        "AudioVerse's own lineup (Philadelphia, News, Hip-Hop, R&B / Soul, Gospel, African American, Caribbean, Africa), built on this device from public lists. Refreshes every 12 h; Reload to rebuild."
                    current.preset -> "Public list, unofficial and community-run: some channels may be offline"
                    current.plainHttp -> "⚠ This link is http://, so your login travels unencrypted. Use an https link if your provider has one."
                    else -> "Saved encrypted on this device"
                },
                color = if (current.plainHttp) AV.Warn else AV.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = vm::reload) { Text("Reload", color = AV.Amber, fontSize = 12.sp) }
            if (!current.preset) TextButton(onClick = { removing = current.id }) { Text("Remove", color = AV.TextMuted, fontSize = 12.sp) }
        }
        if (s.hiddenAdult > 0 || s.showAdult) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.showAdult) "Adult channels shown" else "${s.hiddenAdult} adult channels hidden",
                color = AV.TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.setShowAdult(!s.showAdult) }) { Text(if (s.showAdult) "Hide" else "Show", color = AV.TextMuted, fontSize = 12.sp) }
        }

        s.playing?.let { ch ->
            TvPlayer(vm, onFullscreen, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ch.name, color = AV.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        s.playError ?: if (s.buffering) "Loading…" else ch.group,
                        color = if (s.playError != null) AV.Warn else AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                SmallText("◀") { vm.step(-1) }
                SmallText("▶") { vm.step(1) }
                SmallText("✕") { vm.stop() }
            }
        }

        when {
            s.loading -> Text("Loading channels…", color = AV.TextMuted, modifier = Modifier.padding(top = 10.dp))
            s.error != null -> Text(s.error!!, color = AV.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            else -> ChannelBrowser(s, vm)
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a playlist") },
            text = { AddSourceCard(vm, onDone = { adding = false }, inDialog = true) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Close") } }
        )
    }
    removing?.let { id ->
        val name = s.sources.firstOrNull { it.id == id }?.name ?: ""
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $name?") },
            text = { Text("Deletes the saved link from this phone. You can add it again later.") },
            confirmButton = { TextButton(onClick = { vm.remove(id); removing = null }) { Text("Remove", color = AV.Warn) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ChannelBrowser(s: IptvUi, vm: IptvViewModel) {
    Column {
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = s.query, onValueChange = vm::setQuery, singleLine = true,
            placeholder = { Text("Search channels") }, modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )
        Spacer(Modifier.height(6.dp))
        val chips = listOf(ALL to "All (${s.total})") + s.groups.map { (g, n) -> g to "$g ($n)" }
        ChipRow(chips, s.group ?: ALL) { vm.setGroup(if (it == ALL) null else it) }
        Spacer(Modifier.height(4.dp))
        if (s.visible.isEmpty()) Text("No channels match.", color = AV.TextMuted, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(s.visible, key = { it.index }) { ch -> ChannelRow(ch, playing = s.playing?.index == ch.index) { vm.play(ch) } }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun ChannelRow(ch: IptvChannel, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (playing) AV.SurfaceHigh else AV.Ground)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = if (playing) AV.Amber else AV.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ch.group, color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (playing) "ON" else "▶", color = AV.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Paste-a-link form. The link is masked by default, typed with a password keyboard
 * (so the keyboard doesn't learn or suggest it), held only in memory and cleared after saving.
 */
@Composable
private fun AddSourceCard(vm: IptvViewModel, onDone: () -> Unit, inDialog: Boolean = false) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addFile(it, displayName(context, it)); onDone() }
    }
    Column(
        (if (inDialog) Modifier else Modifier.fillMaxWidth().background(AV.Surface, RoundedCornerShape(16.dp)).border(1.dp, AV.Line, RoundedCornerShape(16.dp)).padding(16.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!inDialog) Text("Add your IPTV playlist", color = AV.Text, fontWeight = FontWeight.SemiBold)
        Text(
            "Paste your provider's M3U link (for example from Gamma). It's saved encrypted on this phone only and shown by name. It never goes to GitHub or into backups.",
            color = AV.TextMuted, fontSize = 12.sp
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(30) }, singleLine = true,
            label = { Text("Name (e.g. Gamma)") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors()
        )
        OutlinedTextField(
            value = link, onValueChange = { link = it; error = null }, singleLine = true,
            label = { Text("M3U link") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show", color = AV.Amber, fontSize = 12.sp) } }
        )
        if (M3uParser.isPlainHttp(link)) {
            Text("This link is http://: your login will travel unencrypted. Use https if your provider offers it.", color = AV.Warn, fontSize = 12.sp)
        }
        error?.let { Text(it, color = AV.Warn, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = link.isNotBlank(), onClick = {
                val err = vm.addLink(name, link)
                if (err == null) { link = ""; name = ""; show = false; onDone() } else error = err
            }) { Text("Save link") }
            OutlinedButton(onClick = {
                pick.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream"))
            }) { Text("Pick file", color = AV.Amber) }
        }
        TextButton(onClick = { pasting = !pasting }) {
            Text(if (pasting) "Hide paste box" else "Paste a list (M3U text or several links)", color = AV.Amber, fontSize = 13.sp)
        }
        if (pasting) {
            Text(
                "Paste M3U text, or one link per line (optionally \"Name, link\"). Playlist links are saved one by one; single stream links become one channel list. " +
                    "A .txt file of links works with Pick file too.",
                color = AV.TextMuted, fontSize = 11.sp
            )
            OutlinedTextField(
                value = pasted, onValueChange = { pasted = it.take(200_000) },
                label = { Text("Paste here") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Button(enabled = pasted.isNotBlank(), onClick = {
                val msg = vm.importText(pasted, name.ifBlank { "Pasted list" })
                if (!msg.startsWith("No ")) { pasted = ""; name = ""; pasting = false; onDone() } else error = msg
            }) { Text("Import list") }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TvPlayer(vm: IptvViewModel, onFullscreen: (Boolean) -> Unit, modifier: Modifier) {
    val onFull by rememberUpdatedState(onFullscreen)
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShutterBackgroundColor(AColor.BLACK)
                setFullscreenButtonClickListener { isFull -> onFull(isFull) }
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        update = { it.player = vm.player },
        onRelease = { it.player = null },
        modifier = modifier
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AV.Amber, unfocusedBorderColor = AV.Line,
    focusedTextColor = AV.Text, unfocusedTextColor = AV.Text, cursorColor = AV.Amber
)

private fun displayName(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) { null }

package com.jaygordonai.audioverse.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color as AColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.jaygordonai.audioverse.app.iptv.IptvViewModel
import com.jaygordonai.audioverse.app.player.PlayerUi
import com.jaygordonai.audioverse.app.radio.Station
import com.jaygordonai.audioverse.app.radio.StationCatalog
import com.jaygordonai.audioverse.app.theme.AV
import com.jaygordonai.audioverse.app.video.VideoEditorViewModel
import com.jaygordonai.audioverse.app.video.VideoEntry

/** Video tab: Watch (Music TV + phone videos), TV (IPTV / M3U) or Edit (the cut editor). */
@Composable
fun VideoHub(s: PlayerUi, vm: VideoEditorViewModel, iptv: IptvViewModel, onImmersive: (Boolean) -> Unit, onModeHandled: () -> Unit = {}, onAddFolder: (android.net.Uri) -> Unit = {}) {
    val activity = LocalContext.current as? Activity
    var mode by rememberSaveable { mutableStateOf("WATCH") }
    var tvFull by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.videoModeRequest) {
        s.videoModeRequest?.let { mode = it; onModeHandled() }
    }
    // Only the TV mode drives full screen from here; Watch manages its own.
    LaunchedEffect(tvFull) {
        if (mode == "TV") {
            onImmersive(tvFull)
            activity?.requestedOrientation =
                if (tvFull) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler(enabled = tvFull) { tvFull = false }
    Column(Modifier.fillMaxSize()) {
        if (!tvFull) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
            ChipRow(listOf("WATCH" to "Watch", "TV" to "TV", "EDIT" to "Edit"), mode) { mode = it }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                "WATCH" -> WatchScreen(s, vm, onImmersive, onEdit = { mode = "EDIT" }, onAddFolder = onAddFolder)
                "TV" -> IptvScreen(iptv, full = tvFull, onFullscreen = { tvFull = it })
                else -> VideoScreen(vm)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun WatchScreen(s: PlayerUi, vm: VideoEditorViewModel, onImmersive: (Boolean) -> Unit, onEdit: () -> Unit, onAddFolder: (android.net.Uri) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val v by vm.ui.collectAsStateWithLifecycle()
    var channel by rememberSaveable { mutableStateOf<String?>(null) }
    var phoneFull by rememberSaveable { mutableStateOf(false) }
    var webFull by remember { mutableStateOf<View?>(null) }
    var webFullCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.open(it); channel = null; vm.player.play() }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(onAddFolder) }
    var addVideo by remember { mutableStateOf<VideoEntry?>(null) }
    var openList by rememberSaveable { mutableStateOf<Long?>(null) }
    var newList by remember { mutableStateOf(false) }

    val tv = remember(s.stations) { s.stations.filter { it.type == "tv" && it.ytChannel != null } }
    val more = remember(s.stations) { s.stations.filter { it.cat in StationCatalog.watchCats && it.type != "tv" } }

    val full = webFull != null || phoneFull
    LaunchedEffect(full) {
        onImmersive(full)
        activity?.requestedOrientation =
            if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(Unit) {
        onDispose {
            onImmersive(false)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    fun exitFull() {
        webFullCallback?.onCustomViewHidden()
        webFull = null; webFullCallback = null; phoneFull = false
    }
    BackHandler(enabled = full) { exitFull() }

    // Pause the phone video when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onBackgrounded() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Watch", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AV.Text, modifier = Modifier.padding(top = 8.dp))
                Text("Music TV and videos on this phone", color = AV.TextMuted, fontSize = 13.sp)
            }

            // ---- Music TV
            val playing = tv.firstOrNull { it.ytChannel == channel }
            if (playing != null) {
                item {
                    YouTubeEmbed(
                        channelId = playing.ytChannel!!,
                        onFullscreen = { view, cb -> webFull = view; webFullCallback = cb },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(playing.name, color = AV.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { openExternal(context, playing.url) }) { Text("Open on YouTube ↗", color = AV.Amber) }
                        TextButton(onClick = { channel = null }) { Text("Close", color = AV.TextMuted) }
                    }
                }
            }

            // ---- Phone video
            if (v.uri != null && playing == null) {
                item {
                    if (!phoneFull) {
                        PhonePlayer(vm, onFullscreen = { phoneFull = it }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(v.title, color = AV.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { phoneFull = true }) { Text("Full", color = AV.Amber) }
                        TextButton(onClick = { addVideo = VideoEntry(v.uri.toString(), v.title) }) { Text("+ List", color = AV.Amber) }
                        TextButton(onClick = { vm.player.pause(); onEdit() }) { Text("Cut ›", color = AV.Amber) }
                    }
                    if (v.queue.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Up next ${v.queueIndex + 1} of ${v.queue.size}" + (v.queue.getOrNull(v.queueIndex + 1)?.let { " · ${it.name}" } ?: ""),
                            color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        SmallText("◀") { vm.stepQueue(-1) }
                        SmallText("▶") { vm.stepQueue(1) }
                    }
                    v.message?.let { Text(it, color = AV.TextMuted, fontSize = 12.sp) }
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().background(AV.Surface, RoundedCornerShape(16.dp)).border(1.dp, AV.Line, RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Play a video from your phone", color = AV.Text, fontWeight = FontWeight.SemiBold)
                    Text("You pick one file at a time. AudioVerse can't see your other videos or photos.", color = AV.TextMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pick.launch(arrayOf("video/*")) }) { Text(if (v.uri == null) "Open a video" else "Open another") }
                        OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("+ Add video folder", color = AV.Amber) }
                    }
                    Text("Folders can be on the phone, an SD card or a USB drive (e.g. a T5). Videos inside show below.", color = AV.TextMuted, fontSize = 11.sp)
                }
            }

            if (s.folderVideos.isNotEmpty()) {
                item { SectionTitle("Videos from your folders (${s.folderVideos.size})") }
                items(s.folderVideos, key = { "fv:" + it.uri }) { fv ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (v.uri == fv.uri) AV.SurfaceHigh else AV.Ground)
                            .clickable {
                                channel = null
                                // Plays on through the folder's videos.
                                val all = s.folderVideos.map { VideoEntry(it.uri.toString(), it.name) }
                                vm.playQueue(all, s.folderVideos.indexOf(fv))
                            }.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(fv.name, color = if (v.uri == fv.uri) AV.Amber else AV.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fv.folder, color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SmallText("+") { addVideo = VideoEntry(fv.uri.toString(), fv.name) }
                    }
                }
            }

            // ---- Video playlists
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Video playlists") }
                    TextButton(onClick = { newList = true }) { Text("+ New", color = AV.Amber) }
                }
                if (v.playlists.isEmpty()) Text("Tap + on any video to start a playlist.", color = AV.TextMuted, fontSize = 12.sp)
            }
            items(v.playlists, key = { "vp:" + it.id }) { p ->
                Column {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { openList = if (openList == p.id) null else p.id }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.name, color = AV.Text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.items.size}", color = AV.TextMuted, fontSize = 12.sp)
                        if (p.items.isNotEmpty()) SmallText("▶ All") { channel = null; vm.playQueue(p.items, 0) }
                        SmallText(if (openList == p.id) "▴" else "▾") { openList = if (openList == p.id) null else p.id }
                    }
                    if (openList == p.id) {
                        p.items.forEachIndexed { i, e ->
                            Row(
                                Modifier.fillMaxWidth().padding(start = 18.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable { channel = null; vm.playQueue(p.items, i) }.padding(vertical = 6.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(e.name, color = if (v.uri?.toString() == e.uri) AV.Amber else AV.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                SmallText("↑") { vm.moveInPlaylist(p.id, i, -1) }
                                SmallText("↓") { vm.moveInPlaylist(p.id, i, 1) }
                                SmallText("✕") { vm.removeFromPlaylist(p.id, i) }
                            }
                        }
                        TextButton(onClick = { vm.deletePlaylist(p.id); openList = null }) { Text("Delete playlist", color = AV.Warn, fontSize = 12.sp) }
                    }
                }
            }

            if (tv.isNotEmpty()) {
                item { SectionTitle("Music TV") }
                items(tv, key = { "tv:" + it.url }) { st ->
                    WatchRow(st, active = st.ytChannel == channel, badge = "PLAY") {
                        vm.player.pause(); channel = st.ytChannel
                    }
                }
            }
            if (more.isNotEmpty()) {
                item { SectionTitle("More to watch") }
                items(more, key = { "w:" + it.url + it.name }) { st ->
                    WatchRow(st, active = false, badge = "Open ↗") { openExternal(context, st.url) }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        addVideo?.let { e ->
            var name by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { addVideo = null },
                title = { Text("Add to video playlist") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(e.name, color = AV.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        v.playlists.forEach { p ->
                            val inIt = p.items.any { it.uri == e.uri }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !inIt) { vm.addToPlaylist(p.id, e); addVideo = null }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {
                                Text(p.name, color = if (inIt) AV.TextMuted else AV.Text, modifier = Modifier.weight(1f))
                                Text(if (inIt) "added" else "${p.items.size}", color = AV.TextMuted, fontSize = 12.sp)
                            }
                        }
                        androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("New playlist") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.createPlaylist(name, e); addVideo = null }) { Text("Create & add") } },
                dismissButton = { TextButton(onClick = { addVideo = null }) { Text("Close") } }
            )
        }
        if (newList) {
            var name by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { newList = false },
                title = { Text("New video playlist") },
                text = { androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") }) },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.createPlaylist(name); newList = false }) { Text("Create") } },
                dismissButton = { TextButton(onClick = { newList = false }) { Text("Cancel") } }
            )
        }

        // Full-screen layers sit on top so the WebView underneath keeps playing.
        webFull?.let { view ->
            AndroidView(
                factory = { view.apply { (parent as? ViewGroup)?.removeView(this) } },
                modifier = Modifier.fillMaxSize().background(Color.Black)
            )
        }
        if (phoneFull && v.uri != null) {
            PhonePlayer(vm, onFullscreen = { phoneFull = it }, modifier = Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = AV.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun WatchRow(st: Station, active: Boolean, badge: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (active) AV.SurfaceHigh else AV.Ground)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(st.name, color = if (active) AV.Amber else AV.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(st.city, st.tags).filter { it.isNotBlank() }.joinToString(" · "), color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(badge, color = AV.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Plain player with the standard controls, sharing the editor's player (so "Cut it" keeps the place). */
@OptIn(UnstableApi::class)
@Composable
private fun PhonePlayer(vm: VideoEditorViewModel, onFullscreen: (Boolean) -> Unit, modifier: Modifier) {
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

/**
 * Plays a YouTube channel's uploads through the privacy-enhanced (no-cookie) embed.
 * Only the embed runs here: any link the player opens goes to the YouTube app or browser.
 * No JavaScript bridge, no file or content access.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeEmbed(
    channelId: String,
    onFullscreen: (View?, WebChromeClient.CustomViewCallback?) -> Unit,
    modifier: Modifier
) {
    val onFull by rememberUpdatedState(onFullscreen)
    var web by remember { mutableStateOf<WebView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> web?.onPause()
                Lifecycle.Event.ON_START -> web?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(AColor.BLACK)
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) { onFull(view, callback) }
                    override fun onHideCustomView() { onFull(null, null) }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (request?.isForMainFrame == true) { openExternal(c, request.url.toString()); return true }
                        return false
                    }
                }
                web = this
            }
        },
        update = { wv ->
            if (wv.tag != channelId) {
                wv.tag = channelId
                wv.loadDataWithBaseURL("https://appassets.androidplatform.net/", embedHtml(channelId), "text/html", "utf-8", null)
            }
        },
        onRelease = { wv -> wv.stopLoading(); wv.destroy(); web = null },
        modifier = modifier
    )
}

/** Uploads playlist of a channel: UC… → UU…. Channel ids are letters, digits, - and _ only. */
private fun embedHtml(channelId: String): String {
    val safe = channelId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    val list = "UU" + safe.removePrefix("UC")
    return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head><body>
<iframe src="https://www.youtube-nocookie.com/embed/videoseries?list=$list&playsinline=1&rel=0"
 allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen
 referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>"""
}

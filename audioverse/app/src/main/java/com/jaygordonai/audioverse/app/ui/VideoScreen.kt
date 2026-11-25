package com.jaygordonai.audioverse.app.ui

import android.graphics.Color as AColor
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.jaygordonai.audioverse.app.theme.AV
import com.jaygordonai.audioverse.app.video.CutTemplate
import com.jaygordonai.audioverse.app.video.Segment
import com.jaygordonai.audioverse.app.video.VideoEditorViewModel
import com.jaygordonai.audioverse.app.video.VideoUi
import com.jaygordonai.audioverse.app.video.shortTime

@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(vm: VideoEditorViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    var renaming by remember { mutableStateOf<Segment?>(null) }

    // Pause when the app goes to the background or the user leaves this tab (exports keep running).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onBackgrounded() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.onBackgrounded() }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Edit", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AV.Text)
                    Text(if (s.uri == null) "Play and cut long videos" else s.title, color = AV.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { pick.launch(arrayOf("video/*")) }) { Text(if (s.uri == null) "Open" else "Open other", color = AV.Amber) }
            }
        }

        if (s.uri == null) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(AV.Surface, RoundedCornerShape(16.dp)).border(1.dp, AV.Line, RoundedCornerShape(16.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Cut a game or a card into parts", color = AV.Text, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Open a video, pick Basketball, Football or Wrestling, then mark where each quarter or match starts and ends. " +
                            "Export saves each part as its own MP4 in Movies/AudioVerse. The original file is never changed.",
                        color = AV.TextMuted, fontSize = 13.sp
                    )
                    Button(onClick = { pick.launch(arrayOf("video/*")) }) { Text("Open a video") }
                }
            }
            return@LazyColumn
        }

        item {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        setShutterBackgroundColor(AColor.BLACK)
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { it.player = vm.player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            )
        }

        item { Timeline(s, onSeek = { f -> vm.seekTo((s.durationMs * f).toLong()) }) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${shortTime(s.positionMs)} / ${shortTime(s.durationMs)}", color = AV.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::cycleSpeed) { Text("${trim(s.speed)}×", color = AV.Amber) }
                TextButton(onClick = vm::toggleCoarse) { Text(if (s.coarse) "Wheel 10 s" else "Wheel 1 s", color = AV.Amber) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SmallBtn("−10s") { vm.seekBy(-10_000) }
                SmallBtn("◀ frame") { vm.stepFrame(-1) }
                SmallBtn(if (s.isPlaying) "Pause" else "Play") { vm.playPause() }
                SmallBtn("frame ▶") { vm.stepFrame(1) }
                SmallBtn("+10s") { vm.seekBy(10_000) }
            }
        }

        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CutTemplate.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = s.template == t, onClick = { vm.setTemplate(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, CutTemplate.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = AV.SurfaceHigh, activeContentColor = AV.Text,
                            inactiveContainerColor = AV.Surface, inactiveContentColor = AV.TextMuted,
                            activeBorderColor = AV.Line, inactiveBorderColor = AV.Line
                        )
                    ) { Text(t.label, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }

        item {
            val nextName = s.template.nameFor(s.segments.size)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::markStart, modifier = Modifier.weight(1f)) { Text("Mark start") }
                Button(
                    onClick = vm::markEnd, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AV.SurfaceHigh, contentColor = AV.Text)
                ) { Text("End $nextName") }
            }
            s.pendingStartMs?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$nextName starts at ${shortTime(it)}", color = AV.Amber, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::cancelPending) { Text("Clear", color = AV.TextMuted) }
                }
            }
        }

        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ControlWheel(
                    size = 180.dp,
                    centerLabel = if (s.pendingStartMs == null) "START" else "END",
                    onTick = vm::wheelTick,
                    onMenu = vm::toggleCoarse,
                    onNext = { vm.jumpMarker(1) },
                    onPrevious = { vm.jumpMarker(-1) },
                    onPlayPause = vm::playPause,
                    onCenter = vm::markToggle
                )
            }
            Text(
                "Turn to scrub · Menu = 1 s / 10 s · ◀ ▶ = jump between marks · centre = mark start, then end",
                color = AV.TextMuted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        if (s.segments.isNotEmpty()) {
            item { Text("Segments (${s.segments.size})", color = AV.Text, fontWeight = FontWeight.SemiBold) }
            items(s.segments, key = { it.id }) { seg ->
                SegmentRow(
                    seg, selected = seg.id == s.selectedId,
                    onClick = { vm.select(if (seg.id == s.selectedId) null else seg.id) },
                    vm = vm, onRename = { renaming = seg }
                )
            }
        }

        item {
            ExportPanel(s, onExport = vm::exportAll, onCancel = vm::cancelExport, onCopyScript = {
                clipboard.setText(AnnotatedString(vm.buildFfmpegScript()))
            })
        }

        item {
            s.message?.let {
                Text(it, color = AV.TextMuted, fontSize = 12.sp, modifier = Modifier.clickable(onClick = vm::clearMessage).padding(bottom = 24.dp))
            } ?: Box(Modifier.height(24.dp))
        }
    }

    renaming?.let { seg ->
        var text by remember(seg.id) { mutableStateOf(seg.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename segment") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(seg.id, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Timeline(s: VideoUi, onSeek: (Float) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(s.durationMs) { detectTapGestures { p -> if (size.width > 0) onSeek((p.x / size.width).coerceIn(0f, 1f)) } }
    ) {
        val d = s.durationMs.coerceAtLeast(1).toFloat()
        val barY = size.height / 2f - 6.dp.toPx()
        val barH = 12.dp.toPx()
        drawRect(AV.Line, topLeft = Offset(0f, barY), size = Size(size.width, barH))
        s.segments.forEach { seg ->
            val x0 = seg.startMs / d * size.width
            val x1 = seg.endMs / d * size.width
            val sel = seg.id == s.selectedId
            drawRect(if (sel) AV.Amber else AV.Amber.copy(alpha = 0.45f), topLeft = Offset(x0, barY), size = Size((x1 - x0).coerceAtLeast(2f), barH))
        }
        s.pendingStartMs?.let { p ->
            val x = p / d * size.width
            drawRect(AV.Good, topLeft = Offset(x - 1.5f, 0f), size = Size(3f, size.height))
        }
        val px = s.positionMs / d * size.width
        drawRect(AV.Text, topLeft = Offset(px - 1.5f, 0f), size = Size(3f, size.height))
    }
}

@Composable
private fun SegmentRow(seg: Segment, selected: Boolean, onClick: () -> Unit, vm: VideoEditorViewModel, onRename: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AV.SurfaceHigh else AV.Surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(seg.name, color = AV.Amber, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${shortTime(seg.startMs)} – ${shortTime(seg.endMs)}  (${shortTime(seg.durationMs)})", color = AV.TextMuted, fontSize = 12.sp)
        }
        if (selected) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SmallBtn("Start −1s") { vm.nudge(seg.id, -1_000, 0) }
                SmallBtn("Start +1s") { vm.nudge(seg.id, 1_000, 0) }
                SmallBtn("End −1s") { vm.nudge(seg.id, 0, -1_000) }
                SmallBtn("End +1s") { vm.nudge(seg.id, 0, 1_000) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SmallBtn("Start here") { vm.setStartHere(seg.id) }
                SmallBtn("End here") { vm.setEndHere(seg.id) }
                SmallBtn("Rename") { onRename() }
                SmallBtn("Delete") { vm.delete(seg.id) }
            }
        }
    }
}

@Composable
private fun ExportPanel(s: VideoUi, onExport: () -> Unit, onCancel: () -> Unit, onCopyScript: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(AV.Surface, RoundedCornerShape(16.dp)).border(1.dp, AV.Line, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Export", color = AV.Text, fontWeight = FontWeight.SemiBold)
        val e = s.export
        if (e.running) {
            Text("Saving ${e.currentName} (${e.index} of ${e.total}) · ${e.percent}%", color = AV.Text, fontSize = 13.sp)
            LinearProgressIndicator(progress = { e.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = AV.Amber, trackColor = AV.Line)
            Text("Keep AudioVerse open until it finishes.", color = AV.TextMuted, fontSize = 12.sp)
            OutlinedButton(onClick = onCancel) { Text("Cancel", color = AV.Amber) }
        } else {
            Button(onClick = onExport, enabled = s.segments.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (s.segments.isEmpty()) "Mark segments to export" else "Export ${s.segments.size} segment(s) as MP4")
            }
            OutlinedButton(onClick = onCopyScript, enabled = s.segments.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Copy ffmpeg script for the Mac", color = AV.Amber)
            }
            Text(
                "Phone export: exact cuts, mostly copied without re-encoding when the video is MP4. " +
                    "Mac script: fully lossless, but cuts snap to the nearest keyframe.",
                color = AV.TextMuted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = AV.Text, fontSize = 12.sp) }
}

private fun trim(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

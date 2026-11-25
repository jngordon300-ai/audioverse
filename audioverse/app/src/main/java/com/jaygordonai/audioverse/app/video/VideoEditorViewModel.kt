package com.jaygordonai.audioverse.app.video

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ExportStatus(
    val running: Boolean = false,
    val currentName: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val percent: Int = 0,
    val done: List<String> = emptyList(),
    val failed: List<String> = emptyList()
)

data class VideoUi(
    val uri: Uri? = null,
    /** Video playlists, and the one playing now (Up next). */
    val playlists: List<VideoPlaylist> = emptyList(),
    val queue: List<VideoEntry> = emptyList(),
    val queueIndex: Int = -1,
    val title: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val coarse: Boolean = false,
    val template: CutTemplate = CutTemplate.BASKETBALL,
    val segments: List<Segment> = emptyList(),
    val pendingStartMs: Long? = null,
    val selectedId: Long? = null,
    val frameMs: Long = 33,
    val export: ExportStatus = ExportStatus(),
    val message: String? = null
)

/**
 * AudioVerse Video: plays one long video and builds a list of segments
 * (quarters, matches, parts) that export as separate MP4 files.
 * Uses its own ExoPlayer, separate from the music engine.
 */
@OptIn(UnstableApi::class)
class VideoEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val _ui = MutableStateFlow(VideoUi())
    val ui: StateFlow<VideoUi> = _ui.asStateFlow()
    private var exportJob: Job? = null
    private var nextId = System.currentTimeMillis()

    // Handles audio focus, so starting a video pauses music or radio (and the reverse).
    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true
        )
        .build().apply {
        setSeekParameters(SeekParameters.EXACT) // frame-accurate marks
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = _ui.update { it.copy(isPlaying = isPlaying) }
            override fun onPlayerError(error: PlaybackException) = _ui.update {
                it.copy(message = "Can't play this video (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playNextInQueue()
                if (state == Player.STATE_READY) {
                    val fps = videoFormat?.frameRate?.takeIf { it > 1f } ?: 30f
                    _ui.update { it.copy(durationMs = duration.takeIf { d -> d != C.TIME_UNSET } ?: 0, frameMs = (1000f / fps).toLong().coerceAtLeast(1)) }
                }
            }
        })
    }

    init {
        viewModelScope.launch {
            while (isActive) {
                _ui.update { it.copy(positionMs = player.currentPosition.coerceAtLeast(0)) }
                delay(200)
            }
        }
    }

    // ---------------- Open ----------------
    fun open(uri: Uri) {
        // Keep read access across restarts so a saved cut list can reopen its video.
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        val title = displayName(uri)
        val saved = CutListStore.load(ctx, uri.toString())
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        _ui.update {
            VideoUi(
                uri = uri, title = title, playlists = it.playlists,
                template = saved?.first ?: it.template,
                segments = saved?.second ?: emptyList(),
                message = saved?.let { s -> "Restored ${s.second.size} saved segment(s)" }
            )
        }
    }

    private fun displayName(uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null } ?: "video.mp4"

    val baseName: String get() = _ui.value.title.substringBeforeLast('.')

    // ---------------- Transport ----------------
    fun playPause() { if (player.isPlaying) player.pause() else player.play() }
    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)
    fun seekTo(ms: Long) {
        val d = _ui.value.durationMs
        player.seekTo(if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0))
        _ui.update { it.copy(positionMs = player.currentPosition) }
    }
    fun stepFrame(dir: Int) { player.pause(); seekBy(dir * _ui.value.frameMs) }
    fun cycleSpeed() {
        val next = when (_ui.value.speed) { 1f -> 2f; 2f -> 4f; 4f -> 0.5f; else -> 1f }
        player.playbackParameters = PlaybackParameters(next)
        _ui.update { it.copy(speed = next) }
    }
    fun toggleCoarse() = _ui.update { it.copy(coarse = !it.coarse) }

    /** Wheel turn = scrub: 1 s per tick, or 10 s per tick in coarse mode. */
    fun wheelTick(dir: Int) = seekBy(dir * (if (_ui.value.coarse) 10_000L else 1_000L))

    /** Wheel left/right jump between segment boundaries. */
    fun jumpMarker(dir: Int) {
        val pos = player.currentPosition
        val marks = (_ui.value.segments.flatMap { listOf(it.startMs, it.endMs) } + listOfNotNull(_ui.value.pendingStartMs, 0L, _ui.value.durationMs))
            .distinct().sorted()
        val target = if (dir > 0) marks.firstOrNull { it > pos + 500 } else marks.lastOrNull { it < pos - 500 }
        target?.let(::seekTo)
    }

    // ---------------- Marking ----------------
    fun setTemplate(t: CutTemplate) { _ui.update { it.copy(template = t) }; persist() }

    /** Centre button: first press marks a start, second press closes the segment. */
    fun markToggle() { if (_ui.value.pendingStartMs == null) markStart() else markEnd() }

    fun markStart() = _ui.update { it.copy(pendingStartMs = player.currentPosition, message = "Start marked at ${shortTime(player.currentPosition)}") }

    fun markEnd() {
        val end = player.currentPosition
        val s = _ui.value
        val start = s.pendingStartMs ?: s.segments.maxOfOrNull { it.endMs }?.takeIf { it < end } ?: 0L
        if (end - start < 1_000) {
            _ui.update { it.copy(message = "End must be at least 1 second after the start") }
            return
        }
        val seg = Segment(nextId++, s.template.nameFor(s.segments.size), start, end)
        _ui.update {
            it.copy(
                segments = (it.segments + seg).sortedBy { x -> x.startMs },
                pendingStartMs = null, selectedId = seg.id,
                message = "${seg.name}: ${shortTime(start)} – ${shortTime(end)}"
            )
        }
        persist()
    }

    fun select(id: Long?) {
        _ui.update { it.copy(selectedId = id) }
        _ui.value.segments.firstOrNull { it.id == id }?.let { seekTo(it.startMs) }
    }

    fun nudge(id: Long, startDelta: Long, endDelta: Long) = editSegment(id) {
        val ns = (it.startMs + startDelta).coerceAtLeast(0)
        val ne = (it.endMs + endDelta).coerceAtMost(_ui.value.durationMs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)
        if (ne - ns >= 1_000) it.copy(startMs = ns, endMs = ne) else it
    }

    fun setStartHere(id: Long) = editSegment(id) { s -> player.currentPosition.let { p -> if (s.endMs - p >= 1_000) s.copy(startMs = p) else s } }
    fun setEndHere(id: Long) = editSegment(id) { s -> player.currentPosition.let { p -> if (p - s.startMs >= 1_000) s.copy(endMs = p) else s } }
    fun rename(id: Long, name: String) = editSegment(id) { it.copy(name = name.trim().ifBlank { it.name }.take(60)) }

    fun delete(id: Long) {
        _ui.update { it.copy(segments = it.segments.filterNot { s -> s.id == id }, selectedId = null) }
        persist()
    }

    fun cancelPending() = _ui.update { it.copy(pendingStartMs = null) }

    private fun editSegment(id: Long, f: (Segment) -> Segment) {
        _ui.update { st -> st.copy(segments = st.segments.map { if (it.id == id) f(it) else it }.sortedBy { it.startMs }) }
        persist()
    }

    private fun persist() {
        val s = _ui.value
        s.uri?.let { CutListStore.save(ctx, it.toString(), s.template, s.segments) }
    }

    // ---------------- Export ----------------
    fun exportAll() {
        val s = _ui.value
        val uri = s.uri ?: return
        if (s.segments.isEmpty()) { _ui.update { it.copy(message = "Mark at least one segment first") }; return }
        player.pause()
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            val exporter = SegmentExporter(ctx)
            val done = mutableListOf<String>()
            val failed = mutableListOf<String>()
            s.segments.forEachIndexed { i, seg ->
                _ui.update { it.copy(export = ExportStatus(true, seg.name, i + 1, s.segments.size, 0, done.toList(), failed.toList())) }
                try {
                    val r = exporter.export(uri, baseName, seg) { p -> _ui.update { it.copy(export = it.export.copy(percent = p)) } }
                    done += r.displayPath.substringAfterLast('/')
                } catch (c: CancellationException) {
                    _ui.update { it.copy(export = ExportStatus(), message = "Export cancelled") }
                    throw c
                } catch (e: Exception) {
                    failed += seg.name
                }
            }
            _ui.update {
                it.copy(
                    export = ExportStatus(false, "", s.segments.size, s.segments.size, 100, done, failed),
                    message = "Saved ${done.size} file(s) to Movies/AudioVerse/${safeName(baseName)}" +
                        if (failed.isNotEmpty()) " · failed: ${failed.joinToString()}" else ""
                )
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }

    fun buildFfmpegScript(): String = ffmpegScript(_ui.value.title, baseName, _ui.value.segments)

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun onBackgrounded() { if (!_ui.value.export.running) player.pause() }

    // ---------------- Video playlists ----------------
    init {
        viewModelScope.launch { val l = VideoPlaylistStore.load(ctx); _ui.update { it.copy(playlists = l) } }
    }

    private fun editPlaylists(f: (List<VideoPlaylist>) -> List<VideoPlaylist>) {
        val next = f(_ui.value.playlists)
        _ui.update { it.copy(playlists = next) }
        viewModelScope.launch { VideoPlaylistStore.save(ctx, next) }
    }

    fun createPlaylist(name: String, first: VideoEntry? = null) = editPlaylists { VideoPlaylistOps.create(it, name, System.currentTimeMillis(), first) }
    fun addToPlaylist(id: Long, e: VideoEntry) = editPlaylists { VideoPlaylistOps.add(it, id, e) }
    fun removeFromPlaylist(id: Long, index: Int) = editPlaylists { VideoPlaylistOps.removeAt(it, id, index) }
    fun moveInPlaylist(id: Long, index: Int, dir: Int) = editPlaylists { VideoPlaylistOps.move(it, id, index, index + dir) }
    fun deletePlaylist(id: Long) = editPlaylists { VideoPlaylistOps.delete(it, id) }

    /** Plays a list of videos in order; each one opens like a normal video (cut lists still work). */
    fun playQueue(items: List<VideoEntry>, index: Int) {
        if (index !in items.indices) return
        _ui.update { it.copy(queue = items, queueIndex = index) }
        openQueued(items[index])
    }

    private fun openQueued(e: VideoEntry) {
        val q = _ui.value.queue; val i = _ui.value.queueIndex
        open(Uri.parse(e.uri))
        _ui.update { it.copy(queue = q, queueIndex = i) }
        player.play()
    }

    fun playNextInQueue() = stepQueue(1)
    fun stepQueue(dir: Int) {
        val s = _ui.value
        val next = s.queueIndex + dir
        if (s.queue.isEmpty() || next !in s.queue.indices) return
        _ui.update { it.copy(queueIndex = next) }
        openQueued(s.queue[next])
    }

    fun clearQueue() = _ui.update { it.copy(queue = emptyList(), queueIndex = -1) }

    override fun onCleared() {
        exportJob?.cancel()
        player.release()
    }
}

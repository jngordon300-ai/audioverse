package com.jaygordonai.audioverse.app.iptv

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.jaygordonai.audioverse.network.SecretPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** A saved playlist. [link] is the M3U URL (with the login in it) or a content:// file. */
private data class IptvSource(val id: Long, val name: String, val link: String, val isFile: Boolean) {
    override fun toString(): String = "IptvSource($id, $name)" // never the link
}

/** What the screen may know about a source: never the link itself. */
data class SourceInfo(val id: Long, val name: String, val isFile: Boolean, val plainHttp: Boolean)

data class IptvUi(
    val sources: List<SourceInfo> = emptyList(),
    val selectedId: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val total: Int = 0,
    val truncated: Boolean = false,
    val skipped: Int = 0,
    val groups: List<Pair<String, Int>> = emptyList(),
    /** null = all groups. */
    val group: String? = null,
    val query: String = "",
    val visible: List<IptvChannel> = emptyList(),
    val playing: IptvChannel? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val playError: String? = null
)

/**
 * IPTV: M3U playlists (a link from the provider, or a file on the phone).
 * Links are stored encrypted with a Keystore key (SecretPrefs), shown only by name,
 * never logged, and excluded from backup. Channel lists stay in memory only.
 */
@OptIn(UnstableApi::class)
class IptvViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val store = SecretPrefs(app, "audioverse_iptv", "audioverse_iptv_v1")
    private val _ui = MutableStateFlow(IptvUi())
    val ui: StateFlow<IptvUi> = _ui.asStateFlow()

    private var sources: List<IptvSource> = emptyList()
    private var channels: List<IptvChannel> = emptyList()
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var triedHls = false

    /** No auth interceptor and no logging: provider links carry their own login. */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = _ui.update { it.copy(isPlaying = isPlaying) }
                override fun onPlaybackStateChanged(state: Int) = _ui.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
                override fun onPlayerError(error: PlaybackException) = onError(error)
            })
        }

    init {
        sources = readSources()
        publishSources()
        sources.firstOrNull()?.let { select(it.id) }
    }

    // ---------------- Sources ----------------

    private fun readSources(): List<IptvSource> = try {
        val arr = JSONArray(store.get(K_SOURCES) ?: "[]")
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            IptvSource(o.getLong("id"), o.getString("name"), o.getString("link"), o.optBoolean("file"))
        }
    } catch (e: Exception) { emptyList() }

    private fun writeSources() {
        val arr = JSONArray()
        sources.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("link", it.link).put("file", it.isFile)) }
        store.put(K_SOURCES, arr.toString())
    }

    private fun publishSources() = _ui.update { s ->
        s.copy(sources = sources.map { SourceInfo(it.id, it.name, it.isFile, !it.isFile && M3uParser.isPlainHttp(it.link)) })
    }

    /** Returns an error to show, or null when saved. The link is never echoed back. */
    fun addLink(name: String, link: String): String? {
        val clean = link.trim()
        if (!M3uParser.isWebUrl(clean)) return "Paste the full link, starting with http:// or https://"
        val src = IptvSource(System.currentTimeMillis(), name.trim().take(30).ifEmpty { "My IPTV" }, clean, isFile = false)
        sources = sources + src
        writeSources(); publishSources(); select(src.id)
        return null
    }

    fun addFile(uri: Uri, displayName: String?) {
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        val src = IptvSource(System.currentTimeMillis(), (displayName ?: "M3U file").removeSuffix(".m3u").removeSuffix(".m3u8").take(30), uri.toString(), isFile = true)
        sources = sources + src
        writeSources(); publishSources(); select(src.id)
    }

    fun rename(id: Long, name: String) {
        val n = name.trim().take(30).ifEmpty { return }
        sources = sources.map { if (it.id == id) it.copy(name = n) else it }
        writeSources(); publishSources()
    }

    fun remove(id: Long) {
        sources.firstOrNull { it.id == id }?.takeIf { it.isFile }?.let {
            try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(it.link), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
        sources = sources.filterNot { it.id == id }
        if (sources.isEmpty()) store.clearAll() else writeSources()
        publishSources()
        if (_ui.value.selectedId == id) {
            stop(); channels = emptyList()
            _ui.update { it.copy(selectedId = null, total = 0, groups = emptyList(), visible = emptyList(), group = null, error = null) }
            sources.firstOrNull()?.let { select(it.id) }
        }
    }

    // ---------------- Loading ----------------

    fun select(id: Long) {
        val src = sources.firstOrNull { it.id == id } ?: return
        loadJob?.cancel()
        _ui.update { it.copy(selectedId = id, loading = true, error = null, group = null, query = "") }
        loadJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { if (src.isFile) readFile(src) else fetch(src) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(loading = false, error = describe(e)) }
                return@launch
            }
            channels = result.channels
            _ui.update {
                it.copy(
                    loading = false, total = result.channels.size, truncated = result.truncated, skipped = result.skipped,
                    groups = M3uParser.groups(result.channels),
                    error = if (result.channels.isEmpty()) "No channels found in this playlist" else null
                )
            }
            refilter()
        }
    }

    fun reload() { _ui.value.selectedId?.let { select(it) } }

    private fun readFile(src: IptvSource): M3uResult =
        ctx.contentResolver.openInputStream(Uri.parse(src.link))?.bufferedReader()?.use { M3uParser.parse(it.lineSequence()) }
            ?: throw IOException("unreadable")

    private fun fetch(src: IptvSource, userAgent: String = UA, retry: Boolean = true): M3uResult {
        val req = Request.Builder().url(src.link).header("User-Agent", userAgent).build()
        http.newCall(req).execute().use { resp ->
            // Some providers only answer media-player user agents.
            if ((resp.code == 403 || resp.code == 401) && retry) return fetch(src, UA_FALLBACK, retry = false)
            if (!resp.isSuccessful) throw HttpError(resp.code)
            val body = resp.body ?: throw IOException("empty")
            val limited = body.source().let { source ->
                generateSequence {
                    if (source.exhausted()) null else source.readUtf8Line()
                }
            }
            var bytes = 0L
            val capped = limited.takeWhile { line -> bytes += line.length + 1; bytes <= MAX_BYTES }
            return M3uParser.parse(capped)
        }
    }

    private class HttpError(val code: Int) : IOException("HTTP $code")

    /** User-facing error text: never includes the link, host or login. */
    private fun describe(e: Exception): String = when (e) {
        is HttpError -> when (e.code) {
            401, 403 -> "The provider refused the login (HTTP ${e.code}). Check the link or your subscription."
            404 -> "Playlist not found (HTTP 404). The link may have changed."
            else -> "The provider answered HTTP ${e.code}. Try again later."
        }
        is UnknownHostException -> "Can't reach the provider. Check your connection or the link."
        is SocketTimeoutException -> "The provider took too long to answer. Try Reload."
        is SecurityException -> "AudioVerse lost access to that file. Remove it and pick it again."
        else -> "Couldn't load the playlist."
    }

    // ---------------- Browse ----------------

    fun setGroup(g: String?) { _ui.update { it.copy(group = g) }; refilter() }
    fun setQuery(q: String) { _ui.update { it.copy(query = q.take(60)) }; refilter() }

    private fun refilter() {
        filterJob?.cancel()
        val g = _ui.value.group
        val q = _ui.value.query.trim().lowercase()
        val all = channels
        filterJob = viewModelScope.launch {
            val list = withContext(Dispatchers.Default) {
                all.filter { (g == null || it.group == g) && (q.isEmpty() || it.name.lowercase().contains(q)) }
            }
            _ui.update { it.copy(visible = list) }
        }
    }

    // ---------------- Playback ----------------

    fun play(ch: IptvChannel) {
        triedHls = false
        _ui.update { it.copy(playing = ch, playError = null) }
        start(ch, forceHls = ch.url.contains(".m3u8", ignoreCase = true))
    }

    private fun start(ch: IptvChannel, forceHls: Boolean) {
        val headers = buildMap { ch.referrer?.let { put("Referer", it) } }
        val dataSource = OkHttpDataSource.Factory(http)
            .setUserAgent(ch.userAgent ?: UA_FALLBACK)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).setArtist(ch.group).build())
            .apply { if (forceHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(item))
        player.prepare()
        player.play()
    }

    private fun onError(error: PlaybackException) {
        val ch = _ui.value.playing
        // Many provider links have no extension; if it isn't a plain TS/MP4 stream, try it as HLS once.
        val parse = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (ch != null && parse && !triedHls) { triedHls = true; start(ch, forceHls = true); return }
        val code = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
        _ui.update { it.copy(playError = "Can't play this channel ($code)") }
    }

    fun step(dir: Int) {
        val list = _ui.value.visible
        val cur = _ui.value.playing ?: return
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.index == cur.index }
        play(list[((if (i < 0) 0 else i + dir) % list.size + list.size) % list.size])
    }

    fun stop() { player.stop(); player.clearMediaItems(); _ui.update { it.copy(playing = null, playError = null) } }
    fun onBackgrounded() = player.pause()

    override fun onCleared() {
        player.release()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private companion object {
        const val K_SOURCES = "sources"
        const val UA = "AudioVerse/0.5 (Android)"
        const val UA_FALLBACK = "VLC/3.0.20 LibVLC/3.0.20"
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}

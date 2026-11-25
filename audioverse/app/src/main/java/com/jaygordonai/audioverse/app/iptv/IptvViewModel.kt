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
private data class IptvSource(val id: Long, val name: String, val link: String, val isFile: Boolean, val inline: Boolean = false) {
    override fun toString(): String = "IptvSource($id, $name)" // never the link
}

/** What the screen may know about a source: never the link itself. */
data class SourceInfo(val id: Long, val name: String, val isFile: Boolean, val plainHttp: Boolean, val preset: Boolean = false)

/**
 * Free, public, no-login playlists built into the app (https only, nothing secret).
 * Fixed negative ids so they never clash with saved sources.
 */
object IptvPresets {
    data class Preset(val id: Long, val name: String, val url: String)
    val all = listOf(
        Preset(-101, "Free · US", "https://iptv-org.github.io/iptv/countries/us.m3u"),
        Preset(-102, "Free · By category", "https://iptv-org.github.io/iptv/index.category.m3u"),
        Preset(-103, "Free · Worldwide", "https://iptv-org.github.io/iptv/index.m3u"),
        Preset(-104, "Free-TV", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"),
        Preset(-105, "Pluto TV US", "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plutotv_us.m3u"),
        Preset(-106, "Samsung TV Plus US", "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/samsungtvplus_us.m3u")
    )

    private val adult = Regex("(?i)(^|[^a-z])(xxx|adult|porn|18\\+)([^a-z]|$)")
    fun isAdultGroup(group: String) = adult.containsMatchIn(group)
}

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
    val playError: String? = null,
    /** Text shared or opened from another app, waiting for the listener to confirm the import. Memory only. */
    val pendingImport: PendingImport? = null,
    /** Adult groups (e.g. XXX in the worldwide list) are hidden unless switched on. */
    val showAdult: Boolean = false,
    val hiddenAdult: Int = 0,
    val notice: String? = null
)

data class PendingImport(val text: String, val name: String, val playlists: Int, val channels: Int)

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

    private val settings = app.getSharedPreferences("audioverse_iptv_settings", android.content.Context.MODE_PRIVATE)
    private val presetSources = IptvPresets.all.map { IptvSource(it.id, it.name, it.url, isFile = false) }
    private fun allSources() = sources + presetSources

    init {
        sources = readSources()
        _ui.update { it.copy(showAdult = settings.getBoolean("show_adult", false)) }
        publishSources()
        sources.firstOrNull()?.let { select(it.id) }
    }

    fun setShowAdult(on: Boolean) {
        settings.edit().putBoolean("show_adult", on).apply()
        _ui.update { it.copy(showAdult = on) }
        reload()
    }

    // ---------------- Sources ----------------

    private fun readSources(): List<IptvSource> = try {
        val arr = JSONArray(store.get(K_SOURCES) ?: "[]")
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            IptvSource(o.getLong("id"), o.getString("name"), o.getString("link"), o.optBoolean("file"), o.optBoolean("inline"))
        }
    } catch (e: Exception) { emptyList() }

    private fun writeSources() {
        val arr = JSONArray()
        sources.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("link", it.link).put("file", it.isFile).put("inline", it.inline)) }
        store.put(K_SOURCES, arr.toString())
    }

    private fun publishSources() = _ui.update { s ->
        s.copy(sources = sources.map { SourceInfo(it.id, it.name, it.isFile || it.inline, !it.isFile && !it.inline && M3uParser.isPlainHttp(it.link)) } +
            presetSources.map { SourceInfo(it.id, it.name, isFile = false, plainHttp = false, preset = true) })
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

    /** Picked .m3u or .txt: a playlist stays linked to the file; a list of links is imported. */
    fun addFile(uri: Uri, displayName: String?) {
        val name = cleanName(displayName ?: "M3U file")
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readUpTo(uri) } ?: run { _ui.update { it.copy(notice = "Couldn't read that file") }; return@launch }
            if (IptvImport.looksLikePlaylist(text)) {
                val persisted = try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true
                } catch (_: SecurityException) { false }
                if (persisted) {
                    val src = IptvSource(System.currentTimeMillis(), name, uri.toString(), isFile = true)
                    sources = sources + src
                    writeSources(); publishSources(); select(src.id)
                } else importText(text, name)
            } else importText(text, name)
        }
    }

    /** A file or text handed over by another app (open-with or share). Asks before saving anything. */
    fun offerImport(text: String?, displayName: String?) {
        if (text.isNullOrBlank()) return
        val plan = IptvImport.plan(text)
        if (plan.playlistLinks.isEmpty() && plan.streams.isEmpty() && !plan.isM3u) {
            _ui.update { it.copy(notice = "No IPTV links found in what was shared") }; return
        }
        val channels = if (plan.isM3u) M3uParser.parse(text.lineSequence()).channels.size else plan.streams.size
        _ui.update { it.copy(pendingImport = PendingImport(text, cleanName(displayName ?: "Shared list"), plan.playlistLinks.size, channels)) }
    }

    fun offerImportFrom(uri: Uri, displayName: String?) {
        viewModelScope.launch { offerImport(withContext(Dispatchers.IO) { readUpTo(uri) }, displayName) }
    }

    fun confirmImport(accept: Boolean) {
        val p = _ui.value.pendingImport ?: return
        _ui.update { it.copy(pendingImport = null) }
        if (accept) importText(p.text, p.name)
    }

    fun clearNotice() = _ui.update { it.copy(notice = null) }

    /** Imports pasted or shared text. Returns a short summary (never the links). */
    fun importText(text: String, baseName: String = "Pasted list"): String {
        val name = cleanName(baseName)
        val plan = IptvImport.plan(text)
        var added = 0
        var lastId: Long? = null
        var id = System.currentTimeMillis()
        fun inline(n: String, content: String) {
            val src = IptvSource(id++, n, "", isFile = false, inline = true)
            store.put(inlineKey(src.id), content.take(MAX_INLINE))
            sources = sources + src; lastId = src.id; added++
        }
        if (plan.isM3u) inline(name, text)
        plan.playlistLinks.forEachIndexed { i, (label, url) ->
            val src = IptvSource(id++, label.ifEmpty { if (plan.playlistLinks.size == 1) name else "$name ${i + 1}" }.take(30), url, isFile = false)
            sources = sources + src; lastId = src.id; added++
        }
        if (plan.streams.isNotEmpty()) {
            val m3u = buildString {
                append("#EXTM3U\n")
                plan.streams.forEachIndexed { i, (label, url) -> append("#EXTINF:-1,").append(label.ifEmpty { "Channel ${i + 1}" }).append('\n').append(url).append('\n') }
            }
            inline(if (plan.playlistLinks.isEmpty()) name else "$name (channels)", m3u)
        }
        val msg = when {
            added == 0 -> "No IPTV links found"
            plan.isM3u -> "Added playlist \"$name\""
            else -> listOfNotNull(
                plan.playlistLinks.size.takeIf { it > 0 }?.let { "$it playlist link" + if (it > 1) "s" else "" },
                plan.streams.size.takeIf { it > 0 }?.let { "$it channel" + if (it > 1) "s" else "" }
            ).joinToString(" and ", prefix = "Added ")
        }
        if (added > 0) { writeSources(); publishSources(); lastId?.let { select(it) } }
        _ui.update { it.copy(notice = msg) }
        return msg
    }

    private fun cleanName(n: String) = n.substringAfterLast('/').removeSuffix(".m3u").removeSuffix(".m3u8").removeSuffix(".txt").trim().take(30).ifEmpty { "My IPTV" }

    private fun readUpTo(uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(MAX_INLINE); var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    private fun inlineKey(id: Long) = "inline_$id"


    fun rename(id: Long, name: String) {
        val n = name.trim().take(30).ifEmpty { return }
        sources = sources.map { if (it.id == id) it.copy(name = n) else it }
        writeSources(); publishSources()
    }

    fun remove(id: Long) {
        if (id < 0) return // built-in free lists
        sources.firstOrNull { it.id == id }?.takeIf { it.isFile }?.let {
            try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(it.link), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
        store.put(inlineKey(id), null)
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
        val src = allSources().firstOrNull { it.id == id } ?: return
        loadJob?.cancel()
        _ui.update { it.copy(selectedId = id, loading = true, error = null, group = null, query = "") }
        loadJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    when {
                        src.inline -> M3uParser.parse((store.get(inlineKey(src.id)) ?: "").lineSequence())
                        src.isFile -> readFile(src)
                        else -> fetch(src)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(loading = false, error = describe(e)) }
                return@launch
            }
            val showAdult = _ui.value.showAdult
            val kept = if (showAdult) result.channels else result.channels.filterNot { IptvPresets.isAdultGroup(it.group) }
            channels = kept
            _ui.update {
                it.copy(
                    hiddenAdult = result.channels.size - kept.size,
                    loading = false, total = kept.size, truncated = result.truncated, skipped = result.skipped,
                    groups = M3uParser.groups(kept),
                    error = if (kept.isEmpty()) "No channels found in this playlist" else null
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
        /** Pasted / shared lists are kept encrypted in prefs, so they're capped at 1 MB. */
        const val MAX_INLINE = 1024 * 1024
    }
}

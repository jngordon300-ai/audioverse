package com.jaygordonai.audioverse.app.player

import android.app.Application
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.jaygordonai.audioverse.app.PlayerHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.jaygordonai.audioverse.app.library.FolderInfo
import com.jaygordonai.audioverse.app.library.FolderLibrary
import com.jaygordonai.audioverse.app.library.FolderVideo
import com.jaygordonai.audioverse.app.library.Playlist
import com.jaygordonai.audioverse.app.server.HomeServer
import com.jaygordonai.audioverse.app.server.ServerStatus
import com.jaygordonai.audioverse.app.server.ServerVideo
import com.jaygordonai.audioverse.app.library.PlaylistEntry
import com.jaygordonai.audioverse.app.library.PlaylistOps
import com.jaygordonai.audioverse.app.library.PlaylistStore
import com.jaygordonai.audioverse.app.radio.Station
import com.jaygordonai.audioverse.app.radio.StationCatalog
import com.jaygordonai.audioverse.dsp.ReplayGainMode
import com.jaygordonai.audioverse.dsp.VocalStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { LIBRARY, RADIO, NOW_PLAYING, DJ, VIDEO, SOUND, DIAGNOSTICS }

/** Library categories. */
enum class LibTab(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), FOLDERS("Folders"), PLAYLISTS("Playlists") }

/** A row in the Artists / Albums / Playlists category lists. */
data class LibGroup(val key: String, val title: String, val count: Int)

data class PlayerUi(
    val screen: Screen = Screen.LIBRARY,
    val hasPermission: Boolean = false,
    val loading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val libTab: LibTab = LibTab.SONGS,
    /** Library search: matches song, artist, album or folder. */
    val query: String = "",
    /** Open artist, album or playlist id (as text) inside the current tab; null = the category list. */
    val group: String? = null,
    /** Songs shown right now (what the wheel scrolls and what plays). */
    val visible: List<Track> = emptyList(),
    val groups: List<LibGroup> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    /** Track or station waiting for the "Add to playlist" dialog. */
    val addTarget: Track? = null,
    val stations: List<Station> = emptyList(),
    val featured: List<String> = emptyList(),
    /** Song title from the station's stream (ICY), when it sends one. */
    val streamTitle: String? = null,
    /** Picked folders (phone, SD, USB drives like a T5). */
    val folders: List<FolderInfo> = emptyList(),
    val folderVideos: List<FolderVideo> = emptyList(),
    /** Jellyfin on the Mac Mini. */
    val server: ServerStatus = ServerStatus(),
    val serverVideos: List<ServerVideo> = emptyList(),
    val serverBusy: Boolean = false,
    /** Progress text while a rescan runs. */
    val scanning: String? = null,
    /** Asks the Video tab to switch mode (e.g. "WATCH" when a video is opened from another app). */
    val videoModeRequest: String? = null,
    val highlighted: Int = 0,
    val nowPlaying: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volumeText: String? = null,
    val error: String? = null,
    val sound: SoundSettings = SoundSettings(),
    val customPresets: List<EqPreset> = emptyList(),
    val limiterGrDb: Float = 0f,
    val correlation: Float = 0f,
    val nearMono: Boolean = false
)

/** Drives the AudioVerse Player screens. Main thread, shared engine via PlayerHolder. */
@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val _ui = MutableStateFlow(PlayerUi(sound = SoundSettings.load(app), customPresets = EqPresets.loadCustom(app)))
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
    private val audio = app.getSystemService(AudioManager::class.java)
    private var queue: List<Track> = emptyList()
    private var volumeJob: kotlinx.coroutines.Job? = null
    private var lastIcyTitle: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _ui.update { it.copy(isPlaying = isPlaying) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // PLAYLIST_CHANGED also fires when we refresh the lock-screen title; keep the song title then.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                lastIcyTitle = null
                _ui.update { it.copy(streamTitle = null) }
            }
            syncNowPlaying()
        }
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val e = metadata.get(i)
                if (e is IcyInfo) e.title?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let { t -> onStreamTitle(t) }
            }
        }
        override fun onPlayerError(error: PlaybackException) =
            _ui.update { it.copy(error = "Can't play this file (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})") }
    }

    private fun player(): Player = PlayerHolder.get(ctx).backend.player

    private var attached: Player? = null

    /** Listen to the current player; re-attaches if Diagnostics rebuilt the engine. */
    private fun ensureAttached() {
        val p = player()
        if (p !== attached) {
            attached?.removeListener(listener)
            p.addListener(listener)
            attached = p
        }
    }

    // Rescan when the phone's media index changes (e.g. a USB drive is plugged in and scanned).
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rescanSoon = Runnable { loadLibrary() }
    private fun scheduleRescan(delayMs: Long) { mainHandler.removeCallbacks(rescanSoon); mainHandler.postDelayed(rescanSoon, delayMs) }
    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) { if (_ui.value.scanning == null) scheduleRescan(4_000) }
    }
    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { scheduleRescan(2_500) }
    }

    init {
        ensureAttached()
        try {
            ctx.contentResolver.registerContentObserver(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, mediaObserver)
        } catch (_: Exception) { }
        val mounts = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        // System broadcasts only; not exported to other apps.
        ContextCompat.registerReceiver(ctx, mountReceiver, mounts, ContextCompat.RECEIVER_NOT_EXPORTED)
        watchNetwork()
        if (FolderLibrary.savedFolders(ctx).isNotEmpty() || HomeServer.isConfigured(ctx)) loadLibrary()
        viewModelScope.launch {
            val cat = try { StationCatalog.load(ctx) } catch (e: Exception) { StationCatalog.Data(emptyList(), emptyList()) }
            val lists = PlaylistStore.load(ctx)
            _ui.update { it.copy(stations = cat.stations, featured = cat.featured, playlists = lists) }
            recompute()
        }
        viewModelScope.launch {
            while (isActive) {
                ensureAttached()
                val p = player()
                val dsp = PlayerHolder.get(ctx).dsp
                _ui.update {
                    it.copy(
                        positionMs = p.currentPosition.coerceAtLeast(0),
                        durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
                        limiterGrDb = dsp.limiterGainReductionDb,
                        correlation = dsp.stereoCorrelation,
                        nearMono = dsp.isNearMono
                    )
                }
                delay(400)
            }
        }
    }

    // ---------------- Library ----------------
    fun onPermissionResult(granted: Boolean) {
        val was = _ui.value.hasPermission
        _ui.update { it.copy(hasPermission = granted) }
        if (granted && (!was || _ui.value.tracks.isEmpty())) loadLibrary()
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    /** Phone library (MediaStore, includes drives Android has indexed) plus picked folders. */
    fun loadLibrary() {
        loadJob?.cancel()
        _ui.update { it.copy(loading = it.tracks.isEmpty(), scanning = "Scanning your library…") }
        loadJob = viewModelScope.launch {
            val phone = if (_ui.value.hasPermission) {
                try { LocalLibrary.load(ctx) } catch (e: SecurityException) { emptyList() }
            } else emptyList()
            val folders = try {
                FolderLibrary.scan(ctx) { msg -> _ui.update { it.copy(scanning = msg) } }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.jaygordonai.audioverse.app.library.FolderScan(emptyList(), emptyList(), emptyList())
            }
            val server = try {
                HomeServer.load(ctx) { msg -> _ui.update { it.copy(scanning = msg) } }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            val local = FolderLibrary.mergeWith(phone, folders.tracks)
            val merged = FolderLibrary.mergeWith(local, server?.tracks ?: emptyList())
            _ui.update {
                it.copy(
                    tracks = merged, loading = false, scanning = null,
                    folders = folders.folders, folderVideos = folders.videos,
                    server = server?.status ?: ServerStatus(trustHomeWifi = com.jaygordonai.audioverse.network.PrivateTransportPolicy.trustHomeWifi), serverVideos = server?.videos ?: emptyList(),
                    highlighted = it.highlighted.coerceAtMost((merged.size - 1).coerceAtLeast(0))
                )
            }
            recompute()
        }
    }

    fun rescan() = loadLibrary()

    // ---------------- Home server (Jellyfin on the Mac Mini) ----------------
    /** Returns an error message, or null when connected. The password is used once and not kept. */
    fun connectServer(home: String, remote: String, user: String, password: String, cfId: String, cfSecret: String, done: (String?) -> Unit) {
        _ui.update { it.copy(serverBusy = true) }
        viewModelScope.launch {
            val err = try { HomeServer.connect(ctx, home, remote, user, password, cfId, cfSecret) } catch (e: Exception) { "Couldn't connect" }
            _ui.update { it.copy(serverBusy = false) }
            done(err)
            if (err == null) loadLibrary()
        }
    }

    fun disconnectServer() {
        viewModelScope.launch {
            HomeServer.disconnect(ctx)
            _ui.update { it.copy(server = ServerStatus(trustHomeWifi = com.jaygordonai.audioverse.network.PrivateTransportPolicy.trustHomeWifi), serverVideos = emptyList()) }
            loadLibrary()
        }
    }

    // Keeps the http-token policy (VPN / Wi-Fi) and the mobile-data saver current.
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private fun watchNetwork() {
        HomeServer.loadTrust(ctx)
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                val wasVpn = com.jaygordonai.audioverse.network.PrivateTransportPolicy.vpnActive
                com.jaygordonai.audioverse.network.PrivateTransportPolicy.vpnActive = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                com.jaygordonai.audioverse.network.PrivateTransportPolicy.onWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                HomeServer.metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                // VPN just came up and the server wasn't reachable: try again.
                if (!wasVpn && com.jaygordonai.audioverse.network.PrivateTransportPolicy.vpnActive && _ui.value.server.error != null) mainHandler.post { scheduleRescan(1_500) }
            }
            override fun onLost(network: android.net.Network) {
                com.jaygordonai.audioverse.network.PrivateTransportPolicy.vpnActive = false
                com.jaygordonai.audioverse.network.PrivateTransportPolicy.onWifi = false
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (_: Exception) { }
    }

    fun setServerAutoSaver(on: Boolean) { HomeServer.setAutoSaver(ctx, on); _ui.update { it.copy(server = it.server.copy(autoSaver = on)) } }
    fun setTrustHomeWifi(on: Boolean) { HomeServer.setTrustHomeWifi(ctx, on); _ui.update { it.copy(server = it.server.copy(trustHomeWifi = on)) } }

    fun setServerDataSaver(on: Boolean) {
        HomeServer.setDataSaver(ctx, on)
        _ui.update { it.copy(server = it.server.copy(dataSaver = on)) }
    }

    fun addFolder(tree: Uri) { FolderLibrary.addFolder(ctx, tree); loadLibrary() }
    fun removeFolder(tree: String) { FolderLibrary.removeFolder(ctx, tree); loadLibrary() }

    /** Plays one file another app asked AudioVerse to open (file manager, Downloads…). */
    fun playExternal(uri: Uri, title: String) {
        val t = Track(
            id = FolderLibrary.trackId(uri.toString()), uri = uri, title = title.substringBeforeLast('.'),
            artist = "Opened file", album = "", durationMs = 0, trackNumber = 0
        )
        playQueue(listOf(t), 0)
    }

    fun requestVideoMode(mode: String) = _ui.update { it.copy(screen = Screen.VIDEO, videoModeRequest = mode) }
    fun consumeVideoModeRequest() = _ui.update { it.copy(videoModeRequest = null) }

    // ---------------- Library categories ----------------
    fun setLibTab(tab: LibTab) { _ui.update { it.copy(libTab = tab, group = null, highlighted = 0) }; recompute() }
    fun openGroup(key: String?) { _ui.update { it.copy(group = key, highlighted = 0) }; recompute() }
    fun setQuery(q: String) { _ui.update { it.copy(query = q.take(60), highlighted = 0) }; recompute() }

    /** Search across the whole library (Now Playing search). */
    fun searchAll(q: String): List<Track> { val t = q.trim(); return if (t.isEmpty()) emptyList() else _ui.value.tracks.filter { it.matches(t) }.take(300) }

    /** Rebuilds the visible song list and the category rows from the library, tab and group. */
    private fun recompute() = _ui.update { s ->
        val q = s.query.trim()
        val all = s.tracks
        val hits = if (q.isEmpty()) all else all.filter { it.matches(q) }
        val (groups0, visible0) = when (s.libTab) {
            LibTab.SONGS -> emptyList<LibGroup>() to all
            LibTab.FOLDERS -> {
                val g = all.groupBy { it.folder.ifBlank { "Other" } }.map { (k, v) -> LibGroup(k, k, v.size) }.sortedBy { it.title.lowercase() }
                g to (s.group?.let { k -> all.filter { it.folder.ifBlank { "Other" } == k } } ?: emptyList())
            }
            LibTab.ARTISTS -> {
                val g = all.groupBy { it.artist }.map { (k, v) -> LibGroup(k, k, v.size) }.sortedBy { it.title.lowercase() }
                g to (s.group?.let { k -> all.filter { it.artist == k } } ?: emptyList())
            }
            LibTab.ALBUMS -> {
                val g = all.filter { it.album.isNotBlank() }.groupBy { it.album }
                    .map { (k, v) -> LibGroup(k, "$k · ${v.first().artist}", v.size) }.sortedBy { it.title.lowercase() }
                g to (s.group?.let { k -> all.filter { it.album == k }.sortedBy { it.trackNumber } } ?: emptyList())
            }
            LibTab.PLAYLISTS -> {
                val g = s.playlists.map { LibGroup(it.id.toString(), it.name, it.entries.size) }
                val open = s.group?.toLongOrNull()?.let { id -> s.playlists.firstOrNull { it.id == id } }
                g to (open?.entries?.map { e -> all.firstOrNull { it.id == e.id } ?: e.toTrack() } ?: emptyList())
            }
        }
        // Search: songs must match; category rows show if their name or any of their songs match.
        val visible = if (q.isEmpty()) visible0 else visible0.filter { it.matches(q) }
        val groups = if (q.isEmpty()) groups0 else {
            val keys = when (s.libTab) {
                LibTab.ARTISTS -> hits.map { it.artist }.toSet()
                LibTab.ALBUMS -> hits.map { it.album }.toSet()
                LibTab.FOLDERS -> hits.map { it.folder.ifBlank { "Other" } }.toSet()
                else -> emptySet()
            }
            groups0.filter { it.title.contains(q, true) || it.key in keys }
        }
        s.copy(groups = groups, visible = visible, highlighted = s.highlighted.coerceIn(0, (visible.size - 1).coerceAtLeast(0)))
    }

    fun playFrom(index: Int) = playQueue(_ui.value.visible, index)

    /** Plays any mix of phone songs and live stations; next/previous walk the list. */
    fun playQueue(tracks: List<Track>, index: Int, openNowPlaying: Boolean = true) {
        if (index !in tracks.indices) return
        queue = tracks
        lastIcyTitle = null
        val items = tracks.map { t ->
            MediaItem.Builder()
                .setUri(HomeServer.resolve(t.uri))
                .setMediaId(t.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        val p = player()
        p.setMediaItems(items, index, 0)
        p.prepare()
        p.play()
        _ui.update {
            it.copy(
                error = null, streamTitle = null,
                highlighted = if (it.screen == Screen.LIBRARY) index else it.highlighted,
                screen = if (openNowPlaying) Screen.NOW_PLAYING else it.screen
            )
        }
        syncNowPlaying()
    }

    // ---------------- Radio ----------------
    /** Plays one station; next/previous move through the other playable stations in the same view. */
    fun playStation(view: List<Station>, station: Station) {
        val playable = view.filter { it.playable }
        val i = playable.indexOfFirst { it.url == station.url }
        if (i < 0) return
        playQueue(playable.map { it.toTrack() }, i, openNowPlaying = false)
    }

    fun shuffleRadio(view: List<Station>) {
        val playable = view.filter { it.playable }
        if (playable.isEmpty()) return
        playStation(playable, playable.random())
    }

    /** Shows the stream's song title in the app and on the lock screen without restarting the stream. */
    private fun onStreamTitle(title: String) {
        if (lastIcyTitle == title) return
        lastIcyTitle = title
        _ui.update { it.copy(streamTitle = title) }
        val p = player()
        val item = p.currentMediaItem ?: return
        val track = queue.firstOrNull { it.id.toString() == item.mediaId } ?: return
        if (!track.live) return
        val meta = item.mediaMetadata.buildUpon().setArtist(title).setAlbumTitle(track.title).build()
        try { p.replaceMediaItem(p.currentMediaItemIndex, item.buildUpon().setMediaMetadata(meta).build()) } catch (_: Exception) { }
    }

    // ---------------- Playlists ----------------
    fun askAddToPlaylist(t: Track?) = _ui.update { it.copy(addTarget = t) }

    private fun editPlaylists(f: (List<Playlist>) -> List<Playlist>) {
        val next = f(_ui.value.playlists)
        _ui.update { it.copy(playlists = next) }
        recompute()
        viewModelScope.launch { PlaylistStore.save(ctx, next) }
    }

    fun addTo(playlistId: Long, t: Track) = editPlaylists { PlaylistOps.add(it, playlistId, PlaylistEntry.from(t)) }
    fun toggleFavorite(t: Track) = editPlaylists { PlaylistOps.toggle(it, PlaylistOps.FAVORITES_ID, PlaylistEntry.from(t)) }
    fun isFavorite(id: Long): Boolean = PlaylistOps.contains(_ui.value.playlists, PlaylistOps.FAVORITES_ID, id)

    fun createPlaylist(name: String, thenAdd: Track? = null) {
        val id = System.currentTimeMillis()
        editPlaylists { lists ->
            val made = PlaylistOps.create(lists, name, id)
            if (thenAdd != null && made.size > lists.size) PlaylistOps.add(made, id, PlaylistEntry.from(thenAdd)) else made
        }
    }

    fun removeFromOpenPlaylist(index: Int) {
        val id = _ui.value.group?.toLongOrNull() ?: return
        editPlaylists { PlaylistOps.removeAt(it, id, index) }
    }

    fun moveInOpenPlaylist(index: Int, dir: Int) {
        val id = _ui.value.group?.toLongOrNull() ?: return
        editPlaylists { PlaylistOps.move(it, id, index, index + dir) }
    }

    fun deletePlaylist(id: Long) {
        editPlaylists { PlaylistOps.delete(it, id) }
        if (_ui.value.group == id.toString()) openGroup(null)
    }

    private fun syncNowPlaying() {
        val id = player().currentMediaItem?.mediaId?.toLongOrNull()
        _ui.update { it.copy(nowPlaying = queue.firstOrNull { t -> t.id == id }) }
    }

    // ---------------- Transport ----------------
    fun playPause() {
        val p = player()
        if (p.mediaItemCount == 0) { playFrom(_ui.value.highlighted); return }
        if (p.isPlaying) p.pause() else p.play()
    }
    fun next() { player().seekToNextMediaItem() }
    fun previous() {
        val p = player()
        if (p.currentPosition > 3_000) p.seekTo(0) else p.seekToPreviousMediaItem()
    }
    fun seekTo(fraction: Float) {
        val d = _ui.value.durationMs
        if (d > 0) player().seekTo((d * fraction).toLong())
    }

    // ---------------- Wheel ----------------
    fun show(screen: Screen) = _ui.update { it.copy(screen = screen) }

    /** Menu = back: Now Playing -> Library; other screens -> Now Playing if playing, else Library. */
    fun menu() = _ui.update {
        it.copy(screen = when (it.screen) {
            Screen.NOW_PLAYING -> if (it.nowPlaying?.live == true) Screen.RADIO else Screen.LIBRARY
            else -> if (it.nowPlaying != null) Screen.NOW_PLAYING else Screen.LIBRARY
        })
    }

    fun wheelTick(dir: Int) {
        when (_ui.value.screen) {
            Screen.LIBRARY -> _ui.update {
                val max = (it.visible.size - 1).coerceAtLeast(0)
                it.copy(highlighted = (it.highlighted + dir).coerceIn(0, max))
            }
            else -> changeVolume(dir)
        }
    }

    fun wheelCenter() {
        when (_ui.value.screen) {
            Screen.LIBRARY -> playFrom(_ui.value.highlighted)
            else -> playPause()
        }
    }

    private fun changeVolume(dir: Int) {
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
        val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Android blocks silent raises past its hearing-safety level; show its warning instead.
        if (dir > 0 && after == before && before < audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        _ui.update { it.copy(volumeText = "Volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} / $max") }
        volumeJob?.cancel()
        volumeJob = viewModelScope.launch { delay(1_500); _ui.update { it.copy(volumeText = null) } }
    }

    // ---------------- Sound ----------------
    fun updateSound(change: (SoundSettings) -> SoundSettings) {
        val next = change(_ui.value.sound)
        next.applyTo(PlayerHolder.get(ctx).dsp)
        next.save(ctx)
        _ui.update { it.copy(sound = next) }
    }

    fun setReplayGain(m: ReplayGainMode) = updateSound { it.copy(replayGain = m) }
    fun setPreamp(db: Float) = updateSound { it.copy(preampDb = (Math.round(db * 2) / 2f).coerceIn(-12f, 12f)) }
    fun setEq(band: Int, db: Float) = updateSound { s ->
        s.copy(eqDb = s.eqDb.toMutableList().also { it[band] = (Math.round(db * 2) / 2f).coerceIn(-12f, 12f) })
    }
    fun flatEq() = updateSound { it.copy(eqDb = List(10) { 0f }) }

    /** One tap: curve + matching preamp, EQ switched on. */
    fun applyPreset(p: EqPreset) = updateSound { it.copy(eqDb = p.bands, preampDb = p.preampDb, eqEnabled = true) }

    fun saveCustomPreset(name: String) {
        val n = name.trim().take(24).ifEmpty { return }
        val p = EqPreset(n, _ui.value.sound.eqDb, custom = true)
        val next = (listOf(p) + _ui.value.customPresets.filterNot { it.name.equals(n, true) }).take(EqPresets.MAX_CUSTOM)
        EqPresets.saveCustom(ctx, next); _ui.update { it.copy(customPresets = next) }
    }

    fun deleteCustomPreset(name: String) {
        val next = _ui.value.customPresets.filterNot { it.name == name }
        EqPresets.saveCustom(ctx, next); _ui.update { it.copy(customPresets = next) }
    }
    fun setEqEnabled(on: Boolean) = updateSound { it.copy(eqEnabled = on) }
    fun setLimiter(on: Boolean) = updateSound { it.copy(limiterEnabled = on) }
    fun setVocalMode(m: VocalStage.Mode) = updateSound { it.copy(vocalMode = m) }
    fun setVocalStrength(v: Float) = updateSound { it.copy(vocalStrength = v.coerceIn(0f, 1f)) }
    fun setBalance(v: Float) = updateSound { it.copy(balance = if (kotlin.math.abs(v) < 0.04f) 0f else v) }
    fun setWidth(v: Float) = updateSound { it.copy(width = if (kotlin.math.abs(v - 1f) < 0.04f) 1f else v) }
    fun setMono(on: Boolean) = updateSound { it.copy(mono = on) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        attached?.removeListener(listener)
        mainHandler.removeCallbacks(rescanSoon)
        try { ctx.contentResolver.unregisterContentObserver(mediaObserver) } catch (_: Exception) { }
        try { ctx.unregisterReceiver(mountReceiver) } catch (_: Exception) { }
        netCallback?.let { cb -> try { ctx.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) { } }
    }
}

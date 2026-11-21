package com.jaygordonai.audioverse.app.player

import android.app.Application
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.jaygordonai.audioverse.app.PlayerHolder
import com.jaygordonai.audioverse.dsp.ReplayGainMode
import com.jaygordonai.audioverse.dsp.VocalStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { LIBRARY, NOW_PLAYING, SOUND, DIAGNOSTICS }

data class PlayerUi(
    val screen: Screen = Screen.LIBRARY,
    val hasPermission: Boolean = false,
    val loading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val highlighted: Int = 0,
    val nowPlaying: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volumeText: String? = null,
    val error: String? = null,
    val sound: SoundSettings = SoundSettings(),
    val limiterGrDb: Float = 0f,
    val correlation: Float = 0f,
    val nearMono: Boolean = false
)

/** Drives the AudioVerse Player screens. Main thread, shared engine via PlayerHolder. */
@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val _ui = MutableStateFlow(PlayerUi(sound = SoundSettings.load(app)))
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
    private val audio = app.getSystemService(AudioManager::class.java)
    private var queue: List<Track> = emptyList()
    private var volumeJob: kotlinx.coroutines.Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _ui.update { it.copy(isPlaying = isPlaying) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncNowPlaying()
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

    init {
        ensureAttached()
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
        _ui.update { it.copy(hasPermission = granted) }
        if (granted && _ui.value.tracks.isEmpty()) loadLibrary()
    }

    fun loadLibrary() {
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val tracks = try { LocalLibrary.load(ctx) } catch (e: SecurityException) { emptyList() }
            _ui.update { it.copy(tracks = tracks, loading = false, highlighted = 0) }
        }
    }

    fun playFrom(index: Int) {
        val tracks = _ui.value.tracks
        if (index !in tracks.indices) return
        queue = tracks
        val items = tracks.map { t ->
            MediaItem.Builder()
                .setUri(t.uri)
                .setMediaId(t.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .build()
                )
                .build()
        }
        val p = player()
        p.setMediaItems(items, index, 0)
        p.prepare()
        p.play()
        _ui.update { it.copy(error = null, highlighted = index, screen = Screen.NOW_PLAYING) }
        syncNowPlaying()
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
            Screen.NOW_PLAYING -> Screen.LIBRARY
            else -> if (it.nowPlaying != null) Screen.NOW_PLAYING else Screen.LIBRARY
        })
    }

    fun wheelTick(dir: Int) {
        when (_ui.value.screen) {
            Screen.LIBRARY -> _ui.update {
                val max = (it.tracks.size - 1).coerceAtLeast(0)
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
    fun setEqEnabled(on: Boolean) = updateSound { it.copy(eqEnabled = on) }
    fun setLimiter(on: Boolean) = updateSound { it.copy(limiterEnabled = on) }
    fun setVocalMode(m: VocalStage.Mode) = updateSound { it.copy(vocalMode = m) }
    fun setVocalStrength(v: Float) = updateSound { it.copy(vocalStrength = v.coerceIn(0f, 1f)) }
    fun setBalance(v: Float) = updateSound { it.copy(balance = if (kotlin.math.abs(v) < 0.04f) 0f else v) }
    fun setWidth(v: Float) = updateSound { it.copy(width = if (kotlin.math.abs(v - 1f) < 0.04f) 1f else v) }
    fun setMono(on: Boolean) = updateSound { it.copy(mono = on) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() { attached?.removeListener(listener) }
}

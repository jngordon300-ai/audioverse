package com.jaygordonai.audioverse.app.dj

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.jaygordonai.audioverse.app.PlayerHolder
import com.jaygordonai.audioverse.app.player.SoundSettings
import com.jaygordonai.audioverse.app.player.Track
import com.jaygordonai.audioverse.dsp.AudiophileDspProcessor
import com.jaygordonai.audioverse.dsp.CrossfadeCurves
import com.jaygordonai.audioverse.playback.Media3Backend
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DeckUi(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Channel fader / trim, 0..1. */
    val trim: Float = 1f,
    /** Tempo as a speed factor, pitch kept (time-stretch). */
    val tempo: Float = 1f,
    val limiting: Boolean = false,
    val error: String? = null
)

data class DjUi(
    val a: DeckUi = DeckUi(),
    val b: DeckUi = DeckUi(),
    /** 0 = deck A only, 1 = deck B only. */
    val fader: Float = 0f,
    val curve: CrossfadeCurves.Curve = CrossfadeCurves.Curve.CONSTANT_POWER,
    val fadeSeconds: Int = 8,
    val fading: Boolean = false
) {
    val anyPlaying: Boolean get() = a.isPlaying || b.isPlaying
}

enum class DeckId { A, B }

/** Pure mixer maths (unit tested). */
object DjMath {
    const val TEMPO_RANGE = 0.08f
    const val TEMPO_STEP = 0.005f

    fun clampTempo(t: Float): Float {
        val stepped = Math.round((t - 1f) / TEMPO_STEP) * TEMPO_STEP + 1f
        return stepped.coerceIn(1f - TEMPO_RANGE, 1f + TEMPO_RANGE)
    }

    /** Smoothstep from [from] to [to] over [durationMs]; keeps a timed fade musical. */
    fun fadePosition(from: Float, to: Float, elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return to
        val t = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val eased = t * t * (3 - 2 * t)
        return from + (to - from) * eased
    }

    /** Snap near the centre so the fader can rest exactly at 50/50. */
    fun detent(x: Float): Float = if (kotlin.math.abs(x - 0.5f) < 0.025f) 0.5f else x.coerceIn(0f, 1f)

    fun volumes(fader: Float, curve: CrossfadeCurves.Curve, trimA: Float, trimB: Float): Pair<Float, Float> {
        val (ga, gb) = CrossfadeCurves.gains(fader, curve)
        return (ga * trimA.coerceIn(0f, 1f)) to (gb * trimB.coerceIn(0f, 1f))
    }
}

/**
 * AudioVerse DJ: two decks, each its own Media3 engine with the listener's sound
 * settings (ReplayGain levels the two tracks), mixed by an equal-power crossfader.
 * Each deck's limiter ceiling is lowered so the summed output stays under 0 dBFS
 * (see CrossfadeCurves.deckCeilingDbfs). Decks don't take audio focus from each other;
 * starting a deck pauses the main player.
 */
@OptIn(UnstableApi::class)
class DjViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val _ui = MutableStateFlow(DjUi())
    val ui: StateFlow<DjUi> = _ui.asStateFlow()
    private var fadeJob: Job? = null

    private inner class Deck(val id: DeckId) {
        val dsp = AudiophileDspProcessor()
        val backend = Media3Backend(
            context = ctx,
            useProcessingSink = true,
            processors = arrayOf(dsp),
            authProvider = { PlayerHolder.auth },
            handleAudioFocus = false
        )
        val player get() = backend.player

        init {
            applySound()
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = edit { it.copy(isPlaying = isPlaying) }
                override fun onPlayerError(error: PlaybackException) = edit {
                    it.copy(error = "Can't play (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})")
                }
                override fun onTracksChanged(tracks: Tracks) {
                    for (group in tracks.groups) {
                        if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
                        for (i in 0 until group.length) if (group.isTrackSelected(i)) { dsp.updateFromFormat(group.getTrackFormat(i)); return }
                    }
                }
            })
        }

        fun applySound() {
            SoundSettings.load(ctx).applyTo(dsp)
            // Two decks sum at the output: always limit, at a ceiling that leaves headroom.
            dsp.limiterEnabled = true
            dsp.limiterCeilingDbfs = CrossfadeCurves.deckCeilingDbfs(_ui.value.curve)
        }

        fun edit(f: (DeckUi) -> DeckUi) = _ui.update { if (id == DeckId.A) it.copy(a = f(it.a)) else it.copy(b = f(it.b)) }
        fun state(): DeckUi = if (id == DeckId.A) _ui.value.a else _ui.value.b
    }

    private val deckA = Deck(DeckId.A)
    private val deckB = Deck(DeckId.B)
    private fun deck(id: DeckId) = if (id == DeckId.A) deckA else deckB

    init {
        applyMix()
        viewModelScope.launch {
            while (isActive) {
                for (d in listOf(deckA, deckB)) {
                    val p = d.player
                    d.edit {
                        it.copy(
                            positionMs = p.currentPosition.coerceAtLeast(0),
                            durationMs = p.duration.takeIf { x -> x != C.TIME_UNSET } ?: 0,
                            limiting = d.dsp.limiterGainReductionDb < -0.5f
                        )
                    }
                }
                delay(200)
            }
        }
    }

    // ---------------- Decks ----------------

    fun load(id: DeckId, t: Track) {
        val d = deck(id)
        d.applySound() // pick up any Sound-tab changes
        d.player.setMediaItem(
            MediaItem.Builder().setUri(com.jaygordonai.audioverse.app.server.HomeServer.resolve(t.uri)).setMediaId(t.id.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build())
                .build()
        )
        d.player.prepare()
        d.edit { it.copy(track = t, error = null, positionMs = 0) }
    }

    fun playPause(id: DeckId) {
        val p = deck(id).player
        if (p.mediaItemCount == 0) return
        if (p.isPlaying) p.pause() else { pauseMainPlayer(); p.play() }
    }

    /** Back to the start of the track, paused (ready to drop in). */
    fun cue(id: DeckId) { deck(id).player.run { pause(); seekTo(0) } }

    fun seek(id: DeckId, fraction: Float) {
        val p = deck(id).player
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        p.seekTo((dur * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun nudge(id: DeckId, deltaMs: Long) { deck(id).player.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) } }

    fun setTempo(id: DeckId, tempo: Float) {
        val t = DjMath.clampTempo(tempo)
        deck(id).player.playbackParameters = PlaybackParameters(t, 1f)
        deck(id).edit { it.copy(tempo = t) }
    }

    fun setTrim(id: DeckId, v: Float) { deck(id).edit { it.copy(trim = v.coerceIn(0f, 1f)) }; applyMix() }

    // ---------------- Mixer ----------------

    fun setFader(x: Float) { fadeJob?.cancel(); _ui.update { it.copy(fader = DjMath.detent(x), fading = false) }; applyMix() }

    fun setCurve(c: CrossfadeCurves.Curve) {
        _ui.update { it.copy(curve = c) }
        deckA.applySound(); deckB.applySound(); applyMix()
    }

    fun setFadeSeconds(s: Int) = _ui.update { it.copy(fadeSeconds = s) }

    /** Timed blend to one side. Starts the target deck if it's loaded and stopped. */
    fun autoFade(to: DeckId) {
        val target = if (to == DeckId.A) 0f else 1f
        val d = deck(to)
        if (d.player.mediaItemCount > 0 && !d.player.isPlaying) { pauseMainPlayer(); d.player.play() }
        fadeJob?.cancel()
        val from = _ui.value.fader
        val dur = _ui.value.fadeSeconds * 1000L
        _ui.update { it.copy(fading = true) }
        fadeJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - start
                _ui.update { it.copy(fader = DjMath.fadePosition(from, target, elapsed, dur)) }
                applyMix()
                if (elapsed >= dur) break
                delay(20)
            }
            _ui.update { it.copy(fading = false) }
            // Stop the deck that faded out so it doesn't keep playing silently.
            val out = deck(if (to == DeckId.A) DeckId.B else DeckId.A)
            if (out.player.isPlaying) out.player.pause()
        }
    }

    private fun applyMix() {
        val s = _ui.value
        val (va, vb) = DjMath.volumes(s.fader, s.curve, s.a.trim, s.b.trim)
        deckA.player.volume = va
        deckB.player.volume = vb
    }

    private fun pauseMainPlayer() {
        try { PlayerHolder.get(ctx).backend.player.pause() } catch (_: Exception) { }
    }

    fun stopAll() { fadeJob?.cancel(); deckA.player.pause(); deckB.player.pause() }

    override fun onCleared() {
        fadeJob?.cancel()
        deckA.backend.release()
        deckB.backend.release()
    }
}

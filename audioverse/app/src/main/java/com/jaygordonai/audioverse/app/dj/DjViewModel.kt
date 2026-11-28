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
import com.jaygordonai.audioverse.dsp.BeatDetector
import com.jaygordonai.audioverse.dsp.BeatInfo
import com.jaygordonai.audioverse.dsp.CrossfadeCurves
import com.jaygordonai.audioverse.dsp.DjFxProcessor
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
    /** Channel fader, 0..1. */
    val trim: Float = 1f,
    /** Tempo as a speed factor, pitch kept (time-stretch). */
    val tempo: Float = 1f,
    val limiting: Boolean = false,
    val error: String? = null,
    // ---- mixer strip
    val gainDb: Float = 0f,
    val eqHigh: Float = 0f,
    val eqMid: Float = 0f,
    val eqLow: Float = 0f,
    /** -1 low-pass … 0 off … +1 high-pass. */
    val filter: Float = 0f,
    val echo: Boolean = false,
    // ---- analysis
    val analyzing: Boolean = false,
    val waveform: Waveform? = null,
    val bpm: Float = 0f,
    val firstBeatMs: Long = 0,
    // ---- performance
    val cues: List<Long?> = List(8) { null },
    val loopIn: Long? = null,
    val loopOut: Long? = null,
    val loopActive: Boolean = false,
    val loopBeats: Int = 4,
    val level: Float = 0f
) {
    /** BPM as heard (after the tempo slider). */
    val liveBpm: Float get() = bpm * tempo
}

data class DjUi(
    val a: DeckUi = DeckUi(),
    val b: DeckUi = DeckUi(),
    /** 0 = deck A only, 1 = deck B only. */
    val fader: Float = 0f,
    val curve: CrossfadeCurves.Curve = CrossfadeCurves.Curve.CONSTANT_POWER,
    val fadeSeconds: Int = 8,
    val fading: Boolean = false,
    val samplerVolume: Float = 0.8f,
    val clearCues: Boolean = false,
    val message: String? = null
) {
    val anyPlaying: Boolean get() = a.isPlaying || b.isPlaying
}

enum class DeckId { A, B }

/** Pure mixer maths (unit tested). */
object DjMath {
    const val TEMPO_RANGE = 0.08f
    const val TEMPO_STEP = 0.005f
    /** Sync may go further than the slider. */
    const val SYNC_RANGE = 0.16f
    const val EQ_MIN = -26f
    const val EQ_MAX = 6f

    fun clampTempo(t: Float): Float {
        val stepped = Math.round((t - 1f) / TEMPO_STEP) * TEMPO_STEP + 1f
        return stepped.coerceIn(1f - TEMPO_RANGE, 1f + TEMPO_RANGE)
    }

    /** Tempo factor that makes a deck at [ownBpm] play at [targetBpm]; null if out of range. */
    fun syncTempo(ownBpm: Float, targetBpm: Float): Float? {
        if (ownBpm <= 0f || targetBpm <= 0f) return null
        // Same tempo, double or half time (e.g. 70 vs 140 BPM).
        val best = listOf(targetBpm, targetBpm * 2, targetBpm / 2).map { it / ownBpm }.minByOrNull { kotlin.math.abs(it - 1f) } ?: return null
        return if (kotlin.math.abs(best - 1f) <= SYNC_RANGE) best else null
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

    /** 3-band DJ EQ onto the 10-band EQ (31…16k): LOW 31–125, MID 250–2k, HIGH 4k–16k. */
    fun bandOffset(band: Int, low: Float, mid: Float, high: Float): Float = when (band) {
        in 0..2 -> low
        in 3..6 -> mid
        else -> high
    }
}

/**
 * AudioVerse DJ: two decks, each its own Media3 engine with the listener's sound settings
 * (ReplayGain levels the two tracks) plus a DJ strip (gain, 3-band EQ, filter, echo), mixed by a
 * crossfader. Waveforms and BPM are computed on the phone for local files. Each deck's limiter
 * ceiling is lowered so the summed output stays under 0 dBFS. Decks don't take audio focus from
 * each other; starting a deck pauses the main player.
 */
@OptIn(UnstableApi::class)
class DjViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val _ui = MutableStateFlow(DjUi())
    val ui: StateFlow<DjUi> = _ui.asStateFlow()
    private var fadeJob: Job? = null
    private val cuePrefs = app.getSharedPreferences("audioverse_dj_cues", android.content.Context.MODE_PRIVATE)
    private val sampler: Sampler by lazy { Sampler(ctx) }
    @Volatile private var samplerUsed = false

    private inner class Deck(val id: DeckId) {
        val dsp = AudiophileDspProcessor()
        val fx = DjFxProcessor()
        val backend = Media3Backend(
            context = ctx,
            useProcessingSink = true,
            processors = arrayOf(dsp, fx),
            authProvider = { PlayerHolder.auth },
            handleAudioFocus = false
        )
        val player get() = backend.player
        var analyzeJob: Job? = null
        var bendBase: Float? = null

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

        /** Listener's Sound settings + this deck's DJ strip. */
        fun applySound() {
            val user = SoundSettings.load(ctx)
            user.applyTo(dsp)
            val s = state()
            dsp.eqEnabled = true
            for (band in 0 until 10) {
                val base = if (user.eqEnabled) user.eqDb[band] else 0f
                dsp.setEqBand(band, base + DjMath.bandOffset(band, s.eqLow, s.eqMid, s.eqHigh))
            }
            dsp.preampDb = user.preampDb + s.gainDb
            // Two decks sum at the output: always limit, at a ceiling that leaves headroom.
            dsp.limiterEnabled = true
            dsp.limiterCeilingDbfs = CrossfadeCurves.deckCeilingDbfs(_ui.value.curve)
            fx.filter = s.filter
            fx.echoOn = s.echo
            fx.echoDelayMs = if (s.bpm > 0) 60_000f / s.liveBpm * 0.75f else 375f // dotted eighth
        }

        fun edit(f: (DeckUi) -> DeckUi) = _ui.update { if (id == DeckId.A) it.copy(a = f(it.a)) else it.copy(b = f(it.b)) }
        fun state(): DeckUi = if (id == DeckId.A) _ui.value.a else _ui.value.b
    }

    private val deckA = Deck(DeckId.A)
    private val deckB = Deck(DeckId.B)
    private fun deck(id: DeckId) = if (id == DeckId.A) deckA else deckB
    private fun other(id: DeckId) = if (id == DeckId.A) deckB else deckA

    init {
        applyMix()
        // UI clock (positions, meters).
        viewModelScope.launch {
            while (isActive) {
                for (d in listOf(deckA, deckB)) {
                    val p = d.player
                    d.edit {
                        it.copy(
                            positionMs = p.currentPosition.coerceAtLeast(0),
                            durationMs = p.duration.takeIf { x -> x != C.TIME_UNSET } ?: 0,
                            limiting = d.dsp.limiterGainReductionDb < -0.5f,
                            level = if (p.isPlaying) levelAt(it, p.currentPosition) * p.volume else 0f
                        )
                    }
                }
                delay(50)
            }
        }
        // Loop clock: tighter than the UI clock.
        viewModelScope.launch {
            while (isActive) {
                for (d in listOf(deckA, deckB)) {
                    val s = d.state()
                    val inMs = s.loopIn; val outMs = s.loopOut
                    if (s.loopActive && inMs != null && outMs != null && outMs > inMs && d.player.currentPosition >= outMs) {
                        d.player.seekTo(inMs)
                    }
                }
                delay(8)
            }
        }
    }

    private fun levelAt(s: DeckUi, pos: Long): Float {
        val wf = s.waveform ?: return if (s.track?.live == true) 0.6f else 0f
        val i = (pos * Waveform.FPS / 1000).toInt()
        return if (i in wf.peaks.indices) wf.peaks[i] else 0f
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    // ---------------- Decks ----------------

    fun load(id: DeckId, t: Track) {
        val d = deck(id)
        d.player.setMediaItem(
            MediaItem.Builder().setUri(com.jaygordonai.audioverse.app.server.HomeServer.resolve(t.uri)).setMediaId(t.id.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build())
                .build()
        )
        d.player.prepare()
        d.edit {
            it.copy(
                track = t, error = null, positionMs = 0, waveform = null, bpm = 0f, firstBeatMs = 0,
                cues = loadCues(t), loopIn = null, loopOut = null, loopActive = false, analyzing = !t.live
            )
        }
        d.applySound()
        d.analyzeJob?.cancel()
        if (!t.live) d.analyzeJob = viewModelScope.launch {
            val wf = WaveformAnalyzer.analyze(ctx, t.uri)
            d.edit { it.copy(analyzing = false, waveform = wf, bpm = wf?.beat?.bpm ?: 0f, firstBeatMs = wf?.beat?.firstBeatMs ?: 0) }
            d.applySound()
        }
    }

    fun playPause(id: DeckId) {
        val p = deck(id).player
        if (p.mediaItemCount == 0) return
        if (p.isPlaying) p.pause() else { pauseMainPlayer(); p.play() }
    }

    /** Back to the first hot cue (or the start), paused: ready to drop in. */
    fun cue(id: DeckId) {
        val d = deck(id)
        d.player.pause()
        d.player.seekTo(d.state().cues.firstOrNull() ?: 0L)
    }

    fun seek(id: DeckId, fraction: Float) {
        val p = deck(id).player
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        p.seekTo((dur * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun nudge(id: DeckId, deltaMs: Long) { deck(id).player.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) } }

    fun setTempo(id: DeckId, tempo: Float) = applyTempo(id, DjMath.clampTempo(tempo))

    private fun applyTempo(id: DeckId, t: Float) {
        val d = deck(id)
        d.player.playbackParameters = PlaybackParameters(t, 1f)
        d.edit { it.copy(tempo = t) }
        d.applySound() // echo follows the tempo
    }

    fun setTrim(id: DeckId, v: Float) { deck(id).edit { it.copy(trim = v.coerceIn(0f, 1f)) }; applyMix() }

    // ---------------- Mixer strip ----------------

    private fun strip(id: DeckId, f: (DeckUi) -> DeckUi) { val d = deck(id); d.edit(f); d.applySound() }
    fun setGain(id: DeckId, db: Float) = strip(id) { it.copy(gainDb = db.coerceIn(-12f, 12f)) }
    fun setEq(id: DeckId, band: Char, db: Float) = strip(id) {
        val v = db.coerceIn(DjMath.EQ_MIN, DjMath.EQ_MAX)
        when (band) { 'H' -> it.copy(eqHigh = v); 'M' -> it.copy(eqMid = v); else -> it.copy(eqLow = v) }
    }
    fun setFilter(id: DeckId, v: Float) = strip(id) { it.copy(filter = if (kotlin.math.abs(v) < 0.03f) 0f else v.coerceIn(-1f, 1f)) }
    fun toggleEcho(id: DeckId) = strip(id) { it.copy(echo = !it.echo) }

    // ---------------- Sync ----------------

    /** Matches this deck's tempo to the other deck, then lines up the beats if the other deck is playing. */
    fun sync(id: DeckId) {
        val me = deck(id).state()
        val o = other(id).state()
        if (me.bpm <= 0f || o.bpm <= 0f) { _ui.update { it.copy(message = "Sync needs a BPM on both decks (phone and drive files)") }; return }
        val t = DjMath.syncTempo(me.bpm, o.liveBpm) ?: run { _ui.update { it.copy(message = "Tempos too far apart to sync") }; return }
        applyTempo(id, t)
        val op = other(id).player
        if (op.isPlaying) {
            val corr = BeatDetector.phaseCorrectionMs(
                op.currentPosition, BeatInfo(o.bpm, o.firstBeatMs, 1f),
                deck(id).player.currentPosition, BeatInfo(me.bpm, me.firstBeatMs, 1f)
            )
            if (corr != 0L) nudge(id, corr)
        }
    }

    // ---------------- Hot cues ----------------

    private fun cueKey(t: Track) = "cues_" + t.id
    private fun loadCues(t: Track): List<Long?> {
        val raw = cuePrefs.getString(cueKey(t), null)?.split(",") ?: return List(8) { null }
        return List(8) { i -> raw.getOrNull(i)?.toLongOrNull() }
    }
    private fun saveCues(t: Track, cues: List<Long?>) {
        cuePrefs.edit().putString(cueKey(t), cues.joinToString(",") { it?.toString() ?: "" }).apply()
    }

    /** Empty pad: set it here. Set pad: jump to it (or clear it in delete mode). */
    fun hotCue(id: DeckId, index: Int) {
        val d = deck(id); val s = d.state(); val t = s.track ?: return
        val existing = s.cues.getOrNull(index)
        val next = when {
            _ui.value.clearCues && existing != null -> s.cues.toMutableList().also { it[index] = null }
            existing == null -> s.cues.toMutableList().also { it[index] = d.player.currentPosition }
            else -> { d.player.seekTo(existing); if (!d.player.isPlaying) { pauseMainPlayer(); d.player.play() }; return }
        }
        d.edit { it.copy(cues = next) }
        saveCues(t, next)
    }

    fun toggleClearCues() = _ui.update { it.copy(clearCues = !it.clearCues) }

    // ---------------- Loops ----------------

    fun loopIn(id: DeckId) { val d = deck(id); d.edit { it.copy(loopIn = d.player.currentPosition, loopOut = null, loopActive = false) } }
    fun loopOut(id: DeckId) {
        val d = deck(id); val pos = d.player.currentPosition
        d.edit { s -> val i = s.loopIn; if (i != null && pos > i + 50) s.copy(loopOut = pos, loopActive = true) else s }
    }
    /** Loop of N beats from here (one second per "beat" without a BPM). */
    fun autoLoop(id: DeckId, beats: Int) {
        val d = deck(id); val s = d.state()
        val beatMs = if (s.bpm > 0) 60_000f / s.bpm else 1000f
        val start = d.player.currentPosition
        d.edit { it.copy(loopIn = start, loopOut = start + (beats * beatMs).toLong(), loopActive = true, loopBeats = beats) }
    }
    fun toggleLoop(id: DeckId) = deck(id).edit { s -> if (s.loopIn != null && s.loopOut != null) s.copy(loopActive = !s.loopActive) else s }

    // ---------------- Jog wheel ----------------

    /** Turning the platter: scrub when paused, bend the tempo while held when playing. */
    fun jog(id: DeckId, degrees: Float) {
        val d = deck(id)
        if (!d.player.isPlaying) { nudge(id, (degrees / 360f * 1800f).toLong()); return }
        val base = d.bendBase ?: d.state().tempo.also { d.bendBase = it }
        val bend = (degrees / 20f).coerceIn(-0.12f, 0.12f)
        d.player.playbackParameters = PlaybackParameters((base * (1f + bend)).coerceIn(0.5f, 2f), 1f)
    }

    fun jogRelease(id: DeckId) {
        val d = deck(id)
        d.bendBase?.let { d.player.playbackParameters = PlaybackParameters(it, 1f) }
        d.bendBase = null
    }

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
            val out = other(to)
            if (out.player.isPlaying) out.player.pause()
        }
    }

    private fun applyMix() {
        val s = _ui.value
        val (va, vb) = DjMath.volumes(s.fader, s.curve, s.a.trim, s.b.trim)
        deckA.player.volume = va
        deckB.player.volume = vb
    }

    // ---------------- Sampler ----------------

    /** Builds the pad sounds off the main thread (called when the sampler opens). */
    fun warmSampler() { if (!samplerUsed) { samplerUsed = true; viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { sampler } } }

    fun pad(name: String) { samplerUsed = true; sampler.volume = _ui.value.samplerVolume; sampler.play(name) }
    fun setSamplerVolume(v: Float) = _ui.update { it.copy(samplerVolume = v.coerceIn(0f, 1f)) }

    private fun pauseMainPlayer() {
        try { PlayerHolder.get(ctx).backend.player.pause() } catch (_: Exception) { }
    }

    fun stopAll() { fadeJob?.cancel(); deckA.player.pause(); deckB.player.pause() }

    override fun onCleared() {
        fadeJob?.cancel()
        deckA.backend.release()
        deckB.backend.release()
        if (samplerUsed) try { sampler.release() } catch (_: Exception) { }
    }
}

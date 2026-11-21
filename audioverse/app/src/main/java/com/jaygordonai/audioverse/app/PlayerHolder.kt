package com.jaygordonai.audioverse.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.jaygordonai.audioverse.app.gate.ProbeAudioProcessor
import com.jaygordonai.audioverse.app.player.SoundSettings
import com.jaygordonai.audioverse.dsp.AudiophileDspProcessor
import com.jaygordonai.audioverse.dsp.ReplayGainMode
import com.jaygordonai.audioverse.network.AuthConfig
import com.jaygordonai.audioverse.network.CredentialStore
import com.jaygordonai.audioverse.playback.Media3Backend

/**
 * One process-wide backend, shared by the gate screen and PlaybackService
 * (so playback continues with the screen locked). Main thread only.
 */
@OptIn(UnstableApi::class)
object PlayerHolder {

    class Engine(val backend: Media3Backend, val probe: ProbeAudioProcessor, val dsp: AudiophileDspProcessor)

    private var engine: Engine? = null
    /** true = AudioVerse ProcessingAudioSink (default); false = Media3 stock sink, for comparison. */
    var processingSink: Boolean = true
        private set
    /** Phase 1 diagnostic: false = probe only, to isolate our DSP from Media3 itself. */
    var dspInChain: Boolean = true
        private set

    /** In-memory copy of credentials so HTTP requests don't decrypt on every call. */
    @Volatile var auth: AuthConfig? = null
        private set

    /** PlaybackService registers here so its MediaSession follows rebuilds. */
    var onPlayerChanged: ((Player) -> Unit)? = null

    fun get(context: Context): Engine = engine ?: build(context)

    fun rebuild(context: Context, processingSink: Boolean, dspInChain: Boolean = this.dspInChain): Engine {
        this.processingSink = processingSink
        this.dspInChain = dspInChain
        engine?.backend?.release()
        engine = null
        return build(context).also { onPlayerChanged?.invoke(it.backend.player) }
    }

    fun loadAuth(context: Context) { auth = CredentialStore(context).load() }

    fun setAuth(context: Context, config: AuthConfig?) {
        val store = CredentialStore(context)
        if (config == null) { store.clear(); auth = store.load() } else { store.save(config); auth = store.load() }
    }

    fun release() { engine?.backend?.release(); engine = null }

    private fun build(context: Context): Engine {
        if (auth == null) loadAuth(context)
        val probe = ProbeAudioProcessor()
        // The listener's saved sound settings (ReplayGain, EQ, limiter, vocal, mixer).
        val dsp = AudiophileDspProcessor().also { SoundSettings.load(context).applyTo(it) }
        val backend = Media3Backend(
            context = context,
            useProcessingSink = processingSink,
            processors = if (dspInChain) arrayOf(probe, dsp) else arrayOf(probe),
            authProvider = { auth }
        )
        // Feed each new track's tags to the DSP so ReplayGain follows the track.
        backend.player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) { dsp.updateFromFormat(group.getTrackFormat(i)); return }
                    }
                }
            }
        })
        return Engine(backend, probe, dsp).also { engine = it }
    }
}

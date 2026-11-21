package com.jaygordonai.audioverse.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.jaygordonai.audioverse.app.gate.ProbeAudioProcessor
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
    var floatOutput: Boolean = true
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

    fun rebuild(context: Context, floatOutput: Boolean, dspInChain: Boolean = this.dspInChain): Engine {
        this.floatOutput = floatOutput
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
        // Neutral DSP: present in the chain so the gate tests what it actually receives,
        // but with no gain, flat EQ, no vocal stage and no limiter, so audio is unchanged.
        val dsp = AudiophileDspProcessor().apply {
            setReplayGainMode(ReplayGainMode.OFF)
            preampDb = 0f
            limiterEnabled = false
        }
        val backend = Media3Backend(
            context = context,
            floatOutput = floatOutput,
            processors = if (dspInChain) arrayOf(probe, dsp) else arrayOf(probe),
            authProvider = { auth }
        )
        return Engine(backend, probe, dsp).also { engine = it }
    }
}

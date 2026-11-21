package com.jaygordonai.audioverse.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow

/**
 * The single per-deck processor (replaces the Council's separate
 * StereoMixerProcessor, so there is one loop and one buffer copy):
 *
 *   ReplayGain -> Preamp -> Phase invert -> Vocal stage -> 10-band EQ
 *     -> Channel mixer -> Limiter
 *
 * Input: 16-bit PCM or 32-bit float. Output: always 32-bit float.
 * Requires TenBandBiquadEq, ReplayGainParser, ReplayGainTags, ReplayGainMode
 * from the Council draft (unchanged).
 *
 * Known open items (tracked in the Council brief):
 *  - Limiter is a hard ceiling placeholder; replace with look-ahead limiter
 *    before MVP criterion #10.
 *  - ReplayGain switches when the player reports the track change, slightly
 *    after the next track's first buffers are processed. Needs per-item gain.
 */
@OptIn(UnstableApi::class)
class AudiophileDspProcessor : BaseAudioProcessor() {

    // ---- Global ----
    @Volatile var globalBypass = false

    // ---- Gain ----
    @Volatile var replayGainMode = ReplayGainMode.TRACK; private set
    @Volatile var replayGainDb = 0f; private set
    @Volatile var preampDb = 0f

    // ---- EQ ----
    @Volatile var eqEnabled = true
    private val bandGainsDb = FloatArray(10)
    private var eqBank: TenBandBiquadEq? = null

    // ---- Stereo stages ----
    /** Master switch for phase invert, vocal stage and channel mixer. */
    @Volatile var stereoStagesEnabled = true
    val channelMixer = ChannelMixer()

    // Vocal settings live here so they survive reconfiguration (new sample rate).
    @Volatile var vocalMode = VocalStage.Mode.OFF
        set(v) { field = v; vocalStage?.mode = v }
    @Volatile var vocalStrength = 0.85f
        set(v) { field = v.coerceIn(0f, 1f); vocalStage?.strength = field }
    @Volatile var preserveBass = true
        set(v) { field = v; vocalStage?.preserveBass = v }
    @Volatile var bassCrossoverHz = 120f
        set(v) { field = v.coerceIn(60f, 250f); vocalStage?.bassCrossoverHz = field }
    private var vocalStage: VocalStage? = null

    /** Near 0 = the current track is effectively mono; vocal removal will not work. */
    val sideToMidRatio: Float get() = vocalStage?.sideToMidRatio ?: 1f

    // ---- Limiter ----
    @Volatile var limiterEnabled = true
    @Volatile var limiterCeilingDbfs = -1f
    @Volatile var limiterEngaged = false; private set

    private var tags = ReplayGainTags()
    private var inputIs16Bit = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        inputIs16Bit = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> true
            C.ENCODING_PCM_FLOAT -> false
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val rate = inputAudioFormat.sampleRate
        eqBank = TenBandBiquadEq(rate, inputAudioFormat.channelCount).also { bank ->
            bank.enabled = true
            for (i in 0 until 10) bank.setBandGain(i, bandGainsDb[i])
        }
        vocalStage = VocalStage(rate).also {
            it.mode = vocalMode
            it.strength = vocalStrength
            it.preserveBass = preserveBass
            it.bassCrossoverHz = bassCrossoverHz
        }
        return AudioFormat(rate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun isActive(): Boolean = super.isActive() && !globalBypass

    override fun onFlush() {
        eqBank?.reset(); vocalStage?.reset(); limiterEngaged = false
    }

    override fun onReset() {
        eqBank = null; vocalStage = null; limiterEngaged = false
    }

    /** Call from Player.Listener.onTracksChanged with the selected audio Format. */
    fun updateFromFormat(format: Format) {
        tags = format.metadata?.let { ReplayGainParser.parseFromMetadata(it) } ?: ReplayGainTags()
        replayGainDb = ReplayGainParser.resolveGainDb(tags, replayGainMode)
    }

    fun setReplayGainMode(mode: ReplayGainMode) {
        replayGainMode = mode
        replayGainDb = ReplayGainParser.resolveGainDb(tags, mode)
    }

    fun setEqBand(band: Int, gainDb: Float) {
        if (band !in 0..9) return
        bandGainsDb[band] = gainDb.coerceIn(-15f, 15f)
        eqBank?.setBandGain(band, bandGainsDb[band])
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val channels = inputAudioFormat.channelCount
        val sampleCount = if (inputIs16Bit) bytes / 2 else bytes / 4
        val out = replaceOutputBuffer(sampleCount * 4)

        val gain = 10.0.pow((replayGainDb + preampDb.coerceIn(-12f, 12f)) / 20.0).toFloat()
        val ceiling = 10.0.pow(limiterCeilingDbfs / 20.0).toFloat()
        val eq = if (eqEnabled) eqBank else null
        val vocal = if (stereoStagesEnabled) vocalStage else null
        val mixer = if (stereoStagesEnabled) channelMixer else null
        val useLimiter = limiterEnabled
        var clipped = false
        vocal?.beginBuffer()

        if (channels == 2) {
            val frameBytes = if (inputIs16Bit) 4 else 8
            while (inputBuffer.remaining() >= frameBytes) {
                var l = readSample(inputBuffer) * gain
                var r = readSample(inputBuffer) * gain
                if (mixer != null) { mixer.applyPhase(l, r); l = mixer.outL; r = mixer.outR }
                if (vocal != null) { vocal.process(l, r); l = vocal.outL; r = vocal.outR }
                if (eq != null) { l = eq.processSample(l, 0); r = eq.processSample(r, 1) }
                if (mixer != null) { mixer.process(l, r); l = mixer.outL; r = mixer.outR }
                if (useLimiter) {
                    if (abs(l) > ceiling) { l = if (l > 0f) ceiling else -ceiling; clipped = true }
                    if (abs(r) > ceiling) { r = if (r > 0f) ceiling else -ceiling; clipped = true }
                }
                out.putFloat(l); out.putFloat(r)
            }
        } else {
            // Mono / multichannel: gain, EQ, limiter only
            var ch = 0
            val sampleBytes = if (inputIs16Bit) 2 else 4
            while (inputBuffer.remaining() >= sampleBytes) {
                var s = readSample(inputBuffer) * gain
                if (eq != null) s = eq.processSample(s, ch)
                if (useLimiter && abs(s) > ceiling) { s = if (s > 0f) ceiling else -ceiling; clipped = true }
                out.putFloat(s)
                ch = (ch + 1) % channels
            }
        }
        // Consume any trailing partial frame so the pipeline never stalls
        inputBuffer.position(inputBuffer.limit())
        limiterEngaged = clipped
        out.flip()
    }

    private fun readSample(buf: ByteBuffer): Float =
        if (inputIs16Bit) buf.short / 32768f else buf.float
}

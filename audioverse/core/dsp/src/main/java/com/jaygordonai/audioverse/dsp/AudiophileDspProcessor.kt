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
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * The single per-deck processor (replaces the Council's separate
 * StereoMixerProcessor, so there is one loop and one buffer copy):
 *
 *   ReplayGain -> Preamp -> Phase invert -> Vocal stage -> 10-band EQ
 *     -> Channel mixer -> Limiter
 *
 * Input: 16-, 24- or 32-bit integer PCM, or 32-bit float. Output: always 32-bit float.
 * Requires TenBandBiquadEq, ReplayGainParser, ReplayGainTags, ReplayGainMode
 * from the Council draft (unchanged).
 *
 * Known open items (tracked in the Council brief):
 *  - Limiter: stereo-linked peak limiter, instant attack + 50 ms release, so
 *    output can never exceed the ceiling. A 1-2 ms look-ahead (smoother attack)
 *    is the next refinement, measured against MVP criterion #10.
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
    /** Normalised L/R correlation (-1..+1) of the current track. */
    val stereoCorrelation: Float get() = vocalStage?.correlation ?: 0f
    /** True when vocal removal will be ineffective (DJ-03 rule). */
    val isNearMono: Boolean get() = vocalStage?.isNearMono ?: false

    // ---- Limiter ----
    @Volatile var limiterEnabled = true
    @Volatile var limiterCeilingDbfs = -1f
    @Volatile var limiterEngaged = false; private set
    /** Current limiter gain reduction in dB (0 = none), for diagnostics. */
    @Volatile var limiterGainReductionDb = 0f; private set
    private var limiterGain = 1f
    private var releaseCoeff = 0f

    private var tags = ReplayGainTags()
    private var inputEncoding = C.ENCODING_PCM_16BIT
    private var bytesPerSample = 2

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputEncoding = inputAudioFormat.encoding
        val rate = inputAudioFormat.sampleRate
        releaseCoeff = exp(-1.0 / (LIMITER_RELEASE_S * rate)).toFloat()
        limiterGain = 1f
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
        eqBank?.reset(); vocalStage?.reset(); resetLimiter()
    }

    override fun onReset() {
        eqBank = null; vocalStage = null; resetLimiter()
    }

    private fun resetLimiter() { limiterGain = 1f; limiterEngaged = false; limiterGainReductionDb = 0f }

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
        val out = replaceOutputBuffer((bytes / bytesPerSample) * 4)

        val gain = 10.0.pow((replayGainDb + preampDb.coerceIn(-12f, 12f)) / 20.0).toFloat()
        val ceiling = 10.0.pow(limiterCeilingDbfs / 20.0).toFloat()
        val eq = if (eqEnabled) eqBank else null
        val vocal = if (stereoStagesEnabled) vocalStage else null
        val mixer = if (stereoStagesEnabled) channelMixer else null
        val useLimiter = limiterEnabled
        var minGain = 1f
        vocal?.beginBuffer()

        if (channels == 2) {
            val frameBytes = bytesPerSample * 2
            while (inputBuffer.remaining() >= frameBytes) {
                var l = readSample(inputBuffer) * gain
                var r = readSample(inputBuffer) * gain
                if (mixer != null) { mixer.applyPhase(l, r); l = mixer.outL; r = mixer.outR }
                if (vocal != null) { vocal.process(l, r); l = vocal.outL; r = vocal.outR }
                if (eq != null) { l = eq.processSample(l, 0); r = eq.processSample(r, 1) }
                if (mixer != null) { mixer.process(l, r); l = mixer.outL; r = mixer.outR }
                if (useLimiter) {
                    // Stereo-linked: one gain for both channels keeps the image stable.
                    val g = limiterGainFor(maxOf(abs(l), abs(r)), ceiling)
                    if (g < minGain) minGain = g
                    l *= g; r *= g
                }
                out.putFloat(l); out.putFloat(r)
            }
        } else {
            // Mono / multichannel: gain, EQ, limiter (linked across the frame)
            val frame = FloatArray(channels)
            while (inputBuffer.remaining() >= bytesPerSample * channels) {
                var peak = 0f
                for (ch in 0 until channels) {
                    var s = readSample(inputBuffer) * gain
                    if (eq != null) s = eq.processSample(s, ch)
                    frame[ch] = s
                    peak = maxOf(peak, abs(s))
                }
                val g = if (useLimiter) limiterGainFor(peak, ceiling) else 1f
                if (g < minGain) minGain = g
                for (ch in 0 until channels) out.putFloat(frame[ch] * g)
            }
        }
        // Consume any trailing partial frame so the pipeline never stalls
        inputBuffer.position(inputBuffer.limit())
        limiterEngaged = minGain < 0.999f
        limiterGainReductionDb = if (minGain < 1f) 20f * log10(minGain) else 0f
        out.flip()
    }

    /**
     * Peak limiter gain: instant attack (gain drops at once to exactly what the
     * peak needs, so no sample can exceed the ceiling), exponential release.
     */
    private fun limiterGainFor(peak: Float, ceiling: Float): Float {
        val target = if (peak > ceiling) ceiling / peak else 1f
        limiterGain = if (target < limiterGain) target else target + (limiterGain - target) * releaseCoeff
        return limiterGain
    }

    private fun readSample(buf: ByteBuffer): Float = when (inputEncoding) {
        C.ENCODING_PCM_16BIT -> buf.short / 32768f
        C.ENCODING_PCM_24BIT -> {
            val lo = buf.get().toInt() and 0xFF
            val mid = buf.get().toInt() and 0xFF
            val hi = buf.get().toInt() // sign-extends
            ((hi shl 16) or (mid shl 8) or lo) / 8388608f
        }
        C.ENCODING_PCM_32BIT -> buf.int / 2147483648f
        else -> buf.float
    }

    private companion object {
        const val LIMITER_RELEASE_S = 0.050
    }
}

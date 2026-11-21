package com.jaygordonai.audioverse.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10-band peaking EQ (RBJ cookbook, Direct Form I), ISO octave centres 31.25 Hz - 16 kHz.
 * From the Council draft. Coefficient arrays are swapped whole from the UI thread
 * (reference write is atomic); filter state is touched only on the audio thread.
 */
class TenBandBiquadEq(
    private val sampleRateHz: Int,
    private val channelCount: Int
) {
    companion object {
        val CENTER_FREQUENCIES = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private const val DEFAULT_Q = 1.4142135f
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f)
    }

    @Volatile private var coeffs: Array<FloatArray> = Array(10) { IDENTITY }
    private val state = Array(10) { Array(channelCount) { FloatArray(4) } }

    @Volatile var enabled = true

    fun setBandGain(bandIndex: Int, gainDb: Float, q: Float = DEFAULT_Q) {
        if (bandIndex !in 0..9) return
        val freq = CENTER_FREQUENCIES[bandIndex]
        val next = coeffs.copyOf()
        if (freq >= sampleRateHz * 0.48f || abs(gainDb) < 0.01f) {
            next[bandIndex] = IDENTITY
        } else {
            val a = 10.0.pow(gainDb / 40.0)
            val omega = 2.0 * PI * freq / sampleRateHz
            val alpha = sin(omega) / (2.0 * q)
            val cosW = cos(omega)
            val a0 = 1.0 + alpha / a
            next[bandIndex] = floatArrayOf(
                ((1.0 + alpha * a) / a0).toFloat(),
                ((-2.0 * cosW) / a0).toFloat(),
                ((1.0 - alpha * a) / a0).toFloat(),
                ((-2.0 * cosW) / a0).toFloat(),
                ((1.0 - alpha / a) / a0).toFloat()
            )
        }
        coeffs = next
    }

    fun processSample(sample: Float, channel: Int): Float {
        if (!enabled || channel !in 0 until channelCount) return sample
        val bank = coeffs
        var y = sample
        for (band in 0 until 10) {
            val c = bank[band]
            if (c === IDENTITY) continue
            val s = state[band][channel]
            val x0 = y
            var out = c[0] * x0 + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
            if (abs(out) < 1e-24f) out = 0f
            s[1] = s[0]; s[0] = x0; s[3] = s[2]; s[2] = out
            y = out
        }
        return y
    }

    fun reset() {
        for (band in 0 until 10) for (ch in 0 until channelCount) state[band][ch].fill(0f)
    }
}

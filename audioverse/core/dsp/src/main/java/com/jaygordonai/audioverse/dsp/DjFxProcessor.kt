package com.jaygordonai.audioverse.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Per-deck DJ effects, after AudiophileDspProcessor (float PCM in, float PCM out):
 *  - Filter knob: -1 … 0 … +1. Left sweeps a low-pass down from 20 kHz to ~150 Hz,
 *    right sweeps a high-pass up from ~20 Hz to ~2.5 kHz; the centre (±0.02) is bypass.
 *  - Echo: delay (ms, usually one beat), feedback and wet mix.
 * A soft clip at the end keeps a feedback-heavy echo from going over full scale.
 * Settings are @Volatile and may be changed from the UI thread at any time.
 */
@OptIn(UnstableApi::class)
class DjFxProcessor : BaseAudioProcessor() {

    @Volatile var filter: Float = 0f
    @Volatile var echoOn: Boolean = false
    @Volatile var echoDelayMs: Float = 375f
    @Volatile var echoFeedback: Float = 0.45f
    @Volatile var echoMix: Float = 0.35f

    private var rate = 44_100
    private var channels = 2
    private var appliedFilter = Float.NaN
    private val bq = Array(2) { Biquad2() }
    private var delay = FloatArray(0)
    private var writePos = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT || inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        delay = FloatArray(rate * 2 * channels) // up to 2 s
        writePos = 0
        appliedFilter = Float.NaN
        return inputAudioFormat
    }

    override fun onFlush() { bq.forEach { it.reset() }; delay.fill(0f); writePos = 0 }
    override fun onReset() { delay = FloatArray(0); appliedFilter = Float.NaN }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
        val f = filter
        val filterOn = abs(f) > 0.02f
        if (filterOn && f != appliedFilter) { design(f); appliedFilter = f }
        val echo = echoOn && delay.isNotEmpty()
        val dFrames = ((echoDelayMs.coerceIn(20f, 1900f) / 1000f) * rate).toInt()
        val fb = echoFeedback.coerceIn(0f, 0.85f)
        val mix = echoMix.coerceIn(0f, 1f)
        val len = delay.size
        while (inBuf.remaining() >= 4 * channels) {
            for (c in 0 until channels) {
                var x = inBuf.float
                if (filterOn) x = bq[c].process(x)
                if (echo) {
                    val readPos = ((writePos - dFrames * channels + c) % len + len) % len
                    val d = delay[readPos]
                    delay[(writePos + c) % len] = x + d * fb
                    x += d * mix
                } else if (len > 0) {
                    delay[(writePos + c) % len] = 0f
                }
                out.putFloat(softClip(x))
            }
            if (len > 0) writePos = (writePos + channels) % len
        }
        out.flip()
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + (a - 0.9f) / (1f + ((a - 0.9f) / 0.1f).pow(2))
        return if (x < 0) -y.coerceAtMost(1f) else y.coerceAtMost(1f)
    }

    private fun design(f: Float) {
        val lowPass = f < 0
        val t = abs(f).coerceIn(0f, 1f)
        val fc = if (lowPass) 20_000.0 * (150.0 / 20_000.0).pow(t.toDouble()) else 20.0 * (2_500.0 / 20.0).pow(t.toDouble())
        bq.forEach { it.design(lowPass, fc.coerceAtMost(rate * 0.45), rate, q = 0.9) }
    }

    /** RBJ cookbook low/high-pass biquad (transposed direct form II). */
    private class Biquad2 {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0

        fun design(lowPass: Boolean, fc: Double, rate: Int, q: Double) {
            val w0 = 2 * PI * fc / rate
            val alpha = sin(w0) / (2 * q)
            val cw = cos(w0)
            val a0 = 1 + alpha
            if (lowPass) { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 }
            else { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 }
            b0 /= a0; b1 /= a0; b2 /= a0
            a1 = -2 * cw / a0; a2 = (1 - alpha) / a0
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y.toFloat()
        }

        fun reset() { z1 = 0.0; z2 = 0.0 }
    }
}

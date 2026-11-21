package com.jaygordonai.audioverse.app.gate

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Pass-through processor placed FIRST in the chain. It records:
 *  - the PCM format Media3 hands to custom processors (answers "is 24-bit
 *    preserved, and does the chain run at all in float mode?")
 *  - the longest run of digital silence while armed (gapless test).
 * It never changes the audio.
 */
@OptIn(UnstableApi::class)
class ProbeAudioProcessor : BaseAudioProcessor() {

    data class Snapshot(
        val configured: Boolean,
        val encoding: Int,
        val sampleRate: Int,
        val channels: Int,
        val framesSeen: Long,
        val longestSilenceMs: Double
    )

    @Volatile private var configured = false
    @Volatile private var encoding = C.ENCODING_INVALID
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    @Volatile private var framesSeen = 0L
    @Volatile private var armed = false
    @Volatile private var longestRun = 0L
    private var currentRun = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        configured = true
        encoding = inputAudioFormat.encoding
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    /** Start measuring silence from now; resets previous measurements. */
    fun arm() { currentRun = 0; longestRun = 0; armed = true }
    fun disarm() { armed = false }

    fun snapshot() = Snapshot(
        configured, encoding, rate, channels, framesSeen,
        if (rate > 0) longestRun * 1000.0 / rate else 0.0
    )

    fun resetCounters() { configured = false; framesSeen = 0 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        if (armed && bytesPerSample > 0 && channels > 0) scanSilence(inputBuffer.duplicate(), bytesPerSample)
        if (bytesPerSample > 0 && channels > 0) framesSeen += remaining / (bytesPerSample * channels)

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    private fun scanSilence(buf: ByteBuffer, bytesPerSample: Int) {
        val frameBytes = bytesPerSample * channels
        while (buf.remaining() >= frameBytes) {
            var silent = true
            repeat(channels) {
                val level = when (encoding) {
                    C.ENCODING_PCM_16BIT -> abs(buf.short.toFloat()) / 32768f
                    C.ENCODING_PCM_24BIT -> {
                        val lo = buf.get().toInt() and 0xFF
                        val mid = buf.get().toInt() and 0xFF
                        val hi = buf.get().toInt()
                        abs(((hi shl 16) or (mid shl 8) or lo).toFloat()) / 8388608f
                    }
                    C.ENCODING_PCM_32BIT -> abs(buf.int.toFloat()) / 2147483648f
                    else -> abs(buf.float)
                }
                if (level > SILENCE_LEVEL) silent = false
            }
            if (silent) {
                currentRun++
                if (currentRun > longestRun) longestRun = currentRun
            } else {
                currentRun = 0
            }
        }
    }

    private companion object {
        /** About -90 dBFS: below any real music content. */
        const val SILENCE_LEVEL = 3.2e-5f
    }
}

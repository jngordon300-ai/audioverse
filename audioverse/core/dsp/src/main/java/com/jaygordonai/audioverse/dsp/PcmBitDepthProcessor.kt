package com.jaygordonai.audioverse.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.floor

enum class WavBitDepth(val bits: Int, val encoding: Int) {
    PCM_16_BIT(16, C.ENCODING_PCM_16BIT),
    PCM_24_BIT(24, C.ENCODING_PCM_24BIT)
}

/**
 * Converts 16-bit, 24-bit or float PCM to the target WAV bit depth.
 *
 * Fixes vs the Council draft:
 *  - Every input/target combination is handled, and the input buffer is always
 *    fully consumed (the draft did nothing for 16<->24, stalling the export).
 *  - Integer paths are exact: 16->24 is a bit shift, 16->16 and 24->24 pass through.
 *  - TPDF dither is applied whenever precision is reduced to 16-bit
 *    (24->16, float->16). Float->24 is not dithered (below audibility).
 *  - Symmetric 2^15 / 2^23 scaling, so a float written from 16-bit returns
 *    the same integer.
 * Output byte order: little-endian, as WAV requires.
 */
@OptIn(UnstableApi::class)
class PcmBitDepthProcessor(
    private val target: WavBitDepth = WavBitDepth.PCM_24_BIT
) : BaseAudioProcessor() {

    private var inEncoding = C.ENCODING_INVALID
    private var rng = 0x2545F491 // xorshift state; no allocation on the audio thread

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        inEncoding = inputAudioFormat.encoding
        if (inEncoding != C.ENCODING_PCM_16BIT &&
            inEncoding != C.ENCODING_PCM_24BIT &&
            inEncoding != C.ENCODING_PCM_FLOAT
        ) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, target.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (inEncoding == target.encoding) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val inBytes = when (inEncoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val outBytes = target.bits / 8
        val samples = remaining / inBytes
        val out = replaceOutputBuffer(samples * outBytes)

        repeat(samples) {
            when (inEncoding) {
                C.ENCODING_PCM_16BIT -> {
                    val v = inputBuffer.short.toInt()
                    // target must be 24-bit here (16->16 passed through above)
                    write24(out, v shl 8)
                }
                C.ENCODING_PCM_24BIT -> {
                    val v = read24(inputBuffer)
                    // target must be 16-bit: reduce with TPDF dither
                    write16(out, ditherTo16(v / 8388608.0))
                }
                else -> {
                    val f = inputBuffer.float.toDouble().coerceIn(-1.0, 1.0)
                    if (target == WavBitDepth.PCM_16_BIT) write16(out, ditherTo16(f))
                    else write24(out, floor(f * 8388608.0 + 0.5).toInt().coerceIn(-8388608, 8388607))
                }
            }
        }
        inputBuffer.position(inputBuffer.limit()) // drop any partial trailing sample
        out.flip()
    }

    private fun ditherTo16(x: Double): Int {
        val tpdf = (nextUniform() - nextUniform()) // triangular, +/-1 LSB
        return floor(x * 32768.0 + tpdf + 0.5).toInt().coerceIn(-32768, 32767)
    }

    private fun nextUniform(): Double {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        rng = x
        return (x.toLong() and 0xFFFFFFFFL) / 4294967296.0
    }

    private fun read24(b: ByteBuffer): Int {
        val lo = b.get().toInt() and 0xFF
        val mid = b.get().toInt() and 0xFF
        val hi = b.get().toInt() // sign-extends
        return (hi shl 16) or (mid shl 8) or lo
    }

    private fun write16(out: ByteBuffer, v: Int) {
        out.put((v and 0xFF).toByte()); out.put(((v shr 8) and 0xFF).toByte())
    }

    private fun write24(out: ByteBuffer, v: Int) {
        out.put((v and 0xFF).toByte())
        out.put(((v shr 8) and 0xFF).toByte())
        out.put(((v shr 16) and 0xFF).toByte())
    }
}

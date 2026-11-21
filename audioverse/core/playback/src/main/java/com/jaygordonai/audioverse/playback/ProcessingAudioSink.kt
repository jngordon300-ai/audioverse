package com.jaygordonai.audioverse.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioVerse output stage (Council U1: DSP separate from the engine).
 *
 * Media3's stock DefaultAudioSink only runs custom processors on 16-bit PCM, and
 * follows them with its own 16-bit-only stages, so a float-output DSP fails there
 * (AUDIO_TRACK_INIT_FAILED) and 24-bit audio is truncated first.
 *
 * This sink runs our processors on the decoder's own PCM (16/24/32-bit or float),
 * then hands the result to a DefaultAudioSink in float mode. Encoder delay/padding
 * are carried over, so Media3's gapless trimming still applies.
 *
 * The processors must consume each input buffer completely in queueInput and keep
 * frame count unchanged (true for the probe and AudiophileDspProcessor).
 */
@OptIn(UnstableApi::class)
class ProcessingAudioSink(
    private val delegate: AudioSink,
    private val processors: List<AudioProcessor>
) : ForwardingAudioSink(delegate) {

    private val activeProcessors = ArrayList<AudioProcessor>()
    private var active = false
    private var pending: ByteBuffer? = null
    private var pendingTimeUs = 0L

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (isProcessablePcm(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else super.getFormatSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pending = null
        activeProcessors.clear()
        if (!isProcessablePcm(inputFormat) || processors.isEmpty()) {
            active = false
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        var current = AudioProcessor.AudioFormat(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding)
        try {
            for (p in processors) {
                val out = p.configure(current)
                if (p.isActive) {
                    activeProcessors += p
                    current = out
                }
            }
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            throw AudioSink.ConfigurationException(e, inputFormat)
        }
        for (p in activeProcessors) p.flush()
        active = activeProcessors.isNotEmpty()

        val outputFormat = inputFormat.buildUpon()
            .setPcmEncoding(current.encoding)
            .setSampleRate(current.sampleRate)
            .setChannelCount(current.channelCount)
            .build()
        super.configure(outputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!active) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        // Deliver anything left from the previous buffer before taking new input.
        if (!drainPending()) return false
        if (!buffer.hasRemaining()) return true

        // Decoder buffers can arrive flagged BIG_ENDIAN even though the PCM is
        // little-endian (native). Media3's own stages never read typed values from
        // them, but our processors do, so read through a native-order view; the
        // caller's buffer is then marked fully consumed.
        var out: ByteBuffer = buffer.duplicate().order(ByteOrder.nativeOrder())
        buffer.position(buffer.limit())
        for (p in activeProcessors) {
            p.queueInput(out)
            out = p.output
        }
        if (out.hasRemaining() && !(super.handleBuffer(out, presentationTimeUs, 1) && !out.hasRemaining())) {
            pending = out
            pendingTimeUs = presentationTimeUs
        }
        return true // input fully consumed (processed); any remainder is held in `pending`
    }

    private fun drainPending(): Boolean {
        val p = pending ?: return true
        if (super.handleBuffer(p, pendingTimeUs, 1) && !p.hasRemaining()) {
            pending = null
            return true
        }
        return false
    }

    override fun playToEndOfStream() {
        if (!drainPending()) return // renderer calls again until isEnded()
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = pending == null && super.isEnded()
    override fun hasPendingData(): Boolean = pending != null || super.hasPendingData()

    override fun flush() {
        pending = null
        for (p in activeProcessors) p.flush()
        super.flush()
    }

    override fun reset() {
        pending = null
        for (p in processors) p.reset()
        activeProcessors.clear()
        active = false
        super.reset()
    }

    private fun isProcessablePcm(format: Format): Boolean =
        MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding in SUPPORTED_ENCODINGS

    private companion object {
        val SUPPORTED_ENCODINGS = setOf(
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT
        )
    }
}

package com.jaygordonai.audioverse.app.dj

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

/**
 * Pure vinyl motion: moves the needle towards where the hand has put the record.
 * [rate] is audio frames per output frame (1 = normal speed, negative = backwards, 0 = held).
 */
object ScratchMath {
    /** Platter: one turn = 1.8 s of audio (33⅓ rpm). */
    const val MS_PER_TURN = 1800.0
    const val MAX_RATE = 8.0

    fun degreesToMs(deg: Float): Double = deg / 360.0 * MS_PER_TURN

    /** Next playback rate: aims to close the gap to the hand within ~2 blocks, smoothed so it doesn't buzz. */
    fun nextRate(rate: Double, gapFrames: Double, blockFrames: Int, smoothing: Double = 0.35): Double {
        val want = (gapFrames / (blockFrames * 2.0)).coerceIn(-MAX_RATE, MAX_RATE)
        return rate + (want - rate) * smoothing
    }

    /** Linear-interpolated sample at a fractional frame position. */
    fun sampleAt(pcm: ShortArray, pos: Double): Float {
        if (pcm.isEmpty()) return 0f
        val p = pos.coerceIn(0.0, (pcm.size - 1).toDouble())
        val i = p.toInt()
        val j = if (i + 1 < pcm.size) i + 1 else i
        val f = (p - i).toFloat()
        return pcm[i] * (1 - f) + pcm[j] * f
    }
}

/**
 * Plays a deck's scratch copy ([Waveform.pcm]) while the platter is touched: forwards, backwards
 * or held still, following the hand. Its own low-latency AudioTrack; the deck's player is muted
 * and paused meanwhile and resumes from wherever the record was left.
 */
class ScratchEngine {
    private val rate = Waveform.PCM_RATE
    @Volatile private var pcm: ShortArray = ShortArray(0)
    @Volatile private var target = 0.0
    @Volatile private var running = false
    @Volatile var volume = 1f
    /** Where the needle is now, in ms. */
    @Volatile var positionMs = 0L; private set
    private var thread: Thread? = null

    val active: Boolean get() = running

    fun start(audio: ShortArray, startMs: Long, vol: Float) {
        stop()
        pcm = audio
        target = startMs * rate / 1000.0
        positionMs = startMs
        volume = vol
        running = true
        thread = Thread({ loop() }, "dj-scratch").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** The hand moved the record by [ms] (negative = pulled back). */
    fun move(ms: Double) {
        target = (target + ms * rate / 1000.0).coerceIn(0.0, (pcm.size - 1).coerceAtLeast(0).toDouble())
    }

    /** Lets go; returns where the needle ended up (ms). */
    fun stop(): Long {
        running = false
        try { thread?.join(300) } catch (_: InterruptedException) { }
        thread = null
        return positionMs
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(minBuf, 2048))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) { running = false; return }
        val block = 256 // ~12 ms
        val out = ShortArray(block)
        var pos = target
        var r = 0.0
        var gain = 0f // short fade in/out: no clicks
        try {
            track.play()
            while (running) {
                val audio = pcm
                r = ScratchMath.nextRate(r, target - pos, block)
                val g = volume
                for (i in 0 until block) {
                    pos = (pos + r).coerceIn(0.0, (audio.size - 1).coerceAtLeast(0).toDouble())
                    gain = (gain + 1f / 64f).coerceAtMost(1f)
                    out[i] = (ScratchMath.sampleAt(audio, pos) * g * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
                positionMs = (pos * 1000.0 / rate).toLong()
                track.write(out, 0, block)
            }
            // fade out the last block
            val audio = pcm
            for (i in 0 until block) {
                gain = (gain - 1f / block).coerceAtLeast(0f)
                out[i] = (ScratchMath.sampleAt(audio, pos) * volume * gain).toInt().toShort()
            }
            track.write(out, 0, block)
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) { }
            track.release()
            positionMs = (pos * 1000.0 / rate).toLong()
        }
    }
}

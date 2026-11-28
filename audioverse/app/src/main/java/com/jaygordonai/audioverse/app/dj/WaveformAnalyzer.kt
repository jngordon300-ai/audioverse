package com.jaygordonai.audioverse.app.dj

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.jaygordonai.audioverse.dsp.BeatDetector
import com.jaygordonai.audioverse.dsp.BeatInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * One loaded track's waveform: [peaks] (0..1) and [bass] (share of low-frequency energy, 0..1,
 * used for colouring) at [FPS] points per second, plus the detected beat grid.
 */
class Waveform(
    val peaks: FloatArray,
    val bass: FloatArray,
    val beat: BeatInfo?,
    /** Mono 16-bit copy of the audio at [PCM_RATE] for vinyl scratching (first [PCM_MAX_SECONDS]); null if none. */
    val pcm: ShortArray? = null
) {
    companion object {
        const val FPS = 100
        const val PCM_RATE = 22_050
        const val PCM_MAX_SECONDS = 10 * 60
    }
}

/**
 * Decodes a local file (content://) on the device to build its waveform and find its BPM.
 * Runs on a background thread; nothing leaves the phone. Live streams and server tracks are
 * skipped (no waveform, no BPM). Stops after 20 minutes of audio.
 */
object WaveformAnalyzer {

    private const val MAX_SECONDS = 20 * 60
    // Each entry can hold up to ~26 MB of scratch audio: keep both decks plus one spare.
    private val cache = object : LinkedHashMap<String, Waveform>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Waveform>?) = size > 3
    }

    suspend fun analyze(context: Context, uri: Uri): Waveform? = withContext(Dispatchers.Default) {
        if (uri.scheme != "content" && uri.scheme != "file") return@withContext null
        synchronized(cache) { cache[uri.toString()] }?.let { return@withContext it }
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@withContext null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime).apply { configure(fmt, null, null, 0); start() }

            val peaks = ArrayList<Float>(MAX_SECONDS * Waveform.FPS / 4)
            val bass = ArrayList<Float>(MAX_SECONDS * Waveform.FPS / 4)
            val onset = ArrayList<Float>(MAX_SECONDS * Waveform.FPS / 4)
            var frameSize = rate / Waveform.FPS
            var lpCoeff = exp(-2.0 * Math.PI * 150.0 / rate).toFloat()
            var lp = 0f
            var inFrame = 0; var framePeak = 0f; var frameAll = 0.0; var frameLow = 0.0
            var lastLowEnergy = 0.0
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var totalFrames = 0L
            val maxFrames = MAX_SECONDS.toLong() * rate
            // Scratch copy: box-averaged down to PCM_RATE mono.
            val pcmMax = Waveform.PCM_MAX_SECONDS * Waveform.PCM_RATE
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var pcm = ShortArray(((durUs / 1_000_000.0 + 2) * Waveform.PCM_RATE).toInt().coerceIn(Waveform.PCM_RATE * 10, pcmMax))
            var pcmLen = 0
            var pcmAcc = 0f; var pcmCnt = 0; var pcmPhase = 0L

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || totalFrames > maxFrames) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else { codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    frameSize = (rate / Waveform.FPS).coerceAtLeast(1)
                    lpCoeff = exp(-2.0 * Math.PI * 150.0 / rate).toFloat()
                } else if (outIdx >= 0) {
                    val out = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset); out.limit(info.offset + info.size)
                    val bytesPerSample = if (floatPcm) 4 else 2
                    while (out.remaining() >= bytesPerSample * channels) {
                        var m = 0f
                        for (c in 0 until channels) m += if (floatPcm) out.float else out.short / 32768f
                        m /= channels
                        pcmAcc += m; pcmCnt++; pcmPhase += Waveform.PCM_RATE
                        if (pcmPhase >= rate) {
                            pcmPhase -= rate
                            if (pcmLen < pcmMax) {
                                if (pcmLen == pcm.size) pcm = pcm.copyOf((pcm.size * 2).coerceAtMost(pcmMax))
                                pcm[pcmLen++] = (pcmAcc / pcmCnt * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            pcmAcc = 0f; pcmCnt = 0
                        }
                        lp = lp * lpCoeff + m * (1 - lpCoeff)
                        val a = abs(m)
                        if (a > framePeak) framePeak = a
                        frameAll += (m * m).toDouble(); frameLow += (lp * lp).toDouble()
                        if (++inFrame >= frameSize) {
                            peaks += framePeak.coerceAtMost(1f)
                            bass += if (frameAll > 1e-9) (frameLow / frameAll).toFloat().coerceIn(0f, 1f) else 0f
                            onset += max(0.0, frameLow - lastLowEnergy).toFloat()
                            lastLowEnergy = frameLow
                            inFrame = 0; framePeak = 0f; frameAll = 0.0; frameLow = 0.0
                        }
                        totalFrames++
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            // Normalise peaks to the loudest point so quiet masters still draw well.
            val maxPeak = (peaks.maxOrNull() ?: 0f).coerceAtLeast(1e-3f)
            val wf = Waveform(
                FloatArray(peaks.size) { peaks[it] / maxPeak },
                bass.toFloatArray(),
                BeatDetector.analyze(onset.toFloatArray(), Waveform.FPS),
                if (pcmLen > Waveform.PCM_RATE) pcm.copyOf(pcmLen) else null
            )
            synchronized(cache) { cache[uri.toString()] = wf }
            wf
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            ex.release()
        }
    }
}

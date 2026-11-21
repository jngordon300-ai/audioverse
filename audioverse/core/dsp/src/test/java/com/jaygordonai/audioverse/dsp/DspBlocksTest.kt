package com.jaygordonai.audioverse.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

class DspBlocksTest {

    @Test
    fun crossfadeHeadroomKeepsCorrelatedSumBelowFullScale() {
        for (curve in CrossfadeCurves.Curve.entries) {
            val ceiling = 10.0.pow(CrossfadeCurves.deckCeilingDbfs(curve) / 20.0).toFloat()
            var x = 0f
            while (x <= 1f) {
                val (gA, gB) = CrossfadeCurves.gains(x, curve)
                // Worst case: two identical full-ceiling signals in phase
                val sum = (gA + gB) * ceiling
                assertTrue("$curve at $x sums to $sum", sum < 1f)
                x += 0.01f
            }
        }
    }

    @Test
    fun balanceNeverBoosts() {
        val mixer = ChannelMixer()
        for (b in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            mixer.balance = b
            mixer.process(0.5f, 0.5f)
            assertTrue(abs(mixer.outL) <= 0.5f + 1e-6f)
            assertTrue(abs(mixer.outR) <= 0.5f + 1e-6f)
        }
    }

    @Test
    fun neutralMixerIsTransparent() {
        val mixer = ChannelMixer()
        mixer.process(0.3f, -0.2f)
        assertEquals(0.3f, mixer.outL, 1e-6f)
        assertEquals(-0.2f, mixer.outR, 1e-6f)
    }

    @Test
    fun vocalStageOffIsTransparent() {
        val stage = VocalStage(48_000)
        stage.beginBuffer()
        stage.process(0.25f, -0.75f)
        assertEquals(0.25f, stage.outL, 0f)
        assertEquals(-0.75f, stage.outR, 0f)
    }

    @Test
    fun vocalCutRemovesCentreAboveCrossover() {
        val rate = 48_000
        val stage = VocalStage(rate).apply {
            mode = VocalStage.Mode.CUT
            strength = 1f
            preserveBass = true
        }
        stage.beginBuffer()
        // 1 kHz identical in both channels = pure centre content
        var peak = 0f
        for (n in 0 until rate) {
            val s = kotlin.math.sin(2 * Math.PI * 1000 * n / rate).toFloat() * 0.5f
            stage.process(s, s)
            if (n > rate / 2) peak = maxOf(peak, abs(stage.outL))
        }
        assertTrue("1 kHz centre should be heavily reduced, peak=$peak", peak < 0.05f)
    }

    @Test
    fun monoTrackIsDetected() {
        val stage = VocalStage(48_000)
        stage.beginBuffer()
        for (n in 0 until 48_000) {
            val s = kotlin.math.sin(n * 0.05).toFloat() * 0.5f
            stage.process(s, s)
        }
        assertTrue(stage.sideToMidRatio < 0.001f)
    }

    @Test
    fun pcm16To24IsExact() {
        val p = PcmBitDepthProcessor(WavBitDepth.PCM_24_BIT)
        p.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        shortArrayOf(1, -1, Short.MAX_VALUE, Short.MIN_VALUE).forEach { input.putShort(it) }
        input.flip()
        p.queueInput(input)
        val out = p.output
        assertEquals(12, out.remaining())
        val expected = intArrayOf(1 shl 8, -1 shl 8, Short.MAX_VALUE.toInt() shl 8, Short.MIN_VALUE.toInt() shl 8)
        for (e in expected) {
            val lo = out.get().toInt() and 0xFF
            val mid = out.get().toInt() and 0xFF
            val hi = out.get().toInt()
            assertEquals(e, (hi shl 16) or (mid shl 8) or lo)
        }
        assertEquals(0, input.remaining())
    }

    @Test
    fun replayGainCapsAtPeak() {
        val tags = ReplayGainTags(trackGainDb = 6f, trackPeak = 0.8f)
        val gain = ReplayGainParser.resolveGainDb(tags, ReplayGainMode.TRACK)
        assertTrue(gain < 2f) // -20*log10(0.8) = 1.94 dB
    }
}

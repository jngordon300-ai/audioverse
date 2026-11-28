package com.jaygordonai.audioverse.app.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjProTest {

    @Test fun syncMatchesTempoWithinRange() {
        assertEquals(128f / 124f, DjMath.syncTempo(124f, 128f)!!, 1e-4f)
        assertEquals(1f, DjMath.syncTempo(128f, 128f)!!, 1e-6f)
    }

    @Test fun syncUsesDoubleAndHalfTime() {
        // 70 BPM against 140 BPM syncs at 1.0 (double time), not at 2.0.
        assertEquals(1f, DjMath.syncTempo(70f, 140f)!!, 1e-4f)
        assertEquals(1f, DjMath.syncTempo(140f, 70f)!!, 1e-4f)
    }

    @Test fun syncRefusesFarTemposAndMissingBpm() {
        assertNull(DjMath.syncTempo(90f, 128f))
        assertNull(DjMath.syncTempo(0f, 128f))
        assertNull(DjMath.syncTempo(128f, 0f))
    }

    @Test fun threeBandEqCoversAllTenBands() {
        val bands = (0 until 10).map { DjMath.bandOffset(it, low = -1f, mid = 2f, high = 3f) }
        assertEquals(listOf(-1f, -1f, -1f, 2f, 2f, 2f, 2f, 3f, 3f, 3f), bands)
    }

    @Test fun samplerPadsAreShortAndKeepHeadroom() {
        for (name in SamplerSynth.pads) {
            val pcm = SamplerSynth.render(name)
            assertTrue("$name empty", pcm.isNotEmpty())
            assertTrue("$name too long", pcm.size <= SamplerSynth.RATE * 3)
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("$name clips ($peak)", peak <= (0.86 * Short.MAX_VALUE).toInt())
        }
    }

    @Test fun wavHeaderIsValid() {
        val wav = SamplerSynth.wav(ShortArray(100))
        assertEquals(44 + 200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
    }
}

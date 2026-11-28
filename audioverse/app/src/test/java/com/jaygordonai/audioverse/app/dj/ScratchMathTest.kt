package com.jaygordonai.audioverse.app.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchMathTest {

    @Test fun oneTurnIsOnePointEightSeconds() {
        assertEquals(1800.0, ScratchMath.degreesToMs(360f), 1e-9)
        assertEquals(-450.0, ScratchMath.degreesToMs(-90f), 1e-9)
    }

    @Test fun rateFollowsTheHandBothWays() {
        var fwd = 0.0; var back = 0.0
        repeat(20) { fwd = ScratchMath.nextRate(fwd, 512.0, 256); back = ScratchMath.nextRate(back, -512.0, 256) }
        assertEquals(1.0, fwd, 0.01)   // 512 frames over 2 blocks of 256 = normal speed
        assertEquals(-1.0, back, 0.01) // pulled back = reverse
    }

    @Test fun heldRecordStopsAndFastSpinIsCapped() {
        var r = 3.0
        repeat(40) { r = ScratchMath.nextRate(r, 0.0, 256) }
        assertEquals(0.0, r, 1e-3)
        repeat(40) { r = ScratchMath.nextRate(r, 1e9, 256) }
        assertEquals(ScratchMath.MAX_RATE, r, 1e-3)
    }

    @Test fun interpolatesAndClampsAtTheEnds() {
        val pcm = shortArrayOf(0, 1000, 2000)
        assertEquals(500f, ScratchMath.sampleAt(pcm, 0.5), 1e-3f)
        assertEquals(0f, ScratchMath.sampleAt(pcm, -5.0), 1e-3f)
        assertEquals(2000f, ScratchMath.sampleAt(pcm, 99.0), 1e-3f)
        assertTrue(ScratchMath.sampleAt(ShortArray(0), 1.0) == 0f)
    }
}

package com.jaygordonai.audioverse.app.dj

import com.jaygordonai.audioverse.dsp.CrossfadeCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjMathTest {

    @Test fun tempoSnapsToHalfPercentAndStaysInRange() {
        assertEquals(1.03f, DjMath.clampTempo(1.0312f), 1e-4f)
        assertEquals(1f + DjMath.TEMPO_RANGE, DjMath.clampTempo(1.5f), 1e-4f)
        assertEquals(1f - DjMath.TEMPO_RANGE, DjMath.clampTempo(0.2f), 1e-4f)
        assertEquals(1f, DjMath.clampTempo(1.001f), 1e-4f)
    }

    @Test fun fadeStartsEndsAndMovesOneWay() {
        assertEquals(0f, DjMath.fadePosition(0f, 1f, 0, 8000), 0f)
        assertEquals(1f, DjMath.fadePosition(0f, 1f, 8000, 8000), 0f)
        assertEquals(1f, DjMath.fadePosition(0f, 1f, 99999, 8000), 0f)
        assertEquals(0.5f, DjMath.fadePosition(0f, 1f, 4000, 8000), 1e-4f)
        var last = -1f
        for (ms in 0..8000 step 250) {
            val p = DjMath.fadePosition(0f, 1f, ms.toLong(), 8000)
            assertTrue(p >= last); last = p
        }
        assertEquals(0.2f, DjMath.fadePosition(0.2f, 0f, 0, 4000), 1e-4f) // fades back to A from where it is
    }

    @Test fun centreDetent() {
        assertEquals(0.5f, DjMath.detent(0.51f), 0f)
        assertEquals(0.6f, DjMath.detent(0.6f), 0f)
        assertEquals(1f, DjMath.detent(1.2f), 0f)
    }

    @Test fun volumesFollowCurveAndTrim() {
        val (a0, b0) = DjMath.volumes(0f, CrossfadeCurves.Curve.CONSTANT_POWER, 1f, 1f)
        assertEquals(1f, a0, 1e-4f); assertEquals(0f, b0, 1e-4f)
        val (a1, b1) = DjMath.volumes(1f, CrossfadeCurves.Curve.CONSTANT_POWER, 1f, 0.5f)
        assertEquals(0f, a1, 1e-4f); assertEquals(0.5f, b1, 1e-4f)
        val (am, bm) = DjMath.volumes(0.5f, CrossfadeCurves.Curve.CONSTANT_POWER, 1f, 1f)
        assertEquals(1f, am * am + bm * bm, 1e-3f) // equal power at the centre
    }
}

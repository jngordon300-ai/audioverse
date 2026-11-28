package com.jaygordonai.audioverse.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BeatDetectorTest {
    private val fps = 100

    /** Onset envelope with a pulse every beat, starting at [firstMs], plus a little noise. */
    private fun clicks(bpm: Float, seconds: Int, firstMs: Int, offbeats: Boolean = false): FloatArray {
        val e = FloatArray(seconds * fps)
        val r = Random(7)
        for (i in e.indices) e[i] = r.nextFloat() * 0.05f
        val period = 60f * fps / bpm
        var t = firstMs * fps / 1000f
        while (t < e.size) {
            e[t.toInt()] += 1f
            if (offbeats) { val h = (t + period / 2).toInt(); if (h < e.size) e[h] += 0.4f }
            t += period
        }
        return e
    }

    @Test fun findsHipHopAndHouseTempos() {
        for (bpm in listOf(90f, 95f, 100f, 124f, 128f, 140f)) {
            val b = BeatDetector.analyze(clicks(bpm, 30, 120), fps)!!
            assertEquals("bpm $bpm", bpm, b.bpm, 1.0f)
        }
    }

    @Test fun offbeatHiHatsDontDoubleTheTempo() {
        val b = BeatDetector.analyze(clicks(92f, 30, 0, offbeats = true), fps)!!
        assertEquals(92f, b.bpm, 1.0f)
    }

    @Test fun findsTheFirstBeat() {
        val b = BeatDetector.analyze(clicks(100f, 30, 250), fps)!!
        // 600 ms per beat at 100 BPM: phase within one frame (10 ms) of 250 ms
        assertTrue("first beat ${b.firstBeatMs}", kotlin.math.abs(b.firstBeatMs - 250) <= 20)
        assertTrue(b.confidence > 0.3f)
    }

    @Test fun tooShortOrSilentGivesNoAnswer() {
        assertNull(BeatDetector.analyze(FloatArray(300), fps))
        assertNull(BeatDetector.analyze(FloatArray(3000), fps))
    }

    @Test fun phaseCorrectionTakesTheShortWay() {
        val a = BeatInfo(100f, 0, 1f)   // 600 ms beats
        val b = BeatInfo(100f, 0, 1f)
        assertEquals(0L, BeatDetector.phaseCorrectionMs(1200, a, 600, b))
        assertEquals(150L, BeatDetector.phaseCorrectionMs(1350, a, 1200, b))
        assertEquals(-150L, BeatDetector.phaseCorrectionMs(1200, a, 1350, b))
    }
}

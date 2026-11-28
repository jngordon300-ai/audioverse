package com.jaygordonai.audioverse.dsp

import kotlin.math.max

/** Tempo and first-beat position found by BeatDetector. */
data class BeatInfo(val bpm: Float, val firstBeatMs: Long, val confidence: Float) {
    val beatMs: Float get() = if (bpm > 0) 60_000f / bpm else 0f
}

/**
 * Finds the tempo from an onset envelope (e.g. positive change in bass energy, [fps] frames per
 * second): autocorrelation over 70–180 BPM, folded into [minBpm, maxBpm), refined with parabolic
 * interpolation, then the beat phase that lines up best with the onsets. Pure Kotlin; unit tested.
 */
object BeatDetector {

    fun analyze(envelope: FloatArray, fps: Int, minBpm: Float = 78f, maxBpm: Float = 160f): BeatInfo? {
        if (envelope.size < fps * 8) return null // need ~8 s of audio
        // Remove the mean so silence doesn't correlate.
        val mean = envelope.average().toFloat()
        val e = FloatArray(envelope.size) { max(0f, envelope[it] - mean) }

        val minLag = (fps * 60f / 180f).toInt().coerceAtLeast(2)
        val maxLag = (fps * 60f / 70f).toInt() + 1
        if (maxLag >= e.size / 2) return null
        val ac = FloatArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0.0
            for (i in 0 until e.size - lag) s += e[i] * e[i + lag]
            ac[lag] = (s / (e.size - lag)).toFloat()
        }
        // Score each lag with its double too (helps pick the beat, not the half-beat).
        var best = -1; var bestScore = 0f
        for (lag in minLag..maxLag) {
            val dbl = if (lag * 2 <= maxLag + 1) ac[lag * 2] else 0f
            val score = ac[lag] + 0.5f * dbl
            if (score > bestScore) { bestScore = score; best = lag }
        }
        if (best <= 0 || bestScore <= 0f) return null

        // Parabolic interpolation for sub-frame accuracy.
        val y0 = ac[best - 1]; val y1 = ac[best]; val y2 = ac[best + 1]
        val denom = y0 - 2 * y1 + y2
        val shift = if (denom != 0f) (0.5f * (y0 - y2) / denom).coerceIn(-0.5f, 0.5f) else 0f
        val lag = best + shift
        var bpm = 60f * fps / lag
        while (bpm < minBpm) bpm *= 2f
        while (bpm >= maxBpm) bpm /= 2f

        // Phase: offset (in frames) within one beat that collects the most onset energy.
        val period = 60f * fps / bpm
        val steps = period.toInt().coerceAtLeast(1)
        var bestOff = 0; var bestSum = -1.0
        for (off in 0 until steps) {
            var s = 0.0; var t = off.toFloat()
            while (t < e.size) { s += e[t.toInt()]; t += period }
            if (s > bestSum) { bestSum = s; bestOff = off }
        }
        // Confidence: how much more onset energy sits on the beat grid than a uniform spread would give.
        val total = e.sum().toDouble().coerceAtLeast(1e-9)
        val ratio = bestSum / (total / steps)
        val confidence = (((ratio - 1.0) / (steps - 1).coerceAtLeast(1)) * 4.0).toFloat().coerceIn(0f, 1f)
        return BeatInfo(Math.round(bpm * 10) / 10f, (bestOff * 1000L) / fps, confidence)
    }

    /** Beat-phase offset (ms, shortest way) that would line deck B's beats up with deck A's. */
    fun phaseCorrectionMs(posA: Long, beatA: BeatInfo, posB: Long, beatB: BeatInfo): Long {
        if (beatA.beatMs <= 0f || beatB.beatMs <= 0f) return 0
        val fracA = (((posA - beatA.firstBeatMs) / beatA.beatMs) % 1f + 1f) % 1f
        val fracB = (((posB - beatB.firstBeatMs) / beatB.beatMs) % 1f + 1f) % 1f
        var d = fracA - fracB
        if (d > 0.5f) d -= 1f
        if (d < -0.5f) d += 1f
        return (d * beatB.beatMs).toLong()
    }
}

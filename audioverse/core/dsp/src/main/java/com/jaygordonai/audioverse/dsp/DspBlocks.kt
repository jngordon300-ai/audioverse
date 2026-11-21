package com.jaygordonai.audioverse.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Merged DSP building blocks (Claude + Council drafts).
 * Threading rule for every class here: settings are @Volatile and may be set from
 * the UI thread; process()/beginBuffer() run ONLY on the audio thread. Filter
 * coefficients are rebuilt on the audio thread at the start of a buffer, never
 * mid-sample, so UI changes cannot tear a coefficient set.
 */

/** RBJ-cookbook biquad, Direct Form I. Audio thread only. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun designLowPass(freqHz: Float, sampleRateHz: Int, q: Double = 0.70710678) {
        val f = freqHz.coerceIn(20f, sampleRateHz * 0.45f)
        val w = 2.0 * PI * f / sampleRateHz
        val cosW = cos(w)
        val alpha = sin(w) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (((1.0 - cosW) / 2.0) / a0).toFloat()
        b1 = ((1.0 - cosW) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cosW) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(x: Float): Float {
        var y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        if (abs(y) < 1e-24f) y = 0f // denormal guard
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
}

/** 24 dB/oct Linkwitz-Riley low-pass: two cascaded Butterworth biquads (Council design). */
class LinkwitzRiley4LowPass {
    private val stage1 = Biquad()
    private val stage2 = Biquad()

    fun design(cutoffHz: Float, sampleRateHz: Int) {
        stage1.designLowPass(cutoffHz, sampleRateHz)
        stage2.designLowPass(cutoffHz, sampleRateHz)
    }

    fun process(x: Float): Float = stage2.process(stage1.process(x))
    fun reset() { stage1.reset(); stage2.reset() }
}

/**
 * Vocal stage: real-time centre-channel processing.
 *
 *  CUT          - karaoke / instrumental. Attenuates the centre ("mid") signal,
 *                 optionally keeping centre bass and kick below the crossover.
 *  CENTRE_FOCUS - attenuates the stereo sides, leaving what is mixed in the
 *                 centre. This is NOT a true acapella: centre snare, kick and
 *                 bass remain (bass is reduced when preserveBass is on).
 *
 * Honest limits (show in UI): reverb and stereo backing vocals survive CUT;
 * mono recordings cannot be processed. At partial strength there is a small
 * response dip around the crossover; acceptable for karaoke use.
 * Studio-grade separation is a separate, later AI stem feature.
 */
class VocalStage(private val sampleRateHz: Int) {
    enum class Mode { OFF, CUT, CENTRE_FOCUS }

    @Volatile var mode = Mode.OFF
    /** 0 = untouched, 1 = maximum attenuation. */
    @Volatile var strength = 0.85f
    @Volatile var preserveBass = true
    @Volatile var bassCrossoverHz = 120f
        set(v) { field = v.coerceIn(60f, 250f); rebuildPending = true }

    @Volatile private var rebuildPending = true
    private val bassFilter = LinkwitzRiley4LowPass()

    /** Rolling side/mid energy ratio; near 0 means the track is effectively mono. */
    @Volatile var sideToMidRatio = 1f; private set
    /** Same ratio in dB (side energy relative to mid energy). */
    @Volatile var sideToMidDb = 0f; private set
    /**
     * Normalised L/R (Pearson) correlation, -1..+1. Near +1 = mono or near-mono,
     * around 0..0.9 = normal stereo, below 0 = out-of-phase content.
     */
    @Volatile var correlation = 0f; private set
    /** True when centre cancellation will do little: r > 0.98 or side/mid below -24 dB (DJ-03). */
    val isNearMono: Boolean get() = correlation > NEAR_MONO_CORRELATION || sideToMidDb < NEAR_MONO_SIDE_DB

    private var sideEnergy = 0f
    private var midEnergy = 0f
    private var lrSum = 0f
    private var llSum = 0f
    private var rrSum = 0f

    var outL = 0f; private set
    var outR = 0f; private set

    /** Call once at the start of each audio buffer. */
    fun beginBuffer() {
        if (rebuildPending) {
            bassFilter.design(bassCrossoverHz, sampleRateHz)
            rebuildPending = false
        }
    }

    fun process(l: Float, r: Float) {
        var mid = (l + r) * 0.5f
        var side = (l - r) * 0.5f

        sideEnergy = sideEnergy * DECAY + side * side * GAIN
        midEnergy = midEnergy * DECAY + mid * mid * GAIN
        lrSum = lrSum * DECAY + l * r * GAIN
        llSum = llSum * DECAY + l * l * GAIN
        rrSum = rrSum * DECAY + r * r * GAIN
        sideToMidRatio = if (midEnergy > 1e-12f) sideEnergy / midEnergy else 1f
        sideToMidDb = 10f * log10(sideToMidRatio.coerceAtLeast(1e-12f))
        val denom = sqrt(llSum * rrSum)
        correlation = if (denom > 1e-12f) (lrSum / denom).coerceIn(-1f, 1f) else 0f

        val keep = 1f - strength.coerceIn(0f, 1f)
        when (mode) {
            Mode.OFF -> { outL = l; outR = r; return }
            Mode.CUT -> {
                if (preserveBass) {
                    val bass = bassFilter.process(mid)
                    mid = bass + (mid - bass) * keep
                } else {
                    mid *= keep
                }
            }
            Mode.CENTRE_FOCUS -> {
                side *= keep
                if (preserveBass) mid -= bassFilter.process(mid) * 0.85f
            }
        }
        outL = mid + side
        outR = mid - side
    }

    fun reset() {
        bassFilter.reset()
        sideEnergy = 0f; midEnergy = 0f; lrSum = 0f; llSum = 0f; rrSum = 0f
        rebuildPending = true
    }

    private companion object {
        // ~43 ms smoothing at 44.1 kHz; the ratios are level-independent, so quiet
        // and loud passages give the same reading.
        const val DECAY = 0.9995f
        const val GAIN = 1f - DECAY
        const val NEAR_MONO_CORRELATION = 0.98f
        const val NEAR_MONO_SIDE_DB = -24f
    }
}

/**
 * Channel mixer: phase invert, mono, stereo width, balance, per-channel trim.
 * Balance only ever attenuates the opposite side (the Council draft's sqrt(2)
 * pan law boosted the loud side by +3 dB and could clip).
 */
class ChannelMixer {
    @Volatile var invertLeft = false
    @Volatile var invertRight = false
    @Volatile var mono = false
    /** 0 = mono, 1 = original, 2 = extra wide. */
    @Volatile var width = 1f
    /** -1 = full left, 0 = centre, +1 = full right. */
    @Volatile var balance = 0f
    @Volatile var leftTrimDb = 0f
    @Volatile var rightTrimDb = 0f

    var outL = 0f; private set
    var outR = 0f; private set

    /** Phase invert is applied first in the chain (before the vocal stage). */
    fun applyPhase(l: Float, r: Float) {
        outL = if (invertLeft) -l else l
        outR = if (invertRight) -r else r
    }

    fun process(l: Float, r: Float) {
        val w = if (mono) 0f else width.coerceIn(0f, 2f)
        val mid = (l + r) * 0.5f
        val side = (l - r) * 0.5f * w
        val b = balance.coerceIn(-1f, 1f)
        val gL = (if (b > 0f) 1f - b else 1f) * dbToLinear(leftTrimDb.coerceIn(-12f, 6f))
        val gR = (if (b < 0f) 1f + b else 1f) * dbToLinear(rightTrimDb.coerceIn(-12f, 6f))
        outL = (mid + side) * gL
        outR = (mid - side) * gR
    }

    fun resetToNeutral() {
        invertLeft = false; invertRight = false; mono = false
        width = 1f; balance = 0f; leftTrimDb = 0f; rightTrimDb = 0f
    }

    private fun dbToLinear(db: Float) = 10.0.pow(db / 20.0).toFloat()
}

/** Crossfader curves for the DJ mixer. */
object CrossfadeCurves {
    enum class Curve { CONSTANT_POWER, LINEAR, SHARP_CUT }

    /** Returns (gainA, gainB). position 0 = deck A only, 1 = deck B only. */
    fun gains(position: Float, curve: Curve): Pair<Float, Float> {
        val x = position.coerceIn(0f, 1f)
        return when (curve) {
            Curve.CONSTANT_POWER -> Pair(cos(x * PI / 2).toFloat(), sin(x * PI / 2).toFloat())
            Curve.LINEAR -> Pair(1f - x, x)
            Curve.SHARP_CUT -> Pair(if (x < 0.95f) 1f else 0f, if (x > 0.05f) 1f else 0f)
        }
    }

    /** Per-deck limiter ceiling so the summed output stays below 0 dBFS. */
    fun deckCeilingDbfs(curve: Curve): Float = when (curve) {
        Curve.CONSTANT_POWER -> -4f // centre: 2 x 0.707
        Curve.LINEAR -> -1f         // centre: 2 x 0.5
        Curve.SHARP_CUT -> -7f      // both decks at full gain over most of the travel
    }
}

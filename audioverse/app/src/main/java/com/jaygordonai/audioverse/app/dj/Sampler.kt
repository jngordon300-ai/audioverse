package com.jaygordonai.audioverse.app.dj

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sampler sounds synthesised in code (original, royalty-free; no recordings bundled).
 * Pure functions returning 16-bit mono PCM at [RATE]; unit tested for length and headroom.
 */
object SamplerSynth {
    const val RATE = 44_100
    val pads = listOf("Kick", "Snare", "Clap", "Hi-Hat", "Open Hat", "Air Horn", "Siren", "Laser")

    fun render(name: String): ShortArray = when (name) {
        "Kick" -> kick(); "Snare" -> snare(); "Clap" -> clap(); "Hi-Hat" -> hat(0.06); "Open Hat" -> hat(0.35)
        "Air Horn" -> airHorn(); "Siren" -> siren(); "Laser" -> laser(); else -> ShortArray(0)
    }

    private fun pcm(seconds: Double, f: (t: Double, i: Int) -> Double): ShortArray {
        val n = (seconds * RATE).toInt()
        return ShortArray(n) { i -> (f(i.toDouble() / RATE, i).coerceIn(-1.0, 1.0) * 0.85 * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun kick(): ShortArray {
        var phase = 0.0
        return pcm(0.45) { t, _ ->
            val freq = 45 + 110 * exp(-t * 28)
            phase += 2 * PI * freq / RATE
            sin(phase) * exp(-t * 7.5)
        }
    }

    private fun snare(): ShortArray {
        val r = Random(1)
        return pcm(0.28) { t, _ -> 0.55 * (r.nextDouble() * 2 - 1) * exp(-t * 18) + 0.45 * sin(2 * PI * 190 * t) * exp(-t * 25) }
    }

    private fun clap(): ShortArray {
        val r = Random(2)
        return pcm(0.3) { t, _ ->
            val bursts = listOf(0.0, 0.011, 0.023).sumOf { s -> if (t >= s) exp(-(t - s) * 60) else 0.0 }
            (r.nextDouble() * 2 - 1) * (0.5 * bursts + 0.5 * exp(-t * 14))
        }
    }

    private fun hat(decay: Double): ShortArray {
        val r = Random(3); var last = 0.0
        return pcm(decay + 0.05) { t, _ ->
            val w = r.nextDouble() * 2 - 1
            val hp = w - last; last = w // crude high-pass for a bright metallic hiss
            0.6 * hp * exp(-t * (4.0 / decay))
        }
    }

    private fun airHorn(): ShortArray {
        val freqs = listOf(466.0, 470.0, 587.0, 699.0)
        return pcm(1.1) { t, _ ->
            val env = minOf(1.0, t * 30) * (if (t > 0.95) (1.1 - t) / 0.15 else 1.0)
            val stutter = if ((t * 8).toInt() % 2 == 0 || t > 0.5) 1.0 else 0.35
            env * stutter * freqs.sumOf { f -> 2 * ((t * f) % 1.0) - 1 } / freqs.size
        }
    }

    private fun siren(): ShortArray {
        var phase = 0.0
        return pcm(1.6) { t, _ ->
            val freq = 750 + 350 * sin(2 * PI * 1.6 * t)
            phase += 2 * PI * freq / RATE
            sin(phase) * minOf(1.0, t * 20) * minOf(1.0, (1.6 - t) * 10)
        }
    }

    private fun laser(): ShortArray {
        var phase = 0.0
        return pcm(0.35) { t, _ ->
            val freq = 2400 * exp(-t * 9) + 120
            phase += 2 * PI * freq / RATE
            (if (sin(phase) >= 0) 0.7 else -0.7) * exp(-t * 6)
        }
    }

    /** Wraps PCM in a minimal WAV header so SoundPool can load it. */
    fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}

/** Plays the sampler pads over the decks (does not take audio focus). */
class Sampler(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .build()
    private val ids = HashMap<String, Int>()
    @Volatile var volume: Float = 0.8f

    init {
        val dir = File(context.cacheDir, "sampler").apply { mkdirs() }
        SamplerSynth.pads.forEach { name ->
            val f = File(dir, name.replace(' ', '_').replace("-", "") + ".wav")
            if (!f.exists()) FileOutputStream(f).use { it.write(SamplerSynth.wav(SamplerSynth.render(name))) }
            ids[name] = pool.load(f.path, 1)
        }
    }

    fun play(name: String) { ids[name]?.let { pool.play(it, volume, volume, 1, 0, 1f) } }
    fun release() = pool.release()
}

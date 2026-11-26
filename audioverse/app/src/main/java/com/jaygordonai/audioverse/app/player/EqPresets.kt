package com.jaygordonai.audioverse.app.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One-tap EQ curve (31 Hz … 16 kHz) with the preamp that keeps it from clipping. */
data class EqPreset(val name: String, val bands: List<Float>, val custom: Boolean = false) {
    /** Lowers the volume by the biggest boost, so a curve never just makes the limiter work. */
    val preampDb: Float get() = -(Math.round((bands.maxOrNull() ?: 0f).coerceAtLeast(0f) * 2) / 2f)
}

object EqPresets {
    const val MAX_CUSTOM = 8

    val builtIn = listOf(
        EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Boom Bap", listOf(5f, 4f, 2f, 0f, -1f, 0f, 1f, 2f, 2f, 1f)),
        EqPreset("Bass Boost", listOf(7f, 6f, 4f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("R&B Warm", listOf(3f, 3f, 2f, 1f, 0f, -1f, 0f, 1f, 1f, 0f)),
        EqPreset("Vocal", listOf(-2f, -1f, 0f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
        EqPreset("Bright", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 4f)),
        EqPreset("Late Night", listOf(-3f, -2f, -1f, 0f, 1f, 2f, 2f, 1f, 0f, -2f)),
        EqPreset("Earbuds", listOf(3f, 2f, 0f, -1f, -1f, 0f, 1f, 2f, 1f, 0f))
    )

    /** The preset whose curve matches the current bands (to highlight its chip), if any. */
    fun matching(bands: List<Float>, all: List<EqPreset>): EqPreset? =
        all.firstOrNull { p -> p.bands.size == bands.size && p.bands.zip(bands).all { (a, b) -> kotlin.math.abs(a - b) < 0.01f } }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("audioverse_sound", Context.MODE_PRIVATE)

    fun loadCustom(c: Context): List<EqPreset> = try {
        val arr = JSONArray(prefs(c).getString("eq_presets", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i); val b = o.getJSONArray("b")
            EqPreset(o.getString("n"), List(b.length()) { b.getDouble(it).toFloat() }, custom = true)
        }.filter { it.bands.size == 10 }
    } catch (e: Exception) { emptyList() }

    fun saveCustom(c: Context, list: List<EqPreset>) {
        val arr = JSONArray()
        list.take(MAX_CUSTOM).forEach { p -> arr.put(JSONObject().put("n", p.name).put("b", JSONArray(p.bands.map { it.toDouble() }))) }
        prefs(c).edit().putString("eq_presets", arr.toString()).apply()
    }
}

package com.jaygordonai.audioverse.app.player

import android.content.Context
import com.jaygordonai.audioverse.dsp.AudiophileDspProcessor
import com.jaygordonai.audioverse.dsp.ReplayGainMode
import com.jaygordonai.audioverse.dsp.VocalStage

/**
 * The listener's sound settings, saved between launches. Contains no personal
 * data (only numbers and switches), so plain SharedPreferences is fine.
 */
data class SoundSettings(
    val replayGain: ReplayGainMode = ReplayGainMode.TRACK,
    val preampDb: Float = 0f,
    val eqEnabled: Boolean = true,
    val eqDb: List<Float> = List(10) { 0f },
    val limiterEnabled: Boolean = true,
    val vocalMode: VocalStage.Mode = VocalStage.Mode.OFF,
    val vocalStrength: Float = 0.85f,
    val balance: Float = 0f,
    val width: Float = 1f,
    val mono: Boolean = false
) {
    fun applyTo(dsp: AudiophileDspProcessor) {
        dsp.setReplayGainMode(replayGain)
        dsp.preampDb = preampDb
        dsp.eqEnabled = eqEnabled
        eqDb.forEachIndexed { i, g -> dsp.setEqBand(i, g) }
        dsp.limiterEnabled = limiterEnabled
        dsp.limiterCeilingDbfs = -1f
        dsp.vocalMode = vocalMode
        dsp.vocalStrength = vocalStrength
        dsp.channelMixer.balance = balance
        dsp.channelMixer.width = width
        dsp.channelMixer.mono = mono
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putString("rg", replayGain.name)
            .putFloat("preamp", preampDb)
            .putBoolean("eqOn", eqEnabled)
            .putString("eq", eqDb.joinToString(","))
            .putBoolean("limiter", limiterEnabled)
            .putString("vocal", vocalMode.name)
            .putFloat("vocalStrength", vocalStrength)
            .putFloat("balance", balance)
            .putFloat("width", width)
            .putBoolean("mono", mono)
            .apply()
    }

    companion object {
        val EQ_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

        fun load(context: Context): SoundSettings {
            val p = prefs(context)
            val d = SoundSettings()
            return SoundSettings(
                replayGain = enumOr(p.getString("rg", null), d.replayGain),
                preampDb = p.getFloat("preamp", d.preampDb),
                eqEnabled = p.getBoolean("eqOn", d.eqEnabled),
                eqDb = p.getString("eq", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
                    ?.takeIf { it.size == 10 } ?: d.eqDb,
                limiterEnabled = p.getBoolean("limiter", d.limiterEnabled),
                vocalMode = enumOr(p.getString("vocal", null), d.vocalMode),
                vocalStrength = p.getFloat("vocalStrength", d.vocalStrength),
                balance = p.getFloat("balance", d.balance),
                width = p.getFloat("width", d.width),
                mono = p.getBoolean("mono", d.mono)
            )
        }

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("audioverse_sound", Context.MODE_PRIVATE)

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
    }
}

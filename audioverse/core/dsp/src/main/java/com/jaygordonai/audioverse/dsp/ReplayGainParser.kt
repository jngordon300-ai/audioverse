package com.jaygordonai.audioverse.dsp

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlin.math.log10

enum class ReplayGainMode { OFF, TRACK, ALBUM }

data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null
)

/** Reads ReplayGain from FLAC/Vorbis comments and ID3 TXXX frames (Council draft). */
object ReplayGainParser {

    @OptIn(UnstableApi::class)
    fun parseFromMetadata(metadata: Metadata): ReplayGainTags {
        var trackGain: Float? = null
        var albumGain: Float? = null
        var trackPeak: Float? = null
        var albumPeak: Float? = null

        fun apply(key: String, value: String) {
            when (key.uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> trackGain = parseDb(value)
                "REPLAYGAIN_ALBUM_GAIN" -> albumGain = parseDb(value)
                "REPLAYGAIN_TRACK_PEAK" -> trackPeak = value.trim().toFloatOrNull()
                "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = value.trim().toFloatOrNull()
            }
        }

        for (i in 0 until metadata.length()) {
            when (val entry = metadata.get(i)) {
                is VorbisComment -> apply(entry.key, entry.value)
                is TextInformationFrame -> {
                    if (entry.id.equals("TXXX", ignoreCase = true)) {
                        val desc = entry.description ?: continue
                        val value = entry.values.firstOrNull() ?: continue
                        apply(desc, value)
                    }
                }
            }
        }
        return ReplayGainTags(trackGain, albumGain, trackPeak, albumPeak)
    }

    /** Effective gain in dB; optionally capped so the stated peak cannot clip. */
    fun resolveGainDb(
        tags: ReplayGainTags,
        mode: ReplayGainMode,
        fallbackUntaggedDb: Float = 0f,
        preventPeakClipping: Boolean = true
    ): Float {
        if (mode == ReplayGainMode.OFF) return 0f
        val raw = when (mode) {
            ReplayGainMode.TRACK -> tags.trackGainDb ?: tags.albumGainDb
            else -> tags.albumGainDb ?: tags.trackGainDb
        } ?: return fallbackUntaggedDb
        if (!preventPeakClipping) return raw
        val peak = when (mode) {
            ReplayGainMode.TRACK -> tags.trackPeak ?: tags.albumPeak
            else -> tags.albumPeak ?: tags.trackPeak
        } ?: return raw
        if (peak <= 0f) return raw
        return minOf(raw, -20f * log10(peak))
    }

    private fun parseDb(raw: String): Float? =
        raw.replace("dB", "", ignoreCase = true).trim().toFloatOrNull()
}

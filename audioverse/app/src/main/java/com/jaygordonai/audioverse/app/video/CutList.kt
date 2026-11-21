package com.jaygordonai.audioverse.app.video

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Segment(val id: Long, val name: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** Naming presets so a game or a card splits with one tap per cut. */
enum class CutTemplate(val label: String) {
    BASKETBALL("Basketball"),
    FOOTBALL("Football"),
    WRESTLING("Wrestling"),
    CUSTOM("Custom");

    /** Name for the segment at [index] (0-based). */
    fun nameFor(index: Int): String = when (this) {
        BASKETBALL, FOOTBALL -> if (index < 4) "Q${index + 1}" else if (index == 4) "OT" else "OT${index - 3}"
        WRESTLING -> "Match ${index + 1}"
        CUSTOM -> "Part ${index + 1}"
    }
}

/**
 * Saves each video's cut list on the phone so a long editing session can resume.
 * Stores segment names and times plus the file's display name; nothing is uploaded.
 */
object CutListStore {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("audioverse_cutlists", Context.MODE_PRIVATE)
    private fun key(videoKey: String) = "v_" + videoKey.hashCode()

    fun save(c: Context, videoKey: String, template: CutTemplate, segments: List<Segment>) {
        val arr = JSONArray()
        segments.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("start", s.startMs).put("end", s.endMs))
        }
        val obj = JSONObject().put("template", template.name).put("segments", arr)
        prefs(c).edit().putString(key(videoKey), obj.toString()).apply()
    }

    fun load(c: Context, videoKey: String): Pair<CutTemplate, List<Segment>>? {
        val raw = prefs(c).getString(key(videoKey), null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val template = CutTemplate.entries.firstOrNull { it.name == obj.optString("template") } ?: CutTemplate.CUSTOM
            val arr = obj.getJSONArray("segments")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Segment(o.getLong("id"), o.getString("name"), o.getLong("start"), o.getLong("end"))
            }
            template to list
        } catch (e: Exception) { null }
    }
}

/** Time as H:MM:SS.mmm (ffmpeg-compatible). */
fun ffTime(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d.%03d", t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
}

/** Short display time: M:SS or H:MM:SS. */
fun shortTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Strips characters that are unsafe in file names (and in a shell script). */
fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim().take(80).ifBlank { "video" }

/**
 * Lossless cut script for the Mac (same approach as vidcut: -ss before -i, stream copy).
 * Cuts land on keyframes, so starts can shift by up to a few seconds.
 */
fun ffmpegScript(inputName: String, base: String, segments: List<Segment>): String = buildString {
    appendLine("#!/bin/bash")
    appendLine("# AudioVerse cut list for: " + inputName.replace(Regex("[\\r\\n]"), " "))
    appendLine("set -e")
    // Single-quoted so a file name containing $ ( ) or backticks can never run a command.
    appendLine("IN='" + inputName.replace("'", "'\\''") + "'")
    segments.forEach { s ->
        val ext = inputName.substringAfterLast('.', "mp4").filter { it.isLetterOrDigit() }.take(5).ifBlank { "mp4" }
        appendLine(
            "ffmpeg -hide_banner -ss ${ffTime(s.startMs)} -i \"\$IN\" -t ${ffTime(s.durationMs)} " +
                "-map 0 -c copy -avoid_negative_ts make_zero \"${safeName(base)} - ${safeName(s.name)}.$ext\""
        )
    }
}

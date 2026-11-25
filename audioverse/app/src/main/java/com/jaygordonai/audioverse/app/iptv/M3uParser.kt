package com.jaygordonai.audioverse.app.iptv

/**
 * One channel from an M3U / M3U Plus playlist.
 * [url] usually contains the account's username and password (Xtream-style
 * http://host/user/pass/123.ts), so it is never shown on screen, logged,
 * or saved to disk — only kept in memory while the list is open.
 */
data class IptvChannel(
    val index: Int,
    val name: String,
    val url: String,
    val group: String,
    val tvgId: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null
) {
    override fun toString(): String = "IptvChannel(#$index, $name, $group)" // never the URL
}

data class M3uResult(val channels: List<IptvChannel>, val truncated: Boolean, val skipped: Int)

/**
 * Line-by-line M3U parser (works on lists with tens of thousands of channels).
 * Understands #EXTINF attributes (tvg-id, tvg-name, group-title), #EXTGRP and
 * #EXTVLCOPT http-user-agent / http-referrer. Only http and https stream links are kept.
 */
object M3uParser {

    const val DEFAULT_LIMIT = 100_000
    const val NO_GROUP = "Other"

    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(lines: Sequence<String>, limit: Int = DEFAULT_LIMIT): M3uResult {
        val out = ArrayList<IptvChannel>()
        var skipped = 0
        var truncated = false
        var pendingName: String? = null
        var pendingGroup: String? = null
        var pendingTvgId: String? = null
        var extGrp: String? = null
        var ua: String? = null
        var ref: String? = null

        fun reset() { pendingName = null; pendingGroup = null; pendingTvgId = null; extGrp = null; ua = null; ref = null }

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    val comma = firstCommaOutsideQuotes(body)
                    val head = if (comma >= 0) body.substring(0, comma) else body
                    val title = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    val attrs = attr.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
                    pendingName = title.ifEmpty { attrs["tvg-name"].orEmpty() }
                    pendingGroup = attrs["group-title"]?.takeIf { it.isNotEmpty() }
                    pendingTvgId = attrs["tvg-id"]?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    extGrp = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }
                    when (key) {
                        "http-user-agent" -> ua = value
                        "http-referrer", "http-referer" -> ref = value
                    }
                }
                line.startsWith("#") -> Unit // #EXTM3U, #KODIPROP, comments
                else -> {
                    if (!isWebUrl(line)) { skipped++; reset(); continue }
                    if (out.size >= limit) { truncated = true; break }
                    val n = out.size + 1
                    out += IptvChannel(
                        index = out.size,
                        name = pendingName?.takeIf { it.isNotBlank() } ?: "Channel $n",
                        url = line,
                        group = pendingGroup ?: extGrp ?: NO_GROUP,
                        tvgId = pendingTvgId,
                        userAgent = ua,
                        referrer = ref
                    )
                    reset()
                }
            }
        }
        return M3uResult(out, truncated, skipped)
    }

    /** Groups in playlist order, with channel counts. */
    fun groups(channels: List<IptvChannel>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        channels.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        return counts.entries.map { it.key to it.value }
    }

    fun isWebUrl(s: String): Boolean = s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    fun isPlainHttp(s: String): Boolean = s.trim().startsWith("http://", ignoreCase = true)

    private fun firstCommaOutsideQuotes(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}

/** How pasted or shared text is imported (pure, unit tested). */
object IptvImport {
    class Plan(val isM3u: Boolean, val playlistLinks: List<Pair<String, String>>, val streams: List<Pair<String, String>>)

    fun looksLikePlaylist(text: String) = text.contains("#EXTINF", ignoreCase = true)

    /** Provider playlist links (vs. single stream links). A bare .m3u8 is usually one HLS channel. */
    fun isPlaylistLink(url: String): Boolean {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        return path.endsWith(".m3u") || u.contains("get.php") || u.contains("type=m3u") || u.contains("output=m3u")
    }

    /** M3U text stays one playlist; otherwise each line's link is a playlist link or a single stream ("Name, link" allowed). */
    fun plan(text: String): Plan {
        if (looksLikePlaylist(text)) return Plan(true, emptyList(), emptyList())
        val links = ArrayList<Pair<String, String>>()
        val streams = ArrayList<Pair<String, String>>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val at = maxOf(line.lastIndexOf("http://", ignoreCase = true), line.lastIndexOf("https://", ignoreCase = true))
            if (at < 0) return@forEach
            val url = line.substring(at).substringBefore(' ').trim()
            val label = line.substring(0, at).trim().trim(',', '|', ';', '-', ':', '\t', ' ').take(30)
            if (isPlaylistLink(url)) links += label to url else streams += label to url
        }
        return Plan(false, links, streams)
    }
}

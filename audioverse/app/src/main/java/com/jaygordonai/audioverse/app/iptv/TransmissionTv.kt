package com.jaygordonai.audioverse.app.iptv

/**
 * "Transmission TV": the app's own curated lineup, assembled on the device from public,
 * no-login playlists (iptv-org raw stream files + BuddyChewChew's Pluto / Samsung / Plex / Roku
 * lists). Same rules as docs/build_diaspora_m3u.py on the Mac Mini.
 *
 * Groups, in order: Philadelphia · News · Hip-Hop Videos · R&B / Soul Videos · Gospel ·
 * African American · Caribbean · Africa. A channel goes to the first group that claims it, and the
 * same channel from two sources appears once.
 *
 * Third-party sources: shown only after the listener turns on the free lists (Council ruling C5).
 */
object TransmissionTv {

    const val ID = -100L
    const val NAME = "Transmission TV"

    private const val IPTV_ORG = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/%s.m3u"
    private const val FAST = "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/%s.m3u"

    val fastLists = listOf("plutotv_us", "samsungtvplus_us", "plex_us", "roku_all").map { FAST.format(it) }
    val usLists = fastLists + listOf("us", "us_local").map { IPTV_ORG.format(it) }
    val africa = listOf("ng", "gh", "za", "ke", "sn", "et").map { IPTV_ORG.format(it) }
    val caribbean = listOf("jm", "tt", "bb", "ht", "bs", "gy").map { IPTV_ORG.format(it) }

    /** Every playlist the lineup needs (fetched in parallel by the caller). */
    val allSources: List<String> get() = (usLists + africa + caribbean).distinct()

    private sealed class Match {
        class Exact(names: List<String>) : Match() { val set = names.map { it.lowercase() }.toSet() }
        class Keywords(val regex: Regex) : Match()
        object Everything : Match()
    }

    private class Rule(val group: String, val match: Match, val sources: List<String>)

    private val rules = listOf(
        Rule("Philadelphia", Match.Exact(listOf("CBS News Philadelphia", "PhillyCAM")), usLists),
        Rule("News", Match.Exact(listOf("CNN Headlines", "NBC News NOW", "TYT Network", "ABC News Live", "CBS News 24/7",
            "LiveNOW from FOX", "FOX SOUL", "TheGrio", "Black Star Network")), usLists),
        Rule("Hip-Hop Videos", Match.Exact(listOf("Vevo Hip-Hop", "Vevo True School Hip-Hop", "Stingray Hip Hop",
            "XITE Hip-Hop Evolution", "XITE Rap Game", "Hip-Hop/R&B", "TRACE Urban", "VH1+ Hip Hop & Rap")), usLists),
        Rule("R&B / Soul Videos", Match.Exact(listOf("Vevo R&B", "XITE R&B Classic Jams", "XITE R&B Feels",
            "Stingray Soul Storm", "XITE 90s Throwback", "XITE 60s & 70s Groove")), usLists),
        Rule("Gospel", Match.Exact(listOf("XITE Gospel")), usLists),
        Rule("African American", Match.Keywords(Regex(
            "\\b(BET|Tyler Perry|ALLBLK|Black Family Sitcoms|Black Cinema|Bounce|Ebony|Aspire|TV One|CLEO|REVOLT|" +
                "Nolly|Afro|Brown Sugar|Kevin Hart|HBCU|Shades of Black|BlackPix|FilmRise Black|Urban TV)\\b",
            RegexOption.IGNORE_CASE)), fastLists),
        Rule("Caribbean", Match.Everything, caribbean),
        Rule("Africa", Match.Everything, africa)
    )

    val groupOrder: List<String> get() = rules.map { it.group }

    private val resolutionTag = Regex("\\s*\\((?:\\d+[pi]|SD|HD)\\)\\s*$", RegexOption.IGNORE_CASE)

    /** "CBS News Philadelphia (720p)" → "CBS News Philadelphia". */
    fun cleanName(name: String): String = name.replace(resolutionTag, "").trim()

    /**
     * Builds the lineup from already-parsed source playlists (pure; unit tested).
     * [bySource] maps each source URL to its channels; missing or failed sources are simply absent.
     */
    fun curate(bySource: Map<String, List<IptvChannel>>): List<IptvChannel> {
        val out = ArrayList<IptvChannel>()
        val seenUrls = HashSet<String>()
        val seenNames = HashSet<String>()
        for (rule in rules) for (src in rule.sources) for (ch in bySource[src].orEmpty()) {
            val name = cleanName(ch.name)
            val key = name.lowercase()
            if (ch.url in seenUrls || key in seenNames) continue
            val ok = when (val m = rule.match) {
                is Match.Exact -> key in m.set
                is Match.Keywords -> m.regex.containsMatchIn(name)
                Match.Everything -> true
            }
            if (!ok) continue
            seenUrls += ch.url; seenNames += key
            out += ch.copy(index = out.size, name = name, group = rule.group)
        }
        return out
    }
}

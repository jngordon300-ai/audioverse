package com.jaygordonai.audioverse.app.iptv

/**
 * Free, public M3U lists added to Video -> TV once, on first run.
 *
 * Rules: https only, no logins or tokens, nothing a listener has to pay for.
 * They are ordinary saved playlists after seeding, so the listener can rename or remove them,
 * and removed presets are never added back. Provider (paid) links are never preset.
 */
object IptvPresets {

    data class Preset(val id: Long, val name: String, val url: String)

    /** Fixed ids far below System.currentTimeMillis(), so they can't collide with user-added sources. */
    val all: List<Preset> = listOf(
        Preset(1L, "Pluto TV (US)", "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/plutotv_us.m3u"),
        Preset(2L, "Samsung TV Plus (US)", "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/samsungtvplus_us.m3u"),
        Preset(3L, "Free-TV", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"),
        Preset(4L, "IPTV-Org US", "https://iptv-org.github.io/iptv/countries/us.m3u"),
        Preset(5L, "IPTV-Org by category", "https://iptv-org.github.io/iptv/index.category.m3u")
    )

    fun isPreset(id: Long): Boolean = all.any { it.id == id }
}

package com.jaygordonai.audioverse.app.radio

import android.content.Context
import android.net.Uri
import com.jaygordonai.audioverse.app.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One entry from the Transmission Across the Diaspora catalog.
 * type: audio = plays in AudioVerse's own engine; tv = YouTube channel for the Watch tab;
 * link / spotify = opens the station's own app or site.
 */
data class Station(
    val name: String,
    val city: String,
    val tags: String,
    val cat: String,
    val type: String,
    val url: String,
    val ytChannel: String? = null
) {
    val playable: Boolean get() = type == "audio"
    val id: Long get() = stationId(url)

    /** Stations ride the same queue as local tracks; negative ids keep them apart from MediaStore ids. */
    fun toTrack(): Track = Track(
        id = id, uri = Uri.parse(url), title = name, artist = city.ifBlank { "Live radio" },
        album = tags, durationMs = 0, trackNumber = 0, live = true
    )
}

fun stationId(url: String): Long = -(url.hashCode().toLong() and 0x7fffffffL) - 1

/** A filter chip on the Radio tab. cats = catalog categories it shows (empty = special). */
data class RadioCategory(val key: String, val label: String, val cats: Set<String>)

object StationCatalog {

    const val FEATURED = "featured"
    const val ALL = "all"

    /** Listen-side categories, in Transmission's order. Watch categories live on the Video tab. */
    val categories: List<RadioCategory> = listOf(
        RadioCategory(FEATURED, "Featured", emptySet()),
        RadioCategory(ALL, "All radio", emptySet()),
        RadioCategory("philly", "Philly", setOf("philly")),
        RadioCategory("hiphop", "Hip-Hop", setOf("hiphop")),
        RadioCategory("rnb", "R&B", setOf("rnb")),
        RadioCategory("gospel", "Gospel", setOf("gospel")),
        RadioCategory("jazz", "Jazz", setOf("jazz")),
        RadioCategory("talk", "Talk", setOf("talk")),
        RadioCategory("hbcu", "HBCU", setOf("hbcu")),
        RadioCategory("college", "College", setOf("college")),
        RadioCategory("internet", "Internet", setOf("internet")),
        RadioCategory("afrobeats", "Afrobeats", setOf("diaspora-afrobeats")),
        RadioCategory("amapiano", "Amapiano", setOf("diaspora-amapiano")),
        RadioCategory("reggae", "Reggae", setOf("diaspora-reggae")),
        RadioCategory("uk", "UK", setOf("diaspora-uk")),
        RadioCategory("morning", "Morning shows", setOf("morning")),
        RadioCategory("pods", "Podcasts", setOf("pods")),
        RadioCategory("siriusxm", "SiriusXM", setOf("siriusxm"))
    )

    val watchCats: Set<String> = setOf("musictv", "wrestling", "comedy")

    data class Data(val stations: List<Station>, val featured: List<String>)

    @Volatile private var cache: Data? = null

    suspend fun load(context: Context): Data = cache ?: withContext(Dispatchers.IO) {
        val json = context.assets.open("stations.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.getJSONArray("stations")
        val list = ArrayList<Station>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.getString("url")
            // Only web links; anything else in the catalog is ignored.
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            list += Station(
                name = o.getString("name"),
                city = o.optString("city"),
                tags = o.optString("tags"),
                cat = o.getString("cat"),
                type = o.getString("type"),
                url = url,
                ytChannel = o.optString("ytChannel").takeIf { it.isNotBlank() }
            )
        }
        val f = root.getJSONArray("featured")
        Data(list, List(f.length()) { f.getString(it) }).also { cache = it }
    }

    /** Stations for a category and search text, Featured in its curated order. */
    fun filter(data: Data, categoryKey: String, query: String): List<Station> {
        val listen = data.stations.filter { it.cat !in watchCats || it.type == "audio" }
        val base = when (categoryKey) {
            FEATURED -> data.featured.mapNotNull { n -> data.stations.firstOrNull { it.name == n } }
            ALL -> listen.filter { it.playable }
            else -> {
                val cats = categories.firstOrNull { it.key == categoryKey }?.cats ?: emptySet()
                listen.filter { it.cat in cats }
            }
        }
        val q = query.trim().lowercase()
        val searched = if (q.isEmpty()) base else (if (categoryKey == FEATURED) data.stations else base).filter {
            it.name.lowercase().contains(q) || it.city.lowercase().contains(q) || it.tags.lowercase().contains(q)
        }
        return searched
    }
}

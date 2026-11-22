package com.jaygordonai.audioverse.app.library

import android.content.Context
import android.net.Uri
import com.jaygordonai.audioverse.app.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A song from the phone or a live station, saved by id so the list survives restarts. */
data class PlaylistEntry(
    val id: Long,
    val uri: String,
    val title: String,
    val subtitle: String,
    val live: Boolean
) {
    fun toTrack(): Track = Track(
        id = id, uri = Uri.parse(uri), title = title, artist = subtitle, album = "",
        durationMs = 0, trackNumber = 0, live = live
    )

    companion object {
        fun from(t: Track) = PlaylistEntry(t.id, t.uri.toString(), t.title, t.artist, t.live)
    }
}

data class Playlist(val id: Long, val name: String, val entries: List<PlaylistEntry>)

/** Pure list operations (unit tested). */
object PlaylistOps {
    const val FAVORITES_ID = 1L
    const val FAVORITES_NAME = "Favorites"
    const val MAX_NAME = 40

    fun ensureFavorites(lists: List<Playlist>): List<Playlist> =
        if (lists.any { it.id == FAVORITES_ID }) lists
        else listOf(Playlist(FAVORITES_ID, FAVORITES_NAME, emptyList())) + lists

    fun create(lists: List<Playlist>, name: String, newId: Long): List<Playlist> {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return lists
        return lists + Playlist(newId, clean, emptyList())
    }

    /** Adds an entry unless that song or station is already in the list. */
    fun add(lists: List<Playlist>, playlistId: Long, entry: PlaylistEntry): List<Playlist> = lists.map { p ->
        if (p.id != playlistId || p.entries.any { it.id == entry.id }) p else p.copy(entries = p.entries + entry)
    }

    fun toggle(lists: List<Playlist>, playlistId: Long, entry: PlaylistEntry): List<Playlist> = lists.map { p ->
        if (p.id != playlistId) p
        else if (p.entries.any { it.id == entry.id }) p.copy(entries = p.entries.filterNot { it.id == entry.id })
        else p.copy(entries = p.entries + entry)
    }

    fun removeAt(lists: List<Playlist>, playlistId: Long, index: Int): List<Playlist> = lists.map { p ->
        if (p.id != playlistId || index !in p.entries.indices) p
        else p.copy(entries = p.entries.toMutableList().also { it.removeAt(index) })
    }

    fun move(lists: List<Playlist>, playlistId: Long, from: Int, to: Int): List<Playlist> = lists.map { p ->
        if (p.id != playlistId || from !in p.entries.indices || to !in p.entries.indices) p
        else p.copy(entries = p.entries.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    /** Favorites can be emptied but not deleted. */
    fun delete(lists: List<Playlist>, playlistId: Long): List<Playlist> =
        if (playlistId == FAVORITES_ID) lists else lists.filterNot { it.id == playlistId }

    fun contains(lists: List<Playlist>, playlistId: Long, entryId: Long): Boolean =
        lists.firstOrNull { it.id == playlistId }?.entries?.any { it.id == entryId } == true
}

/** Saves playlists as JSON in app-private storage (never backed up, see data_extraction_rules). */
object PlaylistStore {
    private fun file(context: Context) = File(context.filesDir, "playlists.json")

    suspend fun load(context: Context): List<Playlist> = withContext(Dispatchers.IO) {
        val f = file(context)
        val lists = if (!f.exists()) emptyList() else try {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val es = o.getJSONArray("entries")
                Playlist(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    entries = List(es.length()) { j ->
                        val e = es.getJSONObject(j)
                        PlaylistEntry(e.getLong("id"), e.getString("uri"), e.getString("title"), e.optString("subtitle"), e.optBoolean("live"))
                    }
                )
            }
        } catch (e: Exception) { emptyList() }
        PlaylistOps.ensureFavorites(lists)
    }

    suspend fun save(context: Context, lists: List<Playlist>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        lists.forEach { p ->
            val es = JSONArray()
            p.entries.forEach { e ->
                es.put(JSONObject().put("id", e.id).put("uri", e.uri).put("title", e.title).put("subtitle", e.subtitle).put("live", e.live))
            }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("entries", es))
        }
        val f = file(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
    }
}

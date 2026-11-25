package com.jaygordonai.audioverse.app.video

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A video in a playlist: a file from a picked folder or one opened from the phone. */
data class VideoEntry(val uri: String, val name: String)

data class VideoPlaylist(val id: Long, val name: String, val items: List<VideoEntry>)

/** Pure list operations (unit tested). */
object VideoPlaylistOps {
    const val MAX_NAME = 40

    fun create(lists: List<VideoPlaylist>, name: String, id: Long, first: VideoEntry? = null): List<VideoPlaylist> {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return lists
        return lists + VideoPlaylist(id, clean, listOfNotNull(first))
    }

    fun add(lists: List<VideoPlaylist>, id: Long, e: VideoEntry): List<VideoPlaylist> = lists.map { p ->
        if (p.id != id || p.items.any { it.uri == e.uri }) p else p.copy(items = p.items + e)
    }

    fun removeAt(lists: List<VideoPlaylist>, id: Long, index: Int): List<VideoPlaylist> = lists.map { p ->
        if (p.id != id || index !in p.items.indices) p else p.copy(items = p.items.toMutableList().also { it.removeAt(index) })
    }

    fun move(lists: List<VideoPlaylist>, id: Long, from: Int, to: Int): List<VideoPlaylist> = lists.map { p ->
        if (p.id != id || from !in p.items.indices || to !in p.items.indices) p
        else p.copy(items = p.items.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun delete(lists: List<VideoPlaylist>, id: Long) = lists.filterNot { it.id == id }
}

/** Saved as JSON in app-private storage (file names and content URIs only; not backed up). */
object VideoPlaylistStore {
    private fun file(c: Context) = File(c.filesDir, "video_playlists.json")

    suspend fun load(c: Context): List<VideoPlaylist> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray(file(c).readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i); val its = o.getJSONArray("items")
                VideoPlaylist(o.getLong("id"), o.getString("name"), List(its.length()) { j ->
                    its.getJSONObject(j).let { VideoEntry(it.getString("uri"), it.getString("name")) }
                })
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun save(c: Context, lists: List<VideoPlaylist>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        lists.forEach { p ->
            val its = JSONArray(); p.items.forEach { its.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("items", its))
        }
        try { file(c).writeText(arr.toString()) } catch (_: Exception) { }
    }
}

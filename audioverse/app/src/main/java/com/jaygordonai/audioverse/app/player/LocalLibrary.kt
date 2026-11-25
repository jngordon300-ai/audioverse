package com.jaygordonai.audioverse.app.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int,
    /** true for an internet radio station (no duration, no album art). */
    val live: Boolean = false
)

/**
 * Reads the phone's music library through MediaStore (audio files only).
 * Needs READ_MEDIA_AUDIO (Android 13+), which grants audio files only: no photos,
 * videos, documents or downloads.
 */
object LocalLibrary {

    suspend fun load(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val order = "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, ${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TRACK}"
        val out = ArrayList<Track>()
        context.contentResolver.query(collection, projection, selection, null, order)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    title = c.getString(titleCol) ?: "Unknown title",
                    artist = c.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                    album = c.getString(albumCol) ?: "",
                    durationMs = c.getLong(durCol),
                    trackNumber = c.getInt(trackCol) % 1000
                )
            }
        }
        out
    }
}

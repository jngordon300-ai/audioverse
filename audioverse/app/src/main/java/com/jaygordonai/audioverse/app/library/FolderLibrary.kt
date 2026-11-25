package com.jaygordonai.audioverse.app.library

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.jaygordonai.audioverse.app.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** A folder the listener picked (phone, SD card or a USB drive like a Samsung T5). */
data class FolderInfo(
    val uri: String,
    val name: String,
    val connected: Boolean = true,
    val tracks: Int = 0,
    val videos: Int = 0
)

/** A video found in a picked folder, for Video → Watch. */
data class FolderVideo(val uri: Uri, val name: String, val folder: String)

data class FolderScan(val tracks: List<Track>, val videos: List<FolderVideo>, val folders: List<FolderInfo>)

/**
 * Reads music and videos from folders picked with the system folder picker
 * (Storage Access Framework). Only those folders are visible to the app: no
 * storage-wide permission. Tags (title, artist, album, length) are read once and
 * cached in app-private storage, keyed by file and modified time.
 */
object FolderLibrary {

    /** Folder track ids live far above MediaStore ids and away from station ids (negative). */
    private const val ID_BASE = 1L shl 40
    private const val MAX_FILES = 50_000
    private const val MAX_DEPTH = 12

    val audioExt = setOf("mp3", "flac", "opus", "ogg", "oga", "m4a", "aac", "wav", "aif", "aiff", "alac", "wma", "mka", "dsf")
    val videoExt = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "avi")

    fun trackId(docUri: String): Long = ID_BASE + (docUri.hashCode().toLong() and 0xffffffffL)

    // ---------------- Saved folders ----------------

    private fun prefs(c: Context) = c.getSharedPreferences("audioverse_folders", Context.MODE_PRIVATE)

    fun savedFolders(c: Context): List<String> = prefs(c).getStringSet("trees", emptySet())!!.sorted()

    fun addFolder(c: Context, tree: Uri) {
        try { c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        prefs(c).edit().putStringSet("trees", savedFolders(c).toSet() + tree.toString()).apply()
    }

    fun removeFolder(c: Context, tree: String) {
        try { c.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        prefs(c).edit().putStringSet("trees", savedFolders(c).toSet() - tree).apply()
    }

    /** "USB drive · Music" style label from the tree's document id (e.g. "1A2B-3C4D:Music"). */
    fun label(tree: Uri): String {
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return "Folder" }
        val volume = id.substringBefore(':', "")
        val path = id.substringAfter(':', id).ifEmpty { "(whole drive)" }
        val where = when {
            volume.equals("primary", ignoreCase = true) -> "Phone"
            volume.isNotEmpty() && tree.authority == "com.android.externalstorage.documents" -> "USB / SD"
            else -> "Folder"
        }
        return "$where · $path"
    }

    // ---------------- Scan ----------------

    suspend fun scan(c: Context, onProgress: (String) -> Unit): FolderScan = withContext(Dispatchers.IO) {
        val cache = loadCache(c)
        val newCache = HashMap<String, JSONObject>()
        val tracks = ArrayList<Track>()
        val videos = ArrayList<FolderVideo>()
        val infos = ArrayList<FolderInfo>()
        for (treeStr in savedFolders(c)) {
            val tree = Uri.parse(treeStr)
            val name = label(tree)
            val files = try { walk(c, tree) } catch (e: Exception) { null }
            if (files == null) { infos += FolderInfo(treeStr, name, connected = false); continue }
            var t = 0; var v = 0
            files.forEachIndexed { i, f ->
                ensureActive()
                if (i % 25 == 0) onProgress("Scanning $name · ${i + 1} of ${files.size}")
                if (f.video) { videos += FolderVideo(f.uri, f.name, name); v++; return@forEachIndexed }
                val key = f.uri.toString()
                val meta = cache[key]?.takeIf { it.optLong("mod") == f.modified } ?: readTags(c, f)
                newCache[key] = meta
                tracks += Track(
                    id = trackId(key), uri = f.uri,
                    title = meta.optString("title").ifBlank { f.name.substringBeforeLast('.') },
                    artist = meta.optString("artist").ifBlank { "Unknown artist" },
                    album = meta.optString("album").ifBlank { name.substringAfter("· ") },
                    durationMs = meta.optLong("dur"),
                    trackNumber = meta.optInt("no")
                )
                t++
            }
            infos += FolderInfo(treeStr, name, connected = true, tracks = t, videos = v)
        }
        // Keep tags for folders that are unplugged right now, so replugging the drive is instant.
        if (infos.any { !it.connected }) cache.forEach { (k, o) -> newCache.putIfAbsent(k, o) }
        saveCache(c, newCache)
        FolderScan(tracks, videos.sortedBy { it.name.lowercase() }, infos)
    }

    private class FileRef(val uri: Uri, val name: String, val modified: Long, val video: Boolean)

    private fun walk(c: Context, tree: Uri): List<FileRef> {
        val out = ArrayList<FileRef>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        // Fails (returns null / throws) when the drive is unplugged.
        val probe = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        c.contentResolver.query(probe, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { if (!it.moveToFirst()) error("gone") }
            ?: error("gone")
        val stack = ArrayDeque<Pair<String, Int>>().apply { add(rootId to 0) }
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        while (stack.isNotEmpty() && out.size < MAX_FILES) {
            val (docId, depth) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            c.contentResolver.query(children, cols, null, null, null)?.use { cur ->
                while (cur.moveToNext() && out.size < MAX_FILES) {
                    val id = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = cur.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) stack.add(id to depth + 1)
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isAudio = mime.startsWith("audio/") || ext in audioExt
                    val isVideo = !isAudio && (mime.startsWith("video/") || ext in videoExt)
                    if (!isAudio && !isVideo) continue
                    out += FileRef(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, cur.getLong(3), isVideo)
                }
            }
        }
        return out
    }

    private fun readTags(c: Context, f: FileRef): JSONObject {
        val o = JSONObject().put("mod", f.modified)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, f.uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { o.put("title", it) }
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.let { o.put("artist", it) }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { o.put("album", it) }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { o.put("dur", it) }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull()?.let { o.put("no", it) }
        } catch (_: Exception) {
            // Unreadable tags: fall back to the file name.
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
        return o
    }

    // ---------------- Tag cache (file names and tags only; app-private, not backed up) ----------------

    private fun cacheFile(c: Context) = File(c.filesDir, "folder_tags.json")

    private fun loadCache(c: Context): Map<String, JSONObject> = try {
        val root = JSONObject(cacheFile(c).readText())
        root.keys().asSequence().associateWith { root.getJSONObject(it) }
    } catch (e: Exception) { emptyMap() }

    private fun saveCache(c: Context, m: Map<String, JSONObject>) {
        try {
            val root = JSONObject()
            m.forEach { (k, v) -> root.put(k, v) }
            cacheFile(c).writeText(root.toString())
        } catch (_: Exception) { }
    }

    /** Drops folder copies of songs the phone's MediaStore already lists (same title, artist, length). */
    fun mergeWith(mediaStore: List<Track>, folder: List<Track>): List<Track> {
        val seen = mediaStore.map { key(it) }.toHashSet()
        return mediaStore + folder.filter { key(it) !in seen }
    }

    private fun key(t: Track) = "${t.title.lowercase()}|${t.artist.lowercase()}|${t.durationMs / 2000}"
}

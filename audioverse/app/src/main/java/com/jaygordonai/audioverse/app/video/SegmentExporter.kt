package com.jaygordonai.audioverse.app.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cuts one segment out of a video into its own MP4 using Media3 Transformer.
 *
 * Trim optimization: when the source is a typical MP4 (H.264/H.265), only the few
 * frames around the cut start are re-encoded and the rest is copied, so a quarter
 * of a game exports fast and with no quality loss. Other sources are fully
 * re-encoded by the phone's hardware encoder.
 *
 * Output: rendered to app-private storage, then copied into
 * Movies/AudioVerse/<video name>/ through MediaStore (no storage permission), then
 * the temp file is deleted. The original video is never modified.
 *
 * Must be called on the main thread (Transformer needs a Looper).
 */
@OptIn(UnstableApi::class)
class SegmentExporter(private val context: Context) {

    data class Result(val uri: Uri, val displayPath: String, val bytes: Long)

    suspend fun export(
        source: Uri,
        baseName: String,
        segment: Segment,
        onProgress: (Int) -> Unit
    ): Result {
        val tempDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
        val temp = withContext(Dispatchers.IO) { File.createTempFile("cut_", ".mp4", tempDir) }
        try {
            render(source, segment, temp, onProgress)
            return withContext(Dispatchers.IO) { publish(temp, baseName, segment) }
        } finally {
            withContext(Dispatchers.IO) { temp.delete() }
        }
    }

    private suspend fun render(source: Uri, seg: Segment, out: File, onProgress: (Int) -> Unit) = coroutineScope {
        val item = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(seg.startMs)
                    .setEndPositionMs(seg.endMs)
                    .build()
            )
            .build()

        var transformerRef: Transformer? = null
        val progressJob = launch {
            val holder = ProgressHolder()
            while (true) {
                val t = transformerRef
                if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                delay(300)
            }
        }
        try {
            suspendCancellableCoroutine<ExportResult> { cont ->
                val transformer = Transformer.Builder(context)
                    .experimentalSetTrimOptimizationEnabled(true)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(exportResult)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformerRef = transformer
                transformer.start(item, out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        } finally {
            progressJob.cancel()
        }
    }

    private fun publish(temp: File, baseName: String, seg: Segment): Result {
        val folder = "${Environment.DIRECTORY_MOVIES}/AudioVerse/${safeName(baseName)}"
        val name = "${safeName(baseName)} - ${safeName(seg.name)}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, folder)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Could not create output file")
        try {
            resolver.openOutputStream(uri)?.use { os -> temp.inputStream().use { it.copyTo(os, 1 shl 20) } }
                ?: throw IllegalStateException("Could not open output file")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null) // never leave a half-written video behind
            throw t
        }
        return Result(uri, "$folder/$name", temp.length())
    }
}

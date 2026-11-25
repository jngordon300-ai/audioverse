package com.jaygordonai.audioverse.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.jaygordonai.audioverse.app.player.PlayerViewModel
import com.jaygordonai.audioverse.app.theme.AudioVerseTheme
import com.jaygordonai.audioverse.app.ui.AudioVerseApp
import com.jaygordonai.audioverse.app.video.VideoEditorViewModel
import com.jaygordonai.audioverse.app.gate.GateViewModel

class MainActivity : ComponentActivity() {

    private val playerVm: PlayerViewModel by viewModels()
    private val gateVm: GateViewModel by viewModels()
    private val videoVm: VideoEditorViewModel by viewModels()
    private val iptvVm: com.jaygordonai.audioverse.app.iptv.IptvViewModel by viewModels()
    private val djVm: com.jaygordonai.audioverse.app.dj.DjViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AudioVerseTheme {
                AudioVerseApp(playerVm, gateVm = { gateVm }, videoVm = { videoVm }, iptvVm = { iptvVm }, djVm = { djVm })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Files opened with AudioVerse from other apps (file manager, Downloads, a USB drive),
     * and text shared to it. Music plays right away; videos open in Video → Watch;
     * M3U files and shared links are only imported after the listener confirms.
     */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        try {
            when (i.action) {
                Intent.ACTION_VIEW -> {
                    val uri = i.data ?: return
                    if (uri.scheme != "content" && uri.scheme != "file") return
                    val mime = (i.type ?: contentResolver.getType(uri) ?: "").lowercase()
                    val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "File"
                    val ext = name.substringAfterLast('.', "").lowercase()
                    when {
                        mime.contains("mpegurl") || ext == "m3u" || ext == "m3u8" -> {
                            iptvVm.offerImportFrom(uri, name); playerVm.requestVideoMode("TV")
                        }
                        mime.startsWith("video/") -> {
                            videoVm.open(uri); videoVm.player.play(); playerVm.requestVideoMode("WATCH")
                        }
                        else -> playerVm.playExternal(uri, name)
                    }
                }
                Intent.ACTION_SEND -> {
                    val text = i.getStringExtra(Intent.EXTRA_TEXT)
                    @Suppress("DEPRECATION") val stream = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    when {
                        !text.isNullOrBlank() -> iptvVm.offerImport(text, i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared list")
                        stream != null && stream.scheme == "content" -> iptvVm.offerImportFrom(stream, displayName(stream))
                        else -> return
                    }
                    playerVm.requestVideoMode("TV")
                }
            }
        } catch (_: Exception) {
            // A bad or revoked link from another app must never crash AudioVerse.
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    override fun onStart() {
        super.onStart()
        // Connecting a controller starts PlaybackService, which keeps audio playing
        // with the screen locked and shows the media notification.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}

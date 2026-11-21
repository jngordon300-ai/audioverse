package com.jaygordonai.audioverse.app

import android.Manifest
import android.content.ComponentName
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
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AudioVerseTheme {
                AudioVerseApp(playerVm, gateVm = { gateVm }, videoVm = { videoVm })
            }
        }
    }

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

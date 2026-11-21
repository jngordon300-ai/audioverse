package com.jaygordonai.audioverse.app

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps playback alive with the screen locked and shows the media notification.
 * Only this app and trusted system controllers (lock screen, Bluetooth, Android
 * Auto) may connect; other apps are rejected.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerHolder.get(this).backend.player
        session = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val allowed = controller.packageName == packageName || controller.isTrusted
                    return if (allowed) MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
                    else MediaSession.ConnectionResult.reject()
                }
            })
            .build()
        PlayerHolder.onPlayerChanged = { newPlayer -> session?.player = newPlayer }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        PlayerHolder.onPlayerChanged = null
        session?.release()
        session = null
        PlayerHolder.release()
        super.onDestroy()
    }
}

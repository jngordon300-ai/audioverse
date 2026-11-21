package com.jaygordonai.audioverse.playback

import android.net.Uri

/**
 * Engine-agnostic playback contract (Chair ruling: one PlaybackBackend interface).
 * Library, queue, ReplayGain, diagnostics and EQ presets talk only to this.
 *
 * Implementations:
 *  - Media3Backend  (primary, both Audiophile and DJ modes)
 *  - LibVlcBackend  (fallback for SMB shares and unusual formats; Phase 3)
 */
interface PlaybackBackend {
    val name: String
    fun setQueue(uris: List<Uri>, startIndex: Int = 0, startPositionMs: Long = 0L)
    fun play()
    fun pause()
    fun seekTo(index: Int, positionMs: Long)
    val currentIndex: Int
    val positionMs: Long
    val durationMs: Long
    val isPlaying: Boolean
    fun release()
}

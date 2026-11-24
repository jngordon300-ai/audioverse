package com.jaygordonai.audioverse.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.jaygordonai.audioverse.network.AuthConfig
import com.jaygordonai.audioverse.network.HttpEventRecorder
import com.jaygordonai.audioverse.network.SecureHeaderInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Primary playback backend built on AndroidX Media3.
 *
 * - Audio-only renderers we control explicitly (MediaCodec audio + metadata for ICY).
 * - Default: ProcessingAudioSink runs our processors on the decoder's PCM, then a
 *   float-mode DefaultAudioSink plays it (hi-res safe, no 16-bit truncation).
 * - useProcessingSink = false keeps Media3's stock sink (processors inside
 *   DefaultAudioSink) for comparison in the gate.
 * - All HTTP goes through OkHttp with SecureHeaderInterceptor + HttpEventRecorder
 *   as NETWORK interceptors (per-hop host checks, no tokens in URLs).
 * - content:// and file:// URIs go through DefaultDataSource.
 */
@OptIn(UnstableApi::class)
class Media3Backend(
    context: Context,
    useProcessingSink: Boolean,
    processors: Array<AudioProcessor>,
    authProvider: () -> AuthConfig?,
    /** false for DJ decks: two decks must play at once, so neither may take audio focus from the other. */
    handleAudioFocus: Boolean = true,
    val httpRecorder: HttpEventRecorder = HttpEventRecorder(authProvider)
) : PlaybackBackend {

    override val name = "Media3"
    private val appContext = context.applicationContext

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(SecureHeaderInterceptor(authProvider))
        .addNetworkInterceptor(httpRecorder)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val audioSink: AudioSink =
        if (useProcessingSink) {
            ProcessingAudioSink(
                DefaultAudioSink.Builder(appContext).setEnableFloatOutput(true).build(),
                processors.toList()
            )
        } else {
            DefaultAudioSink.Builder(appContext)
                .setEnableFloatOutput(true)
                .setAudioProcessors(processors)
                .build()
        }

    private val renderersFactory = RenderersFactory { handler, _, audioListener, _, metadataOutput ->
        arrayOf<Renderer>(
            MediaCodecAudioRenderer(appContext, MediaCodecSelector.DEFAULT, handler, audioListener, audioSink),
            MetadataRenderer(metadataOutput, handler.looper)
        )
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    appContext,
                    OkHttpDataSource.Factory(okHttp).setUserAgent("AudioVerse/0.1")
                )
            )
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            handleAudioFocus
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    override fun setQueue(uris: List<Uri>, startIndex: Int, startPositionMs: Long) {
        player.setMediaItems(uris.map { MediaItem.fromUri(it) }, startIndex, startPositionMs)
        player.prepare()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(index: Int, positionMs: Long) = player.seekTo(index, positionMs)
    override val currentIndex: Int get() = player.currentMediaItemIndex
    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long get() = player.duration
    override val isPlaying: Boolean get() = player.isPlaying

    override fun release() {
        player.release()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }
}

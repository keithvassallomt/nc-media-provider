package com.keithvassallo.ncmediaprovider.provider

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback
import android.provider.CloudMediaProviderContract
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.keithvassallo.ncmediaprovider.data.LibraryRepository

/**
 * Plays videos in the picker's preview (#36). The picker hands over a surface per previewed
 * item and asks for play, pause and seek; this answers with playback states. One ExoPlayer on its
 * own thread plays whichever surface was created or played last. A video that is also on the phone
 * plays from there; otherwise it streams from the server with the app's sign-in. The network is
 * open meanwhile: the picker is on screen and bound to this app, so Android counts it as top.
 *
 * The preview is silent on Android 17: its audio hardening mutes this app's playback because the
 * app isn't on screen itself, and a media-playback foreground service started from the picker's
 * call didn't help (Phase 5 phone test: "AudioHardening background playback muted"). The picker
 * asks for audio focus itself when unmuted, so the player never does: asking was refused, and
 * ExoPlayer paused the video until it was muted again.
 */
@SuppressLint("UnsafeOptInUsageError") // OkHttpDataSource is stable in practice; Media3 marks it unstable.
internal class VideoPreviewController(
    private val context: Context,
    config: Bundle,
    private val callback: CloudMediaSurfaceStateChangedCallback,
    private val repository: LibraryRepository,
) : CloudMediaProvider.CloudMediaSurfaceController() {
    private val thread = HandlerThread("nc-video-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val surfaces = HashMap<Int, Pair<Surface, String>>()
    private var player: ExoPlayer? = null
    private var current: Int? = null
    private var looping = config.getBoolean(CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED, false)
    private var muted = config.getBoolean(CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED, false)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val surfaceId = current ?: return
            when (state) {
                Player.STATE_BUFFERING -> report(surfaceId, CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_BUFFERING)
                Player.STATE_READY -> report(
                    surfaceId,
                    if (player?.playWhenReady == true) {
                        CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_STARTED
                    } else {
                        CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_READY
                    },
                )
                Player.STATE_ENDED -> report(surfaceId, CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_COMPLETED)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val surfaceId = current ?: return
            if (player?.playbackState != Player.STATE_READY) return
            report(
                surfaceId,
                if (playWhenReady) {
                    CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_STARTED
                } else {
                    CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_PAUSED
                },
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val surfaceId = current ?: return
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val info = Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, Point(videoSize.width, videoSize.height)) }
            report(surfaceId, CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_MEDIA_SIZE_CHANGED, info)
        }

        override fun onPlayerError(error: PlaybackException) {
            val surfaceId = current ?: return
            Log.w(TAG, "Preview of surface $surfaceId failed: ${error.errorCodeName}: ${error.message.orEmpty()}")
            report(surfaceId, CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE)
        }
    }

    override fun onPlayerCreate() = post {
        if (player != null) return@post
        val account = repository.accountForPlayback()
        val remote = account?.let { OkHttpDataSource.Factory(repository.callFactory(it)) }
        val dataSources = if (remote != null) DefaultDataSource.Factory(context, remote) else DefaultDataSource.Factory(context)
        player = ExoPlayer.Builder(context)
            .setLooper(thread.looper)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .build()
            .also {
                it.addListener(listener)
                applyConfig(it)
            }
    }

    override fun onPlayerRelease() = post {
        player?.release()
        player = null
        current = null
    }

    override fun onSurfaceCreated(surfaceId: Int, surface: Surface, mediaId: String) = post {
        surfaces[surfaceId] = surface to mediaId
        load(surfaceId)
    }

    override fun onSurfaceChanged(surfaceId: Int, format: Int, width: Int, height: Int) = Unit

    override fun onSurfaceDestroyed(surfaceId: Int) = post {
        surfaces.remove(surfaceId)
        if (current == surfaceId) {
            player?.stop()
            player?.clearVideoSurface()
            current = null
        }
    }

    override fun onMediaPlay(surfaceId: Int) = post {
        if (current != surfaceId) load(surfaceId)
        player?.playWhenReady = true
    }

    override fun onMediaPause(surfaceId: Int) = post {
        if (current == surfaceId) player?.playWhenReady = false
    }

    override fun onMediaSeekTo(surfaceId: Int, timestampMillis: Long) = post {
        if (current == surfaceId) player?.seekTo(timestampMillis)
    }

    override fun onConfigChange(config: Bundle) = post {
        looping = config.getBoolean(CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED, looping)
        muted = config.getBoolean(CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED, muted)
        player?.let(::applyConfig)
    }

    override fun onDestroy() = post {
        player?.release()
        player = null
        surfaces.clear()
        thread.quitSafely()
    }

    /** Points the player at [surfaceId]'s surface and media, ready to play when asked. */
    private fun load(surfaceId: Int) {
        val (surface, mediaId) = surfaces[surfaceId] ?: return
        val player = player ?: return
        val uri = repository.playbackUri(mediaId)
        if (uri == null) {
            report(surfaceId, CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_PERMANENT_FAILURE)
            return
        }
        current = surfaceId
        player.setVideoSurface(surface)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.playWhenReady = false
        player.prepare()
        Log.d(TAG, "Preview of $mediaId on surface $surfaceId: ${uri.scheme}")
    }

    private fun applyConfig(player: ExoPlayer) {
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (muted) 0f else 1f
        player.setAudioAttributes(MOVIE_AUDIO, false)
    }

    private fun report(surfaceId: Int, state: Int, info: Bundle? = null) {
        runCatching { callback.setPlaybackState(surfaceId, state, info) }
            .onFailure { Log.d(TAG, "Couldn't report playback state $state: ${it.javaClass.simpleName}") }
    }

    private fun post(block: () -> Unit) {
        handler.post {
            runCatching(block).onFailure { Log.w(TAG, "Preview call failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
        }
    }

    private companion object {
        const val TAG = "VideoPreview"
        val MOVIE_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }
}

package com.obsidian.dynamicisland

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val updateTimeMs: Long,
    val speed: Float,
    val durationMs: Long,
    val canPrev: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
    val controller: MediaController,
) {
    /** Current position, extrapolated from the last playback-state update. */
    fun positionAt(nowElapsedMs: Long): Long {
        val p = if (isPlaying) positionMs + ((nowElapsedMs - updateTimeMs) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }
}

/**
 * Finds media sessions from media notifications that the accessibility service sees, then
 * follows each session with a MediaController. No notification-listener permission is needed.
 */
class MediaRepository(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()

    /** Called whenever a notification carrying a media session token is posted or updated. */
    fun onSessionToken(token: MediaSession.Token) {
        if (tracked.containsKey(token)) {
            publish()
            return
        }
        val controller = runCatching { MediaController(context, token) }.getOrNull() ?: return
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
            override fun onSessionDestroyed() = remove(token)
        }
        tracked[token] = Tracked(controller, callback)
        controller.registerCallback(callback, handler)
        publish()
    }

    fun stop() {
        tracked.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
        tracked.clear()
        _state.value = null
    }

    private fun remove(token: MediaSession.Token) {
        tracked.remove(token)?.let { runCatching { it.controller.unregisterCallback(it.callback) } }
        publish()
    }

    private fun publish() {
        val candidates = tracked.values.mapNotNull { it.controller.toNowPlaying() }
        _state.value = candidates.lastOrNull { it.isPlaying } ?: candidates.lastOrNull()
    }

    private fun MediaController.toNowPlaying(): NowPlaying? {
        val ps = playbackState ?: return null
        val s = ps.state
        if (s != PlaybackState.STATE_PLAYING && s != PlaybackState.STATE_PAUSED && s != PlaybackState.STATE_BUFFERING) {
            return null
        }
        val md = metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return NowPlaying(
            packageName = packageName,
            title = title,
            artist = artist,
            art = art,
            isPlaying = s == PlaybackState.STATE_PLAYING,
            positionMs = ps.position.coerceAtLeast(0L),
            updateTimeMs = ps.lastPositionUpdateTime,
            speed = ps.playbackSpeed,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            canPrev = ps.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = ps.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSeek = ps.actions and PlaybackState.ACTION_SEEK_TO != 0L,
            controller = this,
        )
    }
}

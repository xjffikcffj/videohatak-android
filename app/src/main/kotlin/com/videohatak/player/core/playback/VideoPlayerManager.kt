package com.videohatak.player.core.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.vault.datasource.EncryptedVaultDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VideoPlayerManager internal constructor(
    private val context: Context?,
    customExoPlayer: ExoPlayer? = null
) {

    companion object {
        private const val TAG = "VideoPlayerManager"

        @Volatile
        private var instance: VideoPlayerManager? = null

        fun getInstance(context: Context): VideoPlayerManager {
            return instance ?: synchronized(this) {
                instance ?: VideoPlayerManager(context.applicationContext).also { instance = it }
            }
        }

        fun createForTesting(exoPlayer: ExoPlayer, context: Context? = null): VideoPlayerManager {
            return VideoPlayerManager(context, exoPlayer)
        }
    }

    var onMediaTransition: ((String) -> Unit)? = null
    var onPeriodicPositionUpdate: ((uri: String, positionMs: Long, durationMs: Long) -> Unit)? = null
    private var lastReportedPositionMs: Long = 0L
    val restorationCoordinator = PlaybackRestorationCoordinator()
    val knownDurations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun getCurrentPlaybackGeneration(): Long = restorationCoordinator.getCurrentPlaybackGeneration()

    fun saveCurrentExoPlayerPositionDirectly(): Boolean {
        val currentUri = _uiState.value.mediaUri?.toString()
        if (!currentUri.isNullOrBlank()) {
            val actualPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val actualDur = exoPlayer.duration.coerceAtLeast(0L).takeIf { it > 0L }
                ?: (knownDurations[currentUri] ?: 0L)
            if (actualDur > 0L) {
                knownDurations[currentUri] = actualDur
            }
            lastReportedPositionMs = actualPos
            onPeriodicPositionUpdate?.invoke(currentUri, actualPos, actualDur)
            return true
        }
        return false
    }

    /**
     * Coordinated position restoration:
     * - Drops stale restorations if the user already navigated to a different video or newer playback generation.
     * - Drops restoration if the user already performed a manual seek or restoration was already applied.
     * - If duration is already known (STATE_READY), validates thresholds and seeks immediately.
     * - If duration is still pending (0L), queues restoration until STATE_READY / onTimelineChanged to prevent 0L threshold race.
     */
    fun applyRestoredPosition(targetUri: String, targetGeneration: Long, restoredPositionMs: Long) {
        val currentUri = _uiState.value.mediaUri?.toString()
        val durationMs = exoPlayer.duration.coerceAtLeast(0L)
        val isReady = exoPlayer.playbackState == Player.STATE_READY

        val decision = restorationCoordinator.evaluateRestoration(
            targetUri = targetUri,
            targetGeneration = targetGeneration,
            restoredPositionMs = restoredPositionMs,
            currentUri = currentUri,
            durationMs = durationMs,
            isReady = isReady
        )

        when (decision) {
            is PlaybackRestorationCoordinator.RestorationDecision.Apply -> {
                val effective = decision.effectivePositionMs
                if (effective > 0L) {
                    exoPlayer.seekTo(effective)
                    _uiState.update { it.copy(currentPositionMs = effective) }
                } else {
                    exoPlayer.seekTo(0L)
                    _uiState.update { it.copy(currentPositionMs = 0L) }
                }
            }
            is PlaybackRestorationCoordinator.RestorationDecision.Queued -> {
                Log.d(TAG, "Restoration queued for $targetUri (waiting for duration / STATE_READY)")
            }
            is PlaybackRestorationCoordinator.RestorationDecision.Rejected -> {
                Log.d(TAG, decision.reason)
            }
        }
    }

    @Volatile
    private var _exoPlayer: ExoPlayer? = customExoPlayer
    val exoPlayer: ExoPlayer
        get() = _exoPlayer ?: synchronized(this) {
            _exoPlayer ?: createExoPlayer().also { _exoPlayer = it }
        }

    private fun createExoPlayer(): ExoPlayer {
        val nonNullContext = checkNotNull(context) { "Context is required to create a default ExoPlayer instance" }
        val defaultFactory = DefaultDataSource.Factory(nonNullContext)
        val mediaSourceFactory = DefaultMediaSourceFactory(nonNullContext)
            .setDataSourceFactory {
                defaultFactory.createDataSource()
            }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        return ExoPlayer.Builder(nonNullContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()
            .apply {
                addListener(playerListener)
            }
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    internal val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startProgressPolling()
            } else {
                stopProgressPolling()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val oldUri = oldPosition.mediaItem?.localConfiguration?.uri?.toString()
            val oldPos = oldPosition.positionMs.coerceAtLeast(0L)
            if (!oldUri.isNullOrBlank() && oldPos > 0L) {
                // Use actual reliable duration linked to this specific URI, or 0L if unknown.
                // NEVER substitute oldPos as duration, to avoid falsely triggering end-of-video reset!
                val actualDur = knownDurations[oldUri] ?: 0L
                lastReportedPositionMs = oldPos
                onPeriodicPositionUpdate?.invoke(oldUri, oldPos, actualDur)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            checkAndApplyPendingRestoration()
        }

        private fun checkAndApplyPendingRestoration() {
            val uri = _uiState.value.mediaUri?.toString() ?: return
            val durationMs = exoPlayer.duration.coerceAtLeast(0L)
            if (durationMs <= 0L) return

            knownDurations[uri] = durationMs

            val effective = restorationCoordinator.checkAndApplyPendingRestoration(uri, durationMs)
            if (effective != null) {
                if (effective > 0L) {
                    exoPlayer.seekTo(effective)
                    _uiState.update { it.copy(currentPositionMs = effective) }
                } else {
                    exoPlayer.seekTo(0L)
                    _uiState.update { it.copy(currentPositionMs = 0L) }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                val currentIndex = exoPlayer.currentMediaItemIndex
                val baseId = if (!mediaItem.mediaId.isNullOrEmpty()) mediaItem.mediaId else mediaItem.localConfiguration?.uri?.toString() ?: ""
                val transitionKey = "${baseId}_#${currentIndex}"
                val isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                val isRealTransition = restorationCoordinator.onMediaItemTransition(transitionKey, isRepeat)

                val title = mediaItem.mediaMetadata.title?.toString() ?: ""
                val uri = mediaItem.localConfiguration?.uri
                lastReportedPositionMs = 0L
                _uiState.update {
                    it.copy(
                        mediaUri = uri,
                        title = title,
                        currentPositionMs = 0L
                    )
                }
                if (isRealTransition && !isRepeat) {
                    onMediaTransition?.invoke(mediaItem.mediaId)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _uiState.update { it.copy(isBuffering = true) }
                Player.STATE_READY -> {
                    val durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    val uri = _uiState.value.mediaUri?.toString()
                    if (!uri.isNullOrBlank() && durationMs > 0L) {
                        knownDurations[uri] = durationMs
                    }

                    checkAndApplyPendingRestoration()

                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            durationMs = durationMs,
                            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                            bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    _uiState.update { it.copy(isPlaying = false, isBuffering = false) }
                    stopProgressPolling()
                    val uri = _uiState.value.mediaUri?.toString()
                    val dur = _uiState.value.durationMs
                    if (!uri.isNullOrBlank() && dur > 0L) {
                        onPeriodicPositionUpdate?.invoke(uri, dur, dur)
                    }
                }
                Player.STATE_IDLE -> {
                    _uiState.update { it.copy(isBuffering = false) }
                }
            }
        }
    }

    init {
        customExoPlayer?.addListener(playerListener)
    }

    fun playMedia(uri: Uri, title: String, startPositionMs: Long = 0L) {
        // Save previous video's position directly from ExoPlayer before switching
        saveCurrentExoPlayerPositionDirectly()

        val startKey = "${uri}_#0"
        restorationCoordinator.onNewPlaybackSession(startKey)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        lastReportedPositionMs = startPositionMs
        _uiState.update {
            it.copy(
                mediaUri = uri,
                title = title,
                currentPositionMs = startPositionMs,
                durationMs = 0L
            )
        }

        if (uri.scheme == "vault") {
            val nonNullContext = checkNotNull(context) { "Context is required for vault encrypted media source" }
            val dataSourceFactory = { EncryptedVaultDataSource(nonNullContext) }
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
            exoPlayer.setMediaSource(mediaSource, startPositionMs)
        } else {
            val mediaItem = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title)
                        .build()
                )
                .build()
            exoPlayer.setMediaItem(mediaItem, startPositionMs)
        }

        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun playPlaylist(
        items: List<VideoItem>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        repeatMode: PlaylistRepeatMode = PlaylistRepeatMode.OFF,
        startPositionMs: Long = 0L
    ) {
        if (items.isEmpty()) return

        // Save previous video's position directly from ExoPlayer before switching
        saveCurrentExoPlayerPositionDirectly()

        val validStartIndex = startIndex.coerceIn(0, items.size - 1)
        val startVideo = items[validStartIndex]
        val startKey = "${startVideo.id}_#${validStartIndex}"
        restorationCoordinator.onNewPlaybackSession(startKey)

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val mediaItems = items.map { video ->
            MediaItem.Builder()
                .setMediaId(video.id.toString())
                .setUri(video.contentUri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(video.safeTitle)
                        .build()
                )
                .build()
        }

        setRepeatMode(repeatMode)
        exoPlayer.shuffleModeEnabled = shuffle

        lastReportedPositionMs = startPositionMs

        _uiState.update {
            it.copy(
                mediaUri = startVideo.contentUri,
                title = startVideo.safeTitle,
                currentPositionMs = startPositionMs,
                durationMs = startVideo.durationMs,
                repeatMode = repeatMode
            )
        }

        exoPlayer.setMediaItems(mediaItems, validStartIndex, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun setRepeatMode(mode: PlaylistRepeatMode) {
        exoPlayer.repeatMode = when (mode) {
            PlaylistRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            PlaylistRepeatMode.ONE -> Player.REPEAT_MODE_ONE
            PlaylistRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        _uiState.update { it.copy(repeatMode = mode) }
    }

    fun setShuffle(shuffle: Boolean) {
        exoPlayer.shuffleModeEnabled = shuffle
    }

    fun nextTrack() {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
        }
    }

    fun previousTrack() {
        if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
        }
    }

    fun play() {
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val uri = _uiState.value.mediaUri?.toString()
        if (!uri.isNullOrBlank()) {
            val dur = exoPlayer.duration.coerceAtLeast(0L).takeIf { it > 0L }
                ?: (knownDurations[uri] ?: 0L)
            if (dur > 0L) {
                knownDurations[uri] = dur
            }
            lastReportedPositionMs = pos
            onPeriodicPositionUpdate?.invoke(uri, pos, dur)
        }
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        // Invalidate and cancel any pending position restoration so a late DataStore result doesn't snap user back!
        restorationCoordinator.onManualSeek(positionMs)
        lastReportedPositionMs = positionMs
        exoPlayer.seekTo(positionMs)
        _uiState.update { it.copy(currentPositionMs = positionMs) }
        updateActiveSubtitles(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun setResizeMode(mode: Int) {
        _uiState.update { it.copy(resizeMode = mode) }
    }

    fun toggleControls() {
        _uiState.update { it.copy(isControlsVisible = !it.isControlsVisible) }
    }

    fun setControlsVisible(visible: Boolean) {
        _uiState.update { it.copy(isControlsVisible = visible) }
    }

    fun toggleLock() {
        _uiState.update { it.copy(isLocked = !it.isLocked) }
    }

    fun setPipActive(active: Boolean) {
        _uiState.update { it.copy(isPipActive = active) }
    }

    fun setPrimarySubtitleTrack(track: SubtitleTrack?) {
        _uiState.update { it.copy(primaryTrack = track) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun setSecondarySubtitleTrack(track: SubtitleTrack?) {
        _uiState.update { it.copy(secondaryTrack = track) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun addSubtitleTrack(track: SubtitleTrack) {
        _uiState.update {
            val existing = it.availableSubtitleTracks.filter { t -> t.id != track.id }
            it.copy(availableSubtitleTracks = existing + track)
        }
    }

    fun syncWithCurrentPlayer() {
        val isPlaying = exoPlayer.isPlaying
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = exoPlayer.duration.coerceAtLeast(0L)
        _uiState.update {
            it.copy(
                isPlaying = isPlaying,
                currentPositionMs = pos,
                durationMs = dur
            )
        }
    }

    fun stopPlayback() {
        saveCurrentExoPlayerPositionDirectly()
        restorationCoordinator.onPlaybackStopped()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        stopProgressPolling()
        _uiState.update {
            it.copy(
                isPlaying = false,
                currentPositionMs = 0L,
                mediaUri = null,
                title = ""
            )
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                val buf = exoPlayer.bufferedPosition.coerceAtLeast(0L)
                _uiState.update {
                    it.copy(
                        currentPositionMs = pos,
                        durationMs = dur,
                        bufferedPositionMs = buf
                    )
                }
                updateActiveSubtitles(pos)

                // Periodic position persistence trigger (every 5 seconds of active playback)
                if (kotlin.math.abs(pos - lastReportedPositionMs) >= 5000L && dur > 0L) {
                    lastReportedPositionMs = pos
                    val currentUri = _uiState.value.mediaUri?.toString()
                    if (!currentUri.isNullOrBlank()) {
                        onPeriodicPositionUpdate?.invoke(currentUri, pos, dur)
                    }
                }

                delay(200)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
    }

    private fun updateActiveSubtitles(currentMs: Long) {
        val state = _uiState.value
        val primaryCues = state.primaryTrack?.cues?.filter {
            currentMs in it.startTimeMs..it.endTimeMs
        } ?: emptyList()

        val secondaryCues = state.secondaryTrack?.cues?.filter {
            currentMs in it.startTimeMs..it.endTimeMs
        } ?: emptyList()

        _uiState.update {
            it.copy(
                activePrimaryCues = primaryCues,
                activeSecondaryCues = secondaryCues
            )
        }
    }

    fun release() {
        stopProgressPolling()
        onPeriodicPositionUpdate = null
        onMediaTransition = null
        _exoPlayer?.release()
        _exoPlayer = null
    }
}

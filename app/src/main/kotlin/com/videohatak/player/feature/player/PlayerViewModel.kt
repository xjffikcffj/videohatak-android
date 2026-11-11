package com.videohatak.player.feature.player

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.PlaybackPositionManager
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlayerUiState
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playlist.data.PlaylistRepositoryImpl
import com.videohatak.player.core.subtitle.SubtitleParser
import com.videohatak.player.core.subtitle.SubtitlePreferencesRepository
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.feature.player.thumbnail.ThumbnailManager as ThumbnailPreviewManager
import com.videohatak.player.feature.player.thumbnail.ThumbnailPreviewState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class PlayerViewModel(
    application: Application,
    val playerManager: VideoPlayerManager,
    private val playbackPreferences: PlaybackPreferencesRepository,
    val positionManager: PlaybackPositionManager = PlaybackPositionManager.getInstance(application),
    private val subtitlePreferences: SubtitlePreferencesRepository? = null,
    private val thumbnailManager: ThumbnailPreviewManager? = null
) : AndroidViewModel(application) {

    private val safeSubtitlePreferences: SubtitlePreferencesRepository? =
        subtitlePreferences ?: try { SubtitlePreferencesRepository(application) } catch (_: Throwable) { null }

    private val safeThumbnailManager: ThumbnailPreviewManager? =
        thumbnailManager ?: try { ThumbnailPreviewManager(application) } catch (_: Throwable) { null }

    var currentVideo: VideoItem? = null
        private set

    private val playlistRepository by lazy { PlaylistRepositoryImpl(getApplication()) }
    private var playlistVideos: List<VideoItem> = emptyList()

    companion object {
        private const val TAG = "PlayerViewModel"

        fun provideFactory(
            application: Application,
            playbackPreferences: PlaybackPreferencesRepository,
            positionManager: PlaybackPositionManager = PlaybackPositionManager.getInstance(application)
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val playerManager = VideoPlayerManager.getInstance(application)
                return PlayerViewModel(
                    application = application,
                    playerManager = playerManager,
                    playbackPreferences = playbackPreferences,
                    positionManager = positionManager,
                    subtitlePreferences = SubtitlePreferencesRepository(application),
                    thumbnailManager = ThumbnailPreviewManager(application)
                ) as T
            }
        }
    }

    init {
        playerManager.onMediaTransition = { mediaId ->
            val matching = playlistVideos.find { it.id.toString() == mediaId }
            if (matching != null) {
                currentVideo = matching
                safeThumbnailManager?.setVideoUri(matching.contentUri)
                val targetUri = matching.contentUri.toString()
                val targetGen = playerManager.getCurrentPlaybackGeneration()
                viewModelScope.launch {
                    val savedPos = playbackPreferences.getSavedVideoPosition(
                        targetUri,
                        matching.durationMs,
                        legacyVideoId = matching.id
                    )
                    playerManager.applyRestoredPosition(targetUri, targetGen, savedPos)
                }
            }
        }
        playerManager.onPeriodicPositionUpdate = { uri, pos, dur ->
            positionManager.enqueueSave(uri, pos, dur)
        }
        viewModelScope.launch {
            playbackPreferences.repeatMode.collect { modeStr ->
                val mode = try {
                    com.videohatak.player.core.playlist.model.PlaylistRepeatMode.valueOf(modeStr)
                } catch (_: Exception) {
                    com.videohatak.player.core.playlist.model.PlaylistRepeatMode.OFF
                }
                playerManager.setRepeatMode(mode)
            }
        }
    }

    val uiState: StateFlow<PlayerUiState> = playerManager.uiState
    val thumbnailPreviewState: StateFlow<ThumbnailPreviewState> =
        safeThumbnailManager?.previewState ?: kotlinx.coroutines.flow.MutableStateFlow(ThumbnailPreviewState())

    val primarySubtitleStyle = safeSubtitlePreferences?.primaryStyle
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.videohatak.player.core.subtitle.model.SubtitleStyleConfig())
        ?: kotlinx.coroutines.flow.MutableStateFlow(com.videohatak.player.core.subtitle.model.SubtitleStyleConfig())

    val secondarySubtitleStyle = safeSubtitlePreferences?.secondaryStyle
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.videohatak.player.core.subtitle.model.SubtitleStyleConfig(fontSizeSp = 15f, bottomPaddingDp = 88f))
        ?: kotlinx.coroutines.flow.MutableStateFlow(com.videohatak.player.core.subtitle.model.SubtitleStyleConfig(fontSizeSp = 15f, bottomPaddingDp = 88f))

    fun playMedia(uri: Uri, title: String) {
        currentVideo = null // Clear any stale VideoItem reference to avoid URI mismatch
        playlistVideos = emptyList()
        safeThumbnailManager?.setVideoUri(uri)
        val targetUri = uri.toString()
        playerManager.playMedia(uri, title, startPositionMs = 0L)
        val targetGen = playerManager.getCurrentPlaybackGeneration()
        viewModelScope.launch {
            val savedPos = playbackPreferences.getSavedVideoPosition(targetUri, 0L)
            playerManager.applyRestoredPosition(targetUri, targetGen, savedPos)
        }
    }

    fun playVideo(video: VideoItem) {
        currentVideo = video
        safeThumbnailManager?.setVideoUri(video.contentUri)
        val targetUri = video.contentUri.toString()
        playerManager.playMedia(video.contentUri, video.safeTitle, startPositionMs = 0L)
        val targetGen = playerManager.getCurrentPlaybackGeneration()
        viewModelScope.launch {
            val savedPos = playbackPreferences.getSavedVideoPosition(
                targetUri,
                video.durationMs,
                legacyVideoId = video.id
            )
            playerManager.applyRestoredPosition(targetUri, targetGen, savedPos)
        }
    }

    fun playPlaylist(playlistId: Long, startVideoId: Long?, shuffle: Boolean) {
        viewModelScope.launch {
            val rawItems = playlistRepository.getPlaylistItems(playlistId).filter { it.isAvailable }
            if (rawItems.isEmpty()) return@launch
            val videoItems = rawItems.map { it.toVideoItem() }
            playlistVideos = videoItems

            val startIndex = if (startVideoId != null) {
                videoItems.indexOfFirst { it.id == startVideoId }.coerceAtLeast(0)
            } else 0

            val startVideo = videoItems[startIndex]
            currentVideo = startVideo
            safeThumbnailManager?.setVideoUri(startVideo.contentUri)

            val targetUri = startVideo.contentUri.toString()
            playerManager.playPlaylist(
                items = videoItems,
                startIndex = startIndex,
                shuffle = shuffle,
                repeatMode = playerManager.uiState.value.repeatMode,
                startPositionMs = 0L
            )
            val targetGen = playerManager.getCurrentPlaybackGeneration()

            val savedPos = playbackPreferences.getSavedVideoPosition(
                targetUri,
                startVideo.durationMs,
                legacyVideoId = startVideo.id
            )
            playerManager.applyRestoredPosition(targetUri, targetGen, savedPos)
        }
    }

    fun cycleRepeatMode() {
        val current = playerManager.uiState.value.repeatMode
        val next = when (current) {
            com.videohatak.player.core.playlist.model.PlaylistRepeatMode.OFF -> com.videohatak.player.core.playlist.model.PlaylistRepeatMode.ALL
            com.videohatak.player.core.playlist.model.PlaylistRepeatMode.ALL -> com.videohatak.player.core.playlist.model.PlaylistRepeatMode.ONE
            com.videohatak.player.core.playlist.model.PlaylistRepeatMode.ONE -> com.videohatak.player.core.playlist.model.PlaylistRepeatMode.OFF
        }
        playerManager.setRepeatMode(next)
        viewModelScope.launch {
            playbackPreferences.setRepeatMode(next.name)
        }
    }

    fun saveCurrentPosition() {
        val capturedDirectly = playerManager.saveCurrentExoPlayerPositionDirectly()
        val uri = playerManager.uiState.value.mediaUri?.toString() ?: currentVideo?.contentUri?.toString()
        val pos = playerManager.uiState.value.currentPositionMs
        val dur = playerManager.uiState.value.durationMs

        val fallback = com.videohatak.player.core.playback.PositionSavePolicy.shouldEnqueueFallbackSave(
            capturedDirectlyFromPlayer = capturedDirectly,
            uri = uri,
            currentPositionMs = pos,
            durationMs = dur
        ) ?: return

        positionManager.enqueueSave(fallback.uri, fallback.positionMs, fallback.durationMs)
    }

    fun stopPlaybackCompletely() {
        currentVideo = null
        playlistVideos = emptyList()
        playerManager.stopPlayback()
    }

    fun play() = playerManager.play()

    fun pause() = playerManager.pause()

    override fun onCleared() {
        // Enqueue final position to the Application-scoped PlaybackPositionManager.
        // Never blocks the Main Thread with runBlocking!
        saveCurrentPosition()
        super.onCleared()
    }

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
        val uri = playerManager.uiState.value.mediaUri?.toString()
            ?: currentVideo?.contentUri?.toString()
        val durationMs = playerManager.uiState.value.durationMs
            .takeIf { it > 0L }
            ?: (currentVideo?.durationMs ?: 0L)

        if (!uri.isNullOrBlank() && positionMs >= 0L) {
            positionManager.enqueueSave(uri, positionMs, durationMs)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        playerManager.setPlaybackSpeed(speed)
        viewModelScope.launch {
            playbackPreferences.setPlaybackSpeed(speed)
        }
    }

    fun setResizeMode(mode: Int) {
        playerManager.setResizeMode(mode)
        viewModelScope.launch {
            playbackPreferences.setResizeMode(mode)
        }
    }

    fun toggleControls() = playerManager.toggleControls()
    fun setControlsVisible(visible: Boolean) = playerManager.setControlsVisible(visible)
    fun toggleLock() = playerManager.toggleLock()
    fun setPipActive(active: Boolean) = playerManager.setPipActive(active)

    fun onScrubStart(positionMs: Long, fraction: Float) {
        safeThumbnailManager?.onScrubStart(positionMs, fraction)
    }

    fun onScrubProgress(positionMs: Long, fraction: Float) {
        safeThumbnailManager?.onScrubProgress(positionMs, fraction)
    }

    fun onScrubStop(finalPositionMs: Long) {
        safeThumbnailManager?.onScrubStop()
        seekTo(finalPositionMs)
    }

    fun setPrimarySubtitleTrack(track: SubtitleTrack?) = playerManager.setPrimarySubtitleTrack(track)
    fun setSecondarySubtitleTrack(track: SubtitleTrack?) = playerManager.setSecondarySubtitleTrack(track)

    fun importSubtitle(context: Context, uri: Uri, name: String) {
        viewModelScope.launch {
            val cues = SubtitleParser.parse(context, uri)
            val track = SubtitleTrack(
                id = UUID.randomUUID().toString(),
                name = name,
                uri = uri,
                cues = cues,
                isExternal = true
            )
            playerManager.addSubtitleTrack(track)
            if (playerManager.uiState.value.primaryTrack == null) {
                playerManager.setPrimarySubtitleTrack(track)
            } else if (playerManager.uiState.value.secondaryTrack == null) {
                playerManager.setSecondarySubtitleTrack(track)
            }
        }
    }

    fun updatePrimaryFontSize(sizeSp: Float) {
        viewModelScope.launch {
            safeSubtitlePreferences?.updatePrimaryFontSize(sizeSp)
        }
    }

    fun updateSecondaryFontSize(sizeSp: Float) {
        viewModelScope.launch {
            safeSubtitlePreferences?.updateSecondaryFontSize(sizeSp)
        }
    }
}

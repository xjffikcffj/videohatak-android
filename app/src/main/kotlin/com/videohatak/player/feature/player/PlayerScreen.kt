package com.videohatak.player.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.pip.PipHelper
import com.videohatak.player.feature.player.components.DualSubtitlesOverlay
import com.videohatak.player.feature.player.components.PlayerControlsOverlay
import com.videohatak.player.feature.player.components.SubtitleManagerDialog

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    video: VideoItem,
    viewModel: PlayerViewModel,
    isInPipMode: Boolean = false,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val thumbnailState by viewModel.thumbnailPreviewState.collectAsStateWithLifecycle()
    val primaryStyle by viewModel.primarySubtitleStyle.collectAsStateWithLifecycle()
    val secondaryStyle by viewModel.secondarySubtitleStyle.collectAsStateWithLifecycle()

    var isSubtitlesDialogVisible by remember { mutableStateOf(false) }
    val pipHelper = remember { PipHelper(context) }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف ترجمة خارجي"
            viewModel.importSubtitle(context, uri, name)
        }
    }

    LaunchedEffect(video.contentUri) {
        if (uiState.mediaUri != video.contentUri) {
            viewModel.playVideo(video)
        }
    }

    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    BackHandler {
        onBackClick()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                viewModel.toggleControls()
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = VideoPlayerManager.getInstance(ctx).exoPlayer
                    resizeMode = uiState.resizeMode
                }
            },
            update = { playerView ->
                playerView.resizeMode = uiState.resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Dual Subtitles Overlay
        if (!isInPipMode) {
            DualSubtitlesOverlay(
                primaryCues = uiState.activePrimaryCues,
                secondaryCues = uiState.activeSecondaryCues,
                primaryStyle = primaryStyle,
                secondaryStyle = secondaryStyle
            )
        }

        // Buffering Indicator
        if (uiState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Controls Overlay
        if (!isInPipMode && !uiState.isPipActive) {
            PlayerControlsOverlay(
                uiState = uiState,
                thumbnailState = thumbnailState,
                onBack = onBackClick,
                onPlayPause = {
                    if (uiState.isPlaying) viewModel.pause() else viewModel.play()
                },
                onSeekStart = viewModel::onScrubStart,
                onSeekProgress = viewModel::onScrubProgress,
                onSeekStop = viewModel::onScrubStop,
                onForward10 = { viewModel.seekTo(uiState.currentPositionMs + 10_000L) },
                onReplay10 = { viewModel.seekTo((uiState.currentPositionMs - 10_000L).coerceAtLeast(0L)) },
                onToggleLock = viewModel::toggleLock,
                onEnterPip = {
                    activity?.let {
                        val entered = pipHelper.enterPip(it, uiState.isPlaying)
                        if (entered) viewModel.setPipActive(true)
                    }
                },
                onCycleResizeMode = {
                    val nextMode = when (uiState.resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    viewModel.setResizeMode(nextMode)
                },
                onOpenSubtitlesDialog = { isSubtitlesDialogVisible = true },
                onSpeedClick = {
                    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    val nextIndex = (speeds.indexOf(uiState.playbackSpeed) + 1) % speeds.size
                    viewModel.setPlaybackSpeed(speeds[nextIndex])
                },
                onCycleRepeatMode = viewModel::cycleRepeatMode
            )
        }

        if (isSubtitlesDialogVisible) {
            SubtitleManagerDialog(
                availableTracks = uiState.availableSubtitleTracks,
                primaryTrack = uiState.primaryTrack,
                secondaryTrack = uiState.secondaryTrack,
                primaryStyle = primaryStyle,
                secondaryStyle = secondaryStyle,
                onSelectPrimaryTrack = viewModel::setPrimarySubtitleTrack,
                onSelectSecondaryTrack = viewModel::setSecondarySubtitleTrack,
                onImportSubtitle = {
                    isSubtitlesDialogVisible = false
                    subtitlePickerLauncher.launch("*/*")
                },
                onChangePrimaryFontSize = viewModel::updatePrimaryFontSize,
                onChangeSecondaryFontSize = viewModel::updateSecondaryFontSize,
                onDismiss = { isSubtitlesDialogVisible = false }
            )
        }
    }
}

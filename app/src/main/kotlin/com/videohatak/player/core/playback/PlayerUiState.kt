package com.videohatak.player.core.playback

import android.net.Uri
import androidx.media3.ui.AspectRatioFrameLayout
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleTrack

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val title: String = "",
    val mediaUri: Uri? = null,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: PlaylistRepeatMode = PlaylistRepeatMode.OFF,
    val isControlsVisible: Boolean = true,
    val isLocked: Boolean = false,
    val isPipActive: Boolean = false,
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val primaryTrack: SubtitleTrack? = null,
    val secondaryTrack: SubtitleTrack? = null,
    val availableSubtitleTracks: List<SubtitleTrack> = emptyList(),
    val activePrimaryCues: List<SubtitleCue> = emptyList(),
    val activeSecondaryCues: List<SubtitleCue> = emptyList(),
    val isBuffering: Boolean = false,
    val errorMessage: String? = null
) {
    val formattedCurrentTime: String
        get() = formatTime(currentPositionMs)

    val formattedTotalDuration: String
        get() = formatTime(durationMs)

    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

    val resizeModeLabelArabic: String
        get() = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "تكبير (Zoom)"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "تمديد (Stretch)"
            else -> "ملاءمة (Fit)"
        }

    private fun formatTime(millis: Long): String {
        if (millis <= 0L) return "00:00"
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}

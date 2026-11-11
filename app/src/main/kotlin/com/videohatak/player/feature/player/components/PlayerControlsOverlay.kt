package com.videohatak.player.feature.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.playback.PlayerUiState
import com.videohatak.player.core.thumbnail.model.ThumbnailPreviewState

@Composable
fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    thumbnailState: ThumbnailPreviewState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekStart: (Long, Float) -> Unit,
    onSeekProgress: (Long, Float) -> Unit,
    onSeekStop: (Long) -> Unit,
    onForward10: () -> Unit,
    onReplay10: () -> Unit,
    onToggleLock: () -> Unit,
    onEnterPip: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onOpenSubtitlesDialog: () -> Unit,
    onSpeedClick: () -> Unit,
    onCycleRepeatMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Clicking outside handled by parent
            }
    ) {
        if (uiState.isLocked) {
            // Only show unlock button
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.TopStart
            ) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.background(Color(0x88000000), shape = MaterialTheme.shapes.small)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "فتح القفل",
                        tint = Color.White
                    )
                }
            }
            return@Box
        }

        AnimatedVisibility(
            visible = uiState.isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x77000000))
            ) {
                // Top Bar Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }

                    Text(
                        text = uiState.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = onOpenSubtitlesDialog) {
                        Icon(Icons.Default.Subtitles, contentDescription = "الترجمات", tint = Color.White)
                    }

                    IconButton(onClick = onEnterPip) {
                        Icon(Icons.Default.PictureInPicture, contentDescription = "صورة في صورة", tint = Color.White)
                    }

                    IconButton(onClick = onToggleLock) {
                        Icon(Icons.Default.LockOpen, contentDescription = "قفل الشاشة", tint = Color.White)
                    }
                }

                // Center Play / Pause / Seek Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onReplay10, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = "ترجيع 10 ثوان",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(32.dp))

                    IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (uiState.isPlaying) "إيقاف" else "تشغيل",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(32.dp))

                    IconButton(onClick = onForward10, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = "تقديم 10 ثوان",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // Bottom Controls & Scrub Slider
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    // Thumbnail Preview above slider
                    if (thumbnailState.isVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ThumbnailPreviewCard(
                                bitmap = thumbnailState.bitmap,
                                positionMs = thumbnailState.positionMs
                            )
                        }
                    }

                    val currentPos = if (isDraggingSlider) {
                        sliderPosition.toLong()
                    } else {
                        uiState.currentPositionMs
                    }

                    Slider(
                        value = if (uiState.durationMs > 0) currentPos.toFloat() else 0f,
                        onValueChange = { value ->
                            isDraggingSlider = true
                            sliderPosition = value
                            val fraction = if (uiState.durationMs > 0) value / uiState.durationMs else 0f
                            onSeekProgress(value.toLong(), fraction)
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            onSeekStop(sliderPosition.toLong())
                        },
                        valueRange = 0f..(uiState.durationMs.coerceAtLeast(1L).toFloat()),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.4f)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatDuration(currentPos)} / ${formatDuration(uiState.durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onCycleRepeatMode) {
                                val (icon, tint) = when (uiState.repeatMode) {
                                    PlaylistRepeatMode.ONE -> Pair(Icons.Default.RepeatOne, MaterialTheme.colorScheme.primary)
                                    PlaylistRepeatMode.ALL -> Pair(Icons.Default.Repeat, MaterialTheme.colorScheme.primary)
                                    PlaylistRepeatMode.OFF -> Pair(Icons.Default.Repeat, Color.White.copy(alpha = 0.5f))
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = when (uiState.repeatMode) {
                                        PlaylistRepeatMode.ONE -> "تكرار مقطع واحد"
                                        PlaylistRepeatMode.ALL -> "تكرار الكل"
                                        PlaylistRepeatMode.OFF -> "إيقاف التكرار"
                                    },
                                    tint = tint
                                )
                            }

                            IconButton(onClick = onSpeedClick) {
                                Text(
                                    text = "${uiState.playbackSpeed}x",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            IconButton(onClick = onCycleResizeMode) {
                                Icon(Icons.Default.AspectRatio, contentDescription = "أبعاد الشاشة", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

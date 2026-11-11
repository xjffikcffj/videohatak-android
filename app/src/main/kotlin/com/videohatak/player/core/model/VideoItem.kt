package com.videohatak.player.core.model

import android.net.Uri

data class VideoItem(
    val id: Long,
    val contentUri: Uri,
    val title: String = "",
    val displayName: String = "",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val dateAddedSec: Long = 0L,
    val dateModifiedSec: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "video/*",
    val path: String = "",
    val relativePath: String = "",
    val bucketId: String = "",
    val bucketDisplayName: String = ""
) {
    val duration: Long get() = durationMs
    val size: Long get() = sizeBytes
    val dateAdded: Long get() = dateAddedSec

    val safeTitle: String
        get() = when {
            title.isNotBlank() -> title.trim()
            displayName.isNotBlank() -> displayName.trim()
            else -> "فيديو محلي"
        }

    val formattedDuration: String
        get() {
            if (durationMs <= 0L) return "00:00"
            val totalSeconds = durationMs / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val formattedSize: String
        get() {
            if (sizeBytes <= 0L) return "0 بايت"
            val gb = sizeBytes / (1024.0 * 1024.0 * 1024.0)
            val mb = sizeBytes / (1024.0 * 1024.0)
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f جيجابايت", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f ميجابايت", mb)
                else -> String.format(java.util.Locale.US, "%d كيلوبايت", sizeBytes / 1024L)
            }
        }

    val resolutionTag: String
        get() {
            val minDim = minOf(width, height)
            val maxDim = maxOf(width, height)
            return when {
                maxDim >= 3840 || minDim >= 2160 -> "4K"
                maxDim >= 1920 || minDim >= 1080 -> "1080p"
                maxDim >= 1280 || minDim >= 720 -> "720p"
                else -> "480p"
            }
        }

    val resolution: String get() = resolutionTag
}

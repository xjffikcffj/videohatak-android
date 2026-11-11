package com.videohatak.player.core.vault.model

import android.net.Uri
import com.videohatak.player.core.model.VideoItem

data class VaultVideo(
    val id: Long,
    val displayName: String,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val durationMs: Long,
    val mimeType: String = "video/mp4",
    val width: Int = 0,
    val height: Int = 0,
    val relativeEncryptedPath: String,
    val createdAtEpochMs: Long,
    val ivBase64: String = ""
) {
    val title: String get() = displayName
    val fileSizeBytes: Long get() = encryptedSizeBytes

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
            if (encryptedSizeBytes <= 0L) return "0 بايت"
            val gb = encryptedSizeBytes / (1024.0 * 1024.0 * 1024.0)
            val mb = encryptedSizeBytes / (1024.0 * 1024.0)
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f جيجابايت مشفرة", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f ميجابايت مشفرة", mb)
                else -> String.format(java.util.Locale.US, "%d كيلوبايت مشفرة", encryptedSizeBytes / 1024L)
            }
        }
}

fun VaultVideo.toVideoItem(): VideoItem {
    val vaultUri = if (relativeEncryptedPath.startsWith("vault://")) {
        Uri.parse(relativeEncryptedPath)
    } else {
        Uri.parse("vault://$relativeEncryptedPath")
    }

    return VideoItem(
        id = id,
        contentUri = vaultUri,
        title = displayName,
        displayName = displayName,
        durationMs = durationMs,
        sizeBytes = encryptedSizeBytes,
        dateAddedSec = createdAtEpochMs / 1000L,
        dateModifiedSec = createdAtEpochMs / 1000L,
        width = width,
        height = height,
        mimeType = mimeType,
        path = relativeEncryptedPath,
        relativePath = "vault",
        bucketId = "secure_vault",
        bucketDisplayName = "الخزنة الخاصة"
    )
}

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

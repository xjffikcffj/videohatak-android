package com.videohatak.player.core.playlist.model

import android.net.Uri
import com.videohatak.player.core.model.VideoItem

data class PlaylistItem(
    val id: Long,
    val playlistId: Long,
    val videoId: Long,
    val orderIndex: Int,
    val videoTitle: String,
    val durationMs: Long,
    val sizeBytes: Long = 0L,
    val dateAddedSec: Long = 0L,
    val contentUri: String,
    val isAvailable: Boolean = true
) {
    val mediaUri: Uri get() = Uri.parse(contentUri)
    val title: String get() = videoTitle
    val position: Int get() = orderIndex
    val isMissing: Boolean get() = !isAvailable
}

fun PlaylistItem.toVideoItem(): VideoItem {
    return VideoItem(
        id = videoId,
        contentUri = Uri.parse(contentUri),
        title = videoTitle,
        displayName = videoTitle,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        dateAddedSec = dateAddedSec,
        dateModifiedSec = dateAddedSec,
        width = 0,
        height = 0,
        mimeType = "video/*",
        path = "",
        relativePath = "",
        bucketId = "playlist_$playlistId",
        bucketDisplayName = "قائمة التشغيل"
    )
}

package com.videohatak.player.core.model

data class VideoFolder(
    val bucketId: String,
    val displayName: String,
    val videoCount: Int,
    val totalSizeBytes: Long,
    val firstVideoItem: VideoItem? = null
) {
    val formattedTotalSize: String
        get() {
            val gb = totalSizeBytes / (1024.0 * 1024.0 * 1024.0)
            val mb = totalSizeBytes / (1024.0 * 1024.0)
            return when {
                gb >= 1.0 -> String.format("%.1f جيجابايت", gb)
                mb >= 1.0 -> String.format("%.1f ميجابايت", mb)
                else -> String.format("%d كيلوبايت", totalSizeBytes / 1024L)
            }
        }
}

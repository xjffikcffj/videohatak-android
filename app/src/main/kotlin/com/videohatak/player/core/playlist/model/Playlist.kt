package com.videohatak.player.core.playlist.model

data class Playlist(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val itemCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val thumbnailUri: String? = null
) {
    val name: String get() = title
}

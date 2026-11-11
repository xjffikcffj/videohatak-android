package com.videohatak.player.core.playlist.model

enum class PlaylistRepeatMode {
    OFF,
    ONE,
    ALL
}

data class PlaylistPlaybackState(
    val playlistId: Long? = null,
    val playlistTitle: String = "",
    val currentVideoId: Long? = null,
    val currentIndex: Int = -1,
    val items: List<PlaylistItem> = emptyList(),
    val shuffleOrder: List<Long> = emptyList(),
    val isShuffle: Boolean = false,
    val repeatMode: PlaylistRepeatMode = PlaylistRepeatMode.OFF,
    val savedPositionMs: Long = 0L
) {
    val isShuffleEnabled: Boolean get() = isShuffle

    val activeItems: List<PlaylistItem>
        get() = if (isShuffle && shuffleOrder.isNotEmpty()) {
            val itemMap = items.associateBy { it.videoId }
            shuffleOrder.mapNotNull { itemMap[it] }
        } else {
            items.sortedBy { it.orderIndex }
        }

    val hasNext: Boolean
        get() {
            if (activeItems.isEmpty()) return false
            if (repeatMode == PlaylistRepeatMode.ALL) return true
            return currentIndex in 0 until (activeItems.size - 1)
        }

    val hasPrevious: Boolean
        get() {
            if (activeItems.isEmpty()) return false
            if (repeatMode == PlaylistRepeatMode.ALL) return true
            return currentIndex > 0
        }
}

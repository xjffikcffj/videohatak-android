package com.videohatak.player.feature.library

import com.videohatak.player.core.model.SortOrder
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.model.VideoSortOrder
import com.videohatak.player.core.permission.VideoPermissionStatus

data class LibraryUiState(
    val permissionStatus: VideoPermissionStatus = VideoPermissionStatus.NotRequested,
    val isLoading: Boolean = false,
    val allVideos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val folders: List<VideoFolder> = emptyList(),
    val errorMessage: String? = null
)

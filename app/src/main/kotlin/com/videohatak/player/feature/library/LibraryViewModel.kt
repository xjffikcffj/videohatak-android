package com.videohatak.player.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.SortOrder
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.permission.VideoPermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _permissionStatus = MutableStateFlow<VideoPermissionStatus>(VideoPermissionStatus.NotRequested)
    private val _isLoading = MutableStateFlow(false)
    private val _rawVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    private val _errorMessage = MutableStateFlow<String?>(null)

    val searchQuery = MutableStateFlow("")
    val sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val currentTab = MutableStateFlow(LibraryTab.VIDEOS)
    val selectedFolder = MutableStateFlow<VideoFolder?>(null)

    private data class FilterCriteria(
        val query: String,
        val sort: SortOrder,
        val folder: VideoFolder?
    )

    private val filterCriteria = combine(
        searchQuery,
        sortOrder,
        selectedFolder
    ) { query, sort, folder ->
        FilterCriteria(query, sort, folder)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        _permissionStatus,
        _isLoading,
        _rawVideos,
        filterCriteria
    ) { status, loading, videos, criteria ->
        val filtered = videoRepository.filterAndSort(
            videos = videos,
            query = criteria.query,
            sortOrder = criteria.sort.asVideoSortOrder,
            folderId = criteria.folder?.bucketId
        )
        val folders = videoRepository.groupIntoFolders(videos)

        LibraryUiState(
            permissionStatus = status,
            isLoading = loading,
            allVideos = videos,
            filteredVideos = filtered,
            folders = folders,
            errorMessage = _errorMessage.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(isLoading = true)
    )

    fun onPermissionStatusChanged(status: VideoPermissionStatus) {
        _permissionStatus.value = status
        if (PermissionHelper.hasAnyAccess(status)) {
            loadVideos()
        }
    }

    // Backward-compatible alias
    fun updatePermissionState(status: VideoPermissionStatus) = onPermissionStatusChanged(status)

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onSortOrderChanged(order: SortOrder) {
        sortOrder.value = order
    }

    fun onTabSelected(tab: LibraryTab) {
        currentTab.value = tab
    }

    fun onFolderSelected(folder: VideoFolder) {
        selectedFolder.value = folder
    }

    fun clearFolderSelection() {
        selectedFolder.value = null
    }

    fun refresh() {
        if (PermissionHelper.hasAnyAccess(_permissionStatus.value)) {
            loadVideos()
        }
    }

    private fun loadVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val videos = videoRepository.getVideos()
                _rawVideos.value = videos
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "فشل في قراءة الفيديوهات"
            } finally {
                _isLoading.value = false
            }
        }
    }

    companion object {
        fun provideFactory(videoRepository: VideoRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(videoRepository) as T
                }
            }
    }
}

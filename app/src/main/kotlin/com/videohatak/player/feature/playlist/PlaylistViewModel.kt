package com.videohatak.player.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.playlist.model.PlaylistPlaybackState
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlaylistViewModel(
    private val repository: PlaylistRepository
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPlaylistWithItems = MutableStateFlow<Pair<Playlist, List<PlaylistItem>>?>(null)
    val selectedPlaylistWithItems: StateFlow<Pair<Playlist, List<PlaylistItem>>?> = _selectedPlaylistWithItems.asStateFlow()

    val currentPlaylistItems: StateFlow<List<PlaylistItem>> = _selectedPlaylistWithItems
        .map { it?.second ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _playbackState = MutableStateFlow(PlaylistPlaybackState())
    val playbackState: StateFlow<PlaylistPlaybackState> = _playbackState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
            _selectedPlaylistWithItems.value?.first?.id?.let { pId ->
                loadPlaylistWithItems(pId)
            }
        }
    }

    fun selectPlaylist(playlist: Playlist?) {
        if (playlist == null) {
            _selectedPlaylistWithItems.value = null
        } else {
            loadPlaylistWithItems(playlist.id)
        }
    }

    fun loadPlaylistWithItems(playlistId: Long) {
        viewModelScope.launch {
            val pair = repository.getPlaylistWithItems(playlistId)
            _selectedPlaylistWithItems.value = pair
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val newId = repository.createPlaylist(name)
            loadPlaylistWithItems(newId)
        }
    }

    fun renamePlaylist(id: Long, newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(id, newName)
            if (_selectedPlaylistWithItems.value?.first?.id == id) {
                loadPlaylistWithItems(id)
            }
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(id)
            if (_selectedPlaylistWithItems.value?.first?.id == id) {
                _selectedPlaylistWithItems.value = null
            }
        }
    }

    fun addVideoToPlaylists(video: VideoItem, playlistIds: List<Long>) {
        viewModelScope.launch {
            repository.addVideoToPlaylists(video, playlistIds)
            _selectedPlaylistWithItems.value?.first?.id?.let {
                if (it in playlistIds) loadPlaylistWithItems(it)
            }
        }
    }

    fun removeItemFromPlaylist(itemId: Long, playlistId: Long) {
        viewModelScope.launch {
            repository.removeVideoFromPlaylist(itemId, playlistId)
            loadPlaylistWithItems(playlistId)
        }
    }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        val current = _selectedPlaylistWithItems.value ?: return
        viewModelScope.launch {
            repository.moveItem(current.first.id, fromIndex, toIndex)
            loadPlaylistWithItems(current.first.id)
        }
    }

    fun relinkMissingItems(playlistId: Long) {
        viewModelScope.launch {
            repository.relinkMissingItems(playlistId)
            loadPlaylistWithItems(playlistId)
        }
    }

    fun toggleShuffle() {
        _playbackState.update { it.copy(isShuffle = !it.isShuffle) }
    }

    fun cycleRepeatMode() {
        _playbackState.update {
            val next = when (it.repeatMode) {
                PlaylistRepeatMode.OFF -> PlaylistRepeatMode.ALL
                PlaylistRepeatMode.ALL -> PlaylistRepeatMode.ONE
                PlaylistRepeatMode.ONE -> PlaylistRepeatMode.OFF
            }
            it.copy(repeatMode = next)
        }
    }

    companion object {
        fun provideFactory(repository: PlaylistRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlaylistViewModel(repository) as T
                }
            }
    }
}

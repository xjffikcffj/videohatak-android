package com.videohatak.player.core.playlist.repository

import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    val playlists: Flow<List<Playlist>>

    suspend fun refresh()
    suspend fun createPlaylist(title: String): Long
    suspend fun renamePlaylist(id: Long, newTitle: String): Boolean
    suspend fun deletePlaylist(id: Long): Boolean
    suspend fun getPlaylistItems(playlistId: Long): List<PlaylistItem>
    suspend fun getPlaylistWithItems(playlistId: Long): Pair<Playlist, List<PlaylistItem>>?
    suspend fun addVideoToPlaylists(video: VideoItem, playlistIds: List<Long>)
    suspend fun removeVideoFromPlaylist(itemId: Long, playlistId: Long)
    suspend fun moveItem(playlistId: Long, fromIndex: Int, toIndex: Int)
    suspend fun relinkMissingItems(playlistId: Long): Int
}

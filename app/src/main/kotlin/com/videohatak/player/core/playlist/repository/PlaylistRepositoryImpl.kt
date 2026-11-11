package com.videohatak.player.core.playlist.repository

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class PlaylistRepositoryImpl(
    private val context: Context,
    private val dbHelper: VideoHatakDatabaseHelper = VideoHatakDatabaseHelper(context),
    private val videoRepository: VideoRepository = LocalVideoRepositoryImpl(context)
) : PlaylistRepository {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    override val playlists: Flow<List<Playlist>> = _playlists.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = dbHelper.getPlaylists()
        _playlists.update { list }
    }

    override suspend fun createPlaylist(title: String): Long = withContext(Dispatchers.IO) {
        val id = dbHelper.insertPlaylist(title.trim().ifBlank { "قائمة جديدة" })
        refresh()
        id
    }

    override suspend fun renamePlaylist(id: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val rows = dbHelper.updatePlaylist(id, newTitle.trim())
        refresh()
        rows > 0
    }

    override suspend fun deletePlaylist(id: Long): Boolean = withContext(Dispatchers.IO) {
        val rows = dbHelper.deletePlaylist(id)
        refresh()
        rows > 0
    }

    override suspend fun getPlaylistItems(playlistId: Long): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val rawItems = dbHelper.getItemsForPlaylist(playlistId)
        rawItems.map { item ->
            val exists = isMediaAvailable(Uri.parse(item.contentUri))
            if (item.isAvailable != exists) {
                dbHelper.updateItemAvailability(item.id, exists)
            }
            item.copy(isAvailable = exists)
        }
    }

    override suspend fun getPlaylistWithItems(playlistId: Long): Pair<Playlist, List<PlaylistItem>>? = withContext(Dispatchers.IO) {
        val playlist = dbHelper.getPlaylists().find { it.id == playlistId } ?: return@withContext null
        val items = getPlaylistItems(playlistId)
        Pair(playlist, items)
    }

    override suspend fun addVideoToPlaylists(video: VideoItem, playlistIds: List<Long>) = withContext(Dispatchers.IO) {
        for (pId in playlistIds) {
            val existing = dbHelper.getItemsForPlaylist(pId)
            val alreadyAdded = existing.any { it.videoId == video.id }
            if (!alreadyAdded) {
                val nextOrder = existing.size
                val item = PlaylistItem(
                    id = 0L,
                    playlistId = pId,
                    videoId = video.id,
                    orderIndex = nextOrder,
                    videoTitle = video.safeTitle,
                    durationMs = video.durationMs,
                    sizeBytes = video.sizeBytes,
                    dateAddedSec = video.dateAddedSec,
                    contentUri = video.contentUri.toString(),
                    isAvailable = true
                )
                dbHelper.insertItem(item)
            }
        }
        refresh()
    }

    override suspend fun removeVideoFromPlaylist(itemId: Long, playlistId: Long) = withContext(Dispatchers.IO) {
        dbHelper.deleteItem(itemId, playlistId)
        // Re-index remaining items
        val remaining = dbHelper.getItemsForPlaylist(playlistId)
        remaining.forEachIndexed { index, item ->
            if (item.orderIndex != index) {
                dbHelper.updateItemOrder(item.id, index)
            }
        }
        refresh()
    }

    override suspend fun moveItem(playlistId: Long, fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        val items = dbHelper.getItemsForPlaylist(playlistId).toMutableList()
        if (fromIndex in items.indices && toIndex in items.indices && fromIndex != toIndex) {
            val item = items.removeAt(fromIndex)
            items.add(toIndex, item)
            items.forEachIndexed { idx, itm ->
                dbHelper.updateItemOrder(itm.id, idx)
            }
            refresh()
        }
    }

    /**
     * AUD-06 & AUD-07: Relink missing items using multi-criteria matching.
     * Skips vault:// URIs safely.
     */
    override suspend fun relinkMissingItems(playlistId: Long): Int = withContext(Dispatchers.IO) {
        val items = dbHelper.getItemsForPlaylist(playlistId)
        val allVideos = videoRepository.getVideos()
        var relinkedCount = 0

        for (item in items) {
            val uri = Uri.parse(item.contentUri)
            if (uri.scheme == "vault") continue
            if (!isMediaAvailable(uri)) {
                val match = allVideos.find { candidate ->
                    val nameMatch = candidate.displayName.equals(item.videoTitle, ignoreCase = true) ||
                            candidate.safeTitle.equals(item.videoTitle, ignoreCase = true)
                    val durationMatch = abs(candidate.durationMs - item.durationMs) < 1500L
                    nameMatch && durationMatch
                } ?: allVideos.find { candidate ->
                    candidate.safeTitle.equals(item.videoTitle, ignoreCase = true) &&
                            abs(candidate.durationMs - item.durationMs) < 2500L
                }

                if (match != null) {
                    dbHelper.updateItemUri(item.id, match.contentUri.toString())
                    relinkedCount++
                }
            }
        }
        if (relinkedCount > 0) {
            refresh()
        }
        relinkedCount
    }

    private fun isMediaAvailable(uri: Uri): Boolean {
        return try {
            if (uri.scheme == "vault") {
                return true
            }
            if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                return file.exists()
            }
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
}

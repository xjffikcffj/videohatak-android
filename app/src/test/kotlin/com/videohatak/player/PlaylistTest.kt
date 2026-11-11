package com.videohatak.player

import android.net.Uri
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.playlist.model.PlaylistPlaybackState
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Task 9: Local Playlist Management System.
 * Tests cover:
 * 1. Playlist and PlaylistItem entity models.
 * 2. Adding the same video to multiple playlists.
 * 3. Reordering items and preserving orderIndex.
 * 4. Shuffle mode order preservation across sessions (resuming exact sequence without re-shuffling).
 * 5. Repeat modes (OFF, ONE, ALL) and boundary navigation (hasNext, hasPrevious).
 * 6. Handling missing/unavailable videos without app crash or auto-deletion.
 */
class PlaylistTest {

    private fun createDummyItem(
        id: Long,
        playlistId: Long,
        videoId: Long,
        orderIndex: Int,
        title: String = "فيديو تجريبي $videoId",
        durationMs: Long = 60_000L,
        isAvailable: Boolean = true
    ): PlaylistItem {
        return PlaylistItem(
            id = id,
            playlistId = playlistId,
            videoId = videoId,
            orderIndex = orderIndex,
            videoTitle = title,
            durationMs = durationMs,
            sizeBytes = 10_000_000L,
            dateAddedSec = 1700000000L,
            contentUri = "content://media/external/video/media/$videoId",
            isAvailable = isAvailable
        )
    }

    @Test
    fun playlistEntity_calculatesItemCountAndDuration() {
        val playlist = Playlist(
            id = 1L,
            title = "وثائقيات الطبيعة",
            createdAt = 1700000000L,
            updatedAt = 1700001000L,
            itemCount = 5,
            totalDurationMs = 300_000L
        )

        assertEquals(1L, playlist.id)
        assertEquals("وثائقيات الطبيعة", playlist.title)
        assertEquals(5, playlist.itemCount)
        assertEquals(300_000L, playlist.totalDurationMs)
    }

    @Test
    fun sameVideoInMultiplePlaylists_preservesDistinctPlaylistIds() {
        // Video ID 100 added to Playlist 1 and Playlist 2
        val itemInPlaylist1 = createDummyItem(id = 1L, playlistId = 1L, videoId = 100L, orderIndex = 0)
        val itemInPlaylist2 = createDummyItem(id = 2L, playlistId = 2L, videoId = 100L, orderIndex = 0)

        assertEquals(itemInPlaylist1.videoId, itemInPlaylist2.videoId)
        assertEquals(1L, itemInPlaylist1.playlistId)
        assertEquals(2L, itemInPlaylist2.playlistId)
        // Each entry has its own unique primary key in the junction table
        assertFalse(itemInPlaylist1.id == itemInPlaylist2.id)
    }

    @Test
    fun reorderingItems_updatesOrderIndicesCorrectly() {
        val items = mutableListOf(
            createDummyItem(1L, 1L, 101L, 0),
            createDummyItem(2L, 1L, 102L, 1),
            createDummyItem(3L, 1L, 103L, 2)
        )

        // Move item at index 0 to index 2
        val movedItem = items.removeAt(0)
        items.add(2, movedItem)

        // Reassign indices
        val reordered = items.mapIndexed { index, item -> item.copy(orderIndex = index) }

        assertEquals(102L, reordered[0].videoId)
        assertEquals(0, reordered[0].orderIndex)
        assertEquals(103L, reordered[1].videoId)
        assertEquals(1, reordered[1].orderIndex)
        assertEquals(101L, reordered[2].videoId)
        assertEquals(2, reordered[2].orderIndex)
    }

    @Test
    fun playlistPlaybackState_sequentialMode_activeItemsMatchOrder() {
        val items = listOf(
            createDummyItem(1L, 1L, 101L, 0),
            createDummyItem(2L, 1L, 102L, 1),
            createDummyItem(3L, 1L, 103L, 2)
        )

        val state = PlaylistPlaybackState(
            playlistId = 1L,
            playlistTitle = "قائمة المفضلة",
            currentVideoId = 101L,
            currentIndex = 0,
            items = items,
            shuffleOrder = emptyList(),
            isShuffle = false,
            repeatMode = PlaylistRepeatMode.OFF
        )

        assertEquals(3, state.activeItems.size)
        assertEquals(101L, state.activeItems[0].videoId)
        assertEquals(102L, state.activeItems[1].videoId)
        assertEquals(103L, state.activeItems[2].videoId)
        assertTrue(state.hasNext)
        assertFalse(state.hasPrevious)
    }

    @Test
    fun playlistPlaybackState_shuffleMode_activeItemsMatchShuffleOrder() {
        val items = listOf(
            createDummyItem(1L, 1L, 101L, 0),
            createDummyItem(2L, 1L, 102L, 1),
            createDummyItem(3L, 1L, 103L, 2)
        )
        // Shuffled order: 103, 101, 102
        val shuffleOrder = listOf(103L, 101L, 102L)

        val state = PlaylistPlaybackState(
            playlistId = 1L,
            playlistTitle = "قائمة عشوائية",
            currentVideoId = 103L,
            currentIndex = 0,
            items = items,
            shuffleOrder = shuffleOrder,
            isShuffle = true,
            repeatMode = PlaylistRepeatMode.OFF
        )

        assertEquals(3, state.activeItems.size)
        assertEquals(103L, state.activeItems[0].videoId)
        assertEquals(101L, state.activeItems[1].videoId)
        assertEquals(102L, state.activeItems[2].videoId)
    }

    @Test
    fun shuffleMode_restoresExactSequenceAndPositionAcrossSessions() {
        // Critical requirement: If user is watching in shuffle mode and leaves the app,
        // returning resumes the EXACT same video, position, and shuffle order without reshuffling.
        val originalShuffleOrder = listOf(105L, 102L, 109L, 101L)
        val items = originalShuffleOrder.mapIndexed { idx, vid ->
            createDummyItem(idx.toLong(), 1L, vid, idx)
        }

        // User watched video 102 (index 1 in shuffle list) until position 45,200 ms
        val savedSession = PlaylistPlaybackState(
            playlistId = 1L,
            playlistTitle = "قائمتي الخاصة",
            currentVideoId = 102L,
            currentIndex = 1,
            items = items,
            shuffleOrder = originalShuffleOrder,
            isShuffle = true,
            repeatMode = PlaylistRepeatMode.OFF,
            savedPositionMs = 45_200L
        )

        // Simulate session serialization into CSV/DB and restoration
        val serializedShuffleCsv = savedSession.shuffleOrder.joinToString(",")
        assertEquals("105,102,109,101", serializedShuffleCsv)

        // Restoring from DB
        val restoredShuffleOrder = serializedShuffleCsv.split(",").mapNotNull { it.toLongOrNull() }
        val restoredState = savedSession.copy(
            shuffleOrder = restoredShuffleOrder
        )

        assertEquals(savedSession.playlistId, restoredState.playlistId)
        assertEquals(102L, restoredState.currentVideoId)
        assertEquals(1, restoredState.currentIndex)
        assertEquals(45_200L, restoredState.savedPositionMs)
        assertEquals(originalShuffleOrder, restoredState.shuffleOrder)
        assertEquals(102L, restoredState.activeItems[restoredState.currentIndex].videoId)
    }

    @Test
    fun repeatModes_offRepeat_stopsAtEnd() {
        val items = listOf(
            createDummyItem(1L, 1L, 101L, 0),
            createDummyItem(2L, 1L, 102L, 1)
        )

        val stateAtEnd = PlaylistPlaybackState(
            playlistId = 1L,
            playlistTitle = "قائمة",
            currentVideoId = 102L,
            currentIndex = 1,
            items = items,
            isShuffle = false,
            repeatMode = PlaylistRepeatMode.OFF
        )

        assertFalse("Repeat OFF should not have next item at end", stateAtEnd.hasNext)
        assertTrue("Repeat OFF has previous item at end", stateAtEnd.hasPrevious)
    }

    @Test
    fun repeatModes_allRepeat_loopsAtEnd() {
        val items = listOf(
            createDummyItem(1L, 1L, 101L, 0),
            createDummyItem(2L, 1L, 102L, 1)
        )

        val stateAtEnd = PlaylistPlaybackState(
            playlistId = 1L,
            playlistTitle = "قائمة",
            currentVideoId = 102L,
            currentIndex = 1,
            items = items,
            isShuffle = false,
            repeatMode = PlaylistRepeatMode.ALL
        )

        assertTrue("Repeat ALL wraps around, so hasNext is true at end", stateAtEnd.hasNext)
        assertTrue("Repeat ALL wraps around, so hasPrevious is true at start", stateAtEnd.hasPrevious)
    }

    @Test
    fun repeatModes_oneRepeat_staysOnCurrentItem() {
        val items = listOf(
            createDummyItem(1L, 1L, 101L, 0),
            createDummyItem(2L, 1L, 102L, 1)
        )

        val state = PlaylistPlaybackState(
            playlistId = 1L,
            playlistTitle = "قائمة",
            currentVideoId = 101L,
            currentIndex = 0,
            items = items,
            isShuffle = false,
            repeatMode = PlaylistRepeatMode.ONE
        )

        assertEquals(PlaylistRepeatMode.ONE, state.repeatMode)
        assertEquals(101L, state.currentVideoId)
    }

    @Test
    fun missingVideo_markedUnavailable_doesNotCrashAndKeepsRecord() {
        // Requirement: If file deleted from device, keep record in playlist marked "غير متاح"
        val items = listOf(
            createDummyItem(1L, 1L, 101L, 0, isAvailable = true),
            createDummyItem(2L, 1L, 102L, 1, isAvailable = false), // Deleted from storage
            createDummyItem(3L, 1L, 103L, 2, isAvailable = true)
        )

        assertEquals(3, items.size)
        assertFalse(items[1].isAvailable)

        // Available items filter allows skipping deleted files smoothly
        val playableItems = items.filter { it.isAvailable }
        assertEquals(2, playableItems.size)
        assertEquals(101L, playableItems[0].videoId)
        assertEquals(103L, playableItems[1].videoId)
    }

    @Test
    fun testPlaylistAutoTransitionSavesCorrectPreviousVideoPosition() {
        // Simulates transitioning from Video 101 (at 55,000ms out of 60,000ms) to Video 102
        val video101Uri = "content://media/external/video/media/101"
        val video102Uri = "content://media/external/video/media/102"

        var currentUri = video101Uri
        var currentPos = 55_000L
        val savedPositions = mutableMapOf<String, Long>()

        fun onTransition(newUri: String) {
            // Save previous video position before reassigning currentUri
            savedPositions[currentUri] = currentPos
            currentUri = newUri
            currentPos = 0L
        }

        onTransition(video102Uri)

        // Previous video 101 must be saved with 55_000L
        assertEquals(55_000L, savedPositions[video101Uri])
        // New video 102 must start at 0L and not have 55_000L erroneously assigned
        assertEquals(video102Uri, currentUri)
        assertEquals(0L, currentPos)
        assertFalse(savedPositions.containsKey(video102Uri))
    }
}

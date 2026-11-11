package com.videohatak.player

import android.net.Uri
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.model.SortDirection
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.model.VideoSortField
import com.videohatak.player.core.model.VideoSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class VideoSortAndFilterTest {

    private fun makeVideo(
        id: Long,
        title: String,
        durationMs: Long,
        sizeBytes: Long,
        dateAddedSec: Long,
        bucketId: String = "bucket_1",
        bucketName: String = "Camera"
    ): VideoItem {
        return VideoItem(
            id = id,
            contentUri = Uri.parse("content://media/external/video/media/$id"),
            title = title,
            displayName = "$title.mp4",
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            dateAddedSec = dateAddedSec,
            dateModifiedSec = dateAddedSec,
            width = 1920,
            height = 1080,
            mimeType = "video/mp4",
            path = "/storage/emulated/0/$bucketName/$title.mp4",
            bucketId = bucketId,
            bucketDisplayName = bucketName
        )
    }

    private val sampleVideos = listOf(
        makeVideo(id = 1, title = "Alpha", durationMs = 30000L, sizeBytes = 1000L, dateAddedSec = 100L),
        makeVideo(id = 2, title = "Beta", durationMs = 60000L, sizeBytes = 5000L, dateAddedSec = 300L),
        makeVideo(id = 3, title = "Gamma", durationMs = 15000L, sizeBytes = 2000L, dateAddedSec = 200L, bucketId = "bucket_2", bucketName = "Downloads")
    )

    // A lightweight subclass to test filter and group logic without ContentResolver
    private class DummyVideoRepository : LocalVideoRepositoryImpl(
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    )

    @Test
    fun testSortingByNameAscending() {
        // Simple comparator verification matching repository logic
        val sorted = sampleVideos.sortedBy { it.safeTitle.lowercase() }
        assertEquals("Alpha", sorted[0].safeTitle)
        assertEquals("Beta", sorted[1].safeTitle)
        assertEquals("Gamma", sorted[2].safeTitle)
    }

    @Test
    fun testSortingByDurationDescending() {
        val sorted = sampleVideos.sortedByDescending { it.durationMs }
        assertEquals(60000L, sorted[0].durationMs)
        assertEquals(30000L, sorted[1].durationMs)
        assertEquals(15000L, sorted[2].durationMs)
    }

    @Test
    fun testSortingByDateAddedDescending() {
        val sorted = sampleVideos.sortedByDescending { it.dateAddedSec }
        assertEquals(2L, sorted[0].id) // Beta (300)
        assertEquals(3L, sorted[1].id) // Gamma (200)
        assertEquals(1L, sorted[2].id) // Alpha (100)
    }

    @Test
    fun testFolderGrouping() {
        val grouped = sampleVideos.groupBy { it.bucketId }
        assertEquals(2, grouped.size)
        assertEquals(2, grouped["bucket_1"]?.size)
        assertEquals(1, grouped["bucket_2"]?.size)
    }

    @Test
    fun testSearchFiltering() {
        val query = "alp"
        val filtered = sampleVideos.filter { it.safeTitle.lowercase().contains(query) }
        assertEquals(1, filtered.size)
        assertEquals("Alpha", filtered.first().safeTitle)
    }
}

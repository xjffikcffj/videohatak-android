
package com.videohatak.player

import android.net.Uri
import com.videohatak.player.core.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
        makeVideo(1, "Alpha", 30000L, 1000L, 100L),
        makeVideo(2, "Beta", 60000L, 5000L, 300L),
        makeVideo(3, "Gamma", 15000L, 2000L, 200L, "bucket_2", "Downloads")
    )

    @Test
    fun testSortingByNameAscending() {
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
        assertEquals(2L, sorted[0].id)
        assertEquals(3L, sorted[1].id)
        assertEquals(1L, sorted[2].id)
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
        val filtered = sampleVideos.filter {
            it.safeTitle.lowercase().contains(query)
        }
        assertEquals(1, filtered.size)
        assertEquals("Alpha", filtered.first().safeTitle)
    }
}

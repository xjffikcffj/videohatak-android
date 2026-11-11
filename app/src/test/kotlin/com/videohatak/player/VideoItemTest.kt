package com.videohatak.player

import android.net.Uri
import com.videohatak.player.core.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class VideoItemTest {

    private fun createSampleVideo(
        id: Long = 1L,
        title: String = "Test Video",
        displayName: String = "test.mp4",
        durationMs: Long = 125000L, // 2m 5s
        sizeBytes: Long = 10485760L, // 10 MB
        width: Int = 1920,
        height: Int = 1080
    ): VideoItem {
        return VideoItem(
            id = id,
            contentUri = Uri.parse("content://media/external/video/media/$id"),
            title = title,
            displayName = displayName,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            dateAddedSec = 1700000000L,
            dateModifiedSec = 1700000000L,
            width = width,
            height = height,
            mimeType = "video/mp4",
            path = "/storage/emulated/0/DCIM/Camera/test.mp4",
            bucketId = "camera_123",
            bucketDisplayName = "Camera"
        )
    }

    @Test
    fun testDurationFormatting() {
        // Zero duration
        val zero = createSampleVideo(durationMs = 0L)
        assertEquals("00:00", zero.formattedDuration)

        // Under 1 hour: 2 minutes 5 seconds
        val shortVid = createSampleVideo(durationMs = 125000L)
        assertEquals("02:05", shortVid.formattedDuration)

        // Over 1 hour: 1 hour, 15 minutes, 30 seconds
        val longDurationMs = (1 * 3600 + 15 * 60 + 30) * 1000L
        val longVid = createSampleVideo(durationMs = longDurationMs)
        assertEquals("01:15:30", longVid.formattedDuration)
    }

    @Test
    fun testSizeFormatting() {
        val zeroSize = createSampleVideo(sizeBytes = 0L)
        assertEquals("0 بايت", zeroSize.formattedSize)

        val mbSize = createSampleVideo(sizeBytes = 15 * 1024 * 1024L)
        assertEquals("15.0 ميجابايت", mbSize.formattedSize)

        val gbSize = createSampleVideo(sizeBytes = (2.5 * 1024 * 1024 * 1024L).toLong())
        assertEquals("2.5 جيجابايت", gbSize.formattedSize)
    }

    @Test
    fun testResolutionTag() {
        val fhd = createSampleVideo(width = 1920, height = 1080)
        assertEquals("1080p", fhd.resolutionTag)

        val uhd = createSampleVideo(width = 3840, height = 2160)
        assertEquals("4K", uhd.resolutionTag)

        val hd = createSampleVideo(width = 1280, height = 720)
        assertEquals("720p", hd.resolutionTag)

        val sd = createSampleVideo(width = 640, height = 480)
        assertEquals("480p", sd.resolutionTag)
    }

    @Test
    fun testSafeTitleFallback() {
        val withTitle = createSampleVideo(title = "My Lecture", displayName = "lecture.mp4")
        assertEquals("My Lecture", withTitle.safeTitle)

        val withoutTitle = createSampleVideo(title = "", displayName = "vacation.mp4")
        assertEquals("vacation.mp4", withoutTitle.safeTitle)

        val blankBoth = createSampleVideo(title = "   ", displayName = "")
        assertEquals("فيديو محلي", blankBoth.safeTitle)
    }
}

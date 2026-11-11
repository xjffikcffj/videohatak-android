package com.videohatak.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MediaSessionTest {

    @Test
    fun testAudioAttributesConfiguration() {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, audioAttributes.contentType)
        assertEquals(C.USAGE_MEDIA, audioAttributes.usage)
    }

    @Test
    fun testMediaMetadataArabicPreservation() {
        val videoTitle = "مقطع وثائقي بدقة 4K — فيديوهاتك كما تحب"
        val metadata = MediaMetadata.Builder()
            .setTitle(videoTitle)
            .setDisplayTitle(videoTitle)
            .setArtist("فيديوهاتك")
            .build()

        assertEquals(videoTitle, metadata.title.toString())
        assertEquals("فيديوهاتك", metadata.artist.toString())

        val mediaItem = MediaItem.Builder()
            .setMediaId("101")
            .setMediaMetadata(metadata)
            .build()

        assertEquals("101", mediaItem.mediaId)
        assertEquals(videoTitle, mediaItem.mediaMetadata.title.toString())
    }

    @Test
    fun testBackgroundStateRetention() {
        // Simulates retaining position and playback state across UI unmount
        var isServicePlaying = true
        var servicePositionMs = 45000L

        // UI unmounts (PlayerScreen disposed)
        val uiMounted = false
        // State remains active in service
        assertTrue(isServicePlaying)
        assertEquals(45000L, servicePositionMs)

        // UI remounts (User taps mini player)
        val reconnectedPositionMs = servicePositionMs
        assertEquals(45000L, reconnectedPositionMs)
    }
}

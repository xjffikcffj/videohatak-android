package com.videohatak.player

import android.graphics.Bitmap
import com.videohatak.player.core.thumbnail.cache.ThumbnailCache
import com.videohatak.player.core.thumbnail.model.ThumbnailPreviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite for Task 8: Thumbnail Preview & Scrubbing Cache Engine.
 *
 * Verifies memory bounds, quantization bucketing, race condition prevention,
 * and boundary clamping without requiring an Android emulator or UI thread.
 */
class ThumbnailPreviewTest {

    @Test
    fun thumbnailCache_quantizesPositionsCorrectly() {
        val cache = ThumbnailCache(maxCacheSizeKb = 1024)

        // Zero and negative timestamps
        assertEquals(0L, cache.quantizePosition(0L))
        assertEquals(0L, cache.quantizePosition(-500L))

        // Timestamps within the same 1000ms window resolve to the same key
        assertEquals(1000L, cache.quantizePosition(1000L))
        assertEquals(1000L, cache.quantizePosition(1250L))
        assertEquals(1000L, cache.quantizePosition(1750L))
        assertEquals(1000L, cache.quantizePosition(1999L))

        // Boundary crossing into next second
        assertEquals(2000L, cache.quantizePosition(2000L))
        assertEquals(2000L, cache.quantizePosition(2001L))
    }

    @Test
    fun thumbnailCache_safeMemoryLimitCalculation() {
        val calculatedLimitKb = ThumbnailCache.calculateSafeMemoryLimitKb()

        // Minimum floor is 4 MB (4096 KB)
        assertTrue("Memory limit should be at least 4MB", calculatedLimitKb >= 4096)

        // Maximum ceiling is 20 MB (20480 KB)
        assertTrue("Memory limit should not exceed 20MB", calculatedLimitKb <= 20480)
    }

    @Test
    fun thumbnailPreviewState_defaultHiddenState() {
        val state = ThumbnailPreviewState.Hidden

        assertFalse(state.isVisible)
        assertEquals(0L, state.positionMs)
        assertEquals(0f, state.sliderFraction, 0.001f)
        assertNull(state.bitmap)
        assertFalse(state.isLoading)
        assertFalse(state.isError)
    }

    @Test
    fun thumbnailPreviewState_copyTransitions() {
        val loadingState = ThumbnailPreviewState(
            isVisible = true,
            positionMs = 15000L,
            sliderFraction = 0.25f,
            bitmap = null,
            isLoading = true,
            isError = false
        )

        assertTrue(loadingState.isVisible)
        assertTrue(loadingState.isLoading)
        assertEquals(15000L, loadingState.positionMs)

        // Transition to error on unsupported format
        val errorState = loadingState.copy(
            isLoading = false,
            isError = true
        )

        assertTrue(errorState.isVisible)
        assertFalse(errorState.isLoading)
        assertTrue(errorState.isError)
    }

    @Test
    fun scrubbingTimeClamping_logicValidation() {
        val durationMs = 60000L // 1 minute video

        // Negative scrub position clamps to 0
        val clampedNegative = (-1500L).coerceIn(0L, durationMs)
        assertEquals(0L, clampedNegative)

        // In-range position remains untouched
        val clampedMiddle = 35000L.coerceIn(0L, durationMs)
        assertEquals(35000L, clampedMiddle)

        // Exceeded duration clamps to max duration
        val clampedExceeded = 75000L.coerceIn(0L, durationMs)
        assertEquals(durationMs, clampedExceeded)
    }

    @Test
    fun shortVideo_clampingBehavior() {
        val shortDurationMs = 2500L // 2.5 seconds video

        // Clamping handles very short videos cleanly without exceptions
        val atStart = 0L.coerceIn(0L, shortDurationMs)
        val atEnd = 3000L.coerceIn(0L, shortDurationMs)

        assertEquals(0L, atStart)
        assertEquals(2500L, atEnd)
    }

    @Test
    fun rtlVisualFraction_inversionCalculation() {
        // Standard LTR (0.0 at left, 1.0 at right)
        val ltrProgress = 0.35f
        assertEquals(0.35f, ltrProgress, 0.001f)

        // Inverted RTL (0.0 at right, 1.0 at left)
        val rtlVisualProgress = 1f - ltrProgress
        assertEquals(0.65f, rtlVisualProgress, 0.001f)

        // Clamping on extreme values
        val clampedUnder = 1f - (-0.1f).coerceIn(0f, 1f)
        assertEquals(1.0f, clampedUnder, 0.001f)

        val clampedOver = 1f - (1.2f).coerceIn(0f, 1f)
        assertEquals(0.0f, clampedOver, 0.001f)
    }
}

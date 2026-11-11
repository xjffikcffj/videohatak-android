package com.videohatak.player

import androidx.media3.ui.AspectRatioFrameLayout
import com.videohatak.player.core.playback.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PlayerUiStateTest {

    @Test
    fun testTimeFormatting() {
        // Zero position
        val zeroState = PlayerUiState(currentPositionMs = 0L, durationMs = 0L)
        assertEquals("00:00", zeroState.formattedCurrentTime)
        assertEquals("00:00", zeroState.formattedTotalDuration)

        // Under an hour (4 minutes 12 seconds)
        val underHourState = PlayerUiState(
            currentPositionMs = (4 * 60 + 12) * 1000L,
            durationMs = (15 * 60 + 30) * 1000L
        )
        assertEquals("04:12", underHourState.formattedCurrentTime)
        assertEquals("15:30", underHourState.formattedTotalDuration)

        // Over an hour (1 hour, 22 minutes, 45 seconds)
        val overHourState = PlayerUiState(
            currentPositionMs = (1 * 3600 + 22 * 60 + 45) * 1000L,
            durationMs = (2 * 3600 + 10 * 60) * 1000L
        )
        assertEquals("01:22:45", overHourState.formattedCurrentTime)
        assertEquals("02:10:00", overHourState.formattedTotalDuration)
    }

    @Test
    fun testProgressFraction() {
        val halfWay = PlayerUiState(
            currentPositionMs = 50_000L,
            durationMs = 100_000L
        )
        assertEquals(0.5f, halfWay.progressFraction, 0.001f)

        val overflow = PlayerUiState(
            currentPositionMs = 150_000L,
            durationMs = 100_000L
        )
        assertEquals(1.0f, overflow.progressFraction, 0.001f)

        val zeroDuration = PlayerUiState(
            currentPositionMs = 10_000L,
            durationMs = 0L
        )
        assertEquals(0f, zeroDuration.progressFraction, 0.001f)
    }

    @Test
    fun testResizeModeLabels() {
        val fit = PlayerUiState(resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT)
        assertEquals("ملاءمة (Fit)", fit.resizeModeLabelArabic)

        val zoom = PlayerUiState(resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
        assertEquals("تكبير (Zoom)", zoom.resizeModeLabelArabic)

        val fill = PlayerUiState(resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL)
        assertEquals("تمديد (Stretch)", fill.resizeModeLabelArabic)
    }
}

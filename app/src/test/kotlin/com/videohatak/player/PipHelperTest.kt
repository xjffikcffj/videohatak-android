package com.videohatak.player

import android.util.Rational
import com.videohatak.player.core.playback.pip.PipHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PipHelperTest {

    @Test
    fun testStandardAspectRatios() {
        // 16:9 standard video
        val ratio169 = PipHelper.getValidAspectRatio(1920, 1080)
        assertEquals(16f / 9f, ratio169.toFloat(), 0.01f)

        // 4:3 classic video
        val ratio43 = PipHelper.getValidAspectRatio(640, 480)
        assertEquals(4f / 3f, ratio43.toFloat(), 0.01f)

        // 9:16 vertical reels/shorts video (0.5625, valid within 0.418..2.39)
        val ratio916 = PipHelper.getValidAspectRatio(1080, 1920)
        assertEquals(9f / 16f, ratio916.toFloat(), 0.01f)
    }

    @Test
    fun testClampedAspectRatios() {
        // Extreme ultra-wide: 3840x1000 (ratio 3.84 > 2.39) -> must clamp to 239/100
        val ultraWide = PipHelper.getValidAspectRatio(3840, 1000)
        assertEquals(2.39f, ultraWide.toFloat(), 0.01f)

        // Extreme ultra-tall: 500x2500 (ratio 0.20 < 1/2.39) -> must clamp to 100/239
        val ultraTall = PipHelper.getValidAspectRatio(500, 2500)
        assertEquals(1f / 2.39f, ultraTall.toFloat(), 0.01f)
    }

    @Test
    fun testInvalidDimensionsFallback() {
        val zeroZero = PipHelper.getValidAspectRatio(0, 0)
        assertEquals(Rational(16, 9), zeroZero)

        val negative = PipHelper.getValidAspectRatio(-1920, -1080)
        assertEquals(Rational(16, 9), negative)
    }
}

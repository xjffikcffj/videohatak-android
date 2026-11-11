
package com.videohatak.player

import android.util.Rational
import com.videohatak.player.core.playback.pip.PipHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PipHelperTest {

    @Test
    fun testStandardAspectRatios() {
        val ratio169 = PipHelper.getValidAspectRatio(1920, 1080)
        assertEquals(16f / 9f, ratio169.toFloat(), 0.01f)

        val ratio43 = PipHelper.getValidAspectRatio(640, 480)
        assertEquals(4f / 3f, ratio43.toFloat(), 0.01f)

        val ratio916 = PipHelper.getValidAspectRatio(1080, 1920)
        assertEquals(9f / 16f, ratio916.toFloat(), 0.01f)
    }

    @Test
    fun testClampedAspectRatios() {
        val ultraWide = PipHelper.getValidAspectRatio(3840, 1000)
        assertEquals(2.39f, ultraWide.toFloat(), 0.01f)

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

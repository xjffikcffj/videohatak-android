package com.videohatak.player.core.thumbnail.model

import android.graphics.Bitmap

data class ThumbnailPreviewState(
    val isVisible: Boolean = false,
    val positionMs: Long = 0L,
    val sliderFraction: Float = 0f,
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val xOffsetFraction: Float = sliderFraction
) {
    companion object {
        val Hidden = ThumbnailPreviewState()
    }
}

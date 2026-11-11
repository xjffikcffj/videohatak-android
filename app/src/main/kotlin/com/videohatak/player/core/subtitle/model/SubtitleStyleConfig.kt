package com.videohatak.player.core.subtitle.model

import androidx.compose.ui.graphics.Color

data class SubtitleStyleConfig(
    val fontSizeSp: Float = 18f,
    val textColor: Color = Color.White,
    val backgroundColor: Color = Color(0x99000000),
    val bottomPaddingDp: Float = 48f
)

package com.videohatak.player.feature.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig

@Composable
fun DualSubtitlesOverlay(
    primaryCues: List<SubtitleCue>,
    secondaryCues: List<SubtitleCue>,
    primaryStyle: SubtitleStyleConfig,
    secondaryStyle: SubtitleStyleConfig,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = primaryStyle.bottomPaddingDp.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Secondary Subtitle (above primary)
            if (secondaryCues.isNotEmpty()) {
                val secondaryText = secondaryCues.joinToString("\n") { it.text }
                Box(
                    modifier = Modifier
                        .background(secondaryStyle.backgroundColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = secondaryText,
                        fontSize = secondaryStyle.fontSizeSp.sp,
                        color = secondaryStyle.textColor,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Primary Subtitle (bottom)
            if (primaryCues.isNotEmpty()) {
                val primaryText = primaryCues.joinToString("\n") { it.text }
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(primaryStyle.backgroundColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = primaryText,
                        fontSize = primaryStyle.fontSizeSp.sp,
                        color = primaryStyle.textColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

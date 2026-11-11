package com.videohatak.player.feature.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import com.videohatak.player.core.subtitle.model.SubtitleTrack

@Composable
fun SubtitleManagerDialog(
    availableTracks: List<SubtitleTrack>,
    primaryTrack: SubtitleTrack?,
    secondaryTrack: SubtitleTrack?,
    primaryStyle: SubtitleStyleConfig,
    secondaryStyle: SubtitleStyleConfig,
    onSelectPrimaryTrack: (SubtitleTrack?) -> Unit,
    onSelectSecondaryTrack: (SubtitleTrack?) -> Unit,
    onImportSubtitle: () -> Unit,
    onChangePrimaryFontSize: (Float) -> Unit,
    onChangeSecondaryFontSize: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "إدارة الترجمات المزدوجة",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Button(
                    onClick = onImportSubtitle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(" استيراد ملف ترجمة (SRT / VTT)")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "الترجمة الأساسية (السفلية)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                TrackSelectionRow(
                    title = "بدون ترجمة",
                    isSelected = primaryTrack == null,
                    onClick = { onSelectPrimaryTrack(null) }
                )

                availableTracks.forEach { track ->
                    TrackSelectionRow(
                        title = track.name,
                        isSelected = primaryTrack?.id == track.id,
                        onClick = { onSelectPrimaryTrack(track) }
                    )
                }

                Text(
                    text = "حجم خط الأساسية: ${primaryStyle.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = primaryStyle.fontSizeSp,
                    onValueChange = onChangePrimaryFontSize,
                    valueRange = 12f..32f
                )

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = "الترجمة الثانوية (العلوية)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )

                TrackSelectionRow(
                    title = "بدون ترجمة ثانوية",
                    isSelected = secondaryTrack == null,
                    onClick = { onSelectSecondaryTrack(null) }
                )

                availableTracks.forEach { track ->
                    TrackSelectionRow(
                        title = track.name,
                        isSelected = secondaryTrack?.id == track.id,
                        onClick = { onSelectSecondaryTrack(track) }
                    )
                }

                Text(
                    text = "حجم خط الثانوية: ${secondaryStyle.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = secondaryStyle.fontSizeSp,
                    onValueChange = onChangeSecondaryFontSize,
                    valueRange = 12f..32f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        }
    )
}

@Composable
private fun TrackSelectionRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

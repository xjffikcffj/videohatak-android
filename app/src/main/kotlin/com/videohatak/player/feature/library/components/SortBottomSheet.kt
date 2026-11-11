package com.videohatak.player.feature.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.model.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortBottomSheet(
    currentSort: SortOrder,
    onSelectSort: (SortOrder) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "ترتيب حسب",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            SortOptionItem(
                title = "التاريخ (الأحدث أولاً)",
                isSelected = currentSort == SortOrder.DATE_DESC,
                onClick = { onSelectSort(SortOrder.DATE_DESC) }
            )

            SortOptionItem(
                title = "التاريخ (الأقدم أولاً)",
                isSelected = currentSort == SortOrder.DATE_ASC,
                onClick = { onSelectSort(SortOrder.DATE_ASC) }
            )

            SortOptionItem(
                title = "الاسم (أ - ي)",
                isSelected = currentSort == SortOrder.NAME_ASC,
                onClick = { onSelectSort(SortOrder.NAME_ASC) }
            )

            SortOptionItem(
                title = "الاسم (ي - أ)",
                isSelected = currentSort == SortOrder.NAME_DESC,
                onClick = { onSelectSort(SortOrder.NAME_DESC) }
            )

            SortOptionItem(
                title = "الحجم (الأكبر أولاً)",
                isSelected = currentSort == SortOrder.SIZE_DESC,
                onClick = { onSelectSort(SortOrder.SIZE_DESC) }
            )

            SortOptionItem(
                title = "المدة (الأطول أولاً)",
                isSelected = currentSort == SortOrder.DURATION_DESC,
                onClick = { onSelectSort(SortOrder.DURATION_DESC) }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SortOptionItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
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

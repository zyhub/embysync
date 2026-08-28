package com.zds.embysync.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zds.embysync.core.model.SyncFilterCategory
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import com.zds.embysync.ui.theme.SyncBlue
import com.zds.embysync.ui.theme.SyncOrange

@Composable
fun SyncFilterChipsBar(
    selectedCategory: SyncFilterCategory,
    countsMap: Map<SyncFilterCategory, Int>,
    onCategorySelected: (SyncFilterCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SyncFilterCategory.values().forEach { category ->
            val isSelected = category == selectedCategory
            val count = countsMap[category] ?: 0

            val activeColor = when (category) {
                SyncFilterCategory.ALL -> MaterialTheme.colorScheme.onSurface
                SyncFilterCategory.SYNCED -> EmbyGreen
                SyncFilterCategory.NEED_DOWNLOAD -> SyncBlue
                SyncFilterCategory.DIFF_UPGRADE -> AppleRed
                SyncFilterCategory.IGNORED -> Color.Gray
            }

            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = category.label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) activeColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = count.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = activeColor.copy(alpha = 0.12f),
                    selectedLabelColor = activeColor
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

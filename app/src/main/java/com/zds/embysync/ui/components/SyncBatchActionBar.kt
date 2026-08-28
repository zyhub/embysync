package com.zds.embysync.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zds.embysync.core.model.SyncProgressState
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import com.zds.embysync.ui.theme.SyncBlue

@Composable
fun SyncBatchActionBar(
    diffCount: Int,
    isSelectionMode: Boolean,
    selectedCount: Int,
    progressState: SyncProgressState,
    onSyncAllDeltas: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onSyncSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onRescanCompare: () -> Unit,
    onOpenFilterRules: () -> Unit,
    onCancelSync: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // 同步中全局浮动进度条面板
        AnimatedVisibility(visible = progressState.isSyncing) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SyncBlue.copy(alpha = 0.1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        progress = { progressState.overallProgress },
                        modifier = Modifier.size(28.dp),
                        color = SyncBlue,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "正在同步 (${progressState.currentItemIndex}/${progressState.totalItems}): ${progressState.currentItemTitle}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val speedMb = String.format("%.2f MB/s", progressState.speedBytesPerSec / (1024.0 * 1024.0))
                        Text(
                            text = "实时速率: $speedMb • 总体进度: ${(progressState.overallProgress * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onCancelSync) {
                        Icon(Icons.Default.Close, contentDescription = "取消同步", tint = AppleRed)
                    }
                }
            }
        }

        // 工具栏按钮群
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // 一键同步差异主按钮
                Button(
                    onClick = onSyncAllDeltas,
                    enabled = diffCount > 0 && !progressState.isSyncing,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (diffCount > 0) "一键同步全部差异 ($diffCount)" else "曲目已全部同步",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 重新比对
                OutlinedButton(
                    onClick = onRescanCompare,
                    enabled = !progressState.isSyncing,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("全量比对", fontSize = 12.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isSelectionMode) {
                    Button(
                        onClick = onSyncSelected,
                        enabled = selectedCount > 0 && !progressState.isSyncing,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SyncBlue),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("同步选中 ($selectedCount)", fontSize = 12.sp)
                    }
                    TextButton(onClick = onSelectAll) {
                        Text("全选", fontSize = 12.sp)
                    }
                }

                IconButton(onClick = onToggleSelectionMode) {
                    Icon(
                        imageVector = if (isSelectionMode) Icons.Default.ChecklistRtl else Icons.Default.Checklist,
                        contentDescription = "批量选择",
                        tint = if (isSelectionMode) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onOpenFilterRules) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "过滤规则",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

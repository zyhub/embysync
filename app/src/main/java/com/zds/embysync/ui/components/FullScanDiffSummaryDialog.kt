package com.zds.embysync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zds.embysync.core.model.SongDiffResult
import com.zds.embysync.core.model.SyncComparisonSong
import com.zds.embysync.core.model.SyncStatus
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import com.zds.embysync.ui.theme.SyncBlue
import com.zds.embysync.ui.theme.SyncOrange

@Composable
fun FullScanDiffSummaryDialog(
    diffResult: SongDiffResult,
    onDismiss: () -> Unit,
    onSyncAllUnsynced: (List<SyncComparisonSong>) -> Unit
) {
    val unsyncedList = diffResult.allSongs.filter { it.syncStatus in listOf(SyncStatus.NEED_DOWNLOAD, SyncStatus.DIFF_UPGRADE) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AppleRed.copy(alpha = 0.12f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, tint = AppleRed, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "⚡ 全量曲库比对报告",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Emby 服务器与本地文件夹对比结果",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 四网格统计面板
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryCard(
                        title = "Emby 云端曲目",
                        count = diffResult.allSongs.size,
                        color = SyncBlue,
                        icon = Icons.Default.Cloud,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "本地已同步",
                        count = diffResult.syncedCount,
                        color = EmbyGreen,
                        icon = Icons.Default.CheckCircle,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryCard(
                        title = "待下载曲目",
                        count = diffResult.needDownloadCount,
                        color = SyncOrange,
                        icon = Icons.Default.Download,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "可升级高音质",
                        count = diffResult.diffUpgradeCount,
                        color = AppleRed,
                        icon = Icons.Default.Upgrade,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                // 结论提示
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("比对说明：", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (unsyncedList.isEmpty()) {
                                "🎉 太棒了！您的本地歌曲已与 Emby 服务器完全同步！"
                            } else {
                                "发现共有 ${unsyncedList.size} 首歌曲待同步下载或升级。点击下方按钮即可一键加入后台并发下载队列。"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 底部操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }

                    if (unsyncedList.isNotEmpty()) {
                        Button(
                            onClick = {
                                onSyncAllUnsynced(unsyncedList)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("一键全量同步 (${unsyncedList.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    count: Int,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$count 首",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

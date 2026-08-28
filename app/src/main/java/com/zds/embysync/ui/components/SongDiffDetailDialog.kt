package com.zds.embysync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.zds.embysync.core.model.SyncComparisonSong
import com.zds.embysync.core.model.SyncStatus
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import com.zds.embysync.ui.theme.SyncBlue
import com.zds.embysync.ui.theme.SyncOrange
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SongDiffDetailDialog(
    song: SyncComparisonSong,
    onDismiss: () -> Unit,
    onSyncNow: (SyncComparisonSong) -> Unit,
    onDeleteLocal: ((SyncComparisonSong) -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 头部曲目信息
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SongCoverThumbnail(
                        coverUrl = song.coverUrl,
                        localFilePath = song.localFilePath,
                        size = 60.dp,
                        cornerRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = song.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        val albumName = if (song.album.isNotBlank()) song.album else "单曲"
                        Text(text = "${song.artist} • $albumName", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // 双端参数对比卡片
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Emby 端
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SyncBlue.copy(alpha = 0.08f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("☁️ Emby 云端", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SyncBlue)
                            Spacer(modifier = Modifier.height(6.dp))
                            if (song.embyItemId != null) {
                                SpecItem("格式", (song.embyFormat ?: "未知").uppercase())
                                SpecItem("码率", "${song.embyBitRate ?: 320} kbps")
                                SpecItem("大小", String.format("%.2f MB", song.embyFileSize / (1024.0 * 1024.0)))
                                SpecItem("路径", song.embyRemotePath ?: "默认目录")
                            } else {
                                Text("云端未索引/缺失", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 本地端
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = EmbyGreen.copy(alpha = 0.08f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("💾 本地存储", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EmbyGreen)
                            Spacer(modifier = Modifier.height(6.dp))
                            if (!song.localFilePath.isNullOrBlank()) {
                                SpecItem("格式", (song.localFormat ?: "未知").uppercase())
                                SpecItem("码率", "${song.localBitRate ?: 320} kbps")
                                SpecItem("大小", String.format("%.2f MB", song.localFileSize / (1024.0 * 1024.0)))
                                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(song.localLastModified))
                                SpecItem("时间", dateStr)
                            } else {
                                Text("本地缺失 (未下载)", fontSize = 12.sp, color = SyncOrange)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 比对结论
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (song.syncStatus) {
                                SyncStatus.SYNCED -> Icons.Default.CheckCircle
                                SyncStatus.DIFF_UPGRADE -> Icons.Default.Upgrade
                                SyncStatus.NEED_DOWNLOAD -> Icons.Default.Download
                                SyncStatus.IGNORED -> Icons.Default.Block
                            },
                            contentDescription = null,
                            tint = when (song.syncStatus) {
                                SyncStatus.SYNCED -> EmbyGreen
                                SyncStatus.DIFF_UPGRADE -> AppleRed
                                SyncStatus.NEED_DOWNLOAD -> SyncBlue
                                SyncStatus.IGNORED -> Color.Gray
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = song.diffReason ?: "状态正常",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 底部动作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!song.localFilePath.isNullOrBlank() && onDeleteLocal != null) {
                        OutlinedButton(
                            onClick = {
                                onDeleteLocal(song)
                                onDismiss()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除本地", fontSize = 12.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (song.syncStatus in listOf(SyncStatus.NEED_DOWNLOAD, SyncStatus.DIFF_UPGRADE)) {
                            Button(
                                onClick = {
                                    onSyncNow(song)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (song.syncStatus == SyncStatus.DIFF_UPGRADE) AppleRed else SyncBlue
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (song.syncStatus == SyncStatus.DIFF_UPGRADE) "立即升级替换" else "立即下载同步")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

package com.zds.embysync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zds.embysync.core.model.SyncComparisonSong
import com.zds.embysync.core.model.SyncStatus
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import com.zds.embysync.ui.theme.SyncBlue
import com.zds.embysync.ui.theme.SyncOrange

@Composable
fun SyncSongCompareRow(
    song: SyncComparisonSong,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    onClick: () -> Unit = {},
    onQuickSync: (SyncComparisonSong) -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = if (isSelectionMode) onSelectToggle else onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectToggle() },
                    colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            // 封面与格式徽标 (高性能智能缩略图)
            Box(modifier = Modifier.size(52.dp)) {
                SongCoverThumbnail(
                    coverUrl = song.coverUrl,
                    localFilePath = song.localFilePath,
                    size = 52.dp,
                    cornerRadius = 8.dp
                )
                val formatText = (song.embyFormat ?: song.localFormat ?: "MP3").uppercase()
                Surface(
                    shape = RoundedCornerShape(topStart = 0.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 0.dp),
                    color = if (formatText in listOf("FLAC", "WAV", "DSD", "APE")) AppleRed else Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text(
                        text = if (formatText in listOf("FLAC", "WAV", "DSD")) "Hi-Res" else formatText,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 中间比对信息区
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.title,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = song.artist,
                        style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Emby 端参数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(3.dp), color = SyncBlue.copy(alpha = 0.12f)) {
                        Text("Emby", color = SyncBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    if (song.embyItemId != null) {
                        val sizeMb = String.format("%.1f MB", song.embyFileSize / (1024.0 * 1024.0))
                        Text(
                            text = "${song.embyFormat?.uppercase() ?: "音频"} • ${song.embyBitRate ?: 320}kbps • $sizeMb",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text("云端未索引", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // 本地端参数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(3.dp), color = EmbyGreen.copy(alpha = 0.12f)) {
                        Text("本地", color = EmbyGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    if (!song.localFilePath.isNullOrBlank()) {
                        val sizeMb = String.format("%.1f MB", song.localFileSize / (1024.0 * 1024.0))
                        Text(
                            text = "${song.localFormat?.uppercase() ?: "音频"} • ${song.localBitRate ?: 320}kbps • $sizeMb",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text("本地缺失 (未下载)", fontSize = 11.sp, color = SyncOrange)
                    }
                }

                if (song.syncStatus == SyncStatus.DIFF_UPGRADE && song.diffReason != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "⚡ ${song.diffReason}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleRed
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 右侧状态徽标与一键操作
            Column(horizontalAlignment = Alignment.End) {
                val (statusText, statusColor) = when (song.syncStatus) {
                    SyncStatus.SYNCED -> "已下载" to EmbyGreen
                    SyncStatus.NEED_DOWNLOAD -> "待下载" to SyncBlue
                    SyncStatus.DIFF_UPGRADE -> "可升级" to AppleRed
                    SyncStatus.IGNORED -> "已忽略" to Color.Gray
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                when (song.syncStatus) {
                    SyncStatus.NEED_DOWNLOAD -> {
                        Button(
                            onClick = { onQuickSync(song) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SyncBlue),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("下载", fontSize = 11.sp)
                        }
                    }
                    SyncStatus.DIFF_UPGRADE -> {
                        Button(
                            onClick = { onQuickSync(song) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Icon(Icons.Default.Upgrade, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("升级", fontSize = 11.sp)
                        }
                    }
                    SyncStatus.SYNCED -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "已同步",
                            tint = EmbyGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

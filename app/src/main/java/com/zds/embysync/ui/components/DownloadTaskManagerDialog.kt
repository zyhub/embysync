package com.zds.embysync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zds.embysync.core.engine.SyncEngine
import com.zds.embysync.core.model.DownloadTaskItem
import com.zds.embysync.core.model.TaskStatus
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import com.zds.embysync.ui.theme.SyncBlue
import com.zds.embysync.ui.theme.SyncOrange

private enum class TaskFilterTab(val label: String) {
    ALL("全部"),
    DOWNLOADING("下载中"),
    QUEUED("等待中"),
    PAUSED("已暂停"),
    COMPLETED("已完成")
}

@Composable
fun DownloadTaskManagerDialog(
    onDismiss: () -> Unit
) {
    val tasks by SyncEngine.tasksFlow.collectAsState()
    val progressState by SyncEngine.progressFlow.collectAsState()
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(TaskFilterTab.ALL) }

    val downloadingCount by remember(tasks) { derivedStateOf { tasks.count { it.status == TaskStatus.DOWNLOADING } } }
    val queuedCount by remember(tasks) { derivedStateOf { tasks.count { it.status == TaskStatus.QUEUED } } }
    val pausedCount by remember(tasks) { derivedStateOf { tasks.count { it.status == TaskStatus.PAUSED } } }
    val completedCount by remember(tasks) { derivedStateOf { tasks.count { it.status == TaskStatus.COMPLETED } } }
    val hasActiveTasks by remember(tasks) { derivedStateOf { downloadingCount > 0 || queuedCount > 0 } }

    val filteredTasks by remember(tasks, selectedTab) {
        derivedStateOf {
            when (selectedTab) {
                TaskFilterTab.ALL -> tasks
                TaskFilterTab.DOWNLOADING -> tasks.filter { it.status == TaskStatus.DOWNLOADING }
                TaskFilterTab.QUEUED -> tasks.filter { it.status == TaskStatus.QUEUED }
                TaskFilterTab.PAUSED -> tasks.filter { it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED }
                TaskFilterTab.COMPLETED -> tasks.filter { it.status == TaskStatus.COMPLETED }
            }
        }
    }

    val selectedTaskIds = remember(tasks) { tasks.filter { it.isSelected }.map { it.id }.toSet() }
    val isAllSelected = tasks.isNotEmpty() && selectedTaskIds.size == tasks.size

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(2.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // 弹窗顶栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = AppleRed.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = AppleRed, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("📥 同步下载队列", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            val speedKb = progressState.speedBytesPerSec / 1024
                            val speedText = if (speedKb > 1024) String.format("%.1f MB/s", speedKb / 1024.0) else "$speedKb KB/s"
                            Text(
                                text = if (hasActiveTasks) "实时网速: $speedText • 总任务数: ${tasks.size}" else "总任务数: ${tasks.size} • 待处理",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 控制工具栏 (全部暂停 / 全部继续 / 全部取消 / 清理已完成 / 多选)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hasActiveTasks) {
                            OutlinedButton(
                                onClick = { SyncEngine.pauseAll() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("全部暂停", fontSize = 11.sp)
                            }
                        } else if (pausedCount > 0) {
                            Button(
                                onClick = { SyncEngine.resumeAll() },
                                colors = ButtonDefaults.buttonColors(containerColor = EmbyGreen),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("全部继续", fontSize = 11.sp)
                            }
                        }

                        if (tasks.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { SyncEngine.cancelAll() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("清空队列", fontSize = 11.sp)
                            }
                        }

                        if (completedCount > 0) {
                            TextButton(
                                onClick = { SyncEngine.clearCompleted() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("清理已完成", fontSize = 11.sp)
                            }
                        }
                    }

                    // 多选开关
                    TextButton(
                        onClick = { isSelectionMode = !isSelectionMode },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(if (isSelectionMode) "退出多选" else "多选", fontSize = 11.sp, color = AppleRed)
                    }
                }

                // 2000+ 列表性能优化核心：分类过滤 Tab 栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TaskFilterTab.values().forEach { tab ->
                        val count = when (tab) {
                            TaskFilterTab.ALL -> tasks.size
                            TaskFilterTab.DOWNLOADING -> downloadingCount
                            TaskFilterTab.QUEUED -> queuedCount
                            TaskFilterTab.PAUSED -> pausedCount
                            TaskFilterTab.COMPLETED -> completedCount
                        }
                        val isSelected = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) AppleRed.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = if (isSelected) BorderStroke(0.8.dp, AppleRed.copy(alpha = 0.6f)) else null,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = tab }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = tab.label,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$count",
                                    fontSize = 9.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                // 多选操作栏
                if (isSelectionMode && tasks.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isAllSelected,
                                    onCheckedChange = { SyncEngine.setAllTasksSelected(it) },
                                    colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("全选 (${selectedTaskIds.size}/${tasks.size})", fontSize = 11.5.sp)
                            }

                            if (selectedTaskIds.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        SyncEngine.deleteTasks(selectedTaskIds)
                                        if (tasks.isEmpty()) isSelectionMode = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("删除 (${selectedTaskIds.size})", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))

                // 2000+ 超大队列丝滑列表 (使用 contentType 深度复用)
                if (filteredTasks.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("该分类下暂无任务", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(
                            items = filteredTasks,
                            key = { it.id },
                            contentType = { it.status }
                        ) { task ->
                            DownloadTaskRow(
                                task = task,
                                isSelectionMode = isSelectionMode,
                                onToggleSelect = { SyncEngine.toggleTaskSelection(task.id) },
                                onPause = { SyncEngine.pauseTask(task.id) },
                                onResume = { SyncEngine.resumeTask(task.id) },
                                onDelete = { SyncEngine.deleteTask(task.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTaskItem,
    isSelectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = task.isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            SongCoverThumbnail(
                coverUrl = task.song.coverUrl,
                localFilePath = task.song.localFilePath,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.song.title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${task.song.artist} • ${task.song.embyFormat?.uppercase() ?: "FLAC"}",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (task.status == TaskStatus.DOWNLOADING) {
                    Spacer(modifier = Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        color = AppleRed,
                        trackColor = AppleRed.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp))
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val speedKb = task.speedBytesPerSec / 1024
                    val speedText = if (speedKb > 1024) String.format("%.1f MB/s", speedKb / 1024.0) else "$speedKb KB/s"
                    Text(
                        text = "$speedText • ${String.format("%.1f", task.downloadedBytes / (1024.0 * 1024.0))}/${String.format("%.1f", task.totalBytes / (1024.0 * 1024.0))} MB",
                        fontSize = 9.5.sp,
                        color = AppleRed
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 状态徽标与单个任务快捷按键
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    TaskStatus.QUEUED -> {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SyncOrange.copy(alpha = 0.15f)
                        ) {
                            Text("等待中", fontSize = 10.sp, color = SyncOrange, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                    TaskStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = "暂停", tint = AppleRed, modifier = Modifier.size(16.dp))
                        }
                    }
                    TaskStatus.PAUSED -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "继续", tint = EmbyGreen, modifier = Modifier.size(16.dp))
                        }
                    }
                    TaskStatus.COMPLETED -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = "完成", tint = EmbyGreen, modifier = Modifier.size(18.dp))
                    }
                    TaskStatus.FAILED -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "重试", tint = AppleRed, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "删除任务", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

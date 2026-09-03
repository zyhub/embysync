package com.zds.embysync.ui.components

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zds.embysync.core.engine.LocalStorageScanner
import com.zds.embysync.core.engine.SyncEngine
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val GoldenFolderColor = Color(0xFFF2A900)
private val AudioExtSet = LocalStorageScanner.AUDIO_EXTENSIONS

@Composable
fun FolderTreeCompareDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    val defaultRoot = remember {
        val f = if (initialPath.isNotBlank() && File(initialPath).exists()) {
            File(initialPath)
        } else {
            Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        }
        if (f.isDirectory) f else (f.parentFile ?: File("/storage/emulated/0"))
    }

    var currentDir by remember { mutableStateOf(defaultRoot) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // 🌟 排查修复 1: 完整扫描当前目录下的子文件夹与音频歌曲列表
    val dirContent: Pair<List<File>, List<File>> = remember(currentDir, refreshTrigger) {
        try {
            val all: List<File> = currentDir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            val folders: List<File> = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            val audios: List<File> = all.filter { it.isFile && it.extension.lowercase() in AudioExtSet }.sortedBy { it.name.lowercase() }
            Pair(folders, audios)
        } catch (_: Exception) {
            Pair(emptyList<File>(), emptyList<File>())
        }
    }

    val subFolders: List<File> = dirContent.first
    val audioFiles: List<File> = dirContent.second

    // 异步递归统计音频数（在后台 IO 协程中计算，彻底杜绝阻塞 UI 组合主线程）
    var totalAudiosInCurrentDir by remember { mutableStateOf(0) }
    var folderSongCountMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(currentDir, refreshTrigger, subFolders) {
        withContext(Dispatchers.IO) {
            val total = try {
                currentDir.walkTopDown().maxDepth(12).count { it.isFile && it.extension.lowercase() in AudioExtSet && it.length() > 5 * 1024 }
            } catch (_: Exception) { 0 }
            totalAudiosInCurrentDir = total

            val countMap = HashMap<String, Int>(subFolders.size)
            for (f in subFolders) {
                val c = try {
                    f.walkTopDown().maxDepth(12).count { it.isFile && it.extension.lowercase() in AudioExtSet && it.length() > 5 * 1024 }
                } catch (_: Exception) { 0 }
                countMap[f.absolutePath] = c
            }
            folderSongCountMap = countMap
        }
    }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFolders by remember { mutableStateOf<Set<File>>(emptySet()) }
    var selectedAudios by remember { mutableStateOf<Set<File>>(emptySet()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val totalSelectedCount = selectedFolders.size + selectedAudios.size
    val totalItemsCount = subFolders.size + audioFiles.size
    val isAllSelected = totalItemsCount > 0 && totalSelectedCount == totalItemsCount

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 顶部导航与路径指示
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentDir.parentFile != null && currentDir.parentFile?.canRead() == true) {
                        IconButton(
                            onClick = {
                                currentDir = currentDir.parentFile!!
                                selectedFolders = emptySet()
                                selectedAudios = emptySet()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级", tint = AppleRed)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📁 本地存储目录浏览与选择", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${currentDir.absolutePath} (含 ${totalAudiosInCurrentDir} 首歌曲)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 多选开关
                    TextButton(
                        onClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) {
                                selectedFolders = emptySet()
                                selectedAudios = emptySet()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(if (isSelectionMode) "退出多选" else "多选", fontSize = 12.sp, color = AppleRed)
                    }
                }

                // 多选操作工具栏
                if (isSelectionMode && totalItemsCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isAllSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            selectedFolders = subFolders.toSet()
                                            selectedAudios = audioFiles.toSet()
                                        } else {
                                            selectedFolders = emptySet()
                                            selectedAudios = emptySet()
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("全选 ($totalSelectedCount/$totalItemsCount)", fontSize = 12.sp)
                            }

                            if (totalSelectedCount > 0) {
                                Button(
                                    onClick = { showDeleteConfirmDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("删除 ($totalSelectedCount)", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                // 子文件夹与音频歌曲列表
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (subFolders.isEmpty() && audioFiles.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("此目录下暂无子文件夹或音频歌曲", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 1. 子文件夹部分
                    if (subFolders.isNotEmpty()) {
                        item {
                            Text(
                                text = "子文件夹 (${subFolders.size})",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }

                        items(subFolders, key = { "folder_${it.absolutePath}" }) { folder: File ->
                            val isSelected = selectedFolders.contains(folder)
                            val songCountInFolder = folderSongCountMap[folder.absolutePath] ?: 0

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) AppleRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (isSelectionMode) {
                                            selectedFolders = if (isSelected) selectedFolders - folder else selectedFolders + folder
                                        } else {
                                            currentDir = folder
                                            selectedFolders = emptySet()
                                            selectedAudios = emptySet()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                selectedFolders = if (checked) selectedFolders + folder else selectedFolders - folder
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (songCountInFolder > 0) GoldenFolderColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.name,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (songCountInFolder > 0) "包含 $songCountInFolder 首歌曲" else "空文件夹",
                                            fontSize = 11.sp,
                                            color = if (songCountInFolder > 0) EmbyGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (!isSelectionMode) {
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    // 2. 当前目录音频歌曲文件列表
                    if (audioFiles.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "音频歌曲 (${audioFiles.size})",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }

                        items(audioFiles, key = { "audio_${it.absolutePath}" }) { audioFile: File ->
                            val isSelected = selectedAudios.contains(audioFile)
                            val ext = audioFile.extension.uppercase()
                            val isLossless = audioFile.extension.lowercase() in listOf("flac", "wav", "ape", "dsf", "dff")

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) AppleRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (isSelectionMode) {
                                            selectedAudios = if (isSelected) selectedAudios - audioFile else selectedAudios + audioFile
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                selectedAudios = if (checked) selectedAudios + audioFile else selectedAudios - audioFile
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = AppleRed),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    Icon(
                                        Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = if (isLossless) EmbyGreen else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = audioFile.nameWithoutExtension,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(3.dp),
                                                color = if (isLossless) EmbyGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = ext,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isLossless) EmbyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = String.format("%.2f MB", audioFile.length() / (1024.0 * 1024.0)),
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                // 底部确认与取消
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(currentDir.absolutePath)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                    ) {
                        Text("选择此目录 (含 $totalAudiosInCurrentDir 首歌曲)")
                    }
                }
            }
        }
    }

    // 二级确认删除弹窗
    if (showDeleteConfirmDialog && totalSelectedCount > 0) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = AppleRed, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("确认删除选中的本地项目？", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "即将永久删除选中的 $totalSelectedCount 个本地项目 (${selectedFolders.size} 个文件夹, ${selectedAudios.size} 个音频)：",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(selectedFolders.toList()) { f ->
                                Text("📁 ${f.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            items(selectedAudios.toList()) { f ->
                                Text("🎵 ${f.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ 此操作将永久移除本地文件且不可撤销，请确认是否继续。",
                        fontSize = 12.sp,
                        color = AppleRed,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedFolders.forEach { f ->
                            if (f.exists()) {
                                val audioPaths = try {
                                    f.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
                                } catch (_: Exception) { emptyList() }
                                f.deleteRecursively()
                                audioPaths.forEach { p -> SyncEngine.notifyMediaDeleted(context, p) }
                            }
                        }
                        selectedAudios.forEach { f ->
                            if (f.exists()) {
                                val p = f.absolutePath
                                f.delete()
                                SyncEngine.notifyMediaDeleted(context, p)
                            }
                        }
                        selectedFolders = emptySet()
                        selectedAudios = emptySet()
                        showDeleteConfirmDialog = false
                        refreshTrigger++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

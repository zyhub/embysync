package com.zds.embysync.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import com.zds.embysync.core.database.EmbySyncDatabase
import com.zds.embysync.core.engine.LocalStorageScanner
import com.zds.embysync.core.engine.SongMatchingResolver
import com.zds.embysync.core.engine.SyncEngine
import com.zds.embysync.core.model.*
import com.zds.embysync.core.network.EmbySyncProtocol
import com.zds.embysync.ui.components.*
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import kotlinx.coroutines.launch
import java.io.File

private val FolderGoldenColor = Color(0xFFF2A900)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSyncScreen(
    database: EmbySyncDatabase,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val protocol = remember { EmbySyncProtocol() }

    val prefs = remember { context.getSharedPreferences("embysync_prefs", android.content.Context.MODE_PRIVATE) }
    var localDownloadDir by remember {
        val defaultPath = File(Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0"), "Music").absolutePath
        mutableStateOf(prefs.getString("sync_local_dir", defaultPath) ?: defaultPath)
    }

    var serverConfig by remember {
        mutableStateOf(
            EmbyServerConfig(
                name = prefs.getString("server_name", "我的 Emby 服务器") ?: "我的 Emby 服务器",
                serverUrl = prefs.getString("server_url", "") ?: "",
                username = prefs.getString("server_user", "") ?: "",
                tokenOrApiKey = prefs.getString("server_token", "") ?: "",
                userId = prefs.getString("server_uid", "") ?: ""
            )
        )
    }

    var isServerOnline by remember { mutableStateOf(false) }
    var localSongs by remember { mutableStateOf<List<SyncComparisonSong>>(emptyList()) }

    var serverFolderItems by remember { mutableStateOf<List<ServerFolderItem>>(emptyList()) }
    var isLoadingFolder by remember { mutableStateOf(false) }
    var serverFolderStack by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var parsingFolderId by remember { mutableStateOf<String?>(null) }
    var songPendingDelete by remember { mutableStateOf<SyncComparisonSong?>(null) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showFolderPickerDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showDownloadManagerDialog by remember { mutableStateOf(false) }
    var inspectingSong by remember { mutableStateOf<SyncComparisonSong?>(null) }
    val downloadTasks by SyncEngine.tasksFlow.collectAsState()
    val syncLogs by database.logDao().getAllLogsFlow().collectAsState(initial = emptyList())

    val folderProgressMap = remember(downloadTasks) {
        val map = mutableMapOf<String, FolderProgressInfo>()
        val grouped = downloadTasks.groupBy { it.folderId }
        for ((fId, tList) in grouped) {
            if (fId != null) {
                val total = tList.size
                val comp = tList.count { it.status == TaskStatus.COMPLETED }
                val failed = tList.count { it.status == TaskStatus.FAILED }
                val curBytes = tList.sumOf { it.downloadedBytes }
                val totBytes = tList.sumOf { it.totalBytes }
                val pct = if (totBytes > 0) curBytes.toFloat() / totBytes.toFloat() else (if (total > 0) comp.toFloat() / total.toFloat() else 0f)
                map[fId] = FolderProgressInfo(fId, total, comp, failed, pct)
            }
        }
        map
    }

    val scanLocalDirectory: suspend () -> Unit = {
        val localDir = File(localDownloadDir)
        val list = if (localDir.exists()) {
            LocalStorageScanner.scanDirectory(context, localDir)
        } else emptyList()
        localSongs = list
    }

    val loadFolderContent: suspend (parentId: String?) -> Unit = { pId ->
        isLoadingFolder = true
        selectedSongIds = emptySet()
        if (serverConfig.serverUrl.isNotBlank()) {
            val res = protocol.getFolders(serverConfig, pId)
            if (res.isSuccess) {
                serverFolderItems = res.getOrDefault(emptyList())
                isServerOnline = true
            } else {
                Toast.makeText(context, "加载文件夹失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
        isLoadingFolder = false
    }

    LaunchedEffect(serverFolderStack) {
        val currentParentId = serverFolderStack.lastOrNull()?.first
        loadFolderContent(currentParentId)
    }

    LaunchedEffect(Unit) {
        scanLocalDirectory()
    }

    // 🌟 监听单曲完成事件，实时将新下载歌曲并入 localSongs，驱动页面内文件夹比对状态即时变为已同步
    LaunchedEffect(Unit) {
        SyncEngine.songCompletedFlow.collect { completedSong ->
            val currentLocals = localSongs.toMutableList()
            val existingIdx = currentLocals.indexOfFirst {
                it.id == completedSong.id ||
                (!it.localFilePath.isNullOrBlank() && it.localFilePath == completedSong.localFilePath) ||
                (it.title.equals(completedSong.title, ignoreCase = true) && it.artist.equals(completedSong.artist, ignoreCase = true))
            }
            if (existingIdx >= 0) {
                currentLocals[existingIdx] = completedSong
            } else {
                currentLocals.add(completedSong)
            }
            localSongs = currentLocals
        }
    }

    val localSongIndex = remember(localSongs) {
        SongMatchingResolver.buildLocalSongIndex(localSongs)
    }

    val resolvedFolderItems = remember(serverFolderItems, searchQuery, localSongIndex) {
        val raw = if (searchQuery.isBlank()) serverFolderItems else {
            val q = searchQuery.trim().lowercase()
            serverFolderItems.filter { item ->
                item.name.contains(q, ignoreCase = true) ||
                (item.song?.artist?.contains(q, ignoreCase = true) == true) ||
                (item.song?.title?.contains(q, ignoreCase = true) == true)
            }
        }

        raw.map { item ->
            if (item.song != null) {
                val resolvedSong = SongMatchingResolver.matchServerSongWithIndex(item.song, localSongIndex)
                item.copy(song = resolvedSong)
            } else {
                item
            }
        }
    }

    // ⚡ 高性能预计算文件夹同步数字典
    val folderSyncCountMap = remember(serverFolderItems, localSongs) {
        val map = HashMap<String, Int>(serverFolderItems.size)
        val folderList = serverFolderItems.filter { it.isFolder }
        if (folderList.isEmpty() || localSongs.isEmpty()) return@remember map

        val folderNormMap = folderList.associateWith { folder ->
            Triple(folder.name.lowercase(), SongMatchingResolver.normalizeArtist(folder.name), SongMatchingResolver.normalizeTrackTitle(folder.name))
        }

        for (i in localSongs.indices) {
            val s = localSongs[i]
            val sRel = (s.relativeFolderPath ?: "").lowercase()
            val sArtist = s.artist.lowercase()
            val sAlbum = s.album.lowercase()
            val sNormArt = SongMatchingResolver.normalizeArtist(s.artist)
            val sNormAlb = SongMatchingResolver.normalizeTrackTitle(s.album)

            for ((folder, norms) in folderNormMap) {
                val (fNameLower, fNormArt, fNormAlb) = norms
                val isMatched = sRel.contains(fNameLower) ||
                        sArtist.contains(fNameLower) ||
                        sAlbum.contains(fNameLower) ||
                        (fNormArt.isNotBlank() && sNormArt == fNormArt) ||
                        (fNormAlb.isNotBlank() && sNormAlb == fNormAlb) ||
                        (sArtist.isNotBlank() && fNameLower.contains(sArtist)) ||
                        (sAlbum.isNotBlank() && fNameLower.contains(sAlbum))

                if (isMatched) {
                    map[folder.id] = (map[folder.id] ?: 0) + 1
                }
            }
        }
        map
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                // 顶栏：返回按键 + 路径/标题 + 操作区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = {
                            if (serverFolderStack.isNotEmpty()) {
                                serverFolderStack = serverFolderStack.dropLast(1)
                            } else {
                                onNavigateBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = AppleRed)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = serverFolderStack.lastOrNull()?.second ?: "📁 文件夹同步",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (serverFolderStack.isEmpty()) "浏览 Emby 媒体库目录并按文件夹同步" else "返回上一级",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isSearchExpanded = !isSearchExpanded }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) selectedSongIds = emptySet()
                        }) {
                            Icon(
                                imageVector = if (isSelectionMode) Icons.Default.Checklist else Icons.Default.ChecklistRtl,
                                contentDescription = "多选",
                                tint = if (isSelectionMode) AppleRed else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = {
                            val currentParentId = serverFolderStack.lastOrNull()?.first
                            scope.launch {
                                scanLocalDirectory()
                                loadFolderContent(currentParentId)
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = AppleRed)
                        }
                        IconButton(onClick = { showLogsDialog = true }) {
                            Icon(Icons.Default.History, contentDescription = "日志", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                // 面包屑导航栏
                if (serverFolderStack.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AppleRed.copy(alpha = 0.1f),
                            modifier = Modifier.clickable { serverFolderStack = emptyList() }
                        ) {
                            Text("📁 根目录", color = AppleRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        serverFolderStack.forEachIndexed { index, pair ->
                            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (index == serverFolderStack.lastIndex) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                modifier = Modifier.clickable {
                                    serverFolderStack = serverFolderStack.take(index + 1)
                                }
                            ) {
                                Text(
                                    text = pair.second,
                                    color = if (index == serverFolderStack.lastIndex) MaterialTheme.colorScheme.onSurface else AppleRed,
                                    fontSize = 11.sp,
                                    fontWeight = if (index == serverFolderStack.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 搜索框
                if (isSearchExpanded) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索当前目录下的项目...", fontSize = 13.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "清空")
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoadingFolder) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppleRed)
                }
            } else if (resolvedFolderItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "没有找到匹配的项目" else "当前目录为空",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(resolvedFolderItems, key = { it.id }) { item ->
                        if (item.isFolder) {
                            val matchedLocalCount = folderSyncCountMap[item.id] ?: 0
                            val isFolderFullySynced = matchedLocalCount > 0 && (item.childCount <= 0 || matchedLocalCount >= item.childCount)
                            val isFolderPartiallySynced = matchedLocalCount > 0 && !isFolderFullySynced
                            val isFolderAnySynced = matchedLocalCount > 0

                            val folderProgress = folderProgressMap[item.id]
                            val isSyncingThisFolder = folderProgress != null && folderProgress.completedCount < folderProgress.totalCount

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        serverFolderStack = serverFolderStack + (item.id to item.name)
                                    },
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.5.dp, if (isFolderAnySynced) EmbyGreen.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                tonalElevation = 1.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isFolderAnySynced) EmbyGreen else FolderGoldenColor,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.name,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isFolderFullySynced) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = EmbyGreen.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "已全部同步",
                                                        color = EmbyGreen,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            } else if (isFolderPartiallySynced) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = EmbyGreen.copy(alpha = 0.12f)
                                                ) {
                                                    Text(
                                                        text = "已同步 $matchedLocalCount 首",
                                                        color = EmbyGreen,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = when {
                                                isFolderFullySynced -> "${if (item.childCount > 0) "${item.childCount} 个项目 • " else ""}已同步 $matchedLocalCount 首 (全部离线)"
                                                isFolderPartiallySynced -> "已同步 $matchedLocalCount / ${item.childCount} 首"
                                                item.childCount > 0 -> "${item.childCount} 个项目"
                                                else -> "文件夹"
                                            },
                                            fontSize = 12.sp,
                                            color = if (isFolderAnySynced) EmbyGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // 文件夹右侧同步控制区与实时圆形进度条
                                    if (isFolderFullySynced) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "已全部同步",
                                            tint = EmbyGreen,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    } else if (isSyncingThisFolder) {
                                        val curProg = folderProgress!!
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(AppleRed.copy(alpha = 0.1f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                                .clickable { showDownloadManagerDialog = true }
                                        ) {
                                            Text(
                                                text = "${curProg.completedCount}/${curProg.totalCount}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleRed
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier.size(28.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    progress = { curProg.currentPercent },
                                                    color = AppleRed,
                                                    trackColor = AppleRed.copy(alpha = 0.2f),
                                                    strokeWidth = 2.5.dp,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                Text(
                                                    text = "${(curProg.currentPercent * 100).toInt()}%",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = AppleRed
                                                )
                                            }
                                        }
                                    } else {
                                        val isParsingThis = parsingFolderId == item.id
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            FilledTonalButton(
                                                onClick = {
                                                    parsingFolderId = item.id
                                                    scope.launch {
                                                        val songsRes = protocol.getFolderSongs(serverConfig, item.id)
                                                        if (songsRes.isSuccess) {
                                                            val songs = songsRes.getOrDefault(emptyList())
                                                            val unsynced = songs.filter { s ->
                                                                SongMatchingResolver.matchServerSongWithIndex(s, localSongIndex).syncStatus != SyncStatus.SYNCED
                                                            }
                                                            if (unsynced.isNotEmpty()) {
                                                                SyncEngine.startBatchSync(
                                                                    context = context,
                                                                    server = serverConfig,
                                                                    songs = unsynced,
                                                                    downloadDir = File(localDownloadDir),
                                                                    database = database,
                                                                    folderId = item.id
                                                                ) {
                                                                    scope.launch { scanLocalDirectory() }
                                                                }
                                                                Toast.makeText(context, "已将 ${unsynced.size} 首歌曲加入同步队列", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                Toast.makeText(context, "该文件夹内歌曲已全部下载！", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                        parsingFolderId = null
                                                    }
                                                },
                                                enabled = !isParsingThis,
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = AppleRed.copy(alpha = 0.12f),
                                                    contentColor = AppleRed
                                                ),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                if (isParsingThis) {
                                                    CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text(if (isFolderPartiallySynced) "补齐" else "同步", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (item.song != null) {
                            val song = item.song
                            val isSelected = selectedSongIds.contains(song.id)
                            SyncSongCompareRow(
                                song = song,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onSelectToggle = {
                                    selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                                },
                                onClick = { inspectingSong = song },
                                onQuickSync = { targetSong ->
                                    SyncEngine.startBatchSync(
                                        context = context,
                                        server = serverConfig,
                                        songs = listOf(targetSong),
                                        downloadDir = File(localDownloadDir),
                                        database = database
                                    ) {
                                        scope.launch { scanLocalDirectory() }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 弹窗群
    if (showSettingsDialog) {
        SyncSettingsDialog(
            initialConfig = serverConfig,
            initialPassword = prefs.getString("server_raw_password", "") ?: "",
            initialLocalDir = localDownloadDir,
            initialConcurrency = prefs.getInt("sync_concurrency", 2),
            onDismiss = { showSettingsDialog = false },
            onSave = { updatedServer, rawPassword, updatedDir, updatedConcurrency ->
                serverConfig = updatedServer
                localDownloadDir = updatedDir
                SyncEngine.concurrencyLimit = updatedConcurrency
                prefs.edit()
                    .putString("sync_local_dir", updatedDir)
                    .putString("server_name", updatedServer.name)
                    .putString("server_url", updatedServer.serverUrl)
                    .putString("server_user", updatedServer.username)
                    .putString("server_token", updatedServer.tokenOrApiKey)
                    .putString("server_uid", updatedServer.userId)
                    .putString("server_raw_password", rawPassword)
                    .putInt("sync_concurrency", updatedConcurrency)
                    .apply()

                val currentParentId = serverFolderStack.lastOrNull()?.first
                scope.launch {
                    scanLocalDirectory()
                    loadFolderContent(currentParentId)
                }
            },
            onChooseLocalFolder = {
                showSettingsDialog = false
                showFolderPickerDialog = true
            }
        )
    }

    if (showFolderPickerDialog) {
        FolderTreeCompareDialog(
            initialPath = localDownloadDir,
            onDismiss = { showFolderPickerDialog = false },
            onConfirm = { chosenPath ->
                localDownloadDir = chosenPath
                prefs.edit().putString("sync_local_dir", chosenPath).apply()
                val currentParentId = serverFolderStack.lastOrNull()?.first
                scope.launch {
                    scanLocalDirectory()
                    loadFolderContent(currentParentId)
                }
            }
        )
    }

    if (showLogsDialog) {
        SyncLogsDialog(
            logs = syncLogs,
            onDismiss = { showLogsDialog = false },
            onClearLogs = {
                scope.launch { database.logDao().clearLogs() }
            }
        )
    }

    if (showDownloadManagerDialog) {
        DownloadTaskManagerDialog(
            onDismiss = { showDownloadManagerDialog = false }
        )
    }

    val curInspectingSong = inspectingSong
    if (curInspectingSong != null) {
        SongDiffDetailDialog(
            song = curInspectingSong,
            onDismiss = { inspectingSong = null },
            onSyncNow = { targetSong ->
                SyncEngine.startBatchSync(
                    context = context,
                    server = serverConfig,
                    songs = listOf(targetSong),
                    downloadDir = File(localDownloadDir),
                    database = database
                ) {
                    scope.launch { scanLocalDirectory() }
                }
            },
            onDeleteLocal = { targetSong ->
                songPendingDelete = targetSong
            }
        )
    }

    val curPendingDelete = songPendingDelete
    if (curPendingDelete != null) {
        val filePath = curPendingDelete.localFilePath
        AlertDialog(
            onDismissRequest = { songPendingDelete = null },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = AppleRed, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("删除本地音频文件", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "确定要从本地存储删除《${curPendingDelete.title}》吗？",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!filePath.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "路径: $filePath",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "删除后将释放本地空间。若需收听，仍可随时从 Emby 服务器重新同步下载。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!filePath.isNullOrBlank()) {
                            val f = File(filePath)
                            if (f.exists()) {
                                f.delete()
                                SyncEngine.notifyMediaDeleted(context, filePath)
                            }
                        }
                        songPendingDelete = null
                        scope.launch { scanLocalDirectory() }
                        Toast.makeText(context, "已删除本地文件", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { songPendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

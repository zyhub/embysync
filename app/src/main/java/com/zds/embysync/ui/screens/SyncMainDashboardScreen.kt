package com.zds.embysync.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import com.zds.embysync.core.engine.SongDiffComparator
import com.zds.embysync.core.engine.SongMatchingResolver
import com.zds.embysync.core.engine.SyncEngine
import com.zds.embysync.core.model.*
import com.zds.embysync.core.network.EmbySyncProtocol
import com.zds.embysync.ui.components.*
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import com.zds.embysync.ui.theme.SyncBlue
import com.zds.embysync.ui.theme.SyncOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val FolderGoldenColor = Color(0xFFF2A900)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncMainDashboardScreen(
    database: EmbySyncDatabase,
    onNavigateToFolderSync: () -> Unit
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

    // 🚀 启动秒开优化：利用本地缓存数据首帧直接渲染看板
    val cachedTotalSongs = remember { prefs.getInt("cache_total_songs", 0) }
    val cachedSyncedSongs = remember { prefs.getInt("cache_synced_songs", 0) }
    val cachedNeedSongs = remember { prefs.getInt("cache_need_songs", 0) }
    val cachedUpgradeSongs = remember { prefs.getInt("cache_upgrade_songs", 0) }
    val cachedServerFolders = remember { prefs.getInt("cache_server_folders", 0) }
    val cachedFullySyncedFolders = remember { prefs.getInt("cache_fully_synced_folders", 0) }
    val cachedIsOnline = remember { prefs.getBoolean("cache_is_online", true) }

    var isServerOnline by remember { mutableStateOf(cachedIsOnline) }
    var localSongs by remember { mutableStateOf<List<SyncComparisonSong>>(emptyList()) }
    var isFullScanning by remember { mutableStateOf(false) }
    var fullScanDiffResult by remember { mutableStateOf<SongDiffResult?>(null) }
    var serverFolders by remember { mutableStateOf<List<ServerFolderItem>>(emptyList()) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showFolderPickerDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showDownloadManagerDialog by remember { mutableStateOf(false) }

    val progressState by SyncEngine.progressFlow.collectAsState()
    val downloadTasks by SyncEngine.tasksFlow.collectAsState()
    val syncLogs by database.logDao().getAllLogsFlow().collectAsState(initial = emptyList())

    // 任务状态精准分类统计
    val totalTaskCount = downloadTasks.size
    val completedTaskCount = downloadTasks.count { it.status == TaskStatus.COMPLETED }
    val downloadingTaskCount = downloadTasks.count { it.status == TaskStatus.DOWNLOADING }
    val queuedTaskCount = downloadTasks.count { it.status == TaskStatus.QUEUED }
    val pausedTaskCount = downloadTasks.count { it.status == TaskStatus.PAUSED }
    val activeTaskCount = downloadingTaskCount + queuedTaskCount

    // 状态判定：优雅暂停中、正常传输中、安全暂停就绪
    val isPausing = progressState.isPausing || (SyncEngine.isPauseRequested && downloadingTaskCount > 0)
    val isTransferring = !isPausing && (progressState.isSyncing || activeTaskCount > 0)
    val isPausedState = !isPausing && !isTransferring && pausedTaskCount > 0

    // 1. 扫描本地歌曲 (后台 IO 异步执行，不卡顿 UI)
    val scanLocalDirectory: suspend () -> Unit = {
        val localDir = File(localDownloadDir)
        val list = if (localDir.exists()) {
            LocalStorageScanner.scanDirectory(context, localDir)
        } else emptyList()
        withContext(Dispatchers.Main) {
            localSongs = list
        }
    }

    // 2. 执行全量比对与文件夹统计关联 (后台异步静默刷新)
    val performFullScanCompare: () -> Unit = {
        isFullScanning = true
        scope.launch(Dispatchers.IO) {
            try {
                scanLocalDirectory()
                if (serverConfig.serverUrl.isNotBlank()) {
                    val online = protocol.ping(serverConfig)
                    withContext(Dispatchers.Main) { isServerOnline = online }

                    if (online) {
                        val foldersRes = protocol.getFolders(serverConfig, null)
                        val folderList = if (foldersRes.isSuccess) foldersRes.getOrDefault(emptyList()).filter { it.isFolder } else emptyList()
                        withContext(Dispatchers.Main) { serverFolders = folderList }

                        val embyAllSongs = protocol.fetchAllSongs(serverConfig)
                        val diff = SongDiffComparator.compare(embyAllSongs, localSongs)
                        withContext(Dispatchers.Main) {
                            fullScanDiffResult = diff

                            // 缓存最新比对数据加速下次冷启动
                            prefs.edit()
                                .putInt("cache_total_songs", diff.allSongs.size)
                                .putInt("cache_synced_songs", diff.syncedCount)
                                .putInt("cache_need_songs", diff.needDownloadCount)
                                .putInt("cache_upgrade_songs", diff.diffUpgradeCount)
                                .putInt("cache_server_folders", folderList.size)
                                .putBoolean("cache_is_online", true)
                                .apply()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            fullScanDiffResult = null
                            serverFolders = emptyList()
                            prefs.edit().putBoolean("cache_is_online", false).apply()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isServerOnline = false
                        fullScanDiffResult = null
                        serverFolders = emptyList()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "比对过程出错: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isFullScanning = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        SyncEngine.concurrencyLimit = prefs.getInt("sync_concurrency", 2)
        scope.launch(Dispatchers.IO) {
            scanLocalDirectory()
            if (serverConfig.serverUrl.isNotBlank()) {
                performFullScanCompare()
            }
        }
    }

    // 🌟 核心修复：监听实时单曲下载完成事件，动态驱动待下载数递减与已同步数实时递增
    LaunchedEffect(Unit) {
        SyncEngine.songCompletedFlow.collect { completedSong ->
            // 1. 本地歌曲集合实时追加/更新 (以内存增量更新替代全量磁盘 IO 重扫)
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

            // 2. 全量比对结果集动态演进 (同步状态原地转为 SYNCED，待下载实时递减，已同步实时递增)
            fullScanDiffResult?.let { diff ->
                val updatedAll = diff.allSongs.map { s ->
                    if (s.id == completedSong.id ||
                        (s.embyItemId != null && s.embyItemId == completedSong.embyItemId) ||
                        (s.title.equals(completedSong.title, ignoreCase = true) && s.artist.equals(completedSong.artist, ignoreCase = true))
                    ) {
                        completedSong.copy(
                            localFilePath = completedSong.localFilePath,
                            localFormat = completedSong.localFormat,
                            localBitRate = completedSong.localBitRate,
                            localFileSize = completedSong.localFileSize,
                            syncStatus = SyncStatus.SYNCED,
                            diffReason = "本地已同步"
                        )
                    } else {
                        s
                    }
                }
                val newSynced = updatedAll.count { it.syncStatus == SyncStatus.SYNCED }
                val newNeed = updatedAll.count { it.syncStatus == SyncStatus.NEED_DOWNLOAD }
                val newUpgrade = updatedAll.count { it.syncStatus == SyncStatus.DIFF_UPGRADE }
                val newIgnored = updatedAll.count { it.syncStatus == SyncStatus.IGNORED }

                fullScanDiffResult = SongDiffResult(
                    allSongs = updatedAll,
                    syncedCount = newSynced,
                    needDownloadCount = newNeed,
                    diffUpgradeCount = newUpgrade,
                    ignoredCount = newIgnored
                )

                // 同步持久化缓存
                prefs.edit()
                    .putInt("cache_synced_songs", newSynced)
                    .putInt("cache_need_songs", newNeed)
                    .putInt("cache_upgrade_songs", newUpgrade)
                    .apply()
            }
        }
    }

    // 🌟 当下载引擎完成所有任务时（如从文件夹同步或单曲下载完成），自动重新比对并静默刷新首页数据
    var prevIsSyncing by remember { mutableStateOf(false) }
    LaunchedEffect(progressState.isSyncing) {
        if (prevIsSyncing && !progressState.isSyncing) {
            performFullScanCompare()
        }
        prevIsSyncing = progressState.isSyncing
    }

    val curDiff = fullScanDiffResult
    val unsyncedList = remember(curDiff) {
        curDiff?.allSongs?.filter { it.syncStatus in listOf(SyncStatus.NEED_DOWNLOAD, SyncStatus.DIFF_UPGRADE) } ?: emptyList()
    }

    // 文件夹维度统计关联
    val folderSyncCountMap = remember(serverFolders, localSongs) {
        val map = HashMap<String, Int>(serverFolders.size)
        val folderList = serverFolders.filter { it.isFolder }
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

    val fullySyncedFoldersCount = remember(serverFolders, folderSyncCountMap, cachedFullySyncedFolders) {
        if (serverFolders.isNotEmpty()) {
            serverFolders.count { f ->
                val matched = folderSyncCountMap[f.id] ?: 0
                matched > 0 && (f.childCount <= 0 || matched >= f.childCount)
            }
        } else cachedFullySyncedFolders
    }

    val partiallySyncedFoldersCount = remember(serverFolders, folderSyncCountMap) {
        serverFolders.count { f ->
            val matched = folderSyncCountMap[f.id] ?: 0
            matched > 0 && f.childCount > 0 && matched < f.childCount
        }
    }

    val unsyncedFoldersCount = if (serverFolders.isNotEmpty()) (serverFolders.size - fullySyncedFoldersCount - partiallySyncedFoldersCount).coerceAtLeast(0) else 0

    // 统计数据与双端枢纽真实数据严格对齐 (杜绝局部队列任务完成后的双重累加与虚假完全同步)
    val totalServerSongs = curDiff?.allSongs?.size ?: if (cachedTotalSongs > 0) cachedTotalSongs else 0
    val actualLocalCount = localSongs.size
    val liveSyncedSongs = curDiff?.syncedCount ?: if (localSongs.isNotEmpty()) localSongs.size else cachedSyncedSongs
    val liveNeedSongs = curDiff?.needDownloadCount ?: if (totalServerSongs > 0) (totalServerSongs - liveSyncedSongs).coerceAtLeast(0) else cachedNeedSongs
    val totalUpgradeSongs = curDiff?.diffUpgradeCount ?: cachedUpgradeSongs
    val totalServerFoldersCount = if (serverFolders.isNotEmpty()) serverFolders.size else cachedServerFolders

    val overallSyncPercentage = if (totalServerSongs > 0) {
        ((liveSyncedSongs.toFloat() / totalServerSongs.toFloat()) * 100).toInt().coerceIn(0, 100)
    } else if (liveSyncedSongs > 0) 100 else 0

    val animatedSyncProgress by animateFloatAsState(
        targetValue = overallSyncPercentage / 100f,
        label = "SyncHealthProgress"
    )

    // 环形仪表进度值与颜色
    val gaugeProgress: Float = when {
        isPausing || isTransferring || isPausedState -> {
            if (totalTaskCount > 0) {
                completedTaskCount.toFloat() / totalTaskCount.toFloat()
            } else {
                progressState.overallProgress
            }
        }
        else -> animatedSyncProgress
    }

    val gaugeColor: Color = when {
        isPausing || isPausedState -> SyncOrange
        isTransferring -> AppleRed
        overallSyncPercentage >= 100 -> EmbyGreen
        else -> AppleRed
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EmbyGreen,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "EMBYsync",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isServerOnline) EmbyGreen.copy(alpha = 0.12f) else AppleRed.copy(alpha = 0.12f),
                            border = BorderStroke(0.8.dp, if (isServerOnline) EmbyGreen.copy(alpha = 0.3f) else AppleRed.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isServerOnline) EmbyGreen else AppleRed)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isServerOnline) "在线比对中枢" else "离线曲库模式",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isServerOnline) EmbyGreen else AppleRed
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = performFullScanCompare, enabled = !isFullScanning) {
                        if (isFullScanning) {
                            CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "比对刷新", tint = AppleRed)
                        }
                    }
                    IconButton(onClick = { showLogsDialog = true }) {
                        Icon(Icons.Default.History, contentDescription = "流水日志", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 离线警示横幅
            AnimatedVisibility(visible = !isServerOnline) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SyncOrange.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, SyncOrange.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().clickable { showSettingsDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = SyncOrange, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("未连接 Emby 服务器（离线曲库模式）", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = SyncOrange)
                            Text("已为您就绪本地 ${liveSyncedSongs} 首歌曲，点击可配置或重新连接服务器", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SyncOrange, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // 🌟 卡片 1: 同步主控仪表盘卡片 (Sleek Sync Cockpit，圆角 16dp，精致卡片化)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：环形健康度仪表 (80dp，支持同步中、暂停中、已暂停与就绪态数值精准联动)
                    Box(
                        modifier = Modifier.size(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { gaugeProgress },
                            color = gaugeColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeWidth = 6.5.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            when {
                                isPausing -> {
                                    Text(
                                        text = "$completedTaskCount/$totalTaskCount",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        color = SyncOrange
                                    )
                                    Text(
                                        text = "暂停中",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SyncOrange
                                    )
                                }
                                isTransferring -> {
                                    Text(
                                        text = "$completedTaskCount/$totalTaskCount",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        color = AppleRed
                                    )
                                    Text(
                                        text = "同步中",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppleRed
                                    )
                                }
                                isPausedState -> {
                                    Text(
                                        text = "$completedTaskCount/$totalTaskCount",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        color = SyncOrange
                                    )
                                    Text(
                                        text = "已暂停",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SyncOrange
                                    )
                                }
                                else -> {
                                    Text(
                                        text = if (isServerOnline) "$overallSyncPercentage%" else "$liveSyncedSongs",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (overallSyncPercentage >= 100) EmbyGreen else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isServerOnline) "同步健康度" else "首本地",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    // 右侧：状态说明 + 实时网速/歌名 + 联动控制组 (包含暂停/继续/取消下载)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isPausing) {
                            // 🌟 1. 正在优雅暂停过渡态
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = SyncOrange, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("正在执行优雅暂停...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("保证正在传输的单曲完整写入并校验入库", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = { /* 正在暂停过渡中，禁用点击防误触 */ },
                                    enabled = false,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 1.6.dp,
                                        modifier = Modifier.size(11.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("正在暂停...", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { SyncEngine.cancelAll() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed),
                                    border = BorderStroke(0.8.dp, AppleRed.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("取消", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { showDownloadManagerDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("队列", fontSize = 11.sp)
                                }
                            }
                        } else if (isTransferring) {
                            // 🌟 2. 正在正常同步：展示当前传输单曲与实时平滑网速/字节进度
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = AppleRed, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = progressState.currentItemTitle.ifBlank { "正在并行同步曲目..." },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            val speedMB = progressState.speedBytesPerSec / (1024.0 * 1024.0)
                            val sessDownMB = progressState.sessionDownloadedBytes / (1024.0 * 1024.0)
                            val sessTotMB = progressState.sessionTotalBytes / (1024.0 * 1024.0)
                            Text(
                                text = "⚡ ${String.format("%.1f MB/s", speedMB)} • 剩余 $activeTaskCount 首" +
                                        (if (sessTotMB > 0.1) String.format(" • %.1f/%.1f MB", sessDownMB, sessTotMB) else ""),
                                fontSize = 11.sp,
                                color = AppleRed,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 按钮组：暂停同步 / 取消下载 / 队列
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { SyncEngine.pauseAll() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("暂停同步", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { SyncEngine.cancelAll() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed),
                                    border = BorderStroke(0.8.dp, AppleRed.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("取消", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { showDownloadManagerDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("队列", fontSize = 11.sp)
                                }
                            }
                        } else if (isPausedState || pausedTaskCount > 0) {
                            // 🌟 3. 暂停就绪态：展示「继续下载 (X首)」+ 取消 + 队列
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PauseCircle, contentDescription = null, tint = SyncOrange, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("同步已暂停", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("已完成 $completedTaskCount 首 • 仍有 $pausedTaskCount 首曲目等待继续下载", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { SyncEngine.resumeAll() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = EmbyGreen),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("继续下载 (${pausedTaskCount}首)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { SyncEngine.cancelAll() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed),
                                    border = BorderStroke(0.8.dp, AppleRed.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("取消下载", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { showDownloadManagerDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("队列", fontSize = 11.sp)
                                }
                            }
                        } else if (liveNeedSongs > 0) {
                            // 🌟 4. 空闲但有待同步歌曲：展示短按钮
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = AppleRed, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("发现 $liveNeedSongs 首新曲目待同步", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "涉及 ${partiallySyncedFoldersCount + unsyncedFoldersCount} 个文件夹，可一键批量离线",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    SyncEngine.startBatchSync(
                                        context = context,
                                        server = serverConfig,
                                        songs = unsyncedList,
                                        downloadDir = File(localDownloadDir),
                                        database = database
                                    ) {
                                        scope.launch(Dispatchers.IO) { scanLocalDirectory() }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("一键全量同步 (${liveNeedSongs}首)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            // 🌟 5. 100% 同步完成
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmbyGreen, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("双端曲库已完全同步", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("全部 ${totalServerSongs} 首歌曲已离线下载至本地", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = performFullScanCompare,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = EmbyGreen.copy(alpha = 0.15f),
                                    contentColor = EmbyGreen
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重新比对", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 🌟 卡片 2: 双端数据生态联动卡片 (Cloud ⇄ Local Pipeline，动态实时递变)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧：Emby 云端子卡片 (融合总数、文件夹数、待下载、可升级)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SyncBlue.copy(alpha = 0.08f),
                            border = BorderStroke(0.8.dp, SyncBlue.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showSettingsDialog = true }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Cloud, contentDescription = null, tint = SyncBlue, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Emby 云端", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SyncBlue)
                                    }
                                    Text(
                                        text = if (totalServerFoldersCount > 0) "${totalServerFoldersCount} 文件夹" else "未连接",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isServerOnline) "${totalServerSongs} 首歌曲" else "离线未连",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(5.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = SyncOrange.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "待下载: $liveNeedSongs",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SyncOrange,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp)
                                        )
                                    }
                                    if (totalUpgradeSongs > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = AppleRed.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = "可升级: $totalUpgradeSongs",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleRed,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 中间互传指示
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .size(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                contentDescription = null,
                                tint = if (isTransferring || isPausing) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 右侧：本地存储子卡片 (融合已同步总数、同步完整率、文件夹数)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = FolderGoldenColor.copy(alpha = 0.08f),
                            border = BorderStroke(0.8.dp, FolderGoldenColor.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showFolderPickerDialog = true }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${File(localDownloadDir).name} 目录",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("本地存储", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FolderGoldenColor)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = FolderGoldenColor, modifier = Modifier.size(14.dp))
                                    }
                                }
                                val displayLocalCount = if (actualLocalCount > 0) actualLocalCount else liveSyncedSongs
                                Text(
                                    text = "${displayLocalCount} 首已同步",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(5.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = EmbyGreen.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "已满 $fullySyncedFoldersCount 文件夹 ($overallSyncPercentage%)",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EmbyGreen,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚡ 实时数据流联动：每下载完成 1 首歌曲，待下载与已同步数据实时递变",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }

            // 🌟 卡片 3: 文件夹同步看板卡片 (Folder Sync Hub)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onNavigateToFolderSync)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = FolderGoldenColor.copy(alpha = 0.15f),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.FolderCopy, contentDescription = null, tint = FolderGoldenColor, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("📁 文件夹同步看板", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    text = if (totalServerFoldersCount > 0) "已同步 $fullySyncedFoldersCount / $totalServerFoldersCount 个文件夹" else "点击进入浏览与同步文件夹",
                                    fontSize = 11.sp,
                                    color = if (fullySyncedFoldersCount == totalServerFoldersCount && totalServerFoldersCount > 0) EmbyGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = onNavigateToFolderSync,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FolderGoldenColor),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("进入管理 ➔", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }

                    if (serverFolders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { if (serverFolders.isNotEmpty()) fullySyncedFoldersCount.toFloat() / serverFolders.size.toFloat() else 0f },
                            color = EmbyGreen,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.5.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // 文件夹状态预览胶囊
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            serverFolders.take(3).forEach { folder ->
                                val matched = folderSyncCountMap[folder.id] ?: 0
                                val isSynced = matched > 0 && (folder.childCount <= 0 || matched >= folder.childCount)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSynced) EmbyGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(0.5.dp, if (isSynced) EmbyGreen.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (isSynced) EmbyGreen else FolderGoldenColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = folder.name,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (isSynced) EmbyGreen else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 🌟 卡片 4: 快捷控制与管理卡片 (Quick Action Grid)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("⚙️ 快捷控制与管理", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showFolderPickerDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("本地目录", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { showDownloadManagerDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载队列", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { showLogsDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("同步日志", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
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

                performFullScanCompare()
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
                performFullScanCompare()
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
}

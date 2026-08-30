package com.zds.embysync.core.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.zds.embysync.core.database.EmbySyncDatabase
import com.zds.embysync.core.database.entity.SyncLogEntity
import com.zds.embysync.core.model.*
import com.zds.embysync.core.network.EmbySyncProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class FolderSyncProgress(
    val folderId: String,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val currentPercent: Float = 0f
)

object SyncEngine {

    private const val TAG = "SyncEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val protocol = EmbySyncProtocol()

    // 并发限制 (默认 2，可通过设置配置 1~5)
    @Volatile
    var concurrencyLimit: Int = 2
        set(value) {
            field = value.coerceIn(1, 5)
        }

    // 优雅暂停控制标志
    @Volatile
    var isPauseRequested = false
        private set

    // 总体进度流
    private val _progressFlow = MutableStateFlow(SyncProgressState())
    val progressFlow: StateFlow<SyncProgressState> = _progressFlow.asStateFlow()

    // 所有下载任务列表流 (用于下载弹窗)
    private val _tasksFlow = MutableStateFlow<List<DownloadTaskItem>>(emptyList())
    val tasksFlow: StateFlow<List<DownloadTaskItem>> = _tasksFlow.asStateFlow()

    // 内部常数级索引表 (用于 2000+ 超大队列极速无卡顿查询与更新)
    private val taskMap = ConcurrentHashMap<String, DownloadTaskItem>()
    private val activeDownloadFiles = ConcurrentHashMap<String, File>() // 跟踪正在下载的 .tmp 文件，防止损坏

    // 各个文件夹的下载进度流 (folderId -> FolderSyncProgress)
    private val _folderProgressMap = MutableStateFlow<Map<String, FolderSyncProgress>>(emptyMap())
    val folderProgressMap: StateFlow<Map<String, FolderSyncProgress>> = _folderProgressMap.asStateFlow()

    // 实时速度追踪表
    private val taskSpeedMap = ConcurrentHashMap<String, Long>()
    private var lastTasksEmissionTime = 0L

    private var activeJob: Job? = null
    private var appContext: Context? = null
    private var currentServerConfig: EmbyServerConfig? = null
    private var currentDownloadDir: File? = null
    private var currentDatabase: EmbySyncDatabase? = null
    private var onSongCompleteCallback: ((SyncComparisonSong) -> Unit)? = null

    /**
     * 批量或单曲加入下载同步队列
     */
    fun startBatchSync(
        context: Context? = null,
        server: EmbyServerConfig,
        songs: List<SyncComparisonSong>,
        downloadDir: File,
        database: EmbySyncDatabase,
        folderId: String? = null,
        onSongComplete: (SyncComparisonSong) -> Unit = {}
    ) {
        if (songs.isEmpty()) return

        context?.let { appContext = it.applicationContext }
        isPauseRequested = false
        currentServerConfig = server
        currentDownloadDir = downloadDir
        currentDatabase = database
        onSongCompleteCallback = onSongComplete

        val newTasks = songs.map { song ->
            DownloadTaskItem(
                id = "${song.id}_${song.embyItemId}",
                song = song,
                status = TaskStatus.QUEUED,
                folderId = folderId
            )
        }

        // $O(1)$ 常数级合并
        for (task in newTasks) {
            if (!taskMap.containsKey(task.id)) {
                taskMap[task.id] = task
            }
        }
        publishTasksImmediately()

        val allTotal = taskMap.size
        val allCompleted = taskMap.values.count { it.status == TaskStatus.COMPLETED }
        val (sessDownloaded, sessTotal) = computeSmoothSessionBytes()

        _progressFlow.value = _progressFlow.value.copy(
            isSyncing = true,
            isPausing = false,
            totalItems = allTotal,
            completedCount = allCompleted,
            currentItemIndex = allCompleted + 1,
            overallProgress = if (allTotal > 0) allCompleted.toFloat() / allTotal.toFloat() else 0f,
            sessionDownloadedBytes = sessDownloaded,
            sessionTotalBytes = sessTotal
        )

        if (folderId != null) {
            val totalInFolder = songs.size
            _folderProgressMap.value = _folderProgressMap.value + (folderId to FolderSyncProgress(folderId, 0, totalInFolder, 0f))
        }

        startProcessingQueue()
    }

    /**
     * 并发任务调度池 (基于 Semaphore 与优雅暂停机制，超出并发数的任务自动排队)
     */
    private fun startProcessingQueue() {
        if (activeJob?.isActive == true) return

        isPauseRequested = false
        activeJob = scope.launch {
            val semaphore = Semaphore(concurrencyLimit)

            while (isActive) {
                if (isPauseRequested) {
                    // 🌟 处于优雅暂停中：等待所有正在传输的活跃下载任务安全落盘
                    val isStillDownloading = taskMap.values.any { it.status == TaskStatus.DOWNLOADING }
                    if (!isStillDownloading) {
                        // 所有在途单曲已全部完整写入并入库，正式完成暂停
                        break
                    }
                    delay(150)
                    continue
                }

                val queuedTasks = taskMap.values.filter { it.status == TaskStatus.QUEUED }
                if (queuedTasks.isEmpty()) {
                    val isStillDownloading = taskMap.values.any { it.status == TaskStatus.DOWNLOADING }
                    if (!isStillDownloading) break
                    delay(150)
                    continue
                }

                for (task in queuedTasks) {
                    if (!isActive || isPauseRequested) break
                    val cur = taskMap[task.id]
                    if (cur?.status != TaskStatus.QUEUED) continue

                    semaphore.acquire()
                    launch {
                        try {
                            executeSingleDownload(task)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Unhandled exception in task ${task.song.title}", e)
                            updateTaskStatus(task.id, TaskStatus.FAILED, e.message ?: "下载异常")
                        } finally {
                            taskSpeedMap.remove(task.id)
                            activeDownloadFiles.remove(task.id)
                            semaphore.release()
                        }
                    }
                }

                delay(120)
            }

            // 队列全部处理完毕或已完全暂停
            val finalTotal = taskMap.size
            val finalCompleted = taskMap.values.count { it.status == TaskStatus.COMPLETED }
            val (sessDownloaded, sessTotal) = computeSmoothSessionBytes()

            _progressFlow.value = _progressFlow.value.copy(
                isSyncing = false,
                isPausing = false,
                totalItems = finalTotal,
                completedCount = finalCompleted,
                overallProgress = if (finalTotal > 0) finalCompleted.toFloat() / finalTotal.toFloat() else 1f,
                speedBytesPerSec = 0L,
                sessionDownloadedBytes = sessDownloaded,
                sessionTotalBytes = sessTotal
            )
        }
    }

    /**
     * 独立协程通道执行单首歌曲原子下载（使用 .tmp 临时写入与校验，保证歌曲文件绝对完整）
     */
    private suspend fun executeSingleDownload(task: DownloadTaskItem) {
        val server = currentServerConfig ?: return
        val downloadDir = currentDownloadDir ?: return
        val database = currentDatabase ?: return

        updateTaskStatus(task.id, TaskStatus.DOWNLOADING)

        val totalCount = taskMap.size
        val completedSoFar = taskMap.values.count { it.status == TaskStatus.COMPLETED }

        _progressFlow.value = _progressFlow.value.copy(
            isSyncing = true,
            totalItems = totalCount,
            completedCount = completedSoFar,
            currentItemIndex = completedSoFar + 1,
            currentItemTitle = "${task.song.title} - ${task.song.artist}"
        )

        val song = task.song
        val songItemId = song.embyItemId
        if (songItemId == null) {
            updateTaskStatus(task.id, TaskStatus.FAILED, "缺少云端 ItemId")
            return
        }

        // 目标文件路径构造：严格保持服务器相对目录层级结构
        val relDir = if (!song.relativeFolderPath.isNullOrBlank()) {
            song.relativeFolderPath.replace('\\', '/').trim('/')
        } else {
            val safeArtist = song.artist.ifBlank { "未知歌手" }
            val safeAlbum = song.album.ifBlank { "未知专辑" }
            "$safeArtist/$safeAlbum".trim('/')
        }
        val targetFolder = File(downloadDir, relDir)
        if (!targetFolder.exists()) targetFolder.mkdirs()

        val rawFileName = if (!song.embyRemotePath.isNullOrBlank()) {
            val norm = song.embyRemotePath.replace('\\', '/')
            norm.substringAfterLast('/').substringBeforeLast('.')
        } else song.title

        val safeTitle = rawFileName.ifBlank { song.title }.replace(Regex("""[/\\:*?"<>|]"""), "_")
        val ext = song.embyFormat?.lowercase()?.ifBlank { "flac" } ?: "flac"
        val targetFile = File(targetFolder, "$safeTitle.$ext")
        val tempFile = File(targetFolder, "$safeTitle.$ext.tmp")
        activeDownloadFiles[task.id] = tempFile

        var speedLastTime = System.currentTimeMillis()
        var speedLastBytes = 0L

        val result = protocol.downloadSongFile(
            config = server,
            itemId = songItemId,
            targetFile = tempFile
        ) { bytesRead: Long, totalBytes: Long ->
            val now = System.currentTimeMillis()
            val timeDiff = now - speedLastTime
            if (timeDiff >= 500L) {
                val bytesDiff = bytesRead - speedLastBytes
                val speed = (bytesDiff * 1000L) / timeDiff
                speedLastTime = now
                speedLastBytes = bytesRead
                taskSpeedMap[task.id] = speed
            }

            val progress = if (totalBytes > 0) bytesRead.toFloat() / totalBytes.toFloat() else 0f
            updateTaskProgressThrottled(task.id, progress, bytesRead, totalBytes, taskSpeedMap[task.id] ?: 0L)

            // 聚合计算多通道总速度与平滑递增的会话总字节 (消除闪烁与跳变)
            val aggregateSpeed = taskSpeedMap.values.sum()
            val (sessDownloaded, sessTotal) = computeSmoothSessionBytes()
            val overallPct = if (sessTotal > 0) sessDownloaded.toFloat() / sessTotal.toFloat() else (if (totalCount > 0) completedSoFar.toFloat() / totalCount.toFloat() else 0f)

            _progressFlow.value = _progressFlow.value.copy(
                currentItemProgress = progress,
                overallProgress = overallPct,
                totalItems = totalCount,
                completedCount = taskMap.values.count { it.status == TaskStatus.COMPLETED },
                downloadedBytes = bytesRead,
                totalBytes = totalBytes,
                sessionDownloadedBytes = sessDownloaded,
                sessionTotalBytes = sessTotal,
                speedBytesPerSec = aggregateSpeed
            )
        }

        taskSpeedMap.remove(task.id)
        activeDownloadFiles.remove(task.id)

        if (result.isSuccess && tempFile.exists() && tempFile.length() > 0) {
            // 原子重命名：确保落地文件为 100% 完整音频 (带跨介质降级保护)
            if (targetFile.exists()) targetFile.delete()
            val renameSuccess = tempFile.renameTo(targetFile)
            if (!renameSuccess) {
                try {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback copyTo targetFile failed", e)
                }
            }

            // 🌟 立即通知 Android 系统 MediaScanner 建立媒体库索引 (彻底消除椒盐音乐等第三方播放器扫描延迟)
            appContext?.let { ctx ->
                notifyMediaScanner(ctx, targetFile.absolutePath, getAudioMimeType(ext))
            }

            updateTaskStatus(task.id, TaskStatus.COMPLETED)

            val updatedSong = song.copy(
                localFilePath = targetFile.absolutePath,
                localFileSize = targetFile.length(),
                localFormat = ext,
                localBitRate = song.embyBitRate,
                localLastModified = targetFile.lastModified(),
                syncStatus = SyncStatus.SYNCED,
                diffReason = "已同步至本地最新"
            )

            database.logDao().insertLog(
                SyncLogEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    songTitle = song.title,
                    artist = song.artist,
                    actionType = if (song.syncStatus == SyncStatus.DIFF_UPGRADE) "升级替换" else "同步下载",
                    sizeBytes = targetFile.length(),
                    status = "成功",
                    detailMessage = "保存至: ${targetFile.absolutePath}"
                )
            )

            // 更新文件夹进度
            task.folderId?.let { fId ->
                val cur = _folderProgressMap.value[fId]
                if (cur != null) {
                    val newCompleted = cur.completedCount + 1
                    val newPct = if (cur.totalCount > 0) newCompleted.toFloat() / cur.totalCount.toFloat() else 1f
                    _folderProgressMap.value = _folderProgressMap.value + (fId to cur.copy(completedCount = newCompleted, currentPercent = newPct))
                }
            }

            val updatedCompleted = taskMap.values.count { it.status == TaskStatus.COMPLETED }
            val (sessDownloaded, sessTotal) = computeSmoothSessionBytes()
            _progressFlow.value = _progressFlow.value.copy(
                completedCount = updatedCompleted,
                totalItems = taskMap.size,
                overallProgress = if (taskMap.isNotEmpty()) updatedCompleted.toFloat() / taskMap.size.toFloat() else 1f,
                sessionDownloadedBytes = sessDownloaded,
                sessionTotalBytes = sessTotal
            )

            withContext(Dispatchers.Main) {
                onSongCompleteCallback?.invoke(updatedSong)
            }
        } else {
            // 清理未完成的 .tmp 临时残余
            if (tempFile.exists()) tempFile.delete()
            val err = result.exceptionOrNull()?.message ?: "下载失败"
            updateTaskStatus(task.id, TaskStatus.FAILED, err)
        }
    }

    /**
     * 极速计算平滑单调递增的会话总下载字节与预估总字节（彻底消除并发下载时的数字跳动闪烁）
     */
    private fun computeSmoothSessionBytes(): Pair<Long, Long> {
        var totalBytes = 0L
        var downloadedBytes = 0L

        for (item in taskMap.values) {
            val itemSize = if (item.totalBytes > 0) item.totalBytes else if (item.song.embyFileSize > 0) item.song.embyFileSize else 0L
            totalBytes += itemSize

            when (item.status) {
                TaskStatus.COMPLETED -> downloadedBytes += itemSize
                TaskStatus.DOWNLOADING -> downloadedBytes += item.downloadedBytes.coerceAtMost(itemSize.takeIf { it > 0 } ?: Long.MAX_VALUE)
                else -> {}
            }
        }

        return Pair(downloadedBytes, totalBytes)
    }

    private fun updateTaskStatus(taskId: String, status: TaskStatus, errorMessage: String? = null) {
        val current = taskMap[taskId]
        if (current != null) {
            taskMap[taskId] = current.copy(status = status, errorMessage = errorMessage)
            publishTasksImmediately()
        }
    }

    // 高频下载进度更新：内存中即时修改，流式向 Compose 推送节流（每 200ms 一次，消除 2000+ 任务的 GC 压力）
    private fun updateTaskProgressThrottled(taskId: String, progress: Float, downloadedBytes: Long, totalBytes: Long, speed: Long) {
        val current = taskMap[taskId]
        if (current != null) {
            taskMap[taskId] = current.copy(
                progress = progress,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                speedBytesPerSec = speed
            )
            val now = System.currentTimeMillis()
            if (now - lastTasksEmissionTime >= 200L) {
                lastTasksEmissionTime = now
                _tasksFlow.value = taskMap.values.toList()
            }
        }
    }

    private fun publishTasksImmediately() {
        lastTasksEmissionTime = System.currentTimeMillis()
        _tasksFlow.value = taskMap.values.toList()
    }

    // 暂停单个任务
    fun pauseTask(taskId: String) {
        updateTaskStatus(taskId, TaskStatus.PAUSED)
    }

    // 继续单个任务
    fun resumeTask(taskId: String) {
        updateTaskStatus(taskId, TaskStatus.QUEUED)
        startProcessingQueue()
    }

    // 删除/取消单个任务
    fun deleteTask(taskId: String) {
        taskMap.remove(taskId)
        publishTasksImmediately()
    }

    /**
     * 优雅暂停：立即停止分发新任务，并标记 isPausing 状态，已处于传输中的单曲保证其完整下载入库后再挂起，绝不生成损坏文件
     */
    fun pauseAll() {
        isPauseRequested = true

        // 将所有排队等待中的任务置为 PAUSED
        for ((id, item) in taskMap) {
            if (item.status == TaskStatus.QUEUED) {
                taskMap[id] = item.copy(status = TaskStatus.PAUSED)
            }
        }
        publishTasksImmediately()

        val hasActiveDownloading = taskMap.values.any { it.status == TaskStatus.DOWNLOADING }
        if (hasActiveDownloading) {
            _progressFlow.value = _progressFlow.value.copy(isPausing = true, isSyncing = true)
        } else {
            _progressFlow.value = _progressFlow.value.copy(isSyncing = false, isPausing = false, speedBytesPerSec = 0L)
        }
    }

    /**
     * 全部恢复继续下载
     */
    fun resumeAll() {
        isPauseRequested = false
        for ((id, item) in taskMap) {
            if (item.status == TaskStatus.PAUSED || item.status == TaskStatus.FAILED) {
                taskMap[id] = item.copy(status = TaskStatus.QUEUED)
            }
        }
        publishTasksImmediately()

        val allTotal = taskMap.size
        val allCompleted = taskMap.values.count { it.status == TaskStatus.COMPLETED }
        val (sessDownloaded, sessTotal) = computeSmoothSessionBytes()

        _progressFlow.value = _progressFlow.value.copy(
            isPausing = false,
            isSyncing = true,
            totalItems = allTotal,
            completedCount = allCompleted,
            sessionDownloadedBytes = sessDownloaded,
            sessionTotalBytes = sessTotal
        )
        startProcessingQueue()
    }

    /**
     * 彻底取消全部下载并清理全部 .tmp 临时残余文件
     */
    fun cancelAll() {
        isPauseRequested = true
        activeJob?.cancel()

        // 清理所有在写 .tmp 临时文件
        for (f in activeDownloadFiles.values) {
            try {
                if (f.exists()) f.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning temp file", e)
            }
        }
        activeDownloadFiles.clear()
        taskSpeedMap.clear()
        taskMap.clear()
        publishTasksImmediately()

        _progressFlow.value = SyncProgressState(isSyncing = false, isPausing = false, totalItems = 0, completedCount = 0, overallProgress = 0f, speedBytesPerSec = 0L)
    }

    // 批量删除
    fun deleteTasks(taskIds: Set<String>) {
        for (id in taskIds) {
            taskMap.remove(id)
        }
        publishTasksImmediately()
    }

    // 清理已完成任务
    fun clearCompleted() {
        val iterator = taskMap.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.status == TaskStatus.COMPLETED) {
                iterator.remove()
            }
        }
        publishTasksImmediately()
    }

    // 切换任务选中状态
    fun toggleTaskSelection(taskId: String) {
        val cur = taskMap[taskId]
        if (cur != null) {
            taskMap[taskId] = cur.copy(isSelected = !cur.isSelected)
            publishTasksImmediately()
        }
    }

    // 全选/取消全选
    fun setAllTasksSelected(selected: Boolean) {
        for ((id, item) in taskMap) {
            taskMap[id] = item.copy(isSelected = selected)
        }
        publishTasksImmediately()
    }

    /**
     * 主动通知 Android 系统 MediaScanner 索引新下载/修改的音频文件
     * 消除第三方播放器（如椒盐音乐、Poweramp等）扫描本地歌曲的延时
     */
    fun notifyMediaScanner(context: Context, filePath: String, mimeType: String? = null) {
        try {
            val mType = mimeType ?: getAudioMimeType(filePath)
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(filePath),
                if (mType.isNotBlank()) arrayOf(mType) else null
            ) { path, uri ->
                Log.i(TAG, "MediaScanner indexed successfully: $path -> $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying MediaScanner for $filePath", e)
        }
    }

    /**
     * 本地删除文件时，通知系统 MediaStore 同步清理脏数据，防止第三方播放器出现失效歌曲
     */
    fun notifyMediaDeleted(context: Context, filePath: String) {
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(filePath),
                null
            ) { path, _ ->
                Log.i(TAG, "MediaScanner clean-up indexed for deleted file: $path")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying MediaScanner delete for $filePath", e)
        }
    }

    fun getAudioMimeType(filePathOrExt: String): String {
        val ext = filePathOrExt.substringAfterLast('.', filePathOrExt).lowercase()
        return when (ext) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/x-wav"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "ape" -> "audio/x-ape"
            "dsf" -> "audio/x-dsf"
            "dff" -> "audio/x-dff"
            "wma" -> "audio/x-ms-wma"
            "alac" -> "audio/alac"
            else -> "audio/*"
        }
    }
}

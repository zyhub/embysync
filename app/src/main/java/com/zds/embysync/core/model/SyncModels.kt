package com.zds.embysync.core.model

enum class SyncStatus {
    SYNCED,         // 🟢 本地已同步 (完全匹配一致)
    NEED_DOWNLOAD,  // 🔵 待下载 (Emby 云端有，本地尚未下载)
    DIFF_UPGRADE,   // 🔴 差异/可升级 (Emby 端音质更高，可升级替换)
    IGNORED         // ⚪ 已忽略 (命中过滤规则)
}

enum class SyncFilterCategory(val label: String) {
    ALL("全部"),
    SYNCED("已下载"),
    NEED_DOWNLOAD("待下载"),
    DIFF_UPGRADE("可升级"),
    IGNORED("已忽略")
}

data class EmbyServerConfig(
    val id: String = "emby_default",
    val name: String = "我的 Emby 服务器",
    val serverUrl: String = "http://192.168.1.100:8096",
    val username: String = "",
    val tokenOrApiKey: String = "",
    val userId: String = "",
    val isCurrentActive: Boolean = true,
    val lastConnectedTime: Long = 0L
)

data class SyncComparisonSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val embyItemId: String? = null,
    val embyRemotePath: String? = null,
    val embyFormat: String? = null,
    val embyBitRate: Int? = null,
    val embyFileSize: Long = 0L,
    val localFilePath: String? = null,
    val localFormat: String? = null,
    val localBitRate: Int? = null,
    val localFileSize: Long = 0L,
    val localLastModified: Long = 0L,
    val syncStatus: SyncStatus = SyncStatus.NEED_DOWNLOAD,
    val isSelected: Boolean = false,
    val relativeFolderPath: String? = null,
    val diffReason: String? = null
)

data class SyncFilterConfig(
    val minSizeBytes: Long = 0L,
    val maxSizeBytes: Long = 0L,
    val ignoreDotFiles: Boolean = true,
    val ignoredExtensions: Set<String> = setOf("lnk", "pst", "swp", "tmp", "bak", "nfo", "jpg", "png", "lrc"),
    val selectiveFolderPaths: Set<String> = emptySet()
)

data class SongDiffResult(
    val allSongs: List<SyncComparisonSong> = emptyList(),
    val syncedCount: Int = 0,
    val needDownloadCount: Int = 0,
    val diffUpgradeCount: Int = 0,
    val ignoredCount: Int = 0
)

data class SyncFolderCompareItem(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val fullPath: String = "",
    val isFolder: Boolean = true,
    val totalSongs: Int = 0,
    val syncedSongs: Int = 0,
    val needDownloadSongs: Int = 0,
    val needUploadSongs: Int = 0,
    val diffSongs: Int = 0
)

data class SyncProgressState(
    val isSyncing: Boolean = false,
    val isPausing: Boolean = false, // 正在执行优雅暂停等待中
    val totalItems: Int = 0,
    val currentItemIndex: Int = 0,
    val completedCount: Int = 0,
    val currentItemTitle: String = "",
    val currentItemProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val sessionDownloadedBytes: Long = 0L,
    val sessionTotalBytes: Long = 0L,
    val errorMessage: String? = null
)

data class SyncLogItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val songTitle: String,
    val artist: String,
    val actionType: String, // 下载, 升级替换, 本地扫描, 忽略, 错误
    val sizeBytes: Long = 0L,
    val status: String,     // 成功, 失败, 跳过
    val detailMessage: String = ""
)

enum class LibraryNavTab(val label: String) {
    SONGS("歌曲"),
    FOLDERS("文件夹"),
    ARTISTS("艺术家"),
    ALBUMS("专辑")
}

data class ServerFolderItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val parentId: String? = null,
    val childCount: Int = 0,
    val coverUrl: String? = null,
    val song: SyncComparisonSong? = null
)

data class UnifiedArtist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val coverUrl: String = ""
)

data class UnifiedAlbum(
    val id: String,
    val name: String,
    val artist: String = "",
    val songCount: Int = 0,
    val coverUrl: String = ""
)

enum class TaskStatus(val label: String) {
    QUEUED("等待中"),
    DOWNLOADING("下载中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("失败")
}

data class DownloadTaskItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val song: SyncComparisonSong,
    val status: TaskStatus = TaskStatus.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    val isSelected: Boolean = false,
    val folderId: String? = null
)

data class FolderProgressInfo(
    val folderId: String,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val currentPercent: Float
)

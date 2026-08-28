package com.zds.embysync.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zds.embysync.core.model.SyncStatus

@Entity(tableName = "emby_servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val serverUrl: String,
    val username: String = "",
    val tokenOrApiKey: String = "",
    val userId: String = "",
    val isCurrentActive: Boolean = true,
    val lastConnectedTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_comparison_cache")
data class SyncComparisonEntity(
    @PrimaryKey val id: String,
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
    val relativeFolderPath: String? = null,
    val diffReason: String? = null,
    val lastComparedTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val songTitle: String,
    val artist: String,
    val actionType: String,
    val sizeBytes: Long,
    val status: String,
    val detailMessage: String
)

package com.zds.embysync.core.database.dao

import androidx.room.*
import com.zds.embysync.core.database.entity.ServerEntity
import com.zds.embysync.core.database.entity.SyncComparisonEntity
import com.zds.embysync.core.database.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM emby_servers")
    fun getAllServersFlow(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM emby_servers")
    suspend fun getAllServers(): List<ServerEntity>

    @Query("SELECT * FROM emby_servers WHERE isCurrentActive = 1 LIMIT 1")
    suspend fun getActiveServer(): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity)

    @Update
    suspend fun updateServer(server: ServerEntity)

    @Query("DELETE FROM emby_servers WHERE id = :id")
    suspend fun deleteServer(id: String)

    @Query("UPDATE emby_servers SET isCurrentActive = 0")
    suspend fun deactivateAllServers()

    @Query("UPDATE emby_servers SET isCurrentActive = 1 WHERE id = :id")
    suspend fun activateServer(id: String)
}

@Dao
interface SyncComparisonDao {
    @Query("SELECT * FROM sync_comparison_cache ORDER BY title ASC")
    fun getAllComparisonsFlow(): Flow<List<SyncComparisonEntity>>

    @Query("SELECT * FROM sync_comparison_cache ORDER BY title ASC")
    suspend fun getAllComparisons(): List<SyncComparisonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComparisons(items: List<SyncComparisonEntity>)

    @Query("DELETE FROM sync_comparison_cache")
    suspend fun clearAll()

    @Query("UPDATE sync_comparison_cache SET syncStatus = :newStatus, localFilePath = :localPath, localFileSize = :fileSize WHERE id = :id")
    suspend fun markAsSynced(id: String, newStatus: com.zds.embysync.core.model.SyncStatus, localPath: String, fileSize: Long)
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT 300")
    fun getAllLogsFlow(): Flow<List<SyncLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SyncLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<SyncLogEntity>)

    @Query("DELETE FROM sync_logs")
    suspend fun clearLogs()
}

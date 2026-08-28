package com.zds.embysync.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.zds.embysync.core.database.dao.ServerDao
import com.zds.embysync.core.database.dao.SyncComparisonDao
import com.zds.embysync.core.database.dao.SyncLogDao
import com.zds.embysync.core.database.entity.ServerEntity
import com.zds.embysync.core.database.entity.SyncComparisonEntity
import com.zds.embysync.core.database.entity.SyncLogEntity

@Database(
    entities = [
        ServerEntity::class,
        SyncComparisonEntity::class,
        SyncLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class EmbySyncDatabase : RoomDatabase() {

    abstract fun serverDao(): ServerDao
    abstract fun comparisonDao(): SyncComparisonDao
    abstract fun logDao(): SyncLogDao

    companion object {
        @Volatile
        private var instance: EmbySyncDatabase? = null

        fun getInstance(context: Context): EmbySyncDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EmbySyncDatabase::class.java,
                    "emby_sync_tool.db"
                ).fallbackToDestructiveMigration()
                 .build()
                 .also { instance = it }
            }
        }
    }
}

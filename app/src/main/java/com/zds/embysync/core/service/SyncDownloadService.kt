package com.zds.embysync.core.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zds.embysync.MainActivity
import com.zds.embysync.R
import com.zds.embysync.core.engine.SyncEngine
import kotlinx.coroutines.*

class SyncDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var lastNotificationUpdateTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildNotification("准备开始同步...", "正在初始化下载队列", 0, 100, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        startObservingProgress()
        return START_NOT_STICKY
    }

    private fun startObservingProgress() {
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            SyncEngine.progressFlow.collect { state ->
                if (!state.isSyncing && !state.isPausing) {
                    // 同步结束或已完全停止
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }

                val now = System.currentTimeMillis()
                // 控制通知栏刷新频率（至少间隔 350ms）
                if (now - lastNotificationUpdateTime >= 350L || state.isPausing) {
                    lastNotificationUpdateTime = now

                    val title = if (state.isPausing) {
                        "正在优雅暂停中..."
                    } else {
                        "正在同步 (${state.completedCount + 1}/${state.totalItems})"
                    }

                    val speedMb = state.speedBytesPerSec / (1024.0 * 1024.0)
                    val speedText = if (speedMb >= 0.1) String.format("%.1f MB/s", speedMb) else "${state.speedBytesPerSec / 1024} KB/s"
                    val content = if (state.isPausing) {
                        "等待在途单曲落盘校验入库..."
                    } else {
                        "${state.currentItemTitle.ifBlank { "正在传输音频" }} • $speedText"
                    }

                    val pct = (state.overallProgress * 100).toInt().coerceIn(0, 100)
                    val notification = buildNotification(title, content, pct, 100, false)

                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int,
        maxProgress: Int,
        indeterminate: Boolean
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(maxProgress, progress, false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EMBYsync 后台下载同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "展示实时同步速率、进度及当前下载曲目"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "embysync_download_channel"
        private const val NOTIFICATION_ID = 10086

        fun start(context: Context) {
            try {
                val intent = Intent(context, SyncDownloadService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                android.util.Log.e("SyncDownloadService", "Failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, SyncDownloadService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                android.util.Log.e("SyncDownloadService", "Failed to stop foreground service", e)
            }
        }
    }
}

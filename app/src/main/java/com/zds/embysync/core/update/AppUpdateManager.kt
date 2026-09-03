package com.zds.embysync.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val latestVersionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSizeBytes: Long = 0L,
    val isForceUpdate: Boolean = false
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    const val CURRENT_VERSION_NAME = "1.2.2"
    const val CURRENT_VERSION_CODE = 23
    const val AUTHOR_NAME = "zhou"
    const val AUTHOR_EMAIL = "1390999045@qq.com"
    const val APP_DESCRIPTION = "EMBYsync - 高性能 Emby / Jellyfin 音乐全量同步与曲库看板工具。"

    // 默认 GitHub 仓库
    const val DEFAULT_GITHUB_REPO = "zyhub/embysync"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 检查新版本 (支持 GitHub 官方 Releases API，并在超时或异常时自动无缝降级至多镜像节点)
     */
    suspend fun checkForUpdates(context: Context, customUrlOrRepo: String? = null): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val targetRepo = customUrlOrRepo?.ifBlank { null } ?: DEFAULT_GITHUB_REPO
        var lastException: Exception? = null

        // 构造候选检查地址：官方 GitHub API 优先，备用国内高速 CDN 镜像 (读取 version.json 静态配置)
        val apiEndpoints = if (targetRepo.startsWith("http://") || targetRepo.startsWith("https://")) {
            listOf(targetRepo)
        } else {
            listOf(
                "https://api.github.com/repos/${targetRepo.trim()}/releases/latest",
                "https://ghfast.top/https://raw.githubusercontent.com/${targetRepo.trim()}/main/version.json",
                "https://ghproxy.net/https://raw.githubusercontent.com/${targetRepo.trim()}/main/version.json",
                "https://raw.githubusercontent.com/${targetRepo.trim()}/main/version.json"
            )
        }

        for (endpoint in apiEndpoints) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/vnd.github.v3+json, application/json")
                    .header("User-Agent", "EMBYsync-App")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)

                        // 模式 1：标准 GitHub Releases API 返回格式
                        if (json.has("tag_name")) {
                            val tagName = json.optString("tag_name", "").trim()
                            val cleanVersion = tagName.trimStart('v', 'V')
                            val notes = json.optString("body", "性能优化与功能增强").ifBlank { "版本更新 $tagName" }
                            val assets = json.optJSONArray("assets")
                            var apkDownloadUrl = ""
                            var apkSize = 0L

                            if (assets != null && assets.length() > 0) {
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    val name = asset.optString("name", "").lowercase()
                                    if (name.endsWith(".apk")) {
                                        apkDownloadUrl = asset.optString("browser_download_url", "")
                                        apkSize = asset.optLong("size", 0L)
                                        break
                                    }
                                }
                            }

                            val hasUpdate = isVersionNewer(cleanVersion, CURRENT_VERSION_NAME)
                            return@withContext Result.success(
                                UpdateInfo(
                                    hasUpdate = hasUpdate,
                                    latestVersion = cleanVersion,
                                    latestVersionCode = CURRENT_VERSION_CODE + (if (hasUpdate) 1 else 0),
                                    releaseNotes = notes,
                                    downloadUrl = apkDownloadUrl,
                                    apkSizeBytes = apkSize,
                                    isForceUpdate = false
                                )
                            )
                        } else if (json.has("versionName")) {
                            // 模式 2：version.json 静态镜像配置格式
                            val cleanVersion = json.optString("versionName", "").trim().trimStart('v', 'V')
                            val vCode = json.optInt("versionCode", CURRENT_VERSION_CODE)
                            val notes = json.optString("releaseNotes", "性能优化与功能增强")
                            val dlUrl = json.optString("downloadUrl", "")
                            val apkSize = json.optLong("apkSizeBytes", 0L)

                            val hasUpdate = isVersionNewer(cleanVersion, CURRENT_VERSION_NAME)
                            return@withContext Result.success(
                                UpdateInfo(
                                    hasUpdate = hasUpdate,
                                    latestVersion = cleanVersion,
                                    latestVersionCode = vCode,
                                    releaseNotes = notes,
                                    downloadUrl = dlUrl,
                                    apkSizeBytes = apkSize,
                                    isForceUpdate = false
                                )
                            )
                        }
                        Unit
                    } else {
                        Log.w(TAG, "Endpoint $endpoint returned HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Failed checking update at $endpoint: ${e.message}")
            }
        }

        if (lastException != null) {
            Result.failure(lastException)
        } else {
            Result.failure(Exception("无法连接更新服务器，请确认网络畅通"))
        }
    }

    /**
     * 语义化版本号比较
     */
    fun isVersionNewer(latestVersion: String, currentVersion: String): Boolean {
        val lParts = latestVersion.trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
        val cParts = currentVersion.trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(lParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * 原子完整性下载 APK 安装包 (自动优先国内高可用镜像加速通道，杜绝超时中断)
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
        val finalApkFile = File(updateDir, "EMBYsync_update.apk")
        val tempApkFile = File(updateDir, "EMBYsync_update.apk.tmp")

        // 构造候选下载通道 (国内 GitHub 加速镜像优先)
        val candidateUrls = mutableListOf<String>()
        if (downloadUrl.startsWith("https://github.com/")) {
            candidateUrls.add("https://ghfast.top/$downloadUrl")
            candidateUrls.add("https://ghproxy.net/$downloadUrl")
        }
        candidateUrls.add(downloadUrl)

        var lastError: Exception? = null

        for (targetUrl in candidateUrls) {
            try {
                if (tempApkFile.exists()) tempApkFile.delete()
                if (finalApkFile.exists()) finalApkFile.delete()

                val request = Request.Builder().url(targetUrl).build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
                    val body = response.body ?: throw Exception("响应体为空")
                    val totalLength = body.contentLength()
                    var totalBytesRead = 0L

                    body.byteStream().use { inputStream ->
                        FileOutputStream(tempApkFile).use { outputStream ->
                            val buffer = ByteArray(32 * 1024)
                            var bytesRead: Int
                            var lastCallbackTime = 0L

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                val now = System.currentTimeMillis()
                                if (now - lastCallbackTime >= 120L || totalBytesRead == totalLength) {
                                    lastCallbackTime = now
                                    val progress = if (totalLength > 0) totalBytesRead.toFloat() / totalLength else 0f
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress, totalBytesRead, totalLength)
                                    }
                                }
                            }
                            outputStream.flush()
                        }
                    }

                    // 文件完整性校验
                    if (totalLength > 0 && totalBytesRead < totalLength) {
                        if (tempApkFile.exists()) tempApkFile.delete()
                        throw Exception("下载中断，已下载 $totalBytesRead 字节 / 总共 $totalLength 字节")
                    }
                    if (tempApkFile.length() < 1024 * 1024) { // 小于 1MB 判定为异常
                        if (tempApkFile.exists()) tempApkFile.delete()
                        throw Exception("下载的安装包大小异常 (${tempApkFile.length()} 字节)")
                    }

                    // 原子重命名与跨介质降级
                    if (finalApkFile.exists()) finalApkFile.delete()
                    val renameSuccess = tempApkFile.renameTo(finalApkFile)
                    if (!renameSuccess) {
                        tempApkFile.copyTo(finalApkFile, overwrite = true)
                        tempApkFile.delete()
                    }

                    if (!finalApkFile.exists() || finalApkFile.length() <= 0L) {
                        throw Exception("未能成功生成目标安装包文件")
                    }

                    return@withContext Result.success(finalApkFile)
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Downloading APK failed from $targetUrl: ${e.message}, trying next mirror...")
            }
        }

        Result.failure(lastError ?: Exception("下载更新安装包失败"))
    }

    /**
     * 调起系统安装器执行覆盖安装更新 (带 FileProvider 与 Android 8.0+ 权限引导)
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() <= 0) {
                Toast.makeText(context, "安装包不存在或已损坏，请重新下载", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hasInstallPermission = context.packageManager.canRequestPackageInstalls()
                if (!hasInstallPermission) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, "请开启「允许安装未知应用」权限以完成更新", Toast.LENGTH_LONG).show()
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, "调起系统安装器失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

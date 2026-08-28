package com.zds.embysync.core.network

import android.util.Log
import com.zds.embysync.core.model.EmbyServerConfig
import com.zds.embysync.core.model.ServerFolderItem
import com.zds.embysync.core.model.SyncComparisonSong
import com.zds.embysync.core.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class EmbySyncProtocol(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    private val TAG = "EmbySyncProtocol"

    private fun cleanBaseUrl(url: String): String = url.trimEnd('/')

    private fun buildAuthHeader(token: String): String {
        return if (token.isNotBlank()) {
            "MediaBrowser Client=\"ZDSPlayer\", Device=\"Android\", DeviceId=\"zds_android_device_001\", DeviceName=\"Android\", Version=\"1.0.0\", Token=\"$token\""
        } else {
            "MediaBrowser Client=\"ZDSPlayer\", Device=\"Android\", DeviceId=\"zds_android_device_001\", DeviceName=\"Android\", Version=\"1.0.0\""
        }
    }

    /**
     * 核心相对目录结构提取：严格去除服务端挂载盘符前缀，提取出相对于音乐媒体库的子目录结构
     */
    fun extractRelativeFolderPath(serverPath: String, artist: String, album: String): String {
        if (serverPath.isNotBlank()) {
            val normalized = serverPath.replace('\\', '/')
            val parentDir = normalized.substringBeforeLast('/', "")
            if (parentDir.isNotBlank()) {
                val segments = parentDir.split('/').filter {
                    it.isNotBlank() &&
                            !it.equals("music", ignoreCase = true) &&
                            !it.equals("musics", ignoreCase = true) &&
                            !it.equals("media", ignoreCase = true) &&
                            !it.equals("mnt", ignoreCase = true) &&
                            !it.equals("storage", ignoreCase = true) &&
                            !it.equals("emby", ignoreCase = true) &&
                            !it.equals("share", ignoreCase = true) &&
                            !it.startsWith("volume", ignoreCase = true) &&
                            !it.equals("d:", ignoreCase = true) &&
                            !it.equals("e:", ignoreCase = true) &&
                            !it.equals("c:", ignoreCase = true) &&
                            !it.equals("f:", ignoreCase = true) &&
                            !it.contains(":")
                }
                if (segments.isNotEmpty()) {
                    return segments.joinToString("/")
                }
            }
        }
        val safeArtist = if (artist.isNotBlank() && !artist.contains("未知")) artist else ""
        val safeAlbum = if (album.isNotBlank() && !album.contains("未知")) album else ""
        return when {
            safeArtist.isNotBlank() && safeAlbum.isNotBlank() -> "$safeArtist/$safeAlbum"
            safeArtist.isNotBlank() -> safeArtist
            else -> ""
        }
    }

    /**
     * 核心 Emby / Jellyfin 鉴权逻辑 (完全复用 ZDSPlayer)
     */
    suspend fun authenticate(config: EmbyServerConfig, secretOrPassword: String = ""): Result<EmbyServerConfig> = withContext(Dispatchers.IO) {
        try {
            val user = config.username.trim()
            val secret = if (secretOrPassword.isNotBlank()) secretOrPassword.trim() else config.tokenOrApiKey.trim()

            if (user.isNotBlank()) {
                // 方式 1: 用户名 + 密码鉴权
                val jsonBody = JSONObject().apply {
                    put("Username", user)
                    put("Pw", secret)
                }.toString()

                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                val endpoints = listOf(
                    "${cleanBaseUrl(config.serverUrl)}/Users/AuthenticateByName",
                    "${cleanBaseUrl(config.serverUrl)}/emby/Users/AuthenticateByName"
                )

                var lastErr: Exception? = null
                for (url in endpoints) {
                    try {
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("X-Emby-Authorization", buildAuthHeader(""))
                            .addHeader("Content-Type", "application/json")
                            .post(requestBody)
                            .build()

                        client.newCall(request).execute().use { response ->
                            val responseBody = response.body?.string() ?: ""
                            if (response.isSuccessful && responseBody.isNotBlank()) {
                                val json = JSONObject(responseBody)
                                val token = json.optString("AccessToken", json.optString("Token", ""))
                                val userObj = json.optJSONObject("User")
                                val uid = userObj?.optString("Id", "") ?: json.optString("UserId", "")
                                val srvObj = json.optJSONObject("Server")
                                val serverName = srvObj?.optString("Name", config.name) ?: config.name

                                if (token.isNotBlank()) {
                                    Log.i(TAG, "Emby Authenticated successfully: userId=$uid, token=$token")
                                    return@withContext Result.success(
                                        config.copy(
                                            tokenOrApiKey = token,
                                            userId = uid,
                                            name = serverName.ifBlank { config.name },
                                            lastConnectedTime = System.currentTimeMillis()
                                        )
                                    )
                                }
                            } else if (response.code == 401) {
                                lastErr = Exception("用户名或密码错误 (401)")
                            } else {
                                lastErr = Exception("Emby 鉴权失败: HTTP ${response.code}")
                            }
                        }
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
                Result.failure(lastErr ?: Exception("无法连接到 Emby 服务器"))
            } else {
                // 方式 2: API Key 鉴权
                val urls = listOf(
                    "${cleanBaseUrl(config.serverUrl)}/System/Info?api_key=$secret",
                    "${cleanBaseUrl(config.serverUrl)}/emby/System/Info?api_key=$secret"
                )
                var lastErr: Exception? = null
                for (url in urls) {
                    try {
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("X-Emby-Authorization", buildAuthHeader(secret))
                            .addHeader("X-Emby-Token", secret)
                            .get()
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                val json = try { JSONObject(body) } catch (_: Exception) { null }
                                val sName = json?.optString("ServerName", config.name) ?: config.name
                                return@withContext Result.success(
                                    config.copy(
                                        tokenOrApiKey = secret,
                                        name = sName.ifBlank { config.name },
                                        lastConnectedTime = System.currentTimeMillis()
                                    )
                                )
                            } else {
                                lastErr = Exception("HTTP ${response.code}")
                            }
                        }
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
                Result.failure(lastErr ?: Exception("Emby API Key 验证失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authentication error", e)
            Result.failure(e)
        }
    }

    /**
     * 自动补全鉴权凭据与用户 ID
     */
    private suspend fun ensureAuthenticated(config: EmbyServerConfig): EmbyServerConfig {
        if (config.tokenOrApiKey.isNotBlank() && config.userId.isNotBlank()) return config
        val authRes = authenticate(config)
        return if (authRes.isSuccess) authRes.getOrNull() ?: config else config
    }

    suspend fun ping(config: EmbyServerConfig): Boolean = withContext(Dispatchers.IO) {
        if (config.serverUrl.isBlank()) return@withContext false
        try {
            val validConfig = ensureAuthenticated(config)
            val token = validConfig.tokenOrApiKey
            val urls = listOf(
                "${cleanBaseUrl(config.serverUrl)}/System/Info?api_key=$token",
                "${cleanBaseUrl(config.serverUrl)}/emby/System/Info?api_key=$token",
                "${cleanBaseUrl(config.serverUrl)}/System/Info"
            )
            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("X-Emby-Authorization", buildAuthHeader(token))
                        .addHeader("X-Emby-Token", token)
                        .get()
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) return@withContext true
                    }
                } catch (_: Exception) {}
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取服务器文件夹列表 (完全复用 ZDSPlayer 核心穿透逻辑)
     */
    suspend fun getFolders(config: EmbyServerConfig, parentId: String?): Result<List<ServerFolderItem>> = withContext(Dispatchers.IO) {
        try {
            val validConfig = ensureAuthenticated(config)
            val token = validConfig.tokenOrApiKey
            val uid = validConfig.userId
            val userPath = if (uid.isNotBlank()) "/Users/$uid" else ""
            val isRoot = parentId.isNullOrBlank()

            // 1. 如果指定了 parentId (子目录)，直接请求该目录下的子项
            if (!isRoot) {
                val candidateUrls = listOf(
                    "${cleanBaseUrl(config.serverUrl)}$userPath/Items?ParentId=$parentId&Fields=MediaSources,Path,UserData,ChildCount,RecursiveItemCount,Container,Bitrate,RunTimeTicks,AlbumArtist,Artists,Album&SortBy=IsFolder,SortName&SortOrder=Ascending&api_key=$token",
                    "${cleanBaseUrl(config.serverUrl)}/Items?ParentId=$parentId&Fields=MediaSources,Path,UserData,ChildCount,RecursiveItemCount,Container,Bitrate,RunTimeTicks,AlbumArtist,Artists,Album&SortBy=IsFolder,SortName&SortOrder=Ascending&api_key=$token"
                )
                for (url in candidateUrls) {
                    try {
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("X-Emby-Authorization", buildAuthHeader(token))
                            .addHeader("X-Emby-Token", token)
                            .get()
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                val json = try { JSONObject(body) } catch (_: Exception) { null }
                                val items = json?.optJSONArray("Items") ?: try { JSONArray(body) } catch (_: Exception) { null }
                                if (items != null) {
                                    val results = mutableListOf<ServerFolderItem>()
                                    for (i in 0 until items.length()) {
                                        val item = items.getJSONObject(i)
                                        val id = item.optString("Id")
                                        val name = item.optString("Name", "未命名项目")
                                        val type = item.optString("Type", "")
                                        val collectionType = item.optString("CollectionType", "").lowercase()
                                        val isFolder = item.optBoolean("IsFolder", false) ||
                                                type.contains("Folder", ignoreCase = true) ||
                                                type.contains("View", ignoreCase = true) ||
                                                type.contains("Collection", ignoreCase = true) ||
                                                collectionType.isNotBlank() ||
                                                type.equals("MusicAlbum", ignoreCase = true) ||
                                                type.equals("MusicArtist", ignoreCase = true)
                                        val childCount = item.optInt("ChildCount", item.optInt("RecursiveItemCount", 0))

                                        val song = if (!isFolder && (type.equals("Audio", ignoreCase = true) || item.optString("MediaType").equals("Audio", ignoreCase = true) || item.has("RunTimeTicks"))) {
                                            val durationMs = item.optLong("RunTimeTicks", 0L) / 10000L
                                            val mediaSources = item.optJSONArray("MediaSources")
                                            val firstSource = if (mediaSources != null && mediaSources.length() > 0) mediaSources.getJSONObject(0) else null
                                            val bitRate = (firstSource?.optLong("Bitrate", 320000L) ?: 320000L) / 1000
                                            val fileSize = firstSource?.optLong("Size", 0L) ?: 0L
                                            val container = item.optString("Container", "flac").lowercase()
                                            val artistArray = item.optJSONArray("Artists")
                                            val artistName = if (artistArray != null && artistArray.length() > 0) {
                                                (0 until artistArray.length()).joinToString(", ") { artistArray.getString(it) }
                                            } else {
                                                item.optString("AlbumArtist", "未知艺术家")
                                            }
                                            val albumName = item.optString("Album", "")
                                            val remotePath = item.optString("Path", "")
                                            val relPath = extractRelativeFolderPath(remotePath, artistName, albumName)
                                            val coverUrl = "${cleanBaseUrl(config.serverUrl)}/Items/$id/Images/Primary?maxHeight=160&maxWidth=160&quality=80&format=webp&api_key=$token"

                                            SyncComparisonSong(
                                                id = "emby_${id}_${remotePath.hashCode()}",
                                                title = name,
                                                artist = artistName,
                                                album = albumName,
                                                durationMs = durationMs,
                                                coverUrl = coverUrl,
                                                embyItemId = id,
                                                embyRemotePath = remotePath,
                                                embyFormat = container,
                                                embyBitRate = bitRate.toInt(),
                                                embyFileSize = fileSize,
                                                syncStatus = SyncStatus.NEED_DOWNLOAD,
                                                relativeFolderPath = relPath
                                            )
                                        } else null

                                        val coverUrl = "${cleanBaseUrl(config.serverUrl)}/Items/$id/Images/Primary?maxHeight=160&maxWidth=160&quality=80&format=webp&api_key=$token"

                                        results.add(
                                            ServerFolderItem(
                                                id = id,
                                                name = name,
                                                isFolder = isFolder,
                                                parentId = parentId,
                                                childCount = childCount,
                                                coverUrl = coverUrl,
                                                song = song
                                            )
                                        )
                                    }
                                    results.sortWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
                                    return@withContext Result.success(results)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                return@withContext Result.failure(Exception("无法获取子目录内容"))
            }

            // 2. 如果 parentId 为空 (请求根目录)：
            val rootCandidateUrls = mutableListOf<String>()
            if (uid.isNotBlank()) {
                rootCandidateUrls.add("${cleanBaseUrl(config.serverUrl)}/Users/$uid/Views?api_key=$token")
                rootCandidateUrls.add("${cleanBaseUrl(config.serverUrl)}/emby/Users/$uid/Views?api_key=$token")
            }
            rootCandidateUrls.add("${cleanBaseUrl(config.serverUrl)}/Library/MediaFolders?api_key=$token")
            rootCandidateUrls.add("${cleanBaseUrl(config.serverUrl)}/emby/Library/MediaFolders?api_key=$token")
            rootCandidateUrls.add("${cleanBaseUrl(config.serverUrl)}$userPath/Items?IncludeItemTypes=CollectionFolder,Folder,UserView&Recursive=false&SortBy=SortName&api_key=$token")
            rootCandidateUrls.add("${cleanBaseUrl(config.serverUrl)}/Items?IncludeItemTypes=CollectionFolder,Folder,UserView&Recursive=false&SortBy=SortName&api_key=$token")

            val rootMediaFolders = mutableListOf<ServerFolderItem>()
            var lastError: Exception? = null

            for (url in rootCandidateUrls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("X-Emby-Authorization", buildAuthHeader(token))
                        .addHeader("X-Emby-Token", token)
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val json = try { JSONObject(body) } catch (_: Exception) { null }
                            val items = json?.optJSONArray("Items") ?: try { JSONArray(body) } catch (_: Exception) { null }
                            if (items != null && items.length() > 0) {
                                for (i in 0 until items.length()) {
                                    val item = items.getJSONObject(i)
                                    val id = item.optString("Id")
                                    val name = item.optString("Name", "未命名目录")
                                    val type = item.optString("Type", "")
                                    val collectionType = item.optString("CollectionType", "").lowercase()

                                    val isNonMusic = collectionType in listOf("movies", "tvshows", "photos", "homevideos", "boxsets", "books", "trailers", "musicvideos", "playlists") ||
                                            collectionType.contains("video") ||
                                            collectionType.contains("movie") ||
                                            collectionType.contains("photo") ||
                                            collectionType.contains("playlist") ||
                                            type.contains("video", ignoreCase = true) ||
                                            type.contains("playlist", ignoreCase = true) ||
                                            name.contains("电影") ||
                                            name.contains("电视剧") ||
                                            name.contains("照片")

                                    if (isNonMusic) continue

                                    val isFolder = item.optBoolean("IsFolder", false) ||
                                            type.contains("Folder", ignoreCase = true) ||
                                            type.contains("View", ignoreCase = true) ||
                                            type.contains("Collection", ignoreCase = true) ||
                                            collectionType.isNotBlank() ||
                                            type.equals("MusicAlbum", ignoreCase = true) ||
                                            type.equals("MusicArtist", ignoreCase = true)
                                    val childCount = item.optInt("ChildCount", item.optInt("RecursiveItemCount", 0))

                                    rootMediaFolders.add(
                                        ServerFolderItem(
                                            id = id,
                                            name = name,
                                            isFolder = isFolder,
                                            parentId = null,
                                            childCount = childCount,
                                            coverUrl = "${cleanBaseUrl(config.serverUrl)}/Items/$id/Images/Primary?maxHeight=160&maxWidth=160&quality=80&format=webp&api_key=$token",
                                            song = null
                                        )
                                    )
                                }

                                if (rootMediaFolders.isNotEmpty()) {
                                    val primaryMusicRoot = rootMediaFolders.firstOrNull {
                                        it.name == "音乐" ||
                                        it.name.equals("music", ignoreCase = true) ||
                                        it.name.contains("音乐") ||
                                        it.name.contains("music", ignoreCase = true)
                                    } ?: rootMediaFolders.firstOrNull { it.isFolder } ?: rootMediaFolders.first()

                                    val subItemsRes = getFolders(config, primaryMusicRoot.id)
                                    if (subItemsRes.isSuccess && subItemsRes.getOrNull()?.isNotEmpty() == true) {
                                        return@withContext subItemsRes
                                    }
                                    return@withContext Result.success(rootMediaFolders)
                                }
                            }
                        } else {
                            lastError = Exception("HTTP ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }

            if (rootMediaFolders.isNotEmpty()) {
                Result.success(rootMediaFolders)
            } else {
                Result.failure(lastError ?: Exception("未能在 Emby 上获取到音乐媒体库文件夹"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 递归获取文件夹下的所有单曲 (用于文件夹一键同步)
     */
    suspend fun getFolderSongs(config: EmbyServerConfig, folderId: String): Result<List<SyncComparisonSong>> = withContext(Dispatchers.IO) {
        try {
            val validConfig = ensureAuthenticated(config)
            val token = validConfig.tokenOrApiKey
            val uid = validConfig.userId
            val userPath = if (uid.isNotBlank()) "/Users/$uid" else ""
            val urls = listOf(
                "${cleanBaseUrl(config.serverUrl)}$userPath/Items?ParentId=$folderId&IncludeItemTypes=Audio&Recursive=true&Fields=MediaSources,Path,UserData,Container,Bitrate,RunTimeTicks,AlbumArtist,Artists,Album&SortBy=SortName&api_key=$token",
                "${cleanBaseUrl(config.serverUrl)}/Items?ParentId=$folderId&IncludeItemTypes=Audio&Recursive=true&Fields=MediaSources,Path,UserData,Container,Bitrate,RunTimeTicks,AlbumArtist,Artists,Album&SortBy=SortName&api_key=$token"
            )

            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("X-Emby-Authorization", buildAuthHeader(token))
                        .addHeader("X-Emby-Token", token)
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val json = JSONObject(body)
                            val items = json.optJSONArray("Items") ?: JSONArray()
                            val results = mutableListOf<SyncComparisonSong>()
                            for (i in 0 until items.length()) {
                                val item = items.getJSONObject(i)
                                val id = item.optString("Id")
                                val name = item.optString("Name", "未知歌曲")
                                val runTimeTicks = item.optLong("RunTimeTicks", 0L)
                                val durationMs = runTimeTicks / 10000L
                                val container = item.optString("Container", "flac").lowercase()
                                val mediaSources = item.optJSONArray("MediaSources")
                                val firstSource = if (mediaSources != null && mediaSources.length() > 0) mediaSources.getJSONObject(0) else null
                                val bitRate = (firstSource?.optLong("Bitrate", 320000L) ?: 320000L) / 1000
                                val fileSize = firstSource?.optLong("Size", 0L) ?: 0L
                                val artistArray = item.optJSONArray("Artists")
                                val artistName = if (artistArray != null && artistArray.length() > 0) {
                                    (0 until artistArray.length()).joinToString(", ") { artistArray.getString(it) }
                                } else {
                                    item.optString("AlbumArtist", "未知艺术家")
                                }
                                val albumName = item.optString("Album", "")
                                val remotePath = item.optString("Path", "")
                                val relPath = extractRelativeFolderPath(remotePath, artistName, albumName)
                                val coverUrl = "${cleanBaseUrl(config.serverUrl)}/Items/$id/Images/Primary?maxHeight=160&maxWidth=160&quality=80&format=webp&api_key=$token"

                                results.add(
                                    SyncComparisonSong(
                                        id = "emby_${id}_${remotePath.hashCode()}",
                                        title = name,
                                        artist = artistName,
                                        album = albumName,
                                        durationMs = durationMs,
                                        coverUrl = coverUrl,
                                        embyItemId = id,
                                        embyRemotePath = remotePath,
                                        embyFormat = container,
                                        embyBitRate = bitRate.toInt(),
                                        embyFileSize = fileSize,
                                        syncStatus = SyncStatus.NEED_DOWNLOAD,
                                        relativeFolderPath = relPath
                                    )
                                )
                            }
                            if (results.isNotEmpty()) {
                                return@withContext Result.success(results)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            Result.failure(Exception("无法获取文件夹内曲目"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 全量曲目获取 (用于全局对比)
     */
    suspend fun fetchAllSongs(config: EmbyServerConfig): List<SyncComparisonSong> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<SyncComparisonSong>()
        try {
            val validConfig = ensureAuthenticated(config)
            val token = validConfig.tokenOrApiKey
            val uid = validConfig.userId
            val userPath = if (uid.isNotBlank()) "/Users/$uid" else ""
            val urls = listOf(
                "${cleanBaseUrl(config.serverUrl)}$userPath/Items?IncludeItemTypes=Audio&Recursive=true&Fields=Path,MediaSources,DateCreated,DateModified,Size,MediaStreams,Container,PrimaryImageAspectRatio,AlbumArtist,Artists,Album,RunTimeTicks&EnableImageTypes=Primary&api_key=$token",
                "${cleanBaseUrl(config.serverUrl)}/Items?IncludeItemTypes=Audio&Recursive=true&Fields=Path,MediaSources,DateCreated,DateModified,Size,MediaStreams,Container,PrimaryImageAspectRatio,AlbumArtist,Artists,Album,RunTimeTicks&EnableImageTypes=Primary&api_key=$token"
            )

            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("X-Emby-Authorization", buildAuthHeader(token))
                        .addHeader("X-Emby-Token", token)
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val json = JSONObject(body)
                            val items = json.optJSONArray("Items") ?: JSONArray()
                            for (i in 0 until items.length()) {
                                val item = items.getJSONObject(i)
                                val id = item.optString("Id", "")
                                val title = item.optString("Name", "未知曲目")
                                val artistArray = item.optJSONArray("Artists")
                                val artist = if (artistArray != null && artistArray.length() > 0) {
                                    (0 until artistArray.length()).joinToString(", ") { artistArray.getString(it) }
                                } else {
                                    item.optString("AlbumArtist", "未知艺术家")
                                }
                                val album = item.optString("Album", "")
                                val runTimeTicks = item.optLong("RunTimeTicks", 0L)
                                val durationMs = runTimeTicks / 10000L
                                val container = item.optString("Container", "mp3").lowercase()
                                val mediaSources = item.optJSONArray("MediaSources")
                                val firstSource = if (mediaSources != null && mediaSources.length() > 0) mediaSources.getJSONObject(0) else null
                                val bitRate = (firstSource?.optLong("Bitrate", 320000L) ?: 320000L) / 1000
                                val fileSize = firstSource?.optLong("Size", 0L) ?: 0L
                                val remotePath = item.optString("Path", "")
                                val relPath = extractRelativeFolderPath(remotePath, artist, album)

                                val coverUrl = "${cleanBaseUrl(config.serverUrl)}/Items/$id/Images/Primary?maxHeight=160&maxWidth=160&quality=80&format=webp&api_key=$token"
                                val songUniqueId = "emby_${id}_${remotePath.hashCode()}"

                                resultList.add(
                                    SyncComparisonSong(
                                        id = songUniqueId,
                                        title = title,
                                        artist = artist,
                                        album = album,
                                        durationMs = durationMs,
                                        coverUrl = coverUrl,
                                        embyItemId = id,
                                        embyRemotePath = remotePath,
                                        embyFormat = container,
                                        embyBitRate = bitRate.toInt(),
                                        embyFileSize = fileSize,
                                        syncStatus = SyncStatus.NEED_DOWNLOAD,
                                        relativeFolderPath = relPath
                                    )
                                )
                            }
                            if (resultList.isNotEmpty()) {
                                return@withContext resultList
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching all songs: ", e)
        }
        resultList
    }

    /**
     * 单首歌曲流媒体/直接下载 URL
     */
    fun getStreamUrl(config: EmbyServerConfig, itemId: String): String {
        val token = config.tokenOrApiKey
        return "${cleanBaseUrl(config.serverUrl)}/Audio/$itemId/stream?static=true&api_key=$token"
    }

    /**
     * 单文件直接高速下载
     */
    suspend fun downloadSongFile(
        config: EmbyServerConfig,
        itemId: String,
        targetFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val validConfig = ensureAuthenticated(config)
            val token = validConfig.tokenOrApiKey
            val candidateUrls = listOf(
                "${cleanBaseUrl(config.serverUrl)}/Audio/$itemId/stream?static=true&api_key=$token",
                "${cleanBaseUrl(config.serverUrl)}/Items/$itemId/Download?api_key=$token",
                "${cleanBaseUrl(config.serverUrl)}/emby/Audio/$itemId/stream?static=true&api_key=$token"
            )

            var lastException: Exception? = null
            for (url in candidateUrls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("X-Emby-Authorization", buildAuthHeader(token))
                        .addHeader("X-Emby-Token", token)
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body
                            if (body != null) {
                                targetFile.parentFile?.mkdirs()
                                val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                                val totalBytes = body.contentLength()
                                body.byteStream().use { input ->
                                    FileOutputStream(tempFile).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var bytesRead = 0L
                                        var read: Int
                                        while (input.read(buffer).also { read = it } != -1) {
                                            output.write(buffer, 0, read)
                                            bytesRead += read
                                            onProgress(bytesRead, totalBytes)
                                        }
                                        output.flush()
                                    }
                                }
                                if (targetFile.exists()) targetFile.delete()
                                tempFile.renameTo(targetFile)
                                return@withContext Result.success(targetFile)
                            }
                        } else {
                            lastException = Exception("HTTP ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }
            Result.failure(lastException ?: Exception("下载音频流失败"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

package com.zds.embysync.core.engine

import com.zds.embysync.core.model.SongDiffResult
import com.zds.embysync.core.model.SyncComparisonSong
import com.zds.embysync.core.model.SyncFilterConfig
import com.zds.embysync.core.model.SyncStatus

/**
 * 全局高性能歌曲差分比对器 (高吞吐量 O(N+M) 单遍流式比对)
 */
object SongDiffComparator {

    fun compare(
        embySongs: List<SyncComparisonSong>,
        localSongs: List<SyncComparisonSong>,
        filterConfig: SyncFilterConfig = SyncFilterConfig()
    ): SongDiffResult {
        val mergedList = ArrayList<SyncComparisonSong>(embySongs.size)
        val index = SongMatchingResolver.buildLocalSongIndex(localSongs)

        var synced = 0
        var needDownload = 0
        var diffUpgrade = 0
        var ignored = 0

        for (i in embySongs.indices) {
            val emby = embySongs[i]

            // 过滤阈值检查
            if (filterConfig.minSizeBytes > 0 && emby.embyFileSize < filterConfig.minSizeBytes) {
                mergedList.add(emby.copy(syncStatus = SyncStatus.IGNORED, diffReason = "文件大小低于过滤阈值"))
                ignored++
                continue
            }
            if (filterConfig.maxSizeBytes > 0 && emby.embyFileSize > filterConfig.maxSizeBytes) {
                mergedList.add(emby.copy(syncStatus = SyncStatus.IGNORED, diffReason = "文件大小超出过滤阈值"))
                ignored++
                continue
            }

            val resolved = SongMatchingResolver.matchServerSongWithIndex(emby, index)

            if (resolved.syncStatus == SyncStatus.SYNCED && !resolved.localFilePath.isNullOrBlank()) {
                val rawEmbyBr: Int = emby.embyBitRate ?: 0
                val rawLocalBr: Int = resolved.localBitRate ?: 0

                val embyKbps: Int = when {
                    rawEmbyBr <= 0 -> 0
                    rawEmbyBr > 10000 -> rawEmbyBr / 1000
                    else -> rawEmbyBr
                }
                val localKbps: Int = when {
                    rawLocalBr <= 0 -> 0
                    rawLocalBr > 10000 -> rawLocalBr / 1000
                    else -> rawLocalBr
                }

                val isEmbyLossless = emby.embyFormat?.lowercase() in listOf("flac", "wav", "dsf", "dff", "ape", "alac")
                val isLocalLossless = resolved.localFormat?.lowercase() in listOf("flac", "wav", "dsf", "dff", "ape", "alac")

                val isDiffUpgrade = (isEmbyLossless && !isLocalLossless) ||
                        (!isEmbyLossless && !isLocalLossless && embyKbps > 0 && localKbps > 0 && embyKbps >= localKbps + 64)

                val status = if (isDiffUpgrade) SyncStatus.DIFF_UPGRADE else SyncStatus.SYNCED
                val reason = if (isDiffUpgrade) {
                    "Emby 端音质更高 (${emby.embyFormat?.uppercase() ?: "无损"} ${if (embyKbps > 0) "${embyKbps}k" else ""} vs ${resolved.localFormat?.uppercase() ?: "音频"} ${if (localKbps > 0) "${localKbps}k" else ""})"
                } else {
                    "双端已匹配同步"
                }

                if (isDiffUpgrade) diffUpgrade++ else synced++
                mergedList.add(resolved.copy(syncStatus = status, diffReason = reason))
            } else {
                needDownload++
                mergedList.add(resolved)
            }
        }

        mergedList.sortWith(
            compareBy(
                {
                    when (it.syncStatus) {
                        SyncStatus.DIFF_UPGRADE -> 0
                        SyncStatus.NEED_DOWNLOAD -> 1
                        SyncStatus.SYNCED -> 2
                        SyncStatus.IGNORED -> 3
                    }
                },
                { it.title }
            )
        )

        return SongDiffResult(
            allSongs = mergedList,
            syncedCount = synced,
            needDownloadCount = needDownload,
            diffUpgradeCount = diffUpgrade,
            ignoredCount = ignored
        )
    }
}

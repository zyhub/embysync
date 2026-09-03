package com.zds.embysync

import com.zds.embysync.core.engine.SongMatchingResolver
import com.zds.embysync.core.model.SyncComparisonSong
import com.zds.embysync.core.model.SyncStatus
import org.junit.Assert.*
import org.junit.Test

class SongMatchingResolverTest {

    @Test
    fun testSimplifyKey() {
        val result = SongMatchingResolver.simplifyKey("周杰伦 - 晴天 (Live)")
        assertEquals("周杰伦晴天live", result)

        val resultSpecial = SongMatchingResolver.simplifyKey("01. Beat It [Remastered]!")
        assertEquals("01beatitremastered", resultSpecial)
    }

    @Test
    fun testNormalizeTrackTitle() {
        assertEquals("晴天", SongMatchingResolver.normalizeTrackTitle("01. 晴天"))
        assertEquals("晴天", SongMatchingResolver.normalizeTrackTitle("01-晴天"))
        assertEquals("晴天", SongMatchingResolver.normalizeTrackTitle("cd 1 - 02. 晴天"))
        assertEquals("晴天", SongMatchingResolver.normalizeTrackTitle("晴天 (Live)"))
        assertEquals("晴天", SongMatchingResolver.normalizeTrackTitle("晴天.flac"))
    }

    @Test
    fun testMatchServerSongWithLocal_ExactMatch() {
        val localSong = SyncComparisonSong(
            id = "local_1",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            localFilePath = "/music/Jay/晴天.flac",
            localFormat = "flac",
            localBitRate = 1000
        )

        val serverSong = SyncComparisonSong(
            id = "server_1",
            embyItemId = "emby_123",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            embyFormat = "flac",
            embyBitRate = 1000
        )

        val matched = SongMatchingResolver.matchServerSongWithLocal(serverSong, listOf(localSong))
        assertEquals(SyncStatus.SYNCED, matched.syncStatus)
        assertEquals("/music/Jay/晴天.flac", matched.localFilePath)
    }

    @Test
    fun testMatchServerSongWithLocal_PrefixTolerant() {
        val localSong = SyncComparisonSong(
            id = "local_2",
            title = "01. 晴天",
            artist = "周杰伦",
            album = "叶惠美",
            localFilePath = "/music/Jay/01. 晴天.flac",
            localFormat = "flac",
            localBitRate = 1000
        )

        val serverSong = SyncComparisonSong(
            id = "server_2",
            embyItemId = "emby_456",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            embyFormat = "flac",
            embyBitRate = 1000
        )

        val matched = SongMatchingResolver.matchServerSongWithLocal(serverSong, listOf(localSong))
        assertEquals(SyncStatus.SYNCED, matched.syncStatus)
        assertEquals("/music/Jay/01. 晴天.flac", matched.localFilePath)
    }

    @Test
    fun testMatchServerSongWithLocal_NoMatch() {
        val localSong = SyncComparisonSong(
            id = "local_3",
            title = "青花瓷",
            artist = "周杰伦",
            album = "我很忙",
            localFilePath = "/music/Jay/青花瓷.flac"
        )

        val serverSong = SyncComparisonSong(
            id = "server_3",
            embyItemId = "emby_789",
            title = "发如雪",
            artist = "周杰伦",
            album = "十一月的萧邦"
        )

        val matched = SongMatchingResolver.matchServerSongWithLocal(serverSong, listOf(localSong))
        assertEquals(SyncStatus.NEED_DOWNLOAD, matched.syncStatus)
        assertNull(matched.localFilePath)
    }
}

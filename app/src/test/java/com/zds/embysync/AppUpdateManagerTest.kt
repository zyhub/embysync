package com.zds.embysync

import com.zds.embysync.core.update.AppUpdateManager
import org.junit.Assert.*
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testIsVersionNewer() {
        assertTrue(AppUpdateManager.isVersionNewer("v1.2.2", "1.2.1"))
        assertTrue(AppUpdateManager.isVersionNewer("1.3.0", "1.2.9"))
        assertTrue(AppUpdateManager.isVersionNewer("2.0.0", "1.9.9"))
        assertTrue(AppUpdateManager.isVersionNewer("1.2.1.1", "1.2.1"))

        assertFalse(AppUpdateManager.isVersionNewer("1.2.1", "1.2.1"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.2.1", "v1.2.1"))
        assertFalse(AppUpdateManager.isVersionNewer("1.2.0", "1.2.1"))
        assertFalse(AppUpdateManager.isVersionNewer("1.1.9", "1.2.0"))
    }
}

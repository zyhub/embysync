package com.zds.embysync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import com.zds.embysync.core.database.EmbySyncDatabase
import com.zds.embysync.ui.screens.FolderSyncScreen
import com.zds.embysync.ui.screens.SyncMainDashboardScreen
import com.zds.embysync.ui.theme.EMBYsyncTheme

enum class AppScreen {
    MAIN_DASHBOARD,  // 一级主页：全量曲库比对与同步主控台
    FOLDER_SYNC      // 二级页面：文件夹同步
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        val database = EmbySyncDatabase.getInstance(this)

        setContent {
            EMBYsyncTheme {
                var currentScreen by rememberSaveable { mutableStateOf(AppScreen.MAIN_DASHBOARD) }

                BackHandler(enabled = currentScreen != AppScreen.MAIN_DASHBOARD) {
                    currentScreen = AppScreen.MAIN_DASHBOARD
                }

                Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                    when (screen) {
                        AppScreen.MAIN_DASHBOARD -> {
                            SyncMainDashboardScreen(
                                database = database,
                                onNavigateToFolderSync = {
                                    currentScreen = AppScreen.FOLDER_SYNC
                                }
                            )
                        }
                        AppScreen.FOLDER_SYNC -> {
                            FolderSyncScreen(
                                database = database,
                                onNavigateBack = {
                                    currentScreen = AppScreen.MAIN_DASHBOARD
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (permissions.isNotEmpty()) {
                requestPermissionLauncher.launch(permissions.toTypedArray())
            }
        }
    }
}

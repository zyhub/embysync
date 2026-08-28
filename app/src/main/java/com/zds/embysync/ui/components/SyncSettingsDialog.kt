package com.zds.embysync.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zds.embysync.core.model.EmbyServerConfig
import com.zds.embysync.core.network.EmbySyncProtocol
import com.zds.embysync.core.update.AppUpdateManager
import com.zds.embysync.core.update.UpdateInfo
import com.zds.embysync.ui.theme.AppleRed
import com.zds.embysync.ui.theme.EmbyGreen
import kotlinx.coroutines.launch

private data class ConcurrencyOption(val count: Int, val label: String, val desc: String, val tag: String)

private val CONCURRENCY_OPTIONS = listOf(
    ConcurrencyOption(1, "1 通道 (单任务顺序)", "单任务顺序下载", "低能耗"),
    ConcurrencyOption(2, "2 通道 (标准推荐)", "双通道均衡稳定", "推荐"),
    ConcurrencyOption(3, "3 通道 (高速并发)", "三通道高速下载", "高速"),
    ConcurrencyOption(4, "4 通道 (极速下载)", "四通道极速并行", "极速"),
    ConcurrencyOption(5, "5 通道 (满速狂飙)", "五通道满速吞吐", "极限")
)

@Composable
fun SyncSettingsDialog(
    initialConfig: EmbyServerConfig,
    initialPassword: String = "",
    initialLocalDir: String,
    initialConcurrency: Int = 2,
    onDismiss: () -> Unit,
    onSave: (updatedServer: EmbyServerConfig, rawPassword: String, updatedDir: String, updatedConcurrency: Int) -> Unit,
    onChooseLocalFolder: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverName by remember { mutableStateOf(initialConfig.name) }
    var serverUrl by remember { mutableStateOf(initialConfig.serverUrl) }
    var username by remember { mutableStateOf(initialConfig.username) }
    var rawPassword by remember { mutableStateOf(initialPassword.ifBlank { initialConfig.tokenOrApiKey }) }
    var localPath by remember { mutableStateOf(initialLocalDir) }
    var concurrency by remember { mutableStateOf(initialConcurrency) }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }

    // 检查更新状态
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfoDialog by remember { mutableStateOf<UpdateInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableStateOf(0f) }
    var updateDownloadedBytes by remember { mutableStateOf(0L) }
    var updateDownloadedTotalBytes by remember { mutableStateOf(0L) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 顶栏 (Header)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            tonalElevation = 0.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("⚡", fontSize = 18.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "EMBYsync 设置与关于",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = EmbyGreen.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, EmbyGreen.copy(alpha = 0.3f)),
                                    tonalElevation = 0.dp
                                ) {
                                    Text(
                                        text = "v${AppUpdateManager.CURRENT_VERSION_NAME}",
                                        color = EmbyGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = "配置 Emby 云端连接、离线并发与系统版本",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 卡片 1: ☁️ 服务器与账号鉴权 (纯白底色 + 浅灰优雅边框)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "☁️ 服务器与账号鉴权",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF475569)
                        )

                        // 行 1: 备注 + 地址
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = serverName,
                                onValueChange = { serverName = it },
                                label = { Text("备注名称", fontSize = 10.5.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    focusedBorderColor = AppleRed,
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.weight(0.38f)
                            )

                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it.trim() },
                                label = { Text("服务器地址 (含端口)", fontSize = 10.5.sp) },
                                leadingIcon = { Text("🌐", fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    focusedBorderColor = AppleRed,
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(0.62f)
                            )
                        }

                        // 行 2: 账号 + 原始登录密码 (带眼睛切换)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it.trim() },
                                label = { Text("登录账号", fontSize = 10.5.sp) },
                                leadingIcon = { Text("👤", fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    focusedBorderColor = AppleRed,
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.weight(1f)
                            )

                            OutlinedTextField(
                                value = rawPassword,
                                onValueChange = { rawPassword = it },
                                label = { Text("原始登录密码", fontSize = 10.5.sp) },
                                leadingIcon = { Text("🔒", fontSize = 12.sp) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { isPasswordVisible = !isPasswordVisible },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Text(if (isPasswordVisible) "👁️" else "🙈", fontSize = 13.sp)
                                    }
                                },
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    focusedBorderColor = AppleRed,
                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 卡片 2: ⚙️ 同步策略 (下拉菜单) 与 本地路径
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "⚙️ 同步策略与本地路径",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF475569)
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 下拉选择框 (并发通道)
                            Box(modifier = Modifier.weight(0.46f)) {
                                Column {
                                    Text(
                                        text = "并发下载通道",
                                        fontSize = 10.5.sp,
                                        color = Color(0xFF64748B),
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, if (isDropdownExpanded) AppleRed else Color(0xFFCBD5E1)),
                                        tonalElevation = 0.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { isDropdownExpanded = !isDropdownExpanded }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFFF1F5F9),
                                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                                    tonalElevation = 0.dp,
                                                    modifier = Modifier.size(22.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text("$concurrency", color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "$concurrency 通道",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF0F172A)
                                                )
                                            }
                                            Icon(
                                                imageVector = if (isDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = Color(0xFF64748B),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                DropdownMenu(
                                    expanded = isDropdownExpanded,
                                    onDismissRequest = { isDropdownExpanded = false },
                                    modifier = Modifier.background(Color.White)
                                ) {
                                    CONCURRENCY_OPTIONS.forEach { opt ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = if (concurrency == opt.count) Color(0xFF0F172A) else Color(0xFFF1F5F9),
                                                            tonalElevation = 0.dp,
                                                            modifier = Modifier.size(18.dp)
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Text("${opt.count}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (concurrency == opt.count) Color.White else Color(0xFF64748B))
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = opt.label,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (concurrency == opt.count) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (concurrency == opt.count) AppleRed else Color(0xFF0F172A)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = if (concurrency == opt.count) EmbyGreen.copy(alpha = 0.15f) else Color(0xFFF1F5F9),
                                                        tonalElevation = 0.dp
                                                    ) {
                                                        Text(
                                                            text = opt.tag,
                                                            fontSize = 9.5.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = if (concurrency == opt.count) EmbyGreen else Color(0xFF64748B),
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                concurrency = opt.count
                                                isDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // 本地存储目录
                            Column(modifier = Modifier.weight(0.54f)) {
                                Text(
                                    text = "本地同步存储目录",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF64748B),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = localPath,
                                        onValueChange = { localPath = it },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.White,
                                            unfocusedContainerColor = Color.White,
                                            focusedBorderColor = AppleRed,
                                            unfocusedBorderColor = Color(0xFFCBD5E1)
                                        ),
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                        tonalElevation = 0.dp,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(onClick = onChooseLocalFolder)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Folder, contentDescription = "选择目录", tint = AppleRed, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 卡片 3: 👨‍💻 关于作者与 GitHub 程序更新 (纯白灰底，无红晕染色)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFF1F5F9),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                tonalElevation = 0.dp,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("👨‍💻", fontSize = 13.sp)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "作者：${AppUpdateManager.AUTHOR_NAME}   邮箱：${AppUpdateManager.AUTHOR_EMAIL}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = "更新来源：GitHub (${AppUpdateManager.DEFAULT_GITHUB_REPO})",
                                    fontSize = 10.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }

                        // 检查更新按钮
                        OutlinedButton(
                            onClick = {
                                isCheckingUpdate = true
                                scope.launch {
                                    val res = AppUpdateManager.checkForUpdates(context)
                                    isCheckingUpdate = false
                                    if (res.isSuccess) {
                                        updateInfoDialog = res.getOrNull()
                                    } else {
                                        Toast.makeText(context, "检查更新失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isCheckingUpdate,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0F172A)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("检查更新", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 底部操作按钮栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 测试连接
                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            scope.launch {
                                val protocol = EmbySyncProtocol()
                                val cfg = initialConfig.copy(
                                    name = serverName.trim(),
                                    serverUrl = serverUrl.trim(),
                                    username = username.trim()
                                )
                                val authResult = protocol.authenticate(cfg, rawPassword.trim())
                                isTesting = false
                                if (authResult.isSuccess) {
                                    Toast.makeText(context, "✅ 成功连接至 Emby 服务器！", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "❌ 连接失败: ${authResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isTesting && serverUrl.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text(if (isTesting) "正在连接..." else "⚡ 测试连接", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text("取消", fontSize = 12.sp, color = Color(0xFF64748B))
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    val protocol = EmbySyncProtocol()
                                    val cfg = initialConfig.copy(
                                        name = serverName.trim(),
                                        serverUrl = serverUrl.trim(),
                                        username = username.trim()
                                    )
                                    val authRes = protocol.authenticate(cfg, rawPassword.trim())
                                    val finalConfig = if (authRes.isSuccess) {
                                        authRes.getOrNull() ?: cfg.copy(tokenOrApiKey = rawPassword.trim())
                                    } else {
                                        cfg.copy(tokenOrApiKey = rawPassword.trim())
                                    }
                                    onSave(finalConfig, rawPassword.trim(), localPath, concurrency)
                                    onDismiss()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text("💾 保存并应用", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // 🚀 程序更新弹窗 (纯白底色，无粉红染色)
    updateInfoDialog?.let { info ->
        Dialog(onDismissRequest = { if (!isDownloadingUpdate) updateInfoDialog = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🚀", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (info.hasUpdate) "发现新版本 v${info.latestVersion}" else "当前已是最新版本",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = EmbyGreen.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, EmbyGreen.copy(alpha = 0.3f)),
                            tonalElevation = 0.dp
                        ) {
                            Text(
                                text = "GitHub 最新",
                                color = EmbyGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // 简短更新内容摘要
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "📝 更新内容详情：",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF334155)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = info.releaseNotes,
                                fontSize = 11.sp,
                                color = Color(0xFF475569),
                                lineHeight = 16.sp
                            )
                        }
                    }

                    // 下载进度面板
                    AnimatedVisibility(visible = isDownloadingUpdate) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                            tonalElevation = 0.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "正在下载更新包...",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppleRed
                                    )
                                    Text(
                                        text = "${(updateDownloadProgress * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { updateDownloadProgress },
                                    color = AppleRed,
                                    trackColor = Color(0xFFE2E8F0),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val curMb = String.format("%.1f MB", updateDownloadedBytes / (1024.0 * 1024.0))
                                val totMb = String.format("%.1f MB", updateDownloadedTotalBytes / (1024.0 * 1024.0))
                                Text(
                                    text = "$curMb / $totMb • 原子校验与覆盖安装",
                                    fontSize = 9.5.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }

                    // 操作栏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { updateInfoDialog = null },
                            enabled = !isDownloadingUpdate
                        ) {
                            Text(if (info.hasUpdate) "暂不更新" else "关闭", fontSize = 12.sp, color = Color(0xFF64748B))
                        }

                        if (info.hasUpdate && info.downloadUrl.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    isDownloadingUpdate = true
                                    scope.launch {
                                        val apkRes = AppUpdateManager.downloadApk(context, info.downloadUrl) { progress, downloaded, total ->
                                            updateDownloadProgress = progress
                                            updateDownloadedBytes = downloaded
                                            updateDownloadedTotalBytes = total
                                        }
                                        isDownloadingUpdate = false
                                        if (apkRes.isSuccess) {
                                            val apkFile = apkRes.getOrNull()
                                            if (apkFile != null) {
                                                AppUpdateManager.installApk(context, apkFile)
                                                updateInfoDialog = null
                                            }
                                        } else {
                                            Toast.makeText(context, "下载失败: ${apkRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !isDownloadingUpdate,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(if (isDownloadingUpdate) "正在下载..." else "⚡ 下载并覆盖安装", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.zds.embysync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zds.embysync.core.model.SyncFilterConfig
import com.zds.embysync.ui.theme.AppleRed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncFilterRulesDialog(
    initialConfig: SyncFilterConfig,
    onDismiss: () -> Unit,
    onConfirm: (SyncFilterConfig) -> Unit
) {
    var minMb by remember { mutableStateOf((initialConfig.minSizeBytes / (1024 * 1024)).let { if (it > 0) it.toString() else "" }) }
    var maxMb by remember { mutableStateOf((initialConfig.maxSizeBytes / (1024 * 1024)).let { if (it > 0) it.toString() else "" }) }
    var ignoreDotFiles by remember { mutableStateOf(initialConfig.ignoreDotFiles) }
    var extensionTags by remember { mutableStateOf(initialConfig.ignoredExtensions.toMutableList()) }
    var newTagInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "⚙️ 过滤规则设置",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 排除大小
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = minMb.isNotBlank(),
                        onCheckedChange = { if (!it) minMb = "" else minMb = "1" },
                        colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                    )
                    Text("排除小于", fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedTextField(
                        value = minMb,
                        onValueChange = { minMb = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(70.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("MB 的文件", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = maxMb.isNotBlank(),
                        onCheckedChange = { if (!it) maxMb = "" else maxMb = "500" },
                        colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                    )
                    Text("排除大于", fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedTextField(
                        value = maxMb,
                        onValueChange = { maxMb = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(70.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("MB 的文件", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 排除隐藏文件
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = ignoreDotFiles,
                        onCheckedChange = { ignoreDotFiles = it },
                        colors = CheckboxDefaults.colors(checkedColor = AppleRed)
                    )
                    Text("排除前缀带 \".\" 的隐藏文件 (如 .DS_Store, .nomedia)", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                Text("排除文件类型后缀", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))

                // 标签展示
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    extensionTags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(tag, fontSize = 12.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "删除",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color.Gray.copy(alpha = 0.2f))
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTagInput,
                        onValueChange = { newTagInput = it.lowercase().trim() },
                        placeholder = { Text("添加后缀(如 log)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newTagInput.isNotBlank() && !extensionTags.contains(newTagInput)) {
                                extensionTags = (extensionTags + newTagInput).toMutableList()
                                newTagInput = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("添加")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 底部按钮
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val minBytes = (minMb.toLongOrNull() ?: 0L) * 1024 * 1024
                            val maxBytes = (maxMb.toLongOrNull() ?: 0L) * 1024 * 1024
                            onConfirm(
                                SyncFilterConfig(
                                    minSizeBytes = minBytes,
                                    maxSizeBytes = maxBytes,
                                    ignoreDotFiles = ignoreDotFiles,
                                    ignoredExtensions = extensionTags.toSet()
                                )
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                    ) {
                        Text("保存规则")
                    }
                }
            }
        }
    }
}

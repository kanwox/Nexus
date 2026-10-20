package com.github.mihomo.android.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import java.util.Locale

private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return "从未更新"
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 60_000L) return "刚刚"
    val minutes = diff / 60_000L
    if (minutes < 60L) return "${minutes}分钟前"
    val hours = minutes / 60L
    if (hours < 24L) return "${hours}小时前"
    val days = hours / 24L
    return "${days}天前"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

private fun formatTraffic(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.0f MB", mb)
        else -> "${bytes / 1024} KB"
    }
}

@Suppress("UnusedParameter")
@Composable
fun ProfilesScreen(
    profiles: List<ProfileItem>,
    selectedProfileId: String?,
    onBack: (() -> Unit)? = null,
    onSelectProfile: (ProfileItem) -> Unit,
    onAddSubscription: (name: String, url: String) -> Unit,
    onUpdateProfile: (ProfileItem) -> Unit,
    onDeleteProfile: (ProfileItem) -> Unit,
    onRefreshSubscription: (ProfileItem) -> Unit = onUpdateProfile,
    onImportLocalFile: (() -> Unit)? = null,
    onReorderProfiles: ((List<ProfileItem>) -> Unit)? = null,
    onNavigateToOverride: ((ProfileItem) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = rememberIosHaptic()

    var showAddSheet by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newSubName by remember { mutableStateOf("") }
    var newSubUrl by remember { mutableStateOf("") }

    var actionSheetProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var editingProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var editName by remember { mutableStateOf("") }
    var editUrl by remember { mutableStateOf("") }

    var deletingProfile by remember { mutableStateOf<ProfileItem?>(null) }

    val localFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val result = ConfigManager.importProfileFromUri(context, uri)
            if (result.isSuccess) {
                val imported = result.getOrThrow()
                onUpdateProfile(imported)
                Toast.makeText(context, "已导入 ${imported.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "导入失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = "配置",
            showBackButton = onBack != null,
            onBack = onBack,
            actions = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .iosPressable {
                            haptic.selection()
                            showAddSheet = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = IosBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (profiles.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = LoonTextMuted.copy(alpha = 0.4f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "暂无配置",
                                color = LoonTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                items(profiles, key = { it.id }) { profile ->
                    val isSelected = profile.id == selectedProfileId
                    val isSub = !profile.url.isNullOrBlank()

                    val metadataStr = remember(profile) {
                        val timePart = formatRelativeTime(profile.lastUpdated)
                        val sizePart = formatFileSize(profile.file.length())
                        if (profile.totalBytes > 0L) {
                            val used = formatTraffic(profile.uploadBytes + profile.downloadBytes)
                            val total = formatTraffic(profile.totalBytes)
                            "$timePart · $used / $total"
                        } else {
                            "$timePart · $sizePart"
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = LoonCard,
                        border = BorderStroke(
                            width = if (isSelected) 1.dp else 0.5.dp,
                            color = if (isSelected) IosBlue.copy(alpha = 0.8f) else LoonCardBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .iosPressable {
                                haptic.selection()
                                onSelectProfile(profile)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSub) IosBlue.copy(alpha = 0.12f) else IosOrange.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isSub) Icons.Default.CloudQueue else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (isSub) IosBlue else IosOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(13.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    color = LoonTextPrimary,
                                    fontSize = 15.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = metadataStr,
                                    color = LoonTextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = IosBlue,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .iosPressable {
                                        haptic.selection()
                                        actionSheetProfile = profile
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreHoriz,
                                    contentDescription = null,
                                    tint = LoonTextSecondary.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showAddSheet = false },
            actions = listOf(
                CupertinoActionSheetItem(
                    text = "添加订阅",
                    onClick = {
                        haptic.selection()
                        showAddSheet = false
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        newSubName = ""
                        newSubUrl = if (clipText.startsWith("http://") || clipText.startsWith("https://")) clipText else ""
                        showAddDialog = true
                    }
                ),
                CupertinoActionSheetItem(
                    text = "导入文件",
                    onClick = {
                        haptic.selection()
                        showAddSheet = false
                        if (onImportLocalFile != null) {
                            onImportLocalFile()
                        } else {
                            localFileLauncher.launch("*/*")
                        }
                    }
                )
            ),
            cancelText = "取消"
        )
    }

    if (showAddDialog) {
        CupertinoAlertDialog(
            title = "添加订阅",
            confirmText = "添加",
            dismissText = "取消",
            onConfirm = {
                val url = newSubUrl.trim()
                if (url.isNotBlank()) {
                    showAddDialog = false
                    val name = newSubName.trim().ifBlank { "订阅" }
                    onAddSubscription(name, url)
                } else {
                    Toast.makeText(context, "请输入链接", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showAddDialog = false },
            onDismissRequest = { showAddDialog = false }
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            CupertinoTextField(
                value = newSubName,
                onValueChange = { newSubName = it },
                placeholder = "名称",
                singleLine = true
            )
            Spacer(modifier = Modifier.height(10.dp))
            CupertinoTextField(
                value = newSubUrl,
                onValueChange = { newSubUrl = it },
                placeholder = "链接 (URL)",
                singleLine = true
            )
        }
    }

    if (actionSheetProfile != null) {
        val target = actionSheetProfile!!
        val isSub = !target.url.isNullOrBlank()

        val actions = buildList {
            if (isSub) {
                add(
                    CupertinoActionSheetItem(
                        text = "更新",
                        onClick = {
                            haptic.lightTick()
                            actionSheetProfile = null
                            onRefreshSubscription(target)
                        }
                    )
                )
            }
            add(
                CupertinoActionSheetItem(
                    text = "编辑",
                    onClick = {
                        haptic.selection()
                        actionSheetProfile = null
                        editName = target.name
                        editUrl = target.url ?: ""
                        editingProfile = target
                    }
                )
            )
            if (onNavigateToOverride != null) {
                add(
                    CupertinoActionSheetItem(
                        text = "覆写",
                        onClick = {
                            haptic.selection()
                            actionSheetProfile = null
                            onNavigateToOverride(target)
                        }
                    )
                )
            }
            add(
                CupertinoActionSheetItem(
                    text = "删除",
                    isDestructive = true,
                    onClick = {
                        haptic.impact()
                        actionSheetProfile = null
                        deletingProfile = target
                    }
                )
            )
        }

        CupertinoActionSheet(
            onDismissRequest = { actionSheetProfile = null },
            title = target.name,
            actions = actions,
            cancelText = "取消"
        )
    }

    if (editingProfile != null) {
        val target = editingProfile!!
        val isSub = !target.url.isNullOrBlank()

        CupertinoAlertDialog(
            title = "编辑",
            confirmText = "保存",
            dismissText = "取消",
            onConfirm = {
                val finalName = editName.trim().ifBlank { target.name }
                val finalUrl = if (isSub) editUrl.trim() else target.url
                val updated = target.copy(name = finalName, url = finalUrl)
                editingProfile = null
                ConfigManager.updateProfile(context, updated)
                onUpdateProfile(updated)
            },
            onDismiss = { editingProfile = null },
            onDismissRequest = { editingProfile = null }
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            CupertinoTextField(
                value = editName,
                onValueChange = { editName = it },
                placeholder = "名称",
                singleLine = true
            )
            if (isSub) {
                Spacer(modifier = Modifier.height(10.dp))
                CupertinoTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it },
                    placeholder = "链接 (URL)",
                    singleLine = true
                )
            }
        }
    }

    if (deletingProfile != null) {
        val target = deletingProfile!!
        CupertinoAlertDialog(
            title = "删除配置",
            message = "确定要删除此配置吗？",
            confirmText = "删除",
            dismissText = "取消",
            isDestructive = true,
            onConfirm = {
                deletingProfile = null
                onDeleteProfile(target)
            },
            onDismiss = { deletingProfile = null },
            onDismissRequest = { deletingProfile = null }
        )
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ProfilesScreenPreview() {
    val sampleProfiles = listOf(
        ProfileItem(
            id = "profile_1",
            name = "示例订阅配置",
            url = "https://example.com/subscribe",
            file = java.io.File("/dummy/profile1.yaml"),
            lastUpdated = System.currentTimeMillis() - 3600_000L * 2,
            uploadBytes = 1024L * 1024L * 500L,
            downloadBytes = 1024L * 1024L * 1024L * 5L,
            totalBytes = 1024L * 1024L * 1024L * 100L
        ),
        ProfileItem(
            id = "profile_2",
            name = "示例本地配置",
            url = null,
            file = java.io.File("/dummy/profile2.yaml"),
            lastUpdated = System.currentTimeMillis() - 86400_000L * 3
        )
    )
    MihomoTheme {
        ProfilesScreen(
            profiles = sampleProfiles,
            selectedProfileId = "profile_1",
            onBack = {},
            onSelectProfile = {},
            onAddSubscription = { _, _ -> },
            onUpdateProfile = {},
            onDeleteProfile = {}
        )
    }
}

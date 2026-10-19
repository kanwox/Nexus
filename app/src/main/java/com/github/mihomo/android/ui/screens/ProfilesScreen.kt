package com.github.mihomo.android.ui.screens

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import androidx.compose.ui.tooling.preview.Preview
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return "刚刚"
    val seconds = diff / 1000
    if (seconds < 60) return "刚刚"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes} 分钟前"
    val hours = minutes / 60
    if (hours < 24) return "${hours} 小时前"
    val days = hours / 24
    if (days < 30) return "${days} 天前"
    val months = days / 30
    if (months < 12) return "${months} 个月前"
    val years = months / 12
    return "${years} 年前"
}

private fun formatTraffic(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    val tb = gb / 1024.0
    return when {
        tb >= 1.0 -> String.format(Locale.US, "%.2f TB", tb)
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val scope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()
    val lang = LocalAppLanguage.current

    // Sheets & Dialog States
    var showAddSheet by remember { mutableStateOf(false) }
    var actionSheetProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var editSheetProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var yamlEditorProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var deleteConfirmProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var refreshingProfileId by remember { mutableStateOf<String?>(null) }

    // File Picker for replacing profile
    var targetReplacingProfile by remember { mutableStateOf<ProfileItem?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val target = targetReplacingProfile
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().readText()
                    ConfigManager.saveProfileContent(context, target.id, content)
                    Toast.makeText(context, "已成功替换配置文件内容", Toast.LENGTH_SHORT).show()
                    val updated = target.copy(lastUpdated = System.currentTimeMillis())
                    onUpdateProfile(updated)
                    if (target.id == selectedProfileId) {
                        onSelectProfile(updated)
                    }
                }
            }.onFailure {
                Toast.makeText(context, "读取本地文件失败: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
        targetReplacingProfile = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Cupertino Navigation Bar
        CupertinoNavBar(
            title = AppStrings.get("profiles_title", lang),
            showBackButton = onBack != null,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = {
                        haptic.selection()
                        showAddSheet = true
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = AppStrings.get("profiles_add", lang),
                        tint = IosBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .bounceOverscroll(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(LoonTertiaryBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = IosBlue,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无配置文件",
                        color = LoonTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "点击右上角 + 按钮添加远程订阅链接或导入本地 YAML 配置文件",
                        color = LoonTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .border(0.5.dp, LoonCardBorder, RoundedCornerShape(12.dp))
                            .iosPressable { showAddSheet = true },
                        color = LoonCard
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = IosBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加首个配置", color = IosBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .bounceOverscroll()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(profiles, key = { it.id }) { item ->
                    val isSelected = item.id == selectedProfileId
                    val isRefreshing = refreshingProfileId == item.id

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .border(
                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                color = if (isSelected) IosBlue else LoonCardBorder,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .iosPressable {
                                haptic.selection()
                                onSelectProfile(item)
                            },
                        color = LoonCard
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            // Top Row: Active Radio, Title, Tag & Actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Checkmark / Select Circle
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) IosBlue else Color.Transparent)
                                        .border(
                                            width = if (isSelected) 0.dp else 1.5.dp,
                                            color = if (isSelected) Color.Transparent else LoonTextMuted,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) IosBlue else LoonTextPrimary,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (item.url != null) "订阅" else "本地",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (item.url != null) IosBlue else LoonTextSecondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "更新于 ${formatRelativeTime(item.lastUpdated)}" +
                                                if (item.autoUpdate) " · 自动更新开启" else "",
                                        fontSize = 11.5.sp,
                                        color = LoonTextSecondary
                                    )
                                }

                                // Quick Refresh Button (for remote subscriptions)
                                if (item.url != null) {
                                    val rotation by rememberInfiniteTransition().animateFloat(
                                        initialValue = 0f,
                                        targetValue = 360f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1000, easing = LinearEasing),
                                            repeatMode = RepeatMode.Restart
                                        )
                                    )
                                    IconButton(
                                        onClick = {
                                            if (refreshingProfileId == null) {
                                                haptic.impact()
                                                refreshingProfileId = item.id
                                                scope.launch {
                                                    runCatching { onRefreshSubscription(item) }
                                                    refreshingProfileId = null
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "刷新",
                                            tint = if (isRefreshing) IosBlue else LoonTextSecondary,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .rotate(if (isRefreshing) rotation else 0f)
                                        )
                                    }
                                }

                                // More Actions Button
                                IconButton(
                                    onClick = {
                                        haptic.selection()
                                        actionSheetProfile = item
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreHoriz,
                                        contentDescription = "更多",
                                        tint = LoonTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Subscription Traffic & Expiration Pill (If available)
                            if (item.totalBytes > 0) {
                                Spacer(modifier = Modifier.height(14.dp))
                                val usedBytes = item.uploadBytes + item.downloadBytes
                                val fraction = (usedBytes.toDouble() / item.totalBytes).coerceIn(0.0, 1.0).toFloat()
                                val progressColor = when {
                                    fraction >= 0.9f -> IosRed
                                    fraction >= 0.75f -> IosOrange
                                    else -> IosBlue
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(LoonBg)
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${formatTraffic(usedBytes)} / ${formatTraffic(item.totalBytes)}",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = LoonTextPrimary
                                        )
                                        if (item.expireTimestamp > 0) {
                                            val expireDateStr = remember(item.expireTimestamp) {
                                                val sdf = SimpleDateFormat("yyyy-MM-dd 到期", Locale.getDefault())
                                                sdf.format(Date(item.expireTimestamp * 1000))
                                            }
                                            Text(
                                                text = expireDateStr,
                                                fontSize = 11.sp,
                                                color = LoonTextSecondary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    // Smooth Progress Track
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(LoonCardBorder)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(progressColor)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 1. Add Subscription Sheet (Cupertino Style)
    // =========================================================================
    if (showAddSheet) {
        var subName by remember { mutableStateOf("") }
        var subUrl by remember { mutableStateOf("") }
        val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }

        LaunchedEffect(Unit) {
            // Smart auto-detect URL from clipboard
            if (clipboard?.hasPrimaryClip() == true) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val clipText = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                    if (clipText.startsWith("http://") || clipText.startsWith("https://")) {
                        subUrl = clipText
                        if (subName.isBlank()) {
                            subName = runCatching {
                                java.net.URI(clipText).host?.substringBefore(".") ?: ""
                            }.getOrDefault("")
                        }
                    }
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            containerColor = LoonBg,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp)
            ) {
                Text(
                    text = "添加配置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary
                )
                Text(
                    text = "支持通过远程订阅 URL 下载或导入本地 YAML 文件",
                    fontSize = 12.5.sp,
                    color = LoonTextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                )

                // Quick Import Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Local File Import
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(0.5.dp, LoonCardBorder, RoundedCornerShape(12.dp))
                            .iosPressable {
                                showAddSheet = false
                                onImportLocalFile?.invoke()
                            },
                        color = LoonCard
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, tint = IosBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("本地文件导入", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LoonTextPrimary)
                        }
                    }

                    // Clipboard Paste
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(0.5.dp, LoonCardBorder, RoundedCornerShape(12.dp))
                            .iosPressable {
                                if (clipboard?.hasPrimaryClip() == true) {
                                    val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                    if (text.isNotBlank()) {
                                        subUrl = text
                                        Toast.makeText(context, "已粘贴剪贴板内容", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        color = LoonCard
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, tint = IosGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("从剪贴板粘贴", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LoonTextPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Grouped Input Fields
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CupertinoTextField(
                            value = subName,
                            onValueChange = { subName = it },
                            placeholder = "订阅备注名称（选填）"
                        )

                        CupertinoTextField(
                            value = subUrl,
                            onValueChange = { subUrl = it },
                            placeholder = "订阅链接 URL (https://...)"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Download & Save Button
                CupertinoButton(
                    text = "下载并保存配置",
                    onClick = {
                        haptic.impact()
                        onAddSubscription(subName.trim(), subUrl.trim())
                        showAddSheet = false
                    },
                    enabled = subUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    style = CupertinoButtonStyle.Filled
                )
            }
        }
    }

    // =========================================================================
    // 2. Action Sheet (Profile Operations)
    // =========================================================================
    if (actionSheetProfile != null) {
        val target = actionSheetProfile!!
        val isSelected = target.id == selectedProfileId

        ModalBottomSheet(
            onDismissRequest = { actionSheetProfile = null },
            containerColor = LoonBg,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = target.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 14.dp)
                )

                CupertinoGroupedSection {
                    if (!isSelected) {
                        CupertinoCell(
                            title = "设为活跃配置",
                            icon = Icons.Default.CheckCircle,
                            iconBg = IosBlue,
                            onClick = {
                                actionSheetProfile = null
                                onSelectProfile(target)
                            }
                        )
                        CupertinoDivider()
                    }

                    if (target.url != null) {
                        CupertinoCell(
                            title = "立即更新订阅",
                            icon = Icons.Default.CloudSync,
                            iconBg = IosGreen,
                            onClick = {
                                actionSheetProfile = null
                                onRefreshSubscription(target)
                            }
                        )
                        CupertinoDivider()
                    }

                    CupertinoCell(
                        title = "编辑配置参数",
                        icon = Icons.Default.Tune,
                        iconBg = Color(0xFFFF9500),
                        onClick = {
                            actionSheetProfile = null
                            editSheetProfile = target
                        }
                    )
                    CupertinoDivider()

                    CupertinoCell(
                        title = "在线查看 / 编辑 YAML",
                        icon = Icons.Default.Code,
                        iconBg = Color(0xFF5856D6),
                        onClick = {
                            actionSheetProfile = null
                            yamlEditorProfile = target
                        }
                    )
                    CupertinoDivider()

                    CupertinoCell(
                        title = "JS 脚本覆写",
                        icon = Icons.Default.Javascript,
                        iconBg = Color(0xFFAF52DE),
                        onClick = {
                            actionSheetProfile = null
                            onNavigateToOverride?.invoke(target)
                        }
                    )
                    CupertinoDivider()

                    CupertinoCell(
                        title = "替换为本地文件",
                        icon = Icons.Default.FileUpload,
                        iconBg = Color(0xFF32ADE6),
                        onClick = {
                            actionSheetProfile = null
                            targetReplacingProfile = target
                            filePickerLauncher.launch("*/*")
                        }
                    )
                    CupertinoDivider()

                    CupertinoCell(
                        title = "删除配置",
                        icon = Icons.Default.DeleteOutline,
                        iconBg = IosRed,
                        onClick = {
                            actionSheetProfile = null
                            deleteConfirmProfile = target
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(0.5.dp, LoonCardBorder, RoundedCornerShape(14.dp))
                        .iosPressable { actionSheetProfile = null },
                    color = LoonCard
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "取消", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = IosBlue)
                    }
                }
            }
        }
    }

    // =========================================================================
    // 3. Edit Profile Sheet
    // =========================================================================
    if (editSheetProfile != null) {
        val target = editSheetProfile!!
        var editName by remember(target) { mutableStateOf(target.name) }
        var editUrl by remember(target) { mutableStateOf(target.url ?: "") }
        var editAutoUpdate by remember(target) { mutableStateOf(target.autoUpdate) }
        var intervalHoursText by remember(target) {
            mutableStateOf(((target.autoUpdateInterval / 60).coerceAtLeast(1)).toString())
        }

        ModalBottomSheet(
            onDismissRequest = { editSheetProfile = null },
            containerColor = LoonBg,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp)
            ) {
                Text(
                    text = "配置参数设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                CupertinoGroupedSection {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("配置备注名称", fontSize = 12.sp, color = LoonTextSecondary)
                        CupertinoTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            placeholder = "配置备注名称"
                        )

                        if (target.url != null) {
                            Text("订阅链接 URL", fontSize = 12.sp, color = LoonTextSecondary)
                            CupertinoTextField(
                                value = editUrl,
                                onValueChange = { editUrl = it },
                                placeholder = "https://..."
                            )
                        }
                    }
                }

                if (target.url != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    CupertinoGroupedSection {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("定时自动更新", fontSize = 15.sp, color = LoonTextPrimary)
                                Text("后台静默拉取最新节点与规则", fontSize = 11.5.sp, color = LoonTextSecondary)
                            }
                            CupertinoSwitch(
                                checked = editAutoUpdate,
                                onCheckedChange = { editAutoUpdate = it }
                            )
                        }

                        if (editAutoUpdate) {
                            CupertinoDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("更新间隔 (小时)", fontSize = 14.5.sp, color = LoonTextPrimary)
                                TextField(
                                    value = intervalHoursText,
                                    onValueChange = { if (it.all { ch -> ch.isDigit() }) intervalHoursText = it },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = LoonBg,
                                        unfocusedContainerColor = LoonBg,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        focusedTextColor = LoonTextPrimary,
                                        unfocusedTextColor = LoonTextPrimary
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    modifier = Modifier.width(80.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(IosBlue)
                        .clickable {
                            val intervalHours = (intervalHoursText.toIntOrNull() ?: 24).coerceAtLeast(1)
                            val updated = target.copy(
                                name = editName.ifBlank { target.name },
                                url = if (target.url != null) editUrl.ifBlank { target.url } else null,
                                autoUpdate = editAutoUpdate,
                                autoUpdateInterval = intervalHours * 60
                            )
                            ConfigManager.updateProfile(context, updated)
                            if (target.id == selectedProfileId) {
                                onSelectProfile(updated)
                            }
                            onUpdateProfile(updated)
                            editSheetProfile = null
                            Toast.makeText(context, "配置修改已保存", Toast.LENGTH_SHORT).show()
                        },
                    color = IosBlue
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("保存设置", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // =========================================================================
    // 4. Online YAML Editor Sheet
    // =========================================================================
    if (yamlEditorProfile != null) {
        val target = yamlEditorProfile!!
        var yamlText by remember(target) { mutableStateOf("") }

        LaunchedEffect(target) {
            yamlText = withContext(Dispatchers.IO) {
                runCatching { target.file.readText() }.getOrDefault("")
            }
        }

        ModalBottomSheet(
            onDismissRequest = { yamlEditorProfile = null },
            containerColor = LoonBg,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            modifier = Modifier.fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("编辑 YAML 配置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LoonTextPrimary)
                        Text(target.name, fontSize = 12.sp, color = LoonTextSecondary)
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(IosBlue)
                            .clickable {
                                val ok = ConfigManager.saveProfileContent(context, target.id, yamlText)
                                if (ok) {
                                    Toast.makeText(context, "配置内容已保存", Toast.LENGTH_SHORT).show()
                                    val updated = target.copy(lastUpdated = System.currentTimeMillis())
                                    onUpdateProfile(updated)
                                    if (target.id == selectedProfileId) {
                                        onSelectProfile(updated)
                                    }
                                    yamlEditorProfile = null
                                } else {
                                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                }
                            },
                        color = IosBlue
                    ) {
                        Text(
                            text = "保存并应用",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                TextField(
                    value = yamlText,
                    onValueChange = { yamlText = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = LoonTextPrimary,
                        lineHeight = 17.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LoonCard,
                        unfocusedContainerColor = LoonCard,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }

    // =========================================================================
    // 5. Delete Confirmation Sheet
    // =========================================================================
    if (deleteConfirmProfile != null) {
        val target = deleteConfirmProfile!!
        CupertinoAlertDialog(
            title = "删除配置文件",
            message = "确定要删除配置 [${target.name}] 吗？此操作将彻底移除该配置文件及其缓存。",
            confirmText = "删除",
            dismissText = "取消",
            isDestructive = true,
            onConfirm = {
                onDeleteProfile(target)
                deleteConfirmProfile = null
            },
            onDismissRequest = { deleteConfirmProfile = null }
        )
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ProfilesScreenPreview() {
    val sampleProfiles = listOf(
        ProfileItem(
            id = "p1",
            name = "Main Subscription (VIP)",
            url = "https://sub.example.com/api/v1/client/subscribe?token=xyz",
            file = java.io.File("/dummy/p1.yaml"),
            uploadBytes = 2147483648L,
            downloadBytes = 42949672960L,
            totalBytes = 107374182400L,
            expireTimestamp = System.currentTimeMillis() + 86400000L * 30
        ),
        ProfileItem(
            id = "p2",
            name = "Backup Node Profile",
            url = null,
            file = java.io.File("/dummy/p2.yaml")
        )
    )
    MihomoTheme {
        ProfilesScreen(
            profiles = sampleProfiles,
            selectedProfileId = "p1",
            onSelectProfile = {},
            onAddSubscription = { _, _ -> },
            onUpdateProfile = {},
            onDeleteProfile = {}
        )
    }
}

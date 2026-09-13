package com.github.mihomo.android.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.ScriptManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.github.mihomo.android.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    var showAddDialog by remember { mutableStateOf(false) }
    var subName by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }

    val lazyListState = rememberLazyListState()
    var localProfiles by remember(profiles) { mutableStateOf(profiles) }
    LaunchedEffect(profiles) {
        localProfiles = profiles
    }
    var draggedProfileId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // Dialog States
    var overrideDialogProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var fullEditDialogProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var onlineEditorProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var deleteConfirmProfile by remember { mutableStateOf<ProfileItem?>(null) }

    // File Picker for replacing profile
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && fullEditDialogProfile != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().readText()
                    ConfigManager.saveProfileContent(context, fullEditDialogProfile!!.id, content)
                    Toast.makeText(context, "已成功替换配置文件内容", Toast.LENGTH_SHORT).show()
                    val updated = fullEditDialogProfile!!.copy(lastUpdated = System.currentTimeMillis())
                    onUpdateProfile(updated)
                    if (fullEditDialogProfile!!.id == selectedProfileId) {
                        onSelectProfile(updated)
                    }
                    fullEditDialogProfile = updated
                }
            }.onFailure {
                Toast.makeText(context, "读取本地文件失败: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val lang = LocalAppLanguage.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 100.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = LoonTextPrimary
                        )
                    }
                }
                Text(
                    text = AppStrings.get("profiles_title", lang),
                    color = LoonTextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Top action button (Apple-style pure black plus icon)
            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = AppStrings.get("profiles_add", lang),
                    tint = LoonTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = LoonTextMuted,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(AppStrings.get("profiles_empty", lang), color = LoonTextSecondary, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(localProfiles, key = { _, item -> item.id }) { index, item ->
                    val isSelected = item.id == selectedProfileId
                    val isBeingDragged = draggedProfileId == item.id

                    val itemModifier = Modifier
                        .fillMaxWidth()
                        .offset {
                            if (isBeingDragged) IntOffset(0, dragOffsetY.roundToInt())
                            else IntOffset.Zero
                        }
                        .zIndex(if (isBeingDragged) 10f else 1f)
                        .graphicsLayer {
                            scaleX = if (isBeingDragged) 1.03f else 1f
                            scaleY = if (isBeingDragged) 1.03f else 1f
                            shadowElevation = if (isBeingDragged) 12f else 0f
                        }
                        .pointerInput(item.id, localProfiles.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedProfileId = item.id
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    onReorderProfiles?.invoke(localProfiles)
                                    draggedProfileId = null
                                    dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    localProfiles = profiles
                                    draggedProfileId = null
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y

                                    val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                                    val draggedItem = visibleItems.firstOrNull { it.key == item.id }
                                    if (draggedItem != null) {
                                        val currentCenter = draggedItem.offset + dragOffsetY + draggedItem.size / 2f
                                        val targetItem = visibleItems.firstOrNull { info ->
                                            info.key != item.id &&
                                            currentCenter >= info.offset &&
                                            currentCenter <= (info.offset + info.size)
                                        }
                                        if (targetItem != null) {
                                            val fromIdx = localProfiles.indexOfFirst { it.id == item.id }
                                            val toIdx = localProfiles.indexOfFirst { it.id == targetItem.key }
                                            if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                                val mutable = localProfiles.toMutableList()
                                                val moved = mutable.removeAt(fromIdx)
                                                mutable.add(toIdx, moved)
                                                localProfiles = mutable
                                                dragOffsetY += (draggedItem.offset - targetItem.offset)
                                            }
                                        }
                                    }
                                }
                            )
                        }

                    LoonProfileCard(
                        modifier = itemModifier,
                        item = item,
                        isSelected = isSelected,
                        onClick = { onSelectProfile(item) },
                        onOverride = {
                            if (onNavigateToOverride != null) {
                                onNavigateToOverride(item)
                            } else {
                                overrideDialogProfile = item
                            }
                        },
                        onEdit = { fullEditDialogProfile = item },
                        onUpdate = { onRefreshSubscription(item) },
                        onDelete = { deleteConfirmProfile = item },
                        onMoveUp = if (index > 0) {
                            {
                                val mutable = localProfiles.toMutableList()
                                val moved = mutable.removeAt(index)
                                mutable.add(index - 1, moved)
                                localProfiles = mutable
                                onReorderProfiles?.invoke(mutable)
                            }
                        } else null,
                        onMoveDown = if (index < localProfiles.size - 1) {
                            {
                                val mutable = localProfiles.toMutableList()
                                val moved = mutable.removeAt(index)
                                mutable.add(index + 1, moved)
                                localProfiles = mutable
                                onReorderProfiles?.invoke(mutable)
                            }
                        } else null
                    )
                }
            }
        }
    }

    // 1. Add Subscription Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("添加 Clash / Mihomo 订阅", color = LoonTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = LoonEditBlueBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showAddDialog = false
                                onImportLocalFile?.invoke()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("从本地文件导入配置", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = LoonBlue)
                                Text("支持选择 .yaml / .yml / .txt 配置文件", fontSize = 11.sp, color = LoonTextSecondary)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = LoonCardBorder)
                        Text("  或添加订阅链接  ", color = LoonTextMuted, fontSize = 11.sp)
                        HorizontalDivider(modifier = Modifier.weight(1f), color = LoonCardBorder)
                    }

                    OutlinedTextField(
                        value = subName,
                        onValueChange = { subName = it },
                        label = { Text("订阅备注名称（选填）") },
                        placeholder = { Text("留空将自动从订阅获取") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LoonTextPrimary,
                            unfocusedTextColor = LoonTextPrimary,
                            focusedBorderColor = LoonBlue,
                            unfocusedBorderColor = LoonCardBorder
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = subUrl,
                        onValueChange = { subUrl = it },
                        label = { Text("订阅链接 URL") },
                        placeholder = { Text("https://...") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LoonTextPrimary,
                            unfocusedTextColor = LoonTextPrimary,
                            focusedBorderColor = LoonBlue,
                            unfocusedBorderColor = LoonCardBorder
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (subUrl.isNotBlank()) {
                            onAddSubscription(subName.trim(), subUrl.trim())
                            subName = ""
                            subUrl = ""
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LoonBlue, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("下载并保存", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消", color = LoonTextSecondary)
                }
            }
        )
    }

    // 2. Dedicated Script Override Dialog (覆写独立二级弹窗)
    if (overrideDialogProfile != null) {
        val target = overrideDialogProfile!!
        val availableScripts = remember { ScriptManager.getScripts(context) }
        var scriptEnabled by remember(target) { mutableStateOf(target.scriptEnabled) }
        var selectedScriptIds by remember(target) { mutableStateOf(target.scriptIds.toSet()) }

        AlertDialog(
            onDismissRequest = { overrideDialogProfile = null },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("JS 脚本覆写", color = LoonTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "配置: ${target.name}",
                        color = LoonTextSecondary,
                        fontSize = 13.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = LoonBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "对此配置启用 JS 覆写",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = LoonTextPrimary
                                )
                                Text(
                                    text = "启用后将按选中脚本动态重写配置",
                                    fontSize = 11.sp,
                                    color = LoonTextMuted
                                )
                            }
                            Switch(
                                checked = scriptEnabled,
                                onCheckedChange = { scriptEnabled = it }
                            )
                        }
                    }

                    if (scriptEnabled) {
                        Text(
                            text = "选择生效的脚本：",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary
                        )

                        if (availableScripts.isEmpty()) {
                            Text(
                                text = "脚本库暂无可用脚本，请前往 [设置 - JS 脚本复写] 导入或创建脚本",
                                fontSize = 12.sp,
                                color = LoonTextSecondary,
                                lineHeight = 18.sp
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                availableScripts.forEach { script ->
                                    val isChecked = selectedScriptIds.contains(script.id)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isChecked) LoonEditBlueBg else LoonBg,
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = 1.dp,
                                            color = if (isChecked) LoonBlue else LoonCardBorder
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedScriptIds = if (isChecked) {
                                                    selectedScriptIds - script.id
                                                } else {
                                                    selectedScriptIds + script.id
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                    selectedScriptIds = if (checked) {
                                                        selectedScriptIds + script.id
                                                    } else {
                                                        selectedScriptIds - script.id
                                                    }
                                                },
                                                colors = CheckboxDefaults.colors(checkedColor = LoonBlue)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column {
                                                Text(
                                                    text = script.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = LoonTextPrimary
                                                )
                                                if (script.url.isNotBlank()) {
                                                    Text(
                                                        text = "远程: ${script.url}",
                                                        fontSize = 10.sp,
                                                        color = LoonTextMuted,
                                                        maxLines = 1
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
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = target.copy(
                            scriptEnabled = scriptEnabled,
                            scriptIds = selectedScriptIds.toList()
                        )
                        ConfigManager.updateProfile(context, updated)
                        if (target.id == selectedProfileId) {
                            onSelectProfile(updated)
                        }
                        onUpdateProfile(updated)
                        overrideDialogProfile = null
                        Toast.makeText(context, "覆写设置已保存", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LoonBlue, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存覆写", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { overrideDialogProfile = null }) {
                    Text("取消", color = LoonTextSecondary)
                }
            }
        )
    }

    // 3. Expanded Full Edit Dialog (重命名、自动更新、修改订阅链接、在线修改 YAML、替换文件)
    if (fullEditDialogProfile != null) {
        val target = fullEditDialogProfile!!
        var editName by remember(target) { mutableStateOf(target.name) }
        var editUrl by remember(target) { mutableStateOf(target.url ?: "") }
        var editAutoUpdate by remember(target) { mutableStateOf(target.autoUpdate) }
        var intervalHoursText by remember(target) {
            mutableStateOf(((target.autoUpdateInterval / 60).coerceAtLeast(1)).toString())
        }

        AlertDialog(
            onDismissRequest = { fullEditDialogProfile = null },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("编辑配置详情", color = LoonTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Name
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("配置备注名称") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LoonTextPrimary,
                            unfocusedTextColor = LoonTextPrimary,
                            focusedBorderColor = LoonBlue,
                            unfocusedBorderColor = LoonCardBorder
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // URL if remote
                    if (target.url != null) {
                        OutlinedTextField(
                            value = editUrl,
                            onValueChange = { editUrl = it },
                            label = { Text("订阅链接 URL") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = LoonTextPrimary,
                                unfocusedTextColor = LoonTextPrimary,
                                focusedBorderColor = LoonBlue,
                                unfocusedBorderColor = LoonCardBorder
                            ),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Auto-update section
                    if (target.url != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = LoonBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("自动更新", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LoonTextPrimary)
                                        Text("在后台定时拉取最新订阅", fontSize = 11.sp, color = LoonTextMuted)
                                    }
                                    Switch(checked = editAutoUpdate, onCheckedChange = { editAutoUpdate = it })
                                }

                                if (editAutoUpdate) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = intervalHoursText,
                                        onValueChange = { input ->
                                            if (input.all { it.isDigit() }) {
                                                intervalHoursText = input
                                            }
                                        },
                                        label = { Text("更新间隔 (小时)") },
                                        placeholder = { Text("例如: 24") },
                                        trailingIcon = {
                                            Text(
                                                text = "小时",
                                                color = LoonTextSecondary,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(end = 12.dp)
                                            )
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = LoonTextPrimary,
                                            unfocusedTextColor = LoonTextPrimary,
                                            focusedBorderColor = LoonBlue,
                                            unfocusedBorderColor = LoonCardBorder
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // Online YAML editor button
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = LoonEditBlueBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onlineEditorProfile = target
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("在线编辑 YAML 配置", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = LoonTextPrimary)
                                Text("直接查看与修改配置文件内容", fontSize = 11.sp, color = LoonTextSecondary)
                            }
                        }
                    }

                    // Replace / Upload local config button
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = LoonEditBlueBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                filePickerLauncher.launch("*/*")
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("替换 / 上传本地配置文件", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = LoonTextPrimary)
                                Text("选择本地 .yaml / .yml 文件替换当前配置", fontSize = 11.sp, color = LoonTextMuted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
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
                        fullEditDialogProfile = null
                        Toast.makeText(context, "配置修改已保存", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LoonBlue, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存设置", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fullEditDialogProfile = null }) {
                    Text("取消", color = LoonTextSecondary)
                }
            }
        )
    }

    // 4. Online YAML Editor Dialog
    if (onlineEditorProfile != null) {
        val target = onlineEditorProfile!!
        var yamlText by remember(target) { mutableStateOf("") }

        // The profile file can be large; never read it synchronously during composition.
        LaunchedEffect(target) {
            yamlText = withContext(Dispatchers.IO) {
                runCatching { target.file.readText() }.getOrDefault("")
            }
        }

        Dialog(onDismissRequest = { onlineEditorProfile = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = LoonCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "在线编辑 YAML",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = LoonTextPrimary
                        )
                        IconButton(onClick = { onlineEditorProfile = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", tint = LoonTextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = yamlText,
                        onValueChange = { yamlText = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = LoonTextPrimary,
                            lineHeight = 17.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LoonBg,
                            unfocusedContainerColor = LoonBg,
                            focusedBorderColor = LoonBlue,
                            unfocusedBorderColor = LoonCardBorder
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { onlineEditorProfile = null }) {
                            Text("取消", color = LoonTextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val ok = ConfigManager.saveProfileContent(context, target.id, yamlText)
                                if (ok) {
                                    Toast.makeText(context, "配置内容已保存", Toast.LENGTH_SHORT).show()
                                    val updated = target.copy(lastUpdated = System.currentTimeMillis())
                                    onUpdateProfile(updated)
                                    if (target.id == selectedProfileId) {
                                        onSelectProfile(updated)
                                    }
                                    onlineEditorProfile = null
                                } else {
                                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LoonBlue),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("保存并应用", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 5. Delete Confirmation Dialog
    if (deleteConfirmProfile != null) {
        val target = deleteConfirmProfile!!
        AlertDialog(
            onDismissRequest = { deleteConfirmProfile = null },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("删除配置", color = LoonTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text("确定要删除配置 [${target.name}] 吗？此操作无法撤销", color = LoonTextSecondary, fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProfile(target)
                        deleteConfirmProfile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedDanger, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("确认删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmProfile = null }) {
                    Text("取消", color = LoonTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun LoonProfileCard(
    modifier: Modifier = Modifier,
    item: ProfileItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onOverride: () -> Unit,
    onEdit: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val lang = LocalAppLanguage.current

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) LoonBlue else LoonCardBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color(0x08000000))
            .clickable { onClick() },
        color = LoonCard
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Header Row: Name & Subtitle on left, 3-dots Menu on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = if (isSelected) LoonBlue else LoonTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // 3-Dots Menu (...)
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = LoonTextSecondary
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(18.dp),
                        containerColor = LoonCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder)
                    ) {
                        DropdownMenuItem(
                            text = { Text(AppStrings.get("profile_override", lang), color = LoonTextPrimary, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Code, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onOverride()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(AppStrings.get("profile_edit", lang), color = LoonTextPrimary, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            }
                        )
                        if (item.url != null) {
                            DropdownMenuItem(
                                text = { Text(AppStrings.get("profile_update", lang), color = LoonTextPrimary, fontSize = 14.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    menuExpanded = false
                                    onUpdate()
                                }
                            )
                        }
                        if (onMoveUp != null) {
                            DropdownMenuItem(
                                text = { Text("上移配置", color = LoonTextPrimary, fontSize = 14.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    menuExpanded = false
                                    onMoveUp()
                                }
                            )
                        }
                        if (onMoveDown != null) {
                            DropdownMenuItem(
                                text = { Text("下移配置", color = LoonTextPrimary, fontSize = 14.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = LoonBlue, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    menuExpanded = false
                                    onMoveDown()
                                }
                            )
                        }
                        HorizontalDivider(color = LoonCardBorder)
                        DropdownMenuItem(
                            text = { Text(AppStrings.get("profile_delete", lang), color = RedDanger, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = RedDanger, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            // Traffic & Expiry info (if subscription data exists)
            if (item.totalBytes > 0L) {
                Spacer(modifier = Modifier.height(10.dp))
                val usedBytes = (item.uploadBytes + item.downloadBytes).coerceAtLeast(0L)
                val remainingBytes = (item.totalBytes - usedBytes).coerceAtLeast(0L)
                val progress = if (item.totalBytes > 0L) (usedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTraffic(remainingBytes)} / ${formatTraffic(item.totalBytes)}",
                            fontSize = 12.sp,
                            color = LoonTextSecondary
                        )
                        if (item.expireTimestamp > 0L) {
                            val expireMs = if (item.expireTimestamp < 10000000000L) item.expireTimestamp * 1000L else item.expireTimestamp
                            val isExpired = System.currentTimeMillis() > expireMs
                            val expireDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expireMs))
                            Text(
                                text = expireDateStr,
                                color = if (isExpired) RedDanger else LoonTextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(LoonEditBlueBg)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = progress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (progress > 0.9f) RedDanger else LoonBlue)
                        )
                    }
                }
            } else if (item.expireTimestamp > 0L) {
                Spacer(modifier = Modifier.height(6.dp))
                val expireMs = if (item.expireTimestamp < 10000000000L) item.expireTimestamp * 1000L else item.expireTimestamp
                val isExpired = System.currentTimeMillis() > expireMs
                val expireDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expireMs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = expireDateStr,
                        color = if (isExpired) RedDanger else LoonTextMuted,
                        fontSize = 11.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Row: update time only
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatRelativeTime(item.lastUpdated),
                    color = LoonTextMuted,
                    fontSize = 11.5.sp
                )
            }
        }
    }
}





package com.github.mihomo.android.ui.screens

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    onBack: () -> Unit,
    onScriptsChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()

    var scripts by remember { mutableStateOf(emptyList<ScriptItem>()) }
    var updatingScriptIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        scripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }

    fun refresh() {
        scripts = ScriptManager.getScripts(context)
        onScriptsChanged()
    }

    var showActionMenuSheet by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }

    var editingScript by remember { mutableStateOf<ScriptItem?>(null) }
    var scriptCodeInput by remember { mutableStateOf("") }
    var deleteConfirmScript by remember { mutableStateOf<ScriptItem?>(null) }

    val localScriptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                var fileName = "local_script.js"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }
                }
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (content.isNotBlank()) {
                    val scriptName = fileName.removeSuffix(".js").removeSuffix(".txt")
                    ScriptManager.saveLocalScript(
                        context = context,
                        name = scriptName,
                        pattern = "",
                        type = "http-response",
                        code = content
                    )
                    refresh()
                    Toast.makeText(context, "已导入本地脚本: $scriptName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "脚本内容为空", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(context, "读取脚本失败: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Cupertino Navigation Bar
        CupertinoNavBar(
            title = "脚本中心",
            subtitle = "${scripts.size} 个配置覆写脚本",
            showBackButton = true,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = {
                        haptic.selection()
                        showActionMenuSheet = true
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加脚本",
                        tint = IosBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        )

        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(LoonCard),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = Color(0xFF5856D6),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无配置脚本",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = LoonTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "脚本可用于在节点解析、规则匹配时动态重写配置",
                        fontSize = 13.sp,
                        color = LoonTextSecondary,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                haptic.selection()
                                showCreateDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IosBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("新建脚本", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                haptic.selection()
                                showTemplateDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder)
                        ) {
                            Text("模板创建", color = LoonTextPrimary)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .bounceOverscroll()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                        color = LoonCard,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            scripts.forEachIndexed { index, script ->
                                val isUpdating = updatingScriptIds.contains(script.id)
                                val isRemote = script.url.isNotBlank()

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            haptic.selection()
                                            val code = ScriptManager.readScriptCode(context, script)
                                            scriptCodeInput = code
                                            editingScript = script
                                        }
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = script.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = LoonTextPrimary,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${script.type.uppercase()} · ${formatRelativeTime(script.updatedAt)}",
                                            fontSize = 11.5.sp,
                                            color = LoonTextSecondary
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isRemote) {
                                            IconButton(
                                                onClick = {
                                                    if (!isUpdating) {
                                                        haptic.selection()
                                                        updatingScriptIds = updatingScriptIds + script.id
                                                        scope.launch {
                                                            val res = ScriptManager.updateScriptFromRemote(context, script)
                                                            updatingScriptIds = updatingScriptIds - script.id
                                                            if (res.isSuccess) {
                                                                refresh()
                                                                Toast.makeText(context, "脚本更新成功", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                Toast.makeText(context, "更新失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                if (isUpdating) {
                                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = IosBlue)
                                                } else {
                                                    Icon(Icons.Default.Refresh, contentDescription = "更新", tint = LoonTextSecondary, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }

                                        CupertinoSwitch(
                                            checked = script.enabled,
                                            onCheckedChange = { checked ->
                                                ScriptManager.toggleScript(context, script.id, checked)
                                                refresh()
                                            }
                                        )

                                        IconButton(
                                            onClick = {
                                                haptic.selection()
                                                deleteConfirmScript = script
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "删除", tint = LoonTextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }

                                if (index < scripts.size - 1) {
                                    HorizontalDivider(
                                        color = LoonCardBorder,
                                        thickness = 0.5.dp,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Flat Action Menu BottomSheet (NO boxed cards inside)
    if (showActionMenuSheet) {
        val actions = listOf(
            Triple("新建空白脚本", "直接编写 JavaScript 自定义逻辑", Icons.Default.EditNote),
            Triple("从网络链接导入", "支持 GitHub 等直接 RAW 链接", Icons.Default.Link),
            Triple("从本地文件导入", "选择设备中的 .js 或 .txt 脚本文件", Icons.Default.FolderOpen),
            Triple("从预设模板创建", "去广告、节点规范化与重定向", Icons.Default.AutoAwesome)
        )

        ModalBottomSheet(
            onDismissRequest = { showActionMenuSheet = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "添加脚本",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                actions.forEachIndexed { index, (title, desc, icon) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                haptic.selection()
                                showActionMenuSheet = false
                                when (index) {
                                    0 -> showCreateDialog = true
                                    1 -> showImportUrlDialog = true
                                    2 -> localScriptLauncher.launch("*/*")
                                    3 -> showTemplateDialog = true
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = IosBlue, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LoonTextPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(desc, fontSize = 12.sp, color = LoonTextSecondary)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = LoonChevronTint, modifier = Modifier.size(16.dp))
                    }

                    if (index < actions.size - 1) {
                        HorizontalDivider(
                            color = LoonCardBorder,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }

    // Create Empty Script BottomSheet
    if (showCreateDialog) {
        var nameInput by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { showCreateDialog = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("新建脚本", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LoonTextPrimary)
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("脚本名称") },
                    placeholder = { Text("例如: RuleRewriter") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = {
                        val name = nameInput.ifBlank { "Untitled_Script" }
                        val defaultCode = """
                            // Nexus Script Studio
                            // Type: http-response
                            function main(params) {
                                return params;
                            }
                        """.trimIndent()
                        ScriptManager.saveLocalScript(context, name, "", "http-response", defaultCode)
                        refresh()
                        showCreateDialog = false
                        Toast.makeText(context, "脚本创建成功", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IosBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("创建并保存", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Import from URL BottomSheet
    if (showImportUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var isDownloading by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { if (!isDownloading) showImportUrlDialog = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("从链接导入脚本", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LoonTextPrimary)
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("脚本 URL") },
                    placeholder = { Text("https://raw.githubusercontent.com/...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("脚本名称 (选填)") },
                    placeholder = { Text("留空将自动提取文件名") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            isDownloading = true
                            scope.launch {
                                val res = ScriptManager.downloadScript(context, urlInput.trim(), nameInput.trim(), "", "http-response")
                                isDownloading = false
                                if (res.isSuccess) {
                                    refresh()
                                    showImportUrlDialog = false
                                    Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "下载失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    enabled = urlInput.isNotBlank() && !isDownloading,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IosBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("立即导入", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Preset Templates BottomSheet (Flat list, NO box-in-a-box)
    if (showTemplateDialog) {
        val templates = listOf(
            Triple("分流与规则重写模板", "自动为指定域名或路径重定向或注入代理规则", """
                // 智能分流重写脚本
                function main(config) {
                    return config;
                }
            """.trimIndent()),
            Triple("节点智能重命名模板", "去除节点前后缀无用 Emoji 与广告标签", """
                // 节点名称规范化脚本
                function main(proxies) {
                    return proxies.map(p => {
                        p.name = p.name.replace(/\[.*?\]|\(.*?\)/g, "").trim();
                        return p;
                    });
                }
            """.trimIndent()),
            Triple("URL 302 重定向模板", "将目标请求重定向至本地或代理镜像", """
                // HTTP 302 重定向
                function handleRequest(req) {
                    if (req.url.indexOf("example.com") !== -1) {
                        return { status: 302, headers: { Location: "https://redirected.com" } };
                    }
                    return req;
                }
            """.trimIndent())
        )

        ModalBottomSheet(
            onDismissRequest = { showTemplateDialog = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "选择预设模板",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                templates.forEachIndexed { idx, (tplName, tplDesc, tplCode) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                haptic.selection()
                                ScriptManager.saveLocalScript(context, tplName, "", "rule", tplCode)
                                refresh()
                                showTemplateDialog = false
                                Toast.makeText(context, "已基于模板创建: $tplName", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFAF52DE), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tplName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LoonTextPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(tplDesc, fontSize = 12.sp, color = LoonTextSecondary)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = LoonChevronTint, modifier = Modifier.size(16.dp))
                    }
                    if (idx < templates.size - 1) {
                        HorizontalDivider(
                            color = LoonCardBorder,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }

    // Cupertino Code Studio Editor Modal
    if (editingScript != null) {
        val target = editingScript!!
        ModalBottomSheet(
            onDismissRequest = { editingScript = null },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { editingScript = null }) {
                        Text("取消", color = LoonTextSecondary)
                    }

                    Text(
                        text = target.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = LoonTextPrimary
                    )

                    TextButton(
                        onClick = {
                            haptic.selection()
                            ScriptManager.updateScriptCode(context, target, scriptCodeInput)
                            refresh()
                            editingScript = null
                            Toast.makeText(context, "代码已保存", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("保存", color = IosBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Editor Canvas
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = LoonBg,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    OutlinedTextField(
                        value = scriptCodeInput,
                        onValueChange = { scriptCodeInput = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = LoonTextPrimary
                        )
                    )
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (deleteConfirmScript != null) {
        val target = deleteConfirmScript!!
        AlertDialog(
            onDismissRequest = { deleteConfirmScript = null },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("删除脚本", color = LoonTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text("确定要删除脚本 [${target.name}] 吗？相关的配置重写将失效。", color = LoonTextSecondary, fontSize = 14.sp)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ScriptManager.deleteScript(context, target.id)
                        refresh()
                        deleteConfirmScript = null
                        Toast.makeText(context, "脚本已删除", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("删除", color = IosRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmScript = null }) {
                    Text("取消", color = LoonTextSecondary)
                }
            }
        )
    }
}

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
import androidx.compose.ui.tooling.preview.Preview
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
                    CupertinoToast.show("已导入本地脚本: $scriptName")
                } else {
                    CupertinoToast.show("脚本内容为空", isDestructive = true)
                }
            }.onFailure {
                CupertinoToast.show("读取脚本失败: ${it.message}", isDestructive = true)
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
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .iosPressable {
                            haptic.selection()
                            showActionMenuSheet = true
                        },
                    contentAlignment = Alignment.Center
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
                        CupertinoButton(
                            text = "新建脚本",
                            onClick = {
                                haptic.selection()
                                showCreateDialog = true
                            },
                            style = CupertinoButtonStyle.Filled
                        )
                        CupertinoButton(
                            text = "模板创建",
                            onClick = {
                                haptic.selection()
                                showTemplateDialog = true
                            },
                            style = CupertinoButtonStyle.Tinted
                        )
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
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .iosPressable(enabled = !isUpdating) {
                                                        haptic.selection()
                                                        updatingScriptIds = updatingScriptIds + script.id
                                                        scope.launch {
                                                            val res = ScriptManager.updateScriptFromRemote(context, script)
                                                            updatingScriptIds = updatingScriptIds - script.id
                                                            if (res.isSuccess) {
                                                                refresh()
                                                                CupertinoToast.show("脚本更新成功")
                                                            } else {
                                                                CupertinoToast.show("更新失败: ${res.exceptionOrNull()?.message}", isDestructive = true)
                                                            }
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isUpdating) {
                                                    CupertinoActivityIndicator(size = 14.dp, color = IosBlue)
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

                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .iosPressable {
                                                    haptic.selection()
                                                    deleteConfirmScript = script
                                                },
                                            contentAlignment = Alignment.Center
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

        CupertinoModalBottomSheet(
            onDismissRequest = { showActionMenuSheet = false },
            title = "添加脚本"
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
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
        CupertinoModalBottomSheet(
            onDismissRequest = { showCreateDialog = false },
            title = "新建脚本"
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                CupertinoTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = "脚本名称 (例如: RuleRewriter)"
                )
                Spacer(modifier = Modifier.height(18.dp))
                CupertinoButton(
                    text = "创建并保存",
                    onClick = {
                        val name = nameInput.ifBlank { "Untitled_Script" }
                        val defaultCode = """
                            // Nexus Script Studio
                            function main(config) {
                                return config;
                            }
                        """.trimIndent()
                        ScriptManager.saveLocalScript(context, name, "", "rule", defaultCode)
                        refresh()
                        showCreateDialog = false
                        CupertinoToast.show("脚本创建成功")
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    style = CupertinoButtonStyle.Filled
                )
            }
        }
    }

    // Import from URL BottomSheet
    if (showImportUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var isDownloading by remember { mutableStateOf(false) }

        CupertinoModalBottomSheet(
            onDismissRequest = { if (!isDownloading) showImportUrlDialog = false },
            title = "从链接导入脚本"
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                CupertinoTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = "脚本 URL (https://...)"
                )
                Spacer(modifier = Modifier.height(10.dp))
                CupertinoTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = "脚本名称 (选填，留空将自动提取)"
                )
                Spacer(modifier = Modifier.height(18.dp))
                CupertinoButton(
                    text = if (isDownloading) "正在下载..." else "立即导入",
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            isDownloading = true
                            scope.launch {
                                val res = ScriptManager.downloadScript(context, urlInput.trim(), nameInput.trim(), "", "rule")
                                isDownloading = false
                                if (res.isSuccess) {
                                    refresh()
                                    showImportUrlDialog = false
                                    CupertinoToast.show("导入成功")
                                } else {
                                    CupertinoToast.show("下载失败: ${res.exceptionOrNull()?.message}", isDestructive = true)
                                }
                            }
                        }
                    },
                    enabled = urlInput.isNotBlank() && !isDownloading,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    style = CupertinoButtonStyle.Filled
                )
            }
        }
    }

    // Preset Templates BottomSheet (Flat list, NO box-in-a-box)
    if (showTemplateDialog) {
        val templates = listOf(
            Triple("分流与规则重写模板", "自动为配置注入高优先级自定义分流规则", """
                // 智能分流规则注入脚本
                function main(config) {
                    var customRules = [
                        "DOMAIN-SUFFIX,openai.com,DIRECT",
                        "DOMAIN-KEYWORD,adservice,REJECT"
                    ];
                    config.rules = customRules.concat(config.rules || []);
                    return config;
                }
            """.trimIndent()),
            Triple("节点智能重命名模板", "去除节点前后缀无用括号与广告标签并同步策略组", """
                // 节点名称规范化脚本
                function main(config) {
                    if (!Array.isArray(config.proxies)) return config;
                    var renameMap = {};
                    config.proxies = config.proxies.map(function(p) {
                        var oldName = p.name;
                        var newName = oldName.replace(/\[.*?\]|\(.*?\)/g, "").trim() || oldName;
                        renameMap[oldName] = newName;
                        p.name = newName;
                        return p;
                    });
                    if (Array.isArray(config["proxy-groups"])) {
                        config["proxy-groups"].forEach(function(g) {
                            if (Array.isArray(g.proxies)) {
                                g.proxies = g.proxies.map(function(n) { return renameMap[n] || n; });
                            }
                        });
                    }
                    return config;
                }
            """.trimIndent()),
            Triple("DNS 与 Hosts 映射重写模板", "将指定域名固定映射到自定义 IP 或镜像地址", """
                // 自定义 Hosts 与 DNS 覆写脚本
                function main(config) {
                    config.hosts = Object.assign({}, config.hosts || {}, {
                        "example.com": "127.0.0.1"
                    });
                    return config;
                }
            """.trimIndent())
        )

        CupertinoModalBottomSheet(
            onDismissRequest = { showTemplateDialog = false },
            title = "选择预设模板"
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            ) {
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
                                CupertinoToast.show("已基于模板创建: $tplName")
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
        CupertinoModalBottomSheet(
            onDismissRequest = { editingScript = null },
            modifier = Modifier.fillMaxHeight(0.92f),
            title = target.name,
            leadingAction = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .iosPressable { editingScript = null }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("取消", color = LoonTextSecondary, fontSize = 15.sp)
                }
            },
            trailingAction = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .iosPressable {
                            haptic.selection()
                            ScriptManager.updateScriptCode(context, target, scriptCodeInput)
                            refresh()
                            editingScript = null
                            CupertinoToast.show("代码已保存")
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("保存", color = IosBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(6.dp))

                // Editor Canvas (Pure Cupertino BasicTextField, no Material3 OutlinedTextField)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = LoonBg,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = scriptCodeInput,
                        onValueChange = { scriptCodeInput = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(IosBlue),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
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
        CupertinoAlertDialog(
            title = "删除脚本",
            message = "确定要删除脚本 [${target.name}] 吗？相关的配置重写将失效。",
            confirmText = "删除",
            dismissText = "取消",
            isDestructive = true,
            onConfirm = {
                ScriptManager.deleteScript(context, target.id)
                refresh()
                deleteConfirmScript = null
                CupertinoToast.show("脚本已删除")
            },
            onDismissRequest = { deleteConfirmScript = null }
        )
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ScriptsScreenPreview() {
    MihomoTheme {
        ScriptsScreen(
            onBack = {},
            onScriptsChanged = {}
        )
    }
}

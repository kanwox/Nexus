package com.github.mihomo.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ConfigScriptEngine
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ScriptBuildMode(val label: String) {
    NEW("新建脚本"),
    URL("URL 导入"),
    LOCAL("本地导入")
}

private enum class WorkbenchTab(val label: String) {
    EDITOR("代码编辑"),
    PREVIEW("运行预览")
}

private enum class PreviewSubTab(val label: String) {
    YAML("输出 YAML"),
    CONSOLE("控制台")
}

private data class ScriptWorkspaceState(
    val existingScript: ScriptItem? = null,
    val initialMode: ScriptBuildMode = ScriptBuildMode.NEW
)

private val DEFAULT_NEW_SCRIPT_CODE = """
// Mihomo 配置覆写脚本 (ES2020 / QuickJS)
function main(config) {
  const proxies = config.proxies || [];
  console.log("当前订阅节点数量:", proxies.length);

  // 示例：注入高优先级自定义分流规则
  const customRules = [
    "DOMAIN-SUFFIX,openai.com,DIRECT"
  ];
  config.rules = customRules.concat(config.rules || []);
  return config;
}
""".trimIndent()

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
    var workspaceState by remember { mutableStateOf<ScriptWorkspaceState?>(null) }
    var deleteConfirmScript by remember { mutableStateOf<ScriptItem?>(null) }

    LaunchedEffect(Unit) {
        scripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }

    fun refresh() {
        scripts = ScriptManager.getScripts(context)
        onScriptsChanged()
    }

    AnimatedContent(
        targetState = workspaceState,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(260, easing = FastOutSlowInEasing)
                ) + fadeIn(tween(180))) togetherWith (slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(150)))
            } else {
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                ) + fadeIn(tween(180))) togetherWith (slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(150)))
            }
        },
        label = "scripts_page_transition"
    ) { activeWorkspace ->
        if (activeWorkspace != null) {
            ScriptWorkspaceScreen(
                workspaceState = activeWorkspace,
                onBack = { workspaceState = null },
                onSaved = {
                    refresh()
                    workspaceState = null
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LoonBg)
                    .statusBarsPadding()
            ) {
                CupertinoNavBar(
                    title = "脚本",
                    subtitle = if (scripts.isEmpty()) null else "${scripts.count { it.enabled }} / ${scripts.size} 已启用",
                    showBackButton = true,
                    onBack = onBack,
                    actions = {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .iosPressable {
                                    haptic.selection()
                                    workspaceState = ScriptWorkspaceState(
                                        existingScript = null,
                                        initialMode = ScriptBuildMode.NEW
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "构建脚本",
                                tint = IosBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                )

                // Quick 3-Way Build Entry Bar (iOS Inset Card Row)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ScriptQuickBuildPill(
                        title = "新建脚本",
                        icon = Icons.Default.Code,
                        tint = IosBlue,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.selection()
                            workspaceState = ScriptWorkspaceState(null, ScriptBuildMode.NEW)
                        }
                    )
                    ScriptQuickBuildPill(
                        title = "URL 导入",
                        icon = Icons.Default.Link,
                        tint = Color(0xFF5856D6),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.selection()
                            workspaceState = ScriptWorkspaceState(null, ScriptBuildMode.URL)
                        }
                    )
                    ScriptQuickBuildPill(
                        title = "本地导入",
                        icon = Icons.Default.FolderOpen,
                        tint = IosOrange,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.selection()
                            workspaceState = ScriptWorkspaceState(null, ScriptBuildMode.LOCAL)
                        }
                    )
                }

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
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(LoonCard)
                                    .border(0.5.dp, LoonCardBorder, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = IosBlue,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "暂无配置覆写脚本",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LoonTextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "支持通过新建、URL 导入或本地文件导入脚本\n在订阅解析与启动时动态重写节点、策略组与分流规则",
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = LoonTextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            CupertinoButton(
                                text = "进入脚本构建台",
                                onClick = {
                                    haptic.selection()
                                    workspaceState = ScriptWorkspaceState(null, ScriptBuildMode.NEW)
                                },
                                style = CupertinoButtonStyle.Filled,
                                icon = Icons.Default.Add
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .bounceOverscroll(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp)),
                                border = BorderStroke(0.5.dp, LoonCardBorder),
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
                                                .clickable {
                                                    haptic.selection()
                                                    workspaceState = ScriptWorkspaceState(
                                                        existingScript = script,
                                                        initialMode = if (isRemote) ScriptBuildMode.URL else ScriptBuildMode.NEW
                                                    )
                                                }
                                                .padding(horizontal = 16.dp, vertical = 13.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(RoundedCornerShape(9.dp))
                                                        .background(
                                                            if (isRemote) Color(0xFF5856D6).copy(alpha = 0.12f)
                                                            else IosBlue.copy(alpha = 0.12f)
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = if (isRemote) Icons.Default.CloudDownload else Icons.Default.Code,
                                                        contentDescription = null,
                                                        tint = if (isRemote) Color(0xFF5856D6) else IosBlue,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = script.name,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = LoonTextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = buildString {
                                                            append(if (isRemote) "远程脚本" else "本地脚本")
                                                            append(" · ")
                                                            append(formatRelativeTime(script.updatedAt))
                                                        },
                                                        fontSize = 11.5.sp,
                                                        color = LoonTextSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (isRemote) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(30.dp)
                                                            .clip(CircleShape)
                                                            .iosPressable(enabled = !isUpdating) {
                                                                haptic.selection()
                                                                updatingScriptIds = updatingScriptIds + script.id
                                                                scope.launch {
                                                                    val res = ScriptManager.updateScriptFromRemote(context, script)
                                                                    updatingScriptIds = updatingScriptIds - script.id
                                                                    if (res.isSuccess) {
                                                                        refresh()
                                                                        CupertinoToast.show("脚本已同步最新远程版本")
                                                                    } else {
                                                                        CupertinoToast.show(
                                                                            "同步失败: ${res.exceptionOrNull()?.message}",
                                                                            isDestructive = true
                                                                        )
                                                                    }
                                                                }
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (isUpdating) {
                                                            CupertinoActivityIndicator(size = 14.dp, color = IosBlue)
                                                        } else {
                                                            Icon(
                                                                Icons.Default.Refresh,
                                                                contentDescription = "同步远程脚本",
                                                                tint = LoonTextSecondary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
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
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .iosPressable {
                                                            haptic.selection()
                                                            deleteConfirmScript = script
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Default.DeleteOutline,
                                                        contentDescription = "删除",
                                                        tint = LoonTextSecondary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        if (index < scripts.lastIndex) {
                                            HorizontalDivider(
                                                color = LoonCardBorder,
                                                thickness = 0.5.dp,
                                                modifier = Modifier.padding(start = 62.dp, end = 16.dp)
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
    }

    if (deleteConfirmScript != null) {
        val target = deleteConfirmScript!!
        CupertinoAlertDialog(
            title = "删除脚本",
            message = "确定要删除脚本「${target.name}」吗？此操作不可撤销。",
            confirmText = "删除",
            dismissText = "取消",
            isDestructive = true,
            onConfirm = {
                ScriptManager.deleteScript(context, target.id)
                refresh()
                deleteConfirmScript = null
                CupertinoToast.show("已删除脚本")
            },
            onDismissRequest = { deleteConfirmScript = null }
        )
    }
}

@Composable
private fun ScriptQuickBuildPill(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(11.dp))
            .iosPressable(pressedScale = 0.96f, onClick = onClick),
        color = LoonCard,
        border = BorderStroke(0.5.dp, LoonCardBorder),
        shape = RoundedCornerShape(11.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = LoonTextPrimary,
                maxLines = 1
            )
        }
    }
}

// =============================================================================
// Standalone Full-Screen Script Builder & VS Code Workbench Page
// =============================================================================
@Composable
private fun ScriptWorkspaceScreen(
    workspaceState: ScriptWorkspaceState,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()
    val isDark = LocalLoonColors.current.isDark

    val existing = workspaceState.existingScript
    val isEditingExisting = existing != null

    var buildMode by remember(workspaceState) {
        mutableStateOf(workspaceState.initialMode)
    }
    var workbenchTab by remember { mutableStateOf(WorkbenchTab.EDITOR) }
    var previewSubTab by remember { mutableStateOf(PreviewSubTab.YAML) }

    var scriptName by remember(existing) {
        mutableStateOf(existing?.name ?: "")
    }
    var scriptUrl by remember(existing) {
        mutableStateOf(existing?.url ?: "")
    }
    var localFileLabel by remember { mutableStateOf("") }

    val initialCode = remember(existing) {
        if (existing != null) {
            ScriptManager.readScriptCode(context, existing)
        } else if (workspaceState.initialMode == ScriptBuildMode.NEW) {
            DEFAULT_NEW_SCRIPT_CODE
        } else {
            ""
        }
    }

    var textFieldValue by remember(existing) {
        mutableStateOf(TextFieldValue(text = initialCode, selection = TextRange(initialCode.length)))
    }

    // Undo / Redo History Stack for VS Code Editor experience
    val undoStack = remember(existing) { mutableStateListOf(initialCode) }
    var undoIndex by remember(existing) { mutableIntStateOf(0) }

    fun updateCodeWithHistory(newValue: TextFieldValue) {
        val oldText = textFieldValue.text
        textFieldValue = newValue
        if (newValue.text != oldText) {
            while (undoStack.size > undoIndex + 1) {
                undoStack.removeAt(undoStack.lastIndex)
            }
            if (undoStack.size >= 40) {
                undoStack.removeAt(0)
            } else {
                undoIndex++
            }
            undoStack.add(newValue.text)
            undoIndex = undoStack.lastIndex
        }
    }

    fun insertSnippet(snippet: String, cursorOffsetBack: Int = 0) {
        val current = textFieldValue.text
        val start = textFieldValue.selection.min.coerceIn(0, current.length)
        val end = textFieldValue.selection.max.coerceIn(0, current.length)
        val updated = current.substring(0, start) + snippet + current.substring(end)
        val newCursor = (start + snippet.length - cursorOffsetBack).coerceIn(0, updated.length)
        updateCodeWithHistory(TextFieldValue(text = updated, selection = TextRange(newCursor)))
    }

    var isFetchingUrl by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isRunningPreview by remember { mutableStateOf(false) }
    var previewResult by remember { mutableStateOf<ConfigScriptEngine.ScriptPreviewResult?>(null) }
    var activeProfileTitle by remember { mutableStateOf("内置示例订阅") }

    val localFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                var displayName = "local_script.js"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                }
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件内容")
                localFileLabel = displayName
                if (scriptName.isBlank()) {
                    scriptName = displayName.removeSuffix(".js").removeSuffix(".yaml").removeSuffix(".yml").removeSuffix(".txt")
                }
                updateCodeWithHistory(TextFieldValue(text = content, selection = TextRange(content.length)))
                CupertinoToast.show("已载入本地文件: $displayName")
            }.onFailure { e ->
                CupertinoToast.show("读取文件失败: ${e.message}", isDestructive = true)
            }
        }
    }

    fun triggerPreview() {
        if (isRunningPreview) return
        val codeToRun = textFieldValue.text
        isRunningPreview = true
        workbenchTab = WorkbenchTab.PREVIEW
        scope.launch {
            val (profileName, rawYaml) = withContext(Dispatchers.IO) {
                val profiles = ConfigManager.getProfiles(context)
                val first = profiles.firstOrNull { it.file.exists() }
                if (first != null) {
                    first.name to runCatching { first.file.readText() }.getOrDefault("")
                } else {
                    "内置示例配置" to ""
                }
            }
            activeProfileTitle = profileName
            val res = withContext(Dispatchers.IO) {
                ConfigScriptEngine.previewScript(
                    rawYaml = rawYaml,
                    code = codeToRun,
                    scriptName = scriptName.ifBlank { "script.js" }
                )
            }
            previewResult = res
            isRunningPreview = false
            if (!res.success) {
                previewSubTab = PreviewSubTab.CONSOLE
            }
        }
    }

    fun handleSave() {
        if (isSaving) return
        scope.launch {
            isSaving = true
            try {
                var finalCode = textFieldValue.text
                val cleanUrl = scriptUrl.trim()

                if (buildMode == ScriptBuildMode.URL && finalCode.isBlank()) {
                    if (cleanUrl.isBlank()) {
                        CupertinoToast.show("请输入脚本 URL 链接", isDestructive = true)
                        return@launch
                    }
                    val fetched = ScriptManager.fetchRemoteScriptContent(cleanUrl)
                    if (fetched.isFailure) {
                        CupertinoToast.show(
                            "拉取远程脚本失败: ${fetched.exceptionOrNull()?.message}",
                            isDestructive = true
                        )
                        return@launch
                    }
                    finalCode = fetched.getOrDefault("")
                }

                if (finalCode.isBlank()) {
                    CupertinoToast.show("脚本代码不能为空", isDestructive = true)
                    return@launch
                }

                val resolvedName = scriptName.trim().ifBlank {
                    when {
                        cleanUrl.isNotBlank() -> cleanUrl.substringAfterLast("/").substringBefore("?").removeSuffix(".js").ifBlank { "远程脚本" }
                        localFileLabel.isNotBlank() -> localFileLabel.removeSuffix(".js").removeSuffix(".txt")
                        else -> "自定义脚本"
                    }
                }

                withContext(Dispatchers.IO) {
                    val savedItem = ScriptManager.saveScript(
                        context = context,
                        existingId = existing?.id,
                        name = resolvedName,
                        url = if (buildMode == ScriptBuildMode.URL || existing?.url?.isNotBlank() == true) cleanUrl else "",
                        pattern = existing?.pattern ?: "",
                        type = existing?.type ?: "config",
                        code = finalCode,
                        enabled = existing?.enabled ?: true
                    )
                    // Also link to profiles that have script override enabled so new scripts take effect seamlessly
                    val profiles = ConfigManager.getProfiles(context)
                    profiles.forEach { profile ->
                        if (profile.scriptEnabled && !profile.scriptIds.contains(savedItem.id)) {
                            ConfigManager.updateProfile(
                                context,
                                profile.copy(scriptIds = profile.scriptIds + savedItem.id)
                            )
                        }
                    }
                }

                CupertinoToast.show(if (isEditingExisting) "脚本已保存" else "脚本构建完成")
                onSaved()
            } finally {
                isSaving = false
            }
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. Top Navigation Bar
        CupertinoNavBar(
            title = if (isEditingExisting) "编辑脚本" else "构建脚本",
            subtitle = if (isEditingExisting) existing?.name else buildMode.label,
            showBackButton = true,
            onBack = onBack,
            actions = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .iosPressable(enabled = !isRunningPreview) {
                                haptic.selection()
                                triggerPreview()
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "运行预览",
                                tint = IosGreen,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "运行",
                                color = IosGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .iosPressable(enabled = !isSaving) {
                                haptic.impact()
                                handleSave()
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSaving) "保存中" else "保存",
                            color = IosBlue,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        )

        // 2. Build Method Selector & Compact Metadata Configuration Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isEditingExisting) {
                CupertinoSegmentedControl(
                    items = ScriptBuildMode.entries,
                    selectedItem = buildMode,
                    onItemSelected = { mode ->
                        buildMode = mode
                        if (mode == ScriptBuildMode.NEW && textFieldValue.text.isBlank()) {
                            updateCodeWithHistory(
                                TextFieldValue(
                                    text = DEFAULT_NEW_SCRIPT_CODE,
                                    selection = TextRange(DEFAULT_NEW_SCRIPT_CODE.length)
                                )
                            )
                        }
                    },
                    labelProvider = { it.label }
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = LoonCard,
                border = BorderStroke(0.5.dp, LoonCardBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Script Name Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "名称",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = LoonTextSecondary,
                            modifier = Modifier.width(44.dp)
                        )
                        BasicTextField(
                            value = scriptName,
                            onValueChange = { scriptName = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = LoonTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(IosBlue),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (scriptName.isEmpty()) {
                                        Text(
                                            text = "输入脚本名称 (选填，可自动识别)",
                                            color = LoonTextMuted,
                                            fontSize = 13.5.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    // Mode-Specific Source Row (URL Import or Local Import)
                    if (buildMode == ScriptBuildMode.URL || (isEditingExisting && scriptUrl.isNotBlank())) {
                        HorizontalDivider(color = LoonCardBorder, thickness = 0.5.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "链接",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = LoonTextSecondary,
                                modifier = Modifier.width(44.dp)
                            )
                            BasicTextField(
                                value = scriptUrl,
                                onValueChange = { scriptUrl = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = LoonTextPrimary,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                cursorBrush = SolidColor(IosBlue),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (scriptUrl.isEmpty()) {
                                            Text(
                                                text = "https://raw.githubusercontent.com/.../script.js",
                                                color = LoonTextMuted,
                                                fontSize = 12.5.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .iosPressable(enabled = !isFetchingUrl && scriptUrl.isNotBlank()) {
                                        haptic.selection()
                                        isFetchingUrl = true
                                        scope.launch {
                                            val res = ScriptManager.fetchRemoteScriptContent(scriptUrl)
                                            isFetchingUrl = false
                                            if (res.isSuccess) {
                                                val body = res.getOrDefault("")
                                                updateCodeWithHistory(
                                                    TextFieldValue(text = body, selection = TextRange(0))
                                                )
                                                if (scriptName.isBlank()) {
                                                    scriptName = scriptUrl.trim()
                                                        .substringAfterLast("/")
                                                        .substringBefore("?")
                                                        .removeSuffix(".js")
                                                }
                                                CupertinoToast.show("已拉取远程脚本到编辑器")
                                            } else {
                                                CupertinoToast.show(
                                                    "拉取失败: ${res.exceptionOrNull()?.message}",
                                                    isDestructive = true
                                                )
                                            }
                                        }
                                    },
                                color = IosBlue.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(7.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isFetchingUrl) {
                                        CupertinoActivityIndicator(size = 11.dp, color = IosBlue)
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = if (isFetchingUrl) "拉取中" else "拉取代码",
                                        color = IosBlue,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    } else if (buildMode == ScriptBuildMode.LOCAL) {
                        HorizontalDivider(color = LoonCardBorder, thickness = 0.5.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.selection()
                                    localFileLauncher.launch("*/*")
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "文件",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = LoonTextSecondary,
                                    modifier = Modifier.width(44.dp)
                                )
                                Text(
                                    text = localFileLabel.ifBlank { "点击选择本地 .js / .yaml / .txt 脚本文件" },
                                    color = if (localFileLabel.isNotBlank()) LoonTextPrimary else LoonTextMuted,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                color = IosOrange.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(7.dp)
                            ) {
                                Text(
                                    text = "选择文件",
                                    color = IosOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. VS Code Workbench Container (Tab Bar + Editor / Preview Canvas + Status Bar)
        val editorBg = if (isDark) Color(0xFF161618) else Color(0xFFFDFDFE)
        val gutterBg = if (isDark) Color(0xFF1D1D20) else Color(0xFFF3F4F7)
        val tabBarBg = if (isDark) Color(0xFF202024) else Color(0xFFEBEDF2)
        val statusBarBg = if (isDark) Color(0xFF1F2430) else Color(0xFF007AFF)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp),
            color = editorBg,
            border = BorderStroke(0.5.dp, LoonCardBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 3.1 VS Code File Tab Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(tabBarBg)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WorkbenchTab.entries.forEach { tab ->
                            val selected = workbenchTab == tab
                            val tabSurfaceColor = if (selected) editorBg else Color.Transparent
                            val tabTextColor = if (selected) LoonTextPrimary else LoonTextSecondary
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                    .background(tabSurfaceColor)
                                    .clickable {
                                        haptic.selection()
                                        if (tab == WorkbenchTab.PREVIEW && previewResult == null) {
                                            triggerPreview()
                                        } else {
                                            workbenchTab = tab
                                        }
                                    }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (tab == WorkbenchTab.EDITOR) Icons.Default.Description else Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = if (tab == WorkbenchTab.EDITOR) IosBlue else IosGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (tab == WorkbenchTab.EDITOR) {
                                        (scriptName.ifBlank { "script" }.take(14)) + ".js"
                                    } else {
                                        "预览与诊断"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = tabTextColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Undo / Redo / Copy Actions in Tab Header
                    if (workbenchTab == WorkbenchTab.EDITOR) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (undoIndex > 0) {
                                        undoIndex--
                                        val prev = undoStack[undoIndex]
                                        textFieldValue = TextFieldValue(prev, TextRange(prev.length))
                                    }
                                },
                                enabled = undoIndex > 0,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = "撤销",
                                    tint = if (undoIndex > 0) LoonTextPrimary else LoonTextMuted.copy(alpha = 0.4f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (undoIndex < undoStack.lastIndex) {
                                        undoIndex++
                                        val next = undoStack[undoIndex]
                                        textFieldValue = TextFieldValue(next, TextRange(next.length))
                                    }
                                },
                                enabled = undoIndex < undoStack.lastIndex,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = "重做",
                                    tint = if (undoIndex < undoStack.lastIndex) LoonTextPrimary else LoonTextMuted.copy(alpha = 0.4f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("script_code", textFieldValue.text))
                                    CupertinoToast.show("代码已复制到剪贴板")
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制代码",
                                    tint = LoonTextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = LoonCardBorder, thickness = 0.5.dp)

                // 3.2 Main Workbench Body: Editor or Preview
                if (workbenchTab == WorkbenchTab.EDITOR) {
                    val codeScrollState = rememberScrollState()
                    val codeHorizontalScroll = rememberScrollState()
                    val codeLines = remember(textFieldValue.text) {
                        val lines = textFieldValue.text.split('\n')
                        if (lines.isEmpty()) listOf("") else lines
                    }
                    val syntaxVisualTransformation = remember(isDark) {
                        CodeSyntaxVisualTransformation(isDark = isDark)
                    }

                    // Editor Canvas with Synchronized Line Number Gutter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Line Number Gutter
                        Column(
                            modifier = Modifier
                                .width(42.dp)
                                .fillMaxHeight()
                                .background(gutterBg)
                                .verticalScroll(codeScrollState)
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (lineNum in 1..codeLines.size) {
                                Text(
                                    text = lineNum.toString(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                    lineHeight = 19.sp,
                                    color = LoonTextMuted,
                                    textAlign = TextAlign.End
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(LoonCardBorder)
                        )

                        // Code Editing Area
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(codeScrollState)
                                .horizontalScroll(codeHorizontalScroll)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            BasicTextField(
                                value = textFieldValue,
                                onValueChange = { updateCodeWithHistory(it) },
                                visualTransformation = syntaxVisualTransformation,
                                cursorBrush = SolidColor(IosBlue),
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                    lineHeight = 19.sp,
                                    color = LoonTextPrimary
                                ),
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 300.dp, minHeight = 240.dp)
                                    .fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (textFieldValue.text.isEmpty()) {
                                            Text(
                                                text = "// 在此编写 function main(config) { ... } 或 YAML 覆写片段",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.5.sp,
                                                lineHeight = 19.sp,
                                                color = LoonTextMuted
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }

                    HorizontalDivider(color = LoonCardBorder, thickness = 0.5.dp)

                    // Quick Symbol & Snippet Bar (VS Code Mobile Accessory Bar)
                    val quickSnippets = remember {
                        listOf(
                            "Tab" to ("  " to 0),
                            "{ }" to ("{}" to 1),
                            "( )" to ("()" to 1),
                            "[ ]" to ("[]" to 1),
                            "=>" to ("=> " to 0),
                            "\" \"" to ("\"\"" to 1),
                            "main(config)" to ("function main(config) {\n  \n  return config;\n}" to 18),
                            "config.proxies" to ("config.proxies" to 0),
                            "config.rules" to ("config.rules" to 0),
                            "console.log" to ("console.log()" to 1)
                        )
                    }
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(gutterBg)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(quickSnippets) { (label, pair) ->
                            val (insertText, backOffset) = pair
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        haptic.lightTick()
                                        insertSnippet(insertText, backOffset)
                                    },
                                color = LoonCard,
                                border = BorderStroke(0.5.dp, LoonCardBorder),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = LoonTextPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    // 3.3 VS Code Preview & Diagnostics Workbench
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val res = previewResult
                        if (isRunningPreview) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CupertinoActivityIndicator(size = 24.dp, color = IosBlue)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "正在沙箱中执行脚本并生成配置预览...",
                                        fontSize = 13.sp,
                                        color = LoonTextSecondary
                                    )
                                }
                            }
                        } else if (res == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CupertinoButton(
                                    text = "立即运行脚本预览",
                                    onClick = { triggerPreview() },
                                    style = CupertinoButtonStyle.Filled,
                                    icon = Icons.Default.PlayArrow
                                )
                            }
                        } else {
                            // Diff Summary Header Bar
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(gutterBg)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (res.success) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (res.success) IosGreen else IosRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (res.success) "执行成功 · ${res.engineName}" else "执行异常",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (res.success) IosGreen else IosRed
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${res.durationMs} ms · 基准: $activeProfileTitle",
                                            fontSize = 11.5.sp,
                                            color = LoonTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                haptic.selection()
                                                triggerPreview()
                                            },
                                        color = IosBlue.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "重新运行",
                                            color = IosBlue,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                // Structural Diff Counters (Proxies / Groups / Rules)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    DiffStatPill(
                                        label = "节点",
                                        before = res.beforeProxies,
                                        after = res.afterProxies,
                                        modifier = Modifier.weight(1f)
                                    )
                                    DiffStatPill(
                                        label = "策略组",
                                        before = res.beforeGroups,
                                        after = res.afterGroups,
                                        modifier = Modifier.weight(1f)
                                    )
                                    DiffStatPill(
                                        label = "分流规则",
                                        before = res.beforeRules,
                                        after = res.afterRules,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                // Sub-tab switcher: YAML Output vs Console Logs
                                CupertinoSegmentedControl(
                                    items = PreviewSubTab.entries,
                                    selectedItem = previewSubTab,
                                    onItemSelected = { previewSubTab = it },
                                    labelProvider = { tab ->
                                        if (tab == PreviewSubTab.CONSOLE) {
                                            "控制台 (${res.consoleLogs.size})"
                                        } else {
                                            tab.label
                                        }
                                    }
                                )
                            }

                            HorizontalDivider(color = LoonCardBorder, thickness = 0.5.dp)

                            if (previewSubTab == PreviewSubTab.YAML) {
                                val previewScroll = rememberScrollState()
                                val previewHorizScroll = rememberScrollState()
                                val yamlLines = remember(res.outputYaml) {
                                    res.outputYaml.lines().take(500)
                                }
                                val highlightedYaml = remember(res.outputYaml, isDark) {
                                    highlightCodeSnippet(
                                        res.outputYaml.lineSequence().take(500).joinToString("\n"),
                                        isDark
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .width(42.dp)
                                            .fillMaxHeight()
                                            .background(gutterBg)
                                            .verticalScroll(previewScroll)
                                            .padding(vertical = 10.dp, horizontal = 6.dp),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        for (idx in 1..yamlLines.size) {
                                            Text(
                                                text = idx.toString(),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                lineHeight = 18.sp,
                                                color = LoonTextMuted
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .fillMaxHeight()
                                            .background(LoonCardBorder)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .verticalScroll(previewScroll)
                                            .horizontalScroll(previewHorizScroll)
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = highlightedYaml,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            } else {
                                // Console Output & Error Diagnostics View
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(res.consoleLogs) { logLine ->
                                        val lineColor = when {
                                            logLine.startsWith("[ERROR]") -> IosRed
                                            logLine.startsWith("[WARN]") -> IosOrange
                                            logLine.startsWith("[OK]") -> IosGreen
                                            else -> LoonTextPrimary
                                        }
                                        Text(
                                            text = logLine,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            color = lineColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3.4 VS Code Bottom Status Bar
                val (cursorLine, cursorCol) = remember(textFieldValue.text, textFieldValue.selection) {
                    val offset = textFieldValue.selection.start.coerceIn(0, textFieldValue.text.length)
                    val before = textFieldValue.text.substring(0, offset)
                    val ln = before.count { it == '\n' } + 1
                    val lastNewline = before.lastIndexOf('\n')
                    val col = if (lastNewline == -1) offset + 1 else offset - lastNewline
                    ln to col
                }
                val totalLines = remember(textFieldValue.text) {
                    textFieldValue.text.count { it == '\n' } + 1
                }
                val isYamlContent = remember(textFieldValue.text) {
                    val t = textFieldValue.text
                    !t.contains("main") && !t.contains("function") && !t.contains("=>") && t.contains(":")
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(statusBarBg)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Ln $cursorLine, Col $cursorCol",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp
                        )
                        Text(
                            text = "$totalLines 行 · ${textFieldValue.text.length} 字符",
                            color = Color.White.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "UTF-8",
                            color = Color.White.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp
                        )
                        Text(
                            text = if (isYamlContent) "YAML" else "JavaScript (ES2020)",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffStatPill(
    label: String,
    before: Int,
    after: Int,
    modifier: Modifier = Modifier
) {
    val delta = after - before
    val deltaText = when {
        delta > 0 -> "+$delta"
        delta < 0 -> "$delta"
        else -> "±0"
    }
    val deltaColor = when {
        delta > 0 -> IosGreen
        delta < 0 -> IosOrange
        else -> LoonTextSecondary
    }
    Surface(
        modifier = modifier,
        color = LoonCard,
        border = BorderStroke(0.5.dp, LoonCardBorder),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = LoonTextSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$after",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "($deltaText)",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = deltaColor
                )
            }
        }
    }
}

// =============================================================================
// VS Code-Inspired Syntax Highlighting for JavaScript & YAML
// =============================================================================
private class CodeSyntaxVisualTransformation(
    private val isDark: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            text = highlightCodeSnippet(text.text, isDark),
            offsetMapping = OffsetMapping.Identity
        )
    }
}

private val JS_KEYWORD_REGEX = Regex(
    """\b(function|return|const|let|var|if|else|for|while|do|switch|case|break|continue|of|in|true|false|null|undefined|new|typeof|instanceof|try|catch|finally|throw|async|await|module|exports|default|export|import)\b"""
)
private val JS_BUILTIN_REGEX = Regex(
    """\b(main|config|proxies|rules|dns|hosts|console|log|info|warn|error|JSON|Array|Object|String|Number|Boolean|Math|RegExp|Map|Set|push|concat|filter|map|forEach|find|some|every|includes|replace|trim|slice|splice|assign|keys|values)\b"""
)
private val NUMBER_REGEX = Regex("""\b\d+(\.\d+)?\b""")
private val YAML_KEY_REGEX = Regex("""^(\s*-?\s*)([A-Za-z0-9_-]+)(?=\s*:)""", RegexOption.MULTILINE)
private val STRING_REGEX = Regex(""""([^"\\]|\\.)*"|'([^'\\]|\\.)*'|`([^`\\]|\\.)*`""")
private val COMMENT_REGEX = Regex("""//.*$|#.*$|/\*[\s\S]*?\*/""", RegexOption.MULTILINE)

private fun highlightCodeSnippet(raw: String, isDark: Boolean): AnnotatedString {
    val keywordColor = if (isDark) Color(0xFFC586C0) else Color(0xFFAF00DB)
    val builtinColor = if (isDark) Color(0xFF4EC9B0) else Color(0xFF0070C1)
    val yamlKeyColor = if (isDark) Color(0xFF9CDCFE) else Color(0xFF0451A5)
    val stringColor = if (isDark) Color(0xFFCE9178) else Color(0xFFA31515)
    val numberColor = if (isDark) Color(0xFFB5CEA8) else Color(0xFF098658)
    val commentColor = if (isDark) Color(0xFF6A9955) else Color(0xFF008000)
    val baseColor = if (isDark) Color(0xFFD4D4D4) else Color(0xFF1C1C1E)

    return buildAnnotatedString {
        append(raw)
        if (raw.isEmpty()) return@buildAnnotatedString

        addStyle(SpanStyle(color = baseColor), 0, raw.length)

        YAML_KEY_REGEX.findAll(raw).forEach { match ->
            val keyGroup = match.groups[2]
            if (keyGroup != null) {
                addStyle(
                    SpanStyle(color = yamlKeyColor, fontWeight = FontWeight.SemiBold),
                    keyGroup.range.first,
                    keyGroup.range.last + 1
                )
            }
        }

        NUMBER_REGEX.findAll(raw).forEach { match ->
            addStyle(SpanStyle(color = numberColor), match.range.first, match.range.last + 1)
        }

        JS_BUILTIN_REGEX.findAll(raw).forEach { match ->
            addStyle(SpanStyle(color = builtinColor), match.range.first, match.range.last + 1)
        }

        JS_KEYWORD_REGEX.findAll(raw).forEach { match ->
            addStyle(
                SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold),
                match.range.first,
                match.range.last + 1
            )
        }

        STRING_REGEX.findAll(raw).forEach { match ->
            addStyle(SpanStyle(color = stringColor), match.range.first, match.range.last + 1)
        }

        COMMENT_REGEX.findAll(raw).forEach { match ->
            addStyle(
                SpanStyle(color = commentColor, fontStyle = FontStyle.Italic),
                match.range.first,
                match.range.last + 1
            )
        }
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

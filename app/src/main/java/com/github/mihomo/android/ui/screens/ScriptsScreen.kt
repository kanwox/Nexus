package com.github.mihomo.android.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ScriptEditorTarget(
    val existingScript: ScriptItem? = null
)

private val DEFAULT_NEW_SCRIPT_CODE = """
function main(config) {
  return config;
}
""".trimIndent()

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return "刚刚"
    val seconds = diff / 1000
    if (seconds < 60) return "刚刚"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}分钟前"
    val hours = minutes / 60
    if (hours < 24) return "${hours}小时前"
    val days = hours / 24
    if (days < 30) return "${days}天前"
    val months = days / 30
    if (months < 12) return "${months}个月前"
    val years = months / 12
    return "${years}年前"
}

private fun linkScriptToEnabledProfiles(context: Context, scriptId: String) {
    val profiles = ConfigManager.getProfiles(context)
    profiles.forEach { profile ->
        if (profile.scriptEnabled && !profile.scriptIds.contains(scriptId)) {
            ConfigManager.updateProfile(
                context,
                profile.copy(scriptIds = profile.scriptIds + scriptId)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    var isUpdatingAll by remember { mutableStateOf(false) }

    var editorTarget by remember { mutableStateOf<ScriptEditorTarget?>(null) }

    // Top-right "+" Action Sheet (3 options: 新建脚本, URL 导入, 本地导入)
    var showAddSheet by remember { mutableStateOf(false) }

    // URL Import Alert Dialog state
    var showUrlImportDialog by remember { mutableStateOf(false) }
    var importUrlName by remember { mutableStateOf("") }
    var importUrlValue by remember { mutableStateOf("") }
    var isImportingUrl by remember { mutableStateOf(false) }

    // Long-press Action Sheet & Dialog states for a script item
    var actionSheetScript by remember { mutableStateOf<ScriptItem?>(null) }
    var editingInfoScript by remember { mutableStateOf<ScriptItem?>(null) }
    var editInfoName by remember { mutableStateOf("") }
    var editInfoUrl by remember { mutableStateOf("") }
    var deleteConfirmScript by remember { mutableStateOf<ScriptItem?>(null) }

    LaunchedEffect(Unit) {
        scripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }

    fun refresh() {
        scripts = ScriptManager.getScripts(context)
        onScriptsChanged()
    }

    val localFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val (displayName, content) = withContext(Dispatchers.IO) {
                        var name = "local_script.js"
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1 && cursor.moveToFirst()) {
                                name = cursor.getString(nameIdx) ?: name
                            }
                        }
                        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("无法读取文件内容")
                        val cleanName = name
                            .removeSuffix(".js")
                            .removeSuffix(".yaml")
                            .removeSuffix(".yml")
                            .removeSuffix(".txt")
                            .ifBlank { "本地脚本" }
                        cleanName to text
                    }
                    withContext(Dispatchers.IO) {
                        val saved = ScriptManager.saveScript(
                            context = context,
                            name = displayName,
                            code = content,
                            enabled = true
                        )
                        linkScriptToEnabledProfiles(context, saved.id)
                    }
                    refresh()
                    CupertinoToast.show("已导入 $displayName")
                }.onFailure { e ->
                    CupertinoToast.show("导入失败: ${e.message}", isDestructive = true)
                }
            }
        }
    }

    fun updateAllRemoteScripts() {
        if (isUpdatingAll) return
        val remoteScripts = scripts.filter { it.url.isNotBlank() }
        if (remoteScripts.isEmpty()) {
            CupertinoToast.show("暂无可更新的远程脚本")
            return
        }
        isUpdatingAll = true
        updatingScriptIds = updatingScriptIds + remoteScripts.map { it.id }
        scope.launch {
            var successCount = 0
            var failCount = 0
            for (script in remoteScripts) {
                val res = ScriptManager.updateScriptFromRemote(context, script)
                updatingScriptIds = updatingScriptIds - script.id
                if (res.isSuccess) {
                    successCount++
                } else {
                    failCount++
                }
            }
            isUpdatingAll = false
            refresh()
            if (failCount == 0) {
                CupertinoToast.show("已更新 $successCount 个远程脚本")
            } else {
                CupertinoToast.show(
                    "更新完成: 成功 $successCount 个, 失败 $failCount 个",
                    isDestructive = successCount == 0
                )
            }
        }
    }

    fun updateSingleScript(script: ScriptItem) {
        if (updatingScriptIds.contains(script.id)) return
        updatingScriptIds = updatingScriptIds + script.id
        scope.launch {
            val res = ScriptManager.updateScriptFromRemote(context, script)
            updatingScriptIds = updatingScriptIds - script.id
            if (res.isSuccess) {
                refresh()
                CupertinoToast.show("已更新 ${script.name}")
            } else {
                CupertinoToast.show(
                    "更新失败: ${res.exceptionOrNull()?.message}",
                    isDestructive = true
                )
            }
        }
    }

    AnimatedContent(
        targetState = editorTarget,
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
    ) { activeEditor ->
        if (activeEditor != null) {
            ScriptEditorScreen(
                existingScript = activeEditor.existingScript,
                onBack = { editorTarget = null },
                onSaved = {
                    refresh()
                    editorTarget = null
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
                    showBackButton = true,
                    onBack = onBack,
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Top-right Update Option
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .iosPressable(enabled = !isUpdatingAll) {
                                        haptic.selection()
                                        updateAllRemoteScripts()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isUpdatingAll) {
                                    CupertinoActivityIndicator(size = 18.dp, color = IosBlue)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "更新脚本",
                                        tint = IosBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Top-right Add Option (opens popup with 3 options)
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .iosPressable {
                                        haptic.selection()
                                        showAddSheet = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "添加脚本",
                                    tint = IosBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
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
                    if (scripts.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Code,
                                        contentDescription = null,
                                        tint = LoonTextMuted.copy(alpha = 0.4f),
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "暂无脚本",
                                        color = LoonTextSecondary,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    } else {
                        items(scripts, key = { it.id }) { script ->
                            val isRemote = script.url.isNotBlank()
                            val isUpdating = updatingScriptIds.contains(script.id)

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = LoonCard,
                                border = BorderStroke(0.5.dp, LoonCardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            haptic.selection()
                                            editorTarget = ScriptEditorTarget(existingScript = script)
                                        },
                                        onLongClick = {
                                            haptic.impact()
                                            actionSheetScript = script
                                        }
                                    )
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
                                            .background(
                                                if (isRemote) IosBlue.copy(alpha = 0.12f)
                                                else IosOrange.copy(alpha = 0.12f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isRemote) Icons.Default.CloudDownload else Icons.Default.Code,
                                            contentDescription = null,
                                            tint = if (isRemote) IosBlue else IosOrange,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(13.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = script.name,
                                            color = LoonTextPrimary,
                                            fontSize = 15.5.sp,
                                            fontWeight = FontWeight.Medium,
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
                                            color = LoonTextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (isUpdating) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CupertinoActivityIndicator(size = 16.dp, color = IosBlue)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 1. Popup with the 3 options: 新建脚本, URL 导入, 本地导入
    if (showAddSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showAddSheet = false },
            actions = listOf(
                CupertinoActionSheetItem(
                    text = "新建脚本",
                    onClick = {
                        haptic.selection()
                        showAddSheet = false
                        editorTarget = ScriptEditorTarget(existingScript = null)
                    }
                ),
                CupertinoActionSheetItem(
                    text = "URL 导入",
                    onClick = {
                        haptic.selection()
                        showAddSheet = false
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        importUrlName = ""
                        importUrlValue = if (clipText.startsWith("http://") || clipText.startsWith("https://")) clipText else ""
                        showUrlImportDialog = true
                    }
                ),
                CupertinoActionSheetItem(
                    text = "本地导入",
                    onClick = {
                        haptic.selection()
                        showAddSheet = false
                        localFileLauncher.launch("*/*")
                    }
                )
            ),
            cancelText = "取消"
        )
    }

    // 2. URL Import Dialog
    if (showUrlImportDialog) {
        CupertinoAlertDialog(
            title = "URL 导入",
            confirmText = if (isImportingUrl) "导入中..." else "导入",
            dismissText = "取消",
            onConfirm = {
                if (isImportingUrl) return@CupertinoAlertDialog
                val cleanUrl = importUrlValue.trim()
                if (cleanUrl.isBlank()) {
                    CupertinoToast.show("请输入脚本链接", isDestructive = true)
                    return@CupertinoAlertDialog
                }
                isImportingUrl = true
                scope.launch {
                    val res = ScriptManager.downloadScript(
                        context = context,
                        url = cleanUrl,
                        name = importUrlName.trim(),
                        pattern = "",
                        type = "config"
                    )
                    isImportingUrl = false
                    if (res.isSuccess) {
                        val item = res.getOrThrow()
                        withContext(Dispatchers.IO) {
                            linkScriptToEnabledProfiles(context, item.id)
                        }
                        showUrlImportDialog = false
                        refresh()
                        CupertinoToast.show("已导入 ${item.name}")
                    } else {
                        CupertinoToast.show(
                            "导入失败: ${res.exceptionOrNull()?.message}",
                            isDestructive = true
                        )
                    }
                }
            },
            onDismiss = {
                if (!isImportingUrl) showUrlImportDialog = false
            },
            onDismissRequest = {
                if (!isImportingUrl) showUrlImportDialog = false
            }
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            CupertinoTextField(
                value = importUrlName,
                onValueChange = { importUrlName = it },
                placeholder = "名称 (选填)",
                singleLine = true
            )
            Spacer(modifier = Modifier.height(10.dp))
            CupertinoTextField(
                value = importUrlValue,
                onValueChange = { importUrlValue = it },
                placeholder = "脚本链接 (URL)",
                singleLine = true
            )
        }
    }

    // 3. Long-Press Popup Action Sheet for Script Item
    if (actionSheetScript != null) {
        val target = actionSheetScript!!
        val isRemote = target.url.isNotBlank()

        val actions = buildList {
            if (isRemote) {
                add(
                    CupertinoActionSheetItem(
                        text = "更新",
                        onClick = {
                            haptic.lightTick()
                            actionSheetScript = null
                            updateSingleScript(target)
                        }
                    )
                )
            }
            add(
                CupertinoActionSheetItem(
                    text = "编辑代码",
                    onClick = {
                        haptic.selection()
                        actionSheetScript = null
                        editorTarget = ScriptEditorTarget(existingScript = target)
                    }
                )
            )
            add(
                CupertinoActionSheetItem(
                    text = "编辑信息",
                    onClick = {
                        haptic.selection()
                        actionSheetScript = null
                        editInfoName = target.name
                        editInfoUrl = target.url
                        editingInfoScript = target
                    }
                )
            )
            add(
                CupertinoActionSheetItem(
                    text = "删除",
                    isDestructive = true,
                    onClick = {
                        haptic.impact()
                        actionSheetScript = null
                        deleteConfirmScript = target
                    }
                )
            )
        }

        CupertinoActionSheet(
            onDismissRequest = { actionSheetScript = null },
            title = target.name,
            actions = actions,
            cancelText = "取消"
        )
    }

    // 4. Edit Script Name / URL Dialog
    if (editingInfoScript != null) {
        val target = editingInfoScript!!
        val isRemote = target.url.isNotBlank()

        CupertinoAlertDialog(
            title = "编辑信息",
            confirmText = "保存",
            dismissText = "取消",
            onConfirm = {
                val finalName = editInfoName.trim().ifBlank { target.name }
                val finalUrl = if (isRemote) editInfoUrl.trim() else target.url
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val code = ScriptManager.readScriptCode(context, target)
                        ScriptManager.saveScript(
                            context = context,
                            existingId = target.id,
                            name = finalName,
                            url = finalUrl,
                            pattern = target.pattern,
                            type = target.type,
                            code = code,
                            enabled = true
                        )
                    }
                    editingInfoScript = null
                    refresh()
                    CupertinoToast.show("已保存")
                }
            },
            onDismiss = { editingInfoScript = null },
            onDismissRequest = { editingInfoScript = null }
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            CupertinoTextField(
                value = editInfoName,
                onValueChange = { editInfoName = it },
                placeholder = "名称",
                singleLine = true
            )
            if (isRemote) {
                Spacer(modifier = Modifier.height(10.dp))
                CupertinoTextField(
                    value = editInfoUrl,
                    onValueChange = { editInfoUrl = it },
                    placeholder = "脚本链接 (URL)",
                    singleLine = true
                )
            }
        }
    }

    // 5. Delete Confirmation Dialog
    if (deleteConfirmScript != null) {
        val target = deleteConfirmScript!!
        CupertinoAlertDialog(
            title = "删除脚本",
            message = "确定要删除脚本「${target.name}」吗？",
            confirmText = "删除",
            dismissText = "取消",
            isDestructive = true,
            onConfirm = {
                ScriptManager.deleteScript(context, target.id)
                refresh()
                deleteConfirmScript = null
                CupertinoToast.show("已删除脚本")
            },
            onDismiss = { deleteConfirmScript = null },
            onDismissRequest = { deleteConfirmScript = null }
        )
    }
}

// =============================================================================
// Standard Clean Mobile Code Editor Screen
// =============================================================================
@Composable
private fun ScriptEditorScreen(
    existingScript: ScriptItem?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()
    val isDark = LocalLoonColors.current.isDark

    val isEditingExisting = existingScript != null

    var scriptName by remember(existingScript) {
        mutableStateOf(existingScript?.name ?: "")
    }
    var showNameDialog by remember { mutableStateOf(false) }
    var tempNameInput by remember { mutableStateOf("") }

    val initialCode = remember(existingScript) {
        if (existingScript != null) {
            ScriptManager.readScriptCode(context, existingScript)
        } else {
            DEFAULT_NEW_SCRIPT_CODE
        }
    }

    val initialValue = remember(existingScript) {
        TextFieldValue(text = initialCode, selection = TextRange.Zero)
    }

    var textFieldValue by remember(existingScript) {
        mutableStateOf(initialValue)
    }

    val undoStack = remember(existingScript) { mutableStateListOf(initialValue) }
    var undoIndex by remember(existingScript) { mutableIntStateOf(0) }

    fun updateCode(newValue: TextFieldValue) {
        val oldText = textFieldValue.text
        textFieldValue = newValue
        if (newValue.text != oldText) {
            while (undoStack.size > undoIndex + 1) {
                undoStack.removeAt(undoStack.lastIndex)
            }
            if (undoStack.size >= 50) {
                undoStack.removeAt(0)
            } else {
                undoIndex++
            }
            undoStack.add(newValue)
            undoIndex = undoStack.lastIndex
        }
    }

    fun insertSymbol(symbol: String, cursorOffsetBack: Int = 0) {
        val current = textFieldValue.text
        val start = textFieldValue.selection.min.coerceIn(0, current.length)
        val end = textFieldValue.selection.max.coerceIn(0, current.length)
        val updated = current.substring(0, start) + symbol + current.substring(end)
        val newCursor = (start + symbol.length - cursorOffsetBack).coerceIn(0, updated.length)
        updateCode(TextFieldValue(text = updated, selection = TextRange(newCursor)))
    }

    var isSaving by remember { mutableStateOf(false) }

    fun commitSave(finalName: String) {
        if (isSaving) return
        val codeToSave = textFieldValue.text
        if (codeToSave.isBlank()) {
            CupertinoToast.show("脚本内容不能为空", isDestructive = true)
            return
        }
        isSaving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val saved = ScriptManager.saveScript(
                        context = context,
                        existingId = existingScript?.id,
                        name = finalName.trim().ifBlank { "自定义脚本" },
                        url = existingScript?.url ?: "",
                        pattern = existingScript?.pattern ?: "",
                        type = existingScript?.type ?: "config",
                        code = codeToSave,
                        enabled = true
                    )
                    linkScriptToEnabledProfiles(context, saved.id)
                }
                CupertinoToast.show("已保存")
                onSaved()
            } finally {
                isSaving = false
            }
        }
    }

    BackHandler(onBack = onBack)

    val codeScrollState = rememberScrollState()
    val codeHorizontalScroll = rememberScrollState()
    var isEditorFocused by remember { mutableStateOf(false) }
    var codeTextLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val lineCount = remember(textFieldValue.text) {
        textFieldValue.text.count { it == '\n' } + 1
    }
    val lineNumbersText = remember(lineCount) {
        (1..lineCount).joinToString("\n")
    }
    val syntaxVisualTransformation = remember(isDark) {
        CodeSyntaxVisualTransformation(isDark = isDark)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonCard)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 1. Standard iOS Navigation Bar
        CupertinoNavBar(
            title = scriptName.ifBlank { "新建脚本" },
            showBackButton = true,
            onBack = onBack,
            actions = {
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .iosPressable(enabled = !isSaving) {
                            haptic.lightTick()
                            if (scriptName.isBlank()) {
                                tempNameInput = ""
                                showNameDialog = true
                            } else {
                                commitSave(scriptName)
                            }
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "保存",
                        color = IosBlue,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )

        // 2. Full-Screen Edge-to-Edge Code Editor Area (Single verticalScroll for gutter + code)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(LoonCard)
                .verticalScroll(codeScrollState)
        ) {
            // Line Number Gutter (Single Text composable for O(1) layout nodes)
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .padding(vertical = 12.dp, horizontal = 6.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Text(
                    text = lineNumbersText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = LoonTextMuted,
                    textAlign = TextAlign.End
                )
            }

            // Hairline separator between line numbers and code
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .fillMaxHeight()
                    .background(LoonSeparator)
            )

            // Code Input Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(codeHorizontalScroll)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { updateCode(it) },
                    onTextLayout = { codeTextLayoutResult = it },
                    visualTransformation = syntaxVisualTransformation,
                    cursorBrush = SolidColor(IosBlue),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = LoonTextPrimary
                    ),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 300.dp, minHeight = 300.dp)
                        .fillMaxWidth()
                        .onFocusChanged { isEditorFocused = it.isFocused }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                if (!isEditorFocused) {
                                    codeTextLayoutResult?.let { layout ->
                                        val tappedOffset = layout.getOffsetForPosition(offset)
                                            .coerceIn(0, textFieldValue.text.length)
                                        if (textFieldValue.selection.collapsed &&
                                            textFieldValue.selection.start != tappedOffset
                                        ) {
                                            textFieldValue = textFieldValue.copy(
                                                selection = TextRange(tappedOffset)
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    decorationBox = { innerTextField ->
                        Box {
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    text = "// 在此输入脚本代码",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = LoonTextMuted
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        // 3. Standard Mobile Keyboard Accessory Toolbar (Flat, borderless symbol keys + Undo/Redo)
        HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)

        val quickSymbols = remember {
            listOf(
                "Tab" to ("  " to 0),
                "{" to ("{}" to 1),
                "}" to ("}" to 0),
                "(" to ("()" to 1),
                ")" to (")" to 0),
                "[" to ("[]" to 1),
                "]" to ("]" to 0),
                "=" to ("=" to 0),
                ";" to (";" to 0),
                ":" to (":" to 0),
                "\"" to ("\"\"" to 1),
                "'" to ("''" to 1),
                "/" to ("/" to 0),
                "." to ("." to 0)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(LoonCard)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Undo
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .iosHighlightPressable(enabled = undoIndex > 0) {
                        if (undoIndex > 0) {
                            haptic.lightTick()
                            undoIndex--
                            textFieldValue = undoStack[undoIndex]
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "撤销",
                    tint = if (undoIndex > 0) LoonTextPrimary else LoonTextMuted.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Redo
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .iosHighlightPressable(enabled = undoIndex < undoStack.lastIndex) {
                        if (undoIndex < undoStack.lastIndex) {
                            haptic.lightTick()
                            undoIndex++
                            textFieldValue = undoStack[undoIndex]
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "重做",
                    tint = if (undoIndex < undoStack.lastIndex) LoonTextPrimary else LoonTextMuted.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(0.5.dp)
                    .height(18.dp)
                    .background(LoonSeparator)
            )

            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(quickSymbols) { (label, pair) ->
                    val (insertText, backOffset) = pair
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .widthIn(min = 34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .iosHighlightPressable {
                                haptic.lightTick()
                                insertSymbol(insertText, backOffset)
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = LoonTextPrimary
                        )
                    }
                }
            }
        }
    }

    if (showNameDialog) {
        CupertinoAlertDialog(
            title = if (isEditingExisting) "脚本名称" else "保存脚本",
            confirmText = "保存",
            dismissText = "取消",
            onConfirm = {
                val resolved = tempNameInput.trim().ifBlank { "自定义脚本" }
                scriptName = resolved
                showNameDialog = false
                commitSave(resolved)
            },
            onDismiss = { showNameDialog = false },
            onDismissRequest = { showNameDialog = false }
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            CupertinoTextField(
                value = tempNameInput,
                onValueChange = { tempNameInput = it },
                placeholder = "输入脚本名称",
                singleLine = true
            )
        }
    }
}

// =============================================================================
// Syntax Highlighting for JavaScript & YAML (Cached to avoid re-running on cursor moves)
// =============================================================================
private const val MAX_HIGHLIGHT_CHARS = 40_000

private class CodeSyntaxVisualTransformation(
    private val isDark: Boolean
) : VisualTransformation {
    private var cachedRaw: String? = null
    private var cachedTransformed: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val existing = cachedTransformed
        if (raw == cachedRaw && existing != null) {
            return existing
        }
        val transformed = TransformedText(
            text = highlightCodeSnippet(raw, isDark),
            offsetMapping = OffsetMapping.Identity
        )
        cachedRaw = raw
        cachedTransformed = transformed
        return transformed
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
        if (raw.length > MAX_HIGHLIGHT_CHARS) return@buildAnnotatedString

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

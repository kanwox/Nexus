@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
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
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoActionSheet
import com.github.mihomo.android.ui.components.CupertinoActionSheetItem
import com.github.mihomo.android.ui.components.CupertinoActivityIndicator
import com.github.mihomo.android.ui.components.CupertinoAlertDialog
import com.github.mihomo.android.ui.components.CupertinoCell
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoIndentedDivider
import com.github.mihomo.android.ui.components.CupertinoModalBottomSheet
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoTextField
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.iosFocusRing
import com.github.mihomo.android.ui.components.iosHighlightPressable
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.rememberClipboardReadText
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosOrange
import com.github.mihomo.android.ui.theme.LocalLoonColors
import com.github.mihomo.android.ui.theme.LocalReduceMotion
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonCard
import com.github.mihomo.android.ui.theme.LoonCardBorder
import com.github.mihomo.android.ui.theme.LoonExtraColors
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonMotion
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSeparator
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSpacing
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.MihomoTheme
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ScriptEditorTarget(
    val existingScript: ScriptItem? = null,
    val templateNameKey: String? = null,
    val templateCode: String? = null
)

private val DEFAULT_NEW_SCRIPT_CODE = """
function main(config) {
  return config;
}
""".trimIndent()

private data class ScriptTemplate(
    val nameKey: String,
    val descKey: String,
    val code: String
)

private val SCRIPT_TEMPLATES = listOf(
    ScriptTemplate(
        nameKey = "script_template_basic",
        descKey = "script_template_basic_desc",
        code = """
            // main(config) receives the raw config object and must return the modified config
            function main(config) {
                return config;
            }
        """.trimIndent()
    ),
    ScriptTemplate(
        nameKey = "script_template_block_ads",
        descKey = "script_template_block_ads_desc",
        code = """
            // Prepend REJECT rules for common ad domains
            const AD_RULES = [
                "DOMAIN-SUFFIX,ad.doubleclick.net,REJECT",
                "DOMAIN-SUFFIX,googlesyndication.com,REJECT",
                "DOMAIN-SUFFIX,googleadservices.com,REJECT",
                "DOMAIN-KEYWORD,adservice,REJECT",
                "DOMAIN-SUFFIX,umeng.com,REJECT",
                "DOMAIN-SUFFIX,umeng.co,REJECT"
            ];

            function main(config) {
                const rules = Array.isArray(config.rules) ? config.rules : [];
                config.rules = AD_RULES.concat(rules);
                return config;
            }
        """.trimIndent()
    ),
    ScriptTemplate(
        nameKey = "script_template_force_dns",
        descKey = "script_template_force_dns_desc",
        code = """
            // Replace upstream nameservers with custom DoH endpoints
            function main(config) {
                config.dns = Object.assign({}, config.dns, {
                    enable: true,
                    "default-nameserver": ["223.5.5.5", "119.29.29.29"],
                    nameserver: ["https://223.5.5.5/dns-query", "https://doh.pub/dns-query"]
                });
                return config;
            }
        """.trimIndent()
    ),
    ScriptTemplate(
        nameKey = "script_template_rename_nodes",
        descKey = "script_template_rename_nodes_desc",
        code = """
            // Example: strip regional markers like "🇭🇰 " from every proxy name
            function main(config) {
                const proxies = Array.isArray(config.proxies) ? config.proxies : [];
                for (const proxy of proxies) {
                    if (typeof proxy.name === "string") {
                        proxy.name = proxy.name.replace(/^\S+\s+/, "");
                    }
                }
                return config;
            }
        """.trimIndent()
    )
)

private val QUICK_EDITOR_SYMBOLS = listOf(
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

private class ScriptListDialogsState {
    var showAddSheet by mutableStateOf(false)
    var showTemplateSheet by mutableStateOf(false)
    var showUrlImportDialog by mutableStateOf(false)
    var importUrlName by mutableStateOf("")
    var importUrlValue by mutableStateOf("")
    var isImportingUrl by mutableStateOf(false)
    var actionSheetScript by mutableStateOf<ScriptItem?>(null)
    var editingInfoScript by mutableStateOf<ScriptItem?>(null)
    var editInfoName by mutableStateOf("")
    var editInfoUrl by mutableStateOf("")
    var deleteConfirmScript by mutableStateOf<ScriptItem?>(null)
}

private class ScriptsListController(
    private val context: Context,
    private val lang: String,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onScriptsChanged: () -> Unit
) {
    var scripts by mutableStateOf(emptyList<ScriptItem>())
    var updatingScriptIds by mutableStateOf(setOf<String>())
    var isUpdatingAll by mutableStateOf(false)

    suspend fun refresh() {
        scripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
        onScriptsChanged()
    }

    fun updateAllRemote() {
        if (isUpdatingAll) return
        val remoteScripts = scripts.filter { item -> item.url.isNotBlank() }
        if (remoteScripts.isEmpty()) {
            CupertinoToast.show(AppStrings.get("scripts_no_remote", lang))
            return
        }
        isUpdatingAll = true
        updatingScriptIds = updatingScriptIds + remoteScripts.map { item -> item.id }
        scope.launch {
            val (okCount, failCount) = runRemoteUpdates(context, remoteScripts) { finishedId ->
                updatingScriptIds = updatingScriptIds - finishedId
            }
            isUpdatingAll = false
            refresh()
            showBatchUpdateToast(lang, okCount, failCount)
        }
    }

    fun updateSingle(script: ScriptItem) {
        if (updatingScriptIds.contains(script.id)) return
        updatingScriptIds = updatingScriptIds + script.id
        scope.launch {
            val res = ScriptManager.updateScriptFromRemote(context, script)
            updatingScriptIds = updatingScriptIds - script.id
            if (res.isSuccess) {
                refresh()
                CupertinoToast.show(AppStrings.get("scripts_updated_name", lang).replace("{name}", script.name))
            } else {
                CupertinoToast.show(
                    AppStrings.get("scripts_sync_failed", lang).replace("{msg}", res.exceptionOrNull()?.message ?: ""),
                    isDestructive = true
                )
            }
        }
    }
}

private data class ScriptsScaffoldActions(
    val onBack: () -> Unit,
    val onUpdateAll: () -> Unit,
    val onOpenAdd: () -> Unit,
    val onOpenEditor: (ScriptItem) -> Unit,
    val onOpenActions: (ScriptItem) -> Unit
)

private data class ScriptDialogsCallbacks(
    val onOpenNewEditor: () -> Unit,
    val onOpenTemplateEditor: (ScriptTemplate) -> Unit,
    val onPickLocalFile: () -> Unit,
    val onEditCode: (ScriptItem) -> Unit,
    val onUpdateSingle: (ScriptItem) -> Unit,
    val onRefresh: () -> Unit
)

@Composable
private fun rememberLocalScriptImportLauncher(
    context: Context,
    lang: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onImported: suspend () -> Unit
) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
        try {
            val (displayName, content) = withContext(Dispatchers.IO) {
                readPickedScriptFile(context, uri, lang)
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
            onImported()
            CupertinoToast.show(AppStrings.get("imported_name", lang).replace("{name}", displayName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CupertinoToast.show(
                AppStrings.get("import_failed", lang).replace("{msg}", e.message ?: ""),
                isDestructive = true
            )
        }
    }
}

private fun resolveScriptEditorTransition(
    targetState: ScriptEditorTarget?,
    reduceMotion: Boolean
): ContentTransform = when {
    reduceMotion -> EnterTransition.None togetherWith ExitTransition.None
    targetState != null -> (slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(LoonMotion.slowMs, easing = FastOutSlowInEasing)
    ) + fadeIn(tween(LoonMotion.normalMs))) togetherWith (slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
    ) + fadeOut(tween(LoonMotion.fastMs)))
    else -> (slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
    ) + fadeIn(tween(LoonMotion.normalMs))) togetherWith (slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(LoonMotion.normalMs, easing = FastOutSlowInEasing)
    ) + fadeOut(tween(LoonMotion.fastMs)))
}

@Composable
fun ScriptsScreen(
    onBack: () -> Unit,
    onScriptsChanged: () -> Unit = {},
    onEditorVisibilityChanged: (Boolean) -> Unit = {}
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()
    val readClipboardText = rememberClipboardReadText()
    val reduceMotion = LocalReduceMotion.current

    val latestOnScriptsChanged by androidx.compose.runtime.rememberUpdatedState(onScriptsChanged)
    val controller = remember(context, scope) {
        ScriptsListController(context, lang, scope) { latestOnScriptsChanged() }
    }
    var editorTarget by remember { mutableStateOf<ScriptEditorTarget?>(null) }
    val dialogs = remember { ScriptListDialogsState() }

    val latestOnEditorVisibilityChanged by androidx.compose.runtime.rememberUpdatedState(onEditorVisibilityChanged)
    androidx.compose.runtime.DisposableEffect(editorTarget != null) {
        latestOnEditorVisibilityChanged(editorTarget != null)
        onDispose { latestOnEditorVisibilityChanged(false) }
    }

    LaunchedEffect(controller) {
        controller.scripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }

    val localFileLauncher = rememberLocalScriptImportLauncher(
        context = context,
        lang = lang,
        scope = scope,
        onImported = { controller.refresh() }
    )

    AnimatedContent(
        targetState = editorTarget,
        transitionSpec = { resolveScriptEditorTransition(targetState, reduceMotion) },
        label = "scripts_page_transition"
    ) { activeEditor ->
        if (activeEditor != null) {
            ScriptEditorScreen(
                existingScript = activeEditor.existingScript,
                templateNameKey = activeEditor.templateNameKey,
                templateCode = activeEditor.templateCode,
                onBack = { editorTarget = null },
                onSaved = {
                    editorTarget = null
                    scope.launch { controller.refresh() }
                }
            )
        } else {
            ScriptsListScaffold(
                scripts = controller.scripts,
                updatingScriptIds = controller.updatingScriptIds,
                isUpdatingAll = controller.isUpdatingAll,
                actions = ScriptsScaffoldActions(
                    onBack = onBack,
                    onUpdateAll = controller::updateAllRemote,
                    onOpenAdd = { haptic.selection(); dialogs.showAddSheet = true },
                    onOpenEditor = { script -> haptic.selection(); editorTarget = ScriptEditorTarget(existingScript = script) },
                    onOpenActions = { script -> haptic.impact(); dialogs.actionSheetScript = script }
                )
            )
        }
    }

    ScriptsDialogsHost(
        dialogs = dialogs,
        readClipboardText = readClipboardText,
        callbacks = ScriptDialogsCallbacks(
            onOpenNewEditor = { editorTarget = ScriptEditorTarget(existingScript = null) },
            onOpenTemplateEditor = { template ->
                editorTarget = ScriptEditorTarget(templateNameKey = template.nameKey, templateCode = template.code)
            },
            onPickLocalFile = { localFileLauncher.launch("*/*") },
            onEditCode = { script -> editorTarget = ScriptEditorTarget(existingScript = script) },
            onUpdateSingle = controller::updateSingle,
            onRefresh = { scope.launch { controller.refresh() } }
        )
    )
}

@Composable
private fun ScriptsListScaffold(
    scripts: List<ScriptItem>,
    updatingScriptIds: Set<String>,
    isUpdatingAll: Boolean,
    actions: ScriptsScaffoldActions
) {
    val lang = LocalAppLanguage.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("scripts_title", lang),
            showBackButton = true,
            onBack = actions.onBack,
            actions = {
                ScriptsNavBarActions(
                    isUpdatingAll = isUpdatingAll,
                    lang = lang,
                    onUpdateAll = actions.onUpdateAll,
                    onOpenAdd = actions.onOpenAdd
                )
            }
        )

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .bounceOverscroll(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (scripts.isEmpty()) {
                    item(key = "scripts_empty") {
                        ScriptsEmptyState(lang = lang)
                    }
                } else {
                    items(scripts, key = { item -> item.id }) { script ->
                        ScriptListCard(
                            script = script,
                            isUpdating = updatingScriptIds.contains(script.id),
                            lang = lang,
                            onClick = { actions.onOpenEditor(script) },
                            onLongClick = { actions.onOpenActions(script) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptsNavBarActions(
    isUpdatingAll: Boolean,
    lang: String,
    onUpdateAll: () -> Unit,
    onOpenAdd: () -> Unit
) {
    val haptic = rememberIosHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LoonSpacing.xxs)
    ) {
        Box(
            modifier = Modifier
                .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                .clip(CircleShape)
                .iosFocusRing(CircleShape)
                .iosPressable(enabled = !isUpdatingAll) {
                    haptic.selection()
                    onUpdateAll()
                },
            contentAlignment = Alignment.Center
        ) {
            if (isUpdatingAll) {
                CupertinoActivityIndicator(size = 18.dp, color = IosBlue)
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = AppStrings.get("scripts_update_all_desc", lang),
                    tint = IosBlue,
                    modifier = Modifier.size(LoonSizes.icon)
                )
            }
        }

        Box(
            modifier = Modifier
                .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                .clip(CircleShape)
                .iosFocusRing(CircleShape)
                .iosPressable(onClick = onOpenAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = AppStrings.get("scripts_add_desc", lang),
                tint = IosBlue,
                modifier = Modifier.size(LoonSizes.icon)
            )
        }
    }
}

@Composable
private fun ScriptsEmptyState(lang: String) {
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
            Spacer(modifier = Modifier.height(LoonSpacing.md))
            Text(
                text = AppStrings.get("scripts_empty_short", lang),
                color = LoonTextSecondary,
                style = LoonTextStyles.body()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptListCard(
    script: ScriptItem,
    isUpdating: Boolean,
    lang: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isRemote = script.url.isNotBlank()
    val cardShape = RoundedCornerShape(LoonRadii.card)

    Surface(
        shape = cardShape,
        color = LoonCard,
        border = BorderStroke(0.5.dp, LoonCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .iosFocusRing(cardShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LoonSizes.touchTargetMin)
                .padding(horizontal = LoonSpacing.lg, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScriptTypeIconBadge(isRemote = isRemote)
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    color = LoonTextPrimary,
                    style = LoonTextStyles.cellTitle().copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(LoonSpacing.xxs))
                Text(
                    text = buildString {
                        append(
                            if (isRemote) AppStrings.get("script_type_remote", lang)
                            else AppStrings.get("script_type_local", lang)
                        )
                        append(" · ")
                        append(formatRelativeTime(script.updatedAt, lang))
                    },
                    color = LoonTextSecondary,
                    style = LoonTextStyles.subtitle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isUpdating) {
                Spacer(modifier = Modifier.width(LoonSpacing.sm))
                CupertinoActivityIndicator(size = 16.dp, color = IosBlue)
            }
        }
    }
}

@Composable
private fun ScriptTypeIconBadge(isRemote: Boolean) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(LoonRadii.control))
            .background(if (isRemote) IosBlue.copy(alpha = 0.12f) else IosOrange.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isRemote) Icons.Default.CloudDownload else Icons.Default.Code,
            contentDescription = null,
            tint = if (isRemote) IosBlue else IosOrange,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ScriptsDialogsHost(
    dialogs: ScriptListDialogsState,
    readClipboardText: () -> String,
    callbacks: ScriptDialogsCallbacks
) {
    val lang = LocalAppLanguage.current
    val haptic = rememberIosHaptic()

    if (dialogs.showAddSheet) {
        ScriptAddActionSheet(
            dialogs = dialogs,
            lang = lang,
            readClipboardText = readClipboardText,
            callbacks = callbacks
        )
    }

    if (dialogs.showTemplateSheet) {
        ScriptTemplatePickerSheet(
            lang = lang,
            onDismiss = { dialogs.showTemplateSheet = false },
            onSelectTemplate = { template ->
                haptic.selection()
                dialogs.showTemplateSheet = false
                callbacks.onOpenTemplateEditor(template)
            }
        )
    }

    if (dialogs.showUrlImportDialog) {
        ScriptUrlImportDialog(dialogs = dialogs, onRefresh = callbacks.onRefresh)
    }

    dialogs.actionSheetScript?.let { target ->
        ScriptItemActionSheet(
            target = target,
            lang = lang,
            onDismiss = { dialogs.actionSheetScript = null },
            onUpdateSingle = callbacks.onUpdateSingle,
            onEditCode = callbacks.onEditCode,
            onEditInfo = {
                dialogs.editInfoName = target.name
                dialogs.editInfoUrl = target.url
                dialogs.editingInfoScript = target
            },
            onRequestDelete = { dialogs.deleteConfirmScript = target }
        )
    }

    dialogs.editingInfoScript?.let { target ->
        ScriptEditInfoDialog(target = target, dialogs = dialogs, onRefresh = callbacks.onRefresh)
    }

    dialogs.deleteConfirmScript?.let { target ->
        ScriptDeleteConfirmDialog(target = target, dialogs = dialogs, onRefresh = callbacks.onRefresh)
    }
}

@Composable
private fun ScriptAddActionSheet(
    dialogs: ScriptListDialogsState,
    lang: String,
    readClipboardText: () -> String,
    callbacks: ScriptDialogsCallbacks
) {
    val haptic = rememberIosHaptic()
    CupertinoActionSheet(
        onDismissRequest = { dialogs.showAddSheet = false },
        actions = listOf(
            CupertinoActionSheetItem(
                text = AppStrings.get("scripts_new", lang),
                onClick = { haptic.selection(); dialogs.showAddSheet = false; callbacks.onOpenNewEditor() }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("scripts_add_from_template", lang),
                onClick = { haptic.selection(); dialogs.showAddSheet = false; dialogs.showTemplateSheet = true }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("scripts_url_import", lang),
                onClick = {
                    haptic.selection()
                    dialogs.showAddSheet = false
                    val clipText = readClipboardText().trim()
                    dialogs.importUrlName = ""
                    dialogs.importUrlValue =
                        if (clipText.startsWith("http://") || clipText.startsWith("https://")) clipText else ""
                    dialogs.showUrlImportDialog = true
                }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("scripts_import_local", lang),
                onClick = { haptic.selection(); dialogs.showAddSheet = false; callbacks.onPickLocalFile() }
            )
        ),
        cancelText = AppStrings.get("cancel", lang)
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ScriptTemplatePickerSheet(
    lang: String,
    onDismiss: () -> Unit,
    onSelectTemplate: (ScriptTemplate) -> Unit
) {
    CupertinoModalBottomSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("scripts_add_from_template", lang),
        doneText = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = LoonSpacing.xs, bottom = LoonSpacing.sm)
        ) {
            CupertinoGroupedSection {
                SCRIPT_TEMPLATES.forEachIndexed { index, template ->
                    if (index > 0) CupertinoIndentedDivider(startIndent = 16.dp)
                    CupertinoCell(
                        title = AppStrings.get(template.nameKey, lang),
                        subtitle = AppStrings.get(template.descKey, lang),
                        showChevron = true,
                        onClick = { onSelectTemplate(template) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptUrlImportDialog(
    dialogs: ScriptListDialogsState,
    onRefresh: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    CupertinoAlertDialog(
        title = AppStrings.get("scripts_url_import", lang),
        confirmText = if (dialogs.isImportingUrl) AppStrings.get("importing", lang) else AppStrings.get("scripts_import", lang),
        dismissText = AppStrings.get("cancel", lang),
        onConfirm = {
            if (dialogs.isImportingUrl) return@CupertinoAlertDialog
            val cleanUrl = dialogs.importUrlValue.trim()
            if (cleanUrl.isBlank()) {
                CupertinoToast.show(AppStrings.get("scripts_url_required", lang), isDestructive = true)
                return@CupertinoAlertDialog
            }
            dialogs.isImportingUrl = true
            scope.launch {
                val res = ScriptManager.downloadScript(
                    context = context,
                    url = cleanUrl,
                    name = dialogs.importUrlName.trim(),
                    pattern = "",
                    type = "config"
                )
                dialogs.isImportingUrl = false
                if (res.isSuccess) {
                    val item = res.getOrThrow()
                    withContext(Dispatchers.IO) { linkScriptToEnabledProfiles(context, item.id) }
                    dialogs.showUrlImportDialog = false
                    onRefresh()
                    CupertinoToast.show(AppStrings.get("imported_name", lang).replace("{name}", item.name))
                } else {
                    CupertinoToast.show(
                        AppStrings.get("import_failed", lang).replace("{msg}", res.exceptionOrNull()?.message ?: ""),
                        isDestructive = true
                    )
                }
            }
        },
        onDismiss = { if (!dialogs.isImportingUrl) dialogs.showUrlImportDialog = false },
        onDismissRequest = { if (!dialogs.isImportingUrl) dialogs.showUrlImportDialog = false }
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        CupertinoTextField(
            value = dialogs.importUrlName,
            onValueChange = { value -> dialogs.importUrlName = value },
            placeholder = AppStrings.get("scripts_name_optional_short", lang),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(10.dp))
        CupertinoTextField(
            value = dialogs.importUrlValue,
            onValueChange = { value -> dialogs.importUrlValue = value },
            placeholder = AppStrings.get("scripts_url_field", lang),
            singleLine = true
        )
    }
}

@Composable
private fun ScriptItemActionSheet(
    target: ScriptItem,
    lang: String,
    onDismiss: () -> Unit,
    onUpdateSingle: (ScriptItem) -> Unit,
    onEditCode: (ScriptItem) -> Unit,
    onEditInfo: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val haptic = rememberIosHaptic()
    val isRemote = target.url.isNotBlank()
    val actions = buildList {
        if (isRemote) {
            add(
                CupertinoActionSheetItem(
                    text = AppStrings.get("profile_update", lang),
                    onClick = {
                        haptic.lightTick()
                        onDismiss()
                        onUpdateSingle(target)
                    }
                )
            )
        }
        add(
            CupertinoActionSheetItem(
                text = AppStrings.get("scripts_edit_code", lang),
                onClick = {
                    haptic.selection()
                    onDismiss()
                    onEditCode(target)
                }
            )
        )
        add(
            CupertinoActionSheetItem(
                text = AppStrings.get("scripts_edit_info", lang),
                onClick = {
                    haptic.selection()
                    onDismiss()
                    onEditInfo()
                }
            )
        )
        add(
            CupertinoActionSheetItem(
                text = AppStrings.get("profile_delete", lang),
                isDestructive = true,
                onClick = {
                    haptic.impact()
                    onDismiss()
                    onRequestDelete()
                }
            )
        )
    }

    CupertinoActionSheet(
        onDismissRequest = onDismiss,
        title = target.name,
        actions = actions,
        cancelText = AppStrings.get("cancel", lang)
    )
}

@Composable
private fun ScriptEditInfoDialog(
    target: ScriptItem,
    dialogs: ScriptListDialogsState,
    onRefresh: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRemote = target.url.isNotBlank()

    CupertinoAlertDialog(
        title = AppStrings.get("scripts_edit_info", lang),
        confirmText = AppStrings.get("action_save", lang),
        dismissText = AppStrings.get("cancel", lang),
        onConfirm = {
            val finalName = dialogs.editInfoName.trim().ifBlank { target.name }
            val finalUrl = if (isRemote) dialogs.editInfoUrl.trim() else target.url
            scope.launch {
                try {
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
                    dialogs.editingInfoScript = null
                    onRefresh()
                    CupertinoToast.show(AppStrings.get("saved_toast", lang))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CupertinoToast.show(
                        e.message ?: AppStrings.get("unknown_error", lang),
                        isDestructive = true
                    )
                }
            }
        },
        onDismiss = { dialogs.editingInfoScript = null },
        onDismissRequest = { dialogs.editingInfoScript = null }
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        CupertinoTextField(
            value = dialogs.editInfoName,
            onValueChange = { value -> dialogs.editInfoName = value },
            placeholder = AppStrings.get("field_name", lang),
            singleLine = true
        )
        if (isRemote) {
            Spacer(modifier = Modifier.height(10.dp))
            CupertinoTextField(
                value = dialogs.editInfoUrl,
                onValueChange = { value -> dialogs.editInfoUrl = value },
                placeholder = AppStrings.get("scripts_url_field", lang),
                singleLine = true
            )
        }
    }
}

@Composable
private fun ScriptDeleteConfirmDialog(
    target: ScriptItem,
    dialogs: ScriptListDialogsState,
    onRefresh: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    CupertinoAlertDialog(
        title = AppStrings.get("scripts_delete_title", lang),
        message = AppStrings.get("scripts_delete_confirm_alt", lang).replace("{name}", target.name),
        confirmText = AppStrings.get("profile_delete", lang),
        dismissText = AppStrings.get("cancel", lang),
        isDestructive = true,
        onConfirm = {
            dialogs.deleteConfirmScript = null
            CupertinoToast.show(AppStrings.get("scripts_deleted_toast", lang))
            scope.launch {
                withContext(Dispatchers.IO) {
                    ScriptManager.deleteScript(context, target.id)
                }
                onRefresh()
            }
        },
        onDismiss = { dialogs.deleteConfirmScript = null },
        onDismissRequest = { dialogs.deleteConfirmScript = null }
    )
}

private class ScriptEditorDocumentState(initialValue: TextFieldValue) {
    var textFieldValue by mutableStateOf(initialValue)
    private val undoStack = mutableStateListOf(initialValue)
    private var undoIndex by mutableIntStateOf(0)

    val canUndo: Boolean get() = undoIndex > 0
    val canRedo: Boolean get() = undoIndex < undoStack.lastIndex

    fun updateCode(newValue: TextFieldValue) {
        val oldText = textFieldValue.text
        textFieldValue = newValue
        if (newValue.text != oldText) {
            while (undoStack.size > undoIndex + 1) {
                undoStack.removeAt(undoStack.lastIndex)
            }
            if (undoStack.size >= MAX_UNDO_STEPS) {
                undoStack.removeAt(0)
            }
            undoStack.add(newValue)
            undoIndex = undoStack.lastIndex
        }
    }

    fun insertSymbol(symbol: String, cursorOffsetBack: Int) {
        val current = textFieldValue.text
        val start = textFieldValue.selection.min.coerceIn(0, current.length)
        val end = textFieldValue.selection.max.coerceIn(0, current.length)
        val updated = current.substring(0, start) + symbol + current.substring(end)
        val newCursor = (start + symbol.length - cursorOffsetBack).coerceIn(0, updated.length)
        updateCode(TextFieldValue(text = updated, selection = TextRange(newCursor)))
    }

    fun undo() {
        if (canUndo) {
            undoIndex--
            textFieldValue = undoStack[undoIndex]
        }
    }

    fun redo() {
        if (canRedo) {
            undoIndex++
            textFieldValue = undoStack[undoIndex]
        }
    }
}

private data class ScriptSaveContext(
    val context: Context,
    val existingScript: ScriptItem?,
    val lang: String,
    val scope: kotlinx.coroutines.CoroutineScope,
    val onSavingChange: (Boolean) -> Unit,
    val onSaved: () -> Unit
)

private fun launchScriptSave(
    saveContext: ScriptSaveContext,
    codeText: String,
    finalName: String
) {
    if (codeText.isBlank()) {
        CupertinoToast.show(
            AppStrings.get("scripts_content_required", saveContext.lang),
            isDestructive = true
        )
        return
    }
    saveContext.onSavingChange(true)
    saveContext.scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val saved = ScriptManager.saveScript(
                    context = saveContext.context,
                    existingId = saveContext.existingScript?.id,
                    name = finalName.trim().ifBlank { AppStrings.get("scripts_default_name", saveContext.lang) },
                    url = saveContext.existingScript?.url ?: "",
                    pattern = saveContext.existingScript?.pattern ?: "",
                    type = saveContext.existingScript?.type ?: "config",
                    code = codeText,
                    enabled = true
                )
                linkScriptToEnabledProfiles(saveContext.context, saved.id)
            }
            saveContext.onSavingChange(false)
            CupertinoToast.show(AppStrings.get("saved_toast", saveContext.lang))
            saveContext.onSaved()
        } catch (e: CancellationException) {
            saveContext.onSavingChange(false)
            throw e
        } catch (e: Exception) {
            saveContext.onSavingChange(false)
            CupertinoToast.show(
                e.message ?: AppStrings.get("unknown_error", saveContext.lang),
                isDestructive = true
            )
        }
    }
}

@Composable
private fun rememberHighlightedCode(
    currentText: String,
    loadedCode: String?,
    isDark: Boolean
): Pair<String, AnnotatedString>? {
    // Highlight on Dispatchers.Default with a short debounce for smooth typing on large scripts.
    var highlightedSnapshot by remember(isDark) { mutableStateOf<Pair<String, AnnotatedString>?>(null) }
    LaunchedEffect(currentText, isDark, loadedCode) {
        if (loadedCode == null || currentText.isEmpty() || currentText.length > MAX_HIGHLIGHT_CHARS) {
            highlightedSnapshot = null
            return@LaunchedEffect
        }
        val isInitialHighlight = highlightedSnapshot == null
        if (!isInitialHighlight) {
            delay(HIGHLIGHT_DEBOUNCE_MS)
        }
        val rendered = withContext(Dispatchers.Default) { highlightCodeSnippet(currentText, isDark) }
        highlightedSnapshot = currentText to rendered
    }
    return highlightedSnapshot
}

@Composable
private fun rememberLoadedScriptCode(
    context: Context,
    existingScript: ScriptItem?,
    templateCode: String?
): String? {
    var loadedCode by remember(existingScript, templateCode) { mutableStateOf<String?>(null) }
    LaunchedEffect(existingScript, templateCode) {
        loadedCode = if (existingScript != null) {
            withContext(Dispatchers.IO) { ScriptManager.readScriptCode(context, existingScript) }
        } else {
            templateCode ?: DEFAULT_NEW_SCRIPT_CODE
        }
    }
    return loadedCode
}

@Composable
private fun ScriptEditorScreen(
    existingScript: ScriptItem?,
    templateNameKey: String?,
    templateCode: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()
    val isDark = LocalLoonColors.current.isDark

    var scriptName by remember(existingScript, templateNameKey) {
        mutableStateOf(
            existingScript?.name ?: templateNameKey?.let { key -> AppStrings.get(key, lang) } ?: ""
        )
    }
    var showNameDialog by remember { mutableStateOf(false) }
    var tempNameInput by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    val loadedCode = rememberLoadedScriptCode(context, existingScript, templateCode)
    val document = remember(loadedCode) {
        ScriptEditorDocumentState(TextFieldValue(text = loadedCode.orEmpty(), selection = TextRange.Zero))
    }
    val saveContext = ScriptSaveContext(
        context = context,
        existingScript = existingScript,
        lang = lang,
        scope = scope,
        onSavingChange = { saving -> isSaving = saving },
        onSaved = onSaved
    )

    var showDiscardDialog by remember { mutableStateOf(false) }
    val isDirty = loadedCode != null && document.textFieldValue.text != loadedCode
    val requestBack: () -> Unit = {
        if (isDirty) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(onBack = requestBack)

    ScriptEditorScaffold(
        scriptName = scriptName,
        loadedCode = loadedCode,
        isSaving = isSaving,
        document = document,
        isDark = isDark,
        lang = lang,
        onBack = requestBack,
        onRequestSave = {
            haptic.lightTick()
            if (scriptName.isBlank()) {
                tempNameInput = ""
                showNameDialog = true
            } else if (!isSaving && loadedCode != null) {
                launchScriptSave(saveContext, document.textFieldValue.text, scriptName)
            }
        }
    )

    if (showDiscardDialog) {
        CupertinoAlertDialog(
            title = AppStrings.get("cancel", lang),
            message = AppStrings.get("scripts_discard_confirm", lang),
            confirmText = AppStrings.get("confirm", lang),
            dismissText = AppStrings.get("cancel", lang),
            isDestructive = true,
            onConfirm = {
                showDiscardDialog = false
                onBack()
            },
            onDismiss = { showDiscardDialog = false },
            onDismissRequest = { showDiscardDialog = false }
        )
    }

    if (showNameDialog) {
        ScriptEditorNameDialog(
            isEditingExisting = existingScript != null,
            tempNameInput = tempNameInput,
            lang = lang,
            onTempNameChange = { value -> tempNameInput = value },
            onConfirm = {
                val resolved = tempNameInput.trim().ifBlank { AppStrings.get("scripts_default_name", lang) }
                scriptName = resolved
                showNameDialog = false
                if (!isSaving && loadedCode != null) {
                    launchScriptSave(saveContext, document.textFieldValue.text, resolved)
                }
            },
            onDismiss = { showNameDialog = false }
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ScriptEditorScaffold(
    scriptName: String,
    loadedCode: String?,
    isSaving: Boolean,
    document: ScriptEditorDocumentState,
    isDark: Boolean,
    lang: String,
    onBack: () -> Unit,
    onRequestSave: () -> Unit
) {
    val haptic = rememberIosHaptic()
    val highlightedSnapshot = rememberHighlightedCode(document.textFieldValue.text, loadedCode, isDark)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonCard)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        ScriptEditorNavBar(
            title = scriptName.ifBlank { AppStrings.get("scripts_new", lang) },
            isSaveEnabled = !isSaving && loadedCode != null,
            lang = lang,
            onBack = onBack,
            onSave = onRequestSave
        )
        ScriptEditorContentPane(
            loadedCode = loadedCode,
            document = document,
            highlightedSnapshot = highlightedSnapshot,
            isDark = isDark,
            lang = lang,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        ScriptEditorAccessoryBar(
            canUndo = document.canUndo,
            canRedo = document.canRedo,
            lang = lang,
            onUndo = { haptic.lightTick(); document.undo() },
            onRedo = { haptic.lightTick(); document.redo() },
            onInsertSymbol = { symbol, offset -> haptic.lightTick(); document.insertSymbol(symbol, offset) }
        )
    }
}

@Composable
private fun ScriptEditorContentPane(
    loadedCode: String?,
    document: ScriptEditorDocumentState,
    highlightedSnapshot: Pair<String, AnnotatedString>?,
    isDark: Boolean,
    lang: String,
    modifier: Modifier = Modifier
) {
    if (loadedCode == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CupertinoActivityIndicator(size = 24.dp, color = IosBlue)
        }
    } else {
        ScriptCodeCanvas(
            textFieldValue = document.textFieldValue,
            highlightedSnapshot = highlightedSnapshot,
            isDark = isDark,
            lang = lang,
            onValueChange = document::updateCode,
            modifier = modifier
        )
    }
}

@Composable
private fun ScriptEditorNameDialog(
    isEditingExisting: Boolean,
    tempNameInput: String,
    lang: String,
    onTempNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    CupertinoAlertDialog(
        title = if (isEditingExisting) AppStrings.get("scripts_name_label", lang)
        else AppStrings.get("scripts_save_title", lang),
        confirmText = AppStrings.get("action_save", lang),
        dismissText = AppStrings.get("cancel", lang),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        onDismissRequest = onDismiss
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        CupertinoTextField(
            value = tempNameInput,
            onValueChange = onTempNameChange,
            placeholder = AppStrings.get("scripts_name_input_hint", lang),
            singleLine = true
        )
    }
}

@Composable
private fun ScriptEditorNavBar(
    title: String,
    isSaveEnabled: Boolean,
    lang: String,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    val shape = RoundedCornerShape(LoonRadii.control)
    CupertinoNavBar(
        title = title,
        showBackButton = true,
        onBack = onBack,
        actions = {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                    .clip(shape)
                    .iosFocusRing(shape)
                    .iosPressable(enabled = isSaveEnabled, onClick = onSave)
                    .padding(horizontal = LoonSpacing.sm),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = AppStrings.get("action_save", lang),
                    color = LoonInfoText,
                    style = LoonTextStyles.button()
                )
            }
        }
    )
}

@Composable
private fun ScriptCodeCanvas(
    textFieldValue: TextFieldValue,
    highlightedSnapshot: Pair<String, AnnotatedString>?,
    isDark: Boolean,
    lang: String,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    val codeScrollState = rememberScrollState()
    val codeHorizontalScroll = rememberScrollState()
    val separatorColor = LoonSeparator
    val gutterWidth = 44.dp
    val monoStyle = LoonTextStyles.mono()

    val lineCount = remember(textFieldValue.text) {
        textFieldValue.text.count { ch -> ch == '\n' } + 1
    }
    val lineNumbersText = remember(lineCount) {
        (1..lineCount).joinToString("\n")
    }
    val baseColor = if (isDark) LoonExtraColors.syntaxTextDark else LoonExtraColors.syntaxTextLight
    val syntaxVisualTransformation = remember(highlightedSnapshot, baseColor) {
        CodeSyntaxVisualTransformation(highlightedSnapshot = highlightedSnapshot, baseColor = baseColor)
    }

    Row(
        modifier = modifier
            .background(LoonCard)
            .verticalScroll(codeScrollState)
            .drawBehind {
                val x = gutterWidth.toPx()
                drawLine(
                    color = separatorColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 0.5.dp.toPx()
                )
            }
    ) {
        Box(
            modifier = Modifier
                .width(gutterWidth)
                .padding(vertical = LoonSpacing.md, horizontal = 6.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Text(
                text = lineNumbersText,
                style = monoStyle,
                color = LoonTextMuted,
                textAlign = TextAlign.End
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(codeHorizontalScroll)
                .padding(horizontal = LoonSpacing.md, vertical = LoonSpacing.md)
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = onValueChange,
                visualTransformation = syntaxVisualTransformation,
                cursorBrush = SolidColor(IosBlue),
                textStyle = monoStyle.copy(color = LoonTextPrimary),
                modifier = Modifier
                    .defaultMinSize(minWidth = 300.dp, minHeight = 300.dp)
                    .fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = AppStrings.get("scripts_editor_hint", lang),
                                style = monoStyle,
                                color = LoonTextMuted
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
private fun ScriptEditorAccessoryBar(
    canUndo: Boolean,
    canRedo: Boolean,
    lang: String,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onInsertSymbol: (String, Int) -> Unit
) {
    val actionShape = RoundedCornerShape(LoonRadii.control)
    val keyShape = RoundedCornerShape(LoonRadii.badge)

    HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin)
            .background(LoonCard)
            .padding(horizontal = LoonSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccessoryBarIconButton(
            icon = Icons.AutoMirrored.Filled.Undo,
            contentDescription = AppStrings.get("undo_desc", lang),
            enabled = canUndo,
            shape = actionShape,
            onClick = onUndo
        )
        AccessoryBarIconButton(
            icon = Icons.AutoMirrored.Filled.Redo,
            contentDescription = AppStrings.get("redo_desc", lang),
            enabled = canRedo,
            shape = actionShape,
            onClick = onRedo
        )

        Box(
            modifier = Modifier
                .padding(horizontal = LoonSpacing.xs)
                .width(0.5.dp)
                .height(18.dp)
                .background(LoonSeparator)
        )

        LazyRow(
            modifier = Modifier
                .weight(1f)
                .height(LoonSizes.touchTargetMin),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(QUICK_EDITOR_SYMBOLS, key = { item -> item.first }) { (label, pair) ->
                val (insertText, backOffset) = pair
                Box(
                    modifier = Modifier
                        .heightIn(min = LoonSizes.touchTargetMin)
                        .widthIn(min = 40.dp)
                        .clip(keyShape)
                        .iosFocusRing(keyShape)
                        .iosHighlightPressable { onInsertSymbol(insertText, backOffset) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = LoonTextStyles.mono().copy(fontWeight = FontWeight.Medium),
                        color = LoonTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessoryBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    shape: Shape,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(LoonSizes.touchTargetMin)
            .clip(shape)
            .iosFocusRing(shape)
            .iosHighlightPressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) LoonTextPrimary else LoonTextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp)
        )
    }
}

private const val MAX_HIGHLIGHT_CHARS = 40_000
private const val HIGHLIGHT_DEBOUNCE_MS = 120L
private const val MAX_UNDO_STEPS = 50

private class CodeSyntaxVisualTransformation(
    private val highlightedSnapshot: Pair<String, AnnotatedString>?,
    private val baseColor: androidx.compose.ui.graphics.Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val snapshot = highlightedSnapshot
        val rendered = if (snapshot != null && snapshot.first == raw) {
            snapshot.second
        } else {
            buildAnnotatedString {
                append(raw)
                if (raw.isNotEmpty()) {
                    addStyle(SpanStyle(color = baseColor), 0, raw.length)
                }
            }
        }
        return TransformedText(text = rendered, offsetMapping = OffsetMapping.Identity)
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
    val keywordColor = if (isDark) LoonExtraColors.syntaxKeywordDark else LoonExtraColors.syntaxKeywordLight
    val builtinColor = if (isDark) LoonExtraColors.syntaxBuiltinDark else LoonExtraColors.syntaxBuiltinLight
    val yamlKeyColor = if (isDark) LoonExtraColors.syntaxKeyDark else LoonExtraColors.syntaxKeyLight
    val stringColor = if (isDark) LoonExtraColors.syntaxStringDark else LoonExtraColors.syntaxStringLight
    val numberColor = if (isDark) LoonExtraColors.syntaxNumberDark else LoonExtraColors.syntaxNumberLight
    val commentColor = if (isDark) LoonExtraColors.syntaxCommentDark else LoonExtraColors.syntaxCommentLight
    val baseColor = if (isDark) LoonExtraColors.syntaxTextDark else LoonExtraColors.syntaxTextLight

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

private fun readPickedScriptFile(
    context: Context,
    uri: android.net.Uri,
    lang: String
): Pair<String, String> {
    var name = "local_script.js"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIdx != -1 && cursor.moveToFirst()) {
            name = cursor.getString(nameIdx) ?: name
        }
    }
    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader -> reader.readText() }
        ?: error(AppStrings.get("scripts_read_content_failed", lang))
    val cleanName = name
        .removeSuffix(".js")
        .removeSuffix(".yaml")
        .removeSuffix(".yml")
        .removeSuffix(".txt")
        .ifBlank { AppStrings.get("script_type_local", lang) }
    return cleanName to text
}

private suspend fun runRemoteUpdates(
    context: Context,
    remoteScripts: List<ScriptItem>,
    onScriptFinished: (String) -> Unit
): Pair<Int, Int> {
    var successCount = 0
    var failCount = 0
    for (script in remoteScripts) {
        val res = ScriptManager.updateScriptFromRemote(context, script)
        onScriptFinished(script.id)
        if (res.isSuccess) successCount++ else failCount++
    }
    return successCount to failCount
}

private fun showBatchUpdateToast(lang: String, successCount: Int, failCount: Int) {
    if (failCount == 0) {
        CupertinoToast.show(AppStrings.get("scripts_updated_count", lang).replace("{n}", successCount.toString()))
    } else {
        CupertinoToast.show(
            AppStrings.get("scripts_update_summary", lang)
                .replace("{ok}", successCount.toString())
                .replace("{fail}", failCount.toString()),
            isDestructive = successCount == 0
        )
    }
}

private fun formatRelativeTime(timestamp: Long, lang: String): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return AppStrings.get("time_just_now", lang)
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    val months = days / 30
    return when {
        seconds < 60 -> AppStrings.get("time_just_now", lang)
        minutes < 60 -> AppStrings.get("time_minutes_ago", lang).replace("{n}", minutes.toString())
        hours < 24 -> AppStrings.get("time_hours_ago", lang).replace("{n}", hours.toString())
        days < 30 -> AppStrings.get("time_days_ago", lang).replace("{n}", days.toString())
        months < 12 -> AppStrings.get("time_months_ago", lang).replace("{n}", months.toString())
        else -> AppStrings.get("time_years_ago", lang).replace("{n}", (months / 12).toString())
    }
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

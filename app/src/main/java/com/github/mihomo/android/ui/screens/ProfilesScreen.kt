@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.mihomo.android.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.SubscriptionUpdateWorker
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY_MS = 86_400_000L
private const val EXPIRE_WARN_DAYS = 7L
private const val DRAG_LIFT_SCALE = 1.03f
private const val DRAG_SHADOW_ELEVATION = 24f
private const val LIST_ITEM_SPACING_DP = 10

// java.time formatters are immutable and thread-safe, unlike SimpleDateFormat, and the
// date is user-visible so it follows the device locale instead of a fixed US format.
// The formatter is built per call rather than cached in a static field: a cached
// Locale would keep the locale that was active at class-load time and would not
// follow a locale change made while the app is running.
private fun expireDateFormat(): DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun formatRelativeTime(timestamp: Long, lang: String): String {
    if (timestamp <= 0L) return AppStrings.get("time_never_updated", lang)
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 60_000L) return AppStrings.get("time_just_now", lang)
    val minutes = diff / 60_000L
    if (minutes < 60L) return AppStrings.get("time_minutes_ago", lang).replace("{n}", minutes.toString())
    val hours = minutes / 60L
    if (hours < 24L) return AppStrings.get("time_hours_ago", lang).replace("{n}", hours.toString())
    val days = hours / 24L
    return AppStrings.get("time_days_ago", lang).replace("{n}", days.toString())
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
    onNavigateToOverride: ((ProfileItem) -> Unit)? = null,
    onNavigateToQrScan: () -> Unit = {},
    preFillSubscriptionUrl: String? = null,
    onPreFillConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = rememberIosHaptic()
    val lang = LocalAppLanguage.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val itemSpacingPx = with(LocalDensity.current) { LIST_ITEM_SPACING_DP.dp.toPx() }

    var showAddSheet by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogFromPrefill by remember { mutableStateOf(false) }
    var newSubName by remember { mutableStateOf("") }
    var newSubUrl by remember { mutableStateOf("") }

    var actionSheetProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var editingProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var editName by remember { mutableStateOf("") }
    var editUrl by remember { mutableStateOf("") }

    var deletingProfile by remember { mutableStateOf<ProfileItem?>(null) }

    // On-disk size per profile. `File.length()` is a stat() syscall and must not run in the
    // composition body (UI-PERF-03), so it is resolved off the main thread and cached here.
    var profileFileSizes by remember { mutableStateOf(emptyMap<String, Long>()) }

    // Auto-update editor sheet draft state.
    var autoUpdateProfile by remember { mutableStateOf<ProfileItem?>(null) }
    var draftAutoUpdate by remember { mutableStateOf(false) }
    var draftIntervalText by remember { mutableStateOf("1440") }

    // Long-press drag reorder state. Data swaps happen live while dragging; the dragged
    // card keeps following the finger through dragOffsetY, re-based after every swap.
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var localProfiles by remember(profiles) { mutableStateOf(profiles) }
    val canReorder = profiles.size > 1 && onReorderProfiles != null

    LaunchedEffect(preFillSubscriptionUrl) {
        if (!preFillSubscriptionUrl.isNullOrBlank()) {
            newSubName = ""
            newSubUrl = preFillSubscriptionUrl
            addDialogFromPrefill = true
            showAddDialog = true
        }
    }

    // Resolve the on-disk sizes off the main thread whenever the profile list changes.
    LaunchedEffect(profiles) {
        val sizes = withContext(Dispatchers.IO) {
            localProfiles.associate { it.id to it.file.length() }
        }
        profileFileSizes = sizes
    }

    val closeAddDialog: () -> Unit = {
        showAddDialog = false
        if (addDialogFromPrefill) {
            addDialogFromPrefill = false
            onPreFillConsumed()
        }
    }

    val localFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // The SAF import reads the whole document: it must not run on the main thread.
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ConfigManager.importProfileFromUri(context, uri)
                }
                if (result.isSuccess) {
                    val imported = result.getOrThrow()
                    onUpdateProfile(imported)
                    CupertinoToast.show(AppStrings.get("imported_toast", lang).replace("{name}", imported.name))
                } else {
                    CupertinoToast.show(AppStrings.get("import_failed", lang).replace("{msg}", result.exceptionOrNull()?.message ?: ""), isDestructive = true)
                }
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
            title = AppStrings.get("nav_profiles", lang),
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
                        contentDescription = AppStrings.get("action_add", lang),
                        tint = IosBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )

        LazyColumn(
            state = listState,
            userScrollEnabled = draggedId == null,
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(LIST_ITEM_SPACING_DP.dp)
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
                                text = AppStrings.get("profiles_empty_short", lang),
                                color = LoonTextSecondary,
                                fontSize = LoonTypeScale.subhead
                            )
                        }
                    }
                }
            } else {
                if (profiles.size > 1) {
                    item(key = "profile_reorder_hint") {
                        Text(
                            text = AppStrings.current("profile_reorder_hint"),
                            color = LoonTextMuted,
                            fontSize = LoonTypeScale.caption,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                items(localProfiles, key = { it.id }) { profile ->
                    val isSelected = profile.id == selectedProfileId
                    val isSub = !profile.url.isNullOrBlank()
                    val isDragging = draggedId == profile.id
                    val dragTranslationY = remember { Animatable(0f) }
                    val settleDragCard: () -> Unit = {
                        onReorderProfiles?.invoke(localProfiles)
                        val residual = dragOffsetY
                        scope.launch {
                            // Settle the card into its new slot instead of snapping to it.
                            dragTranslationY.snapTo(residual)
                            draggedId = null
                            dragOffsetY = 0f
                            dragTranslationY.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f))
                        }
                    }

                    val infoText = remember(profile, lang, profileFileSizes) {
                        if (profile.totalBytes > 0L) {
                            val used = formatTraffic(profile.uploadBytes + profile.downloadBytes)
                            val total = formatTraffic(profile.totalBytes)
                            "$used / $total"
                        } else {
                            // The on-disk size is read off the main thread (see the
                            // profileFileSizes loader); an unknown size falls back to "0 B".
                            formatFileSize(profileFileSizes[profile.id] ?: 0L)
                        }
                    }
                    val timeText = remember(profile, lang) { formatRelativeTime(profile.lastUpdated, lang) }

                    val expireText: String?
                    val expireColor: Color
                    if (profile.expireTimestamp > 0L) {
                        val expireMillis = profile.expireTimestamp * 1000L
                        val now = System.currentTimeMillis()
                        if (expireMillis <= now) {
                            expireText = AppStrings.current("profile_expired")
                            expireColor = LoonDangerText
                        } else {
                            val daysLeft = (expireMillis - now + DAY_MS - 1L) / DAY_MS
                            expireText = if (daysLeft <= EXPIRE_WARN_DAYS) {
                                AppStrings.current("profile_expire_days_left").replace("{days}", daysLeft.toString())
                            } else {
                                AppStrings.current("profile_expire_at")
                                    .replace("{date}", expireDateFormat().format(Instant.ofEpochMilli(expireMillis)))
                            }
                            expireColor = if (daysLeft <= EXPIRE_WARN_DAYS) LoonWarningText else LoonTextSecondary
                        }
                    } else {
                        expireText = null
                        expireColor = LoonTextSecondary
                    }

                    val dragModifier = if (canReorder) {
                        Modifier.pointerInput(profile.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedId = profile.id
                                    dragOffsetY = 0f
                                    scope.launch { dragTranslationY.snapTo(0f) }
                                },
                                onDragEnd = { settleDragCard() },
                                onDragCancel = { settleDragCard() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (draggedId != profile.id) return@detectDragGesturesAfterLongPress
                                    dragOffsetY += dragAmount.y

                                    val visibleItems = listState.layoutInfo.visibleItemsInfo
                                    val fromInfo = visibleItems.firstOrNull { it.key == profile.id }
                                        ?: return@detectDragGesturesAfterLongPress
                                    val fromIndex = localProfiles.indexOfFirst { it.id == profile.id }
                                    if (fromIndex == -1) return@detectDragGesturesAfterLongPress

                                    val draggedCenter = fromInfo.offset + fromInfo.size / 2f + dragOffsetY
                                    val targetInfo = visibleItems.firstOrNull { candidate ->
                                        candidate.key != profile.id &&
                                            draggedCenter >= candidate.offset &&
                                            draggedCenter <= candidate.offset + candidate.size
                                    } ?: return@detectDragGesturesAfterLongPress
                                    val toIndex = localProfiles.indexOfFirst { it.id == targetInfo.key }
                                    if (toIndex == -1 || toIndex == fromIndex) {
                                        return@detectDragGesturesAfterLongPress
                                    }

                                    val reordered = localProfiles.toMutableList()
                                    val moved = reordered.removeAt(fromIndex)
                                    reordered.add(toIndex, moved)
                                    localProfiles = reordered

                                    // Predict where the next layout pass will place this card and
                                    // re-base the drag offset so it stays glued to the finger. The
                                    // list re-anchors on the first visible item (keeping its viewport
                                    // offset), which changes the prediction.
                                    val anchorKey = visibleItems.firstOrNull()?.key
                                    val newOffset = when (anchorKey) {
                                        profile.id -> fromInfo.offset.toFloat()
                                        targetInfo.key -> targetInfo.offset - fromInfo.size - itemSpacingPx
                                        else -> targetInfo.offset.toFloat()
                                    }
                                    dragOffsetY = fromInfo.offset + dragOffsetY - newOffset
                                }
                            )
                        }
                    } else {
                        Modifier
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
                            .zIndex(if (isDragging) 100f else 1f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetY else dragTranslationY.value
                                val lift = if (isDragging) DRAG_LIFT_SCALE else 1f
                                scaleX = lift
                                scaleY = lift
                                shadowElevation = if (isDragging) DRAG_SHADOW_ELEVATION else 0f
                                shape = RoundedCornerShape(14.dp)
                            }
                            .clip(RoundedCornerShape(14.dp))
                            .then(dragModifier)
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
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (isSub) IosBlue.copy(alpha = 0.12f) else IosOrange.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isSub) Icons.Default.CloudQueue else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (isSub) IosBlue else IosOrange,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(13.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    color = LoonTextPrimary,
                                    fontSize = LoonTypeScale.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = infoText,
                                            color = LoonTextSecondary,
                                            fontSize = LoonTypeScale.footnote,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (expireText != null) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = expireText,
                                                color = expireColor,
                                                fontSize = LoonTypeScale.footnote,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = timeText,
                                        color = LoonTextSecondary,
                                        fontSize = LoonTypeScale.footnote,
                                        maxLines = 1
                                    )
                                }
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
                                    contentDescription = AppStrings.get("action_more", lang),
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
                    text = AppStrings.get("profiles_add_subscription", lang),
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
                    text = AppStrings.get("qr_scan_title", lang),
                    onClick = {
                        haptic.selection()
                        showAddSheet = false
                        onNavigateToQrScan()
                    }
                ),
                CupertinoActionSheetItem(
                    text = AppStrings.get("profiles_import_file", lang),
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
            cancelText = AppStrings.get("cancel", lang)
        )
    }

    if (showAddDialog) {
        CupertinoAlertDialog(
            title = AppStrings.get("profiles_add_subscription", lang),
            confirmText = AppStrings.get("action_add", lang),
            dismissText = AppStrings.get("cancel", lang),
            onConfirm = {
                val url = newSubUrl.trim()
                if (url.isNotBlank()) {
                    closeAddDialog()
                    val name = newSubName.trim().ifBlank { AppStrings.get("default_sub_name", lang) }
                    onAddSubscription(name, url)
                } else {
                    CupertinoToast.show(AppStrings.get("profile_url_required", lang), isDestructive = true)
                }
            },
            onDismiss = closeAddDialog,
            onDismissRequest = closeAddDialog
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            CupertinoTextField(
                value = newSubName,
                onValueChange = { newSubName = it },
                placeholder = AppStrings.get("field_name", lang),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(10.dp))
            CupertinoTextField(
                value = newSubUrl,
                onValueChange = { newSubUrl = it },
                placeholder = AppStrings.get("field_url", lang),
                singleLine = true
            )
        }
    }

    if (actionSheetProfile != null) {
        val target = actionSheetProfile!!
        val isSub = !target.url.isNullOrBlank()
        val autoUpdateLabel = AppStrings.current("profile_auto_update")

        val actions = buildList {
            if (isSub) {
                add(
                    CupertinoActionSheetItem(
                        text = AppStrings.get("profile_update", lang),
                        onClick = {
                            haptic.lightTick()
                            actionSheetProfile = null
                            onRefreshSubscription(target)
                        }
                    )
                )
                add(
                    CupertinoActionSheetItem(
                        text = autoUpdateLabel,
                        onClick = {
                            haptic.selection()
                            actionSheetProfile = null
                            draftAutoUpdate = target.autoUpdate
                            draftIntervalText = target.autoUpdateInterval.toString()
                            autoUpdateProfile = target
                        }
                    )
                )
            }
            add(
                CupertinoActionSheetItem(
                    text = AppStrings.get("shortcuts_edit", lang),
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
                        text = AppStrings.get("profile_override", lang),
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
                    text = AppStrings.get("profile_delete", lang),
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
            cancelText = AppStrings.get("cancel", lang)
        )
    }

    if (autoUpdateProfile != null) {
        val target = autoUpdateProfile!!
        CupertinoModalBottomSheet(
            onDismissRequest = { autoUpdateProfile = null },
            title = AppStrings.current("profile_auto_update"),
            doneText = null,
            trailingAction = {
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .iosPressable {
                            val interval = draftIntervalText.trim().toIntOrNull()
                                ?.coerceIn(SubscriptionUpdateWorker.MIN_INTERVAL_MINUTES, 60 * 24 * 30)
                                ?: 1440
                            val updated = target.copy(
                                autoUpdate = draftAutoUpdate,
                                autoUpdateInterval = interval
                            )
                            autoUpdateProfile = null
                            onUpdateProfile(updated)
                            // The profile store is rewritten as a whole: keep it off the
                            // main thread (UI-PERF-03).
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ConfigManager.updateProfile(context, updated)
                                }
                            }
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = AppStrings.current("action_save"),
                        color = IosBlue,
                        fontSize = LoonTypeScale.title3,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        ) {
            Spacer(modifier = Modifier.height(2.dp))
            CupertinoGroupedSection {
                CupertinoCell(
                    title = AppStrings.current("profile_auto_update"),
                    trailing = {
                        CupertinoSwitch(
                            checked = draftAutoUpdate,
                            onCheckedChange = { draftAutoUpdate = it }
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            CupertinoGroupedSection(header = AppStrings.current("profile_auto_update_interval")) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    CupertinoTextField(
                        value = draftIntervalText,
                        onValueChange = { newValue -> draftIntervalText = newValue.filter { ch -> ch.isDigit() } },
                        placeholder = "1440",
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = AppStrings.current("profile_interval_unit"),
                        color = LoonTextSecondary,
                        fontSize = LoonTypeScale.footnote
                    )
                }
            }
            Text(
                text = AppStrings.current("profile_auto_update_desc"),
                color = LoonTextSecondary,
                fontSize = LoonTypeScale.footnote,
                lineHeight = LoonLineHeight.footnote,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp, vertical = 10.dp)
            )
        }
    }

    if (editingProfile != null) {
        val target = editingProfile!!
        val isSub = !target.url.isNullOrBlank()

        CupertinoAlertDialog(
            title = AppStrings.get("shortcuts_edit", lang),
            confirmText = AppStrings.get("action_save", lang),
            dismissText = AppStrings.get("cancel", lang),
            onConfirm = {
                val finalName = editName.trim().ifBlank { target.name }
                val finalUrl = if (isSub) editUrl.trim() else target.url
                val updated = target.copy(name = finalName, url = finalUrl)
                editingProfile = null
                onUpdateProfile(updated)
                // The profile store is rewritten as a whole: keep it off the main thread.
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ConfigManager.updateProfile(context, updated)
                    }
                }
            },
            onDismiss = { editingProfile = null },
            onDismissRequest = { editingProfile = null }
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            CupertinoTextField(
                value = editName,
                onValueChange = { editName = it },
                placeholder = AppStrings.get("field_name", lang),
                singleLine = true
            )
            if (isSub) {
                Spacer(modifier = Modifier.height(10.dp))
                CupertinoTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it },
                    placeholder = AppStrings.get("field_url", lang),
                    singleLine = true
                )
            }
        }
    }

    if (deletingProfile != null) {
        val target = deletingProfile!!
        CupertinoAlertDialog(
            title = AppStrings.get("profile_delete_title", lang),
            message = AppStrings.get("profile_delete_confirm", lang),
            confirmText = AppStrings.get("profile_delete", lang),
            dismissText = AppStrings.get("cancel", lang),
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
            totalBytes = 1024L * 1024L * 1024L * 100L,
            expireTimestamp = System.currentTimeMillis() / 1000L + 86_400L * 5
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

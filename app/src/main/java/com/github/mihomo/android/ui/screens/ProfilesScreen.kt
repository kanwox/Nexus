@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.SubscriptionUpdateWorker
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoActionSheet
import com.github.mihomo.android.ui.components.CupertinoActionSheetItem
import com.github.mihomo.android.ui.components.CupertinoActivityIndicator
import com.github.mihomo.android.ui.components.CupertinoAlertDialog
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSwitch
import com.github.mihomo.android.ui.components.CupertinoTextField
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.IosHapticHelper
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.rememberClipboardReadText
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosOrange
import com.github.mihomo.android.ui.theme.LocalReduceMotion
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonCard
import com.github.mihomo.android.ui.theme.LoonCardBorder
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.MihomoTheme
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DAY_MS = 86_400_000L
private const val EXPIRE_WARN_DAYS = 7L
private const val DRAG_LIFT_SCALE = 1.03f
private const val DRAG_SHADOW_ELEVATION = 24f
private const val LIST_ITEM_SPACING_DP = 10

private fun expireDateFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", locale).withZone(ZoneId.systemDefault())

private fun formatRelativeTime(timestamp: Long, lang: String, nowMillis: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L) return AppStrings.get("time_never_updated", lang)
    val diff = nowMillis - timestamp
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
private fun resolveExpiryInfo(
    profile: ProfileItem,
    isSub: Boolean,
    nowTick: Long,
    lang: String,
    dateFormatter: DateTimeFormatter
): Pair<String?, Color> {
    if (!isSub || profile.expireTimestamp <= 0L) return null to LoonTextSecondary
    val expireMillis = profile.expireTimestamp * 1000L
    if (expireMillis <= nowTick) {
        return AppStrings.get("profile_expired", lang) to LoonDangerText
    }
    val daysLeft = (expireMillis - nowTick + DAY_MS - 1L) / DAY_MS
    return if (daysLeft <= EXPIRE_WARN_DAYS) {
        AppStrings.get("profile_expire_days_left", lang).replace("{days}", daysLeft.toString()) to LoonWarningText
    } else {
        dateFormatter.format(Instant.ofEpochMilli(expireMillis)) to LoonTextSecondary
    }
}

private class ProfilesModalState {
    var showAddSheet by mutableStateOf(false)
    var showAddDialog by mutableStateOf(false)
    var addDialogFromPrefill by mutableStateOf(false)
    var newSubName by mutableStateOf("")
    var newSubUrl by mutableStateOf("")
    var actionSheetProfile by mutableStateOf<ProfileItem?>(null)
    var editingProfile by mutableStateOf<ProfileItem?>(null)
    var deletingProfile by mutableStateOf<ProfileItem?>(null)
}

private class ProfilesBodyConfig(
    val lang: String,
    val haptic: IosHapticHelper,
    val showBackButton: Boolean,
    val isUpdatingAll: Boolean
)

private class ProfilesBodyCallbacks(
    val onBack: (() -> Unit)?,
    val onRefreshAllSubscriptions: (() -> Unit)?,
    val onRefreshSubscription: (ProfileItem) -> Unit,
    val onReorderProfiles: ((List<ProfileItem>) -> Unit)?,
    val onLocalReorder: (List<ProfileItem>) -> Unit,
    val onOpenAddSheet: () -> Unit,
    val onSelectProfile: (ProfileItem) -> Unit,
    val onOpenActionSheet: (ProfileItem) -> Unit
)

private data class ProfilesDialogActions(
    val readClipboardText: () -> String,
    val onAddSubscription: (String, String) -> Unit,
    val onUpdateProfile: (ProfileItem) -> Unit,
    val onRefreshSubscription: (ProfileItem) -> Unit,
    val onDeleteProfile: (ProfileItem) -> Unit,
    val onNavigateToOverride: ((ProfileItem) -> Unit)?,
    val onNavigateToQrScan: () -> Unit,
    val onLaunchImportFile: () -> Unit,
    val onPreFillConsumed: () -> Unit,
    val onRemoveDeletedLocalProfile: (ProfileItem) -> Unit
)

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
    isUpdatingAll: Boolean = false,
    onRefreshAllSubscriptions: (() -> Unit)? = null,
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
    val readClipboardText = rememberClipboardReadText()
    val scope = rememberCoroutineScope()
    val modals = remember { ProfilesModalState() }
    var localProfiles by remember(profiles) { mutableStateOf(profiles) }

    LaunchedEffect(preFillSubscriptionUrl) {
        if (!preFillSubscriptionUrl.isNullOrBlank()) {
            modals.newSubName = ""
            modals.newSubUrl = preFillSubscriptionUrl
            modals.addDialogFromPrefill = true
            modals.showAddDialog = true
        }
    }

    val localFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) scope.importProfileFile(context, uri, lang, onUpdateProfile)
    }

    ProfilesScreenBody(
        profiles = profiles,
        localProfiles = localProfiles,
        selectedProfileId = selectedProfileId,
        config = ProfilesBodyConfig(
            lang = lang,
            haptic = haptic,
            showBackButton = onBack != null,
            isUpdatingAll = isUpdatingAll
        ),
        callbacks = ProfilesBodyCallbacks(
            onBack = onBack,
            onRefreshAllSubscriptions = onRefreshAllSubscriptions,
            onRefreshSubscription = onRefreshSubscription,
            onReorderProfiles = onReorderProfiles,
            onLocalReorder = { updated -> localProfiles = updated },
            onOpenAddSheet = { modals.showAddSheet = true },
            onSelectProfile = onSelectProfile,
            onOpenActionSheet = { profile -> modals.actionSheetProfile = profile }
        )
    )

    ProfilesDialogsHost(
        modals = modals,
        localProfiles = localProfiles,
        lang = lang,
        haptic = haptic,
        actions = ProfilesDialogActions(
            readClipboardText = readClipboardText,
            onAddSubscription = onAddSubscription,
            onUpdateProfile = onUpdateProfile,
            onRefreshSubscription = onRefreshSubscription,
            onDeleteProfile = onDeleteProfile,
            onNavigateToOverride = onNavigateToOverride,
            onNavigateToQrScan = onNavigateToQrScan,
            onLaunchImportFile = {
                if (onImportLocalFile != null) onImportLocalFile() else localFileLauncher.launch("*/*")
            },
            onPreFillConsumed = onPreFillConsumed,
            onRemoveDeletedLocalProfile = { target ->
                localProfiles = localProfiles.filter { item -> item.id != target.id }
            }
        )
    )
}

@Composable
private fun ProfilesScreenBody(
    profiles: List<ProfileItem>,
    localProfiles: List<ProfileItem>,
    selectedProfileId: String?,
    config: ProfilesBodyConfig,
    callbacks: ProfilesBodyCallbacks
) {
    val listState = rememberLazyListState()
    val nowTick by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000L)
            value = System.currentTimeMillis()
        }
    }
    var profileFileSizes by remember { mutableStateOf(emptyMap<String, Long>()) }
    var draggedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profiles) {
        profileFileSizes = withContext(Dispatchers.IO) {
            profiles.associate { profile -> profile.id to profile.file.length() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        val remoteSubs = profiles.filter { profile -> !profile.url.isNullOrBlank() }
        ProfilesNavBar(
            lang = config.lang,
            showBackButton = config.showBackButton,
            hasRemoteSubs = remoteSubs.isNotEmpty(),
            isUpdatingAll = config.isUpdatingAll,
            haptic = config.haptic,
            onBack = callbacks.onBack,
            onRefreshAll = {
                if (callbacks.onRefreshAllSubscriptions != null) {
                    callbacks.onRefreshAllSubscriptions.invoke()
                } else {
                    remoteSubs.forEach { sub -> callbacks.onRefreshSubscription(sub) }
                }
            },
            onOpenAddSheet = callbacks.onOpenAddSheet
        )
        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            ProfilesListBody(
                listState = listState,
                localProfiles = localProfiles,
                selectedProfileId = selectedProfileId,
                profileFileSizes = profileFileSizes,
                nowTick = nowTick,
                canReorder = profiles.size > 1 && callbacks.onReorderProfiles != null,
                draggedId = draggedId,
                lang = config.lang,
                haptic = config.haptic,
                onDraggedIdChange = { id -> draggedId = id },
                onLocalReorder = callbacks.onLocalReorder,
                onCommitReorder = { reordered -> callbacks.onReorderProfiles?.invoke(reordered) },
                onSelectProfile = callbacks.onSelectProfile,
                onOpenActionSheet = callbacks.onOpenActionSheet
            )
        }
    }
}

@Composable
private fun ProfilesDialogsHost(
    modals: ProfilesModalState,
    localProfiles: List<ProfileItem>,
    lang: String,
    haptic: IosHapticHelper,
    actions: ProfilesDialogActions
) {
    ProfilesAddModals(modals = modals, lang = lang, haptic = haptic, actions = actions)
    ProfilesManageModals(
        modals = modals,
        localProfiles = localProfiles,
        lang = lang,
        haptic = haptic,
        actions = actions
    )
}

@Composable
private fun ProfilesAddModals(
    modals: ProfilesModalState,
    lang: String,
    haptic: IosHapticHelper,
    actions: ProfilesDialogActions
) {
    val closeAddDialog = {
        modals.showAddDialog = false
        if (modals.addDialogFromPrefill) {
            modals.addDialogFromPrefill = false
            actions.onPreFillConsumed()
        }
    }
    if (modals.showAddSheet) {
        AddProfileOptionSheet(
            lang = lang,
            haptic = haptic,
            readClipboardText = actions.readClipboardText,
            onDismiss = { modals.showAddSheet = false },
            onAddSubscription = { prefillUrl ->
                modals.newSubName = ""
                modals.newSubUrl = prefillUrl
                modals.showAddDialog = true
            },
            onScanQr = actions.onNavigateToQrScan,
            onImportFile = actions.onLaunchImportFile
        )
    }
    if (modals.showAddDialog) {
        AddSubscriptionDialog(
            lang = lang,
            name = modals.newSubName,
            url = modals.newSubUrl,
            onNameChange = { value -> modals.newSubName = value },
            onUrlChange = { value -> modals.newSubUrl = value },
            onConfirm = { name, url ->
                closeAddDialog()
                actions.onAddSubscription(name, url)
            },
            onDismiss = closeAddDialog
        )
    }
}

@Composable
private fun ProfilesManageModals(
    modals: ProfilesModalState,
    localProfiles: List<ProfileItem>,
    lang: String,
    haptic: IosHapticHelper,
    actions: ProfilesDialogActions
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    modals.actionSheetProfile?.let { target ->
        ProfileActionSheet(
            target = target,
            lang = lang,
            haptic = haptic,
            onDismiss = { modals.actionSheetProfile = null },
            callbacks = ProfileActionSheetCallbacks(
                onRefreshSubscription = actions.onRefreshSubscription,
                onEdit = { modals.editingProfile = target },
                onNavigateToOverride = actions.onNavigateToOverride,
                onRequestDelete = { modals.deletingProfile = target }
            )
        )
    }
    modals.editingProfile?.let { target ->
        EditProfileDialog(
            target = target,
            localProfiles = localProfiles,
            lang = lang,
            onDismiss = { modals.editingProfile = null },
            onSave = { updated ->
                modals.editingProfile = null
                scope.launch {
                    withContext(Dispatchers.IO) { ConfigManager.updateProfile(context, updated) }
                    actions.onUpdateProfile(updated)
                }
            }
        )
    }
    modals.deletingProfile?.let { target ->
        CupertinoAlertDialog(
            title = AppStrings.get("profile_delete_title", lang),
            message = AppStrings.get("profile_delete_confirm", lang),
            confirmText = AppStrings.get("profile_delete", lang),
            dismissText = AppStrings.get("cancel", lang),
            isDestructive = true,
            onConfirm = {
                modals.deletingProfile = null
                actions.onRemoveDeletedLocalProfile(target)
                actions.onDeleteProfile(target)
            },
            onDismiss = { modals.deletingProfile = null },
            onDismissRequest = { modals.deletingProfile = null }
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ProfilesNavBar(
    lang: String,
    showBackButton: Boolean,
    hasRemoteSubs: Boolean,
    isUpdatingAll: Boolean,
    haptic: IosHapticHelper,
    onBack: (() -> Unit)?,
    onRefreshAll: () -> Unit,
    onOpenAddSheet: () -> Unit
) {
    CupertinoNavBar(
        title = AppStrings.get("nav_profiles", lang),
        showBackButton = showBackButton,
        onBack = onBack,
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasRemoteSubs) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                            .clip(CircleShape)
                            .iosPressable(enabled = !isUpdatingAll) {
                                haptic.selection()
                                onRefreshAll()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isUpdatingAll) {
                            CupertinoActivityIndicator(modifier = Modifier.size(18.dp), color = IosBlue)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = AppStrings.get("profile_update", lang),
                                tint = IosBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                        .clip(CircleShape)
                        .iosPressable {
                            haptic.selection()
                            onOpenAddSheet()
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
        }
    )
}

@Composable
private fun ProfilesEmptyPlaceholder(lang: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 100.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = LoonTextMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = AppStrings.get("profiles_empty_short", lang), color = LoonTextSecondary, style = LoonTextStyles.value())
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ProfilesListBody(
    listState: LazyListState,
    localProfiles: List<ProfileItem>,
    selectedProfileId: String?,
    profileFileSizes: Map<String, Long>,
    nowTick: Long,
    canReorder: Boolean,
    draggedId: String?,
    lang: String,
    haptic: IosHapticHelper,
    onDraggedIdChange: (String?) -> Unit,
    onLocalReorder: (List<ProfileItem>) -> Unit,
    onCommitReorder: (List<ProfileItem>) -> Unit,
    onSelectProfile: (ProfileItem) -> Unit,
    onOpenActionSheet: (ProfileItem) -> Unit
) {
    val itemSpacingPx = with(LocalDensity.current) { LIST_ITEM_SPACING_DP.dp.toPx() }
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) { expireDateFormatter(locale) }

    LazyColumn(
        state = listState,
        userScrollEnabled = draggedId == null,
        modifier = Modifier.fillMaxSize().bounceOverscroll(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(LIST_ITEM_SPACING_DP.dp)
    ) {
        if (localProfiles.isEmpty()) {
            item(key = "profiles_empty_placeholder") { ProfilesEmptyPlaceholder(lang) }
        } else {
            if (localProfiles.size > 1) {
                item(key = "profile_reorder_hint") {
                    Text(
                        text = AppStrings.get("profile_reorder_hint", lang),
                        color = LoonTextMuted,
                        style = LoonTextStyles.caption(),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            items(localProfiles, key = { profile -> profile.id }) { profile ->
                ProfileListCard(
                    profile, profile.id == selectedProfileId, profileFileSizes[profile.id],
                    nowTick, dateFormatter, canReorder, draggedId == profile.id, listState,
                    localProfiles, itemSpacingPx, lang, haptic,
                    onDragStart = { onDraggedIdChange(profile.id) },
                    onDragEnd = { onCommitReorder(localProfiles); onDraggedIdChange(null) },
                    onLocalReorder = onLocalReorder,
                    onSelect = { onSelectProfile(profile) },
                    onMore = { onOpenActionSheet(profile) }
                )
            }
        }
    }
}

private fun formatProfileSizeInfo(profile: ProfileItem, fileSizeBytes: Long?): String = when {
    profile.totalBytes > 0L -> "${formatTraffic(profile.uploadBytes + profile.downloadBytes)} / ${formatTraffic(profile.totalBytes)}"
    fileSizeBytes == null -> "--"
    else -> formatFileSize(fileSizeBytes)
}

@Suppress("LongParameterList")
private fun Modifier.profileReorderDragModifier(
    canReorder: Boolean,
    profileId: String,
    isDragging: Boolean,
    listState: LazyListState,
    localProfiles: List<ProfileItem>,
    itemSpacingPx: Float,
    getDragOffsetY: () -> Float,
    setDragOffsetY: (Float) -> Unit,
    onDragStart: () -> Unit,
    onSettle: () -> Unit,
    onSnapTranslation: () -> Unit,
    onLocalReorder: (List<ProfileItem>) -> Unit
): Modifier = if (!canReorder) Modifier else pointerInput(profileId) {
    detectDragGesturesAfterLongPress(
        onDragStart = { setDragOffsetY(0f); onDragStart(); onSnapTranslation() },
        onDragEnd = onSettle,
        onDragCancel = onSettle,
        onDrag = { change, dragAmount ->
            change.consume()
            if (!isDragging) return@detectDragGesturesAfterLongPress
            val nextY = getDragOffsetY() + dragAmount.y
            setDragOffsetY(nextY)
            reorderProfileListOnDrag(profileId, listState, localProfiles, nextY, itemSpacingPx, onLocalReorder)
                ?.let(setDragOffsetY)
        }
    )
}

@Suppress("LongParameterList")
@Composable
private fun ProfileListCard(
    profile: ProfileItem,
    isSelected: Boolean,
    fileSizeBytes: Long?,
    nowTick: Long,
    dateFormatter: DateTimeFormatter,
    canReorder: Boolean,
    isDragging: Boolean,
    listState: LazyListState,
    localProfiles: List<ProfileItem>,
    itemSpacingPx: Float,
    lang: String,
    haptic: IosHapticHelper,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onLocalReorder: (List<ProfileItem>) -> Unit,
    onSelect: () -> Unit,
    onMore: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val isSub = !profile.url.isNullOrBlank()
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dragTranslationY = remember { Animatable(0f) }
    val cardShape = RoundedCornerShape(LoonRadii.sheet)

    val settleDragCard = {
        val residual = dragOffsetY
        onDragEnd()
        scope.launch {
            dragOffsetY = 0f
            if (reduceMotion) dragTranslationY.snapTo(0f)
            else {
                dragTranslationY.snapTo(residual)
                dragTranslationY.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f))
            }
        }
        Unit
    }
    val infoText = remember(profile, fileSizeBytes) { formatProfileSizeInfo(profile, fileSizeBytes) }
    val timeText = remember(profile.lastUpdated, lang, nowTick) { formatRelativeTime(profile.lastUpdated, lang, nowTick) }
    val (expireDateStr, expireColor) = resolveExpiryInfo(profile, isSub, nowTick, lang, dateFormatter)
    val dragModifier = Modifier.profileReorderDragModifier(
        canReorder, profile.id, isDragging, listState, localProfiles, itemSpacingPx,
        getDragOffsetY = { dragOffsetY }, setDragOffsetY = { next -> dragOffsetY = next },
        onDragStart = onDragStart, onSettle = settleDragCard,
        onSnapTranslation = { scope.launch { dragTranslationY.snapTo(0f) } }, onLocalReorder = onLocalReorder
    )
    val liftModifier = if (isDragging || dragTranslationY.value != 0f) {
        Modifier.zIndex(100f).graphicsLayer {
            translationY = if (isDragging) dragOffsetY else dragTranslationY.value
            val lift = if (isDragging) DRAG_LIFT_SCALE else 1f
            scaleX = lift; scaleY = lift
            shadowElevation = if (isDragging) DRAG_SHADOW_ELEVATION else 0f
            shape = cardShape
        }
    } else Modifier

    Surface(
        shape = cardShape,
        color = LoonCard,
        border = BorderStroke(if (isSelected) 1.dp else 0.5.dp, if (isSelected) IosBlue.copy(alpha = 0.8f) else LoonCardBorder),
        modifier = Modifier.fillMaxWidth().then(liftModifier).then(dragModifier)
            .iosPressable { haptic.selection(); onSelect() }
    ) {
        ProfileCardRow(
            profile.name, isSub, isSelected, expireDateStr, expireColor, infoText, timeText, lang,
            onMore = { haptic.selection(); onMore() }
        )
    }
}

@Composable
private fun ProfileTypeBadge(isSub: Boolean) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(LoonRadii.badge))
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
}

@Suppress("LongParameterList")
@Composable
private fun ProfileCardRow(
    profileName: String,
    isSub: Boolean,
    isSelected: Boolean,
    expireDateStr: String?,
    expireColor: Color,
    infoText: String,
    timeText: String,
    lang: String,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileTypeBadge(isSub)
        Spacer(modifier = Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = profileName,
                    color = LoonTextPrimary,
                    style = LoonTextStyles.body().copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (expireDateStr != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = expireDateStr, color = expireColor, style = LoonTextStyles.subtitle(), maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = infoText, color = LoonTextSecondary, style = LoonTextStyles.subtitle(),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = timeText, color = LoonTextSecondary, style = LoonTextStyles.subtitle(), maxLines = 1)
        Spacer(modifier = Modifier.width(8.dp))
        if (isSelected) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = IosBlue, modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier.sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                .clip(CircleShape).iosPressable(onClick = onMore),
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

private fun reorderProfileListOnDrag(
    profileId: String,
    listState: LazyListState,
    localProfiles: List<ProfileItem>,
    dragOffsetY: Float,
    itemSpacingPx: Float,
    onLocalReorder: (List<ProfileItem>) -> Unit
): Float? {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val fromInfo = visibleItems.firstOrNull { item -> item.key == profileId } ?: return null
    val fromIndex = localProfiles.indexOfFirst { item -> item.id == profileId }
    if (fromIndex == -1) return null
    val draggedCenter = fromInfo.offset + fromInfo.size / 2f + dragOffsetY
    val targetInfo = visibleItems.firstOrNull { candidate ->
        candidate.key != profileId &&
            draggedCenter >= candidate.offset &&
            draggedCenter <= candidate.offset + candidate.size
    } ?: return null
    val toIndex = localProfiles.indexOfFirst { item -> item.id == targetInfo.key }
    if (toIndex == -1 || toIndex == fromIndex) return null

    val reordered = localProfiles.toMutableList()
    val moved = reordered.removeAt(fromIndex)
    reordered.add(toIndex, moved)
    onLocalReorder(reordered)

    val anchorKey = visibleItems.firstOrNull()?.key
    val newOffset = when (anchorKey) {
        profileId -> fromInfo.offset.toFloat()
        targetInfo.key -> targetInfo.offset - fromInfo.size - itemSpacingPx
        else -> targetInfo.offset.toFloat()
    }
    return fromInfo.offset + dragOffsetY - newOffset
}

@Composable
private fun AddProfileOptionSheet(
    lang: String,
    haptic: IosHapticHelper,
    readClipboardText: () -> String,
    onDismiss: () -> Unit,
    onAddSubscription: (String) -> Unit,
    onScanQr: () -> Unit,
    onImportFile: () -> Unit
) {
    CupertinoActionSheet(
        onDismissRequest = onDismiss,
        actions = listOf(
            CupertinoActionSheetItem(
                text = AppStrings.get("profiles_add_subscription", lang),
                onClick = {
                    haptic.selection()
                    onDismiss()
                    val clipText = readClipboardText()
                    val prefill = if (clipText.startsWith("http://") || clipText.startsWith("https://")) {
                        clipText
                    } else {
                        ""
                    }
                    onAddSubscription(prefill)
                }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("qr_scan_title", lang),
                onClick = {
                    haptic.selection()
                    onDismiss()
                    onScanQr()
                }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("profiles_import_file", lang),
                onClick = {
                    haptic.selection()
                    onDismiss()
                    onImportFile()
                }
            )
        ),
        cancelText = AppStrings.get("cancel", lang)
    )
}

@Composable
private fun AddSubscriptionDialog(
    lang: String,
    name: String,
    url: String,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    CupertinoAlertDialog(
        title = AppStrings.get("profiles_add_subscription", lang),
        confirmText = AppStrings.get("action_add", lang),
        dismissText = AppStrings.get("cancel", lang),
        onConfirm = {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isNotBlank()) {
                val resolvedName = name.trim().ifBlank { AppStrings.get("default_sub_name", lang) }
                onConfirm(resolvedName, trimmedUrl)
            } else {
                CupertinoToast.show(AppStrings.get("profile_url_required", lang), isDestructive = true)
            }
        },
        onDismiss = onDismiss,
        onDismissRequest = onDismiss
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        CupertinoTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = AppStrings.get("field_name", lang),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(10.dp))
        CupertinoTextField(
            value = url,
            onValueChange = onUrlChange,
            placeholder = AppStrings.get("field_url", lang),
            singleLine = true
        )
    }
}

private data class ProfileActionSheetCallbacks(
    val onRefreshSubscription: (ProfileItem) -> Unit,
    val onEdit: () -> Unit,
    val onNavigateToOverride: ((ProfileItem) -> Unit)?,
    val onRequestDelete: () -> Unit
)

@Composable
private fun ProfileActionSheet(
    target: ProfileItem,
    lang: String,
    haptic: IosHapticHelper,
    onDismiss: () -> Unit,
    callbacks: ProfileActionSheetCallbacks
) {
    val isSub = !target.url.isNullOrBlank()
    val actions = buildList {
        if (isSub) {
            add(
                CupertinoActionSheetItem(
                    text = AppStrings.get("profile_update", lang),
                    onClick = {
                        haptic.lightTick()
                        onDismiss()
                        callbacks.onRefreshSubscription(target)
                    }
                )
            )
        }
        add(
            CupertinoActionSheetItem(
                text = AppStrings.get("shortcuts_edit", lang),
                onClick = {
                    haptic.selection()
                    onDismiss()
                    callbacks.onEdit()
                }
            )
        )
        callbacks.onNavigateToOverride?.let { navigateOverride ->
            add(
                CupertinoActionSheetItem(
                    text = AppStrings.get("profile_override", lang),
                    onClick = {
                        haptic.selection()
                        onDismiss()
                        navigateOverride(target)
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
                    onDismiss()
                    callbacks.onRequestDelete()
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
private fun EditSubscriptionAutoUpdateFields(
    editUrl: String,
    onEditUrlChange: (String) -> Unit,
    editAutoUpdate: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    editIntervalText: String,
    onIntervalChange: (String) -> Unit,
    lang: String
) {
    Spacer(modifier = Modifier.height(10.dp))
    CupertinoTextField(value = editUrl, onValueChange = onEditUrlChange, placeholder = AppStrings.get("field_url", lang), singleLine = true)
    Spacer(modifier = Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = editAutoUpdate, role = Role.Switch, onValueChange = onAutoUpdateChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = AppStrings.get("profile_auto_update", lang), color = LoonTextPrimary, style = LoonTextStyles.body())
        CupertinoSwitch(checked = editAutoUpdate, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
    if (editAutoUpdate) {
        Spacer(modifier = Modifier.height(10.dp))
        CupertinoTextField(
            value = editIntervalText,
            onValueChange = { raw -> onIntervalChange(raw.filter { ch -> ch.isDigit() }) },
            placeholder = "1440",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = AppStrings.get("profile_interval_unit", lang), color = LoonTextSecondary, style = LoonTextStyles.subtitle())
    }
}

@Composable
private fun EditProfileDialog(
    target: ProfileItem,
    localProfiles: List<ProfileItem>,
    lang: String,
    onDismiss: () -> Unit,
    onSave: (ProfileItem) -> Unit
) {
    val isSub = !target.url.isNullOrBlank()
    var editName by remember(target.id) { mutableStateOf(target.name) }
    var editUrl by remember(target.id) { mutableStateOf(target.url ?: "") }
    var editAutoUpdate by remember(target.id) { mutableStateOf(target.autoUpdate) }
    var editIntervalText by remember(target.id) { mutableStateOf(target.autoUpdateInterval.toString()) }

    CupertinoAlertDialog(
        title = AppStrings.get("shortcuts_edit", lang),
        confirmText = AppStrings.get("action_save", lang),
        dismissText = AppStrings.get("cancel", lang),
        onConfirm = {
            val finalName = editName.trim().ifBlank { target.name }
            val finalUrl = if (isSub) editUrl.trim() else target.url
            if (isSub && finalUrl.isNullOrBlank()) {
                CupertinoToast.show(AppStrings.get("profile_url_required", lang), isDestructive = true)
                return@CupertinoAlertDialog
            }
            val latest = localProfiles.find { item -> item.id == target.id } ?: target
            val interval = editIntervalText.trim().toIntOrNull()
                ?.coerceIn(SubscriptionUpdateWorker.MIN_INTERVAL_MINUTES, 60 * 24 * 30) ?: 1440
            onSave(
                latest.copy(
                    name = finalName,
                    url = finalUrl,
                    autoUpdate = if (isSub) editAutoUpdate else latest.autoUpdate,
                    autoUpdateInterval = if (isSub) interval else latest.autoUpdateInterval
                )
            )
        },
        onDismiss = onDismiss,
        onDismissRequest = onDismiss
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        CupertinoTextField(value = editName, onValueChange = { v -> editName = v }, placeholder = AppStrings.get("field_name", lang), singleLine = true)
        if (isSub) {
            EditSubscriptionAutoUpdateFields(
                editUrl = editUrl,
                onEditUrlChange = { v -> editUrl = v },
                editAutoUpdate = editAutoUpdate,
                onAutoUpdateChange = { enabled -> editAutoUpdate = enabled },
                editIntervalText = editIntervalText,
                onIntervalChange = { v -> editIntervalText = v },
                lang = lang
            )
        }
    }
}

private fun CoroutineScope.importProfileFile(
    context: Context,
    uri: Uri,
    lang: String,
    onUpdateProfile: (ProfileItem) -> Unit
) {
    launch {
        val result = withContext(Dispatchers.IO) {
            ConfigManager.importProfileFromUri(context, uri)
        }
        if (result.isSuccess) {
            val imported = result.getOrThrow()
            onUpdateProfile(imported)
            CupertinoToast.show(AppStrings.get("imported_toast", lang).replace("{name}", imported.name))
        } else {
            CupertinoToast.show(
                AppStrings.get("import_failed", lang).replace("{msg}", result.exceptionOrNull()?.message ?: ""),
                isDestructive = true
            )
        }
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ProfilesScreenPreview() {
    val sampleProfiles = listOf(
        ProfileItem(
            id = "profile_1",
            name = "Sample Subscription",
            url = "https://example.com/subscribe",
            file = File("/dummy/profile1.yaml"),
            lastUpdated = System.currentTimeMillis() - 3600_000L * 2,
            uploadBytes = 1024L * 1024L * 500L,
            downloadBytes = 1024L * 1024L * 1024L * 5L,
            totalBytes = 1024L * 1024L * 1024L * 100L,
            expireTimestamp = System.currentTimeMillis() / 1000L + 86_400L * 5
        ),
        ProfileItem(
            id = "profile_2",
            name = "Sample Local Profile",
            url = null,
            file = File("/dummy/profile2.yaml"),
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

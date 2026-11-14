@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.github.mihomo.android.data.AppInfoManager
import com.github.mihomo.android.data.InstalledApp
import com.github.mihomo.android.data.PerAppProxyMode
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoActionSheet
import com.github.mihomo.android.ui.components.CupertinoActionSheetItem
import com.github.mihomo.android.ui.components.CupertinoActivityIndicator
import com.github.mihomo.android.ui.components.CupertinoDivider
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSearchBar
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.IosHapticHelper
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.cupertinoGroupedItems
import com.github.mihomo.android.ui.components.iosFocusRing
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.components.rememberIosHaptic
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonOnAccent
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonTertiaryBg
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private object AppRoutingIconCache {
    private val cache = LruCache<String, ImageBitmap>(256)

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun put(packageName: String, bitmap: ImageBitmap) {
        cache.put(packageName, bitmap)
    }
}

@Composable
private fun AppRoutingMoreAction(
    lang: String,
    haptic: IosHapticHelper,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
            .clip(CircleShape)
            .iosFocusRing(CircleShape)
            .iosPressable {
                haptic.selection()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MoreHoriz,
            contentDescription = AppStrings.get("more_actions_desc", lang),
            tint = LoonInfoText,
            modifier = Modifier.size(LoonSizes.icon)
        )
    }
}

private data class AppRoutingListData(
    val currentMode: PerAppProxyMode,
    val selectedPackages: Set<String>,
    val appsEmpty: Boolean,
    val displayApps: List<InstalledApp>,
    val isLoading: Boolean,
    val searchQuery: String
)

private data class AppRoutingSheetCallbacks(
    val onToggleShowSystemApps: () -> Unit,
    val onUpdateSelectedPackages: ((Set<String>) -> Unit)?,
    val onTogglePackage: (String) -> Unit,
    val onDismiss: () -> Unit
)

@Composable
fun AppRoutingScreen(
    currentMode: PerAppProxyMode,
    selectedPackages: Set<String>,
    apps: List<InstalledApp>,
    isLoading: Boolean,
    onBack: (() -> Unit)? = null,
    onModeChanged: (PerAppProxyMode) -> Unit,
    onTogglePackage: (String) -> Unit,
    onUpdateSelectedPackages: ((Set<String>) -> Unit)? = null
) {
    val lang = LocalAppLanguage.current
    val haptic = rememberIosHaptic()

    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var showActionSheet by remember { mutableStateOf(false) }

    val displayApps = remember(apps, showSystemApps, searchQuery, selectedPackages) {
        filterAndSortApps(apps, showSystemApps, searchQuery, selectedPackages)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("shortcut_routing", lang),
            showBackButton = onBack != null,
            onBack = onBack,
            actions = {
                if (currentMode != PerAppProxyMode.DISABLED) {
                    AppRoutingMoreAction(lang = lang, haptic = haptic, onClick = { showActionSheet = true })
                }
            }
        )

        LoonResponsiveContent {
            AppRoutingListContent(
                data = AppRoutingListData(
                    currentMode = currentMode,
                    selectedPackages = selectedPackages,
                    appsEmpty = apps.isEmpty(),
                    displayApps = displayApps,
                    isLoading = isLoading,
                    searchQuery = searchQuery
                ),
                lang = lang,
                haptic = haptic,
                onSearchChange = { next -> searchQuery = next },
                onModeChanged = onModeChanged,
                onTogglePackage = onTogglePackage
            )
        }
    }

    if (showActionSheet) {
        AppRoutingActionSheet(
            lang = lang,
            selectedPackages = selectedPackages,
            displayApps = displayApps,
            showSystemApps = showSystemApps,
            haptic = haptic,
            callbacks = AppRoutingSheetCallbacks(
                onToggleShowSystemApps = { showSystemApps = !showSystemApps },
                onUpdateSelectedPackages = onUpdateSelectedPackages,
                onTogglePackage = onTogglePackage,
                onDismiss = { showActionSheet = false }
            )
        )
    }
}

private fun filterAndSortApps(
    apps: List<InstalledApp>,
    showSystemApps: Boolean,
    searchQuery: String,
    selectedPackages: Set<String>
): List<InstalledApp> {
    val base = if (showSystemApps) apps else apps.filter { app -> !app.isSystem }
    val q = searchQuery.trim().lowercase(Locale.ROOT)
    val filtered = if (q.isEmpty()) {
        base
    } else {
        base.filter { app ->
            app.label.lowercase(Locale.ROOT).contains(q) ||
                app.packageName.lowercase(Locale.ROOT).contains(q)
        }
    }
    return filtered.sortedWith(
        compareByDescending<InstalledApp> { app -> selectedPackages.contains(app.packageName) }
            .thenBy { app -> app.label.lowercase(Locale.ROOT) }
    )
}

@Composable
private fun AppRoutingListContent(
    data: AppRoutingListData,
    lang: String,
    haptic: IosHapticHelper,
    onSearchChange: (String) -> Unit,
    onModeChanged: (PerAppProxyMode) -> Unit,
    onTogglePackage: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)
    ) {
        item {
            AppRoutingModeSection(
                currentMode = data.currentMode,
                lang = lang,
                haptic = haptic,
                onModeChanged = onModeChanged
            )
        }

        if (data.currentMode != PerAppProxyMode.DISABLED) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    CupertinoSearchBar(
                        value = data.searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = AppStrings.get("app_search_placeholder_short", lang)
                    )
                }
            }

            if (data.isLoading && data.appsEmpty) {
                item { AppRoutingLoadingPlaceholder(lang = lang) }
            } else if (data.displayApps.isEmpty()) {
                item {
                    AppRoutingEmptyPlaceholder(
                        message = if (data.searchQuery.isNotBlank()) {
                            AppStrings.get("app_no_match_short", lang)
                        } else {
                            AppStrings.get("app_empty", lang)
                        }
                    )
                }
            } else {
                val headerText = if (data.searchQuery.isNotBlank()) {
                    AppStrings.get("search_results", lang).replace("{n}", data.displayApps.size.toString())
                } else {
                    AppStrings.get("app_list_header", lang)
                        .replace("{a}", data.selectedPackages.size.toString())
                        .replace("{b}", data.displayApps.size.toString())
                }

                cupertinoGroupedItems(
                    items = data.displayApps,
                    header = headerText,
                    key = { _, app -> app.packageName },
                    dividerStartIndent = 16.dp
                ) { _, app ->
                    AppItemCell(
                        app = app,
                        isSelected = data.selectedPackages.contains(app.packageName),
                        onClick = {
                            haptic.lightTick()
                            onTogglePackage(app.packageName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRoutingModeSection(
    currentMode: PerAppProxyMode,
    lang: String,
    haptic: IosHapticHelper,
    onModeChanged: (PerAppProxyMode) -> Unit
) {
    CupertinoGroupedSection(
        footer = if (currentMode == PerAppProxyMode.DISABLED) {
            AppStrings.get("app_routing_disabled_footer", lang)
        } else {
            null
        }
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            ModeRow(
                title = AppStrings.get("app_mode_all", lang),
                isSelected = currentMode == PerAppProxyMode.DISABLED,
                onClick = {
                    haptic.selection()
                    onModeChanged(PerAppProxyMode.DISABLED)
                }
            )
            CupertinoDivider()
            ModeRow(
                title = AppStrings.get("app_mode_whitelist", lang),
                isSelected = currentMode == PerAppProxyMode.WHITELIST,
                onClick = {
                    haptic.selection()
                    onModeChanged(PerAppProxyMode.WHITELIST)
                }
            )
            CupertinoDivider()
            ModeRow(
                title = AppStrings.get("app_mode_blacklist", lang),
                isSelected = currentMode == PerAppProxyMode.BLACKLIST,
                onClick = {
                    haptic.selection()
                    onModeChanged(PerAppProxyMode.BLACKLIST)
                }
            )
        }
    }
}

@Composable
private fun AppRoutingLoadingPlaceholder(lang: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CupertinoActivityIndicator(color = LoonInfoText, size = 32.dp)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = AppStrings.get("app_loading", lang),
                color = LoonTextSecondary,
                style = LoonTextStyles.calloutLarge()
            )
        }
    }
}

@Composable
private fun AppRoutingEmptyPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                tint = LoonTextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = LoonTextSecondary,
                style = LoonTextStyles.body()
            )
        }
    }
}

@Composable
private fun AppRoutingActionSheet(
    lang: String,
    selectedPackages: Set<String>,
    displayApps: List<InstalledApp>,
    showSystemApps: Boolean,
    haptic: IosHapticHelper,
    callbacks: AppRoutingSheetCallbacks
) {
    val onDismiss = callbacks.onDismiss
    val onToggleShowSystemApps = callbacks.onToggleShowSystemApps
    val onUpdateSelectedPackages = callbacks.onUpdateSelectedPackages
    val onTogglePackage = callbacks.onTogglePackage
    CupertinoActionSheet(
        onDismissRequest = onDismiss,
        title = AppStrings.get("app_actions_title", lang),
        message = AppStrings.get("app_selected_message", lang).replace("{n}", selectedPackages.size.toString()),
        actions = listOf(
            CupertinoActionSheetItem(
                text = if (showSystemApps) AppStrings.get("app_hide_system", lang) else AppStrings.get("app_show_system", lang),
                onClick = {
                    haptic.selection()
                    onToggleShowSystemApps()
                    onDismiss()
                }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("app_select_all", lang).replace("{n}", displayApps.size.toString()),
                onClick = {
                    haptic.lightTick()
                    onDismiss()
                    val newSet = selectedPackages + displayApps.map { app -> app.packageName }.toSet()
                    if (onUpdateSelectedPackages != null) {
                        onUpdateSelectedPackages(newSet)
                    } else {
                        displayApps.forEach { app ->
                            if (!selectedPackages.contains(app.packageName)) onTogglePackage(app.packageName)
                        }
                    }
                }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("app_invert_selection", lang),
                onClick = {
                    haptic.lightTick()
                    onDismiss()
                    val currentPkgs = displayApps.map { app -> app.packageName }.toSet()
                    val newSet = selectedPackages.toMutableSet()
                    currentPkgs.forEach { pkg -> if (newSet.contains(pkg)) newSet.remove(pkg) else newSet.add(pkg) }
                    if (onUpdateSelectedPackages != null) {
                        onUpdateSelectedPackages(newSet)
                    } else {
                        currentPkgs.forEach { pkg -> onTogglePackage(pkg) }
                    }
                }
            ),
            CupertinoActionSheetItem(
                text = AppStrings.get("app_clear_selection", lang),
                isDestructive = true,
                onClick = {
                    haptic.impact()
                    onDismiss()
                    if (onUpdateSelectedPackages != null) {
                        onUpdateSelectedPackages(emptySet())
                    } else {
                        selectedPackages.forEach { pkg -> onTogglePackage(pkg) }
                    }
                    CupertinoToast.show(AppStrings.get("app_selection_cleared_toast", lang))
                }
            )
        ),
        cancelText = AppStrings.get("cancel", lang)
    )
}

@Composable
private fun ModeRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .iosFocusRing(RoundedCornerShape(LoonRadii.control))
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = LoonTextPrimary,
            style = LoonTextStyles.cellTitle(),
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = LoonInfoText,
                modifier = Modifier.size(LoonSizes.icon)
            )
        }
    }
}

@Composable
private fun AppItemCell(
    app: InstalledApp,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .iosFocusRing(RoundedCornerShape(LoonRadii.control))
            .toggleable(
                value = isSelected,
                role = Role.Checkbox,
                onValueChange = { _ -> onClick() }
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(
            packageName = app.packageName,
            label = app.label,
            modifier = Modifier.size(34.dp),
            fallbackIcon = app.icon
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                color = LoonTextPrimary,
                style = LoonTextStyles.cellTitle(),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = app.packageName,
                color = LoonTextSecondary,
                style = LoonTextStyles.caption(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .then(
                    if (isSelected) {
                        Modifier.background(IosBlue)
                    } else {
                        Modifier.border(1.5.dp, LoonTextMuted.copy(alpha = 0.25f), CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = LoonOnAccent,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    fallbackIcon: Drawable? = null
) {
    val context = LocalContext.current
    var loadedBitmap by remember(packageName) {
        mutableStateOf(AppRoutingIconCache.get(packageName))
    }

    LaunchedEffect(packageName) {
        if (loadedBitmap != null) return@LaunchedEffect
        val resolved = withContext(Dispatchers.Default) {
            if (fallbackIcon != null) {
                try {
                    fallbackIcon.toBitmap(width = 72, height = 72, config = Bitmap.Config.ARGB_8888).asImageBitmap()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        } ?: AppInfoManager.loadAppIcon(context, packageName)
        if (resolved != null) {
            AppRoutingIconCache.put(packageName, resolved)
            loadedBitmap = resolved
        }
    }

    val currentBitmap = loadedBitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(LoonTertiaryBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(1).uppercase(LocalConfiguration.current.locales[0]),
                color = LoonTextPrimary,
                style = LoonTextStyles.subtitleStrong()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppRoutingScreenPreview() {
    val sampleApps = listOf(
        InstalledApp("com.android.chrome", "Chrome", false),
        InstalledApp("com.tencent.mm", "\u5FAE\u4FE1", false),
        InstalledApp("org.telegram.messenger", "Telegram", false),
        InstalledApp("com.google.android.youtube", "YouTube", false),
        InstalledApp("com.android.settings", "\u7CFB\u7EDF\u8BBE\u7F6E", true)
    )

    AppRoutingScreen(
        currentMode = PerAppProxyMode.WHITELIST,
        selectedPackages = setOf("com.android.chrome", "org.telegram.messenger"),
        apps = sampleApps,
        isLoading = false,
        onBack = {},
        onModeChanged = {},
        onTogglePackage = {}
    )
}

package com.github.mihomo.android.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import com.github.mihomo.android.ui.components.CupertinoActivityIndicator
import com.github.mihomo.android.ui.components.CupertinoToast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.github.mihomo.android.data.InstalledApp
import com.github.mihomo.android.data.PerAppProxyMode
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*

@Suppress("UnusedParameter", "UNUSED_PARAMETER")
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

    // Direct filter based on system app toggle & search query
    val displayApps = remember(apps, showSystemApps, searchQuery) {
        val base = if (showSystemApps) apps else apps.filter { !it.isSystem }
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            base
        } else {
            base.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // iOS Navigation Bar: Balanced Left Back, Centered Title, Right Action Icon (•••)
        CupertinoNavBar(
            title = AppStrings.get("shortcut_routing", lang),
            showBackButton = onBack != null,
            onBack = onBack,
            actions = {
                if (currentMode != PerAppProxyMode.DISABLED) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .iosPressable {
                                haptic.selection()
                                showActionSheet = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "更多操作",
                            tint = IosBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )

        // Scroll Container
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)
        ) {
            // Mode Selector: Clean, single-line iOS radio group
            item {
                CupertinoGroupedSection(
                    footer = if (currentMode == PerAppProxyMode.DISABLED) {
                        "当前模式下，设备上所有应用程序的网络流量均经由代理核心转发，无需单独选择应用。"
                    } else null
                ) {
                    ModeRow(
                        title = "全部代理",
                        isSelected = currentMode == PerAppProxyMode.DISABLED,
                        onClick = {
                            haptic.selection()
                            onModeChanged(PerAppProxyMode.DISABLED)
                        }
                    )
                    CupertinoIndentedDivider(startIndent = 16.dp)
                    ModeRow(
                        title = "仅代理所选应用",
                        isSelected = currentMode == PerAppProxyMode.WHITELIST,
                        onClick = {
                            haptic.selection()
                            onModeChanged(PerAppProxyMode.WHITELIST)
                        }
                    )
                    CupertinoIndentedDivider(startIndent = 16.dp)
                    ModeRow(
                        title = "仅绕过所选应用",
                        isSelected = currentMode == PerAppProxyMode.BLACKLIST,
                        onClick = {
                            haptic.selection()
                            onModeChanged(PerAppProxyMode.BLACKLIST)
                        }
                    )
                }
            }

            // Only show search bar and app list when in Whitelist or Blacklist mode
            if (currentMode != PerAppProxyMode.DISABLED) {
                // Fluid Search Bar (scrolls smoothly out of screen when browsing down)
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        CupertinoSearchBar(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = "搜索应用或包名"
                        )
                    }
                }

                // Loading State or App List
                if (isLoading && apps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CupertinoActivityIndicator(
                                    color = IosBlue,
                                    size = 32.dp
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "正在读取已安装应用...",
                                    color = LoonTextSecondary,
                                    fontSize = 13.5.sp
                                )
                            }
                        }
                    }
                } else if (displayApps.isEmpty()) {
                    item {
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
                                    text = if (searchQuery.isNotBlank()) "未找到匹配应用" else "暂无应用",
                                    color = LoonTextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                } else {
                    val headerText = if (searchQuery.isNotBlank()) {
                        "搜索结果 (${displayApps.size})"
                    } else {
                        "应用列表 (${selectedPackages.size} / ${displayApps.size})"
                    }

                    cupertinoGroupedItems(
                        items = displayApps,
                        header = headerText,
                        key = { _, app -> app.packageName },
                        dividerStartIndent = 16.dp
                    ) { _, app ->
                        val isSelected = selectedPackages.contains(app.packageName)

                        AppItemCell(
                            app = app,
                            isSelected = isSelected,
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

    // Advanced Operations Action Sheet
    if (showActionSheet) {
        CupertinoActionSheet(
            onDismissRequest = { showActionSheet = false },
            title = "高级操作",
            message = "当前已勾选 ${selectedPackages.size} 个应用",
            actions = listOf(
                CupertinoActionSheetItem(
                    text = if (showSystemApps) "隐藏系统应用" else "显示系统应用",
                    onClick = {
                        haptic.selection()
                        showSystemApps = !showSystemApps
                        showActionSheet = false
                    }
                ),
                CupertinoActionSheetItem(
                    text = "全选当前列表 (${displayApps.size} 个)",
                    onClick = {
                        haptic.lightTick()
                        showActionSheet = false
                        val newSet = selectedPackages + displayApps.map { it.packageName }.toSet()
                        if (onUpdateSelectedPackages != null) {
                            onUpdateSelectedPackages(newSet)
                        } else {
                            displayApps.forEach { if (!selectedPackages.contains(it.packageName)) onTogglePackage(it.packageName) }
                        }
                    }
                ),
                CupertinoActionSheetItem(
                    text = "反选当前列表",
                    onClick = {
                        haptic.lightTick()
                        showActionSheet = false
                        val currentPkgs = displayApps.map { it.packageName }.toSet()
                        val newSet = selectedPackages.toMutableSet()
                        currentPkgs.forEach { if (newSet.contains(it)) newSet.remove(it) else newSet.add(it) }
                        if (onUpdateSelectedPackages != null) {
                            onUpdateSelectedPackages(newSet)
                        } else {
                            currentPkgs.forEach { onTogglePackage(it) }
                        }
                    }
                ),
                CupertinoActionSheetItem(
                    text = "清空所有已选应用",
                    isDestructive = true,
                    onClick = {
                        haptic.impact()
                        showActionSheet = false
                        if (onUpdateSelectedPackages != null) {
                            onUpdateSelectedPackages(emptySet())
                        } else {
                            selectedPackages.forEach { onTogglePackage(it) }
                        }
                        CupertinoToast.show("已清空已勾选应用")
                    }
                )
            ),
            cancelText = "取消"
        )
    }
}

/**
 * Clean Single-Line iOS Mode Row. No redundant subtitles or essays.
 */
@Composable
private fun ModeRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = LoonTextPrimary,
            fontSize = 15.5.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选择",
                tint = IosBlue,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * App Item Row with 34dp Squircle App Icon, Label, Package Name, and iOS Checkmark.
 */
@Composable
private fun AppItemCell(
    app: InstalledApp,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Icon (34dp standard iOS squircle)
        AppIcon(
            icon = app.icon,
            label = app.label,
            modifier = Modifier.size(34.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        // App Label & Package Name
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = app.label,
                color = LoonTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = app.packageName,
                color = LoonTextMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // iOS Checkbox Circle (22dp circle)
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
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/**
 * Efficient and safe App Icon renderer.
 */
@Composable
private fun AppIcon(
    icon: Drawable?,
    label: String,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(icon) {
        runCatching {
            icon?.toBitmap(width = 80, height = 80, config = Bitmap.Config.ARGB_8888)?.asImageBitmap()
        }.getOrNull()
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(LoonTertiaryBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(1).uppercase(),
                color = LoonTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppRoutingScreenPreview() {
    val sampleApps = listOf(
        InstalledApp("com.android.chrome", "Chrome", false),
        InstalledApp("com.tencent.mm", "微信", false),
        InstalledApp("org.telegram.messenger", "Telegram", false),
        InstalledApp("com.google.android.youtube", "YouTube", false),
        InstalledApp("com.android.settings", "系统设置", true)
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

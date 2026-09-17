package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.InstalledApp
import com.github.mihomo.android.data.PerAppProxyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoutingScreen(
    currentMode: PerAppProxyMode,
    selectedPackages: Set<String>,
    apps: List<InstalledApp>,
    isLoading: Boolean,
    onBack: (() -> Unit)? = null,
    onModeChanged: (PerAppProxyMode) -> Unit,
    onTogglePackage: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    val lang = LocalAppLanguage.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 90.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = AppStrings.get("back", lang), tint = LoonTextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = AppStrings.get("app_routing_title", lang),
                color = LoonTextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Mode Segmented Buttons (Pill Capsule Shape)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LoonCard,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LoonCardBorder)
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                val modes = listOf(
                    PerAppProxyMode.DISABLED to AppStrings.get("per_app_disabled", lang),
                    PerAppProxyMode.WHITELIST to AppStrings.get("per_app_whitelist", lang),
                    PerAppProxyMode.BLACKLIST to AppStrings.get("per_app_blacklist", lang)
                )
                modes.forEach { (mode, label) ->
                    val isSelected = currentMode == mode
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onModeChanged(mode) },
                        color = if (isSelected) LoonBlue else Color.Transparent
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else LoonTextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .padding(vertical = 9.dp)
                                .wrapContentWidth(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (currentMode != PerAppProxyMode.DISABLED) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                placeholder = { Text(AppStrings.get("app_search_placeholder", lang), color = LoonTextMuted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = LoonTextSecondary, modifier = Modifier.size(18.dp)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = LoonCard,
                    unfocusedContainerColor = LoonCard,
                    focusedBorderColor = LoonBlue,
                    unfocusedBorderColor = LoonCardBorder,
                    focusedTextColor = LoonTextPrimary,
                    unfocusedTextColor = LoonTextPrimary
                ),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )

            // Sub-bar: stats & system apps toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppStrings.get("app_selected_count", lang).replace("{n}", selectedPackages.size.toString()),
                    color = LoonTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = AppStrings.get("app_show_system", lang),
                        color = LoonTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it },
                        modifier = Modifier.scale(0.8f),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = LoonBlue,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFE2E4EB)
                        )
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LoonBlue)
                }
            } else {
                val visibleApps = remember(apps, showSystemApps) {
                    if (showSystemApps) apps else apps.filter { !it.isSystem }
                }
                val filtered = remember(visibleApps, searchQuery) {
                    if (searchQuery.isBlank()) visibleApps
                    else visibleApps.filter {
                        it.label.contains(searchQuery, ignoreCase = true) ||
                                it.packageName.contains(searchQuery, ignoreCase = true)
                    }
                }
                val (checkedApps, unselectedApps) = remember(filtered, selectedPackages) {
                    filtered.partition { selectedPackages.contains(it.packageName) }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .bounceOverscroll(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Category 1: Selected (Checked) Apps
                    if (checkedApps.isNotEmpty()) {
                        item(key = "hdr_checked") {
                            Text(
                                text = AppStrings.get("app_proxied_section", lang).replace("{n}", checkedApps.size.toString()),
                                color = LoonBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp, start = 4.dp)
                            )
                        }
                        items(checkedApps, key = { "chk_${it.packageName}" }) { app ->
                            AppItemRow(
                                app = app,
                                isChecked = true,
                                onToggle = { onTogglePackage(app.packageName) }
                            )
                        }
                        item(key = "sp_checked") {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    // Category 2: Other (Unselected) Apps
                    if (unselectedApps.isNotEmpty()) {
                        if (checkedApps.isNotEmpty()) {
                            item(key = "hdr_other") {
                                Text(
                                    text = AppStrings.get("app_unproxied_section", lang).replace("{n}", unselectedApps.size.toString()),
                                    color = LoonTextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp, start = 4.dp)
                                )
                            }
                        }
                        items(unselectedApps, key = { "other_${it.packageName}" }) { app ->
                            AppItemRow(
                                app = app,
                                isChecked = false,
                                onToggle = { onTogglePackage(app.packageName) }
                            )
                        }
                    } else if (checkedApps.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(AppStrings.get("app_no_match", lang), color = LoonTextMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = AppStrings.get("app_routing_disabled_hint", lang),
                    color = LoonTextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

private val iconBitmapCache = androidx.collection.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(250)

@Composable
private fun AppItemRow(
    app: InstalledApp,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (isChecked) LoonBlue else LoonCardBorder, RoundedCornerShape(16.dp))
            .clickable { onToggle() },
        color = LoonCard
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                // Drawing the launcher icon allocates a bitmap; doing it in a `remember` initializer
                // would run that work on the composition thread for every row.
                val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                    initialValue = iconBitmapCache.get(app.packageName),
                    key1 = app.packageName,
                    key2 = app.icon
                ) {
                    if (value == null) {
                        value = withContext(Dispatchers.Default) {
                            iconBitmapCache.get(app.packageName) ?: app.icon?.let { d ->
                                val w = d.intrinsicWidth.coerceAtLeast(48)
                                val h = d.intrinsicHeight.coerceAtLeast(48)
                                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bmp)
                                d.setBounds(0, 0, canvas.width, canvas.height)
                                d.draw(canvas)
                                bmp.asImageBitmap().also { iconBitmapCache.put(app.packageName, it) }
                            }
                        }
                    }
                }

                val iconBitmap = bitmap
                if (iconBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = iconBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(LoonCardBorder),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Android,
                            contentDescription = null,
                            tint = LoonTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        color = LoonTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = app.packageName,
                        color = LoonTextMuted,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = LoonBlue,
                    checkmarkColor = Color.White,
                    uncheckedColor = LoonTextMuted
                )
            )
        }
    }
}

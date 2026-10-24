package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.ProfileParser
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileOverrideScreen(
    profile: ProfileItem,
    onBack: () -> Unit,
    onNavigateToScripts: () -> Unit,
    onProfileUpdated: (ProfileItem) -> Unit
) {
    val lang = LocalAppLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allScripts by remember { mutableStateOf(emptyList<ScriptItem>()) }

    LaunchedEffect(Unit) {
        allScripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }
    val selectedScriptIds = remember(profile) { profile.scriptIds.toMutableStateList() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Cupertino Navigation Bar
        CupertinoNavBar(
            title = AppStrings.get("profile_override_title", lang),
            subtitle = profile.name,
            showBackButton = true,
            onBack = onBack,
            actions = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val updated = profile.copy(
                                scriptEnabled = selectedScriptIds.isNotEmpty(),
                                scriptIds = selectedScriptIds.toList()
                            )
                            ConfigManager.updateProfile(context, updated)
                            ProfileParser.clearCache()
                            onProfileUpdated(updated)
                            scope.launch(Dispatchers.IO) {
                                val settings = SettingsManager(context)
                                val profiles = ConfigManager.getProfiles(context)
                                val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
                                if (activeProfile?.id == profile.id && ClashCore.isCoreLoaded) {
                                    ClashCore.reloadActiveConfig(context)
                                }
                            }
                            CupertinoToast.show(AppStrings.get("override_saved", lang))
                            onBack()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "保存",
                        color = IosBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .bounceOverscroll(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CupertinoGroupedSection(
                    header = "配置信息",
                    footer = AppStrings.get("override_desc", lang)
                ) {
                    CupertinoCell(
                        title = profile.name,
                        value = if (profile.url != null) AppStrings.get("override_remote", lang) else AppStrings.get("override_local", lang),
                        icon = Icons.Default.FolderZip,
                        iconBg = Color(0xFFAF52DE),
                        showChevron = false
                    )
                    CupertinoDivider()
                    CupertinoCell(
                        title = AppStrings.get("override_go_scripts", lang),
                        subtitle = AppStrings.get("override_scripts_hint", lang),
                        icon = Icons.Default.Code,
                        iconBg = Color(0xFF5856D6),
                        showChevron = true,
                        onClick = onNavigateToScripts
                    )
                }
            }

            item {
                CupertinoGroupedSection(
                    header = AppStrings.get("override_select_scripts", lang)
                ) {
                    if (allScripts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "暂无可用脚本",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = LoonTextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "请点击上方「${AppStrings.get("override_go_scripts", lang)}」创建或导入脚本",
                                    fontSize = 12.sp,
                                    color = LoonTextSecondary
                                )
                            }
                        }
                    } else {
                        allScripts.forEachIndexed { idx, script ->
                            val isChecked = selectedScriptIds.contains(script.id)
                            CupertinoCell(
                                title = script.name,
                                subtitle = if (script.url.isNotBlank()) "远程脚本" else "本地脚本",
                                icon = Icons.Default.Javascript,
                                iconBg = if (isChecked) IosBlue else Color(0xFF8E8E93),
                                trailing = {
                                    CupertinoSwitch(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedScriptIds.add(script.id)
                                            else selectedScriptIds.remove(script.id)
                                        }
                                    )
                                },
                                onClick = {
                                    if (isChecked) selectedScriptIds.remove(script.id)
                                    else selectedScriptIds.add(script.id)
                                }
                            )
                            if (idx < allScripts.size - 1) {
                                CupertinoDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun ProfileOverrideScreenPreview() {
    val sampleProfile = ProfileItem(
        id = "sample_1",
        name = "Sample Subscription",
        url = "https://subscribe.example.com/clash",
        file = java.io.File("/dummy/path.yaml"),
        scriptEnabled = true,
        scriptIds = listOf("script_1")
    )
    MihomoTheme {
        ProfileOverrideScreen(
            profile = sampleProfile,
            onBack = {},
            onNavigateToScripts = {},
            onProfileUpdated = {}
        )
    }
}

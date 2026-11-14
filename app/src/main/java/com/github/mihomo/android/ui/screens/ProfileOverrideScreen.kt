@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.ProfileParser
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoCell
import com.github.mihomo.android.ui.components.CupertinoDivider
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoSwitchCell
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.iosPressable
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosGray
import com.github.mihomo.android.ui.theme.IosIndigo
import com.github.mihomo.android.ui.theme.IosPurple
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.MihomoTheme
import com.github.mihomo.android.ui.theme.RoundedCornerShape
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
    val selectedScriptIds = remember(profile.id, profile.scriptIds) {
        profile.scriptIds.toMutableStateList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("profile_override_title", lang),
            subtitle = profile.name,
            showBackButton = true,
            onBack = onBack,
            actions = {
                Box(
                    modifier = Modifier
                        .sizeIn(
                            minWidth = LoonSizes.touchTargetMin,
                            minHeight = LoonSizes.touchTargetMin
                        )
                        .clip(RoundedCornerShape(LoonRadii.badge))
                        .iosPressable {
                            val updated = profile.copy(
                                scriptEnabled = selectedScriptIds.isNotEmpty(),
                                scriptIds = selectedScriptIds.toList()
                            )
                            scope.launch {
                                val reloadOk = saveProfileScriptsAndReload(context, profile.id, updated)
                                if (!reloadOk) {
                                    CupertinoToast.show(
                                        AppStrings.get("core_reload_failed", lang),
                                        isDestructive = true
                                    )
                                }
                                // Pop first so the exit transition plays on stable content; the
                                // list reload below would otherwise rebuild the screen mid-animation.
                                onBack()
                                onProfileUpdated(updated)
                                CupertinoToast.show(AppStrings.get("override_saved", lang))
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = AppStrings.get("action_save", lang),
                        color = LoonInfoText,
                        style = LoonTextStyles.actionLabelStrong()
                    )
                }
            }
        )

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            ProfileOverrideList(
                profile = profile,
                lang = lang,
                allScripts = allScripts,
                selectedScriptIds = selectedScriptIds,
                onNavigateToScripts = onNavigateToScripts
            )
        }
    }
}

@Composable
private fun ProfileOverrideList(
    profile: ProfileItem,
    lang: String,
    allScripts: List<ScriptItem>,
    selectedScriptIds: MutableList<String>,
    onNavigateToScripts: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CupertinoGroupedSection(header = AppStrings.get("override_profile_info", lang)) {
                CupertinoCell(
                    title = profile.name,
                    value = if (profile.url != null) {
                        AppStrings.get("override_remote", lang)
                    } else {
                        AppStrings.get("override_local", lang)
                    },
                    icon = Icons.Default.FolderZip,
                    iconBg = IosPurple,
                    showChevron = false
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("override_go_scripts", lang),
                    icon = Icons.Default.Code,
                    iconBg = IosIndigo,
                    showChevron = true,
                    onClick = onNavigateToScripts
                )
            }
        }

        item {
            CupertinoGroupedSection(header = AppStrings.get("override_select_scripts", lang)) {
                if (allScripts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = AppStrings.get("override_no_scripts_title", lang),
                            style = LoonTextStyles.body().copy(fontWeight = FontWeight.Medium),
                            color = LoonTextPrimary
                        )
                    }
                } else {
                    allScripts.forEachIndexed { idx, script ->
                        val isChecked = selectedScriptIds.contains(script.id)
                        CupertinoSwitchCell(
                            title = script.name,
                            checked = isChecked,
                            icon = Icons.Default.Javascript,
                            iconBg = if (isChecked) IosBlue else IosGray,
                            onCheckedChange = { checked ->
                                if (checked && !selectedScriptIds.contains(script.id)) {
                                    selectedScriptIds.add(script.id)
                                } else if (!checked) {
                                    selectedScriptIds.remove(script.id)
                                }
                            }
                        )
                        if (idx < allScripts.lastIndex) {
                            CupertinoDivider()
                        }
                    }
                }
            }
        }
    }
}

private suspend fun saveProfileScriptsAndReload(
    context: android.content.Context,
    profileId: String,
    updated: ProfileItem
): Boolean = withContext(Dispatchers.IO) {
    ConfigManager.updateProfile(context, updated)
    ProfileParser.clearCache()
    val settings = SettingsManager(context)
    val profiles = ConfigManager.getProfiles(context)
    val activeProfile = profiles.firstOrNull { candidate ->
        candidate.id == settings.selectedProfileId
    } ?: profiles.firstOrNull()
    if (activeProfile?.id == profileId && ClashCore.isCoreLoaded) {
        ClashCore.reloadActiveConfig(context)
    } else {
        true
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

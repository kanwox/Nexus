@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.data.UpdateChecker
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoCell
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoIndentedDivider
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosCyan
import com.github.mihomo.android.ui.theme.IosGray
import com.github.mihomo.android.ui.theme.IosGreen
import com.github.mihomo.android.ui.theme.IosIndigo
import com.github.mihomo.android.ui.theme.IosOrange
import com.github.mihomo.android.ui.theme.IosPink
import com.github.mihomo.android.ui.theme.IosPurple
import com.github.mihomo.android.ui.theme.IosTeal
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.MihomoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main settings page: three sections of navigation cells only. Every editable detail lives
 * in a second-level page (VPN & Tunnel, DNS, About, ...) so this list stays short and
 * scannable.
 */
@Composable
fun SettingsScreen(
    scrollState: ScrollState,
    tunStack: String,
    fakeIpEnabled: Boolean,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onNavigateToScripts: () -> Unit = {},
    onNavigateToProfiles: () -> Unit,
    onNavigateToAppRouting: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToDnsSettings: () -> Unit = {},
    onNavigateToCore: () -> Unit = {},
    onNavigateToVpnTunnel: () -> Unit = {},
    onNavigateToNetworkShare: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current

    var scripts by remember { mutableStateOf(emptyList<ScriptItem>()) }
    val installedVersion = remember { UpdateChecker.installedVersion(context) }
    val availableUpdate by UpdateChecker.availableUpdate.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        scripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 8.dp)
                .heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = AppStrings.get("settings_title", lang),
                color = LoonTextPrimary,
                style = LoonTextStyles.largeTitleBold()
            )
        }

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .bounceOverscroll()
                    .verticalScroll(scrollState)
                    .padding(bottom = 120.dp)
            ) {
                SettingsCoreSection(
                    lang = lang,
                    fakeIpEnabled = fakeIpEnabled,
                    scriptCount = scripts.size,
                    navigation = SettingsCoreNavigation(
                        onNavigateToCore = onNavigateToCore,
                        onNavigateToAppRouting = onNavigateToAppRouting,
                        onNavigateToDnsSettings = onNavigateToDnsSettings,
                        onNavigateToProfiles = onNavigateToProfiles,
                        onNavigateToScripts = onNavigateToScripts,
                        onNavigateToLogs = onNavigateToLogs
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                SettingsNetworkSection(
                    lang = lang,
                    tunStack = tunStack,
                    onNavigateToVpnTunnel = onNavigateToVpnTunnel,
                    onNavigateToNetworkShare = onNavigateToNetworkShare
                )

                Spacer(modifier = Modifier.height(20.dp))

                SettingsGeneralSection(
                    lang = lang,
                    themeMode = themeMode,
                    installedVersion = installedVersion,
                    discoveredUpdate = availableUpdate,
                    onNavigateToAppearance = onNavigateToAppearance,
                    onNavigateToAbout = onNavigateToAbout
                )
            }
        }
    }
}

private class SettingsCoreNavigation(
    val onNavigateToCore: () -> Unit,
    val onNavigateToAppRouting: () -> Unit,
    val onNavigateToDnsSettings: () -> Unit,
    val onNavigateToProfiles: () -> Unit,
    val onNavigateToScripts: () -> Unit,
    val onNavigateToLogs: () -> Unit
)

@Composable
private fun SettingsCoreSection(
    lang: String,
    fakeIpEnabled: Boolean,
    scriptCount: Int,
    navigation: SettingsCoreNavigation
) {
    CupertinoGroupedSection(header = AppStrings.get("settings_section_core_services", lang)) {
        CupertinoCell(
            title = AppStrings.get("settings_core_providers", lang),
            icon = Icons.Default.Dns,
            iconBg = IosGray,
            showChevron = true,
            onClick = navigation.onNavigateToCore
        )
        CupertinoIndentedDivider()
        CupertinoCell(
            title = AppStrings.get("settings_app_routing", lang),
            icon = Icons.Default.Apps,
            iconBg = IosOrange,
            showChevron = true,
            onClick = navigation.onNavigateToAppRouting
        )
        CupertinoIndentedDivider()
        CupertinoCell(
            title = AppStrings.get("settings_dns_section", lang),
            value = if (fakeIpEnabled) "Fake-IP" else "Redir-Host",
            icon = Icons.Default.Public,
            iconBg = IosTeal,
            showChevron = true,
            onClick = navigation.onNavigateToDnsSettings
        )
        CupertinoIndentedDivider()
        CupertinoCell(
            title = AppStrings.get("settings_profiles_subs_plain", lang),
            icon = Icons.Default.FolderZip,
            iconBg = IosPurple,
            showChevron = true,
            onClick = navigation.onNavigateToProfiles
        )
        CupertinoIndentedDivider()
        CupertinoCell(
            title = AppStrings.get("settings_scripts_center", lang),
            value = AppStrings.get("script_count", lang).replace("{n}", scriptCount.toString()),
            icon = Icons.Default.Code,
            iconBg = IosIndigo,
            showChevron = true,
            onClick = navigation.onNavigateToScripts
        )
        CupertinoIndentedDivider()
        CupertinoCell(
            title = AppStrings.get("shortcut_logs", lang),
            icon = Icons.Default.Terminal,
            iconBg = IosGray,
            showChevron = true,
            onClick = navigation.onNavigateToLogs
        )
    }
}

@Composable
private fun SettingsNetworkSection(
    lang: String,
    tunStack: String,
    onNavigateToVpnTunnel: () -> Unit,
    onNavigateToNetworkShare: () -> Unit
) {
    CupertinoGroupedSection(header = AppStrings.get("settings_section_network", lang)) {
        CupertinoCell(
            title = AppStrings.get("settings_vpn_tunnel", lang),
            value = if (tunStack.equals("gvisor", ignoreCase = true)) "gVisor" else "Mips",
            icon = Icons.Default.VpnKey,
            iconBg = IosBlue,
            showChevron = true,
            onClick = onNavigateToVpnTunnel
        )
        CupertinoIndentedDivider()
        CupertinoCell(
            title = AppStrings.get("settings_lan_share_plain", lang),
            icon = Icons.Default.WifiTethering,
            iconBg = IosCyan,
            showChevron = true,
            onClick = onNavigateToNetworkShare
        )
    }
}

@Composable
private fun SettingsGeneralSection(
    lang: String,
    themeMode: ThemeMode,
    installedVersion: String,
    discoveredUpdate: UpdateChecker.UpdateInfo?,
    onNavigateToAppearance: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    CupertinoGroupedSection(header = AppStrings.get("settings_section_general", lang)) {
        CupertinoCell(
            title = AppStrings.get("settings_appearance", lang),
            value = when (themeMode) {
                ThemeMode.SYSTEM -> AppStrings.get("theme_system", lang)
                ThemeMode.LIGHT -> AppStrings.get("theme_light_short", lang)
                ThemeMode.DARK -> AppStrings.get("theme_dark_short", lang)
            },
            icon = Icons.Default.Palette,
            iconBg = IosPink,
            showChevron = true,
            onClick = onNavigateToAppearance
        )
        CupertinoIndentedDivider()
        CupertinoCell(
            title = AppStrings.get("settings_about_page", lang),
            value = if (discoveredUpdate != null) {
                AppStrings.get("settings_update_available_short", lang)
                    .replace("{v}", discoveredUpdate.tagName)
            } else {
                "v$installedVersion"
            },
            icon = Icons.Default.Security,
            iconBg = IosGreen,
            showChevron = true,
            trailing = if (discoveredUpdate != null) {
                {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(IosBlue)
                    )
                }
            } else {
                null
            },
            onClick = onNavigateToAbout
        )
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun SettingsScreenPreview() {
    MihomoTheme {
        SettingsScreen(
            scrollState = rememberScrollState(),
            tunStack = "mips",
            fakeIpEnabled = true,
            themeMode = ThemeMode.SYSTEM,
            onNavigateToProfiles = {},
            onNavigateToAppRouting = {},
            onNavigateToLogs = {}
        )
    }
}

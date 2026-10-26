package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.data.UpdateChecker
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = AppStrings.get("settings_title", lang),
                color = LoonTextPrimary,
                style = LoonTextStyles.largeTitleBold()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .bounceOverscroll()
                .verticalScroll(scrollState)
                .padding(bottom = 120.dp)
        ) {
            CupertinoGroupedSection(header = AppStrings.get("settings_section_core_services", lang)) {
                CupertinoCell(
                    title = AppStrings.get("settings_core_providers", lang),
                    icon = Icons.Default.Dns,
                    iconBg = IosGray,
                    showChevron = true,
                    onClick = onNavigateToCore
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_app_routing", lang),
                    icon = Icons.Default.Apps,
                    iconBg = IosOrange,
                    showChevron = true,
                    onClick = onNavigateToAppRouting
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_dns_section", lang),
                    value = if (fakeIpEnabled) "Fake-IP" else "Redir-Host",
                    icon = Icons.Default.Public,
                    iconBg = IosTeal,
                    showChevron = true,
                    onClick = onNavigateToDnsSettings
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_profiles_subs_plain", lang),
                    icon = Icons.Default.FolderZip,
                    iconBg = IosPurple,
                    showChevron = true,
                    onClick = onNavigateToProfiles
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_scripts_center", lang),
                    value = AppStrings.get("script_count", lang).replace("{n}", scripts.size.toString()),
                    icon = Icons.Default.Code,
                    iconBg = IosIndigo,
                    showChevron = true,
                    onClick = onNavigateToScripts
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("shortcut_logs", lang),
                    icon = Icons.Default.Terminal,
                    iconBg = IosGray,
                    showChevron = true,
                    onClick = onNavigateToLogs
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            CupertinoGroupedSection(header = AppStrings.get("settings_section_network", lang)) {
                CupertinoCell(
                    title = AppStrings.get("settings_vpn_tunnel", lang),
                    value = when (tunStack.lowercase()) {
                        "mixed" -> "Mixed"
                        "gvisor" -> "gVisor"
                        else -> "System"
                    },
                    icon = Icons.Default.VpnKey,
                    iconBg = IosBlue,
                    showChevron = true,
                    onClick = onNavigateToVpnTunnel
                )
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_lan_share_plain", lang),
                    icon = Icons.Default.WifiTethering,
                    iconBg = IosCyan,
                    showChevron = true,
                    onClick = onNavigateToNetworkShare
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

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
                CupertinoDivider()
                CupertinoCell(
                    title = AppStrings.get("settings_about_page", lang),
                    value = "v$installedVersion",
                    icon = Icons.Default.Security,
                    iconBg = IosGreen,
                    showChevron = true,
                    onClick = onNavigateToAbout
                )
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun SettingsScreenPreview() {
    MihomoTheme {
        SettingsScreen(
            scrollState = androidx.compose.foundation.rememberScrollState(),
            tunStack = "mixed",
            fakeIpEnabled = true,
            themeMode = ThemeMode.SYSTEM,
            onNavigateToProfiles = {},
            onNavigateToAppRouting = {},
            onNavigateToLogs = {}
        )
    }
}

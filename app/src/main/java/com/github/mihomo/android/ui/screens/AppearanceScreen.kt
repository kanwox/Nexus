package com.github.mihomo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*

@Composable
fun AppearanceScreen(
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLanguageChanged: (String) -> Unit,
    showGroupIcons: Boolean,
    onShowGroupIconsChanged: (Boolean) -> Unit,
    nodeColumns: Int,
    onNodeColumnsChanged: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val lang = LocalAppLanguage.current
    var currentLanguage by remember { mutableStateOf(settingsManager.appLanguage) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Cupertino Navigation Bar
        CupertinoNavBar(
            title = AppStrings.get("settings_appearance", lang),
            showBackButton = true,
            onBack = onBack
        )

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .bounceOverscroll()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Section 1: 主题模式
            val themes = listOf(
                ThemeMode.SYSTEM to AppStrings.get("theme_system", lang),
                ThemeMode.LIGHT to AppStrings.get("theme_light", lang),
                ThemeMode.DARK to AppStrings.get("theme_dark", lang)
            )

            CupertinoGroupedSection(
                title = AppStrings.get("settings_theme", lang)
            ) {
                themes.forEachIndexed { index, (mode, label) ->
                    val isSelected = themeMode == mode
                    CupertinoCell(
                        title = label,
                        trailing = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = IosBlue,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        },
                        onClick = { onThemeModeChanged(mode) }
                    )
                    if (index < themes.size - 1) {
                        CupertinoIndentedDivider(startIndent = 16.dp)
                    }
                }
            }

            // Section 2: 界面语言
            val langs = listOf(
                "SYSTEM" to AppStrings.get("theme_system", lang),
                "zh" to "简体中文",
                "en" to "English",
                "ru" to "Русский"
            )

            CupertinoGroupedSection(
                title = AppStrings.get("settings_language", lang)
            ) {
                langs.forEachIndexed { index, (code, label) ->
                    val isSelected = currentLanguage == code
                    CupertinoCell(
                        title = label,
                        trailing = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = IosBlue,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        },
                        onClick = {
                            currentLanguage = code
                            settingsManager.appLanguage = code
                            onLanguageChanged(code)
                            Toast.makeText(
                                context,
                                "${AppStrings.get("language_updated", code)}: $label",
                                Toast.LENGTH_SHORT
                            ).show()
                            @Suppress("DEPRECATION")
                            runCatching {
                                val locale = if (code == "SYSTEM") java.util.Locale.getDefault() else java.util.Locale.forLanguageTag(code)
                                java.util.Locale.setDefault(locale)
                                val config = context.resources.configuration
                                config.setLocale(locale)
                                context.resources.updateConfiguration(config, context.resources.displayMetrics)
                            }
                        }
                    )
                    if (index < langs.size - 1) {
                        CupertinoIndentedDivider(startIndent = 16.dp)
                    }
                }
            }

            // Section 3: 策略与节点排版
            CupertinoGroupedSection(
                title = "策略与节点排版"
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "默认每行节点数",
                        fontSize = 15.5.sp,
                        color = LoonTextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CupertinoSegmentedControl(
                        items = listOf(1, 2, 3),
                        selectedItem = nodeColumns,
                        onItemSelected = onNodeColumnsChanged,
                        labelProvider = { col ->
                            when (col) {
                                1 -> "单列"
                                2 -> "双列"
                                else -> "三列"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                CupertinoIndentedDivider(startIndent = 16.dp)

                CupertinoCell(
                    title = "显示策略组远程图标",
                    subtitle = "在节点列表中展示代理协议或服务商的专用图标",
                    trailing = {
                        CupertinoSwitch(
                            checked = showGroupIcons,
                            onCheckedChange = onShowGroupIconsChanged
                        )
                    }
                )
            }
        }
    }
}

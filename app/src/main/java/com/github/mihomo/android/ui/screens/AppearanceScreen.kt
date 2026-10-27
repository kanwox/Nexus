package com.github.mihomo.android.ui.screens

import com.github.mihomo.android.ui.components.CupertinoToast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.data.AppFont
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.ThemeMode
import androidx.compose.ui.tooling.preview.Preview
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*

@Composable
fun AppearanceScreen(
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    appFont: AppFont = LocalAppFont.current,
    onAppFontChanged: (AppFont) -> Unit = {},
    appLanguage: String = LocalAppLanguage.current,
    onAppLanguageChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val lang = LocalAppLanguage.current
    var currentFont by remember(appFont) { mutableStateOf(appFont) }

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

            // Section 2: 界面字体 (System Default vs SF Pro / PingFang SC)
            val fonts = listOf(
                AppFont.SYSTEM to AppStrings.get("font_system", lang),
                AppFont.IOS to AppStrings.get("font_ios", lang)
            )

            CupertinoGroupedSection(
                title = AppStrings.get("settings_font", lang)
            ) {
                fonts.forEachIndexed { index, (fontOption, label) ->
                    val isSelected = currentFont == fontOption
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
                            currentFont = fontOption
                            settingsManager.appFont = fontOption
                            onAppFontChanged(fontOption)
                            CupertinoToast.show(
                                AppStrings.get("font_updated", lang).replace("{font}", label)
                            )
                        }
                    )
                    if (index < fonts.size - 1) {
                        CupertinoIndentedDivider(startIndent = 16.dp)
                    }
                }
            }

            // Section 3: 界面语言 (System / Chinese / English / Russian)
            // The language names are endonyms (not translated); only the "follow system"
            // entry is localized (UI-I18N-01).
            val languages = listOf(
                "SYSTEM" to AppStrings.get("language_follow_system", lang),
                "zh" to "简体中文",
                "en" to "English",
                "ru" to "Русский"
            )

            CupertinoGroupedSection(
                title = AppStrings.get("settings_language", lang)
            ) {
                languages.forEachIndexed { index, (code, label) ->
                    val isSelected = appLanguage == code
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
                            settingsManager.appLanguage = code
                            onAppLanguageChanged(code)
                            CupertinoToast.show(AppStrings.get("language_updated", lang))
                        }
                    )
                    if (index < languages.size - 1) {
                        CupertinoIndentedDivider(startIndent = 16.dp)
                    }
                }
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true, showSystemUi = true)
@Composable
fun AppearanceScreenPreview() {
    MihomoTheme {
        AppearanceScreen(
            themeMode = ThemeMode.LIGHT,
            onThemeModeChanged = {},
            onBack = {}
        )
    }
}

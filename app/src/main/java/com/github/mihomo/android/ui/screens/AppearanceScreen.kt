package com.github.mihomo.android.ui.screens

import android.widget.Toast
import com.github.mihomo.android.ui.components.CupertinoToast
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
import androidx.compose.ui.tooling.preview.Preview
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*

@Composable
fun AppearanceScreen(
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLanguageChanged: (String) -> Unit,
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
                            CupertinoToast.show("${AppStrings.get("language_updated", code)}: $label")
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
            onLanguageChanged = {},
            onBack = {}
        )
    }
}

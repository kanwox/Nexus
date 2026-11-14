package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.data.AppFont
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoCell
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoIndentedDivider
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.LocalAppFont
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSpacing
import com.github.mihomo.android.ui.theme.MihomoTheme

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

    val themes = listOf(
        ThemeMode.SYSTEM to AppStrings.get("theme_system", lang),
        ThemeMode.LIGHT to AppStrings.get("theme_light", lang),
        ThemeMode.DARK to AppStrings.get("theme_dark", lang)
    )
    val fonts = listOf(
        AppFont.SYSTEM to AppStrings.get("font_system", lang),
        AppFont.IOS to AppStrings.get("font_ios", lang)
    )
    // Language names are endonyms; only the "follow system" entry is localized (UI-I18N-01).
    val languages = listOf(
        "SYSTEM" to AppStrings.get("language_follow_system", lang),
        "zh" to "简体中文",
        "en" to "English",
        "ru" to "Русский"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("settings_appearance", lang),
            showBackButton = true,
            onBack = onBack
        )

        LoonResponsiveContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .bounceOverscroll()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = LoonSpacing.md),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                AppearanceOptionSection(
                    title = AppStrings.get("settings_theme", lang),
                    options = themes,
                    selectedValue = themeMode,
                    onSelect = { mode, _ -> onThemeModeChanged(mode) }
                )

                AppearanceOptionSection(
                    title = AppStrings.get("settings_font", lang),
                    options = fonts,
                    selectedValue = currentFont,
                    onSelect = { fontOption, label ->
                        currentFont = fontOption
                        settingsManager.appFont = fontOption
                        onAppFontChanged(fontOption)
                        CupertinoToast.show(
                            AppStrings.get("font_updated", lang).replace("{font}", label)
                        )
                    }
                )

                AppearanceOptionSection(
                    title = AppStrings.get("settings_language", lang),
                    options = languages,
                    selectedValue = appLanguage,
                    onSelect = { code, _ ->
                        settingsManager.appLanguage = code
                        onAppLanguageChanged(code)
                        CupertinoToast.show(AppStrings.get("language_updated", lang))
                    }
                )
            }
        }
    }
}

@Composable
private fun <T> AppearanceOptionSection(
    title: String,
    options: List<Pair<T, String>>,
    selectedValue: T,
    onSelect: (T, String) -> Unit
) {
    CupertinoGroupedSection(title = title) {
        Column(modifier = Modifier.selectableGroup()) {
            options.forEachIndexed { index, (value, label) ->
                val isSelected = selectedValue == value
                CupertinoCell(
                    title = label,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        selected = isSelected
                        role = Role.RadioButton
                    },
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
                    onClick = { onSelect(value, label) }
                )
                if (index < options.lastIndex) {
                    CupertinoIndentedDivider(startIndent = 16.dp)
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

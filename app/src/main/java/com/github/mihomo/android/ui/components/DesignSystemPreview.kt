package com.github.mihomo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.data.AppFont
import com.github.mihomo.android.data.ThemeMode
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.MihomoTheme

/**
 * Design-system gallery. Keep one preview per visual axis so a reviewer can spot drift in
 * Light/Dark, system/iOS font, language and large-font rendering without launching the app.
 */
@Preview(name = "Cells · Light · zh", showBackground = true, widthDp = 360)
@Composable
fun CellsLightZhPreview() {
    MihomoTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(LocalAppLanguage provides "zh") {
            PreviewGallery()
        }
    }
}

@Preview(name = "Cells · Dark · en · iOS font", showBackground = true, widthDp = 360)
@Composable
fun CellsDarkEnPreview() {
    MihomoTheme(themeMode = ThemeMode.DARK, appFont = AppFont.IOS) {
        CompositionLocalProvider(LocalAppLanguage provides "en") {
            PreviewGallery()
        }
    }
}

@Preview(
    name = "Cells · Large font 1.3x",
    showBackground = true,
    widthDp = 360,
    fontScale = 1.3f
)
@Composable
fun CellsLargeFontPreview() {
    MihomoTheme(themeMode = ThemeMode.LIGHT, appFont = AppFont.SYSTEM) {
        CompositionLocalProvider(LocalAppLanguage provides "ru") {
            PreviewGallery()
        }
    }
}

@Composable
private fun PreviewGallery() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CupertinoGroupedSection(
            header = "Network & core",
            footer = "Secondary text uses the accessible token; muted is placeholder-only."
        ) {
            CupertinoCell(
                title = "LAN sharing",
                subtitle = "Expose the mixed port to other devices",
                value = "On",
                showChevron = true,
                onClick = {}
            )
            CupertinoIndentedDivider()
            CupertinoCell(
                title = "Kill switch",
                subtitle = "Block traffic while the tunnel is down",
                trailing = {
                    CupertinoSwitch(checked = true, onCheckedChange = {})
                }
            )
            CupertinoIndentedDivider()
            CupertinoCell(
                title = "Latency",
                subtitle = "1.2 s ago",
                value = "42 ms"
            )
        }

        CupertinoGroupedSection(header = "Controls") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CupertinoSegmentedControl(
                    items = listOf("Rule", "Global", "Direct"),
                    selectedItem = "Rule",
                    labelProvider = { it },
                    onItemSelected = {}
                )
                CupertinoButton(text = "Save", onClick = {})
                CupertinoButton(
                    text = "Delete",
                    style = CupertinoButtonStyle.Destructive,
                    onClick = {}
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

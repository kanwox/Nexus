package com.github.mihomo.android.ui

import com.github.mihomo.android.i18n.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppStringsConsistencyTest {

    private val placeholderRegex = Regex("""\{[a-zA-Z0-9_]+\}|%[sd]|%\d+\$[sd]""")

    private fun extractPlaceholders(value: String): List<String> =
        placeholderRegex.findAll(value).map { match -> match.value }.sorted().toList()

    @Test
    fun `zh en and ru tables define identical non-blank key sets`() {
        val zhKeys = AppStrings.zhAll.keys
        val enKeys = AppStrings.enAll.keys
        val ruKeys = AppStrings.ruAll.keys

        assertEquals(zhKeys, enKeys)
        assertEquals(zhKeys, ruKeys)
        assertTrue(zhKeys.isNotEmpty())

        for (key in zhKeys) {
            assertTrue("Blank zh value for $key", AppStrings.zhAll[key].orEmpty().isNotBlank())
            assertTrue("Blank en value for $key", AppStrings.enAll[key].orEmpty().isNotBlank())
            assertTrue("Blank ru value for $key", AppStrings.ruAll[key].orEmpty().isNotBlank())
        }
    }

    @Test
    fun `all locales share identical placeholder tokens per key`() {
        for (key in AppStrings.zhAll.keys) {
            val zhPlaceholders = extractPlaceholders(AppStrings.zhAll.getValue(key))
            val enPlaceholders = extractPlaceholders(AppStrings.enAll.getValue(key))
            val ruPlaceholders = extractPlaceholders(AppStrings.ruAll.getValue(key))

            assertEquals("en placeholder mismatch for $key", zhPlaceholders, enPlaceholders)
            assertEquals("ru placeholder mismatch for $key", zhPlaceholders, ruPlaceholders)
        }
    }

    @Test
    fun `get resolves locales and falls back cleanly`() {
        assertEquals("前往设置", AppStrings.get("qr_open_settings", "zh"))
        assertEquals("Open Settings", AppStrings.get("qr_open_settings", "en"))
        assertEquals("Открыть настройки", AppStrings.get("qr_open_settings", "ru"))
        assertEquals("Open Settings", AppStrings.get("qr_open_settings", "fr"))
        assertEquals("non_existent_key_sentinel", AppStrings.get("non_existent_key_sentinel", "zh"))

        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            assertEquals("Open Settings", AppStrings.get("qr_open_settings", "SYSTEM"))
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("前往设置", AppStrings.get("qr_open_settings", "SYSTEM"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `group icon description reflects bitmap-only decoder`() {
        for (table in listOf(AppStrings.zhAll, AppStrings.enAll, AppStrings.ruAll)) {
            val desc = table.getValue("proxies_show_icons_desc")
            assertFalse("Icon description should not advertise SVG: $desc", desc.contains("SVG", ignoreCase = true))
        }
    }
}

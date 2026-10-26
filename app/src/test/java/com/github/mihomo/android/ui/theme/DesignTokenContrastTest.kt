package com.github.mihomo.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Guards the color tokens that UI_GUIDELINES.md promises:
 * essential text must stay at WCAG AA (4.5:1) on both the page and card surfaces.
 * `textMuted` is intentionally excluded: it is reserved for placeholders/disabled content.
 */
class DesignTokenContrastTest {

    private fun linear(channel: Float): Double =
        if (channel <= 0.03928f) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun ratio(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAa(foreground: Color, background: Color, label: String) {
        val value = ratio(foreground, background)
        assertTrue("$label contrast was ${"%.2f".format(value)}:1 (needs 4.5:1)", value >= 4.5)
    }

    @Test
    fun `light theme text tokens meet wcag aa`() {
        val colors = LightLoonThemeColors
        assertAa(colors.textPrimary, colors.card, "light primary/card")
        assertAa(colors.textSecondary, colors.card, "light secondary/card")
        assertAa(colors.textSecondary, colors.bg, "light secondary/bg")
        assertAa(colors.successText, colors.card, "light success/card")
        assertAa(colors.warningText, colors.card, "light warning/card")
        assertAa(colors.dangerText, colors.card, "light danger/card")
        assertAa(colors.infoText, colors.card, "light info/card")
    }

    @Test
    fun `dark theme text tokens meet wcag aa`() {
        val colors = DarkLoonThemeColors
        assertAa(colors.textPrimary, colors.card, "dark primary/card")
        assertAa(colors.textSecondary, colors.card, "dark secondary/card")
        assertAa(colors.textSecondary, colors.bg, "dark secondary/bg")
        assertAa(colors.successText, colors.card, "dark success/card")
        assertAa(colors.warningText, colors.card, "dark warning/card")
        assertAa(colors.dangerText, colors.card, "dark danger/card")
        assertAa(colors.infoText, colors.card, "dark info/card")
    }
}

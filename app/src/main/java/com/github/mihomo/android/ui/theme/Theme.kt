package com.github.mihomo.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.github.mihomo.android.data.ThemeMode


data class LoonThemeColors(
    val bg: Color,
    val card: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val bottomBarBg: Color,
    val bottomBarBorder: Color,
    val isDark: Boolean,
    val separator: Color = if (isDark) Color(0x38545458) else Color(0x263C3C43),
    val secondaryBg: Color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF),
    val tertiaryBg: Color = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA),
    val chevronTint: Color = if (isDark) Color(0xFF636366) else Color(0xFFC7C7CC),
    val switchUncheckedTrack: Color = if (isDark) Color(0xFF39393D) else Color(0xFFE9E9EA)
)

val LightLoonThemeColors = LoonThemeColors(
    bg = Color(0xFFF2F2F7), // Apple System Grouped Background
    card = Color(0xFFFFFFFF), // Apple Secondary System Grouped Background
    cardBorder = Color(0x1A000000), // Clean iOS hairline border
    textPrimary = Color(0xFF000000), // iOS Primary Text
    textSecondary = Color(0xFF8E8E93), // iOS Secondary Label
    textMuted = Color(0xFFC7C7CC), // iOS Tertiary Label
    bottomBarBg = Color.White.copy(alpha = 0.88f),
    bottomBarBorder = Color(0x1F000000),
    isDark = false,
    separator = Color(0x263C3C43),
    secondaryBg = Color(0xFFFFFFFF),
    tertiaryBg = Color(0xFFE5E5EA),
    chevronTint = Color(0xFFC7C7CC),
    switchUncheckedTrack = Color(0xFFE9E9EA)
)

val DarkLoonThemeColors = LoonThemeColors(
    bg = Color(0xFF000000), // True OLED Pure Black
    card = Color(0xFF1C1C1E), // Apple Secondary System Grouped Background
    cardBorder = Color(0x2EFFFFFF), // Clean iOS dark hairline border
    textPrimary = Color(0xFFFFFFFF), // iOS White Text
    textSecondary = Color(0xFF8E8E93), // iOS Secondary Label
    textMuted = Color(0xFF636366), // iOS Tertiary Label
    bottomBarBg = Color(0xFF1C1C1E).copy(alpha = 0.90f),
    bottomBarBorder = Color(0x26FFFFFF),
    isDark = true,
    separator = Color(0x38545458),
    secondaryBg = Color(0xFF1C1C1E),
    tertiaryBg = Color(0xFF2C2C2E),
    chevronTint = Color(0xFF636366),
    switchUncheckedTrack = Color(0xFF39393D)
)

val LocalLoonColors = staticCompositionLocalOf { LightLoonThemeColors }

private object NoIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(
        interactionSource: androidx.compose.foundation.interaction.InteractionSource
    ): androidx.compose.ui.Modifier.Node {
        return object : androidx.compose.ui.Modifier.Node() {}
    }

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1
}

@Composable
fun MihomoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val loonColors = if (isDark) DarkLoonThemeColors else LightLoonThemeColors

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = LoonOrange,
            onPrimary = Color.White,
            primaryContainer = Color(0xFF3B2414),
            onPrimaryContainer = LoonOrangeLight,
            background = loonColors.bg,
            surface = loonColors.card,
            surfaceVariant = loonColors.bg,
            onBackground = loonColors.textPrimary,
            onSurface = loonColors.textPrimary,
            onSurfaceVariant = loonColors.textSecondary
        )
    } else {
        lightColorScheme(
            primary = LoonOrange,
            onPrimary = Color.White,
            primaryContainer = LoonOrangeBg,
            onPrimaryContainer = LoonOrange,
            background = loonColors.bg,
            surface = loonColors.card,
            surfaceVariant = loonColors.bg,
            onBackground = loonColors.textPrimary,
            onSurface = loonColors.textPrimary,
            onSurfaceVariant = loonColors.textSecondary
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val statusBarColor = loonColors.bg.toArgb()
        val navBarColor = loonColors.bg.toArgb()
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            window.statusBarColor = statusBarColor
            window.navigationBarColor = navBarColor
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
        }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    CompositionLocalProvider(
        LocalLoonColors provides loonColors,
        androidx.compose.foundation.LocalOverscrollConfiguration provides null,
        androidx.compose.foundation.LocalIndication provides NoIndication
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

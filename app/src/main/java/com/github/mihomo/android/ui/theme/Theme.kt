package com.github.mihomo.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.LocalOverscrollConfiguration
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
    val isDark: Boolean
)

val LightLoonThemeColors = LoonThemeColors(
    bg = Color(0xFFF6F7FB),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFECEEF5),
    textPrimary = Color(0xFF191C24),
    textSecondary = Color(0xFF8E95A5),
    textMuted = Color(0xFFB0B6C3),
    bottomBarBg = Color.White.copy(alpha = 0.95f),
    bottomBarBorder = Color(0xFFEBEFF5),
    isDark = false
)

val DarkLoonThemeColors = LoonThemeColors(
    bg = Color(0xFF13161F),
    card = Color(0xFF1E2330),
    cardBorder = Color(0xFF2B3244),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    bottomBarBg = Color(0xFF1E2330).copy(alpha = 0.95f),
    bottomBarBorder = Color(0xFF2B3244),
    isDark = true
)

val LocalLoonColors = staticCompositionLocalOf { LightLoonThemeColors }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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

    CompositionLocalProvider(
        LocalLoonColors provides loonColors,
        LocalOverscrollConfiguration provides null
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

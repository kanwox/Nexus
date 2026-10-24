package com.github.mihomo.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.github.mihomo.android.data.AppFont
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
val LocalAppFont = staticCompositionLocalOf { AppFont.SYSTEM }
val LocalAppFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * Apple iOS Typography family: SF Pro (Latin/Cyrillic/Numeric) + PingFang SC (苹方-简, CJK)
 * with graceful fallback to system humanist sans-serif weights.
 */
val IosSfProPingFangFontFamily: FontFamily = FontFamily(
    Font(DeviceFontFamilyName("PingFang SC"), FontWeight.Light),
    Font(DeviceFontFamilyName("PingFang SC"), FontWeight.Normal),
    Font(DeviceFontFamilyName("PingFang SC"), FontWeight.Medium),
    Font(DeviceFontFamilyName("PingFang SC"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("PingFang SC"), FontWeight.Bold),
    Font(DeviceFontFamilyName("SF Pro Text"), FontWeight.Light),
    Font(DeviceFontFamilyName("SF Pro Text"), FontWeight.Normal),
    Font(DeviceFontFamilyName("SF Pro Text"), FontWeight.Medium),
    Font(DeviceFontFamilyName("SF Pro Text"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("SF Pro Text"), FontWeight.Bold),
    Font(DeviceFontFamilyName("SF Pro Display"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("SF Pro Display"), FontWeight.Bold),
    Font(DeviceFontFamilyName("sans-serif-light"), FontWeight.Light),
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-medium"), FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif-medium"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Bold)
)

private val IosContinuousShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private fun buildAppTypography(appFont: AppFont): Typography {
    val isIos = appFont == AppFont.IOS
    val family = if (isIos) IosSfProPingFangFontFamily else FontFamily.Default
    val platformStyle = PlatformTextStyle(includeFontPadding = !isIos)

    val displayTracking = if (isIos) (-0.45).sp else 0.sp
    val titleTracking = if (isIos) (-0.3).sp else 0.sp
    val bodyTracking = if (isIos) (-0.22).sp else 0.sp
    val labelTracking = if (isIos) (-0.12).sp else 0.sp

    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(
            fontFamily = family,
            letterSpacing = displayTracking,
            platformStyle = platformStyle
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = family,
            letterSpacing = displayTracking,
            platformStyle = platformStyle
        ),
        displaySmall = base.displaySmall.copy(
            fontFamily = family,
            letterSpacing = displayTracking,
            platformStyle = platformStyle
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = family,
            letterSpacing = displayTracking,
            platformStyle = platformStyle
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = family,
            letterSpacing = titleTracking,
            platformStyle = platformStyle
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = family,
            letterSpacing = titleTracking,
            platformStyle = platformStyle
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = family,
            letterSpacing = titleTracking,
            platformStyle = platformStyle
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = family,
            letterSpacing = titleTracking,
            platformStyle = platformStyle
        ),
        titleSmall = base.titleSmall.copy(
            fontFamily = family,
            letterSpacing = bodyTracking,
            platformStyle = platformStyle
        ),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = family,
            letterSpacing = bodyTracking,
            platformStyle = platformStyle
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = family,
            letterSpacing = bodyTracking,
            platformStyle = platformStyle
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = family,
            letterSpacing = labelTracking,
            platformStyle = platformStyle
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = family,
            letterSpacing = labelTracking,
            platformStyle = platformStyle
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = family,
            letterSpacing = labelTracking,
            platformStyle = platformStyle
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = family,
            letterSpacing = labelTracking,
            platformStyle = platformStyle
        )
    )
}

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
    appFont: AppFont = AppFont.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val loonColors = if (isDark) DarkLoonThemeColors else LightLoonThemeColors
    val typography = remember(appFont) { buildAppTypography(appFont) }
    val activeFontFamily = if (appFont == AppFont.IOS) IosSfProPingFangFontFamily else FontFamily.Default

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
        @Suppress("DEPRECATION")
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            window.statusBarColor = statusBarColor
            window.navigationBarColor = navBarColor
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
        }
    }

    val defaultTextStyle = remember(typography) {
        typography.bodyMedium.merge(TextStyle(fontFamily = activeFontFamily))
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    CompositionLocalProvider(
        LocalLoonColors provides loonColors,
        LocalAppFont provides appFont,
        LocalAppFontFamily provides activeFontFamily,
        androidx.compose.foundation.LocalOverscrollConfiguration provides null,
        androidx.compose.foundation.LocalIndication provides NoIndication
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = IosContinuousShapes
        ) {
            ProvideTextStyle(value = defaultTextStyle, content = content)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

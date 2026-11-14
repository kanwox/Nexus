package com.github.mihomo.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.mihomo.android.data.AppFont
import com.github.mihomo.android.data.ThemeMode


val LocalLoonColors = staticCompositionLocalOf { LightLoonThemeColors }
val LocalAppFont = staticCompositionLocalOf { AppFont.SYSTEM }
val LocalAppFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * True when the system animator scale is zero (accessibility "remove animations" or battery
 * saver). Components use it to snap instead of spring so the iOS micro-interactions never
 * fight the platform accessibility setting.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduceMotion by remember(context) { mutableStateOf(readReduceMotion(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val resolver = context.contentResolver
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = readReduceMotion(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        if (uri != null) {
            runCatching { resolver.registerContentObserver(uri, false, observer) }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduceMotion = readReduceMotion(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            handler.removeCallbacksAndMessages(null)
        }
    }
    return reduceMotion
}

private fun readReduceMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
}.getOrDefault(false)

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
    extraSmall = RoundedCornerShape(LoonRadii.badge),
    small = RoundedCornerShape(LoonRadii.control),
    medium = RoundedCornerShape(LoonRadii.card),
    large = RoundedCornerShape(LoonRadii.sheet),
    extraLarge = RoundedCornerShape(LoonRadii.extraLarge)
)

private fun TextStyle.withAppFont(
    family: FontFamily,
    tracking: TextUnit,
    platformStyle: PlatformTextStyle
): TextStyle = copy(fontFamily = family, letterSpacing = tracking, platformStyle = platformStyle)

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
        displayLarge = base.displayLarge.withAppFont(family, displayTracking, platformStyle),
        displayMedium = base.displayMedium.withAppFont(family, displayTracking, platformStyle),
        displaySmall = base.displaySmall.withAppFont(family, displayTracking, platformStyle),
        headlineLarge = base.headlineLarge.withAppFont(family, displayTracking, platformStyle),
        headlineMedium = base.headlineMedium.withAppFont(family, titleTracking, platformStyle),
        headlineSmall = base.headlineSmall.withAppFont(family, titleTracking, platformStyle),
        titleLarge = base.titleLarge.withAppFont(family, titleTracking, platformStyle),
        titleMedium = base.titleMedium.withAppFont(family, titleTracking, platformStyle),
        titleSmall = base.titleSmall.withAppFont(family, bodyTracking, platformStyle),
        bodyLarge = base.bodyLarge.withAppFont(family, bodyTracking, platformStyle),
        bodyMedium = base.bodyMedium.withAppFont(family, bodyTracking, platformStyle),
        bodySmall = base.bodySmall.withAppFont(family, labelTracking, platformStyle),
        labelLarge = base.labelLarge.withAppFont(family, labelTracking, platformStyle),
        labelMedium = base.labelMedium.withAppFont(family, labelTracking, platformStyle),
        labelSmall = base.labelSmall.withAppFont(family, labelTracking, platformStyle)
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

private fun mihomoColorScheme(isDark: Boolean, loonColors: LoonThemeColors) = if (isDark) {
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

@Composable
private fun SystemBarsAppearanceEffect(isDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    LaunchedEffect(isDark) {
        val activity = view.context.findActivity()
        if (activity is ComponentActivity) {
            val style = if (isDark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            }
            activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        } else {
            val window = activity?.window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }
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
    val reduceMotion = rememberReduceMotion()
    val windowWidthClass = rememberWindowWidthClass()
    val colorScheme = mihomoColorScheme(isDark, loonColors)
    SystemBarsAppearanceEffect(isDark)
    val defaultTextStyle = remember(typography) {
        typography.bodyMedium.merge(TextStyle(fontFamily = activeFontFamily))
    }

    CompositionLocalProvider(
        LocalLoonColors provides loonColors,
        LocalAppFont provides appFont,
        LocalAppFontFamily provides activeFontFamily,
        LocalReduceMotion provides reduceMotion,
        LocalWindowWidthClass provides windowWidthClass
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = IosContinuousShapes
        ) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            CompositionLocalProvider(
                // Placed INSIDE MaterialTheme so Material 3's internal LocalIndication (ripple)
                // is overridden with NoIndication across the entire app.
                androidx.compose.foundation.LocalOverscrollFactory provides null,
                androidx.compose.foundation.LocalIndication provides NoIndication
            ) {
                ProvideTextStyle(value = defaultTextStyle, content = content)
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

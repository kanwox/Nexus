package com.github.mihomo.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Loon Brand Colors
val LoonOrange = Color(0xFFFF6B00)
val LoonOrangeLight = Color(0xFFFF8533)
val LoonOrangeBg = Color(0xFFFFF4EB)

val LoonGreen = Color(0xFF00C853)
val LoonGreenDark = Color(0xFF00A844)
val LoonBlue = Color(0xFF4A69FF)
val LoonBlueDark = Color(0xFF3852D4)

val LoonEditBlue = Color(0xFF2F80ED)
val LoonEditBlueBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalLoonColors.current.isDark) Color(0xFF252A37) else Color(0xFFE8EBF2)

// Dynamic theme-aware colors
val LoonBg: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.bg

val LoonCard: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.card

val LoonCardBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.cardBorder

val LoonTextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.textPrimary

val LoonTextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.textSecondary

val LoonTextMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.textMuted

// Legacy compatibility
val CyanPrimary = LoonOrange
val CyanVariant = LoonOrangeLight
val CyanGlow = Color(0x33FF6B00)
val OledBlack = Color(0xFF13161F)
val DarkSurface = Color(0xFF1E2330)
val DarkSurfaceVariant = Color(0xFF1E2330)

val DarkCard: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.card

val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.textPrimary

val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.textSecondary

val TextMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.textMuted

val GreenSuccess = LoonGreen
val YellowWarning = Color(0xFFFFA000)
val RedDanger = Color(0xFFFF5252)

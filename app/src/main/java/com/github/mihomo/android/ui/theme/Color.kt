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

/** Text-safe status colors. The bright Ios* palette remains for fills, icons and badges. */
val LoonSuccessText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.successText

val LoonWarningText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.warningText

val LoonDangerText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.dangerText

val LoonInfoText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.infoText

val LoonFocusRing: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.focusRing

// Apple Official System Palette
val IosBlue = Color(0xFF007AFF)
val IosGreen = Color(0xFF34C759)
val IosIndigo = Color(0xFF5856D6)
val IosMint = Color(0xFF00C7BE)
val IosCyan = Color(0xFF32ADE6)
val IosOrange = Color(0xFFFF9500)
val IosPink = Color(0xFFFF2D55)
val IosPurple = Color(0xFFAF52DE)
val IosRed = Color(0xFFFF3B30)
val IosTeal = Color(0xFF30B0C7)
val IosYellow = Color(0xFFFFCC00)
val IosGray = Color(0xFF8E8E93)
val IosGray2 = Color(0xFFAEAEC2)
val IosGray3 = Color(0xFFC7C7CC)
val IosGray4 = Color(0xFFD1D1D6)
val IosGray5 = Color(0xFFE5E5EA)
val IosGray6 = Color(0xFFF2F2F7)

// Loon-style selected node card background (theme-aware)
val IosSelectedCardBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalLoonColors.current.isDark) Color(0xFF12233A) else Color(0xFFEFF6FF)

val LoonSeparator: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.separator

val LoonSecondaryBg: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.secondaryBg

val LoonTertiaryBg: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.tertiaryBg

val LoonChevronTint: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.chevronTint

val LoonSwitchUncheckedTrack: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalLoonColors.current.switchUncheckedTrack

// Semantic status colors
val RedDanger = IosRed
val GreenSuccess = IosGreen
val YellowWarning = IosOrange

/**
 * Foreground colour for saturated accent surfaces: filled buttons, traffic cards and the
 * switch thumb. UI_GUIDELINES.md §3.1 keeps the Apple system blue + white label combination
 * as the single documented exception; every other accent surface must use this token instead
 * of a raw `Color.White` so the checkUiTokens guard can stay strict.
 */
val LoonOnAccent: Color = Color(0xFFFFFFFF)

/** Neutral scrim used to separate overlapping illustration layers. */
val LoonOverlayScrim: Color = Color(0x14000000)



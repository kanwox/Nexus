package com.github.mihomo.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The single source of truth for the iOS-inspired visual language.
 *
 * Rules that used to live only in UI_GUIDELINES.md are encoded here so screens cannot drift:
 *  - palette + semantic roles (text/status/focus), light and dark;
 *  - spacing / radius / size / motion scales.
 *
 * Feature code must reference these tokens (or the semantic helpers in Color.kt /
 * LoonTextStyles.kt) instead of inventing raw colors, font sizes or animation durations.
 */
data class LoonThemeColors(
    val bg: Color,
    val card: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    /** Essential secondary text (subtitles, metadata). Meets WCAG AA on the card surface. */
    val textSecondary: Color,
    /** Placeholder / decorative / disabled text only; never essential information. */
    val textMuted: Color,
    val bottomBarBg: Color,
    val bottomBarBorder: Color,
    val isDark: Boolean,
    val separator: Color = if (isDark) Color(0x38545458) else Color(0x263C3C43),
    val secondaryBg: Color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF),
    val tertiaryBg: Color = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA),
    val chevronTint: Color = if (isDark) Color(0xFF636366) else Color(0xFFC7C7CC),
    val switchUncheckedTrack: Color = if (isDark) Color(0xFF39393D) else Color(0xFFE9E9EA),
    /** Text-safe status colors (the bright Ios* palette stays for fills and icons). */
    val successText: Color,
    val warningText: Color,
    val dangerText: Color,
    val infoText: Color,
    /** Visible keyboard/D-pad focus ring; the global NoIndication removes ripples. */
    val focusRing: Color,
)

val LightLoonThemeColors = LoonThemeColors(
    bg = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0x1A000000),
    textPrimary = Color(0xFF000000),
    // Apple's secondary label composited on white is ~#666; #6E6E73 keeps that look and
    // reaches 5.07:1 (the old #8E8E93 was only 3.26:1 for 12-13sp metadata).
    textSecondary = Color(0xFF6E6E73),
    // Placeholder/tertiary only. #8E8E93 is intentionally quieter than secondary.
    textMuted = Color(0xFF8E8E93),
    bottomBarBg = Color.White.copy(alpha = 0.88f),
    bottomBarBorder = Color(0x1F000000),
    isDark = false,
    separator = Color(0x263C3C43),
    secondaryBg = Color(0xFFFFFFFF),
    tertiaryBg = Color(0xFFE5E5EA),
    chevronTint = Color(0xFFC7C7CC),
    switchUncheckedTrack = Color(0xFFE9E9EA),
    successText = Color(0xFF1B7F3B),
    warningText = Color(0xFFA85A00),
    dangerText = Color(0xFFC62828),
    infoText = Color(0xFF0055CC),
    focusRing = Color(0xFF0055CC),
)

val DarkLoonThemeColors = LoonThemeColors(
    bg = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardBorder = Color(0x2EFFFFFF),
    textPrimary = Color(0xFFFFFFFF),
    // #8E8E93 on the dark card was 5.22:1; #A1A1A6 raises metadata legibility further.
    textSecondary = Color(0xFFA1A1A6),
    textMuted = Color(0xFF8E8E93),
    bottomBarBg = Color(0xFF1C1C1E).copy(alpha = 0.90f),
    bottomBarBorder = Color(0x26FFFFFF),
    isDark = true,
    separator = Color(0x38545458),
    secondaryBg = Color(0xFF1C1C1E),
    tertiaryBg = Color(0xFF2C2C2E),
    chevronTint = Color(0xFF636366),
    switchUncheckedTrack = Color(0xFF39393D),
    successText = Color(0xFF30D158),
    warningText = Color(0xFFFFB340),
    dangerText = Color(0xFFFF6961),
    infoText = Color(0xFF409CFF),
    focusRing = Color(0xFF409CFF),
)

/** 4dp-based spacing scale. Screens should pick the nearest step, not a bespoke value. */
object LoonSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val section = 32.dp
}

/** Continuous-corner radii used by the component library. */
object LoonRadii {
    val badge = 6.dp
    val iconBadge = 7.dp
    val control = 10.dp
    val card = 14.dp
    val sheet = 16.dp
    val dialog = 14.dp
    val segmented = 8.5.dp
    val segmentedThumb = 7.dp
    val bottomBar = 32.dp
    val extraLarge = 28.dp
}

/** Reusable dimensions, including the Android accessibility minimum touch target. */
object LoonSizes {
    val touchTargetMin = 48.dp
    val navBarHeight = 48.dp
    val icon = 20.dp
    val iconSmall = 16.dp
    val iconBadge = 30.dp
    val switchWidth = 51.dp
    val switchHeight = 31.dp
    val switchThumb = 27.dp
    val searchHeight = 38.dp
    val buttonHeight = 48.dp
}

/** Motion tokens. Feature code must use these instead of ad-hoc tween/spring values. */
object LoonMotion {
    const val fastMs = 150
    const val normalMs = 220
    const val slowMs = 300
    const val pressScale = 0.982f
    const val pressScaleStrong = 0.97f
    const val dragLiftScale = 1.03f
}

/**
 * Low-level type scale. Prefer the semantic roles in [LoonTextStyles]; this exists so screens
 * that still need a one-off size do not hardcode `sp` literals.
 */
object LoonTypeScale {
    val micro = 10.sp
    val caption = 11.sp
    val captionLarge = 11.5.sp
    val footnote = 12.sp
    val footnoteLarge = 12.5.sp
    val callout = 13.sp
    val calloutLarge = 13.5.sp
    val subhead = 14.sp
    val subheadLarge = 14.5.sp
    val body = 15.sp
    val bodyLarge = 15.5.sp
    val title3 = 16.sp
    val title2 = 17.sp
    val title1 = 22.sp
    val largeTitle = 32.sp
}

/** Shadow/glow values shared by the illustration and component layers. */
object LoonExtraColors {
    val shadowHairline = Color(0x0A000000)
    val shadowSoft = Color(0x1F000000)
    val shadowMedium = Color(0x28000000)
    val shadowStrong = Color(0x33000000)
    val glowGreen = Color(0x3300C853)
    val glowBlue = Color(0x334A69FF)

    // Illustration gradients
    val greenBright = Color(0xFF00E676)
    val violet = Color(0xFF6C5CE7)
    val purple = Color(0xFFA855F7)
    val purpleDark = Color(0xFF9333EA)
    val bluePale = Color(0xFF93C5FD)
    val blue = Color(0xFF60A5FA)
    val blueDark = Color(0xFF1D4ED8)
    val sky = Color(0xFF38BDF8)
    val skyDark = Color(0xFF0284C7)
    val indigo = Color(0xFF818CF8)
    val indigoDark = Color(0xFF6366F1)
    val blueMain = Color(0xFF2563EB)
    val red = Color(0xFFF87171)
    val redDark = Color(0xFFDC2626)
    val green = Color(0xFF34D399)
    val greenDark = Color(0xFF059669)
    val orangeTint = Color(0xFFFFF6EE)
    val lavender = Color(0xFFF9F9FD)

    // Code editor syntax palette
    val syntaxKeywordDark = Color(0xFFC586C0)
    val syntaxKeywordLight = Color(0xFFAF00DB)
    val syntaxBuiltinDark = Color(0xFF4EC9B0)
    val syntaxBuiltinLight = Color(0xFF0070C1)
    val syntaxKeyDark = Color(0xFF9CDCFE)
    val syntaxKeyLight = Color(0xFF0451A5)
    val syntaxStringDark = Color(0xFFCE9178)
    val syntaxStringLight = Color(0xFFA31515)
    val syntaxNumberDark = Color(0xFFB5CEA8)
    val syntaxNumberLight = Color(0xFF098658)
    val syntaxCommentDark = Color(0xFF6A9955)
    val syntaxCommentLight = Color(0xFF008000)
    val syntaxTextDark = Color(0xFFD4D4D4)
    val syntaxTextLight = Color(0xFF1C1C1E)
}


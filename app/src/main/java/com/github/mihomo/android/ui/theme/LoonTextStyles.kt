package com.github.mihomo.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.AppFont

/**
 * Semantic text styles for the iOS/Loon visual language.
 *
 * Feature code should use these roles instead of raw `fontSize` / `letterSpacing` values.
 * Tracking is applied only for the optional SF Pro/PingFang font; the system font keeps the
 * neutral 0sp spacing required by the guidelines.
 */
object LoonTextStyles {

    @Composable
    @ReadOnlyComposable
    private fun tracking(ios: Float): TextUnit =
        if (LocalAppFont.current == AppFont.IOS) ios.sp else 0.sp

    @Composable
    @ReadOnlyComposable
    private fun base(
        size: Float,
        weight: FontWeight,
        iosTracking: Float,
        lineHeight: Float? = null,
        family: FontFamily = LocalAppFontFamily.current
    ): TextStyle = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = tracking(iosTracking),
        lineHeight = lineHeight?.sp ?: TextUnit.Unspecified
    )

    @Composable
    @ReadOnlyComposable
    fun largeTitle(): TextStyle = base(32f, FontWeight.Black, -0.45f)

    @Composable
    @ReadOnlyComposable
    fun largeTitleBold(): TextStyle = base(32f, FontWeight.Bold, -0.45f)

    @Composable
    @ReadOnlyComposable
    fun sectionTitle(): TextStyle = base(22f, FontWeight.Bold, -0.30f)

    @Composable
    @ReadOnlyComposable
    fun navTitle(): TextStyle = base(17f, FontWeight.SemiBold, -0.30f)

    @Composable
    @ReadOnlyComposable
    fun dialogTitle(): TextStyle = base(17f, FontWeight.Bold, -0.30f, lineHeight = 22f)

    @Composable
    @ReadOnlyComposable
    fun dialogMessage(): TextStyle = base(13.5f, FontWeight.Normal, -0.18f, lineHeight = 18f)

    @Composable
    @ReadOnlyComposable
    fun cellTitle(): TextStyle = base(15.5f, FontWeight.Normal, -0.20f)

    @Composable
    @ReadOnlyComposable
    fun cellTitleStrong(): TextStyle = base(15.5f, FontWeight.SemiBold, -0.20f)

    @Composable
    @ReadOnlyComposable
    fun body(): TextStyle = base(15f, FontWeight.Normal, -0.22f)

    @Composable
    @ReadOnlyComposable
    fun value(): TextStyle = base(14.5f, FontWeight.Normal, -0.18f)

    @Composable
    @ReadOnlyComposable
    fun subtitle(): TextStyle = base(12f, FontWeight.Normal, -0.12f, lineHeight = 16f)

    @Composable
    @ReadOnlyComposable
    fun subtitleStrong(): TextStyle = base(12f, FontWeight.SemiBold, -0.12f, lineHeight = 16f)

    @Composable
    @ReadOnlyComposable
    fun actionSheetTitle(): TextStyle = base(13f, FontWeight.SemiBold, -0.15f)

    @Composable
    @ReadOnlyComposable
    fun segmentedLabel(): TextStyle = base(13f, FontWeight.Normal, -0.15f)

    @Composable
    @ReadOnlyComposable
    fun segmentedLabelStrong(): TextStyle = base(13f, FontWeight.SemiBold, -0.15f)

    /**
     * Detail-sheet label role (13sp). Used by the shared [CupertinoDetailRow] so every
     * inspector sheet reads identically instead of each screen inventing its own size.
     */
    @Composable
    @ReadOnlyComposable
    fun callout(): TextStyle = base(13f, FontWeight.Normal, -0.15f)

    /** Detail-sheet value role (13.5sp); weight is chosen by the call site. */
    @Composable
    @ReadOnlyComposable
    fun calloutLarge(): TextStyle = base(13.5f, FontWeight.Normal, -0.15f)

    @Composable
    @ReadOnlyComposable
    fun caption(): TextStyle = base(11f, FontWeight.Normal, -0.12f)

    @Composable
    @ReadOnlyComposable
    fun groupHeader(): TextStyle = TextStyle(
        fontFamily = LocalAppFontFamily.current,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp
    )

    @Composable
    @ReadOnlyComposable
    fun button(): TextStyle = base(16f, FontWeight.SemiBold, -0.20f)

    @Composable
    @ReadOnlyComposable
    fun buttonSmall(): TextStyle = base(14f, FontWeight.SemiBold, -0.20f)

    @Composable
    @ReadOnlyComposable
    fun actionLabel(): TextStyle = base(17f, FontWeight.Normal, -0.22f)

    @Composable
    @ReadOnlyComposable
    fun actionLabelStrong(): TextStyle = base(17f, FontWeight.SemiBold, -0.22f)

    @Composable
    @ReadOnlyComposable
    fun input(): TextStyle = base(14.5f, FontWeight.Normal, -0.18f)

    @Composable
    @ReadOnlyComposable
    fun search(): TextStyle = base(14f, FontWeight.Normal, -0.18f)

    @Composable
    @ReadOnlyComposable
    fun toast(): TextStyle = base(13.5f, FontWeight.Medium, -0.15f)

    @Composable
    @ReadOnlyComposable
    fun tab(): TextStyle = base(10.5f, FontWeight.Normal, -0.10f)

    @Composable
    @ReadOnlyComposable
    fun tabStrong(): TextStyle = base(10.5f, FontWeight.SemiBold, -0.10f)

    @Composable
    @ReadOnlyComposable
    fun micro(): TextStyle = base(10f, FontWeight.Normal, -0.10f)

    @Composable
    @ReadOnlyComposable
    fun mono(): TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp
    )
}

package com.github.mihomo.android.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material-style width classes without pulling in the extra window-size-class artifact. */
enum class WindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

val LocalWindowWidthClass = staticCompositionLocalOf { WindowWidthClass.COMPACT }

@Composable
@ReadOnlyComposable
fun rememberWindowWidthClass(): WindowWidthClass {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val widthDp = with(density) { windowInfo.containerSize.width.toDp() }
    return when {
        widthDp < 600.dp -> WindowWidthClass.COMPACT
        widthDp < 840.dp -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }
}

/**
 * Centers phone-first content on tablets/foldables and caps its measure at [maxWidth].
 * On compact phones this fills the available bounds without altering layout.
 */
@Composable
fun LoonResponsiveContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 680.dp,
    content: @Composable () -> Unit
) {
    val containerModifier = if (modifier === Modifier) Modifier.fillMaxSize() else modifier.fillMaxWidth()
    Box(
        modifier = containerModifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = maxWidth)
                .fillMaxWidth()
        ) {
            content()
        }
    }
}

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
import androidx.compose.ui.platform.LocalConfiguration
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
fun rememberWindowWidthClass(): WindowWidthClass =
    when (LocalConfiguration.current.screenWidthDp) {
        in 0..599 -> WindowWidthClass.COMPACT
        in 600..839 -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }

/**
 * Centers phone-first content on tablets/foldables and caps its measure at [maxWidth].
 * On compact phones this is a no-op, so the existing layout is untouched.
 */
@Composable
fun LoonResponsiveContent(
    maxWidth: Dp = 680.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
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

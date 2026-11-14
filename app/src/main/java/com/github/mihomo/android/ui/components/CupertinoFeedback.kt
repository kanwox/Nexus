package com.github.mihomo.android.ui.components

import android.content.ClipData
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.nativeClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.github.mihomo.android.ui.theme.LocalLoonColors
import com.github.mihomo.android.ui.theme.LocalReduceMotion
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonMotion
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSpacing
import com.github.mihomo.android.ui.theme.LoonSuccessText
import com.github.mihomo.android.ui.theme.LoonTextStyles
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

// =============================================================================
// iOS Dynamic Pill HUD / Toast & Clipboard Helpers
// =============================================================================

data class CupertinoToastData(
    val id: Long,
    val message: String,
    val icon: ImageVector? = null,
    val isDestructive: Boolean = false,
    val durationMs: Long = 2000L
)

object CupertinoToast {
    // Monotonic counter: two toasts in the same millisecond must still differ, or the
    // id-keyed LaunchedEffect in the host would not restart for the second one.
    private val nextId = AtomicLong(0)
    private val _currentToast = MutableStateFlow<CupertinoToastData?>(null)
    val currentToast: StateFlow<CupertinoToastData?> = _currentToast.asStateFlow()

    fun show(
        message: String,
        icon: ImageVector? = null,
        isDestructive: Boolean = false,
        durationMs: Long = 2000L
    ) {
        _currentToast.value = CupertinoToastData(
            id = nextId.incrementAndGet(),
            message = message,
            icon = icon,
            isDestructive = isDestructive,
            durationMs = durationMs
        )
    }

    fun dismiss(id: Long? = null) {
        if (id == null) {
            _currentToast.value = null
        } else {
            val cur = _currentToast.value
            if (cur != null && cur.id == id) {
                _currentToast.compareAndSet(cur, null)
            }
        }
    }
}

@Suppress("unused")
fun Context.showCupertinoToast(
    message: String,
    icon: ImageVector? = null,
    isDestructive: Boolean = false
) {
    CupertinoToast.show(message = message, icon = icon, isDestructive = isDestructive)
}

/**
 * Top floating pill HUD container adhering to iOS Dynamic Island / Capsule aesthetic.
 */
@Composable
fun CupertinoToastHost(
    modifier: Modifier = Modifier
) {
    val toastData by CupertinoToast.currentToast.collectAsState()
    val haptic = rememberIosHaptic()
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(toastData?.id) {
        val current = toastData ?: return@LaunchedEffect
        haptic.lightTick()
        delay(current.durationMs.milliseconds)
        CupertinoToast.dismiss(current.id)
    }

    val enterAnim = if (reduceMotion) {
        fadeIn(animationSpec = snap())
    } else {
        fadeIn(animationSpec = tween(LoonMotion.normalMs)) +
            slideInVertically(
                initialOffsetY = { height -> -height },
                animationSpec = spring(
                    dampingRatio = LoonMotion.toastDampingRatio,
                    stiffness = LoonMotion.toastStiffness
                )
            )
    }
    val exitAnim = if (reduceMotion) {
        fadeOut(animationSpec = snap())
    } else {
        fadeOut(animationSpec = tween(LoonMotion.fastMs)) +
            slideOutVertically(
                targetOffsetY = { height -> -height },
                animationSpec = tween(LoonMotion.fastMs)
            )
    }

    AnimatedVisibility(
        visible = toastData != null,
        enter = enterAnim,
        exit = exitAnim,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 10.dp)
            .zIndex(9999f)
    ) {
        val data = toastData ?: return@AnimatedVisibility
        CupertinoToastPill(data = data)
    }
}

@Composable
private fun CupertinoToastPill(data: CupertinoToastData) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            shape = CircleShape,
            color = LocalLoonColors.current.card.copy(alpha = 0.94f),
            border = BorderStroke(0.5.dp, LocalLoonColors.current.cardBorder),
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(min = 120.dp, max = 340.dp)
                .semantics {
                    // Screen readers announce toasts; the visual pill alone is invisible to them.
                    liveRegion = if (data.isDestructive) {
                        LiveRegionMode.Assertive
                    } else {
                        LiveRegionMode.Polite
                    }
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val iconVector = data.icon ?: if (data.isDestructive) Icons.Default.Clear else Icons.Default.Check
                val iconTint = if (data.isDestructive) LoonDangerText else LoonSuccessText
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(LoonSizes.iconSmall)
                )
                Spacer(modifier = Modifier.width(LoonSpacing.sm))
                Text(
                    text = data.message,
                    color = LocalLoonColors.current.textPrimary,
                    style = LoonTextStyles.toast(),
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Copies [text] to the system clipboard via Android's [ClipData.newPlainText] API without
 * relying on deprecated Compose `ClipboardManager.setText`.
 */
@Composable
fun rememberClipboardCopy(): (String) -> Unit {
    val nativeClipboard = LocalClipboard.current.nativeClipboardManager
    return remember(nativeClipboard) {
        { text: String ->
            runCatching {
                nativeClipboard.setPrimaryClip(ClipData.newPlainText("", text))
            }
        }
    }
}

/**
 * Safely reads primary clipboard plain text, returning empty string when the clip is empty.
 */
@Composable
fun rememberClipboardReadText(): () -> String {
    val nativeClipboard = LocalClipboard.current.nativeClipboardManager
    return remember(nativeClipboard) {
        {
            runCatching {
                val clip = nativeClipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).text?.toString().orEmpty()
                } else {
                    ""
                }
            }.getOrDefault("")
        }
    }
}

package com.github.mihomo.android.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosGreen
import com.github.mihomo.android.ui.theme.LocalLoonColors
import com.github.mihomo.android.ui.theme.LocalReduceMotion
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonCard
import com.github.mihomo.android.ui.theme.LoonCardBorder
import com.github.mihomo.android.ui.theme.LoonChevronTint
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonExtraColors
import com.github.mihomo.android.ui.theme.LoonFocusRing
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonMotion
import com.github.mihomo.android.ui.theme.LoonOnAccent
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonSeparator
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSpacing
import com.github.mihomo.android.ui.theme.LoonSwitchUncheckedTrack
import com.github.mihomo.android.ui.theme.LoonTertiaryBg
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.RedDanger
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import java.util.Locale

// =============================================================================
// iOS Haptic Feedback Utilities
// =============================================================================

@Composable
fun rememberIosHaptic(): IosHapticHelper {
    val view = LocalView.current
    return remember(view) { IosHapticHelper(view) }
}

class IosHapticHelper(private val view: android.view.View) {
    // Generic click haptics are cancelled per design requirements (silent)
    fun lightTick() {
        // Silent
    }

    fun mediumImpact() {
        // Silent
    }

    fun impact() {
        // Silent
    }

    fun selection() {
        // Silent
    }

    // Only explicitly authorized interaction haptics:
    /** Haptic feedback for switching bottom navigation bar tabs */
    fun navTabSelection() {
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** Haptic feedback for starting / stopping VPN toggle */
    fun vpnToggleImpact() {
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}

// =============================================================================
// iOS Pressable Modifier (Instant micro-scale & highlight, replacing ink ripples)
// =============================================================================

/**
 * Visible keyboard/D-pad focus. The global [NoIndication] removes ripples, so a focus ring is
 * the only affordance keyboard and switch-access users get.
 */
fun Modifier.iosFocusRing(
    shape: Shape = RoundedCornerShape(LoonRadii.control)
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = LoonFocusRing
    this
        .onFocusChanged { state -> focused = state.isFocused }
        .then(if (focused) Modifier.border(2.dp, ring, shape) else Modifier)
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.iosPressable(
    enabled: Boolean = true,
    pressedScale: Float = LoonMotion.pressScale,
    focusShape: Shape? = RoundedCornerShape(LoonRadii.control),
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current

    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed && !reduceMotion) pressedScale else 1.0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = LoonMotion.pressDampingRatio,
                stiffness = LoonMotion.pressStiffness
            )
        },
        label = "ios_press_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(if (focusShape != null) Modifier.iosFocusRing(focusShape) else Modifier)
        .then(
            if (onClick != null && onLongClick != null) {
                Modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onLongClick = onLongClick,
                    onClick = onClick
                )
            } else if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )
}

/**
 * iOS Dialog / Action Sheet Button Press Feedback (Smooth translucent highlight, no ripples).
 */
fun Modifier.iosHighlightPressable(
    enabled: Boolean = true,
    highlightColor: Color? = null,
    focusShape: Shape? = null,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val defaultHighlight = LocalLoonColors.current.tertiaryBg.copy(alpha = 0.65f)
    val color = highlightColor ?: defaultHighlight
    val focusHighlight = LoonFocusRing.copy(alpha = 0.10f)

    this
        .background(
            when {
                enabled && isPressed -> color
                enabled && focused -> focusHighlight
                else -> Color.Transparent
            }
        )
        .onFocusChanged { state -> focused = state.isFocused }
        .then(if (focusShape != null) Modifier.iosFocusRing(focusShape) else Modifier)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )
}

// =============================================================================
// Authentic Cupertino Activity Indicator (iOS Radial Spoke Spinner)
// =============================================================================

@Composable
fun CupertinoActivityIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = LoonTextSecondary,
    spokeCount: Int = 8
) {
    val reduceMotion = LocalReduceMotion.current
    // UI-A11Y-08: with the animator duration scale at 0 the infinite spinner must stop. The
    // static fallback draws the same spokes at a fixed alpha, so the control still reads as
    // "busy" without animating.
    val step = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "cupertino_spinner")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = spokeCount.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = LoonMotion.spinnerCycleMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "cupertino_spinner_step"
        ).value
    }

    Canvas(
        modifier = modifier.size(size)
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outerRadius = this.size.minDimension / 2f
        val innerRadius = outerRadius * 0.52f
        val spokeWidth = (outerRadius - innerRadius) * 0.72f
        val currentStep = step.toInt() % spokeCount

        for (i in 0 until spokeCount) {
            val angleDeg = (i * 360f / spokeCount)
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cos = Math.cos(angleRad).toFloat()
            val sin = Math.sin(angleRad).toFloat()

            val pStart = Offset(center.x + innerRadius * cos, center.y + innerRadius * sin)
            val pEnd = Offset(center.x + outerRadius * cos, center.y + outerRadius * sin)

            val distance = (currentStep - i + spokeCount) % spokeCount
            val alphaFactor = (1f - (distance.toFloat() / spokeCount.toFloat())).coerceIn(0.2f, 1.0f)

            drawLine(
                color = color.copy(alpha = color.alpha * alphaFactor),
                start = pStart,
                end = pEnd,
                strokeWidth = spokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

// =============================================================================
// iOS Inset Grouped Section & Cell
// =============================================================================

@Composable
fun CupertinoGroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    title: String? = null,
    footer: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val displayHeader = header ?: title
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        if (!displayHeader.isNullOrBlank()) {
            Text(
                text = displayHeader.uppercase(Locale.ROOT),
                color = LoonTextSecondary,
                style = LoonTextStyles.groupHeader(),
                modifier = Modifier.padding(start = 14.dp, bottom = LoonSpacing.xs)
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(LoonRadii.card))
                .border(
                    width = 0.5.dp,
                    color = LoonCardBorder,
                    shape = RoundedCornerShape(LoonRadii.card)
                ),
            color = LoonCard,
            shape = RoundedCornerShape(LoonRadii.card)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }

        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                color = LoonTextSecondary,
                style = LoonTextStyles.subtitle(),
                modifier = Modifier.padding(start = 14.dp, top = LoonSpacing.xs, end = 14.dp)
            )
        }
    }
}

/**
 * Virtualized iOS Inset Grouped section for [LazyColumn].
 * Renders each item as an independent lazy row so lists with thousands of rules/logs/apps
 * never compose all rows upfront.
 */
fun <T> LazyListScope.cupertinoGroupedItems(
    items: List<T>,
    header: String? = null,
    footer: String? = null,
    key: ((index: Int, item: T) -> Any)? = null,
    dividerStartIndent: Dp = 16.dp,
    itemContent: @Composable (index: Int, item: T) -> Unit
) {
    if (!header.isNullOrBlank()) {
        item(contentType = "cupertino_group_header") {
            Text(
                text = header.uppercase(Locale.ROOT),
                color = LoonTextSecondary,
                style = LoonTextStyles.groupHeader(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(start = 14.dp, bottom = LoonSpacing.xs)
            )
        }
    }

    val lastIdx = items.lastIndex
    itemsIndexed(
        items = items,
        key = key,
        contentType = { _, _ -> "cupertino_group_row" }
    ) { index, item ->
        val isFirst = index == 0
        val isLast = index == lastIdx
        val shape = when {
            isFirst && isLast -> RoundedCornerShape(LoonRadii.card)
            isFirst -> RoundedCornerShape(topStart = LoonRadii.card, topEnd = LoonRadii.card)
            isLast -> RoundedCornerShape(bottomStart = LoonRadii.card, bottomEnd = LoonRadii.card)
            else -> RoundedCornerShape(0.dp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(shape)
                .background(LoonCard)
        ) {
            itemContent(index, item)
            if (!isLast) {
                val endGap = if (dividerStartIndent > 20.dp) 14.dp else 16.dp
                CupertinoIndentedDivider(startIndent = dividerStartIndent, endIndent = endGap)
            }
        }
    }

    if (!footer.isNullOrBlank()) {
        item(contentType = "cupertino_group_footer") {
            Text(
                text = footer,
                color = LoonTextSecondary,
                style = LoonTextStyles.subtitle(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(start = 14.dp, top = LoonSpacing.xs, end = 14.dp)
            )
        }
    }
}

@Composable
fun CupertinoCell(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBg: Color = IosBlue,
    customIcon: (@Composable () -> Unit)? = null,
    value: String? = null,
    showChevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val haptic = rememberIosHaptic()
    val clickModifier = if (onClick != null && enabled) {
        Modifier.iosPressable(enabled = true) {
            haptic.selection()
            onClick()
        }
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            CupertinoCellLeading(icon = icon, iconBg = iconBg, customIcon = customIcon)
            CupertinoCellLabels(title = title, subtitle = subtitle, enabled = enabled)
        }
        CupertinoCellTrailing(value = value, showChevron = showChevron, trailing = trailing)
    }
}

/**
 * Full-row switch cell exposing a single `Role.Switch` accessibility node to TalkBack
 * so users never get double-focus on the row plus trailing switch.
 */
@Composable
fun CupertinoSwitchCell(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBg: Color = IosBlue,
    enabled: Boolean = true
) {
    val haptic = rememberIosHaptic()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .iosFocusRing(RoundedCornerShape(LoonRadii.control))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { next ->
                    haptic.selection()
                    onCheckedChange(next)
                }
            )
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            CupertinoCellLeading(icon = icon, iconBg = iconBg, customIcon = null)
            CupertinoCellLabels(title = title, subtitle = subtitle, enabled = enabled)
        }
        Spacer(modifier = Modifier.width(LoonSpacing.sm))
        Box(modifier = Modifier.clearAndSetSemantics {}) {
            CupertinoSwitchVisual(checked = checked, enabled = enabled)
        }
    }
}

@Composable
private fun CupertinoCellLeading(
    icon: ImageVector?,
    iconBg: Color,
    customIcon: (@Composable () -> Unit)?
) {
    if (customIcon != null) {
        Box(
            modifier = Modifier
                .size(LoonSizes.iconBadge)
                .clip(RoundedCornerShape(LoonRadii.iconBadge)),
            contentAlignment = Alignment.Center
        ) {
            customIcon()
        }
        Spacer(modifier = Modifier.width(12.dp))
    } else if (icon != null) {
        Box(
            modifier = Modifier
                .size(LoonSizes.iconBadge)
                .clip(RoundedCornerShape(LoonRadii.iconBadge))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LoonOnAccent,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
    }
}

@Composable
private fun RowScope.CupertinoCellLabels(
    title: String,
    subtitle: String?,
    enabled: Boolean
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            color = if (enabled) LoonTextPrimary else LoonTextMuted,
            style = LoonTextStyles.cellTitle()
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(LoonSpacing.xxs))
            Text(
                text = subtitle,
                color = LoonTextSecondary,
                style = LoonTextStyles.subtitle()
            )
        }
    }
}

@Composable
private fun CupertinoCellTrailing(
    value: String?,
    showChevron: Boolean,
    trailing: (@Composable () -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        if (trailing != null) {
            trailing()
        } else {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    color = LoonTextSecondary,
                    style = LoonTextStyles.value()
                )
            }
            if (showChevron) {
                Spacer(modifier = Modifier.width(LoonSpacing.xs))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LoonChevronTint,
                    modifier = Modifier.size(LoonSizes.iconSmall)
                )
            }
        }
    }
}

/**
 * Text-aligned indented hairline divider for iOS Inset Grouped lists with leading icons.
 * Indents past the icon squircle on the left, and leaves a small subtle margin on the right.
 */
@Composable
fun CupertinoIndentedDivider(
    startIndent: Dp = 58.dp,
    endIndent: Dp = 14.dp
) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent, end = endIndent),
        color = LoonSeparator,
        thickness = 0.5.dp
    )
}

/**
 * Balanced hairline divider for rows without leading icons.
 * Leaves equal, symmetric small margins on both sides.
 */
@Composable
fun CupertinoDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 16.dp,
    endIndent: Dp = 16.dp
) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent, end = endIndent),
        color = LoonSeparator,
        thickness = 0.5.dp
    )
}

// =============================================================================
// Authentic Cupertino Switch (51dp x 31dp, 27dp circle thumb, spring damping)
// =============================================================================

@Composable
fun CupertinoSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // When onCheckedChange is null, the parent container handles toggleable semantics
    // (matching Material Switch), so omit the duplicate toggleable wrapper.
    val interactiveModifier = if (onCheckedChange != null) {
        Modifier
            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
            .iosFocusRing(CircleShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
    } else {
        Modifier
    }
    Box(
        modifier = modifier.then(interactiveModifier),
        contentAlignment = Alignment.Center
    ) {
        CupertinoSwitchVisual(checked = checked, enabled = enabled)
    }
}

@Composable
private fun CupertinoSwitchVisual(
    checked: Boolean,
    enabled: Boolean
) {
    val checkedTrackColor = IosGreen
    val uncheckedTrackColor = LoonSwitchUncheckedTrack
    val reduceMotion = LocalReduceMotion.current

    val trackColor by animateColorAsState(
        targetValue = if (checked) checkedTrackColor else uncheckedTrackColor,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = LoonMotion.normalMs),
        label = "switch_track_color"
    )

    // Thumb slide position: width is 51dp, thumb is 27dp, padding is 2dp on each side.
    val targetOffset = if (checked) 22.dp else 2.dp
    val thumbOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = LoonMotion.thumbDampingRatio, stiffness = LoonMotion.thumbStiffness)
        },
        label = "switch_thumb_offset"
    )

    Box(
        modifier = Modifier
            .size(LoonSizes.switchWidth, LoonSizes.switchHeight)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .size(LoonSizes.switchThumb)
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    spotColor = LoonExtraColors.shadowStrong
                )
                .clip(CircleShape)
                .background(LoonOnAccent)
        )
    }
}

// =============================================================================
// Standard Cupertino Navigation Bar
// =============================================================================

@Composable
fun CupertinoNavBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBackButton: Boolean = false,
    backLabel: String? = AppStrings.get("back", LocalAppLanguage.current),
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LoonBg,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LoonSizes.navBarHeight)
                    .padding(horizontal = LoonSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier.widthIn(min = 60.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (showBackButton && onBack != null) {
                        CupertinoNavBackButton(backLabel = backLabel, onBack = onBack)
                    }
                }

                CupertinoNavTitleColumn(
                    title = title,
                    subtitle = subtitle,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier.widthIn(min = 60.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (actions != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions
                        )
                    }
                }
            }

            HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun CupertinoNavBackButton(
    backLabel: String?,
    onBack: () -> Unit
) {
    val haptic = rememberIosHaptic()
    val lang = LocalAppLanguage.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(LoonRadii.control))
            .heightIn(min = LoonSizes.navBarHeight)
            .sizeIn(minWidth = LoonSizes.touchTargetMin)
            .iosFocusRing(RoundedCornerShape(LoonRadii.control))
            .iosPressable {
                haptic.lightTick()
                onBack()
            }
            .padding(horizontal = LoonSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBackIos,
            contentDescription = if (backLabel.isNullOrBlank()) AppStrings.get("back", lang) else null,
            tint = LoonInfoText,
            modifier = Modifier.size(17.dp)
        )
        if (!backLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = backLabel,
                color = LoonInfoText,
                style = LoonTextStyles.body()
            )
        }
    }
}

@Composable
private fun CupertinoNavTitleColumn(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = title,
            color = LoonTextPrimary,
            style = LoonTextStyles.navTitle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = LoonTextSecondary,
                style = LoonTextStyles.caption(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =============================================================================
// iOS Segmented Pill Control (For strategy groups in ProxiesScreen)
// =============================================================================

@Composable
fun <T> CupertinoSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    labelProvider: (T) -> String,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val haptic = rememberIosHaptic()
    val reduceMotion = LocalReduceMotion.current
    val selectedIndex = remember(items, selectedItem) { items.indexOf(selectedItem) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fontScale = LocalDensity.current.fontScale
    val visualHeight = (18f * fontScale + 14f).dp.coerceAtLeast(32.dp)

    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = LoonMotion.segmentedDampingRatio, stiffness = LoonMotion.segmentedStiffness)
        },
        label = "segmented_thumb_index"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = LoonSizes.touchTargetMin),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(visualHeight)
                .clip(RoundedCornerShape(LoonRadii.segmented))
                .background(LoonTertiaryBg)
                .padding(2.dp)
        ) {
            if (selectedIndex >= 0) {
                SegmentedThumb(
                    itemCount = items.size,
                    animatedIndex = animatedIndex,
                    isRtl = isRtl
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                SegmentedControlItem(
                    index = index,
                    isSelected = index == selectedIndex,
                    showDividerBefore = index > 0 && index != selectedIndex && (index - 1) != selectedIndex,
                    label = labelProvider(item),
                    onClick = {
                        if (index != selectedIndex) {
                            haptic.selection()
                            onItemSelected(item)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SegmentedThumb(
    itemCount: Int,
    animatedIndex: Float,
    isRtl: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(1f / itemCount)
            .fillMaxHeight()
            .graphicsLayer {
                val dir = if (isRtl) -1f else 1f
                translationX = dir * animatedIndex * size.width
            }
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(LoonRadii.segmentedThumb),
                spotColor = LoonExtraColors.shadowMedium
            )
            .clip(RoundedCornerShape(LoonRadii.segmentedThumb))
            .background(LoonCard)
            .border(
                0.5.dp,
                LoonCardBorder.copy(alpha = 0.6f),
                RoundedCornerShape(LoonRadii.segmentedThumb)
            )
    )
}

@Composable
private fun RowScope.SegmentedControlItem(
    index: Int,
    isSelected: Boolean,
    showDividerBefore: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDividerBefore) {
            Box(
                modifier = Modifier
                    .width(0.8.dp)
                    .height(12.dp)
                    .background(LoonTextMuted.copy(alpha = 0.35f))
            )
        } else if (index > 0) {
            Spacer(modifier = Modifier.width(0.8.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(LoonRadii.segmentedThumb))
                .iosFocusRing(RoundedCornerShape(LoonRadii.segmentedThumb))
                .selectable(
                    selected = isSelected,
                    role = Role.Tab,
                    onClick = onClick
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) LoonTextPrimary else LoonTextSecondary,
                style = LoonTextStyles.segmentedLabel(strong = isSelected),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =============================================================================
// Authentic Cupertino Search Bar (SF-styled pill input with 48dp accessible clear target)
// =============================================================================

@Composable
fun CupertinoSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = AppStrings.get("search_placeholder", LocalAppLanguage.current),
    leadingIcon: ImageVector = Icons.Default.Search
) {
    val haptic = rememberIosHaptic()
    val visualHeight = (16f * LocalDensity.current.fontScale + 22f).dp.coerceAtLeast(38.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = visualHeight),
            shape = RoundedCornerShape(10.dp),
            color = LoonTertiaryBg
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = visualHeight)
                    .padding(
                        start = 10.dp,
                        end = if (value.isNotEmpty()) LoonSizes.touchTargetMin else 10.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = LoonTextSecondary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = LoonTextMuted,
                            style = LoonTextStyles.search()
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = LoonTextStyles.search().copy(
                            color = LoonTextPrimary,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(LoonInfoText),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (value.isNotEmpty()) {
            SearchClearButton(
                modifier = Modifier.align(Alignment.CenterEnd),
                onClear = {
                    haptic.lightTick()
                    onValueChange("")
                }
            )
        }
    }
}

@Composable
private fun SearchClearButton(
    modifier: Modifier = Modifier,
    onClear: () -> Unit
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
            .iosFocusRing(RoundedCornerShape(LoonRadii.control))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClear
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(LoonTextSecondary.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = AppStrings.get("clear", LocalAppLanguage.current),
                tint = LoonOnAccent,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

// =============================================================================
// Authentic Cupertino Text Field (iOS grouped form input)
// =============================================================================

@Composable
fun CupertinoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = LoonTertiaryBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(8.dp))
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    Text(
                        text = placeholder,
                        color = LoonTextMuted,
                        style = LoonTextStyles.input()
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    textStyle = LoonTextStyles.input().copy(
                        color = LoonTextPrimary,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(LoonInfoText),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (trailingIcon != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailingIcon()
            }
        }
    }
}

// =============================================================================
// Authentic Cupertino Button (Filled / Tinted / Plain / Destructive)
// =============================================================================

enum class CupertinoButtonStyle {
    Filled,
    Tinted,
    Plain,
    Destructive
}

@Composable
fun CupertinoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CupertinoButtonStyle = CupertinoButtonStyle.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val haptic = rememberIosHaptic()

    val (bg, contentColor) = when (style) {
        CupertinoButtonStyle.Filled -> Pair(IosBlue, LoonOnAccent)
        CupertinoButtonStyle.Tinted -> Pair(IosBlue.copy(alpha = 0.14f), LoonInfoText)
        CupertinoButtonStyle.Plain -> Pair(Color.Transparent, LoonInfoText)
        CupertinoButtonStyle.Destructive -> Pair(RedDanger.copy(alpha = 0.14f), LoonDangerText)
    }

    Surface(
        shape = RoundedCornerShape(LoonRadii.control),
        color = if (enabled) bg else bg.copy(alpha = 0.4f),
        modifier = modifier
            .iosPressable(
                enabled = enabled,
                pressedScale = LoonMotion.pressScaleStrong,
                focusShape = RoundedCornerShape(LoonRadii.control)
            ) {
                haptic.lightTick()
                onClick()
            }
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = LoonSizes.buttonHeight)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(LoonSizes.iconSmall)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
                style = LoonTextStyles.buttonSmall()
            )
        }
    }
}

// =============================================================================
// Shared detail row (label on the left, value + optional copy action on the right)
//
// UI_GUIDELINES.md §4.4 forbids a "second Cell": the rules, request-log and DNS inspectors
// all need this row, so it lives here once and every screen imports it.
// =============================================================================

@Composable
fun CupertinoDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LoonTextPrimary,
    showCopy: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    val lang = LocalAppLanguage.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = LoonTextSecondary,
            style = LoonTextStyles.callout()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f, fill = false)
        ) {
            Text(
                text = value,
                color = valueColor,
                style = LoonTextStyles.calloutLarge(),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (showCopy && onCopy != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                        .iosFocusRing(RoundedCornerShape(LoonRadii.control))
                        .clickable(onClick = onCopy),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = AppStrings.get("copy_desc", lang),
                        tint = LoonInfoText,
                        modifier = Modifier.size(LoonSizes.iconSmall)
                    )
                }
            }
        }
    }
}

/**
 * Shared staggered cross-fade and optional eased size transition for compact status slots,
 * fading out old content before fading in new content (or snapping when reduced motion is enabled).
 */
internal fun AnimatedContentTransitionScope<*>.statusFadeTransform(
    reduceMotion: Boolean,
    animateSize: Boolean = true
): ContentTransform {
    val content = if (reduceMotion) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        fadeIn(
            tween(
                durationMillis = LoonMotion.statusFadeInMs,
                delayMillis = LoonMotion.statusFadeInDelayMs,
                easing = FastOutSlowInEasing
            )
        ) togetherWith fadeOut(
            tween(
                durationMillis = LoonMotion.statusFadeOutMs,
                easing = LinearEasing
            )
        )
    }
    return if (animateSize) {
        content.using(
            SizeTransform(clip = false) { _, _ ->
                if (reduceMotion) {
                    snap()
                } else {
                    tween(durationMillis = LoonMotion.statusFadeInMs, easing = FastOutSlowInEasing)
                }
            }
        )
    } else {
        content.using(SizeTransform(clip = false) { _, _ -> snap() })
    }
}

/**
 * Visual states shared by inline speed-test latency indicators across cards and sheets.
 */
internal sealed interface LatencySlotState {
    data object Testing : LatencySlotState
    data class Measured(val delayMs: Int) : LatencySlotState
    data object Timeout : LatencySlotState
    data object Failed : LatencySlotState
    data object Idle : LatencySlotState
}

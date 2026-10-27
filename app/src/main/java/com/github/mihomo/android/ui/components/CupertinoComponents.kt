package com.github.mihomo.android.ui.components

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import com.github.mihomo.android.ui.theme.LocalAppFont
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.data.AppFont
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.BasicTextField
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.nativeClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

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
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, ring, shape) else Modifier)
}

fun Modifier.iosPressable(
    enabled: Boolean = true,
    pressedScale: Float = LoonMotion.pressScale,
    focusShape: Shape? = null,
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
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
            } else Modifier
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
        .onFocusChanged { focused = it.isFocused }
        .then(if (focusShape != null) Modifier.iosFocusRing(focusShape) else Modifier)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
            } else Modifier
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
    content: @Composable ColumnScope.() -> Unit
) {
    val displayHeader = header ?: title
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        if (!displayHeader.isNullOrBlank()) {
            Text(
                text = displayHeader.uppercase(),
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
                text = header.uppercase(),
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
                CupertinoIndentedDivider(startIndent = dividerStartIndent)
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.iosPressable(enabled = true) {
                        haptic.selection()
                        onClick()
                    }
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Optional Squircle Icon Badge
            if (customIcon != null) {
                Box(
                    modifier = Modifier
                        .size(LoonSizes.iconBadge)
                        .clip(RoundedCornerShape(LoonRadii.iconBadge)),
                    contentAlignment = Alignment.Center
                ) {
                    customIcon()
                }
                Spacer(modifier = Modifier.width(13.dp))
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
                Spacer(modifier = Modifier.width(13.dp))
            }

            // Title & optional Subtitle
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

        // Trailing Content (Value / Chevron / Switch / Custom)
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
}

/**
 * Text-aligned indented hairline divider for iOS Inset Grouped lists.
 * Indents past the leading icon squircle so it starts aligned with the cell text.
 */
@Composable
fun CupertinoIndentedDivider(
    startIndent: Dp = 59.dp,
    endIndent: Dp = 0.dp
) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent, end = endIndent),
        color = LoonSeparator,
        thickness = 0.5.dp
    )
}

@Composable
fun CupertinoDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 59.dp,
    endIndent: Dp = 0.dp
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
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
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

    // The visible track stays 51x31; the toggle (and its semantics / touch target) is the
    // 48dp-high wrapper so TalkBack announces a real switch and the tap area is accessible.
    Box(
        modifier = modifier
            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
            .iosFocusRing(CircleShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(LoonSizes.switchWidth, LoonSizes.switchHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(trackColor),
            contentAlignment = Alignment.CenterStart
        ) {
            // Thumb circle with subtle iOS drop shadow
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
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
    val haptic = rememberIosHaptic()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LoonBg,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LoonSizes.navBarHeight)
                    .padding(horizontal = LoonSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Back button or empty placeholder
                Box(
                    modifier = Modifier.widthIn(min = 60.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (showBackButton && onBack != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(LoonRadii.control))
                                .heightIn(min = LoonSizes.navBarHeight)
                                // UI-A11Y-01: the back control must also be 48dp wide.
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
                                contentDescription = AppStrings.get("back", LocalAppLanguage.current),
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
                }

                // Center: Title
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        color = LoonTextPrimary,
                        style = LoonTextStyles.navTitle(),
                        maxLines = 1
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = LoonTextSecondary,
                            style = LoonTextStyles.caption(),
                            maxLines = 1
                        )
                    }
                }

                // Right: Actions
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

            // Hairline separator underneath
            HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
        }
    }
}

// =============================================================================
// Cupertino Action Sheet & Single-Selection Dialog (Replacing Android RadioButtons)
// =============================================================================

data class CupertinoActionSheetItem(
    val text: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CupertinoActionSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    message: String? = null,
    actions: List<CupertinoActionSheetItem>,
    cancelText: String = AppStrings.get("cancel", LocalAppLanguage.current)
) {
    val haptic = rememberIosHaptic()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = LoonTextPrimary,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(LoonRadii.card),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp)
                .padding(top = 8.dp, bottom = 16.dp)
                .semantics { paneTitle = title ?: cancelText },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Actions container
            Surface(
                shape = RoundedCornerShape(LoonRadii.card),
                color = LoonCard,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!title.isNullOrBlank() || !message.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (!title.isNullOrBlank()) {
                                Text(
                                    text = title,
                                    style = LoonTextStyles.actionSheetTitle(),
                                    color = LoonTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                            if (!message.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = message,
                                    style = LoonTextStyles.caption(),
                                    color = LoonTextMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
                    }

                    actions.forEachIndexed { index, action ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .iosHighlightPressable {
                                    haptic.selection()
                                    onDismissRequest()
                                    action.onClick()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = action.text,
                                style = LoonTextStyles.actionLabel(),
                                color = if (action.isDestructive) LoonDangerText else LoonInfoText,
                                textAlign = TextAlign.Center
                            )
                        }
                        if (index < actions.size - 1) {
                            HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
                        }
                    }
                }
            }

            // Standalone Cancel button
            Surface(
                shape = RoundedCornerShape(LoonRadii.card),
                color = LoonCard,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .iosHighlightPressable {
                        haptic.lightTick()
                        onDismissRequest()
                    }
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cancelText,
                        style = LoonTextStyles.actionLabelStrong(),
                        color = LoonInfoText,
                        textAlign = TextAlign.Center
                    )
                }
            }
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
    // -1 (selection not in the list, e.g. a persisted legacy value) must not be coerced to
    // zero: highlighting the first segment would misrepresent the actual selection.
    val selectedIndex = remember(items, selectedItem) {
        items.indexOf(selectedItem)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(LoonRadii.segmented))
            .background(LoonTertiaryBg)
            .padding(2.dp)
    ) {
        val totalWidth = this.maxWidth
        val segmentWidth = totalWidth / items.size

        // iOS spring sliding indicator (Single physical Thumb)
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex.coerceAtLeast(0),
            animationSpec = if (reduceMotion) {
                snap()
            } else {
                spring(dampingRatio = LoonMotion.segmentedDampingRatio, stiffness = LoonMotion.segmentedStiffness)
            },
            label = "segmented_thumb_offset"
        )

        // The Sliding White Pill Thumb with subtle iOS shadow & border
        if (selectedIndex >= 0) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
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

        // Row of items & separators
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                // Subtle separator divider between unselected adjacent items
                val showDividerBefore = index > 0 && index != selectedIndex && (index - 1) != selectedIndex

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
                                onClick = {
                                    if (index != selectedIndex) {
                                        haptic.selection()
                                        onItemSelected(item)
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = labelProvider(item),
                            color = if (isSelected) LoonTextPrimary else LoonTextSecondary,
                            style = if (isSelected) {
                                LoonTextStyles.segmentedLabelStrong()
                            } else {
                                LoonTextStyles.segmentedLabel()
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// Authentic Cupertino Alert & Confirm Dialog (270dp iOS Modal Alert)
// =============================================================================

@Composable
fun CupertinoAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    message: String? = null,
    confirmText: String = AppStrings.get("confirm", LocalAppLanguage.current),
    dismissText: String? = AppStrings.get("cancel", LocalAppLanguage.current),
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = onDismissRequest,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val haptic = rememberIosHaptic()

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(LoonRadii.dialog),
            color = LoonCard,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
            modifier = Modifier
                .widthIn(min = 270.dp, max = 310.dp)
                .fillMaxWidth(0.85f)
                .semantics { paneTitle = title }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title and message
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 18.dp,
                            end = 18.dp,
                            top = LoonSpacing.xl,
                            bottom = if (content != null) LoonSpacing.md else 18.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        color = LoonTextPrimary,
                        style = LoonTextStyles.dialogTitle(),
                        textAlign = TextAlign.Center
                    )

                    if (!message.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(LoonSpacing.xs))
                        Text(
                            text = message,
                            color = LoonTextSecondary,
                            style = LoonTextStyles.dialogMessage(),
                            textAlign = TextAlign.Center
                        )
                    }

                    if (content != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        content()
                    }
                }

                // Horizontal Hairline Divider
                HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)

                // Buttons
                if (dismissText != null && onDismiss != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LoonSizes.touchTargetMin),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Dismiss button (Cancel)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .iosHighlightPressable {
                                    haptic.lightTick()
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dismissText,
                                color = LoonInfoText,
                                style = LoonTextStyles.button()
                            )
                        }

                        // Vertical Hairline Divider
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(LoonSeparator)
                        )

                        // Confirm button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .iosHighlightPressable {
                                    haptic.lightTick()
                                    onConfirm()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = confirmText,
                                color = if (isDestructive) LoonDangerText else LoonInfoText,
                                style = LoonTextStyles.button()
                            )
                        }
                    }
                } else {
                    // Single Confirm / Acknowledge button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LoonSizes.touchTargetMin)
                            .iosHighlightPressable {
                                haptic.lightTick()
                                onConfirm()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = confirmText,
                            color = if (isDestructive) LoonDangerText else LoonInfoText,
                            style = LoonTextStyles.button()
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// Authentic Cupertino Search Bar (SF-styled 38dp pill input with quick clear)
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
        shape = RoundedCornerShape(10.dp),
        color = LoonTertiaryBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
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
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = LoonTextMuted,
                        fontSize = LoonTypeScale.subhead
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = LoonTextPrimary,
                        fontSize = LoonTypeScale.subhead,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(IosBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(LoonTextSecondary.copy(alpha = 0.6f))
                        .clickable {
                            haptic.lightTick()
                            onValueChange("")
                        },
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
                        fontSize = LoonTypeScale.subheadLarge
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    visualTransformation = visualTransformation,
                    textStyle = LocalTextStyle.current.copy(
                        color = LoonTextPrimary,
                        fontSize = LoonTypeScale.subheadLarge,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(IosBlue),
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
// Authentic Cupertino Modal Bottom Sheet (16dp squircle, slim capsule grabber, modal nav bar)
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CupertinoModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    title: String? = null,
    doneText: String? = AppStrings.get("action_done", LocalAppLanguage.current),
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = LoonCard,
        shape = RoundedCornerShape(topStart = LoonRadii.sheet, topEnd = LoonRadii.sheet),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.5.dp)
                    .clip(CircleShape)
                    .background(LoonTextMuted.copy(alpha = 0.28f))
            )
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp)
                .semantics { if (title != null) paneTitle = title }
        ) {
            val showNavBar = title != null || doneText != null
            val hasActions = leadingAction != null || trailingAction != null
            if (showNavBar || hasActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LoonSizes.touchTargetMin)
                        .padding(horizontal = LoonSpacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.widthIn(min = LoonSizes.touchTargetMin),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        leadingAction?.invoke()
                    }

                    if (title != null) {
                        Text(
                            text = title,
                            style = LoonTextStyles.navTitle(),
                            color = LoonTextPrimary,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier.widthIn(min = LoonSizes.touchTargetMin),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (trailingAction != null) {
                            trailingAction()
                        } else if (doneText != null) {
                            val haptic = rememberIosHaptic()
                            Box(
                                modifier = Modifier
                                    .height(LoonSizes.touchTargetMin)
                                    .clip(RoundedCornerShape(LoonRadii.control))
                                    .iosFocusRing(RoundedCornerShape(LoonRadii.control))
                                    .iosPressable {
                                        haptic.lightTick()
                                        onDismissRequest()
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = doneText,
                                    color = LoonInfoText,
                                    style = LoonTextStyles.button()
                                )
                            }
                        }
                    }
                }
            }

            content()
        }
    }
}

// =============================================================================
// iOS Dynamic Pill HUD / Toast (Top floating island capsule with haptics)
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
    private val nextId = java.util.concurrent.atomic.AtomicLong(0)
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

    fun dismiss() {
        _currentToast.value = null
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

    LaunchedEffect(toastData?.id) {
        val current = toastData ?: return@LaunchedEffect
        haptic.lightTick()
        delay(current.durationMs.milliseconds)
        CupertinoToast.dismiss()
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = toastData != null,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(LoonMotion.normalMs)) +
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = spring(
                        dampingRatio = LoonMotion.toastDampingRatio,
                        stiffness = LoonMotion.toastStiffness
                    )
                ),
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(LoonMotion.fastMs)) +
                androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(LoonMotion.fastMs)
                ),
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 10.dp)
            .zIndex(9999f)
    ) {
        val data = toastData ?: return@AnimatedVisibility
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                shape = CircleShape,
                color = LocalLoonColors.current.card.copy(alpha = 0.94f),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, LocalLoonColors.current.cardBorder),
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
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 9.dp),
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
}

// =============================================================================
// Clipboard helper
//
// Compose 1.12 deprecated LocalClipboardManager in favour of LocalClipboard, whose
// setClipEntry is a suspend function. The copy actions all live inside non-suspend
// click callbacks, so this helper bridges to the platform ClipboardManager without
// making every call site a coroutine.
// =============================================================================

/**
 * Copies [text] to the system clipboard.
 *
 * Uses the platform clipboard directly: the Compose [androidx.compose.ui.platform.Clipboard]
 * interface is suspend-based, which cannot be called from the non-suspend click handlers
 * this app uses for its copy actions. [nativeClipboardManager] is the supported way to
 * reach the platform manager from a [androidx.compose.ui.platform.Clipboard].
 */
@Composable
fun rememberClipboardCopy(): (String) -> Unit {
    val nativeClipboard = LocalClipboard.current.nativeClipboardManager
    return remember(nativeClipboard) {
        { text: String ->
            runCatching {
                nativeClipboard.setText(AnnotatedString(text))
            }
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
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = AppStrings.get("copy_desc", lang),
                    tint = IosBlue,
                    modifier = Modifier
                        .size(LoonSizes.iconSmall)
                        .clickable(onClick = onCopy)
                        // UI-A11Y-01: keep the 16dp icon, widen the touch target to 48dp.
                        .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                )
            }
        }
    }
}




package com.github.mihomo.android.ui.components

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
    fun lightTick() {
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun mediumImpact() {
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun impact() {
        mediumImpact()
    }

    fun selection() {
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}

// =============================================================================
// iOS Pressable Modifier (Instant micro-scale & highlight, replacing ink ripples)
// =============================================================================

fun Modifier.iosPressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.982f,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) pressedScale else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ios_press_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
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
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val defaultHighlight = LocalLoonColors.current.tertiaryBg.copy(alpha = 0.65f)
    val color = highlightColor ?: defaultHighlight

    this
        .background(if (enabled && isPressed) color else Color.Transparent)
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
    val transition = rememberInfiniteTransition(label = "cupertino_spinner")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = spokeCount.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cupertino_spinner_step"
    )

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
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp)
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = 0.5.dp,
                    color = LoonCardBorder,
                    shape = RoundedCornerShape(14.dp)
                ),
            color = LoonCard,
            shape = RoundedCornerShape(14.dp)
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
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, end = 14.dp)
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
                        .size(30.dp)
                        .clip(RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    customIcon()
                }
                Spacer(modifier = Modifier.width(13.dp))
            } else if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
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
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.2).sp
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = LoonTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 15.sp
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
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
                if (showChevron) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = LoonChevronTint,
                        modifier = Modifier.size(16.dp)
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
    val haptic = rememberIosHaptic()
    val checkedTrackColor = IosGreen
    val uncheckedTrackColor = LoonSwitchUncheckedTrack

    val trackColor by animateColorAsState(
        targetValue = if (checked) checkedTrackColor else uncheckedTrackColor,
        animationSpec = tween(durationMillis = 200),
        label = "switch_track_color"
    )

    // Thumb slide position: width is 51dp, thumb is 27dp, padding is 2dp on each side.
    // Unchecked offset: 2dp. Checked offset: 51 - 27 - 2 = 22dp.
    val targetOffset = if (checked) 22.dp else 2.dp
    val thumbOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = 650f
        ),
        label = "switch_thumb_offset"
    )

    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(RoundedCornerShape(15.5.dp))
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled
            ) {
                haptic.lightTick()
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Thumb circle with subtle iOS drop shadow
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(27.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    spotColor = Color(0x33000000)
                )
                .clip(CircleShape)
                .background(Color.White)
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
    backLabel: String? = "返回",
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
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
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
                                .clip(RoundedCornerShape(8.dp))
                                .iosPressable {
                                    haptic.lightTick()
                                    onBack()
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBackIos,
                                contentDescription = "返回",
                                tint = IosBlue,
                                modifier = Modifier.size(17.dp)
                            )
                            if (!backLabel.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = backLabel,
                                    color = IosBlue,
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Normal
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
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp,
                        maxLines = 1
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = LoonTextSecondary,
                            fontSize = 11.sp,
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

@Composable
fun CupertinoActionSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    message: String? = null,
    actions: List<CupertinoActionSheetItem>,
    cancelText: String = "取消"
) {
    val haptic = rememberIosHaptic()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismissRequest() }
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* prevent click-through */ },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Actions container
                Surface(
                    shape = RoundedCornerShape(14.dp),
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
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = LoonTextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                if (!message.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = message,
                                        fontSize = 12.sp,
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
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = if (action.isDestructive) RedDanger else IosBlue,
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
                    shape = RoundedCornerShape(14.dp),
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
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = IosBlue,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun <T> CupertinoSelectDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = rememberIosHaptic()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = LoonCard,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                // Header
                Text(
                    text = title,
                    color = LoonTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)

                // Option rows
                options.forEachIndexed { index, (key, label) ->
                    val isSelected = key == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .iosHighlightPressable {
                                haptic.selection()
                                onSelect(key)
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            fontSize = 15.sp,
                            color = if (isSelected) IosBlue else LoonTextPrimary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = IosBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (index < options.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 20.dp),
                            color = LoonSeparator,
                            thickness = 0.5.dp
                        )
                    }
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
    val selectedIndex = remember(items, selectedItem) {
        items.indexOf(selectedItem).coerceAtLeast(0)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(8.5.dp))
            .background(LoonTertiaryBg)
            .padding(2.dp)
    ) {
        val totalWidth = maxWidth
        val segmentWidth = totalWidth / items.size

        // iOS spring sliding indicator (Single physical Thumb)
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = 0.84f,
                stiffness = 380f
            ),
            label = "segmented_thumb_offset"
        )

        // The Sliding White Pill Thumb with subtle iOS shadow & border
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(elevation = 2.dp, shape = RoundedCornerShape(7.dp), spotColor = Color(0x28000000))
                .clip(RoundedCornerShape(7.dp))
                .background(LoonCard)
                .border(0.5.dp, LoonCardBorder.copy(alpha = 0.6f), RoundedCornerShape(7.dp))
        )

        // Row of items & separators
        Row(
            modifier = Modifier.fillMaxSize(),
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
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (index != selectedIndex) {
                                    haptic.selection()
                                    onItemSelected(item)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = labelProvider(item),
                            color = if (isSelected) LoonTextPrimary else LoonTextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
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
    confirmText: String = "确定",
    dismissText: String? = "取消",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = onDismissRequest,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val haptic = rememberIosHaptic()

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = LoonCard,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
            modifier = Modifier
                .widthIn(min = 270.dp, max = 310.dp)
                .fillMaxWidth(0.85f)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title and message
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = if (content != null) 12.dp else 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        color = LoonTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )

                    if (!message.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message,
                            color = LoonTextSecondary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
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
                            .height(44.dp),
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
                                color = IosBlue,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
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
                                color = if (isDestructive) RedDanger else IosBlue,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    // Single Confirm / Acknowledge button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .iosHighlightPressable {
                                haptic.lightTick()
                                onConfirm()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = confirmText,
                            color = if (isDestructive) RedDanger else IosBlue,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
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
    placeholder: String = "搜索",
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
                        fontSize = 14.sp
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = LoonTextPrimary,
                        fontSize = 14.sp,
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
                        contentDescription = "清除",
                        tint = Color.White,
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
                        fontSize = 14.5.sp
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    textStyle = TextStyle(
                        color = LoonTextPrimary,
                        fontSize = 14.5.sp,
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
        CupertinoButtonStyle.Filled -> Pair(IosBlue, Color.White)
        CupertinoButtonStyle.Tinted -> Pair(IosBlue.copy(alpha = 0.14f), IosBlue)
        CupertinoButtonStyle.Plain -> Pair(Color.Transparent, IosBlue)
        CupertinoButtonStyle.Destructive -> Pair(RedDanger.copy(alpha = 0.14f), RedDanger)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) bg else bg.copy(alpha = 0.4f),
        modifier = modifier
            .iosPressable(enabled = enabled, pressedScale = 0.97f) {
                haptic.lightTick()
                onClick()
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
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
    doneText: String? = "完成",
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = LoonCard,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
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
                .padding(bottom = 16.dp)
        ) {
            val showNavBar = title != null || doneText != null
            val hasActions = leadingAction != null || trailingAction != null
            if (showNavBar || hasActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.widthIn(min = 44.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        leadingAction?.invoke()
                    }

                    if (title != null) {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LoonTextPrimary,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier.widthIn(min = 44.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (trailingAction != null) {
                            trailingAction()
                        } else if (doneText != null) {
                            val haptic = rememberIosHaptic()
                            Box(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .iosPressable {
                                        haptic.lightTick()
                                        onDismissRequest()
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = doneText,
                                    color = IosBlue,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
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
    val id: Long = System.currentTimeMillis(),
    val message: String,
    val icon: ImageVector? = null,
    val isDestructive: Boolean = false,
    val durationMs: Long = 2000L
)

object CupertinoToast {
    private val _currentToast = MutableStateFlow<CupertinoToastData?>(null)
    val currentToast: StateFlow<CupertinoToastData?> = _currentToast.asStateFlow()

    fun show(
        message: String,
        icon: ImageVector? = null,
        isDestructive: Boolean = false,
        durationMs: Long = 2000L
    ) {
        _currentToast.value = CupertinoToastData(
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
        delay(current.durationMs)
        CupertinoToast.dismiss()
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = toastData != null,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(180)) +
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = 500f
                    )
                ),
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(150)) +
                androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(150)
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
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val iconVector = data.icon ?: if (data.isDestructive) Icons.Default.Clear else Icons.Default.Check
                    val iconTint = if (data.isDestructive) RedDanger else IosGreen
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = data.message,
                        color = LocalLoonColors.current.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}




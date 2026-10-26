package com.github.mihomo.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.*

@Composable
fun LoonTrafficCard(
    modifier: Modifier = Modifier,
    title: String,
    speed: String,
    isUpload: Boolean
) {
    val gradient = if (isUpload) {
        Brush.horizontalGradient(listOf(LoonGreen, LoonExtraColors.greenBright))
    } else {
        Brush.horizontalGradient(listOf(LoonBlue, LoonExtraColors.violet))
    }

    Surface(
        modifier = modifier
            .height(82.dp)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(18.dp),
                spotColor = if (isUpload) LoonExtraColors.glowGreen else LoonExtraColors.glowBlue
            ),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(gradient)
        ) {
            // Primary Information (Title & Real-time speed)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp, end = 56.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = LoonTypeScale.footnote,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (speed.isBlank()) "--" else speed,
                    color = Color.White,
                    fontSize = LoonTypeScale.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            // iOS-Style Rounded Traffic Arrow (SF Symbols Morphology, Rounded Curves, Frosted Translucency)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                IosTrafficArrow(
                    isUpload = isUpload,
                    modifier = Modifier.size(width = 32.dp, height = 38.dp)
                )
            }
        }
    }
}

/**
 * Modern iOS SF Symbols rounded traffic arrow.
 * Features organic rounded apex, rounded wingtips, smooth concave fillets, and rounded base.
 * Employs frosted translucent gradients and subtle high-clarity contour for authentic iOS vibrancy.
 */
@Composable
private fun IosTrafficArrow(
    isUpload: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f

        // iOS rounded proportions
        val rApex = 2.4.dp.toPx()
        val rWing = 2.4.dp.toPx()
        val rInner = 2.8.dp.toPx()
        val rBase = 2.6.dp.toPx()

        val wingW = w * 0.92f
        val wingLeft = cx - wingW * 0.5f
        val wingRight = cx + wingW * 0.5f

        val stemW = w * 0.36f
        val stemLeft = cx - stemW * 0.5f
        val stemRight = cx + stemW * 0.5f

        val path = Path()

        if (isUpload) {
            // ==================== UPLOAD (Points UP) ====================
            val topY = 1.5.dp.toPx()
            val wingY = h * 0.52f
            val bottomY = h - 1.5.dp.toPx()

            path.apply {
                // 1. Rounded Apex
                moveTo(cx - rApex * 0.8f, topY + rApex * 0.8f)
                quadraticTo(cx, topY, cx + rApex * 0.8f, topY + rApex * 0.8f)

                // 2. Right downward wing slope
                lineTo(wingRight - rWing * 0.7f, wingY - rWing * 0.7f)

                // 3. Right wingtip curve
                quadraticTo(wingRight, wingY, wingRight - rWing, wingY)

                // 4. Horizontal notch in towards stem
                lineTo(stemRight + rInner, wingY)

                // 5. Right inner fillet curve (smooth concave corner)
                quadraticTo(stemRight, wingY, stemRight, wingY + rInner)

                // 6. Right stem vertical edge
                lineTo(stemRight, bottomY - rBase)

                // 7. Bottom-right base curve
                quadraticTo(stemRight, bottomY, stemRight - rBase, bottomY)

                // 8. Base horizontal edge
                lineTo(stemLeft + rBase, bottomY)

                // 9. Bottom-left base curve
                quadraticTo(stemLeft, bottomY, stemLeft, bottomY - rBase)

                // 10. Left stem vertical edge
                lineTo(stemLeft, wingY + rInner)

                // 11. Left inner fillet curve (smooth concave corner)
                quadraticTo(stemLeft, wingY, stemLeft - rInner, wingY)

                // 12. Horizontal notch out towards wing
                lineTo(wingLeft + rWing, wingY)

                // 13. Left wingtip curve
                quadraticTo(wingLeft, wingY, wingLeft + rWing * 0.7f, wingY - rWing * 0.7f)

                // 14. Left upward wing slope back to apex
                lineTo(cx - rApex * 0.8f, topY + rApex * 0.8f)
                close()
            }
        } else {
            // ==================== DOWNLOAD (Points DOWN) ====================
            val topY = 1.5.dp.toPx()
            val wingY = h * 0.48f
            val bottomY = h - 1.5.dp.toPx()

            path.apply {
                // 1. Top-left stem base curve
                moveTo(stemLeft, topY + rBase)
                quadraticTo(stemLeft, topY, stemLeft + rBase, topY)

                // 2. Top base horizontal edge
                lineTo(stemRight - rBase, topY)

                // 3. Top-right stem base curve
                quadraticTo(stemRight, topY, stemRight, topY + rBase)

                // 4. Right stem vertical edge down to fillet
                lineTo(stemRight, wingY - rInner)

                // 5. Right inner fillet curve (smooth concave corner)
                quadraticTo(stemRight, wingY, stemRight + rInner, wingY)

                // 6. Horizontal notch out towards wing
                lineTo(wingRight - rWing, wingY)

                // 7. Right wingtip curve
                quadraticTo(wingRight, wingY, wingRight - rWing * 0.7f, wingY + rWing * 0.7f)

                // 8. Right downward slope to apex
                lineTo(cx + rApex * 0.8f, bottomY - rApex * 0.8f)

                // 9. Bottom apex curve
                quadraticTo(cx, bottomY, cx - rApex * 0.8f, bottomY - rApex * 0.8f)

                // 10. Left upward slope to wing
                lineTo(wingLeft + rWing * 0.7f, wingY + rWing * 0.7f)

                // 11. Left wingtip curve
                quadraticTo(wingLeft, wingY, wingLeft + rWing, wingY)

                // 12. Horizontal notch in towards stem
                lineTo(stemLeft - rInner, wingY)

                // 13. Left inner fillet curve (smooth concave corner)
                quadraticTo(stemLeft, wingY, stemLeft, wingY - rInner)

                // 14. Left stem vertical edge back to top
                lineTo(stemLeft, topY + rBase)
                close()
            }
        }

        // Frosted translucent body fill (reveals vibrant card gradient underneath)
        val fillBrush = Brush.verticalGradient(
            colors = if (isUpload) {
                listOf(
                    Color.White.copy(alpha = 0.78f),
                    Color.White.copy(alpha = 0.45f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.45f),
                    Color.White.copy(alpha = 0.78f)
                )
            },
            startY = 0f,
            endY = h
        )
        drawPath(path = path, brush = fillBrush)

        // Crisp semi-translucent contour (clean iOS Vibrancy outline)
        val strokeBrush = Brush.verticalGradient(
            colors = if (isUpload) {
                listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color.White.copy(alpha = 0.65f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.65f),
                    Color.White.copy(alpha = 0.95f)
                )
            },
            startY = 0f,
            endY = h
        )
        drawPath(
            path = path,
            brush = strokeBrush,
            style = Stroke(
                width = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
fun LoonShortcutCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    isEditing: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val haptic = rememberIosHaptic()
    Surface(
        modifier = modifier
            .height(118.dp)
            .shadow(1.5.dp, RoundedCornerShape(18.dp), spotColor = LoonExtraColors.shadowHairline)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 0.5.dp,
                color = LoonCardBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .iosPressable(enabled = !isEditing, pressedScale = 0.982f) {
                haptic.lightTick()
                onClick()
            },
        color = LoonCard
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Text info on top-left
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, top = 14.dp, end = 56.dp)
            ) {
                Text(
                    text = title,
                    color = LoonTextPrimary,
                    fontSize = LoonTypeScale.body,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        color = LoonTextSecondary,
                        fontSize = LoonTypeScale.footnote,
                        maxLines = 1
                    )
                }
            }

            // Bottom-left: clean chevron '>' button or drag handle (no background color block)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isEditing) {
                    val handleColor = LoonTextSecondary
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val stroke = 2.dp.toPx()
                        val w = size.width
                        val h = size.height
                        val color = handleColor
                        drawLine(color, Offset(0f, h * 0.35f), Offset(w, h * 0.35f), stroke, StrokeCap.Round)
                        drawLine(color, Offset(0f, h * 0.65f), Offset(w, h * 0.65f), stroke, StrokeCap.Round)
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = LoonTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Bottom-right large floating illustration
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp)
                    .size(54.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                icon()
            }

            // Red minus/remove button badge at top-right in edit mode
            if (isEditing && onDelete != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(IosRed)
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u2212",
                        color = Color.White,
                        fontSize = LoonTypeScale.body,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// iOS/Loon Style High-Fidelity Canvas Illustrations
// -------------------------------------------------------------

@Composable
fun RequestLogsIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Main document paper card (vibrant violet-purple with subtle lighting)
            val docGrad = Brush.verticalGradient(
                colors = listOf(LoonExtraColors.purple, LoonExtraColors.purpleDark),
                startY = h * 0.16f,
                endY = h * 0.84f
            )
            drawRoundRect(
                brush = docGrad,
                topLeft = Offset(w * 0.18f, h * 0.16f),
                size = Size(w * 0.64f, h * 0.68f),
                cornerRadius = CornerRadius(8.dp.toPx())
            )

            // Three white data lines on document body (1:1 identical to original Loon image)
            // Top line: shorter and bolder
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(w * 0.28f, h * 0.32f),
                size = Size(w * 0.24f, h * 0.055f),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            // Middle line: longer
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(w * 0.28f, h * 0.45f),
                size = Size(w * 0.44f, h * 0.055f),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            // Bottom line: medium length
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(w * 0.28f, h * 0.58f),
                size = Size(w * 0.32f, h * 0.055f),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}

@Composable
fun ProfilesIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cardW = w * 0.62f
            val cardH = h * 0.52f
            val cornerRadius = CornerRadius(5.5.dp.toPx())

            // Two stacked card layers (from back to front)
            // 1. Back card (upper-right)
            drawRoundRect(
                color = LoonExtraColors.bluePale,
                topLeft = Offset(w * 0.22f, h * 0.18f),
                size = Size(cardW, cardH),
                cornerRadius = cornerRadius
            )

            // Subtle drop shadow under front card for depth separation
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.08f),
                topLeft = Offset(w * 0.12f, h * 0.30f + 1.dp.toPx()),
                size = Size(cardW, cardH),
                cornerRadius = cornerRadius
            )

            // 2. Front card (blue gradient)
            val frontGradient = Brush.linearGradient(
                colors = listOf(LoonExtraColors.blue, LoonExtraColors.blueDark),
                start = Offset(w * 0.12f, h * 0.30f),
                end = Offset(w * 0.12f + cardW, h * 0.30f + cardH)
            )
            drawRoundRect(
                brush = frontGradient,
                topLeft = Offset(w * 0.12f, h * 0.30f),
                size = Size(cardW, cardH),
                cornerRadius = cornerRadius
            )

            // White lines on front card
            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(w * 0.20f, h * 0.45f),
                size = Size(w * 0.38f, h * 0.07f),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.6f),
                topLeft = Offset(w * 0.20f, h * 0.59f),
                size = Size(w * 0.28f, h * 0.07f),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}

@Composable
fun RulesIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val gradient = Brush.linearGradient(
                colors = listOf(LoonExtraColors.sky, LoonExtraColors.skyDark),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            val drawDiamond = { cy: Float, alpha: Float ->
                val path = Path().apply {
                    moveTo(w * 0.48f, cy - h * 0.14f)
                    lineTo(w * 0.86f, cy)
                    lineTo(w * 0.48f, cy + h * 0.14f)
                    lineTo(w * 0.10f, cy)
                    close()
                }
                drawPath(path, brush = gradient, alpha = alpha)
            }
            drawDiamond(h * 0.64f, 0.5f)
            drawDiamond(h * 0.48f, 0.75f)
            drawDiamond(h * 0.32f, 1.0f)

            // White connected nodes on top diamond
            val n1 = Offset(w * 0.36f, h * 0.32f)
            val n2 = Offset(w * 0.60f, h * 0.26f)
            val n3 = Offset(w * 0.58f, h * 0.38f)
            drawLine(Color.White, n1, n2, strokeWidth = 2.dp.toPx())
            drawLine(Color.White, n1, n3, strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, radius = 3.dp.toPx(), center = n1)
            drawCircle(Color.White, radius = 2.5.dp.toPx(), center = n2)
            drawCircle(Color.White, radius = 2.5.dp.toPx(), center = n3)
        }
    }
}

@Composable
fun PluginsIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val gradient = Brush.linearGradient(
                colors = listOf(LoonExtraColors.indigo, LoonExtraColors.indigoDark),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            // Puzzle main body
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(w * 0.18f, h * 0.18f),
                size = Size(w * 0.56f, h * 0.56f),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
            // Top tab knob
            drawCircle(
                brush = gradient,
                radius = w * 0.14f,
                center = Offset(w * 0.46f, h * 0.18f)
            )
            // Right tab knob
            drawCircle(
                brush = gradient,
                radius = w * 0.14f,
                center = Offset(w * 0.74f, h * 0.46f)
            )
        }
    }
}

@Composable
fun NodesIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val bladeGradient = Brush.linearGradient(
                colors = listOf(LoonExtraColors.blue, LoonExtraColors.blueMain),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )

            val cornerRadius = CornerRadius(6.dp.toPx())
            val bladeWidth = w * 0.66f
            val bladeHeight = h * 0.29f
            val bladeLeft = w * 0.17f

            // Top Server Blade
            val topBladeY = h * 0.16f
            drawRoundRect(
                brush = bladeGradient,
                topLeft = Offset(bladeLeft, topBladeY),
                size = Size(bladeWidth, bladeHeight),
                cornerRadius = cornerRadius
            )
            // Top Blade LED indicator (clean white dot on the left)
            drawCircle(
                color = Color.White,
                radius = 3.6.dp.toPx(),
                center = Offset(bladeLeft + bladeHeight * 0.58f, topBladeY + bladeHeight * 0.50f)
            )

            // Bottom Server Blade
            val bottomBladeY = h * 0.53f
            drawRoundRect(
                brush = bladeGradient,
                topLeft = Offset(bladeLeft, bottomBladeY),
                size = Size(bladeWidth, bladeHeight),
                cornerRadius = cornerRadius
            )
            // Bottom Blade LED indicator (clean white dot on the left)
            drawCircle(
                color = Color.White,
                radius = 3.6.dp.toPx(),
                center = Offset(bladeLeft + bladeHeight * 0.58f, bottomBladeY + bladeHeight * 0.50f)
            )
        }
    }
}


@Composable
fun NetworkShareIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val gradient = Brush.linearGradient(
                colors = listOf(LoonExtraColors.red, LoonExtraColors.redDark),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            val center = Offset(w * 0.46f, h * 0.48f)
            // Central transmitter circle
            drawCircle(brush = gradient, radius = 5.5.dp.toPx(), center = center)
            // Inner radiating arc wave
            drawArc(
                brush = gradient,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(center.x - 12.dp.toPx(), center.y - 12.dp.toPx()),
                size = Size(24.dp.toPx(), 24.dp.toPx()),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            // Outer radiating arc wave
            drawArc(
                brush = gradient,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(center.x - 19.dp.toPx(), center.y - 19.dp.toPx()),
                size = Size(38.dp.toPx(), 38.dp.toPx()),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun DnsIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val gradient = Brush.linearGradient(
                colors = listOf(LoonExtraColors.green, LoonExtraColors.greenDark),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            // Main server chip body
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(w * 0.18f, h * 0.20f),
                size = Size(w * 0.64f, h * 0.60f),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
            // Three server slots
            val slotColor = Color.White.copy(alpha = 0.85f)
            drawRoundRect(color = slotColor, topLeft = Offset(w * 0.26f, h * 0.31f), size = Size(w * 0.14f, h * 0.08f), cornerRadius = CornerRadius(2.dp.toPx()))
            drawRoundRect(color = slotColor, topLeft = Offset(w * 0.26f, h * 0.46f), size = Size(w * 0.14f, h * 0.08f), cornerRadius = CornerRadius(2.dp.toPx()))
            drawRoundRect(color = slotColor, topLeft = Offset(w * 0.26f, h * 0.61f), size = Size(w * 0.14f, h * 0.08f), cornerRadius = CornerRadius(2.dp.toPx()))
            // Right-side label lines
            drawRoundRect(color = Color.White.copy(alpha = 0.7f), topLeft = Offset(w * 0.46f, h * 0.33f), size = Size(w * 0.28f, h * 0.06f), cornerRadius = CornerRadius(2.dp.toPx()))
            drawRoundRect(color = Color.White.copy(alpha = 0.7f), topLeft = Offset(w * 0.46f, h * 0.48f), size = Size(w * 0.20f, h * 0.06f), cornerRadius = CornerRadius(2.dp.toPx()))
            drawRoundRect(color = Color.White.copy(alpha = 0.7f), topLeft = Offset(w * 0.46f, h * 0.63f), size = Size(w * 0.24f, h * 0.06f), cornerRadius = CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
fun AppRoutingIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Single rounded square background (scaled down to 0.72f to match other icons)
            val bgGrad = Brush.linearGradient(
                colors = listOf(LoonExtraColors.indigoDark, LoonExtraColors.blueMain),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            drawRoundRect(
                brush = bgGrad,
                topLeft = Offset(w * 0.14f, h * 0.14f),
                size = Size(w * 0.72f, h * 0.72f),
                cornerRadius = CornerRadius(8.dp.toPx())
            )

            // 1. Larger Plus (+) in Top-Left
            val cx1 = w * 0.38f
            val cy1 = h * 0.38f
            val armPlus = 4.8.dp.toPx()
            val strokePlus = 2.5.dp.toPx()
            // Horizontal bar of plus
            drawLine(
                color = Color.White,
                start = Offset(cx1 - armPlus, cy1),
                end = Offset(cx1 + armPlus, cy1),
                strokeWidth = strokePlus,
                cap = StrokeCap.Round
            )
            // Vertical bar of plus
            drawLine(
                color = Color.White,
                start = Offset(cx1, cy1 - armPlus),
                end = Offset(cx1, cy1 + armPlus),
                strokeWidth = strokePlus,
                cap = StrokeCap.Round
            )

            // 2. Smaller Minus (-) in Bottom-Right
            val cx2 = w * 0.62f
            val cy2 = h * 0.62f
            val armMinus = 3.4.dp.toPx()
            val strokeMinus = 2.0.dp.toPx()
            // Horizontal bar of minus
            drawLine(
                color = Color.White.copy(alpha = 0.95f),
                start = Offset(cx2 - armMinus, cy2),
                end = Offset(cx2 + armMinus, cy2),
                strokeWidth = strokeMinus,
                cap = StrokeCap.Round
            )
        }
    }
}

// -------------------------------------------------------------
// Floating Bottom Navigation Bar
// -------------------------------------------------------------

@Composable
fun LoonFloatingBottomBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(16.dp, RoundedCornerShape(32.dp), spotColor = LoonExtraColors.shadowSoft)
            .border(0.5.dp, LocalLoonColors.current.bottomBarBorder, RoundedCornerShape(32.dp)),
        shape = RoundedCornerShape(32.dp),
        color = LocalLoonColors.current.bottomBarBg
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 28.dp, vertical = 7.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val lang = LocalAppLanguage.current
            BottomNavItem(
                icon = Icons.Default.Speed,
                label = AppStrings.get("nav_dashboard", lang),
                isSelected = selectedTab == 0,
                onClick = { onTabSelected(0) }
            )
            BottomNavItem(
                customIcon = { tint ->
                    StandardCubeIcon(tint = tint)
                },
                label = AppStrings.get("nav_proxies", lang),
                isSelected = selectedTab == 1,
                onClick = { onTabSelected(1) }
            )
            BottomNavItem(
                icon = Icons.Outlined.Settings,
                label = AppStrings.get("nav_settings", lang),
                isSelected = selectedTab == 2,
                onClick = { onTabSelected(2) }
            )
        }
    }
}

@Composable
fun StandardCubeIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(22.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        // Center point where the 3 visible isometric edges join
        val cx = w * 0.5f
        val cy = h * 0.52f

        // Top diamond (top face)
        val topP = Path().apply {
            moveTo(cx, h * 0.12f)
            lineTo(w * 0.88f, h * 0.32f)
            lineTo(cx, cy)
            lineTo(w * 0.12f, h * 0.32f)
            close()
        }
        drawPath(topP, color = tint.copy(alpha = 0.08f))
        drawPath(topP, color = tint, style = stroke)

        // Left face
        val leftP = Path().apply {
            moveTo(w * 0.12f, h * 0.32f)
            lineTo(cx, cy)
            lineTo(cx, h * 0.92f)
            lineTo(w * 0.12f, h * 0.72f)
            close()
        }
        drawPath(leftP, color = tint, style = stroke)

        // Right face
        val rightP = Path().apply {
            moveTo(cx, cy)
            lineTo(w * 0.88f, h * 0.32f)
            lineTo(w * 0.88f, h * 0.72f)
            lineTo(cx, h * 0.92f)
            close()
        }
        drawPath(rightP, color = tint, style = stroke)
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector? = null,
    customIcon: (@Composable (Color) -> Unit)? = null,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val haptic = rememberIosHaptic()
    val reduceMotion = LocalReduceMotion.current
    val tint by animateColorAsState(
        targetValue = if (isSelected) IosBlue else IosGray,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 200),
        label = "nav_tint"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected && !reduceMotion) 1.08f else 1.0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.7f, stiffness = 400f)
        },
        label = "nav_scale"
    )

    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
            .iosFocusRing(RoundedCornerShape(LoonRadii.control))
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = {
                    haptic.navTabSelection()
                    onClick()
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (customIcon != null) {
            customIcon(tint)
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = label,
            color = tint,
            style = if (isSelected) LoonTextStyles.tabStrong() else LoonTextStyles.tab()
        )
    }
}

// -------------------------------------------------------------
// iOS-style Rubber-Band Bounce Overscroll (Translates content rigidly without stretching)
// allowTop=true  → produces space when pulling past the top edge
// allowBottom=true → produces space when pushing past the bottom edge
// -------------------------------------------------------------
@Composable
fun rememberBounceOverscrollConnection(
    allowTop: Boolean = true,
    allowBottom: Boolean = true
): Pair<NestedScrollConnection, State<Float>> {
    val coroutineScope = rememberCoroutineScope()
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    var springJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val connection = remember(allowTop, allowBottom) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val current = overscrollOffset.floatValue
                if (current == 0f) return Offset.Zero
                // Scroll back toward zero synchronously before passing delta to the list
                if (current > 0f && available.y < 0f) {
                    springJob?.cancel()
                    val consumed = available.y.coerceAtLeast(-current)
                    overscrollOffset.floatValue = current + consumed
                    return Offset(0f, consumed)
                } else if (current < 0f && available.y > 0f) {
                    springJob?.cancel()
                    val consumed = available.y.coerceAtMost(-current)
                    overscrollOffset.floatValue = current + consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    // available.y > 0 → pulling down past top edge (top overscroll)
                    // available.y < 0 → pushing up past bottom edge (bottom overscroll)
                    val isTopOverscroll = available.y > 0f
                    val isBottomOverscroll = available.y < 0f
                    if (isTopOverscroll && !allowTop) {
                        return Offset.Zero
                    }
                    if (isBottomOverscroll && !allowBottom) {
                        return Offset.Zero
                    }
                    springJob?.cancel()
                    val current = overscrollOffset.floatValue
                    val resistance = (1f - (kotlin.math.abs(current) / 500f).coerceIn(0f, 0.85f)) * 0.42f
                    val delta = available.y * resistance
                    overscrollOffset.floatValue = (current + delta).coerceIn(-220f, 220f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            private fun animateBackToZero() {
                val startValue = overscrollOffset.floatValue
                if (startValue == 0f) return
                springJob?.cancel()
                springJob = coroutineScope.launch {
                    animate(
                        initialValue = startValue,
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { value, _ ->
                        overscrollOffset.floatValue = value
                    }
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscrollOffset.floatValue != 0f) {
                    animateBackToZero()
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (overscrollOffset.floatValue != 0f) {
                    animateBackToZero()
                }
                return Velocity.Zero
            }
        }
    }
    return Pair(connection, overscrollOffset)
}

// iOS-style Rubber-Band Bounce Overscroll
fun Modifier.bounceOverscroll(
    allowTop: Boolean = true,
    allowBottom: Boolean = true
): Modifier = composed {
    val (connection, offsetState) = rememberBounceOverscrollConnection(allowTop, allowBottom)
    this
        .clipToBounds()
        .nestedScroll(connection)
        .graphicsLayer {
            translationY = offsetState.value
        }
}

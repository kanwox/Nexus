package com.github.mihomo.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.ui.theme.*

@Composable
fun LoonTrafficCard(
    modifier: Modifier = Modifier,
    title: String,
    speed: String,
    isUpload: Boolean
) {
    val gradient = if (isUpload) {
        Brush.horizontalGradient(listOf(Color(0xFF00C853), Color(0xFF00E676)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF4A69FF), Color(0xFF6C5CE7)))
    }

    Surface(
        modifier = modifier
            .height(82.dp)
            .shadow(6.dp, RoundedCornerShape(18.dp), spotColor = if (isUpload) Color(0x3300C853) else Color(0x334A69FF)),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(end = 46.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (speed.isBlank()) "--" else speed,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            // Circular icon with arrow
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .align(Alignment.CenterEnd)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isUpload) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
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
    Surface(
        modifier = modifier
            .height(118.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = if (isEditing) 1.5.dp else 1.dp,
                color = if (isEditing) LoonBlue.copy(alpha = 0.5f) else LoonCardBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color(0x0A000000))
            .clickable(enabled = !isEditing) { onClick() },
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        color = LoonTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }

            // Bottom-left circular chevron '>' button or drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp)
            ) {
                if (isEditing) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (LocalLoonColors.current.isDark) Color(0xFF262C3A) else Color(0xFFF1F3F7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "☰",
                            fontSize = 14.sp,
                            color = LoonBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (LocalLoonColors.current.isDark) Color(0xFF262C3A) else Color(0xFFF1F3F7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Color(0xFF9AA0AE),
                            modifier = Modifier.size(16.dp)
                        )
                    }
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
                        .background(Color(0xFFFF3B30))
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "−",
                        color = Color.White,
                        fontSize = 15.sp,
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
fun BlurOverlay(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .size(22.dp)
            .shadow(3.dp, CircleShape, spotColor = Color(0x33000000))
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun RequestLogsIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Main document paper card (vibrant violet-purple with subtle lighting)
            val docGrad = Brush.verticalGradient(
                colors = listOf(Color(0xFFA855F7), Color(0xFF9333EA)),
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
            // Three stacked card layers (from back to front)
            // Back card (lightest)
            drawRoundRect(
                color = Color(0xFF93C5FD),
                topLeft = Offset(w * 0.22f, h * 0.16f),
                size = Size(w * 0.62f, h * 0.52f),
                cornerRadius = CornerRadius(5.dp.toPx())
            )
            // Middle card
            drawRoundRect(
                color = Color(0xFF3B82F6),
                topLeft = Offset(w * 0.14f, h * 0.26f),
                size = Size(w * 0.62f, h * 0.52f),
                cornerRadius = CornerRadius(5.dp.toPx())
            )
            // Front card (darkest, blue gradient)
            val frontGradient = Brush.linearGradient(
                colors = listOf(Color(0xFF60A5FA), Color(0xFF1D4ED8)),
                start = Offset(0f, h * 0.34f),
                end = Offset(w, h)
            )
            drawRoundRect(
                brush = frontGradient,
                topLeft = Offset(w * 0.06f, h * 0.36f),
                size = Size(w * 0.62f, h * 0.52f),
                cornerRadius = CornerRadius(5.dp.toPx())
            )
            // White lines on front card
            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(w * 0.14f, h * 0.50f),
                size = Size(w * 0.38f, h * 0.07f),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.6f),
                topLeft = Offset(w * 0.14f, h * 0.64f),
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
                colors = listOf(Color(0xFF38BDF8), Color(0xFF0284C7)),
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
                colors = listOf(Color(0xFF818CF8), Color(0xFF6366F1)),
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
                colors = listOf(Color(0xFF60A5FA), Color(0xFF2563EB)),
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
fun PacketCaptureIllustration() {
    Box(modifier = Modifier.size(54.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val gradient = Brush.linearGradient(
                colors = listOf(Color(0xFFA78BFA), Color(0xFF7C3AED)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            // Phone body
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(w * 0.20f, h * 0.10f),
                size = Size(w * 0.35f, h * 0.60f),
                cornerRadius = CornerRadius(5.dp.toPx())
            )
            // Phone screen
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(w * 0.24f, h * 0.16f),
                size = Size(w * 0.27f, h * 0.42f),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            // Branching arrows from phone right side
            val midY = h * 0.40f
            // Horizontal stem
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(w * 0.56f, midY),
                end = Offset(w * 0.68f, midY),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Upper branch
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(w * 0.68f, midY),
                end = Offset(w * 0.68f, h * 0.26f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(w * 0.68f, h * 0.26f),
                end = Offset(w * 0.84f, h * 0.26f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Lower branch
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(w * 0.68f, midY),
                end = Offset(w * 0.68f, h * 0.54f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(w * 0.68f, h * 0.54f),
                end = Offset(w * 0.84f, h * 0.54f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Arrow heads
            val arrSize = 4.dp.toPx()
            // Upper arrowhead
            drawPath(Path().apply {
                moveTo(w * 0.84f, h * 0.26f - arrSize)
                lineTo(w * 0.84f + arrSize, h * 0.26f)
                lineTo(w * 0.84f, h * 0.26f + arrSize)
                close()
            }, color = Color.White.copy(alpha = 0.9f))
            // Lower arrowhead
            drawPath(Path().apply {
                moveTo(w * 0.84f, h * 0.54f - arrSize)
                lineTo(w * 0.84f + arrSize, h * 0.54f)
                lineTo(w * 0.84f, h * 0.54f + arrSize)
                close()
            }, color = Color.White.copy(alpha = 0.9f))
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
                colors = listOf(Color(0xFFF87171), Color(0xFFDC2626)),
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
                colors = listOf(Color(0xFF34D399), Color(0xFF059669)),
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
                colors = listOf(Color(0xFF6366F1), Color(0xFF2563EB)),
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
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(bottom = 18.dp)
            .shadow(16.dp, RoundedCornerShape(32.dp), spotColor = Color(0x1F000000))
            .border(1.dp, LocalLoonColors.current.bottomBarBorder, RoundedCornerShape(32.dp)),
        shape = RoundedCornerShape(32.dp),
        color = LocalLoonColors.current.bottomBarBg
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 28.dp, vertical = 7.dp),
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
    val tint = if (isSelected) LoonBlue else Color(0xFF9AA1B0)

    Column(
        modifier = Modifier
            .clickable(
                interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                indication = null
            ) { onClick() }
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
            fontSize = 10.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

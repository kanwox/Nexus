package com.github.mihomo.android.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * iOS Continuous Corner Curve (`CALayer.cornerCurve = .continuous` / Squircle).
 *
 * Extends [CornerBasedShape] so it works as a drop-in replacement for Compose's
 * circular `RoundedCornerShape` across all modifiers, `Surface`, and Material3 `Shapes`.
 */
@Stable
class IosContinuousCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize
) : CornerBasedShape(
    topStart = topStart,
    topEnd = topEnd,
    bottomEnd = bottomEnd,
    bottomStart = bottomStart
) {

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) {
            return Outline.Rectangle(Rect(0f, 0f, w, h))
        }

        val maxRadius = min(w, h) / 2f
        val ts = topStart.coerceIn(0f, maxRadius)
        val te = topEnd.coerceIn(0f, maxRadius)
        val be = bottomEnd.coerceIn(0f, maxRadius)
        val bs = bottomStart.coerceIn(0f, maxRadius)

        val topLeft = if (layoutDirection == LayoutDirection.Ltr) ts else te
        val topRight = if (layoutDirection == LayoutDirection.Ltr) te else ts
        val bottomRight = if (layoutDirection == LayoutDirection.Ltr) be else bs
        val bottomLeft = if (layoutDirection == LayoutDirection.Ltr) bs else be

        val largestCorner = max(max(topLeft, topRight), max(bottomRight, bottomLeft))
        if (largestCorner <= 0f) {
            return Outline.Rectangle(Rect(0f, 0f, w, h))
        }

        val path = buildContinuousRoundedRectPath(
            w = w,
            h = h,
            maxRadius = maxRadius,
            tl = topLeft,
            tr = topRight,
            br = bottomRight,
            bl = bottomLeft
        )
        return Outline.Generic(path)
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): IosContinuousCornerShape = IosContinuousCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IosContinuousCornerShape) return false
        return topStart == other.topStart &&
            topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd &&
            bottomStart == other.bottomStart
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }
}

// Apple UIBezierPath(.continuous) exact G2 cubic Bezier coefficients
private const val K0 = 1.52866483f
private const val K1 = 1.08849296f
private const val K2 = 0.86840694f
private const val K3 = 0.63149379f
private const val K4 = 0.07491139f
private const val K5 = 0.37282383f
private const val K6 = 0.16905956f
private const val KAPPA = 0.55228475f

private data class CornerParams(
    val d0: Float,
    val c1a: Float,
    val c1b: Float,
    val p1x: Float,
    val p1y: Float,
    val c2a: Float,
    val c2b: Float
)

private fun computeCornerParams(r: Float, maxRadius: Float): CornerParams {
    if (r <= 0f) return CornerParams(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    // Smoothness blend factor t in [0f, 1f]:
    // t = 1f when r * K0 <= maxRadius (full iOS continuous corner curve)
    // t = 0f when r >= maxRadius (pure circular capsule end)
    val fullContinuousLimit = maxRadius / K0
    val t = if (r <= fullContinuousLimit) {
        1f
    } else if (r >= maxRadius) {
        0f
    } else {
        ((maxRadius - r) / (maxRadius - fullContinuousLimit)).coerceIn(0f, 1f)
    }

    val d0 = lerpFloat(r, K0 * r, t).coerceAtMost(maxRadius)
    val c1a = lerpFloat(r, K1 * r, t).coerceAtMost(maxRadius)
    val c1b = lerpFloat(r, K2 * r, t).coerceAtMost(maxRadius)
    val p1x = lerpFloat(r, K3 * r, t)
    val p1y = lerpFloat(0f, K4 * r, t)
    val c2a = lerpFloat(r * (1f - KAPPA), K5 * r, t)
    val c2b = lerpFloat(0f, K6 * r, t)
    return CornerParams(d0, c1a, c1b, p1x, p1y, c2a, c2b)
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun buildContinuousRoundedRectPath(
    w: Float,
    h: Float,
    maxRadius: Float,
    tl: Float,
    tr: Float,
    br: Float,
    bl: Float
): Path {
    val tlP = computeCornerParams(tl, maxRadius)
    val trP = computeCornerParams(tr, maxRadius)
    val brP = computeCornerParams(br, maxRadius)
    val blP = computeCornerParams(bl, maxRadius)

    return Path().apply {
        // Start on top edge after top-left corner
        moveTo(tlP.d0, 0f)

        // Top edge to top-right corner
        lineTo(w - trP.d0, 0f)
        if (tr > 0f) {
            cubicTo(
                w - trP.c1a, 0f,
                w - trP.c1b, 0f,
                w - trP.p1x, trP.p1y
            )
            cubicTo(
                w - trP.c2a, trP.c2b,
                w - trP.c2b, trP.c2a,
                w - trP.p1y, trP.p1x
            )
            cubicTo(
                w, trP.c1b,
                w, trP.c1a,
                w, trP.d0
            )
        }

        // Right edge to bottom-right corner
        lineTo(w, h - brP.d0)
        if (br > 0f) {
            cubicTo(
                w, h - brP.c1a,
                w, h - brP.c1b,
                w - brP.p1y, h - brP.p1x
            )
            cubicTo(
                w - brP.c2b, h - brP.c2a,
                w - brP.c2a, h - brP.c2b,
                w - brP.p1x, h - brP.p1y
            )
            cubicTo(
                w - brP.c1b, h,
                w - brP.c1a, h,
                w - brP.d0, h
            )
        }

        // Bottom edge to bottom-left corner
        lineTo(blP.d0, h)
        if (bl > 0f) {
            cubicTo(
                blP.c1a, h,
                blP.c1b, h,
                blP.p1x, h - blP.p1y
            )
            cubicTo(
                blP.c2a, h - blP.c2b,
                blP.c2b, h - blP.c2a,
                blP.p1y, h - blP.p1x
            )
            cubicTo(
                0f, h - blP.c1b,
                0f, h - blP.c1a,
                0f, h - blP.d0
            )
        }

        // Left edge to top-left corner
        lineTo(0f, tlP.d0)
        if (tl > 0f) {
            cubicTo(
                0f, tlP.c1a,
                0f, tlP.c1b,
                tlP.p1y, tlP.p1x
            )
            cubicTo(
                tlP.c2b, tlP.c2a,
                tlP.c2a, tlP.c2b,
                tlP.p1x, tlP.p1y
            )
            cubicTo(
                tlP.c1b, 0f,
                tlP.c1a, 0f,
                tlP.d0, 0f
            )
        }

        close()
    }
}

/**
 * Drop-in replacement for Compose's circular `RoundedCornerShape` that produces
 * iOS `.continuous` smooth corners (Squircle).
 */
@Stable
@Suppress("FunctionNaming")
fun RoundedCornerShape(size: Dp): IosContinuousCornerShape =
    IosContinuousCornerShape(
        topStart = CornerSize(size),
        topEnd = CornerSize(size),
        bottomEnd = CornerSize(size),
        bottomStart = CornerSize(size)
    )

@Stable
@Suppress("FunctionNaming")
fun RoundedCornerShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp
): IosContinuousCornerShape =
    IosContinuousCornerShape(
        topStart = CornerSize(topStart),
        topEnd = CornerSize(topEnd),
        bottomEnd = CornerSize(bottomEnd),
        bottomStart = CornerSize(bottomStart)
    )

@Stable
@Suppress("FunctionNaming")
fun RoundedCornerShape(percent: Int): IosContinuousCornerShape =
    IosContinuousCornerShape(
        topStart = CornerSize(percent),
        topEnd = CornerSize(percent),
        bottomEnd = CornerSize(percent),
        bottomStart = CornerSize(percent)
    )

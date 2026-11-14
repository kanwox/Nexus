package com.github.mihomo.android.ui

import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.github.mihomo.android.ui.theme.LoonMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal const val EDGE_BACK_GESTURE_ACTIVE_THRESHOLD_PX = 1f
internal const val EDGE_BACK_COMMIT_WIDTH_FRACTION = 0.30f
internal const val PREDICTIVE_SWEEP_WIDTH_FRACTION = 0.72f
internal const val MIN_GESTURE_EXIT_DURATION_MS = 80
private const val EXIT_TEARDOWN_TIMEOUT_MS = 500L

internal enum class PredictiveBackCompletionAction {
    INSTANT_NAVIGATE,
    STANDARD_EXIT_TRANSITION,
    GESTURE_EXIT_CONTINUATION
}

internal fun resolvePredictiveBackCompletionAction(
    reduceMotion: Boolean,
    currentEdgeOffsetPx: Float
): PredictiveBackCompletionAction = when {
    reduceMotion -> PredictiveBackCompletionAction.INSTANT_NAVIGATE
    currentEdgeOffsetPx <= EDGE_BACK_GESTURE_ACTIVE_THRESHOLD_PX ->
        PredictiveBackCompletionAction.STANDARD_EXIT_TRANSITION
    else -> PredictiveBackCompletionAction.GESTURE_EXIT_CONTINUATION
}

internal fun computePredictiveSweepWidthPx(
    minSweepWidthPx: Float,
    containerWidthPx: Float
): Float = if (containerWidthPx > 0f) {
    maxOf(
        minSweepWidthPx.coerceAtLeast(0f),
        containerWidthPx * PREDICTIVE_SWEEP_WIDTH_FRACTION
    ).coerceAtMost(containerWidthPx)
} else {
    minSweepWidthPx.coerceAtLeast(0f)
}

internal fun computePredictiveEdgeOffsetPx(
    progress: Float,
    sweepWidthPx: Float
): Float = (progress.coerceIn(0f, 1f) * sweepWidthPx.coerceAtLeast(0f)).coerceAtLeast(0f)

internal fun shouldCommitEdgeDrag(
    edgeOffsetPx: Float,
    containerWidthPx: Float
): Boolean = containerWidthPx > 0f && edgeOffsetPx > containerWidthPx * EDGE_BACK_COMMIT_WIDTH_FRACTION

internal fun computeGestureExitTargetWidthPx(
    containerWidthPx: Float,
    fallbackSweepWidthPx: Float,
    currentEdgeOffsetPx: Float
): Float {
    val baseWidth = if (containerWidthPx > 0f) {
        containerWidthPx
    } else {
        fallbackSweepWidthPx.coerceAtLeast(1f)
    }
    return maxOf(baseWidth, currentEdgeOffsetPx.coerceAtLeast(0f))
}

internal fun computeGestureExitDurationMs(
    currentEdgeOffsetPx: Float,
    targetWidthPx: Float,
    maxDurationMs: Int,
    minDurationMs: Int = MIN_GESTURE_EXIT_DURATION_MS
): Int = if (targetWidthPx <= 0f || currentEdgeOffsetPx >= targetWidthPx || maxDurationMs <= 0) {
    0
} else {
    val remainingFraction = ((targetWidthPx - currentEdgeOffsetPx.coerceAtLeast(0f)) / targetWidthPx)
        .coerceIn(0f, 1f)
    val clampedMin = minDurationMs.coerceIn(0, maxDurationMs)
    (remainingFraction * maxDurationMs.toFloat()).roundToInt().coerceIn(clampedMin, maxDurationMs)
}

internal fun isEdgeStartInZone(
    startOffsetX: Float,
    containerWidthPx: Float,
    edgeZonePx: Float,
    isRtl: Boolean
): Boolean = if (isRtl) {
    startOffsetX >= containerWidthPx - edgeZonePx
} else {
    startOffsetX <= edgeZonePx
}

internal fun shouldResetEdgeStateOnScreenChange(
    currentScreen: SubScreen?,
    isCommittingGestureExit: Boolean,
    gestureExitingFromScreen: SubScreen?,
    expectedPostBackScreen: SubScreen?
): Boolean = currentScreen != null && (
    !isCommittingGestureExit ||
        (currentScreen != gestureExitingFromScreen && currentScreen != expectedPostBackScreen)
    )

@Stable
internal class SubScreenEdgeBackController(
    private val state: MainAppState,
    private val visibleState: MutableTransitionState<Boolean>,
    private val coroutineScope: CoroutineScope
) {
    val edgeOffset = Animatable(0f)
    val overlayAlpha = Animatable(1f)
    var containerWidthPx by mutableFloatStateOf(0f)
    var fallbackSweepWidthPx by mutableFloatStateOf(0f)
    var reduceMotion by mutableStateOf(false)
    var isEdgeDragging by mutableStateOf(false)
    var isPredictiveBackActive by mutableStateOf(false)
    var isCommittingGestureExit by mutableStateOf(false)
    var skipVisibilityExit by mutableStateOf(false)
    var skipNestedTransition by mutableStateOf(false)
    var gestureExitingFromScreen by mutableStateOf<SubScreen?>(null)
    var expectedPostBackScreen by mutableStateOf<SubScreen?>(null)
    var isBodyTransitionSettled: (SubScreen) -> Boolean = { true }
    private var activeCommitToken = 0

    private val canAcceptEdgeDrag: Boolean
        get() = !isCommittingGestureExit && !isPredictiveBackActive

    suspend fun syncOnScreenChange(current: SubScreen?, onActiveScreen: (SubScreen) -> Unit) {
        isEdgeDragging = false
        if (current != null) {
            onActiveScreen(current)
            if (shouldResetEdgeStateOnScreenChange(
                    currentScreen = current,
                    isCommittingGestureExit = isCommittingGestureExit,
                    gestureExitingFromScreen = gestureExitingFromScreen,
                    expectedPostBackScreen = expectedPostBackScreen
                )
            ) {
                resetImmediately()
            }
        } else if (!isCommittingGestureExit && overlayAlpha.value < 1f) {
            overlayAlpha.snapTo(1f)
        }
    }

    suspend fun resetIfOverlaySettledHidden() {
        val overlaySettledHidden = !visibleState.currentState && !visibleState.targetState
        if (state.currentSubScreen == null && overlaySettledHidden && !isCommittingGestureExit) {
            resetImmediately()
        }
    }

    fun onDragStarted(
        startOffsetX: Float,
        widthPx: Float,
        edgeZonePx: Float,
        isRtl: Boolean
    ) {
        isEdgeDragging = canAcceptEdgeDrag &&
            isEdgeStartInZone(startOffsetX, widthPx, edgeZonePx, isRtl)
    }

    fun onDragDelta(dragAmount: Float, isRtl: Boolean): Boolean {
        val activeDrag = isEdgeDragging && canAcceptEdgeDrag
        var consumed = false
        if (activeDrag) {
            val delta = if (isRtl) -dragAmount else dragAmount
            val currentOffset = edgeOffset.value
            if (delta > 0f || currentOffset > 0f) {
                snapOffsetFromDrag((currentOffset + delta).coerceAtLeast(0f))
                consumed = true
            }
        }
        return consumed
    }

    fun onDragEnded(widthPx: Float) {
        val wasDragging = isEdgeDragging
        isEdgeDragging = false
        if (wasDragging && canAcceptEdgeDrag) {
            if (widthPx > 0f) {
                containerWidthPx = widthPx
            }
            if (shouldCommitEdgeDrag(edgeOffset.value, widthPx)) {
                if (reduceMotion) {
                    state.navigateBackSubScreen()
                    launchResetImmediately()
                } else {
                    launchGestureExitCommit()
                }
            } else {
                launchSettleBackToZero()
            }
        }
    }

    fun onDragCanceled() {
        val wasDragging = isEdgeDragging
        isEdgeDragging = false
        if (wasDragging && canAcceptEdgeDrag) {
            launchSettleBackToZero()
        }
    }

    suspend fun handlePredictiveBack(progress: Flow<BackEventCompat>) {
        if (isCommittingGestureExit || reduceMotion) {
            progress.collect { }
            if (!isCommittingGestureExit) {
                state.navigateBackSubScreen()
                resetImmediately()
            }
        } else {
            isPredictiveBackActive = true
            try {
                progress.collect { backEvent ->
                    val sweepWidthPx = computePredictiveSweepWidthPx(
                        minSweepWidthPx = fallbackSweepWidthPx,
                        containerWidthPx = containerWidthPx
                    )
                    edgeOffset.snapTo(computePredictiveEdgeOffsetPx(backEvent.progress, sweepWidthPx))
                }
                when (resolvePredictiveBackCompletionAction(false, edgeOffset.value)) {
                    PredictiveBackCompletionAction.INSTANT_NAVIGATE -> {
                        state.navigateBackSubScreen()
                        resetImmediately()
                    }
                    PredictiveBackCompletionAction.STANDARD_EXIT_TRANSITION -> {
                        state.navigateBackSubScreen()
                        edgeOffset.snapTo(0f)
                    }
                    PredictiveBackCompletionAction.GESTURE_EXIT_CONTINUATION -> {
                        launchGestureExitCommit()
                    }
                }
            } catch (e: CancellationException) {
                if (!isCommittingGestureExit) {
                    launchSettleBackToZero()
                }
                throw e
            } finally {
                isPredictiveBackActive = false
            }
        }
    }

    fun snapOffsetFromDrag(offsetPx: Float) {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            edgeOffset.snapTo(offsetPx.coerceAtLeast(0f))
        }
    }

    fun launchSettleBackToZero() {
        if (reduceMotion) {
            launchResetImmediately()
        } else {
            coroutineScope.launch {
                edgeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = LoonMotion.edgeSettleDampingRatio,
                        stiffness = LoonMotion.edgeSettleStiffness
                    )
                )
            }
        }
    }

    fun launchGestureExitCommit() {
        val startingScreen = state.currentSubScreen
        if (startingScreen != null && !isCommittingGestureExit) {
            val commitToken = ++activeCommitToken
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                performGestureExitCommit(startingScreen, commitToken)
            }
        }
    }

    fun launchResetImmediately() {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            resetImmediately()
        }
    }

    suspend fun resetImmediately() {
        activeCommitToken++
        clearGestureCommitState()
        edgeOffset.snapTo(0f)
        overlayAlpha.snapTo(1f)
    }

    private suspend fun performGestureExitCommit(startingScreen: SubScreen, commitToken: Int) {
        isCommittingGestureExit = true
        isEdgeDragging = false
        gestureExitingFromScreen = startingScreen
        try {
            val targetWidth = computeGestureExitTargetWidthPx(
                containerWidthPx = containerWidthPx,
                fallbackSweepWidthPx = fallbackSweepWidthPx,
                currentEdgeOffsetPx = edgeOffset.value
            )
            val durationMs = computeGestureExitDurationMs(
                currentEdgeOffsetPx = edgeOffset.value,
                targetWidthPx = targetWidth,
                maxDurationMs = LoonMotion.normalMs
            )
            if (durationMs > 0) {
                edgeOffset.animateTo(
                    targetValue = targetWidth,
                    animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing)
                )
            } else {
                edgeOffset.snapTo(targetWidth)
            }
            if (state.currentSubScreen == startingScreen) {
                val targetParent = state.parentSubScreen
                if (targetParent == null) {
                    finishCommitToPrimaryScreen()
                } else {
                    finishCommitToParentSubScreen(targetParent)
                }
            } else {
                resetImmediately()
            }
        } finally {
            if (activeCommitToken == commitToken) {
                clearGestureCommitState()
            }
        }
    }

    private suspend fun finishCommitToPrimaryScreen() {
        expectedPostBackScreen = null
        skipVisibilityExit = true
        state.navigateBackSubScreen()
        withTimeoutOrNull(EXIT_TEARDOWN_TIMEOUT_MS) {
            snapshotFlow {
                state.currentSubScreen != null ||
                    (!visibleState.currentState && !visibleState.targetState)
            }.first { it }
        }
        if (state.currentSubScreen == null) {
            edgeOffset.snapTo(0f)
            overlayAlpha.snapTo(1f)
        }
    }

    private suspend fun finishCommitToParentSubScreen(targetParent: SubScreen) {
        expectedPostBackScreen = targetParent
        skipNestedTransition = true
        overlayAlpha.snapTo(0f)
        state.navigateBackSubScreen()
        withTimeoutOrNull(EXIT_TEARDOWN_TIMEOUT_MS) {
            snapshotFlow {
                state.currentSubScreen != targetParent || isBodyTransitionSettled(targetParent)
            }.first { it }
        }
        if (state.currentSubScreen == targetParent) {
            edgeOffset.snapTo(0f)
            clearGestureCommitState()
            overlayAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = LoonMotion.fastMs)
            )
        }
    }

    private fun clearGestureCommitState() {
        isCommittingGestureExit = false
        skipVisibilityExit = false
        skipNestedTransition = false
        gestureExitingFromScreen = null
        expectedPostBackScreen = null
    }
}

@Composable
internal fun rememberSubScreenEdgeBackController(
    env: SubScreenEnvironment,
    visibleState: MutableTransitionState<Boolean>,
    fallbackSweepWidthPx: Float,
    reduceMotion: Boolean
): SubScreenEdgeBackController {
    val controller = remember(env.state, visibleState, env.coroutineScope) {
        SubScreenEdgeBackController(
            state = env.state,
            visibleState = visibleState,
            coroutineScope = env.coroutineScope
        )
    }
    controller.fallbackSweepWidthPx = fallbackSweepWidthPx
    controller.reduceMotion = reduceMotion
    return controller
}

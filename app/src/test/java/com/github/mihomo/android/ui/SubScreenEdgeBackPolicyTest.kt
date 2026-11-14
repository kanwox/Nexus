package com.github.mihomo.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubScreenEdgeBackPolicyTest {

    @Test
    fun `predictive back completion distinguishes button back from interactive slow swipe`() {
        assertEquals(
            PredictiveBackCompletionAction.INSTANT_NAVIGATE,
            resolvePredictiveBackCompletionAction(reduceMotion = true, currentEdgeOffsetPx = 240f)
        )
        assertEquals(
            PredictiveBackCompletionAction.STANDARD_EXIT_TRANSITION,
            resolvePredictiveBackCompletionAction(reduceMotion = false, currentEdgeOffsetPx = 0f)
        )
        assertEquals(
            PredictiveBackCompletionAction.STANDARD_EXIT_TRANSITION,
            resolvePredictiveBackCompletionAction(
                reduceMotion = false,
                currentEdgeOffsetPx = EDGE_BACK_GESTURE_ACTIVE_THRESHOLD_PX
            )
        )
        assertEquals(
            PredictiveBackCompletionAction.GESTURE_EXIT_CONTINUATION,
            resolvePredictiveBackCompletionAction(reduceMotion = false, currentEdgeOffsetPx = 12f)
        )
        assertEquals(
            PredictiveBackCompletionAction.GESTURE_EXIT_CONTINUATION,
            resolvePredictiveBackCompletionAction(reduceMotion = false, currentEdgeOffsetPx = 420f)
        )
    }

    @Test
    fun `predictive sweep width and offset track slow drag proportionally without exceeding width`() {
        assertEquals(
            600f,
            computePredictiveSweepWidthPx(minSweepWidthPx = 600f, containerWidthPx = 0f),
            0.01f
        )
        assertEquals(
            777.6f,
            computePredictiveSweepWidthPx(minSweepWidthPx = 550f, containerWidthPx = 1080f),
            0.01f
        )
        assertEquals(
            480f,
            computePredictiveSweepWidthPx(minSweepWidthPx = 550f, containerWidthPx = 480f),
            0.01f
        )
        assertEquals(0f, computePredictiveEdgeOffsetPx(progress = -0.2f, sweepWidthPx = 800f), 0.01f)
        assertEquals(200f, computePredictiveEdgeOffsetPx(progress = 0.25f, sweepWidthPx = 800f), 0.01f)
        assertEquals(800f, computePredictiveEdgeOffsetPx(progress = 1.4f, sweepWidthPx = 800f), 0.01f)
    }

    @Test
    fun `edge drag commit threshold and monotonic continuation target never pull screen backward`() {
        assertFalse(shouldCommitEdgeDrag(edgeOffsetPx = 300f, containerWidthPx = 0f))
        assertFalse(shouldCommitEdgeDrag(edgeOffsetPx = 300f, containerWidthPx = 1000f))
        assertTrue(shouldCommitEdgeDrag(edgeOffsetPx = 301f, containerWidthPx = 1000f))

        assertEquals(
            1080f,
            computeGestureExitTargetWidthPx(
                containerWidthPx = 1080f,
                fallbackSweepWidthPx = 600f,
                currentEdgeOffsetPx = 420f
            ),
            0.01f
        )
        assertEquals(
            1140f,
            computeGestureExitTargetWidthPx(
                containerWidthPx = 1080f,
                fallbackSweepWidthPx = 600f,
                currentEdgeOffsetPx = 1140f
            ),
            0.01f
        )
        assertEquals(
            600f,
            computeGestureExitTargetWidthPx(
                containerWidthPx = 0f,
                fallbackSweepWidthPx = 600f,
                currentEdgeOffsetPx = 200f
            ),
            0.01f
        )
    }

    @Test
    fun `gesture exit duration scales smoothly with remaining travel distance`() {
        assertEquals(
            220,
            computeGestureExitDurationMs(
                currentEdgeOffsetPx = 0f,
                targetWidthPx = 1000f,
                maxDurationMs = 220
            )
        )
        assertEquals(
            110,
            computeGestureExitDurationMs(
                currentEdgeOffsetPx = 500f,
                targetWidthPx = 1000f,
                maxDurationMs = 220
            )
        )
        assertEquals(
            MIN_GESTURE_EXIT_DURATION_MS,
            computeGestureExitDurationMs(
                currentEdgeOffsetPx = 950f,
                targetWidthPx = 1000f,
                maxDurationMs = 220
            )
        )
        assertEquals(
            0,
            computeGestureExitDurationMs(
                currentEdgeOffsetPx = 1000f,
                targetWidthPx = 1000f,
                maxDurationMs = 220
            )
        )
    }

    @Test
    fun `edge start hit zone respects LTR and RTL edges`() {
        assertTrue(isEdgeStartInZone(startOffsetX = 0f, containerWidthPx = 1080f, edgeZonePx = 88f, isRtl = false))
        assertTrue(isEdgeStartInZone(startOffsetX = 88f, containerWidthPx = 1080f, edgeZonePx = 88f, isRtl = false))
        assertFalse(isEdgeStartInZone(startOffsetX = 89f, containerWidthPx = 1080f, edgeZonePx = 88f, isRtl = false))

        assertTrue(isEdgeStartInZone(startOffsetX = 1080f, containerWidthPx = 1080f, edgeZonePx = 88f, isRtl = true))
        assertTrue(isEdgeStartInZone(startOffsetX = 992f, containerWidthPx = 1080f, edgeZonePx = 88f, isRtl = true))
        assertFalse(isEdgeStartInZone(startOffsetX = 991f, containerWidthPx = 1080f, edgeZonePx = 88f, isRtl = true))
    }

    @Test
    fun `screen change reset preserves expected nested handoff and resets unexpected switches`() {
        assertFalse(
            shouldResetEdgeStateOnScreenChange(
                currentScreen = null,
                isCommittingGestureExit = false,
                gestureExitingFromScreen = SubScreen.PROFILES,
                expectedPostBackScreen = null
            )
        )
        assertTrue(
            shouldResetEdgeStateOnScreenChange(
                currentScreen = SubScreen.PROFILES,
                isCommittingGestureExit = false,
                gestureExitingFromScreen = null,
                expectedPostBackScreen = null
            )
        )
        assertFalse(
            shouldResetEdgeStateOnScreenChange(
                currentScreen = SubScreen.PROFILE_OVERRIDE,
                isCommittingGestureExit = true,
                gestureExitingFromScreen = SubScreen.PROFILE_OVERRIDE,
                expectedPostBackScreen = SubScreen.PROFILES
            )
        )
        assertFalse(
            shouldResetEdgeStateOnScreenChange(
                currentScreen = SubScreen.PROFILES,
                isCommittingGestureExit = true,
                gestureExitingFromScreen = SubScreen.PROFILE_OVERRIDE,
                expectedPostBackScreen = SubScreen.PROFILES
            )
        )
        assertTrue(
            shouldResetEdgeStateOnScreenChange(
                currentScreen = SubScreen.LOGS,
                isCommittingGestureExit = true,
                gestureExitingFromScreen = SubScreen.PROFILE_OVERRIDE,
                expectedPostBackScreen = SubScreen.PROFILES
            )
        )
    }
}

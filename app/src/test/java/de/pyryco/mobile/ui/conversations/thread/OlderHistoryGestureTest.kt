package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OlderHistoryGestureTest {
    @Test
    fun dragEnteringRangeChecksItsCurrentPosition() {
        var near = false
        var asks = 0
        val gesture = OlderHistoryGesture({ near }, { asks++ })
        gesture.onGestureStart()
        move(gesture)
        assertEquals(0, asks)
        near = true
        move(gesture)
        assertEquals(1, asks)
    }

    @Test
    fun flingEnteringRangeRetainsItsTouchProvenance() =
        runTest {
            var near = false
            var asks = 0
            val gesture = OlderHistoryGesture({ near }, { asks++ })
            gesture.onGestureStart()
            move(gesture)
            gesture.onPreFling(Velocity(0f, 1000f))
            move(gesture, NestedScrollSource.SideEffect)
            assertEquals(0, asks)
            near = true
            move(gesture, NestedScrollSource.SideEffect)
            assertEquals(1, asks)
            gesture.onPostFling(Velocity.Zero, Velocity.Zero)
            move(gesture, NestedScrollSource.SideEffect)
            assertEquals(1, asks)
        }

    @Test
    fun idleArrivalAndSlotReleaseAskNothingButLaterMovementCanAsk() {
        var loading = false
        var asks = 0
        val gesture =
            OlderHistoryGesture({ true }, {
                if (!loading) {
                    asks++
                    loading = true
                }
            })
        gesture.onGestureStart()
        move(gesture)
        move(gesture)
        assertEquals(1, asks)
        loading = false
        assertEquals(1, asks)
        move(gesture)
        assertEquals(2, asks)
    }

    @Test
    fun semanticsAndProgrammaticScrollWithoutTouchCannotAskOrStartAFling() =
        runTest {
            var asks = 0
            val gesture = OlderHistoryGesture({ true }, { asks++ })
            move(gesture)
            move(gesture, NestedScrollSource.SideEffect)
            gesture.onPreFling(Velocity(0f, 1000f))
            move(gesture, NestedScrollSource.SideEffect)
            assertEquals(0, asks)
        }

    @Test
    fun newerMovementAndZeroVelocityCompletionAskNothing() =
        runTest {
            var asks = 0
            val gesture = OlderHistoryGesture({ true }, { asks++ })
            gesture.onGestureStart()
            gesture.onPostScroll(Offset(0f, -10f), Offset.Zero, NestedScrollSource.UserInput)
            gesture.onPreFling(Velocity.Zero)
            move(gesture)
            move(gesture, NestedScrollSource.SideEffect)
            assertEquals(0, asks)
        }

    @Test
    fun unconsumedPullOnANonScrollableThreadStillAsksWithoutConsuming() {
        var asks = 0
        val gesture = OlderHistoryGesture({ true }, { asks++ })
        gesture.onGestureStart()
        assertEquals(Offset.Zero, gesture.onPostScroll(Offset.Zero, Offset(0f, 10f), NestedScrollSource.UserInput))
        assertEquals(1, asks)
    }

    @Test
    fun pointerEndRejectsSemanticsAndCancellationRejectsFling() =
        runTest {
            var asks = 0
            val gesture = OlderHistoryGesture({ true }, { asks++ })
            gesture.onGestureStart()
            gesture.onGestureEnd()
            move(gesture)
            assertEquals(0, asks)
            gesture.onGestureStart()
            move(gesture)
            gesture.onGestureCancel()
            gesture.onPreFling(Velocity(0f, 1000f))
            move(gesture, NestedScrollSource.SideEffect)
            assertEquals(1, asks)
        }

    @Test
    fun aContinuingFlingCanAskAgainOnlyOnMovementAfterSlotRelease() =
        runTest {
            var loading = false
            var asks = 0
            val gesture =
                OlderHistoryGesture({ true }, {
                    if (!loading) {
                        asks++
                        loading = true
                    }
                })
            gesture.onGestureStart()
            move(gesture)
            gesture.onGestureEnd()
            gesture.onPreFling(Velocity(0f, 1000f))
            move(gesture, NestedScrollSource.SideEffect)
            assertEquals(1, asks)
            loading = false
            gesture.onPostScroll(Offset.Zero, Offset.Zero, NestedScrollSource.SideEffect)
            assertEquals(1, asks)
            move(gesture, NestedScrollSource.SideEffect)
            assertEquals(2, asks)
        }

    private fun move(
        gesture: OlderHistoryGesture,
        source: NestedScrollSource = NestedScrollSource.UserInput,
    ) {
        gesture.onPostScroll(Offset(0f, 10f), Offset.Zero, source)
    }
}

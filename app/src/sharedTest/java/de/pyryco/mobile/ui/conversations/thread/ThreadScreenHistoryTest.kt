package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** The list side of the history walk (#777): the oldest-end affordance and the reader's pull (#1352). */
@RunWith(AndroidJUnit4::class)
class ThreadScreenHistoryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun markersAreBetweenContent_andAReaderPullTargetsTheFirstCrossedGap() {
        val items = rows(3)
        val markers = listOf(ThreadHistoryMarker(1, items[1].historyKeys().first()), ThreadHistoryMarker(2, items[2].historyKeys().first()))
        var oldest = 0
        val demands = mutableListOf<Long>()
        setScreen({ threadState(items, ThreadHistoryTail.None).copy(historyMarkers = markers) }, onDemand = { oldest++ }, onGap = {
            demands +=
                it
        })
        composeRule.onNodeWithTag("history-gap:2").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(demands.isEmpty()) }
        val marker = composeRule.onNodeWithTag("history-gap:2").fetchSemanticsNode().boundsInRoot
        val older = composeRule.onNodeWithText("Row 2.").fetchSemanticsNode().boundsInRoot
        val newer = composeRule.onNodeWithText("Row 3.").fetchSemanticsNode().boundsInRoot
        assertTrue(older.bottom <= marker.top)
        assertTrue(marker.bottom <= newer.top)
        pullTowardOlder()
        composeRule.runOnIdle {
            assertEquals(listOf(2L), demands)
            assertEquals(0, oldest)
        }
    }

    @Test
    fun gapPageRemovingItsMarkerKeepsTheHeldDragOnTheSelectedWalk() {
        assertGapDragStaysSelected(removeMarker = true)
    }

    @Test
    fun gapPageSettlingThenDraggingPastItsMarkerKeepsTheSelectedWalk() {
        assertGapDragStaysSelected(removeMarker = false)
    }

    private fun assertGapDragStaysSelected(removeMarker: Boolean) {
        val items = rows(30)
        val marker = ThreadHistoryMarker(20, items[19].historyKeys().first())
        var state by mutableStateOf(threadState(items, ThreadHistoryTail.None).copy(historyMarkers = listOf(marker)))
        var oldest = 0
        val gaps = mutableListOf<Long>()
        setScreen({ state }, onDemand = {
            oldest++
            state = state.copy(historyTail = ThreadHistoryTail.Loading)
        }, onGap = {
            gaps += it
            state = state.copy(historyTail = ThreadHistoryTail.Loading)
        })
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        composeRule.onNodeWithTag("history-gap:20").assertIsDisplayed()
        val region = composeRule.onNodeWithTag(MESSAGE_REGION_TAG)
        region.performTouchInput {
            down(Offset(center.x, height * 0.2f))
            moveBy(Offset(0f, 40f), delayMillis = 400)
        }
        composeRule.runOnIdle {
            assertEquals(listOf(20L), gaps)
            assertEquals(0, oldest)
            // A controlled response frees the slot and may close the selected gap.
            state = state.copy(historyTail = ThreadHistoryTail.None, historyMarkers = if (removeMarker) emptyList() else listOf(marker))
        }
        composeRule.runOnIdle { assertEquals(0, oldest) }
        region.performTouchInput {
            moveBy(Offset(0f, height * 0.4f), delayMillis = 600)
        }
        composeRule.onNodeWithTag("history-gap:20").assertIsNotDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(20L), gaps)
            assertEquals("A settled gap pull must not switch to the backwards walk", 0, oldest)
        }
        region.performTouchInput {
            advanceEventTime(200)
            up()
        }
        // A fresh touch is free to select the backwards walk now that the marker is absent.
        pullTowardOlder(fraction = 0.1f)
        composeRule.runOnIdle { assertEquals(1, oldest) }
    }

    @Test
    fun gapPageRemovingItsMarkerKeepsTheContinuingFlingOnTheSelectedWalk() {
        assertGapFlingStaysSelected(removeMarker = true)
    }

    @Test
    fun gapPageSettlingThenFlingingPastItsMarkerKeepsTheSelectedWalk() {
        assertGapFlingStaysSelected(removeMarker = false)
    }

    private fun assertGapFlingStaysSelected(removeMarker: Boolean) {
        val items = rows(30)
        val marker = ThreadHistoryMarker(20, items[19].historyKeys().first())
        var state by mutableStateOf(threadState(items, ThreadHistoryTail.None).copy(historyMarkers = listOf(marker)))
        var oldest = 0
        val gaps = mutableListOf<Long>()
        setScreen({ state }, onDemand = {
            oldest++
            state = state.copy(historyTail = ThreadHistoryTail.Loading)
        }, onGap = {
            gaps += it
            state = state.copy(historyTail = ThreadHistoryTail.Loading)
        })
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        composeRule.onNodeWithTag("history-gap:20").assertIsDisplayed()
        val region = composeRule.onNodeWithTag(MESSAGE_REGION_TAG)
        composeRule.mainClock.autoAdvance = false
        region.performTouchInput {
            swipeWithVelocity(
                start = Offset(center.x, height * 0.2f),
                end = Offset(center.x, height * 0.4f),
                endVelocity = 5000f,
            )
        }
        composeRule.runOnIdle {
            assertEquals(listOf(20L), gaps)
            assertEquals(0, oldest)
            state = state.copy(historyTail = ThreadHistoryTail.None, historyMarkers = if (removeMarker) emptyList() else listOf(marker))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 1.").assertIsNotDisplayed()
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("history-gap:20").assertIsNotDisplayed()
        composeRule.onNodeWithText("Row 1.").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(20L), gaps)
            assertEquals("A gap fling must not switch to the backwards walk", 0, oldest)
        }
        pullTowardOlder(fraction = 0.1f)
        composeRule.runOnIdle { assertEquals(1, oldest) }
    }

    @Test
    fun aNonRenderingNewerSpanHasAPullableMarker_withoutAskingOnArrival() {
        val demands = mutableListOf<Long>()
        setScreen({
            threadState(rows(2), ThreadHistoryTail.None).copy(historyMarkers = listOf(ThreadHistoryMarker(2, "")))
        }, onDemand = {}, onGap = {
            demands += it
        })
        composeRule.onNodeWithTag("history-gap:2").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(demands.isEmpty()) }
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(listOf(2L), demands) }
    }

    @Test
    fun loading_row_is_shown_at_the_oldest_end_only_while_a_page_is_in_flight() {
        var state by mutableStateOf(threadState(rows(count = 3), ThreadHistoryTail.None))
        setScreen({ state }, onDemand = {})
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(historyTail = ThreadHistoryTail.Loading) }
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(historyTail = ThreadHistoryTail.None) }
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertDoesNotExist()
    }

    // --- #1352: only the reader's pull asks ------------------------------------------------------

    @Test
    fun a_pull_on_an_empty_thread_asks_once() {
        var demands = 0
        var pending = false
        setScreen({ threadState(emptyList(), ThreadHistoryTail.None).copy(hasMessages = false) }, onDemand = {
            if (!pending) {
                demands++
                pending = true
            }
        })
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(1, demands) }
    }

    @Test
    fun a_pull_on_a_thread_too_short_to_scroll_asks_once() {
        var demands = 0
        var pending = false
        setScreen({ threadState(rows(count = 3), ThreadHistoryTail.None) }, onDemand = {
            if (!pending) {
                demands++
                pending = true
            }
        })
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(1, demands) }
    }

    @Test
    fun a_long_thread_prefetches_only_near_its_oldest_end() {
        val count = 120
        var demands = 0
        var pending = false
        setScreen({ threadState(rows(count), ThreadHistoryTail.None) }, onDemand = {
            if (!pending) {
                demands++
                pending = true
            }
        })

        assertScreenHistoryDistance(minViewports = 3f)
        pullInReadingArea(fraction = 0.1f)
        assertScreenHistoryDistance(minViewports = 2f)
        composeRule.runOnIdle { assertEquals(0, demands) }

        // Semantics positioning asks nothing; only the subsequent reader movement asks.
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(count - 1)
        assertScreenHistoryDistance(maxViewports = 0.25f)
        composeRule.runOnIdle { assertEquals(0, demands) }
        pullInReadingArea(fraction = 0.1f)
        composeRule.runOnIdle {
            assertEquals(1, demands)
            pending = false
        }
        assertScreenHistoryDistance(maxViewports = 0.25f)
        pullInReadingArea(fraction = 0.1f)
        composeRule.runOnIdle { assertEquals(2, demands) }
    }

    @Test
    fun the_prefetch_band_is_two_current_viewports_from_the_oldest_end() {
        val count = 120
        var demands = 0
        var pending = false
        setScreen({ threadState(rows(count), ThreadHistoryTail.None) }, onDemand = {
            if (!pending) {
                demands++
                pending = true
            }
        })

        positionScreenFromOldest(count, viewports = 1.3f)
        assertScreenHistoryDistance(minViewports = 1f, maxViewports = 1.6f)
        composeRule.onNodeWithText("Row 1.").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, demands) }
        pullInReadingArea(fraction = 0.05f)
        assertScreenHistoryDistance(minViewports = 0.5f, maxViewports = 2f)
        composeRule.onNodeWithText("Row 1.").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, demands)
            pending = false
        }

        positionScreenFromOldest(count, viewports = 3f)
        assertScreenHistoryDistance(minViewports = 2.5f)
        composeRule.runOnIdle { assertEquals(1, demands) }
        pullInReadingArea(fraction = 0.05f)
        assertScreenHistoryDistance(minViewports = 2f)
        composeRule.runOnIdle { assertEquals(1, demands) }
    }

    @Test
    fun a_pull_while_a_page_is_loading_asks_nothing() {
        var demands = 0
        setScreen({ threadState(rows(count = 3), ThreadHistoryTail.Loading) }, onDemand = { demands++ })
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(0, demands) }
    }

    @Test
    fun reaching_the_oldest_row_or_cycling_the_tail_asks_nothing() {
        // Opening, a page arriving and the oldest row coming into view are not the reader's pull.
        var state by mutableStateOf(threadState(rows(count = 30), ThreadHistoryTail.None))
        var demands = 0
        setScreen({ state }, onDemand = { demands++ })
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        composeRule.waitForIdle()
        listOf(
            ThreadHistoryTail.Loading,
            ThreadHistoryTail.Retry,
            ThreadHistoryTail.DeadEnd,
            ThreadHistoryTail.Offline,
            ThreadHistoryTail.None,
        ).forEach { tail ->
            composeRule.runOnIdle { state = state.copy(historyTail = tail) }
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle { state = state.copy(items = rows(count = 10, prefix = "Older") + state.items) }
        composeRule.waitForIdle()
        assertEquals(0, demands)
    }

    @Test
    fun offline_notice_is_shown_at_the_oldest_end() {
        setScreen({ threadState(rows(count = 3), ThreadHistoryTail.Offline) }, onDemand = {})
        composeRule.onNodeWithText(HISTORY_OFFLINE_TEXT).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun a_prepended_page_leaves_the_row_the_reader_is_looking_at_where_it_was() {
        // reverseLayout puts older rows at HIGHER indices, so a prepend lands beyond the viewport rather
        // than shifting it, and the per-subtype keys are computed from item fields and never position.
        var state by mutableStateOf(threadState(rows(count = 30), ThreadHistoryTail.None))
        setScreen({ state }, onDemand = {})
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 19.").assertIsDisplayed()
        val before = composeRule.onNodeWithText("Row 19.").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { state = state.copy(items = rows(count = 20, prefix = "Older") + state.items) }
        composeRule.onNodeWithText("Row 19.").assertIsDisplayed()
        assertEquals(before, composeRule.onNodeWithText("Row 19.").fetchSemanticsNode().boundsInRoot)
    }

    // --- #778: the failure states of the same oldest-end slot --------------------------------------

    @Test
    fun retry_row_is_shown_for_a_retryable_failure_and_its_press_reaches_the_callback() {
        var state by mutableStateOf(threadState(rows(count = 3), ThreadHistoryTail.Retry))
        var retries = 0
        setScreen({ state }, onDemand = {}, onRetryOlder = { retries++ })

        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).performClick()
        assertEquals(1, retries)

        // The slot is one slot: settling back to loading replaces the row rather than stacking one.
        composeRule.runOnIdle { state = state.copy(historyTail = ThreadHistoryTail.Loading) }
        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun dead_end_row_is_shown_for_a_permanent_failure_with_nothing_to_press() {
        val state = threadState(rows(count = 3), ThreadHistoryTail.DeadEnd)
        var retries = 0
        setScreen({ state }, onDemand = {}, onRetryOlder = { retries++ })

        composeRule.onNodeWithContentDescription(HISTORY_DEAD_END_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).assertDoesNotExist()
        // A dead end offers no affordance at all: pressing where the retry would be does nothing.
        composeRule.onNodeWithContentDescription(HISTORY_DEAD_END_DESCRIPTION).performClick()
        assertEquals(0, retries)
    }

    @Test
    fun dragEnteringTwoViewportRangePrefetchesWhileOldestIsStillHidden() {
        var asks = 0
        var pending = false
        val list =
            setPrefetchList(initialIndex = 0, onDemand = {
                if (!pending) {
                    asks++
                    pending = true
                }
            })
        positionPrefetchOutsideBand(list)
        assertPrefetchBand(list, oldest = 29, inside = false)
        composeRule.runOnIdle { assertEquals(0, asks) }
        composeRule.onNodeWithTag("prefetch-list").performTouchInput {
            down(Offset(center.x, height * 0.05f))
            moveBy(Offset(0f, height * 0.8f), delayMillis = 1000)
            advanceEventTime(200)
            up()
        }
        assertPrefetchBand(list, oldest = 29, inside = true)
        composeRule.runOnIdle { assertEquals(1, asks) }
    }

    @Test
    fun flingEnteringRangeFromOutsideAsksOnlyAfterTouchRelease() {
        var asks = 0
        var pending = false
        val list =
            setPrefetchList(initialIndex = 8, onDemand = {
                if (!pending) {
                    asks++
                    pending = true
                }
            })
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("prefetch-list").performTouchInput {
            swipeWithVelocity(start = Offset(center.x, 10f), end = Offset(center.x, 150f), endVelocity = 5000f)
        }
        assertEquals(0, asks)
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle {
            assertEquals(1, asks)
            assertTrue(list.firstVisibleItemIndex > 8)
        }
    }

    @Test
    fun viewportResizeAndMixedRowsChangeRangeButOnlyMovementAsks() {
        var height by mutableStateOf(100.dp)
        var asks = 0
        var pending = false
        val list =
            setPrefetchList(
                20,
                viewportHeight = { height },
                mixed = true,
                onDemand = {
                    if (!pending) {
                        asks++
                        pending = true
                    }
                },
            )
        composeRule.runOnIdle {
            assertTrue(!list.layoutInfo.isNearOldestEnd(29, list.layoutInfo.viewportSize.height * 2f))
            height = 200.dp
        }
        composeRule.runOnIdle {
            assertTrue(list.layoutInfo.isNearOldestEnd(29, list.layoutInfo.viewportSize.height * 2f))
            assertEquals(0, asks)
        }
        composeRule.onNodeWithTag("prefetch-list").performTouchInput {
            down(Offset(center.x, 20f))
            moveBy(Offset(0f, 40f), delayMillis = 300)
            advanceEventTime(200)
            up()
        }
        composeRule.runOnIdle { assertEquals(1, asks) }
    }

    @Test
    fun pageArrivingDuringAHeldDragPreservesAnchorAndOnlyFurtherMovementAsks() {
        var count by mutableStateOf(30)
        var loading = false
        var asks = 0
        val list =
            setPrefetchList(0, count = { count }, onDemand = {
                if (!loading) {
                    asks++
                    loading = true
                }
            })
        positionPrefetchOutsideBand(list)
        assertPrefetchBand(list, oldest = count - 1, inside = false)
        composeRule.runOnIdle { assertEquals(0, asks) }
        val surface = composeRule.onNodeWithTag("prefetch-list")
        surface.performTouchInput {
            down(Offset(center.x, height * 0.05f))
            moveBy(Offset(0f, height * 0.8f), delayMillis = 600)
        }
        assertPrefetchBand(list, oldest = count - 1, inside = true)
        val before =
            composeRule.runOnIdle {
                assertEquals(1, asks)
                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            }
        composeRule.runOnIdle {
            count = 32
            loading = false
        }
        assertPrefetchBand(list, oldest = count - 1, inside = true)
        composeRule.runOnIdle {
            assertEquals(before, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
            assertEquals(1, asks)
        }
        surface.performTouchInput {
            advanceEventTime(600)
        }
        composeRule.runOnIdle { assertEquals("A resting finger cannot request the arrived page", 1, asks) }
        assertPrefetchBand(list, oldest = count - 1, inside = true)
        surface.performTouchInput {
            moveBy(Offset(0f, height * 0.1f), delayMillis = 600)
        }
        assertPrefetchBand(list, oldest = count - 1, inside = true)
        composeRule.runOnIdle { assertEquals(2, asks) }
        surface.performTouchInput {
            advanceEventTime(200)
            up()
        }
        // The controlled page is already loaded: subsequent reader scrolling reaches its rows.
        repeat(4) {
            surface.performTouchInput { swipeDown(durationMillis = 1000) }
        }
        composeRule.onNodeWithText("Prefetch 30").assertExists()
        composeRule.runOnIdle { assertTrue(list.layoutInfo.visibleItemsInfo.any { it.index >= 30 }) }
    }

    @Test
    fun semanticsAfterATouchEndsCannotReuseItsProvenance() {
        var asks = 0
        var pending = false
        setPrefetchList(27, onDemand = {
            if (!pending) {
                asks++
                pending = true
            }
        })
        val surface = composeRule.onNodeWithTag("prefetch-list")
        surface.performTouchInput {
            down(center)
            up()
        }
        surface.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100f) }
        composeRule.runOnIdle { assertEquals(0, asks) }
    }

    @Test
    fun fullThreadPrefetchArrivalKeepsTheVisibleRowAndLetsTheReaderEnterOlderRows() {
        var state by mutableStateOf(threadState(rows(80), ThreadHistoryTail.None))
        var asks = 0
        setScreen({ state }, onDemand = {
            if (state.historyTail != ThreadHistoryTail.Loading) {
                asks++
                state = state.copy(historyTail = ThreadHistoryTail.Loading)
            }
        })
        val list = composeRule.onNode(hasScrollToIndexAction())
        val region = composeRule.onNodeWithTag(MESSAGE_REGION_TAG)
        list.performScrollToIndex(79)
        val bounds = region.fetchSemanticsNode().boundsInRoot
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -bounds.height * 2.2f) }
        composeRule.runOnIdle { assertEquals(0, asks) }
        region.performTouchInput {
            down(Offset(center.x, height * 0.3f))
            moveBy(Offset(0f, height * 0.35f), delayMillis = 800)
        }
        composeRule.runOnIdle { assertEquals(1, asks) }
        composeRule.onNodeWithText("Row 1.").assertDoesNotExist()
        val anchor =
            composeRule
                .onAllNodes(hasText("Row ", substring = true))
                .fetchSemanticsNodes()
                .first { it.boundsInRoot.top > bounds.top + 100 && it.boundsInRoot.bottom < bounds.bottom - 150 }
        val text = anchor.config[SemanticsProperties.Text].single().text
        val before = anchor.boundsInRoot
        // Complete the controlled response while its spinner is still ahead of the reader.
        composeRule.runOnIdle {
            state = state.copy(items = rows(10, prefix = "Older") + state.items, historyTail = ThreadHistoryTail.None)
        }
        assertEquals(before, composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot)
        composeRule.runOnIdle { assertEquals(1, asks) }
        region.performTouchInput {
            advanceEventTime(200)
            up()
        }
        // Hold later requests unresolved. The arrived page can still be read without waiting on them.
        repeat(7) { pullTowardOlder(fraction = 0.5f) }
        composeRule.onNodeWithText("Older 5.").assertIsDisplayed()
    }

    /** Small geometry-controlled fixture exercising the production modifier and distance predicate. */
    private fun setPrefetchList(
        initialIndex: Int,
        count: () -> Int = { 30 },
        viewportHeight: () -> Dp = { 200.dp },
        mixed: Boolean = false,
        onDemand: () -> Unit,
    ): LazyListState {
        val list = LazyListState(firstVisibleItemIndex = initialIndex)
        composeRule.setContent {
            PyrycodeMobileTheme {
                val oldest by rememberUpdatedState(count() - 1)
                val demand by rememberUpdatedState(onDemand)
                val gesture =
                    remember(list) {
                        OlderHistoryGesture(
                            nearOldestEnd = { list.layoutInfo.isNearOldestEnd(oldest, list.layoutInfo.viewportSize.height * 2f) },
                            onDemand = { demand() },
                        )
                    }
                LazyColumn(
                    state = list,
                    reverseLayout = true,
                    modifier =
                        Modifier
                            .width(200.dp)
                            .height(viewportHeight())
                            .testTag("prefetch-list")
                            .olderHistoryPull(gesture),
                ) {
                    items(count(), key = { "prefetch:$it" }) { index ->
                        val rowHeight =
                            if (!mixed) {
                                50.dp
                            } else if (index % 2 == 0) {
                                40.dp
                            } else {
                                60.dp
                            }
                        Text("Prefetch $index", Modifier.height(rowHeight))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return list
    }

    /** A user drag toward older messages: the finger moves down over [fraction] of the message region. */
    private fun pullTowardOlder(fraction: Float = 0.4f) {
        composeRule.onNodeWithTag(MESSAGE_REGION_TAG).performTouchInput {
            swipeDown(startY = height * 0.3f, endY = height * (0.3f + fraction), durationMillis = 400)
        }
        composeRule.waitForIdle()
    }

    /** Uniform rows let measured pitch locate the offscreen oldest row independently. */
    private fun assertScreenHistoryDistance(
        minViewports: Float = Float.NEGATIVE_INFINITY,
        maxViewports: Float = Float.POSITIVE_INFINITY,
    ) {
        val viewport = composeRule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        val visible = visibleScreenRows()
        val (olderNumber, olderTop) = visible.first()
        val pitch = screenRowPitch(visible)
        val headerBottom =
            composeRule
                .onNodeWithTag("thread-top-bar")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val distance = headerBottom - (olderTop - (olderNumber - 1) * pitch)
        val viewports = distance / viewport.height
        assertTrue("Hidden history is $viewports viewports; expected $minViewports..$maxViewports", viewports in minViewports..maxViewports)
    }

    private fun visibleScreenRows(): List<Pair<Int, Float>> {
        val viewport = composeRule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        val visible =
            composeRule
                .onAllNodes(hasText("Row ", substring = true))
                .fetchSemanticsNodes()
                .filter { it.boundsInRoot.top > viewport.top && it.boundsInRoot.bottom < viewport.bottom }
                .map { node ->
                    node.config[SemanticsProperties.Text]
                        .single()
                        .text
                        .removePrefix("Row ")
                        .removeSuffix(".")
                        .toInt() to node.boundsInRoot.top
                }.sortedBy { it.first }
        assertTrue("Need two fully measured uniform rows: $visible", visible.size >= 2)
        return visible
    }

    private fun screenRowPitch(visible: List<Pair<Int, Float>>): Float {
        val (olderNumber, olderTop) = visible.first()
        val (newerNumber, newerTop) = visible.last()
        val pitch = (newerTop - olderTop) / (newerNumber - olderNumber)
        assertTrue("Row pitch must be positive", pitch > 0f)
        return pitch
    }

    private fun positionScreenFromOldest(
        count: Int,
        viewports: Float,
    ) {
        val list = composeRule.onNode(hasScrollToIndexAction())
        // Jump by measured row pitch: a large animated ScrollBy can still be settling on a device.
        list.performScrollToIndex(0)
        composeRule.waitForIdle()
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val visible = visibleScreenRows()
        val pitch = screenRowPitch(visible)
        val (number, top) = visible.last()
        val headerBottom =
            composeRule
                .onNodeWithTag("thread-top-bar")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val currentDistance = headerBottom - (top - (number - 1) * pitch)
        val targetNumber = number + ((viewports * viewport.height - currentDistance) / pitch).roundToInt()
        assertTrue("Fixture must contain the target row $targetNumber", targetNumber in 1..count)
        list.performScrollToIndex(count - targetNumber)
        composeRule.waitForIdle()
    }

    /** The drawing viewport underlaps chrome; touch starts in the measured clear reading area. */
    private fun pullInReadingArea(fraction: Float) {
        val list = composeRule.onNode(hasScrollToIndexAction())
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot
        val composer = composeRule.onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot
        val start = header.bottom - viewport.top + (composer.top - header.bottom) * 0.2f
        val distance = viewport.height * fraction
        assertTrue("Gesture must stay between header and composer", start + distance < composer.top - viewport.top)
        list.performTouchInput {
            down(Offset(center.x, start))
            moveBy(Offset(0f, distance), delayMillis = 100)
            // Inspect the drag distance without a released swipe's continuing fling.
            advanceEventTime(250)
            up()
        }
        composeRule.waitForIdle()
    }

    private fun positionPrefetchOutsideBand(list: LazyListState) {
        val surface = composeRule.onNodeWithTag("prefetch-list")
        surface.performScrollToIndex(29)
        val distance = composeRule.runOnIdle { list.layoutInfo.viewportSize.height * 2.1f }
        surface.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -distance) }
        composeRule.waitForIdle()
    }

    private fun assertPrefetchBand(
        list: LazyListState,
        oldest: Int,
        inside: Boolean,
    ) {
        composeRule.runOnIdle {
            val layout = list.layoutInfo
            assertEquals(
                "index=${list.firstVisibleItemIndex}, offset=${list.firstVisibleItemScrollOffset}, viewport=${layout.viewportSize.height}",
                inside,
                layout.isNearOldestEnd(oldest, layout.viewportSize.height * 2f),
            )
            assertTrue("The oldest row must still be hidden", layout.visibleItemsInfo.none { it.index == oldest })
        }
    }

    private fun setScreen(
        state: () -> ThreadUiState,
        onDemand: () -> Unit,
        onRetryOlder: () -> Unit = {},
        onGap: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onDemandOlderHistory = onDemand,
                    onDemandHistoryGap = onGap,
                    onRetryOlderHistory = onRetryOlder,
                )
            }
        }
    }

    private fun threadState(
        items: List<ThreadItem>,
        historyTail: ThreadHistoryTail,
    ) = ThreadUiState(
        conversationId = "conversation",
        displayName = "History walk",
        isPromoted = true,
        hasMessages = true,
        items = items,
        historyTail = historyTail,
    )

    private fun rows(
        count: Int,
        prefix: String = "Row",
    ): List<ThreadItem> =
        (1..count).map { index ->
            ThreadItem.MessageItem(
                Message(
                    id = "$prefix-$index",
                    sessionId = "s1",
                    role = Role.Assistant,
                    content = "$prefix $index.",
                    timestamp = Instant.parse("2026-09-22T10:00:00Z"),
                    isStreaming = false,
                ),
            )
        }

    // #1605: Figma 689:4330 draws the Retry row's label in its full line box, and every history tail row
    // now leaves the stream's standard 16dp gap below it, which this row previously had none of at all.
    // The row's own reported height (its 44dp content plus its own trailing gutter) grows from 44dp to 60dp.
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun retryRow_keepsItsLabelsFullLineBox_andLeavesTheStandard16dpGapBelowIt() {
        composeRule.setContent {
            PyrycodeMobileTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .testTag("retry-row"),
                ) {
                    HistoryRetryRow(onRetry = {})
                }
            }
        }

        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule
            .onNode(
                androidx.compose.ui.test
                    .hasText("Try again", substring = true),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(
            androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            results
                .single()
                .layoutInput.style.lineHeightStyle
                ?.trim,
        )

        val retry = composeRule.onNodeWithTag("retry-row").getUnclippedBoundsInRoot()
        assertEquals(60f, retry.height.value, 0.5f)
    }

    private companion object {
        const val HISTORY_LOADING_DESCRIPTION = "Loading earlier messages in this conversation"
        const val HISTORY_RETRY_DESCRIPTION = "Couldn't load earlier messages. Try again."
        const val HISTORY_DEAD_END_DESCRIPTION = "Earlier messages in this conversation are unavailable"
        const val HISTORY_OFFLINE_TEXT = "Older messages require a connection."
        const val MESSAGE_REGION_TAG = "thread-message-region"
    }
}

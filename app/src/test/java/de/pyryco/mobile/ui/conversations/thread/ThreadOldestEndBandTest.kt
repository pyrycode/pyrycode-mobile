package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prefetch measures the reversed list's loaded distance using exact edges or estimated unseen rows.
 * Top content padding lies past the oldest row and must not widen the band.
 */
class ThreadOldestEndBandTest {
    @Test
    fun the_band_is_measured_from_the_oldest_end_without_content_padding() {
        assertTrue(layout(distanceFromEnd = BAND, afterPadding = 0).isNearOldestEnd(OLDEST, BAND.toFloat()))
        assertFalse(layout(distanceFromEnd = BAND + 1, afterPadding = 0).isNearOldestEnd(OLDEST, BAND.toFloat()))
    }

    @Test
    fun top_content_padding_does_not_widen_the_band() {
        assertTrue(layout(distanceFromEnd = 0, afterPadding = PADDING).isNearOldestEnd(OLDEST, BAND.toFloat()))
        assertTrue(layout(distanceFromEnd = BAND, afterPadding = PADDING).isNearOldestEnd(OLDEST, BAND.toFloat()))
        assertFalse(layout(distanceFromEnd = BAND + PADDING / 2, afterPadding = PADDING).isNearOldestEnd(OLDEST, BAND.toFloat()))
    }

    @Test
    fun an_oldest_row_not_laid_out_is_outside_the_band() {
        val info = FakeLayoutInfo(visibleItemsInfo = emptyList(), viewportEnd = VIEWPORT, after = PADDING)
        assertFalse(info.isNearOldestEnd(OLDEST, BAND.toFloat()))
    }

    @Test
    fun hiddenOldestRowUsesMixedMeasuredHeightsAndSpacing() {
        // Highest visible edge is at the viewport edge. Four unmeasured rows remain,
        // estimated at the visible mean of 300px plus 10px spacing each.
        val info =
            FakeLayoutInfo(
                visibleItemsInfo = listOf(FakeItem(24, 0, 200), FakeItem(25, 210, 400)),
                viewportEnd = 610,
                after = 0,
            )
        assertTrue(info.isNearOldestEnd(29, 1240f))
        assertFalse(info.isNearOldestEnd(29, 1239f))
    }

    @Test
    fun twoViewportThresholdChangesWithTheCurrentViewportHeight() {
        val info = FakeLayoutInfo(listOf(FakeItem(26, 200, 300)), viewportEnd = 500, after = 0)
        assertTrue(info.isNearOldestEnd(29, 2f * 500))
        assertFalse(info.isNearOldestEnd(29, 2f * 400))
    }

    @Test
    fun promptsAndTheTailDoNotBiasTheUnseenRowEstimate() {
        val info =
            FakeLayoutInfo(
                listOf(FakeItem(0, -5000, 5000), FakeItem(5, 0, 100), FakeItem(7, 110, 5000)),
                viewportEnd = 100,
                after = 28,
            )
        assertTrue(info.isNearOldestEnd(6, 138f, firstHistoryIndex = 2))
        assertFalse(info.isNearOldestEnd(6, 137f, firstHistoryIndex = 2))
    }

    @Test
    fun emptyHistoryIsAlwaysPullable() {
        val info = FakeLayoutInfo(emptyList(), viewportEnd = 600, after = 28)
        assertTrue(info.isNearOldestEnd(-1, 1200f))
    }

    /** The oldest row [distanceFromEnd] px short of the scroll's oldest end, past [afterPadding] px of padding. */
    private fun layout(
        distanceFromEnd: Int,
        afterPadding: Int,
    ): LazyListLayoutInfo {
        val offset = VIEWPORT - afterPadding + distanceFromEnd - ROW
        return FakeLayoutInfo(visibleItemsInfo = listOf(FakeItem(OLDEST, offset, ROW)), viewportEnd = VIEWPORT, after = afterPadding)
    }

    private class FakeItem(
        override val index: Int,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo {
        override val key: Any = index
    }

    private class FakeLayoutInfo(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        viewportEnd: Int,
        after: Int,
    ) : LazyListLayoutInfo {
        override val viewportStartOffset: Int = 0
        override val viewportEndOffset: Int = viewportEnd
        override val totalItemsCount: Int = OLDEST + 1
        override val mainAxisItemSpacing: Int = 10
        override val reverseLayout: Boolean = true
        override val afterContentPadding: Int = after
    }

    private companion object {
        const val OLDEST = 29
        const val VIEWPORT = 600
        const val ROW = 300
        const val BAND = 200
        const val PADDING = 28
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1352's ask band measures distance from the reversed list's oldest end. #1562 gave the list top content
 * padding, which under `reverseLayout` lies past the oldest row; it must not widen the band.
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

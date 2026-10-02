package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactionBoundaryLabelTest {
    private fun boundary(
        pre: Long?,
        post: Long?,
        manual: Boolean,
    ) = ThreadItem.CompactionBoundary(pre, post, manual, Instant.parse("2026-09-23T12:00:00Z"))

    @Test
    fun label_bothCountsAndManual_claimsSizesAndByYou() {
        assertEquals("Conversation compacted, 24k → 3k tokens by you", compactionBoundaryLabel(boundary(24000, 3000, manual = true)))
    }

    @Test
    fun label_bothCountsNotManual_claimsSizesOnly() {
        assertEquals("Conversation compacted, 182.5k → 850 tokens", compactionBoundaryLabel(boundary(182_450, 850, manual = false)))
    }

    @Test
    fun label_eitherCountNull_claimsNoSize() {
        assertEquals("Conversation compacted by you", compactionBoundaryLabel(boundary(24000, null, manual = true)))
        assertEquals("Conversation compacted", compactionBoundaryLabel(boundary(null, 3000, manual = false)))
        assertEquals("Conversation compacted", compactionBoundaryLabel(boundary(null, null, manual = false)))
    }

    // #1358: desktop's failed branch wins over any count or trigger.
    @Test
    fun label_failed_readsCompactionFailed() {
        val failed =
            ThreadItem.CompactionBoundary(
                null,
                null,
                manual = false,
                occurredAt = Instant.parse("2026-09-23T12:00:00Z"),
                failed = true,
            )
        assertEquals("Compaction failed", compactionBoundaryLabel(failed))
    }

    @Test
    fun label_zeroIsAStatedCount_notAMissingOne() {
        assertEquals("Conversation compacted, 24k → 0 tokens", compactionBoundaryLabel(boundary(24000, 0, manual = false)))
    }

    @Test
    fun label_growthIsRenderedAsStated() {
        assertEquals("Conversation compacted, 3k → 24k tokens", compactionBoundaryLabel(boundary(3000, 24000, manual = false)))
    }

    @Test
    fun tokenCount_belowOneThousand_isThePlainNumber() {
        assertEquals("0", compactionTokenCount(0))
        assertEquals("999", compactionTokenCount(999))
    }

    @Test
    fun tokenCount_thousands_dropATrailingZeroTenth() {
        assertEquals("1k", compactionTokenCount(1000))
        assertEquals("24k", compactionTokenCount(24000))
        assertEquals("24k", compactionTokenCount(24_049))
    }

    @Test
    fun tokenCount_roundsTenthsHalfUp() {
        assertEquals("1.3k", compactionTokenCount(1250))
        assertEquals("1.2k", compactionTokenCount(1249))
        assertEquals("3.5k", compactionTokenCount(3456))
        assertEquals("1000k", compactionTokenCount(999_950))
    }

    @Test
    fun tokenCount_largestSafeCount_doesNotOverflow() {
        assertEquals("9007199254741k", compactionTokenCount(9_007_199_254_740_991))
    }
}

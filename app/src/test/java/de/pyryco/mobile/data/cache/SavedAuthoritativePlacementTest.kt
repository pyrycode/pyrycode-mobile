package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedAuthoritativePlacementTest {
    private fun fact(
        id: Int,
        parent: Int,
        display: Boolean = false,
    ) = AuthoritativeItem("host", "thread", "epoch", id.toLong(), id, id.toLong(), "", display = display, parentId = parent.toLong())

    @Test fun availabilityInvariant_rootFirstDeepChainReducesEveryContribution() {
        val facts = (1..18_000).map { fact(it, it - 1, display = it == 18_000) }
        val expected = facts.last().savedContributions("" to "")
        val summaries = savedContributionIndex(facts)
        assertEquals(18_000, summaries.size)
        facts.forEach { assertEquals("contribution for ${it.id}", expected, summaries[it.id]) }
    }

    @Test fun availabilityInvariant_deepCycleTerminatesWithoutLosingContributions() {
        val facts = (1..18_000).map { fact(it, if (it == 1) 18_000 else it - 1, display = it == 18_000) }
        val expected = facts.last().savedContributions("" to "")
        val summaries = savedContributionIndex(facts)
        assertEquals(18_000, summaries.size)
        facts.forEach { assertEquals("cyclic contribution for ${it.id}", expected, summaries[it.id]) }
    }

    @Test fun placementInvariant_backEdgesKeepTheirEmptyContributionAndHeldOrder() {
        val root = fact(1, 3, display = true)
        val middle = fact(2, 1)
        val last = fact(3, 2, display = true)
        val self = fact(4, 4, display = true)
        val empty = fact(5, 5)
        val summaries = savedContributionIndex(listOf(root, middle, last, self, empty))
        val lastSummary = last.savedContributions("" to "")
        assertEquals(lastSummary, summaries[last.id])
        assertEquals(lastSummary, summaries[middle.id])
        assertEquals(root.savedContributions(lastSummary), summaries[root.id])
        assertEquals(self.savedContributions("" to ""), summaries[self.id])
        assertEquals("" to "", summaries[empty.id])
    }
}

package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedAuthoritativePlacementTest {
    @Test fun availabilityInvariant_nestedAgentKeysHaveLinearStorage() {
        val facts = (1..18_000).map { fact(it, it - 1, display = true).copy(agent = true) }
        val cyclic = facts.map { if (it.id == 1L) it.copy(parentId = 18_000) else it }
        for (input in listOf(facts, facts.asReversed(), cyclic)) {
            val placement = SavedPlacementIndex(input)
            val keys = facts.map { placement.keys.getValue(it.id).first }
            assertEquals(keys, keys.sorted())
            assertTrue("largest key=${keys.maxOf { it.length }}", keys.all { it.length <= 62 })
            assertTrue("aggregate characters=${keys.sumOf { it.length }}", keys.sumOf { it.length } <= facts.size * 62)
            assertEquals(facts.size, placement.ancestryVisits)
            assertEquals(facts.size, placement.rankVisits)
            assertEquals(facts.size, placement.contributions.size)
            assertEquals(facts.size, placement.anchors.size)
            println("nested_agent_allocation ancestry=${placement.ancestryVisits} ranks=${placement.rankVisits} characters=${keys.sumOf { it.length }}")
        }
    }

    @Test fun placementInvariant_sharedRanksKeepBranchesTiesTransparentParentsAndCycleRoots() {
        fun item(id: Int, parent: Int, order: Long, agent: Boolean = true) =
            fact(id, parent, display = true).copy(agent = agent, orderKey = order, rendererOrder = order)
        val facts = listOf(
            item(1, 0, 50), item(2, 1, 2), item(3, 1, 2), item(4, 2, 400, agent = false),
            item(5, 4, 1), item(6, 5, 30, agent = false), item(7, 0, 10, agent = false),
        )
        val expected = listOf(7L, 1L, 2L, 5L, 6L, 4L, 3L)
        for (input in listOf(facts, facts.asReversed())) {
            val index = SavedPlacementIndex(input)
            assertEquals(expected, index.keys.entries.sortedBy { it.value.first }.map { it.key })
            assertEquals(5L, index.anchors.getValue(1)?.third?.id)
            assertEquals(2L, index.anchors.getValue(1)?.second?.id)
        }
        val cycle = SavedPlacementIndex(listOf(item(8, 9, 3), item(9, 8, 4), item(10, 8, 5)))
        assertEquals(listOf(8L, 9L, 10L), cycle.keys.entries.sortedBy { it.value.first }.map { it.key })
        assertTrue(cycle.anchors.values.all { it == null })
    }

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

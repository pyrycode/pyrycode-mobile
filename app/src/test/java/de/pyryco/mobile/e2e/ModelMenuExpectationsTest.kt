package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelMenuExpectationsTest {
    @Test fun uniqueInheritedMatchBeyondMergedCapHasOnlyFamilyFallback() {
        val hidden = row("opus", "claude-opus-4-7")
        val menu = ModelMenu(prefix() + row("default", hidden.resolvedModel) + hidden, 0)
        assertEquals(32, renderedModelRows(menu).size)
        assertNull(announcedRow(menu, "opus"))
        assertNull(announcedRow(menu, hidden.resolvedModel))
        assertNull(announcedRow(menu, "claude-opus-older"))
        assertEquals("Opus", inheritedModelLabel(menu))
        assertNull(inheritedModelRow(menu))
    }

    @Test fun ambiguityBetweenVisibleAndOverflowRowsMarksNeither() {
        val visible = row("opus", "claude-opus-4-7")
        val hidden = row("opus-other", visible.resolvedModel)
        val menu = ModelMenu(listOf(visible) + prefix() + row("default", visible.resolvedModel) + hidden, 0)
        assertNull(announcedRow(menu, visible.resolvedModel))
        assertNull(announcedRow(menu, "claude-opus-older"))
        assertEquals("Opus", inheritedModelLabel(menu))
        assertNull(inheritedModelRow(menu))
        // Exact value takes precedence over an ambiguous lower tier.
        assertEquals(visible, announcedRow(menu, "opus"))
    }

    private fun prefix() =
        (0 until 32).map {
            ModelMenuRow("gpt-$it", "gpt-$it", "Codex $it", emptyList(), false, null, ConversationAgent.Codex)
        }

    private fun row(
        value: String,
        resolved: String,
    ) = ModelMenuRow(resolved, value, "Claude $value", emptyList(), false, null, ConversationAgent.Claude)
}

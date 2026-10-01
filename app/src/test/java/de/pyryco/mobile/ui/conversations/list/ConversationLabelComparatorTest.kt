package de.pyryco.mobile.ui.conversations.list

import de.pyryco.mobile.data.model.Conversation
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationLabelComparatorTest {
    private val placeholder = "Untitled discussion"

    private fun conversation(
        id: String,
        name: String?,
    ) = Conversation(
        id = id,
        name = name,
        cwd = "/w",
        currentSessionId = "$id-s",
        sessionHistory = emptyList(),
        isPromoted = false,
        lastUsedAt = Instant.fromEpochSeconds(0),
    )

    private fun sortedIds(vararg conversations: Conversation): List<String> =
        conversations.sortedWith(conversationLabelComparator(placeholder)).map { it.id }

    @Test
    fun workedExample_foldsCaseAndAccents_andPlacesTheUnnamedChatAtItsPlaceholder() {
        val ids =
            sortedIds(
                conversation("1", "beta"),
                conversation("2", "Alpha"),
                conversation("3", "alpha"),
                conversation("4", "Émile"),
                conversation("5", "zeta"),
                conversation("6", null),
            )

        assertEquals(listOf("2", "3", "1", "4", "6", "5"), ids)
    }

    @Test
    fun numbersCompareByCodeUnit_notNaturalOrder() {
        assertEquals(listOf("ten", "two"), sortedIds(conversation("two", "Chat 2"), conversation("ten", "Chat 10")))
    }

    @Test
    fun blankName_sortsAsThePlaceholder_andWhitespaceIsTrimmed() {
        val ids =
            sortedIds(
                conversation("v", "Vault"),
                conversation("blank", "   "),
                conversation("t", "  Topic  "),
            )

        assertEquals(listOf("t", "blank", "v"), ids)
    }

    @Test
    fun equalKeys_breakTiesOnTheTrimmedLabel_upperCaseFirst() {
        assertEquals(listOf("upper", "lower"), sortedIds(conversation("lower", "alpha"), conversation("upper", " Alpha")))
    }

    @Test
    fun equalLabels_breakTiesOnTheConversationId() {
        assertEquals(listOf("a", "b"), sortedIds(conversation("b", "Same"), conversation("a", "Same")))
    }

    @Test
    fun sortKey_trimsDecomposesStripsMarksAndLowercases() {
        assertEquals("emile", conversationSortKey("  Émile "))
        // NFKD, not NFD: the compatibility ligature decomposes to its letters.
        assertEquals("file", conversationSortKey("ﬁle"))
    }
}

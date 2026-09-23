package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.SlashCommandMenu
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the published slash-command menu (#882) proves a composer action's command absent (#884). The proof
 * needs a menu, no dropped commands, no truncated name or alias, and no row naming the command. Anything
 * short of that leaves every row enabled.
 */
class ComposerActionAvailabilityTest {
    private fun row(
        name: String,
        aliases: List<String> = emptyList(),
        truncatedFields: List<String>? = null,
    ) = SlashCommandMenuRow(
        name = name,
        argumentHint = "",
        description = "",
        aliases = aliases,
        truncatedFields = truncatedFields,
    )

    private fun menu(
        vararg rows: SlashCommandMenuRow,
        dropped: Int = 0,
    ) = SlashCommandMenu(rows = rows.toList(), droppedCommands = dropped)

    private val compact = ComposerAction.CompactSession

    @Test
    fun completeMenuLackingTheCommand_provesItAbsent() {
        assertEquals(
            setOf(ComposerAction.CompactSession, ComposerAction.KnowledgeCapture),
            absentComposerActions(menu(row("clear"), row("model"))),
        )
    }

    @Test
    fun emptyCompleteMenu_provesEveryCommandAbsent_butNeverReset() {
        val absent = absentComposerActions(menu())

        assertTrue(compact in absent)
        assertFalse(ComposerAction.ResetSession in absent)
    }

    @Test
    fun noMenu_provesNothing() {
        assertEquals(emptySet<ComposerAction>(), absentComposerActions(null))
    }

    @Test
    fun droppedCommands_provesNothing() {
        assertEquals(emptySet<ComposerAction>(), absentComposerActions(menu(row("clear"), dropped = 1)))
    }

    @Test
    fun negativeDroppedCount_provesNothing() {
        assertEquals(emptySet<ComposerAction>(), absentComposerActions(menu(row("clear"), dropped = -1)))
    }

    @Test
    fun anyTruncatedName_provesNothing() {
        assertEquals(
            emptySet<ComposerAction>(),
            absentComposerActions(menu(row("clear"), row("comp", truncatedFields = listOf("name")))),
        )
    }

    @Test
    fun anyTruncatedAliases_provesNothing() {
        assertEquals(
            emptySet<ComposerAction>(),
            absentComposerActions(menu(row("clear", truncatedFields = listOf("aliases")))),
        )
    }

    @Test
    fun truncatedDescriptionOrHint_stillProves() {
        val absent =
            absentComposerActions(menu(row("clear", truncatedFields = listOf("description", "argument_hint"))))

        assertTrue(compact in absent)
    }

    @Test
    fun nameMatch_isPresent() {
        assertFalse(compact in absentComposerActions(menu(row("compact"))))
    }

    @Test
    fun aliasMatch_isPresent() {
        val absent = absentComposerActions(menu(row("summarise", aliases = listOf("squash", "compact"))))

        assertFalse(compact in absent)
        assertTrue(ComposerAction.KnowledgeCapture in absent)
    }

    @Test
    fun knowledgeCaptureName_isPresent() {
        assertFalse(ComposerAction.KnowledgeCapture in absentComposerActions(menu(row("knowledge-capture"))))
    }

    @Test
    fun matchIsExact_noSlashNoCaseFoldNoTrim() {
        val absent = absentComposerActions(menu(row("/compact"), row("Compact"), row(" compact"), row("compact\n")))

        assertTrue(compact in absent)
    }
}

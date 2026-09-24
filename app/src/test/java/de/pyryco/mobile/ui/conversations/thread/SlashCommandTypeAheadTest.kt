package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import de.pyryco.mobile.ui.conversations.components.OptionsOverlayOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's slash type-ahead rules (#885), after desktop's `slashCommandTypeAhead.ts`: when the
 * suggestions open, which published rows they show and in what order, what a pick puts in the composer,
 * and how a row's workspace-authored strings are reduced to inert display text.
 */
class SlashCommandTypeAheadTest {
    private fun row(
        name: String,
        aliases: List<String> = emptyList(),
        argumentHint: String = "",
        description: String = "",
        truncatedFields: List<String>? = null,
    ) = SlashCommandMenuRow(name, argumentHint, description, aliases, truncatedFields)

    private val clear = row("clear", aliases = listOf("reset", "new"))
    private val model = row("model", argumentHint = "<model>")
    private val compact = row("compact")
    private val menu = listOf(clear, model, compact)

    private fun names(rows: List<SlashCommandMenuRow>) = rows.map { it.name }

    // ---- opening --------------------------------------------------------------------------------

    @Test
    fun aLoneSlash_showsTheWholeListInPublishedOrder() {
        assertEquals(menu, slashCommandTypeAheadRows("/", menu))
    }

    @Test
    fun onlyABareLeadingSlashFragment_opens() {
        for (text in listOf("", "clear", "a/cl", " /cl", "/cl ear", "/clear ", "/clear\n", "/\t", "/cl ")) {
            assertEquals("'$text' must stay closed", emptyList<SlashCommandMenuRow>(), slashCommandTypeAheadRows(text, menu))
        }
        assertEquals(listOf(clear), slashCommandTypeAheadRows("/cle", menu))
    }

    @Test
    fun anAbsentOrEmptyMenu_orNoMatch_showsNothing() {
        assertEquals(emptyList<SlashCommandMenuRow>(), slashCommandTypeAheadRows("/", null))
        assertEquals(emptyList<SlashCommandMenuRow>(), slashCommandTypeAheadRows("/", emptyList()))
        assertEquals(emptyList<SlashCommandMenuRow>(), slashCommandTypeAheadRows("/zzz", menu))
    }

    // ---- ranking --------------------------------------------------------------------------------

    @Test
    fun prefixMatches_comeBeforeContainedMatches_eachInPublishedOrder() {
        val rows = listOf(row("xmo"), row("model"), row("amo"), row("modes"))

        assertEquals(listOf("model", "modes", "xmo", "amo"), names(slashCommandTypeAheadRows("/mo", rows)))
    }

    @Test
    fun anAliasPrefix_outranksANameThatOnlyContainsTheFragment() {
        val rows = listOf(row("plain-re"), row("summarise", aliases = listOf("xre", "reset")), row("rename"))

        assertEquals(listOf("summarise", "rename", "plain-re"), names(slashCommandTypeAheadRows("/re", rows)))
    }

    @Test
    fun matchingIgnoresCase_inBothDirections() {
        val rows = listOf(row("Clear"), row("model", aliases = listOf("MDL")))

        assertEquals(listOf("Clear"), names(slashCommandTypeAheadRows("/cL", rows)))
        assertEquals(listOf("model"), names(slashCommandTypeAheadRows("/mdl", rows)))
    }

    @Test
    fun theDescription_isNeverSearched() {
        val rows = listOf(row("usage", description = "Show the clear plan"))

        assertEquals(emptyList<SlashCommandMenuRow>(), slashCommandTypeAheadRows("/clear", rows))
    }

    @Test
    fun aRowWithCutAliases_trailsTheMatches_butEmptyAliasesAloneDoNotKeepARow() {
        val cut = row("zeta", truncatedFields = listOf("aliases"))
        val cutDescription = row("omega", truncatedFields = listOf("description"))
        val rows = listOf(cut, row("pxy"), cutDescription, row("xyz"))

        assertEquals(listOf("xyz", "pxy", "zeta"), names(slashCommandTypeAheadRows("/xy", rows)))
        assertEquals(listOf(cut), slashCommandTypeAheadRows("/nothing", rows))
    }

    // ---- completion -----------------------------------------------------------------------------

    @Test
    fun completion_addsOneTrailingSpace_onlyForAHintedCommand() {
        assertEquals("/model ", completeSlashCommand(model))
        assertEquals("/compact", completeSlashCommand(compact))
        assertEquals("/odd ", completeSlashCommand(row("odd", argumentHint = " ")))
    }

    @Test
    fun aRowMatchedByAlias_completesToItsCanonicalName() {
        val matched = slashCommandTypeAheadRows("/rese", menu).single()

        assertEquals("/clear", completeSlashCommand(matched))
    }

    // ---- display ---------------------------------------------------------------------------------

    @Test
    fun options_useTheIndexAsValue_andShowNameHintAndDescription() {
        val options =
            slashCommandOptions(
                listOf(
                    row("model", argumentHint = "<model>", description = "Pick a model"),
                    row("model", description = ""),
                ),
            )

        assertEquals(
            listOf(
                OptionsOverlayOption(value = "0", label = "/model <model>", detail = "Pick a model"),
                OptionsOverlayOption(value = "1", label = "/model", detail = null),
            ),
            options,
        )
    }

    @Test
    fun labels_dropControlCharacters() {
        val option = slashCommandOptions(listOf(row("ev\u001B[31mil\n", argumentHint = "[a\u0007rg]"))).single()

        assertEquals("/ev[31mil [arg]", option.label)
    }

    @Test
    fun theDetail_collapsesLineBreaks_dropsControls_andIsBounded() {
        assertEquals("line one line two tab", slashCommandDetail("line one\nline two\ttab"))
        assertEquals("a  b", slashCommandDetail("a\r\nb"))
        assertEquals("ab", slashCommandDetail("a\u0000b"))
        assertNull(slashCommandDetail(" \n\t\u0000"))

        val long = slashCommandDetail("x".repeat(1_500))
        assertTrue(long != null && long.length <= 240)
    }
}

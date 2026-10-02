package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `knownModelLabel` (#1494): an exact `value` or `resolvedModel` match with one agreed label, else unknown. */
class ThreadRunConfigKnownModelLabelTest {
    private val opus = choice("opus", "claude-opus-5-5", "Opus")
    private val sonnet = choice("sonnet", "claude-sonnet-5", "Sonnet")

    private fun choice(
        value: String,
        resolved: String,
        label: String,
    ) = ThreadModelChoice(value, label, "", emptyList(), resolvedModel = resolved)

    private fun config(
        choices: List<ThreadModelChoice> = listOf(opus, sonnet),
        overflow: List<ThreadModelChoice> = emptyList(),
        inherited: ThreadModelChoice? = null,
    ) = ThreadRunConfig(
        choices = choices,
        overflowChoices = overflow,
        inheritedChoice = inherited,
        menuAvailable = true,
    )

    @Test
    fun matchesARowByItsResolvedModelOrItsValue() {
        assertEquals("Opus", config().knownModelLabel("claude-opus-5-5"))
        assertEquals("Sonnet", config().knownModelLabel("sonnet"))
    }

    @Test
    fun matchesARowTheSheetCannotShow() {
        assertEquals("Haiku", config(overflow = listOf(choice("haiku", "claude-haiku-4-5", "Haiku"))).knownModelLabel("claude-haiku-4-5"))
    }

    @Test
    fun rowsThatAgreeOnTheLabelStayKnown() {
        val wide = choice("opus[1m]", "claude-opus-5-5", "Opus")
        assertEquals("Opus", config(choices = listOf(opus, sonnet, wide)).knownModelLabel("claude-opus-5-5"))
    }

    @Test
    fun rowsThatDisagreeOnTheLabelAreAmbiguous() {
        val other = choice("best", "claude-opus-5-5", "Best")
        assertNull(config(choices = listOf(opus, other)).knownModelLabel("claude-opus-5-5"))
        // A disagreeing row past the rendered cut still makes the match ambiguous.
        assertNull(config(overflow = listOf(other)).knownModelLabel("claude-opus-5-5"))
    }

    @Test
    fun noPrefixFamilyOrCaseGuessing() {
        assertNull(config().knownModelLabel("claude-opus"))
        assertNull(config().knownModelLabel("claude-opus-5-5-preview"))
        assertNull(config().knownModelLabel("claude-opus-4-1"))
        assertNull(config().knownModelLabel("Opus"))
        assertNull(config().knownModelLabel("CLAUDE-OPUS-5-5"))
        assertNull(config().knownModelLabel("claude-opus-5-5\n"))
    }

    @Test
    fun noMenuMeansUnknown() {
        assertNull(ThreadRunConfig().knownModelLabel("claude-opus-5-5"))
        assertNull(config(choices = emptyList()).knownModelLabel("claude-opus-5-5"))
    }

    @Test
    fun anEmptyIdentifierNeverMatchesACutResolvedModel() {
        assertNull(config(choices = listOf(choice("opus", "", "Opus"))).knownModelLabel(""))
    }

    @Test
    fun theHiddenDefaultRowIsNotAPublishedChoice() {
        val inherited = choice("default", "claude-haiku-4-5", "Haiku")
        assertNull(config(inherited = inherited).knownModelLabel("claude-haiku-4-5"))
        assertNull(config(inherited = inherited).knownModelLabel("default"))
    }
}

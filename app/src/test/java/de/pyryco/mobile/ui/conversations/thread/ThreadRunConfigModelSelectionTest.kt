package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.ConversationAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadRunConfigModelSelectionTest {
    private val sonnet = choice("sonnet", "claude-sonnet-5", "Sonnet")
    private val opus = choice("opus[1m]", "claude-opus-5", "Opus")
    private val inherited =
        choice("default", "claude-sonnet-5", "Default").copy(
            effortChoices = listOf(ThreadEffortChoice("high", "high")),
            supportsAutoMode = true,
        )

    private fun choice(
        value: String,
        resolved: String,
        label: String,
    ) = ThreadModelChoice(value, label, "", emptyList(), resolvedModel = resolved)

    private fun config(
        saved: String = "",
        pending: String? = null,
        reading: Boolean = true,
        ordinary: List<ThreadModelChoice> = listOf(sonnet, opus),
        default: ThreadModelChoice? = inherited,
        announced: String = "",
        agent: ConversationAgent = ConversationAgent.Claude,
    ) = ThreadRunConfig(
        choices = ordinary,
        inheritedChoice = default,
        settingsAvailable = reading,
        savedModel = saved,
        pendingModel = pending,
        sessionId = "session-1",
        announcedModel = announced,
        agent = agent,
    )

    private fun ThreadRunConfig.assertNoDefault() {
        assertFalse(modelLabel.contains("Default"))
        assertFalse(selectedChoice?.label.orEmpty().contains("Default"))
    }

    @Test
    fun noReadingMarksNothingEvenWithAnAnnouncement() {
        val waiting = config(reading = false, announced = "claude-opus-5")
        assertNull(waiting.selectedChoice)
        assertEquals(UNKNOWN_RUN_CONFIG_LABEL, waiting.modelLabel)
    }

    @Test
    fun inheritedAnnouncementMarksByValueThenResolvedModelThenFamily() {
        val byValue = config(announced = "opus[1m]")
        assertEquals("opus[1m]", byValue.selectedChoice?.value)
        assertEquals("Opus", byValue.modelLabel)

        val byResolved = config(announced = "claude-opus-5")
        assertEquals("opus[1m]", byResolved.selectedChoice?.value)

        val byFamily = config(announced = "claude-opus-5-5-20260901")
        assertEquals("opus[1m]", byFamily.selectedChoice?.value)
        assertNull(byFamily.modelSelectionNote)
        listOf(byValue, byResolved, byFamily).forEach { it.assertNoDefault() }
    }

    @Test
    fun announcementWinsOverTheDefaultRowsResolution() {
        val saved = listOf("", "default")
        saved.forEach { model ->
            val current = config(saved = model, announced = "claude-opus-5")
            assertEquals("opus[1m]", current.selectedChoice?.value)
            assertEquals("Opus", current.modelLabel)
            // Effort levels and the Auto check still come from the hidden default row.
            assertEquals(listOf("high"), current.effortChoices.map { it.value })
            assertTrue(current.offersPermission(PermissionModeOption.Auto))
        }
    }

    @Test
    fun twoRowsSharingTheAnnouncedFamilyMarkNothingAndLabelTheFamily() {
        val opusPlain = choice("opus", "claude-opus-5-plain", "Opus")
        val current = config(ordinary = listOf(sonnet, opus, opusPlain), announced = "claude-opus-6")
        assertNull(current.selectedChoice)
        assertEquals("Opus", current.modelLabel)
        assertEquals("Opus", current.modelSelectionNote)
    }

    @Test
    fun ambiguousEarlierTierStopsBeforeFamily() {
        // Another family resolving to the announced id: only the family tier would single out opus[1m].
        val twin = choice("sonnet-alt", "claude-opus-5", "Sonnet")
        val current = config(ordinary = listOf(sonnet, opus, twin), announced = "claude-opus-5")
        assertNull(current.selectedChoice)
        assertEquals("Opus", current.modelLabel)
    }

    @Test
    fun aMatchPastTheRenderCapMakesTheRenderedMatchAmbiguous() {
        val shadow = choice("opus-shadow", "claude-opus-5", "Opus")
        val ambiguous = config(announced = "claude-opus-5").copy(overflowChoices = listOf(shadow))
        assertNull(ambiguous.selectedChoice)
        assertEquals("Opus", ambiguous.modelLabel)

        // An exact-value match past the cap decides its tier, so the rendered resolvedModel match is not marked.
        val exactHidden = config(announced = "opus-shadow").copy(overflowChoices = listOf(shadow))
        assertNull(exactHidden.selectedChoice)

        val unrelated = config(announced = "claude-opus-5").copy(overflowChoices = listOf(choice("other", "other-1", "Other")))
        assertEquals("opus[1m]", unrelated.selectedChoice?.value)
    }

    @Test
    fun anAnnouncementNamedLikeTheDefaultNeverLabelsDefault() {
        val current = config(announced = "default-next", default = null)
        assertNull(current.selectedChoice)
        assertEquals(UNAVAILABLE_MODEL_LABEL, current.modelLabel)
        current.assertNoDefault()
    }

    @Test
    fun unmatchedAnnouncementMarksNothingAndLabelsItsFamily() {
        val current = config(announced = "claude-haiku-4-5")
        assertNull(current.selectedChoice)
        assertEquals("Haiku", current.modelLabel)
    }

    @Test
    fun explicitModelIsNeverMovedByTheAnnouncement() {
        val matched = config(saved = "sonnet", announced = "claude-opus-5")
        assertEquals("sonnet", matched.selectedChoice?.value)
        assertEquals("Sonnet", matched.modelLabel)

        val unmatched = config(saved = "opus", announced = "claude-opus-5")
        assertNull(unmatched.selectedChoice)
        assertEquals("opus", unmatched.modelLabel)
    }

    @Test
    fun pendingPickOutranksTheAnnouncementAndARejectionRestoresTheMark() {
        val pending = config(pending = "sonnet", announced = "claude-opus-5")
        assertEquals("sonnet", pending.selectedChoice?.value)
        val rejected = pending.copy(pendingModel = null)
        assertEquals("opus[1m]", rejected.selectedChoice?.value)
    }

    @Test
    fun codexConversationIgnoresTheAnnouncementAndNeverReadsDefault() {
        val gpt = ThreadModelChoice("gpt-6", "GPT-6", "", emptyList(), resolvedModel = "gpt-6")
        val inheritedCodex = config(ordinary = listOf(gpt), default = null, announced = "gpt-6", agent = ConversationAgent.Codex)
        assertNull(inheritedCodex.selectedChoice)
        assertEquals(UNAVAILABLE_MODEL_LABEL, inheritedCodex.modelLabel)
        val explicitCodex = inheritedCodex.copy(savedModel = "gpt-6")
        assertEquals("gpt-6", explicitCodex.selectedChoice?.value)
        assertEquals("GPT-6", explicitCodex.modelLabel)
    }

    @Test
    fun confirmedInheritedEmptyAndDefaultResolveOnlyTheUniqueConcreteRow() {
        for (saved in listOf("", "default")) {
            val current = config(saved = saved)
            assertEquals("sonnet", current.selectedChoice?.value)
            assertEquals("Sonnet", current.modelLabel)
            assertNull(current.modelSelectionNote)
            assertEquals(listOf("high"), current.effortChoices.map { it.value })
            assertTrue(current.offersPermission(PermissionModeOption.Auto))
        }
    }

    @Test
    fun inheritedWithoutUsableUniqueResolutionIsUnavailable() {
        val cases =
            listOf(
                config(default = null),
                config(default = inherited.copy(resolvedModel = "")),
                config(default = inherited.copy(resolvedModel = "<unmeasured>")),
            )
        cases.forEach { current ->
            assertNull(current.selectedChoice)
            assertEquals(UNAVAILABLE_MODEL_LABEL, current.modelLabel)
            assertEquals(UNAVAILABLE_MODEL_LABEL, current.modelSelectionNote)
        }
    }

    @Test
    fun ambiguousDefaultResolutionMarksNothingAndLabelsItsFamily() {
        val current = config(ordinary = listOf(sonnet, sonnet.copy(value = "sonnet[1m]")))
        assertNull(current.selectedChoice)
        assertEquals("Sonnet", current.modelLabel)
        assertEquals("Sonnet", current.modelSelectionNote)
    }

    @Test
    fun explicitAndPendingRequireExactRawValue() {
        assertEquals("opus[1m]", config(saved = "opus[1m]").selectedChoice?.value)
        assertEquals("sonnet", config(saved = "opus[1m]", pending = "sonnet").selectedChoice?.value)
        val unmatched = config(saved = "opus")
        assertNull(unmatched.selectedChoice)
        assertEquals("opus", unmatched.modelLabel)
        assertEquals("opus", unmatched.modelSelectionNote)
        assertFalse(unmatched.offersPermission(PermissionModeOption.Auto))
    }

    @Test
    fun publishedMenuDoesNotConfirmSelectionBeforeSettingsReply() {
        val waiting = config(reading = false)
        assertNull(waiting.selectedChoice)
        assertEquals(UNKNOWN_RUN_CONFIG_LABEL, waiting.modelLabel)
        assertNull(waiting.modelSelectionNote)
        assertTrue(waiting.effortChoices.isEmpty())
        assertFalse(waiting.offersPermission(PermissionModeOption.Auto))
        assertEquals("sonnet", waiting.copy(settingsAvailable = true).selectedChoice?.value)
    }
}

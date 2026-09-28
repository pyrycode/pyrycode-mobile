package de.pyryco.mobile.ui.conversations.thread

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
    ) = ThreadRunConfig(
        choices = ordinary,
        inheritedChoice = default,
        settingsAvailable = reading,
        savedModel = saved,
        pendingModel = pending,
        sessionId = "session-1",
    )

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
                config(ordinary = listOf(sonnet, sonnet.copy(value = "sonnet[1m]"))),
            )
        cases.forEach { current ->
            assertNull(current.selectedChoice)
            assertEquals(UNAVAILABLE_MODEL_LABEL, current.modelLabel)
            assertEquals(UNAVAILABLE_MODEL_LABEL, current.modelSelectionNote)
        }
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

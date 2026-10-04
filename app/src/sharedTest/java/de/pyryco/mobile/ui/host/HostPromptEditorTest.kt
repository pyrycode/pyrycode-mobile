package de.pyryco.mobile.ui.host

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.components.EDIT_HOST_NAME_FIELD_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostPromptEditorTest {
    @get:Rule val rule = createComposeRule()
    private val state = mutableStateOf(HostEditorState("A", "A", "wss://relay", "Host"))
    private val events = mutableListOf<HostPromptEvent>()
    private val names = mutableListOf<String>()

    private fun show(
        prompt: HostPromptState,
        editing: Boolean = false,
    ) {
        state.value = state.value.copy(prompt = prompt, editingPrompt = editing)
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                HostEditorModal(state.value, { names += it }, {}, {}, {}, {}, onPromptEvent = {
                    events += it
                    val loaded = state.value.prompt as? HostPromptState.Loaded
                    state.value =
                        when (it) {
                            HostPromptEvent.Open -> state.value.copy(editingPrompt = true)
                            HostPromptEvent.Discard ->
                                state.value.copy(
                                    editingPrompt = false,
                                    prompt =
                                        loaded?.copy(draft = loaded.confirmed) ?: state.value.prompt,
                                )
                            is HostPromptEvent.Edit -> state.value.copy(prompt = requireNotNull(loaded).copy(draft = it.text))
                            HostPromptEvent.Reset -> state.value.copy(prompt = requireNotNull(loaded).copy(draft = loaded.defaultPrompt))
                            HostPromptEvent.Save ->
                                state.value.copy(
                                    editingPrompt = false,
                                    prompt = requireNotNull(loaded).copy(confirmed = loaded.draft),
                                )
                        }
                })
            }
        }
    }

    private fun loaded(
        current: String = "custom",
        default: String = "default",
    ) = HostPromptState.Loaded(current, default, current)

    @Test fun emptyPreviewAppearsOnlyAfterSuccessfulEmptyRead() {
        show(HostPromptState.Loading)
        rule.onNodeWithText("Loading…").assertIsDisplayed()
        rule.onNodeWithText("Empty").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(prompt = HostPromptState.Unavailable) }
        rule.onNodeWithText("Unavailable").assertIsDisplayed()
        rule.onNodeWithText("Empty").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(prompt = loaded("")) }
        rule.onNodeWithText("Empty").assertIsDisplayed()
        val name = rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).getUnclippedBoundsInRoot()
        val entry = rule.onNodeWithText("Host system prompt").getUnclippedBoundsInRoot()
        val unpair = rule.onNodeWithText("Unpair host").getUnclippedBoundsInRoot()
        assertTrue(entry.top >= name.bottom)
        assertTrue(unpair.top >= entry.bottom)
        rule.onNodeWithText("Host system prompt").performClick()
        assertEquals(HostPromptEvent.Open, events.last())
    }

    @Test fun unreadEditorHasNoEditableFieldResetOrEnabledOk() {
        show(HostPromptState.Loading, true)
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertDoesNotExist()
        rule.onNodeWithText("Reset to default").assertDoesNotExist()
        rule.onNodeWithText("OK").assertIsNotEnabled()
        rule.runOnIdle { state.value = state.value.copy(prompt = HostPromptState.Unavailable) }
        rule.onNodeWithText("Unavailable").assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(HostPromptEvent.Discard, events.last())
    }

    @Test fun multilineFieldHasNoHintExactHelperAndResetOnlyChangesDraft() {
        show(loaded(""), true)
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertTextEquals("")
        rule
            .onNodeWithText(
                "Added to every conversation on this host, before the channel system prompt. " +
                    "A change takes effect from each conversation's next session.",
            ).assertIsDisplayed()
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).performTouchInput { click(Offset(18f, 18f)) }
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertIsFocused().performTextInput("  first\nsecond  ")
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertTextEquals("  first\nsecond  ")
        rule.onNodeWithText("Reset to default").performTouchInput { click(centerRight - Offset(2f, 0f)) }
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertTextEquals("default")
        rule.onNodeWithText("Reset to default").assertDoesNotExist()
        assertEquals("", (state.value.prompt as HostPromptState.Loaded).confirmed)
        assertFalse(events.contains(HostPromptEvent.Save))
    }

    @Test fun defaultHasNoResetAndOverLimitRemainsEditableWithGenericError() {
        show(loaded("default"), true)
        rule.onNodeWithText("Reset to default").assertDoesNotExist()
        rule.onNodeWithText("OK").assertIsEnabled()
        rule.runOnIdle { state.value = state.value.copy(prompt = loaded("x".repeat(8193))) }
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertIsEnabled()
        rule.onNodeWithText("Unable to save this prompt.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsNotEnabled()
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).performTextReplacement("é".repeat(4096))
        rule.onNodeWithText("OK").assertIsEnabled()
    }

    @Test fun failedSaveRetainsTextAndSavingDisablesFieldAndReset() {
        show(loaded().copy(failed = true), true)
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertTextEquals("custom")
        rule.onNodeWithText("Unable to save this prompt.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsEnabled()
        rule.runOnIdle { state.value = state.value.copy(prompt = loaded().copy(saving = true)) }
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertIsNotEnabled()
        rule.onNodeWithText("Reset to default").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test fun promptSaveAndCancelPreserveUnsavedHostName() {
        show(loaded())
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextReplacement("unsaved name")
        rule.onNodeWithText("Host system prompt").performClick()
        rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).performTextReplacement("new prompt")
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertTextEquals("unsaved name")
        rule.onNodeWithText("new prompt").assertIsDisplayed()
        rule.onNodeWithText("Host system prompt").performClick()
        rule.onNodeWithText("Reset to default").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertTextEquals("unsaved name")
        rule.onNodeWithText("OK").performClick()
        assertEquals(listOf("unsaved name"), names)
    }

    @Test fun closeAndBackDiscardWithoutSave() {
        show(loaded(), true)
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(HostPromptEvent.Discard, events.last())
        rule.onNodeWithText("Host system prompt").performClick()
        rule.waitForIdle()
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(2, events.count { it == HostPromptEvent.Discard })
        assertFalse(events.contains(HostPromptEvent.Save))
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertIsDisplayed()
    }

    @Test fun filledPreviewIsOneLineAndEllipsized() {
        show(loaded("Beginning " + "long text ".repeat(100)))
        val preview = rule.onNodeWithTag(HOST_PROMPT_PREVIEW_TAG, useUnmergedTree = true)
        preview.assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        preview.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadScreenModalTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun baseState(): ThreadUiState =
        ThreadUiState(
            conversationId = "c1",
            displayName = "Test channel",
            isPromoted = true,
            hasMessages = false,
        )

    // A `permission`-shaped modal: four options in canonical (wire array) order, fail-safe-deny
    // default = reject_once. Labels are distinct so onNodeWithText resolves a single node each.
    private val permissionOptions =
        listOf(
            ModalOption(id = "allow_once", label = "Allow once"),
            ModalOption(id = "allow_always", label = "Allow always"),
            ModalOption(id = "reject_once", label = "Reject once"),
            ModalOption(id = "reject_always", label = "Reject always"),
        )

    private fun openModal(): ModalUiState.Open =
        ModalUiState.Open(
            modalId = "m1",
            modalClass = "permission",
            title = "Permission required",
            prompt = "claude wants to run rm -rf /tmp/scratch",
            options = permissionOptions,
            defaultOptionId = "reject_once",
        )

    private fun setContent(
        modalState: ModalUiState,
        onModalOption: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    modalState = modalState,
                    onModalOption = onModalOption,
                )
            }
        }
    }

    @Test
    fun open_modal_renders_title_prompt_and_options_in_array_order() {
        val modal = openModal()
        setContent(modal)

        composeTestRule.onNodeWithText(modal.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(modal.prompt).assertIsDisplayed()
        permissionOptions.forEach { option ->
            composeTestRule.onNodeWithText(option.label).assertIsDisplayed()
        }

        // array order (the wire order) == top-to-bottom display order.
        val tops =
            permissionOptions.map {
                composeTestRule
                    .onNodeWithText(it.label)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertTrue("options must render in array order", tops.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun open_modal_highlights_only_the_fail_safe_deny_default() {
        setContent(openModal())
        val defaultDesc = string(R.string.modal_default_option_desc)

        // exactly one option carries the default semantics …
        composeTestRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, defaultDesc))
            .assertCountEquals(1)
        // … and it is the reject_once option (the producer's fail-safe-deny default).
        composeTestRule
            .onNode(
                hasText("Reject once") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, defaultDesc),
            ).assertIsDisplayed()
    }

    @Test
    fun tapping_an_option_invokes_the_inert_callback_with_its_id() {
        val tapped = mutableListOf<String>()
        setContent(openModal(), onModalOption = { tapped += it })

        composeTestRule.onNodeWithText("Allow once").performClick()

        assertEquals(listOf("allow_once"), tapped)
    }

    @Test
    fun dismissed_remote_removes_overlay_and_surfaces_reason() {
        setContent(ModalUiState.Dismissed(modalId = "m1", outcome = "reject_once", source = "remote"))

        composeTestRule.onNodeWithText("Permission required").assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.modal_dismissed_remote)).assertIsDisplayed()
    }

    @Test
    fun dismissed_local_surfaces_reason() {
        setContent(ModalUiState.Dismissed(modalId = "m1", outcome = "allow_once", source = "local"))

        composeTestRule.onNodeWithText(string(R.string.modal_dismissed_local)).assertIsDisplayed()
    }

    @Test
    fun dismissed_timeout_surfaces_reason() {
        setContent(ModalUiState.Dismissed(modalId = "m1", outcome = "", source = "timeout"))

        composeTestRule.onNodeWithText(string(R.string.modal_dismissed_timeout)).assertIsDisplayed()
    }

    @Test
    fun dismissed_forward_compat_source_falls_back_to_generic_reason() {
        setContent(ModalUiState.Dismissed(modalId = "m1", outcome = "", source = "some_future_value"))

        composeTestRule.onNodeWithText(string(R.string.modal_dismissed_resolved)).assertIsDisplayed()
    }

    @Test
    fun hidden_renders_no_overlay() {
        setContent(ModalUiState.Hidden)

        composeTestRule.onNodeWithText("Permission required").assertDoesNotExist()
        permissionOptions.forEach { option ->
            composeTestRule.onNodeWithText(option.label).assertDoesNotExist()
        }
    }
}

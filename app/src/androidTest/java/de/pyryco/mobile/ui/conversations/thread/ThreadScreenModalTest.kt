package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
        armedOptionId: String? = null,
        onModalOption: (String) -> Unit = {},
        onModalCancel: () -> Unit = {},
        modalSendErrors: Flow<Unit> = emptyFlow(),
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
                    armedOptionId = armedOptionId,
                    modalSendErrors = modalSendErrors,
                    onModalOption = onModalOption,
                    onModalCancel = onModalCancel,
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

    @Test
    fun armed_non_default_renders_the_second_confirm_affordance() {
        setContent(openModal(), armedOptionId = "allow_once")
        val armedDesc = string(R.string.modal_armed_option_desc)

        // exactly one option carries the armed marker …
        composeTestRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, armedDesc))
            .assertCountEquals(1)
        // … and it is the armed allow_once option.
        composeTestRule
            .onNode(
                hasText("Allow once") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, armedDesc),
            ).assertIsDisplayed()
    }

    @Test
    fun no_arm_marker_when_nothing_is_armed() {
        setContent(openModal(), armedOptionId = null)
        val armedDesc = string(R.string.modal_armed_option_desc)

        composeTestRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, armedDesc))
            .assertCountEquals(0)
    }

    @Test
    fun default_option_shows_no_arm_step_even_when_a_non_default_is_armed() {
        setContent(openModal(), armedOptionId = "allow_once")
        val armedDesc = string(R.string.modal_armed_option_desc)

        // the fail-safe-deny default never carries the armed marker (it answers on a single tap).
        composeTestRule
            .onNode(
                hasText("Reject once") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, armedDesc),
            ).assertDoesNotExist()
    }

    @Test
    fun tapping_the_default_forwards_its_id() {
        val tapped = mutableListOf<String>()
        setContent(openModal(), onModalOption = { tapped += it })

        composeTestRule.onNodeWithText("Reject once").performClick()

        assertEquals(listOf("reject_once"), tapped)
    }

    @Test
    fun tapping_cancel_invokes_on_modal_cancel() {
        var cancelled = 0
        setContent(openModal(), onModalCancel = { cancelled++ })

        composeTestRule.onNodeWithText(string(R.string.modal_cancel)).performClick()

        assertEquals(1, cancelled)
    }

    // AC#4 "single tap does not confirm, second tap confirms" observed at the screen layer. The VM's
    // authoritative two-tap rule is unit-tested in #451; here a small stateful stand-in faithfully mimics it
    // (default → send; armed-match → send + clear; else → arm) so the rendered affordance drives the flow.
    @Test
    fun non_default_requires_two_taps_to_confirm_via_vm_mimicking_stand_in() {
        val sent = mutableListOf<String>()
        composeTestRule.setContent {
            val modal = remember { openModal() }
            var armed by remember { mutableStateOf<String?>(null) }
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    modalState = modal,
                    armedOptionId = armed,
                    onModalOption = { id ->
                        when {
                            id == modal.defaultOptionId -> sent += id
                            armed == id -> {
                                sent += id
                                armed = null
                            }
                            else -> armed = id
                        }
                    },
                )
            }
        }
        val armedDesc = string(R.string.modal_armed_option_desc)

        // first tap of a non-default arms it — it does NOT confirm.
        composeTestRule.onNodeWithText("Allow once").performClick()
        assertTrue("first tap must not send", sent.isEmpty())
        composeTestRule
            .onNode(
                hasText("Allow once") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, armedDesc),
            ).assertIsDisplayed()

        // second tap of the same option confirms.
        composeTestRule.onNodeWithText("Allow once").performClick()
        assertEquals(listOf("allow_once"), sent)
    }

    @Test
    fun modal_send_error_surfaces_local_string_without_payload() {
        val errors = Channel<Unit>(Channel.BUFFERED)
        // Hidden: with no modal drawn, the snackbar is the only thing that could carry a payload substring.
        setContent(modalState = ModalUiState.Hidden, modalSendErrors = errors.receiveAsFlow())

        errors.trySend(Unit)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.modal_send_failed)).assertIsDisplayed()
        // confidentiality: a sensitive command / path must never reach the un-secured snackbar window.
        composeTestRule.onNodeWithText("rm -rf", substring = true).assertDoesNotExist()
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

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
        alwaysAllowAccepted: Boolean = false,
        onAlwaysAllowChanged: (String, Boolean) -> Unit = { _, _ -> },
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
                    alwaysAllowAccepted = alwaysAllowAccepted,
                    onAlwaysAllowChanged = onAlwaysAllowChanged,
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

    // ---- #817: the permission ask's decision context ---------------------------------------------

    private fun top(text: String): Float =
        composeTestRule
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .boundsInRoot.top

    @Test
    fun context_rows_render_between_the_prompt_and_the_options_in_desktop_order() {
        val modal =
            openModal().copy(
                context =
                    ModalContext(
                        reason = "Bash(rm:*) is on the ask list",
                        reasonType = "rule",
                        blockedPath = "/tmp/scratch",
                        description = "Remove the scratch directory",
                    ),
            )
        setContent(modal)

        val ruleLabel = string(R.string.modal_context_reason_rule)
        val descriptionLabel = string(R.string.modal_context_description)
        val blockedPathLabel = string(R.string.modal_context_blocked_path)
        listOf(ruleLabel, "Bash(rm:*) is on the ask list", descriptionLabel, "Remove the scratch directory")
            .plus(listOf(blockedPathLabel, "/tmp/scratch"))
            .forEach { composeTestRule.onNodeWithText(it).assertIsDisplayed() }

        // prompt → reason → description → blocked path → options, top to bottom.
        val tops = listOf(modal.prompt, ruleLabel, descriptionLabel, blockedPathLabel, "Allow once").map(::top)
        assertTrue("context rows must sit in desktop order under the prompt", tops.zipWithNext().all { (a, b) -> a < b })
    }

    // An unknown category renders as its raw value, and a non-string reason's JSON text stays visible.
    @Test
    fun unknown_reason_type_renders_its_raw_category_beside_a_non_string_reason() {
        setContent(openModal().copy(context = ModalContext(reason = "false", reasonType = "futureCategory_v9")))

        val label =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.modal_context_reason_type,
                "futureCategory_v9",
            )
        composeTestRule.onNodeWithText(label).assertIsDisplayed()
        composeTestRule.onNodeWithText("false").assertIsDisplayed()
    }

    @Test
    fun classifier_type_without_a_reason_renders_its_sentence_alone() {
        setContent(openModal().copy(context = ModalContext(reasonType = "classifier")))

        composeTestRule.onNodeWithText(string(R.string.modal_context_reason_classifier)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.modal_context_reason)).assertDoesNotExist()
    }

    @Test
    fun reason_without_a_type_uses_the_generic_label() {
        setContent(openModal().copy(context = ModalContext(reason = "Needs approval")))

        composeTestRule.onNodeWithText(string(R.string.modal_context_reason)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Needs approval").assertIsDisplayed()
    }

    @Test
    fun modal_without_context_renders_no_context_rows() {
        setContent(openModal())

        composeTestRule.onNodeWithText(openModal().prompt).assertIsDisplayed()
        listOf(
            R.string.modal_context_reason,
            R.string.modal_context_reason_rule,
            R.string.modal_context_reason_classifier,
            R.string.modal_context_description,
            R.string.modal_context_blocked_path,
        ).forEach { composeTestRule.onNodeWithText(string(it)).assertDoesNotExist() }
    }

    // ---- #818: the don't-ask-again offer ------------------------------------------------------------

    private val offeredRules = listOf("Bash(npm test)", "Read")

    @Test
    fun offered_permission_prompt_shows_the_offer_and_its_rules_between_context_and_options() {
        val modal = openModal().copy(context = ModalContext(reason = "Needs approval"), alwaysAllowRules = offeredRules)
        setContent(modal)

        val label = string(R.string.modal_always_allow_label)
        composeTestRule.onNodeWithText(label).assertIsDisplayed()
        offeredRules.forEach { composeTestRule.onNodeWithText(it).assertIsDisplayed() }

        // prompt → context → offer label → rules in wire order → options, top to bottom.
        val tops = listOf(modal.prompt, "Needs approval", label, "Bash(npm test)", "Read", "Allow once").map(::top)
        assertTrue("the offer must sit between the context and the options", tops.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun permission_prompt_without_an_offer_shows_no_offer() {
        setContent(openModal())

        composeTestRule.onNodeWithText(openModal().prompt).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.modal_always_allow_label)).assertDoesNotExist()
    }

    @Test
    fun trust_prompt_carrying_rules_shows_no_offer() {
        setContent(openModal().copy(modalClass = "trust", alwaysAllowRules = offeredRules))

        composeTestRule.onNodeWithText(string(R.string.modal_always_allow_label)).assertDoesNotExist()
        offeredRules.forEach { composeTestRule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun tapping_the_offer_reports_its_prompt_and_never_answers() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        val tapped = mutableListOf<String>()
        setContent(
            openModal().copy(alwaysAllowRules = offeredRules),
            onModalOption = { tapped += it },
            onAlwaysAllowChanged = { modalId, accepted -> changes += modalId to accepted },
        )

        composeTestRule
            .onNode(
                hasText(string(R.string.modal_always_allow_label)).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)),
            ).assertIsOff()
            .performClick()

        assertEquals(listOf("m1" to true), changes)
        assertTrue("accepting the offer must not answer the prompt", tapped.isEmpty())
    }

    @Test
    fun accepted_offer_renders_checked() {
        setContent(openModal().copy(alwaysAllowRules = offeredRules), alwaysAllowAccepted = true)

        composeTestRule
            .onNode(
                hasText(string(R.string.modal_always_allow_label)).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)),
            ).assertIsOn()
    }

    @Test
    fun offered_checkbox_uses_figma_box_size_and_label_gap() {
        setContent(openModal().copy(alwaysAllowRules = offeredRules))

        val box = composeTestRule.onNodeWithTag("always_allow_box", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label =
            composeTestRule
                .onNodeWithText(string(R.string.modal_always_allow_label), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val density = composeTestRule.density.density
        assertEquals(20f, box.width / density, 0.5f)
        assertEquals(20f, box.height / density, 0.5f)
        assertEquals(12f, (label.left - box.right) / density, 0.5f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun enlarged_text_keeps_all_permission_decisions_reachable_in_compact_dialog() {
        val longOptions =
            permissionOptions.map { option -> option.copy(label = "${option.label} for the current session with these rules") }
        val modal =
            openModal().copy(
                prompt = "A long permission request describing a command and its arguments without making the text interactive.",
                context = ModalContext(reason = "Review the requested action before choosing an option."),
                alwaysAllowRules = listOf("Applies to this session only; review each matching rule before allowing."),
                options = longOptions,
            )
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                PyrycodeMobileTheme(darkTheme = true) {
                    PermissionModalOverlay(modal, armedOptionId = null, onOption = {}, onCancel = {})
                }
            }
        }

        composeTestRule.onNodeWithText(modal.prompt).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.modal_always_allow_label)).performScrollTo().assertIsDisplayed()
        longOptions.forEach { option ->
            composeTestRule.onNodeWithText(option.label).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            composeTestRule
                .onNodeWithText(option.label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("${option.id} wraps at enlarged text", layout.lineCount > 1)
            assertFalse("${option.id} clips horizontally", layout.didOverflowWidth)
            assertFalse("${option.id} clips vertically", layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse("${option.id} ellipsizes", layout.isLineEllipsized(line))
            }
        }
        composeTestRule.onNodeWithText(string(R.string.modal_cancel)).assertIsDisplayed()
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

    @Test
    fun back_press_neither_answers_nor_cancels_and_no_close_glyph_is_offered() {
        val tapped = mutableListOf<String>()
        var cancelled = 0
        setContent(openModal(), onModalOption = { tapped += it }, onModalCancel = { cancelled++ })

        composeTestRule.onNodeWithContentDescription("Close").assertDoesNotExist()
        Espresso.pressBack()

        composeTestRule.runOnIdle {
            assertTrue("back must not answer the prompt", tapped.isEmpty())
            assertEquals("back must not cancel the prompt", 0, cancelled)
        }
        composeTestRule.onNodeWithText(openModal().prompt).assertIsDisplayed()
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

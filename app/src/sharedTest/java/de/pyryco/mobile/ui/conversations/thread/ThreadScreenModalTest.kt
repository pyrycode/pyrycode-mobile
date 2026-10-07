package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.assertDpEquals
import de.pyryco.mobile.ui.assertRectEqualsWithinPixel
import de.pyryco.mobile.ui.conversations.components.STATUS_GLYPH_TEST_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
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
        alwaysAllowAccepted: Boolean = false,
        answerRejected: Boolean = false,
        onDismissAnswerRejection: () -> Unit = {},
        onAlwaysAllowChanged: (String, Boolean) -> Unit = { _, _ -> },
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = onBack,
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    modalState = modalState,
                    armedOptionId = armedOptionId,
                    onModalOption = { modalId, optionId -> onModalOption("$modalId/$optionId") },
                    onModalCancel = { modalId -> if (modalId == "m1") onModalCancel() },
                    alwaysAllowAccepted = alwaysAllowAccepted,
                    onAlwaysAllowChanged = onAlwaysAllowChanged,
                    answerRejected = answerRejected,
                    onDismissAnswerRejection = onDismissAnswerRejection,
                )
            }
        }
    }

    // ---- #1306: the request lives in its conversation's stream ---------------------------------------

    @Test
    fun open_request_renders_inline_in_an_empty_thread_with_no_dialog_and_live_navigation() {
        var backs = 0
        val tapped = mutableListOf<String>()
        var cancelled = 0
        setContent(openModal(), onModalOption = { tapped += it }, onModalCancel = { cancelled++ }, onBack = { backs++ })

        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        composeTestRule.onNodeWithTag("permission-request-card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("permission-request-title").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeTestRule.onNodeWithContentDescription("Send message").assertExists()
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, backs)
            assertTrue("Back must not answer the request", tapped.isEmpty())
            assertEquals("Back must not cancel the request", 0, cancelled)
        }
    }

    @Test
    fun arrival_and_grant_toggles_keep_a_history_reader_anchored_without_history_demand() {
        var modal by mutableStateOf<ModalUiState>(ModalUiState.Hidden)
        var accepted by mutableStateOf(false)
        var demands = 0
        val items =
            (1..30).map { i ->
                ThreadItem.MessageItem(
                    Message("m$i", "session", Role.Assistant, "History $i", Instant.parse("2026-10-01T00:00:00Z"), isStreaming = false),
                )
            }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state = baseState().copy(hasMessages = true, items = items),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        isThinking = true,
                        modalState = modal,
                        alwaysAllowAccepted = accepted,
                        onDemandOlderHistory = { demands++ },
                    )
                }
            }
        }
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        val anchor = composeTestRule.onNodeWithText("History 15").fetchSemanticsNode().boundsInRoot
        composeTestRule.runOnIdle { modal = openModal().copy(alwaysAllowRules = offeredRules) }
        assertRectEqualsWithinPixel(anchor, composeTestRule.onNodeWithText("History 15").fetchSemanticsNode().boundsInRoot)
        composeTestRule.runOnIdle { accepted = true }
        assertRectEqualsWithinPixel(anchor, composeTestRule.onNodeWithText("History 15").fetchSemanticsNode().boundsInRoot)
        composeTestRule.onNodeWithTag("permission-request-card").assertDoesNotExist()
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(31)
        // #1352: reaching the oldest row is not a pull, so it asks nothing.
        composeTestRule.runOnIdle { assertEquals(0, demands) }
        composeTestRule.runOnIdle { modal = openModal().copy(modalId = "m2") }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { assertEquals("a replaced request cannot manufacture a history demand", 0, demands) }
    }

    @Test
    fun arrival_reveals_the_request_to_a_reader_at_the_newest_end() {
        var modal by mutableStateOf<ModalUiState>(ModalUiState.Hidden)
        val items =
            (1..30).map { i ->
                ThreadItem.MessageItem(
                    Message("m$i", "session", Role.Assistant, "History $i", Instant.parse("2026-10-01T00:00:00Z"), isStreaming = false),
                )
            }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state = baseState().copy(hasMessages = true, items = items),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        modalState = modal,
                    )
                }
            }
        }
        composeTestRule.onNodeWithText("History 30").assertIsDisplayed()
        composeTestRule.runOnIdle { modal = openModal() }
        composeTestRule.onNodeWithTag("permission-request-cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reject once").assertIsDisplayed()
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

        assertEquals(listOf("m1/allow_once"), tapped)
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
            // Unclipped, so a row scrolled above the message area still orders (#1483 made the card taller).
            .positionInRoot.y

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
            // #1312: the always-present status band shortens the message area, so the lower rows scroll into view.
            .forEach { composeTestRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }

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

    // ---- #1501: Figma 639:2242 measured layout ---------------------------------------------------------

    private val frameRequest =
        ModalUiState.Open(
            modalId = "m1",
            modalClass = "permission",
            title = "Permission required",
            prompt = "Allow Claude to read this project?",
            options = listOf(ModalOption("allow_once", "Allow once"), ModalOption("reject_once", "Reject once")),
            defaultOptionId = "reject_once",
            context = ModalContext(reason = "This folder is outside the allowed paths.", blockedPath = "/projects/client"),
        )

    private fun setFrameContent(
        armedOptionId: String? = null,
        onModalOption: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    modalState = frameRequest,
                    armedOptionId = armedOptionId,
                    onModalOption = { modalId, optionId -> onModalOption("$modalId/$optionId") },
                )
            }
        }
    }

    private fun textBounds(text: String): DpRect = composeTestRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w412dp-h892dp")
    fun permission_context_spacing_matches_the_frame() {
        setFrameContent()
        val reasonLabel = textBounds(string(R.string.modal_context_reason))
        val reason = textBounds("This folder is outside the allowed paths.")
        val folderLabel = textBounds(string(R.string.modal_context_blocked_path))
        val folder = textBounds("/projects/client")
        val allow = composeTestRule.onNodeWithText("Allow once").getUnclippedBoundsInRoot()

        assertEquals("Reason label to value, top to top", 28f, (reason.top - reasonLabel.top).value, 0.5f)
        assertEquals("Folder label to value, top to top", 28f, (folder.top - folderLabel.top).value, 0.5f)
        assertEquals("Reason group to Folder group", 16f, (folderLabel.top - reason.bottom).value, 0.5f)
        assertEquals("last context value to the first choice", 16f, (allow.top - folder.bottom).value, 0.5f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w412dp-h892dp")
    fun permission_choices_are_40dp_surfaces_8dp_apart_with_48dp_touch_targets() {
        val tapped = mutableListOf<String>()
        setFrameContent(onModalOption = { tapped += it })
        val allow = composeTestRule.onNodeWithText("Allow once")
        val reject = composeTestRule.onNodeWithText("Reject once")
        val allowBounds = allow.getUnclippedBoundsInRoot()
        val rejectBounds = reject.getUnclippedBoundsInRoot()

        assertEquals(40f, allowBounds.height.value, 0.5f)
        assertEquals(40f, rejectBounds.height.value, 0.5f)
        assertEquals("visible gap between the choices", 8f, (rejectBounds.top - allowBounds.bottom).value, 0.5f)
        with(composeTestRule.density) {
            assertTrue(allow.fetchSemanticsNode().touchBoundsInRoot.height >= 48.dp.toPx() - 0.5f)
            assertTrue(reject.fetchSemanticsNode().touchBoundsInRoot.height >= 48.dp.toPx() - 0.5f)
        }

        // Real pointer taps in the gap, 3 dp outside each visible surface, reach the nearer choice.
        allow.performTouchInput { click(Offset(centerX, height + 3.dp.toPx())) }
        reject.performTouchInput { click(Offset(centerX, -3.dp.toPx())) }
        composeTestRule.runOnIdle { assertEquals(listOf("m1/allow_once", "m1/reject_once"), tapped) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w412dp-h892dp")
    fun armed_hint_sits_in_the_8dp_choice_gaps() {
        setFrameContent(armedOptionId = "allow_once")
        val allow = composeTestRule.onNodeWithText("Allow once").getUnclippedBoundsInRoot()
        val hint =
            textBounds(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.modal_armed_option_hint, "Allow once"))
        val reject = composeTestRule.onNodeWithText("Reject once").getUnclippedBoundsInRoot()

        assertEquals(8f, (hint.top - allow.bottom).value, 0.5f)
        assertEquals(8f, (reject.top - hint.bottom).value, 0.5f)
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

    // #1321: every control that would send waits for the host; the session grant is a local choice.
    @Test
    fun sending_controls_are_disabled_offline_and_re_enable_on_reconnect_while_the_grant_stays_usable() {
        var connection by mutableStateOf<ConnectionState>(ConnectionState.Offline)
        val tapped = mutableListOf<String>()
        var cancelled = 0
        val changes = mutableListOf<Pair<String, Boolean>>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = connection,
                    onRetry = {},
                    modalState = openModal().copy(alwaysAllowRules = offeredRules),
                    armedOptionId = "allow_once",
                    onModalOption = { modalId, optionId -> tapped += "$modalId/$optionId" },
                    onModalCancel = { cancelled++ },
                    onAlwaysAllowChanged = { modalId, accepted -> changes += modalId to accepted },
                )
            }
        }
        val options = permissionOptions.map { it.label }

        options.forEach { composeTestRule.onNodeWithText(it).performScrollTo().assertIsNotEnabled() }
        composeTestRule.onNodeWithText("Allow once").performClick() // the armed second tap
        composeTestRule.onNodeWithText("Allow always").performClick() // a first tap that would arm
        composeTestRule
            .onNodeWithTag("permission-request-cancel")
            .onChild()
            .assertIsNotEnabled()
            .performClick()
        composeTestRule
            .onNode(
                hasText(string(R.string.modal_always_allow_label)).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)),
            ).performScrollTo()
            .assertIsEnabled()
            // #1312: the always-present status band leaves the scroll stopping with this row at the message
            // area's top, under the Offline retry pill at its end; tap its start, which the pill does not cover.
            .performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        composeTestRule.runOnIdle {
            assertTrue("no option may be forwarded while offline", tapped.isEmpty())
            assertEquals(0, cancelled)
            assertEquals(listOf("m1" to true), changes)
        }

        composeTestRule.runOnIdle { connection = ConnectionState.Connected }
        options.forEach { composeTestRule.onNodeWithText(it).performScrollTo().assertIsEnabled() }
        composeTestRule.onNodeWithText("Allow once").performClick()
        composeTestRule
            .onNodeWithTag("permission-request-cancel")
            .onChild()
            .assertIsEnabled()
            .performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf("m1/allow_once"), tapped)
            assertEquals(1, cancelled)
        }
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
    fun enlarged_text_keeps_all_permission_decisions_reachable_in_the_compact_stream() {
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
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 700.dp)) then DeviceConfigurationOverride.FontScale(1.5f),
            ) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state = baseState(),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        modalState = modal,
                    )
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
        // Scrolling up to the prompt disposed the lazy Cancel item, so reach it through the list.
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.modal_cancel)))
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

        assertEquals(listOf("m1/reject_once"), tapped)
    }

    @Test
    fun tapping_cancel_invokes_on_modal_cancel() {
        var cancelled = 0
        setContent(openModal(), onModalCancel = { cancelled++ })

        composeTestRule.onNodeWithText(string(R.string.modal_cancel)).performClick()

        assertEquals(1, cancelled)
    }

    @Test
    fun no_close_glyph_is_offered_and_cancel_follows_the_card() {
        setContent(openModal())

        composeTestRule.onNodeWithContentDescription("Close").assertDoesNotExist()
        val card = composeTestRule.onNodeWithTag("permission-request-card").fetchSemanticsNode().boundsInRoot
        val cancel = composeTestRule.onNodeWithText(string(R.string.modal_cancel)).fetchSemanticsNode().boundsInRoot
        assertTrue("Cancel sits below the card", cancel.top >= card.bottom)
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
                    onModalOption = { _, id ->
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

    // ---- #1483: the Questions and permissions frames (`639:2242`, `639:2882`) ------------------------

    @Test
    fun title_is_the_cards_first_line_and_keeps_heading_semantics() {
        setContent(openModal())

        composeTestRule
            .onNode(hasTestTag("permission-request-title") and hasAnyAncestor(hasTestTag("permission-request-card")))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertTextEquals("Permission required")
        assertTrue("the title sits above the prompt", top("Permission required") < top(openModal().prompt))
    }

    // #1601: Figma 668:3186 draws the card flush on the stream's own top inset, the same y 97 the pill
    // (#1599) and ThreadMessageAreaTopTest's offline pill keep, with no extra top gutter of its own.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w412dp-h892dp")
    fun open_request_card_sits_flush_on_the_stream_top() {
        setContent(openModal())

        val card = composeTestRule.onNodeWithTag("permission-request-card").getUnclippedBoundsInRoot()

        assertStreamTop(card, "card")
    }

    private fun assertStreamTop(
        bounds: DpRect,
        item: String,
    ) {
        val header = composeTestRule.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot()
        assertDpEquals(69.dp, header.bottom, "header retains the frame's 69dp height")
        assertDpEquals(28.dp, bounds.top - header.bottom, "$item keeps the 28dp clearance below the header")
        // At density 2.625 the header's rounded 14/48/6/1dp segments total 182px, plus 74px
        // for the 28dp clearance: 256px = 97.52381dp, 1.375px above the 97dp target.
        assertDpEquals(97.dp, bounds.top, "$item top matches the frame's y 97", pixels = 2)
    }

    @Test
    fun cancel_is_start_aligned_with_the_card() {
        setContent(openModal())

        val card = composeTestRule.onNodeWithTag("permission-request-card").fetchSemanticsNode().boundsInRoot
        val cancel = composeTestRule.onNodeWithText(string(R.string.modal_cancel)).fetchSemanticsNode().boundsInRoot
        assertEquals(card.left, cancel.left)
    }

    @Test
    fun blocked_path_row_is_labelled_folder() {
        setContent(openModal().copy(context = ModalContext(blockedPath = "/projects/client")))

        composeTestRule.onNodeWithText("Folder").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("/projects/client").assertIsDisplayed()
    }

    @Test
    fun armed_choice_shows_its_confirm_hint_directly_under_it_until_the_arm_clears() {
        var armed by mutableStateOf<String?>("allow_once")
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    modalState = openModal(),
                    armedOptionId = armed,
                )
            }
        }
        val hint = "Tap Allow once again to confirm."

        composeTestRule.onNodeWithText(hint).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("again to confirm.", substring = true)).assertCountEquals(1)
        val button = composeTestRule.onNodeWithText("Allow once").fetchSemanticsNode().boundsInRoot
        val next = composeTestRule.onNodeWithText("Allow always").fetchSemanticsNode().boundsInRoot
        val line = composeTestRule.onNodeWithText(hint).fetchSemanticsNode().boundsInRoot
        assertTrue("the hint sits between the armed choice and the next", button.bottom < line.top && line.bottom < next.top)

        composeTestRule.runOnIdle { armed = null }
        composeTestRule.onAllNodes(hasText("again to confirm.", substring = true)).assertCountEquals(0)
    }

    @Test
    fun status_band_reads_waiting_for_permission_while_a_request_is_open_and_connected() {
        var stage by mutableStateOf(LocalSendStage.Sending)
        var connection by mutableStateOf<ConnectionState>(ConnectionState.Connected)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = connection,
                    onRetry = {},
                    modalState = openModal(),
                    localSendStage = stage,
                )
            }
        }
        val waiting = string(R.string.thread_status_waiting_for_permission)

        composeTestRule.onNodeWithText(waiting).assertIsDisplayed()
        composeTestRule.onNodeWithTag(STATUS_GLYPH_TEST_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText(string(R.string.question_waiting_for_answers)).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.thread_sending_label)).assertDoesNotExist()
        composeTestRule.runOnIdle { stage = LocalSendStage.Waiting }
        composeTestRule.onNodeWithText(waiting).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_waiting_label)).assertDoesNotExist()

        composeTestRule.runOnIdle { connection = ConnectionState.Connecting }
        composeTestRule.onNodeWithText(waiting).assertDoesNotExist()
    }

    @Test
    fun status_band_has_no_permission_reading_without_a_request() {
        setContent(ModalUiState.Hidden)

        composeTestRule.onNodeWithText(string(R.string.thread_status_waiting_for_permission)).assertDoesNotExist()
    }

    // ---- #1340: a refused answer stays in its chat as a Default notice with an X ----------------------

    @Test
    fun answer_rejected_shows_the_client_notice_in_the_cards_slot_and_its_x_dismisses_it() {
        var dismissed = 0
        setContent(modalState = ModalUiState.Hidden, answerRejected = true, onDismissAnswerRejection = { dismissed++ })

        composeTestRule.onNodeWithTag(PERMISSION_REJECTION_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.permission_answer_rejected)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.thread_notice_dismiss)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun no_rejection_shows_no_notice() {
        setContent(modalState = openModal())

        composeTestRule.onNodeWithTag(PERMISSION_REJECTION_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.permission_answer_rejected)).assertDoesNotExist()
    }

    // #1599: Figma 668:3054 draws the pill flush on the stream's own top inset (y 97 in the 412x892
    // reference frame, as ThreadMessageAreaTopTest's offline pill also pins), 24dp tall — the item's own
    // gutter must add no further top gap, and the pill's line-height-centred text must not get trimmed
    // short of its 4+4dp padding plus bodySmall's 16dp line.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w412dp-h892dp")
    fun answer_rejected_pill_sits_flush_on_the_stream_top_at_its_frame_height() {
        setContent(modalState = ModalUiState.Hidden, answerRejected = true)

        val pill = composeTestRule.onNodeWithTag(PERMISSION_REJECTION_TEST_TAG).getUnclippedBoundsInRoot()

        assertStreamTop(pill, "pill")
        assertDpEquals(24.dp, pill.height, "pill height matches the frame's 24dp")
    }
}

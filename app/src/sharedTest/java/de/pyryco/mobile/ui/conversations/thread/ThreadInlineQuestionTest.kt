package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.e2e.questionAnswerTarget
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class ThreadInlineQuestionTest {
    @get:Rule val rule = createComposeRule()
    private val question =
        QuestionModalState(
            QuestionBatch(
                "chat",
                "request",
                listOf(Question("Choose a language", "Language", listOf(QuestionOption("Kotlin", "JVM")), false)),
            ),
            generation = 1,
        )
    private val twoQuestions =
        QuestionModalState(
            QuestionBatch(
                "chat",
                "request",
                listOf("Language", "Targets").map { header ->
                    Question("Which $header?", header, listOf(QuestionOption("Kotlin", "JVM"), QuestionOption("Rust", "Systems")), false)
                },
            ),
            generation = 1,
        )

    @Test
    fun empty_thread_has_inline_questions_active_back_and_composer_without_history_demand() {
        var backs = 0
        var demands = 0
        rule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    ThreadUiState("chat", "Client planning", isPromoted = false),
                    { backs++ },
                    {},
                    ConnectionState.Connected,
                    {},
                    questionState = question,
                    onDemandOlderHistory = { demands++ },
                )
            }
        }
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNodeWithText("Choose a language").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsDisplayed()
        rule.onNodeWithText("Waiting for answers").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle {
            assertEquals(1, backs)
            assertEquals(0, demands)
        }
    }

    @Test
    fun arrival_reveals_the_batch_to_a_reader_at_the_newest_end() {
        var pending by mutableStateOf<QuestionModalState?>(null)
        rule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        ThreadUiState("chat", "Client planning", isPromoted = false, hasMessages = true, items = historyItems()),
                        {},
                        {},
                        ConnectionState.Connected,
                        {},
                        questionState = pending,
                    )
                }
            }
        }
        rule.onNodeWithText("History 30").assertIsDisplayed()
        rule.runOnIdle { pending = question }
        rule.onNodeWithTag("question-batch-actions").assertIsDisplayed()
        rule.onNodeWithText("Choose a language").assertIsDisplayed()
    }

    @Test
    fun failure_feedback_is_announced_and_the_title_is_a_heading() {
        rule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    ThreadUiState("chat", "Client planning", isPromoted = false),
                    {},
                    {},
                    ConnectionState.Connected,
                    {},
                    questionState = question.copy(phase = QuestionSendPhase.Failed),
                )
            }
        }
        rule.onNodeWithTag("question-batch-title").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule
            .onNodeWithTag("question-send-failed")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun an_open_permission_prompt_withholds_the_question_until_it_resolves_with_picks_intact() {
        val picked =
            QuestionModalState(
                QuestionBatch(
                    "chat",
                    "request",
                    listOf(Question("Choose a language", "Language", listOf(QuestionOption("Kotlin", "JVM")), true)),
                ),
                selections = listOf(QuestionSelection(optionIndices = setOf(0), otherTicked = true, otherText = "Rust too")),
                generation = 1,
            )
        val prompt =
            ModalUiState.Open(
                modalId = "m1",
                modalClass = "permission",
                title = "Permission required",
                prompt = "claude wants to run ls",
                options = listOf(ModalOption(id = "allow_once", label = "Allow once")),
                defaultOptionId = "allow_once",
            )
        var modal by mutableStateOf<ModalUiState>(prompt)
        rule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    ThreadUiState("chat", "Client planning", isPromoted = false),
                    {},
                    {},
                    ConnectionState.Connected,
                    {},
                    questionState = picked,
                    modalState = modal,
                )
            }
        }
        rule.onNodeWithText("Allow once").assertIsDisplayed()
        rule.onNodeWithTag("question-batch-title").assertDoesNotExist()
        rule.onNodeWithText("Choose a language").assertDoesNotExist()
        rule.onNodeWithText("Waiting for answers").assertDoesNotExist()

        rule.runOnIdle { modal = ModalUiState.Hidden }
        rule.onNodeWithText("Allow once").assertDoesNotExist()
        rule.onNodeWithText("Choose a language").assertIsDisplayed()
        rule.onNodeWithTag("question_control_0_0_row").assertIsOn()
        rule.onNodeWithTag("question_control_0_other_row").assertIsOn()
        rule.onNodeWithTag("question_other_0").assertTextEquals("Rust too")
    }

    // #1321: answering and refusing wait for the host; picks and other text stay editable and are kept.
    @Test
    fun continue_and_refuse_are_disabled_offline_while_picks_stay_editable_and_re_enable_on_reconnect() {
        var connection by mutableStateOf<ConnectionState>(ConnectionState.Offline)
        val events = mutableListOf<QuestionModalEvent>()
        val picked = question.copy(selections = listOf(QuestionSelection(optionIndices = setOf(0))))
        rule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    ThreadUiState("chat", "Client planning", isPromoted = false),
                    {},
                    {},
                    connection,
                    {},
                    questionState = picked,
                    onQuestionEvent = { event, _ -> events += event },
                )
            }
        }

        rule.onNodeWithText("Continue").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Cancel").assertIsNotEnabled().performClick()
        rule
            .onNodeWithText("Kotlin")
            .assertIsSelected()
            .assertIsEnabled()
            .performClick()
        rule.onNodeWithTag("question_other_0").performTextInput("Go")
        rule.runOnIdle {
            assertEquals(listOf(QuestionModalEvent.OptionToggled(0, 0), QuestionModalEvent.OtherTextChanged(0, "Go")), events)
        }

        rule.runOnIdle {
            events.clear()
            connection = ConnectionState.Connected
        }
        rule.onNodeWithText("Kotlin").assertIsSelected()
        rule.onNodeWithText("Cancel").assertIsEnabled()
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf<QuestionModalEvent>(QuestionModalEvent.Continue), events) }
    }

    private fun historyItems() =
        (1..30).map { i ->
            ThreadItem.MessageItem(
                Message("m$i", "session", Role.Assistant, "History $i", Instant.parse("2026-09-30T00:00:00Z"), isStreaming = false),
            )
        }

    @Test
    fun arrival_and_edits_preserve_a_history_reader_and_prompt_rows_do_not_advance_history_demand() {
        var pending by mutableStateOf<QuestionModalState?>(null)
        var demands = 0
        val items =
            (1..30).map { i ->
                ThreadItem.MessageItem(
                    Message("m$i", "session", Role.Assistant, "History $i", Instant.parse("2026-09-30T00:00:00Z"), isStreaming = false),
                )
            }
        rule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        ThreadUiState("chat", "Client planning", isPromoted = false, hasMessages = true, items = items),
                        {},
                        {},
                        ConnectionState.Connected,
                        {},
                        isThinking = true,
                        questionState = pending,
                        onDemandOlderHistory = { demands++ },
                    )
                }
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        val anchor = rule.onNodeWithText("History 15").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { pending = question }
        assertEquals(anchor, rule.onNodeWithText("History 15").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { pending = question.copy(selections = listOf(QuestionSelection(otherTicked = true, otherText = " draft "))) }
        assertEquals(anchor, rule.onNodeWithText("History 15").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("question-batch-title").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(32)
        // #1352: reaching the oldest row is not a pull, so it asks nothing.
        rule.runOnIdle { assertEquals(0, demands) }
        rule.runOnIdle { pending = null }
        rule.waitForIdle()
        rule.runOnIdle { pending = question.copy(generation = 2) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("prompt replacement cannot manufacture an oldest-history demand", 0, demands) }
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun selected_option_can_reach_continue_when_the_actions_row_is_uncomposed() {
        var pending by mutableStateOf(
            question.copy(
                batch =
                    question.batch.copy(
                        questions =
                            listOf(
                                Question(
                                    "Choose a language",
                                    "Language",
                                    listOf(QuestionOption("Kotlin", "JVM\n".repeat(8)), QuestionOption("Rust", "Systems\n".repeat(8))),
                                    false,
                                ),
                            ),
                    ),
            ),
        )
        val continued = mutableListOf<Long>()
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 500.dp))) {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    PyrycodeMobileTheme {
                        ThreadScreen(
                            ThreadUiState("chat", "Client planning", isPromoted = false),
                            {},
                            {},
                            ConnectionState.Connected,
                            {},
                            questionState = pending,
                            onQuestionEvent = { event, generation ->
                                when (event) {
                                    is QuestionModalEvent.OptionToggled ->
                                        pending =
                                            pending.copy(
                                                selections = listOf(QuestionSelection(optionIndices = setOf(event.optionIndex))),
                                            )
                                    QuestionModalEvent.Continue -> continued += generation
                                    else -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }
        val option = hasText("Kotlin", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("thread-question-row"))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(option)
        rule.onNode(option).performClick().assertIsSelected()
        rule.runOnIdle { assertTrue("selection already permits Continue", pending.canContinue) }
        rule.onNodeWithTag("question-batch-actions").assertDoesNotExist()
        val continueButton =
            hasText("Continue") and hasClickAction() and isEnabled() and hasAnyAncestor(hasTestTag("question-batch-actions"))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("question-batch-actions"))
        rule.waitUntil(1_000) { rule.onAllNodes(continueButton).fetchSemanticsNodes().isNotEmpty() }
        rule
            .onNode(continueButton)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        rule.runOnIdle { assertEquals(listOf(pending.generation), continued) }
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun phone_question_scroll_sequence_submits_from_a_short_thread() {
        var pending by mutableStateOf(
            question.copy(
                batch =
                    question.batch.copy(
                        questions =
                            listOf(
                                Question(
                                    "Choose a language",
                                    "Language",
                                    listOf(QuestionOption("Kotlin", "JVM"), QuestionOption("Rust", "Systems")),
                                    false,
                                ),
                            ),
                    ),
            ),
        )
        val continued = mutableListOf<Long>()
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 650.dp))) {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    PyrycodeMobileTheme {
                        ThreadScreen(
                            ThreadUiState(
                                "chat",
                                "Client planning",
                                isPromoted = false,
                                hasMessages = true,
                                items = historyItems().take(1),
                            ),
                            {},
                            {},
                            ConnectionState.Connected,
                            {},
                            questionState = pending,
                            onQuestionEvent = { event, generation ->
                                when (event) {
                                    is QuestionModalEvent.OptionToggled ->
                                        pending =
                                            pending.copy(selections = listOf(QuestionSelection(optionIndices = setOf(event.optionIndex))))
                                    QuestionModalEvent.Continue -> continued += generation
                                    else -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("question-batch-title"))
        val option = hasText("Rust", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("thread-question-row"))
        rule.questionAnswerTarget(option).performTouchInput { click(center) }
        rule.onNode(option).assertIsSelected()
        val continueButton =
            hasText("Continue") and hasClickAction() and isEnabled() and hasAnyAncestor(hasTestTag("question-batch-actions"))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("question-batch-actions"))
        rule.waitUntil(1_000) { rule.onAllNodes(continueButton).fetchSemanticsNodes().isNotEmpty() }
        val target = rule.questionAnswerTarget(continueButton)
        val tap = rule.onNode(continueButton).fetchSemanticsNode().boundsInRoot
        val chromeTop =
            rule
                .onNodeWithTag("thread-composer")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Continue tap $tap must clear composer top $chromeTop", tap.center.y < chromeTop)
        target.performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(listOf(pending.generation), continued) }
    }

    // #1484, Figma 636:3803: the focus scroll shows the focused field and both actions, not only the field's own row.
    @Test
    fun focusing_other_reveals_the_field_and_both_actions() {
        rule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        ThreadUiState("chat", "Client planning", isPromoted = false, hasMessages = true, items = historyItems()),
                        {},
                        {},
                        ConnectionState.Connected,
                        {},
                        questionState = twoQuestions,
                    )
                }
            }
        }
        // Index 1 is the second question: under reverseLayout its bottom meets the stream's bottom edge, with the
        // actions (index 0) just below it, out of view.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        val stream = rule.onNodeWithTag("thread-message-region").getUnclippedBoundsInRoot()
        assertTrue("the field starts in view", stream.contains(rule.onNodeWithTag("question_other_1").getUnclippedBoundsInRoot()))
        assertTrue(
            "Continue is below the clear reading area",
            rule.onNodeWithText("Continue").getUnclippedBoundsInRoot().top >=
                rule.onNodeWithTag("thread-composer").getUnclippedBoundsInRoot().top,
        )

        rule.onNodeWithTag("question_other_1").performClick()
        rule.waitForIdle()
        for (bounds in listOf(
            rule.onNodeWithTag("question_other_1").getUnclippedBoundsInRoot(),
            rule.onNodeWithText("Cancel").getUnclippedBoundsInRoot(),
            rule.onNodeWithText("Continue").getUnclippedBoundsInRoot(),
        )) {
            assertTrue(
                "$bounds lies above the composer",
                bounds.bottom <= rule.onNodeWithTag("thread-status-band").getUnclippedBoundsInRoot().top,
            )
            assertTrue("$bounds lies inside the stream $stream", stream.contains(bounds))
        }
    }

    // #1484: only the last question's field reveals the actions, so an earlier field already in view never leaves it.
    @Test
    fun focusing_an_earlier_other_keeps_the_field_in_view_on_every_frame() {
        rule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        ThreadUiState("chat", "Client planning", isPromoted = false, hasMessages = true, items = historyItems()),
                        {},
                        {},
                        ConnectionState.Connected,
                        {},
                        questionState = twoQuestions,
                    )
                }
            }
        }
        // Index 2 is the first question: its bottom meets the stream's bottom edge, the second question and the
        // actions below it, out of view.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        val field = rule.onNodeWithTag("question_other_0")
        moveEarlierFieldTo(clearReadingArea().top + 80.dp)
        val before = field.getUnclippedBoundsInRoot()
        assertTrue("the field starts clear of chrome", clearReadingArea().contains(before))

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("question_other_0").performClick()
        repeat(30) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val bounds = field.getUnclippedBoundsInRoot()
            assertTrue("frame $frame: $bounds clears chrome", clearReadingArea().contains(bounds))
            assertEquals("an already-readable earlier field stays anchored", before.top.value, bounds.top.value, 0.5f)
        }
        rule.mainClock.autoAdvance = true
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @OptIn(ExperimentalTestApi::class)
    fun earlier_other_focus_clears_both_bars_and_tracks_viewport_and_composer_changes() {
        var viewportHeight by mutableStateOf(892.dp)
        var draft by mutableStateOf("")
        var attachments by mutableStateOf(emptyList<PendingAttachment>())
        val pending =
            twoQuestions.copy(
                batch = twoQuestions.batch.copy(questions = List(4) { twoQuestions.batch.questions[it % 2] }),
                selections = List(4) { QuestionSelection() },
            )
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, viewportHeight))) {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    PyrycodeMobileTheme {
                        ThreadScreen(
                            ThreadUiState("chat", "Client planning", isPromoted = false, hasMessages = true, items = historyItems()),
                            {},
                            {},
                            ConnectionState.Connected,
                            {},
                            questionState = pending,
                            draft = draft,
                            attachments = attachments,
                        )
                    }
                }
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        val field = rule.onNodeWithTag("question_other_0")
        val header = rule.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot()
        moveEarlierFieldTo(header.bottom - 40.dp)
        val obscured = field.getUnclippedBoundsInRoot()
        assertTrue("setup: field wholly under header: $obscured", obscured.top >= header.top && obscured.bottom < header.bottom)
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.assertIsFocused()
        assertEarlierFieldReadable()

        // Keyboard/accessibility focus does not inject a pointer through the chrome hit-test barrier.
        rule
            .onNode(
                hasSetTextAction() and hasAnyAncestor(hasTestTag("thread-composer")),
            ).performSemanticsAction(SemanticsActions.RequestFocus) {
                it()
            }
        val composer = rule.onNodeWithTag("thread-composer").getUnclippedBoundsInRoot()
        moveEarlierFieldTo(composer.top + 8.dp)
        assertTrue("setup: field beneath composer", field.getUnclippedBoundsInRoot().top > composer.top)
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.assertIsFocused()
        assertEarlierFieldReadable()

        // A shrinking drawing viewport models the layout part of IME open/close/reopen; real IME is covered on device.
        for (height in listOf(600.dp, 892.dp, 600.dp)) {
            rule.runOnIdle { viewportHeight = height }
            assertEarlierFieldReadable()
            field.assertIsFocused()
        }
        val initialComposer = clearReadingArea().bottom
        rule.runOnIdle { attachments = listOf(PendingAttachment(1, "content://test/file", "file.pdf", "application/pdf", 10)) }
        assertEarlierFieldReadable()
        assertTrue("attachments raise the measured composer", clearReadingArea().bottom < initialComposer)
        val attachmentComposer = clearReadingArea().bottom
        rule.runOnIdle { draft = "line 1\nline 2\nline 3\nline 4" }
        assertEarlierFieldReadable()
        assertTrue("multiline draft raises the measured composer", clearReadingArea().bottom < attachmentComposer)
        rule.runOnIdle {
            draft = ""
            attachments = emptyList()
        }
        assertEarlierFieldReadable()
        assertEquals("composer returns to its initial height", initialComposer.value, clearReadingArea().bottom.value, 0.5f)
    }

    private fun clearReadingArea(): DpRect {
        val stream = rule.onNodeWithTag("thread-message-region").getUnclippedBoundsInRoot()
        return DpRect(
            stream.left,
            rule.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot().bottom,
            stream.right,
            rule.onNodeWithTag("thread-composer").getUnclippedBoundsInRoot().top,
        )
    }

    private fun assertEarlierFieldReadable() {
        rule.waitForIdle()
        val field = rule.onNodeWithTag("question_other_0").getUnclippedBoundsInRoot()
        assertTrue("focused field $field clears both bars ${clearReadingArea()}", clearReadingArea().contains(field))
    }

    private fun moveEarlierFieldTo(top: androidx.compose.ui.unit.Dp) {
        val field = rule.onNodeWithTag("question_other_0")
        val list = rule.onNode(hasScrollToIndexAction())
        val before = field.getUnclippedBoundsInRoot()
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10f) }
        rule.waitForIdle()
        val moved = field.getUnclippedBoundsInRoot()
        val direction = with(rule.density) { (moved.top - before.top).toPx() } / 10f
        assertTrue("setup scroll must move the field", kotlin.math.abs(direction) > 0.5f)
        val shift = with(rule.density) { (top - moved.top).toPx() } / direction
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }
        rule.waitForIdle()
    }

    // #1484, Figma 636:4325: stacked at 150 %, the pair sits at the card's start with Cancel centred over Continue.
    @Test
    fun stacked_actions_sit_at_the_start_with_cancel_centred_over_continue() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        ThreadUiState("chat", "Client planning", isPromoted = false),
                        {},
                        {},
                        ConnectionState.Connected,
                        {},
                        questionState = question,
                    )
                }
            }
        }
        val actions = rule.onNodeWithTag("question-batch-actions").getUnclippedBoundsInRoot()
        val cancel = rule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        val continueButton = rule.onNodeWithText("Continue").getUnclippedBoundsInRoot()
        assertTrue("stacked", cancel.bottom <= continueButton.top)
        assertEquals(actions.left.value, continueButton.left.value, 0.5f)
        assertEquals((continueButton.left + continueButton.right).value / 2, (cancel.left + cancel.right).value / 2, 0.5f)
    }

    // #1501, Figma 636:3279: the control top-aligned with "Other", the 32 dp well 28 dp below the label's top, 242 dp cards.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w412dp-h892dp")
    fun other_choice_matches_the_frame_and_keeps_a_48dp_field_target() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    ThreadUiState("chat", "Client planning", isPromoted = false),
                    {},
                    {},
                    ConnectionState.Connected,
                    {},
                    questionState = twoQuestions,
                )
            }
        }
        for (index in 0..1) {
            val label =
                rule
                    .onNode(hasText("Other") and hasAnyAncestor(hasTestTag("question_control_${index}_other_row")), useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
            val control = rule.onNodeWithTag("question_control_${index}_other", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val field = rule.onNodeWithTag("question_other_$index").getUnclippedBoundsInRoot()
            val card = rule.onNodeWithTag("question_card_$index", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals("question $index: control top-aligned with the label", label.top.value, control.top.value, 0.5f)
            assertEquals("question $index: field 28 dp below the label's top", 28f, (field.top - label.top).value, 0.5f)
            assertEquals("question $index: 32 dp well", 32f, field.height.value, 0.5f)
            assertEquals("question $index: card height", 242f, card.height.value, 2f)
        }

        val field = rule.onNodeWithTag("question_other_1")
        with(rule.density) { assertTrue(field.fetchSemanticsNode().touchBoundsInRoot.height >= 48.dp.toPx() - 0.5f) }
        // A real tap 6 dp below the visible well, in the card's padding, still focuses the field.
        field.performTouchInput { click(Offset(centerX, height + 6.dp.toPx())) }
        field.assertIsFocused()
    }

    private fun DpRect.contains(other: DpRect): Boolean =
        other.left >= left - 0.5.dp && other.right <= right + 0.5.dp && other.top >= top - 0.5.dp && other.bottom <= bottom + 0.5.dp
}

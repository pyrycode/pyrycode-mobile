package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        rule.runOnIdle { assertEquals(1, demands) }
        rule.runOnIdle { pending = null }
        rule.waitForIdle()
        rule.runOnIdle { pending = question.copy(generation = 2) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("prompt replacement cannot manufacture another oldest-history demand", 1, demands) }
    }
}

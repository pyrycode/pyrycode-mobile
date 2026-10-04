package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.STATUS_GLYPH_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * #1312: the composer's status band is always composed while a thread is open, with the one status
 * snowflake at its leading edge in every reading, and it keeps its height so the input field never moves.
 * Native graphics at Figma's 412dp reference width, so each reading measures its real one-line height inside
 * the 24dp band; a reading that wraps on a narrower screen raises the band, as it always has.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ThreadStatusBandTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** One band reading, as the screen's hoisted inputs. */
    private data class Reading(
        val name: String,
        val connectionState: ConnectionState = ConnectionState.Connected,
        val isThinking: Boolean = false,
        val isBusy: Boolean = false,
        val apiRetry: ApiRetryStatus = ApiRetryStatus.NotRetrying,
        val isCompacting: Boolean = false,
        val resetting: ResetStatus? = null,
        val isStalled: Boolean = false,
        val turnOutcome: TurnRecoveryNotice? = null,
        val runningTool: Boolean = false,
        val taskCount: Int = 0,
        val waiting: Boolean = false,
        val localSendStage: LocalSendStage = LocalSendStage.None,
    )

    private var reading by mutableStateOf(Reading("idle"))

    private fun string(id: Int): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id)

    private fun message(
        id: String,
        toolCall: ToolCall? = null,
    ) = ThreadItem.MessageItem(
        Message(
            id = id,
            sessionId = "s1",
            role = Role.Assistant,
            content = "message $id",
            timestamp = Instant.parse("2026-05-20T10:00:00Z"),
            isStreaming = false,
            toolCall = toolCall,
        ),
    )

    private val question =
        QuestionModalState(
            QuestionBatch(
                "c1",
                "request",
                listOf(Question("Choose a language", "Language", listOf(QuestionOption("Kotlin", "JVM")), false)),
            ),
            generation = 1,
        )

    private fun setThread(referenceWidth: Boolean = true) {
        composeTestRule.setContent {
            val r = reading
            val items =
                if (r.runningTool) {
                    listOf(message("m0"), message("t1", ToolCall("Bash", "", "", ToolCallStatus.Running)))
                } else {
                    listOf(message("m0"))
                }
            // ForcedSize rescales density, so the absolute-dp geometry checks keep the default configuration.
            val size = DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp)).takeIf { referenceWidth }
            DeviceConfigurationOverride(size ?: DeviceConfigurationOverride.FontScale(1f)) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                conversationId = "c1",
                                displayName = "Test channel",
                                isPromoted = true,
                                hasMessages = true,
                                items = items,
                                backgroundTaskCount = r.taskCount,
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = r.connectionState,
                        onRetry = {},
                        questionState = question.takeIf { r.waiting },
                        isThinking = r.isThinking,
                        isBusy = r.isBusy,
                        localSendStage = r.localSendStage,
                        thinkingProgress = ThinkingProgress(184, 184).takeIf { r.localSendStage != LocalSendStage.None },
                        apiRetry = r.apiRetry,
                        isCompacting = r.isCompacting,
                        resetting = r.resetting,
                        isStalled = r.isStalled,
                        turnOutcome = r.turnOutcome,
                    )
                }
            }
        }
    }

    private val states =
        listOf(
            Reading("idle"),
            Reading("sending", localSendStage = LocalSendStage.Sending),
            Reading("waiting", localSendStage = LocalSendStage.Waiting),
            Reading("offline", connectionState = ConnectionState.Offline),
            Reading("connecting", connectionState = ConnectionState.Connecting),
            Reading("reconnecting", connectionState = ConnectionState.Reconnecting(5)),
            Reading("thinking", isThinking = true, isBusy = true),
            Reading("working", isBusy = true),
            Reading("running tool", isBusy = true, runningTool = true),
            Reading("api-retry", isBusy = true, apiRetry = ApiRetryStatus.Attempt(2, 10)),
            Reading("compaction", isBusy = true, isCompacting = true),
            Reading("reset", isBusy = true, resetting = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)),
            Reading("stall", isBusy = true, isStalled = true),
            Reading("turn outcome", turnOutcome = TurnRecoveryNotice.ContextTooLong),
            Reading("task pill, idle", taskCount = 2),
            Reading("task pill, busy", isBusy = true, isThinking = true, taskCount = 2),
        )

    @Test
    fun everyReading_drawsOneSnowflake_atTheSameLeadingEdge() {
        setThread()
        val glyphs = composeTestRule.onAllNodesWithTag(STATUS_GLYPH_TEST_TAG, useUnmergedTree = true)
        var expected: DpRect? = null
        states.forEach { state ->
            reading = state
            composeTestRule.waitForIdle()
            glyphs.assertCountEquals(1)
            val bounds = glyphs[0].getUnclippedBoundsInRoot()
            if (state.turnOutcome != null) {
                // #1357: every recovery notice wraps to two lines even at 412dp, raising the band, and the glyph
                // stays centred on it; its leading edge must not move.
                val reference = expected ?: bounds
                assertEquals("glyph left in ${state.name}", reference.left, bounds.left)
                assertEquals("glyph right in ${state.name}", reference.right, bounds.right)
                return@forEach
            }
            assertEquals("glyph bounds in ${state.name}", expected ?: bounds, bounds)
            expected = bounds
        }
    }

    @Test
    fun waitingForAnswers_keepsItsQuestionGlyph_insteadOfTheSnowflake() {
        reading = Reading("waiting", isBusy = true, waiting = true, localSendStage = LocalSendStage.Sending)
        setThread()

        for (stage in listOf(LocalSendStage.Sending, LocalSendStage.Waiting)) {
            reading = reading.copy(localSendStage = stage)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(string(R.string.question_waiting_for_answers)).assertIsDisplayed()
            composeTestRule.onAllNodesWithTag(STATUS_GLYPH_TEST_TAG, useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onNodeWithText(string(R.string.thread_sending_label)).assertDoesNotExist()
            composeTestRule.onNodeWithText(string(R.string.thread_waiting_label)).assertDoesNotExist()
        }
    }

    @Test
    fun turnReadings_sitInTheTaggedReadingBox() {
        reading = Reading("working", isBusy = true)
        setThread()

        composeTestRule
            .onNode(
                hasText(string(R.string.thread_working_label)) and hasAnyAncestor(hasTestTag(STATUS_READING_TEST_TAG)),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    @Test
    fun inputField_keepsItsBounds_betweenIdleAndBusy() {
        setThread()
        val field = composeTestRule.onNode(hasSetTextAction())
        val idle = field.getUnclippedBoundsInRoot()

        reading = Reading("thinking", isThinking = true, isBusy = true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.thread_thinking_label)).assertIsDisplayed()
        assertEquals(idle, field.getUnclippedBoundsInRoot())

        for ((stage, label) in listOf(
            LocalSendStage.Sending to R.string.thread_sending_label,
            LocalSendStage.Waiting to R.string.thread_waiting_label,
        )) {
            reading = Reading("local", localSendStage = stage)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(string(label)).assertIsDisplayed()
            assertEquals(idle, field.getUnclippedBoundsInRoot())
        }
        reading = Reading("idle")
        composeTestRule.waitForIdle()
        assertEquals(idle, field.getUnclippedBoundsInRoot())
    }

    @Test
    fun label_startsAfterTheFigmaGlyphAndGap_onTheComposerGutter() {
        reading = Reading("thinking", isThinking = true, isBusy = true)
        setThread(referenceWidth = false)

        // 20dp gutter + 14dp glyph + 8dp gap.
        composeTestRule
            .onNodeWithText(string(R.string.thread_thinking_label), useUnmergedTree = true)
            .assertLeftPositionInRootIsEqualTo(42.dp)
    }

    @Test
    fun localStages_showOnlyTheirLabel_withoutAStaleThinkingTokenReading() {
        setThread()
        for ((stage, label) in listOf(
            LocalSendStage.Sending to R.string.thread_sending_label,
            LocalSendStage.Waiting to R.string.thread_waiting_label,
        )) {
            reading = Reading("local", localSendStage = stage)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(string(label)).assertIsDisplayed()
            composeTestRule.onNodeWithText(string(R.string.thread_thinking_label)).assertDoesNotExist()
            composeTestRule.onAllNodes(hasText("184", substring = true)).assertCountEquals(0)
        }
    }

    @Test
    fun turningGlyph_keepsItsFigmaSlotAcrossFrames() {
        composeTestRule.mainClock.autoAdvance = false
        reading = Reading("working", isBusy = true)
        setThread(referenceWidth = false)
        composeTestRule.mainClock.advanceTimeByFrame()
        val glyph = composeTestRule.onNodeWithTag(STATUS_GLYPH_TEST_TAG, useUnmergedTree = true)
        val first = glyph.getUnclippedBoundsInRoot()
        assertEquals(14.dp, first.right - first.left)
        assertEquals(16.dp, first.bottom - first.top)

        composeTestRule.mainClock.advanceTimeBy(400)
        assertEquals(first, glyph.getUnclippedBoundsInRoot())
        composeTestRule.mainClock.advanceTimeBy(400)
        assertEquals(first, glyph.getUnclippedBoundsInRoot())
    }
}

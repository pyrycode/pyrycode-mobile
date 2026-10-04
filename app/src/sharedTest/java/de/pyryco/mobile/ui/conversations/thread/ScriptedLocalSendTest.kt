package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.network.Envelope
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

/** Rung 2: real repository sends, with acknowledgement and first turn_state held independently. */
@RunWith(AndroidJUnit4::class)
class ScriptedLocalSendTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness
    private val sends = ConcurrentLinkedQueue<Envelope>()
    private val localLabels = listOf(R.string.thread_sending_label, R.string.thread_waiting_label, R.string.thread_waiting_label_codex)

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    private fun start(agent: ConversationAgent = ConversationAgent.Claude) {
        harness =
            ScriptedThreadHarness(
                composeRule,
                seedAgent = agent,
                onSend = { envelope ->
                    if (envelope.type == "send_message") sends.add(envelope)
                    true
                },
            )
        harness.start()
    }

    /** Tap the actual composer Send control; capture the repository's request, not a fake send result. */
    private fun send(): Envelope {
        composeRule.onNode(hasSetTextAction()).performTextInput("Please reply")
        composeRule.onNodeWithContentDescription(string(R.string.cd_send_message)).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { sends.isNotEmpty() }
        return checkNotNull(sends.poll())
    }

    private fun acknowledge(
        request: Envelope,
        matching: Boolean = true,
    ) {
        harness.pushEnvelope(
            Envelope(
                id = 900L,
                type = "ack",
                ts = "2026-10-04T00:00:00Z",
                inReplyTo = request.id + if (matching) 0L else 10_000L,
                payload = buildJsonObject {},
            ),
        )
    }

    private fun reading(label: String) = hasText(label) and hasAnyAncestor(hasTestTag(STATUS_READING_TEST_TAG))

    private fun awaitReading(label: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(reading(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(reading(label), useUnmergedTree = true).assertIsDisplayed()
        localLabels.map(::string).filter { it != label }.forEach {
            composeRule.onNodeWithText(it).assertDoesNotExist()
        }
    }

    private fun assertLocalOnly(label: Int) {
        awaitReading(string(label))
        composeRule.onNodeWithText(string(R.string.thread_thinking_label)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.cd_thread_interrupt)).assertDoesNotExist()
    }

    @Test
    fun claude_sendWaitsForItsCorrelatedAcknowledgement_thenForItsOwnTurnState() {
        start()
        val request = send()
        assertLocalOnly(R.string.thread_sending_label)

        acknowledge(request, matching = false)
        harness.pushTurnState("thinking", targetConversationId = "another-chat")
        composeRule.waitForIdle()
        assertLocalOnly(R.string.thread_sending_label)

        acknowledge(request)
        assertLocalOnly(R.string.thread_waiting_label)
        // Neither a different chat starting nor ordinary text establishes this chat's turn phase.
        harness.pushTurnState("responding", targetConversationId = "another-chat")
        harness.pushAssistantDelta("t1", 0, "Still waiting for a phase")
        composeRule.waitForIdle()
        assertLocalOnly(R.string.thread_waiting_label)

        harness.pushTurnState("thinking")
        awaitReading(string(R.string.thread_thinking_label))
        composeRule.onNodeWithContentDescription(string(R.string.cd_thread_interrupt)).assertIsDisplayed()
    }

    @Test
    fun codex_usesCodexWaitingCopy_untilRespondingStarts() {
        start(ConversationAgent.Codex)
        val request = send()
        assertLocalOnly(R.string.thread_sending_label)
        acknowledge(request)
        assertLocalOnly(R.string.thread_waiting_label_codex)

        harness.pushTurnState("responding")
        awaitReading(string(R.string.thread_working_label))
    }

    private fun closeBeforeAcknowledgement(
        phase: String,
        expected: Int?,
    ) {
        start()
        val request = send()
        assertLocalOnly(R.string.thread_sending_label)
        harness.pushTurnState(phase)
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(reading(string(R.string.thread_sending_label)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        if (expected != null) awaitReading(string(expected))
        acknowledge(request)
        // A later event is an inbound barrier: it must fold after the ack before the assertions run.
        harness.pushAssistantDelta("barrier", 0, "Acknowledgement processed")
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Acknowledgement processed", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        localLabels.forEach { composeRule.onNodeWithText(string(it)).assertDoesNotExist() }
        if (expected != null) {
            awaitReading(string(expected))
        } else {
            composeRule.onNodeWithContentDescription(string(R.string.cd_thread_interrupt)).assertDoesNotExist()
        }
    }

    @Test
    fun thinkingBeforeAcknowledgement_doesNotReopenWaiting() = closeBeforeAcknowledgement("thinking", R.string.thread_thinking_label)

    @Test
    fun respondingBeforeAcknowledgement_doesNotReopenWaiting() = closeBeforeAcknowledgement("responding", R.string.thread_working_label)

    @Test
    fun idleBeforeAcknowledgement_doesNotReopenWaiting() = closeBeforeAcknowledgement("idle", null)

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

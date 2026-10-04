package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
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
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

/** Rung 2 only: session_error → real repository → ViewModel → stateless thread, with held send acknowledgements. */
@RunWith(AndroidJUnit4::class)
class ScriptedSessionErrorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness
    private val sends = ConcurrentLinkedQueue<Envelope>()
    private var barrier = 0

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    private fun start(agent: ConversationAgent = ConversationAgent.Claude) {
        harness =
            ScriptedThreadHarness(
                composeRule,
                seedAgent = agent,
                onSend = {
                    if (it.type == "send_message") sends.add(it)
                    true
                },
            )
        harness.start()
    }

    private fun error(
        code: String,
        conversationId: String = "c1",
    ) {
        harness.pushEnvelope(
            Envelope(
                id = 700L,
                type = "session_error",
                ts = TS,
                payload =
                    buildJsonObject {
                        put("conversation_id", conversationId)
                        put("code", code)
                        put("message", DAEMON_PROSE)
                    },
            ),
        )
    }

    /** A durable row proves prior inbound envelopes were processed, including negative assertions. */
    private fun inboundBarrier() {
        val text = "Inbound fence ${++barrier}"
        harness.pushAssistantDelta("fence-$barrier", 0, text)
        awaitText(text, substring = true)
        composeRule.waitForIdle()
    }

    private fun awaitText(
        text: String,
        substring: Boolean = false,
    ) {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text, substring = substring).assertIsDisplayed()
    }

    private fun assertPill(text: String) {
        awaitText(text)
        composeRule.onNodeWithContentDescription(text).assertIsDisplayed().assertHasNoClickAction()
        composeRule.onNodeWithContentDescription(string(R.string.thread_notice_dismiss)).assertDoesNotExist()
        composeRule.onNodeWithText(DAEMON_PROSE).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(DAEMON_PROSE).assertDoesNotExist()
    }

    private fun allCopy(
        agent: ConversationAgent,
        name: String,
    ) {
        start(agent)
        val cases =
            listOf(
                "session.blocked" to "$name did not pick up the last message. It was not delivered.",
                "session.child_crashing" to "$name keeps failing to start. Your message is waiting.",
                HOSTILE_CODE to "$name stopped responding.",
            )
        var previous: String? = null
        for ((code, expected) in cases) {
            error(code)
            assertPill(expected)
            previous?.let { composeRule.onNodeWithText(it).assertDoesNotExist() }
            composeRule.onNodeWithText(code).assertDoesNotExist()
            composeRule.onNodeWithContentDescription(code).assertDoesNotExist()
            previous = expected
        }
        // No timeout or tap hides the latest code. An unrelated live event leaves it in place.
        composeRule.mainClock.advanceTimeBy(300_000)
        inboundBarrier()
        assertPill("$name stopped responding.")

        harness.pushTurnState("idle")
        inboundBarrier()
        assertPill("$name stopped responding.")
        harness.pushTurnState("thinking", targetConversationId = "other")
        inboundBarrier()
        assertPill("$name stopped responding.")
        harness.pushTurnState("thinking")
        awaitText(string(R.string.thread_thinking_label))
        composeRule.onNodeWithText("$name stopped responding.").assertDoesNotExist()
    }

    @Test
    fun claude_knownAndUnknownCodes_areClientCopy_untilOwnNonIdleTurnClears() = allCopy(ConversationAgent.Claude, "Claude")

    @Test
    fun codex_knownAndUnknownCodes_areClientCopy_untilOwnNonIdleTurnClears() = allCopy(ConversationAgent.Codex, "Codex")

    @Test
    fun anotherConversationsError_neverAppearsInTheOpenThread_butIsHeldForItsOwnThread() {
        start()
        error("session.blocked", conversationId = "other")
        inboundBarrier()
        composeRule.onNodeWithText(BLOCKED).assertDoesNotExist()
        harness.openConversation("other", "Second channel")
        assertPill(BLOCKED)
        harness.openConversation("c1", "First channel")
        composeRule.onNodeWithText(BLOCKED).assertDoesNotExist()
    }

    private fun send(): Envelope {
        composeRule.onNode(hasSetTextAction()).performTextInput("Please reply")
        composeRule.onNodeWithContentDescription(string(R.string.cd_send_message)).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { sends.isNotEmpty() }
        return checkNotNull(sends.poll())
    }

    private fun acknowledge(request: Envelope) {
        harness.pushEnvelope(
            Envelope(
                id = 900L,
                type = "ack",
                ts = TS,
                inReplyTo = request.id,
                payload = buildJsonObject {},
            ),
        )
    }

    private fun assertNoLocalIndicator() {
        for (id in listOf(R.string.thread_sending_label, R.string.thread_waiting_label, R.string.thread_waiting_label_codex)) {
            composeRule.onNodeWithText(string(id)).assertDoesNotExist()
        }
        composeRule.onNodeWithText(string(R.string.thread_thinking_label)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.cd_thread_interrupt)).assertDoesNotExist()
    }

    @Test
    fun errorBeforeAck_endsSending_andLateAckCannotReopen_thenNewSendClearsThePill() {
        start()
        val first = send()
        awaitText(string(R.string.thread_sending_label))
        error("session.blocked", conversationId = "other")
        inboundBarrier()
        awaitText(string(R.string.thread_sending_label))
        composeRule.onNodeWithText(BLOCKED).assertDoesNotExist()

        error("session.blocked")
        assertPill(BLOCKED)
        assertNoLocalIndicator()
        acknowledge(first)
        inboundBarrier()
        assertPill(BLOCKED)
        assertNoLocalIndicator()

        val second = send()
        awaitText(string(R.string.thread_sending_label))
        composeRule.onNodeWithText(BLOCKED).assertDoesNotExist()
        acknowledge(second)
        awaitText(string(R.string.thread_waiting_label))
        error("session.child_crashing")
        assertPill(CRASHING)
        assertNoLocalIndicator()
        harness.pushTurnState("responding")
        awaitText(string(R.string.thread_working_label))
        composeRule.onNodeWithText(CRASHING).assertDoesNotExist()
    }

    @Test
    fun codex_errorAfterAck_endsWaiting_andRecoveryClearsItsPill() {
        start(ConversationAgent.Codex)
        val request = send()
        acknowledge(request)
        awaitText(string(R.string.thread_waiting_label_codex))
        error("session.child_crashing")
        assertPill("Codex keeps failing to start. Your message is waiting.")
        assertNoLocalIndicator()
        harness.pushTurnState("responding")
        awaitText(string(R.string.thread_working_label))
        composeRule.onNodeWithText("Codex keeps failing to start. Your message is waiting.").assertDoesNotExist()
    }

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val TS = "2026-10-04T00:00:00Z"
        const val BLOCKED = "Claude did not pick up the last message. It was not delivered."
        const val CRASHING = "Claude keeps failing to start. Your message is waiting."
        const val DAEMON_PROSE = "Untrusted daemon prose: secret detail <script>do not show</script>"
        const val HOSTILE_CODE = "unknown\nraw.secret<script>code</script>"
    }
}

package de.pyryco.mobile.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ConnectionStateSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Happy-path end-to-end test for the mobile interactive event stream (#642 rung 3, ADR 025): the
 * **real** app on a headless emulator connects to a host `pyry` + relay, sends a constrained prompt to
 * real claude, and asserts the streamed assistant reply renders in the thread.
 *
 * This test does **not** stand alone — it is driven by `scripts/e2e-emulator.sh`, which boots the
 * relay + daemon, mints a device token with `pyry pair`, and passes the relay URL / token / server id
 * / server static public key as instrumentation arguments. [E2eInstrumentationRunner] sees those args
 * and swaps in [E2eTestApplication] (paired + relay-backed). Run it via the managed-device task:
 *
 * ```
 * bash scripts/e2e-emulator.sh
 * ```
 *
 * Semi-deterministic by nature (real claude): every assertion is **tolerant** — substring + case
 * insensitive, generous timeouts, and never an assertion on delta counts or timing. The "reply with
 * exactly: ping" framing is what keeps real claude's output predictable enough to assert against while
 * still exercising the whole real path.
 *
 * The Compose selectors are text / content-description based (no test tags in the production UI). If
 * the UI strings change, update the constants below.
 */
@RunWith(AndroidJUnit4::class)
class InteractiveStreamE2ETest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun interactiveTurn_pingPrompt_streamsPingReplyIntoThread() {
        // 1. A paired launch lands on the channel list. The "New discussion" FAB is the list marker.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Wait for the relay connection to open before creating a conversation — createDiscussion
        //    round-trips to the daemon, so tapping before the session is Open would fail the send.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread. The send button (only on
        //    the thread) is the marker that we have arrived.
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Type the constrained prompt into the only editable field, then send.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        // 5. The prompt itself contains "ping" (so does, possibly, the auto-derived thread title), so
        //    we do not assert on a fixed count. Instead: wait for the sent prompt to render, snapshot
        //    how many "ping"-bearing nodes exist, then wait for the assistant's streamed reply to add
        //    at least one MORE. This is robust to whatever the prompt/title contribute.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { pingNodeCount() >= 1 }
        val baseline = pingNodeCount()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { pingNodeCount() > baseline }

        // The newest "ping" node is the assistant reply — confirm it is actually on screen.
        composeTestRule
            .onAllNodesWithText(PING, substring = true, ignoreCase = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * Negative control (manual). Un-ignore once to confirm the positive assertion is real: it sends the
     * same ping prompt but waits for a word claude is never asked to say. On a correct build this wait
     * **times out and the test FAILS** — proving the substring matcher is not matching everything and
     * the positive test is genuinely observing claude's output. Left `@Ignore` so it does not burn a
     * claude turn on every suite run.
     */
    @Ignore("manual negative control — un-ignore to confirm the positive assertion can fail")
    @Test
    fun negativeControl_wordClaudeNeverSays_isNeverDisplayed() {
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        awaitConnected()
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        // "pong" is in neither the prompt nor the expected reply, so this must time out (→ fail).
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("pong", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Tool-use twin of the ping happy path (#481, Layer 3): a constrained prompt makes **real claude run
     * a shell tool**, and we assert the tool step renders in the thread. The render path (`tool_use` →
     * running row, `tool_result` → done — #387 correlation / #388 tool-row status UI) is already shipped
     * and reviewed; this exercises it end to end against real claude.
     *
     * The load-bearing signal is the **durable** terminal one: the verbatim tool name [TOOL_NAME] sits in
     * the collapsed tool-row header in all three states, carried verbatim through the #387 fold. We do
     * **not** race the transient running spinner — rung 3 has no scripted backend to hold the turn open
     * (that is what #455's two-drop fence is for), and chasing the transient over a real relay turn is
     * exactly the "never on timing" failure the ladder forbids.
     *
     * [TOOL_PROMPT] deliberately contains neither "Bash" nor "bash", so [TOOL_NAME] is absent from
     * everything on screen before claude responds (the echoed user bubble, the auto-derived thread title,
     * the thinking spinner). A non-empty match can therefore only come from the rendered tool row — a
     * presence check, not a count.
     */
    @Test
    fun interactiveTurn_toolPrompt_rendersToolStepInThread() {
        // 1. A paired launch lands on the channel list. The "New discussion" FAB is the list marker.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Wait for the relay connection to open before creating a conversation.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival.
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Type the tool-forcing prompt into the only editable field, then send.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(TOOL_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        // 5. Wait for the verbatim tool name to appear, then confirm it is on screen. Because the prompt
        //    omits the token, the only source of a match is the rendered tool row's header.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(TOOL_NAME, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(TOOL_NAME, substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * Negative control (manual) for the tool-use test. Un-ignore once to confirm the [TOOL_NAME] matcher
     * is selective: it sends the same tool prompt but waits for [TOOL_NEVER_USED] — a real, distinct tool
     * name the read-only echo prompt never asks claude to use. On a correct build this wait **times out
     * and the test FAILS**, proving the positive assertion genuinely observes a rendered tool row rather
     * than matching everything. Left `@Ignore` so it does not burn a claude turn on every suite run; the
     * operator un-ignores it once to confirm, then re-ignores.
     */
    @Ignore("manual negative control — un-ignore to confirm the tool-name matcher is selective")
    @Test
    fun negativeControl_toolClaudeNeverUses_isNeverDisplayed() {
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        awaitConnected()
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(TOOL_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        // The prompt asks only for a read-only shell command, never a file edit, so "Edit" must never
        // render a tool row → this wait must time out (→ fail).
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(TOOL_NEVER_USED, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Count the on-screen semantic nodes whose text contains "ping" (case-insensitive, substring). */
    private fun pingNodeCount(): Int =
        composeTestRule
            .onAllNodesWithText(PING, substring = true, ignoreCase = true)
            .fetchSemanticsNodes()
            .size

    /** Block until the relay connection reports [ConnectionState.Connected], or fail after a timeout. */
    private fun awaitConnected() {
        val source = GlobalContext.get().get<ConnectionStateSource>()
        runBlocking {
            withTimeout(CONNECT_TIMEOUT_MS) {
                source.observe().first { it is ConnectionState.Connected }
            }
        }
    }

    private companion object {
        const val PING = "ping"

        // Constrained prompt: real claude's output is predictable enough to assert against, while the
        // path stays fully real. "exactly the word: ping" is the determinism lever.
        const val PING_PROMPT = "Reply with exactly the word: ping (nothing else)."

        // Tool-use determinism lever (#481): a direct imperative to RUN a shell command reliably makes
        // real claude use its shell tool (claude names it "Bash"), where "what does X output?" might be
        // answered inline. `echo <fixed string>` is read-only, side-effect-free, and harmless on the
        // host. "then stop without commentary" keeps surrounding prose minimal. The prompt contains
        // neither "Bash" nor "bash" so the asserted tool-name substring can only come from the tool row.
        const val TOOL_PROMPT = "Run this exact shell command with your tools, then stop without commentary: echo pyry481"

        // Claude's verbatim shell-tool name; renders in the tool-row header (#388) in all three states.
        const val TOOL_NAME = "Bash"

        // Negative control: a real, distinct tool name the read-only echo prompt never asks claude to
        // use, so the matcher's selectivity is what is proven (not a nonsense string).
        const val TOOL_NEVER_USED = "Edit"

        // Production UI strings (no test tags exist). Keep in sync with res/values/strings.xml:
        //   cd_new_discussion = "New discussion", cd_send_message = "Send message".
        const val CD_NEW_DISCUSSION = "New discussion"
        const val CD_SEND_MESSAGE = "Send message"

        const val LIST_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val THREAD_TIMEOUT_MS = 30_000L

        // Generous: a real claude turn over the relay can take many seconds end to end.
        const val REPLY_TIMEOUT_MS = 90_000L
    }
}

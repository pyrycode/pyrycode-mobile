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
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
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

    // The thinking spinner's content-description (production UI string, no test tags). Copied from
    // DeterministicInteractiveStreamE2ETest (the rung-4 twin). Keep in sync with res/values/strings.xml:
    //   cd_thread_thinking = "Agent is thinking".
    private val thinkingDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_thinking)

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

    /**
     * Thinking-spinner twin of the ping happy path (#482, Layer 3): a **pure-reasoning** prompt makes
     * **real claude think for a beat**, and we assert the thinking spinner is displayed while the turn is
     * active. The render path (`turn_state(thinking)` →
     * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.isThinking] → `ThinkingIndicator`, #406) is
     * already shipped and reviewed; this exercises it end to end against real claude — the only layer that
     * catches real claude changing the screen text the screen-sourced spinner is matched from. Real-claude
     * twin of #454 (rung 4 scripted) and #432 (Layer 1a component).
     *
     * **`@Ignore`d by default — a documented manual case (AC #3), not a flaky always-on test.** This is the
     * flakiest scenario on the ladder. Unlike #481's tool row — whose verbatim tool name [TOOL_NAME] is a
     * **durable** terminal signal that survives turn-end — the spinner leaves **no durable artifact**: the
     * instant real claude emits its first token the daemon flips `turn_state` to `responding`, `isThinking`
     * goes false, and `ThinkingIndicator` early-returns, so the node disappears with no trace. Rung 3 has no
     * scripted backend to hold the turn open and cannot imperatively pause real claude (the levers #454's
     * two-drop fence and #432's `pushTurnState` give the twins), so the spinner's presence mid-turn cannot
     * be made deterministic here. The operator un-ignores to attempt the run; if a pure-reasoning prompt
     * yields a `turn_state(thinking)` window long enough to observe over the relay, the operator may
     * promote it to always-on — otherwise it stays a documented manual case. See
     * `docs/e2e-interactive-stream.md`.
     *
     * **Presence-only, mid-turn — no absence-after-end assertion.** Asserting the spinner *cleared* would
     * need the scripted two-drop fence to make the transition deterministic; at rung 3 a second real-claude
     * turn would itself re-enter `thinking` and re-show the spinner, so an absence check would race a 2nd
     * turn — exactly the "never on timing" failure the ladder forbids (AC #2). The single load-bearing
     * assertion catches the spinner content-description while the turn is in its thinking phase, tolerantly,
     * never on counts or timing.
     *
     * **No negative control (deliberate divergence from the ping / tool-use siblings).** Those assert on
     * claude *output* substrings and each ship an `@Ignore`d control to prove the matcher is selective. Here
     * the asserted token is the production content-description [thinkingDescription] (`cd_thread_thinking`),
     * which never appears in any user bubble, auto-derived title, or claude output — there is nothing for a
     * negative control to disprove. This `@Ignore`d positive test *is* the manual case.
     */
    @Ignore("manual — transient spinner; un-ignore to attempt promotion, see KDoc")
    @Test
    fun interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn() {
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

        // 4. Type the pure-reasoning prompt into the only editable field, then send. The prompt forbids
        //    tool use (so the #428 permission modal never interposes) and asks claude to reason a beat
        //    before answering with only a short token, widening the transient thinking window.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(THINK_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        // 5. Catch the thinking spinner mid-turn, then confirm it is on screen. Presence only — the spinner
        //    is transient and leaves no durable artifact once the turn moves on, so we never assert it
        //    cleared (that would race a 2nd turn). The matched content-description is emitted only by
        //    ThinkingIndicator, so a non-empty match can only be the live spinner.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodes(hasContentDescription(thinkingDescription))
            .onFirst()
            .assertIsDisplayed()
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

        // Pure-reasoning determinism lever (#482): keeps real claude *thinking* (no tool call → the #428
        // permission modal never interposes, unlike TOOL_PROMPT) and asks it to reason a beat before
        // replying with only a short token, widening the transient turn_state(thinking) window the spinner
        // shows during. Correctness does NOT depend on the window being reliably catchable — that is why
        // the test ships @Ignore'd; the exact wording is the developer's to tune on first operator run.
        const val THINK_PROMPT =
            "Without using any tools, take a moment to reason this through silently, then reply with only " +
                "the single word: ready. (Reason through first: what is the 12th prime number?)"

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

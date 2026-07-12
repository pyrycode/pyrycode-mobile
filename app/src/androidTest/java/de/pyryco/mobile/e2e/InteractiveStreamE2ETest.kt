package de.pyryco.mobile.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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

    /**
     * Create-workspace-folder twin of the ping happy path (#566, Layer 3): drive the real
     * create-a-workspace-folder flow end to end against real claude, exercising the already-shipped
     * #564 create wire and #565 recents wire. Long-press the channel-list FAB → Workspace Picker →
     * "Create new folder…" → type a folder name → land in a fresh discussion whose workspace **is**
     * the created folder → send the constrained ping to prove it is a usable live-session workspace →
     * re-open the picker and confirm the folder shows in "Recent".
     *
     * **Reachability (the material difference from #537).** The create affordance is **ungated** — it
     * is *not* behind the `mutationsSupported` gate that hides the rename family, so this scenario is
     * buildable where the #537 rename e2e is not. The FAB long-press → picker → create-row path is the
     * same ungated entry on the live build the operator uses.
     *
     * **Why AC-3 re-opens the picker from the channel list, not the thread.** The thread's in-place
     * picker entry — [de.pyryco.mobile.ui.conversations.components.WorkspaceChip] — is gated on
     * `!state.hasMessages` (`ThreadScreen.kt`), so once the ping reply renders the chip has unmounted and
     * cannot re-open the picker. AC-3 therefore returns to the list via the thread "Back" nav and re-uses
     * the same ungated FAB long-press AC-1 already used. Sequencing AC-3 *after* the ping also means the
     * folder has unambiguously been used by an active session before we assert it in "Recent".
     *
     * Semi-deterministic by nature (real claude): every assertion is **tolerant** — generous timeouts,
     * substring / case-insensitive, never a delta-count or timing assertion. [folderName][FOLDER_NAME_PREFIX]
     * is a runtime-unique string that cannot pre-exist on screen, so a substring match on it (in the chip
     * and in the recents row) is a genuine presence check — the ping/tool tests' token discipline. The
     * unique name also keeps repeated LIVE gate runs green: `~/pyry-workspace` lives on the operator's
     * **real** `$HOME` (#527 isolates the pyry instance name, not `$HOME`) and the gate does not clean
     * between runs, so a fixed name would collide/accumulate.
     */
    @Test
    fun interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace() {
        // 1. A paired launch lands on the channel list. The "New discussion" FAB is the list marker.
        //    Wait for the relay connection to open before creating — the picker's create round-trips to
        //    the daemon, so acting before the session is Open would fail the request.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        awaitConnected()

        // 2. Long-press the FAB to open the Workspace Picker. A *tap* would create a scratch discussion;
        //    the long-press routes to ChannelListEvent.LongPressFab (combinedClickable.onLongClick).
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performTouchInput { longClick() }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CREATE_FOLDER_ROW, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Open the create dialog, type a collision-resistant folder name, and confirm. The name is a
        //    clean single path element (the daemon rejects empty / absolute / separator-bearing / ".."),
        //    unique per run so a substring match on it is a genuine presence check.
        val folderName = FOLDER_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onAllNodesWithText(CREATE_FOLDER_ROW, substring = true).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(folderName)
        composeTestRule.onAllNodesWithText(CREATE_BUTTON).onFirst().performClick()

        // 4. AC-1: creating the folder navigates into a fresh discussion whose cwd is the created folder.
        //    The send button marks the thread; the workspace chip reflects the folder's basename verbatim
        //    ("Workspace: <folderName> (change)"). The chip is still present here — no messages yet.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(folderName, substring = true)
            .onFirst()
            .assertIsDisplayed()

        // 5. AC-2: send the constrained ping in the new workspace and assert the streamed reply renders —
        //    proving the created folder is usable as a live session's workspace against a real claude turn.
        //    Tail reused from the ping scenario; folderName does not contain "ping", so it never perturbs
        //    the count. Snapshot the "ping"-bearing node count, then wait for the reply to add at least one.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { pingNodeCount() >= 1 }
        val baseline = pingNodeCount()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { pingNodeCount() > baseline }
        composeTestRule
            .onAllNodesWithText(PING, substring = true, ignoreCase = true)
            .onFirst()
            .assertIsDisplayed()

        // 6. AC-3: the thread now has messages, so the WorkspaceChip is gone (!hasMessages gate). Re-open
        //    the picker from the channel list — Back to the list, then long-press the FAB again — and assert
        //    the freshly-used folder appears in "Recent" (the recents flow re-fetches cold on every open,
        //    #565). Waiting for the "Recent" header covers the daemon round-trip; the channel-list section
        //    is "Recent discussions", so an exact "Recent" match is unambiguous.
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performTouchInput { longClick() }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RECENT_SECTION).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(folderName, substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * New-session twin of the ping happy path (#541, Layer 3): drive the real "New session" overflow flow
     * end to end against real claude, exercising the already-shipped #540 fire-and-forget wire. With a live,
     * exercised session, open the thread overflow menu → tap "New session" → the daemon runs `/clear` →
     * broadcasts `session_transition` (`reason: "clear"`) → the thread folds a `ThreadItem.SessionBoundary`
     * (#336, canonical in `RemoteConversationRepository`) → `SessionBoundaryDelimiter` renders it. This proves
     * that path against real claude + a real daemon `/clear`, not the boundary the Fake synthesizes.
     *
     * **Reachability.** The "New session" item is gated on `mutationsSupported` only (not promotion), which is
     * `true` in relay mode (PR #572), so the scenario is reachable on a plain **discussion** — the same real
     * overflow menu the operator uses.
     *
     * **Fire-and-forget — assert the durable delimiter, never an ack.** `new_session` is fire-and-forget
     * (pyrycode#831, #540 wire), so the only observable is the post-broadcast delimiter. The load-bearing
     * matcher is [DELIMITER_EXPLANATION], the delimiter's hardcoded explanation line
     * ([de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter]), which can **only** come from
     * the rendered delimiter — it is reason-independent, so the match is robust even if the daemon's
     * `session_transition` reason differs from `clear`. The matcher is deliberately **not** [NEW_SESSION_ITEM]
     * (`"New session"`): that text is byte-identical to both the overflow menu item and the
     * `BoundaryReason.Clear` label prefix, so it is not selective at rung 3 (the #481 `TOOL_PROMPT`-omits-"Bash"
     * / #566 unique-`folderName` token discipline). The delimiter's **absence is asserted before** the
     * New-session tap, so its later appearance is attributable to the action — a deterministic guard, no extra
     * claude turn.
     *
     * **Always-on, not `@Ignore`d.** Unlike #482's transient thinking spinner — which leaves no trace once the
     * turn moves on — the delimiter is a **durable** artifact that survives the turn, so it belongs in the
     * always-on gate, matching #481's durable tool-name row.
     *
     * Total real-claude cost: **one** turn (the ping proving the session is live); `/clear` spends none.
     */
    @Test
    fun interactiveTurn_newSession_rendersSessionBoundaryDelimiter() {
        // 1. A paired launch lands on the channel list. The "New discussion" FAB is the list marker.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Wait for the relay connection to open before creating a conversation.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — the "New session" item is gated on mutationsSupported only, not promotion.
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Prove the session is live (AC-3): send the constrained ping and wait for the streamed reply, so the
        //    session is genuinely exercised and there is de-emphasized above-delimiter content once it clears.
        //    Tail reused verbatim from the ping scenario — this spends the one real claude turn; /clear spends none.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { pingNodeCount() >= 1 }
        val baseline = pingNodeCount()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { pingNodeCount() > baseline }

        // 5. Absence guard (AC-2, deterministic — no extra turn): the delimiter explanation must not be on
        //    screen yet, so its later appearance is attributable to the New-session tap.
        composeTestRule
            .onAllNodesWithText(DELIMITER_EXPLANATION, substring = true)
            .assertCountEquals(0)

        // 6. Drive the REAL overflow menu: open "More actions", wait for the item to render, then tap "New
        //    session". NEW_SESSION_ITEM locates/taps the menu item ONLY — never the durable assertion (its
        //    text collides with the Clear-label prefix; the durable matcher is DELIMITER_EXPLANATION, step 7).
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(NEW_SESSION_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(NEW_SESSION_ITEM).onFirst().performClick()

        // 7. Assert the durable delimiter (AC-1, AC-2): after the daemon's /clear → session_transition
        //    broadcast folds a SessionBoundary, wait for the explanation line to render, then confirm it is on
        //    screen. Tolerant: substring, generous timeout, presence — never a delta count or timing. A
        //    non-empty match can only come from the rendered SessionBoundaryDelimiter (the folded boundary).
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(DELIMITER_EXPLANATION, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(DELIMITER_EXPLANATION, substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * Delete-conversation twin of the ping happy path (#554, Layer 3): drive the real Delete flow end to
     * end against a real daemon, exercising the already-shipped #532 `delete` wire (pyrycode#822). Give a
     * scratch discussion a runtime-unique, list-visible identity via **Rename**, confirm it is **present**
     * on the channel list, then delete it from the thread — thread overflow → "Channel info" → the sheet's
     * "Delete" → the "Delete conversation?" dialog → confirm — and assert **both** durable post-conditions:
     * the unique name is **gone from the list** and the **thread has popped back**.
     *
     * **Reachability.** The Delete affordance lives in the `mutationsSupported`-gated Actions block of the
     * Channel Info sheet, reached from the **ungated** "Channel info" overflow item.
     * [de.pyryco.mobile.data.repository.RemoteConversationRepository.mutationsSupported] is `true` in relay
     * mode (PR #572), so the flow is reachable on a plain **discussion** — the same real overflow the
     * operator uses. Delete is conversation-scoped (keyed by `conversation_id`, replies
     * `conversation_deleted`), so it carries none of the session-scoped blockers that re-park the sibling
     * e2es; it is in the clean-buildable camp with #541 / #566.
     *
     * **Durable identity via Rename, not promote.** A scratch discussion is auto-named server-side, so its
     * name is not test-controlled and asserting one's absence is fragile. Renaming to
     * [CONVERSATION_NAME_PREFIX]` + System.currentTimeMillis()` gives a runtime-unique, list-visible token
     * that cannot pre-exist on screen nor collide with conversations accumulated by prior LIVE gate runs.
     * Rename (not "Save as channel") touches only the name — no dedicated-workspace folder that would
     * accumulate on the operator's real `~/pyry-workspace` across runs (the #566 accumulation problem). The
     * renamed discussion stays a discussion and is #1 in `observeConversations(Discussions)`
     * (`sortedByDescending { lastUsedAt }`, just created) → always inside the visible recents, so its row is
     * guaranteed present.
     *
     * **The absence is a genuine inversion.** [CONVERSATION_NAME_PREFIX]` + …` is unique, so its presence is
     * observed on the list (step 5 assert + step 6 re-enter tap) *before* the delete, and its
     * `assertCountEquals(0)` after (step 9) is a real present→absent flip on the same surface — never a
     * match-everything, never a delta count or timing (the #481 / #566 token discipline, applied to an
     * **absence** assertion).
     *
     * **The "Delete" collision (the one gotcha).** The sheet's Delete `ActionCell` and the confirm dialog's
     * button are **both** the literal `"Delete"`, and `ThreadEvent.Delete` leaves the sheet composed behind
     * the dialog (it sets `pendingDeleteConfirm` without clearing `pendingChannelInfo`), so both "Delete"
     * nodes are on screen at confirm time. The confirm tap is disambiguated by a compound matcher only the
     * dialog's button satisfies — its sibling is [DELETE_DIALOG_CANCEL], which the sheet (whose dismiss is a
     * Close *icon*) has no equivalent of. Never [onFirst] across the two identical "Delete" nodes (z-order
     * is not guaranteed).
     *
     * **Always-on, not `@Ignore`d.** The post-conditions are **durable** structural facts (a conversation is
     * in the list or not; the thread popped or not) — no transient like #482's spinner — so the scenario
     * belongs in the always-on gate, matching #481's tool-name row and #541's delimiter.
     *
     * **Zero real-claude turns (deliberate divergence from #541 / #566).** Create-discussion, rename, and
     * delete are daemon round-trips, not claude turns, and the durable identity is the typed name (no live
     * session content needed to identify it), so this scenario sends **no** ping and spends **no** claude
     * turn. It still rides the real rung-3 stack (real relay + daemon) and belongs in the LIVE gate: it
     * catches a broken `delete` / `rename` wire against the production relay. The LIVE gate is a **quartet**
     * (4 methods) at **still 3 turns** (delete adds a method, not a turn).
     */
    @Test
    fun interactiveTurn_deleteConversation_removesFromListAndClosesThread() {
        // 1. A paired launch lands on the channel list. The "New discussion" FAB is the list marker.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Wait for the relay connection to open before creating — rename/delete round-trip to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Rename" (mutationsSupported) and "Channel info" (ungated) both reach it.
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Rename the discussion to a runtime-unique, list-visible name. Open the overflow, tap "Rename".
        //    The RenameDialog opens OVER the thread, whose composer is also an editable field, so
        //    hasSetTextAction() alone is ambiguous — target the dialog's field by its focus (RenameDialog
        //    auto-focuses on open; the composer never requested focus), waiting for focus to land. REPLACE
        //    the pre-filled+selected auto-name (performTextReplacement, not performTextInput) so the field
        //    holds exactly the unique name, then Save.
        val uniqueName = CONVERSATION_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RENAME_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(RENAME_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(uniqueName)
        composeTestRule.onNodeWithText(RENAME_SAVE).performClick()

        // 5. Presence check (AC-3): back to the list, wait for it, then confirm the unique name is displayed on
        //    a recents row. The rename reply (conversation_updated) upserts → observeConversations re-emits with
        //    the new name; the waitUntil covers that round-trip. This is the genuine presence observation on the
        //    same surface where absence is later asserted (step 9).
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()

        // 6. Re-enter the thread by tapping the recents row (a 2nd presence observation — it can only succeed if
        //    the name is on the list). The merged DiscussionPreviewRow carries the name as text and is clickable.
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 7. Open Channel info → tap the sheet's Delete. "Channel info" is ungated; the sheet's Delete
        //    ActionCell is unique while only the sheet is open. Tapping it opens the confirm dialog OVER the
        //    still-composed sheet (ThreadEvent.Delete leaves pendingChannelInfo true) → two "Delete" nodes.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(DELETE_ACTION).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(DELETE_ACTION).performClick()

        // 8. Confirm the delete. Wait for the dialog's unique title, then tap the CONFIRM "Delete" — the sheet's
        //    "Delete" is also on screen, so disambiguate by the dialog's sibling "Cancel" button (the sheet has
        //    none). If the button-row tree differs on first run, pick another unambiguous anchor rooted at the
        //    dialog title (rung 3 permits selector tuning) — never onFirst() across the two identical "Delete".
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(DELETE_DIALOG_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNode(hasText(DELETE_ACTION) and hasAnySibling(hasText(DELETE_DIALOG_CANCEL)))
            .performClick()

        // 9. Both durable post-conditions (AC-2). After DeleteConfirm → repository.delete → PopBack: wait for the
        //    list marker (the thread has popped back), then assert the unique name is gone from the list. delete
        //    completes (conversation_deleted → removeConversation clears all projections) BEFORE PopBack fires
        //    (sequential in the same coroutine), so the re-projection has landed by the time the list renders →
        //    a direct assertCountEquals(0). Tolerant: presence/absence, generous timeout — never a delta count.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)
    }

    /**
     * Archive/restore round-trip twin of the delete scenario (#551, Layer 3): drive the real Archive and
     * Restore flows end to end against a real daemon, exercising the already-shipped #549 archive/unarchive
     * wire, the #556 archive-from-thread surfacing, and the #557 restore-from-Archive-screen surfacing. Give
     * a scratch discussion a runtime-unique, list-visible identity via **Rename**, confirm it is **present**
     * on the channel list, archive it from the thread (overflow → "Archive", **immediate — no confirm**) and
     * assert it is **gone** from the list, then restore it (settings → "Archived discussions" → the Archived
     * screen's restore affordance) and assert it is **back** in the list. The same unique token flips **out
     * of** and then **back into** the same surface, so each list assertion is a genuine inversion of the other.
     *
     * **Reachability.** The "Archive" overflow item lives in the `mutationsSupported`-gated block
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadOverflowMenu]);
     * [de.pyryco.mobile.data.repository.RemoteConversationRepository.mutationsSupported] is `true` in relay
     * mode (PR #572), so it is reachable on a plain **discussion** — the same real overflow the operator uses.
     * Archive/unarchive are conversation-scoped (keyed by `conversation_id`, reply reuses
     * `conversation_updated`), so they carry none of the session-scoped blockers that re-park the sibling
     * e2es; clean-buildable with #541 / #554 / #566.
     *
     * **Durable identity via Rename** (unchanged from #554). The Archived screen renders **only the display
     * name** ([de.pyryco.mobile.ui.conversations.components.ArchiveRow]), and a scratch discussion is
     * auto-named server-side → it renders as the non-unique fallback "Untitled discussion", un-findable on the
     * Archived screen. Renaming to [ARCHIVE_NAME_PREFIX]` + System.currentTimeMillis()` gives a runtime-unique,
     * list-visible token that survives archive → restore, cannot pre-exist on screen nor collide with prior
     * LIVE-gate leftovers, and makes both the archive **absence** and the restore **presence** assertions
     * genuine inversions.
     *
     * **Round-trip, not one-shot (the divergence from #554).** #554 asserts one direction (delete → absent).
     * This asserts the list flip in **both** directions. Two structural differences from the delete twin:
     * (1) **Archive is immediate — no confirm dialog, no sheet.** The "Archive" item sits directly in the
     * thread overflow and fires `ThreadEvent.Archive → sendArchive → repository.archive → success-only
     * PopBack`; there is **none** of #554's "Delete"-collision / sheet-behind-dialog disambiguation — the
     * archive tap is a single [onNodeWithText] in the open overflow. (2) **Restore needs a second screen:**
     * channel list → Settings → "Archived discussions" → the Archived screen (default **Discussions** tab, so
     * the renamed discussion is on it with no tab tap), restore, then two Back hops to confirm re-appearance.
     *
     * **The one gotcha — the restore-coroutine cancellation race.** `RestoreRequested` handling is
     * `viewModelScope.launch { repository.unarchive(id); … }` scoped to the **Archived screen's**
     * `ArchivedDiscussionsViewModel`. Tapping restore then immediately navigating Back would `popBackStack`
     * that ViewModel and cancel a launched-but-unstarted `unarchive` before it ever sent the request → the
     * conversation would never restore and the closing presence check would flake to a timeout. Step 11's wait
     * for the **"Restored" success snackbar** closes this: the snackbar renders only after `unarchive`
     * returned and `RestoreSucceeded` was sent, so once it is observed the round-trip has fully completed and
     * navigating away is safe. [RESTORED_SNACKBAR] (`"Restored"`) appears in no other on-screen string (the row
     * subtitle is "Archived <time>"; the restore button's content-description is "Restore …", not "Restored"),
     * so a non-empty text match can only be the success snackbar.
     *
     * **Always-on, not `@Ignore`d.** Both post-conditions are **durable** structural facts (a conversation is
     * in the active list or not) — no transient like #482's spinner — so the scenario belongs in the always-on
     * gate, matching #481's tool-name row, #541's delimiter, and #554's delete inversion.
     *
     * **Zero real-claude turns (same as #554).** Create-discussion, rename, archive, and restore are daemon
     * round-trips, not claude turns, and the durable identity is the typed name, so this scenario sends **no**
     * ping and spends **no** claude turn. It still rides the real rung-3 stack (real relay + daemon) and
     * catches a broken `archive` / `unarchive` / `rename` wire against the production relay. The LIVE gate goes
     * from a quartet (4 methods) to a **quintet** (5 methods) at **still 3 turns** — archive/restore adds a
     * method, not a turn.
     */
    @Test
    fun interactiveTurn_archiveRestore_roundTripsListMembership() {
        // 1. A paired launch lands on the channel list. The "New discussion" FAB is the list marker.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Wait for the relay connection to open before creating — rename/archive/restore round-trip to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Rename" and "Archive" are both gated on mutationsSupported, reachable on it.
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Rename the discussion to a runtime-unique, list-visible name (identical to #554 step 4). The
        //    RenameDialog opens OVER the thread, whose composer is also an editable field, so hasSetTextAction()
        //    alone is ambiguous — target the dialog's field by its focus (RenameDialog auto-focuses on open),
        //    REPLACE the pre-filled+selected auto-name (performTextReplacement, not performTextInput), then Save.
        val uniqueName = ARCHIVE_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RENAME_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(RENAME_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(uniqueName)
        composeTestRule.onNodeWithText(RENAME_SAVE).performClick()

        // 5. Presence check #1 (AC-1): back to the list, wait for it, then confirm the unique name is displayed on
        //    a recents row — the genuine presence observation on the surface where absence is later asserted (step 8).
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()

        // 6. Re-enter the thread by tapping the recents row (a 2nd presence observation — it can only succeed if
        //    the name is on the list). Archive is driven "from the thread".
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 7. Archive (immediate — no confirm dialog). Open the overflow, wait for the "Archive" item, tap it:
        //    sendArchive → repository.archive → success-only PopBack. "Archive" is unique in the open overflow.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ARCHIVE_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(ARCHIVE_ITEM).performClick()

        // 8. Absence check (AC-1). After PopBack: wait for the list marker (the thread has popped back), then
        //    assert the unique name is gone from the active list — a genuine inversion of step 5. archive folds
        //    the conversation out of the active projection on the repo's demux loop before PopBack renders the
        //    list, so a direct assertCountEquals(0). Tolerant: presence/absence, generous timeout — never a delta count.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)

        // 9. Navigate to the Archived screen: tap the channel-list settings button, wait for the Settings marker
        //    "Archived discussions", tap it, then wait for the Archived-screen top-bar title "Archived". The
        //    default tab is Discussions → the seeded (renamed) discussion is on it, so no tab tap is needed.
        composeTestRule.onNode(hasContentDescription(CD_OPEN_SETTINGS)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ARCHIVED_ROW).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(ARCHIVED_ROW).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).fetchSemanticsNodes().isNotEmpty()
        }

        // 10. Restore. Wait for the restore affordance keyed on the unique name — the "Restore <uniqueName>"
        //     IconButton (the row name is a Text node, so only the restore button matches a content-description
        //     search) — a presence observation on the Archived screen, then tap it: RestoreRequested →
        //     repository.unarchive.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(uniqueName, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasContentDescription(uniqueName, substring = true)).onFirst().performClick()

        // 11. Restore-completed guard (the one gotcha — see KDoc). Wait for the "Restored <name>" success
        //     snackbar BEFORE navigating back: it renders only after repository.unarchive returned and
        //     RestoreSucceeded was sent, so the ArchivedDiscussionsViewModel-scoped restore coroutine is not
        //     cancelled mid-flight by the return-nav's popBackStack.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RESTORED_SNACKBAR, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 12. Navigate back to the channel list. Two Back hops: Archived → Settings, then Settings → list. The
        //     waitUntil(ARCHIVED_ROW) between them lets Compose idle so the Archived screen is fully torn down
        //     and only Settings' single "Back" node exists before the 2nd tap (both screens' nav icon is "Back").
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ARCHIVED_ROW).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }

        // 13. Presence check #2 (AC-2 — round-trip closes). Wait for the unique name on the active list, then
        //     confirm it is displayed. The re-appearance is attributable to the restore (asserted absent in
        //     step 8), on the same surface, same unique token.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * Change-workspace twin of the create-workspace-folder scenario (#562, Layer 3): drive the real
     * "Change workspace…" overflow flow end to end against a real daemon, exercising the already-shipped
     * #560 `change_workspace` wire and #561 surfacing. Create a plain discussion, then via the **real**
     * thread overflow "Change workspace…" → Workspace Picker → "Create new folder…" → a runtime-unique
     * folder name, complete a `change_workspace` round-trip to that new target path, and assert the
     * conversation's recorded workspace durably flips to it — read off the [WorkspaceChip] (`"Workspace:
     * <newWorkspace> (change)"`, the recorded `cwd` basename).
     *
     * **Reachability.** The "Change workspace…" item lives in the `mutationsSupported`-gated block of the
     * overflow ([de.pyryco.mobile.ui.conversations.thread.ThreadOverflowMenu]) and is **not**
     * promotion-gated; [de.pyryco.mobile.data.repository.RemoteConversationRepository.mutationsSupported]
     * is `true` in relay mode (PR #572), so it is reachable on a plain **discussion** — the same real
     * overflow the operator uses. `change_workspace` is **conversation-scoped** (keyed by `conversation_id`,
     * a line-for-line mirror of `rename`, **no** session transition), so it carries none of the
     * session-scoped `currentSessionId == ""` blocker that re-parks the settings e2e (#545); clean-buildable
     * with #541 / #554 / #551 / #566.
     *
     * **Assert the recorded cwd, not a session id.** Because `change_workspace` performs no session
     * transition, the durable post-condition is the recorded workspace, so the assertion targets the
     * `WorkspaceChip` (the `cwd` basename) — it deliberately does **not** reintroduce the session-scoped
     * dependency that parks #545. The chip is the assertion surface **only because no message is sent:** it
     * is gated `!isPromoted && !hasMessages` ([de.pyryco.mobile.ui.conversations.thread.ThreadScreen]), and
     * this scenario spends no claude turn (no ping), so `hasMessages` stays false and the chip stays mounted
     * throughout.
     *
     * **The one field-disambiguation gotcha (unlike #566).** #566 opens the picker over the **channel
     * list** (no editable field), so `onNode(hasSetTextAction())` is unambiguous there. Here the picker
     * opens over the **thread**, whose composer ([de.pyryco.mobile.ui.conversations.thread.ThreadInputBar])
     * is also an editable field, so once the [CreateFolderDialog] opens **two** `hasSetTextAction()` nodes
     * are on screen. The dialog auto-focuses its field on open (`focusRequester.requestFocus()`) and the
     * composer never requests focus, so `hasSetTextAction() and isFocused()` selects the dialog's field —
     * the same disambiguation #554 uses for RenameDialog-over-thread. (Empty field → `performTextInput`,
     * not `performTextReplacement`.)
     *
     * **Two sequential daemon round-trips, one wait (the correctness note).** The Create tap chains
     * `createWorkspaceFolder` (returns the canonical path) → `onWorkspacePicked` → `sendChangeWorkspace` →
     * `changeWorkspace` → `conversation_updated` → the projection re-emits with the new `cwd` → the chip
     * re-labels. Step 6's single `waitUntil` spans **both** round-trips ([THREAD_TIMEOUT_MS] comfortably
     * covers them over `wss://`; bump only if the live relay proves slow on first operator run — rung 3
     * permits timeout tuning).
     *
     * **The unique name's only post-Create on-screen home is the chip — no transient false match.** [onCreate]
     * ([de.pyryco.mobile.ui.conversations.components.WorkspacePicker]) sets `showCreateDialog = false`
     * **synchronously before** the suspend, so the dialog's text field (which held [newWorkspace][WORKSPACE_FOLDER_PREFIX])
     * is gone the instant Create is tapped; the picker sheet then closes on `onPicked`; and the just-created
     * folder is not yet in the picker's "Recent" (a folder becomes recent only once used). So `onFirst()`
     * unambiguously lands on the chip.
     *
     * **The before → after inversion.** [newWorkspace][WORKSPACE_FOLDER_PREFIX] is `"e2e562-" +
     * System.currentTimeMillis()` — runtime-unique, so its **absence is asserted before** the change (step 3,
     * a deterministic guard, no claude turn) and its appearance in the chip after (step 6) is attributable to
     * the change. The unique suffix also keeps repeated LIVE gate runs green: each run creates one folder
     * under the operator's real `~/pyry-workspace` (the #566 accumulation pattern), and a fixed name would
     * collide with folders left by prior runs.
     *
     * **Always-on, not `@Ignore`d.** The recorded cwd is a **durable** fact (the chip re-label survives the
     * turn) — no transient like #482's spinner — so the scenario belongs in the always-on gate, matching
     * #481's tool-name row, #541's delimiter, and #554's / #551's list inversions. **Zero real-claude turns**
     * (like #554 / #551): create-folder and change-workspace are daemon round-trips, not claude turns. The
     * LIVE gate goes from a quintet (5 methods) to a **sextet** (6 methods) at **still 3 turns**.
     */
    @Test
    fun interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace() {
        // 1. A paired launch lands on the channel list. The "New discussion" FAB is the list marker. Wait
        //    for the relay connection to open before creating — the picker's create + change round-trip to
        //    the daemon, so acting before the session is Open would fail the request.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        awaitConnected()

        // 2. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Change workspace…" is mutationsSupported-gated only, reachable on it.
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Absence guard (the before-state, deterministic — no claude turn): the runtime-unique target
        //    name is not on screen yet (the chip shows the discussion's scratch workspace), so its later
        //    appearance in the chip is attributable to the change_workspace round-trip.
        val newWorkspace = WORKSPACE_FOLDER_PREFIX + System.currentTimeMillis()
        composeTestRule.onAllNodesWithText(newWorkspace, substring = true).assertCountEquals(0)

        // 4. Drive the REAL overflow: open "More actions", wait for the "Change workspace…" item (matched as
        //    a substring — the production string ends in a real U+2026 ellipsis), then tap it. The picker
        //    sheet opens (pendingWorkspacePicker = true), the same one the WorkspaceChip opens.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CHANGE_WORKSPACE_ITEM, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CHANGE_WORKSPACE_ITEM, substring = true).onFirst().performClick()

        // 5. Open the create dialog, type the collision-resistant folder name, and confirm. The dialog opens
        //    OVER the thread, whose composer is also an editable field, so hasSetTextAction() alone is
        //    ambiguous — target the dialog's field by its focus (CreateFolderDialog auto-focuses on open; the
        //    composer never requested focus), waiting for focus to land. The empty field → performTextInput.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CREATE_FOLDER_ROW, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CREATE_FOLDER_ROW, substring = true).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextInput(newWorkspace)
        composeTestRule.onAllNodesWithText(CREATE_BUTTON).onFirst().performClick()

        // 6. After-state (the durable post-condition). The single wait spans BOTH sequential daemon
        //    round-trips (create_workspace_folder → change_workspace); on success the chip re-labels to
        //    "Workspace: <newWorkspace> (change)" (the recorded cwd basename), so a non-empty match on the
        //    unique name can only be the chip. Tolerant: substring, generous timeout, presence — a genuine
        //    inversion of step 3's absence on the same surface.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(newWorkspace, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(newWorkspace, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * Rename-conversation twin of the delete / change-workspace scenarios (#537, Layer 3): drive the real
     * Rename flow end to end against a real daemon, exercising the already-shipped #530 `rename` wire
     * (pyrycode#820). The four shipped siblings (#541 / #551 / #554 / #562) already drive the same
     * `RenameDialog` as a **seeding** step; this promotes rename from a seed to the **subject** of its own
     * scenario — only the assertion target changes. Create a scratch discussion, rename it to a
     * runtime-unique title, submit, and assert the new title appears **durably** on **two** surfaces after the
     * round-trip: the **thread top bar** (in-thread, immediately after submit) and the **conversation list**
     * (after popping back).
     *
     * **Reachability.** The "Rename" overflow item lives in the `mutationsSupported`-gated block
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadOverflowMenu]) and is **not** promotion-gated;
     * [de.pyryco.mobile.data.repository.RemoteConversationRepository.mutationsSupported] is `true` in relay
     * mode (PR #572), so it is reachable on a plain **discussion** — the same real overflow the operator uses.
     * `rename` is **conversation-scoped** (keyed by `conversation_id`, a line-for-line mirror of
     * `change_workspace` and `delete`, **no** session transition), so it carries none of the session-scoped
     * `currentSessionId == ""` blocker that re-parks the settings e2e (#545); clean-buildable with
     * #541 / #554 / #551 / #562.
     *
     * **Assert the recorded name, not a session id.** Because `rename` performs no session transition, the
     * durable post-condition is the recorded conversation **name**. Surface #1 is the thread top bar
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadScreen] `title = state.displayName`), reached
     * **immediately after submit** — unlike delete/archive there is **no PopBack** (`RenameSubmit` dismisses
     * the dialog and the thread stays open, [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel]), so
     * the top bar re-labels in place after `conversation_updated` folds into thread state. Surface #2 is the
     * conversation-list recents row, reached after tapping Back — the same fold upserts the list projection.
     * Neither surface is a session id (the #545 lesson).
     *
     * **The RenameDialog-over-thread field disambiguation (the one gotcha).** `RenameDialog` opens **over**
     * the thread, whose composer is also an editable field, so `hasSetTextAction()` alone is ambiguous — two
     * nodes. The dialog auto-focuses its field (`focusRequester.requestFocus()`) and the composer never
     * requests focus, so `hasSetTextAction() and isFocused()` selects the dialog's field (`and` is a
     * `SemanticsMatcher` member — no import). The field is **pre-filled with the server auto-name and fully
     * selected** → `performTextReplacement` (not `performTextInput`, which could leave the auto-name
     * concatenated). This is verbatim the selector the four siblings use for their rename seed.
     *
     * **No top-bar false match.** After Save, `RenameSubmit` flips `showRenameDialog` false synchronously, so
     * the dialog (whose field held [uniqueName][RENAME_NAME_PREFIX]) leaves composition before the round-trip
     * lands; the top bar still shows the old auto-name until then. The two never hold the unique name
     * simultaneously, so step 6's first match is the top bar.
     *
     * **Always-on, not `@Ignore`d.** Both post-conditions are **durable** structural facts (the recorded name
     * on two surfaces) — no transient like #482's spinner — so the scenario belongs in the always-on gate,
     * matching #481's tool-name row, #541's delimiter, and #554's / #562's inversions. **Zero real-claude
     * turns** (like #554 / #562): create-discussion and rename are daemon round-trips, not claude turns, and
     * the durable identity is the typed name, so this scenario sends **no** ping. The LIVE gate goes from a
     * sextet (6 methods) to a **septet** (7 methods) at **still 3 turns** — rename adds a method, not a turn.
     */
    @Test
    fun interactiveTurn_renameConversation_relabelsTopBarAndListRow() {
        // 1. A paired launch lands on the channel list. The "New discussion" FAB is the list marker.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Wait for the relay connection to open before creating — rename round-trips to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Rename" is mutationsSupported-gated only, reachable on it.
        composeTestRule.onNode(hasContentDescription(CD_NEW_DISCUSSION)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Absence guard (the before-state, deterministic — no claude turn): the runtime-unique target title
        //    is not on screen yet (the top bar shows the server auto-name), so its later appearance is
        //    attributable to the rename round-trip. Same guard as #562 step 3 / #554 step 9's inversion.
        val uniqueName = RENAME_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)

        // 5. Rename to the unique title (identical drive to #554 step 4). Open the overflow, tap "Rename". The
        //    RenameDialog opens OVER the thread, whose composer is also an editable field, so hasSetTextAction()
        //    alone is ambiguous — target the dialog's field by its focus (RenameDialog auto-focuses on open; the
        //    composer never requested focus), waiting for focus to land. REPLACE the pre-filled+selected
        //    auto-name (performTextReplacement, not performTextInput) so the field holds exactly the unique
        //    name, then Save.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RENAME_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(RENAME_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(uniqueName)
        composeTestRule.onNodeWithText(RENAME_SAVE).performClick()

        // 6. Top-bar assertion (AC-2, surface #1 — in-thread). This wait spans the rename round-trip
        //    (conversation_updated → state.displayName); there is NO PopBack, so the thread stays open and the
        //    top bar re-labels in place. The dialog has already left composition (Save flips showRenameDialog
        //    false synchronously), so the match is the top bar Text, not the dismissing field. Tolerant:
        //    substring, generous timeout, presence.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()

        // 7. List assertion (AC-2, surface #2 — after popping back). Tap Back, wait for the list marker (the
        //    thread has popped back), then wait for the unique name on a recents row and confirm it is
        //    displayed. The same conversation_updated fold upserts the list projection → observeConversations
        //    re-emits with the new name; the waitUntil covers that round-trip. A genuine inversion of step 4's
        //    absence, on the list surface, same unique token.
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()
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
        //   cd_new_discussion = "New discussion", cd_send_message = "Send message", cd_back = "Back".
        const val CD_NEW_DISCUSSION = "New discussion"
        const val CD_SEND_MESSAGE = "Send message"
        const val CD_BACK = "Back"

        // #541 new-session scenario. Overflow-menu production strings (no test tags): CD_MORE_ACTIONS opens
        // the menu; NEW_SESSION_ITEM is the tap target ONLY — its text is byte-identical to the delimiter's
        // BoundaryReason.Clear label prefix, so it is NOT selective at rung 3. The load-bearing DURABLE matcher
        // is DELIMITER_EXPLANATION, the delimiter's reason-independent hardcoded explanation line, which can
        // only come from the rendered SessionBoundaryDelimiter. Keep in sync with res/values/strings.xml:
        //   cd_more_actions = "More actions", thread_overflow_new_session = "New session".
        const val CD_MORE_ACTIONS = "More actions"
        const val NEW_SESSION_ITEM = "New session"
        const val DELIMITER_EXPLANATION = "Claude doesn't remember messages above this line"

        // #566 create-workspace-folder scenario. Picker/dialog production strings (no test tags):
        //   the WorkspacePickerSheet create row (matched as a substring so the trailing ellipsis need
        //   not be reproduced), the CreateFolderDialog confirm button, and the picker's "Recent" header.
        const val CREATE_FOLDER_ROW = "Create new folder under pyry-workspace"
        const val CREATE_BUTTON = "Create"
        const val RECENT_SECTION = "Recent"

        // Collision-resistant folder-name prefix: a clean single path element (lowercase alphanumerics +
        // dash — the daemon rejects empty / absolute / separator-bearing / ".." names). Suffixed with
        // System.currentTimeMillis() at runtime so repeated LIVE gate runs never collide under the
        // operator's real ~/pyry-workspace (#527 isolates the pyry instance name, not $HOME).
        const val FOLDER_NAME_PREFIX = "e2e566-"

        // #562 change-workspace scenario. Reuses the #566 picker/dialog constants (CREATE_FOLDER_ROW,
        // CREATE_BUTTON) and the overflow opener (CD_MORE_ACTIONS); adds only these two. CHANGE_WORKSPACE_ITEM
        // is the overflow item, matched as a SUBSTRING — the production string is "Change workspace…" with a
        // real U+2026 ellipsis (mirrors how CREATE_FOLDER_ROW drops the trailing ellipsis). Keep in sync with
        // res/values/strings.xml: thread_overflow_change_workspace = "Change workspace…".
        const val CHANGE_WORKSPACE_ITEM = "Change workspace"

        // Runtime-unique target-folder prefix: "e2e562-" + System.currentTimeMillis(). Distinct from #566's
        // FOLDER_NAME_PREFIX (the shared companion forbids redeclaration). A clean single path element
        // (lowercase alphanumerics + dash — the daemon rejects empty / absolute / separator-bearing / ".."
        // names). Unique so a substring match cannot pre-exist on screen — the absence guard (step 3) and its
        // inversion, the chip presence after change_workspace (step 6), are both genuine; also keeps repeated
        // LIVE gate runs clean under the operator's real ~/pyry-workspace (no collision/accumulation).
        const val WORKSPACE_FOLDER_PREFIX = "e2e562-"

        // #554 delete-conversation scenario. Overflow / sheet / dialog production strings (no test tags).
        // RENAME_ITEM + RENAME_SAVE drive the rename that gives the seeded discussion a runtime-unique,
        // list-visible identity; CHANNEL_INFO_ITEM (ungated) opens the sheet whose Actions block holds the
        // Delete affordance. DELETE_ACTION is the ONE gotcha: the sheet's ActionCell label AND the confirm
        // dialog's button are BOTH the literal "Delete", and ThreadEvent.Delete leaves the sheet composed
        // behind the dialog, so both nodes are on screen at confirm time — the confirm tap is disambiguated
        // by DELETE_DIALOG_CANCEL, the dialog's sibling button the sheet has no equivalent of.
        // DELETE_DIALOG_TITLE is the unique wait anchor for the opened dialog. Keep in sync with
        // res/values/strings.xml: thread_overflow_rename = "Rename", rename_dialog_save = "Save",
        // thread_overflow_channel_info = "Channel info", delete_dialog_confirm = "Delete" (== the sheet's
        // ActionCell literal), delete_dialog_title = "Delete conversation?", delete_dialog_cancel = "Cancel".
        const val RENAME_ITEM = "Rename"
        const val RENAME_SAVE = "Save"
        const val CHANNEL_INFO_ITEM = "Channel info"
        const val DELETE_ACTION = "Delete"
        const val DELETE_DIALOG_TITLE = "Delete conversation?"
        const val DELETE_DIALOG_CANCEL = "Cancel"

        // Runtime-unique rename target: "e2e554-" + System.currentTimeMillis(). Unique so a substring match
        // cannot pre-exist on screen — the presence check (step 5) and its inversion, assertCountEquals(0)
        // after delete (step 9), are both genuine. #566 unique-folderName / #481 token-omission discipline,
        // applied here to an ABSENCE assertion. Also keeps repeated LIVE gate runs clean (no accumulation).
        const val CONVERSATION_NAME_PREFIX = "e2e554-"

        // #551 archive/restore round-trip scenario. Archive from the thread overflow (ARCHIVE_ITEM,
        // mutationsSupported-gated) is IMMEDIATE — no confirm dialog, unlike #554's DELETE_ACTION. Restore
        // navigates channel list → Settings: CD_OPEN_SETTINGS is the list top-bar settings button;
        // ARCHIVED_ROW is the Settings row that opens the Archived screen AND doubles as the Settings-screen
        // return-nav marker; ARCHIVED_TITLE is the Archived-screen top-bar arrival anchor; RESTORED_SNACKBAR
        // is the restore-completion guard (a prefix of "Restored %1$s", appearing in no other on-screen
        // string). The restore affordance is keyed on the unique name via its content-description ("Restore
        // <name>"), so it needs no constant. Keep in sync with res/values/strings.xml:
        //   thread_overflow_archive = "Archive", cd_open_settings = "Open settings",
        //   archived_discussions_settings_row = "Archived discussions", archived_title = "Archived",
        //   restored_snackbar = "Restored %1$s".
        const val ARCHIVE_ITEM = "Archive"
        const val CD_OPEN_SETTINGS = "Open settings"
        const val ARCHIVED_ROW = "Archived discussions"
        const val ARCHIVED_TITLE = "Archived"
        const val RESTORED_SNACKBAR = "Restored"

        // Runtime-unique rename target: "e2e551-" + System.currentTimeMillis(). Distinct from #554's
        // CONVERSATION_NAME_PREFIX (the shared companion forbids redeclaration). Unique so a substring match
        // cannot pre-exist on screen — the presence check (step 5), its inversion after archive (step 8), and
        // the re-appearance after restore (step 13) are all genuine; also keeps repeated LIVE gate runs clean.
        const val ARCHIVE_NAME_PREFIX = "e2e551-"

        // #537 rename-conversation scenario. Reuses the #554 rename constants (RENAME_ITEM, RENAME_SAVE) and
        // the overflow opener (CD_MORE_ACTIONS); adds only this prefix. Runtime-unique rename target:
        // "e2e537-" + System.currentTimeMillis(). Distinct prefix (the shared companion forbids redeclaration;
        // each scenario owns its own). Unique so a substring match cannot pre-exist on screen — the absence
        // guard (step 4) and its inversions on the top bar (step 6) and the list row (step 7) are all genuine;
        // also keeps repeated LIVE gate runs green (no collision with titles left by prior runs) and does not
        // collide as a substring with top-bar / list chrome the assertion also matches.
        const val RENAME_NAME_PREFIX = "e2e537-"

        const val LIST_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val THREAD_TIMEOUT_MS = 30_000L

        // Generous: a real claude turn over the relay can take many seconds end to end.
        const val REPLY_TIMEOUT_MS = 90_000L
    }
}

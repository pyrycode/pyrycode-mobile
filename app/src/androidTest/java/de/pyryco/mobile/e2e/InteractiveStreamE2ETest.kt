package de.pyryco.mobile.e2e

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.e2e.E2eTestApplication.Companion.ARG_RELAY_URL
import de.pyryco.mobile.e2e.E2eTestApplication.Companion.ARG_SERVER_ID
import de.pyryco.mobile.e2e.E2eTestApplication.Companion.ARG_SERVER_STATIC_PUBLIC_KEY
import de.pyryco.mobile.ui.components.CHANNEL_PROMPT_FIELD_TAG
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.RUNNING_MODEL_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.treeHostAddTestTag
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.TREE_CHANNEL_ROW_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.TREE_CHAT_ROW_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.PING_PROMPT
import de.pyryco.mobile.ui.conversations.thread.SESSION_BOUNDARY_EXPLANATION
import de.pyryco.mobile.ui.conversations.thread.awaitDisplayedPingReply
import de.pyryco.mobile.ui.conversations.thread.awaitDisplayedSessionBoundary
import de.pyryco.mobile.ui.conversations.thread.pingReplyMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
 * Semi-deterministic by nature (real claude): assertions use generous timeouts and case-insensitive
 * text matching. Ping matches the exact constrained reply in the message list. The "reply with
 * exactly: ping" framing is what keeps real claude's output predictable enough to assert against while
 * still exercising the whole real path.
 *
 * Most Compose selectors are text / content-description based; if those UI strings change, update the
 * constants below. The exceptions are the handles the list screen authors for the device suites: the
 * arrival marker [awaitChannelList] keys on (#736) and the tier tags the promote scenario reads (#731).
 * Those survive chrome changes the strings do not — which is why arrival and creation are three shared
 * helpers here rather than repeated in every scenario.
 */
@RunWith(AndroidJUnit4::class)
class InteractiveStreamE2ETest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /**
     * #586: fails **any** scenario in this class during which the daemon reported a claude message kind
     * its parser could not map. Declaring it is the entire per-class cost — a ninth scenario added
     * tomorrow inherits the guard with no line to remember. Red does not mean broken; it means the
     * daemon's measured ignore-list needs re-taking. See [UnrecognizedRowSentinel].
     *
     * The sentinel can only fire when the resolved interactive runner is stream-json, which is where the
     * `unrecognized_message` emitter lives. On `LIVE=1` that comes from the operator's real
     * `~/.pyry/config.json` (`INTERACTIVE_RUNNER` is refused in preflight), and #614's
     * `interactive runner: <runner> (<reason>)` line prints it before every daemon spawn — a known,
     * visible condition, deliberately not engineered around.
     */
    @get:Rule
    val unrecognizedRowSentinel = UnrecognizedRowSentinel()

    // The thinking spinner's content-description (production UI string, no test tags). Copied from
    // DeterministicInteractiveStreamE2ETest (the rung-4 twin). Keep in sync with res/values/strings.xml:
    //   cd_thread_thinking = "Agent is thinking".
    private val thinkingDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_thinking)

    // The queued row's state description and its drop control (#849), production strings from resources:
    //   thread_queued_state_desc = "Waiting to send", cd_thread_queued_drop = "Drop this queued message".
    private val queuedStateDescription: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_queued_state_desc)
    private val queuedDropDescription: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_queued_drop)

    // #891: the footer's Status-sheet opener and the running-model row's unavailable note, from resources.
    private val statusExpandDescription: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_status_expand)
    private val runningModelUnavailable: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.status_sheet_running_model_unavailable)

    @Test
    fun interactiveTurn_pingPrompt_streamsPingReplyIntoThread() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating a conversation — createDiscussion
        //    round-trips to the daemon, so tapping before the session is Open would fail the send.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread. The send button (only on
        //    the thread) is the marker that we have arrived.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Type the constrained prompt into the only editable field, then send.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        // 5. Match the displayed reply itself; queued prompt removal cannot offset this signal.
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
    }

    /**
     * #891: after one real turn, the Status sheet's running-model row shows what claude announced on its
     * `system/init` line (`model_announced`). Asserts only that the row carries a non-empty value and not
     * the unavailable note — the model name depends on the operator's claude and is never hard-coded.
     */
    @Test
    fun interactiveTurn_pingPrompt_statusSheetShowsRunningModel() {
        awaitChannelList()
        awaitConnected()
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

        composeTestRule.onNode(hasContentDescription(statusExpandDescription)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(RUNNING_MODEL_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }

        val shown =
            composeTestRule
                .onNode(hasTestTag(RUNNING_MODEL_TEST_TAG))
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
        assertTrue("running-model row is empty", shown.isNotBlank())
        assertNotEquals(runningModelUnavailable, shown)
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
        awaitChannelList()
        awaitConnected()
        createChat()
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
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating a conversation.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival.
        createChat()
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
        awaitChannelList()
        awaitConnected()
        createChat()
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
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating a conversation.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival.
        createChat()
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
     * #564 create wire and #565 recents wire. Long-press the host row's add control → Add workspace
     * (#904) → "Create new folder…" → type a folder name → the folder is selected → OK → land in a fresh
     * discussion whose workspace **is** the created folder → send the constrained ping to prove it is a usable live-session workspace →
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
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        //    Wait for the relay connection to open before creating — the picker's create round-trips to
        //    the daemon, so acting before the session is Open would fail the request.
        awaitChannelList()
        awaitConnected()

        // 2. Open the Workspace Picker — the long-press path, not the tap, which would create a scratch
        //    discussion instead. The helper waits for the control it drives; see its KDoc.
        openWorkspacePicker()
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

        // 3b. Add workspace (#904): the created folder becomes the modal's selection and starts nothing.
        //     OK enables once the folder is selected and the host reads connected; OK starts the chat.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasText(OK_BUTTON) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(folderName, substring = true).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(OK_BUTTON) and isEnabled()).onFirst().performClick()

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
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

        // 6. AC-3: the thread now has messages, so the WorkspaceChip is gone (!hasMessages gate). Re-open
        //    the picker from the channel list — Back to the list, then the same long-press again — and assert
        //    the freshly-used folder appears in "Recent" (the recents flow re-fetches cold on every open,
        //    #565). Waiting for the "Recent" header covers the daemon round-trip; an exact "Recent" match is
        //    unambiguous — more so since #731, which took the list's own "Recent discussions" header away.
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        awaitChannelList()
        openWorkspacePicker()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RECENT_SECTION).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(folderName, substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * New-session twin of the ping happy path (#541, Layer 3): drive the real "Reset session" overflow flow
     * end to end against real claude, exercising the already-shipped #540 fire-and-forget wire. With a live,
     * exercised session, open the thread overflow menu → tap "Reset session" → the daemon wraps up and rotates →
     * broadcasts `session_transition` (`reason: "clear"`) → the thread folds a `ThreadItem.SessionBoundary`
     * (#336, canonical in `RemoteConversationRepository`) → `SessionBoundaryDelimiter` renders it. This proves
     * that path against real claude and the daemon's reset, not the boundary the Fake synthesizes.
     *
     * **Reachability.** The "Reset session" item is gated on `mutationsSupported` only (not promotion), which is
     * `true` in relay mode (PR #572), so the scenario is reachable on a plain **discussion** — the same real
     * overflow menu the operator uses.
     *
     * **Fire-and-forget — assert the durable delimiter, never an ack.** `new_session` is fire-and-forget
     * (pyrycode#831, #540 wire), so the only observable is the post-broadcast delimiter. The load-bearing
     * matcher is [DELIMITER_EXPLANATION], the delimiter's hardcoded explanation line
     * ([de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter]), which can **only** come from
     * the rendered delimiter — it is reason-independent, so the match is robust even if the daemon's
     * `session_transition` reason differs from `clear`. [NEW_SESSION_ITEM] only selects the reset action;
     * the delimiter explanation proves the resulting session boundary. Its absence is asserted before
     * the reset tap, so its later appearance is attributable to the action — no extra
     * claude turn.
     *
     * **Always-on, not `@Ignore`d.** Unlike #482's transient thinking spinner — which leaves no trace once the
     * turn moves on — the delimiter is a **durable** artifact that survives the turn, so it belongs in the
     * always-on gate, matching #481's durable tool-name row.
     *
     * Real-claude cost: the ping turn plus the daemon's reset wrap-up turn when handoff notes are enabled.
     */
    @Test
    fun interactiveTurn_newSession_rendersSessionBoundaryDelimiter() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating a conversation.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — the "Reset session" item is gated on mutationsSupported only, not promotion.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Prove the session is live (AC-3): send the constrained ping and wait for the streamed reply, so the
        //    session is genuinely exercised and there is de-emphasized above-delimiter content once it clears.
        //    The daemon may run a separate wrap-up turn after the New-session tap.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

        // 5. Absence guard (AC-2, deterministic — no extra turn): the delimiter explanation must not be on
        //    screen yet, so its later appearance is attributable to the New-session tap.
        composeTestRule
            .onAllNodesWithText(DELIMITER_EXPLANATION, substring = true)
            .assertCountEquals(0)

        // 6. Open the real overflow menu and tap Reset session. The durable assertion uses
        //    DELIMITER_EXPLANATION, independently of the action label.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(NEW_SESSION_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(NEW_SESSION_ITEM).onFirst().performClick()

        // 7. Reveal the newest row while waiting: the daemon's wrap-up reply can fill the viewport
        //    before session_transition appends the delimiter. The explanation must still be displayed.
        composeTestRule.awaitDisplayedSessionBoundary(REPLY_TIMEOUT_MS)
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
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating — rename/delete round-trip to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Rename" (mutationsSupported) and "Channel info" (ungated) both reach it.
        createChat()
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
        awaitChannelList()
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
        awaitChannelList()
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
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating — rename/archive/restore round-trip to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Rename" and "Archive" are both gated on mutationsSupported, reachable on it.
        createChat()
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
        awaitChannelList()
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
        awaitChannelList()
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)

        // 9. Navigate to the Archived screen: tap the channel-list settings button, wait for the Settings marker
        //    "Archived discussions", tap it, then wait for the Archived-screen top-bar title "Archived". The
        //    default tab is Discussions → the seeded (renamed) discussion is on it, so no tab tap is needed.
        composeTestRule.onNode(hasContentDescription(CD_OPEN_SETTINGS)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ARCHIVED_ROW).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNodeWithText(ARCHIVED_ROW)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
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
        awaitChannelList()

        // 13. Presence check #2 (AC-2 — round-trip closes). Wait for the unique name on the active list, then
        //     confirm it is displayed. The re-appearance is attributable to the restore (asserted absent in
        //     step 8), on the same surface, same unique token.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * The list's own archive entry reaches the Archived screen (#740). #737 put two entries on the channel
     * list's bar; [interactiveTurn_archiveRestore_roundTripsListMembership] travels the settings one (list →
     * Settings → Archived), and this travels the archive one, which otherwise is proven only at the event
     * boundary (`ChannelListScreenTest.archiveEntry_emitsArchiveTapped`).
     *
     * The bar is drawn on every state of the list, so the scenario needs no connection wait, no seeded
     * conversation, no prompt and no claude turn. The list draws no "Archived" text, so [ARCHIVED_TITLE] is
     * asserted absent before the tap and the arrival after it is a genuine inversion.
     */
    @Test
    fun interactiveTurn_listArchiveEntry_opensArchived() {
        awaitChannelList()
        composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).assertCountEquals(0)

        composeTestRule.onNode(hasContentDescription(CD_OPEN_ARCHIVE)).performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).onFirst().assertIsDisplayed()
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
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736). Wait
        //    for the relay connection to open before creating — the picker's create + change round-trip to
        //    the daemon, so acting before the session is Open would fail the request.
        awaitChannelList()
        awaitConnected()

        // 2. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Change workspace…" is mutationsSupported-gated only, reachable on it.
        createChat()
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
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating — rename round-trips to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Rename" is mutationsSupported-gated only, reachable on it.
        createChat()
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
        awaitChannelList()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * Save-as-channel (promote) twin of the rename scenario (#581, Layer 3): drive the real "Save as channel…"
     * overflow flow end to end against a real daemon, exercising the already-shipped #348 `promote` wire. This
     * is the **backfill** member of the family — save-as-channel shipped *before* the real-stack
     * definition-of-done rule (pyrycode-mobile-agents#9), and the gap was not theoretical: the daemon never
     * registered the `promote_conversation` handler, so the verb answered `unsupported` over the real wire and
     * **the promote never happened**, while the mobile suite stayed green against a fake daemon that answers
     * anything. pyrycode/pyrycode#949 landed the handler; this is the remaining mobile client half (the desktop
     * parallel is pyrycode-desktop#430). **Expected RED on any daemon older than #949 — that is the regression
     * it exists to catch.**
     *
     * **Reachability.** The "Save as channel…" item is gated on the conversation being **unpromoted**
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadOverflowMenu] `if (!isPromoted)`) and is **not**
     * `mutationsSupported`-gated (unlike #537's "Rename"), so a freshly created discussion reaches it
     * regardless of the capability flag. `promote` is **conversation-scoped** (**no** session transition), so it
     * carries none of the session-scoped `currentSessionId == ""` blocker that re-parks the settings e2e
     * (#545); clean-buildable with #541 / #551 / #554 / #562 / #566 / #537.
     *
     * **Wire shape.** `promote` is a **request/reply** verb whose reply is the **bare conversation object**,
     * folded by a confirmed upsert — it is **not** a `conversation_updated` broadcast (that is *rename's*
     * shape, from #530). So this asserts on **rendered UI**, never on a named wire message.
     *
     * **Three durable surfaces, one projection.** [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel]
     * derives thread state from `observeConversations(ConversationFilter.All)`, so a single confirmed upsert of
     * the promote reply drives all three assertions: (1) the **thread top bar**
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadScreen] `title = state.displayName`), reached
     * **as soon as the modal closes** — unlike delete/archive there is **no PopBack** (the modal closes once its
     * writes are confirmed and the thread stays open), so the top bar re-labels in place; (2) the
     * **[de.pyryco.mobile.ui.conversations.components.WorkspaceChip] unmount** — the `isPromoted` tier flip,
     * in-thread and free, because the chip is gated `!isPromoted && !hasMessages` and this scenario **sends no
     * message**, so `hasMessages` stays false and the chip's disappearance is attributable **solely** to the
     * promote (the #562 gating fact, read in the opposite direction); and (3) after Back, **presence on the
     * main list** ∧ **absence from the Discussions drilldown**. None of the three is a session id (the #545
     * lesson).
     *
     * **Why the tier read is a tagged main-list matcher (#731).** The drilldown this scenario used to take is
     * gone with the recent-discussions section: the assembled list shows both tiers at once, so there is no
     * `See all discussions (N)` row left to tap. The tiers are still separable by no production string — both
     * sections instance the **same** [de.pyryco.mobile.ui.conversations.components.TreeConversationRow], with
     * the same glyph and type scale and no tier word anywhere on it — and a section's header and its rows are
     * **siblings** inside one `LazyColumn`, so there is no ancestor scoping to bet on either. The assembling
     * screen therefore tags each conversation row with the tier it drew it in ([TREE_CHANNEL_ROW_TEST_TAG] /
     * [TREE_CHAT_ROW_TEST_TAG]). Present on a channel-tagged row ∧ absent from every chat-tagged row **is**
     * "presented in the promoted (channel) tier rather than among the chats", expressed entirely in
     * presence/absence matchers — and its positive half is stronger than the old absence-in-a-drilldown,
     * because it names the tier the row is actually in. This is the #551 tier-membership idiom, reused.
     *
     * **The modal-over-thread field disambiguation (#537's idiom, reused).**
     * [de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog] (#957, the `MobileModal` shell) opens
     * **over** the thread, whose composer is also an editable field, and the modal holds a second one (the system
     * prompt), so `hasSetTextAction()` alone is ambiguous. The form focuses its name field on open and nothing
     * else requests focus, so `hasSetTextAction() and isFocused()` selects the name field. It is **pre-filled
     * and fully selected** with the chat's own name → `performTextReplacement`. The prompt field is reached by
     * its [CHANNEL_PROMPT_FIELD_TAG].
     *
     * **In place, with a prompt (#957).** There is no location choice any more: the chat is promoted in its own
     * `cwd` (`promote(..., workspace = null)`), so the scenario creates no folder on the operator's machine. It
     * types a short system prompt, so the modal's second write — `set_system_prompt`, sent only after the
     * promote is confirmed — rides the same run. The prompt applies at the next session start and this scenario
     * starts none, so it costs no claude turn.
     *
     * **One literal collision matched exactly, not by substring.** `save_as_channel_action` is
     * `"Save as channel…"` (U+2026) while `save_as_channel_dialog_title` is `"Save as channel"` (no ellipsis),
     * so a substring search conflates them — [SAVE_AS_CHANNEL_ITEM] and [SAVE_AS_CHANNEL_TITLE] are both matched
     * **exactly**. The second collision this scenario used to dodge went with the drilldown: step 8 no longer
     * navigates, so it needs no arrival marker.
     *
     * **No top-bar false match.** The modal now stays open, holding the unique name in its field, until both
     * writes are confirmed — a failure would keep it open with an error. So step 6 first waits for the modal's
     * exact title to leave, which is the proof both writes landed, and only then reads the unique name, which
     * can by then be only the top bar's.
     *
     * **Always-on, not `@Ignore`d.** All three post-conditions are **durable** structural facts (the recorded
     * name and the recorded `isPromoted` flag) — no transient like #482's spinner — so the scenario belongs in
     * the always-on gate, matching #481's tool-name row, #541's delimiter, and #554's / #551's / #562's /
     * #537's inversions. **Zero real-claude turns**: create-discussion and promote are daemon round-trips
     * (promote is a pure registry op daemon-side), not claude turns, and the durable identity is the typed
     * name, so this scenario sends **no** ping. The LIVE gate goes from a septet (7 methods) to an **octet**
     * (8 methods) at **still 3 turns** — promote adds a method, not a turn.
     */
    @Test
    fun interactiveTurn_saveAsChannel_promotesToChannelTier() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating — promote round-trips to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion is exactly what is needed — "Save as channel…" is !isPromoted-gated (NOT
        //    mutationsSupported-gated), and a fresh discussion is unpromoted by construction.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Absence guard + tier before-state (both deterministic — no claude turn). The runtime-unique
        //    channel name is not on screen yet (the top bar shows the server auto-name, which the modal
        //    pre-fills), so its later appearance is attributable to the promote
        //    round-trip. And the WorkspaceChip IS mounted — the discussion tier — the before-state of step 6's
        //    tier-flip inversion (it stays mounted because no message is sent: !isPromoted && !hasMessages).
        val uniqueName = PROMOTE_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(WORKSPACE_CHIP_PREFIX, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(WORKSPACE_CHIP_PREFIX, substring = true).onFirst().assertIsDisplayed()

        // 5. Promote to the unique name, with a system prompt. Open the overflow, tap "Save as channel…"
        //    (matched EXACTLY — the modal title is the same literal minus the U+2026 ellipsis). The modal opens
        //    OVER the thread, whose composer is also an editable field, and holds a second field of its own, so
        //    target the name field by its focus (the form focuses it on open), waiting for focus to land, and
        //    REPLACE the pre-filled+selected chat name. Type the prompt into its tagged field, then OK. There is
        //    no location choice: the chat is promoted in its own cwd, so no folder is created.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(SAVE_AS_CHANNEL_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(SAVE_AS_CHANNEL_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(uniqueName)
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextInput(SAVE_AS_CHANNEL_PROMPT)
        composeTestRule.onNodeWithText(SAVE_AS_CHANNEL_OK).performClick()

        // 6. In-thread assertions (surfaces #1 + #2). The first wait spans both writes: the modal closes only
        //    once the promote reply and then the set_system_prompt reply are confirmed (a failure keeps it open
        //    with an error, so this wait is the proof both landed). Only then is the unique name read — the
        //    modal's field held it until now, so after the close the match is the top bar, re-labelled in place
        //    (there is NO PopBack). The next wait is the tier flip — the WorkspaceChip unmounts once
        //    state.isPromoted is true. Independent waits keep a failure attributable.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(SAVE_AS_CHANNEL_TITLE).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(WORKSPACE_CHIP_PREFIX, substring = true).fetchSemanticsNodes().isEmpty()
        }

        // 7. Main-list presence (surface #3a). Tap Back, wait for the list marker (the thread has popped back),
        //    then wait for the unique name on the list and confirm it is displayed — a genuine inversion of
        //    step 4's absence, same unique token. The same upsert feeds both list projections.
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        awaitChannelList()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()

        // 8. The tier read (surface #3b), now on the assembled list itself — no navigation, so no arrival
        //    marker to get wrong. Wait for the promoted conversation on a CHANNEL-tagged row, then assert it is
        //    on no CHAT-tagged row. Both tiers are drawn on this one screen, so present-as-a-channel ∧
        //    absent-among-the-chats ⇒ presented in the promoted tier. Tolerant: presence/absence with a
        //    generous timeout — never a delta count, a row ordering or a geometric read.
        val channelTierRow = hasTestTag(TREE_CHANNEL_ROW_TEST_TAG) and hasText(uniqueName, substring = true)
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(channelTierRow).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(channelTierRow).onFirst().assertIsDisplayed()
        composeTestRule
            .onAllNodes(hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(uniqueName, substring = true))
            .assertCountEquals(0)
    }

    /**
     * Two paired hosts whose conversations share one id stay separate (#847, rung 3). Daemon-minted ids
     * never collide by chance, so `scripts/e2e-emulator.sh` seeds the collision: before either daemon
     * starts it writes ONE promoted conversation under the same run-unique id into host A's instance and
     * a second test daemon's instance, named [collisionNameA][ARG_COLLISION_NAME_A] and
     * [collisionNameB][ARG_COLLISION_NAME_B]. Host A is pre-paired by [E2eTestApplication]; host B is
     * paired here through the app's own paste-a-code flow, with the code the harness minted and
     * re-pointed at the relay the phone dials.
     *
     * **What "separate" is read from.** Row, thread and cache are all keyed by `(serverId,
     * conversationId)` (#731, #795–#798). A key that dropped the host would show one name twice, open
     * one host's conversation from the other's row, or file a rename under both. Each check below keys
     * on the two exact, run-unique names, so the two reads distinguish the hosts with no new test tag:
     *  * **under its own host** — folding a host's Channels row hides its own conversation and leaves
     *    the other host's ([assertEachUnderOwnHost]);
     *  * **opens its own conversation** — a row's thread shows that row's name and never the other's
     *    ([assertRowOpensOwnThread]).
     * Both are re-read after a rename of host A's conversation, after each host's link is cut and
     * restored, and after the object graph is rebuilt over the same on-device state — the restart an
     * instrumented test can perform ([E2eTestApplication.rebuildGraph]; it cannot kill its own process).
     *
     * **Shared app state.** Every live method shares one Application and one Koin graph, and a newly
     * saved host becomes the registry's selection, which the other scenarios' connection waits follow.
     * Host B is therefore removed in `finally`, on whichever graph is current then.
     *
     * **Zero real-claude turns**: pairing, navigation, rename and link cycling are daemon round-trips.
     */
    @Test
    fun interactiveTurn_twoHostsCollidingConversationId_stayPerHost() {
        val serverIdA = twoHostArg(ARG_SERVER_ID)
        val serverIdB = twoHostArg(ARG_SERVER_ID_B)
        val collisionId = twoHostArg(ARG_COLLISION_CONVERSATION_ID)
        val nameA = twoHostArg(ARG_COLLISION_NAME_A)
        val nameB = twoHostArg(ARG_COLLISION_NAME_B)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var relaunched: ActivityScenario<MainActivity>? = null
        try {
            // 1. Host A's seeded conversation is on the list, and host A really holds it under the seeded id.
            awaitChannelList()
            awaitConnected()
            awaitChannelRow(nameA)
            assertHostHoldsConversation(serverIdA, collisionId, nameA)

            // 2. Pair host B through the section header's add control → scanner → paste link → PairCodeScreen.
            //    The scanner asks for CAMERA at runtime; granting it first keeps the system dialog off screen.
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
            pairHostByCode(twoHostArg(ARG_PAIR_CODE_B))
            awaitChannelRow(nameB)
            assertHostHoldsConversation(serverIdB, collisionId, nameB)

            // 3. AC-1 + AC-2: each conversation sits under its own host and each row opens its own thread.
            val labelA = hostLabel(serverIdA)
            val labelB = hostLabel(serverIdB)
            assertHostsStaySeparate(labelA to nameA, labelB to nameB)

            // 4. AC-2: rename host A's conversation from its thread (#537's drive). The new name shows on
            //    A's thread and A's row only; B's row and thread keep B's name.
            val renamedA = RENAMED_NAME_PREFIX + System.currentTimeMillis()
            openRow(nameA)
            renameOpenThread(renamedA)
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            awaitChannelRow(renamedA)
            composeTestRule.onAllNodes(channelRow(nameA)).assertCountEquals(0)
            assertHostsStaySeparate(labelA to renamedA, labelB to nameB)

            // 5. AC-3: cut and restore each host's link in turn, then re-read both.
            cycleHostLink(serverIdA)
            cycleHostLink(serverIdB)
            awaitChannelRow(renamedA)
            awaitChannelRow(nameB)
            assertHostsStaySeparate(labelA to renamedA, labelB to nameB)

            // 6. AC-3: restart. No activity may outlive the graph it resolved, so the rule's activity is
            //    destroyed first (recreate() would retain its view models, which hold the old graph).
            composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
            instrumentation.runOnMainSync {
                (instrumentation.targetContext.applicationContext as E2eTestApplication).rebuildGraph()
            }
            relaunched = ActivityScenario.launch(MainActivity::class.java)
            awaitChannelList()
            awaitConnected()
            awaitChannelRow(renamedA)
            awaitChannelRow(nameB)
            assertHostsStaySeparate(labelA to renamedA, labelB to nameB)
        } finally {
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverIdB) }
            relaunched?.close()
        }
    }

    /**
     * A turn started from another client continues on the phone (#848, rung 3). A [SecondClientPeer] —
     * a second paired device on host A with its own token ([ARG_PEER_TOKEN]) and key, standing in for the
     * desktop — sends the constrained ping prompt into a chat the phone has open.
     *
     * The daemon carries no live frame with another device's message text: the turn's stream frames fan
     * out to every interactive connection, and the message text reaches the phone through history. So the
     * checks are what the operator sees, however delivered:
     *  * **while open** — claude's reply renders in the thread exactly once;
     *  * **after leaving and reopening** — the peer's message and the reply each render exactly once.
     *
     * The chat is renamed to a run-unique name before any message is sent, so the daemon's first-message
     * auto-naming never fires (a name set by rename is never overwritten) and the row can be found again.
     * The prompt count is read inside the thread's scrollable list, where a delivered bubble and an inline
     * queued row both live, so a message drawn once as each would count twice; the top bar is outside it.
     *
     * **One real-claude turn**: the peer's ping.
     */
    @Test
    fun interactiveTurn_peerStartedTurn_continuesOnPhone() {
        val args = InstrumentationRegistry.getArguments()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = twoHostArg(ARG_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
                ),
            )
        try {
            // 1. The phone creates a chat and is in its thread; the new id is the one it did not hold before.
            awaitChannelList()
            awaitConnected()
            val before = runBlocking { withTimeout(LIST_TIMEOUT_MS) { hostConversationIds(serverId) { true } } }
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId =
                runBlocking {
                    withTimeout(LIST_TIMEOUT_MS) { hostConversationIds(serverId) { ids -> (ids - before).isNotEmpty() } - before }
                }.single()
            val chatName = PEER_CHAT_NAME_PREFIX + System.currentTimeMillis()
            renameOpenThread(chatName)

            // 2. AC-1: the peer, as its own device, sends into that conversation and observes its frames.
            runBlocking {
                peer.open(CONNECT_TIMEOUT_MS)
                peer.sendMessage(conversationId, PING_PROMPT, THREAD_TIMEOUT_MS)
                peer.awaitFrame(conversationId, "turn_end", REPLY_TIMEOUT_MS)
            }

            // 3. AC-2: with the thread open, claude's reply renders there once.
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(1)

            // 4. AC-3: leave, reopen, and read the message and the reply once each.
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            val chatRow = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(chatName, substring = true)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) { runCatching { scrollListTo(chatRow) }.isSuccess }
            composeTestRule.onAllNodes(chatRow).onFirst().performClick()
            val peerMessage = hasText(PING_PROMPT) and hasAnyAncestor(hasScrollToNodeAction())
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(peerMessage, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodes(peerMessage, useUnmergedTree = true).assertCountEquals(1)
            composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(1)
        } finally {
            peer.close()
        }
    }

    /**
     * Phone replies, queued sends and drops stay consistent with another client (#849, rung 3). In a
     * conversation the [SecondClientPeer] starts, the peer's opening turn has claude run a shell command
     * ([WAIT_PROMPT]) that needs permission. No device answers until the queue steps are done, so the
     * pending prompt holds the turn open for them, with no timing involved:
     *  * the phone's [PING_PROMPT] queues — a queued row on the phone and an item in the peer's snapshot;
     *  * the phone queues and drops [DROP_PROMPT] — gone from both backlogs and from the phone's thread;
     *  * the peer queues and drops [PEER_QUEUED_PROMPT] — a plain queued row on the phone until then.
     * The peer, paired with `--allow-remote-permissions`, then allows the command once. The turn ends and
     * the ping drains: claude's reply renders once on the phone, the prompt draws once,
     * the peer sees that turn's `turn_end`, and both backlogs are empty — so neither dropped message can
     * still reach claude, and neither one's reply token is ever drawn.
     *
     * Every backlog check on the peer reads the latest `queue_state`, which fans out to every interactive
     * connection. The prompt counts use #848's list matcher, so a message drawn once as a bubble and once
     * as a queued row counts twice.
     *
     * **Two real-claude turns**: the peer's wait turn and the drained ping.
     */
    @Test
    fun interactiveTurn_peerQueue_staysConsistentAcrossClients() {
        val args = InstrumentationRegistry.getArguments()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = twoHostArg(ARG_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
                ),
            )
        try {
            // 1. The phone creates and renames a chat, as #848 does; the peer joins as its own device.
            awaitChannelList()
            awaitConnected()
            val before = runBlocking { withTimeout(LIST_TIMEOUT_MS) { hostConversationIds(serverId) { true } } }
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId =
                runBlocking {
                    withTimeout(LIST_TIMEOUT_MS) { hostConversationIds(serverId) { ids -> (ids - before).isNotEmpty() } - before }
                }.single()
            renameOpenThread(QUEUE_CHAT_NAME_PREFIX + System.currentTimeMillis())

            // 2. The peer starts the conversation with a turn that stops on a permission prompt. The prompt
            //    stays outstanding until step 6, so every queue step runs while that turn is still open.
            val permissionModalId =
                runBlocking {
                    peer.open(CONNECT_TIMEOUT_MS)
                    peer.sendMessage(conversationId, WAIT_PROMPT, THREAD_TIMEOUT_MS)
                    peer.awaitPermissionModal(conversationId, REPLY_TIMEOUT_MS)
                }

            // 3. AC-2: the phone's message queues behind it, on the phone and in the peer's snapshot.
            sendFromPhone(PING_PROMPT)
            awaitQueuedRow(PING_PROMPT)
            runBlocking { peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.any { it.text == PING_PROMPT } } }

            // 4. AC-3: a message the phone queues and drops leaves both backlogs and the phone's thread; the
            //    ping stays queued.
            sendFromPhone(DROP_PROMPT)
            awaitQueuedRow(DROP_PROMPT)
            runBlocking { peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.any { it.text == DROP_PROMPT } } }
            val dropControl = hasContentDescription(queuedDropDescription) and hasAnyAncestor(queuedRow(DROP_PROMPT))
            scrollListTo(dropControl)
            composeTestRule.onNode(dropControl).performClick()
            awaitGoneFromThread(DROP_PROMPT)
            runBlocking {
                peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue ->
                    queue.none { it.text == DROP_PROMPT } && queue.any { it.text == PING_PROMPT }
                }
            }

            // 5. AC-3: a message the peer queues is a plain queued row on the phone until the peer drops it.
            val peerItem =
                runBlocking {
                    peer.sendMessage(conversationId, PEER_QUEUED_PROMPT, THREAD_TIMEOUT_MS)
                    peer
                        .awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.any { it.text == PEER_QUEUED_PROMPT } }
                        .first { it.text == PEER_QUEUED_PROMPT }
                }
            awaitQueuedRow(PEER_QUEUED_PROMPT)
            peer.dequeueMessage(conversationId, peerItem.queuedMsgId)
            awaitGoneFromThread(PEER_QUEUED_PROMPT)
            runBlocking {
                peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.none { it.text == PEER_QUEUED_PROMPT } }
            }

            // 6. AC-1 / AC-2: the peer allows the command, the turn ends, and the ping drains and runs; the
            //    peer sees that turn end and an empty backlog, and the phone draws the reply and the prompt
            //    once each, no longer queued.
            runBlocking {
                peer.allowOnce(permissionModalId, THREAD_TIMEOUT_MS)
                peer.awaitFrame(conversationId, "turn_end", WAIT_TURN_TIMEOUT_MS, occurrence = 2)
                peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.isEmpty() }
            }
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(1)
            composeTestRule.onAllNodes(inThreadList(PING_PROMPT), useUnmergedTree = true).assertCountEquals(1)
            composeTestRule.onAllNodes(queuedRow(PING_PROMPT)).assertCountEquals(0)

            // 7. AC-3: with the backlog empty nothing dropped can run, and no dropped reply was ever drawn.
            composeTestRule.onAllNodes(inThreadList(DROP_PROMPT), useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(inThreadList(PEER_QUEUED_PROMPT), useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(hasText(DROP_REPLY), useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(hasText(PEER_QUEUED_REPLY), useUnmergedTree = true).assertCountEquals(0)
        } finally {
            peer.close()
        }
    }

    /**
     * A loaded conversation stays readable offline and catches up on reconnect (#850, rung 3; the live
     * proof #795–#798 deferred). The phone loads a chat's history with one ping turn, then cuts its own
     * link to the host as [setHostLink] does. With the connection-scoped repository gone, readability can
     * only come from retained content:
     *  * **offline** — the open thread still draws the ping and its reply, the chat's row is still in the
     *    list, and reopening the row draws both again (the on-disk thread restore, not the in-memory rows);
     *  * **meanwhile** — the [SecondClientPeer] sends [OFFLINE_PROMPT] and its turn ends, and the phone
     *    draws none of it, which is what shows it really was offline;
     *  * **reconnected** — the still-open thread draws the peer's reply from the ring replay and its prompt
     *    from the reconnect history re-ask (#861), that turn after the ping, and each of the four messages
     *    once. The prompt comes only from a history page.
     *
     * The cut waits until the phone itself has settled the ping reply — its thread cache holds it, which
     * the open thread's collector writes only after drawing the settled row. A disconnect keeps only settled
     * rows, and the peer's copy of `turn_end` can arrive before the phone's, so waiting on the peer alone
     * could cut while the phone's reply still streamed and drop it by design.
     *
     * **Two real-claude turns**: the phone's ping and the peer's offline turn.
     */
    @Test
    fun interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect() {
        val args = InstrumentationRegistry.getArguments()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = twoHostArg(ARG_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
                ),
            )
        try {
            // 1. The peer records frames from here on; the phone creates and renames a chat, as #848 does.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val before = runBlocking { withTimeout(LIST_TIMEOUT_MS) { hostConversationIds(serverId) { true } } }
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId =
                runBlocking {
                    withTimeout(LIST_TIMEOUT_MS) { hostConversationIds(serverId) { ids -> (ids - before).isNotEmpty() } - before }
                }.single()
            val chatName = OFFLINE_CHAT_NAME_PREFIX + System.currentTimeMillis()
            renameOpenThread(chatName)

            // 2. Load history: the phone's ping turn renders and ends. The peer's turn_end keeps the later
            //    occurrence count right; the phone's own cache is what shows its rows settled before the cut.
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            runBlocking { peer.awaitFrame(conversationId, "turn_end", REPLY_TIMEOUT_MS) }
            awaitCachedAssistantReply(serverId, conversationId)

            // 3. AC-1: cut the phone's link. The open thread keeps what it drew.
            setHostLink(serverId, up = false)
            composeTestRule.waitForIdle()
            assertDrawnOnce(inThreadList(PING_PROMPT), pingReplyMatcher())

            // 4. AC-1: the chat's row is still listed, and reopening it offline draws the history again.
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            val chatRow = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(chatName, substring = true)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) { runCatching { scrollListTo(chatRow) }.isSuccess }
            composeTestRule.onAllNodes(chatRow).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(inThreadList(PING_PROMPT), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.awaitDisplayedPingReply(THREAD_TIMEOUT_MS)
            assertDrawnOnce(inThreadList(PING_PROMPT), pingReplyMatcher())

            // 5. AC-2: while the phone is offline the peer's turn runs to its end; the phone draws none of it.
            runBlocking {
                peer.sendMessage(conversationId, OFFLINE_PROMPT, THREAD_TIMEOUT_MS)
                peer.awaitFrame(conversationId, "turn_end", WAIT_TURN_TIMEOUT_MS, occurrence = 2)
            }
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodes(inThreadList(OFFLINE_PROMPT), useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(offlineReplyMatcher(), useUnmergedTree = true).assertCountEquals(0)

            // 6. AC-2: reconnect with the thread open; the ring replay brings the peer's reply into it. No live
            //    frame carries another device's message text, so the prompt comes only from a history page:
            //    the still-open thread's reconnect re-ask, which waits for the published repository (#861).
            setHostLink(serverId, up = true)
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onNode(offlineReplyMatcher(), useUnmergedTree = true).isDisplayed() &&
                    composeTestRule.onAllNodes(inThreadList(OFFLINE_PROMPT), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitForIdle()
            assertDrawnOnce(inThreadList(PING_PROMPT), pingReplyMatcher(), inThreadList(OFFLINE_PROMPT), offlineReplyMatcher())
            val tops =
                listOf(pingReplyMatcher(), inThreadList(OFFLINE_PROMPT), offlineReplyMatcher()).map {
                    composeTestRule
                        .onNode(it, useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                }
            assertTrue("expected ping reply, offline prompt, offline reply top to bottom; tops $tops", tops == tops.sorted())
            assertTrue("two of the messages share a row; tops $tops", tops.distinct().size == tops.size)
        } finally {
            peer.close()
        }
    }

    /**
     * Wait until the phone's thread cache for [conversationId] holds an assistant reply. The cache only
     * ever holds settled rows, and the open thread's collector writes them after drawing them, so this is
     * the phone's own proof that the reply settled — not another device's copy of `turn_end`.
     */
    private fun awaitCachedAssistantReply(
        serverId: String,
        conversationId: String,
    ) {
        val cache = GlobalContext.get().get<ConversationCache>()
        runBlocking {
            withTimeout(THREAD_TIMEOUT_MS) {
                while (cache
                        .readThread(
                            serverId,
                            conversationId,
                        ).none { it is ThreadItem.MessageItem && it.message.role == Role.Assistant }
                ) {
                    delay(CACHE_POLL_MS)
                }
            }
        }
    }

    /** Each of [matchers] matches exactly one node in the unmerged tree. */
    private fun assertDrawnOnce(vararg matchers: SemanticsMatcher) {
        matchers.forEach { composeTestRule.onAllNodes(it, useUnmergedTree = true).assertCountEquals(1) }
    }

    /** The peer's offline reply as a delivered bubble: exactly [OFFLINE_REPLY], as [pingReplyMatcher] anchors ping. */
    private fun offlineReplyMatcher(): SemanticsMatcher =
        hasText(OFFLINE_REPLY, ignoreCase = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))

    /**
     * Type [text] and send it from the open thread. While a turn runs the button is Stop until the composer
     * holds text, so the tap waits for Send rather than interrupting the turn.
     */
    private fun sendFromPhone(text: String) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput(text)
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
    }

    /** A node inside the thread's scrollable list with exactly [text]: a bubble or a queued row, not the top bar. */
    private fun inThreadList(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasScrollToNodeAction())

    /** The queued row for [text]: its merged node carries the text and the "Waiting to send" state. */
    private fun queuedRow(text: String): SemanticsMatcher =
        hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, queuedStateDescription)

    /** Wait until [text] draws as a queued row in the open thread. */
    private fun awaitQueuedRow(text: String) {
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(queuedRow(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Wait until nothing in the open thread's list carries [text] — neither a queued row nor a bubble. */
    private fun awaitGoneFromThread(text: String) {
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(inThreadList(text), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Wait until [serverId]'s repository holds a conversation-id set satisfying [ready], and return it. */
    private suspend fun hostConversationIds(
        serverId: String,
        ready: (Set<String>) -> Boolean,
    ): Set<String> {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        val repository = checkNotNull(bundle.coordinator.currentRepository.first { it != null })
        return repository
            .observeConversations(ConversationFilter.All)
            .first { rows -> ready(rows.mapTo(mutableSetOf()) { it.id }) }
            .mapTo(mutableSetOf()) { it.id }
    }

    /** A two-host instrumentation argument (#847), failing with the script that passes it. */
    private fun twoHostArg(key: String): String =
        requireNotNull(InstrumentationRegistry.getArguments().getString(key)) {
            "missing instrumentation arg '$key' — scripts/e2e-emulator.sh passes it on rung 3 and LIVE"
        }

    /** A conversation row in the Channels tier carrying exactly [name]. */
    private fun channelRow(name: String): SemanticsMatcher = hasTestTag(TREE_CHANNEL_ROW_TEST_TAG) and hasText(name)

    /** Scroll the tree until a node matching [matcher] is composed, so a long list cannot hide it. */
    private fun scrollListTo(matcher: SemanticsMatcher) {
        composeTestRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
    }

    /** Wait until the Channels row named [name] is on screen. */
    private fun awaitChannelRow(name: String) {
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            runCatching { scrollListTo(channelRow(name)) }.isSuccess
        }
        composeTestRule.onAllNodes(channelRow(name)).onFirst().assertIsDisplayed()
    }

    /**
     * The seeded collision is real: [serverId]'s own repository holds [conversationId] under [name]. Without
     * this a seed that wrote two different ids would pass every UI check below trivially.
     */
    private fun assertHostHoldsConversation(
        serverId: String,
        conversationId: String,
        name: String,
    ) {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        val held =
            runBlocking {
                withTimeout(LIST_TIMEOUT_MS) {
                    val repository = bundle.coordinator.currentRepository.first { it != null }
                    checkNotNull(repository)
                        .observeConversations(ConversationFilter.All)
                        .first { rows -> rows.any { it.id == conversationId } }
                        .first { it.id == conversationId }
                }
            }
        assertEquals(name, held.name)
    }

    /** The host row's label as the tree draws it: the saved display name, else "Unnamed host". */
    private fun hostLabel(serverId: String): String {
        val saved = runBlocking { GlobalContext.get().get<PairedServerCollectionStore>().loadById(serverId) }
        return saved?.displayName?.takeIf { it.isNotBlank() }
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.unnamed_host)
    }

    /**
     * Pair a second host by pasting [pairCode]: the Channels header's add control opens the scanner, whose
     * every state offers a paste link ([PASTE_CODE_LINK]), which opens `PairCodeScreen`. Pair → confirm the
     * fingerprint → the screen waits for Connected and returns to the list.
     */
    private fun pairHostByCode(pairCode: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pairControl =
            context.getString(R.string.cd_tree_section_pair_host, context.getString(R.string.channels_section_header))
        composeTestRule.onAllNodes(hasContentDescription(pairControl)).onFirst().performClick()
        val pasteLink = hasText(PASTE_CODE_LINK, substring = true) and hasClickAction()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(pasteLink).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(pasteLink).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and hasText(PAIR_CODE_FIELD)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and hasText(HOST_NAME_FIELD)).performTextInput(HOST_B_NAME)
        composeTestRule.onNode(hasSetTextAction() and hasText(PAIR_CODE_FIELD)).performTextInput(pairCode)
        composeTestRule.onNode(hasText(PAIR_BUTTON) and hasClickAction()).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CONFIRM_PAIRING).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(CONFIRM_PAIRING).performClick()
        // Save, then up to the view model's 30 s connection wait, then the pop back to the list.
        composeTestRule.waitUntil(PAIR_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Both halves of "separate", for both hosts: see [assertEachUnderOwnHost] and [assertRowOpensOwnThread]. */
    private fun assertHostsStaySeparate(
        hostA: Pair<String, String>,
        hostB: Pair<String, String>,
    ) {
        assertEachUnderOwnHost(hostA, hostB)
        assertEachUnderOwnHost(hostB, hostA)
        assertRowOpensOwnThread(hostA.second, hostB.second)
        assertRowOpensOwnThread(hostB.second, hostA.second)
    }

    /**
     * Folding [host]'s Channels row (label to conversation name) hides its own conversation and leaves
     * [other]'s: the row was drawn under that host. The first fold control of a label is the Channels
     * section's, which the tree draws before the Chats section. The fold is undone before returning.
     */
    private fun assertEachUnderOwnHost(
        host: Pair<String, String>,
        other: Pair<String, String>,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collapse = hasContentDescription(context.getString(R.string.cd_tree_row_collapse, host.first))
        val expand = hasContentDescription(context.getString(R.string.cd_tree_row_expand, host.first))
        awaitChannelRow(host.second)
        scrollListTo(collapse)
        composeTestRule.onAllNodes(collapse).onFirst().performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(channelRow(host.second)).fetchSemanticsNodes().isEmpty()
        }
        awaitChannelRow(other.second)
        scrollListTo(expand)
        composeTestRule.onAllNodes(expand).onFirst().performClick()
        awaitChannelRow(host.second)
    }

    /** [name]'s row opens a thread titled [name], never [other]; then back to the list. */
    private fun assertRowOpensOwnThread(
        name: String,
        other: String,
    ) {
        openRow(name)
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(name).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(other).assertCountEquals(0)
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        awaitChannelList()
    }

    /**
     * Tap the Channels row named [name] and wait for its thread: the send button is drawn and the list has
     * left composition, so no row still fading out of the transition can answer a thread-side name check.
     */
    private fun openRow(name: String) {
        awaitChannelRow(name)
        composeTestRule.onAllNodes(channelRow(name)).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Rename the open thread's conversation to [newName] and wait for its top bar to re-label (#537's drive). */
    private fun renameOpenThread(newName: String) {
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RENAME_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(RENAME_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(newName)
        composeTestRule.onNodeWithText(RENAME_SAVE).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(newName).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Cut and restore one host's link, the per-host form of the rung-4 close / connect drive. Readiness is
     * the coordinator's repository, not a `ConnectionState`: an idle `Connected` after `close()` would
     * false-green a state check.
     */
    private fun cycleHostLink(serverId: String) {
        setHostLink(serverId, up = false)
        setHostLink(serverId, up = true)
    }

    /**
     * Cut ([up] false) or restore one host's link, and wait until its coordinator's repository is gone or
     * back. Cutting tears down the connection-scoped repository, so what the phone still draws is retained.
     */
    private fun setHostLink(
        serverId: String,
        up: Boolean,
    ) {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        runBlocking {
            withTimeout(CONNECT_TIMEOUT_MS) {
                if (up) bundle.supervisor.connect() else bundle.supervisor.close()
                bundle.coordinator.currentRepository.first { (it != null) == up }
            }
        }
    }

    /**
     * Block until the channel list is on screen, keyed on the app-authored marker the list screen sets
     * ([CHANNEL_LIST_TEST_TAG], #736) rather than on anything drawn on it. Every scenario arrives through
     * here, including the two `@Ignore`d manual ones.
     *
     * Deliberately **weaker** than the "New discussion" wait it replaced. That control drew only on a loaded
     * flat state, so waiting for it implied a loaded list; this marker is on both of the list's draws — the
     * blank placeholder and the assembled tree — and implies only that the list is the destination on
     * screen. [awaitHostAddControl] carries that wait now, for the two helpers where it was load-bearing:
     * acting on a control before it is drawn fails the drive, not the wait.
     */
    private fun awaitChannelList() {
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Create a fresh chat from the channel list; the app navigates into its thread.
     *
     * With [openWorkspacePicker], one of the **two** places in this class that name the create control —
     * the two bodies #738 edits when the control changes, in place of the 33 sites they replaced. Each
     * carries its own wait rather than sharing a third helper, so the count stays at two.
     */
    private fun createChat() {
        awaitHostAddControl().performClick()
    }

    /**
     * Long-press the same control to open that host's Add workspace modal (#904) — a *tap* would create
     * a scratch chat instead (the long press routes to `ChannelListEvent.TreeHostAddLongPressed`,
     * `combinedClickable.onLongClick`).
     */
    private fun openWorkspacePicker() {
        awaitHostAddControl().performTouchInput { longClick() }
    }

    /**
     * Wait for the paired host's own add control and return it (#738).
     *
     * The floating button's single fixed content description is gone; each host row's control carries a
     * per-host name instead, so the durable handle is the per-host test tag keyed on the `serverId` the
     * harness itself passed in — unambiguous the moment a second host is paired, which a name-based or
     * position-based match would not be. The tree draws the same host in both sections, so the tag matches
     * twice; either node is the same control on the same host.
     */
    private fun awaitHostAddControl(): SemanticsNodeInteraction {
        val tag = treeHostAddTestTag(requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID)))
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
        return composeTestRule.onAllNodes(hasTestTag(tag)).onFirst()
    }

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

        // Production UI strings. Keep in sync with res/values/strings.xml:
        //   cd_send_message = "Send message", cd_back = "Back".
        // The button's fixed "New discussion" description went with the button (#738). Creation is now
        // addressed by the paired host's own tag, built in awaitHostAddControl from the harness's own
        // serverId argument, so nothing here has to name it.
        const val CD_SEND_MESSAGE = "Send message"
        const val CD_BACK = "Back"

        // #541 new-session scenario. Overflow-menu production strings (no test tags): CD_MORE_ACTIONS opens
        // the menu; NEW_SESSION_ITEM is the tap target. The durable matcher is DELIMITER_EXPLANATION,
        // the delimiter's reason-independent hardcoded explanation line, which can
        // only come from the rendered SessionBoundaryDelimiter. Keep in sync with res/values/strings.xml:
        //   cd_more_actions = "More actions", thread_overflow_new_session = "Reset session".
        const val CD_MORE_ACTIONS = "More actions"
        const val NEW_SESSION_ITEM = "Reset session"
        const val DELIMITER_EXPLANATION = SESSION_BOUNDARY_EXPLANATION

        // #566 create-workspace-folder scenario. Picker/dialog production strings (no test tags):
        //   the WorkspacePickerSheet create row (matched as a substring so the trailing ellipsis need
        //   not be reproduced), the CreateFolderDialog confirm button, and the picker's "Recent" header.
        const val CREATE_FOLDER_ROW = "Create new folder under pyry-workspace"
        const val CREATE_BUTTON = "Create"
        const val RECENT_SECTION = "Recent"

        // #904: Add workspace's submit — MobileModal's fixed footer label.
        const val OK_BUTTON = "OK"

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
        // navigates channel list → Settings: CD_OPEN_SETTINGS is the settings entry on the list's own bar (#737);
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

        // #740 list-archive-entry scenario: the archive entry on the list's own bar (#737), the sibling of
        // CD_OPEN_SETTINGS. Keep in sync with res/values/strings.xml: cd_open_archive = "Open archive".
        const val CD_OPEN_ARCHIVE = "Open archive"

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

        // #581 save-as-channel (promote) scenario, driven through the #957 modal. Reuses the overflow opener
        // (CD_MORE_ACTIONS) and the list / thread markers. SAVE_AS_CHANNEL_ITEM and SAVE_AS_CHANNEL_TITLE are
        // matched EXACTLY — the production menu item ends in a real U+2026 ellipsis and the modal title is the
        // same literal WITHOUT it, so substring matching would conflate them. SAVE_AS_CHANNEL_OK is the
        // MobileModal footer's submit label. SAVE_AS_CHANNEL_PROMPT is typed into the prompt field so the
        // post-promote set_system_prompt write rides the run; it takes effect at the next session start, which
        // this scenario never reaches. WORKSPACE_CHIP_PREFIX is the in-thread isPromoted tier signal: the
        // WorkspaceChip's label is a hardcoded literal ("Workspace: <label> (change)"), not a string resource, so
        // this prefix is the matchable token — mounted before the promote, unmounted after. The tier read needs no
        // constant of its own: it matches the row test tags the assembling screen sets (#731). Keep in sync with
        // res/values/strings.xml: save_as_channel_action = "Save as channel…",
        // save_as_channel_dialog_title = "Save as channel".
        const val SAVE_AS_CHANNEL_ITEM = "Save as channel…"
        const val SAVE_AS_CHANNEL_TITLE = "Save as channel"
        const val SAVE_AS_CHANNEL_OK = "OK"
        const val SAVE_AS_CHANNEL_PROMPT = "e2e957: answer briefly."
        const val WORKSPACE_CHIP_PREFIX = "Workspace:"

        // Runtime-unique promote target: "e2e581-" + System.currentTimeMillis(). Distinct prefix (the shared
        // companion forbids redeclaration; each scenario owns its own). Unique so a substring match cannot
        // pre-exist on screen — the absence guard (step 4), its inversions on the top bar (step 6) and the main
        // list (step 7), and the chat-tier absence (step 8) are all genuine; also keeps repeated LIVE
        // gate runs green (no collision with channels left by prior runs) and does not collide as a substring
        // with top-bar / list chrome the assertions also match.
        const val PROMOTE_NAME_PREFIX = "e2e581-"

        // #847 two-host scenario. The five arguments scripts/e2e-emulator.sh passes on rung 3 and LIVE
        // (host A's own four stay E2eTestApplication's). PAIR_CODE_B carries a pairing token: never log it.
        const val ARG_SERVER_ID_B = "serverIdB"
        const val ARG_PAIR_CODE_B = "pairCodeB"
        const val ARG_COLLISION_CONVERSATION_ID = "collisionConversationId"
        const val ARG_COLLISION_NAME_A = "collisionNameA"
        const val ARG_COLLISION_NAME_B = "collisionNameB"

        // #848 peer scenario. PEER_TOKEN is the second device's pairing token that scripts/e2e-emulator.sh
        // mints on host A for the SecondClientPeer (rung 3 and LIVE): never log it. The chat's run-unique
        // name shares no substring with PING_PROMPT, "ping" or the other scenarios' prefixes.
        const val ARG_PEER_TOKEN = "peerToken"
        const val PEER_CHAT_NAME_PREFIX = "e2e848-"

        // #849 queue scenario. WAIT_PROMPT reuses #481's shell-tool lever to hold the peer's turn open for
        // the queue steps. What holds it is the permission prompt the command raises, not the command's
        // run time: a `python3` command is never auto-allowed, and the harness's devices answer nothing
        // until the peer, paired with `--allow-remote-permissions`, allows it. The command itself is quick; a
        // timed wait is not needed, and a bare `sleep` of 25 s or more is refused by claude's Bash tool. DROP_PROMPT and PEER_QUEUED_PROMPT ask for reply tokens no other prompt produces, so an exact-text node with either
        // would mean a dropped message reached claude. None of the texts or the chat prefix contains the exact
        // word "ping".
        const val WAIT_PROMPT =
            "Run this exact shell command with your tools in the foreground, not in the background, then reply " +
                "with exactly: pyrywait. Command: python3 -c \"print(849)\""
        const val DROP_PROMPT = "Reply with exactly: pyrydropped"
        const val DROP_REPLY = "pyrydropped"
        const val PEER_QUEUED_PROMPT = "Reply with exactly: pyrypeerdropped"
        const val PEER_QUEUED_REPLY = "pyrypeerdropped"
        const val QUEUE_CHAT_NAME_PREFIX = "e2e849-"

        // The peer's turn while the phone is offline (#850). The reply is matched as exactly the token in a
        // bubble, which the prompt's own text is not.
        const val OFFLINE_PROMPT = "Reply with exactly: pyryoffline"
        const val OFFLINE_REPLY = "pyryoffline"
        const val OFFLINE_CHAT_NAME_PREFIX = "e2e850-"

        // Pairing-flow production strings (hardcoded in the composables, no resources). PASTE_CODE_LINK is
        // the common tail of all three scanner states' paste links — "Trouble scanning? Paste the pairing
        // code instead", "Paste the pairing code instead", "Paste code instead" — matched as a substring so
        // the camera state the emulator lands in does not matter, and only with a click action: the camera-error
        // and denied states draw a plain message ending in the same words above their button, and tapping that
        // message navigates nowhere. PAIR_BUTTON is matched exactly and with a
        // click action, apart from the "Pairing" title and the "Pairing code" label.
        const val PASTE_CODE_LINK = "code instead"
        const val HOST_NAME_FIELD = "Host name"
        const val PAIR_CODE_FIELD = "Pairing code"
        const val PAIR_BUTTON = "Pair"
        const val CONFIRM_PAIRING = "Confirm pairing"

        // Host B's display name, typed on the pair-code screen. Shares no substring with the seeded
        // "e2e847-a-" / "e2e847-b-" conversation names or RENAMED_NAME_PREFIX, so no exact match can
        // confuse a host row with a conversation row.
        const val HOST_B_NAME = "Second e2e host"

        // Runtime-unique rename target for host A's seeded conversation, distinct from #537's prefix.
        const val RENAMED_NAME_PREFIX = "e2e847-renamed-"

        // The pair-code screen waits up to 30 s for the new host to connect before it returns to the list.
        const val PAIR_TIMEOUT_MS = 60_000L

        const val LIST_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val THREAD_TIMEOUT_MS = 30_000L

        // How often the #850 cut re-reads the thread cache while it waits for the settled reply.
        const val CACHE_POLL_MS = 200L

        // Generous: a real claude turn over the relay can take many seconds end to end.
        const val REPLY_TIMEOUT_MS = 90_000L

        // #849: two real claude turns back to back, the allowed wait turn and the drained ping.
        const val WAIT_TURN_TIMEOUT_MS = 240_000L
    }
}

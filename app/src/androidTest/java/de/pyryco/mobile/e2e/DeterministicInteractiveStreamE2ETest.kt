package de.pyryco.mobile.e2e

import androidx.compose.ui.test.assertCountEquals
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
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Deterministic end-to-end test for the mobile interactive event stream (#431 rung 4, ADR 025): the
 * **real** app on a headless emulator connects to a host `pyry` + relay backed by the scripted
 * `fakeclaude` backend (pyrycode #642) — **no real claude, zero claude turns** — and asserts a
 * scripted reply renders in the thread.
 *
 * Seven scenarios, one per script invocation (the harness runs exactly one `@Test` method per run,
 * selected by `SCENARIO` in `scripts/e2e-emulator.sh`):
 *  - `ping` (default, #431) — a single-line reply renders.
 *  - `stream` (#454) — a multi-`assistant_delta` reply assembles into one message.
 *  - `spinner` (#454) — the thinking spinner shows mid-turn, then clears at turn end (a two-fixture
 *    drop holds the turn open so the transient state is observable; see the method KDoc).
 *  - `tool` (#455) — a tool step renders running mid-turn, then done after the result (two-fixture
 *    drop, same causal fence as the spinner; see the method KDoc).
 *  - `tool-failed` (#455) — a failing tool step renders failed (single terminal drop).
 *  - `reconnect` (#476) — an in-flight reply survives a mid-turn relay-link drop: the turn is held
 *    open (two-fixture drop, spinner-style), the phone's link is severed and restored across the gap,
 *    and the reply renders exactly once after reconnect (see the method KDoc).
 *  - `replay-order` (#477) — a sequence of events produced **entirely while the phone is offline**
 *    replays **in production order, each exactly once** after reconnect: the turn is held open, the
 *    link is severed, the ordered sequence accrues in the daemon's in-ring buffer during the outage
 *    (drop B fenced on the relay-logged phone-leg disconnect, not a 2nd send a severed phone cannot
 *    make), then the link is restored and the buffered sequence replays in order (see the method KDoc).
 *
 * It is a thin variant of [InteractiveStreamE2ETest] (rung 3). **One** step differs: instead of
 * tapping "New discussion" (which mints a *fresh* per-conversation claude session that `fakeclaude` —
 * being env-only — never writes to), it taps a **seeded promoted channel** the host pre-binds to the
 * bootstrap session id. One conversation, one session, one fixture file → the daemon's by-id producer
 * tails exactly the file `fakeclaude` writes. See `scripts/e2e-emulator.sh` (DETERMINISTIC mode) and
 * `docs/e2e-interactive-stream.md`.
 *
 * Driven by `DETERMINISTIC=1 bash scripts/e2e-emulator.sh`, which seeds the channel, pre-creates the
 * session JSONL, starts the daemon with `-pyry-claude=<fakeclaude>`, mints a device token with
 * `pyry pair`, and — after the `send_message.enqueued` cursor-stamp fence — drops the scenario's
 * fixture(s).
 * [E2eInstrumentationRunner] swaps in [E2eTestApplication] (paired + relay-backed) from the same
 * instrumentation args as rung 3.
 *
 * Deterministic, but assertions stay **tolerant** (substring + case insensitive, generous timeout,
 * never on delta counts or timing) so the test survives harmless streaming/rendering variation. The
 * scripted backend replies "ping" regardless of the prompt, so a non-"ping" prompt keeps the only
 * on-screen "ping" the scripted reply — no baseline/+1 dance is needed.
 *
 * The Compose selectors are text / content-description based (no test tags in the production UI). If
 * the UI strings — or the seeded channel name in `scripts/e2e-emulator.sh` — change, update the
 * constants below.
 */
@RunWith(AndroidJUnit4::class)
class DeterministicInteractiveStreamE2ETest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    // The spinner's content-description (production UI string, no test tags). Copied from
    // ScriptedThreadRenderTest (the Layer-1 twin). Keep in sync with res/values/strings.xml:
    //   cd_thread_thinking = "Agent is thinking".
    private val thinkingDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_thinking)

    // The tool-row status content-descriptions (production UI strings, no test tags). Read the same way
    // as thinkingDescription. Keep in sync with res/values/strings.xml:
    //   cd_tool_running = "Tool call running", cd_tool_failed = "Tool call failed".
    // Done has no positive CD (its icon's contentDescription is null), so "done" is asserted indirectly
    // (running CD gone + failed CD absent + tool name still on screen) — see the method KDoc.
    private val toolRunningDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_tool_running)

    private val toolFailedDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_tool_failed)

    @Test
    fun interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread() {
        arriveInSeededThread()

        // Type a non-"ping" prompt into the only editable field, then send. The scripted backend replies
        // "ping" regardless, so the prompt text never contributes a "ping" node.
        typeAndSend(SEND_PROMPT)

        // Wait (tolerantly) for the scripted reply to render, then confirm it is on screen. Generous
        // timeout: the fixture drop is fenced on the host-observed send_message.enqueued line, then tails
        // the real producer → Noise/relay → phone fold.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(PING, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(PING, substring = true, ignoreCase = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * `stream` scenario — a reply that arrives over three `assistant_delta` chunks (fixture
     * `stream.jsonl`) must render as **one** assembled assistant message. Asserting a substring that
     * spans the 2nd→3rd delta boundary ("streamed world") proves the deltas concatenated into a single
     * message rather than rendering as separate rows. Tolerant (substring, generous timeout); never on
     * delta count or the streaming caret.
     */
    @Test
    fun interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread() {
        arriveInSeededThread()
        typeAndSend(SEND_PROMPT)

        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(STREAMED_SUBSTRING, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(STREAMED_SUBSTRING, substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * `spinner` scenario — the thinking spinner must show **while** the turn is active and clear
     * **after** it ends. The thinking state is transient, so the harness holds the turn open: drop A
     * (`spinner-open.jsonl`, a `thinking`-only line) fires on the **1st** `send_message.enqueued` and
     * leaves the turn open with `isThinking == true` indefinitely. Only after this test asserts the
     * spinner is shown does it send a **2nd** message, whose `send_message.enqueued` triggers drop B
     * (`spinner-end.jsonl`, an end-of-turn text line) which ends the turn and clears the spinner.
     * Because drop B is causally gated on the 2nd send, the thinking window is arbitrarily long — no
     * timing dependency, no race. Tolerant (presence → absence, generous timeout); never on timing.
     */
    @Test
    fun interactiveTurn_seededChannel_showsThinkingSpinnerDuringTurn() {
        arriveInSeededThread()

        // Message #1 → drop A → turn_state(thinking), held open. The spinner appears and stays.
        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).onFirst().assertIsDisplayed()

        // Message #2 → drop B → responding + turn_end, which clears the spinner. The 2nd prompt is inert
        // for the reply (the scripted backend ignores it); it only causally fences drop B.
        typeAndSend(SECOND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNode(hasContentDescription(thinkingDescription)).assertDoesNotExist()
    }

    /**
     * `tool` scenario — a tool step must render **running** while the tool is in flight and **done**
     * after the result. "Running" is transient (the row flips to done the instant the correlated
     * `tool_result` folds in), so — exactly like the spinner — the harness holds the turn open across
     * two causally-fenced drops: drop A (`tool-open.jsonl`, a lone `tool_use`) fires on the **1st**
     * `send_message.enqueued`, opening a `Running` tool row that persists; only after this test asserts
     * the running CD does it send a **2nd** message, whose enqueue triggers drop B (`tool-done.jsonl`,
     * the correlated success `tool_result` + a turn-ending text line), flipping the row to `Done`.
     * Because drop B is gated on the 2nd send, the running window is arbitrarily long — no timing
     * dependency, no race. `Done` has no positive content-description, so it is asserted **indirectly**:
     * the running CD that was present is now absent, the failed CD never appears, and the tool row is
     * still on screen (the verbatim tool name) — a triad that uniquely identifies a running → done
     * resolution. Tolerant (presence → absence + verbatim name, generous timeout); never on timing.
     */
    @Test
    fun interactiveTurn_seededChannel_toolStepRunsThenCompletes() {
        arriveInSeededThread()

        // Message #1 → drop A → tool_use, held open. The tool row appears Running and stays.
        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(toolRunningDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasContentDescription(toolRunningDescription)).onFirst().assertIsDisplayed()

        // Message #2 → drop B → tool_result(done) + turn end. The row resolves Running → Done in place.
        // The 2nd prompt is inert for the reply (the scripted backend ignores it); it only causally
        // fences drop B.
        typeAndSend(SECOND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(toolRunningDescription)).fetchSemanticsNodes().isEmpty()
        }
        // Resolved to done, not failed (no failed glyph), and the row is still present (tool name shown).
        composeTestRule.onNode(hasContentDescription(toolFailedDescription)).assertDoesNotExist()
        composeTestRule.onAllNodesWithText(TOOL_NAME, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * `tool-failed` scenario — a failing tool step must render **failed**. The failed end state is
     * stable (it does not auto-resolve), so it needs no held-open two-drop fence: a single fixture
     * (`tool-failed.jsonl`) carries `tool_use` → an error `tool_result` (`is_error: true`) → a
     * turn-ending text line. The fold renders the row `Running` (briefly) → `Failed`; the test asserts
     * only the terminal `Failed` content-description. Tolerant (presence, generous timeout).
     */
    @Test
    fun interactiveTurn_seededChannel_failedToolStepRendersFailed() {
        arriveInSeededThread()

        // The single fixture drops tool_use + error tool_result + turn end; the row settles on Failed.
        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(toolFailedDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasContentDescription(toolFailedDescription)).onFirst().assertIsDisplayed()
    }

    /**
     * `reconnect` scenario (#476, Layer 2b) — an in-flight reply must survive a mid-turn relay-link
     * drop. The turn is held open across the outage the same way the `spinner` scenario holds it: drop A
     * (`reconnect-open.jsonl`, a `thinking`-only line) fires on the **1st** `send_message.enqueued` and
     * leaves the turn streaming. While the turn is open the test severs the phone's relay link and
     * restores it ([severAndRestoreLink]) — a **phone-side** drop only: the daemon stays up, so its
     * in-ring event buffer survives and the reconnecting phone re-advertises its `last_event_id` (#416)
     * rather than tripping the `resync`/gap path a daemon restart (#417) would. Only after the link is
     * back does the test send a **2nd** message, whose `send_message.enqueued` triggers drop B
     * (`reconnect-done.jsonl`, a complete reply + `end_turn`) completing the held-open turn to the
     * reconnected phone. The 2nd prompt is inert (the scripted backend ignores its text); it only
     * causally fences drop B, so the sever/restore injects no `send_message` and the two-drop watcher is
     * reused unchanged.
     *
     * Drop A carries **no** partial text by design: on the drop the connection-scoped repo tears down
     * (`currentRepository` → `null`) and the thread projection clears, so the **whole** reply arrives
     * post-reconnect in drop B — "no missing text" holds by construction without depending on uncertain
     * partial-turn replay semantics. The closing assert is the one deliberate count assertion in this
     * suite: `assertCountEquals(1)` on the assembled reply text is the load-bearing dedup invariant (the
     * `event_id` high-water + `message_id` upsert fold, #337/#385) — a re-delivered event must render the
     * reply **exactly once**, no duplicate row and no missing text. This is the final assembled text, not
     * a transient delta count, so it is stable; the ladder's "never on counts" rule targets delta/timing
     * counts, not this terminal invariant. Tolerant otherwise (substring, generous timeouts).
     */
    @Test
    fun interactiveTurn_seededChannel_replySurvivesMidTurnReconnect() {
        arriveInSeededThread()

        // Message #1 → drop A → turn_state(thinking), held open. The turn is now mid-stream (the spinner
        // proves the turn is open at the moment we sever).
        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).onFirst().assertIsDisplayed()

        // Sever the phone's relay link mid-turn and restore it. The daemon stays up (in-ring buffer
        // intact); on reconnect the phone re-advertises last_event_id (#416) and the turn resumes.
        severAndRestoreLink()

        // Message #2 → drop B → the complete reply + turn_end streams to the reconnected phone.
        typeAndSend(SECOND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RECONNECT_REPLY_SUBSTRING, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Exactly once: the complete reply renders in a single node — no missing text, no duplicate row.
        composeTestRule.onAllNodesWithText(RECONNECT_REPLY_SUBSTRING, substring = true).assertCountEquals(1)
    }

    /**
     * `replay-order` scenario (#477, Layer 2d) — a sequence of events produced **entirely while the
     * phone's relay link is severed** must replay **in their original production order, each exactly
     * once**, after reconnect. Where the continuity case (#476) proves a single in-flight reply survives
     * a drop, this proves a *sequence* buffered during the outage replays in order — and genuinely
     * exercises the dedup fold on a **buffered-during-outage re-delivery**, the path #476 did not reach
     * (its reply arrived strictly *after* reconnect, `event_id` > the advertised cursor → delivered once,
     * never deduplicated).
     *
     * It forks the `reconnect` two-drop fence, but the sever and restore **straddle** the event
     * production — using the split [severLink] / [restoreLink] halves of #476's atomic
     * [severAndRestoreLink] primitive. Drop A (`replay-order-open.jsonl`, a `thinking`-only line) holds
     * the turn open on the 1st `send_message.enqueued`; the test then **severs** the link, the host drops
     * drop B (`replay-order.jsonl`, three ordered `assistant_delta` lines + `end_turn`) fenced on the
     * relay logging the phone-leg disconnect — so the whole sequence accrues in the daemon's in-ring
     * buffer **while the phone is offline** — and the test **restores** the link, on which the buffered
     * sequence (all `event_id` > the advertised `last_event_id`, #416) replays whole into the fresh,
     * empty repo, which folds the deltas in arrival (= production) order.
     *
     * A bounded [OFFLINE_WINDOW_MS] hold between sever and restore keeps the phone down until the
     * producer has emitted. This is **not** the harness's forbidden fixed-delay-to-catch-a-transient: the
     * buffered events are **durable** (they replay whenever the phone returns), so erring long is free,
     * and the order / exactly-once asserts hold whether the deltas arrive as pure replay (window long
     * enough — the intended path) or as a replay/live mix (window short). A too-short window only
     * under-exercises "entirely offline"; it never false-greens (a reordering still breaks the substring)
     * nor false-reds. The window is a determinism quality knob, not a correctness razor.
     *
     * Drop A carries **no** partial text by design (same as #476): on the sever the connection-scoped
     * repo tears down (`currentRepository` → `null`) and the thread clears, so the **whole** ordered
     * sequence arrives post-reconnect into the fresh, empty repo. "No missing segment, in order" holds by
     * construction, independent of uncertain partial-turn replay semantics.
     *
     * The closing asserts are the one deliberate count assertion the suite allows (#476 precedent): the
     * **order** check matches the cross-delta-boundary concatenation [ORDERED_REPLY_SUBSTRING] (present
     * only if the deltas assembled in production order — a reordered replay breaks the substring), and the
     * **exactly-once** check `assertCountEquals(1)` on it is the load-bearing dedup invariant (`event_id`
     * high-water + `message_id` upsert, #337/#385) on a genuinely buffered-during-outage re-delivery.
     * Both key on the final assembled text (stable — any duplicate from replay is a stable extra row, not
     * a transient), so they do not violate the ladder's "never on counts" rule, which targets delta /
     * timing counts. Tolerant otherwise (substring, generous timeouts).
     */
    @Test
    fun interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect() {
        arriveInSeededThread()

        // Message #1 → drop A → turn_state(thinking), held open. The spinner proves the turn is open and
        // streaming at the moment we sever; nothing renderable has arrived yet (no partial text by design).
        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).onFirst().assertIsDisplayed()

        // Sever the phone's relay link (daemon stays up, in-ring buffer intact). While the phone is offline
        // the host drops the ordered sequence — fenced on the relay logging the phone-leg disconnect, since
        // a severed phone cannot send a 2nd send_message — so it accrues in the buffer entirely during the
        // outage. The bounded hold keeps the phone down until the producer has emitted.
        severLink()
        runBlocking { delay(OFFLINE_WINDOW_MS) }

        // Restore the link. The fresh Noise hello re-advertises last_event_id (#416); the buffered sequence
        // (all event_id > cursor) replays whole into the fresh empty repo, which folds the deltas in
        // arrival (= production) order.
        restoreLink()

        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ORDERED_REPLY_SUBSTRING, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // In order: the cross-delta-boundary concatenation is present (a reordering breaks the substring).
        // Exactly once: it renders in a single node — no segment lost, no row duplicated.
        composeTestRule.onAllNodesWithText(ORDERED_REPLY_SUBSTRING, substring = true).assertCountEquals(1)
    }

    /**
     * Steps shared by every scenario: a paired launch lands on the channel list → the host-seeded
     * promoted channel "e2e-seed" surfaces once `list_conversations` round-trips (so waiting on that
     * text implicitly waits for the connection + list response) → gate on [ConnectionState.Connected]
     * (tapping and sending both round-trip to the daemon, so the relay session must be Open) → tap the
     * channel into its thread, marked arrived by the thread-only send button.
     */
    private fun arriveInSeededThread() {
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(SEED_CHANNEL_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        awaitConnected()
        composeTestRule.onAllNodesWithText(SEED_CHANNEL_NAME).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Type [prompt] into the only editable field and tap send. The input bar clears after each send. */
    private fun typeAndSend(prompt: String) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput(prompt)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
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

    /**
     * Sever the phone's relay link and restore it atomically (#476 continuity): the drop is taken and
     * immediately healed with no gap. Re-expressed as [severLink] then [restoreLink] — the two halves
     * #477 (ordering) drives separately so event production can straddle the offline window. The
     * `replySurvivesMidTurnReconnect` test still calls this combined form unchanged.
     */
    private fun severAndRestoreLink() {
        severLink()
        restoreLink()
    }

    /**
     * Sever the phone's relay link, proving the drop truly landed (the connection-scoped repository tore
     * down to `null` — not a no-op). One half of the split drop/restore primitive (see [restoreLink]);
     * #477 holds an offline window between the two halves.
     *
     * **Phone-side only.** [RelayConnectionSupervisor.close] cancels the supervision loop and closes the
     * live socket at the transport layer; the daemon's relay session is independent and stays up, so its
     * in-ring event buffer survives — this can never trip the `resync`/gap path a daemon restart (#417)
     * would.
     *
     * **Race-free.** `close()` leaves the loop cancelled (no auto-redial racing the explicit drive), so
     * the `currentRepository == null` phase is stable; the `first { … }` await over the coordinator's
     * `StateFlow` cannot be conflated away. Mirrors [awaitConnected]'s `runBlocking { withTimeout { … } }`
     * idiom; both singletons resolve off Koin like [ConnectionStateSource].
     */
    private fun severLink() {
        val supervisor = GlobalContext.get().get<RelayConnectionSupervisor>()
        val coordinator = GlobalContext.get().get<RelayRepositoryCoordinator>()
        runBlocking {
            withTimeout(RECONNECT_TIMEOUT_MS) {
                supervisor.close()
                coordinator.currentRepository.first { it == null }
            }
        }
    }

    /**
     * Restore the phone's relay link, proving the phone re-attached (a fresh Noise pump reached `Open`).
     * The other half of the split primitive (see [severLink]).
     *
     * [RelayConnectionSupervisor.connect] starts a fresh supervision loop → new dial → a new Noise
     * `hello` re-advertising `last_event_id` (#416), read live off the coordinator's surviving
     * `replayCursor`; the daemon then replays any events buffered while the phone was offline. Awaiting
     * `currentRepository != null` proves true end-to-end readiness (the fold can receive replay), not
     * bare socket-up — an idle-`Connected` `ConnectionState` after `close()` would false-green a
     * `ConnectionState.Connected` check.
     */
    private fun restoreLink() {
        val supervisor = GlobalContext.get().get<RelayConnectionSupervisor>()
        val coordinator = GlobalContext.get().get<RelayRepositoryCoordinator>()
        runBlocking {
            withTimeout(RECONNECT_TIMEOUT_MS) {
                supervisor.connect()
                coordinator.currentRepository.first { it != null }
            }
        }
    }

    private companion object {
        const val PING = "ping"

        // The host-seeded promoted channel's name (scripts/e2e-emulator.sh writes conversations.json
        // with name="e2e-seed", is_promoted=true). Keep the two in sync. Deliberately does NOT contain
        // "ping": the channel name renders verbatim in the thread top bar (ThreadTopAppBar shows
        // displayName), and the reply assert below is a "ping" substring match — a "ping"-bearing name
        // would satisfy that assert on the title alone and false-green the test even if the scripted
        // reply never arrived.
        const val SEED_CHANNEL_NAME = "e2e-seed"

        // A non-"ping" prompt: the scripted backend ignores it and always replies "ping", so the only
        // on-screen "ping" is the scripted reply — no baseline/count dance needed. Reused as the first
        // prompt of every scenario (the scripted reply is prompt-independent).
        const val SEND_PROMPT = "hello"

        // The spinner scenario's 2nd prompt. Its only role is to causally fence drop B (the turn-ending
        // fixture) on the 2nd send_message.enqueued — its text is inert (the scripted reply ignores it).
        const val SECOND_PROMPT = "bye"

        // The `stream` fixture's three deltas assemble into "Hello, streamed world"; this substring spans
        // the 2nd→3rd delta boundary, so matching it proves the deltas concatenated into one message.
        // Neither word collides with the seeded channel name "e2e-seed" rendered in the top bar.
        const val STREAMED_SUBSTRING = "streamed world"

        // The tool scenarios' verbatim tool name (carried through the fold from the envelope `name`,
        // ToolCallRow renders it in the collapsed header). Asserted in the running → done case to prove
        // the row resolved in place rather than vanishing. Does not collide with the seeded channel name
        // "e2e-seed" rendered in the top bar.
        const val TOOL_NAME = "Bash"

        // The reconnect scenario's drop-B reply text ("reconnected reply ok" in reconnect-done.jsonl); this
        // substring is asserted to render exactly once after the mid-turn drop. A unique, self-documenting
        // phrase that collides with nothing else on screen (the "e2e-seed" title, "ping", "Bash",
        // "streamed world").
        const val RECONNECT_REPLY_SUBSTRING = "reconnected reply"

        // The replay-order scenario's drop-B sequence assembles into "alpha bravo charlie" (three deltas
        // "alpha "/"bravo "/"charlie" in replay-order.jsonl). The asserted substring spans all three delta
        // boundaries, so matching it requires the deltas to have assembled in production order — a reordered
        // replay ("bravo alpha charlie") fails the match. Collides with nothing else on screen (the
        // "e2e-seed" title, "ping", "Bash", "streamed world", "reconnected reply", the inert "hello" prompt).
        const val ORDERED_REPLY_SUBSTRING = "alpha bravo charlie"

        // Production UI string (no test tags exist). Keep in sync with res/values/strings.xml:
        //   cd_send_message = "Send message".
        const val CD_SEND_MESSAGE = "Send message"

        const val LIST_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val THREAD_TIMEOUT_MS = 30_000L

        // The two currentRepository awaits in severLink/restoreLink (drop-to-null, then reconnect-to-non-null);
        // reuses the connect/thread wait budget — an immediate, test-triggered reconnect, not a backoff.
        const val RECONNECT_TIMEOUT_MS = 30_000L

        // The replay-order offline window: how long the phone stays severed between severLink() and
        // restoreLink() so the ordered sequence is produced entirely while it is offline. NOT a
        // catch-a-transient delay — the buffered events are durable, so erring long is free; erring short
        // only under-exercises "entirely offline" without breaking the order/exactly-once verdict. Must
        // exceed [relay disconnect-detect + watcher poll 0.5s + fakeclaude append + one producer tail
        // cycle]. Primary first-run tuning point — lengthen if the replay arrives as a live mix.
        const val OFFLINE_WINDOW_MS = 5_000L

        // Generous: the fixture drop is fenced on send_message.enqueued, then tails the real producer
        // over the relay. Also used as the spinner scenario's presence/absence timeout.
        const val REPLY_TIMEOUT_MS = 90_000L
    }
}

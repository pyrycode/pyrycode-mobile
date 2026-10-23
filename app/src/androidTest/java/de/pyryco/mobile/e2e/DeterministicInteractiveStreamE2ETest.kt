package de.pyryco.mobile.e2e

import android.content.pm.ShortcutManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.grantNotificationPermission
import de.pyryco.mobile.notifications.NotificationTap
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * Ten scenarios, one per script invocation (the harness runs exactly one `@Test` method per run,
 * selected by `SCENARIO` in `scripts/e2e-emulator.sh`):
 *  - `ping` (default, #431) — a single-line reply renders.
 *  - `stream` (#454, #1765) — arrived words display while held open, then complete in the same reply.
 *  - `reopen-stream` (#1762) — arrived prefix renders immediately on reopening, before its display-checkpoint release.
 *  - `spinner` (#454) — the thinking spinner shows mid-turn, then clears at turn end (a two-fixture
 *    drop holds the turn open so the transient state is observable; see the method KDoc).
 *  - `tool` (#455) — a tool step renders running mid-turn, then done after the result (two-fixture
 *    drop, same causal fence as the spinner; see the method KDoc).
 *  - `tool-failed` (#455) — a failing tool step renders failed (single terminal drop).
 *  - `tool-then-text` (#1417) — reply text written after a tool step renders in its own node below the
 *    tool row, and the text before it above (single terminal drop; see the method KDoc).
 *  - `tool-progress` (#950) — the status area's running-tool label shows claude's elapsed reading after a
 *    `tool_progress` heartbeat, then clears when the call's `tool_result` lands (see the method KDoc).
 *  - `reconnect` (#476) — an in-flight reply survives a mid-turn relay-link drop: the turn is held
 *    open (two-fixture drop, spinner-style), the phone's link is severed and restored across the gap,
 *    and the reply renders exactly once after reconnect (see the method KDoc).
 *  - `replay-order` (#477) — a sequence of events produced **entirely while the phone is offline**
 *    replays **in production order, each exactly once** after reconnect: the turn is held open, the
 *    link is severed, the ordered sequence accrues in the daemon's in-ring buffer during the outage
 *    (drop B released by the phone's explicit control after deliberate sever), then the link is
 *    restored and the buffered sequence replays in order (see the method KDoc).
 *  - `refusal` (#1360) — a session-scoped `model_refusal_fallback` offers Switch back on its row; the tap
 *    writes the original model, the button goes, and a fresh settings reading names that model.
 *  - `mcp-failed` (#1457) — fakeclaude's first `mcp_status` answer names a `failed` server; the thread's
 *    Error pill shows, and its tap opens Channel info on the MCP servers section.
 *  - `context-overflow` (#1473) — the first turn ends `is_error` / `prompt_too_long`; the status area offers
 *    Compact, whose `/compact` reaches the daemon's child and comes back as fakeclaude's echo.
 *
 * It is a thin variant of [InteractiveStreamE2ETest] (rung 3). **One** step differs: instead of tapping
 * the host row's add control (which mints a *fresh* per-conversation claude session that `fakeclaude` —
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
    init {
        grantNotificationPermission()
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /** #1818: reply's keyboard check needs a real IME, which ATD images omit; selected per method. */
    @get:Rule
    val testIme = TestImeRule()

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

    // The status area's running-tool label (#897), the only producer of these descriptions. Keep in sync with
    // res/values/strings.xml: cd_thread_tool_running = "Claude is running %1$s",
    // cd_thread_tool_running_elapsed = "Claude is running %1$s, %2$s elapsed". The fixtures' heartbeat reads 30.
    private val runningToolLabel: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_tool_running, TOOL_NAME)

    private val switchBackPrefix: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_refusal_switch_back)

    // The literal text before the server name in thread_mcp_server_failed ("MCP server "). The trailing space
    // keeps Channel info's "MCP servers" header from matching the pill.
    private val mcpFailedPrefix: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.thread_mcp_server_failed, MCP_NAME_CUT)
            .substringBefore(MCP_NAME_CUT)

    private val contextNotice: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_recovery_context)

    private val runningToolElapsedLabel: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_tool_running_elapsed, TOOL_NAME, HEARTBEAT_ELAPSED)

    /** #1731: private tagged daemons retain their Runner and session through failure release. */
    @Test
    fun interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog() {
        SessionErrorRecoveryScenario(composeTestRule).run()
    }

    /** #1674: selection-copy runs the live selection assertion on a fixed finished multi-word reply. */
    @Test
    fun interactiveTurn_seededChannel_systemCopyCopiesSelectedWord() {
        arriveInSeededThread()
        val repository =
            requireNotNull(
                GlobalContext
                    .get()
                    .get<RelayRepositoryCoordinator>()
                    .currentRepository.value,
            )
        val conversationId =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    repository
                        .observeConversations(ConversationFilter.All)
                        .first { rows -> rows.any { it.name == SEED_CHANNEL_NAME } }
                        .single { it.name == SEED_CHANNEL_NAME }
                        .id
                }
            }
        typeAndSend(SELECTION_PROMPT)
        composeTestRule.assertFinishedReplySystemCopy(
            GlobalContext.get().get<RelayRepositoryCoordinator>().currentRepository,
            conversationId,
            REPLY_TIMEOUT_MS,
        )
    }

    /** Preparation only. A host process executes actual app death after this instrumentation exits. */
    @Test
    fun interactiveTurn_externalForceStop_preparesSettledChannel() {
        arriveInSeededThread()
        val args = InstrumentationRegistry.getArguments()
        val serverId = requireNotNull(args.getString("serverId"))
        val repo =
            requireNotNull(
                GlobalContext
                    .get()
                    .get<RelayRepositoryCoordinator>()
                    .currentRepository.value,
            )
        val name = "e2e-stop-" + System.currentTimeMillis()
        val conversation = runBlocking { repo.createDiscussion().also { repo.promote(it.id, name) } }
        val prefix = "e2e1833-stop-" + System.currentTimeMillis()
        DaemonFaultControl().posts(name, "$prefix-baseline", 1)
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) { composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNodeWithText(name).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("$prefix-baseline-000").fetchSemanticsNodes().isNotEmpty()
        }
        val cache = GlobalContext.get().get<ConversationCache>()
        runBlocking {
            withTimeout(THREAD_TIMEOUT_MS) {
                while (cache
                        .readAuthoritativeThread(serverId, conversation.id)
                        ?.let {
                            de.pyryco.mobile.ui.conversations.thread
                                .projectAuthoritativeThread(
                                    serverId,
                                    conversation.id,
                                    it.toSnapshot(),
                                ).items
                        }.orEmpty()
                        .filterIsInstance<ThreadItem.MessageItem>()
                        .none { it.message.content == "$prefix-baseline-000" && !it.message.isStreaming }
                ) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.filesDir, "e2e1833-proof.json").writeText(
            kotlinx.serialization.json
                .buildJsonObject {
                    put("conversation_id", kotlinx.serialization.json.JsonPrimitive(conversation.id))
                    put("channel", kotlinx.serialization.json.JsonPrimitive(name))
                    put("prefix", kotlinx.serialization.json.JsonPrimitive(prefix))
                }.toString(),
        )
    }

    @Test
    fun interactiveTurn_directShareShortcut_stagesBeforeExplicitSend() {
        arriveInSeededThread()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ShortcutManager::class.java)
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { manager.dynamicShortcuts.any { it.shortLabel.toString() == SEED_CHANNEL_NAME } }
        val shortcut = manager.dynamicShortcuts.single { it.shortLabel.toString() == SEED_CHANNEL_NAME }
        val target =
            requireNotNull(
                NotificationTap
                    .target(shortcut.intent),
            )
        composeTestRule.runOnUiThread {
            context.startActivity(
                android.content.Intent(context, MainActivity::class.java).apply {
                    action = android.content.Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, SEND_PROMPT)
                    putExtra(android.content.Intent.EXTRA_SHORTCUT_ID, shortcut.id)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and hasText(SEND_PROMPT)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasText("Share to…")).assertCountEquals(0)
        val repository =
            requireNotNull(GlobalContext.get().get<HostConversationSource>().repositoryFor(target.serverId))
        val evidence = harnessThreadEvidence(repository)
        val before = runBlocking { evidence.observe(target.conversationId).first() }
        assertTrue("nothing may be sent before the explicit tap", before.holdsNoUserMessage())
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(PING, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(PING, substring = true).onFirst().assertIsDisplayed()
        runBlocking {
            withTimeout(REPLY_TIMEOUT_MS) {
                evidence.observe(target.conversationId).first { it.ownedUserEcho(before.owner, SEND_PROMPT) != null }
            }
        }
    }

    /** #1866: the native fixture holds the suggestion until the explicit submission clears it. */
    @Test
    fun interactiveTurn_seededChannel_replySuggestionLongPressSends() {
        arriveInSeededThread()
        typeAndSend(SEND_PROMPT)
        val repositories = GlobalContext.get().get<RelayRepositoryCoordinator>().currentRepository
        val conversation =
            runBlocking {
                withTimeout(REPLY_TIMEOUT_MS) {
                    repositories
                        .firstOnLive(
                            { it.observeConversations(ConversationFilter.All) },
                        ) { rows -> rows.any { it.name == SEED_CHANNEL_NAME } }
                        .first { it.name == SEED_CHANNEL_NAME }
                        .id
                }
            }
        composeTestRule.assertReplySuggestionLongPress(repositories, conversation, REPLY_TIMEOUT_MS)
    }

    /** Zero-Claude durable twin runs beside the original ping scenario, with its own channel. */
    @Test
    fun interactiveTurn_seededChannel_durableGapCatchUp() {
        arriveInSeededThread()
        val args = InstrumentationRegistry.getArguments()
        val serverId = requireNotNull(args.getString("serverId"))
        val repo =
            requireNotNull(
                GlobalContext
                    .get()
                    .get<RelayRepositoryCoordinator>()
                    .currentRepository.value,
            )
        val name = "e2e-gap-" + System.currentTimeMillis()
        val conversation = runBlocking { repo.createDiscussion().also { repo.promote(it.id, name) } }
        val fault = DaemonFaultControl()
        fault.posts(name, "e2e1833-baseline", 1)
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) { composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNodeWithText(name).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("e2e1833-baseline-000").fetchSemanticsNodes().isNotEmpty()
        }
        val proof = DurableGapProof(composeTestRule, serverId, conversation.id)
        proof.cacheBaseline()
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId,
                    requireNotNull(args.getString("peerToken")),
                    requireNotNull(args.getString("relayUrl")),
                    requireNotNull(args.getString("serverStaticPublicKey")),
                ),
            )
        try {
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            // This scripted fixture has no settings subscriber. Read the physical session through
            // the existing correlated probe; create-conversation summaries intentionally omit it.
            val coordinator = GlobalContext.get().get<RelayRepositoryCoordinator>()
            val session =
                runBlocking {
                    withTimeout(THREAD_TIMEOUT_MS) {
                        SettingsReadProbe
                            .read(
                                repo as RemoteConversationRepository,
                                conversation.id,
                                { coordinator.currentRepository.value === repo },
                                { coordinator.hostReadings.liveSessionBindings[conversation.id] },
                            ).sessionId
                            .also { check(it.isNotBlank()) { "scripted conversation has no admitted session" } }
                    }
                }
            val before = runBlocking { harnessThreadEvidence(repo).observe(conversation.id).first() }
            val observation = OwnedReplyObservation(serverId, before, legacyProducer = true, knownSession = session)
            peer.replyObservation = observation
            var produced: ProducedReplyEvidence? = null
            severLink()
            composeTestRule.onNodeWithText("e2e1833-baseline-000").assertIsDisplayed()
            proof.missPages(name) {
                runBlocking {
                    withTimeout(REPLY_TIMEOUT_MS) {
                        while (peer.recorded(conversation.id).none {
                                it.type == "assistant_delta" && peer.field(it, "text") == proof.lastOlderPost
                            }
                        ) {
                            kotlinx.coroutines.delay(50)
                        }
                    }
                    peer.sendMessage(conversation.id, "e2e1833-completed-turn", THREAD_TIMEOUT_MS)
                    withTimeout(REPLY_TIMEOUT_MS) {
                        while (peer.recorded(conversation.id).none {
                                it.type == "turn_end" && peer.field(it, "producer") != "channel_post"
                            }
                        ) {
                            delay(50)
                        }
                    }
                    produced = checkNotNull(observation.produced()) { observation.producerState() }
                    assertEquals("scripted fixture keeps its exact reply contract", "ping", produced!!.text)
                }
            }
            proof.catchUp(::restoreLink, "e2e1833-completed-turn", checkNotNull(produced), listOf(hasText("e2e1833-baseline-000")))
            assertEquals("cached older row retained", 1, proof.messages().count { it == "e2e1833-baseline-000" })
        } finally {
            peer.close()
        }
    }

    @Test
    fun interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread() {
        arriveInSeededThread()

        // The authoritative initial thread reports an empty task set without a legacy roster (#2245).
        composeTestRule.onNode(hasContentDescription("More actions")).performClick()
        composeTestRule.onNode(hasText("Background tasks") and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Channel info").assertDoesNotExist()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("No background tasks").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("No background tasks").assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("Close")).performClick()
        composeTestRule.onNodeWithText("Background tasks").assertDoesNotExist()

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
        // #1912: the scripted reply must be confirmed as read after it is rendered in the foreground.
        val args = InstrumentationRegistry.getArguments()
        val serverId = requireNotNull(args.getString("serverId"))
        val registry = GlobalContext.get().get<de.pyryco.mobile.di.RelayConnectionRegistry>()
        val coordinator = requireNotNull(registry.connectionFor(serverId)).coordinator
        val repository = requireNotNull(coordinator.currentRepository.value)
        val destinations = GlobalContext.get().get<de.pyryco.mobile.di.ThreadDestinationFactory>()
        val route = requireNotNull(destinations.repository(serverId).negotiatedThread)

        fun ownsConnection() = (route.support.value as? de.pyryco.mobile.data.repository.ThreadSupport.Supported)?.connection === repository
        assertTrue("read proof requires the negotiated connection", ownsConnection())
        val evidence = harnessThreadEvidence(repository)
        assertEquals(ThreadEvidenceKind.Authoritative, evidence.kind)
        val conversationId =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    repository
                        .observeConversations(ConversationFilter.All)
                        .first { rows -> rows.any { it.name == SEED_CHANNEL_NAME } }
                        .single { it.name == SEED_CHANNEL_NAME }
                        .id
                }
            }
        val reply =
            runBlocking {
                withTimeout(REPLY_TIMEOUT_MS) {
                    evidence.observe(conversationId).first { it.loadedMainReply()?.content?.contains(PING, ignoreCase = true) == true }
                }
            }
        val replyFact = requireNotNull(reply.records.single { it.message.id == reply.loadedMainReply()?.id }.fact)
        val checkpoint = replyFact.rev.toULong()
        runBlocking {
            withTimeout(REPLY_TIMEOUT_MS) {
                route.observe(conversationId).first {
                    ownsConnection() &&
                        it.epoch == reply.owner.epoch &&
                        it.generation == reply.owner.generation &&
                        it.checkpoint.toULong() >= checkpoint
                }
            }
        }
        // Keep driving Compose frames while layout/reveal qualification catches up with receipt.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            runBlocking {
                val marks = repository.observeReadMarks(conversationId).first()
                val shown = marks?.lastShownVersion
                ownsConnection() &&
                    evidence
                        .observe(conversationId)
                        .first()
                        .owner
                        .matches(reply.owner) &&
                    marks?.threadSupported == true &&
                    shown != null &&
                    shown >= replyFact.id.toULong() &&
                    (marks.readUpTo ?: 0uL) >= shown
            }
        }

        // #1883 rung 4: a new durable post stays unread until a peer reads it, with no phone reopen.
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        // The owned post control admits only the existing e2e1833 fixture prefix.
        DaemonFaultControl().posts(SEED_CHANNEL_NAME, "e2e1833-attention-read", 1)
        val postText = "e2e1833-attention-read-000"
        runBlocking {
            withTimeout(REPLY_TIMEOUT_MS) {
                val current =
                    requireNotNull(
                        kotlinx.coroutines.flow
                            .combine(coordinator.currentRepository, route.support) { candidate, support ->
                                candidate?.takeIf {
                                    (support as? de.pyryco.mobile.data.repository.ThreadSupport.Supported)?.connection === it
                                }
                            }.first { it != null },
                    )
                current.observeConversations(ConversationFilter.All).first()
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val row = hasText(SEED_CHANNEL_NAME) and hasTestTag(de.pyryco.mobile.ui.conversations.list.TREE_CHANNEL_ROW_TEST_TAG)
        val unread = context.getString(R.string.cd_conversation_attention_unread)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(row and hasContentDescription(unread)).fetchSemanticsNodes().isNotEmpty()
        }
        SecondClientPeer(
            PairedServer(
                requireNotNull(args.getString("serverId")),
                requireNotNull(args.getString("peerToken")),
                requireNotNull(args.getString("relayUrl")),
                requireNotNull(args.getString("serverStaticPublicKey")),
            ),
            threadEnabled = true,
        ).use { peer ->
            runBlocking {
                peer.open(CONNECT_TIMEOUT_MS)
                val post =
                    peer.threadWindow(conversationId, THREAD_TIMEOUT_MS).single {
                        it.kind == "assistant_message" && (it.content as? JsonObject)?.get("text")?.jsonPrimitive?.content == postText
                    }
                val facts =
                    withTimeout(THREAD_TIMEOUT_MS) {
                        var reading = peer.readMarks(conversationId, THREAD_TIMEOUT_MS)
                        while (reading.lastShownVersion?.let { it >= post.id.toULong() } != true) {
                            delay(50)
                            reading = peer.readMarks(conversationId, THREAD_TIMEOUT_MS)
                        }
                        reading
                    }
                val shown = requireNotNull(facts.lastShownVersion)
                assertTrue("peer received an authoritative unread post", facts.threadSupported && shown >= post.id.toULong())
                assertTrue("the unseen post is still unread", (facts.readUpTo ?: 0uL) < shown)
                assertTrue("peer read confirmed", peer.markRead(conversationId, shown, THREAD_TIMEOUT_MS) >= shown)
            }
            val idle = context.getString(R.string.cd_conversation_attention_idle)
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onAllNodes(row and hasContentDescription(idle)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag(CHANNEL_LIST_TEST_TAG).assertIsDisplayed()
        }
    }

    /**
     * `refusal` scenario (#1360) — the fixture's claude line is a session-scoped `model_refusal_fallback` from
     * `haiku` to `sonnet`, then a reply. fakeclaude's canned menu publishes `haiku`, so the refusal row names it
     * by its menu label (#1494) and offers "Switch back to Haiku"; one tap writes `haiku` to the session through
     * the real daemon, the button goes once the write is acknowledged, and a fresh `request_session_settings`
     * reply names `haiku`. `haiku` because the scripted daemon accepts only
     * fakeclaude's canned menu, which it holds once the turn has spawned fakeclaude, so the tap waits for the
     * reply. The held reading a subscription opens with is skipped, as #1397 does for the live class.
     */
    @Test
    fun interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel() {
        arriveInSeededThread()
        typeAndSend(SEND_PROMPT)

        val switchBack = switchBackPrefix + REFUSAL_ORIGINAL_LABEL
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(REFUSAL_REPLY, substring = true).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodes(hasText(switchBack) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(switchBack, substring = true).assertCountEquals(1)
        composeTestRule.onNode(hasText(switchBack) and hasClickAction()).performClick()

        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(switchBackPrefix, substring = true).fetchSemanticsNodes().isEmpty()
        }
        val repository =
            requireNotNull(
                GlobalContext
                    .get()
                    .get<RelayRepositoryCoordinator>()
                    .currentRepository.value,
            )
        val fresh =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    val seeded =
                        repository.observeConversations(ConversationFilter.All).first { list ->
                            list.any {
                                it.name ==
                                    SEED_CHANNEL_NAME
                            }
                        }
                    val id = seeded.first { it.name == SEED_CHANNEL_NAME }.id
                    repository.observeSessionSettings(id).filterNotNull().first { !it.held }
                }
            }
        assertEquals(REFUSAL_ORIGINAL_MODEL, fresh.model)
    }

    /**
     * `mcp-failed` scenario (#1457) — every deterministic run sets `PYRY_FAKE_CLAUDE_MCP_STATUS=1`, so fakeclaude
     * answers the daemon's own first `mcp_status` ask, made once the turn spawns it, with `pyry_mcp_test` /
     * `failed`, and the daemon publishes that report to the conversation. The thread shows the Error pill; its
     * tap opens Channel info on the MCP servers section. The sheet's own ask gets fakeclaude's later
     * `connected` answer, so the failed name is not asserted there.
     */
    @Test
    fun interactiveTurn_seededChannel_failedMcpServerPillOpensChannelInfo() {
        arriveInSeededThread()
        typeAndSend(SEND_PROMPT)

        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(MCP_FAILED_REPLY, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val pill = hasClickAction() and SemanticsMatcher("text starts with $mcpFailedPrefix") { nodeText(it).startsWith(mcpFailedPrefix) }
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(pill).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodes(pill)
            .onFirst()
            .assertIsDisplayed()
            .performClick()

        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(MCP_SECTION_HEADER).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(MCP_SECTION_HEADER)
            .onFirst()
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * `context-overflow` scenario (#1473) — the fixture's `result` is `is_error` with `terminal_reason`
     * `prompt_too_long`, so the top overlay shows the combined context/Compact pill. The tap sends `/compact`, which
     * the daemon hands its child as an ordinary message, and fakeclaude answers any later turn by echoing the
     * prompt. The phone's sent row and that echo both read exactly `/compact`, with no role tag between them,
     * so the second such node is the daemon's answer. The send clears the notice.
     */
    @Test
    fun interactiveTurn_seededChannel_contextOverflowCompactReachesDaemon() {
        arriveInSeededThread()
        typeAndSend(SEND_PROMPT)

        val compact = hasContentDescription(contextNotice) and hasClickAction()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(compact).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(COMPACT_COMMAND).assertCountEquals(0)
        composeTestRule
            .onAllNodes(compact)
            .onFirst()
            .assertIsDisplayed()
            .performTouchInput {
                click(centerLeft + Offset(2f, 0f))
            }

        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(COMPACT_COMMAND).fetchSemanticsNodes().size >= 2
        }
        composeTestRule.onAllNodes(hasContentDescription(contextNotice)).assertCountEquals(0)
    }

    /** The real daemon-absent state keeps Retry visible until the pill restores this seeded thread (#1286). */
    @Test
    fun interactiveTurn_seededChannel_offlineRetryRestoresScriptedReply() {
        arriveInSeededThread()
        val supervisor = GlobalContext.get().get<RelayConnectionSupervisor>()
        val coordinator = GlobalContext.get().get<RelayRepositoryCoordinator>()
        val fault = DaemonFaultControl()
        try {
            val retryDeadline =
                fault.stopUntilRetryWindow(supervisor) {
                    composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                        composeTestRule.onAllNodes(hasTestTag("offline_retry_target")).fetchSemanticsNodes().isNotEmpty()
                    }
                    composeTestRule.onNodeWithTag("offline_retry_target").assertIsDisplayed()
                }
            assertNull(coordinator.currentRepository.value)
            fault.start()
            composeTestRule.onNodeWithTag("offline_retry_target").assertIsDisplayed()
            assertNull(coordinator.currentRepository.value)
            composeTestRule.onNodeWithTag("offline_retry_target").performClick()
            runBlocking {
                withTimeout(fault.recoveryTimeRemaining(retryDeadline)) {
                    coordinator.currentRepository.first { it != null }
                }
            }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag("offline_retry_target")).fetchSemanticsNodes().isEmpty()
            }
            typeAndSend(SEND_PROMPT)
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(PING, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodesWithText(PING, substring = true, ignoreCase = true).onFirst().assertIsDisplayed()
        } finally {
            runCatching { fault.start() }
        }
    }

    /** #1762: fragment B cannot arrive until the phone proves the immediate reopen checkpoint. */
    @Test
    fun interactiveTurn_seededChannel_reopenOngoingReplyShowsArrivedPrefixImmediately() {
        arriveInSeededThread()
        val coordinator = GlobalContext.get().get<RelayRepositoryCoordinator>()
        val repository = requireNotNull(coordinator.currentRepository.value)
        val conversationId =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    repository
                        .observeConversations(ConversationFilter.All)
                        .first { rows -> rows.any { it.name == SEED_CHANNEL_NAME } }
                        .single { it.name == SEED_CHANNEL_NAME }
                        .id
                }
            }
        val inBubble = hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
        val prefix = hasText(REOPEN_PREFIX, substring = true) and inBubble
        // Retire the captured source before the prefix, reproducing #2463 without a timing race.
        severAndRestoreLink()
        assertTrue("the prefix must arrive through a replacement source", coordinator.currentRepository.value !== repository)
        typeAndSend(SEND_PROMPT)
        android.util.Log.i(
            "ReopenStreamProof",
            "phase=await_prefix captured_source_current=${repository === coordinator.currentRepository.value}",
        )
        val arrived =
            try {
                runBlocking {
                    withTimeout(REPLY_TIMEOUT_MS) {
                        coordinator.currentRepository.firstOnLive(
                            { current ->
                                harnessThreadEvidence(current)
                                    .observe(conversationId)
                                    .onEach { reading ->
                                        android.util.Log.i(
                                            "ReopenStreamProof",
                                            "phase=prefix_read source_current=${current === coordinator.currentRepository.value} " +
                                                "admitted=${reading.admitted} assistants=${reading.assistantRecords().size} " +
                                                "live=${(current as? RemoteConversationRepository)?.usesAuthoritativeLiveState} " +
                                                "has_epoch=${reading.sourceSnapshot?.epoch != null} " +
                                                "repair=${reading.sourceSnapshot?.repairNeeded} " +
                                                "reset=${reading.sourceSnapshot?.resetNeeded} " +
                                                "generation=${reading.sourceSnapshot?.generation} " +
                                                "facts=${reading.sourceSnapshot?.items?.size} " +
                                                "prefix=${reading.openReply(reading.owner)?.fullText == REOPEN_PREFIX}",
                                        )
                                    }
                            },
                        ) { it.openReply(it.owner)?.fullText == REOPEN_PREFIX }
                    }
                }
            } finally {
                android.util.Log.i(
                    "ReopenStreamProof",
                    "phase=prefix_wait_exit captured_source_current=${repository === coordinator.currentRepository.value} " +
                        "replacement_available=${coordinator.currentRepository.value != null}",
                )
            }
        // Acquire on the live connection, then retain that exact owner for every display/settlement check.
        val replyRepository = requireNotNull(arrived.owner.connection as? RemoteConversationRepository)
        assertTrue("the admitted prefix source must still be current", replyRepository === coordinator.currentRepository.value)
        val evidence = harnessThreadEvidence(replyRepository)
        val arrivedReply = requireNotNull(arrived.openReply(arrived.owner))

        fun assistantRows() = runBlocking { evidence.observe(conversationId).first() }

        fun assertOpenReply(expected: String) {
            val rows = assistantRows()
            assertEquals("only the held reply may exist before completion", 1, rows.assistantRecords().size)
            val row = requireNotNull(rows.openReply(arrived.owner)) { "the reply must remain streaming at the display checkpoint" }
            assertEquals(expected, row.fullText)
            assertTrue(
                "the open reply must be the arrived prefix's item",
                if (expected == REOPEN_PREFIX) row.isSameItem(arrivedReply) else row.advancesFrom(arrivedReply),
            )
            assertNotEquals(LiveSessionEvent.TurnState.Phase.Idle, runBlocking { replyRepository.observeTurnPhase(conversationId).first() })
        }
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(prefix, useUnmergedTree = true).fetchSemanticsNodes().size ==
                1
        }
        composeTestRule.onNode(prefix, useUnmergedTree = true).assertIsDisplayed()
        assertOpenReply(REOPEN_PREFIX)
        android.util.Log.i("ReopenStreamProof", "phase=leave_thread")
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasTestTag(MESSAGE_BUBBLE_TEST_TAG), useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.mainClock.autoAdvance = false
        try {
            composeTestRule.onAllNodesWithText(SEED_CHANNEL_NAME).onFirst().performClick()
            awaitFirstReopenedStreamingBody()
            // No text-based retry: inspect the first composed streaming body before reveal catch-up.
            assertOpenReply(REOPEN_PREFIX)
            composeTestRule.onNode(prefix, useUnmergedTree = true).assertIsDisplayed()
            composeTestRule.onAllNodes(prefix, useUnmergedTree = true).assertCountEquals(1)
            composeTestRule.onAllNodesWithText(REOPEN_SUFFIX, substring = true).assertCountEquals(0)
            assertOpenReply(REOPEN_PREFIX)
            android.util.Log.i("ReopenStreamProof", "phase=reopened_prefix_committed")
            DaemonFaultControl().releaseStream()
            val combined = REOPEN_PREFIX + REOPEN_SUFFIX
            runBlocking {
                withTimeout(REPLY_TIMEOUT_MS) {
                    evidence.observe(conversationId).first { rows -> rows.assistantRecords().any { it.fullText == combined } }
                }
            }
            assertOpenReply(combined)
            // Witness the updated body, not just the repository: the first suffix word was absent
            // before drop B. A reset cannot retype the 26-word prefix within this 128 ms budget.
            val appendedWord = hasText(" and", substring = true) and inBubble
            val start = composeTestRule.mainClock.currentTime
            while (composeTestRule.onAllNodes(appendedWord, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                check(composeTestRule.mainClock.currentTime - start < 128) { "suffix did not compose while retaining the arrived prefix" }
                runBlocking { delay(250) }
                composeTestRule.mainClock.advanceTimeByFrame()
                composeTestRule.waitForIdle()
            }
            composeTestRule.onNode(appendedWord, useUnmergedTree = true).assertIsDisplayed()
            composeTestRule.onNode(prefix, useUnmergedTree = true).assertIsDisplayed()
            composeTestRule.onAllNodes(prefix, useUnmergedTree = true).assertCountEquals(1)
            assertOpenReply(combined)
        } finally {
            composeTestRule.mainClock.autoAdvance = true
        }
        // Completion is fenced separately, after the still-open suffix-arrival display checkpoint.
        android.util.Log.i("ReopenStreamProof", "phase=suffix_committed")
        DaemonFaultControl().finishStream()
        val combined = REOPEN_PREFIX + REOPEN_SUFFIX
        val fullReply = hasText(combined, substring = true) and inBubble
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(fullReply, useUnmergedTree = true).fetchSemanticsNodes().size ==
                1
        }
        composeTestRule.onNode(prefix, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNode(fullReply, useUnmergedTree = true).assertIsDisplayed()
        val settled =
            runBlocking {
                withTimeout(REPLY_TIMEOUT_MS) {
                    evidence.observe(conversationId).first { it.settledFormOf(arrived.owner, arrivedReply)?.fullText == combined }
                }
            }
        assertTrue(requireNotNull(settled.settledFormOf(arrived.owner, arrivedReply)).advancesFrom(arrivedReply))
        val final = assistantRows().assistantRecords()
        assertEquals(1, final.size)
        assertEquals(1, final.count { it.fullText == combined && !it.isOpen })
        composeTestRule.onAllNodes(fullReply, useUnmergedTree = true).assertCountEquals(1)
    }

    /** Network/composition may settle in wall time; reveal receives at most 128 ms of frames. */
    private fun awaitFirstReopenedStreamingBody() {
        val body =
            SemanticsMatcher("streaming caret inside a reply bubble") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.endsWith("▎") }
            } and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
        val start = composeTestRule.mainClock.currentTime
        while (composeTestRule.onAllNodes(body, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            check(composeTestRule.mainClock.currentTime - start < 128) { "reopened streaming body did not compose within the frame budget" }
            runBlocking { delay(250) }
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
        assertTrue(composeTestRule.mainClock.currentTime - start <= 128)
    }

    /**
     * `stream` (#1765): two arrivals stay open until their words display. The phone's checkpoint
     * releases `stream-end.jsonl`; the same reply then contains all three deltas without its caret.
     * Repository settlement and idle phase establish completion, independently of caret blinking.
     */
    @Test
    fun interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread() {
        testIme.select()
        arriveInSeededThread()
        val repository =
            requireNotNull(
                GlobalContext
                    .get()
                    .get<RelayRepositoryCoordinator>()
                    .currentRepository.value,
            )
        val conversationId =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    repository
                        .observeConversations(ConversationFilter.All)
                        .first { rows -> rows.any { it.name == SEED_CHANNEL_NAME } }
                        .single { it.name == SEED_CHANNEL_NAME }
                        .id
                }
            }
        val inBubble = hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
        val prefix =
            hasText(STREAMED_PREFIX, substring = true) and
                hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG) and !hasClickAction())
        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(prefix, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        composeTestRule.onNode(prefix, useUnmergedTree = true).assertIsDisplayed()
        val evidence = harnessThreadEvidence(repository)
        val heldEvidence = runBlocking { evidence.observe(conversationId).first() }
        assertEquals(1, heldEvidence.assistantRecords().size)
        val heldReply =
            requireNotNull(heldEvidence.openReply(heldEvidence.owner)) { "displayed arrived text must belong to an ongoing reply" }
        assertEquals(STREAMED_PREFIX, heldReply.fullText.trimEnd())
        val held = heldReply.message
        assertNotEquals(LiveSessionEvent.TurnState.Phase.Idle, runBlocking { repository.observeTurnPhase(conversationId).first() })

        acknowledgeFailedMcpPill()
        composeTestRule.assertSideMessageCopy(held)
        val sent =
            requireNotNull(runBlocking { evidence.observe(conversationId).first() }.ownedUserEcho(heldEvidence.owner, SEND_PROMPT))
                .message
        composeTestRule.assertSideMessageCopy(sent)

        val heldQuote = "Assistant:\n\"${held.content}\"\n"
        composeTestRule.assertSideMessageReply(held, heldQuote)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.runOnIdle {
                ViewCompat.getRootWindowInsets(composeTestRule.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        val staged = heldQuote + "User:\n\"${sent.content}\"\n"
        composeTestRule.assertSideMessageReply(sent, staged)
        assertNotNull(
            "exactly one owned user message while the quote is staged",
            runBlocking { evidence.observe(conversationId).first() }.ownedUserEcho(heldEvidence.owner, SEND_PROMPT),
        )
        // Release later chunks without another turn while the phone's staged quote stays open.
        DaemonFaultControl().releaseStream()
        val finished =
            runBlocking {
                withTimeout(REPLY_TIMEOUT_MS) {
                    val reply =
                        evidence
                            .observe(conversationId)
                            .first { it.settledFormOf(heldEvidence.owner, heldReply)?.fullText == STREAMED_REPLY }
                            .let { requireNotNull(it.settledFormOf(heldEvidence.owner, heldReply)) }
                    repository.observeTurnPhase(conversationId).first { it == LiveSessionEvent.TurnState.Phase.Idle }
                    // Keep the same captured reply identity through all three deltas.
                    assertTrue("the settled reply must grow from the displayed prefix", reply.advancesFrom(heldReply))
                    reply.message
                }
            }
        assertEquals(held.id, finished.id)
        val finalBody = hasText(STREAMED_REPLY) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG) and hasClickAction())
        val caret = hasText("▎", substring = true) and inBubble
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(finalBody, useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                composeTestRule.onAllNodes(caret, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNode(finalBody, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onAllNodes(caret, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.assertSideMessageCopy(finished)
        assertEquals(
            staged,
            composeTestRule
                .onNode(hasSetTextAction())
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        composeTestRule.assertSideMessageReply(finished, staged + "Assistant:\n\"${finished.content}\"\n")
    }

    /** A queued own follow-up retains its supplied identity while the held authoritative reply settles in one row. */
    @Test
    fun interactiveTurn_seededChannel_queuedPromptDoesNotSplitHeldReply() {
        arriveInSeededThread()
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString("serverId"))
        val registry = GlobalContext.get().get<de.pyryco.mobile.di.RelayConnectionRegistry>()
        val coordinator = requireNotNull(registry.connectionFor(serverId)).coordinator
        val repository = requireNotNull(coordinator.currentRepository.value)
        val hostRepository = GlobalContext.get().get<de.pyryco.mobile.di.ThreadDestinationFactory>().repository(serverId)
        val route = requireNotNull(hostRepository.negotiatedThread)
        runBlocking {
            var checkpoint = "conversation"
            var snapshotShape = "unobserved"
            try {
                withTimeout(REPLY_TIMEOUT_MS) {
                    val conversationId =
                        repository
                            .observeConversations(ConversationFilter.All)
                            .first { rows -> rows.any { it.name == SEED_CHANNEL_NAME } }
                            .single { it.name == SEED_CHANNEL_NAME }
                            .id
                    checkpoint = "held prefix"
                    val firstSent = repository.sendMessage(conversationId, SEND_PROMPT)
                    val evidence = harnessThreadEvidence(hostRepository)

                    fun currentEvidence(observed: ThreadEvidence? = null): ThreadEvidence? {
                        val supported = (route.support.value as? de.pyryco.mobile.data.repository.ThreadSupport.Supported)?.connection
                        val current = coordinator.currentRepository.value?.takeIf { it === supported } ?: return null
                        val snapshot = route.store.current(serverId, conversationId)
                        if (observed != null && !observed.isCurrentReplay(serverId, conversationId, current, snapshot)) return null
                        val fresh = authoritativeThreadEvidence(serverId, conversationId, current, snapshot)
                        return fresh.takeIf {
                            fresh.isCurrentReplay(serverId, conversationId, current, route.store.current(serverId, conversationId)) &&
                                coordinator.currentRepository.value === current &&
                                (route.support.value as? de.pyryco.mobile.data.repository.ThreadSupport.Supported)?.connection === current
                        }
                    }
                    val start = evidence.observe(conversationId).first { it.heldOpenReply(it.owner, STREAMED_PREFIX) != null }
                    val held = requireNotNull(start.heldOpenReply(start.owner, STREAMED_PREFIX))
                    val phoneDevice =
                        requireNotNull(start.sourceSnapshot)
                            .items
                            .single { fact ->
                                fact.kind == "user_message" &&
                                    (fact.content as? kotlinx.serialization.json.JsonObject)?.get("message_id")?.jsonPrimitive?.content ==
                                    firstSent.id
                            }.let { fact -> (fact.content as kotlinx.serialization.json.JsonObject)["device_id"]?.jsonPrimitive?.content }
                    require(!phoneDevice.isNullOrEmpty())
                    val prefix = hasText(STREAMED_PREFIX, substring = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
                    composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                        composeTestRule.onAllNodes(prefix, useUnmergedTree = true).fetchSemanticsNodes().size == 1
                    }
                    composeTestRule.onNode(prefix, useUnmergedTree = true).assertIsDisplayed()
                    checkpoint = "queued follow-up"
                    val sent = repository.sendMessage(conversationId, SECOND_PROMPT)
                    val queuedSnapshot =
                        evidence.observe(conversationId).first { current ->
                            current.admitted &&
                                current.owner.matches(start.owner) &&
                                current.sourceSnapshot?.items?.any { fact ->
                                    val content = fact.content as? kotlinx.serialization.json.JsonObject
                                    fact.kind == "user_message" &&
                                        fact.status == "queued" &&
                                        fact.active &&
                                        content?.get("text")?.jsonPrimitive?.content == SECOND_PROMPT &&
                                        content["message_id"]?.jsonPrimitive?.content == sent.id &&
                                        content["device_id"]?.jsonPrimitive?.content == phoneDevice
                                } == true
                        }
                    val queued =
                        requireNotNull(queuedSnapshot.sourceSnapshot).items.single { fact ->
                            val content = fact.content as? kotlinx.serialization.json.JsonObject
                            fact.kind == "user_message" &&
                                fact.status == "queued" &&
                                fact.active &&
                                content?.get("text")?.jsonPrimitive?.content == SECOND_PROMPT &&
                                content["message_id"]?.jsonPrimitive?.content == sent.id &&
                                content["device_id"]?.jsonPrimitive?.content == phoneDevice
                        }
                    val queuedContent = queued.content as kotlinx.serialization.json.JsonObject
                    assertEquals(SECOND_PROMPT, queuedContent["text"]?.jsonPrimitive?.content)
                    assertEquals(sent.id, queuedContent["message_id"]?.jsonPrimitive?.content)
                    assertEquals(
                        "the queued entry must retain phone device identity",
                        phoneDevice,
                        queuedContent["device_id"]?.jsonPrimitive?.content,
                    )
                    evidence.observe(conversationId).first { it.holdsOpen(start.owner, held) }
                    checkpoint = "same reply completion"
                    android.util.Log.i("QueuedEcho", "event=queued_reply_phase phase=queued_admitted")
                    DaemonFaultControl().releaseStream()
                    android.util.Log.i("QueuedEcho", "event=queued_reply_phase phase=released")
                    // Force the producer-gap retirement boundary before completion observation, every run.
                    severAndRestoreLink()
                    assertTrue(
                        "the completion observer must follow a replacement source",
                        coordinator.currentRepository.value !== start.owner.connection,
                    )
                    android.util.Log.i("QueuedEcho", "event=queued_reply_phase phase=replacement_authenticated")
                    evidence.observe(conversationId).first { observed ->
                        snapshotShape = "admitted=${observed.admitted};source_current=" +
                            "${observed.owner.connection === coordinator.currentRepository.value};records=${observed.records.size}"
                        val current = currentEvidence(observed) ?: return@first false
                        snapshotShape =
                            current.records.joinToString { record ->
                                val message = record.message
                                "${message.role}:${record.fullText.length}:${message.segment?.firstSeq}:${message.segment?.lastSeq}:" +
                                    "${message.isStreaming}:${message.id == held.message.id}:${record.fact?.rev}"
                            }
                        val before = requireNotNull(start.sourceSnapshot)
                        val snapshot = requireNotNull(current.sourceSnapshot)
                        val original = requireNotNull(held.fact)
                        val reply = current.records.singleOrNull { it.fact?.id == original.id }
                        val fact = reply?.fact
                        val accepted = snapshot.items.singleOrNull { it.id == queued.id }
                        val senders =
                            snapshot.items.count {
                                val content = it.content as? kotlinx.serialization.json.JsonObject
                                content?.get("message_id") == queuedContent["message_id"] &&
                                    content?.get("device_id") == queuedContent["device_id"]
                            }
                        snapshotShape += ";document=${before.incarnation.isNotBlank()}:${before.incarnation == snapshot.incarnation}:" +
                            "${before.epoch == snapshot.epoch}:${before.generation == snapshot.generation}" +
                            ";reply=${fact?.session == original.session}:${fact?.turn == original.turn}:" +
                            "${fact?.agent == original.agent}:${fact?.parent == original.parent}:${fact?.order == original.order}" +
                            ";family=${current.settledReply(
                                current.owner,
                                requireNotNull(held.session),
                                requireNotNull(held.turn),
                            )?.size}" +
                            ";queue=${accepted != null}:$senders:${accepted?.status == "delivered"}:" +
                            "${(accepted?.content as? kotlinx.serialization.json.JsonObject)?.get("text") == queuedContent["text"]}"
                        current.records.any {
                            it.fact?.id == original.id && it.fullText == STREAMED_REPLY && !it.message.isStreaming
                        }
                    }
                    checkpoint = "rendered reply"
                    composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { composeTestRule.renderedReplyText(STREAMED_REPLY) == STREAMED_REPLY }
                    // Rendering can publish more facts. Reconcile only freshly admitted current evidence.
                    val fresh = requireNotNull(currentEvidence()) { "queued reply source retired during rendering" }
                    // The held item's one settled form, alone in its turn: no suffix row split it.
                    assertNotNull(
                        "the held reply must settle alone; shape=$snapshotShape",
                        fresh.settledQueuedReplyAfterRecovery(start, held, queued, STREAMED_REPLY, coordinator.currentRepository.value),
                    )
                    android.util.Log.i(
                        "QueuedEcho",
                        "event=queued_reply_phase phase=completed source_replaced=${fresh.owner.connection !== start.owner.connection}",
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("queued echo checkpoint timed out: $checkpoint; shape=$snapshotShape", e)
            }
        }
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

    /** #1642 rung 4: release the tool result and user echo only after actual send-now delivery. */
    @Test
    fun interactiveTurn_seededChannel_sendQueuedNow_placesAfterToolResult() {
        arriveInSeededThread()
        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(toolRunningDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        val repo =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    harnessHostRepository(GlobalContext.get().get<RelayRepositoryCoordinator>())
                }
            }
        val conversationId =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    repo
                        .observeConversations(ConversationFilter.All)
                        .first { rows -> rows.any { it.name == SEED_CHANNEL_NAME } }
                        .first { it.name == SEED_CHANNEL_NAME }
                        .id
                }
            }
        repo.refreshSessionSettings(conversationId)
        typeAndSend(SECOND_PROMPT)
        val queued = hasText(SECOND_PROMPT) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Waiting to send")
        val send =
            hasContentDescription("Send now") and
                androidx.compose.ui.test
                    .hasAnyAncestor(queued)
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(send).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNode(send).performClick()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("send-now-marker").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(queued).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(SECOND_PROMPT, useUnmergedTree = true).assertCountEquals(1)
        val evidence = runBlocking { harnessThreadEvidence(repo).observe(conversationId).first() }
        assertNull(
            "user delivery must follow tool result and precede final reply",
            evidence.sendNowPlacementFailure(SECOND_PROMPT, "send-now-marker"),
        )
    }

    /** #1830: only a phone Stop tap releases the scripted held task; no second message fence. */
    @Test
    fun interactiveTurn_seededChannel_stopBackgroundTaskRemovesRunningRow() {
        arriveInSeededThread()
        typeAndSend(SEND_PROMPT)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = GlobalContext.get().get<RelayRepositoryCoordinator>()
        val repository = requireNotNull(coordinator.currentRepository.value)
        val conversation =
            runBlocking {
                repository
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single {
                        it.name ==
                            SEED_CHANNEL_NAME
                    }.id
            }
        val evidence = harnessThreadEvidence(repository)
        val running =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    evidence.observe(conversation).first { reading ->
                        reading.admitted &&
                            reading.sourceSnapshot?.let {
                                de.pyryco.mobile.ui.conversations.thread
                                    .projectAuthoritativeThread(
                                        requireNotNull(reading.owner.host),
                                        conversation,
                                        it,
                                    ).tasks.liveCount == 1
                            } == true
                    }
                }
            }

        fun currentTasks() =
            runBlocking { evidence.observe(conversation).first() }.let { reading ->
                reading.sourceSnapshot?.takeIf { reading.admitted && reading.owner.matches(running.owner) }?.let {
                    de.pyryco.mobile.ui.conversations.thread
                        .projectAuthoritativeThread(
                            requireNotNull(reading.owner.host),
                            conversation,
                            it,
                        ).tasks
                }
            }
        assertEquals("canonical fixture keeps its task label", "cat ${'$'}FIFO", currentTasks()?.tasks?.single()?.description)
        composeTestRule.onNodeWithContentDescription("More actions").performClick()
        composeTestRule.onNode(hasText(context.getString(R.string.background_tasks_title)) and hasClickAction()).performClick()
        val panel = hasAnyAncestor(isDialog())
        val row = hasText("cat ${'$'}FIFO") and hasClickAction() and panel
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(row).fetchSemanticsNodes().size == 1 }
        } catch (failure: Throwable) {
            android.util.Log.d(
                "ScriptedTaskProof",
                "supported=${coordinator.supportsBackgroundTaskStop.value} live=${currentTasks()?.liveCount}",
            )
            composeTestRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
                composeTestRule.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToLog("ScriptedTaskProof")
            }
            throw failure
        }
        composeTestRule.onNode(row).performTouchInput { click() }
        val stop = context.getString(R.string.background_tasks_stop)
        composeTestRule.onNode(hasText(stop) and panel).performTouchInput { click() }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            currentTasks()?.liveCount == 0 &&
                composeTestRule.onAllNodesWithText(stop).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, currentTasks()?.liveCount)
        assertTrue(
            currentTasks()
                ?.tasks
                ?.none { it.taskId == "bybi8g8i8" && !it.isFinished } == true,
        )
    }

    /** #1783: deterministic lifecycle/parent fixture twin; second send releases the terminal fragment. */
    @Test
    fun interactiveTurn_seededChannel_backgroundAgentMovesAndSettles() {
        backgroundAgentProof(restart = false)
    }

    @Test
    fun interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect() {
        backgroundAgentProof(restart = true)
    }

    private fun backgroundAgentProof(restart: Boolean) {
        arriveInSeededThread()
        val host = requireNotNull(InstrumentationRegistry.getArguments().getString(E2eTestApplication.ARG_SERVER_ID))
        val repository = GlobalContext.get().get<de.pyryco.mobile.di.ThreadDestinationFactory>().repository(host)
        val conversationId =
            runBlocking {
                repository
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single { it.name == SEED_CHANNEL_NAME }
                    .id
            }
        val evidence = harnessThreadEvidence(repository)
        typeAndSend(SEND_PROMPT)
        val running = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.agent_still_working)
        val finished = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.agent_finished)
        val go = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.agent_go_to)
        val marker = hasText(go) and hasClickAction()
        val prose = hasText("child1827-before")
        val expandLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.tool_run_expand)
        val list = composeTestRule.onAllNodes(hasScrollToNodeAction()).onFirst()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { composeTestRule.onAllNodesWithText(running).fetchSemanticsNodes().isNotEmpty() }
        val launched =
            runBlocking {
                withTimeout(REPLY_TIMEOUT_MS) {
                    evidence.observe(conversationId).first { reading ->
                        val family = reading.agentFamily(reading.owner)
                        family?.tools?.size == 3 &&
                            reading.sourceSnapshot?.items?.any {
                                it.kind == "turn_end" &&
                                    (it.parent ?: 0) == 0L &&
                                    it.session == family.root.session &&
                                    it.turn == family.root.turn &&
                                    !it.active
                            } == true
                    }
                }
            }
        val family = requireNotNull(launched.agentFamily(launched.owner))

        fun toolUseId(record: ThreadEvidenceRecord) = (record.fact?.content as? JsonObject)?.get("tool_use_id")?.jsonPrimitive?.content
        assertEquals("agent1783", toolUseId(family.root))
        val origin = requireNotNull(family.root.turn)
        val mainEnds =
            requireNotNull(launched.sourceSnapshot).items.filter {
                it.kind == "turn_end" && (it.parent ?: 0) == 0L && it.session == family.root.session && it.turn == origin
            }
        val mainEnd = mainEnds.single()
        assertFalse("launching main turn must end", mainEnd.active)
        val mainEndOrder = requireNotNull(mainEnd.order)
        assertEquals(listOf("child1783", "late1951-one", "late1951-two"), family.tools.map(::toolUseId))
        val lateTools = family.tools.filter { requireNotNull(it.fact?.order) > mainEndOrder }
        assertEquals(listOf("late1951-one", "late1951-two"), lateTools.map(::toolUseId))
        assertTrue(
            "late children retain launching attribution",
            lateTools.all {
                it.turn == origin && it.session == family.root.session && it.fact?.parent == family.root.fact?.id
            },
        )
        assertEquals(
            "child activity must not open another main turn",
            1,
            requireNotNull(launched.sourceSnapshot).items.count { it.kind == "turn_end" && (it.parent ?: 0) == 0L },
        )
        if (launched.owner.kind == ThreadEvidenceKind.Authoritative) {
            // Unknown parents stay private in canonical history, rather than becoming main-thread prose.
            assertTrue("unknown-parent prose must stay private", launched.records.none { it.fullText == "unmatched1827" })
        } else {
            assertTrue("unmatched prose must stay outside the Agent", launched.holdsOutsideAgent("unmatched1827", family))
        }
        assertTrue("owned prose must belong once to the Agent", family.ownsSingleProse("child1827-before"))
        val header = hasTestTag("background-agent:${family.agentId}")
        val child = hasTestTag("background-agent-child:${family.agentId}")
        // The first owned tool names the run; the Agent root stays separate.
        val run =
            hasText("Using tools:", substring = true) and
                hasClickAction() and
                hasAnyAncestor(hasTestTag("tool-run:${requireNotNull(family.runId)}"))
        val closedRun =
            run and
                SemanticsMatcher("closed owned Agent run") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == expandLabel
                }
        if (launched.owner.kind == ThreadEvidenceKind.Authoritative) {
            composeTestRule.onAllNodesWithText("unmatched1827", useUnmergedTree = true).assertCountEquals(0)
        } else {
            list.performScrollToNode(hasText("unmatched1827"))
            composeTestRule.onNodeWithText("unmatched1827").assertIsDisplayed()
            composeTestRule.onAllNodes(hasText("unmatched1827") and hasAnyAncestor(child), useUnmergedTree = true).assertCountEquals(0)
        }

        fun childMembership(expanded: Boolean): Boolean {
            val indexForKey = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
            return family.childIds.all { id -> (indexForKey("msg:$id") >= 0) == (expanded || id == family.runId) }
        }
        list.performScrollToNode(run)
        composeTestRule.onAllNodes(prose, useUnmergedTree = true).assertCountEquals(0)
        assertTrue("only the first-tool representative remains in a collapsed run", childMembership(expanded = false))
        composeTestRule.questionAnswerTarget(closedRun).performTouchInput { click(center) }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { childMembership(expanded = true) }
        val proseId =
            family.children
                .single { it.fullText == "child1827-before" }
                .message.id
        val proseIndex = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:$proseId")
        assertTrue("the opened run must retain its exact owned prose row", proseIndex >= 0)
        list.performScrollToIndex(proseIndex)
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(prose, useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
        } catch (failure: Throwable) {
            val record = family.children.single { it.message.id == proseId }
            android.util.Log.d(
                "ScriptedAgentProof",
                "rendered=${record.message.content} source=${record.fullText} status=${record.fact?.status}",
            )
            composeTestRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
                composeTestRule.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToLog("ScriptedAgentProof")
            }
            throw failure
        }
        composeTestRule.onNode(prose and hasAnyAncestor(child), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onAllNodes(prose, useUnmergedTree = true).assertCountEquals(1)
        list.performScrollToNode(run)
        composeTestRule.questionAnswerTarget(run).performTouchInput { click(center) }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { childMembership(expanded = false) }
        composeTestRule.onAllNodes(prose, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.questionAnswerTap(
            marker,
            lazyKey = "agent-start:${family.agentId}",
            evidence = { android.util.Log.d("ScriptedAgentProof", it) },
        )
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(header).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onAllNodes(isDialog()).assertCountEquals(0)
        composeTestRule.onNode(header).assertIsDisplayed()
        composeTestRule.onAllNodes(prose, useUnmergedTree = true).assertCountEquals(0)
        list.performScrollToNode(run)
        composeTestRule.onNode(closedRun).assertExists()
        typeAndSend("release1783")
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("after1783").fetchSemanticsNodes().isNotEmpty()
        }
        var pendingSettlement: ThreadEvidence? = null
        val settled =
            try {
                runBlocking {
                    withTimeout(THREAD_TIMEOUT_MS) {
                        evidence.observe(conversationId).onEach { pendingSettlement = it }.first {
                            it.settledAgentAfterRecovery(launched, family, "after1783") != null
                        }
                    }
                }
            } catch (failure: Throwable) {
                pendingSettlement?.let { current ->
                    val before = launched.owner
                    val after = current.owner
                    val currentFamily = current.agentFamily(after)
                    val currentSettlement = currentFamily?.let { current.settledAgentPrecedes(after, it, "after1783") }
                    val familyOrder = currentFamily?.let { (listOf(it.root) + it.children).map { row -> row.fact?.order } }
                    val followingOrder = current.records.filter { it.agentId == null && it.fullText == "after1783" }.map { it.fact?.order }
                    android.util.Log.d(
                        "ScriptedAgentRecovery",
                        "admitted=${current.admitted} host=${before.host == after.host} " +
                            "conversation=${before.conversation == after.conversation} " +
                            "connection=${before.connection === after.connection} kind=${before.kind == after.kind} " +
                            "epoch=${before.epoch == after.epoch} generation=${before.generation}->${after.generation} " +
                            "family=${currentFamily != null} settled=$currentSettlement checkpoint=${current.sourceSnapshot?.checkpoint} " +
                            "coverage=${current.sourceSnapshot?.coverage}",
                    )
                    val candidates = current.records.filter { it.rawToolUseId() == family.root.rawToolUseId() }
                    android.util.Log.d(
                        "ScriptedAgentRecovery",
                        "rootCandidates=${candidates.size} familyOrder=$familyOrder followingOrder=$followingOrder",
                    )
                    (listOf(family.root) + family.children).forEach { previous ->
                        val old = previous.fact ?: return@forEach
                        val row = current.records.singleOrNull { it.fact?.id == old.id }
                        val now = row?.fact
                        val oldInput = old.content as? JsonObject
                        val input = now?.content as? JsonObject
                        val sameInput = listOf("tool_use_id", "name", "input").all { oldInput?.get(it) == input?.get(it) }
                        android.util.Log.d(
                            "ScriptedAgentRecovery",
                            "id=${old.id} present=${row != null} kind=${old.kind == now?.kind} " +
                                "session=${old.session == now?.session} turn=${old.turn == now?.turn} " +
                                "parent=${old.parent == now?.parent} order=${old.order}->${now?.order} " +
                                "revision=${old.rev}->${now?.rev} state=${old.status}/${old.active}->${now?.status}/${now?.active} " +
                                "input=$sameInput text=${previous.fullText == row?.fullText} " +
                                "prefix=${row?.fullText?.startsWith(previous.fullText)}",
                        )
                    }
                }
                throw failure
            }
        val owned = requireNotNull(settled.settledAgentAfterRecovery(launched, family, "after1783"))
        val settledHeader = hasTestTag("background-agent:${owned.agentId}")
        val settledChild = hasTestTag("background-agent-child:${owned.agentId}")
        composeTestRule.questionAnswerTap(
            marker and hasText(finished),
            lazyKey = "agent-start:${owned.agentId}",
            evidence = { android.util.Log.d("ScriptedAgentProof", it) },
        )
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(settledHeader).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onAllNodes(isDialog()).assertCountEquals(0)
        composeTestRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("after1783"))
        val agent = composeTestRule.onNode(settledHeader).fetchSemanticsNode().boundsInRoot
        val after = composeTestRule.onNodeWithText("after1783", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("terminal block must settle before the following reply", agent.bottom <= after.top)
        // Navigation preserves collapse state; explicitly open the owned run to inspect its reply.
        composeTestRule.onAllNodes(hasText("child1827-after", substring = true), useUnmergedTree = true).assertCountEquals(0)
        assertEquals("every late received tool must be retained in the owned run", 1 + lateTools.size, owned.tools.size)
        assertTrue("owned prose must belong once to the Agent", owned.ownsSingleProseFragment("child1827-after"))
        if (settled.owner.kind == ThreadEvidenceKind.Authoritative) {
            // Unknown parents stay private in canonical history, rather than becoming main-thread prose.
            assertTrue("unknown-parent prose must stay private", settled.records.none { it.fullText == "unmatched1827" })
        } else {
            assertTrue("unmatched prose must stay outside the Agent", settled.holdsOutsideAgent("unmatched1827", owned))
        }
        composeTestRule.verifyAgentRunNavigation(
            agentId = owned.agentId,
            runId = requireNotNull(owned.runId),
            childIds = owned.childIds,
            ownedChild = hasText("child1827-after", substring = true) and hasAnyAncestor(settledChild),
            goLabel = go,
            expandLabel = expandLabel,
            collapseLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.tool_run_collapse),
        )
        if (restart) {
            composeTestRule.proveNegotiatedAgentRestart(host, conversationId, "agent1783", "task1783", restart = { beforeLaunch ->
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                composeTestRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
                instrumentation.runOnMainSync { (instrumentation.targetContext.applicationContext as E2eTestApplication).rebuildGraph() }
                beforeLaunch()
                androidx.test.core.app.ActivityScenario
                    .launch(MainActivity::class.java)
            }, openThread = ::arriveInSeededThread, send = { typeAndSend("settlement2175") })
        }
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
     *
     * The same held-open window proves the status area's running-tool label without an elapsed reading
     * (#950): drop A carries no heartbeat, so the label names the tool and nothing else, and it is gone once
     * drop B closes the call. The `tool-progress` scenario covers the elapsed reading.
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
        // #950: the status area names the open tool, with no elapsed reading since no heartbeat arrived. It
        // shows once the turn reads busy, which need not be the frame the row appeared in.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(runningToolLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasContentDescription(runningToolLabel)).assertIsDisplayed()

        // Message #2 → drop B → tool_result(done) + turn end. The row resolves Running → Done in place.
        // The 2nd prompt is inert for the reply (the scripted backend ignores it); it only causally
        // fences drop B.
        typeAndSend(SECOND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(toolRunningDescription)).fetchSemanticsNodes().isEmpty()
        }
        // Resolved to done, not failed (no failed glyph), and the row is still present (its command shown).
        composeTestRule.onNode(hasContentDescription(toolFailedDescription)).assertDoesNotExist()
        composeTestRule.onAllNodesWithText(TOOL_COMMAND, substring = true).onFirst().assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription(runningToolLabel)).assertDoesNotExist()
    }

    /**
     * `tool-progress` scenario (#950) — the status area's running-tool label must show claude's elapsed
     * reading once a `tool_progress` heartbeat arrived, and be gone once the call's `tool_result` lands.
     * Drop A (`tool-progress-open.jsonl`) is the `tool` scenario's lone `tool_use` followed by one heartbeat
     * in claude's captured shape (`heartbeat: true`, `parent_tool_use_id` = the open call's id,
     * `elapsed_time_seconds: 30`); a heartbeat missing either key is dropped by the daemon without a trace.
     * It is held open until the **2nd** send releases drop B (`tool-progress-result.jsonl`).
     *
     * Drop B is the correlated `tool_result` **alone**, with no turn end: the turn stays busy, so the label
     * can only clear because the call closed. A label that cleared only at turn end would keep this test
     * red, where reusing `tool-done.jsonl` would let it pass. The `tool` scenario proves the label without a
     * reading. Tolerant (presence → absence, generous timeout); never on timing.
     */
    @Test
    fun interactiveTurn_seededChannel_runningToolLabelShowsElapsedThenClears() {
        arriveInSeededThread()

        // Message #1 → drop A → tool_use + heartbeat, held open. The label reads the tool and its 30 s.
        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(runningToolElapsedLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasContentDescription(runningToolElapsedLabel)).assertIsDisplayed()

        // Message #2 → drop B → the tool_result alone. The call closes while the turn is still busy, so the
        // label leaves the status area and the tool row stops reading running.
        typeAndSend(SECOND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(runningToolElapsedLabel)).fetchSemanticsNodes().isEmpty() &&
                composeTestRule.onAllNodes(hasContentDescription(toolRunningDescription)).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNode(hasContentDescription(runningToolLabel)).assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription(toolFailedDescription)).assertDoesNotExist()
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
     * `tool-then-text` scenario (#1417) — a turn that goes text, tool, text must draw the first text above
     * the tool row and the second below it, in separate nodes (#1350's per-segment reply). One fixture
     * (`tool-then-text.jsonl`) carries claude's per-block records: the first text and the `Bash` `tool_use`
     * share one `message.id`, the correlated `tool_result` follows, then the second text under a new id and
     * the turn end. The `tool_use` has no input fields, so the collapsed row leads with the tool name `Bash`
     * rather than a command (#1315). The test waits for the turn to settle (both markers on screen, the row
     * no longer running, the thinking spinner gone), then compares on-screen bounds.
     */
    @Test
    fun interactiveTurn_seededChannel_replyTextAfterToolRendersBelowIt() {
        arriveInSeededThread()

        typeAndSend(SEND_PROMPT)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(BEFORE_TOOL_MARKER, substring = true).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText(AFTER_TOOL_MARKER, substring = true).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText(TOOL_NAME).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodes(hasContentDescription(toolRunningDescription)).fetchSemanticsNodes().isEmpty() &&
                composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isEmpty()
        }

        val before = composeTestRule.onNodeWithText(BEFORE_TOOL_MARKER, substring = true).fetchSemanticsNode()
        val after = composeTestRule.onNodeWithText(AFTER_TOOL_MARKER, substring = true).fetchSemanticsNode()
        val toolRow = composeTestRule.onNodeWithText(TOOL_NAME).fetchSemanticsNode()

        assertNotEquals("both markers rendered in one node", before.id, after.id)
        assertFalse(nodeText(before).contains(AFTER_TOOL_MARKER))
        assertFalse(nodeText(after).contains(BEFORE_TOOL_MARKER))
        assertTrue(
            "text before the tool (${before.boundsInRoot}) is not above the tool row (${toolRow.boundsInRoot})",
            before.boundsInRoot.bottom <= toolRow.boundsInRoot.top,
        )
        assertTrue(
            "text after the tool (${after.boundsInRoot}) is not below the tool row (${toolRow.boundsInRoot})",
            after.boundsInRoot.top >= toolRow.boundsInRoot.bottom,
        )
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
     *
     * Between the restore and the 2nd message the thinking indicator must be displayed again (#1617). The
     * reconnected repository is fresh and reads idle, and the held-open turn's `thinking` was delivered
     * before the drop, so no replayed frame restates it; the daemon's connect-time `turn_state`
     * reconcile (pyrycode#2712) does. Sending the 2nd message first could mask a missing reconcile.
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

        // #1617: the fresh connection's projection starts idle and the turn's `thinking` predates the
        // replay cursor, so only the daemon's connect-time reconcile `turn_state` (no event_id,
        // pyrycode#2712) can bring the indicator back. Asserted before message #2, whose drop could restate it.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).onFirst().assertIsDisplayed()

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
     * [severAndRestoreLink] primitive. Drop A (`replay-order-open.jsonl`, user echo then thinking) holds
     * the turn open on the 1st `send_message.enqueued`; the test then **severs** the link, the host drops
     * drop B (`replay-order.jsonl`, three ordered `assistant_delta` lines + `end_turn`) fenced on the
     * explicit test control after deliberate close — so the whole sequence accrues in the daemon's in-ring
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
     * Check the final reply text in visible top-to-bottom order, across assistant segments. A durable
     * user echo can land between deltas and legitimately split the reply into two bubbles (ADR 0007).
     * Requiring one text node would reject that valid history/replay join. Exact equality with
     * [ORDERED_REPLY_SUBSTRING] still rejects missing, reordered or duplicated text, regardless of where
     * the echo was logged; neither the seeded title nor the prompts contain these fixture words.
     */
    @Test
    fun interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect() {
        fun <T> phase(
            name: String,
            action: () -> T,
        ): T {
            android.util.Log.i("ReplayOrder", "event=replay_order_phase phase=$name completed=false")
            return action().also { android.util.Log.i("ReplayOrder", "event=replay_order_phase phase=$name completed=true") }
        }

        phase("initial_readiness") { arriveInSeededThread() }
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString("serverId"))
        val registry = GlobalContext.get().get<de.pyryco.mobile.di.RelayConnectionRegistry>()
        val coordinator = requireNotNull(registry.connectionFor(serverId)).coordinator
        val hostRepository = GlobalContext.get().get<de.pyryco.mobile.di.ThreadDestinationFactory>().repository(serverId)
        val route = requireNotNull(hostRepository.negotiatedThread)
        val conversationId =
            phase("conversation_identity") {
                runBlocking {
                    withTimeout(THREAD_TIMEOUT_MS) {
                        coordinator.currentRepository
                            .firstOnLive({ it.observeConversations(ConversationFilter.All) }) { rows ->
                                rows.any {
                                    it.name ==
                                        SEED_CHANNEL_NAME
                                }
                            }.single { it.name == SEED_CHANNEL_NAME }
                            .id
                    }
                }
            }

        fun currentSource(evidence: ThreadEvidence): ThreadEvidence? {
            val supported = (route.support.value as? de.pyryco.mobile.data.repository.ThreadSupport.Supported)?.connection
            val current = coordinator.currentRepository.value?.takeIf { it === supported } ?: return null
            val snapshot = route.store.current(serverId, conversationId)
            if (!evidence.isCurrentReplay(serverId, conversationId, current, snapshot)) return null
            val fresh = authoritativeThreadEvidence(serverId, conversationId, current, snapshot)
            return fresh.takeIf {
                current === coordinator.currentRepository.value &&
                    (route.support.value as? de.pyryco.mobile.data.repository.ThreadSupport.Supported)?.connection === current &&
                    fresh.isCurrentReplay(serverId, conversationId, current, route.store.current(serverId, conversationId))
            }
        }

        // Message #1 → drop A → turn_state(thinking), held open. The spinner proves the turn is open and
        // streaming at the moment we sever; nothing renderable has arrived yet (no partial text by design).
        typeAndSend(SEND_PROMPT)
        phase("open_turn") {
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).onFirst().assertIsDisplayed()
        }

        // Sever the phone's relay link (daemon stays up, in-ring buffer intact). While the phone is offline
        // explicitly release the ordered sequence. An automatic producer-gap disconnect before the
        // thinking assertion must not release it. The bounded hold retains the offline production window.
        phase("sever") { severLink() }
        phase("offline_release") { DaemonFaultControl().releaseStream() }
        phase("offline_window") { runBlocking { delay(OFFLINE_WINDOW_MS) } }

        // Restore the link. The fresh Noise hello re-advertises last_event_id (#416); the buffered sequence
        // (all event_id > cursor) replays whole into the fresh empty repo, which folds the deltas in
        // arrival (= production) order.
        phase("restore") { restoreLink() }

        phase("rendered_text") {
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.renderedReplyText(ORDERED_REPLY_SUBSTRING) == ORDERED_REPLY_SUBSTRING
            }
        }
        // Prove exact text and the source's own identity too: a duplicated sequence within one row must fail.
        val evidence =
            phase("completed_current_source") {
                runBlocking {
                    withTimeout(THREAD_TIMEOUT_MS) {
                        harnessThreadEvidence(hostRepository).observe(conversationId).first {
                            currentSource(it)?.hasCompletedReplay(ORDERED_REPLY_SUBSTRING) == true
                        }
                    }
                }
            }
        phase("ordered_identity_and_echo") {
            composeTestRule.assertOrderedReplay(evidence, ORDERED_REPLY_SUBSTRING, SEND_PROMPT, refresh = ::currentSource)
        }
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

    /**
     * Every scripted run reports a failed MCP server (#1457), and its Error pill has no dismiss. A short thread
     * starts at the Top overlay's inset (#1509), so the pill covers the first row's side actions and the
     * pointer helpers cannot scroll them clear. Acknowledge it as a user would: open it, close Channel info.
     */
    private fun acknowledgeFailedMcpPill() {
        val pill = hasClickAction() and SemanticsMatcher("text starts with $mcpFailedPrefix") { nodeText(it).startsWith(mcpFailedPrefix) }
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { composeTestRule.onAllNodes(pill).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onAllNodes(pill).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(MCP_SECTION_HEADER).fetchSemanticsNodes().isNotEmpty()
        }
        // The first back can only close the keyboard, so press until the sheet is gone, never past it.
        repeat(3) {
            if (composeTestRule.onAllNodesWithText(MCP_SECTION_HEADER).fetchSemanticsNodes().isNotEmpty()) {
                Espresso.pressBack()
                composeTestRule.waitForIdle()
            }
        }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(MCP_SECTION_HEADER).fetchSemanticsNodes().isEmpty() &&
                composeTestRule.onAllNodes(pill).fetchSemanticsNodes().isEmpty()
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

    /** The node's text, joined across its merged `Text` children. */
    private fun nodeText(node: SemanticsNode): String = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ")

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
        const val REOPEN_PREFIX =
            "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november " +
                "oscar papa quebec romeo sierra tango uniform victor whiskey xray yankee zulu"
        const val REOPEN_SUFFIX = " and the held reply is now complete"

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

        // Held words arrive in two deltas; the final check also spans the terminal delta boundary.
        const val STREAMED_PREFIX = "Hello, streamed"
        const val STREAMED_REPLY = "Hello, streamed world"

        // The tool scenarios' verbatim tool name (carried through the fold from the envelope `name`,
        // ToolCallRow renders it in the collapsed header). Asserted in the running → done case to prove
        // the row resolved in place rather than vanishing. Does not collide with the seeded channel name
        // "e2e-seed" rendered in the top bar.
        const val TOOL_NAME = "Bash"

        // The `tool` fixture's Bash command, which carries no description. The collapsed row leads with it
        // instead of the tool name (#1315); an older daemon without input fields shows it as the précis.
        const val TOOL_COMMAND = "echo hello"

        // The `tool-then-text` fixture's markers: the first sits in the text before the tool_use, the second in
        // the text after its result. Neither occurs in "e2e-seed", the "hello" prompt, "Bash", the tool row or
        // the other marker, so a match can only be the reply segment that carries it (#431).
        const val BEFORE_TOOL_MARKER = "foxtrot"
        const val AFTER_TOOL_MARKER = "zulu"

        // The `tool-progress` heartbeat's `elapsed_time_seconds: 30`, as the label formats it (#950).
        const val HEARTBEAT_ELAPSED = "30s"

        // The reconnect scenario's drop-B reply text ("reconnected reply ok" in reconnect-done.jsonl); this
        // substring is asserted to render exactly once after the mid-turn drop. A unique, self-documenting
        // phrase that collides with nothing else on screen (the "e2e-seed" title, "ping", "Bash",
        // "streamed world").
        const val RECONNECT_REPLY_SUBSTRING = "reconnected reply"

        // The replay-order scenario's drop-B sequence assembles into "alpha bravo charlie" (three deltas
        // "alpha "/"bravo "/"charlie" in replay-order.jsonl). Reading the rendered parts top-to-bottom
        // checks production order and exactly-once text even across a retained user separator. A reordered
        // replay ("bravo alpha charlie") fails the match. Collides with nothing else on screen (the
        // "e2e-seed" title, "ping", "Bash", "streamed world", "reconnected reply", the inert "hello" prompt).
        const val ORDERED_REPLY_SUBSTRING = "alpha bravo charlie"

        /** `refusal.jsonl`'s `original_model` (#1360): a value fakeclaude's canned menu offers, so the write is accepted. */
        const val REFUSAL_ORIGINAL_MODEL = "haiku"

        /** The label the thread's menu gives [REFUSAL_ORIGINAL_MODEL], which the Switch back button shows (#1494). */
        const val REFUSAL_ORIGINAL_LABEL = "Haiku"

        /** `refusal.jsonl`'s reply text, which follows the refusal line. */
        const val REFUSAL_REPLY = "refusal handled"

        /** `mcp-failed.jsonl`'s reply text (#1457). */
        const val MCP_FAILED_REPLY = "mcp checked"

        /** `ComposerAction.CompactSession`'s command, which fakeclaude echoes back verbatim (#1473). */
        const val COMPACT_COMMAND = "/compact"

        /** Stands in for the server name so [mcpFailedPrefix] can cut the format at it. */
        const val MCP_NAME_CUT = "\u0000"

        // ChannelInfoSheet's header above McpServersSection (#1344), a literal in production code.
        const val MCP_SECTION_HEADER = "MCP servers"

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
        // exceed [explicit release + fakeclaude append + one producer tail
        // cycle]. Primary first-run tuning point — lengthen if the replay arrives as a live mix.
        const val OFFLINE_WINDOW_MS = 5_000L

        // Generous: the fixture drop is fenced on send_message.enqueued, then tails the real producer
        // over the relay. Also used as the spinner scenario's presence/absence timeout.
        const val REPLY_TIMEOUT_MS = 90_000L
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.util.UUID

/**
 * Layer 1a component-level harness (#432): drives a scripted sequence of structured live-session
 * events through the **real production graph** — [RemoteConversationRepository]'s fold (#337
 * `assistant_delta`→streaming row + #406 `turn_state`→`isThinking`) into [ThreadViewModel] into the
 * stateless [ThreadScreen] — and renders the assembled thread, with no network, daemon, or
 * emulator-host. The production-canonical streaming row lives in the **repository** fold (its inbound
 * collector runs unconditionally on [scope]), so the harness drives the real
 * [RemoteConversationRepository]; a fake-repo + scripted-`LiveSessionEvent` harness would exercise only
 * the dormant ViewModel fold and miss regressions in the real one.
 *
 * The single shared entry point sibling **#435** (Layer 1b: tool rows, session divider, connection
 * banner) extends — it injects the real graph in the constructor, so new scenarios add scripting
 * methods on top without re-deriving the stream → fold → render wiring. Keep the scripting surface
 * minimal: only the two render cases (text, spinner) are wired here.
 *
 * Construct, then [start] (from `@Before`), script via the `push*` methods (each a thin `pump.push`),
 * and [close] (from `@After`) to cancel [scope]. The `FakeSessionPump` and envelope builders are ported
 * from `RemoteConversationRepositoryTest` (they depend only on `main/` types); ktlint's
 * single-public-class rule constrains only the public [ScriptedThreadHarness], so the private siblings
 * share this file.
 */
class ScriptedThreadHarness(
    private val composeRule: ComposeContentTestRule,
    private val conversationId: String = "c1",
    private val seedName: String = SEED_NAME,
) {
    /** Owns the repository's inbound collector + the DataStore; cancelled by [close]. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pump = FakeSessionPump()

    private val repo =
        RemoteConversationRepository(
            pump = pump,
            scope = scope,
            // Gate open: with the default closed set, `assistant_delta` folds nothing and
            // `liveSessionEvents` stays silent (see assistantDelta_capabilityGateClosed_producesNoRow).
            negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) },
        )

    /** Records taps on the interrupt affordance (#459) — the recording analog of #458's send path,
     *  injected as the VM's interrupt lambda so the screen test can assert exactly-once invocation. */
    private var interruptCount = 0

    private val vm =
        ThreadViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversationId)),
            repository = repo,
            connectionStateSource = FakeConnectionStateSource(),
            appPreferences = AppPreferences(newDataStore()),
            liveSessionEvents = repo.liveSessionEvents,
            interrupt = { interruptCount++ },
        )

    /**
     * Compose the real render surface over the live VM, seed one conversation so [ThreadViewModel.state]
     * emits, and block until the pipeline is live and subscribed ([awaitReady]). Call once, from `@Before`.
     */
    fun start() {
        composeRule.setContent {
            PyrycodeMobileTheme {
                // collectAsState (not collectAsStateWithLifecycle): these are StateFlows and
                // createComposeRule has no LifecycleOwner.
                ThreadScreen(
                    state = vm.state.collectAsState().value,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = vm.connectionState.collectAsState().value,
                    onRetry = {},
                    isThinking = vm.isThinking.collectAsState().value,
                    // #459: subscribe isBusy in the same composition pass as state/isThinking so its
                    // `replay = 0` upstream is live before any push* (awaitReady's top-bar proof covers it).
                    isBusy = vm.isBusy.collectAsState().value,
                    onInterrupt = vm::onInterrupt,
                )
            }
        }
        seedConversation()
        awaitReady()
    }

    /** Number of times the interrupt affordance's tap invoked the VM's interrupt-send action (#459). */
    fun interruptInvocations(): Int = interruptCount

    /** Script one `assistant_delta` for [turnId] at [seq] carrying [text] (#337). */
    fun pushAssistantDelta(
        turnId: String,
        seq: Int,
        text: String,
    ) = pump.push(assistantDeltaEnvelope(conversationId, turnId, seq, text))

    /** Script one `turn_state` ("thinking" | "responding" | "idle") (#406). */
    fun pushTurnState(state: String) = pump.push(turnStateEnvelope(conversationId, state))

    /** Script one `turn_end` for [turnId], finalizing the streaming row (#337). */
    fun pushTurnEnd(
        turnId: String,
        stopReason: String = "end_turn",
    ) = pump.push(turnEndEnvelope(conversationId, turnId, stopReason))

    /** Cancel [scope] — stops the inbound collector and the DataStore scope. Call from `@After`. */
    fun close() {
        scope.cancel()
    }

    /**
     * Seed one promoted conversation whose id matches [conversationId] so `observeConversations` /
     * `observeMessages` have a conversation and [ThreadViewModel.state]'s `combine` emits. The
     * `conversations` snapshot is safe to push at any time — it is a retained cold projection, unlike the
     * `replay = 0` live stream (the subscribe-before-push constraint applies only to the `push*` events).
     */
    private fun seedConversation() = pump.push(conversationsEnvelope(seedSnapshot(conversationId, seedName)))

    /**
     * Block until the render pipeline is live and the VM's `replay = 0` live-event collectors are
     * subscribed — the single most likely flake source. [ThreadViewModel.isThinking] subscribes upstream
     * to the `replay = 0` `liveSessionEvents` only once [start]'s `collectAsState` composes, so any
     * `turn_state` pushed before then is dropped, not buffered. Proof of liveness: the seeded
     * conversation's name rendered in the top bar, which means [ThreadViewModel.state] (and, from the same
     * composition pass, `isThinking`) has subscribed and processed an emission. Gate every live `push*`
     * behind this.
     */
    private fun awaitReady() {
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = READY_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText(seedName, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun newDataStore(): DataStore<Preferences> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Per-run unique file under the instrumentation sandbox; leaking it across runs is acceptable.
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(context.filesDir, "scripted_thread_harness_${UUID.randomUUID()}.preferences_pb") },
        )
    }

    private companion object {
        const val READY_TIMEOUT_MS = 5_000L

        // Deliberately distinctive + non-substring of any asserted text, so awaitReady's top-bar match is
        // unambiguous and never collides with a render assertion.
        const val SEED_NAME = "Harness channel"
    }
}

/** The negotiated capability that opens the structured-stream fold gate (mirrors the wire token). */
private const val CAPABILITY_INTERACTIVE = "interactive"

/** Fixed envelope timestamp — the harness asserts on render, never on timing (ladder-doc rule). */
private const val TS = "2026-05-31T00:00:00Z"

/**
 * Channel-backed fake of the inbound surface, ported from `RemoteConversationRepositoryTest`: an
 * unlimited buffer so a push pre-subscription survives, and a non-throwing [send] (the reads' fire-and-
 * forget requests ignore its result). Depends only on `main/` [SessionPump] / [Envelope].
 */
private class FakeSessionPump : SessionPump {
    private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

    override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

    override fun send(envelope: Envelope): Boolean = true

    fun push(envelope: Envelope) {
        inboundChannel.trySend(envelope)
    }
}

private fun assistantDeltaEnvelope(
    conversationId: String,
    turnId: String,
    seq: Int,
    text: String,
): Envelope =
    Envelope(
        id = 1L,
        type = "assistant_delta",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"$conversationId","turn_id":"$turnId","seq":$seq,"text":"$text"}""",
            ),
    )

private fun turnStateEnvelope(
    conversationId: String,
    state: String,
): Envelope =
    Envelope(
        id = 1L,
        type = "turn_state",
        ts = TS,
        payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId","state":"$state"}"""),
    )

private fun turnEndEnvelope(
    conversationId: String,
    turnId: String,
    stopReason: String,
): Envelope =
    Envelope(
        id = 1L,
        type = "turn_end",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"$conversationId","turn_id":"$turnId","stop_reason":"$stopReason"}""",
            ),
    )

private fun conversationsEnvelope(rawConversationsPayload: String): Envelope =
    Envelope(
        id = 1L,
        type = "conversations",
        ts = TS,
        payload = MobileJson.parseToJsonElement(rawConversationsPayload),
    )

/** One promoted conversation row whose id matches the harness's conversation, named for the top-bar proof. */
private fun seedSnapshot(
    conversationId: String,
    name: String,
): String =
    """
    {"conversations":[
      {"id":"$conversationId","name":"$name","is_promoted":true,"cwd":"/p/$conversationId","last_message_ts":"$TS","last_used_at":"$TS"}
    ]}
    """.trimIndent()

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.e2e.TappingConversationRepository
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
 * minimal: text/spinner (#432), the interrupt affordance (#459), and tool rows (#472) are wired here.
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

    /**
     * The connection-state seam driving the banner (#200/#201), distinct from the [pump] envelope
     * stream that drives the deltas / tool rows. Held as the concrete [FakeConnectionStateSource] (not
     * the interface) so [pushConnectionState] can reach its `emit`, mirroring how the harness holds the
     * concrete [pump]. Default `Connected` ⇒ no banner until a state is pushed (#474).
     */
    private val connectionStateSource = FakeConnectionStateSource()

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
    private val interruptTargets = mutableListOf<String>()

    private val vm =
        ThreadViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversationId)),
            // #586: the same inert tap the live graph installs, so the rung-2 non-vacuity proof covers the
            // whole chain (scripted envelope → real fold → tap → recorder → finding) rather than a pure
            // function in isolation. It records and nothing more, so every other scenario is unaffected.
            repository = TappingConversationRepository(repo),
            connectionStateSource = connectionStateSource,
            // #789: a store of this harness's own — the scenarios never type, and an isolated one keeps
            // the scripted render independent of any other surface's composer state.
            draftStore = ComposerDraftStore(),
            liveSessionEvents = repo.liveSessionEvents,
            interrupt = { interruptTargets += it },
        )

    /**
     * Compose the real render surface over the live VM, seed one conversation so [ThreadViewModel.state]
     * emits, and block until the pipeline is live and subscribed ([awaitReady]). Call once, from `@Before`.
     */
    fun start() {
        composeRule.setContent {
            PyrycodeMobileTheme {
                val colors = MaterialTheme.colorScheme
                SideEffect { colorScheme = colors }
                // collectAsState (not collectAsStateWithLifecycle): these are StateFlows and
                // createComposeRule has no LifecycleOwner.
                ThreadScreen(
                    state = vm.state.collectAsState().value,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = vm.connectionState.collectAsState().value,
                    onRetry = {},
                    isThinking = vm.isThinking.collectAsState().value,
                    apiRetry = vm.apiRetry.collectAsState().value,
                    usageLimit = vm.usageLimit.collectAsState().value,
                    resetting = vm.resetting.collectAsState().value,
                    isCompacting = vm.isCompacting.collectAsState().value,
                    // #805: a `replay = 0` live-event fold like isBusy, so it subscribes in this same pass.
                    turnOutcome = vm.turnOutcome.collectAsState().value,
                    thinkingProgress = vm.thinkingProgress.collectAsState().value,
                    // #459: subscribe isBusy in the same composition pass as state/isThinking so its
                    // `replay = 0` upstream is live before any push* (awaitReady's top-bar proof covers it).
                    isBusy = vm.isBusy.collectAsState().value,
                    // #1311: the stall arm and the local-send window.
                    isStalled = vm.isStalled.collectAsState().value,
                    localSendPending = vm.localSendPending.collectAsState().value,
                    onInterrupt = vm::onInterrupt,
                )
            }
        }
        seedConversation()
        awaitReady()
    }

    /** The colour scheme the render surface resolved, so a colour assertion follows the theme (#1311). */
    lateinit var colorScheme: ColorScheme
        private set

    /** Number of times the interrupt affordance's tap invoked the VM's interrupt-send action (#459). */
    fun interruptInvocations(): Int = interruptTargets.size

    fun interruptedConversations(): List<String> = interruptTargets.toList()

    /** Script one `assistant_delta` for [turnId] at [seq] carrying [text] (#337). */
    fun pushAssistantDelta(
        turnId: String,
        seq: Int,
        text: String,
    ) = pump.push(assistantDeltaEnvelope(conversationId, turnId, seq, text))

    /** Script one `turn_state` ("thinking" | "responding" | "idle") (#406). */
    fun pushTurnState(state: String) = pump.push(turnStateEnvelope(conversationId, state))

    /**
     * Script one `api_retry` edge (#593) — [active] `true` is the rising edge (re-fire it with a climbed
     * [current] for a counter update), `false` the clearing edge. The falling edge on the wire carries the
     * last-known counter verbatim, so [current] / [total] stay settable there; the repository's mapper is
     * what discards them. Unlike the `replay = 0` live-event `push*` methods this one projects a retained
     * `MutableStateFlow`, so it has no subscribe-before-push hazard — pushed after [start] anyway, for
     * uniformity.
     */
    fun pushApiRetry(
        active: Boolean,
        current: Int,
        total: Int,
    ) = pump.push(apiRetryEnvelope(conversationId, active, current, total))

    /**
     * Script one `compacting` edge (#596) — [active] `true` is the rising edge, `false` the explicit
     * clearing edge. On/off only: the wire payload is `{conversation_id, active}` and carries no counter,
     * percent, or ETA, so there is nothing else to script. Like [pushApiRetry] (and unlike the `replay = 0`
     * live-event `push*` methods) this one projects a retained `MutableStateFlow`, so it has no
     * subscribe-before-push hazard — pushed after [start] anyway, for uniformity.
     */
    fun pushCompacting(active: Boolean) = pump.push(compactingEnvelope(conversationId, active))

    /**
     * Script one `resetting` edge (#871/#872). [active] `true` is a rising edge carrying [phase]
     * (`wrapping_up` | `restarting`) and [handoff] (`pending` | `written` | `skipped`); a second rising
     * edge is a phase change of the same reset. `false` is the falling edge, whose strings the wire sends
     * empty. Both stay plain `String`s so a scenario can script any token. [targetConversationId] defaults
     * to the harness's own conversation; pass another id to prove a reset elsewhere never shows here. Like
     * [pushCompacting] it projects a retained `MutableStateFlow`, so it has no subscribe-before-push hazard.
     */
    fun pushResetting(
        active: Boolean,
        phase: String = "",
        handoff: String = "",
        targetConversationId: String = conversationId,
    ) = pump.push(resettingEnvelope(targetConversationId, active, phase, handoff))

    /**
     * Script one `rate_limited` reading (#802/#804). A non-`allowed` [status] raises or replaces the
     * reading; `"allowed"` is the benign frame that clears it. Every field stays freely settable — out-of-range
     * [resetsAt] / [utilization] included — because the render path's range checks are what the scenarios
     * prove. Like [pushCompacting] it projects a retained `MutableStateFlow`, so it has no
     * subscribe-before-push hazard.
     */
    fun pushRateLimited(
        status: String,
        limitType: String = "seven_day",
        resetsAt: Long = 0L,
        utilization: Double? = null,
        truncatedFields: List<String>? = null,
    ) = pump.push(rateLimitedEnvelope(conversationId, status, limitType, resetsAt, utilization, truncatedFields))

    /**
     * Script one `thinking_progress` reading (#801). There is **no edge to script**: the frame only ever
     * asserts a reading, so re-fire it with a lower [estimatedTokens] for the restart behaviour and with
     * an identical one for the repeat. Clears come from other frames — [pushTurnEnd] and
     * [pushSessionTransition] — never from this one. Like [pushCompacting] it projects a retained
     * `MutableStateFlow`, so it has no subscribe-before-push hazard.
     */
    fun pushThinkingProgress(
        estimatedTokens: Long,
        estimatedTokensDelta: Long = 64,
    ) = pump.push(thinkingProgressEnvelope(conversationId, estimatedTokens, estimatedTokensDelta))

    /**
     * Script one `unrecognized_message` (#609) — a claude message kind the daemon's stream-json parser
     * could not map. [site] stays a raw wire token rather than the [de.pyryco.mobile.data.repository
     * .UnrecognizedSite] enum, matching [pushSessionTransition]'s choice for `reason`: the harness must be
     * able to script an *unrecognized* site so a test can exercise the mapper's drop path. [raw] is the
     * offending JSON verbatim and realistically contains quotes, which is why this builder assembles its
     * payload through `kotlinx.serialization` instead of the string interpolation the sibling builders use.
     */
    fun pushUnrecognizedMessage(
        site: String,
        messageType: String,
        raw: String,
        truncated: Boolean = false,
    ) = pump.push(
        unrecognizedMessageEnvelope(
            conversationId = conversationId,
            site = site,
            messageType = messageType,
            raw = raw,
            truncated = truncated,
        ),
    )

    /**
     * Script one `turn_end` for [turnId], finalizing the streaming row (#337). The four stop-shape fields
     * (#805) are omitted from the payload when `null`, so a caller that passes none sends the older frame
     * unchanged and exercises the absent-field decode.
     */
    fun pushTurnEnd(
        turnId: String,
        stopReason: String = "end_turn",
        outcome: String? = null,
        isError: Boolean? = null,
        terminalReason: String? = null,
        errorCategory: String? = null,
    ) = pump.push(turnEndEnvelope(conversationId, turnId, stopReason, outcome, isError, terminalReason, errorCategory))

    /** Script one `tool_use` — opens a `Running` tool row keyed by [toolUseId] (#387). */
    fun pushToolUse(
        turnId: String,
        toolUseId: String,
        name: String,
        inputSummary: String,
    ) = pump.push(toolUseEnvelope(conversationId, turnId, toolUseId, name, inputSummary))

    /**
     * Script one `tool_result` — flips the row whose `tool_use_id` matches [toolUseId] to `Failed`
     * when [isError], else `Done` (#387). The [toolUseId] MUST match a prior [pushToolUse]'s id or the
     * fold drops the result (no row to correlate) and the row never resolves.
     */
    fun pushToolResult(
        turnId: String,
        toolUseId: String,
        isError: Boolean,
        resultSummary: String,
    ) = pump.push(toolResultEnvelope(conversationId, turnId, toolUseId, isError, resultSummary))

    /** Script one `tool_progress` — claude's elapsed reading for the open call [toolUseId] (#812). */
    fun pushToolProgress(
        turnId: String,
        toolUseId: String,
        elapsedSeconds: Int,
    ) = pump.push(toolProgressEnvelope(conversationId, turnId, toolUseId, elapsedSeconds))

    /** Script one `tool_denied` — claude refused the call [toolUseId], which leaves the open set (#811). */
    fun pushToolDenied(
        turnId: String,
        toolUseId: String,
        toolName: String,
    ) = pump.push(toolDeniedEnvelope(conversationId, turnId, toolUseId, toolName))

    /**
     * Script one `stall` onset (#395). The wire has no clearing edge: the next live-session event clears it
     * through `StallProjection`. Projects a retained `MutableStateFlow`, like [pushCompacting].
     */
    fun pushStall() = pump.push(stallEnvelope(conversationId))

    /**
     * Script one `session_transition` — folds a `ThreadItem.SessionBoundary` into the thread in arrival
     * order (#336), routed on the harness's own [conversationId] like the other `push*` methods so the
     * folded boundary lands in the thread the screen observes. [reason] stays a plain `String` (matching
     * the wire and the unit-test builder); the harness does not constrain it to the recognized set
     * (`clear` / `idle_evict` / `workspace_change`). Named args below so the builder's param order can't
     * silently transpose [workspaceCwd] into the `occurred_at` slot.
     */
    fun pushSessionTransition(
        previousSessionId: String,
        newSessionId: String,
        reason: String,
        workspaceCwd: String? = null,
    ) = pump.push(
        sessionTransitionEnvelope(
            conversationId = conversationId,
            previousSessionId = previousSessionId,
            newSessionId = newSessionId,
            reason = reason,
            workspaceCwd = workspaceCwd,
        ),
    )

    /**
     * Drive the connection banner (#474). Pushes [state] into the [connectionStateSource] — a retained
     * `MutableStateFlow`, so (unlike the `replay = 0` pump stream) a value emitted before the VM's
     * collector subscribes is re-emitted on subscribe; no readiness gate is needed beyond [start]'s
     * `awaitReady`. This seam is disjoint from the [pump]: the banner reacts to its own connection
     * source, never to the scripted envelope stream.
     */
    fun pushConnectionState(state: ConnectionState) = connectionStateSource.emit(state)

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

/** An `api_retry` envelope `{conversation_id, active, current, total}` (#593), cloning [turnStateEnvelope]'s shape. */
private fun apiRetryEnvelope(
    conversationId: String,
    active: Boolean,
    current: Int,
    total: Int,
): Envelope =
    Envelope(
        id = 1L,
        type = "api_retry",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"$conversationId","active":$active,"current":$current,"total":$total}""",
            ),
    )

/** A `compacting` envelope `{conversation_id, active}` (#596), cloning [turnStateEnvelope]'s shape. */
private fun compactingEnvelope(
    conversationId: String,
    active: Boolean,
): Envelope =
    Envelope(
        id = 1L,
        type = "compacting",
        ts = TS,
        payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId","active":$active}"""),
    )

/** A `resetting` envelope `{conversation_id, active, phase, handoff}` (#871). */
private fun resettingEnvelope(
    conversationId: String,
    active: Boolean,
    phase: String,
    handoff: String,
): Envelope =
    Envelope(
        id = 1L,
        type = "resetting",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"$conversationId","active":$active,"phase":"$phase","handoff":"$handoff"}""",
            ),
    )

/**
 * A `rate_limited` envelope (#802), built through `kotlinx.serialization` so a null [utilization] and a
 * null [truncatedFields] cross as the literal `null`s the daemon always emits.
 */
private fun rateLimitedEnvelope(
    conversationId: String,
    status: String,
    limitType: String,
    resetsAt: Long,
    utilization: Double?,
    truncatedFields: List<String>?,
): Envelope =
    Envelope(
        id = 1L,
        type = "rate_limited",
        ts = TS,
        payload =
            buildJsonObject {
                put("conversation_id", conversationId)
                put("status", status)
                put("limit_type", limitType)
                put("resets_at", resetsAt)
                put("utilization", utilization)
                if (truncatedFields == null) {
                    put("truncated_fields", JsonNull)
                } else {
                    putJsonArray("truncated_fields") { truncatedFields.forEach { add(it) } }
                }
            },
    )

/**
 * A `thinking_progress` envelope `{conversation_id, estimated_tokens, estimated_tokens_delta}` (#801),
 * cloning [turnStateEnvelope]'s shape. Both readings are 64-bit on the wire, so they are interpolated as
 * [Long] — an `Int` here would silently refuse a value the wire can legally carry.
 */
private fun thinkingProgressEnvelope(
    conversationId: String,
    estimatedTokens: Long,
    estimatedTokensDelta: Long,
): Envelope =
    Envelope(
        id = 1L,
        type = "thinking_progress",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"$conversationId","estimated_tokens":$estimatedTokens,""" +
                    """"estimated_tokens_delta":$estimatedTokensDelta}""",
            ),
    )

/**
 * An `unrecognized_message` envelope `{conversation_id, site, message_type, raw, truncated}` (#609).
 *
 * Built with [buildJsonObject] rather than the interpolated `"""{...}"""` the siblings above use, because
 * `raw` is itself JSON: interpolating it would emit a malformed envelope that the real fold silently
 * drops, and the probe would prove nothing while passing (the #593/#460 lesson — a decoder that accepts
 * the wrong thing makes a probe vacuous). The same applies to a hostile `message_type` carrying quotes or
 * control characters.
 */
private fun unrecognizedMessageEnvelope(
    conversationId: String,
    site: String,
    messageType: String,
    raw: String,
    truncated: Boolean,
): Envelope =
    Envelope(
        id = 1L,
        type = "unrecognized_message",
        ts = TS,
        payload =
            buildJsonObject {
                put("conversation_id", conversationId)
                put("site", site)
                put("message_type", messageType)
                put("raw", raw)
                put("truncated", truncated)
            },
    )

/**
 * Built with [buildJsonObject] so a claude-authored field holding control characters (#805) is escaped
 * into valid JSON rather than breaking the interpolated literal.
 */
private fun turnEndEnvelope(
    conversationId: String,
    turnId: String,
    stopReason: String,
    outcome: String?,
    isError: Boolean?,
    terminalReason: String?,
    errorCategory: String?,
): Envelope =
    Envelope(
        id = 1L,
        type = "turn_end",
        ts = TS,
        payload =
            buildJsonObject {
                put("conversation_id", conversationId)
                put("turn_id", turnId)
                put("stop_reason", stopReason)
                outcome?.let { put("outcome", it) }
                isError?.let { put("is_error", it) }
                terminalReason?.let { put("terminal_reason", it) }
                errorCategory?.let { put("error_category", it) }
            },
    )

private fun toolUseEnvelope(
    conversationId: String,
    turnId: String,
    toolUseId: String,
    name: String,
    inputSummary: String,
): Envelope =
    Envelope(
        id = 1L,
        type = "tool_use",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"$conversationId","turn_id":"$turnId","tool_use_id":"$toolUseId","name":"$name","input_summary":"$inputSummary"}""",
            ),
    )

private fun toolResultEnvelope(
    conversationId: String,
    turnId: String,
    toolUseId: String,
    isError: Boolean,
    resultSummary: String,
): Envelope =
    Envelope(
        id = 1L,
        type = "tool_result",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"$conversationId","turn_id":"$turnId","tool_use_id":"$toolUseId","is_error":$isError,"result_summary":"$resultSummary"}""",
            ),
    )

/** A `tool_progress` envelope `{conversation_id, turn_id, tool_use_id, elapsed_seconds}` (#812). */
private fun toolProgressEnvelope(
    conversationId: String,
    turnId: String,
    toolUseId: String,
    elapsedSeconds: Int,
): Envelope =
    Envelope(
        id = 1L,
        type = "tool_progress",
        ts = TS,
        payload =
            buildJsonObject {
                put("conversation_id", conversationId)
                put("turn_id", turnId)
                put("tool_use_id", toolUseId)
                put("elapsed_seconds", elapsedSeconds)
            },
    )

/** A `tool_denied` envelope (#811) with a fixed harness reason. */
private fun toolDeniedEnvelope(
    conversationId: String,
    turnId: String,
    toolUseId: String,
    toolName: String,
): Envelope =
    Envelope(
        id = 1L,
        type = "tool_denied",
        ts = TS,
        payload =
            buildJsonObject {
                put("conversation_id", conversationId)
                put("turn_id", turnId)
                put("tool_use_id", toolUseId)
                put("tool_name", toolName)
                put("decision_reason_type", "other")
                put("decision_reason", "denied by the harness")
                put("message", "The user denied this call.")
            },
    )

/** A `stall` envelope `{conversation_id}` (#395): onset only. */
private fun stallEnvelope(conversationId: String): Envelope =
    Envelope(
        id = 1L,
        type = "stall",
        ts = TS,
        payload = buildJsonObject { put("conversation_id", conversationId) },
    )

/**
 * A `session_transition` envelope `{conversation_id, previous_session_id, new_session_id, reason,
 * occurred_at, workspace_cwd}` (#336), ported verbatim from `RemoteConversationRepositoryTest`.
 * [workspaceCwd] emits `"workspace_cwd":null` when null (the `clear` / `idle_evict` shape) and a quoted
 * string otherwise (the `workspace_change` shape).
 */
private fun sessionTransitionEnvelope(
    conversationId: String,
    previousSessionId: String,
    newSessionId: String,
    reason: String,
    occurredAt: String = TS,
    workspaceCwd: String? = null,
    id: Long = 1L,
): Envelope {
    val cwd = workspaceCwd?.let { "\"$it\"" } ?: "null"
    return Envelope(
        id = id,
        type = "session_transition",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"$conversationId","previous_session_id":"$previousSessionId","new_session_id":"$newSessionId","reason":"$reason","occurred_at":"$occurredAt","workspace_cwd":$cwd}""",
            ),
    )
}

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

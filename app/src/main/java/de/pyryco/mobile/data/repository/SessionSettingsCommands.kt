package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RequestSessionSettingsPayloadDto
import de.pyryco.mobile.data.network.RequestSystemPromptPayloadDto
import de.pyryco.mobile.data.network.SessionSettingsUpdatedPayloadDto
import de.pyryco.mobile.data.network.SetSessionSettingsPayloadDto
import de.pyryco.mobile.data.network.setSystemPromptPayload
import de.pyryco.mobile.data.network.toConversation
import de.pyryco.mobile.data.network.toSessionSettings
import de.pyryco.mobile.data.network.toSystemPromptReading
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.SYSTEM_PROMPT_READ_NOT_INTERACTIVE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.SYSTEM_PROMPT_TOO_LONG
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_REQUEST_SESSION_SETTINGS
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_REQUEST_SYSTEM_PROMPT
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_SET_SESSION_SETTINGS
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_SET_SYSTEM_PROMPT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The session settings and system prompt commands of one connection (#916): the settings read and its
 * refresh trigger, the settings write, and the system prompt read and write, split out of
 * [RemoteConversationRepository] the way the conversation (#914) and message (#915) commands were. The
 * repository keeps the routing — the `session_transition` arm of its `onInbound` calls
 * [bumpSettingsRevision], and the correlated `session_settings` / `session_settings_updated` /
 * `system_prompt` / `conversation_updated` replies reach their waiters through its shared arms — and a
 * one-line hand-off per public command.
 *
 * Built on [requests], the connection's one request counter and reply waiters, so every frame takes its
 * envelope id from the same sequence as every other request. [negotiatedCapabilities] is the repository's
 * supplier, read at send time to gate the two reads on `interactive`. A confirmed [setSystemPrompt] folds
 * into [conversationList].
 *
 * One instance per repository, and a fresh repository per connection (#351), so [settingsRevision] is
 * connection-scoped exactly as it was when it lived in the repository. Nothing here logs.
 */
internal class SessionSettingsCommands(
    private val requests: RelayRequests,
    private val negotiatedCapabilities: () -> Set<String>,
    private val conversationList: ConversationListProjection,
) {
    /**
     * `conversationId -> settings-read ordinal` (#590) — the **refresh trigger** for
     * [observeSessionSettings], deliberately not a cache of the readings themselves. A bump means "the
     * reading you hold is stale, read again"; the value is meaningless beyond being different from the
     * last one, and is never compared across connections (a new connection is a new repository with a
     * fresh map).
     *
     * Two writers, unlike the single-collector projections: the repository's inbound collector bumps
     * through [bumpSettingsRevision] on a `session_transition`, and any caller thread bumps through
     * [refreshSessionSettings] once its settings write has settled. The increment is a genuine **read-modify-write**, so running it inside
     * the atomic [MutableStateFlow.update] is load-bearing rather than stylistic — the
     * [CompactingProjection] posture, not [ApiRetryProjection]'s pure replace. Even the
     * degenerate collapse is safe: two bumps folding into one still trigger a read that observes the
     * newest state, because the read asks the daemon rather than replaying a stored edge.
     *
     * An absent key reads as ordinal `0`, so a first collector needs no seeding and a conversation
     * nobody has opened costs nothing.
     */
    private val settingsRevision = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * The run configuration of [conversationId]'s session over v2 `request_session_settings` (#590,
     * daemon pyrycode#1610/#2449/#2510). A cold per-collector read that re-issues on every trigger and
     * folds **nothing** — no projection on this class holds a [SessionSettings], so there is no stale
     * value to invalidate and no slot for a late reply to land in.
     *
     * Reads the conversation's own [settingsRevision] slice, so a bump for **another** conversation does
     * not re-read this one; [distinctUntilChanged] means a value-identical re-emission does not either.
     * [flatMapLatest] is what makes a superseded read harmless: a new trigger **cancels** the in-flight
     * one before starting the next, so a reply that arrives late has no collector to reach and its
     * deferred is already deregistered by [RelayRequests.sendAndAwaitReply]'s `finally`.
     *
     * The [onStart] `null` is not cosmetic. It resets the reading to *unavailable* at the head of every
     * subscription, which is what keeps a host handoff clean: the facade re-subscribes on the new
     * connection, and without it a consumer would keep rendering the **previous host's** values until
     * the new read landed (AC #1).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> =
        settingsRevision
            .map { it[conversationId] ?: 0L }
            .distinctUntilChanged()
            .flatMapLatest { sessionSettingsRead(conversationId) }
            .onStart { emit(null) }

    /**
     * One settings read as a single-emission flow (#590). Fails **closed to `null`** rather than to the
     * caller, because the consumer of a footer reading has nothing to retry with — the next trigger
     * re-reads from scratch — and AC #4 requires a failed read to leave the reading unavailable rather
     * than fall back to anything.
     *
     * [kotlinx.coroutines.flow.catch] rather than a `try`/`catch`: it converts an upstream failure
     * without swallowing the **collector's own cancellation**, which a `runCatching` around a suspend
     * call would. Scoped to this inner flow rather than applied to [observeSessionSettings] as a whole,
     * because a terminal `catch` there would end the outer flow and the conversation would never read
     * again after one failure.
     *
     * **The caught throwable is discarded and never logged, and that is load-bearing.** Only the
     * `effective_effort` message is ours and content-free; a structural decode failure is authored by
     * kotlinx-serialization, whose message can quote the offending input. Dropping it is what keeps AC
     * #2's "logs no payload content" true — a later `catch { Log.w(TAG, it) }` would leak daemon payload
     * content to Logcat in one line.
     *
     * **Gated fail-closed on `interactive`**, like the live-stream arms: the daemon leaves a conn that
     * did not negotiate it fully inert on this verb — no reply, not even a signal that the conversation
     * exists — so an ungated send would suspend until teardown. Not sending is also what keeps "the read
     * sends `request_session_settings` and nothing else" true in the degenerate case.
     */
    private fun sessionSettingsRead(conversationId: String): Flow<SessionSettings?> =
        flow {
            emit(
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) readSessionSettings(conversationId) else null,
            )
        }.catch { emit(null) }

    /**
     * Send one `request_session_settings` and decode its correlated `session_settings` reply (#590) —
     * the [RemoteConversationRepository.requestHistory] body minus the projection fold. The reply is routed **by the id this call
     * asked with**: it carries no `conversation_id` of its own, so a reading structurally cannot
     * cross-route into another conversation, the property [ThreadProjection.mergeHistoryPage] relies on one level up.
     *
     * Throws rather than returning null — [sessionSettingsRead] owns the conversion — so every failure
     * mode stays distinguishable at this seam: [IllegalStateException] when the pump is not `Open` or
     * tears down mid-await (#488), [RelayErrorException] for a server `error` (this verb publishes no
     * reject codes of its own — it always answers — so one can only be transport-level), and a
     * [kotlinx.serialization.SerializationException] for a malformed reply. The daemon's all-zero reply
     * is a **successful** read of "nothing resolved", not a failure.
     *
     * Emits no log on any branch, like the rest of this class: the session id, the model and the effort
     * strings never reach Logcat.
     */
    private suspend fun readSessionSettings(conversationId: String): SessionSettings {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_REQUEST_SESSION_SETTINGS,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RequestSessionSettingsPayloadDto(conversationId = conversationId)),
            )
        return requests.sendAndAwaitReply(request).toSessionSettings()
    }

    /**
     * Invalidate [conversationId]'s settings reading (#590) — the caller-driven trigger, for the moment
     * a settings write has settled. Sends nothing itself: it bumps [settingsRevision], and a collector
     * (if one is listening) issues the read on its own coroutine. Non-suspending and non-throwing, so a
     * caller with no live connection drops it rather than handling a failure it cannot act on.
     */
    fun refreshSessionSettings(conversationId: String) = bumpSettingsRevision(conversationId)

    /** Atomic read-modify-write of one conversation's settings-read ordinal; see [settingsRevision]. */
    fun bumpSettingsRevision(conversationId: String) {
        settingsRevision.update { current -> current + (conversationId to (current[conversationId] ?: 0L) + 1L) }
    }

    /**
     * Apply the operator's run-configuration change — [model] / [effort] / [yolo] — to the running
     * session [sessionId] over v2 `set_session_settings` (#543, server #844/#845). A direct analogue of
     * [ConversationCommands.rename] (encode → [RelayRequests.sendAndAwaitReply] → typed-decode) **minus the state fold**: session settings
     * are ViewModel state (#544), not a projection in this repo, so there is nothing to upsert.
     *
     * Encodes [SetSessionSettingsPayloadDto] under the presence contract — a `null` field is **omitted**
     * ([MobileJson]'s `explicitNulls = false`), meaning "leave unchanged"; a non-null value (including
     * `false` / `""`) is always sent — then awaits the correlated `session_settings_updated` ack. The
     * ack carries only `{session_id}` (an echo of the input, not the applied settings), so it is decoded
     * through the [SessionSettingsUpdatedPayloadDto] boundary **only** to validate the reply shape
     * (#318 posture — a malformed ack throws here); the decoded value is discarded. Returns [Unit] —
     * there is nothing to return, and no projection is touched.
     *
     * [sessionId] / [model] / [effort] / [yolo] are forwarded **verbatim** (as [ConversationCommands.rename] forwards the
     * dialog's name); the daemon re-validates `model` / `effort` server-side and gates on the
     * interactive capability. Throws [IllegalStateException] when the session is not connected, and —
     * unlike the conversation-scoped verbs — has **no** [IllegalArgumentException] path: the
     * unhosted-session code is `session.not_found` (not `conversation.not_found`), so every server
     * `error` maps to a [RelayErrorException] carrying its `code` (`session.not_found` /
     * `protocol.malformed` / `server.binary_offline`). A malformed ack throws the #318 decode exception.
     * None of these mutate any projection.
     */
    suspend fun setSessionSettings(
        sessionId: String,
        model: String?,
        effort: String?,
        yolo: Boolean?,
        permissionMode: String?,
    ) {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_SET_SESSION_SETTINGS,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        SetSessionSettingsPayloadDto(
                            sessionId = sessionId,
                            model = model,
                            effort = effort,
                            yolo = yolo,
                            permissionMode = permissionMode,
                        ),
                    ),
            )
        // Throws on a server `error` / not-Open session before the decode below. The reply is the bare
        // {session_id} ack; decode validates its shape (a malformed ack throws) — the result is discarded.
        val reply = requests.sendAndAwaitReply(request)
        MobileJson.decodeFromJsonElement<SessionSettingsUpdatedPayloadDto>(reply)
    }

    /**
     * Read [conversationId]'s stored system prompt over v2 `request_system_prompt` (#823) — the
     * [readSessionSettings] shape as a public one-shot. The `system_prompt` reply carries no
     * conversation id, so it can only complete the waiter this call registered.
     *
     * **Gated fail-closed on `interactive`** before any frame is built: the daemon leaves a conn without
     * it fully inert on this verb, so an ungated send would wait until teardown. Throws
     * [IllegalStateException] for that and for a not-`Open` pump or teardown mid-await (#488), and a
     * [kotlinx.serialization.SerializationException] for a malformed reply. Touches no state on any
     * branch and logs nothing: the prompt, its length and the conversation id never reach Logcat.
     */
    suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading {
        check(CAPABILITY_INTERACTIVE in negotiatedCapabilities()) { SYSTEM_PROMPT_READ_NOT_INTERACTIVE }
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_REQUEST_SYSTEM_PROMPT,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RequestSystemPromptPayloadDto(conversationId = conversationId)),
            )
        return requests.sendAndAwaitReply(request).toSystemPromptReading()
    }

    /**
     * Set or clear [conversationId]'s system prompt over v2 `set_system_prompt` (#823) — the [ConversationCommands.rename]
     * shape. [systemPrompt] is forwarded verbatim in its three states ([setSystemPromptPayload]); a value
     * over [SystemPromptLimit.MAX_BYTES] UTF-8 bytes is refused with [IllegalArgumentException] **before
     * any frame is sent**. The ack is the reused `conversation_updated` record, which carries no prompt;
     * it is decoded through the #318 boundary and confirmed-upserted exactly as [ConversationCommands.rename]'s is.
     *
     * Not gated on `interactive`: the daemon answers this verb on any conn. Throws
     * [IllegalArgumentException] for `conversation.not_found` ([RelayRequests.mapError]), [RelayErrorException] for
     * any other server `error`, [IllegalStateException] when not connected, and the decode exception for
     * a malformed ack — none of which mutate [ConversationListProjection]. The refusal message is static.
     */
    suspend fun setSystemPrompt(
        conversationId: String,
        systemPrompt: String?,
    ) {
        require(systemPrompt == null || SystemPromptLimit.fits(systemPrompt)) { SYSTEM_PROMPT_TOO_LONG }
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = TYPE_SET_SYSTEM_PROMPT,
                ts = Clock.System.now().toString(),
                payload = setSystemPromptPayload(conversationId, systemPrompt),
            )
        val reply = requests.sendAndAwaitReply(request)
        conversationList.upsertConversation(MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation())
    }
}

package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ErrorPayload
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModelListPayloadDto
import de.pyryco.mobile.data.network.RequestModelListPayloadDto
import de.pyryco.mobile.data.network.toMenu
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.ERROR_MODEL_LIST_UNAVAILABLE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_REQUEST_MODEL_LIST
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * The model menu of every conversation on one connection (#913): the retained menus, the one-shot
 * `request_model_list` ask and its refusal correlation, split out of [RemoteConversationRepository] the way
 * the thread store was (#912). The repository keeps the routing: its `onInbound` arms call [apply] for a
 * `model_list` only behind the negotiated `interactive` gate, and hand an `error` to [applyRefusal] beside
 * completing any pending request. [RemoteConversationRepository.observeModelMenu] reads [observe].
 *
 * [send] is the repository's pump send, [negotiatedCapabilities] its capability supplier, and
 * [nextRequestId] its one request-id counter, so the ask takes its envelope id from the same sequence as
 * every other request and [modelListAsks] stays disjoint from the repository's pending requests.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository. Nothing here logs.
 */
internal class ModelMenuProjection(
    private val send: (Envelope) -> Boolean,
    private val negotiatedCapabilities: () -> Set<String>,
    private val nextRequestId: () -> Long,
) {
    /**
     * `conversationId -> the model menu this connection heard for it` (#791) — the identifiers, labels,
     * per-row effort levels and auto-mode support the daemon published. Written **only** from the
     * repository's single inbound collector: each `model_list` frame is a full snapshot that **replaces** that
     * conversation's entry, leaving every other conversation untouched. Single writer on the one
     * collector coroutine, so snapshots never race; the atomic [MutableStateFlow.update] matches the
     * sibling projections' memory-visibility posture. [observe] fans out from it.
     *
     * **Nothing ever removes a key, and no connection edge clears the map.** Absence of a frame is the
     * wire's only "no list" signal, so a blanket clear would manufacture an unavailable reading the
     * daemon never stated. Connection-scoped in-memory state — a fresh repository per connection (#351)
     * starts empty, which is the only reset this state has, and is also where "per host" comes from: the
     * published vocabulary varies by machine and account rather than by conversation.
     *
     * Unlike [QueueProjection] this is **not** a transient "right now" condition — a published
     * vocabulary is a standing fact about the host for as long as the connection lives.
     */
    private val modelMenusByConversation = MutableStateFlow<Map<String, ModelMenu>>(emptyMap())

    /**
     * The conversations this connection already sent a `request_model_list` for (#792) — the one-shot
     * ledger behind [askForModelMenu]. Connection-scoped like [modelMenusByConversation], so "asked
     * once" means once per connection: a fresh repository (#351) starts empty and the new connection's
     * reconcile burst is what fills it, which is exactly the recovery path the no-retry rule names.
     *
     * Membership is added by an atomic [MutableSet.add] **test-and-set** rather than a read followed by
     * a write, so two collectors subscribing to the same conversation in the same instant still produce
     * one ask. An entry is removed on only two occasions, neither of which re-sends anything: a send the
     * transport refused (an ask that never left is not an ask), and a `model_list.unavailable` refusal,
     * which is the wire's statement that *the same request may succeed later* — see
     * [onModelListRefusal].
     *
     * Its keyspace is conversation ids the client already holds from the daemon's own `conversations`
     * snapshot, so nothing a daemon sends can grow it past the set it already published, and it dies
     * with the connection. It holds ids and no content.
     */
    private val askedModelMenus: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Request *envelope* id -> the conversation that `request_model_list` named (#792). The refusal
     * correlation, and deliberately **not** [RelayRequests]' `pendingRequests`: this verb's two replies
     * arrive on different arms — a success is a `model_list` that #791's arm applies by the payload's own
     * conversation id and that completes no waiter, while a refusal is an `error`. A
     * [RelayRequests.sendAndAwaitReply] here would therefore suspend until teardown on the one outcome the verb
     * exists to produce, and AC #4 forbids waiting on a reply that cannot come at all.
     *
     * So the ask is fire-and-forget and this map is the whole of its correlation: written before the
     * send, consumed by whichever arm answers. The two maps are **disjoint by construction** — an ask
     * registers in exactly one of them and never the other — so an `inReplyTo` resolves in at most one
     * and neither lookup can consume the other's reply.
     *
     * **The entry is consumed and discarded, never used to route the retention.** Routing stays the
     * `model_list` payload's own `conversation_id`, so a daemon answering an ask for A with a payload
     * naming B cannot land B's rows under A — the cross-conversation injection #791 foreclosed
     * structurally, which this ticket must not re-open by correlating what it must not.
     */
    private val modelListAsks = ConcurrentHashMap<Long, String>()

    /**
     * Apply one `model_list` envelope. Called only from the repository's `interactive`-gated arm; the
     * reasoning below was written for that arm and moved here with it.
     */
    fun apply(envelope: Envelope) {
        // The per-conversation model menu (#791). Same `interactive` gate as the live-session /
        // `stall` / `queue_state` siblings: a non-interactive phone never decodes a spurious
        // `model_list` from a buggy/hostile daemon that ignored the server-side fan-out gate
        // (fail-closed, defence in depth). Each frame is snapshot-shaped full state, so it FULLY
        // REPLACES this conversation's entry and leaves every other conversation untouched — no
        // merge, no append, and re-applying the reconnect burst is safe by construction.
        //
        // Routing is the payload's own conversation_id and NOTHING else. Never the envelope id,
        // which every frame in the reconcile burst repeats, and never burst position: the daemon
        // walks its registry in an order that is not a contract. An id no collector observes
        // simply sits unread in the map.
        //
        // Nothing is cleared here or on a connection edge — absence of a frame is the wire's only
        // "no list" signal, so a clear would manufacture an unavailable reading the daemon never
        // stated. A malformed payload decodes to null and is dropped so the single inbound
        // consumer survives, leaving the previously retained menu standing. Like the `queue_state`
        // sibling and unlike the live-session arm, this folds no thread row and does NOT clear a
        // stall — a menu is not turn forward progress. Drop silently: every row string is
        // claude-authored text that crossed the subprocess trust boundary, and a logged
        // conversation_id is a cross-conversation correlation leak.
        //
        // Since #792 the frame is also the correlated answer to this client's own
        // `request_model_list`, and the ONLY thing that changes for it is that the ask's
        // correlation entry is consumed here. It is consumed and DISCARDED: the retention below
        // still routes on the payload's own conversation_id, so a daemon answering an ask for A
        // with a payload naming B lands B's rows under B and leaves A unavailable, rather than
        // cross-routing them. The removal is unconditional on the decode succeeding — a
        // malformed answer is still an answer, and leaving the entry would leak it until the
        // connection died — and it does NOT release the one-shot in [askedModelMenus], because
        // the ask was answered.
        envelope.inReplyTo?.let(modelListAsks::remove)
        decodeModelList(envelope)?.let { (conversationId, menu) ->
            modelMenusByConversation.update { it + (conversationId to menu) }
        }
    }

    /**
     * Hand an `error` correlated by [inReplyTo] to the model-list ask it answers, if any (#792). An id
     * this projection never registered — a reply to one of the repository's pending requests, or a
     * stale, duplicated or unsolicited error — is a no-op.
     */
    fun applyRefusal(
        inReplyTo: Long,
        payload: JsonElement,
    ) {
        modelListAsks.remove(inReplyTo)?.let { conversationId -> onModelListRefusal(conversationId, payload) }
    }

    /**
     * The model menu this connection heard for [conversationId] (#791), a cold projection of the
     * shared [modelMenusByConversation] `StateFlow` over the `model_list` frames the daemon publishes
     * unasked. An absent key is `null`, which is **unavailable**: a normal,
     * permanent resting state, never an error, never the `Model` / `Effort` device enums and — because
     * the lookup is by the caller's own id — never another conversation's rows.
     *
     * [distinctUntilChanged] suppresses only value-*identical* re-emissions, so a `model_list` for
     * **another** conversation does not re-emit this flow, and the reconnect burst's re-send of an
     * unchanged menu costs a consumer nothing. A genuinely different menu is a different [ModelMenu]
     * value and does reach the collector — the [RemoteConversationRepository.observeApiRetry] property,
     * which a membership `Set` could not provide. A `StateFlow` always has a current value, so every
     * collector (including a `flatMapLatest` re-subscription through the facade) receives the current
     * reading (`null` until a frame lands) on subscription; the one inbound consumer fans out to unlimited
     * collectors.
     *
     * Since #792 the subscription also **triggers the ask** for a conversation this connection holds no
     * menu for — see [askForModelMenu]. Subscribing to a conversation's menu is wanting it, and this is
     * the seam where the conversation to name is known, the desktop client's conversation-activation
     * decision transferred to the reading mobile actually has. The ask is non-suspending and
     * non-throwing, so the first emission is not delayed and an unavailable reading still reports
     * `null` immediately rather than stalling on a reply that may never come.
     */
    fun observe(conversationId: String): Flow<ModelMenu?> =
        modelMenusByConversation
            .map { it[conversationId] }
            .distinctUntilChanged()
            .onStart { askForModelMenu(conversationId) }

    /**
     * Send one `request_model_list` naming [conversationId] (#792, daemon pyrycode#2125) — the third
     * and last way a client gets a menu and the only one it can trigger itself. It closes the window
     * the frame's two unsolicited paths leave open: a conversation **created after the phone connected**
     * crosses neither the live lane nor the connect-time reconcile, so without this its model and effort
     * controls stay blank with nothing to wait for.
     *
     * **Fire-and-forget, non-suspending and non-throwing** — the
     * [RemoteConversationRepository.requestDebugBundle] posture, not [RelayRequests.sendAndAwaitReply].
     * The success is a `model_list` handled by its own arm and completes no waiter,
     * so an awaiting send would suspend until teardown on the very outcome this verb exists to produce;
     * and a conn without `interactive` is answered with nothing at all, so there would be nothing to
     * wait for. Every failure is absorbed here: nothing is thrown into the subscribing collector, and
     * the reading keeps reporting `null`.
     *
     * Four guards, each returning without sending:
     *
     *  1. **An empty id names nothing** and is refused daemon-side, so it is the same failure spelled
     *     differently rather than a second case — not sending is the whole of that branch.
     *  2. **`interactive` was not negotiated.** The daemon leaves such a conn fully inert on this verb,
     *     so a send would buy nothing and could not even be refused.
     *  3. **A menu is already retained** for it — "a conversation that already holds a menu is not asked
     *     again". A plain snapshot read, deliberately not atomic with guard 4: the worst a race there
     *     costs is one redundant ask for a menu that landed in the same instant, which the daemon
     *     answers idempotently.
     *  4. **It was already asked** on this connection, via [askedModelMenus]'s atomic test-and-set.
     *
     * Past the guards, the correlation is registered **before** the send ([RelayRequests.sendAndAwaitReply]'s
     * no-lost-reply ordering) and a send the transport refused rolls **both** entries back. That is not
     * a retry — nothing re-sends — it only declines to burn the one shot on a frame that never left,
     * and in that window the pump is not `Open`, so no ask of any collector's could have gone out
     * either.
     *
     * **Never logs, on any branch.** The conversation id is a cross-conversation correlation key, and
     * this method authors no message at all — deliberately not [RemoteConversationRepository.interrupt]'s
     * `check(pump.send(…)) { … }` idiom, so there is no failure text to leak.
     */
    private fun askForModelMenu(conversationId: String) {
        if (conversationId.isEmpty()) return
        if (CAPABILITY_INTERACTIVE !in negotiatedCapabilities()) return
        if (conversationId in modelMenusByConversation.value) return
        if (!askedModelMenus.add(conversationId)) return

        val request =
            Envelope(
                id = nextRequestId(),
                type = TYPE_REQUEST_MODEL_LIST,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RequestModelListPayloadDto(conversationId = conversationId)),
            )
        modelListAsks[request.id] = conversationId
        val sent =
            try {
                send(request)
            } catch (e: Exception) {
                false
            }
        if (!sent) {
            modelListAsks.remove(request.id)
            askedModelMenus.remove(conversationId)
        }
    }

    /**
     * Read a refusal of this client's own `request_model_list` (#792) and decide whether the one-shot
     * stands. **Tells the daemon's two codes apart, which is the point of this method**: they mean
     * different things and a client that merged them would either re-ask a conversation that will never
     * exist or never re-ask one that would answer tomorrow.
     *
     *  - **`model_list.unavailable`** — the daemon *does* host the conversation but has no vocabulary to
     *    answer with yet, so the same request may succeed later. The [askedModelMenus] entry is released
     *    so a **later trigger** may ask again.
     *  - **`conversation.not_found`** — the daemon does not host what was named. **Terminal** for that
     *    id on this connection; the entry stands.
     *  - **Any other code, and a payload that will not decode** — fail closed, treated as terminal. An
     *    unrecognised code is not a statement that asking again would help.
     *
     * **Releasing is not retrying** (AC #3). There is no timer, no backoff, no scheduled re-send and
     * nothing here that sends at all: a refusal with the collector still subscribed produces no second
     * frame, and only a **new** subscription asks again — which this class never creates. A reply that
     * never arrives and a timeout need no handling for the same reason there is no waiter to expire.
     *
     * **Neither branch writes [modelMenusByConversation]**, so no refusal becomes an empty menu; both
     * leave the conversation unavailable, the same resting state it was already in.
     *
     * [RelayRequests.mapError] is deliberately not reused: it collapses `conversation.not_found` into an
     * [IllegalArgumentException] and discards the very code this branch exists to read.
     *
     * **Never logs.** It reads [ErrorPayload.code] and discards the rest — `message` is daemon-authored
     * prose and pairing it with a conversation id in one line is exactly what this ticket's security
     * note forbids. The decode failure is caught and **discarded** rather than logged or rethrown, since
     * a kotlinx-serialization message can quote the offending input.
     */
    private fun onModelListRefusal(
        conversationId: String,
        payload: JsonElement,
    ) {
        val code =
            try {
                MobileJson.decodeFromJsonElement<ErrorPayload>(payload).code
            } catch (e: IllegalArgumentException) {
                return
            }
        if (code == ERROR_MODEL_LIST_UNAVAILABLE) askedModelMenus.remove(conversationId)
    }

    /**
     * Decode one v2 `model_list` envelope (#791) to its routing conversation id and retained
     * [ModelMenu], or **null** when it cannot be read. Decodes the untrusted [Envelope.payload] through
     * the single configured [MobileJson] and maps via `toMenu()`. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload — a missing/wrong-typed `conversation_id` or
     * `dropped_models`, a `models` or `effort_levels` that is explicitly `null` (both are always arrays
     * on the wire), or a row missing one of its required strings — yields `null`, dropping the one
     * envelope while the lone inbound collector survives. There is no partial menu: a bad row fails the
     * whole frame, and the caller's previously retained menu stands because nothing was written.
     *
     * Because `toMenu()` is **total**, structural malformation is the only null path — there is no
     * unrecognized *value* to reject, unlike the repository's `decodeLiveSessionEvent`. Returning a [Pair]
     * of the routing id and the already-mapped domain value keeps the untrusted wire DTO from escaping this
     * boundary, matching every sibling decoder. Mirrors the [StallProjection] / [QueueProjection] decoders' drop idiom —
     * **nothing here logs the payload**, which is mandatory rather than stylistic: the rows are
     * claude-authored text the daemon does not sanitize, and the caught throwable (kotlinx-serialization
     * can quote the offending input in its message) is discarded rather than surfaced.
     */
    private fun decodeModelList(envelope: Envelope): Pair<String, ModelMenu>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ModelListPayloadDto>(envelope.payload)
            dto.conversationId to dto.toMenu()
        } catch (e: IllegalArgumentException) {
            null
        }
}

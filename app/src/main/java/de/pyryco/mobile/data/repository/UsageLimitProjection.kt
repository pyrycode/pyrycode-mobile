package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RateLimitedPayloadDto
import de.pyryco.mobile.data.network.toReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Instant
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * What claude last said about its usage-limit window, for every conversation on one connection (#802): its
 * state, its decoder and its read for the `rate_limited` event, kept out of [RemoteConversationRepository]
 * so each status event lives in its own file. The repository keeps the routing: its `onInbound` arm calls
 * [apply] only behind the negotiated `interactive` gate. [now] is the repository's clock, passed through
 * so the read-time expiry compares against the same wall clock a test can supply.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped.
 */
internal class UsageLimitProjection(
    private val now: () -> Instant,
) {
    /**
     * `conversationId -> the usage-limit reading claude last reported for it` (#802) — what claude said
     * about its usage-limit window, so a waiting turn can say why. A payload-carrying `Map`, the
     * [ApiRetryProjection] shape rather than [CompactingProjection]'s membership `Set`: this frame
     * carries five wire fields, not an edge bool.
     *
     * Written **only** from the repository's single inbound collector: a non-benign frame **replaces** that
     * conversation's entry and a benign one **removes** it, leaving every other conversation untouched.
     * Single writer on the one collector coroutine, so the raising and clearing edges never race; each
     * write is a pure replace or a pure removal that reads no held state, so there is no
     * check-then-mutate window even in principle, and the atomic [MutableStateFlow.update] matches the
     * sibling projections' memory-visibility posture. [observe] fans out from it.
     *
     * **A cleared entry is REMOVED rather than stored as a tombstone**, deliberately unlike
     * [ApiRetryProjection]'s stored falling edge: absence is already the observable "nothing to
     * read" because the observer maps an absent key to `null`, and a stored tombstone would need a
     * second value meaning the same thing.
     *
     * Connection-scoped in-memory state — a fresh repository per connection (#351) starts empty, which
     * is also this state's **pairing-scoped clear**: a usage-limit posture belongs to an account, and
     * nothing re-asserts a reading after a reconnect, so a reading can never be attributed to the next
     * account. **Nothing here is persisted and nothing may be** — a persisted copy would outlive the
     * connection scope that is the whole clear mechanism.
     *
     * Growth, stated rather than defended: one bounded record per distinct `conversation_id` seen on
     * this connection. Both halves are bounded per frame — the transport's frame contract caps the
     * envelope ahead of any parse and the daemon bounds both strings at construction — so a flooding
     * daemon costs one entry per distinct id rather than an unbounded append per frame, and the
     * connection scope returns it to zero. The posture [QueueProjection] and
     * `ModelMenuProjection.modelMenusByConversation` already ship; no eviction policy is built for a failure nobody has
     * observed. In particular **the expiry is not an eviction**: an expired entry stays here and merely
     * stops being readable (see [observe]), which is what keeps this projection free of the
     * timer it would otherwise need.
     */
    private val usageLimitsByConversation = MutableStateFlow<Map<String, UsageLimitReading>>(emptyMap())

    /**
     * Apply one `rate_limited` envelope. Called only from the repository's `interactive`-gated arm; the
     * reasoning below was written for that arm and moved here with it.
     */
    fun apply(envelope: Envelope) {
        // What claude said about its usage-limit window (#802). Same `interactive` gate as the
        // live-session / `stall` / `queue_state` / `api_retry` / `compacting` siblings: a
        // non-interactive phone never decodes a spurious `rate_limited` from a buggy/hostile
        // daemon that ignored the server-side fan-out gate (fail-closed, defence in depth).
        //
        // One transition for this conversation, leaving every other conversation untouched
        // (AC #2) — a non-benign reading replaces its entry, the benign falling edge removes
        // it. The `reading == null` branch is a *dispatch* on what the mapper already decided,
        // not a second reading of the wire: `toReading()` is the sole owner of the benign
        // comparison, so unlike the `compacting` arm no status is examined here. Routing is
        // strictly the payload's own conversation_id, which is what makes AC #2's
        // different-`limit_type` clause hold structurally: `limit_type` is read by no
        // control-flow path, so pairing the clear to it is not expressible. Removing an absent
        // id is a no-op, so a benign frame for a conversation holding nothing is inert.
        //
        // A malformed payload decodes to null and is dropped so the single inbound consumer
        // survives (AC #1). Like the `queue_state` / `api_retry` / `compacting` siblings and
        // unlike the live-session arm, this folds no thread row and does NOT clear a stall in
        // either direction (AC #5) — a usage-limit report is neither a stall nor turn forward
        // progress, and clearing one here would let a daemon suppress the phone's stall
        // indicator by emitting `rate_limited` frames.
        //
        // Drop silently. Nothing here logs the payload, and that is mandatory rather than
        // stylistic: `status` and `limit_type` are claude-authored text the daemon does not
        // sanitize, a logged conversation_id is a cross-conversation correlation leak, and the
        // pair together discloses the account's quota posture — a fact about the operator
        // rather than about this frame.
        decodeRateLimited(envelope)?.let { (conversationId, reading) ->
            usageLimitsByConversation.update {
                if (reading == null) it - conversationId else it + (conversationId to reading)
            }
        }
    }

    /**
     * The usage-limit reading claude last reported for [conversationId] (#802), a cold projection of
     * the shared [usageLimitsByConversation] `StateFlow` — **and the only read surface it has**, which
     * is what makes this the single place the expiry rule lives. Issues no request; rides the live
     * `rate_limited` frames. An absent key is `null`, so "nothing reported", "cleared by a benign
     * frame" and "the reported window has passed" are one observable state, which is all a consumer
     * needs to tell apart.
     *
     * [distinctUntilChanged] suppresses only value-*identical* re-emissions, so a `rate_limited` for
     * **another** conversation does not re-emit this flow, while a genuinely changed reading is a
     * different [UsageLimitReading] value and does reach the collector — the [ApiRetryProjection.observe]
     * property a membership `Set` could not provide. A `StateFlow` always has a current value, so
     * every collector (including a `flatMapLatest` re-subscription through the facade) receives the
     * current reading (`null` until a frame lands) on subscription; the one inbound consumer fans out
     * to unlimited collectors.
     *
     * **The expiry is one comparison performed when a reader asks, and there is deliberately no
     * timer.** Nothing in this class schedules, delays, allocates or iterates from
     * [UsageLimitReading.resetsAt] — it is claude's unvalidated number, so a delay computed from it
     * could be negative (firing immediately, and spinning if a handler re-armed) or past a timer's
     * clamp, which *also* fires immediately rather than never. The consequence, stated rather than
     * discovered: an already-subscribed collector receives **no spontaneous emission at the deadline**
     * — it re-evaluates on the next upstream change, while a collector subscribing after the deadline
     * reads `null` at once because the `StateFlow` replays its current value through this `map`. A
     * render consumer owns its own recomposition cadence and must not re-derive the rule.
     *
     * One further consequence of comparing against a **wall** clock, named so a later reader does not
     * re-derive it as a bug: a device clock moved backwards can make an expired reading readable
     * again. That is inherent rather than a defect — `resets_at` is a wall-clock unix instant, so a
     * monotonic clock would be the wrong comparand — and the blast radius is one stale row that the
     * next frame corrects. Desktop's `selectUsageLimitFor` carries the identical property.
     */
    fun observe(conversationId: String): Flow<UsageLimitReading?> =
        usageLimitsByConversation.map { it[conversationId]?.takeIf(::isReadable) }.distinctUntilChanged()

    /**
     * Whether [reading] is still readable at the current [now] (#802) — the whole of the expiry rule,
     * in reading order, which is also precedence order:
     *
     * ```
     * resetsAt == 0                    → readable   claude reported NO reset
     * now().epochSeconds < resetsAt    → readable   inside the window claude reported
     * now().epochSeconds >= resetsAt   → hidden     the window claude reported has passed
     * ```
     *
     * **The zero test comes first and that ordering is the point.** `0` means claude reported no
     * reset, *not* the epoch; folded into the comparison it would read as "expired in 1970" and make
     * every unreported reading invisible the moment it landed — the failure this branch forecloses,
     * and the common case rather than an exotic one.
     *
     * **The boundary is exclusive**, so a reading is hidden *at* [UsageLimitReading.resetsAt] as well
     * as after it: the reset instant is when the window is fresh again, not the last instant it was
     * stale. A negative `resetsAt` is a past instant and so is hidden immediately — the honest reading
     * of an unvalidated number rather than a rejection, since the wire rejects none either and the
     * decode still **carried** it.
     */
    private fun isReadable(reading: UsageLimitReading): Boolean = reading.resetsAt == 0L || now().epochSeconds < reading.resetsAt

    /**
     * Decode one v2 `rate_limited` envelope (#802) to its routing conversation id and the mapped
     * [UsageLimitReading], or **null** when it cannot be read. Decodes the untrusted [Envelope.payload]
     * through the single configured [MobileJson] and maps via `toReading()`. Returning already-mapped
     * domain values keeps the untrusted wire DTO from escaping this boundary, matching every sibling
     * decoder.
     *
     * **The two nullability levels say different things, and conflating them is the trap here.** The
     * **outer** `null` is "malformed — drop this envelope and hold what we have": the whole body is one
     * `try`/`catch (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a missing required field, or one whose JSON shape cannot be read
     * as its declared type, drops the one envelope while the lone inbound collector survives (AC #1).
     * The **inner** `null` is the mapper's benign **falling edge** — a perfectly well-formed frame
     * saying claude's latest reading is benign — which the caller turns into a clear (AC #2). A caller
     * that collapsed the two would either clear on a malformed frame or ignore every clear.
     *
     * Unlike [RemoteConversationRepository.decodeLiveSessionEvent] there is no unrecognized-*value* drop: an unrecognised `status`
     * is an ordinary warning and surfaces verbatim, because its value set is almost entirely
     * unmeasured and narrowing it would discard the first real limit that fires.
     *
     * Mirrors [StallProjection] / [CompactingProjection]'s drop idiom — **nothing here logs the payload**, and
     * here that is mandatory rather than uniform-for-its-own-sake: `status` and `limit_type` are
     * claude-authored text the daemon does not sanitize, and the caught throwable is **discarded**
     * rather than surfaced because kotlinx-serialization can quote the offending input in its message.
     */
    private fun decodeRateLimited(envelope: Envelope): Pair<String, UsageLimitReading?>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<RateLimitedPayloadDto>(envelope.payload)
            dto.conversationId to dto.toReading()
        } catch (e: IllegalArgumentException) {
            null
        }
}

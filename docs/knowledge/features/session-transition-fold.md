# Session-transition fold — `session_transition` → `ThreadItem.SessionBoundary`

The **data-layer wire→thread fold** ([#336](../codebase/336.md), split from #313) that turns the
capability-gated v2 `session_transition` interactive event into a `ThreadItem.SessionBoundary` row
interleaved into the conversation thread at a `/clear` / idle-evict / workspace-change transition. This
is the **real-backend source** of the boundary delimiters the
[`SessionBoundaryDelimiter`](session-boundary-delimiter.md) (#135/#192) already renders — the producing
half of that component, on the live path. The render side is untouched by #336.

It is a sibling of the other capability-gated interactive folds in the
[`RemoteConversationRepository`](remote-conversation-repository.md) — [live-session
events](live-session-events.md) (#385), [stall state](stall-state.md) (#395), [queued
backlog](queued-backlog.md) (#460), [modal events](modal-events.md) (#437) — and reuses their decode
substrate and gate verbatim. Where it differs: it folds a **thread row** (not a separate flow), so it
required the one structural change #336 carried — unifying the thread store to `List<ThreadItem>`.

`security-sensitive` — § [Trust boundary](#trust-boundary--no-payload-logging). Architect self-review
PASS; code-review PASS, zero findings.

## The load-bearing change: a unified thread store

The store the thread read fans out from moved from holding messages to holding **thread rows**:

```kotlin
// before (#313):  messages only, every fold keys off Message.id
private val messagesByConversation = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
// after  (#336):  both row kinds, so a non-Message boundary interleaves by arrival order
private val threadByConversation  = MutableStateFlow<Map<String, List<ThreadItem>>>(emptyMap())
```

A `SessionBoundary` is a non-`Message` `ThreadItem` that must interleave **in arrival order** with
messages. Two separate stores can't be re-interleaved — messages are id-fixed, boundaries have no id, so
there is no shared ordering key. The elegant move is one store holding both kinds. The five existing
`Message.id`-keyed folds (`appendMessages`, `applyToolUse`, `applyToolResult`, `applyAssistantDelta`,
`finalizeAssistantTurn`) were lifted to `ThreadItem` **output-preserving** — the dedup / append /
in-place-replace / idempotent-skip semantics are unchanged, guarded by the existing test suite. Each
fold gained a `is ThreadItem.MessageItem` type-guard so it never mistakes a boundary for a message; four
of the five share a `private fun List<ThreadItem>.indexOfMessage(id, role): Int` helper, while
`appendMessages` keeps an **inline id-only** guard because message dedup is role-agnostic (see
[#336 § Lessons](../codebase/336.md#lessons-learned)). `lastMessages` (the last-message preview,
messages-only) was deliberately **not** touched.

`observeMessages(conversationId)` and its `threadProjection` simplify — the store already holds
`ThreadItem`s, so there is no longer a `.map { MessageItem(it) }` wrap:

```kotlin
private fun threadProjection(conversationId: String): Flow<List<ThreadItem>> =
    threadByConversation.map { it[conversationId].orEmpty() }.distinctUntilChanged()
```

## Decode — DTO + mapper in `InteractivePayloads.kt`

```kotlin
@Serializable
internal data class SessionTransitionPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("previous_session_id") val previousSessionId: String,
    @SerialName("new_session_id") val newSessionId: String,
    val reason: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("workspace_cwd") val workspaceCwd: String? = null,
)
```

- **Strict-required-non-null except `workspaceCwd`.** Every field is required so a missing/wrong-typed
  field fails the structural decode and the one envelope drops. `workspaceCwd` is the one documented
  nullable field (`null` for `clear` / `idle_evict`, non-null only for `workspace_change`), defaulted
  `= null` — the single-field latitude of [`QueueStatePayloadDto.queued`](queued-backlog.md) — so both a
  present-`null` and a (future) omitted key map to `null` without throwing (`MobileJson` sets no
  `coerceInputValues`).
- **`reason` is a plain `String`, not an enum.** The unrecognized-value decision is a *mapper* concern
  (mirrors [`TurnStatePayloadDto.state`](live-session-events.md)) — an unknown reason drops the one
  envelope rather than conflating "unknown reason" with "malformed envelope".
- **Not a `LiveSessionEvent`.** It produces a `ThreadItem`, so it has **no** `toEvent()` and never lands
  on `liveSessionEvents`.

```kotlin
internal fun SessionTransitionPayloadDto.toBoundary(): ThreadItem.SessionBoundary? =
    reason.toBoundaryReason()?.let { ThreadItem.SessionBoundary(previousSessionId, newSessionId, it,
        Instant.parse(occurredAt), workspaceCwd) }

private fun String.toBoundaryReason(): BoundaryReason? = when (this) {
    "clear" -> BoundaryReason.Clear
    "idle_evict" -> BoundaryReason.IdleEvict
    "workspace_change" -> BoundaryReason.WorkspaceChange
    else -> null   // drop one envelope (mapper concern, not a malformed decode)
}
```

- **`occurredAt`** parses via `Instant.parse` (the existing message-mapper template), which throws
  `IllegalArgumentException` on a malformed RFC3339Nano timestamp — caught at the decode boundary and
  dropped.
- **`workspaceCwd` passes through verbatim.** The `workspaceCwd`-non-null-iff-`WorkspaceChange` invariant
  (asserted in tests) is a **wire guarantee, not enforced in the mapper** — matching the
  `ThreadItem.SessionBoundary` KDoc ("not enforced at construction") and the modal-`default_option_id`
  verbatim-no-enforce precedent. No `require(...)` couples decode to producer correctness.
- **Eviction.** On `idle_evict` the wire carries the evicted id in **both** `previous_session_id` and
  `new_session_id`; the mapper copies both verbatim, no special-casing.

## Fold — in `RemoteConversationRepository`

The new `onInbound` arm rides the **single existing** `pump.inbound` collector (no second subscription),
beside the `stall` / `queue_state` siblings:

```kotlin
TYPE_SESSION_TRANSITION -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        decodeSessionTransition(envelope)?.let { (conversationId, boundary) ->
            appendSessionBoundary(conversationId, boundary)
        }
    }
}
```

- **Capability-gated, fail-closed.** Decoded and folded **only** when the connection negotiated
  `interactive` (the **reused** #385 lazy supplier — no new capability advertised). A non-interactive
  phone never decodes a spurious `session_transition` from a buggy/hostile daemon that ignored the
  server-side fan-out gate — the client mirror of the producer's server-side drop of unbindable
  transitions (#741). Same gate as the structured-stream / `stall` / `resync` arms.
- **`decodeSessionTransition`** mirrors `decodeStall` / `decodeQueueState` exactly: one
  `try { MobileJson.decodeFromJsonElement<SessionTransitionPayloadDto>(payload); dto.toBoundary()?.let {
  dto.conversationId to it } } catch (e: IllegalArgumentException) { null }`
  (`SerializationException ⊂ IllegalArgumentException`; a bad `occurred_at` throws here too). Returns the
  routing key + the mapped row, or `null` to drop the one envelope while the lone collector survives.
- **`appendSessionBoundary`** pure-appends in arrival order, **no dedup**:
  `threadByConversation.update { it + (conversationId to (it[conversationId].orEmpty() + boundary)) }`.
  The wire carries no row id and the repo is connection-scoped (#351), so within a connection arrival
  order is correct — the same posture as `applyAssistantDelta`'s arrival-order concatenation.
- **Routes strictly by the payload's `conversation_id`.** A boundary can only ever surface in
  `observeMessages(thatId)` — cross-routing is structurally impossible. A boundary for a conversation no
  collector observes simply sits unread in the map (exactly as a `message`/`queue_state` for an unknown
  id does today). **No "is this conversation observed?" guard** (there is no registry of observed
  conversations — observation is per-collector and lazy).
- **Folds a thread row only.** Unlike the structured-stream arm it surfaces **nothing** on
  `liveSessionEvents` (a boundary is not a streaming event) and does **not** clear a stall (a session
  transition is not turn forward-progress — the same posture as `queue_state` / `modal`).

## Error handling

| Failure mode | Result |
|---|---|
| Missing/wrong-typed required field | `MobileJson` → `SerializationException` → caught → `null` → drop one envelope, collector survives |
| Unparseable `occurred_at` | `Instant.parse` → `IllegalArgumentException` → same catch → drop |
| Unrecognized `reason` | `toBoundaryReason` → `null` → `toBoundary` → `null` → drop one envelope (distinct from malformed, same drop-without-crash) |
| `interactive` not negotiated | capability gate → never decoded, never folded |
| `conversation_id` names an unobserved conversation | stored under its own id, never surfaced into another thread (fail-closed) |

All drops are **silent** — nothing logs the payload (see below). UI surfacing: none — the existing
`SessionBoundaryDelimiter` renders whatever rows arrive; a dropped envelope simply yields no row.

## Trust boundary — no payload logging

`security-sensitive`, but the repository stays plain orchestration: decode runs behind the
already-authenticated Noise channel. The untrusted→trusted crossing is a single explicit point
(`decodeSessionTransition` + `toBoundary`, both `internal` to `data/network`); downstream holds the typed
`ThreadItem.SessionBoundary` only. The headline threat — a **cross-conversation leak** — is structurally
closed: the fold keys strictly into `threadByConversation[conversationId]` and `observeMessages(X)` reads
only `threadByConversation[X]`, so a boundary tagged `c2` can never surface in `c1`'s thread (no string
manipulation or index arithmetic that could mis-key). **Nothing in the new arm or any drop branch logs
the payload** — `conversation_id` / session ids / `workspace_cwd` are sensitive, and a logged or
mis-routed boundary *is* the leak. The one trap is the decode `catch`, which must be a bare `null` (a
`SerializationException` / `Instant.parse` message echoes payload field values); it copies the sibling
bare-catch verbatim, grep-confirmed clean.

## Fidelity vs the fake — live transitions only

`session_transition` is a **live** emit; the `message_chunk` backfill carries no boundaries. Boundaries
appear only for transitions observed while connected — a deliberate fidelity gap vs the
[fake](conversation-repository.md), which derives boundaries from full in-memory history. Historical
boundaries that occurred before the phone connected have no wire representation and are out of scope. And
because the producer (#741) emits only `clear` / `idle_evict` today (there is no server-side
workspace-change source), no `workspace_change` event arrives until a future server capability lands —
the arm and the invariant exist so the decode is exhaustive and expressible, and are tested regardless.

## Related

- [#336 codebase note](../codebase/336.md) — implementation record + lessons (the five-fold lift nuance).
- Spec: [`docs/specs/architecture/336-session-boundary-fold-remote-thread.md`](../../specs/architecture/336-session-boundary-fold-remote-thread.md).
- [Remote conversation repository](remote-conversation-repository.md) — the host class; the
  `observeMessages` thread read (#313) this extends and the unified `threadByConversation` store.
- [SessionBoundaryDelimiter](session-boundary-delimiter.md) — the render half (#135/#192); consumes the
  `ThreadItem.SessionBoundary` rows this fold produces.
- Component render test: [#473 session-boundary divider](../codebase/473.md) — the e2e-ladder Layer-1b
  test that drives **this** fold (not the fake) through to render, asserting one folded boundary draws a
  delimiter between two cross-session messages. The data-layer behaviour is unit-tested in
  `RemoteConversationRepositoryTest`; #473 adds the missing render rung.
- Sibling interactive folds: [live-session events](live-session-events.md) (#385, the shared decode
  substrate + gate), [stall state](stall-state.md) (#395), [queued backlog](queued-backlog.md) (#460),
  [modal events](modal-events.md) (#437).
- `ThreadItem` / `SessionBoundary` / `BoundaryReason` definitions live with the repository contract
  (`data/repository/ConversationRepository.kt:198-220`, defined #3 / authored #192) — see
  [conversation repository](conversation-repository.md).
- Wire SSOT: pyrycode `docs/protocol-mobile.md` § Interactive events (v2); pyrycode#656/#657/#740/#741/#739.

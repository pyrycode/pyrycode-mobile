# #336 — Fold `session_transition` into the remote thread as `ThreadItem.SessionBoundary`

**Size:** S · **Security-sensitive:** yes (see § Security review) · **Split from:** #313

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:165` — the thread store `messagesByConversation: MutableStateFlow<Map<String, List<Message>>>`. **This is the load-bearing field this ticket changes.**
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:264-440` — `onInbound` demux; the `TYPE_QUEUE_STATE` (386-401), `TYPE_TURN_STATE…` (340-372), and `TYPE_STALL` (373-385) arms are the exact templates for the new `TYPE_SESSION_TRANSITION` arm (capability gate + decode-or-drop).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:626-786` — the **five `Message.id`-keyed folds** you rework to `ThreadItem`: `appendMessages` (626), `applyToolUse` (658), `applyToolResult` (695), `applyAssistantDelta` (738), `finalizeAssistantTurn` (771).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:495-518` — `decodeStall` / `decodeQueueState`: the drop-idiom template for the new `decodeSessionTransition` (one `try`/`catch (IllegalArgumentException)`, returns `Pair<String, …>?`).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:872-888` — `observeMessages` + `threadProjection`; the latter simplifies (no more `.map { MessageItem(it) }` wrap).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1332-1358` — the `TYPE_*` constants block (turn_state … queue_state); add `TYPE_SESSION_TRANSITION` here in the same KDoc style.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:78-154` — DTO + mapper patterns. `StallPayloadDto`/`QueueStatePayloadDto` (strict-required fields, the `toEvent()`/`toQueue()` mappers) and `TurnStatePayloadDto.toEvent()` + `String.toPhase()` (the **unknown-value-returns-null** mapper idiom you copy for `reason`). `Instant.parse(ts)` usage at line 121 is the `occurred_at` template; `Instant` is already imported (line 7).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:198-220` — `ThreadItem`, `ThreadItem.MessageItem`, `ThreadItem.SessionBoundary`, `BoundaryReason`. **All already defined — do not re-declare.** Note the documented (not enforced) `workspaceCwd`-non-null-iff-`WorkspaceChange` invariant.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` — the test harness. Read: `FakeSessionPump` (3935) + `push` (3950); the interactive-fold pattern `RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })`; `queueStateEnvelope` (3743) and `toolUseEnvelope` (3859) as builder templates; `toolCall_interleavesChronologicallyWithMessages` (2660) as the arrival-order assertion template; `collectMessages` / `messageIds` helpers. **This file is the AC #5 "test double of the v2 server message set."**
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:108-120` — how the fake derives boundaries from in-memory history (the fidelity reference; not changed by this ticket).
- `gradle/libs.versions.toml` — confirm: **no new dependency.** `kotlinx-serialization`, `kotlinx-datetime` are already present.

## Context

#313 shipped the `MessageItem`-only remote thread. Session-boundary markers were de-scoped then because the v2 wire carried no session-transition representation. Every upstream gate is now closed:

- `pyrycode#656`/`#657` define and emit the `session_transition` interactive event (capability-gated fan-out).
- `pyrycode#740`/`#741`/`#739` closed the **routing-key gap** the 2026-06-23 architect review found: the payload now carries `conversation_id` (plain `string`, no `omitempty`), the producer resolves and stamps the owning conversation on every emit (and **drops server-side** any transition it can't bind), and the conversation↔session binding survives `/clear` + eviction. `session_transition` now routes exactly like `turn_state` / `assistant_delta` / `tool_use`.

This ticket is **purely the data-layer wire→thread fold**. The render side already ships (`SessionBoundaryDelimiter` #135/#192, above-delimiter de-emphasis #136) and is untouched here.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Thread screen. The boundary renders as a centered caption ("Workspace changed to … — 2 hours ago") above a thin full-width horizontal rule, with the de-emphasized "Claude doesn't remember messages above this line. Install…" line below it and above-rule messages dimmed. **This component already exists and ships** — the developer writes **no UI code** and needs no `Design source` reproduction work; this ticket only produces the `ThreadItem.SessionBoundary` rows the existing component consumes. The screenshot was read to confirm the render contract is satisfied by the existing `SessionBoundary` fields (`reason`, `occurredAt`, `workspaceCwd`).

## Design

### The load-bearing change: unify the thread store to `List<ThreadItem>`

A `SessionBoundary` is a non-`Message` `ThreadItem` that must **interleave in arrival order** with messages. The current store holds `Map<String, List<Message>>` and every fold keys off `Message.id`. There is no clean way to keep messages and boundaries in separate stores and re-interleave them by arrival order (no shared ordering key — message positions are id-fixed, boundaries have no id). The elegant, output-preserving move is to make the store hold both row kinds:

```
// before
private val messagesByConversation = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
// after  (rename: the field now holds thread rows, not messages)
private val threadByConversation = MutableStateFlow<Map<String, List<ThreadItem>>>(emptyMap())
```

Rename `messagesByConversation` → `threadByConversation`. The field is `private`; all references are in this one file and are already being edited for the type change, so the rename is free-riding clarity, not extra scope. Rewrite its KDoc to say "thread rows (`MessageItem` + `SessionBoundary`)".

**Do NOT touch `lastMessages`** (`Map<String, Message>`, line 153) — the last-message preview is messages-only and never holds boundaries.

### Rework the five folds to `ThreadItem` (output-preserving)

Each fold's id/role predicate gains a `MessageItem` type-guard and its writes wrap/unwrap `MessageItem`. The semantics (append, in-place replace, idempotent skip, dedup-by-id) are **unchanged** — this is a mechanical, behaviour-preserving lift guarded by the existing test suite.

The predicate transform, applied in all five:

```
// before:  existing.indexOfFirst { it.id == key && it.role == Role.X }
// after:   existing.indexOfFirst { it is ThreadItem.MessageItem && it.message.id == key && it.message.role == Role.X }
```

Per-fold notes:
- `appendMessages(rows: List<Pair<String, Message>>)` — **signature unchanged** (its callers still deal in `Message`). Internally: match `MessageItem` by `message.id`; replace in place as `ThreadItem.MessageItem(message)` or append `ThreadItem.MessageItem(message)`.
- `applyToolUse` / `applyToolResult` / `applyAssistantDelta` / `finalizeAssistantTurn` — same guard; the in-place `row.copy(...)` becomes `(existing[index] as ThreadItem.MessageItem).message.copy(...)` re-wrapped in `ThreadItem.MessageItem(...)`; new rows append as `ThreadItem.MessageItem(row)`.

Optional DRY (developer's call): a private `List<ThreadItem>.indexOfMessage(id: String, role: Role): Int` helper collapses the repeated guard. Not required.

`threadProjection` simplifies — the stored value is already `List<ThreadItem>`:

```
private fun threadProjection(conversationId: String): Flow<List<ThreadItem>> =
    threadByConversation.map { it[conversationId].orEmpty() }.distinctUntilChanged()
```

### Decode: new DTO + mapper in `InteractivePayloads.kt`

```
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

- All fields strict-required-non-null **except `workspaceCwd`**, which is the one documented nullable field (`null` for `clear`/`idle_evict`, non-null for `workspace_change`). Default it `= null` — the same single-field latitude as `QueueStatePayloadDto.queued` — so both a present-`null` and a (future) omitted key map to `null` without throwing (`MobileJson` sets no `coerceInputValues`). Every other field stays strict so a missing/wrong-typed field fails the structural decode → drop (AC #5).
- `reason` is a **plain `String`**, not an enum — the unrecognized-value decision is a *mapper* concern (mirrors `TurnStatePayloadDto.state`), so an unknown reason drops the one envelope rather than conflating "unknown reason" with "malformed envelope".

Mapper — returns **null** on unknown reason (drop-one-envelope), throws inside `Instant.parse` on a bad timestamp (caught upstream → drop):

```
internal fun SessionTransitionPayloadDto.toBoundary(): ThreadItem.SessionBoundary?
    // null when reason ∉ {clear, idle_evict, workspace_change};
    // else SessionBoundary(previousSessionId, newSessionId, reason, Instant.parse(occurredAt), workspaceCwd)

private fun String.toBoundaryReason(): BoundaryReason?
    // "clear"→Clear, "idle_evict"→IdleEvict, "workspace_change"→WorkspaceChange, else null
```

- `workspaceCwd` passes through **verbatim**. The `workspaceCwd`-non-null-iff-`WorkspaceChange` invariant (AC #4) is a **wire guarantee asserted in tests, not enforced in the mapper** — matching the `ThreadItem.SessionBoundary` KDoc ("not enforced at construction") and the modal-`default_option_id` verbatim-no-enforce precedent. Do **not** add a `require(...)` coupling decode to producer correctness.
- On `idle_evict`, the wire carries the evicted id in both `previous_session_id` and `new_session_id`; the mapper copies both verbatim (no special-casing). (AC #4, eviction case.)
- Add imports: `de.pyryco.mobile.data.repository.ThreadItem`, `de.pyryco.mobile.data.repository.BoundaryReason` (sibling of the existing `QueuedMessage` import). `Instant` is already imported.
- File-comment note: this DTO is **not** a `LiveSessionEvent` — it produces a `ThreadItem`, so it has no `toEvent()` and never lands on `liveSessionEvents`.

### Decode + fold in `RemoteConversationRepository.kt`

New constant in the `TYPE_*` block (same KDoc style as `TYPE_QUEUE_STATE`):

```
/** Capability-gated thread event: a session transition `{conversation_id, previous_session_id,
 *  new_session_id, reason, occurred_at, workspace_cwd}` (#336, pyrycode#656/#740) — folds a
 *  ThreadItem.SessionBoundary into the conversation thread in arrival order. */
const val TYPE_SESSION_TRANSITION = "session_transition"
```

New decode helper (mirrors `decodeQueueState` — returns the routing key + the mapped row, `null` on malformed/unknown-reason):

```
private fun decodeSessionTransition(envelope: Envelope): Pair<String, ThreadItem.SessionBoundary>?
    // try { val dto = MobileJson.decodeFromJsonElement<SessionTransitionPayloadDto>(payload)
    //       dto.toBoundary()?.let { dto.conversationId to it } }
    // catch (e: IllegalArgumentException) { null }     // SerializationException ⊂ IAE; bad occurred_at throws here too
```

New `onInbound` arm (place beside `TYPE_QUEUE_STATE`):

```
TYPE_SESSION_TRANSITION -> {
    // Capability-gated (AC #2): a non-interactive phone never decodes it (fail-closed, defence in
    // depth against a daemon ignoring the server-side fan-out gate). Decode-or-drop (AC #3/#5):
    // a malformed payload or unknown reason yields null → drop one envelope, collector survives.
    // Routes strictly by the payload's conversation_id → structurally cannot cross-route (AC #1).
    // Unlike the structured-stream arm: folds a thread row, surfaces NOTHING on liveSessionEvents,
    // and does NOT clear a stall (a session transition is not turn forward-progress). Drop silently —
    // conversation_id / session ids / workspace_cwd are sensitive; nothing here logs the payload.
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        decodeSessionTransition(envelope)?.let { (conversationId, boundary) ->
            appendSessionBoundary(conversationId, boundary)
        }
    }
}
```

New fold (pure append in arrival order, no dedup):

```
private fun appendSessionBoundary(conversationId: String, boundary: ThreadItem.SessionBoundary)
    // threadByConversation.update { it + (conversationId to (it[conversationId].orEmpty() + boundary)) }
```

### Two design decisions to lock (so the developer doesn't add wrong code)

1. **No "is this conversation observed?" guard.** Routing keys strictly on the payload's `conversation_id`, so a boundary can only ever appear in `observeMessages(thatId)` — cross-routing is structurally impossible (AC #1's hard guarantee). A boundary for a conversation no collector observes simply sits unread in the map, exactly as a `message` or `queue_state` for an unknown id does today. There is no registry of "observed" conversations (observation is per-collector and lazy); do **not** invent one. The producer (#741) already drops unbindable transitions server-side, so a fanned event always carries a populated `conversation_id`; this is the defensive client mirror.

2. **No boundary dedup / idempotency key.** The wire `session_transition` carries no id (and `envelope.eventId` is reserved for the replay cursor, not row identity). The repository is **connection-scoped** (fresh per connection, #351), so within a connection arrival order is correct and boundaries pure-append — the same posture as `applyAssistantDelta`'s arrival-order concatenation. Cross-reconnect replay dedup is a #402-class concern, deferred. Do **not** add a dedup guard.

## State + concurrency model

- **Single store, single writer.** `threadByConversation` is the one source of thread state; all writes go through `MutableStateFlow.update {}` (atomic CAS), so the new `appendSessionBoundary` composes correctly with the five existing folds. Same memory-visibility posture as every sibling projection.
- **Single inbound collector.** The new arm runs inside the existing `init { scope.launch { pump.inbound.collect { onInbound(it) } } }` coroutine (line 259) — no new coroutine, no new scope. `appendSessionBoundary` is a synchronous, throw-free side-write (the decode's `try`/`catch` already absorbed every throw), so it can never tear down the lone collector.
- **Cold fan-out unchanged.** `observeMessages` stays cold; `threadProjection` fans out from the hot `StateFlow` via `distinctUntilChanged` — a boundary folded into conversation `c1` re-emits only `c1`'s thread, never another conversation's (`distinctUntilChanged` on the per-conversation slice). No dispatcher change; all `data/`-portable (no `android.*`).
- **No `liveSessionEvents` / stall / queue interaction.** The arm writes only `threadByConversation`. It does not `tryEmit` to `liveSessionEvents` (a boundary is a thread row, not a streaming event — the thinking-indicator/tool-timeline consumers must not see it) and does not mutate `stalledConversations` (a `/clear` or eviction is orthogonal to stall state — same posture as `queue_state`/`modal`).

## Error handling

| Failure mode | Layer | Result |
|---|---|---|
| Missing/wrong-typed required field | `MobileJson.decodeFromJsonElement` → `SerializationException` (⊂ `IllegalArgumentException`) | caught in `decodeSessionTransition` → `null` → drop one envelope, collector survives (AC #5) |
| Unparseable `occurred_at` | `Instant.parse` → `IllegalArgumentException` | same `catch` → `null` → drop (AC #5) |
| Unrecognized `reason` | `toBoundaryReason` → `null` → `toBoundary` → `null` | `decodeSessionTransition` → `null` → drop one envelope (AC #3), distinct from "malformed" but same drop-without-crash |
| `interactive` not negotiated | capability gate | never decoded, never folded (AC #2) |
| `conversation_id` names an unobserved conversation | routing key | stored under its own id, never surfaced into another thread (AC #1) — fail-closed |

All drops are **silent — nothing logs the payload** (security: a logged `conversation_id`/session-id/`workspace_cwd`, or a mis-routed boundary, is a cross-conversation leak). UI surfacing: none — the existing `SessionBoundaryDelimiter` renders whatever rows arrive; a dropped envelope simply yields no row.

## Testing strategy

Unit only — `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"` (no device; the `FakeSessionPump` harness is the v2-server double). No instrumented tests.

**First, run the full existing suite green after the fold refactor and before adding new tests** — it is the output-preserving guard for the five-fold lift (it asserts message ordering + `message_id` dedup via `observeMessages`, which must be unchanged).

Add a test helper `sessionTransitionEnvelope(conversationId, previousSessionId, newSessionId, reason, occurredAt, workspaceCwd = null, id = 1L)` following the raw-JSON `queueStateEnvelope` pattern (emit `"workspace_cwd":null` when null). Reuse `collectMessages` / `messageIds`; add a small `boundariesOf(items)` or index-into-`items` assertion as needed.

New scenarios (each a `@Test`, inputs → expected; write bodies in the file's idiom):

- **AC #1 — fold + arrival order.** Seed `message(m1)`, `session_transition(clear)`, `message(m2)` (interactive) → `observeMessages("c1").last()` is `[MessageItem(m1), SessionBoundary(clear), MessageItem(m2)]` in that order; the boundary sits between the runs, message ids/order undisturbed.
- **AC #1 — no cross-routing.** While collecting `"c1"`, seed a `session_transition` carrying `conversation_id = "c2"` → `"c1"`'s thread never contains the boundary (and collecting `"c2"` shows it only there).
- **AC #2 — gate closed.** `negotiatedCapabilities = { emptySet() }` (and a second test `{ setOf("something_else") }`) → a well-formed `session_transition` folds nothing.
- **AC #2 — gate open.** `{ setOf("interactive") }` → the same envelope folds its boundary.
- **AC #3 — reason mapping.** `clear`→`Clear`, `idle_evict`→`IdleEvict`, `workspace_change`→`WorkspaceChange` fold with the correct `reason` (one test or three).
- **AC #3 — unknown reason dropped, collector survives.** `reason = "bogus"` → no boundary; a later valid `session_transition` (or `message`) still folds → proves the lone collector survived.
- **AC #4 — invariant + eviction.** `clear`/`idle_evict` → folded `boundary.workspaceCwd == null`; `workspace_change` with `workspace_cwd = "/x"` → `== "/x"`; an `idle_evict` with `previous == new == "s1"` → both ids carried verbatim.
- **AC #5 — malformed dropped, collector survives.** A `session_transition` missing a required field (e.g. no `new_session_id`) and one with an unparseable `occurred_at` → no boundary; a subsequent valid envelope still surfaces → collector alive.
- **AC #5 — round-trip against the v2 message set.** A realistic sequence — `message_chunk` backfill, then live `message`s interleaved with `session_transition`s (mixed reasons, mixed conversations) — asserts the full ordered `List<ThreadItem>` with boundaries folded into the matching conversation only.

## Open questions

- **`occurred_at` ordering vs arrival order.** The fold uses **arrival order** (append at current end), not an `occurred_at` sort — consistent with every existing fold (thread order is wire/arrival order, never a timestamp sort; `occurredAt` is carried for display only). Within a single connection the wire delivers in causal order, so arrival order is correct. No action; flagged so code-review doesn't expect a sort.
- **Historical boundaries pre-connection.** Out of scope — there is no wire representation; the `message_chunk` backfill carries no boundaries (a deliberate fidelity gap vs the fake, which derives boundaries from full in-memory history). Boundaries appear only for transitions observed while connected. No follow-up ticket owed unless a future server capability adds historical replay.
- **`workspace_change` never arrives today.** The producer emits only `clear`/`idle_evict` (#741); `workspace_change` stays a valid decode arm so the mapping is exhaustive and the invariant expressible, but no such event lands until a future server source exists. Decode is built and tested regardless.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No finding. The untrusted→trusted crossing is a single explicit point: `decodeSessionTransition` (the `MobileJson.decodeFromJsonElement` parse) + `SessionTransitionPayloadDto.toBoundary` (the map), both `internal` to `data/network`, mirroring `decodeStall`/`decodeQueueState`. Downstream (`appendSessionBoundary`, `observeMessages`) holds the typed `ThreadItem.SessionBoundary` only — never raw JSON; the type system signals trust. The headline threat the ticket names — **cross-conversation leak** — is structurally closed: the fold keys strictly on the payload's `conversation_id` into `threadByConversation[conversationId]`, and `observeMessages(X)` reads only `threadByConversation[X]`, so a boundary tagged `c2` can never surface in `c1`'s thread (no string manipulation or index arithmetic that could mis-key). A hostile *authenticated* daemon stamping a boundary onto a conversation the phone observes is within the existing trust model and strictly weaker than the spurious `message` it can already inject — no new trust surface is opened.
- **[Tokens/secrets/credentials]** Not applicable — this slice generates, stores, and compares no tokens or secrets. `conversation_id` / `previous_session_id` / `new_session_id` are opaque routing identifiers (not auth credentials), the same posture as every sibling interactive event that carries `conversation_id`. Their only sensitivity is leakage-via-logs, covered under [Logs].
- **[File / storage]** Not applicable — zero filesystem/storage I/O. The thread store is in-memory `StateFlow`, connection-scoped, lost on process death and re-derived from the live stream. No path concatenation (no traversal/TOCTOU), no at-rest persistence (no encryption-at-rest or `allowBackup` concern).
- **[Android attack surface]** Not applicable — no Activity/Service/Receiver/Provider, Intent/deep-link, PendingIntent, or WebView touched. `data/`-portable, no `android.*`.
- **[Cryptographic primitives]** Not applicable — no RNG, hashing, key handling, or comparison-of-secrets. The Noise_IK transport that authenticates the daemon is upstream (`NoiseSessionPump`), unchanged. `reason` is matched with a `when` on a plain (non-secret) string.
- **[Network & I/O]** No new network call — the arm consumes the existing single `pump.inbound` flow; frame-size caps, timeouts, TLS, and pinning are owned by `OkHttpRelayTransport`/`NoiseSessionPump` (unchanged). **OUT OF SCOPE (pre-existing):** `appendSessionBoundary` pure-appends with no per-conversation cap, so a flooding daemon could grow `threadByConversation[conversationId]` unboundedly — but this is identical to the existing `appendMessages`/`applyToolUse` behaviour (the thread store is intentionally unbounded within a connection, reset on reconnect), strictly weaker than `message` flooding, and not introduced here. A thread-store memory cap, if wanted, is a cross-cutting ticket spanning *all* fold paths, not a boundary-only patch.
- **[Logs / telemetry]** SHOULD FIX (already encoded in the spec; code-review verifies). Every drop path is silent and nothing logs the payload (`conversation_id` / session ids / `workspace_cwd` are sensitive; a logged or mis-routed boundary is the cross-conversation leak). The one trap: the `catch (e: IllegalArgumentException)` in `decodeSessionTransition` MUST be a bare `null` — no `Log`/`Timber`/`println` of `e` or `e.message`, because a `SerializationException` message and an `Instant.parse` failure both echo the offending payload field values. The developer copies the sibling `decodeStall`/`decodeQueueState` bare-catch verbatim; code-review must confirm no logging was added. No telemetry/metrics introduced.
- **[Concurrency]** No finding. The new arm runs inside the existing single inbound-collector coroutine (`init { scope.launch { pump.inbound.collect … } }`), owned and cancelled by the connection lifecycle (#279/#302) — no new coroutine or scope. `appendSessionBoundary` writes via atomic `MutableStateFlow.update {}` (no check-then-set TOCTOU), and the boundary fold and the five message folds are serialized by the single-consumer collector, so they never race. `observeMessages` stays cold; per-conversation `distinctUntilChanged` prevents any cross-screen/cross-conversation row leak.
- **[Threat model alignment]** No finding. The mobile-wire threat — a buggy/hostile daemon emitting spurious or mis-targeted `session_transition` — is the fail-closed client mirror of the producer-side drop (#741): (a) capability gate (a non-interactive phone never decodes), (b) strict structural decode (malformed → drop), (c) routing strictly by payload `conversation_id` (no cross-routing), (d) no payload logging (no leak). Render-surface mobile threats (screenshot leakage, FLAG_SECURE, overlay/accessibility) concern the unchanged `SessionBoundaryDelimiter` UI, not this data-layer slice — out of scope, owned by the render/snapshot tickets.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-23

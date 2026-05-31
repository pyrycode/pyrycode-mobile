# Spec: RemoteConversationRepository.observeMessages — thread + backfill + boundaries (v2) (#313)

**Ticket:** #313
**Size:** S
**Status:** architecture complete

---

## Files to read first

| Path | Lines | What to extract |
|------|-------|-----------------|
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | full (88) | Current `observeMessages` delegates to the **live-only** `messagesFlow`; the cold-`flow{}` + accumulator + `emit(map(...))` pattern; `start()`/`handleInbound` show the inbound-demux idiom. **Leave `messagesFlow` and `observeLastMessage` untouched** — this slice adds a parallel `threadFlow`. |
| `app/src/main/java/de/pyryco/mobile/data/network/mapper/MessageMapper.kt` | full (37) | `MessagePayload.toDomain()` (role→`Author` string mapping) + `mapToMessageList` (live-only). The header comment defers boundary-folding to this slice. Add the new fold here; do not change the existing signature. |
| `app/src/main/java/de/pyryco/mobile/data/network/wire/MessagePayloads.kt` | full (23) | `MessagePayload` shape + `@SerialName` snake_case convention + `: WirePayload`. New backfill/thread wire types go in **this** file (message domain). |
| `app/src/main/java/de/pyryco/mobile/data/network/wire/ConversationPayloads.kt` | full | `ConversationSummaryPayload` (a `WirePayload`) **with a nested `@Serializable data class`** (`ConversationSummary`). Mirror this exact pattern for `BackfillResponse` + nested `ThreadElement`. |
| `app/src/main/java/de/pyryco/mobile/data/network/wire/Envelope.kt` | full (19) | `Envelope(id, inReplyTo, payload)`. `inReplyTo` is the correlation key — null on live frames, set on responses. |
| `app/src/main/java/de/pyryco/mobile/data/network/wire/WirePayload.kt` | full (14) | Sealed `WirePayload`; polymorphic by `@SerialName`. |
| `app/src/main/java/de/pyryco/mobile/data/network/pump/NoiseSessionPump.kt` | full (19) | `val inbound: SharedFlow<Envelope>` + `suspend fun send(envelope)`. The transport surface this flow consumes (#309). |
| `app/src/main/java/de/pyryco/mobile/data/network/codec/MobileWireCodec.kt` | 23–35 | `wireModule` polymorphic registration. Register the two new `WirePayload` subtypes here. Note `ignoreUnknownKeys = true` — decode tolerates extra/renamed server fields. |
| `app/src/main/java/de/pyryco/mobile/data/model/ThreadItem.kt` | full (29) | Domain target: `MessageItem(message)` + `SessionBoundary(kind, workspaceCwd)`, `enum Kind { Clear, IdleEvict, WorkspaceChange }`, and the **`workspaceCwd` non-null-iff-`WorkspaceChange`** invariant the mapper must enforce. |
| `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` | full (16) | `Message(id, author, text, timestampMillis, isStreaming)`. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` | full | `FakePump` (records outbound in `sent`; `inbound` is a `MutableSharedFlow` the test pushes into) + `TestScope(UnconfinedTestDispatcher)` + **`runCurrent()` not `advanceUntilIdle()`** harness. Read the request id back from `pump.sent`. |
| `app/src/test/java/de/pyryco/mobile/data/network/mapper/MessageMapperTest.kt` | full | Existing pure-mapper test shape to extend with the fold scenarios. |
| `app/src/test/java/de/pyryco/mobile/data/network/codec/MobileWireCodecTest.kt` | full | Existing round-trip test shape to extend for the new payloads. |
| `docs/specs/architecture/317-message-payload-domain-mapping.md` | "Open questions" | The deferral this slice fulfils: session-transition wire shape + `backfill_since` types + the boundary fold all land **here**. |
| `docs/specs/architecture/329-remote-conversation-repository-observe-last-message.md` | full | The live `messagesFlow` this slice must **not** regress. |

---

## Context

Phase 4 chain. Third behaviour slice of `RemoteConversationRepository` (split from #278); follows #312 (class + `observeConversations`), #316/#317/#318 (mappers), #329 (live `observeLastMessage` + the shared live `messagesFlow`).

Today `observeMessages(conversationId)` delegates to `messagesFlow`, which is **live-only**: it accumulates `MessagePayload`s off `pump.inbound`, maps each to a `MessageItem`, and re-emits. It has **no history backfill** and produces **no session boundaries** (`mapToMessageList` is a flat `payloads.map { MessageItem }`).

This slice makes the thread complete and correct:

1. On collection, fetch the conversation's full ordered history via a `backfill_since` request/response over the pump (correlated by `Envelope.inReplyTo`), seeding the thread so it is complete on first emission.
2. Fold the ordered history — messages **interleaved with session transitions** — into `List<ThreadItem>`, inserting a `SessionBoundary` at each transition.
3. Continue merging the live `message` stream, deduping against history and updating streaming messages in place.

`observeLastMessage` and the existing live `messagesFlow` are **out of scope and unchanged** (see Design § "Why a separate flow").

---

## Design

### Module / package placement

| Concern | File | Change |
|---------|------|--------|
| Backfill + thread wire types | `data/network/wire/MessagePayloads.kt` | **add** 3 types |
| Boundary-folding mapper | `data/network/mapper/MessageMapper.kt` | **add** 1 top-level fn (+ private kind helper) |
| Backfill + merge logic | `data/repository/RemoteConversationRepository.kt` | **add** `threadFlow`; point `observeMessages` at it |
| Codec registration | `data/network/codec/MobileWireCodec.kt` | **add** 2 `subclass(...)` lines |

No new files; no signature changes to existing public types ⇒ no consumer cascade. `observeMessages`'s return type (`Flow<List<ThreadItem>>`) is unchanged, so the thread ViewModel/screen are untouched.

### New wire types (`MessagePayloads.kt`)

Two `WirePayload` envelopes + one nested ordered-history element. Field names mirror this repo's snake_case `@SerialName` convention; the exact server discriminators/field names are provisional pending the SSOT (see Open Questions) — `ignoreUnknownKeys = true` makes decode forgiving for fields we don't read.

```kotlin
@Serializable @SerialName("backfill_since")
data class BackfillSinceRequest(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("since_timestamp_millis") val sinceTimestampMillis: Long? = null, // null = full history
) : WirePayload

@Serializable @SerialName("backfill_response")
data class BackfillResponse(
    @SerialName("conversation_id") val conversationId: String,
    val items: List<ThreadElement>,   // server-ordered, chronological; do NOT reorder client-side
) : WirePayload
```

`ThreadElement` is the **ordered-history union** — exactly one of `{message, transitionKind}` is set per element. It is a plain nested `@Serializable data class` (not a `WirePayload`, not codec-registered):

```kotlin
@Serializable
data class ThreadElement(
    val message: MessagePayload? = null,                              // set ⇔ this element is a message
    @SerialName("transition_kind") val transitionKind: String? = null, // "clear" | "idle_evict" | "workspace_change"
    @SerialName("workspace_cwd") val workspaceCwd: String? = null,      // set ⇔ transitionKind == "workspace_change"
)
```

Discrimination is by field **presence** (an explicit tag pattern, not null-punning per [[v2-app-payload-shapes-ssot]]): `message != null` ⇒ message element; else `transitionKind != null` ⇒ boundary element; else malformed.

**Decision — dedicated transition representation, not session-id inference.** #317's open question asked whether boundaries are a dedicated wire shape or inferred from `session_id` changes. The ACs require the boundary's `kind` (Clear/IdleEvict/WorkspaceChange) **and** new `workspaceCwd`, neither derivable from a `session_id` delta. So transitions are explicit. They are carried **only inside the backfill response's ordered list** for this slice; a live `/clear` arriving mid-observation is deferred (see Open Questions) — keeps the live collector message-only.

### Mapper fold (`MessageMapper.kt`)

Add one pure top-level function; do not touch `toDomain` or `mapToMessageList`.

```kotlin
/** Folds the ordered backfill history (messages interleaved with transitions) into ThreadItems. */
fun mapThreadElements(elements: List<ThreadElement>): List<ThreadItem>
```

Behaviour (per element, preserving order):
- `message != null` → `ThreadItem.MessageItem(message.toDomain())`.
- `transitionKind != null` → `ThreadItem.SessionBoundary(kind, workspaceCwd)` where the string maps `"clear"→Clear`, `"idle_evict"→IdleEvict`, `"workspace_change"→WorkspaceChange` (mirror the `role→Author` `when` in `toDomain`).
- **`workspaceCwd` invariant enforcement:** for `WorkspaceChange`, the element's `workspaceCwd` must be non-null and is carried through; for `Clear`/`IdleEvict`, force `workspaceCwd = null` (ignore any stray value).
- **Malformed → drop the element** (no fabrication, no exception): both fields null; unknown `transitionKind`; `WorkspaceChange` with null `workspaceCwd`. Defensive, since the wire is server-authored.

Keep `mapThreadElements` private-helper-free if it fits; a small private `String.toBoundaryKind(): ThreadItem.SessionBoundary.Kind?` is acceptable (returns null ⇒ caller drops the element).

The invariant is asserted by the mapper tests (see Testing).

### Repository — `threadFlow` (`RemoteConversationRepository.kt`)

```kotlin
override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
    threadFlow(conversationId)

private fun threadFlow(conversationId: String): Flow<List<ThreadItem>>  // cold; backfill-seeded + live-merged
```

Contract (cold `flow {}`):

1. Generate a correlation id (`java.util.UUID.randomUUID().toString()` — JVM-only; flag as a Compose-Multiplatform walk-back point, acceptable now per CLAUDE.md). Build `Envelope(id = requestId, payload = BackfillSinceRequest(conversationId, sinceTimestampMillis = null))`.
2. **Subscribe before sending** to avoid losing frames between `send` and `collect` (the pump's `inbound` is `replay = 0`). Use `pump.inbound.onStart { pump.send(request) }.collect { ... }` — `onStart` runs after the SharedFlow subscription is registered, so the request goes out with the subscription already live.
3. Maintain a small in-collector state machine (a `MutableList<ThreadElement> history`, a `MutableList<MessagePayload> pendingLive`, a `Boolean seeded`):
   - **`BackfillResponse`** with `inReplyTo == requestId` and matching `conversationId`: `history = response.items`; drain `pendingLive` into `history` via upsert; `seeded = true`; `emit(mapThreadElements(history))`.
   - **`MessagePayload`** for this `conversationId`: if not `seeded` → buffer into `pendingLive`; else upsert into `history` and `emit(...)`.
   - any other payload (or message for a different conversation) → ignore (**no emission** — satisfies AC #3 isolation).
4. **Upsert-by-id rule** (the single rule that covers dedup-against-backfill *and* streaming growth, AC #2 + #4): given a `MessagePayload`, find the `ThreadElement` whose `message?.id == payload.id`; if present, **replace in place** (preserves position; updates `content`/`isStreaming`); else **append** `ThreadElement(message = payload)`. Then re-emit the full mapped list.
5. **Never reorder.** Trust wire/arrival order (the encrypted stream cannot skip a frame). No timestamp sort, no gap-filling.

First emission = the backfill-seeded list ⇒ thread is complete on first paint (AC #1/#2).

### Codec (`MobileWireCodec.kt`)

Add to `wireModule`'s `polymorphic(WirePayload::class)` block:
```kotlin
subclass(BackfillSinceRequest::class)
subclass(BackfillResponse::class)
```
`ThreadElement` is nested inside `BackfillResponse` and needs no registration (non-polymorphic).

### Why a separate flow (not extending `messagesFlow`)

`observeLastMessage` shares the live-only `messagesFlow`. Folding backfill into that shared helper would (a) regress #329's chosen live-only `observeLastMessage` behaviour and (b) fire a full `backfill_since` request **per channel-list row** (every preview observes its last message) — request amplification for data the row only needs the tail of. So `threadFlow` is a parallel cold flow used only by `observeMessages`. The minor live-collection overlap between the two flows is acceptable; unifying them (a parameterised seed) is noted as a future cleanup, not done here ([[Simplicity First]]).

---

## State + concurrency model

- **Cold flow, per-collector state.** Each `observeMessages` collector runs its own `flow {}`, sends its own `backfill_since`, and owns its own `history`/`pendingLive`/`seeded`. No shared mutable state; no `StateFlow` added.
- **Backed by the hot pump.** `pump.inbound` is the shared hot `SharedFlow<Envelope>` (#309). `threadFlow` is one subscriber; the existing `start()` collector (driving `conversationsState`) is independent and unchanged.
- **Dispatcher.** No explicit switch. The fold is pure and cheap; emissions run on the collector's context (the thread screen collects via `collectAsStateWithLifecycle` on Main). If large-history folds ever show jank, `.flowOn(Default)` is the lever — deferred (unobserved, per [[Evidence-Based Fix Selection]]).
- **Cancellation / lifecycle.** When the thread screen leaves composition the collector cancels, unsubscribing from `pump.inbound`; the in-flight `backfill_since` is harmless (its response is simply never collected). No `viewModelScope` job is added by this slice; no leak. `data/` stays portable — no `android.*` (UUID is `java.util`, flagged above).

---

## Error handling

| Failure | Layer | Surfaced as |
|---------|-------|-------------|
| Malformed `ThreadElement` (both fields null / unknown `transitionKind` / `WorkspaceChange` w/ null cwd) | mapper | Element **dropped**; rest of the list maps normally. No throw. |
| Unknown `role` on a message | mapper (existing `toDomain`) | Falls to `Author.System` (unchanged). |
| Extra / renamed server fields | codec | Tolerated (`ignoreUnknownKeys = true`). Fields we read must still match (see Open Questions). |
| Backfill response never arrives (transport drop mid-request) | repository | Flow emits nothing until seeded; `pendingLive` stays buffered. **Deferred** — transport reliability/reconnect is owned by #306/#307/#309. `onStart` fires once per collection, so a reconnect does not auto-retry the backfill; see Open Questions. Do not build a retry/timeout here (unobserved failure mode, [[Evidence-Based Fix Selection]]). |
| Transport / Noise errors | pump (#306/#307/#309) | Out of scope; `threadFlow` only collects `inbound`. |

The thread screen renders whatever `List<ThreadItem>` it receives; this slice surfaces no new UI error states.

---

## Testing strategy

Unit only (`./gradlew test`) — pure data layer, no instrumented tests. Extend the three existing test files; **bullet scenarios below, not pre-written bodies** — developer writes them in the project idiom. Repository tests use the existing `FakePump` + `TestScope(UnconfinedTestDispatcher)` and **`runCurrent()`** (not `advanceUntilIdle()` — no timers; see [[remote-repo-test-runcurrent-not-advanceuntilidle]]).

**`MessageMapperTest` (fold):**
- empty list → empty.
- messages only → all `MessageItem`, order preserved.
- each transition kind → correct `SessionBoundary.Kind`; `Clear`/`IdleEvict` yield `workspaceCwd == null`; `WorkspaceChange` carries the cwd through (invariant).
- interleaved messages + transitions → exact ordered `ThreadItem` list.
- malformed elements (both null / unknown kind / `WorkspaceChange` null cwd) → dropped, neighbours intact.

**`RemoteConversationRepositoryTest` (threadFlow / `observeMessages`):**
- on collection, a `BackfillSinceRequest` for the conversation is sent — assert via `pump.sent`; capture `requestId = pump.sent.last().id`.
- backfill response (`inReplyTo = requestId`) with a mixed ordered `items` list → first emission equals `mapThreadElements(items)` (complete thread, boundaries present).
- live `message` after seed → list re-emits with the message appended.
- live `message` **before** the backfill response → buffered, then merged (deduped) after seed; final order correct.
- duplicate id (live message whose id is already in backfill) → no duplicate row (upsert).
- streaming: same id emitted twice with growing `content` (and `isStreaming` true→false) → row updated in place, re-emits each time (AC #4).
- `message` for a **different** conversation → no re-emission (AC #3 isolation).
- cold: a second collector triggers a **second** `backfill_since` send (own state).
- round-trip (AC #5): seed history + a sequence of live envelopes → exact expected ordered `ThreadItem` list.

**`MobileWireCodecTest`:**
- encode→decode round-trip of `Envelope(payload = BackfillSinceRequest(...))`.
- encode→decode round-trip of `Envelope(inReplyTo, payload = BackfillResponse(items = [message element, each transition kind]))` → structurally equal; `type` discriminator present; `inReplyTo` preserved.

---

## Open questions

- **Wire field names / discriminators are provisional.** The SSOT is the server Go structs (pyrycode #272 messaging — [[v2-app-payload-shapes-ssot]]), not reachable from this worktree (no QMD collection, no reference client present). `backfill_since` / `backfill_response` / `transition_kind` / `since_timestamp_millis` follow this repo's conventions; reconcile exact names against #272 when the live relay is wired. Decode is forgiving (`ignoreUnknownKeys`), so a mismatch on an *unread* field is harmless; a mismatch on a *read* field (e.g. `items`, `transition_kind`) is not — verify those.
- **Live session transitions** (a `/clear` while the thread is open) are not handled this slice — boundaries come only from the backfill history. If/when needed, promote `transition_kind`/`workspace_cwd` to a standalone `SessionTransition : WirePayload`, register it, and have the live collector upsert it into `history`. Clean additive follow-up.
- **Multi-page backfill.** This slice assumes one `backfill_since` round-trip returns the complete ordered history (all past sessions). If the server paginates the response, a follow-up loops `backfill_since` with an advancing `since_timestamp_millis` until exhausted. Deferred.
- **Dropped-backfill retry.** A backfill response lost to a mid-flight transport drop strands the thread (no re-send, since `onStart` fires once). Resolving this likely needs the pump/reconnect layer (#307) to expose a reconnect signal that re-triggers backfill. Deferred — out of this slice's scope.

# 623 — Request and decode paged conversation history

The data layer learns to ask for one page of a conversation's on-disk history and hand back
what came in. Nothing renders here; folding a page into the timeline is #645 and paging as
the operator scrolls is #646.

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` →
  `ConversationRepository`, `ThreadItem`, `QueuedMessage` — the contract the new verb joins, and
  the co-located-domain-type precedent the page/entry pair follows.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` →
  `sendAndAwaitReply` (the correlated request↔reply primitive), `mapError` (already generic over
  unknown codes), `onInbound`'s success-reply arm (where a new reply type must be registered or its
  deferred hangs), `recentWorkspaces` / `setSessionSettings` (the two shapes this one copies),
  `rename` (encode → await → typed-decode).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` →
  `live` — the one-shot delegation shape, and the `IllegalStateException` it throws with no
  connection.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` →
  `ConversationRecord`, `buildThreadItems`, `unknown` — the in-memory log the fake pages over and
  the unknown-id exception type the contract pins.
- `app/src/main/java/de/pyryco/mobile/data/network/RecentWorkspacesPayloads.kt`,
  `SessionSettingsPayloads.kt` → `RecentWorkspacesListPayloadDto`, `SetSessionSettingsPayloadDto` —
  the decode-only / encode-only DTO discipline and the KDoc-cites-server-SSOT convention.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` → `MessagePayloadDto`,
  `toMessage` — the `Instant.parse` mapper shape and its single post-decode failure site.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` — precedent for a
  `data/network/` file importing domain types from `data.repository` (`QueuedMessage`, `ThreadItem`).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` → `MobileJson` —
  `encodeDefaults = true`, `explicitNulls = false`, `ignoreUnknownKeys = true`.
- `docs/knowledge/features/remote-conversation-repository.md` § Testing — **drive the
  push→demux→project cascade with `runCurrent()`, never `advanceUntilIdle()`**; the buffered channel
  item is not delivered to the background collector by `advanceUntilIdle` and projection assertions
  come back empty. Also § The `SessionPump` consumed contract — `send` returns `false` on a
  not-`Open` session and the repository never retries.
- `docs/knowledge/features/conversation-repository.md` § Conventions, § Shape — the
  default-implementation cascade-avoidance rule the new method uses.
- `../pyrycode/docs/protocol-mobile.md` § *Conversation history (v2)* — authoritative for the
  frame contract; cited, not restated.
- Daemon fixtures `internal/protocol/testdata/{request_history,history_page,history_page_at_start,
  history_page_at_start_entries}.json` (daemon `main` at `43a52426`) — the three page shapes, copied
  as JSON literals into this repo's tests rather than read at runtime.

## Design source

N/A — data layer only. Nothing in this ticket renders, so there is no visual-fidelity check.

## Context

A client that opens an existing conversation sees only what arrived after it connected. The daemon
publishes `request_history` / `history_page` (pyrycode#2113) and answers them (pyrycode#2116); this
slice is the phone's half of that exchange. It rides the repository's existing correlated
request↔reply seam and adds no new dispatch mechanism.

Host isolation needs no parameter: since #636 `ThreadDestinationFactory.repository` builds one
`StableConversationRepository` per host over that host's coordinator, and each live connection owns
its own pump and its own pending-request table. A reply cannot reach another host's thread because
it never enters another host's repository.

No ADR is warranted — this is one verb on an established seam, not a new architectural choice.

## Design

### ① Domain types — `ConversationRepository.kt`, beside `ThreadItem`

```kotlin
data class HistoryPage(entries: List<HistoryEntry>, cursor: String, atStart: Boolean)
data class HistoryEntry(id: Long, type: String, payload: JsonElement, timestamp: Instant)
```

- `entries` preserves the wire's **newest-first** order verbatim; the client never re-sorts.
- `cursor` is **opaque** — stored and handed back unexamined, never parsed. Empty whenever
  `atStart` is true, so the two are never both meaningful.
- `atStart` is the **only** termination signal. A short page says nothing about the end of the log.
- `HistoryEntry.id` is the durable per-conversation log id and is **never** joined to an
  `Envelope.eventId` — different sequences that both look like small integers. Pinned in KDoc and
  covered by a decode test that feeds an entry carrying both keys.
- `payload` is `JsonElement`, not `String`: #645 re-reduces a page through the same decode arms the
  live lane runs, all of which take `JsonElement`. A `String` would force a second parse and add a
  second failure surface for bytes that were already parsed. `JsonElement` is a kotlinx-serialization
  multiplatform type, so `data/` stays portable.
- `type` and `payload` are **untrusted replayed content** (protocol threat 1) on the same terms as
  the live lane. Neither is logged; neither is parsed here.

### ② Interface method — default-throwing, zero call sites to update

```kotlin
suspend fun requestHistory(conversationId: String, cursor: String = "", limit: Int = 0): HistoryPage
```

Default `error(...)`, the `delete` / `requestScreenSnapshot` / `dropQueuedMessage`
cascade-avoidance shape — inline test doubles need no override. `cursor = ""` means "start at the
newest"; `limit = 0` asks the daemon to choose and **never** means zero entries. KDoc pins the
failure contract: `IllegalArgumentException` for an unknown conversation (`conversation.not_found`),
`RelayErrorException` carrying `code`/`retryable` for the four `history.*` codes
(`history.unavailable` the only retryable member), `IllegalStateException` when not connected.

### ③ Wire DTOs — new `data/network/HistoryPayloads.kt`

`RequestHistoryPayloadDto(conversation_id, cursor, limit)` — encode-only; all three keys always
present (the wire has no `omitempty` here), so no field carries a default.
`HistoryPagePayloadDto(entries, cursor, at_start)` and `HistoryEntryDto(id, type, payload, ts)` —
decode-only; **every field required, no defaults**, so a missing key is a malformed page rather
than a silently-defaulted one. `at_start` in particular must never default to `false`: a defaulted
absence would read as "keep walking" on a page that said nothing.
`HistoryPagePayloadDto.toHistoryPage()` maps to the domain pair and parses `ts` through
`Instant.parse` — the single post-decode failure site, exactly as `MessagePayloadDto.toMessage` has.

### ④ `RemoteConversationRepository`

`requestHistory` = encode → `sendAndAwaitReply` → typed-decode → map, the `rename` shape minus the
state fold (a page mutates no projection). Adds `TYPE_REQUEST_HISTORY` / `TYPE_HISTORY_PAGE`
constants and registers `TYPE_HISTORY_PAGE` in `onInbound`'s existing success-reply arm — the one
edit to that dispatch. **Deliberately no `.catch {}`** (unlike `recentWorkspaces`, which fails closed
to empty for a picker): every failure must reach the caller so #646 can distinguish a retryable
`history.unavailable` from a permanent reject.

### ⑤ `StableConversationRepository`

One-line delegation to `live`, arguments forwarded verbatim; no live connection throws
`IllegalStateException` — the same type the remote throws on a not-`Open` pump.

### ⑥ `FakeConversationRepository` — an honest walk, not a stub

The fake's log is its seeded messages, oldest-first, entry id = position + 1. It mints its own
cursor (the id of the next older entry to serve) and reproduces the daemon's boundary rule exactly:

- `pageSize` = `limit` when positive, else the fake's own page size.
- `remaining` = entries at or older than the cursor; `take = min(pageSize, remaining)`.
- `atStart = remaining < pageSize` — the fill **ran out**, not "the page happened to be short". A
  page that fills exactly at the first entry therefore reports `atStart` false with a usable cursor,
  and the call after it returns no entries with `atStart` true.
- outgoing `cursor` = `""` when `atStart`, else the next position.

That yields all three daemon page shapes from real data. Each entry carries `type = "message"` and a
`MessagePayloadDto`-shaped payload object built for the message; a `Role.Tool` message carries the
wire's documented future-additive `"tool"` role, which a decoder that does not know it treats as an
unrecognised entry — the AC #4 behaviour, exercised rather than asserted in prose. Unknown
conversation id throws the fake's existing `unknown(id)` `IllegalArgumentException`, matching what
`mapError` produces for `conversation.not_found`.

The fake does **not** model the daemon's negative-`limit` reject: a reject is a wire behaviour with
no in-memory analogue, and inventing a second exception type for it would make a UI consumer handle
the same condition two ways. Non-positive `limit` means "choose", documented in the fake's KDoc.

## State + concurrency model

No new coroutine, no new scope, no new `StateFlow`. `requestHistory` is a `suspend` one-shot on the
caller's coroutine; its only concurrency surface is the existing `pendingRequests` table, which
`sendAndAwaitReply` registers before sending and removes in a `finally` covering success, error and
caller cancellation. Cancellation is the caller's (a ViewModel's `viewModelScope`); teardown is
already handled by `failAllPending` (#488). Nothing here is hot or shared.

## Error handling

| Condition | Surfaced as |
|---|---|
| Not connected / torn down mid-await | `IllegalStateException` (existing `check` / `failAllPending`) |
| `conversation.not_found` | `IllegalArgumentException` (existing `mapError`, unchanged) |
| `history.invalid_cursor` / `history.invalid_page_size` / `history.invalid_request` | `RelayErrorException(code, retryable = false)` (existing `mapError`, unchanged) |
| `history.unavailable` | `RelayErrorException(code, retryable = true)` — the one retryable member |
| Malformed `history_page` | the decode exception (`SerializationException` ⊂ `IllegalArgumentException`) or `IllegalArgumentException` from a bad `ts` |

Every one of these fails **only the ask that drew it**: the failure lands on the awaiting caller's
deferred, the shared inbound collector never sees a throw, and no projection is touched. `mapError`
needs no new mapping — it is already generic over unrecognised codes.

## Logging

`RemoteConversationRepository` has **zero** log call sites today and this change adds none, so the
cursor and entry payloads structurally cannot reach a log. No new exception message carries a
cursor, a payload or an entry type; the not-connected `check` names the wire type only.

## Testing strategy

JVM unit only (`./gradlew testDebugUnitTest`); no device rung. The ticket has no operator-facing
surface, so no rung-3 scenario — scroll-back's live rung belongs to #646. Coverage is **partitioned,
not duplicated**: the page shapes are proven once at the decode boundary and the remote test proves
the wire round-trip and the failure routing.

- **`data/network/HistoryPayloadsTest.kt` (new)** — the three daemon page shapes as JSON literals
  (entries + usable cursor + `at_start` false; entries + empty cursor + `at_start` true; no entries +
  empty cursor + `at_start` true); newest-first order preserved; an entry whose `type` is unrecognised
  is carried through; an entry carrying both `id` and `event_id` decodes with `id` from `id`; unknown
  keys tolerated; a missing `entries` / `cursor` / `at_start` and a malformed `ts` each throw; the
  request encodes all three snake_case keys including `limit: 0`.
- **`RemoteConversationRepositoryTest.kt`** — the request rides the correlated primitive with type
  `request_history` and the expected payload; a correlated `history_page` resolves to the page (this
  also proves the demux registration — without the arm the deferred hangs); `history.unavailable`
  surfaces `RelayErrorException` with `retryable` true; `conversation.not_found` surfaces
  `IllegalArgumentException`; a malformed page fails only that ask — a second request on the same
  repository still completes and another conversation's thread projection is unchanged. Driven with
  `runCurrent()` per the overview's test idiom.
- **`StableConversationRepositoryTest.kt`** — delegates to the live repository with arguments
  verbatim; no live connection throws `IllegalStateException`.
- **`FakeConversationRepositoryTest.kt`** — a full walk visits every entry exactly once and
  terminates on `atStart`; an exact-fill page reports `atStart` false with a usable cursor and the
  next call returns empty with `atStart` true; an unknown id throws `IllegalArgumentException`.

## Size

Estimated ~825 lines of total written work against the table's 800 — roughly 3% over one line, and
within the noise of an estimate. Every available split produces a one-consumer child: the DTOs'
only consumer is the remote binding in the same family, and each binding's only consumer is its
sibling. § A1's floor rule governs when floor and ceiling disagree, so this builds as one ticket
with the overage stated here. Five production files and five new exported types sit **at** their
ceilings, not over; consumer call sites needing simultaneous update: 0 (the interface method
carries a default). Acceptance criteria: 4. No state machine, so no reject-branch fan-out.

## Documentation handoff

Pending for the documentation stage — this ticket writes none of it:

- `docs/knowledge/features/conversation-repository.md` — the repository contract's new history seam
  (`requestHistory`, `HistoryPage`, `HistoryEntry`, and the default-throwing cascade-avoidance).
- `docs/knowledge/features/remote-conversation-repository.md` — the request, the decode, and
  `at_start` as the sole termination signal.

No new document.

## Open questions

1. **Does `HistoryEntry.payload` want a size bound at this layer?** Resolved during Phase B: no —
   the frame is already bounded by the application-envelope cap the transport enforces, and the
   daemon re-asks for a smaller page rather than truncating. A second bound here would be a defence
   for a failure mode that cannot reach this layer.
2. **Should the fake reject a negative `limit`?** Resolved in § Design ⑥: no — a reject is a wire
   behaviour, and inventing a second exception type for it would split one condition across two
   types for consumers.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** SHOULD FIX. The boundary is single and explicit —
  `HistoryPagePayloadDto.toHistoryPage`, one call site in `requestHistory`. But `entry.payload` and
  `entry.type` deliberately **do not cross** it: they stay untrusted, carried through as
  `JsonElement` / `String`, because AC #4 requires an unrecognised type to survive rather than be
  rejected. `JsonElement` is a weak type-system signal for "still untrusted", and #645's implementer
  will read the type, not this plan. So the untrusted class must be stated in `HistoryEntry`'s own
  KDoc — the `ThreadItem.UnrecognizedMessage` posture ("the most untrusted strings the thread
  holds") — not only here. The checklist's render rule (length bound, text-only path) does not apply
  in this ticket because nothing reaches Compose; it is the obligation handed to **#645**.
- **[Tokens, secrets, credentials]** No findings, and the reason is worth recording so a later
  ticket does not invent one: the cursor is explicitly **not a secret and not a capability**
  (protocol § The cursor) — trivially reversible, deliberately unsigned, carrying only the
  conversation id the client already knows. Authorization is pairing, enforced at the Noise IK
  handshake. This ticket also does not persist the cursor; it lives in the caller's memory.
- **[File / storage operations]** No findings — no filesystem path is built, read or written on any
  branch. The cursor never reaches a path, so the daemon-side "empty component resolves to the log
  root" hazard the protocol raises has no phone-side analogue.
- **[Inter-process / Android attack surface]** No findings — no intent filter, deep link, content
  provider, pending intent, push path or WebView is touched. No page content reaches a WebView on
  any path here.
- **[Cryptographic primitives]** No findings — rides the existing
  `Noise_IK_25519_ChaChaPoly_BLAKE2s` session unchanged. No key, nonce, RNG or secret comparison is
  introduced.
- **[Network & I/O]** No finding on frame size: an unbounded `entries` list is structurally
  unreachable because one Noise transport message is capped at `Noise.MAX_PACKET_LEN` (65535) by the
  vendored library, so the byte cap binds well before the protocol's 4096-entry ceiling. Named but
  not fixed: `sendAndAwaitReply` carries **no per-request timeout**, so a daemon that accepts a
  `request_history` and never answers leaves the caller suspended until `failAllPending` (#488) runs
  at teardown or the caller's scope is cancelled (`LifecycleConnectionDriver` closes on background).
  That is a pre-existing property of every correlated verb on this seam and is unchanged here; a
  per-request deadline would be a new mechanism for a failure mode nothing has observed.
- **[Error messages, logs, telemetry]** SHOULD FIX as an implementation obligation: no new exception
  message, on any branch, may carry the cursor, an entry `payload` or an entry `type`. The class has
  zero log call sites today and this change adds none.
- **[Concurrency]** No findings. No new coroutine, scope or shared mutable state. Concurrent asks
  are safe by construction — `requestId.incrementAndGet()` is atomic, each ask owns a
  `pendingRequests` entry keyed by its own envelope id, and a cancelled caller removes its entry in
  `sendAndAwaitReply`'s `finally`; there is no read-then-write across a suspension point. Recorded
  because it is load-bearing: **type-confused correlation is possible by construction** on this seam
  (the dispatch completes a waiter by `in_reply_to` and decodes by type, so a daemon could hand a
  `history_page` to an unrelated waiter, or answer a `request_history` with another reply type). The
  strict decode chosen in § Design ③ — every field required, no defaults — is what makes that fail
  loudly on the one caller instead of silently yielding a plausible page.
- **[Threat model alignment]** Hostile daemon frame is the live threat and is addressed: strict
  decode, failure scoped to the single ask, shared collector survives, unknown entry types carried
  through per the forward-compatibility rule. A compromised relay is content-blind and on-path — it
  can drop or delay a page (covered by the timeout note above) but cannot forge one inside the Noise
  session. Token theft and UI-side leakage are N/A here (no credential, nothing renders).
  **OUT OF SCOPE, deferred to #646:** a daemon that answers every page with `at_start: false` and a
  fresh cursor makes a walk non-terminating. One ask is one page at this layer, so the bound belongs
  to the loop — #646 owns paging as the operator scrolls and must cap its walk rather than trusting
  `at_start` to arrive.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

## Revisions

_None yet._

# #593 — Decode the `api_retry` wire event as a thread-observable retry state

**Size:** S (4 production files modified, 0 new, ~145 production lines) · **Label:** `security-sensitive`

## Design source

N/A — pure data-layer decode slice; no UI surface. The visible reaction ("Retrying — attempt N/M"
in place of the generic thinking label) is the natively-blocked sibling UI slice #594, which owns the
Figma anchor and the visual-fidelity check.

## Files to read first

The whole design is a structural clone of two shipped arms in one file. Read these before writing
anything — every decision below is anchored to a line range here, and the `queue_state` arm is a
near-verbatim template.

| Path | Extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:196-207` | `queuedByConversation` projection field + its KDoc — the **exact** shape and doc idiom for the new `apiRetryByConversation` field (single writer, atomic `update`, connection-scoped) |
| `…/RemoteConversationRepository.kt:421-436` | `TYPE_QUEUE_STATE` demux arm — the template arm to clone: capability gate, decode-or-drop, one `update`, and the "folds no thread row / does NOT clear a stall" comment |
| `…/RemoteConversationRepository.kt:408-420` | `TYPE_STALL` arm — the capability-gate rationale wording (fail-closed vs. a daemon that ignored the server-side fan-out gate) |
| `…/RemoteConversationRepository.kt:544-576` | `decodeStall` + `decodeQueueState` — the `try`/`catch (IllegalArgumentException)` drop idiom and the "nothing here logs the payload" posture. `decodeQueueState`'s `Pair` return is the shape to copy |
| `…/RemoteConversationRepository.kt:1114-1137` | `observeStall` + `observeQueue` — the `.map { … }.distinctUntilChanged()` cold-projection idiom + KDoc explaining why a `StateFlow` gives every late/`flatMapLatest` collector a current value |
| `…/RemoteConversationRepository.kt:1926-1940` | KDoc'd `TYPE_STALL` / `TYPE_QUEUE_STATE` companion consts — the one-paragraph "Capability-gated … event: `{fields}` (#N, pyrycode#N)" const-doc format |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:32-55` | `observeStall` / `observeQueue` interface declarations — the **defaulted-method lever** that avoids the fake + inline-double cascade. Copy the "Default … implementations without an interactive wire inherit … and need no override" wording |
| `…/ConversationRepository.kt:237-272` | `ThreadItem` sealed interface + `QueuedMessage` — the co-location precedent: a stream's element type lives in this file, next to the contract it serves. Multiple top-level types here, so the ktlint single-class-filename rule does not apply |
| `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:55-77` | `switchToLive` + the `observeStall`/`observeQueue` overrides — the one-line facade delegation and its `whenAbsent` seed |
| `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:71-123` | `StallPayloadDto`, `QueueStatePayloadDto`, `toQueue()` — where the new DTO + mapper go, the strict-required-field fail-closed posture, and the KDoc depth expected |
| `…/InteractivePayloads.kt:125-143`, `242-270` | `TurnStatePayloadDto.toEvent()` and `SessionTransitionPayloadDto.toBoundary()` — the *nullable* mapper posture (unknown value → drop). Contrast with this ticket's **total** mapper (§ Design 3) |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:5532-5558` | `collectQueue` + `queueStateEnvelope` test helpers — clone both |
| `…/RemoteConversationRepositoryTest.kt:3579-3810` | The 8 `queue_*` tests — the scenario set to mirror (per-conversation isolation, full replace, malformed drop, both capability-gate tests) |
| `…/RemoteConversationRepositoryTest.kt:3390-3560` | The 11 `stall_*` tests — source for the "inert toward neighbours" assertion style and `assertClearsStall` |
| `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt:225-290, 355-380` | The two `observeQueue_*` facade tests + the inline `ConversationRepository` stub with its `pushQueue` — clone both plus a `pushApiRetry` |

Server SSOT, already read and reconciled into § Wire contract below — **do not re-fetch**: pyrycode
`internal/protocol/interactive.go` (`ApiRetryPayload{ConversationID, Active, Current, Total}`, no
`omitempty`) and `docs/protocol-mobile.md § api_retry`. Nothing in this repo mentions `api_retry`
yet (verified by grep); there is nothing to find in the mobile tree.

## Context

`api_retry` tells the phone that claude is stuck retrying an API error, so the thread can say
"Retrying — attempt N/M" instead of an indefinite thinking spinner. It shipped daemon-side in
pyrycode#1074 (PR pyrycode#1160, merged 2026-07-21) as a PTY-derived status peer of `stall`.

This ticket is the **data half only**: decode the frame off the shared inbound stream into
per-conversation state the thread layer can observe through the repository facade. #594 renders it.

Two differences from the shipped `stall` arm drive every design decision below: `api_retry` has an
explicit **falling edge**, and it **carries a counter**. That combination is why this arm follows
`queue_state`'s payload-carrying shape (`queuedByConversation` / `observeQueue`) rather than `stall`'s
bare membership `Set` — a `Set<String>` cannot represent a counter, so a membership model would pin
the first counter forever and silently violate AC #2.

`compacting`, the banner-only twin from the same daemon PR, is mobile-side #583. This arm is the
shape #583's data half will follow, but **nothing here is generalised for it** — no status-event
framework, no shared abstraction, no registry. Two samples are not a pattern; #583 clones this arm
the way this arm clones `queue_state`.

## Wire contract (verified, reconciled)

Envelope `type` is `api_retry`, direction binary → phone, gated on the negotiated `interactive`
capability (the same gate as `assistant_delta` / `turn_state` / `stall`). Payload:

| Field | Type | Meaning |
|---|---|---|
| `conversation_id` | string | The conversation in the retry state. Routing key only. |
| `active` | bool | Rising edge (`true`) / falling edge (`false`, claude recovered). |
| `current` | int | Parsed `attempt N/M` counter's `N`. `0` when the on-screen counter did not parse. |
| `total` | int | The counter's `M`. `0` alongside `current: 0` for the same unparsed case. |

All four fields are **always present** (the Go package's no-`omitempty` rule — boundary values like
`active: false` / `current: 0` always serialize), so all four DTO fields are strict-required.

Semantics that the design must honour, and where each is handled:

- **Rising edge re-fires on a counter climb** (`3/10` → `4/10`), as just another `active: true` frame
  with an updated counter. The daemon does no dedup and re-fires only on an actual count change, so
  there is no per-tick flood. → § Design 4 (why `distinctUntilChanged` is still correct).
- **`{0, 0}` is a legitimate "retrying, count unknown" state**, not an error and not a drop. → § Design 3.
- **The falling edge carries the last-known counter verbatim** (copied from the rising edge so the
  daemon's final render stays coherent). A client **ignores** `current`/`total` when `active` is
  `false`. → § Design 3 (the mapper discards them; the demux arm never sees them).
- **Coarse and conversation-level, not turn-scoped** — no `turn_id`. Emitting it never opens, closes,
  or alters a turn, and it is **not** turn forward-progress. → § Design 5.
- **No field ever carries banner or screen text.** `current`/`total` are the only screen-derived
  data and are bounded, pre-sanitized ints. → § Security review 1.

## Design

Four production files, all modified, zero new. In dependency order:

### 1. `ApiRetryStatus` — the observable domain type

New in `data/repository/ConversationRepository.kt`, alongside `QueuedMessage` (same relationship: the
element type of a repository stream, co-located with the contract it serves). This file already
carries several top-level types, so the ktlint single-class-filename rule does not apply.

```kotlin
sealed interface ApiRetryStatus {
    data object NotRetrying : ApiRetryStatus
    data object AttemptUnknown : ApiRetryStatus
    data class Attempt(val current: Int, val total: Int) : ApiRetryStatus
}
```

A flat 3-member sealed interface, one member per observable state AC #1 enumerates: not retrying /
retrying with the counter unknown / retrying at attempt N of M. Rationale:

- **Sealed, not a nullable payload.** `Flow<ApiRetryStatus>` is non-null, so the facade seeds
  `switchToLive(NotRetrying)` and the interface default is `flowOf(NotRetrying)` — no
  `switchToLive<Foo?>(null)` explicit-type-argument awkwardness (`observeLastMessage` needed that).
  The consumer's `when` is exhaustive with no null branch.
- **Flat, not `Retrying(attempt: Attempt?)`.** Same type count, but nesting re-introduces a nullable
  the flat form doesn't need. #594 gets a 3-arm `when` and no null handling.
- **`data class` / `data object` are load-bearing, not cosmetic.** Structural equality is what makes
  § Design 4's `distinctUntilChanged` behave: `Attempt(3,10) != Attempt(4,10)` emits, while an
  unrelated conversation's frame leaves this conversation's value equal and does not re-emit.

Matches the project's sealed-type idiom (`ThreadItem`, `LiveSessionEvent`, `ModalEvent`, `PumpState`)
and Kotlin 2.2.10 supports `data object` (used widely under `ui/`).

### 2. `ApiRetryPayloadDto` — the decode DTO

New in `data/network/InteractivePayloads.kt`, next to `StallPayloadDto` / `QueueStatePayloadDto`.
**Inbound** v2 payload DTOs live together in that file; the per-payload `*PayloadDto.kt` files in
`data/network/` are all *outbound* request payloads and are not the pattern for a decode slice.

Signature: an `internal @Serializable data class` with four strict-required non-null fields —
`@SerialName("conversation_id") conversationId: String`, `active: Boolean`, `current: Int`,
`total: Int`. No field is nullable and none is defaulted: unlike `QueueStatePayloadDto.queued` and
`SessionTransitionPayloadDto.workspaceCwd`, this payload has **no documented nullable latitude**, so
strictness is uniform. A missing or wrong-typed field fails the structural decode with a
`SerializationException` — the fail-closed posture for an untrusted boundary (AC #3).

### 3. `ApiRetryPayloadDto.toStatus()` — a **total** mapper that owns both edges

Same file. Signature: `internal fun ApiRetryPayloadDto.toStatus(): ApiRetryStatus` — note the
non-nullable return.

Behaviour, in three cases:

- `!active` → `NotRetrying`, **discarding `current`/`total`**. This is the single place the
  "ignore the counter on the falling edge" contract is enforced, so the stale counter the daemon
  copies onto that frame structurally cannot reach the projection (AC #2).
- `active` and both `current > 0` and `total > 0` → `Attempt(current, total)`, carried verbatim.
- `active` otherwise → `AttemptUnknown`. One predicate covers the documented `{0, 0}` unparsed case
  **and** every undocumented shape (a partially-zero `{3, 0}`, a negative from a hostile daemon).

The mapper is **total — it never returns null**, deliberately unlike `toEvent()`/`toBoundary()`,
whose unrecognized-enum-value drop is a mapper concern. There is no unrecognized *value* here to
reject: `active` is a bool and the counter is two ints. Treating an undocumented counter shape as
`AttemptUnknown` rather than dropping the envelope is the safer failure: dropping would discard a
**real retry onset** and leave the thread on an indefinite thinking spinner — exactly the bug this
feature exists to fix. Structural malformation remains the only path that drops a frame (§ Design 6).

Contract sketch for the invariant, asserted by the § Testing scenarios: `active: false` maps to
`NotRetrying` for *any* `current`/`total`, and `active: true` maps to a retry state for *any*
`current`/`total`.

### 4. `apiRetryByConversation` projection + `observeApiRetry`

New private field in `RemoteConversationRepository`, a verbatim structural sibling of
`queuedByConversation` (`:207`):

```kotlin
private val apiRetryByConversation = MutableStateFlow<Map<String, ApiRetryStatus>>(emptyMap())
```

KDoc must state, mirroring `:196-207`: written **only** from the single `init` inbound collector, so
edges never race; the atomic `update` matches the sibling projections' memory-visibility posture;
connection-scoped in-memory state, so a fresh repository per connection (#351) starts empty and a
retry state never survives a reconnect (AC #5); a transient "right now" condition, not durable state.

The observer, cloning `observeQueue` (`:1136-1137`):

```kotlin
override fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> =
    apiRetryByConversation.map { it[conversationId] ?: ApiRetryStatus.NotRetrying }.distinctUntilChanged()
```

Two load-bearing details:

- `?: NotRetrying` gives **not-retrying-until-first-frame**, and makes an absent key and a stored
  `NotRetrying` observationally identical — which is why § Design 5's demux arm needs no `active`
  branch (it stores `NotRetrying` on a falling edge rather than removing the key, exactly as
  `queuedByConversation` stores `emptyList()`).
- `distinctUntilChanged` is correct **and** required by AC #2, which is the subtle part. It
  suppresses only value-*identical* re-emissions, so another conversation's frame does not re-emit
  this flow (the sibling behaviour), while a climbed counter is a *different* `Attempt` value and
  **does** reach the observer as a new emission. This is precisely what a membership `Set<String>`
  could not do: `true` → `true` would collapse the climb. A value-identical re-fire cannot occur
  (the daemon re-fires only on an actual count change) and would be observationally nothing if it did.

Cold, per-collector `.map{}` over a shared `StateFlow` — not a hot `SharedFlow`. A `StateFlow` always
has a current value, so every collector, including a `flatMapLatest` re-subscription through the
facade, receives the current state on subscription.

### 5. The `TYPE_API_RETRY` demux arm — one write, nothing else

New `const val TYPE_API_RETRY = "api_retry"` in the companion, KDoc'd in the `TYPE_STALL` /
`TYPE_QUEUE_STATE` format (`:1926-1940`). New arm in `onInbound`'s `when`, cloning the
`TYPE_QUEUE_STATE` arm's structure (`:421-436`): the capability gate, `decodeApiRetry(envelope)?.let`,
and a single unconditional replace —

```kotlin
apiRetryByConversation.update { it + (conversationId to status) }
```

**Deliberately no branch on `active` in the arm.** The mapper (§ Design 3) is the sole owner of the
edge semantics; a second `if (active)` here would encode the same rule twice. The arm is dumb: one
replace of one conversation's entry, leaving every other conversation untouched.

AC #4 (inert toward its neighbours) is satisfied **structurally, by that being the arm's only
statement** — there is nothing to add and nothing to suppress:

- Folds no thread row: it never touches `threadByConversation`.
- Does not clear an active stall: the `stalledConversations.update { it - … }` clearing lives only in
  the `decodeLiveSessionEvent` arm (`:389`), because a retry is *not* turn forward-progress. Claude
  retrying is stuck, not progressing — clearing a stall here would be a real bug.
- Does not open, close, or alter a turn: it never touches turn lifecycle state, and the payload
  carries no `turn_id` to key one by.
- Does not surface on `liveSessionEvents`: retry state is transient "right now" state, not one of the
  five streaming render events — the same reasoning that kept `stall` off that stream.

The gate wording follows the siblings: a non-interactive phone never decodes a spurious `api_retry`
from a buggy or hostile daemon that ignored the server-side fan-out gate (fail-closed, defence in
depth). Drop silently — nothing here logs the payload.

### 6. `decodeApiRetry` — the trust boundary

New private method, a verbatim `decodeQueueState` clone (`:570-576`):

```kotlin
private fun decodeApiRetry(envelope: Envelope): Pair<String, ApiRetryStatus>?
```

Decodes the untrusted `Envelope.payload` through the single configured `MobileJson` (never a default
`Json`), maps via `toStatus()`, and returns `conversationId to status`. The whole body is one
`try`/`catch (IllegalArgumentException)` — `SerializationException ⊂ IllegalArgumentException` — so a
malformed payload yields `null`, dropping that one envelope while the lone inbound collector survives
(AC #3). Because the mapper is total, **structural malformation is the only null path**. Nothing here
logs the payload.

`catch (IllegalArgumentException)` is *not* the `catch (IllegalStateException)` /
`CancellationException` trap: `CancellationException` extends `IllegalStateException`, a sibling of
`IllegalArgumentException`, and this is a non-suspend function with no suspension point to cancel.

### 7. Interface default + facade delegation

`ConversationRepository` (`:32-55` idiom) gains one defaulted method:

```kotlin
fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> = flowOf(ApiRetryStatus.NotRetrying)
```

The default is the **cascade-avoidance lever**: `FakeConversationRepository` and every inline test
double inherit "never retrying" and need **no override**, exactly as with `observeStall`/`observeQueue`.
Only the two real implementors change.

`StableConversationRepository` (`:74-77` idiom) gains one override:

```kotlin
override fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> =
    switchToLive(ApiRetryStatus.NotRetrying) { it.observeApiRetry(conversationId) }
```

This is all of AC #5: `switchToLive`'s `whenAbsent` reads "not retrying" while no connection is live,
and its `flatMapLatest` cancels the prior inner flow on every connection change, so combined with
#351's fresh-repository-per-connection a retry state structurally cannot survive a reconnect.

## State + concurrency model

- **No new coroutine, scope, dispatcher, mutex, or channel.** The arm runs inside the existing single
  `init` inbound collector on the repository's injected scope.
- **Single writer.** Only that one collector writes `apiRetryByConversation`, so rising and falling
  edges cannot race. The write is a pure `update {}` replace — not a read-modify-write — so there is
  no check-then-mutate TOCTOU even in principle.
- **Cannot back-pressure the connection.** `MutableStateFlow.update` is non-suspending, so a slow or
  absent `observeApiRetry` collector can never stall the shared inbound collector's
  `conversations` / `message` / `ack` processing. No `tryEmit`, no buffer, no `DROP_OLDEST` question
  (this is held state, not an event stream).
- **Hot state, cold reads.** One shared `MutableStateFlow` (hot, connection-scoped) fanned out to
  unlimited cold per-collector projections.
- **Lifecycle.** Connection-scoped: born empty with the repository, discarded with it, never
  persisted — so a retry state cannot survive process death or leak into a later session.

## Error handling

| Failure mode | Layer | Result |
|---|---|---|
| Missing / wrong-typed field (`conversation_id` absent, `active` a string, `current` a float or > Int32) | `MobileJson` structural decode | `SerializationException` → caught as `IllegalArgumentException` → that one envelope dropped, collector survives (AC #3) |
| Undocumented counter shape (`{3, 0}`, negative, `current > total`) | `toStatus()` | `AttemptUnknown` — no drop, no throw. Losing a real onset is the worse failure (§ Design 3) |
| `api_retry` without the negotiated `interactive` capability | demux gate | Never decoded (AC #3) |
| Frame for a conversation nobody observes | projection | Sits unread in the map; no other conversation's state is disturbed |
| No live connection | facade `switchToLive` | `NotRetrying` (AC #5) |

Nothing in this arm throws to a caller, and nothing logs — uniform with every sibling `onInbound` arm.
The UI surface (banner vs. label) is #594's decision; this slice surfaces state only.

## Testing strategy

Plain-JVM unit tests only (`ANDROID_HOME=~/Library/Android/sdk ./gradlew testDebugUnitTest`) — no
instrumented tests, no `ComposeTestRule`, no Robolectric (nothing here touches `android.util.Log`, so
the "not mocked" trap does not apply). Test-first per the repo convention.

Drive the pump with `runCurrent()`, **not** `advanceUntilIdle()` — the established idiom for
remote-repo pump→demux→project→emit fan-out tests; `advanceUntilIdle()` fails to deliver the Channel
item because there are no timers.

### `RemoteConversationRepositoryTest.kt`

Two new helpers, cloned from `collectQueue` / `queueStateEnvelope` (`:5532-5558`): a
`collectApiRetry(repo, conversationId)` returning a `MutableList<ApiRetryStatus>`, and an
`apiRetryEnvelope(conversationId, active, current, total, id = 1L)` building the four-field payload.

Scenarios (each seeds `negotiatedCapabilities = { setOf("interactive") }` unless stated):

- **Rising edge with a counter** — `active: true, 3, 10` → observed `Attempt(3, 10)` (AC #1).
- **Rising edge, counter unparsed** — `active: true, 0, 0` → observed `AttemptUnknown`, *not*
  `NotRetrying` and not a drop (AC #1).
- **Counter climb reaches the observer as a new emission** — push `3/10` then `4/10` and assert the
  **full emission list** is `[NotRetrying, Attempt(3,10), Attempt(4,10)]`. Asserting `.last()` alone
  would pass under a dedup bug; the list is what pins "no collapsing the climb" (AC #2). This is the
  single most important test in the slice.
- **Falling edge clears** — `active: true, 3, 10` then `active: false, 3, 10` → final `NotRetrying`.
  The stale non-zero counter on the falling edge is ignored, not stored (AC #2).
- **Falling edge with no prior rising edge** — a lone `active: false` leaves the observer at
  `NotRetrying` and emits nothing new (`distinctUntilChanged` collapses it).
- **Per-conversation isolation** — a frame for `c1` leaves `c2`'s emission list at exactly
  `[NotRetrying]` (AC #3).
- **`distinctUntilChanged`** — `c2`'s flow does not re-emit when `c1`'s status changes.
- **Malformed payload dropped, collector survives** — push a payload missing `conversation_id` (and
  one with `active` as a string), assert no emission, then push a valid frame and assert it lands —
  proving the single inbound consumer was not torn down (AC #3).
- **Capability gate closed** — `negotiatedCapabilities = { emptySet() }`: a valid frame produces no
  emission beyond the seeded `NotRetrying` (AC #3).
- **Capability gate, other token only** — `{ setOf("something_else") }`: same, proving fail-closed
  rather than any-token-passes.
- **Inert toward neighbours (AC #4)** — one test, three assertions: seed a stall via `stallEnvelope`,
  push an `api_retry`, then assert (a) `observeStall` is **still `true`** (a retry is not forward
  progress), (b) `observeMessages` for that conversation is still empty (no thread row folded), and
  (c) the `api_retry` state landed anyway. Undocumented-counter shapes (`{3, 0}`, negative) fold into
  a table-style assertion here or in the unparsed-counter test — all map to `AttemptUnknown`.

### `StableConversationRepositoryTest.kt`

Clone the two `observeQueue_*` facade tests (`:263-290`) and add a `pushApiRetry` to the inline
`ConversationRepository` stub (`:355-380`, next to `pushStall`/`pushQueue`):

- **`observeApiRetry_whileAbsent_emitsNotRetrying`** — no live repository → `NotRetrying` (AC #5).
- **`observeApiRetry_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch`** — a status pushed on one live
  repository is observed; after the coordinator switches repositories, the prior connection's status
  is not observable, proving connection-scoping across a reconnect (AC #5).

No `FakeConversationRepository` or other test-double changes: they inherit the interface default.

## Open questions

None blocking. Two notes for downstream:

- **#594 owns the display of an incoherent counter** (`current > total`, or an implausibly large
  `total`) from a hostile or buggy daemon. This arm deliberately does not clamp — see § Security
  review 6/9.
- **#583 (`compacting`) clones this arm**, it does not extend it. If #583's architect finds itself
  wanting a shared status-event abstraction, that is a third-sample decision, not this ticket's.

## Security review

**Verdict:** PASS

**Findings:**

- **[1. Trust boundaries]** No findings. Exactly one boundary, `decodeApiRetry`
  (`RemoteConversationRepository.kt`, § Design 6) — a single named function, not scattered parsing.
  The untrusted `Envelope.payload` never escapes it: only a mapped `ApiRetryStatus` crosses into the
  projection, so downstream holds trusted, typed data by construction. Strongest property of this
  design: `ApiRetryStatus` is a **closed sealed type carrying two `Int`s and no `String`** (the
  routing `conversationId` stays in the map key, never in the value), so no daemon-supplied text —
  banner, screen scrape, or otherwise — can structurally reach the UI through this arm. A second,
  independent boundary is the `interactive` capability gate, fail-closed and evaluated before decode.
- **[2. Tokens, secrets, credentials]** Not applicable, by design decision: this arm reads an
  already-authenticated, already-Noise-decrypted stream and adds no credential creation, storage,
  comparison, rotation, or revocation surface. No token, key, or fingerprint is touched.
- **[3. File / storage]** Not applicable, by design decision: state is a single in-memory
  `MutableStateFlow`, connection-scoped and **never persisted** (§ State + concurrency). Nothing is
  written to disk, so no path handling, no TOCTOU, no at-rest-encryption choice, no atomic-write
  requirement, and no `allowBackup` exclusion arises. Non-persistence is also a positive property: a
  retry state cannot survive process death into a later session.
- **[4. Inter-process / Android attack surface]** Not applicable, by design decision: no `Activity`,
  `Service`, `BroadcastReceiver`, `ContentProvider`, `PendingIntent`, `WebView`, deep link, or
  `intent-filter` is added or altered. Nothing in the slice is exported.
- **[5. Cryptographic primitives]** Not applicable, by design decision: no RNG (nothing random), no
  key, no hash, and no comparison of an attacker-controlled value against a secret — so the
  `MessageDigest.isEqual` constant-time rule has no site here. `conversation_id` is used **only** as
  a map key; routing by it is not an authorization decision (the daemon already scoped the fan-out,
  and the phone only displays what it observes).
- **[6. Network & I/O]** No findings on the checklist items — this arm opens no connection, builds no
  `OkHttpClient`, sets no timeout or TLS config, and does not lift any frame-size cap; it rides the
  existing single inbound collector and adds no I/O and no suspension point. It also cannot
  back-pressure the connection (§ State + concurrency: non-suspending `update`).
  **OUT OF SCOPE —** a hostile-but-authenticated daemon could grow `apiRetryByConversation` without
  bound by sending frames for endlessly many fabricated `conversation_id`s. Deliberately not fixed
  here: this is **pre-existing and identical** for every sibling projection
  (`stalledConversations`, `queuedByConversation`, `threadByConversation`, `lastMessages` — none caps
  its key count), and this arm's per-key cost (a sealed singleton or two `Int`s) is *strictly
  smaller* than the shipped `queuedByConversation`, which stores unbounded user text per key. The map
  is connection-scoped, so a reconnect reclaims it. Capping this one arm would be a spot-fix that
  does not close the class; it has never been observed, and the peer is authenticated and
  Noise-sealed. If it is ever to be addressed it belongs in a projection-family ticket covering all
  five maps at once — filing that is PO's call, not a precondition for this slice.
  **SHOULD FIX (in #594, not here) —** `current`/`total` are bounded by the `Int` decode (a value
  exceeding Int32 fails structurally and is dropped), but a hostile daemon can still send a coherent
  extreme (`2147483647`) or an incoherent pair (`9/3`). The consequence is display-side only —
  layout overflow or a nonsense label, never memory growth or code execution. This arm carries the
  ints **verbatim** rather than clamping, deliberately: clamping at the decode boundary would
  silently rewrite server data and diverge from the carry-verbatim posture every sibling mapper uses.
  #594 owns display-side bounding; its code review should check it.
- **[7. Error messages, logs, telemetry]** No findings. The arm logs **nothing** and constructs no
  error message (drops are silent, and nothing throws to a caller), uniform with every sibling
  `onInbound` arm and with the repository's no-log posture. MUST-NOT-log, stated explicitly in the
  spec's KDoc requirements: the payload, `conversation_id` (sensitive per the sibling KDocs), and the
  counter — which matters even though it is "just two ints", because `current`/`total` are
  **screen-derived** data crossing the tui-driver substrate seal, and the uniform no-log rule is what
  keeps them out of Logcat in any build variant. No telemetry, metrics, or crash-reporter surface is
  added, so no consent question arises.
- **[8. Concurrency]** No findings. No coroutine is launched, so there is no scope-ownership or
  outlives-the-`ViewModel` leak question; no mutex, so no lock-ordering question. On the checklist's
  explicit TOCTOU item: the design uses `update {}` and in fact performs a pure replace rather than a
  read-modify-write, so there is no check-then-mutate window at all. Single writer (the one inbound
  collector) means edges cannot interleave. On the hot-vs-cold item: `observeApiRetry` is correctly
  **cold** and per-collector over a shared `StateFlow` — a hot `SharedFlow` would have been wrong
  here, since late subscribers and `flatMapLatest` re-subscriptions through the facade would observe
  nothing until the next frame. Cancellation safety: the decode is non-suspending and catches
  `IllegalArgumentException`, which does **not** swallow `CancellationException`
  (that extends `IllegalStateException`, a sibling class) — the known trap is verified absent, not
  merely unencountered.
- **[9. Threat model alignment]** No findings. The threat this arm actually faces is a compromised or
  buggy *authenticated* daemon injecting frames the phone should not act on. Addressed twice over:
  the `interactive` capability gate (client-side mirror of the server-side fan-out gate — defence in
  depth, so a daemon that ignored its own gate still gets nothing) and the closed sealed type that
  admits no text. The specific reason pyrycode#1074 was itself labelled `security-sensitive` —
  screen-derived data crossing the tui-driver substrate seal — is addressed structurally: the only
  screen-derived values are two bounded, pre-sanitized ints that the type system prevents from ever
  being a string. Mobile-specific threats from the checklist (screenshot leakage,
  accessibility-service eavesdropping, overlay attacks, malicious deep links, third-party keyboard
  logging) have no site in a data-layer decode slice with no UI and no input; the first three become
  relevant only once #594 renders the counter on screen, and #594 owns them.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-30

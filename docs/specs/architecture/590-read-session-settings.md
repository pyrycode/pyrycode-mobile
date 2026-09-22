# #590 — Read settings for the open conversation

The data-layer read half of the settings cluster: a `request_session_settings` → `session_settings`
round trip, a presence-aware `effective_effort` decode, and a per-conversation retained reading that
refreshes on thread entry, host reconnect, session transition and caller invalidation. No UI change —
#649 / #650 / #651 consume this reading.

## Files read

| File | Symbol | Why it matters |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` | `SetSessionSettingsPayloadDto`, `SessionSettingsUpdatedPayloadDto` | The write half this read joins; its presence-contract KDoc is the omission contract the new types must not weaken. |
| `app/src/main/java/de/pyryco/mobile/data/network/HistoryPayloads.kt` | `RequestHistoryPayloadDto`, `HistoryPagePayloadDto`, `HistoryEntryDto`, `toHistoryPage` | The nearest request/reply pair; `HistoryEntryDto.payload` is the raw-`JsonElement` precedent for a decode that must not flatten the wire shape. |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` | `MobileJson` | `explicitNulls = false`, `ignoreUnknownKeys = true`, `encodeDefaults = true` — the configuration this ticket must not change and must decode around. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` | `requestHistory`, `HistoryPage`, `ApiRetryStatus`, `setSessionSettings` | Where the typed return is declared beside its method; `ApiRetryStatus` is the sealed-family precedent for a three-state domain value. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `sendAndAwaitReply`, `onInbound`, `requestHistory`, `observeApiRetry`, `decodeSessionTransition`, `negotiatedCapabilities`, `failAllPending` | The correlation primitive, the correlated-reply demux arm the new reply type must join, the per-conversation projection shape, and the session-transition arm that already decodes the refresh trigger. |
| `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` | `switchToLive`, `live` | The two delegation shapes: `flatMapLatest` for cold reads, throwing snapshot for one-shots. |
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` | `setSessionSettingsCalls`, `requestHistory` | The recorded-call seam the refresh trigger mirrors. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` | `onConnection` | Confirms a fresh repository per connection — the mechanism that makes host isolation structural rather than checked. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` | `FakeSessionPump`, `startRequestHistory`, `historyPageEnvelope`, `errorEnvelope` | The harness the new tests reuse verbatim. |
| `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md` | § application-payload DTOs | The house rules this follows: decode is the single validate boundary; encode-only DTOs model only what is sent; `WorkspaceUpdatedPayloadDto` is the recorded case where an omitted key and an explicit `null` were *deliberately* collapsed — this ticket is the first where they must not be. |
| `../pyrycode/docs/protocol-mobile.md` (at `43a5242`) | § `request_session_settings`, § `session_settings` | The wire SSOT: three always-answer branches, every original field always present, `effective_effort` the one optional key. |

Codegraph returned nothing for this repo — `codegraph_status` reports 0 files / 0 nodes indexed — so
the reading list above came from grep and Read.

## Design source

N/A — no composable or screen change. The footer and composer rendering lands in #649 / #650 / #651
against Figma node `16-8`.

## Context

Mobile has the settings write half only: `setSessionSettings` sends `set_session_settings` and awaits
`session_settings_updated`, which echoes nothing but the session id. Nothing on the phone can say what
the daemon currently holds, so the composer has to source `selectedModel` / `selectedEffort` from
`AppPreferences` device defaults and `currentSessionId` from a conversation record that is empty on the
live path. This ticket adds the read: a conversation-scoped `request_session_settings` whose reply
carries the session to address, the saved model and effort, Claude's applied effort, the current
child's confirmed permission posture, and the context-window reading.

The three distinctions the daemon publishes and this ticket must preserve intact:

- **`effort` is the saved choice; `effective_effort` is the applied reading.** Neither substitutes for
  the other, and `effective_effort` has three states — **omitted** (unavailable/unsupported),
  **explicit `null`** (Claude reports no effort parameter), **a string** (confirmed, verbatim).
- **An empty `permission_mode` means unavailable, not Manual approval.** It can accompany a live
  unconfirmed child or a dormant session, and `yolo: false` alone is not evidence that approvals are
  enforced.
- **The reply is never an error frame.** Unhosted, unbound, dormant and unnamed all resolve to the same
  all-zero reply, which is what keeps the verb from being a conversation-membership probe.

No ADR is warranted: this adds a verb to an existing transport under rules ADR 025 already sets.

## Design

Five production files, matching the ticket's ceiling.

### 1. Wire types — `SessionSettingsPayloads.kt`

Two new types beside the existing write pair, plus one mapper.

```kotlin
@Serializable
data class RequestSessionSettingsPayloadDto(@SerialName("conversation_id") val conversationId: String)

@Serializable
data class SessionSettingsPayloadDto(
    @SerialName("session_id") sessionId: String, model: String, effort: String, yolo: Boolean,
    @SerialName("permission_mode") permissionMode: String,
    @SerialName("used_tokens") usedTokens: Long, @SerialName("window_tokens") windowTokens: Long,
)

fun JsonElement.toSessionSettings(): SessionSettings
```

`RequestSessionSettingsPayloadDto` is **encode-only**, one required key, the encode-only discipline
`CreateConversationPayloadDto` records. `SessionSettingsPayloadDto` is **decode-only** and carries
**no field for `effective_effort`** — that is the whole point of the two-step decode below. Every
original field is required with **no default**, mirroring `HistoryPagePayloadDto`: the wire emits all
seven unconditionally, so an absent key is a malformed frame rather than a silently-defaulted one, and
`permission_mode: ""` must be a *read* zero rather than a *manufactured* one.

**Why the reply is not decoded as one DTO.** `MobileJson` sets `explicitNulls = false`, so a
`String?`-with-`null`-default field decodes an omitted key and an explicit `null` to the same Kotlin
`null` — exactly the collapse `WorkspaceUpdatedPayloadDto` wanted and this ticket must not have. The
decode therefore runs in two steps inside `toSessionSettings`, in this order:

1. `MobileJson.decodeFromJsonElement<SessionSettingsPayloadDto>(this)` — the structural boundary. A
   non-object, a missing original key, or a wrong-typed one fails the frame here, before any presence
   read. This keeps "decode as the single validate boundary" true for the seven original fields.
2. A presence read of `effective_effort` off the same `JsonObject`, reached only after step 1 proved
   the payload is an object. Absent key → `Unavailable`; `JsonNull` → `NotReported`; a string
   `JsonPrimitive` → `Applied(content)` verbatim, `""` included; anything else (number, boolean,
   object, array) → `SerializationException`. `JsonNull` is a `JsonPrimitive` subtype, so the null
   check runs first.

The thrown message is a **static literal** naming the key and nothing else — no value, no type
fragment of the payload. The frame's content never reaches an exception message, and therefore never
reaches a crash report or a log line.

### 2. Domain types — declared beside the method in `ConversationRepository.kt`

```kotlin
data class SessionSettings(
    val sessionId: String, val model: String, val effort: String,
    val effectiveEffort: EffectiveEffort, val permissionMode: String, val yolo: Boolean,
    val usedTokens: Long, val windowTokens: Long,
)

sealed interface EffectiveEffort {
    data object Unavailable : EffectiveEffort   // key omitted
    data object NotReported : EffectiveEffort   // explicit null
    data class Applied(val value: String) : EffectiveEffort
}
```

Declared here rather than in `data/model/` for the reason the ticket gives — it is what holds this at
five production files — and because `HistoryPage` set that precedent for a per-verb return type.

`model`, `effort` and `Applied.value` are **arbitrary daemon strings retained verbatim**: never mapped
through `Model` or `Effort`, never trimmed, never normalised. `""` means "no override, inherited
default" and is a real value, not an absence. `permissionMode` stays an open `String` rather than an
enum for two reasons: the read half legally reports `bypassPermissions`, which the write half's closed
set rejects, and `""` means *unavailable* — a sealed enum would have to mint a member for "no
confirmation" that a consumer could mistake for a posture. `data` modifiers are load-bearing on both
types: structural equality is what makes the repository projection's `distinctUntilChanged` behave, as
`ApiRetryStatus` already documents.

Token counts are `Long` (a Go wire `int` is 64-bit; the pyrycode#720 width trap). They are decoded and
carried rather than discarded, because AC #5 names the all-zero and dormant replies' zero counts as a
state that must survive decoding.

### 3. Repository contract — two defaulted methods

```kotlin
fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = flowOf(null)
fun refreshSessionSettings(conversationId: String) { }
```

Both carry defaults, so **no existing implementor or call site changes** — the cascade-avoidance the
interface already applies to `delete` / `requestScreenSnapshot` / `requestHistory`. `null` is the
single "reading unavailable" value: no connection, no interactive capability, a failed read, a
malformed reply, or nothing read yet. It is never a fallback to device defaults and never another
conversation's values.

`refreshSessionSettings` is a **non-suspend, fire-and-forget invalidation**, not a second read method.
It exists for AC #4's fourth trigger — a caller whose settings write has settled asks for a fresh
reading and receives it on the flow it already collects. Non-suspend and non-throwing so a facade with
no live connection can drop it rather than throw into a caller that has nothing to do with the failure.

### 4. `RemoteConversationRepository`

One new field, one new projection, one new read body, two new wire constants, two arm edits.

- `settingsRevision: MutableStateFlow<Map<String, Long>>` — `conversationId -> refresh ordinal`. The
  refresh trigger, not the reading: nothing caches a `SessionSettings` on this class.
- `observeSessionSettings(conversationId)` — the revision slice for this conversation,
  `distinctUntilChanged`, `flatMapLatest` into a one-shot read flow, prefixed by an `onStart` emission
  of `null`.
- A private read flow: `flow { emit(readSessionSettings(conversationId)) }.catch { emit(null) }`,
  where the read returns `null` without sending anything unless `interactive` is negotiated. `catch`
  is the cancellation-safe failure conversion — it does not swallow the collector's own cancellation,
  and unlike a terminal `.catch` on the outer flow it leaves the outer flow alive for the next
  revision.
- `readSessionSettings(conversationId)` — encode → `sendAndAwaitReply` → `toSessionSettings()`. The
  `requestHistory` body, one line shorter: there is no projection fold.
- `refreshSessionSettings(conversationId)` — `settingsRevision.update { it + (id to (it[id] ?: 0L) + 1) }`.
- `onInbound`: `TYPE_SESSION_SETTINGS` joins the correlated-reply arm that completes a pending
  deferred by `inReplyTo`.
- `onInbound`: the `TYPE_SESSION_TRANSITION` arm gains a third write beside `appendSessionBoundary`
  and `updateCurrentSessionId` — a revision bump for the transitioning conversation, inside the
  existing `interactive` gate.

**Why the four triggers land without a fourth mechanism.** Thread entry is subscription: the revision
`StateFlow` always has a value, so a fresh collector reads immediately. Host reconnect is
`RelayRepositoryCoordinator` minting a fresh repository per connection plus the facade's
`flatMapLatest` re-subscribing — the read re-issues against the new host and the old host's in-flight
read dies with its repository. Session transition and caller invalidation are revision bumps.

**Why a stale reply cannot overwrite a current one.** Three independent reasons, none of them a check
the implementation has to remember: `flatMapLatest` cancels the previous read before starting the
next, so a superseded read has no live collector to emit into; `sendAndAwaitReply` removes its pending
deferred in a `finally`, so a late or duplicate reply correlates with nothing and is a no-op at the
demux; and nothing is retained per conversation, so there is no slot for a stale value to land in. A
reply from another host cannot arrive at all — it belongs to another repository instance.

**Why the read fails closed without `interactive`.** The daemon leaves a non-interactive conn fully
inert on this verb — no reply, not even a signal that the conversation exists — so a frame sent
without the capability would suspend until teardown. Not sending it is the same fail-closed posture
the `session_transition` / `stall` / `queue_state` arms already take, and it keeps "the read sends
`request_session_settings` and nothing else" true in the degenerate case too.

### 5. `StableConversationRepository` and `FakeConversationRepository`

Facade: `observeSessionSettings` → `switchToLive<SessionSettings?>(null)`, the `observeLastMessage`
shape. `refreshSessionSettings` → `currentRepository.value?.refreshSessionSettings(id)`, deliberately
**not** through `live` — an invalidation with no connection is a no-op, not a failure.

Fake: a `MutableStateFlow<Map<String, SessionSettings>>` seeded empty, so an unseeded conversation
reads `null` (unavailable) rather than a manufactured default; a test seam to set one; and a recorded
list of refresh calls mirroring `setSessionSettingsCalls`, which is what lets #649's ViewModel test
assert that a settled write asked for a fresh reading.

## State + concurrency model

- `settingsRevision` is a `MutableStateFlow` written by two producers — the single `init` inbound
  collector (session transition) and any caller thread (`refreshSessionSettings`). Both write through
  an atomic `update {}` read-modify-write; the increment reads the current ordinal, so `update` is
  load-bearing rather than stylistic (the `compactingConversations` argument, not
  `apiRetryByConversation`'s pure replace).
- The read runs on the **collector's** coroutine — the consumer's `viewModelScope` in production, the
  test's scope in unit tests. The repository launches nothing new and owns no scope for this feature.
- Cancellation: `flatMapLatest` cancels a superseded read; collector cancellation cancels the in-flight
  `sendAndAwaitReply`, whose `finally` deregisters the pending deferred; connection teardown completes
  every pending deferred exceptionally through `failAllPending`, which the read flow's `catch` turns
  into `null`. Every path has a defined end.
- Dispatcher: none is switched. The work is a frame encode, a suspend await and a JSON decode on
  already-in-memory bytes, exactly as `requestHistory` does it.
- Ordinals are per conversation and monotonic within a connection; they are never compared across
  connections because a new connection is a new repository with a new map.

## Error handling

| Failure | Where | Result |
|---|---|---|
| No live connection at subscription | facade `switchToLive` | `null` emitted; resumes on the next connection |
| `interactive` not negotiated | read body | `null`, **no frame sent** |
| Pump not `Open` | `sendAndAwaitReply`'s `check` | `IllegalStateException` → `catch` → `null` |
| Connection torn down mid-await | `failAllPending` | `IllegalStateException` → `catch` → `null` |
| Server `error` frame | `mapError` on the deferred | thrown → `catch` → `null` |
| Missing / wrong-typed original field | step 1 of `toSessionSettings` | `SerializationException` → `catch` → `null` |
| Wrong-typed `effective_effort` | step 2 of `toSessionSettings` | `SerializationException` with a static message → `catch` → `null` |

Every failure is scoped to the one read: no projection is touched, no other conversation's flow
re-emits, and the next revision bump retries from scratch. There is no partial `SessionSettings` — the
decode either produces all eight fields or throws. No branch logs, and no exception message carries
payload content.

`mapError` needs no new mapping: the verb has no reject codes at all (the daemon always answers), so
an `error` frame here can only be a generic transport-level one the existing arm already handles.

## Testing strategy

Unit only — `./gradlew testDebugUnitTest`, `runTest`, the existing `FakeSessionPump`. This ticket is
data-layer; no composable changes, so no Compose UI test and no emulator rung. It is **not an
operator-facing flow** (nothing the operator exercises on the phone changes until #649), so it lands
no rung-3 scenario. The ticket states no live run is required: live effort proof is #545, live
permission proof is #687 via #650.

**`SessionSettingsPayloadsTest.kt`** (new) — the decode boundary:

- request encodes to exactly `{"conversation_id":"c1"}`
- the three `effective_effort` states decode distinctly: key omitted → `Unavailable`; explicit `null` →
  `NotReported`; `"medium"` → `Applied("medium")`
- `""` and an unrecognised value (`"ludicrous"`) both decode to `Applied` verbatim
- an older daemon's reply omitting the key decodes successfully rather than failing
- saved `effort` disagreeing with `effective_effort` retains both independently
- wrong-typed `effective_effort` — number, boolean, object, array — each fails the frame, and the
  thrown message contains no fragment of the payload
- each missing original key fails the frame; a non-object payload fails the frame
- the all-zero reply and the dormant reply (non-empty `session_id`, `permission_mode: ""`,
  `yolo: false`, zero counts) decode to retained zeros with nothing manufactured
- `permission_mode: "bypassPermissions"` is retained, and an unknown future mode string is too
- an arbitrary `model` string is retained verbatim

**`RemoteConversationRepositoryTest.kt`** (extend) — the wire round trip and the triggers:

- the sent frame is exactly one `request_session_settings` carrying the conversation id, and no
  `set_session_settings` is sent
- a correlated reply reaches the collector as a decoded reading
- a session transition for this conversation issues a second read; one for another conversation does
  not
- `refreshSessionSettings` issues a fresh read
- a reply correlated to a superseded request does not overwrite the newer reading, and a duplicate
  reply changes nothing
- without `interactive`, nothing is sent and the collector sees `null`
- an `error` reply and a malformed reply each leave the reading `null`
- two conversations' readings are independent

**`StableConversationRepositoryTest.kt`** (extend) — `null` while no connection is live; delegation to
the live repository; `refreshSessionSettings` does not throw with no connection.

**`FakeConversationRepositoryTest.kt`** (extend) — unseeded reads `null`; a seeded reading is observed;
refresh calls are recorded.

## Open questions

1. Does `Flow.catch` on the inner read flow leave the outer `flatMapLatest` alive for the next
   revision? Expected yes (the failure is confined to the inner flow); the "session transition after a
   failed read issues a fresh read" test is what proves it. **Resolve in Phase B; record here if the
   shape has to change.**
2. Do the token counts belong on `SessionSettings` at all, given no consumer reads them until a later
   context-figure ticket? Carried on the grounds that AC #5 names their zero state as decodable and
   dropping them would force a second edit of this exact surface.

## Documentation handoff

Pending the documentation stage. Requirement from the ticket body, carried forward verbatim:

- Fold this read into `docs/knowledge/features/remote-conversation-repository.md` (relay-backed reads)
  and `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` (payload handling), in the sections
  each already uses for request/reply pairs.
- State observably that `effort` is the saved choice, that `effective_effort` is the applied reading
  with three distinct states, and that an empty `permission_mode` means unavailable rather than Manual
  approval.
- Run `scripts/docs-guard.sh` before committing docs.

No documentation file is edited by this ticket.

## Size

Six boundary lines, re-counted against this written plan:

| Limit | Boundary | This ticket |
|---|---|---|
| Production source files | ≤ 5 | 5 |
| Total written work | ≤ 800 | ~900 — **over, stated** |
| New exported types | ≤ 5 | 4 (`RequestSessionSettingsPayloadDto`, `SessionSettingsPayloadDto`, `SessionSettings`, `EffectiveEffort` as one 3-member sealed family, the `ApiRetryStatus` precedent) |
| Consumer call sites | ≤ 10 | 0 — both interface methods are defaulted |
| Acceptance criteria | ≤ 5 | 5 |
| Reject branches | ≤ 10 | 0 daemon rejects; 3 decode-failure shapes |

The line count is over by roughly a hundred, and it is built rather than split for the reason the
refiner recorded: the presence-aware decode and the retained per-conversation reading each have exactly
one consumer — the other half — so cutting them apart yields a slice nothing outside the pair consumes.
The floor rule outranks the ceiling, a ceiling miss costs at most a continuation leg, and a slice that
cannot be verified on its own is not recoverable by any resume. Stated here rather than escalated.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** The boundary is explicit and singular: `toSessionSettings` is the only place a
  `session_settings` payload becomes trusted, and everything downstream holds `SessionSettings` /
  `EffectiveEffort` — decoded types, never raw JSON. Nothing re-parses the payload later. The reply's
  `session_id` is fed back to the daemon as `set_session_settings`' addressing key by #649; it is a
  registry lookup key on the daemon side and never joins a path, a URL, a filename or a cache key on
  the phone, so returning the daemon's own id to it introduces no new authority — authorization is
  pairing at the Noise handshake, as it is for every other verb.
- **[Trust boundaries]** OUT OF SCOPE — `model`, `effort`, `EffectiveEffort.Applied.value` and
  `permissionMode` are **daemon-authored text that #649 / #650 / #651 will render in Compose**, and
  this ticket deliberately applies **no length bound**: AC #1 and AC #2 require them retained verbatim,
  so a cap here would violate the contract it is meant to protect. The only bound is structural — the
  inbound frame cap in `OkHttpRelayTransport`, which this ticket does not relax, so a single field
  cannot exceed the envelope. The **render-side** bound (truncate-for-display, inert text, never
  markup / a URL / a log line) belongs to #649 and #651, which own the composer and footer. Named
  here so those tickets inherit it rather than discover it.
- **[Tokens, secrets, credentials]** No finding, and none applicable by construction: this change mints,
  stores, rotates and revokes nothing. The reply carries no secret — a session id, two configuration
  strings, a posture and two counters. Nothing is written to `DataStore`, `SharedPreferences` or
  `AppPreferences`; AC #4 forbids the last of those and the design has no reference to reach it with.
- **[File / storage]** No finding — the change performs no filesystem I/O of any kind. No path is
  built, so there is no traversal, TOCTOU, atomicity or backup-exclusion surface to reason about.
- **[Inter-process / Android attack surface]** No finding — no `Activity`, `Service`, `Receiver`,
  intent filter, deep link, `PendingIntent`, content provider or push path is touched. The daemon text
  this verb carries reaches **no WebView**: the app has none, and the decode produces a data class
  rather than markup.
- **[Cryptographic primitives]** No finding — no randomness, no key, no nonce, no digest and no
  comparison against a secret. The frame rides the existing `Noise_IK_25519_ChaChaPoly_BLAKE2s`
  session through `SessionPump` with no change to handshake, key schedule or AEAD framing.
- **[Network & I/O]** No finding, with two hostile-daemon cases checked concretely. (a) *A
  `session_transition` storm*: each transition bumps one revision and issues at most one read, and
  `flatMapLatest` cancels the previous read before starting the next, so in-flight reads per
  conversation per collector never exceed one — no amplification, no unbounded growth of
  `pendingRequests`, and nothing at all while no thread is open. (b) *A daemon that never answers*:
  the read suspends, and is ended by collector cancellation, by the next revision, or by
  `failAllPending` on teardown. No new client, no timeout configuration, and no change to the inbound
  frame cap.
- **[Error messages, logs, telemetry]** SHOULD FIX — **the read flow's `catch` must discard the
  throwable, never log it, and Phase B must say so in a comment at that site.** The wrong-type
  `effective_effort` failure this ticket authors uses a static message naming only the key, which is
  what AC #2's "logs no payload content" asks for. But step 1's failure is thrown by
  kotlinx-serialization, whose `JsonDecodingException` / `MissingFieldException` messages can quote
  the offending input — so the payload-content guarantee rests entirely on that throwable being
  dropped rather than on the message being clean. A later ticket adding a well-meaning
  `.catch { Log.w(TAG, it) }` would leak daemon payload content to Logcat in one line. The comment is
  the durable guard; there is no build check for it in this repo.
- **[Concurrency]** No finding. `settingsRevision`'s increment is a genuine read-modify-write and runs
  **inside** the atomic `update {}` lambda, so the two producers (the inbound collector's transition
  bump and a caller's `refreshSessionSettings`) retry-merge rather than clobber — the
  `compactingConversations` posture, not `apiRetryByConversation`'s pure replace. Even the degenerate
  collapse is safe: two bumps folding into one still trigger a read that observes the newest state.
  The flow is **cold**, so no value is shared between collectors or screens; the repository launches no
  coroutine and owns no scope, so there is nothing to outlive a `ViewModel`. Background/foreground
  churn resolves through `LifecycleConnectionDriver` → teardown → `null` → fresh repository → fresh
  read, and nothing is persisted, so there is no partial state to recover.
- **[Threat model alignment]** Three mobile threats, addressed rather than deferred. *Malicious relay*
  (on-path, content-blind): it can drop, delay, reorder or replay frames; a dropped reply ends as
  `null`, and a reordered or replayed one correlates with a deferred that `sendAndAwaitReply`'s
  `finally` already removed, so it is a no-op — no plaintext leaks and no state moves. *Hostile daemon
  frame*: the decisive property is that **`session_settings` carries no `conversation_id`**, so the
  reading is routed strictly by the id the phone asked with; a daemon can lie about values (it always
  can) but structurally cannot steer a reading into a conversation the phone never asked about, and an
  uncorrelated or unsolicited `session_settings` matches no pending deferred and changes nothing.
  Adding a seventh type to the shared correlated-reply arm introduces no cross-verb confusion of
  consequence, because each waiter decodes against its own DTO and the required-key sets are disjoint —
  a settings payload cannot decode as a history page or vice versa, so a mismatched reply fails that
  one ask instead of succeeding quietly. *Token theft from disk*: nothing is written to disk.
- **[Threat model alignment]** The category's principal finding, and the reason the label is on this
  ticket: **mapping `permission_mode: ""` to `default` would tell the operator that approvals are
  enforced when the daemon said it does not know**, and deriving that from `yolo: false` would do the
  same from a field that also accompanies an unavailable confirmation. That is a security-relevant lie
  about a bypass posture, not a cosmetic default. The design keeps `""` a retained value on
  `SessionSettings.permissionMode` with no default manufactured anywhere in the decode, and
  `EffectiveEffort` keeps omission, explicit null and a value distinct for the same reason one step
  down. UI-side leakage of a rendered posture (screenshots, accessibility services, overlays) is
  OUT OF SCOPE here and belongs to #650, which renders it.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

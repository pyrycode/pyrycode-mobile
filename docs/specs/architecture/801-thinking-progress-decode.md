# #801 — decode `thinking_progress` as conversation-observable state

Decode-only data slice, split from #653. Rendering the reading in the status area is a sibling slice.

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `CompactingPayloadDto`,
  `ApiRetryPayloadDto.toStatus`, `UnrecognizedMessagePayloadDto` — the strict-DTO posture this slice
  clones, and the measured latitude note (kotlinx's tree decoder accepts a *quoted* primitive even at
  `isLenient = false`, so that is not a usable malformed probe).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeCompacting`,
  `observeModelMenu`, `ApiRetryStatus`, `ModelMenu` — where the defaulted seam and the co-located domain
  type go, and the two established "absent reading" idioms (`flowOf(false)` vs `flowOf(null)`).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` →
  `compactingConversations`, `apiRetryByConversation`, `modelMenusByConversation`, `onInbound`,
  `decodeCompacting`, `observeCompacting`, `TYPE_COMPACTING` — the projection field, the gated demux
  arm, the decode-or-drop helper, the cold projection, and the constant block.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` →
  `switchToLive`, `observeCompacting`, `observeModelMenu` — the one-line pass-through shape, and the
  `flatMapLatest` that makes a host switch drop the previous connection's projection.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` →
  `collectCompacting`, `compactingEnvelope`, `compactingProbe`, `turnEndEnvelope`,
  `sessionTransitionEnvelope`, `collectStall`, `stallEnvelope` — the helpers and the twelve-case
  `compacting` block this slice's cases sit beside.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt` →
  `RecordingConversationRepository`, `observeCompacting_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch`
  — the facade double and the connection-switch case that is this slice's reconnect proof.
- `docs/knowledge/features/compacting-state.md` — the #596 overview. Two lessons carried forward: the
  quoted-primitive probe is worthless, and a hostile daemon leaving per-conversation state stuck is a
  pre-existing family-wide property, not this arm's to fix.
- `docs/knowledge/features/remote-conversation-repository.md` § projections — confirms #593 chose a
  payload-carrying `Map` over #395's bare `Set` *because the wire carries a counter*. Same reasoning
  selects `Map` here.
- pyrycode `docs/protocol-mobile.md` § `thinking_progress` and `internal/protocol/interactive.go`
  (`ThinkingProgressPayload`) — the wire SSOT. Cited, not restated.

## Design source

N/A — data-layer decode slice with no UI surface. The visible reading is a sibling slice.

## Context

`thinking_progress` is claude's only mid-turn proof of life on the stream-json surface: during a long
assistant turn nothing else crosses the wire, so a phone showing "thinking" for three minutes cannot
otherwise separate a slow answer from a wedged session. Mobile drops the frame today — no DTO, no
demux arm.

This is the eighth conversation-level projection after `stall`, `queue_state`, `api_retry`,
`compacting`, `session_transition`, `unrecognized_message` and `model_list`. It introduces **no**
shared status-event abstraction, holding the line #593 drew and #596 restated: a family of similar
arms is the observation that would justify a framework, not a mandate to build one.

No ADR is warranted — this adds a member to an established family and decides nothing new at the
architecture level.

## Design

**No `LiveSessionEvent` subtype.** The frame opens and closes no turn, carries no `turn_id`, and the
turn's thinking state is already reported by `turn_state`. A new member of that sealed family would
force every `when (event)` in the app to grow an arm for something that is *state*, not an event —
the reason `compacting` and `api_retry` both stayed off it.

**1. Wire DTO** — `InteractivePayloadsDto.kt`'s neighbourhood, beside `CompactingPayloadDto`:

```kotlin
@Serializable
internal data class ThinkingProgressPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("estimated_tokens") val estimatedTokens: Long,
    @SerialName("estimated_tokens_delta") val estimatedTokensDelta: Long,
)
```

All three strict-required with no Kotlin default: the Go struct sets no `omitempty`, so
`estimated_tokens: 0` arrives **present-and-zero** rather than omitted, which is exactly what lets
every field stay required. A missing field, or one whose JSON shape cannot be read as its declared
type, fails the structural decode and drops the one envelope.

**`Long`, not `Int`**, for two independent reasons: the Go field is a 64-bit `int` on the daemon's
platforms, so an `Int` DTO would fail to decode a value the wire can legally express and drop the
frame; and this repo's existing token-valued fields (`SessionSettings.usedTokens` / `windowTokens`)
are already `Long`, so a consumer comparing a reading against the context window needs no widening
cast. The `QueuedMessage.id` KDoc records the pyrycode#720 trap for picking a narrower width than the
wire's — the same reasoning applies here.

**No `toX()` mapper**, following `CompactingPayloadDto`'s precedent and unlike
`ApiRetryPayloadDto.toStatus()`: that one exists to collapse four wire fields into a counter-carrying
domain type with real edge semantics. Here the two integer fields already *are* the domain shape and
nothing is narrowed, dropped or validated, so a mapper would be a ceremonial two-field copy. The
decoder constructs the domain value inline, as `decodeCompacting` does.

**2. Domain type** — in `ConversationRepository.kt` beside `ApiRetryStatus`:

```kotlin
data class ThinkingProgress(val estimatedTokens: Long, val estimatedTokensDelta: Long)
```

The routing `conversation_id` stays a **map key and never reaches this value** — the `ApiRetryStatus`
rule. Two `Long`s and no `String`: no daemon-supplied text of any kind can structurally reach a
consumer through this arm. `data` is load-bearing rather than cosmetic — structural equality is what
makes the projection's `distinctUntilChanged` behave.

**3. Interface seam** — `fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> =
flowOf(null)`.

`null` is **"no reading"**, deliberately not a `NotThinking` member of a sealed family: the wire has
no falling edge of its own, so a "not thinking" value would be a claim the daemon never makes. This is
`observeModelMenu`'s `flowOf(null)` posture rather than `observeCompacting`'s `flowOf(false)`, chosen
for that reason. The default is the cascade-escape valve — the Fake and every inline test double
inherit "no reading" and need no override.

**4. Projection** — `private val thinkingProgressByConversation = MutableStateFlow<Map<String,
ThinkingProgress>>(emptyMap())`.

A payload-carrying `Map`, #593/#791's shape, not #395/#596's membership `Set`: the wire carries a
reading, and a `Set` cannot represent one. Written only from the single `init` inbound collector, plus
the two clear hooks below — all on that one collector coroutine, so no writer races another.

**The write is a pure replace, and that is the mechanism, not a comment.** `it + (conversationId to
reading)` structurally cannot clamp, difference, or gate a reading: there is nowhere for a `max` to
live, because the prior value is never read. AC #2 is therefore enforced by the shape of the write
rather than by a rule an implementer must remember — which matters because the reading restarts near
zero at every inference-request boundary, several times inside one turn, and a merge-shaped write is
precisely where a well-meaning monotonicity guard would appear.

**5. Demux arm** — `TYPE_THINKING_PROGRESS = "thinking_progress"`, a new arm after `TYPE_COMPACTING`,
inside the same `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` gate as every sibling
(fail-closed, defence in depth). The arm:

- replaces this conversation's entry, leaving every other conversation untouched;
- **touches `stalledConversations` in neither direction.** Not raising one is the protocol's explicit
  rule — the rate bound means a quiet window is not a stall, and the PTY surface emits none at all.
  Not clearing one is the `compacting`/`unrecognized_message` rule for the same reason it exists
  there: clearing a stall here would hand a hostile daemon a lever to suppress the phone's stall
  indicator by emitting these frames;
- folds no thread row, opens/closes no turn, and never reaches `liveSessionEvents`;
- logs nothing — a logged `conversation_id` is a cross-conversation correlation leak.

**6. The two clears.** The reading has no falling edge of its own, so its clears live on other arms
(desktop's `threadTimeline.ts` `thinkingProgress` case says the same):

| Clear | Where | Mechanism |
|---|---|---|
| Turn end | the live-session arm's `is LiveSessionEvent.TurnEnd` branch | `it - event.conversationId`, beside the existing `finalizeAssistantTurn` |
| Session transition | the `TYPE_SESSION_TRANSITION` arm | `it - conversationId`, a fourth write beside the existing three |
| Reconnect | nothing to write | connection-scoped state; see below |

Removal of an absent key is a no-op and `StateFlow` conflates the equal map, so a turn end for a
conversation with no reading emits nothing. Both clears route by their own frame's decoded
conversation id, so neither can clear another conversation's reading.

**Reconnect needs no code and that is the design, not an omission.** The repository is
connection-scoped (#351) — a fresh instance per connection starts with an empty map — and the facade's
`flatMapLatest` drops the previous connection's projection outright on switch. The daemon re-asserts
no `thinking_progress` on connect, so a held reading would report the depth of a think that has since
finished; the existing lifecycle already guarantees it cannot be held.

**7. Facade pass-through** — one line, `switchToLive<ThinkingProgress?>(null) { … }`.

## State + concurrency model

One new `MutableStateFlow` on the connection-scoped repository, in-memory and never persisted. Single
writer (the one `init` inbound collector) for the write and both clears, so no edge races another. The
write is a pure replace and each clear is a key removal, so no read-modify-write window exists even in
principle; `MutableStateFlow.update {}` is used throughout for the family's memory-visibility posture.

No coroutine is launched by this slice — the projection rides the existing collector, and
`observeThinkingProgress` is a cold `.map { … }.distinctUntilChanged()` with no scope of its own.
Cancellation is therefore the existing collector's, cancelled with the connection's child scope.

`distinctUntilChanged` suppresses **only value-identical re-emissions** and is what keeps a frame for
another conversation from waking this collector. It is not a monotonicity filter and not a truthiness
check: a *lower* reading is a different `ThinkingProgress` value and reaches the collector, as does a
reading of `0`. A value-identical repeat leaves the held reading exactly what the daemon sent — the
value is unaltered, which is what AC #2 protects.

## Error handling

`decodeThinkingProgress(envelope): Pair<String, ThinkingProgress>?` — one `try` / `catch
(IllegalArgumentException)` (`SerializationException` ⊂ `IllegalArgumentException`) around the single
`MobileJson.decodeFromJsonElement`, returning `null` on any structural malformation. The one envelope
drops and the lone inbound collector survives. Structural malformation is the **only** null path:
there is no unrecognized *value* to reject, since both readings are plain `Long`s carried verbatim —
including negative ones, which are not this boundary's to reject (rewriting server data at decode
would diverge from every sibling's carry-verbatim posture, and there is no documented lower bound to
enforce). Nothing is logged, and the caught throwable is discarded rather than surfaced —
kotlinx-serialization can quote the offending input in its message.

No new UI-facing failure mode: absence is a normal resting state, not an error and not a spinner.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`), `runTest` + the existing `FakeSessionPump`. No Compose test
and no emulator rung: this slice has no operator-facing surface, so the definition-of-done e2e
paragraph does not fire — the live rung belongs to the rendering sibling.

`RemoteConversationRepositoryTest`, beside the `compacting` block, with new
`collectThinkingProgress` / `thinkingProgressEnvelope` / `thinkingProgressProbe` helpers mirroring
their `compacting` twins:

- a well-formed frame surfaces the reading on the seam (AC #1);
- malformed payloads — missing `conversation_id`, wrong-typed `conversation_id`, missing
  `estimated_tokens`, object- and array-shaped `estimated_tokens` — all drop, and a later valid frame
  still lands, proving the collector survived (AC #1). No quoted-primitive probe: measured to decode
  green, so it would prove nothing;
- `interactive` not negotiated, and a negotiated set carrying another token only, both block the
  decode (AC #1);
- a **falling** reading emits the lower value, not the prior maximum (AC #2);
- a `0` reading after a non-zero one emits `ThinkingProgress(0, 0)` — the inference-boundary restart,
  proving no truthiness check and no clamp (AC #2);
- a value-identical repeat leaves the held reading equal to the daemon's (AC #2);
- per-conversation isolation, and another conversation's frames do not re-emit this flow (AC #3);
- a `turn_end` clears the reading; a `session_transition` clears the reading; a `turn_end` naming
  **another** conversation leaves this reading standing (AC #3);
- a frame neither raises a stall nor clears a standing one, while its own reading still lands (AC #4).

`StableConversationRepositoryTest`, beside the `compacting` cases, extending
`RecordingConversationRepository` with a `thinkingProgress` flow and `pushThinkingProgress`:

- `null` while no connection is live;
- delegation plus the connection switch dropping the prior connection's reading — **this is AC #3's
  "not held across a reconnect" proof**, since the reconnect path is the facade's switch and not a
  clear in the arm.

## Open questions

1. Should a value-identical repeat produce a *new emission* rather than being deduped? Resolved at
   design time: no. `distinctUntilChanged` is load-bearing for AC #3's conversation scoping, and
   suppressing an emission whose value is identical alters no reading a consumer holds. Re-check if
   the rendering sibling needs frame arrival as a liveness tick — that would be a different signal
   (an arrival time), not this value, and belongs on its own seam rather than by removing the dedupe.
2. Does the rendering sibling need `estimatedTokensDelta` at all, given the deltas a client receives
   do not sum to the turn's total? Carried anyway: the wire sends it, dropping a field at the decode
   boundary is not this slice's call, and the consumer slice can ignore it.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. One explicit untrusted→trusted boundary,
  `decodeThinkingProgress(envelope): Pair<String, ThinkingProgress>?`, a single named function behind
  the already-authenticated Noise channel; downstream holds the decoded `ThinkingProgress`, never the
  DTO. This is the **narrowest** payload in the status-arm family: the entire surface is a routing id
  plus two integers, and the id stays a map key that is only ever compared for equality against the
  *caller's own* id — no daemon-authored text of any kind can structurally reach Compose through this
  arm, so § 1's "new inbound verb carrying text into the UI" rule has nothing to bind to. A daemon
  naming conversation A for a think happening on B is misattribution the wire offers no second
  identifier to cross-check; routing by the payload's own id is the family's uniform accepted posture
  (`api_retry`, `compacting`, `model_list`), not a regression introduced here.
- **[Wire width]** **Resolved in-plan before commit** (was the one finding that changed the design).
  The first draft declared both readings `Int`. The Go field is a 64-bit `int`, so a legal
  large value would fail the structural decode and silently drop a frame, and `Int` also diverges from
  this repo's existing token-valued fields. Now `Long` in both the DTO and the domain type — see
  § Design 1. The residual risk is nil: a value exceeding `Long` cannot be expressed by the producer.
- **[Tokens, secrets, credentials]** Not applicable, by design rather than by omission: this slice
  generates, stores, compares and logs no token, key or credential. The state it adds is in-memory and
  connection-scoped, so nothing reaches `SharedPreferences`, Keystore, disk or auto-backup, and there
  is no lifecycle to rotate, revoke or expire.
- **[File / storage operations]** Not applicable. No path is constructed, no file opened, nothing
  persisted — so no traversal, TOCTOU, storage-scope, encryption-at-rest, atomic-write or
  `allowBackup` question arises. The reading dies with the connection.
- **[Inter-process / Android attack surface]** Not applicable. No `Activity`/`Service`/`Receiver`, no
  intent filter, deep link, `PendingIntent`, content provider or WebView is added or touched, and the
  FCM wake path is untouched.
- **[Cryptographic primitives]** Not applicable. No RNG, no hashing, no key derivation, no key or
  nonce handling. The frame rides the existing `NoiseIkSession`; neither the handshake, the key
  schedule nor the AEAD framing is touched, and nothing is hand-rolled.
- **[Network & I/O]** No findings, and one positive property worth recording: the arm is **flood-safe
  by shape**. The write is a full replace keyed by conversation, so N frames for one conversation cost
  O(1) memory rather than O(N) — strictly better than the row-folding arms (`unrecognized_message`
  appends a row per frame), and unlike the live-session and modal arms this one never `tryEmit`s onto
  a `SharedFlow`, so it adds no buffer pressure. No new connection, timeout, TLS setting, URL or
  frame-size cap is introduced; inbound size stays bounded by `OkHttpRelayTransport`'s existing
  65519-byte frame contract, and the supervisor's backoff is untouched.
- **[Error messages, logs, telemetry]** No findings, load-bearing rather than incidental. **Nothing on
  this path logs**: not the payload, not the `conversation_id` (a logged one is a cross-conversation
  correlation leak), and not the caught throwable — kotlinx-serialization can quote the offending
  input in its own message, so the exception is discarded rather than surfaced or wrapped. No
  user-facing error text is produced at all: a malformed frame drops silently and absence is a normal
  resting state, so there is no message that could leak internal state.
- **[Concurrency]** No findings on the design; one implementer trap recorded as **SHOULD FIX** below.
  Single writer (the one `init` inbound collector) for the write and both clears, so the write and the
  two removals cannot race each other. The write is a pure replace and each clear a key removal, so no
  check-then-act window exists even in principle; `update {}` is used throughout for the family's
  memory-visibility posture. No coroutine is launched, so there is no scope-ownership, cancellation or
  mutex-ordering question, and nothing survives process death because nothing is persisted.
- **[Concurrency] SHOULD FIX — placement of the turn-end clear.** The clear must go **inside** the
  `is LiveSessionEvent.TurnEnd` branch of the live-session arm's `when (event)`. Hoisting it beside
  the pre-`when` stall clear, where it would read as a natural sibling, would clear the reading on
  **every** live event — an `assistant_delta`, a `tool_use` — rather than at turn end, making the
  reading near-useless while still passing a naive turn-end test. The verifier should check the
  placement, not just that a clear exists.
- **[Threat model alignment] Malicious / compromised relay — addressed.** Content-blind but on-path,
  so it can drop, delay, reorder or flood these frames. Dropping yields absence, which this design
  already treats as proving nothing (AC #4 — no stall may be inferred from a gap). Reordering yields a
  stale reading, bounded by the next turn end, session transition or reconnect. Flooding is O(1) in
  memory per the § Network finding. No plaintext leaks and nothing hangs.
- **[Threat model alignment] Hostile daemon frame — addressed.** Strict DTO, decode-or-drop, and the
  decoded value carries no text to render. The single new capability this arm grants an authenticated
  daemon is "set two integers under a conversation key it names" — the minimal surface available for a
  frame of this kind.
- **[Threat model alignment] OUT OF SCOPE — a stuck reading.** A hostile or crashed daemon can send
  one frame and never a clearing event, leaving the reading standing until the next turn end, session
  transition or reconnect. Deliberately undefended, and defending it would be *wrong here* rather than
  merely unnecessary: a client-side timeout is precisely the "infer something from a gap" that AC #4
  forbids, since the rate bound means a quiet window is not a stall. The shipped `stall` (#395) and
  `compacting` (#596) arms have the identical property — worse, in `stall`'s case, with no wire
  clearing edge at all — and have been in production without incident. **The rendering sibling must
  not block interaction on the reading and must tolerate a long-lived one**; carried into the PR's
  Lessons learned so the sibling ticket inherits it.
- **[Threat model alignment] OUT OF SCOPE — unbounded projection key growth.** An authenticated but
  hostile daemon sending frames for fabricated conversation ids grows the map without bound. This is
  pre-existing and identical across every sibling projection (`stalledConversations`,
  `queuedByConversation`, `apiRetryByConversation`, `modelMenusByConversation`, `threadByConversation`,
  `lastMessages`); this arm's per-key cost is among the smallest of the family (two `Long`s and no
  collection), and a reconnect discards the map. A projection-family fix, if ever warranted, is a
  separate ticket — the same verdict #596 recorded.
- **[Threat model alignment] OUT OF SCOPE — UI-side leakage.** A displayed token reading discloses
  that a conversation is active and roughly how hard claude is reasoning; screenshot, overlay and
  accessibility-eavesdropping exposure belong to the rendering sibling, exactly as #597 inherited them
  from #596. This slice renders nothing.
- **[Threat model alignment] Token theft from disk — not applicable.** Nothing is persisted.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

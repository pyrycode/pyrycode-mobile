# #871 — Decode the `resetting` wire event as observable reset-phase state

## Files read

- `../pyrycode/docs/protocol-mobile.md` § `resetting` — the wire contract: four fields, two rising edges before one falling edge, closed sets for `phase` / `handoff`, falling edge always arrives, nothing claude-authored. Cited, not restated.
- `app/src/main/java/de/pyryco/mobile/data/repository/CompactingProjection.kt` → `CompactingProjection` — the shape the ticket names as the model: own file, `apply` / `observe`, private decoder, drop idiom, no logging.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThinkingProgressProjection.kt` → `ThinkingProgressProjection.clear` — the payload-carrying `Map` projection with a `clear` the `session_transition` arm calls; the new projection needs both.
- `app/src/main/java/de/pyryco/mobile/data/repository/ApiRetryProjection.kt` → `ApiRetryProjection` — payload-carrying map sibling.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` (`TYPE_COMPACTING`, `TYPE_THINKING_PROGRESS`, `TYPE_SESSION_TRANSITION` arms), the projection fields, `observeCompacting` / `observeThinkingProgress` overrides, companion `TYPE_*` constants.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeCompacting` / `observeThinkingProgress` defaults; `ApiRetryStatus` / `ThinkingProgress` — where the new reading type sits.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `observeCompacting` / `observeUsageLimit` — the `switchToLive` pass-through.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `CompactingPayloadDto`, `SessionTransitionPayloadDto.toBoundary` / `toBoundaryReason` — the strict DTO posture and the closed-set mapper-drop idiom.
- `app/src/test/.../RemoteConversationRepositoryTest.kt` → the `#596` compacting block, `thinkingProgress_sessionTransitionClearsTheReading`, helpers `compactingEnvelope` / `compactingProbe` / `collectCompacting` / `sessionTransitionEnvelope` / `stallEnvelope` / `collectStall`.
- `app/src/test/.../StableConversationRepositoryTest.kt` → `observeCompacting_*` tests and `RecordingConversationRepository`.
- `docs/knowledge/features/compacting-state.md` — lessons carried: a quoted primitive is not a usable malformed probe (kotlinx's tree decoder accepts `"active":"true"`); a status frame must not clear a stall (hostile-daemon suppression lever); nothing logs the payload.

## Design source

N/A — decode-only data slice; nothing renders. The render consumer is a separate ticket.

## Context

The daemon emits `resetting` (pyrycode#2478) and mobile drops it: no DTO, no `onInbound` arm. This slice decodes it into a per-conversation reading the thread layer can observe, following the `compacting` decode (#596). No ADR warranted — this is the fifth instance of an established projection pattern.

## Design

### Domain type (in `ConversationRepository.kt`, beside `ApiRetryStatus`)

```kotlin
data class ResetStatus(val phase: Phase, val handoff: Handoff) {
    enum class Phase { WrappingUp, Restarting }
    enum class Handoff { Pending, Written, Skipped }
}
```

- No `String` field: the routing `conversation_id` stays a map key, and both wire strings are narrowed to enums at the decode boundary, so no daemon-supplied text reaches a consumer.
- `data` is load-bearing: structural equality drives the projection's `distinctUntilChanged`, so the `wrapping_up/pending` → `restarting/written` phase change re-emits while another conversation's frame does not.
- No "not resetting" member: absence is `null` at the flow, as `ThinkingProgress` does it.
- The two fields are carried independently. The contract states per-field closed sets, not a combination rule, so `wrapping_up` + `written` is not rejected here.

### Observe seam (on `ConversationRepository`)

`fun observeResetting(conversationId: String): Flow<ResetStatus?> = flowOf(null)` — `null` means no reset in progress. Default keeps the fake and every inline double untouched (the cascade-escape valve every status seam uses). Overridden by `RemoteConversationRepository` (delegates to the projection) and `StableConversationRepository` (`switchToLive<ResetStatus?>(null) { it.observeResetting(conversationId) }`).

### DTO + mapper (in `InteractivePayloads.kt`)

- `@Serializable internal data class ResettingPayloadDto(conversationId: String, active: Boolean, phase: String, handoff: String)` — all four strict-required, no defaults (the daemon never omits a key).
- `internal fun ResettingPayloadDto.toStatus(): ResetStatus?` — the **rising-edge** mapper: `null` when `phase` or `handoff` is outside its closed set (a mapper drop, the `toBoundary` posture). Two private `String.toResetPhase()` / `String.toResetHandoff()` `when` lookups hold the wire tokens.

### Projection (new `data/repository/ResettingProjection.kt`)

`internal class ResettingProjection` with:

- `private val resetByConversation = MutableStateFlow<Map<String, ResetStatus>>(emptyMap())`.
- `fun apply(envelope: Envelope)` — decode; on `active: true` map via `toStatus()` and **replace** the entry (a second rising edge is a phase change, never stacked); an unrecognised token drops the frame and leaves any prior reading standing; on `active: false` remove the key regardless of what the (meaningless) strings hold. The `if (active)` lives here, as in `CompactingProjection`: the falling edge must not depend on validating fields the contract says are meaningless.
- `fun clear(conversationId: String)` — remove the key; called from the `session_transition` arm.
- `fun observe(conversationId: String): Flow<ResetStatus?>` — `map { it[conversationId] }.distinctUntilChanged()`.
- `private fun decodeResetting(envelope: Envelope): Pair<String, ResetStatus?>?`, where the outer `null` is a drop (malformed or unrecognised rising edge) and an inner `null` is a falling edge. One `try`/`catch (IllegalArgumentException)`; the caught throwable is discarded.

### Repository wiring (`RemoteConversationRepository`)

- Field `private val resettingProjection = ResettingProjection()` beside its siblings.
- Companion `const val TYPE_RESETTING = "resetting"` with a KDoc citing the protocol section.
- `onInbound` arm `TYPE_RESETTING -> if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) resettingProjection.apply(envelope)`.
- In the `TYPE_SESSION_TRANSITION` arm, beside `thinkingProgressProjection.clear(conversationId)`, a fifth write `resettingProjection.clear(conversationId)` routed by the same decoded id.
- `override fun observeResetting(...) = resettingProjection.observe(conversationId)`.
- The arm touches no stall state (neither raises nor clears), folds no thread row, and emits nothing on `liveSessionEvents`.

## State + concurrency model

Single writer: all three writes (`apply`'s replace / remove, `clear`) run on the repository's one inbound collector coroutine, so edges never race; `MutableStateFlow.update` for the sibling memory-visibility posture. Cold `observe` projections, fan-out to any number of collectors. Connection-scoped: a fresh repository per connection starts empty; the facade's `flatMapLatest` drops the previous connection's reading on switch. No jobs, no dispatchers, no timeout (the contract guarantees the falling edge).

## Error handling

Malformed payload (missing key, wrong JSON shape) → `SerializationException` ⊂ `IllegalArgumentException` → `null` → envelope dropped, collector survives. Rising edge with an out-of-set token → mapper `null` → dropped. Nothing surfaces to the UI; nothing is logged (a logged `conversation_id` is a cross-conversation correlation leak — uniform with every sibling arm, which is why no lifecycle log is added).

## Testing strategy

Unit tests only (`runTest`, `FakeSessionPump`), in a `#871` block beside the `#596` compacting block in `RemoteConversationRepositoryTest`, plus two helpers (`resettingEnvelope`, `resettingProbe`, `collectResetting`):

- Absent until a frame; rising edge surfaces `ResetStatus(WrappingUp, Pending)`.
- Full sequence `wrapping_up/pending` → `restarting/written` → falling edge emits `[null, (WrappingUp,Pending), (Restarting,Written), null]` — replace, not stack.
- `restarting/skipped` surfaces `Skipped`.
- Falling edge clears only that conversation (c2's reading stands); falling edge with no prior rising edge emits nothing new.
- Falling edge with non-empty strings still clears.
- Malformed probes (missing field, number id, object `active`, array `phase`) and out-of-set tokens (`phase:"exploding"`, `handoff:"lost"`, empty strings on a rising edge) dropped; prior reading stands; later valid frame lands.
- Capability gate closed (empty set and other-token-only) → nothing decoded.
- `session_transition` for c1 clears c1's reading and not c2's.
- Stall: a `resetting` rising and falling edge leave an existing stall standing, and on a fresh conversation raise none.

`StableConversationRepositoryTest`: `observeResetting_whileAbsent_emitsNull` and `observeResetting_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch`, with a `pushResetting` on `RecordingConversationRepository`.

No androidTest and no rung-3 scenario: not an operator-facing flow in this slice (nothing renders); the render ticket owns that.

## Documentation handoff

Pending for the documentation stage: the ticket names none. The documentation stage may want a `resetting-state.md` overview beside `compacting-state.md` and a line in `remote-conversation-repository-thread-observables.md`.

## Open questions

- Name of the seam and type: `observeResetting` (matches the wire type, as `observeCompacting` does) and `ResetStatus`. Resolved here.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — one explicit boundary: `ResettingProjection`'s private decoder through the single `MobileJson`, behind the authenticated Noise channel and the `interactive` gate. Both wire strings are narrowed to enums by `toStatus()` before anything is stored; the routing id stays a map key. No daemon-supplied text, number or id can reach a consumer through `ResetStatus`. An out-of-set token is dropped, never carried as "unknown".
- [Trust boundaries] Considered: a hostile daemon sending a rising edge and never a falling edge leaves the reading stuck. The contract guarantees the falling edge; a `session_transition` and a reconnect both clear it. Same posture as `compacting`, deliberately undefended by a client timeout (the ticket says none is owed). The render ticket must not block interaction on it.
- [Trust boundaries] Considered: a `resetting` frame used to suppress the stall indicator. The arm touches no stall state in either direction; a test pins it.
- [Tokens] No findings — no token, key or credential is read, stored or generated.
- [File / storage] No findings — in-memory, connection-scoped state; no persistence, no paths.
- [Inter-process] No findings — no intent, deep link, pending intent, provider or WebView.
- [Crypto] No findings — no primitive touched; the frame arrives through the existing `NoiseIkSession`.
- [Network & I/O] No findings — frame size is bounded upstream by `OkHttpRelayTransport` before any parse; no new send path.
- [Logs] No findings — nothing on this path logs; the caught decode exception is discarded (kotlinx can quote input in its message).
- [Concurrency] No findings — single writer on the inbound collector; `update {}` for every write; no new coroutine.
- [Threat model] OUT OF SCOPE — unbounded key growth from fabricated conversation ids is the pre-existing projection-family posture (every sibling map); a family-wide fix would be its own ticket. UI-side leakage belongs to the render ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

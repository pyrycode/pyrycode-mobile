# #609 — Decode `unrecognized_message` and fold it into the thread

**Size:** S (PO's `size:s` held) · **Labels:** `security-sensitive` (§ Security review runs)

## Files to read first

| Path | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:302-360` | `SessionTransitionPayloadDto` + `toBoundary()` + `toBoundaryReason()` — **the structural twin.** The DTO KDoc's strict-required framing, the plain-`String`-not-enum decision, and the `else -> null` closed-set drop are all reused verbatim in shape. |
| `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:1-12` | Import block. `ThreadItem` and `Instant` are already imported; only `UnrecognizedSite` is new. |
| `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:182-213` | `CompactingPayloadDto` — the *most recent* decode-slice DTO; the "quoted primitive is not a strictness probe" latitude paragraph is the wording to mirror. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:517-539` | `TYPE_SESSION_TRANSITION` arm — the gated-arm comment shape and the `decodeX(envelope)?.let { (id, row) -> … }` destructuring. New arm goes immediately after this one. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:700-719` | `decodeSessionTransition` — the exact 4-line decoder body + KDoc to clone. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:884-898` | `appendSessionBoundary` — the pure end-append fold. **Read the no-dedup rationale carefully; ours differs (see § The fold).** |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:924-963` | `applyToolUse` — the `Clock.System.now()` locally-assembled-row clock precedent (`:950`). |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:247` | `private val requestId = AtomicLong(0)` — the counter idiom + the already-present `java.util.concurrent.atomic.AtomicLong` import (`:85`). |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:2110-2119` | `TYPE_COMPACTING` / `TYPE_SESSION_TRANSITION` companion constants — the KDoc-per-constant house style. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:288-331` | `ThreadItem.UnrecognizedMessage` + `UnrecognizedSite`. **The destination — do not modify.** Its KDoc names this ticket as producer of `id` and `occurredAt`. |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` | `MobileJson` config: `ignoreUnknownKeys = true`, **no** `isLenient`, **no** `coerceInputValues`. Governs what "malformed" means. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:4590-4700` | The `sessionTransition_*` test block — `FakeSessionPump` + `collectMessages` + `runCurrent()` harness. New tests sit alongside. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:5863-5872` | `threadShape()` — **already carries its `unrecognized:<site>` arm** (#608). Its KDoc says "#609 wires the decode"; update that sentence, nothing else. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/UnrecognizedMessageRow.kt:53-74` | The shipped render posture. Read-only context for why `raw` crosses this seam untouched. |

Wire SSOT (sibling checkout, read-only): `~/Workspace/Projects/pyrycode/docs/protocol-mobile.md:611-648` and `~/Workspace/Projects/pyrycode/internal/protocol/interactive.go:117-145`.

## Design source

N/A — data-layer decode and repository fold; no pixels change. The row's visual design shipped in #608 against `node-id=16-8` (Conversation Thread Screen). The visual-fidelity check is intentionally skipped for this slice.

## Context

The daemon now forwards a claude message its stream-json parser could not map as its own `unrecognized_message` frame instead of dropping it into a debug log the production daemon never prints. #608 landed the destination — `ThreadItem.UnrecognizedMessage`, `UnrecognizedSite`, and `UnrecognizedMessageRow` — but the repository's inbound demux still falls through to `else -> Unit`, so the frame is discarded. This slice is the decode arm and the fold that makes the row appear. Nothing here touches the UI.

## Verified wire facts

Confirmed against the Go struct, not just the prose — **no field carries `omitempty`**, so all five keys always serialize:

```go
type UnrecognizedMessagePayload struct {
	ConversationID string `json:"conversation_id"`
	Site           string `json:"site"`
	MessageType    string `json:"message_type"`
	Raw            string `json:"raw"`
	Truncated      bool   `json:"truncated"`
}
```

This is load-bearing twice over: `message_type: ""` arrives as a **present-and-empty** key (not a missing one), and `truncated: false` arrives as a **present** key. Both therefore decode cleanly under a strict, no-default DTO — which is what lets the DTO stay strict on all five fields with no Kotlin defaults anywhere.

## Design

Three seams, mirroring `session_transition` exactly, plus one thing that arm does not have: two client-stamped fields.

### 1. Inbound DTO + mapper — `data/network/InteractivePayloads.kt`

Appended at the end of the file, after `toBoundaryReason`. Add `import de.pyryco.mobile.data.repository.UnrecognizedSite`.

```kotlin
@Serializable
internal data class UnrecognizedMessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val site: String,
    @SerialName("message_type") val messageType: String,
    val raw: String,
    val truncated: Boolean,
)
```

All five **strict-required, no Kotlin default** — the `CompactingPayloadDto` posture. `site` is a plain `String`, **not** a `@Serializable` enum, so an unrecognized value is a *mapper* drop rather than a decode failure — the `SessionTransitionPayloadDto.reason` / `TurnStatePayloadDto.state` precedent.

Two functions:

- `internal fun UnrecognizedMessagePayloadDto.toRow(id: String, occurredAt: Instant): ThreadItem.UnrecognizedMessage?`
  Returns `null` iff `site` is outside the closed set; otherwise a total verbatim copy of `messageType` / `raw` / `truncated` plus the two injected client-owned values. Injecting non-wire values into a mapper is the established idiom here — `MessagePayloadDto.toMessage(envelope, sessionId)` already does it (`RemoteConversationRepository.kt:354`). Keeping `occurredAt` a **parameter** rather than calling `Clock.System.now()` inside the mapper is what keeps the mapper pure and deterministically testable.
- `private fun String.toUnrecognizedSite(): UnrecognizedSite?`
  Four arms — `line_type` → `LineType`, `assistant_block` → `AssistantBlock`, `user_block` → `UserBlock`, `undecodable` → `Undecodable` — plus `else -> null`. A byte-for-byte structural clone of `toBoundaryReason`.

**Do not** cross-validate the empty-`messageType` ⟺ `Undecodable` invariant. It is daemon-guaranteed; enforcing it here would defend an unobserved failure and could drop a valid frame. The desktop client made the same call (`parseUnrecognizedMessagePayload`, `inboundMessage.ts:515`) — the two clients agree deliberately.

**Do not** add a client-side length cap on `raw`. Two bounds already exist (daemon truncates at 16 KiB; `OkHttpRelayTransport.kt:217` enforces the 65519-byte frame contract — ~4× headroom), and a third would defend a failure that cannot reach this code.

### 2. Type constant + row-id counter + gated arm + decoder — `data/repository/RemoteConversationRepository.kt`

**Constant.** `const val TYPE_UNRECOGNIZED_MESSAGE = "unrecognized_message"` in the companion, immediately after `TYPE_SESSION_TRANSITION` (`:2119`), with a KDoc in the house style naming the payload shape and that it drives no turn lifecycle.

**Counter.** `private val unrecognizedRowId = AtomicLong(0)`, declared beside `requestId` (`:247`). `AtomicLong` for consistency with the existing counter, not because concurrency demands it — the inbound demux is a single collector. See § Stamping `id` for why this is sufficient.

**Arm.** A new `TYPE_UNRECOGNIZED_MESSAGE ->` branch inside `onInbound`'s `when`, placed directly after the `TYPE_SESSION_TRANSITION` arm. Body shape:

```kotlin
if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
    decodeUnrecognizedMessage(envelope)?.let { (conversationId, row) ->
        appendUnrecognizedMessage(conversationId, row)
    }
}
```

The comment above it must state what this arm deliberately does **not** do, matching how every sibling arm documents its non-actions — it does not `tryEmit` on `liveSessionEvents`, does not touch `stalledConversations` (in either direction), does not open/close/alter a turn, and does not write `updateCurrentSessionId`. That last omission is the one difference from the `session_transition` twin and is worth naming explicitly, because the twin's arm has two sibling writes and a reader will look for the second.

**Decoder.** `private fun decodeUnrecognizedMessage(envelope: Envelope): Pair<String, ThreadItem.UnrecognizedMessage>?` — the same 4-line body as `decodeSessionTransition`: decode the DTO through `MobileJson`, call `toRow(...)`, `?.let { dto.conversationId to it }`, all wrapped in one `try` / `catch (e: IllegalArgumentException)` returning `null` (`SerializationException ⊂ IllegalArgumentException`). This is where the counter is read and the clock is stamped:

- `id = "unrecognized-${unrecognizedRowId.incrementAndGet()}"`
- `occurredAt = Clock.System.now()`

Two null paths, both dropping exactly one envelope while the lone inbound collector survives: structural malformation (missing/wrong-typed field) and an unrecognized `site` value.

**Nothing in this arm logs any payload field** — uniform with every sibling arm, and here non-negotiable: `raw` is the most untrusted string the thread holds.

### 3. The fold

```kotlin
private fun appendUnrecognizedMessage(conversationId: String, row: ThreadItem.UnrecognizedMessage)
```

One atomic `threadByConversation.update { … }`, a pure end-append into `conversationId`'s slice — structurally identical to `appendSessionBoundary` (`:897`). Routing is strictly by the payload's own `conversation_id`, so a row cannot cross-route; an id no collector observes simply sits unread in the map.

**Write it as a separate function; do not generalise `appendSessionBoundary` into a shared `appendThreadItem`.** The two share an implementation but not a contract, and the difference is exactly the no-dedup rationale:

- `appendSessionBoundary` does not dedup because there is **nothing to dedup on** — the wire carries no row id.
- `appendUnrecognizedMessage` does not dedup because **dedup would destroy the signal**. How often this frame fires is the number that tells someone to go fix something; merging repeats hides it. The refusal is the point, not an oversight.

A shared helper would have to carry both rationales in one KDoc, and a later change to one contract would silently change the other. The KDoc must say this, so code-review reads the duplication as deliberate.

This cuts against the two nearest folds — `appendMessages` dedups by `message_id`, `applyToolUse` is idempotent on a repeat id. `appendSessionBoundary`'s pure end-append is the one to follow.

### Stamping `id` — the one genuinely new decision

The wire carries neither a message id nor a `turn_id`, and `ThreadScreen` keys the `LazyColumn` on `"unrecognized:${item.id}"` (`ThreadScreen.kt:290`), so **a duplicate `id` crashes the thread**. It cannot be derived from the payload or the instant: two byte-identical frames stamped in the same instant would collide, and AC 1 requires exactly that case to yield two distinguishable rows.

A per-repository monotonic counter is sufficient, and the reason is structural rather than incidental:

1. `unrecognizedRowId` is monotonic within one `RemoteConversationRepository` instance, so ids are globally unique across that instance — which implies unique per thread.
2. The repository is **connection-scoped** (#351): each connection gets a distinct instance with a fresh `threadByConversation`.
3. `StableConversationRepository.observeMessages` switches over `currentRepository` with `flatMapLatest` (`StableConversationRepository.kt:68`), so on reconnect the previous connection's projection is dropped outright. No reader ever observes rows from two instances merged, so a restarted counter cannot collide with a prior connection's ids.

Two consequences to write down so nobody "fixes" them later:

- **The counter carries no wire data.** The `id` is purely client-generated — no `conversation_id`, no payload hash. This keeps the same posture `ApiRetryStatus` documents: no daemon-supplied string reaches the UI through a field the UI treats as structural.
- **Ordinals may be skipped.** A frame that decodes structurally but is dropped by the unknown-`site` mapper still consumed its `incrementAndGet()`. This is fine and intentional: the invariant is *uniqueness*, not density. Do not restructure the decoder to make ordinals contiguous.

The `"unrecognized-"` prefix is for debuggability only. It is **not** load-bearing for collision-avoidance: ids are compared only within their own `ThreadItem` type, and `ThreadScreen` namespaces each type's key separately.

## State + concurrency model

No new coroutine, no new scope, no new flow. Everything runs on the existing single inbound collector, synchronously inside `onInbound`. The only shared-state write is one `MutableStateFlow.update {}` on the existing `threadByConversation` — CAS-atomic, no check-then-mutate. The row reaches the UI through the existing `threadProjection(conversationId)` cold slice (`:1226`), whose `distinctUntilChanged` means another conversation's frame does not re-emit this thread.

**No repository interface method is added**, so `StableConversationRepository`, `FakeConversationRepository`, and every inline test double compile unchanged.

## Error handling

| Failure | Where caught | Result |
|---|---|---|
| Missing / wrong-typed field (object where a string belongs, etc.) | `catch (IllegalArgumentException)` in `decodeUnrecognizedMessage` | That one frame drops; collector survives |
| `site` outside the closed set | `toUnrecognizedSite()` → `null` → `toRow()` → `null` | That one frame drops; collector survives |
| Frame arrives without `interactive` negotiated | `CAPABILITY_INTERACTIVE` gate in the arm | Never decoded, never surfaced (fail-closed) |
| Unknown extra fields on the payload | `MobileJson`'s `ignoreUnknownKeys = true` | Tolerated — forward-compat, by design |

Nothing surfaces to the user as an error: a dropped frame is silent, because the only thing we could say about it comes from untrusted content. **No branch logs any payload field.**

## Testing strategy

JVM unit tests only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"`), added to `RemoteConversationRepositoryTest.kt` alongside the `sessionTransition_*` block, using the existing `FakeSessionPump` + `collectMessages` + `runCurrent()` harness. Add one `unrecognizedMessageEnvelope(...)` builder next to `sessionTransitionEnvelope`.

No instrumented tests: nothing here is rendered, and #608's `ComposeTestRule` coverage already owns the row. No test-double changes.

Scenarios (AC 5 plus the routing and non-interference claims):

- **Short raw body folds a row** — a well-formed `line_type` frame yields exactly one `UnrecognizedMessage` on that conversation's thread, with `messageType` / `raw` / `truncated` carried verbatim and `occurredAt` non-null.
- **Interleaves in arrival order** — message → unrecognized → message yields `["m1", "unrecognized:LineType", "m2"]` via `threadShape`.
- **Truncated body** — `truncated: true` round-trips as `true`; a separate case asserts `truncated: false` round-trips as `false` (guarding against anyone defaulting it or reading it through truthiness).
- **Empty `message_type` on the `undecodable` site** — folds a row with `messageType == ""`; not dropped.
- **Back-to-back byte-identical repeats** — two rows, both present, ids **distinct** and neither merged nor collapsed. Assert on the id pair directly, not only on list length.
- **Unknown `site`** — a frame with `site: "wormhole"` folds nothing, and a subsequent well-formed frame on the same conversation still folds, proving the collector survived.
- **Malformed payload** — use a **genuinely wrong shape** (a missing required field, or an object/array where a string belongs). Measured on #593: kotlinx's tree decoder accepts quoted primitives (`{"truncated": "true"}`) even at `isLenient = false`, so a stringified boolean is a **known false green** and proves nothing. Same survival assertion as above.
- **Capability gate closed** — with `negotiatedCapabilities = { emptySet() }` and again with `{ setOf("something_else") }`, a well-formed frame folds nothing.
- **Never cross-routes** — a frame naming `c2` leaves `c1`'s thread empty and appears only in `c2`'s.
- **Alters no turn / no stream / no status** — assert that after a well-formed frame: `observeStall` for that conversation is unchanged (in particular, a pre-existing stall is **not** cleared), no `LiveSessionEvent` is emitted on `liveSessionEvents`, and `observeApiRetry` / `observeCompacting` are untouched.

Also update the one stale sentence in `threadShape`'s KDoc (`:5866`) — it currently reads "this repository cannot yet produce that row; #609 wires the decode." Nothing else in that helper changes.

## Scope carve-outs

- **No e2e work.** #586 owns injecting a synthetic unrecognized frame on the deterministic rung as its non-vacuity demonstration, and is natively blocked by this ticket. Do not extend `docs/e2e-interactive-stream.md` here.
- **No UI changes.** Every visible decision shipped in #608.
- **No knowledge-base doc.** `docs/knowledge/codebase/609.md` is the documentation phase's deliverable, written after merge — not a developer AC.

## Open questions

None blocking. One thing the developer should confirm at implementation time rather than assume: that `threadShape`'s existing `unrecognized:<site>` arm needs no signature change (it should not — the arm was written against the shipped type).

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The boundary is explicit and singular: `decodeUnrecognizedMessage` is the only place the untrusted `Envelope.payload` becomes a typed value, and the untrusted DTO never escapes it — the function returns `Pair<String, ThreadItem.UnrecognizedMessage>`, matching every sibling decoder's already-trusted-primitives posture (`decodeCompacting`'s KDoc names this rule explicitly). Downstream code holds only the domain type, whose KDoc (`ConversationRepository.kt:288-313`) states in bold that its strings are the most untrusted the thread holds, and the render layer's obligations were discharged in #608 (`UnrecognizedMessageRow.kt:58-70`: inert `Text`, never `MarkdownText`, no `SelectionContainer`, no persistence, no logging). The one type-system gap is honest and documented: `raw` and `messageType` are plain `String`s with no taint marker — a `data class` field cannot carry that signal in Kotlin, so the KDoc carries it instead. That is the pre-existing convention for all six interactive arms, not something this slice weakens.
- **[Trust boundaries — closed-set narrowing]** No findings, and this is a positive property worth recording: `site` is the one payload field that gets *narrowed* rather than copied. The `else -> null` in `toUnrecognizedSite` means only four client-owned enum constants can reach the UI's label lookup, so the render surface for daemon-supplied strings is exactly two fields (`raw`, `messageType`) and not three. A hostile daemon cannot inject a fifth site label.
- **[Tokens, secrets, credentials]** N/A by design. This slice reads no token, writes no credential, and touches no key store. The one identifier it *creates* — `id` — is deliberately not security-relevant: it is a monotonic ordinal, not a secret, carries no wire data, and is never compared against anything attacker-controlled. `AtomicLong` (not `SecureRandom`) is therefore the correct choice, and the spec states the non-secret framing so nobody later "hardens" it into a random id and breaks the determinism the tests rely on. This mirrors `QueuedMessage.id`'s documented "monotonic ordinal, not a secret" posture.
- **[File / storage operations]** N/A. Nothing is persisted. `threadByConversation` is process memory on a connection-scoped object; there is no DataStore write, no file I/O, no cache. Worth stating rather than skipping, because `raw` is precisely the kind of content that would need `EncryptedFile` if anyone ever persisted it — #608 already forbids it reaching `rememberSaveable`, and this slice adds no persistence path that would reopen the question.
- **[Inter-process / Android attack surface]** N/A. No `Activity`, `Service`, `BroadcastReceiver`, intent filter, deep link, `PendingIntent`, content provider, or `WebView` is added or modified. The frame arrives only over the established authenticated Noise channel; there is no new way to inject one from another app on the device.
- **[Cryptographic primitives]** N/A. No RNG, no hashing, no key derivation, no comparison against a secret. The frame rides the existing `Noise_IK` transport, whose primitives this slice neither selects nor configures.
- **[Network & I/O]** No findings. No new connection, client, or timeout configuration; the frame arrives on the existing `SessionPump` inbound flow. The `raw` size question is where a finding would live, and it resolves to *deliberately no client-side cap*: the daemon truncates at construction to 16 KiB and `OkHttpRelayTransport.kt:217` enforces the 65519-byte frame contract, so an oversized `raw` cannot reach this code — a third bound would defend an unreachable failure and is explicitly refused in § Design. The remaining exposure is memory growth from a high-frequency frame, addressed under Concurrency below.
- **[Error messages, logs, telemetry]** No findings, and this is the category with the sharpest requirement, so it is stated as a hard rule in three places (the arm, the decoder, and AC 4): **no branch logs any payload field.** That covers `raw` and `messageType` (untrusted model-adjacent JSON) and `conversation_id` (a logged one is a cross-conversation correlation leak, the reason `decodeCompacting` gives). Nothing user-facing surfaces on a drop, so no error string can leak decode internals. This matches the silent-drop posture of all six sibling arms; no new logging seam is introduced, so there is nothing for a crash reporter to pick up either.
- **[Concurrency]** No findings. No coroutine is launched, so no scope-ownership or cancellation question arises; all work is synchronous on the existing single inbound collector. The one shared-state mutation uses `MutableStateFlow.update {}` (CAS, no check-then-mutate), so the TOCTOU category does not apply. `AtomicLong.incrementAndGet()` is safe regardless of collector count. Nothing is written to disk, so process death mid-write cannot leave partial state. The flow shape is unchanged — `threadProjection` is a cold per-conversation slice with `distinctUntilChanged`, so a frame for one conversation cannot leak into another screen's collector.
- **[Concurrency — unbounded growth]** SHOULD FIX (accepted, not gating). A daemon emitting `unrecognized_message` at high frequency grows `threadByConversation[conversationId]` without bound, since the fold refuses dedup by design and no cap is applied. Three reasons this stays as designed: (a) the no-dedup behaviour is the feature — coalescing repeats would hide the frequency signal the frame exists to carry; (b) the exposure is not new — `appendSessionBoundary` and `appendMessages` are equally unbounded, so a cap here would be an inconsistent partial defence; (c) an authenticated paired daemon is already fully trusted for liveness, and the bound is process memory on a connection-scoped object that a reconnect discards. Recording it rather than fixing it is the [Evidence-Based Fix Selection] call: no such flood has been observed, and a thread-length cap is a repository-wide concern, not this slice's.
- **[Threat model alignment]** No findings. The mobile-relevant threat this frame introduces is *hostile-daemon content injection into the thread*, and it is answered at two layers this slice preserves: the capability gate (a phone that never negotiated `interactive` ignores a spurious frame — fail-closed, defence in depth against a daemon ignoring the server-side fan-out gate) and #608's inert render. Two mobile-specific threats from the checklist are named and deferred deliberately: **screenshot leakage** of an expanded `raw` blob and **accessibility-service eavesdropping** on it. Both were considered and rejected in #608 — `FLAG_SECURE` is a per-window flag, so applying it for one diagnostic row would harden the entire thread screen as a side effect (`UnrecognizedMessageRow.kt:70-74`). That decision is #608's to revisit, not this slice's; this slice adds no new surface for either. The frame carries no turn lifecycle, so it also cannot be used to wedge a conversation by opening a turn that never ends — the daemon-side rationale the ticket records, which the client honours by touching no turn state.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-08-01

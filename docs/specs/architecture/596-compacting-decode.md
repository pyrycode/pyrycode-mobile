# #596 — Decode the `compacting` wire event as a thread-observable compaction state

**Size:** S (4 production files modified, 0 new, ~95 production lines) · **Label:** `security-sensitive`

## Design source

N/A — pure data-layer decode slice; no UI surface. The visible reaction (a compaction banner in place
of a frozen-looking spinner) is the natively-blocked sibling UI slice **#597**, which owns the Figma
anchor and the visual-fidelity check.

## Files to read first

This design is a structural clone of the shipped `stall` arm (#395), with the `api_retry` arm (#593)
as its provenance relative. Read these before writing anything — every decision below is anchored to
a line range here.

| Path | Extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:185-196` | `stalledConversations` field + its KDoc — the **exact** shape and doc idiom for `compactingConversations` (membership `Set`, single writer, atomic `update`, connection-scoped, "transient right-now condition") |
| `…/RemoteConversationRepository.kt:427-439` | `TYPE_STALL` demux arm — the arm to clone: capability-gate rationale wording, decode-or-drop, one `update`. Note it has **no falling edge** to branch on; § Design 4 is where this ticket diverges |
| `…/RemoteConversationRepository.kt:456-474` | `TYPE_API_RETRY` arm — the "folds no thread row / does **not** clear a stall" comment wording, and the same-daemon-PR provenance. Its "deliberately no branch on `active`" note does **not** transfer (§ Design 4) |
| `…/RemoteConversationRepository.kt:582-596` | `decodeStall` — the `try`/`catch (IllegalArgumentException)` drop idiom, and the "nothing here logs the payload" posture. `decodeQueueState`/`decodeApiRetry` (`:598-634`) show the `Pair` return shape to copy |
| `…/RemoteConversationRepository.kt:1173-1183` | `observeStall` — the `.map { conversationId in it }.distinctUntilChanged()` cold-projection idiom + the KDoc explaining why a `StateFlow` gives every late / `flatMapLatest` collector a current value |
| `…/RemoteConversationRepository.kt:2000-2011` | KDoc'd `TYPE_STALL` / `TYPE_QUEUE_STATE` companion consts — the one-paragraph "Capability-gated … event: `{fields}` (#N, pyrycode#N)" const-doc format. `TYPE_API_RETRY` (`:2013-2020`) is the same-PR sibling |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:32-70` | `observeStall` / `observeQueue` / `observeApiRetry` declarations — the **defaulted-method lever** that avoids the fake + inline-double cascade. Copy the "Default … implementations without an interactive wire inherit … and need no override" wording |
| `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:49-80` | `switchToLive` + the `observeStall` override (`:74`) — the one-line facade delegation with a `false` seed. `observeStall`'s single-line form is the target shape |
| `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:72-84` | `StallPayloadDto` — where the new DTO goes, the strict-required-field fail-closed posture, and the "no `toEvent()` mapper: this is *state*, not a streaming event" note that also applies here |
| `…/InteractivePayloads.kt:126-180` | `ApiRetryPayloadDto` + `toStatus()` — the same-daemon-PR twin. Read its KDoc for the **measured quoted-primitive decode latitude** (§ Testing). Its mapper is the piece this ticket deliberately does *not* clone (§ Design 2) |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:5745-5770` | `collectStall` + `stallEnvelope` helpers — clone both |
| `…/RemoteConversationRepositoryTest.kt:3386-3578` | The 11 `stall_*` tests — the scenario set to mirror (onset, isolation, `distinctUntilChanged`, idempotent re-onset, malformed drop, both capability-gate tests) |
| `…/RemoteConversationRepositoryTest.kt:3944-3997` | `apiRetry_malformed_droppedCollectorSurvives` — the multi-probe malformed test and its in-line warning about which probes are **not** usable |
| `…/RemoteConversationRepositoryTest.kt:4027-4049` | `apiRetry_inertTowardNeighbours_keepsStallAndFoldsNoThreadRow` — clone verbatim in shape (§ Testing) |
| `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt:225-260, 302-330` | The `observeStall_*` and `observeApiRetry_*` facade test pairs — clone one pair |
| `…/StableConversationRepositoryTest.kt:382-415` | The inline `RecordingConversationRepository` stub with `pushStall` / `pushQueue` / `pushApiRetry` — add a `pushCompacting` alongside |
| `docs/specs/architecture/593-api-retry-decode.md` | The twin spec. § Open questions carries a directive addressed to this ticket's architect (§ Non-goals below) |

Server SSOT, already read and reconciled into § Wire contract below — **do not re-fetch**: pyrycode
`internal/protocol/interactive.go:106-115` (`CompactingPayload{ConversationID, Active}`, no
`omitempty`) and `docs/protocol-mobile.md:597-611` (§ `compacting`). Both confirm the two-field
banner-only shape the ticket body states.

## Context

When claude auto-compacts, it goes silent on the content channel for tens of seconds. The
`compacting` frame is the daemon's **only** signal that something is happening rather than hung; today
the mobile client has no handler, so the frame is dropped and a remote head looks frozen.

This ticket is the **data half only**: decode the frame off the shared inbound stream into
per-conversation state the thread layer can observe through the repository facade. **#597** renders it.

It shipped daemon-side in pyrycode#1074 (PR pyrycode#1160, merged 2026-07-21) as the banner-only twin
of `api_retry`. The mobile `api_retry` half (#593) landed in PR #595 (`022c0b8`) and is on `main`, so
its arm is in-tree precedent to read — but the shape to clone is the **simpler** `stall` arm, because
compaction carries **no counter**. `api_retry` needed a payload-carrying `Map` and a three-member
sealed type purely to represent `attempt N/M`; with nothing to carry, this arm is a `Boolean` over a
membership `Set` and needs no new domain type at all.

### Non-goals (from #593's spec, § Open questions)

`compacting` is the **third** conversation-level status arm after `stall` and `api_retry` — exactly
where a shared status-event abstraction starts looking justified. It is out of scope, per the
directive #593's spec left for this ticket: *"#583 (`compacting`) clones this arm, it does not extend
it. If #583's architect finds itself wanting a shared status-event abstraction, that is a
third-sample decision, not this ticket's."* **Do not introduce** a registry, a shared base type, a
generic `observeStatus(kind)`, or a common `StatusPayloadDto`. Three near-identical arms is the
observation that would justify such a ticket; making that call is PO's, on evidence, later.

### Name collision — do not "fix" the two `turn_state` tests

`"compacting"` already appears in this repo as a `turn_state` **phase value**, in
`RemoteConversationRepositoryTest.kt` — `liveEvents_unrecognizedTurnState_droppedNextSurvives`
(`:3290`) and `stall_notClearedByUnrecognizedOrMalformedLiveEvent` (`:3540`) — where it is pushed as a
deliberately *unrecognized* phase and asserted to be dropped.

Those tests stay correct and **must not change**. The server's `turn_state.state` is a closed set
(`thinking` / `responding` / `idle`); `compacting` is not and never was a member. Compaction arrives
as its own top-level envelope `type`, dispatched by a different `when` arm, so adding `TYPE_COMPACTING`
cannot affect them. Mapping a `compacting` *turn phase* would invent a frame the daemon does not send.

## Wire contract (verified, reconciled)

Envelope `type` is `compacting`, direction binary → phone, gated on the negotiated `interactive`
capability (the same gate as `assistant_delta` / `turn_state` / `stall` / `api_retry`). Payload:

| Field | Type | Meaning |
|---|---|---|
| `conversation_id` | string | The conversation in claude's auto-compaction pass. Routing key only. |
| `active` | bool | Rising edge (`true`, compaction started) / falling edge (`false`, finished — clear the indicator). |

Both fields are **always present** (the Go package's no-`omitempty` rule, so `active: false` always
serializes), so both DTO fields are strict-required, non-null.

Semantics the design must honour, and where each is handled:

- **Banner-only.** tui-driver streams no compaction progress, so there is deliberately no counter,
  percent, or ETA. There is nothing to carry beyond the id and the edge bool. → § Design 1/2.
- **Explicit falling edge.** `active: false` clears; the state must never stick after it. This is the
  one place `stall`'s shape does not transfer — `stall` is onset-only and inferred-clear. → § Design 4.
- **Coarse and conversation-level, not turn-scoped** — no `turn_id`. It does not open, close, or alter
  a turn, and it is **not** forward progress, so it must not clear an active stall or fold a thread
  row. → § Design 4.
- **No server-supplied free text.** `conversation_id` and a bool are the whole surface — this arm
  carries strictly less daemon-supplied data than `api_retry` (no ints) or `queue_state` (no user
  text). → § Security review 1.

## Design

Four production files, all modified, zero new. **No new domain type and no mapper function** — the
observable is a `Boolean`. In dependency order:

### 1. No new domain type — deliberately

`api_retry` needed `ApiRetryStatus` (`ConversationRepository.kt:280-300`) only because a `Set<String>`
cannot represent a counter. Compaction is a bare on/off edge, so the observable is
`Flow<Boolean>` — exactly `observeStall`'s shape. Adding a two-member `CompactingStatus` sealed type
would be a `Boolean` with extra ceremony and would drag the file's type count up for nothing.

### 2. `CompactingPayloadDto` — the decode DTO, with no mapper

New in `data/network/InteractivePayloads.kt`, next to `StallPayloadDto` (`:72-84`). **Inbound** v2
payload DTOs live together in that file; the per-payload `*PayloadDto.kt` files in the same package
are all *outbound request* payloads and are not the pattern for a decode slice (a new standalone file
would also collide with the ktlint single-class-filename rule).

Signature: an `internal @Serializable data class` with two strict-required non-null fields —
`@SerialName("conversation_id") conversationId: String`, `active: Boolean`. Nothing is nullable and
nothing is defaulted: unlike `QueueStatePayloadDto.queued` this payload has no documented nullable
latitude, so strictness is uniform. A missing or wrong-shaped field fails the structural decode with a
`SerializationException` — the fail-closed posture for an untrusted boundary (AC #5).

**No `toX()` mapper.** `api_retry` needed `toStatus()` because the four wire fields had to collapse
into a three-member domain type, including discarding the stale counter on the falling edge. Here the
two wire fields *are* the domain shape (`String` + `Boolean`), so a mapper would be a ceremonial
identity function. Follow `StallPayloadDto`'s precedent, which also has none, and say so in the KDoc so
a developer cloning the `api_retry` arm does not add one.

KDoc must state: the wire SSOT (`interactive.go` `CompactingPayload` + `protocol-mobile.md §
compacting`), decode-only (the phone never sends one), always decode through `MobileJson`, that
`active` is the edge with a **real falling edge** unlike `stall`, and that it is **banner-only** — no
counter exists to carry, which is why there is no mapper and no domain type.

### 3. `compactingConversations` projection + `observeCompacting`

New private field in `RemoteConversationRepository`, a structural sibling of `stalledConversations`
(`:196`):

```kotlin
private val compactingConversations = MutableStateFlow<Set<String>>(emptySet())
```

KDoc must state, mirroring `:185-195`: membership = compacting; written **only** from the single
`init` inbound collector, so the rising and falling edges never race; the atomic
`MutableStateFlow.update` matches the sibling projections' memory-visibility posture — and here
`update {}` is **load-bearing rather than stylistic**, because unlike `api_retry`'s pure replace this
is a genuine read-modify-write on the set (`it + id` / `it - id`); connection-scoped in-memory state,
so a fresh repository per connection (#351) starts empty and a compaction state never survives a
reconnect (AC #4); a transient "right now" condition, not durable state. Also note the difference
from `stalledConversations` worth documenting: removal is driven by an **explicit wire falling edge**,
not inferred from forward progress.

The observer, cloning `observeStall` (`:1182-1183`):

```kotlin
override fun observeCompacting(conversationId: String): Flow<Boolean> =
    compactingConversations.map { conversationId in it }.distinctUntilChanged()
```

Two load-bearing details:

- Membership over an empty set gives **not-compacting-until-the-first-frame** (AC #4), with no
  `?: false` default needed — the `Set` model makes "absent" and "not compacting" the same thing by
  construction, which is tidier than `api_retry`'s stored-`NotRetrying` arrangement.
- `distinctUntilChanged` is correct **and** unproblematic here: it suppresses only value-identical
  re-emissions, so another conversation's frame does not re-emit this flow, and a repeated rising edge
  is genuinely nothing new. **#593's no-dedup hazard does not transfer** — that hazard existed only
  because a climbing counter had to survive `distinctUntilChanged`; a bool has no intermediate values
  to collapse.

Cold, per-collector `.map{}` over a shared `StateFlow` — not a hot `SharedFlow`. A `StateFlow` always
has a current value, so every collector, including a `flatMapLatest` re-subscription through the
facade, receives the current state on subscription.

### 4. The `TYPE_COMPACTING` demux arm — one write, branching on the edge

New `const val TYPE_COMPACTING = "compacting"` in the companion, KDoc'd in the `TYPE_STALL` /
`TYPE_API_RETRY` format (`:2000-2020`): capability-gated status event `{conversation_id, active}`
(#596, pyrycode#1074), banner-only, `active` is the edge, **has** a clearing edge unlike `TYPE_STALL`.
There is no type-registration step and no `TYPE_*` drift-detector test in this repo, so the const
needs no registration anywhere — adding the arm is the whole story.

New arm in `onInbound`'s `when`, cloning the `TYPE_STALL` arm's structure (`:427-439`): the capability
gate, `decodeCompacting(envelope)?.let`, and a single `update`:

```kotlin
compactingConversations.update { if (active) it + conversationId else it - conversationId }
```

**The `if (active)` belongs here, and this is not #593's duplicated-rule smell.** That arm pushed the
edge semantics into `toStatus()` so the demux would not encode the same rule twice. Here there is no
mapper to own it (§ Design 2) and the membership transition *is* the edge, expressed exactly once. The
alternative — cloning `api_retry`'s `Map<String, Boolean>` for a branch-free `it + (id to active)` —
would store a wire field the payload does not have a domain reason to keep, keep `false` entries alive
forever, and diverge from the in-tree precedent for a boolean-per-conversation projection.
`it - conversationId` on an absent id is a no-op, so a falling edge with no prior rising edge is
harmlessly inert, and a repeated rising edge is an idempotent `Set` add.

AC-relevant inertness is satisfied **structurally, by that being the arm's only statement**:

- Folds no thread row: it never touches `threadByConversation`.
- Does not clear an active stall: the `stalledConversations.update { it - … }` clearing lives only in
  the `decodeLiveSessionEvent` arm (`:408`), because compaction is *not* turn forward-progress.
  Clearing a stall here would be a real bug — and a hostile-daemon lever (§ Security review 9).
- Does not open, close, or alter a turn: it never touches turn lifecycle state, and the payload
  carries no `turn_id` to key one by.
- Does not surface on `liveSessionEvents`: compaction is transient "right now" state, not one of the
  five streaming render events — the same reasoning that kept `stall` off that stream.

Gate wording follows the siblings: a non-interactive phone never decodes a spurious `compacting` from
a buggy or hostile daemon that ignored the server-side fan-out gate (fail-closed, defence in depth).
Drop silently — nothing here logs the payload.

### 5. `decodeCompacting` — the trust boundary

New private method, a `decodeQueueState`-shaped clone (`:608-614`) of `decodeStall`'s body:

```kotlin
private fun decodeCompacting(envelope: Envelope): Pair<String, Boolean>?
```

Decodes the untrusted `Envelope.payload` through the single configured `MobileJson` (never a default
`Json`) and returns `conversationId to active`. Returning a `Pair` of already-trusted primitives —
rather than the DTO — keeps the untrusted wire type from escaping the boundary, matching every sibling
decoder. The whole body is one `try`/`catch (IllegalArgumentException)`
(`SerializationException ⊂ IllegalArgumentException`), so a malformed payload yields `null`, dropping
that one envelope while the lone inbound collector survives (AC #5). Structural malformation is the
only null path — there is no unrecognized *value* to reject (`active` is a bool). Nothing here logs
the payload.

`catch (IllegalArgumentException)` is *not* the `catch (IllegalStateException)` /
`CancellationException` trap: `CancellationException` extends `IllegalStateException`, a sibling of
`IllegalArgumentException`, and this is a non-suspend function with no suspension point to cancel.

### 6. Interface default + facade delegation

`ConversationRepository` (`:32-70` idiom) gains one defaulted method, placed after `observeApiRetry`:

```kotlin
fun observeCompacting(conversationId: String): Flow<Boolean> = flowOf(false)
```

The default is the **cascade-avoidance lever**: `FakeConversationRepository` and every inline test
double inherit "never compacting" and need **no override**, exactly as with
`observeStall` / `observeQueue` / `observeApiRetry`. Only the two real implementors change — this is
the second half of AC #4, and it is what keeps this ticket free of an edit cascade. KDoc should state
what the flow means, that `false` holds until the wire says otherwise, that a falling edge returns to
`false`, and that the thread layer observes it to distinguish compaction from a frozen spinner (#597).

`StableConversationRepository` (`:74` idiom) gains one override, matching `observeStall`'s single-line
form:

```kotlin
override fun observeCompacting(conversationId: String): Flow<Boolean> = switchToLive(false) { it.observeCompacting(conversationId) }
```

This is the rest of AC #4: `switchToLive`'s `whenAbsent` reads "not compacting" while no connection is
live, and its `flatMapLatest` cancels the prior inner flow on every connection change, so combined
with #351's fresh-repository-per-connection a compaction state structurally cannot survive a
reconnect. (Watch the 120-char line limit — spotless may keep or wrap this; follow whatever
`spotlessApply` produces.)

## State + concurrency model

- **No new coroutine, scope, dispatcher, mutex, or channel.** The arm runs inside the existing single
  `init` inbound collector on the repository's injected scope.
- **Single writer.** Only that collector writes `compactingConversations`, so the rising and falling
  edges cannot race. The write is a read-modify-write on the set, so it **must** go through
  `update {}` (a CAS loop), never `.value = …`.
- **Cannot back-pressure the connection.** `MutableStateFlow.update` is non-suspending, so a slow or
  absent `observeCompacting` collector can never stall the shared inbound collector's
  `conversations` / `message` / `ack` processing. No `tryEmit`, no buffer, no `DROP_OLDEST` question
  (this is held state, not an event stream).
- **Hot state, cold reads.** One shared `MutableStateFlow` (hot, connection-scoped) fanned out to
  unlimited cold per-collector projections.
- **Lifecycle.** Connection-scoped: born empty with the repository, discarded with it, never
  persisted — so a compaction state cannot survive process death or leak into a later session.

## Error handling

| Failure mode | Layer | Result |
|---|---|---|
| Missing / wrong-shaped field (`conversation_id` absent or a number, `active` absent or an object) | `MobileJson` structural decode | `SerializationException` → caught as `IllegalArgumentException` → that one envelope dropped, collector survives (AC #5) |
| `compacting` without the negotiated `interactive` capability | demux gate | Never decoded, fail-closed |
| Falling edge for a conversation that was never compacting | demux arm | `it - id` on an absent id is a no-op; `distinctUntilChanged` emits nothing |
| Repeated rising edge | demux arm | Idempotent `Set` add; no new emission |
| Frame for a conversation nobody observes | projection | Sits unread in the set; no other conversation's state is disturbed (AC #3) |
| No live connection | facade `switchToLive` | `false` (AC #4) |

Nothing in this arm throws to a caller, and nothing logs — uniform with every sibling `onInbound` arm.
The UI surface (banner shape, placement, precedence against the thinking indicator and the retry
status) is **#597's** decision; this slice surfaces state only.

## Testing strategy

Plain-JVM unit tests only (`ANDROID_HOME=~/Library/Android/sdk ./gradlew testDebugUnitTest`) — no
instrumented tests, no `ComposeTestRule`, no Robolectric (nothing here touches `android.util.Log`, so
the "not mocked" trap does not apply). Test-first per the repo convention.

Drive the pump with `runCurrent()`, **not** `advanceUntilIdle()` — the established idiom for
remote-repo pump→demux→project→emit fan-out tests; `advanceUntilIdle()` fails to deliver the Channel
item because there are no timers.

Every scenario below seeds `negotiatedCapabilities = { setOf("interactive") }` unless stated, and
asserts the **full emission list** (the `stall_*` block's style), so the leading `false` seed also
carries the never-received half of AC #4 in every test.

### `RemoteConversationRepositoryTest.kt`

Two new helpers cloned from `collectStall` / `stallEnvelope` (`:5745-5770`): a
`collectCompacting(repo, conversationId)` returning a `MutableList<Boolean>`, and a
`compactingEnvelope(conversationId, active, id = 1L)` building the two-field payload. Place the new
test block after the `apiRetry_*` block (`:4049`), with a `// ---- #596: …` banner comment matching
the neighbours.

- **Rising edge flips on** — one `active: true` → `[false, true]` (AC #1).
- **Round-trip, falling edge clears** — `true` then `false` → `[false, true, false]`, and the state is
  not active afterwards (AC #2). This is the most important test in the slice: it is the behaviour
  `stall` structurally cannot have, so cloning `stall` too literally fails exactly here.
- **Repeated rising edge is idempotent** — a second `active: true` adds no emission (`Set` add +
  `distinctUntilChanged`).
- **Falling edge with no prior rising edge** — a lone `active: false` leaves the list at `[false]`, no
  crash (removal of an absent id is a no-op).
- **Per-conversation isolation** — a frame for `c1` leaves `c2`'s list at exactly `[false]` (AC #3).
- **`distinctUntilChanged`** — `c2`'s flow does not re-emit when `c1`'s state changes.
- **Malformed payload dropped, collector survives** — push several probes, assert no emission beyond
  the seed, then push a valid frame and assert it lands, proving the single inbound consumer was not
  torn down (AC #5). Usable probes: `conversation_id` absent; `conversation_id` as a JSON **number**;
  `active` absent; `active` as a JSON **object** or **array** (a genuinely wrong shape).
  **Do not use a quoted primitive** — kotlinx's tree decoder accepts `{"active":"true"}` as `true`
  even with `isLenient = false`, so that probe decodes green and proves nothing. This is measured and
  documented in-repo; see the `ApiRetryPayloadDto` KDoc (`InteractivePayloads.kt:126-148`) and the
  in-line note at `RemoteConversationRepositoryTest.kt:3969-3973`.
- **Capability gate closed** — `negotiatedCapabilities = { emptySet() }`: a valid frame produces no
  emission beyond the seeded `false`.
- **Capability gate, other token only** — `{ setOf("something_else") }`: same, proving fail-closed
  rather than any-token-passes.
- **Inert toward neighbours** — one test, three assertions, cloning
  `apiRetry_inertTowardNeighbours_keepsStallAndFoldsNoThreadRow` (`:4031-4049`): seed a stall via
  `stallEnvelope`, push a `compacting` rising edge, then assert (a) `observeStall` is **still `true`**
  (compaction is not forward progress), (b) `observeMessages` for that conversation is still empty (no
  thread row folded), and (c) the compaction state landed anyway.

The two `turn_state`-phase tests named in § Name collision must be left untouched; if either goes red,
the new arm is wrong, not the test.

### `StableConversationRepositoryTest.kt`

Clone the `observeStall_*` facade pair (`:225-260`) and add a `pushCompacting(value: Boolean)` to the
inline `RecordingConversationRepository` stub (`:382-415`, next to `pushStall`):

- **`observeCompacting_whileAbsent_emitsFalse`** — no live repository → `false` (AC #4).
- **`observeCompacting_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch`** — a state pushed on one live
  repository is observed; after the coordinator switches repositories, the prior connection's state is
  not observable, proving connection-scoping across a reconnect (AC #4).

No `FakeConversationRepository` or other test-double changes: they inherit the interface default. That
the existing suites keep compiling and passing unchanged **is** the assertion for AC #4's second half.

## Open questions

None blocking. Two notes for downstream:

- **#597 owns every display decision**, including precedence when compaction coincides with the
  thinking indicator, an active stall, or a retry status — four status affordances now exist and this
  slice deliberately expresses no opinion about their stacking. Note for #597: `isStalled`'s
  ViewModel-hoist precedent has **JVM** unit tests (`ThreadViewModelTest.kt` ~`:1207/1216/1235`), not
  androidTest-only coverage; a hoist added without them lives outside the mandatory gates.
- **A hostile daemon can leave the state stuck active** by sending a rising edge and never a falling
  one. Deliberately not defended here — see § Security review 6.

## Security review

**Verdict:** PASS

**Findings:**

- **[1. Trust boundaries]** No findings. Exactly one boundary, `decodeCompacting`
  (`RemoteConversationRepository.kt`, § Design 5) — a single named function, not scattered parsing.
  The untrusted `Envelope.payload` never escapes it: the DTO is consumed inside, and only a
  `Pair<String, Boolean>` of already-trusted primitives crosses into the projection, so downstream
  holds trusted, typed data by construction. This is the **narrowest** of the four sibling arms:
  `queue_state` carries user text and `api_retry` carries two screen-derived ints, whereas this
  payload's entire surface is a routing id (which stays a `Set` element, never a value) plus a bool.
  No daemon-supplied data of any kind — text, number, or otherwise — can structurally reach the UI
  through this arm; a consumer sees only a `Boolean` derived from set membership. A second,
  independent boundary is the `interactive` capability gate, fail-closed and evaluated before decode.
- **[2. Tokens, secrets, credentials]** Not applicable, by design decision: this arm reads an
  already-authenticated, already-Noise-decrypted stream and adds no credential creation, storage,
  comparison, rotation, or revocation surface. No token, key, or fingerprint is touched.
- **[3. File / storage]** Not applicable, by design decision: state is a single in-memory
  `MutableStateFlow<Set<String>>`, connection-scoped and **never persisted** (§ State + concurrency).
  Nothing is written to disk, so no path handling, no TOCTOU-on-file, no at-rest-encryption choice, no
  atomic-write requirement, and no `allowBackup` exclusion arises. Non-persistence is also a positive
  property: a compaction state cannot survive process death into a later session.
- **[4. Inter-process / Android attack surface]** Not applicable, by design decision: no `Activity`,
  `Service`, `BroadcastReceiver`, `ContentProvider`, `PendingIntent`, `WebView`, deep link, or
  `intent-filter` is added or altered. Nothing in the slice is exported, and the slice adds no UI.
- **[5. Cryptographic primitives]** Not applicable, by design decision: no RNG (nothing random), no
  key, no hash, and no comparison of an attacker-controlled value against a secret — so the
  `MessageDigest.isEqual` constant-time rule has no site here. `conversation_id` is used **only** as a
  `Set` element / routing key; routing by it is not an authorization decision (the daemon already
  scoped the fan-out, and the phone only displays what it observes).
- **[6. Network & I/O]** No findings on the checklist items — this arm opens no connection, builds no
  `OkHttpClient`, sets no timeout or TLS config, and does not lift any frame-size cap; it rides the
  existing single inbound collector and adds no I/O and no suspension point. It also cannot
  back-pressure the connection (non-suspending `update`).
  **OUT OF SCOPE —** a hostile-but-authenticated daemon could grow `compactingConversations` without
  bound by sending rising edges for endlessly many fabricated `conversation_id`s. Deliberately not
  fixed here: it is **pre-existing and identical** for every sibling projection
  (`stalledConversations`, `queuedByConversation`, `apiRetryByConversation`, `threadByConversation`,
  `lastMessages` — none caps its key count), and this arm's per-key cost (a bare `String`) is the
  *smallest* of the family — strictly smaller than `queuedByConversation`, which stores unbounded user
  text per key. It is also the only one of the family whose keys are **reclaimed in normal operation**,
  since a falling edge removes the id, and the whole set is connection-scoped so a reconnect reclaims
  it. Capping this one arm would be a spot-fix that does not close the class; it has never been
  observed, and the peer is authenticated and Noise-sealed. If it is ever addressed it belongs in a
  projection-family ticket covering all five maps at once — filing that is PO's call, not a
  precondition for this slice.
  **No fix (considered, deliberately deferred) —** a hostile or crashed daemon can send `active: true`
  and never the falling edge, leaving the phone showing "compacting" indefinitely. Consequence is a
  misleading indicator only: no memory growth beyond the single set entry, no code execution, no data
  exposure. Not defended, for three reasons: the shipped `stall` arm has the *identical* property and
  worse (it has no wire clearing edge at all) and has been in production since #395 without incident;
  a client-side compaction timeout would invent a policy the wire contract does not define and would
  fight the daemon as SSOT; and a reconnect clears it. No observed occurrence, so per the
  evidence-based-fix rule this stays undefended rather than acquiring a speculative watchdog. #597
  should be aware the flag can be long-lived and must not, for example, block interaction on it.
- **[7. Error messages, logs, telemetry]** No findings. The arm logs **nothing** and constructs no
  error message (drops are silent, and nothing throws to a caller), uniform with every sibling
  `onInbound` arm and with the repository's no-log posture. MUST-NOT-log, to be stated explicitly in
  the spec'd KDoc: the payload and `conversation_id` (sensitive per the sibling KDocs — a logged id is
  a cross-conversation correlation leak). There is no counter or text to log here at all, so the
  MUST-NOT-log surface is smaller than `api_retry`'s. No telemetry, metrics, or crash-reporter surface
  is added, so no consent question arises.
- **[8. Concurrency]** One design-level requirement, addressed in the spec rather than left implicit.
  The write is a genuine **read-modify-write** on a `Set` (`it + id` / `it - id`), unlike `api_retry`'s
  pure replace — so a `.value = compactingConversations.value + id` formulation would be a real
  check-then-mutate TOCTOU window. § Design 3 and § State + concurrency therefore require
  `MutableStateFlow.update {}` (a CAS loop) explicitly and say why, and the single-writer property
  (one inbound collector) removes the window a second time. Beyond that: no coroutine is launched, so
  no scope-ownership or outlives-the-`ViewModel` leak question; no mutex, so no lock-ordering
  question. On the hot-vs-cold item, `observeCompacting` is correctly **cold** and per-collector over
  a shared `StateFlow` — a hot `SharedFlow` would have been wrong, since late subscribers and
  `flatMapLatest` re-subscriptions through the facade would observe nothing until the next frame.
  Cancellation safety: the decode is non-suspending and catches `IllegalArgumentException`, which does
  **not** swallow `CancellationException` (that extends `IllegalStateException`, a sibling class) —
  the known trap is verified absent, not merely unencountered.
- **[9. Threat model alignment]** No findings. The threat this arm faces is a compromised or buggy
  *authenticated* daemon injecting frames the phone should not act on. Addressed three times over:
  the `interactive` capability gate (a client-side mirror of the server-side fan-out gate, so a daemon
  that ignored its own gate still gets nothing); a boundary type that admits no daemon-supplied text
  or number at all (§ 1); and the arm's structural inertness toward its neighbours (§ Design 4).
  That last one is a **specific hostile lever this design closes**: if the arm cleared a stall the way
  the live-session arm does, a daemon could suppress the phone's stall indicator by emitting
  `compacting` frames, hiding a genuinely hung remote head from the user. Because the arm's only
  statement touches `compactingConversations`, that is structurally impossible, and the
  inert-toward-neighbours test pins it. The reason pyrycode#1074 was itself labelled
  `security-sensitive` — screen-derived data crossing the tui-driver substrate seal — is addressed by
  the payload shape: the only screen-derived datum is the existence of a compaction banner, reduced to
  a bool, with no scraped text or count on the wire. Mobile-specific threats from the checklist
  (screenshot leakage, accessibility-service eavesdropping, overlay attacks, malicious deep links,
  third-party keyboard logging) have no site in a data-layer decode slice with no UI and no input;
  the first three become relevant only once #597 renders the state, and #597 owns them — with a
  materially smaller exposure than #594 had, since what is rendered derives from one bool.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-30

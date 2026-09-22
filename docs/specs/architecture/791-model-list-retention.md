# #791 — retain the server-published model menu per conversation

Decode the `model_list` frame, retain its rows on the connection that received them, and expose the
result as a per-conversation reading. **Renders nothing** — the composer's controls are #649, and the
on-demand ask is #792.

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `../pyrycode/docs/protocol-mobile.md` (sibling checkout at `Workspace/Projects/pyrycode`) | § `model_list` | Wire SSOT: the field table, the three optionality shapes, the reconcile-by-id rule and the never-by-position warning. Cited, never restated. |
| `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` | `SessionSettingsPayloadDto`, `toSessionSettings` | The named precedent for the decode file: strict-required fields, one `MobileJson` validate boundary, a mapper that authors no payload-bearing message. |
| `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` | `QueueStatePayloadDto`, `toQueue`, `ModalOptionDto` | The shape a *routed live* frame takes (DTO carries `conversation_id`, mapper returns the domain value). `ModalOptionDto` is why the new row type is not called `ModelOptionDto` — see Design. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `onInbound`, `queuedByConversation`, `decodeQueueState`, `observeQueue`, `TYPE_QUEUE_STATE` | The demux arm, the `interactive` gate, the connection-scoped snapshot-replace projection and the decode-or-drop idiom this ticket copies verbatim. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` | `observeSessionSettings`, `SessionSettings`, `EffectiveEffort` | The unavailable-reading precedent the ticket names, and the rule that a reading's element type is co-located with the contract it serves. |
| `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` | `observeSessionSettings`, `switchToLive` | The nullable passthrough form (`switchToLive<T?>(null)`) the new reading reuses. |
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` | `sessionSettings`, `setSessionSettingsReading`, `observeSessionSettings` | The settable-double seam #649 will need; copied field for field. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` | `queueStateEnvelope`, `collectQueue`, `queue_*` tests | The fixture-builder + `collect*` helper shape, and `FakeSessionPump` / `negotiatedCapabilities` wiring. |
| `app/src/test/java/de/pyryco/mobile/data/network/SessionSettingsPayloadsTest.kt` | `decode` | The payload-test shape: raw JSON strings through `MobileJson.parseToJsonElement`, one behaviour per test. |
| `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md` | § "The session-settings read exchange (#590)" | Carries the lesson that decides `truncated_fields` here: `explicitNulls = false` collapses omitted and explicit-`null`, which `effective_effort` had to *avoid* and `WorkspaceUpdatedPayloadDto` deliberately *wants*. This field is in the second camp. |
| `docs/knowledge/features/remote-conversation-repository-live-stream-and-modals.md` | § `liveSessionEvents`, § `recordReplayCursor` | Confirms the cursor side-write runs before the demux and is type-agnostic, so the live lane's `event_id` needs no handling here. |

## Context

Mobile's model and effort vocabulary is hardcoded in `Model` and `Effort`. Neither came from the
server, and the published vocabulary varies by machine and account. The daemon already publishes a
per-conversation menu as a `model_list` frame — once per claude child spawn on the live interactive
lane, and again as a per-conversation burst on every (re)connect — and mobile drops both: `onInbound`'s
type demux has no arm for it and falls through to `else -> Unit`.

This slice adds the arm, the decode boundary and the retention. It adds no UI, no outbound verb and no
new dependency. It does not retire `Model` / `Effort`; #649 does that when it reads what this retains.

No ADR is warranted — this follows the established inbound-frame pattern rather than deciding anything
new about it.

## Design

### Domain types — `ConversationRepository.kt`

Co-located with the contract they serve, the `SessionSettings` / `QueuedMessage` / `ThreadItem` rule.
Both `data` classes, because structural equality is what makes a consumer's `distinctUntilChanged`
behave (the `ApiRetryStatus` rule).

```kotlin
data class ModelMenu(val rows: List<ModelMenuRow>, val droppedModels: Int)

data class ModelMenuRow(
    val resolvedModel: String,
    val value: String,
    val displayName: String,
    val effortLevels: List<String>,
    val supportsAutoMode: Boolean,
    val truncatedFields: List<String>?,
)
```

**Naming.** The wire calls a row a *model option*; this repo already exports
`de.pyryco.mobile.data.model.ModalOption` and `ModalOptionDto`, one letter away. A public
`ModelOption` beside them is a homograph trap at every future call site, so the row takes the ticket's
own word — *row* — and the pair reads as `ModelMenu.rows`.

**`ModelMenu` is a type rather than a bare `List<ModelMenuRow>`** because `droppedModels` is frame-level
state that must survive beside the rows (AC #5), and because a *present but empty* menu and an *absent*
menu are different readings — a list type could only express the first as `emptyList()` and the second
as `null`, which is exactly the pun AC #3 forbids.

**Nothing is normalised.** No trim, no case fold, no alias rewrite, no mapping through `Model` or
`Effort`, no family derived by splitting `value`. `effortLevels` comes from that row alone and an empty
list is the positive statement that the row exposes no effort control (AC #2).

**`truncatedFields` stays nullable**: `null` is the wire's "nothing was cut". An out-of-contract `[]`
decodes to an empty list rather than being punned to `null`, so the retained value is what arrived.

### Decode boundary — new `data/network/ModelListPayloads.kt`

`internal` DTOs (the `InteractivePayloads` posture — only the domain type crosses the package boundary)
decoded through `MobileJson`:

- `ModelListPayloadDto` — `conversation_id: String`, `models: List<ModelListRowDto>`,
  `dropped_models: Int`. All three strict-required with no default: the wire emits all three
  unconditionally, so a missing key is a malformed frame rather than a silently-defaulted one. `models`
  is never `null` on the wire, so a non-optional list type needs no null branch (AC #1) and a literal
  `null` fails the frame.
- `ModelListRowDto` — `resolved_model`, `value`, `display_name` as required `String`s; `effort_levels`
  as a required `List<String>` (same never-`null` contract); `supports_auto_mode` as
  `Boolean = false`; `truncated_fields` as `List<String>? = null`.
- `internal fun ModelListPayloadDto.toMenu(): ModelMenu` — total, non-throwing, a pure field copy that
  carries `droppedModels` **verbatim** rather than deriving it from `rows.size` (AC #5).

**Two fields carry a default, and each default is a *read* rather than a manufactured value.**
`supports_auto_mode` absent means `false` per the wire's own contract, so defaulting it states what the
wire states — the opposite of `session_settings`' `permission_mode`, where a default would have
invented a posture. `truncated_fields` absent and explicit `null` both mean "nothing was cut", so
`explicitNulls = false` collapsing them is correct here; that collapse is the trap `effective_effort`
had to avoid precisely because *its* three states mean three different things.

**Signature choice, stated so it reads as a decision.** The ticket names `toSessionSettings` as the
model. That mapper returns the bare domain value because a correlated reply carries no routing id; this
frame does, and routing by it is AC #4. So the file keeps `toSessionSettings`'s *discipline* — one
`MobileJson.decodeFromJsonElement` as the single validate boundary, a total mapper after it, no
payload content in any authored message — while taking `QueueStatePayloadDto.toQueue`'s *shape*, where
the DTO holds `conversationId` and the repository pairs it with the mapped value.

### Inbound arm + retention — `RemoteConversationRepository.kt`

- `const val TYPE_MODEL_LIST = "model_list"` beside `TYPE_QUEUE_STATE`.
- `private val modelMenusByConversation = MutableStateFlow<Map<String, ModelMenu>>(emptyMap())` —
  connection-scoped, written only from the single `init` inbound collector.
- `private fun decodeModelList(envelope: Envelope): Pair<String, ModelMenu>?` — one
  `try` / `catch (IllegalArgumentException)` around the decode + map, mirroring `decodeQueueState`.
- A demux arm gated on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, like its `session_transition`
  and `queue_state` siblings, doing one snapshot replace:
  `modelMenusByConversation.update { it + (conversationId to menu) }`.

Routing is the payload's own `conversation_id` and nothing else — never the envelope id, which the
reconcile burst repeats across every frame, and never burst position. A `+` on the map replaces that
one key wholesale and leaves every other conversation untouched (AC #4); no merge, no append, no
element-level reconciliation.

**No clearing edge anywhere.** Nothing removes a key, and no connection edge clears the map. Absence of
a frame is the wire's only "no list" signal, so a blanket clear would manufacture an unavailable reading
the daemon never stated. A fresh repository per connection (#351) already starts empty, which is the
only reset this design has.

`recordReplayCursor` runs before the demux and is type-agnostic, so the live lane's `event_id` and the
reconcile burst's absence of one both need zero handling here.

### Reading — the interface, the facade, the double

- `ConversationRepository.observeModelMenu(conversationId: String): Flow<ModelMenu?> = flowOf(null)` —
  a default so the inline test doubles inherit it, the `observeSessionSettings` cascade-avoidance.
  `null` is **unavailable**: a normal, permanent resting state, never an error, never a spinner, never
  the device enum, never another conversation's rows (AC #3).
- `RemoteConversationRepository` overrides it as a pure cold projection:
  `modelMenusByConversation.map { it[conversationId] }.distinctUntilChanged()`. Issues no request. A
  `StateFlow` always has a current value, so every collector receives `null`-until-first-frame on
  subscription; `distinctUntilChanged` means a frame for another conversation does not re-emit.
- `StableConversationRepository` passes through: `switchToLive<ModelMenu?>(null) { it.observeModelMenu(id) }`.
- `FakeConversationRepository` gains `private val modelMenus` plus
  `fun setModelMenu(conversationId: String, menu: ModelMenu?)` — the settable seam #649's UI work needs.

## State + concurrency model

One new `MutableStateFlow<Map<String, ModelMenu>>` on the repository, connection-scoped and in-memory.
**Single writer**: the one `init` inbound collector coroutine, so snapshots never race; the write is an
atomic `update {}`, the memory-visibility posture every sibling projection already uses. No new
coroutine, no new scope, no new dispatcher, no new cancellation path — the arm runs inside the existing
collector, and the reading is a cold `map` over the shared flow with no scope of its own. The
`LifecycleConnectionDriver`'s background close destroys the repository along with the connection, which
is the whole of this state's lifecycle.

## Error handling

Decode-or-drop, silently. A malformed payload — not an object, a missing key, a wrong-typed field,
`models` or `effort_levels` as `null`, a non-integer `dropped_models` — throws inside `decodeModelList`,
is caught as `IllegalArgumentException` (`SerializationException` ⊂ it), and yields `null`: the one
envelope is dropped and the lone inbound collector survives. The previously retained menu for that
conversation stands, because nothing was written.

**Nothing on this path logs.** Every row string is claude-authored text that crossed the subprocess
trust boundary, and the conversation id is a cross-conversation correlation leak — the uniform rule
across every `onInbound` arm. `toMenu` is total and authors no message at all, so the only throwables
here are kotlinx-serialization's, which are caught and discarded rather than surfaced.

Nothing reaches the UI in this slice, so there is no banner, dialog or silent-failure decision to make.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`) — this slice has no composable and no device behaviour, so no
Compose UI test and no emulator rung is in scope. It is not an operator-facing flow: it renders nothing,
so no rung-3 scenario is owed; #649 carries the operator-facing proof when the controls land.

**`ModelListPayloadsTest`** (new) — the wire shapes, per AC #1/#2/#5:
- a full row's four strings and its effort levels survive verbatim (padded, mixed-case, non-ASCII and a
  control character all held, proving no trim / fold / re-encode);
- `models: []` decodes to a present menu with zero rows;
- `effort_levels: []` decodes to an empty list;
- `truncated_fields` across all four shapes: absent → `null`, explicit `null` → `null`, a populated
  array → verbatim, `[]` → empty list;
- `supports_auto_mode` absent → `false`, and both explicit values honoured;
- `dropped_models` carried verbatim beside a row count that contradicts it;
- rejections: `models: null`, `effort_levels: null`, a missing `conversation_id`, a wrong-typed
  `dropped_models`.

**`RemoteConversationRepositoryTest`** additions — the arm and the retention, per AC #3/#4:
- unavailable before any frame; a frame surfaces that conversation's menu;
- a conversation the connection heard no frame for stays `null` while another holds a menu;
- a later frame replaces wholesale — a shorter list replaces a longer one, no merge or append;
- two frames sharing one envelope id but naming different conversations are retained independently
  (the burst's non-load-bearing envelope id, never position);
- an empty `models: []` frame is a present-but-empty menu, distinct from unavailable;
- without `interactive`, a well-formed frame never surfaces;
- a malformed frame is dropped, the prior menu stands, and the collector survives a later good frame;
- `droppedModels` and `truncatedFields` reach the consumer.

**`StableConversationRepositoryTest`** — the pair its `observeSessionSettings` siblings have: `null`
while no live repo is attached, and delegation that does not leak across a host switch.

**`FakeConversationRepositoryTest`** — unseeded reads unavailable; a seeded menu reads back.

Fakes throughout (`FakeSessionPump`, the existing `Fake…Repository`), no MockK.

## Open questions

1. **Does `ModelMenu` need the conversation id as a field?** Resolved at design time: no. The reading is
   already addressed by `observeModelMenu(conversationId)`, so carrying it inside the value would give a
   consumer a second, redundant identity to disagree with.
2. **Should `truncatedFields` be `List<String>` defaulting to empty instead of nullable?** Resolved: keep
   it nullable, so what the wire stated is what is retained. Revisit only if #649 finds the null branch
   noisy at the render site — and then in that ticket, not this one.

## Documentation handoff

Pending for the documentation stage; this ticket writes no `docs/knowledge/` file.

- `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md` — the `model_list`
  payload family and its decode boundary, in the section application payloads belong in. State the three
  optionality shapes (`models` / `effort_levels` always present and never `null`; `truncated_fields`
  nullable; `supports_auto_mode` absent → `false`) and the untrusted-string obligation.
- `docs/knowledge/features/remote-conversation-repository-live-stream-and-modals.md` — the inbound arm,
  its `interactive` gate, and the retention's snapshot-replace semantics keyed by conversation id.

## Sizing

Re-counted against this written plan: **5 production source files** (`ModelListPayloads.kt` new;
`ConversationRepository.kt`, `RemoteConversationRepository.kt`, `StableConversationRepository.kt`,
`FakeConversationRepository.kt` modified) — at the ceiling. **2 new exported types**
(`ModelMenu`, `ModelMenuRow`) plus one defaulted interface method, so **0 consumer call sites** need a
simultaneous update. **5 acceptance criteria. 1 reject branch** (decode-or-drop).

**Total written work ≈ 950 lines, over the 800-line ceiling — deliberately, and for the reason the
refiner's estimate states.** The decode boundary's only consumer is the retention in the same family: a
child holding `ModelListPayloads.kt` alone would have nothing outside the family calling it and could
not be verified on its own. The floor beats the ceiling, so the slices stay merged and the overage is
stated rather than engineered around. Nearest analogues at the same shape and KDoc density: #590
(1157 lines, 5 production files) and #623 (914 lines, 5 production files) — both landed in one leg.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] SHOULD FIX — the boundary is explicit, but nothing in the type system says the
  strings are untrusted, and this ticket's own consumer does not yet exist.** The boundary is a single
  named function, `ModelListPayloadDto.toMenu` behind `decodeModelList`, and no second site parses this
  frame. But `ModelMenuRow`'s four claude-authored strings and every element of `effortLevels` reach a
  consumer as plain `String`, identical in type to a trusted one. `SessionSettings.model` has the same
  property and the same mitigation — a KDoc that states the obligation — so the consistent fix is a KDoc
  on `ModelMenuRow` naming the strings as claude-authored text that crossed the subprocess trust
  boundary, unsanitised by the daemon (no control character or terminal escape is stripped anywhere on
  this path), safe only as inert text, and never parseable: `value` is an alias (`sonnet`), a bracketed
  variant (`opus[1m]`) or `default`, so no family may be split out of it and it must never be presented
  as a version. Phase B lands that KDoc on the domain type, where a consumer reads it, rather than only
  in the wire DTO, which #649 will never open. The render-side obligation is #649's; this ticket cannot
  discharge it because it renders nothing.
- **[Trust boundaries] No further finding on length.** The checklist asks a new inbound verb carrying
  text into Compose for a length bound. Nothing here reaches Compose, and the bound that exists is the
  daemon's producer cap reported in `dropped_models` — which the plan carries verbatim and explicitly
  forbids deriving a cap from. Mobile must not add a second bound: silently dropping rows the daemon
  said it kept would present a cut menu as complete, the failure the wire's own `truncated_fields`
  design exists to prevent. A render-side length bound belongs to #649.
- **[Tokens, secrets, credentials] No findings.** No token, key or credential is read, written,
  derived or compared. The retained state is claude-authored display vocabulary; it is never persisted,
  never leaves memory, and dies with the connection.
- **[File / storage operations] No findings.** No filesystem path is constructed, read or written. The
  ticket's explicit "never treat the selectable value as parseable" rule already forecloses the one path
  by which `value` could have become a filename or a cache key, and the retention is keyed by
  conversation id and by nothing derived from row text — so no row string ever becomes a map key, a
  file name or a lookup path.
- **[Inter-process / Android attack surface] No findings** — no Activity, Service, Receiver, intent
  filter, deep link, pending intent, content provider or WebView is touched. Worth stating rather than
  skipping: a WebView rendering these strings would be a MUST FIX, and this ticket adds none. #649 owns
  keeping it that way.
- **[Cryptographic primitives] No findings.** No RNG, no hash, no comparison against a secret, and no
  part of the Noise handshake, key schedule or AEAD framing is touched. The frame arrives already
  decrypted through `NoiseIkSession`.
- **[Network & I/O] No findings.** No new socket, no client construction, no timeout, no TLS or pinning
  decision, no reconnect behaviour. Frame size stays bounded by the existing transport cap, which this
  ticket does not lift. The design adds no outbound frame at all — the on-demand `request_model_list`
  ask is #792 — so there is no new request this could be made to spin on.
- **[Error messages, logs, telemetry] No findings, and this is the category with the sharpest teeth.**
  The plan logs nothing on this path, which is mandatory rather than stylistic: a logged row would put
  claude-authored text into Logcat, and a logged conversation id is a cross-conversation correlation
  leak. `toMenu` is total and authors no message; the only throwables are kotlinx-serialization's, which
  can quote the offending input and are therefore caught and **discarded** inside `decodeModelList`
  rather than logged, rethrown or surfaced. The no-payload-content guarantee rests on that discard. No
  telemetry, no metrics, no crash-reporter surface is added.
- **[Concurrency] No findings.** One `MutableStateFlow` written by the single existing inbound collector
  through an atomic `update {}` — no check-then-act across a suspension point, because there is no
  suspension point and no read-then-write. No coroutine is launched, so there is no scope to own or
  cancel. The reading is a cold per-collector `map`, so nothing is shared across screens that was meant
  to be per-collector. Process death or a `LifecycleConnectionDriver` background close loses the map,
  which is correct: a fresh connection is re-sent the reconcile burst.
- **[Threat model alignment] Addressed: hostile daemon frame, the threat this arm actually faces.** Every
  frame is decoded defensively behind one fail-closed boundary and dropped whole on any malformation, so
  a hostile or buggy daemon can waste one envelope but cannot corrupt a retained menu, crash the
  collector or stall the stream. The `interactive` gate is the client-side mirror of the server's
  fan-out gate — defence in depth, so a daemon that ignores the negotiated set still reaches nothing.
  Cross-conversation injection is structurally foreclosed: routing is the payload's `conversation_id`
  alone, so a frame can only overwrite the menu of the conversation it names, and a name no collector
  observes sits unread. **Malicious relay:** out of scope and unchanged — the relay is content-blind and
  cannot forge a frame inside the Noise session; drop/delay/reorder degrade to "no menu yet", which is a
  legal resting state here by construction. **Token theft / UI-side leakage** (screenshots, accessibility
  eavesdropping, overlays): not applicable — nothing is stored and nothing is drawn. Prompt injection
  through claude-authored display text is the standing upstream threat (`protocol-mobile.md` § Security
  model, threat 1, `mitigation: partial`); this ticket holds the strings inert and hands the render
  boundary to **#649**, which owns it.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

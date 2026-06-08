# Spec #387 — Correlate `tool_use`/`tool_result` into live tool-call timeline items with status

**Ticket:** pyrycode-mobile #387 (`feat(data)`, `size:s`, `security-sensitive`)
**Split from:** #368. **Blocked by:** #385 (decoded live-session events, merged to `main`).
**Consumed by:** #388 (the tool-row status affordance — the visual; out of scope here).

Turn the `tool_use` (start) / `tool_result` (completion) pair from #385's decoded
`LiveSessionEvent` family into **one evolving thread row** that carries a status
(`Running` → `Done`/`Failed`), correlated by `toolUseId`, interleaved chronologically into the
existing `observeMessages` thread stream. This slice does **not** parse the wire (that's #385) and
does **not** render anything (that's #388).

---

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:1-22` — **the model you extend.**
  `Message` (note `toolCall: ToolCall? = null` with the "Non-null iff role is `Role.Tool`"
  invariant at :12), `enum class Role { User, Assistant, Tool }`, and `data class ToolCall(toolName,
  input, output)`. The file already holds **three** public top-level types, so adding a fourth
  (`ToolCallStatus`) does **not** trip ktlint's single-class-filename rule (see memory
  `ktlint-filename-rule-single-class`). The new enum + the defaulted `status` field land here, no new file.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:279-297` —
  **the load-bearing edit site.** The existing `TYPE_TURN_STATE, …` live-session arm: it gates on
  `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, decodes via `decodeLiveSessionEvent`, then in
  `.let { event -> … }` does the #395 stall-clear (`stalledConversations.update { it - event.conversationId }`)
  and `mutableLiveSessionEvents.tryEmit(event)`. The new `tool_use`/`tool_result` fold dispatch goes
  **inside** this same `.let` block — one shared collector, one gate, no second subscription.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:133-143` —
  `messagesByConversation: MutableStateFlow<Map<String, List<Message>>>` and its doc: ordered,
  `message_id`-deduped, **in arrival order** ("first insertion fixes a message's position, a repeat id
  replaces it in place"). **This is the state the tool rows fold into** — that's what gives AC#4
  (chronological interleave) for free.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:422-445` —
  `appendMessages`: the existing append-or-replace-by-`message_id` fold. **Read it as the shape to
  mirror** — but note the new tool folds do **not** call it (different match predicate + absent-behavior;
  see Design § 3). The two new folds (`applyToolUse`, `applyToolResult`) are siblings of this.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:531-547` —
  `observeMessages` + `threadProjection`: the cold projection maps `messagesByConversation[id]` to
  `ThreadItem.MessageItem`s **with no re-sort** (arrival order = thread order) and
  `distinctUntilChanged`. Confirms: (a) tool rows interleave by arrival position, (b) an in-place
  status change re-emits because the `Message` value changed.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt:51-69` —
  `LiveSessionEvent.ToolUse(conversationId, turnId, toolUseId, name, inputSummary)` and
  `ToolResult(conversationId, turnId, toolUseId, isError, resultSummary)`. These are the inputs to
  the two folds. Note the doc: `inputSummary`/`resultSummary` are **server-authored, carried verbatim**.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:55` and
  `ToolCallRow.kt:203` — `Role.Tool -> message.toolCall?.let { ToolCallRow(toolCall = it) }`. Proof the
  UI renders a tool row from `toolCall`, **ignoring `content`** — so `Message.content` for a tool row is
  a low-stakes fallback (Design § 3 sets it to the tool name). #388 extends `ToolCallRow` to read the new `status`.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1470-1560,
  2202-2236` — **the harness is already in place.** The interactive-event idiom
  (`RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })`,
  `FakeSessionPump`, `pump.push(...)`, `runCurrent()`) and the **existing**
  `toolUseEnvelope(conversationId, turnId, toolUseId, name, inputSummary)` /
  `toolResultEnvelope(conversationId, turnId, toolUseId, isError, resultSummary)` builders. New tests
  reuse these verbatim — **no new envelope builder needed**. (`runCurrent()`, not `advanceUntilIdle()` —
  memory `remote-repo-test-runcurrent-not-advanceuntilidle`.)
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:446-459` — an
  existing seeded `Role.Tool` `Message` carrying a `ToolCall`. Confirms the shape; the named-arg
  construction means the new defaulted `status` field needs no change here (cascade check, Design § 1).
- Prior specs for the established pattern (read for posture, not to copy):
  `docs/specs/architecture/395-observe-stall-state.md` (the "maintain per-conversation state from the
  one inbound collector" precedent this mirrors) and `385-decode-structured-live-session-stream.md`
  (the decode seam + the `liveSessionEvents` lossiness this slice deliberately bypasses).

---

## Context

Part of the Phase 2 structured-streaming exit-gate (pyrycode#596, ADR 025). The daemon emits
`tool_use` when the agent starts a tool and `tool_result` when it finishes, both gated on the
negotiated `interactive` capability. #385 already decodes them into typed
`LiveSessionEvent.ToolUse` / `LiveSessionEvent.ToolResult` and surfaces them off the single inbound
collector. The static tool-call payload (`ToolCall` — `toolName`/`input`/`output`) already exists
from #191 and already renders in the thread (`Role.Tool` `Message` → `ToolCallRow`).

What's missing is the **correlation + status**: a `tool_use` should appear immediately as a *running*
tool row, and its matching `tool_result` should update **that same row in place** to done/failed and
attach the output — correlated by `toolUseId`, not by position, and tolerant of a misbehaving stream.

---

## Design

Four edits across **two production files** (one model extension, one repository), plus tests. **No
new file, no new `ThreadItem` variant, no interface change, no new collector.**

| File | Edit |
|------|------|
| `data/model/Message.kt` | add `enum class ToolCallStatus`; add `status: ToolCallStatus = ToolCallStatus.Done` to `ToolCall` |
| `data/repository/RemoteConversationRepository.kt` | dispatch `ToolUse`/`ToolResult` inside the existing live-session `.let` arm; add the `applyToolUse` + `applyToolResult` folds |

### 1. Model extension — `data/model/Message.kt`

Add a status enum and a **defaulted** field on `ToolCall`:

```kotlin
enum class ToolCallStatus { Running, Done, Failed }

data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolCallStatus = ToolCallStatus.Done,
)
```

- **Default = `Done`, deliberately.** Every existing `ToolCall(...)` construction (the fake's seed,
  the `ToolCallRow`/`ThreadScreen` previews — all named-arg, verified via `codegraph_impact ToolCall`
  → 2 symbols, no consumer cascade) represents a *finished* tool call with output. A `Done` default
  keeps them compiling **and** semantically correct, and keeps existing test equality assertions
  passing: `ToolCall(a, b, c)` `==` `ToolCall(a, b, c, status = Done)`. `Running` would be wrong for
  finished seeds and would force a test cascade. **No fixture cascade** (the whole point of the
  defaulted field, per the ticket).
- `ToolCallStatus` is the **only** new exported type in the slice.

### 2. Integration point — the existing live-session arm (no new collector, no new gate)

The fold rides the **existing** single inbound collector and the **existing** `interactive` gate —
exactly the #395 posture. Inside `RemoteConversationRepository.onInbound`'s
`TYPE_TURN_STATE, TYPE_ASSISTANT_DELTA, TYPE_TOOL_USE, TYPE_TOOL_RESULT, TYPE_TURN_END` arm, the
`decodeLiveSessionEvent(envelope)?.let { event -> … }` block gains a `when (event)` dispatch
**alongside** the unchanged stall-clear and `tryEmit`:

```kotlin
decodeLiveSessionEvent(envelope)?.let { event ->
    stalledConversations.update { it - event.conversationId }   // #395, unchanged
    when (event) {                                              // #387, new
        is LiveSessionEvent.ToolUse -> applyToolUse(event)
        is LiveSessionEvent.ToolResult -> applyToolResult(event)
        else -> Unit
    }
    mutableLiveSessionEvents.tryEmit(event)                     // #385, unchanged
}
```

**Why fold here and not subscribe to `liveSessionEvents`:** the ticket says mirror #395, and #395
maintains state from the *one shared inbound collector*, not a second subscription. `liveSessionEvents`
is `replay = 0` + `DROP_OLDEST` + best-effort by design (#385 § Open questions) — subscribing to it
would risk dropped tool events under load and add a second collector. Folding in the inbound collector
is lossless and ordered.

### 3. The two folds — both mutate `messagesByConversation`

Tool rows **are** thread rows: a `Role.Tool` `Message` carrying the `ToolCall`. Folding them into
`messagesByConversation` (the same `StateFlow` `observeMessages` reads) makes them interleave by
**arrival order** with messages — satisfying AC#4 with **no merge, no second flow, no timestamp sort**.

**`applyToolUse(event: LiveSessionEvent.ToolUse)`** — *insert a running row, idempotent on repeat id.*
Build a `Message`:

| field | value |
|-------|-------|
| `id` | `event.toolUseId` — the correlation handle, also the dedup key |
| `sessionId` | `""` — the v2 wire carries none; matches the `message` arm's placeholder |
| `role` | `Role.Tool` |
| `content` | `event.name` — verbatim; a non-empty fallback the UI ignores (renders `toolCall`) |
| `timestamp` | `Clock.System.now()` — see "Timestamp" below |
| `isStreaming` | `false` |
| `toolCall` | `ToolCall(toolName = event.name, input = event.inputSummary, output = "", status = Running)` |

Fold semantics (one `messagesByConversation.update {}`): in the conversation's list, find a row with
`id == event.toolUseId && role == Role.Tool`. **If absent, append** (new running item, AC#1). **If
present, leave unchanged** (a repeat `tool_use` is ignored — the existing item, possibly already
completed by an earlier `tool_result`, is preserved, so a duplicate never resets status or produces a
second row; AC#3). `&& role == Role.Tool` namespaces the match to tool rows so a `toolUseId` can never
collide with a real `message_id` and clobber a message.

**`applyToolResult(event: LiveSessionEvent.ToolResult)`** — *update the matching running row in place,
or drop.* One `messagesByConversation.update {}`: find the row with `id == event.toolUseId && role ==
Role.Tool`. **If absent, no-op** (a `tool_result` with no matching `tool_use` — including a
result-before-use out-of-order arrival — is dropped, no orphan half-row; AC#3). **If present**, replace
it in place (position and timestamp preserved) with its `toolCall` copied as:
`output = event.resultSummary`, `status = if (event.isError) ToolCallStatus.Failed else
ToolCallStatus.Done` (AC#2). A duplicate `tool_result` updates in place again (last-write-wins, one row).

Both folds are ~12 lines, the same "find index in the per-conversation list, rebuild that one entry"
shape as `appendMessages` — but kept separate because the match predicate (tool-only) and the
absent-behavior (`applyToolUse` appends, `applyToolResult` drops) differ from `appendMessages`. Do not
generalize `appendMessages` to cover them; two small focused folds read clearer.

**Timestamp.** `Clock.System.now()` at `tool_use` arrival, **not** `envelope.ts`. Rationale: (a) thread
order in the remote is **arrival order**, never a timestamp sort (`threadProjection` does not sort), so
the value does not affect placement; (b) `Clock.System.now()` is the established pattern for
locally-assembled rows (`sendMessage`, `createDiscussion`); (c) it cannot throw, so it avoids coupling a
valid tool event's survival to a malformed `envelope.ts` (which would otherwise need a guarded
`Instant.parse` in the un-try-wrapped `.let` block). The `tool_result` update **preserves** the original
`tool_use` timestamp (the row's position and `timestamp` are kept; only the `toolCall` changes).

**Not folded into `lastMessages`.** Tool rows are **not** added to the last-message preview
(`recordLastMessage` is not called). The conversation-list preview should show conversational text, not
a tool invocation — and tool events arrive on a different wire path than `message`. So a tool row never
becomes a list-tier preview.

### Data-flow diagram

```
pump.inbound (single collector, wire/arrival order)
   │  onInbound(envelope) → when(type)
   ├─ tool_use      ──[gate: interactive]──> decodeLiveSessionEvent → ToolUse ──> applyToolUse
   │                                                                               (append running Role.Tool row,
   │                                                                                id = toolUseId; ignore if present)
   ├─ tool_result   ──[gate: interactive]──> decodeLiveSessionEvent → ToolResult ─> applyToolResult
   │                                                                               (update matching row in place:
   │                                                                                output + Done/Failed; drop if absent)
   │     (both also: stall-clear #395, tryEmit #385 — unchanged)
   ▼
messagesByConversation: StateFlow<Map<id, List<Message>>>   (tool rows interleaved by arrival order)
   │  threadProjection: map → List<ThreadItem.MessageItem>, distinctUntilChanged
   ▼
observeMessages(conversationId): Flow<List<ThreadItem>>  ──►  thread UI (#388 renders status)
```

---

## State + concurrency model

- **Single writer, no new state field.** Both folds write the **existing** `messagesByConversation`,
  written only from the lone `init` inbound collector coroutine (and `sendMessage`'s confirmed insert —
  itself going through the same atomic `update {}`). Tool `tool_use`/`tool_result` are processed on that
  one collector in wire arrival order, so insert-then-update never races and a result can never be
  applied before its use *for a well-ordered stream*; an out-of-order result simply finds no row and
  drops. `MutableStateFlow.update {}` (not `.value =`) matches the sibling folds' memory-visibility posture.
- **No new coroutine, no dispatcher choice, no new gate.** The fold is pure in-process state-folding on
  the existing collector, inside the existing `CAPABILITY_INTERACTIVE` gate. A non-interactive phone
  never folds a tool row (fail-closed, defence in depth on top of the server-side fan-out gate).
- **Cold fan-out, re-emit on change.** `observeMessages` → `threadProjection` is a cold
  `map` + `distinctUntilChanged` over the one `StateFlow`; N collectors share the single inbound
  consumer. A `tool_use` append changes the list → re-emit; a `tool_result` in-place update changes the
  `Message` value → the list is unequal → `distinctUntilChanged` re-emits (the status flip propagates).
- **Connection-scoped, in-memory.** A fresh repository per connection (#351) starts with an empty
  `messagesByConversation`; live tool rows are re-derived from the live stream on reconnect. Transient
  "right now" state, not durable — same lifetime as the live `message` rows it sits beside.

## Error handling

| Failure mode | Layer | Result |
|---|---|---|
| Malformed `tool_use`/`tool_result` payload (missing/wrong-typed field) | `decodeLiveSessionEvent` (existing) | `SerializationException ⊂ IllegalArgumentException` caught → `null` → one envelope dropped, the fold never runs, the single inbound collector survives (AC#3, AC#5) |
| `tool_result` with no matching `tool_use` (incl. result-before-use out-of-order) | `applyToolResult` | no row matches → no-op; no orphan/half row created (AC#3) |
| Duplicate `tool_use` (same `toolUseId`) | `applyToolUse` | existing tool row found → left unchanged; no second row, no status reset (AC#3) |
| Duplicate `tool_result` (same `toolUseId`) | `applyToolResult` | in-place update re-applied (idempotent / last-write-wins); one row (AC#3) |
| Tool event on a non-interactive connection | live-session arm gate | dropped before decode; never folded (fail-closed) |

No failure tears down the connection or the inbound collector — the seam fails closed and silent.
**Nothing in the folds logs the payload** (`name`/`inputSummary`/`resultSummary` may carry sensitive
content — see Security review).

## Testing strategy

Unit only — `./gradlew testDebugUnitTest --tests
"de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"`. Pure data layer: no Compose, no
device. Test-first (RED → GREEN). Reuse the in-place #385 harness verbatim: `FakeSessionPump`,
`RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })`,
`pump.push(...)`, `runCurrent()`, the existing `toolUseEnvelope` / `toolResultEnvelope` builders. Collect
`observeMessages("c1")` into a list and assert the `ThreadItem.MessageItem` whose `message.toolCall` is
the tool row. **No new envelope builder.**

Scenarios (bullets → one test each; developer writes the bodies in the project idiom):

- **`tool_use` → running row (AC#1).** Push `toolUseEnvelope("c1","t1","tu1","Bash","ls -la")`; assert one
  `MessageItem` with `role == Role.Tool`, `toolCall.toolName == "Bash"`, `toolCall.input == "ls -la"`,
  `toolCall.output == ""`, `toolCall.status == Running`.
- **`tool_use` then `tool_result` → done in place (AC#2).** After the running row, push
  `toolResultEnvelope("c1","t1","tu1", isError = false, resultSummary = "files")`; assert **still one
  row at the same position**, now `status == Done`, `output == "files"`, `toolName`/`input` unchanged.
- **Failed result (AC#2).** `tool_result` with `isError = true` → `status == Failed`, `output` = the
  result summary.
- **Correlation by id, not position.** Two concurrent uses `tu1`,`tu2`; a `tool_result` for `tu2`
  completes only the `tu2` row; `tu1` stays `Running`.
- **`tool_result` with no matching `tool_use` (AC#3).** Push only `toolResultEnvelope(...,"tuX",...)`;
  assert **no** tool row appears (thread unchanged), no crash.
- **Out-of-order: result before use (AC#3).** Push `tool_result` for `tu1`, then `tool_use` for `tu1`;
  assert exactly one row, `status == Running` (the early result was dropped), no duplicate.
- **Duplicate `tool_use` (AC#3).** Push `tool_use` `tu1` twice; assert one row. (Optionally: complete it,
  then push `tool_use` `tu1` again → still `Done`, not reset to `Running`.)
- **Duplicate `tool_result` (AC#3).** Complete `tu1`, push the same `tool_result` again; assert one row,
  status stable.
- **Chronological interleave (AC#4).** Push `message`(user) → `tool_use tu1` → `tool_result tu1` →
  `message`(assistant); assert the `ThreadItem` order is `[user message, tool row (done), assistant
  message]` (tool row between the two messages, by arrival order; messages reuse the existing
  `messageEnvelope` builder).
- **Capability gate (fail-closed).** `negotiatedCapabilities = { emptySet() }` (and
  `{ setOf("something_else") }`): push `tool_use`/`tool_result`; assert no tool row ever appears. (Mirror
  the existing #385 gate tests.)
- **Malformed tool payload dropped, collector survives (AC#5).** Push a `tool_use` missing a required
  field, then a valid `tool_use tu1`; assert only the valid one surfaces (the malformed one folded
  nothing and did not tear down the collector).

(`FakeConversationRepository` needs no change — it never receives wire tool events; its seeded tool rows
inherit `status == Done` via the default, which an optional one-liner can assert but isn't required.)

## Open questions

1. **Out-of-order result is dropped, not buffered.** A `tool_result` arriving before its `tool_use`
   leaves the row permanently `Running`. This is the conservative "tolerate without crashing/duplicating"
   contract the AC asks for; a well-ordered stream never hits it. If real streams turn out to reorder
   under load, a small pending-results map (keyed by `toolUseId`, drained on the matching `tool_use`)
   would recover it — out of scope here, flagged for the #388 architect only if the UX needs it.
2. **`turnId` is not used for correlation.** Correlation is by `toolUseId` alone (globally unique per the
   wire SSOT), so `turnId` is carried on the event but ignored by the folds. If the daemon ever reuses a
   `toolUseId` across turns, add `turnId` to the match key — not observed today.
3. **`content` for a tool row = the tool name.** The UI ignores it (renders `toolCall`), so this is a
   pure fallback. If #388 ever wants a richer single-line label, that's a UI derivation over `toolCall`,
   not a data-layer change.

## Security review

**Verdict:** PASS

This ticket is `security-sensitive`: it carries server-authored, untrusted strings (`name`,
`inputSummary`, `resultSummary`) into domain state. Walked every applicable category adversarially with
the assumption the spec has holes.

**Findings:**

- **[Trust boundaries]** No MUST FIX. The untrusted network→process boundary is the **existing** single
  point — `decodeLiveSessionEvent(envelope)` in `RemoteConversationRepository` (#385), decoding through
  the one configured `MobileJson` into `internal` DTOs. This slice adds **no new parse point**: it
  consumes the already-decoded, already-typed `LiveSessionEvent.ToolUse`/`ToolResult` and copies their
  fields **verbatim** into `ToolCall.toolName`/`input`/`output` — **no trim, no parse, no sanitize, no
  re-decode** at the data layer (decode fidelity is the contract; #385's `LiveSessionEvent` doc pins it).
  The strings remain inert data inside a `data class`. **Output-encoding — treating
  `input`/`output`/`toolName` as non-active, non-markup content at render — is the #388 UI consumer's
  job**, named here as a hand-off, exactly as #385/#395 named it. The boundary runs **behind** the
  authenticated, AEAD-encrypted Noise channel, and the `interactive` capability gate is defence-in-depth:
  a buggy/hostile daemon that ignores the server-side fan-out gate still cannot fold a tool row onto a
  non-interactive phone.
- **[Tokens, secrets, credentials]** N/A — no tokens/keys/credentials touched. `toolUseId`/`turnId`/
  `conversationId` are opaque correlation strings, non-secret; no compare-against-secret, no storage.
- **[File / storage operations]** N/A — purely in-memory `StateFlow` folding. `toolUseId` is used only as
  a `List`-membership match key and a `Message.id`, **never** to build a path or filename; no filesystem,
  no persistence, no path concatenation.
- **[Inter-process / Android attack surface]** N/A — `data/model` + `data/repository` only. No Intent,
  deep link, exported component, `PendingIntent`, `ContentProvider`, or WebView added.
- **[Cryptographic primitives]** N/A — no RNG, no hashing, no key handling. `Clock.System.now()` is a
  non-security timestamp (a display field; thread order is arrival order, not a timestamp sort), so it
  needs no `SecureRandom`-grade source.
- **[Network & I/O]** No MUST FIX. No new socket, frame-size, or timeout surface (inherits the existing
  pump/transport). **DoS / unbounded-growth posture:** a `tool_use` folds at most one row **per distinct
  `toolUseId`**; a duplicate `tool_use` is ignored (no growth) and a `tool_result` updates in place (no
  growth). A daemon flooding **distinct fabricated `toolUseId`s** grows `messagesByConversation` — but
  that is the **same** growth posture the existing `message`/`message_chunk` path already accepts for
  daemon-supplied `message_id`s, under the same authenticated-paired-daemon threat model; this slice adds
  no new unbounded surface and no heavier per-entry footprint. A per-conversation row cap, if ever wanted,
  belongs across all of `messagesByConversation`, not tool rows alone. `tryEmit` to `liveSessionEvents`
  is unchanged (#385's `DROP_OLDEST` bound still holds).
- **[Error messages, logs, telemetry]** No MUST FIX — and **load-bearing** for a tool-content path.
  `name`/`inputSummary`/`resultSummary` can carry sensitive session content (commands, paths, command
  output). The folds and the drop branches (`applyToolResult` no-op, `applyToolUse` ignore-if-present,
  the existing `decodeLiveSessionEvent` `catch`) **log nothing** — no `Log.*`/`println`, no payload in any
  exception message — matching the repository's uniform no-log posture. MUST be preserved in
  implementation: **no diagnostic logging of any tool field or raw payload**, including in the
  malformed-drop branch. Code-review should verify zero `Log`/`println`/`print` touch tool-derived data.
- **[Concurrency]** No MUST FIX. No new coroutine/scope — the folds ride the existing single
  connection-scoped `init` inbound collector (the sole writer of `messagesByConversation` alongside
  `sendMessage`'s `update {}`-guarded insert), so insert/update cannot race and there is no
  check-then-mutate TOCTOU (`MutableStateFlow.update {}`, not `.value =`). The thread flow is **cold**
  (`map` + `distinctUntilChanged` per collector), not a subscriber-shared hot flow, so no cross-screen
  leak. State is connection-scoped and dies with the connection scope (#351) — no row survives a reconnect
  to leak into a new connection's thread.
- **[Threat model alignment]** The in-scope threat — a malformed/hostile/duplicated/out-of-order tool
  event from a (possibly compromised) paired daemon — is addressed by fail-closed strict decode (#385,
  drop-without-crash) + the tolerant correlation folds (no crash, no duplicate row, no orphan) + the
  `interactive` capability gate. UI-surface threats (screenshot/overlay/accessibility leakage of rendered
  tool input/output, treating tool summaries as active content) belong to the rendering consumer **#388**
  — out of scope here, named for #388 to own. This slice surfaces typed rows into an in-process flow and
  renders nothing.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08

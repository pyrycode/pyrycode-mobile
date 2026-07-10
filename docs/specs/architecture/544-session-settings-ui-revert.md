# Spec — #544 send session-settings changes from the Status sheet, revert on failure

**Size:** S (confirmed; not split — see § Scope). **Security-sensitive:** yes (see § Security review).

UI/ViewModel slice of #536 (three-way split: **#543 data → #544 UI/ViewModel → #545 e2e**). #543 shipped the repository seam (`ConversationRepository.setSessionSettings`, PR #547); this ticket wires the three Status-sheet controls to it, confirms on ack, and reverts + surfaces a transient snackbar on failure.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:644-683` — **`sendChangeWorkspace` (#561). This is the template.** Its catch triad (`CancellationException` rethrow first, then `RelayErrorException`, then `IllegalStateException` → one-shot error channel; success passive, no side effect) is exactly what `sendSessionSettings` needs. Clone its shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:395-409` — the `changeWorkspaceErrorChannel` + `changeWorkspaceErrors: Flow<Unit>` pair. Clone verbatim as `sessionSettingsErrorChannel` / `sessionSettingsErrors`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:158-175` — the existing `modelOverride` / `effortOverride` / `yoloEnabled` MutableStateFlows and their `override ?: default` combines. **These stay** — they become the optimistic-then-revert state; the handlers change, the flows do not.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:712-722` — the current **VM-local-only** `onModelSelected` / `onEffortSelected` / `onYoloToggled` (the Tier-3 placebo). These three bodies are what this ticket replaces. Signatures are **unchanged**.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:225-267` — the five-arm `state` combine + `initialValue`. Add `currentSessionId` here (from the already-fetched `conv`) — the routing key (see § Design 1).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:116-154` — the four existing one-shot-error → `LaunchedEffect { collect { showSnackbar(...) } }` blocks. Add a fifth for `sessionSettingsErrors`, identical shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:331-350` — the `StatusSheet` invocation. **Note: selecting a model or effort sets `sheetVisible = false` (the sheet closes on tap); the YOLO switch does not.** This determines where each control's revert is *observed* (§ Design 2). No change to this block — the callbacks it forwards (`onModelSelected` / `onEffortSelected` / `onYoloToggled`) keep their signatures.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:371-394` — the `ThreadScreen(...)` call. It wires the VM's four existing error flows (lines 383-386) and the three handlers (392-394). Add one line: `sessionSettingsErrors = vm.sessionSettingsErrors`.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:61-63` — the `live` getter: **throws `IllegalStateException` when no connection is live.** This is what makes the disconnected path surface as ISE (AC #3). Lines ~99-133 are the one-line facade delegations (`= live.rename(...)`). Add the `setSessionSettings` delegation in that block.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:104-125` — the shipped `setSessionSettings(sessionId, model?, effort?, yolo?): Unit` interface method (#543). The contract this ticket calls; `= null` defaults mean a single-control change passes only that field.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:255-265` — the Fake's `setSessionSettingsCalls: List<SetSessionSettingsPayloadDto>` recording (#543). The success-path assertion surface for VM tests.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:9` — `currentSessionId: String` (non-null). The routing key. **But read the next two entries — the live path never resolves it.**
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt:75` **and** `ConversationResponseDto.kt:76` — both map `currentSessionId = ""` (the v2 wire carries no session id). **Load-bearing gap** — against the live daemon `currentSessionId` is empty and a send fails-safe to `session.not_found` → revert + snackbar. Against the `FakeConversationRepository` (the current runtime, per CLAUDE.md, and all tests) it *is* populated. See § Open questions.
- `app/src/main/java/de/pyryco/mobile/data/preferences/Model.kt` + `Effort.kt` — the enums to map to wire strings. `Effort.name.lowercase()` is the exact daemon vocabulary (verified below); `Model` needs an explicit mapping (§ Design 3).
- `app/src/main/res/values/strings.xml:97-100` — the four existing `*_failed` snackbar strings. Add `session_settings_failed` alongside them.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:117` (a `object : ConversationRepository by FakeConversationRepository() { override … }` error-injection double), `:540-541` (constructing `RelayErrorException` / `IllegalStateException` test throws), `:846` (a `launch { vm.xxxErrors.collect { errors += it } }` one-shot collector). The three patterns the revert tests reuse.
- `docs/specs/architecture/543-session-settings-wire.md` — the sibling data-layer spec: the wire shape, the presence contract (omitted field = leave unchanged), and the error taxonomy (`session.not_found` / `protocol.malformed` / `server.binary_offline`, **all** `RelayErrorException`, **no IAE path**).
- Relevant lessons (grep — codegraph doesn't index markdown): `catch(IllegalStateException)` swallows `CancellationException` (**the** load-bearing lesson here — the rethrow-first ordering); `android.util.Log throws in plain JVM unit tests` (N/A — this path logs nothing).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=22-4

The "Run configuration" ModalBottomSheet (node `20:100` under the Bottom Sheets section) — a dark M3 bottom sheet with a drag handle, a "Run configuration" title + close, a **Model** radio group (Opus 4.7 / Sonnet 4.6 / Haiku 4.5, each with a description line), an **Effort** `FilterChip` row (low / medium / high / xhigh / max), a **YOLO mode** "Auto-accept tool calls" row with a trailing `Switch`, and read-only Context-window + Log-data sections. **Its visual design is locked and already fully built in `StatusSheet.kt` — this ticket adds no UI and changes no composable.** The only user-visible addition is the standard transient snackbar on failure, which reuses the existing `SnackbarHost` in `ThreadScreen.kt` (the #452 / #556 / #561 idiom); no new visual design.

## Context

The Status sheet's model / effort / YOLO controls today update ViewModel-local state only (`onModelSelected` etc. just set `modelOverride.value = model`) and never reach the daemon — Tier-3 placebo in the 2026-07-03 backend-gaps audit: the operator believes the session changed and nothing happened. #543 landed the round-trip (`repository.setSessionSettings(sessionId, model?, effort?, yolo?)` — send the changed field, await the `session_settings_updated` ack, throw on server error or not-connected). This ticket:

1. **Sends** the changed setting to `Conversation.currentSessionId` (session-scoped, **not** conversation-scoped — the first session-scoped mobile mutation) on each control change, forwarding only the field that changed.
2. **Confirms optimistically:** the control moves on tap; on ack the value stays (visible on reopen — the daemon does **not** echo settings, so "reflects the persisted state" means "the value that was sent and acked").
3. **Reverts on failure:** a daemon error (`RelayErrorException`) or no live connection (`IllegalStateException`) reverts the control to its last-known value and fires a transient snackbar. The sheet never *settles* on a value the daemon did not confirm.

### Daemon vocabulary (verified against `pyrycode` on `main`)

`internal/relay/v2session.go` — `validEffort` accepts exactly `{"", low, medium, high, xhigh, max}` (a closed enum matching the Kotlin `Effort`); `validModel` is a **shape check, not an allowlist** (1..64 bytes, first byte alphanumeric, charset `[A-Za-z0-9._-]`; `""` accepted = "inherited daemon default") — the daemon forwards the value to claude's `--model` and does not constrain the vocabulary. `protocol-mobile.md` confirms `model`/`effort` empty-string = "inherited daemon default". Consequence: the wire string per `Model` enum entry is a mobile-side product decision, not a daemon-imposed constant (§ Design 3, § Open questions).

## Design

Four production touchpoints (four `.kt` files) + one string resource. **No new files, no new exported types, no composable change, no signature change** on the three public handlers.

### 1. `ThreadViewModel` — the wiring core (the only substantive file)

**a. Route key.** Add `currentSessionId: String` to `ThreadUiState` (default `""`), populated in the existing `state` combine from the already-fetched `conv` (`currentSessionId = conv?.currentSessionId ?: ""`) and in `initialValue`. It carries the send's routing id; the handler reads `state.value.currentSessionId` (the same idiom as `state.value.conversationId` used by `sendMessage`/`sendArchive`). It is a routing field, not rendered by any composable — acceptable, exactly as `mutationsSupported` rides `ThreadUiState` for gating rather than display.

**b. One-shot error signal.** Clone the `changeWorkspaceErrors` pair (:395-409):

```kotlin
private val sessionSettingsErrorChannel = Channel<Unit>(capacity = Channel.BUFFERED)
val sessionSettingsErrors: Flow<Unit> = sessionSettingsErrorChannel.receiveAsFlow()
```

Payload-free (`Unit`) so nothing daemon-derived — least of all `RelayErrorException.message` — can reach the snackbar surface (kdoc mirrors `changeWorkspaceErrors`).

**c. One shared send helper.** A private `sendSessionSettings(model: String? = null, effort: String? = null, yolo: Boolean? = null, revert: () -> Unit)` that reads `state.value.currentSessionId` at entry and launches the guarded send. Contract (clone `sendChangeWorkspace` :671-683, **minus** the `path` arg, **plus** the `revert` call in the two failure catches):

- `try { repository.setSessionSettings(sessionId, model, effort, yolo) }` — success is passive (the optimistic value already displayed stays).
- `catch (CancellationException) { throw e }` — **MUST be the first catch** (`j.u.c.CancellationException extends IllegalStateException` on the JVM). On screen-exit teardown mid-send this rethrows: **no revert, no signal** — the VM is dying and the optimistic value dies with it (a fresh VM re-seeds `override = null`). Asserted by the cancellation test.
- `catch (RelayErrorException) { revert(); sessionSettingsErrorChannel.trySend(Unit) }` — any server error (`session.not_found` / `protocol.malformed` / `server.binary_offline`; all map here — #543 confirmed no IAE path). The caught `message` is **never read**.
- `catch (IllegalStateException) { revert(); sessionSettingsErrorChannel.trySend(Unit) }` — not-connected (facade `live` getter throws before send, or the pump-not-Open `check`).

The `IllegalArgumentException` / decode exceptions are **not** caught (no `conversation.not_found` on this verb; a malformed ack is a fail-loud protocol violation — parity with `sendArchive`/`sendChangeWorkspace`).

**d. The three handlers** (replace the VM-local bodies; signatures unchanged). Each: no-op guard if unchanged → capture the *previous nullable override* → optimistic set → send with a revert lambda that restores the captured previous:

- `onModelSelected(model)`: `if (model == state.value.selectedModel) return; val prev = modelOverride.value; modelOverride.value = model; sendSessionSettings(model = model.wire()) { modelOverride.value = prev }`
- `onEffortSelected(effort)`: same shape over `effortOverride`, `effort = effort.wire()`.
- `onYoloToggled(enabled)`: `if (enabled == yoloEnabled.value) return; val prev = yoloEnabled.value; yoloEnabled.value = enabled; sendSessionSettings(yolo = enabled) { yoloEnabled.value = prev }`.

Capturing the **nullable** override (not the resolved `Model`/`Effort`) preserves the pre-existing "null = track the app-preferences default" semantics on revert. The no-op guard prevents a redundant round-trip when the tapped value already matches the displayed one (a radio/chip `onClick` fires even when already selected).

### 2. Where the revert is observed (no code — behavioural note for the developer + tests)

Selecting a model or effort closes the sheet (`ThreadScreen.kt` sets `sheetVisible = false` in the callback wrapper); the YOLO switch keeps the sheet open. So:
- **Model / effort revert** is observed on **reopen** (the reverted `state.selectedModel` / `state.selectedEffort`) — plus the snackbar, which is visible immediately over the thread. This satisfies AC #1's "the sheet shows the new value after reopening" and AC #2's revert.
- **YOLO revert** is observed **live** (the switch flips back while the sheet is open) — the cleanest control for a `ComposeTestRule` component test if one is written.

### 3. Enum → wire-string mapping (file-private in `ThreadViewModel.kt`)

```kotlin
private fun Effort.wire(): String = name.lowercase()   // low|medium|high|xhigh|max — exact daemon validEffort set
private fun Model.wire(): String = when (this) {
    Model.OPUS_4_7 -> "claude-opus-4-7"
    Model.SONNET_4_6 -> "claude-sonnet-4-6"
    Model.HAIKU_4_5 -> "claude-haiku-4-5"
}
```

Kept file-private to hold the production file count at four (adding `wire()` to `Model.kt`/`Effort.kt` would push it to five and trip the ≥5 commit gate for no benefit — no other consumer needs them). `Effort.wire()` is verified exact against `validEffort`. `Model.wire()` uses the version-pinned canonical ids (the enum is version-specific, and claude reports these exact ids); the daemon shape-validates and forwards them. See § Open questions for the vocabulary caveat.

### 4. `StableConversationRepository` — facade delegation (one method)

Add alongside the other one-line delegations (~:105):

```kotlin
override suspend fun setSessionSettings(
    sessionId: String, model: String?, effort: String?, yolo: Boolean?,
) = live.setSessionSettings(sessionId, model, effort, yolo)
```

`live` throws `IllegalStateException` when no connection is live (:62-63) — so a disconnected change surfaces as ISE before the delegate runs, which the VM catches → revert + snackbar (AC #3). Deferred from #543 by design (its § Scope); this is the documented one-liner.

### 5. `ThreadScreen` — snackbar wiring (one param, one effect)

- Add param `sessionSettingsErrors: Flow<Unit> = emptyFlow()` (defaulted, beside `changeWorkspaceErrors`).
- Add `val sessionSettingsFailedMessage = stringResource(R.string.session_settings_failed)` and a `LaunchedEffect(sessionSettingsErrors, snackbarHostState) { sessionSettingsErrors.collect { snackbarHostState.showSnackbar(sessionSettingsFailedMessage) } }` — identical to the four existing blocks. The fixed local string keeps anything exception-derived out of the un-secured Activity window.

### 6. `MainActivity` — one line

Add `sessionSettingsErrors = vm.sessionSettingsErrors,` to the `ThreadScreen(...)` call (beside `changeWorkspaceErrors = vm.changeWorkspaceErrors,`, ~:386).

### 7. `strings.xml` — one string

`<string name="session_settings_failed">Couldn\'t update the run configuration. Try again.</string>` (matches the tone of `change_workspace_failed`).

### Data flow

```
StatusSheet control tap → ThreadScreen callback → vm.onModelSelected / onEffortSelected / onYoloToggled
  ├─ optimistic: modelOverride/effortOverride/yoloEnabled.value = new        (sheet shows new value)
  └─ sendSessionSettings(changedField) { revert }
       └─ repository.setSessionSettings(state.value.currentSessionId, model?, effort?, yolo?)   [facade → live #543]
            ├─ success (ack)        → passive; optimistic value stays (confirmed)
            ├─ RelayErrorException  → revert(); sessionSettingsErrorChannel.trySend(Unit)
            ├─ IllegalStateException→ revert(); sessionSettingsErrorChannel.trySend(Unit)
            └─ CancellationException→ rethrow (no revert, no signal — teardown)
                 ▲
sessionSettingsErrors ─→ ThreadScreen LaunchedEffect ─→ snackbarHostState.showSnackbar(fixed string)
```

## State + concurrency model

- **Single source of state per control preserved.** `modelOverride` / `effortOverride` / `yoloEnabled` remain the sole holders; the optimistic set and the revert both write only these, and the exposed `state.selectedModel` etc. stay derived from them. No parallel mutable copy.
- **Scope.** The send runs on `viewModelScope` (`sendSessionSettings`'s `launch`), cancelled on screen exit; the `CancellationException`-first catch keeps teardown clean (no spurious revert/snackbar on a dying VM). No new scope, no `GlobalScope`.
- **Concurrency between controls is race-free:** each control's revert lambda captures *its own* flow's previous value, and the three flows are independent — two in-flight changes to *different* controls never interfere.
- **Same-control rapid re-change (accepted, non-blocking).** Tapping one control twice before the first send resolves could let a stale first-send's revert clobber the second optimistic value. For model/effort this is practically unreachable — selecting one closes the sheet, so a second change requires reopening, by which time the first send has resolved. For YOLO (sheet stays open) a stale revert could briefly show the wrong toggle, self-correcting on the next successful change or on reopen. No defensive sequencing is added (evidence-based fix selection — no observed failure; belt-and-suspenders would add a generation token for a race the sheet-close behaviour already all but eliminates). Noted for code-review awareness.
- **Dispatcher.** Inherited from `setSessionSettings` (main-safe suspend over the shared inbound collector; no manual dispatcher switch). No IO on the main thread.

## Error handling

| Failure | Surfaced as | VM handling | AC |
|---|---|---|---|
| Not connected (facade `live` null, or pump not Open) | `IllegalStateException` | revert + `sessionSettingsErrors` | #3 |
| `session.not_found` / `protocol.malformed` / `server.binary_offline` | `RelayErrorException` | revert + `sessionSettingsErrors` | #2 |
| Teardown mid-send (screen exit) | `CancellationException` | rethrow (no revert, no signal) | #4 correctness |
| Malformed `session_settings_updated` ack | decode exception (#543) | **not caught** — fail-loud protocol violation | (defensive) |

The UI surface is a **single fixed local string** (`session_settings_failed`); the server-supplied `RelayErrorException.message` is never read, logged, or shown — the daemon's error strings are fixed constants anyway (#543 § Security), but the client's non-disclosure is independent of that. No `Log.*` on any path (the `android.util.Log`-throws-in-JVM-unit-tests lesson stays N/A).

## Testing strategy

Unit (`./gradlew testDebugUnitTest`) drives every AC; an optional `ComposeTestRule` YOLO-revert component test is the natural home for the "component test" phrasing in AC #5 but is not required (a ViewModel test satisfies "Component / ViewModel tests … for at least one control"). Reuse the `ThreadViewModelTest` helpers: `makeVm(handle, repo, source)`, the `object : ConversationRepository by FakeConversationRepository() { override … }` throwing double, and a `launch { vm.sessionSettingsErrors.collect { errors += it } }` collector. Scenarios (inputs → expected; developer writes bodies in-idiom):

**Send-on-change (AC #1) — Fake records the request:**
- Change model → `fake.setSessionSettingsCalls` holds exactly one entry with `model = "claude-opus-4-7"` (or the chosen wire value), `effort == null`, `yolo == null`, `sessionId == <conv currentSessionId>` (proves only the changed field is sent, routed to the session id).
- Change effort → one entry, `effort = "high"`, others null. Change YOLO → one entry, `yolo = false/true`, others null.
- On success the override **persists**: `state.selectedModel` (or `yoloEnabled`) still shows the new value after the call settles (no revert on the happy path).

**Revert on daemon error (AC #2) — inject a `setSessionSettings` that throws `RelayErrorException(code = "protocol.malformed", …)`:**
- After the change, `state.selectedModel` (or `state.yoloEnabled`) has reverted to the prior value **and** `sessionSettingsErrors` emitted exactly once.

**Revert on disconnected (AC #3) — inject a `setSessionSettings` that throws `IllegalStateException("not connected")`:**
- Same assertion: reverted value + one `sessionSettingsErrors` emission. (At least one control must cover both #2 and #3 per AC #5; covering model + YOLO across the two is ideal.)

**Cancellation is not mis-surfaced (correctness guard, mirrors the #556/#561 tests):**
- A `setSessionSettings` that suspends indefinitely, then cancel the collecting scope / VM: **no** `sessionSettingsErrors` emission (the `CancellationException` rethrow precedes the typed catches). A bare `catch (IllegalStateException)` would false-fire here.

**Sheet-never-shows-unconfirmed (AC #4) — is the sum of the above:** the happy path keeps the confirmed value; every failure path reverts. No separate test.

## Scope

Production source files (Kotlin, excluding tests / md / spec / resources): **4** —
1. `ui/conversations/thread/ThreadViewModel.kt` (handlers + send helper + error channel + `currentSessionId` field + `wire()` mappings),
2. `ui/conversations/thread/ThreadScreen.kt` (one param + one `LaunchedEffect` + one string ref),
3. `data/repository/StableConversationRepository.kt` (one facade delegation),
4. `MainActivity.kt` (one wiring line).

Plus `res/values/strings.xml` (one string — a resource, not a `.kt` file). New exported types / composables: **0** (`sessionSettingsErrors` is a new public property, not a new type; the `wire()` helpers are file-private; the three handlers keep their signatures, so **no consumer/call-site cascade** — `StatusSheet.kt` and its invocation are untouched). New reject branches: **2** (the two failure catches, in one shared helper). Consumer fan-out: **1** new param threaded through **1** call site (`MainActivity`). ~60 production LOC + ~180 test LOC ≈ **~240 total**. Every § 1 red line clears with wide margin; the ≥5-prod-file commit gate clears at **4**. **Size S confirmed** — PO's `size:s` upheld, not overridden.

## Open questions

None blocking. Deliberate boundaries, all documented above:

- **`currentSessionId` is `""` in the live path (pre-existing upstream gap).** Both v2 conversation DTO mappers hardcode `currentSessionId = ""` (the v2 wire carries no session id — see § Files to read). Against the live daemon a settings send therefore routes to an empty session id → `session.not_found` → the design's fail-safe revert + snackbar (never a crash). Against `FakeConversationRepository` — the current runtime (CLAUDE.md: "UI still runs against `FakeConversationRepository`") and **every** unit test — `currentSessionId` is populated, so all ACs are exercised and pass. Resolving the live-path session id (the source is `session_transition` / #336-adjacent session-id resolution) is **out of scope for this UI-wiring ticket** and does not change any code here — when it lands, `currentSessionId` becomes non-empty and the same wiring works unchanged. Flagged for the operator running #545 (which, per that child's DoD, may be `@Ignore`-manual: settings acks leave no durable UI artifact).
- **Model wire vocabulary is not daemon-constrained.** `validModel` is a shape check only and the daemon forwards the value to claude's `--model`; #544's observable success is the **ack** (which fires for any shape-valid string). The spec picks the version-pinned canonical ids (`claude-opus-4-7`, …) to honour the version-specific enum. If operator testing shows claude's `--model` rejects that form, switch `Model.wire()` to the family aliases (`opus` / `sonnet` / `haiku`) — a one-line change, invisible at the ack layer this ticket operates on. Not a correctness blocker for the pipeline.
- **The Settings screen's "Default YOLO" hardcoded-"off" label** is a different surface, explicitly out of scope per the ticket body.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. #544 decodes **nothing** from the wire — #543 owns the single `SessionSettingsUpdatedPayloadDto` decode boundary; #544 observes only `Unit` (success) or a thrown exception and surfaces only a fixed local string. Outbound it forwards its own VM state (`currentSessionId`, from the conversation list) and the operator's **bounded-enum** selection (`Model.entries` / `Effort.entries` / a `Boolean`, mapped by `wire()`) — no free-text or attacker-controlled value crosses to the wire, and the daemon re-validates (`validModel` / `validEffort` / interactive gate) regardless.
- **[Tokens, secrets, credentials]** N/A — no tokens / keys / credentials on this path. It rides the established Noise_IK session (`data/network`, `data/crypto` untouched). `currentSessionId` is a routing id, not a secret, and is never logged.
- **[File / storage operations]** N/A — no filesystem access and no persistence. The optimistic overrides are in-memory `MutableStateFlow` state (reset on VM recreation); nothing is written to DataStore or disk. `model` / `effort` are never concatenated into a path or argv on the client (the argv-injection defense is server-side, `validModel`, #845).
- **[Inter-process / Android attack surface]** N/A — no new `Activity` / `Service` / `Receiver` / deep-link / `PendingIntent` / provider / WebView. The one `MainActivity` edit is a single wiring line into the existing `ThreadScreen`; the snackbar draws in the existing Activity window with a fixed local string (nothing sensitive rendered).
- **[Cryptographic primitives]** N/A — no RNG / hashing / key handling / comparison introduced. Correlation reuses #543/#316's existing monotonic `Envelope.id` counter, untouched.
- **[Network & I/O]** No findings. No new socket / timeout / TLS / frame-size config — reuses #543's `setSessionSettings` over the existing `SessionPump` / OkHttp transport. #488's `failAllPending` bounds an in-flight change at teardown to a prompt `IllegalStateException` (caught → revert + snackbar), never a hang. Sends are user-paced and no-op-guarded (a redundant identical tap sends nothing; model/effort close the sheet, throttling further) — no flood/DoS vector.
- **[Error messages, logs, telemetry]** No findings — the security-relevant heart. The failure surface is a **single fixed local string** (`session_settings_failed`); the server-supplied `RelayErrorException.message` is **never** read, logged, or shown. The one-shot signal is payload-free (`Unit`), so nothing daemon-derived can ride it to the un-secured Activity window. **No `Log.*`** on any path; no telemetry added.
- **[Concurrency]** No findings. The send runs on `viewModelScope`; the `CancellationException`-first catch keeps screen-exit teardown from mis-firing a revert/snackbar. A single `StateFlow.value` write is atomic — no partial-write corruption; the documented same-control rapid-re-change staleness is a bounded, self-correcting **functional** limitation (value staleness, not memory-safety / TOCTOU), all but eliminated by the sheet-close-on-select behaviour. `sessionSettingsErrors` is a cold single-consumer `receiveAsFlow()` (no cross-screen leak). Nothing is persisted, so process death loses only unconfirmed optimistic state — the correct fail-safe.
- **[Threat model alignment]** The relevant mobile-wire threat — a malicious / compromised relay returning a crafted `session_settings_updated` or `error` frame — is contained. A crafted ack fails #543's typed decode (throws, uncaught here → fail-loud, no state effect) or is discarded by #543; #544 sees only success/exception. A crafted `error` maps to a swallowable `RelayErrorException` → revert + fixed-string snackbar with **no text leak** and **no IAE-crash path** (this verb never produces `conversation.not_found` → IAE, unlike `rename`). The worst a hostile relay achieves is feature-denial (a change appears to fail), never compromise, crash, or exfiltration. Faithfully forwarding the operator's own bounded-enum selection is correct (the daemon is the value authority). Out of scope (named): the live-path `currentSessionId = ""` resolution (upstream, #336-adjacent), the model wire-vocabulary tuning (operator), and server-side value validation (#845).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10

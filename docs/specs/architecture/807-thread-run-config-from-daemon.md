# 807 — Source the thread's model and effort from the daemon's session settings

Retires the thread's `AppPreferences`-backed model/effort sourcing in favour of the two authoritative
daemon readings that already ship: `ConversationRepository.observeSessionSettings` (#590) for the saved
values and the routing session id, and `ConversationRepository.observeModelMenu` (#791) for the
selectable choices. The footer line and the Status sheet stay the rendering surfaces and both read one
state, so they agree by construction.

## Files read

| Path | Symbol | Why it matters |
| --- | --- | --- |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` | `ThreadUiState`, `ThreadViewModel.state`, `modelOverride` / `effortOverride` / `selectedModelFlow` / `selectedEffortFlow` / `runConfigFlow`, `onModelSelected` / `onEffortSelected` / `onYoloToggled` / `sendSessionSettings` | The sourcing and the write this ticket replaces. `state` is at Kotlin's five-arity `combine` ceiling; `ThreadViewModel.draft`'s KDoc records the sibling-flow escape. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` | `observeSessionSettings`, `observeModelMenu`, `refreshSessionSettings`, `setSessionSettings`, `SessionSettings`, `ModelMenu`, `ModelMenuRow`, `EffectiveEffort` | The contracts. Their KDoc pins every rule this plan obeys: `null` is unavailable and never a fallback, `sessionId == ""` is read-only, `rows` are in claude's order, `droppedModels` is never recomputed, and row text is untrusted. |
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` | `setSessionSettingsReading`, `setModelMenu`, `sessionSettingsRefreshes`, `setSessionSettingsCalls` | The test seam is already complete — #590/#791 landed it. No production edit here; the Fake's unseeded default is exactly the "unavailable" case. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` | `StatusSheet`, `StatusSheetContent`, `ModelRow`, `EffortChipRow`, `Model.description` | The choice surface. `Model.entries` / `Effort.entries` are the device guesses this ticket removes. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` | `ThreadScreen`, its `ThreadStatusRow` call and its `StatusSheet` call | The two render sites and the `onModelSelected` / `onEffortSelected` parameter types. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt` | `ThreadStatusRow` | Already takes `model: String, effort: String` — the footer needs no signature change for the label swap, only for the new pending cue. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` | `thread` | Both `ThreadViewModel` construction sites; they pass the `AppPreferences` this ticket makes dead. |
| `docs/knowledge/features/status-sheet.md` | § `EffortChipRow`, § Model descriptions, § Recomposition / stability | Records why `Model.description()` is private and why the sheet compares enum identity rather than label strings — both premises this ticket removes, so the doc's reasoning is what tells me what replaces them. |
| `docs/knowledge/features/conversation-repository.md`, `docs/knowledge/features/thread-status-row.md` | contract + the row's spec'd Figma divergence | The row already diverges from `16:58` deliberately (#602/#603); this ticket must not silently re-add a third segment. |
| `docs/specs/architecture/590-read-session-settings.md`, `791-model-list-retention.md`, `544-session-settings-ui-revert.md` | — | The producing half of both readings, and the send-and-revert path this ticket re-routes. |

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=16-8

Node `16:8` is the whole thread screen; its footer draws `Actions ⌃  Auto ⌃  Opus ⌃  Max ⌃  Cxt: 84%` —
a row of per-control buttons whose model and effort names are **server-sourced labels**, not device enum
entries. Those buttons are a separate ticket; this one keeps the shipped surfaces — the monospace
`ThreadStatusRow` (`16:58`, already a spec'd two-segment divergence per #602) and the `StatusSheet`
(`20:100`) — and changes only the strings and the choice list they carry. No layout, token, typography
or component change: every colour stays on `MaterialTheme.colorScheme.*` and every style on
`MaterialTheme.typography.*` exactly as `status-sheet.md` § Color & typography mapping records them.

## Context

The thread reads `AppPreferences.defaultModel` / `defaultEffort` — the three-entry `Model` and five-entry
`Effort` device enums — and the Status sheet offers those same entries. Neither is what the owning daemon
configured, and a value this server never published is re-validated and refused server-side. Both
authoritative readings already ship and both are unconsumed. This ticket connects them and routes the
write to the session the settings read names.

`Settings` keeps `Model` / `Effort` for its own device defaults; `ModelPickerDialog`,
`EffortPickerDialog` and `SettingsViewModel` are untouched. #651 owns the settled applied-effort display
from `effectiveEffort`, which this ticket does not render. Phone-local recall of a previous choice is a
separate follow-up.

**Worth an ADR? No.** This is a consumer wiring change; the decisions it rests on (`null` means
unavailable, row text is untrusted, `""` session id is read-only) are already recorded in the two
contracts' KDoc and in #590/#791's specs.

## Sizing — two boundaries exceeded, deliberately

Re-counted against this written plan: 5 production files (`ThreadViewModel.kt`, `StatusSheet.kt`,
`ThreadScreen.kt`, `ThreadStatusRow.kt`, `AppModule.kt`; `MainActivity.kt` needs none — it binds method
references, which re-adapt), 3 new exported types, 5 acceptance criteria, 4 reject branches — all inside
the boundary. Two lines are over:

- **Total written work ≈ 950 lines** against a ceiling of 800, close to the refiner's own ~850 estimate. The excess is the fixture cascade: `ThreadViewModelTest` carries 84 references to this state and `StatusSheetTest` 54.
- **Consumer call sites: 18** against a ceiling of 10 — one production `StatusSheet` call, 5 in-file previews, 12 `StatusSheetContent` calls in `StatusSheetTest`.

Not split, on the floor rule. Every candidate boundary produces a child whose only consumer is its
sibling: a read-only slice would leave the sheet comparing a daemon-authored string against a `Model`
enum entry, which is not a state anything can verify, and would require keeping `modelOverride` alive
purely to be deleted by the next slice. The read, the choices, the write and the two surfaces that
render them are one `ThreadRunConfig`; the refiner reached the same conclusion on the estimate line.
The overage is stated here rather than engineered around, and the 12-call test cascade is reduced by a
defaulted fixture helper — hygiene, not a re-count of the boundary.

## Design

### Where the state lives

`state`'s `combine` is at its five-arity ceiling, but the run-config arm is itself a nested `combine` —
so the new sourcing fits inside that one arm rather than needing a sixth or a sibling flow. The arm's
inputs are exactly five: `observeSessionSettings`, `observeModelMenu`, a pending-model flow, a
pending-effort flow, and the existing `yoloEnabled`. `ThreadUiState` keeps one field for the whole
surface.

### New types (co-located in `ThreadViewModel.kt`, beside `ThreadUiState`)

```kotlin
/** One selectable model — the daemon's row reduced to what the sheet renders plus the write argument. */
data class ThreadModelChoice(
    val value: String,                          // ModelMenuRow.value, verbatim; sent back, never rendered
    val label: String,                          // ModelMenuRow.displayName, made inert (below)
    val detail: String,                         // ModelMenuRow.resolvedModel, made inert; "" when redundant
    val effortChoices: List<ThreadEffortChoice>,
)

/** One selectable effort level of one row. */
data class ThreadEffortChoice(
    val value: String,                          // wire level, verbatim; sent back
    val label: String,                          // the same level, made inert for display
)

/** The thread's whole run-configuration surface. */
data class ThreadRunConfig(
    val choices: List<ThreadModelChoice> = emptyList(),
    val menuAvailable: Boolean = false,
    val droppedModels: Int = 0,                 // as the daemon reported; never recomputed
    val hiddenChoices: Int = 0,                 // this client's own render cap cut (below); distinct provenance
    val settingsAvailable: Boolean = false,
    val savedModel: String = "",
    val savedEffort: String = "",
    val pendingModel: String? = null,
    val pendingEffort: String? = null,
    val sessionId: String = "",
)
```

Derived (computed properties, deliberately outside `equals`): `selectedModel` / `selectedEffort`
(`pending ?: saved`), `selectedChoice` (the row whose `value` equals `selectedModel`), `effortChoices`
(the selected row's, empty when none), `modelLabel` / `effortLabel` (the footer strings), `pending`, and
`writable` (`sessionId.isNotEmpty()`).

**The three display states, kept apart.** `settingsAvailable == false` ⇒ the label is `"unknown"`;
a saved value of `""` ⇒ `"default"` (the contract's "no override, inherited default", which is not a
device guess); otherwise the matching row's `label`, falling back to the saved value **made inert** when
the menu published no matching row. `savedEffort` renders through the same treatment.
`effectiveEffort` is read by nobody here.

### The trust boundary — one function, named

Every string this ticket renders — `displayName`, `resolvedModel`, each `effortLevels` element, and the
`SessionSettings.model` / `effort` fallbacks — is claude-authored text that crossed the subprocess
boundary unsanitized (`ModelMenuRow`'s KDoc says so in terms, and names the client as the render boundary
that owes the sanitization). Nothing in this repo strips control characters today, so this ticket
introduces the first such helper, file-private in `ThreadViewModel.kt`:

```kotlin
/** A daemon-authored string reduced to inert display text: no control characters (terminal escapes
 *  included), length-bounded. The write argument is never taken from here — it stays verbatim. */
private fun String.inert(): String
```

Contract: drop every `Char.isISOControl()` character (which covers `ESC`, the C0/C1 ranges, newline and
tab — a multi-line label would break the single-line footer as well as carry an escape), then
`take(MAX_RUN_CONFIG_LABEL_CHARS)`, a file-private constant in the established
`MAX_WORKSPACE_LABEL_CHARS` / `MAX_PLAUSIBLE_ATTEMPTS` shape. Applied once, in the ViewModel's mapping,
so `ThreadModelChoice.label` / `.detail` and `ThreadEffortChoice.label` are inert by construction and the
sheet cannot forget. `value` deliberately skips it: it is the write argument, sent verbatim and never
rendered.

**Render sink.** These strings go to `androidx.compose.material3.Text` only — never `MarkdownText` (which
this package ships and which would turn daemon text into links and tables), never a `WebView`, an
`AnnotatedString` annotation, a URL, a filename, a `testTag`, a map key or a log field. The retained menu
is keyed by conversation id, as #791 requires.

**Render cap.** `ModelMenu.rows`' producer cap is daemon-side and explicitly not a wire constant, and the
Model section is a plain `Column`, not a lazy list — so an oversized menu from a buggy or hostile daemon
would lay out every row at once. The mapping keeps the first `MAX_RENDERED_MODEL_CHOICES` rows and
reports the remainder in `hiddenChoices`, a field of its own so the daemon's `droppedModels` keeps its
provenance and is never recomputed. `StatusSheetContent`'s root `Column` also gains
`verticalScroll(rememberScrollState())` — the `SettingsScreen` / `MobileModal` idiom — so a menu larger
than the sheet stays reachable instead of clipping.

### `ThreadUiState`

`selectedModel: Model` and `selectedEffort: Effort` are replaced by a single `runConfig: ThreadRunConfig`.
`currentSessionId` is **deleted**: `ThreadRunConfig.sessionId` — sourced from `SessionSettings.sessionId`
— is the authoritative routing key, and two routing sources would be a second place to get it wrong.
`yoloEnabled` and `mutationsSupported` are unchanged.

**This re-routes YOLO too.** `onYoloToggled` shares `sendSessionSettings`, so it now addresses the same
authoritative id and inherits the read-only gate. That is a consequence of making the key authoritative,
not a second deliverable; the alternative is keeping `Conversation.currentSessionId` alive purely for one
control, which is the incoherence the ticket names.

### Clearing a pending selection

A pending value is cleared by an **arriving settings reading**, never by the acknowledgement — AC #3's
"an acknowledgement alone never becomes the confirmed reading". Mechanically, the settings arm carries an
`onEach` that clears both pending flows, so every reading (subscription, reconnect, `session_transition`,
or the `refreshSessionSettings` a settled write asks for) ends the pending state. `onEach` rather than a
second collector: `observeSessionSettings` is cold and per-collector, so a separate subscription would
send a second `request_session_settings` frame per thread entry.

### The write

`onModelSelected(value: String)` / `onEffortSelected(level: String)` keep #544's shape and gain two
guards: no-op when `!writable` (empty `sessionId` ⇒ read-only) and no-op while a write is pending.
`sendSessionSettings` takes the session id explicitly from `runConfig`, and on success calls
`repository.refreshSessionSettings(conversationId)`. `SetSessionSettingsPayloadDto`'s nullable-omission
contract is preserved untouched — only the changed field is non-null.

Failure keeps #544's behaviour exactly: `RelayErrorException` / `IllegalStateException` ⇒ clear the
pending (which restores the last confirmed reading) plus the payload-free `sessionSettingsErrors`
one-shot; `CancellationException` rethrown first; the caught `message` never read.

### `StatusSheet`

Signature moves off the device enums onto the pre-sanitized choices:

```kotlin
fun StatusSheet(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,                       // droppedModels + hiddenChoices, summed at the call site
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    pending: Boolean,
    enabled: Boolean,
    yoloEnabled: Boolean,
    onYoloToggled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
)
```

`StatusSheetContent` mirrors it, keeping the shell/`*Content` split the sibling sheets use. The sheet
becomes a dumb renderer: the ViewModel owns the trust boundary, so `StatusSheet` no longer imports
anything from `data/` and never sees a raw `ModelMenuRow`.

Behaviour, all following the honest-unavailable idiom #601 established for the Context-window section:

- Model section iterates `choices` in order, one `ModelRow` each, `selected = choice.value == selectedModel`. `detail` replaces the deleted private `Model.description()`; when blank the second line is omitted.
- Menu unavailable ⇒ the section renders an inert "unavailable" label and caption instead of rows. Present-but-empty `rows` is a different, equally legal reading and says claude offered nothing.
- `notListedModels > 0` ⇒ a caption naming that count beside the shown count. Never recomputed from `choices.size`.
- The content `Column` scrolls, so a long menu cannot clip the YOLO and Context-window sections below it.
- Effort section iterates `effortChoices`. Empty ⇒ an inert "no levels published for this model" line, no chips — a row publishing no levels offers no effort choice. An unset saved effort simply matches no chip and blocks nothing.
- `pending` ⇒ the Model and Effort section headers gain an `"· applying…"` suffix and every control is disabled; `enabled = false` (read-only session) disables them without the suffix.

### `ThreadScreen` and `ThreadStatusRow`

`ThreadScreen`'s `onModelSelected` / `onEffortSelected` become `(String) -> Unit`; the `Model` / `Effort` /
`label` imports go. The footer reads `state.runConfig.modelLabel` / `effortLabel`. `ThreadStatusRow` gains
one `pending: Boolean = false` parameter that lowers its existing `.alpha(0.85f)` while a write is in
flight — the footer's share of "pending stays distinguishable". `MainActivity` needs no edit: it binds
method references, which re-adapt.

### `AppModule`

`ThreadViewModel`'s `appPreferences` parameter is dead once the two preference-backed flows go, so it is
removed along with both `AppModule.thread` call sites' argument. `AppPreferences` itself and every other
consumer are untouched.

## State + concurrency model

- Both readings are collected only as arms of the existing `runConfigFlow`, itself one arm of `state` — `stateIn(viewModelScope, WhileSubscribed(5_000))`, so screen exit tears the subscriptions down and a re-entry drives a fresh read. No new scope, no new job, no `GlobalScope`, no `runBlocking`.
- `pendingModel` / `pendingEffort` are `MutableStateFlow<String?>` with one mutator each plus the `onEach` clear — the single-writer posture `yoloEnabled` already has. Both the mutator and the clear run on the main dispatcher with no suspension between the read and the write, so the guard is not a check-then-act window. A reading that arrives between the selection and its ack clears the pending early; that is deliberate — a reading is authoritative over an unacked optimistic value, which is the same rule AC #3 states for the ack itself.
- The second tap during an in-flight write is refused by the `pending` guard, so two writes for the same control can never be outstanding at once.
- The send stays a `viewModelScope.launch`; screen-exit cancellation neither reverts nor signals, exactly as #544 documented.
- Per-conversation and per-host scoping is structural: the ViewModel is destination-scoped and both flows are asked by `conversationId`, and each host connection has its own repository, so a new context starts with `null` readings and `null` pendings. Nothing is persisted.

## Error handling

| Failure | Result |
| --- | --- |
| Settings reading unavailable (`null`) | `settingsAvailable = false`; labels read `"unknown"`; `sessionId = ""` ⇒ controls read-only |
| Model menu unavailable (`null`) | `menuAvailable = false`; the sheet says so; no device-enum substitution |
| Empty `sessionId` | No frame sent at all; no error surfaced (a read-only session is not a failure). One content-free `RelayLog.d { "event=run_config_write_skipped reason=no_session" }` so a silently dropped tap stays diagnosable — static codes only, in the shape `event=history_ask_failed` already uses in this file |
| `RelayErrorException` from the write | Pending cleared → previously confirmed state restored; `sessionSettingsErrors` one-shot → fixed-string snackbar |
| `IllegalStateException` (not connected) | Same as above |
| `CancellationException` | Rethrown before the typed catches; no revert, no signal |

## Testing strategy

Unit (`app/src/test/.../ThreadViewModelTest.kt`, `./gradlew testDebugUnitTest`, `runTest` + the Fake's
existing seams). The six device-default cases (`selectedModel_followsAppPreferencesDefault`,
`..._reemitsWhenAppPreferencesDefaultChanges`, `onModelSelected_overridesPerConversation...`,
`..._overrideWinsOverSubsequentDefaultChange`, and the two effort twins) assert the behaviour this ticket
retires and are **deleted**, not rewritten. New scenarios:

- Labels come from the reading and the menu; an absent reading reads unknown and never a device default.
- Choices are exactly the published rows in wire order; effort levels come from the selected row; a row with no levels offers none; an unset saved effort still allows selection.
- `droppedModels` is carried as reported, and an oversized menu is capped into `hiddenChoices` without touching it.
- A row whose `displayName` / `resolvedModel` / level carries control characters, a terminal escape or an over-long run renders inert and bounded, while the `value` sent back stays byte-identical to what the daemon published.
- A selection sends only its own field to `SessionSettings.sessionId`, not `Conversation.currentSessionId`.
- An empty `sessionId` sends nothing.
- A settled write asks for a fresh reading (`sessionSettingsRefreshes`) and the pending survives the ack until a reading lands.
- A refused write restores the previously confirmed state and fires the payload-free signal without leaking the message.
- Two conversations, and a reading for one, leave the other's state empty.

Compose UI (`app/src/androidTest/.../StatusSheetTest.kt`): the twelve `StatusSheetContent` call sites move
behind one defaulted fixture helper so each case names only what it varies. Cases: rows render in wire
order with labels and details; tapping a row reports its `value`; the selected row reports selected
semantics; effort chips come from the selected row; no rows ⇒ unavailable copy, no chips ⇒ the no-levels
line; pending disables the controls and shows the applying cue. The existing YOLO and Context-window
cases keep their assertions.

Not an operator-facing new flow — it changes what two shipped surfaces display — so no new rung-3
scenario. AC #5 assigns live verification to #679.

## Open questions

1. Does any assertion outside the deleted block read `ThreadUiState.currentSessionId` (as opposed to `Conversation.currentSessionId`)? Resolve by grep at the top of Phase B; the field is being deleted.
2. Does the footer want `"default"` or a dash for a `""` saved value? Settled in Phase B against how the sheet renders the same state; both surfaces must agree.

## Documentation handoff

The ticket body carries no **Documentation handoff** section and no documentation-only acceptance
criterion. The documentation phase owns the follow-up edits this change implies —
`docs/knowledge/features/status-sheet.md` (§ Shape, § `EffortChipRow`, § Model descriptions and
§ Recomposition / stability all describe the retired enum sourcing),
`docs/knowledge/features/thread-status-row.md`, `docs/knowledge/features/thread-screen.md` and
`docs/knowledge/features/app-preferences.md` (the thread is no longer a consumer of
`defaultModel` / `defaultEffort`). **Pending for the documentation stage**; not edited here.

## Security review

**Verdict:** PASS (first pass FAILed on three MUST FIX findings, all revised into the plan above before this commit)

**Findings:**

- [Trust boundaries] **MUST FIX — fixed.** The first draft said the ViewModel maps rows to "sanitized" strings without defining the word, and left two paths rendering daemon text raw: the footer's fallback to `SessionSettings.model` when the menu publishes no matching row, and `savedEffort`. `ModelMenuRow`'s KDoc is explicit that `displayName` / `value` / `resolvedModel` / `effortLevels` arrive unsanitized and that the client owes the sanitization. Fixed by § The trust boundary — one function, named: a file-private `String.inert()` with a stated contract (drop every `Char.isISOControl()`, then `take(MAX_RUN_CONFIG_LABEL_CHARS)`), applied once in the mapping so every rendered field — the two fallbacks included — is inert by construction, while `value` stays verbatim because it is the write argument. Nothing in this repo strips control characters today, so this is the first such helper rather than a reuse.
- [Trust boundaries] **MUST FIX — fixed.** The draft named no render sink, and this very package ships `MarkdownText`, which would turn daemon-authored text into links and tables. The plan now names `androidx.compose.material3.Text` as the only sink and forbids `MarkdownText`, `WebView`, `AnnotatedString` annotations, URLs, filenames, `testTag`s, map keys and log fields by name. The retained menu stays keyed by conversation id per #791.
- [Threat model — hostile daemon frame] **MUST FIX — fixed.** `ModelMenu.rows`' producer cap is daemon-side and explicitly not a wire constant, and the Model section is a plain `Column`, not a lazy list, so an oversized menu would lay out every row at once. The mapping now caps at `MAX_RENDERED_MODEL_CHOICES` and reports the remainder in a `hiddenChoices` field distinct from the daemon's own `droppedModels` (which keeps its provenance and is still never recomputed), and `StatusSheetContent` gains `verticalScroll` so a long menu cannot clip the sections below it.
- [Error messages, logs, telemetry] No findings. No row text, model value, effort level or session id reaches any log. The one added line is content-free with static codes (`event=run_config_write_skipped reason=no_session`), matching this file's existing `event=history_ask_failed` shape; the failure path keeps #544's payload-free `Flow<Unit>` and fixed-string snackbar, so the server-supplied `RelayErrorException.message` is still never read. No telemetry.
- [Concurrency] No findings. Every job is `viewModelScope`-owned and torn down by `WhileSubscribed(5_000)`; no new scope, no `GlobalScope`, no `runBlocking`. The `pending` guard and the `onEach` clear both run on the main dispatcher with no suspension between read and write, so there is no check-then-act window, and the `pending` guard makes two outstanding writes for one control impossible. The reading-beats-unacked-optimism ordering is stated in § State + concurrency model rather than left to the implementer.
- [Network & I/O] No findings. No client, timeout, TLS setting, URL or frame-size cap is touched. One frame class is newly sent from this layer — the `refreshSessionSettings` a *settled* write asks for. A failed write does not refresh, so an error cannot spin into a send loop, and the ask is fire-and-forget, non-throwing and a no-op without a connection.
- [Tokens, secrets, credentials] Not applicable, concretely: this ticket creates, stores, reads and transmits no token, key or credential. `SessionSettings.sessionId` is a routing identifier inside an established Noise session, not a secret, and it is never rendered, logged or persisted. The change *removes* two `AppPreferences` reads rather than adding any device-storage surface.
- [File / storage operations] Not applicable, concretely: nothing is written to or read from disk, no path is built from any input, and no state is persisted — the whole surface is connection-scoped and dies with the ViewModel.
- [Inter-process / Android attack surface] Not applicable, concretely: no `Activity` / `Service` / `BroadcastReceiver` / provider / `<intent-filter>` / deep link / `PendingIntent` / push handling is added or changed. The WebView question is answered under Trust boundaries above.
- [Cryptographic primitives] Not applicable, concretely: no primitive, RNG, key, nonce or handshake step is touched. The write rides the existing `ConversationRepository.setSessionSettings` through the shipped `NoiseIkSession`; the vendored `noise-java` path is untouched.
- [Threat model — malicious relay] No findings. The relay is content-blind and on-path; a dropped, delayed or reordered settings frame lands as `null`, which this design renders as *unknown* and never as a device default or another conversation's value — so a hostile relay can withhold the reading but cannot substitute one.
- [Threat model — UI-side leakage] OUT OF SCOPE. The sheet and footer render a model name and an effort level, not secrets, and the thread already renders message bodies without `FLAG_SECURE`; adding a screenshot flag to this one surface would be both inconsistent and broader than this ticket. Belongs with any future app-wide screenshot-policy ticket, not here.
- [Threat model — token theft from disk] Not applicable: see Tokens above — nothing of this feature reaches disk.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

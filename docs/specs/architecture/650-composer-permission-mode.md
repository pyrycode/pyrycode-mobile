# #650 — Offer the server permission modes in the composer

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `ThreadRunConfig`, `runConfig`, `runConfigFlow`, `sessionSettings` (the pending-clearing `onEach`), `onModelSelected`, `onYoloToggled`, `sendSessionSettings`, `skipUnlessWritable`, `RunConfig`, `inert` — the run-config surface this ticket extends and the YOLO flag it deletes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `FooterControl`, `FooterMenu`, `footerMenu`, `footerControlEnabled`, `ThreadComposerFooter`, `FooterButton` — where the third control lands.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt` → `OptionsOverlay` — reused unchanged; a `selectedValue` matching no option marks nothing selected, which is what an unrecognised mode needs.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` (`openControl`, the overlay `onSelect` branch, the `StatusSheet` call) — wiring.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` → `StatusSheet`, `StatusSheetContent`, `YoloRow`, `PreviewSheet` — the YOLO switch to remove.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `ThreadScreen` call binding `vm::onYoloToggled`.
- `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` → `SetSessionSettingsPayloadDto` — gains `permission_mode`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `setSessionSettings`, `observeSessionSettings`, `refreshSessionSettings`, `SessionSettings`, `ModelMenuRow.supportsAutoMode`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `setSessionSettings`, `observeSessionSettings` (`onStart { emit(null) }`, `flatMapLatest` over `settingsRevision`, no `distinctUntilChanged` after the read), the `TYPE_SESSION_TRANSITION` arm (`updateCurrentSessionId` then `bumpSettingsRevision`).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `setSessionSettings`, `switchToLive` (emits the absent value on a host change; no dedupe).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` → `setSessionSettings`, `setSessionSettingsCalls`, `refreshSessionSettings` (records, never re-emits).
- `app/src/test/.../thread/ThreadViewModelTest.kt`, `FooterMenuTest.kt`, `ThreadViewModelRePairTest.kt` (the small-file VM test shape), `data/repository/RemoteConversationRepositoryTest.kt` → `startSetSessionSettings`; `app/src/androidTest/.../components/StatusSheetTest.kt`, `thread/ThreadComposerFooterTest.kt`.
- `../pyrycode/docs/protocol-mobile.md` § `set_session_settings` / `permission_mode` (#1687), § `session_settings`, § Error codes `session.not_found` — the contract; not restated here.
- `docs/knowledge/features/thread-composer-footer.md`, `options-overlay.md`, `status-sheet.md` — footer / overlay / sheet conventions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=115-3678

The `Input footer button` is the same shape as Model and Effort: a `M3/body/small` label in `Schemes/Primary` with a small up-chevron 4dp to its right. In the mobile `Input footer` (`110-3494`) the permission button (`Auto`) sits **before** Model and Effort (`Actions · Auto · Opus · Max · Cxt`); Actions and Cxt belong to other tickets. The choice list is the existing `Options overlay` (`533-1958`), reused unchanged.

## Context

The only permission control today is the Status sheet's YOLO switch — a local `false`-seeded flag that never reads the daemon. This ticket replaces it with a footer control that shows only the mode the current child confirmed (`SessionSettings.permissionMode`) and writes through `set_session_settings`. `Settings → Default YOLO` stays untouched and feeds nothing here.

**Size overage, stated:** 10 production files against the 8-file line. The five data-layer files each get a 1–5 line forward of the new write field, whose only consumer is this control, so the floor rule keeps them in this ticket (the refiner's estimate says the same). Every other line of the table holds.

## Design

### Data layer (forward only)

- `SetSessionSettingsPayloadDto` gains `@SerialName("permission_mode") val permissionMode: String? = null` under the same presence contract, plus an `init` `require(yolo == null || permissionMode == null)` — the wire rejects a frame carrying both, so the client refuses to build one (deterministic net under the ViewModel's rule).
- `ConversationRepository.setSessionSettings(sessionId, model, effort, yolo, permissionMode: String? = null)`; Remote encodes it, Stable delegates, Fake records it. Verbatim forward, no validation in the data layer beyond the both-fields guard.

### Vocabulary (in `ThreadComposerFooter.kt`)

`internal enum class PermissionModeOption(val wire: String, val label: String)` in display order:
`default` Manual approval · `acceptEdits` Auto-approve edits · `auto` Auto approval · `plan` Plan · `dontAsk` Approved actions only · `bypassPermissions` Bypass approvals. `fromWire(String): PermissionModeOption?`.

### `ThreadRunConfig` additions

- `permissionMode: String = ""` — the latest reading's value verbatim; `""` = no confirmation (or a stale context, below).
- `pendingPermission: String? = null` — a requested mode whose write or settle is still running. Drives only the pending mark and the control's enable gate; **never** the label.
- `ThreadModelChoice.supportsAutoMode: Boolean = false` — copied from `ModelMenuRow`.
- `pending` keeps its meaning (model/effort only), so model/effort gating is unchanged.

`permissionModeLabel(runConfig): String?` (footer file): `null` when `permissionMode` is `""`; the option label for a known value; otherwise `permissionMode.inert()` (make `inert` `internal`).

### Footer

- `FooterControl` gains `Permission`. `footerMenu(Permission)`: `null` when `permissionMode` is `""`; else every `PermissionModeOption`, `auto` only when `selectedChoice?.supportsAutoMode == true`, `selectedValue = permissionMode` (an unknown value selects nothing and is never an option), `notListed = 0`.
- `footerControlEnabled(Permission)`: `writable && pendingPermission == null && menu != null`. Model/Effort branches unchanged.
- `ThreadComposerFooter` draws the permission button first, only when its label is non-null; `pending = pendingPermission != null`; click label `R.string.thread_footer_change_permission`. KDoc no longer says the sheet is YOLO's home.

### Screen / sheet / activity

- `ThreadScreen`: new `onPermissionModeSelected: (String) -> Unit = {}`; overlay `onSelect` routes `FooterControl.Permission` there. `onYoloToggled` removed, as are the `yoloEnabled` / `onYoloToggled` args to `StatusSheet`.
- `StatusSheet`: `YoloRow` and its two parameters removed (from `StatusSheet`, `StatusSheetContent`, previews).
- `MainActivity`: binds `onPermissionModeSelected = vm::onPermissionModeSelected`.

### ViewModel

- Delete `yoloEnabled`, `onYoloToggled`, `ThreadUiState.yoloEnabled` and the private `RunConfig` wrapper; `runConfigFlow`'s fifth input becomes `pendingPermission`, and `runConfig(...)` fills `permissionMode` from the reading and `supportsAutoMode` per row.
- **Session-reset staleness** (in the `state` combine, which already has the conversation): when `conv.currentSessionId` is non-empty and differs from `runConfig.sessionId`, `permissionMode` is blanked. The Remote folds a `session_transition`'s new id into `currentSessionId` synchronously before bumping the settings read, so the old mode hides immediately and returns only when a reading for the new session lands. Order-independent: equality, not "which arrived first". Model and effort labels are not touched.
- `fun onPermissionModeSelected(value: String)`: ignore unless `PermissionModeOption.fromWire(value)` is known; ignore `auto` unless the selected row supports it; ignore when `value == permissionMode` (confirmed mode sends nothing), when `permissionMode` is `""` (no control is shown), when a permission write is outstanding, or when not writable (`skipUnlessWritable`). Then set `pendingPermission` and launch the write job.
- **Write job** (one `Job` held with its target session id and requested mode):
  - send `setSessionSettings(sessionId, yolo = true)` for `bypassPermissions`, else `setSessionSettings(sessionId, permissionMode = value)` — never both;
  - **ack** → run the settle rule: `refreshSessionSettings` at once, wait for the next reading, stop if it reports the requested mode for the target session; otherwise `delay(500)` and repeat, all inside `withTimeoutOrNull(15_000)`. Reads are serialized: the next refresh is issued only after the previous reading arrives. When the loop ends, clear `pendingPermission`;
  - **`RelayErrorException` / `IllegalStateException`** → clear `pendingPermission`, `sessionSettingsErrorChannel.trySend(Unit)` (existing snackbar), one `refreshSessionSettings`;
  - `CancellationException` rethrown first, as in `sendSessionSettings`.
- **Reading tick:** the existing `sessionSettings.onEach` also publishes `(seq, reading)` to a private `MutableStateFlow` the settle loop waits on — no second subscription, so no second `request_session_settings`.
- **Context cancellation** (same `onEach`): a `null` reading (subscription head on host switch / owning-host reconnect, or a failed read) or a reading whose `sessionId` differs from the job's target cancels the write job and clears `pendingPermission`. Cancelling mid-send abandons the reply waiter, so a late ack from the old context reaches nothing and cannot start a settle. A conversation switch is a new ViewModel; the old scope cancels.

## State + concurrency model

- All work on `viewModelScope` (Main). One permission job at most; `pendingPermission != null` blocks a second write, and the canceller clears it.
- Settle cadence: ≤ ~31 reads per ack worst case, each serialized, bounded 15 s. Reading ticks arrive only while `state` is collected (`WhileSubscribed`); with no collector the loop simply expires.
- No optimistic label anywhere: label = latest (non-stale) reading only.

## Error handling

- Refusal (`session.not_found` on a dormant session, `protocol.malformed`) and not-connected: fixed-string snackbar via the existing `sessionSettingsErrors`, one re-read, label unchanged. Exception messages are never read.
- Settle expiry: silent; the label shows whatever the last reading said (the daemon may ack without changing the child — not hidden, not fixed here).
- Content-free logs: `event=permission_write outcome=acked|refused|failed`, `event=permission_settle outcome=confirmed|expired reads=N`. No mode, session id or message.

## Testing strategy

- **Unit, `FooterMenuTest`:** hidden (`null` menu/label) on `""`; each of six labels; `auto` offered only with support; unknown value → inert label, no option selected, not listed; enable gate (pending permission, no session).
- **Unit, new `ThreadViewModelPermissionTest`** (fake-backed plus a small scripted double whose `refreshSessionSettings` emits a queued reading or holds): label from reading, hidden on `""` with non-empty session id; bypass → `yolo=true` & no `permissionMode`; other choice → `permissionMode` & no `yolo`; same mode, empty session, unknown value and unsupported `auto` send nothing; label unchanged while pending and after ack until a reading reports the new mode; settle re-reads immediately, then every 500 ms, stops on the requested mode, survives an early old-mode reading, stops at 15 s; serialized (a held read issues no second refresh); refusal and ISE → one re-read + one error signal; a `null` reading cancels retries and hides; a session-id change cancels retries; `currentSessionId` ≠ reading session hides the label while model/effort labels stay.
- **Unit, `RemoteConversationRepositoryTest`:** `permission_mode` encoded and `yolo` omitted; both fields → `IllegalArgumentException`, nothing sent.
- **Unit, existing `ThreadViewModelTest`:** YOLO cases deleted; the three `setSessionSettings` overrides take the new parameter.
- **Compose UI:** `ThreadComposerFooterTest` — permission button shows its label and opens with the six-mode menu; absent when `permissionMode` is `""`. `StatusSheetTest` — YOLO cases removed; asserts no YOLO row.
- **e2e:** the live Plan → Bypass approvals → Manual approval transition is #687's (per the ticket); no scenario lands here.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/thread-composer-footer.md` (third control, settle rule, staleness), `docs/knowledge/features/status-sheet.md` (YOLO row removed), `docs/knowledge/features/options-overlay.md` (third anchor). The ticket names no further doc requirement.

## Open questions

1. Does the scripted double's same-value reading reach the VM `onEach` in production? Yes by reading: Remote emits per read (no dedupe after `flatMapLatest`), Stable's `switchToLive` has no dedupe. Confirm with the double during Phase B.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No MUST FIX. Two daemon-authored inputs reach this surface: `SessionSettings.permissionMode` and `ModelMenuRow.supportsAutoMode`. A known mode renders a fixed client label. Any other non-empty value goes through `inert` (C0/C1 stripped, 128-char cap) into `Text` only, and it is never an option, never a write argument and never a log field. A hostile daemon can print a misleading label, including a literal "Manual approval". It could already do that by reporting `default`: the protocol calls the field a claim rather than a guarantee, and the label grants nothing. `supportsAutoMode` only decides whether `auto` is offered. If the daemon lies, the write is refused and the snackbar shows.
- [Trust boundaries / escalation] No MUST FIX. Every write argument comes from the client's own `PermissionModeOption` table. `onPermissionModeSelected` re-validates against that table, so no daemon string and no screen-supplied string outside it can become a `permission_mode` or `yolo: true`. `yolo: true` is sent only for an explicit Bypass approvals choice. Nothing reads `AppPreferences.defaultYolo`, and no reading, ack or retry ever produces a write.
- [Trust boundaries / wire rule] SHOULD FIX, landing in Phase B: the `SetSessionSettingsPayloadDto` `init` guard makes a both-fields frame unconstructible. The verifier should check that the guard and its test landed. It is deterministic, beneath the ViewModel's own one-field rule.
- [Tokens / storage / crypto / IPC] Not applicable by design. The ticket adds no storage, token, key, intent, deep link, WebView or crypto path. It rides the existing Noise session through `setSessionSettings`.
- [Network & I/O] No findings. The settle loop sends at most one read at a time and stops after 15 s, about 31 reads per ack at worst. A refusal adds one read. A held or never-answered read creates no pile-up, because the next refresh waits for a reading.
- [Error messages, logs] No findings. The snackbar shows the existing fixed string, and `RelayErrorException.message` is never read. The logs carry only static codes and a read count, never a mode, session id or model.
- [Concurrency] No MUST FIX. At most one permission job exists, owned by `viewModelScope`. The job is cancelled on a `null` reading, on a reading for another session and when the ViewModel clears. Cancelling mid-send abandons the reply waiter, so a late ack cannot start a settle in the new context. The canceller clears `pendingPermission`, so a later write in the new context cannot be cleared by the old job. All mutations run on Main, and the only state across suspension points is the job's own.
- [Concurrency / stale context] No findings. After a session reset the label hides whenever the conversation's `currentSessionId` differs from the reading's session. This fails closed: a stale mismatch hides the button rather than showing the old mode.
- [Threat model: accidental escalation] OUT OF SCOPE. One tap on Bypass approvals sends `yolo: true` without a second confirmation. The ticket asks for desktop PR #1555's directly selectable Bypass approvals, and the Status-sheet switch it replaces was also a single tap. Adding a confirmation step is a product decision for a future ticket, not this one. A daemon restart remains the revocation point, and the dormant-session refusal is enforced by the daemon.
- [Threat model: hostile relay] No findings. The relay can delay or drop a reply. A delayed ack stops at the settle window or at cancellation. A dropped read expires silently, and the label keeps the last real reading.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

- **2026-09-23, Phase B.** Open question 1 is resolved as planned. The Remote emits every read result with no dedupe after `flatMapLatest`, and Stable's `switchToLive` adds none, so a same-value re-read reaches the ViewModel's `onEach` and advances the settle loop. `ThreadViewModelPermissionTest`'s scripted double relies on that. One design delta follows from deleting YOLO: `sendSessionSettings` loses its now-dead `yolo` parameter and serves model and effort only. The permission path is `sendPermissionMode`. No contract changed.

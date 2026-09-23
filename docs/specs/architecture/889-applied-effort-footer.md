# Show Claude's applied effort in the composer footer (#889)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig` (`selectedEffort`, `effortLabel`, the private `label` helper, `INHERITED_RUN_CONFIG_LABEL`) — the one place both render sites read effort from; this ticket changes its effort derivation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runConfig` (file-private fold), `forLiveSession`, `sessionSettings` (the pending-clearing `onEach`), `onEffortSelected`, `sendSessionSettings`, `String.inert()` — where the reading enters `ThreadRunConfig` and where rollback already happens.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `SessionSettings.effectiveEffort`, `EffectiveEffort` (`Unavailable` / `NotReported` / `Applied`) — #590's three-state decode this ticket consumes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `footerMenu` (Effort branch), `footerControlEnabled`, `FooterButton` (its `stateDescription`), `ThreadComposerFooter`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` → `StatusSheet`, `StatusSheetContent`, `EffortChipRow`, `Caption` — the sheet's effort section, where the explanation line goes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `StatusSheet(...)` call — passes the resolved explanation.
- `../pyrycode/docs/protocol-mobile.md` § `session_settings` — the `effective_effort` contract (string / explicit null / omitted). Cited, not restated.
- `../pyrycode-desktop/docs/specs/architecture/1549-applied-effort.md` and desktop commit `1fdc1476` (PR #1554) → `selectDisplayedEffort` — the display rule adopted here: pending > non-empty applied > saved fallback (only on omitted/empty reading); explicit null never falls back.
- `docs/knowledge/features/thread-composer-footer.md` § Sourcing, § Error handling ("Known interaction" NIT on pending clearing) — the pending rule this ticket keeps unchanged.
- Tests: `ThreadViewModelTest` (`settings()` fixture, `runConfig_readingWithNoOverride_readsAsInheritedDefaultNotUnknown` which asserts the retired "default" effort label), `ThreadViewModelPermissionTest` (VM harness to mirror), `FooterMenuTest` (`config(...)` builder), androidTest `ThreadComposerFooterTest`, `StatusSheetTest`.

No in-flight `origin/feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (footer `110:3494`)

The footer row is unchanged: body-small primary labels with a 14dp up-chevron (`Actions · Auto · Opus · Max · Cxt`), the effort button being the `Max` slot. Only the effort button's text, which option the overlay marks selected, and an explanation (button state description + one Status-sheet caption line) change; touch layout and theme tokens stay as they are.

## Context

`SessionSettings.effectiveEffort` (#590) is decoded but unused; the footer shows the saved choice, and "default" for an empty one. The daemon reports Claude's applied effort separately from the saved choice (protocol `session_settings.effective_effort`). This adopts desktop #1549 + PR #1554's display: show what Claude actually runs, fall back to the saved choice only when no applied value is known, and explain the fallback. Display-only; the write path is unchanged.

## Design

### `ThreadRunConfig` (ThreadUiState.kt)

- New field `appliedEffort: EffectiveEffort = EffectiveEffort.Unavailable` — the reading verbatim (raw, like `savedEffort`; made inert only at label time).
- New `enum class EffortNote { SelectedRunningUnavailable, DefaultRunningUnavailable, NotReported }` — which explanation applies; the UI layer maps it to a string resource.
- `selectedEffort` (the overlay/sheet selection **and** the `onEffortSelected` same-value guard) becomes the displayed effort:
  - `pendingEffort` if non-null;
  - else `Applied(v)` with `v` non-empty → `v`;
  - else `NotReported` → `""` (no selection, never the saved value);
  - else (`Unavailable`, `Applied("")`) → `savedEffort` (may be `""`).
- `effortNote: EffortNote?`:
  - `null` when `!settingsAvailable`, when a pending tap is showing (the existing "Applying" description covers it), or for a non-empty applied value;
  - `NotReported` for explicit null;
  - `Unavailable` / `Applied("")` → `SelectedRunningUnavailable` if `savedEffort` is non-empty, else `DefaultRunningUnavailable`.
- `effortLabel`: `!settingsAvailable` → `UNKNOWN_RUN_CONFIG_LABEL` (unchanged); `selectedEffort` empty → new `EFFORT_PLACEHOLDER_LABEL = "Effort"`; else `selectedEffort.inert()`. It no longer uses `INHERITED_RUN_CONFIG_LABEL` ("default"), which stays for the model label. An applied value outside the published levels shows as its label; `footerMenu`'s `selectedValue` then matches no option, so nothing is marked selected — no code needed.
- `savedEffort` keeps its meaning; its KDoc is corrected (it no longer says `effectiveEffort` belongs to another ticket).

### ViewModel (ThreadViewModel.kt)

- `runConfig(...)` sets `appliedEffort = settings?.effectiveEffort ?: EffectiveEffort.Unavailable` — whole-reading replacement, so a later reading that omits the key drops a previous applied value.
- `forLiveSession(liveSessionId)` also blanks `appliedEffort` to `Unavailable` when the reading's `sessionId` differs from the conversation's live session — a late reply for a replaced session never shows the old session's applied effort (display falls back to the saved choice with the "running effort unavailable" note).
- `onEffortSelected` / `sendSessionSettings` unchanged. The write argument is still a tapped published level; `effectiveEffort` is never sent. Rejection clears `pendingEffort` exactly as today, so the display re-derives from the last reading — applied value, fallback, or no selection — without extra state.
- Pending clearing unchanged: a reading clears `pendingEffort`; after an ack the existing `refreshSessionSettings` reading settles the display, and because applied outranks saved, an acknowledged selection cannot mask a fresh applied value.

### UI

- `ThreadComposerFooter.kt`: `internal fun EffortNote.textRes(): Int` (`@StringRes`). `FooterButton` gains `note: String? = null`; its `stateDescription` is "Applying" while pending, otherwise `note`. The effort button passes the resolved note.
- `StatusSheet` / `StatusSheetContent` gain `effortNote: String? = null`, rendered as one `Caption` line below the effort chips.
- `ThreadScreen` passes `effortNote = state.runConfig.effortNote?.let { stringResource(it.textRes()) }` to the sheet.
- `strings.xml`: `thread_effort_note_selected_unavailable` "Selected effort. The running effort is unavailable.", `thread_effort_note_default_unavailable` "Claude's default applies. The running effort is unavailable.", `thread_effort_note_not_reported` "Claude reports no effort parameter."

Controls/gates unchanged: `footerMenu` Effort still offers exactly `effortChoices` in published order and is `null` (read-only button) when none; `footerControlEnabled` keeps `writable && !pending`.

## State + concurrency model

No new flows, jobs or scopes. `appliedEffort` rides the existing `sessionSettings` → `runConfigFlow` → `state` combine; `forLiveSession` already runs in the `state` combine with `conv?.currentSessionId`. The VM is per conversation, so another conversation's saved choice is never read or cleared. A host switch / reconnect heads the subscription with a `null` reading → `settingsAvailable = false` → "unknown", no applied value.

## Error handling

No new failure modes. A refused write keeps today's revert + snackbar path. Unavailable / null readings are data, rendered with an explanation, never substituted with "High", "Not set", "default" or a device value.

## Testing strategy

- **Unit, pure** (`FooterMenuTest`, extending its `config(...)` builder with `appliedEffort`): saved and applied disagree → label/selection = applied, overlay `selectedValue` = applied; `Unavailable`, `Applied("")`, `NotReported`, each with and without a saved choice → selection, label ("Effort" when empty), note; pending outranks applied and clears the note; applied value outside published levels → label shows it, no option selected; no published levels → effort menu `null`.
- **Unit, ViewModel** (new `ThreadViewModelAppliedEffortTest`, Fake repository): an older daemon's reading (`Unavailable`) shows the saved choice with its note; a reading alone sends no `setSessionSettings`; a late reply for a replaced session (conversation `currentSessionId` ≠ reading `sessionId`) never shows the old applied value; rejection rolls back to the pre-tap display for an applied value and for no selection; after a confirmed write the refresh reading's applied value wins over the tapped level.
- Update `runConfig_readingWithNoOverride_readsAsInheritedDefaultNotUnknown`'s effort assertion ("default" → "Effort"): it asserts the retired contract.
- **Compose** (focused managed-device run): one `ThreadComposerFooterTest` case — a `NotReported` config renders "Effort" with the not-reported state description; one `StatusSheetTest` case — the sheet renders the note caption.
- No rung-3 scenario: the ticket names #545 as the live proof of applied effort.

## Open questions

- None blocking. Does the Fake repository's `setSessionSettingsReading` plus conversation `currentSessionId` let the VM test stage a replaced session? If not, stage it through the existing Fake conversation seed; recorded in Revisions if it changes the approach.

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/thread-composer-footer.md` § Sourcing — the `effortLabel` bullet and the "no fallback" paragraph: effort now shows pending > non-empty applied > saved fallback (omitted/empty reading only), "Effort" replaces "default", explicit null clears the selection; `forLiveSession` also blanks the applied effort; the button's state description carries the explanation.
- `docs/knowledge/features/status-sheet.md` — the Effort section's new explanation caption.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — `EffectiveEffort.Applied.value` is daemon-authored; it reaches the UI only through `ThreadRunConfig.effortLabel`, which applies `String.inert()` (ISO-control strip + 128-char bound), and through `selectedEffort`, which is compared by equality against published option values and never rendered unsanitized. The Status sheet renders chip labels (already inert) and a client-owned note string. No daemon text reaches a log, `testTag`, `contentDescription`/`stateDescription` (those are static resources), URL or filename. SHOULD FIX in Phase B: a unit test that a hostile applied value (control characters, oversize) renders inert.
- [Trust boundaries / write path] No findings — `onEffortSelected` still only sends a level the user tapped from the published list; `appliedEffort` has no path into `setSessionSettings`. The same-value guard now compares to the displayed value, which can only suppress a write, never create one.
- [Tokens] No findings — no tokens, keys or storage touched.
- [File / storage] No findings — nothing persisted; no remembered-choice store (that is #686).
- [Android surface] No findings — no intents, deep links, WebViews or providers.
- [Crypto] No findings — no primitives touched.
- [Network & I/O] No findings — no new frames; decode bounds live in #590's `readEffectiveEffort`, which rejects non-string values.
- [Logs] No findings — no new log lines; effort values stay out of `RelayLog`.
- [Concurrency] No findings — no new coroutine; staleness across session replacement is an equality check in `forLiveSession`, not an arrival-order race; host switch/reconnect resets via the `null` subscription head.
- [Threat model] Hostile daemon frame: a lying applied value can only change a label; it grants nothing and triggers no write or turn. Relay/UI-leakage threats unaffected.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

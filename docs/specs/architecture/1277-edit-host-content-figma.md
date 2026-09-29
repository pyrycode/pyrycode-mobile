# Edit host content alignment (#1277)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `EditHostModal`, `IdentityRow`, `HostNameField`, `UnpairAction`, `boundedText` — current form, clamps, action callbacks and Figma geometry.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell`, `ModalCancelButton` — landed shell, pinned/scrolling content, IME and footer treatment.
- `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` → `HostEditorModal` — generic error resolution and controller state binding.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt` → `EditHostModalTest` — existing behavior, compact width, font scale and inert text proofs.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalCaptureTest.kt` → `editHostAtFigmaViewport` — device screenshot pattern at the exact viewport.
- `app/src/main/java/de/pyryco/mobile/ui/theme/ModalColors.kt` → `modalFieldContainer`, `modalFieldText` — existing Figma matched field roles.
- `docs/knowledge/features/host-editor.md` → `HostEditorModal` — display-only state, generic errors and unpair ownership.
- `docs/knowledge/features/mobile-modal.md` → `MobileModal` — content and shell contract.
- `docs/knowledge/features/development-verification.md` → Compose evidence — actual emulator capture and evidence limits.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2078

Inspected the Edit host component `487:2078`, dark 412 × 892 composition `533:2369` (child `533:2334`), and Input large `347:6446` on 2026-09-29. The dark frame has a centered content group: two 20 dp inert inline rows at 12 dp spacing, a label 8 dp above a 52 dp filled name well, and an outlined Unpair host button after 8 dp extra top space. The existing `MobileModal` owns the header, fixed navy surface, scrolling content and footer; the form owns only these four children.

## Context

The host editor already supplies bounded display text and correct rename/unpair transitions. This ticket aligns the content's visible geometry to the current dark design without changing storage, target selection or the shared shell. Figma provides the default state; loading, error, confirmation, compact width, large text and keyboard behavior use the existing product contract.

## Design

Keep `EditHostModal`'s signature and callback routing. Make `IdentityRow` use natural label width followed by a weighted, single-line ellipsized value, matching the inline Figma labels while retaining finite width for untrusted display text. Keep the row's merged read-only semantics and `boundedText` clamp. Retain the explicit name label, existing buffer key and submit behavior; size/style its editable well to the 52 dp Figma field using existing theme roles. Keep the outlined Unpair host action at its visible 40 dp reference size with a separate 48 dp touch target that does not overlap the name field or footer. No new shell padding or inset.

The content remains in `MobileModal`'s scroll column. Its pinned layout centers the group on the 412 × 892 viewport; compact and IME constrained layouts scroll it. No new state, coroutine or I/O path is introduced.

## State and concurrency model

The name buffer remains `remember(serverIdentity)` in `EditHostModal`. `HostEditorController` retains its `StateFlow`, `viewModelScope` jobs and cancellation on owner exit/dismissal; this visual change introduces no job or dispatcher switch.

## Error handling

`HostEditorModal` continues mapping failure flags to generic strings. Loading disables submit through `MobileModal`; error text remains in its live region. The unpair confirmation continues swapping content in place and using the shell footer for the decision.

## Testing strategy

- Add or adjust focused `EditHostModalTest` assertions for inline label/value geometry, well/action dimensions, blank-name submit, touch routing, loading/error, inert identity values, compact width and enlarged text; run RED then GREEN.
- Run affected existing `EditHostModalTest`, `SettingsScreenTest` and channel list host-editor tests under Robolectric.
- Use a focused managed-device capture test to save an actual 412 × 892 emulator image and compare it with the `533:2369` dark Figma render, with labelled overlay/difference evidence under `app/src/androidTest/assets/host-content-1277/`. Check keyboard and compact state for clipping. The visual fixture requires real pixels, so it is device-only.
- Run focused lint, assembly and androidTest compilation. No rung-3 scenario: this is a presentation adjustment to an existing operator flow with no new daemon interaction.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/mobile-modal-callers.md` in its Edit host caller section with the final content geometry, responsive behavior and evidence reference. The ticket names no other reference-document path.

## Open questions

- Whether the existing Material text field can be tuned to the 52 dp visible Figma well without reducing its 48 dp touch target; resolve from a device render before finalizing.
- Whether `487:2078`'s light component or `533:2369`'s dark composition controls the palette; the fixed dark product requirement selects `533:2369`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `HostEditorModal` passes display-only strings to `EditHostModal`; `boundedText` clamps them before text layout and merged semantics. Keep identity and relay as inert `Text`, and retain the raw identity as the name-buffer key.
- [Tokens and secrets] `HostEditorState` carries no pairing token or private key. The form must not log, render or capture a secret; screenshot fixtures use synthetic identity and relay values.
- [Storage, Android surface, crypto, network and I/O] The plan changes no storage, intent, exported component, primitive, endpoint or protocol path; `HostEditorController` retains the existing unpair operation and target id.
- [Errors and telemetry] `HostEditorModal` continues to pass only generic resource strings to the shell's live region. `logEditHostEvent` stays content-free and debug gated.
- [Concurrency] The field's remembered state remains keyed on raw `serverIdentity`; loading/error recompose without resetting the draft. The controller's scoped write and compare-and-set behavior is unchanged.
- [Threat model] Pairing-derived identity and relay text remain length bounded, read-only and never interpreted as a URL. UI screenshot/accessibility exposure is limited to the same public display fields already shown by this form; no credentials are added.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-29

## Revisions

- 2026-09-29: The M3 `TextField` retained its 56 dp minimum in the failing geometry test. `HostNameField` now uses the same `BasicTextField` well pattern as `ChannelFormFields`, giving the 52 dp reference height while retaining an editable, labelled field and IME Done submission.
- 2026-09-29: Natural label widths match the Figma viewport, but the enlarged-text 320 dp regression clipped the server label. `IdentityRow` uses the existing weighted split below 320 dp of content width and natural labels at the reference width. `UnpairAction` draws a 40 dp outline inside a 48 dp M3 `Surface` click target so the visible reference size and touch floor both hold.

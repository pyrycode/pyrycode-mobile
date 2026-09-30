# Permission choices and session grant Figma alignment (#1300)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` → `PermissionModalOverlay`, `AlwaysAllowOffer`, `ModalOptionButton`: caller-owned content and current semantics/routing.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileGateModal`, `MobileModalShell`, `ModalCancelButton`, `ModalSubmitButton`: secure shell, scroll/IME behavior and component action styling.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt` → `MuteNotificationsRow`: existing tertiary checkbox pattern.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` and `Theme.kt` → dark `primary`, `tertiary`, `onBackground`, `modalContainer` roles.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt` → existing option, arm, grant, Cancel and Back assertions.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalCaptureTest.kt` → full-device 412 × 892 capture setup and strict pixel guard.
- `docs/knowledge/features/permission-modal-overlay.md` → render-only trust boundary and known missing permission frame.
- `docs/knowledge/features/mobile-modal.md` → gate security and existing keyboard/compact behavior.
- `docs/knowledge/features/development-verification.md` → device capture, real system bars and test placement requirements.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6771

Inspected on 2026-09-30: checkbox frame `347:6771`, its label child `347:6215`, button states `489:1876`, and mobile modal `533:2369`. The dark checkbox uses a 20 dp rounded tertiary outline and tertiary check, a 12 dp label gap and label-medium text; buttons use primary fill/on-primary text or primary outline/text, body-large medium labels and 6 dp corners. The existing full-height navy shell follows `533:2369`; there is **no full-screen permission reference**. The armed non-default state is also unreferenced, so its existing tonal distinction remains a documented design gap, never an asserted exact Figma match.

## Context

`PermissionModalOverlay` already owns option order, session grant display and callback routing. This slice aligns their visuals while retaining the hardened `MobileGateModal` shell. The issue's estimate is about 300 written lines and one production source file; the sketch is about 350–450 lines including tests, capture helper, evidence and this plan, with one production Kotlin file, one vector resource, no exported types, no consumer changes, four criteria and no new reject branches. The six ticket boundaries hold. No in-flight feature branch overlaps the planned source or test files after fetching origin.

## Design

- Keep `PermissionModalOverlay`'s order and callbacks. `AlwaysAllowOffer` retains its single checkbox-role row target and inert plain-text rules. Its visible 20 dp box uses a tertiary outline and the Figma selector path when checked; `labelMedium` and 12 dp gap match `347:6215`. The touch row remains at least 48 dp.
- `ModalOptionButton` stays stateless. Apply the existing shared action recipe locally because the options fill the available width: default uses primary fill/on-primary text; ordinary choices use primary outline/text; both use body-large medium text, 6 dp corners and Figma content padding while retaining a 48 dp touch floor. Armed choice stays visually below the default with its existing tonal treatment and accessibility state description.
- Keep prompt, context and rule text as plain `Text`. Do not change `MobileGateModal`, any ViewModel decision branch or option-id semantics.
- Add a dark 412 × 892 preview for inspection, and a device capture of this caller. Save the emulator and current component references plus a labelled overlay or difference image at matching logical scale. Document the absence of a permission frame and the armed-state design gap in the PR evidence.

## State and concurrency model

All state remains hoisted: `armedOptionId` and `alwaysAllowAccepted` enter `PermissionModalOverlay`, and taps call the existing callbacks. No new coroutine, flow or persistence path is added. The shell's existing dialog lifecycle, scrolling and IME insets remain authoritative.

## Error handling

No I/O or parsing is added. An option label or rule from the daemon remains length-bounded upstream and rendered as inert plain text. The existing payload-free send-error snackbar and Cancel behavior are unchanged.

## Testing strategy

- Add a shared Compose assertion for the Figma checkbox geometry/role and option styling at default and enlarged text, plus compact-width reachability. First run the new test red, then green.
- Re-run `ThreadScreenModalTest` for order, arming, grant toggle, Cancel and Back, and the focused `MobileModalTest` gate security/keyboard cases. Use the managed device for the new pixel capture and relevant device-only keyboard method; inspect executed counts in fresh XML.
- Compare a 412 × 892 emulator capture against the component render at 1 dp per reference pixel using a labelled composite or difference image. The whole modal has no exact Figma counterpart, so comparison targets the checkbox, button and existing shell regions.
- Run focused unit test, lint, assembleDebug, androidTest compilation, Spotless apply and forced check. The dispatcher owns the full UI/scripted gates; this visual change adds no new operator-facing flow needing a real-Claude scenario.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/permission-modal-overlay.md` § Visual spec status with inspected nodes `347:6771`, `347:6215`, `489:1876`, `533:2369` and date 2026-09-30, the verified component alignment, and the missing full-screen permission and armed-state references. The ticket contains no separate Documentation handoff section.

## Open questions

- Does the available emulator provide real nonblack screenshot pixels and the 412 × 892 logical viewport? Use the full system image as in `MobileModalCaptureTest` if the ATD image does not.
- Does compact width plus enlarged text require any option-content adjustment beyond the shell's existing scroll behavior? Resolve with the focused test and capture; record a revision if the design changes.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `PermissionModalOverlay` receives daemon-authored text and option IDs from the existing decoded `ModalUiState.Open`; this plan keeps plain `Text` rendering and forwards IDs verbatim to the ViewModel. No new trust decision occurs in Compose.
- [Tokens, files, crypto, network] No credentials, storage, primitives, outbound request or wire frame change. Existing boundaries remain outside this visual slice.
- [Android attack surface] The gate still uses `MobileGateModal`, whose own dialog window has `SecureOn`, obscured-touch filtering and disabled Back/outside dismissal. The plan does not add an exported component, link or WebView.
- [Errors and logs] No new error payload or value log. The existing payload-free error signal remains; capture fixtures use fixed synthetic text and must not record live daemon content.
- [Concurrency] No new job or shared mutable state. Existing ViewModel arm and grant projections keep their cancellation and modal-id scoping.
- [Threat alignment] A hostile daemon label remains inert text and cannot become an action target except by a deliberate option tap. Accessibility and visual emphasis must continue to distinguish the safe default from an armed non-default choice; tests cover both. Accessibility-service eavesdropping is outside this ticket and the existing Android trust boundary.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-30

## Revisions

- 2026-09-30: Verifier review found `ModalOptionButton` using a literal 6 dp action shape despite the existing `MaterialTheme.shapes.modalControl` token. All three option states now use that shared token; the geometry and decision behavior remain the same.
- 2026-09-30: The device capture uses Compose's rendered `paneTitle` surface because the gate's `SecureOn` policy intentionally masks ordinary system screenshots. On the managed API 33 device it produced nonblank 412 × 892 pixels for unchecked, checked and armed states. The compact 320 dp Robolectric test passed at 1.5× text scale; the existing shell's compact overflow and real IME device tests passed without a layout change.
- 2026-09-30: A stronger compact-width test with long decision labels found `ModalOptionButton` clipping text within Material's button row. The option label now takes the available row width, wraps and stays centered. The initial short-label test did not expose this failure.
- 2026-09-30: The exact multi-line text assertion uses Robolectric native graphics for its test method. The default graphics mode returned a misleading one-line layout for the same 51-character label, so it could not verify wrapping.

# Shared mobile modal shell and actions (#1232)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell`, `ModalCancelButton`, `ModalSubmitButton`: shared chrome, action geometry and gate policy.
- `app/src/main/res/drawable/ic_modal_close.xml` → close vector: already exported from the referenced Figma node; retain its path.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/MobileModalFillTest.kt` → `MobileModalFillTest`: Robolectric pixel and disabled-colour proof.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt` → `MobileModalTest`: real window security, input, keyboard, and touch target proof.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`: existing M3 color roles.
- `docs/knowledge/features/mobile-modal.md` § Layout and theme, Focus and verification: the pinned/compact scroll contract and reference-specific colors.
- `docs/knowledge/features/development-verification.md` § Compose evidence: device capture and comparison procedure.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369 (inspected 2026-09-28); component `489:1942`; button states `489:1876`.

The dark 412 × 892 modal is a full-height navy surface with 44 dp corners, 28 dp horizontal and 24 dp vertical padding. The header uses `titleLarge`, a 28 dp close asset, a 12 dp gap and a 1 dp `inversePrimary` separator at 60% opacity. A centered footer with 20 dp action gap sits below a flexible content area; its 40 dp high primary and outlined secondary actions use `bodyLarge` medium, 6 dp corners, primary/on-primary colors and a 1 dp secondary border. The component's light screenshot has different palette values, so theme roles remain authoritative for light mode. Figma gives Default and Hover states, but no disabled or loading state.

## Context

The shell already has the right container, theme roles, section padding, scroll behavior and Figma close vector. The close button's 48 dp layout slot enlarges the header beyond the 28 dp design row, while `heightIn(min = 48.dp)` on actions enlarges their visible bodies beyond 40 dp. The purpose is to make those measures match the design without changing the caller-owned content or decision policy.

## Design

- Keep `MobileModal`, `MobileGateModal` and `MobileReadOnlyModal` signatures and their shared `MobileModalShell` composition. Keep the title, separator, content and footer order, with the existing pinned/compact scroll switch.
- Give the close glyph a 28 dp layout slot with a centered, invisible 48 dp interactive region. It must not overlap title, divider or other controls. Retain the existing vector, accessible Close name and primary tint.
- Size the visible action surfaces from their 24 dp text line and Figma's primary 20 × 8 dp / secondary 19 × 7 dp internal padding plus border. Remove extra explicit visual height and account for Material's implicit sizing/insets. Preserve a distinct invisible touch region if required for 48 dp targets. Use M3 color and typography roles, including native disabled appearance; do not invent a Figma disabled or loading treatment.
- Keep the current no-back, no-close, secure-window and obscured-touch policy for gates. Plain and read-only dismissals, caller content composition, focus and keyboard scrolling remain unchanged.

## State and concurrency model

This is presentation only. It introduces no state, jobs, flows or dispatchers. Existing `Dialog` and `DisposableEffect` lifetimes remain as they are.

## Error handling

Keep caller error text and live-region semantics in the content scroll area. Loading and disabled actions keep their existing callback guards. No new I/O occurs.

## Testing strategy

- Add a focused shared Compose test that first fails on visible close/header and action bounds, then passes after the change. Retain the existing color and disabled-state assertions.
- Run `MobileModalFillTest` and the affected `MobileModalTest` method/class on the managed API 33 emulator. Check 412 × 892, compact 320 dp width, enlarged text, keyboard reachability, action bounds, dismissals and gate window policy. Collect fresh XML with executed counts.
- Capture the actual rendered modal on the emulator at 412 × 892 logical size, compare it with a current Figma render at that size, and put labelled side-by-side/difference evidence on the PR. Record any unavailable Figma state and visual deviation there.
- No rung-3 scenario: this changes shared presentation but adds no operator-to-daemon flow.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/mobile-modal.md` § Layout and theme and § Focus and verification to describe the revised chrome/action geometry and capture evidence. No Documentation handoff section was present in the issue.

## Open questions

- Resolve after a real Compose render whether Material's implicit minimum interactive size or padding alters the visible 40 dp button and footer position; adjust the local shell styling only.
- Confirm the existing vector's effective 28 dp rendering matches Figma `489:1898` and `533:2369`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `MobileModalShell` continues to render caller text through Compose `Text`; this change introduces no parser, URL or markup path. The existing caller owns any daemon-text bound.
- [Tokens, storage, files, crypto, network] No new data access, persisted value, cryptographic primitive or I/O is designed; these categories do not cross the shell's presentation boundary.
- [Android attack surface] The gate's `DialogProperties` keeps `SecureOn` and disables Back and outside dismissal. Its decor view keeps `filterTouchesWhenObscured`, and `gate` still suppresses the close control. MUST preserve these in code and device test.
- [Errors and logs] `logModalEvent` remains debug-only and content-free; caller errors remain text with current semantics.
- [Concurrency] No new asynchronous work; `DisposableEffect` remains bound to the modal composition.
- [Threat alignment] Screenshot leakage and overlay taps on the gate remain controlled by the secure-window and obscured-touch policies. The plain editing modal intentionally keeps its existing non-secure window. Hostile relay and token-storage threats are outside this visual ticket and remain owned by the transport/pairing surfaces.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-28

## Revisions

- 2026-09-28: Pixel comparison resolved the button measurement question: `minimumInteractiveComponentSize` preserves an invisible 48 dp target around the 40 dp surface; a 4 dp local offset places that surface 24 dp above the modal's safe-area bottom. The existing 28 dp vector path matches the exported Figma foreground exactly; its circle uses the current theme roles. Android's real status and navigation bars occupy 24 dp each in the capture, while the Figma render omits them.
- 2026-09-28: The gate device test's `Espresso.pressBack` selected the unfocused activity root while the dialog held focus. A real device Back key preserves the intended security assertion and passed on the focused rerun.
- 2026-09-28: Figma `489:1876` has Hover roles. Material's default hover ripple layered a second color over the referenced fill, so the action functions use a controlled hover interaction source and suppress that ripple only while hovered; touch press feedback remains native. The focused pixel test drives HoverInteraction directly because Robolectric does not deliver a synthetic mouse-enter event to the dialog.

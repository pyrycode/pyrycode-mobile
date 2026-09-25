# #1135 — Mobile modal shell: scroll the whole shell when it is too short to pin its chrome

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell` — the only file changed. Pins the title row and footer around a `weight(1f)` scroll region; that region collapses to nothing when the IME leaves less height than the chrome needs.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt` → `ime_keeps_focused_field_final_item_and_actions_reachable`, `small_window_overflow_scrolls_with_header_and_footer_fixed`, `withTestIme`, the order-0 `imeBeforeActivity` rule — the portrait contract that must stay green as written, and the IME fixture the landscape test reuses.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTestIme.java` → `MobileModalTestIme` — a fixed 240 dp keyboard in every orientation.
- `docs/knowledge/features/mobile-modal.md` § "Layout and theme", § "Focus and verification" — the pinned-chrome layout and the rule that an IME test must prove a positive inset, not just pass without a keyboard.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369 (shell), https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=500-2120 (Edit channel content)

Portrait only: a full-height rounded `primaryContainer` surface, title row with the close glyph and a divider pinned at the top, caller content centered, and the centered Cancel/OK pair pinned at the bottom. There is no landscape-with-keyboard frame; the ticket makes readability and reachability the goal there, so the compact mode below is a deliberate deviation with no Figma counterpart. Portrait is unchanged.

## Context

At `1ba24026`, a phone in landscape with the keyboard up leaves the shell roughly 100–180 dp of height. The pinned chrome (24 dp vertical padding each side, 61 dp header, two 20 dp gaps, 52 dp footer) needs about 200 dp, so the weighted content region measures to zero and the focused field sits behind the keyboard. Every `MobileModal`, `MobileGateModal` and `MobileReadOnlyModal` caller uses this shell, so they all get the fix. No ADR is warranted.

## Design

`MobileModalShell` measures its own height inside the `Surface` (a `BoxWithConstraints` around the shell column) and picks one of two modes:

- **Pinned** (height ≥ `MinPinnedShellHeight`, 280 dp): today's layout, unchanged. Header and footer fixed, content in the weighted inner scroll region.
- **Compact** (height < 280 dp): the header, the content and the footer scroll together as one column. The inner region drops its weight, its inner `verticalScroll` and its `heightIn(min)`, since it is now measured with unbounded height.

280 dp is the ~200 dp of chrome plus room for one outlined text field. It keeps the existing 320 × 640 dp portrait IME test (about 330 dp of shell above the test keyboard) pinned, and switches landscape with any keyboard (under ~180 dp) to compact. Landscape without a keyboard (~360 dp) stays pinned.

**Two structural rules keep the switch safe**, because the mode flips while the operator is typing — the IME appearing is what shrinks the shell:

1. **The tree is the same shape in both modes; only modifiers change.** The caller's `content()` stays at the same composition position, so its `remember`ed values and the focused field survive the flip. A branch that called `content()` from two different places would reset the typed text and drop focus, which would hide the keyboard and flip back.
2. **The outer `verticalScroll` is always applied**, with a fixed `height(maxHeight)` in pinned mode so it has zero range there. The same scroll node therefore sees the viewport shrink when the IME rises, and Compose's scrollable keeps the focused child in view on a shrinking viewport. A scroll modifier added only at the flip would be a new node with no prior size and would not reveal the field.

Both scroll states are `remember`ed unconditionally.

## State + concurrency model

No new state beyond two `rememberScrollState()` values and a derived `Boolean`. No coroutines, no effects added.

## Error handling

None: layout only.

## Testing strategy

Device-only (the IME and a real rotation cannot run under Robolectric), added to `MobileModalTest` under `app/src/androidTest/`:

- New annotation `@Landscape`, handled by the existing order-0 rule: rotate with `UiAutomation.setRotation(ROTATION_FREEZE_90)` before the Compose rule launches its activity (after the IME switch, so no activity is recreated mid-test), and restore `ROTATION_FREEZE_0` then `ROTATION_UNFREEZE` in `finally`.
- `landscape_ime_keeps_focused_field_and_actions_reachable` (`@WithTestIme @Landscape`), full-window modal content: assert the configuration is landscape; focus the field and show the keyboard as the portrait test does; wait for a visible IME; assert the field is focused and its bottom on screen is above the keyboard's top; `performScrollTo` "Final item" (content below the field), OK and Cancel, each asserted above the keyboard top; click Cancel and count one dismissal.
- RED: at `1ba24026` the field's bounds sit in a zero-height region, so the above-keyboard assertion fails.
- The existing portrait IME test and the fixed-chrome overflow test stay as written (AC 3).

Focused runs: the new method and the two portrait tests on `pixel2Api33Atd` (§ B2 command).

## Open questions

- Whether the emulator's IME inset rises in one step or animates. Rule 2 above covers both; the test shows which. If the field is not revealed, add an explicit bring-into-view and record it under Revisions.

## Revisions

**2026-09-25, during implementation: how the test reaches landscape.** The shell design is unchanged. The test fixture differs from the plan in three ways:

- Rotating before launch does nothing: the portrait-only launcher is on top, so the display stays at rotation 0. `rotateToLandscape()` now runs in the test body after the host activity launches and before `setContent`, and waits for the relaunched host to be landscape and focused. The `@Landscape` rule only restores rotation after the activity has closed.
- The rule is `createAndroidComposeRule<ComponentActivity>()` (what `createComposeRule()` already was on Android) so the test can read the relaunched activity through its scenario.
- On the `pixel2Api33Atd` image, a "Bluetooth keeps stopping" system crash dialog takes window focus after the rotation. The wait broadcasts `CLOSE_SYSTEM_DIALOGS` until the host has focus.

**Open question resolved.** No explicit bring-into-view was needed: with the outer scroll always applied, the focused field is revealed above the test keyboard in landscape.

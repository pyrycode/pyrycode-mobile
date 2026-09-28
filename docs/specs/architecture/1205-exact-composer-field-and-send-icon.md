# #1205 — Exact composer field and send icon

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar`: field geometry, draft and paste ownership, and shared send/stop action.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`: `composerFieldContainer` and fixed dark scheme.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` → `primaryDark`, `onBackgroundDark`: existing design colours.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBarStyleTest.kt` → `ThreadInputBarStyleTest`: current rendered-fill, type, and geometry assertions.
- `docs/knowledge/features/thread-input-bar.md` → “The message-input button” and “Draft binding”: queue-while-busy and heap-only draft constraints.
- `docs/knowledge/features/development-verification.md` → “Compose evidence”: real-pixel capture requirements and ATD limitations.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957

Inspected `533:1957` (Input area), `347:6446` (Input large), and `113:3543` (Send) on 2026-09-29. The dark field is a 52dp high, 6dp rounded translucent blue well with 16dp leading text inset, 20sp line height, and a trailing 48dp touch region whose centered 28dp filled circle-chevron uses `Schemes/Primary` (`#9DCBFC` in fixed dark). The provided send SVG is the source of the vector path. These nodes define Send only; they do not define a Stop asset or disabled appearance.

## Context

The composer currently renders a stock `ArrowCircleUp`, whose path differs from the current Figma send icon. The existing fill and most geometry already align with the reference. The disabled icon remains full strength and fails to distinguish an unavailable send action.

## Design

Convert the provided `circle-chevron-up-solid-full 1` SVG path into a 28dp Android vector resource and use it in `ThreadInputBar`'s existing 48dp `IconButton`. Keep `StopCircle` for the stop action because the inspected design exposes no stop asset. Preserve the one-button action selection, field state, paste receiver, IME action, and draft ownership. Use the theme's existing `composerFieldContainer`, `bodyMedium`, and `primary` roles. Set the icon's disabled tint with the button's actual enabled condition; retain full-strength primary for Send and Stop. Adjust only field and button layout where rendered evidence shows an in-scope difference.

## State + concurrency model

No new state, jobs, flows, or dispatchers. `ThreadInputBar` retains its existing `TextFieldState`, `LaunchedEffect` draft sync, and `snapshotFlow` undo path. The ViewModel and screen continue to own draft and sending state.

## Error handling

This visual change introduces no new I/O or error path. Send and interrupt callbacks keep their existing behavior.

## Testing strategy

- Extend `ThreadInputBarStyleTest` with focused assertions for the send/stop enabled state, compact width, enlarged text, and field/button geometry. Run it red before implementation and green after.
- Preserve the existing draft and paste screen checks, including IME Send. Run touched focused tests, lint, debug assembly, and Android test compilation.
- Capture actual emulator pixels in fixed dark at 412 × 892 and a compact width, with keyboard and relevant composer menu states. Store labelled Figma comparisons under `app/src/androidTest/assets/composer-1205/`, and record inspected node IDs and design date in the PR. Capture evidence must identify the device and viewport; an ATD black frame is not visual proof.
- This is a visual correction to an existing operator flow, so no new live-Claude scenario is necessary.

## Open questions

- Does the full emulator render reveal any text-baseline or button-position mismatch after the vector replacement? Resolve against the 412 × 892 Figma viewport and record any change under Revisions.
- Figma defines Send only. Record the absent Stop and disabled variants in the PR rather than claiming a design match for them.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-input-bar.md` sections “The message-input button” and “Previews” with the custom send vector, disabled tint, and verified dark field geometry. Do not edit that shared overview in this builder run.

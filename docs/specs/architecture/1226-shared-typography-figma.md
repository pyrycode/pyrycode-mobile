# 1226 — Shared typography from Figma

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` → `AppTypography`: currently delegates every slot to the Material default ramp.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`: supplies `AppTypography` to the whole application.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell`, `ModalCancelButton`, `ModalSubmitButton`: the modal consumes title large, body medium and medium-weight body large.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `IdentityRow`, `HostNameField`: the reference uses semibold label large and body medium.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeConversationRow`: sidebar labels consume title small and body small.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageBubble`: thread message text consumes body medium.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt` → `smallViewportKeepsEveryControlReachableAndUnpairAtFortyEightDp`: existing compact modal behavior proof.
- `docs/knowledge/features/development-verification.md` § “Where a screen test goes” and “Compose evidence”: 412 dp layout and device capture constraints.
- `docs/knowledge/features/thread-screen.md`, `channel-list-screen.md`, `mobile-modal.md`: existing ownership and visual behavior; this change stays at the shared type ramp.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected live on 2026-09-28: thread `16:8`, sidebar `15:8`, Edit host modal `533:2369`. The sidebar uses compact 12 sp rows with 14 sp medium-weight headings; the thread uses a 22 sp header, 14 sp messages and 12 sp metadata; the modal uses a 22 sp title, 14 sp content and labels, and 16 sp medium-weight actions. These are M3 text roles over the existing dark palette; layout, colors and decorations remain with the screen components.

## Context

`AppTypography` delegates to `Typography()`, so shared text metrics are implicit. The current Figma references specify Roboto directly. This ticket pins only the roles observed in those references, including metrics inherited by the emphasized variants already expressed at call sites.

## Design

Keep `AppTypography` as the sole shared type ramp. Define `bodySmall` (Roboto 400, 12/16 sp, 0.4 sp tracking), `bodyMedium` (Roboto 400, 14/20, 0.25), `titleSmall` (Roboto 500, 14/20, 0.1), and `titleLarge` (Roboto 400, 22/28, 0) explicitly. Also define the base `bodyLarge` (Roboto 400, 16/24, 0.5) and `labelLarge` (Roboto 500, 14/20, 0.1) so existing call-site weight overrides yield the reference's body-large emphasized (500) and label-large emphasized (600) with explicit inherited family, size, line height and tracking. The thread's body-small emphasized variant similarly uses the explicit base with a 500 override. Leave `PyrycodeMobileTheme` wiring intact.

No new state, coroutine, transport, error path or public API is needed. Text continues to scale with Android font scale through `sp`; no fixed text height enters the ramp. Screen-specific widths and spacing stay with their owners.

## Testing strategy

- Add a focused theme test asserting all five metrics for the six base roles and the three emphasized weight variants as consumed by the reference components. Assert through `PyrycodeMobileTheme` so wiring is covered. Font family must be explicit Roboto via Android's `FontFamily.SansSerif` mapping.
- Run the focused theme test red, then green. Run the existing compact-width modal and sidebar/thread screen tests at enlarged font scale where feasible, checking text reachability and adjacent controls. Use `@GraphicsMode(NATIVE)` for exact text geometry.
- Capture the rendered 412 × 892 logical viewport on a managed emulator and compare it with the current Figma reference in a labelled overlay or difference image. Keep the capture and comparison under `app/src/androidTest/assets/` and record reference conflicts in the PR. The image is review evidence, while metric and reachability assertions are executable proof.
- Run scoped unit tests, lint, assembleDebug and androidTest compilation if a capture test is touched. No real-Claude scenario is needed for a shared typography change.

## Documentation handoff

No reference-document section is named by the issue. The documentation stage owns any later feature-overview update; pending.

## Open questions

- Does the available managed-device capture render nonblank pixels at 412 × 892? If the ATD image cannot, use the existing full-image capture route documented in development verification and record which device provided visual evidence.

## Revisions

- 2026-09-28: The full-image `pixel8Api35` capture supplied nonblank 412 × 892 sidebar evidence. A new `EditHostModalTest` enlarged-text assertion found `IdentityRow` clipping “Server identity:” at 320 dp and 1.5× text; the focused API 33 emulator run reproduced it. `IdentityRow` layout is outside this shared-ramp ticket, so the assertion remains `@Ignore` pending [#1229](https://github.com/pyrycode/pyrycode-mobile/issues/1229). The test is retained as the bug's regression proof; this ticket's executable checks cover the shared metrics and existing compact modal behavior, but cannot claim enlarged-label acceptance until #1229 lands.
- The comparison image uses Figma sidebar `15:8` against the current fake-backed app's sidebar, with physical system bars removed from the emulator image before alignment. The fake's names, row count, control placement and backdrop differ from the reference; the type-role metrics are compared by the focused assertions rather than treating those screen-content differences as typography defects.

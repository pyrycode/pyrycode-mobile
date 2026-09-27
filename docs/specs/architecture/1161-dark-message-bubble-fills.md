# Match the dark message bubble fills (#1161)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `UserMessageBubble`, `AssistantMessage`, `MessageContainer` — role fills and shared geometry; streaming uses the same container.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedMessageRow.kt` → `QueuedMessageRow` — user fill beneath the existing 0.6 row alpha.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt` → `MessageMetaRow`, `CopyTextControl` — retain content colour at 0.8 alpha and bounded clipboard writes.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme` — resolved app mode and wallpaper selection own the override.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` → `onPrimaryDark`, `modalContainerDark` — existing reference hues; do not couple bubble roles to modal semantics or change global containers.
- `app/src/main/java/de/pyryco/mobile/ui/theme/ComposerColors.kt` → `LocalComposerFieldContainer`, `composerFieldContainer` — composition-local colour role precedent from #1160.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBarStyleTest.kt` → `show`, `assertWell` — native Canvas sampling and opposite app/system mode coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt` → `MessageBubbleTest` — existing geometry, copy and streaming regression coverage.
- `docs/knowledge/features/message-bubble.md` → Token mapping, Meta row, Fill vs. hug — supersede only the static-dark fill divergence; retain metadata and streaming adaptations.
- `docs/knowledge/features/queued-backlog-section.md` → `QueuedMessageRow` — inert queued text and caller-owned drop action.
- `docs/knowledge/features/development-verification.md` → Where a screen test goes — shared native-graphics fixtures, 320dp default and explicit sizing when needed.
- `docs/specs/architecture/1160-dark-composer-field.md` → Change and Testing strategy — theme-local override and rendered proof precedent (implementation commit `29f46f58`: 219 added lines).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read Message area `533:1956` design context, its variable definitions, and the full thread screenshot. The column alternates left assistant and right user surfaces, 6dp corners, 20dp/16dp inner padding and M3 bodyMedium text. User fill is `Schemes/On Primary` **#003355**, assistant fill is `Schemes/On Primary Fixed` **#001D34**; retain the existing body colours and the readable 0.8 metadata adaptation instead of the reference's low-contrast inversePrimary.

## Change

Add `BubbleColors.kt` with two composition-local colour roles exposed as `ColorScheme.userBubbleContainer` and `ColorScheme.assistantBubbleContainer`, following `ComposerColors`. Define the assistant reference hue in this theme file; reuse `onPrimaryDark` for the user hue. `PyrycodeMobileTheme` provides these hues only when its resolved `darkTheme` is true and wallpaper colours are off; otherwise provide the selected scheme's `primaryContainer` and `secondaryContainer`. Never inspect system dark mode in the bubble consumers. Switch only the two role fills and the queued user fill. Keep explicit body colours, metadata, geometry, assets, copy/drop actions and streaming logic as they are. Existing light/dark previews exercise the updated roles. No new state, jobs, I/O, error paths or logging events are introduced by this colour retune.

One deliverable; four production files; approximately 320 total written lines including plan and tests; zero new exported types; zero signature/call-site migrations; two acceptance criteria; zero error/reject branches. All six bounds hold. Refreshed remote feature branches and found no overlapping changes to the proposed files; #1160 is already included in main.

## Testing strategy

Add a shared native-graphics `MessageBubblePaletteTest` first and run RED against the current fills. Sample actual user, finalized assistant, streaming assistant and queued fills; queued pixels must equal user fill at 0.6 over the background. Cover static dark, static light and wallpaper dark/light, with app mode opposite the supplied system configuration. Assert rendered body and timestamp text colours, and pin unchanged global Material container values for static modes. Check the streaming-to-finalized transition and runtime theme changes without remounting where practical.

Run the new test GREEN and existing `MessageBubbleTest` / `QueuedBacklogTest` for copy, geometry, streaming and queue-drop behavior. Run Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin. Use an optional synthetic local render to compare the changed fills against the Figma screenshot. This is a palette retune with no new operator flow or daemon path, so no new rung-3 or rung-4 scenario is required; full regression and device acceptance remain dispatcher-owned.

## Documentation handoff

Pending for the documentation stage, exact ticket requirement: “Documentation stage: update the token-mapping and configuration sections of `docs/knowledge/features/message-bubble.md` to describe the static-dark exception and retained light/wallpaper mapping.”

## Security review

**Verdict:** PASS

- **Trust boundaries:** `PyrycodeMobileTheme` derives colours only from local theme flags and palette constants. Message content cannot select a colour or alter the override; `MessageContainer` keeps its existing render inputs.
- **Tokens/secrets:** no credential access or storage is added; test content is synthetic.
- **File/storage:** production changes perform no file operations. Optional test captures contain synthetic fixture text only and use a test-process property for their destination.
- **Android attack surface:** no manifest, intent, provider, permission, WebView or exported component change. `CopyTextControl` retains its existing clipboard bound.
- **Cryptography:** no crypto or key lifecycle changes; the theme consumes no transport material.
- **Network/I/O:** no transport, decoder or repository modification; the colour selection is synchronous and local.
- **Logs/telemetry:** no new logs or telemetry; message text and clipboard data never enter a new sink.
- **Concurrency:** immutable colour values are provided per composition from the effective theme; no jobs, mutable shared state or lifecycle changes.
- **Threat model:** hostile relay/daemon inputs, disk token theft and screenshot/accessibility exposure gain no new path from this change. Existing rendering and security boundaries remain intact; this pass identifies no new deferred security work.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27

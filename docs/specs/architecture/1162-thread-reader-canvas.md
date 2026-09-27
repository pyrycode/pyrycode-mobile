# Thread and reader dark canvas (#1162)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`: effective app mode and local palette providers.
- `app/src/main/java/de/pyryco/mobile/ui/theme/BubbleColors.kt` → `LocalUserBubbleContainer`, `LocalAssistantBubbleContainer`: scoped-palette precedent from #1161.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`: Scaffold background and opaque composer surround.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` → `ThreadTopAppBar`: inherited canvas and inset 1dp rule.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` → `MarkdownReaderScreen`, `MarkdownReaderTopBar`: full-size Surface and fixed header over scrolling text.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState`: empty/populated fixtures.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubblePaletteTest.kt` → `MessageBubblePaletteTest`: native graphics bitmap assertions and deliberately opposite system/app modes.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt` → `longContent_scrollsUnderAFixedBar`: existing scrolling proof.
- `docs/knowledge/features/thread-screen.md`: screen ownership and related component map.
- `docs/knowledge/features/thread-screen-how-it-works-overlays-and-app-bar.md` § “ThreadTopAppBar — Figma 16:8 chrome”: retain divider geometry and touch slack; replace only the recorded static-dark colour divergence.
- `docs/knowledge/features/markdown-reader-screen.md` § “What it does”: Surface supplies onSurface foreground; preserve it explicitly.
- `docs/knowledge/features/development-verification.md` § “Where a screen test goes” and “Compose evidence”: shared native-graphics tests; assert actual measured fixture dimensions.
- `app/build.gradle.kts`, `gradle/libs.versions.toml`: existing shared Robolectric/Compose test dependencies suffice.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574

Both screenshots show a dark column with a fixed titleLarge header and inset horizontal rule; the thread has message bubbles and a composer below, while the reader has scrollable markdown and blank trailing space. Content nodes `533:1947` and `553:2576` overlay 30% black on Schemes/Surface (#101418), giving #0B0E11; the rule uses Schemes/Inverse Primary (#32628D) at 60%. Keep existing glyphs, spacing, text roles and all unrelated visual details.

## Context and scope

One presentation deliverable shared by two related screens. Estimate: about 400 written lines including tests and this plan, five production files, one internal palette type, zero signature migrations, two acceptance criteria, zero error/reject branches. This matches the refiner's estimate and #1161's scoped palette precedent. Refreshed all remote feature branches: no overlapping files or dependencies. Codegraph context identified the entry points; callees for `PyrycodeMobileTheme` returned no results, so its body was read directly.

## Design

Add `ThreadColors.kt` alongside the existing local palettes, with an immutable internal palette carrying `background`, `surface` and `headerRule`, a CompositionLocal and a read-only `ColorScheme.threadColors` accessor. `PyrycodeMobileTheme` provides it from the already-resolved `darkTheme` and `dynamicColor` arguments. In static dark only, background and surface are the scheme's black scrim at 30% composited over surface; headerRule is inversePrimary. Every other mode maps background to background, surface to surface and headerRule to outlineVariant. Rule alpha stays at its existing call sites.

`ThreadScreen` uses the local background for its Scaffold and local surface for the composer surround; retain `onBackground` explicitly as Scaffold contentColor. `MarkdownReaderScreen` uses the local surface for its full-size Surface and explicitly retains `onSurface` contentColor. Both header rules consume the same local rule role. Header containers remain transparent over their owning canvas. No global Material role, foreground, bubble/input fill, inset, dimension, interaction or asset changes. Existing light/dark previews continue to exercise the screens.

## State, concurrency and errors

No new state, jobs, I/O, failure modes or lifecycle events. Palette values are recomputed from the effective theme and propagate via CompositionLocal on app theme changes, without consulting system mode inside either screen. Existing screen lifecycle/error logging stays unchanged; a pure colour mapping adds no log-worthy event.

## Testing strategy

Add `ThreadCanvasPaletteTest` under sharedTest with AndroidJUnit4 and native graphics. Render real screens, then sample pixels independently of production palette accessors: header blank area, divider interior and outside its gutter, empty/populated thread blank space, composer surround, and short/scrollable reader canvas. Assert retained foreground text colours and global Material roles, and exercise all four app dark/light × wallpaper on/off mappings with system mode deliberately opposite. Switch theme on already-composed content to prove propagation. Use synthetic fixtures only, with optional PNG output for visual comparison; verify scrolling leaves the header fixed and the canvas correct.

Run the static-dark fixture RED before production changes, then the new class GREEN plus existing bubble palette, thread header and reader scrolling coverage. Run spotlessApply, lint, assembleDebug and compileDebugAndroidTestKotlin. This is a presentation retune of existing flows, adding no operator action or daemon behaviour, so no new real-Claude scenario or device-only test is needed; full regression gates remain dispatcher-owned.

## Open questions

None.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/markdown-reader-screen.md` under “What it does” and `docs/knowledge/features/thread-screen-how-it-works-overlays-and-app-bar.md` under “ThreadTopAppBar — Figma 16:8 chrome” to describe the static-dark canvas/rule exception and retained light/wallpaper mapping.

## Security review

**Verdict:** PASS

- Trust boundaries: `PyrycodeMobileTheme` derives colours solely from local theme booleans and existing scheme constants. No daemon text enters the new palette; existing screen rendering boundaries remain unchanged.
- Tokens, secrets and credentials: no credential access or new storage; synthetic rendering fixtures contain no live content.
- File/storage operations: production changes perform none; optional test PNGs contain only constructed fixture text.
- Inter-process/Android surface: no manifest, intents, providers, clipboard or WebView changes; reader action implementations stay untouched.
- Cryptography: no crypto or wire dependencies enter the colour mapping.
- Network/I/O: no transport, decoder, request or limits change.
- Errors/logging/telemetry: no new log or error path; no screen text is captured outside synthetic tests.
- Concurrency: immutable palette values follow composition; no coroutine or shared mutable production state is introduced.
- Threat model: relay compromise, token theft and hostile frames retain existing protections because this diff only selects paint colours. Existing UI screenshot/accessibility policy is unchanged; test captures are explicitly synthetic.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27

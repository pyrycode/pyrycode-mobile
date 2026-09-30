# Markdown reader alignment (#1291)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` → `MarkdownReaderScreen`, `MarkdownReaderTopBar`, `MarkdownReaderMenu` — owns the fixed shell, six actions and scroll body.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` → bar metrics and its vector usage — supplies shared gutters, rule spacing and the existing exact artwork.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `MarkdownTextStyle`, `MarkdownBlock`, `CodeBlock`, `BlockQuoteBlock` — shared parser, link routing and block presentation.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt` → reader actions and scrolling regressions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTypographyTest.kt` → reader and thread typography and spacing assertions.
- `docs/knowledge/features/markdown-reader-screen.md` § “What it does” and `docs/knowledge/features/markdown-text.md` § “Public surface” — current reader contract; documentation update belongs to the later stage.
- `docs/knowledge/features/shared-typography.md` § “Roles” and `docs/knowledge/features/development-verification.md` § “Where a screen test goes” — M3 ramp and evidence rules.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574 — node `553:2574`, inspected 2026-09-30 at 412 × 892. The frame has a dark `surface` canvas under a 30% black content layer, a 20dp inset top bar with 24dp back artwork, `titleLarge` filename, blue 6 × 24dp overflow artwork and a 60% `inversePrimary` rule. The 20dp inset scrolling body starts at y=97 and uses `headlineSmall`, `bodyLarge`, `titleLarge`, a compact `surfaceContainer` code panel and an `outlineVariant` quote rule. The Figma frame contains no overflow-open, compact-width or enlarged-text state; those are checked against current product behavior.

## Context

The reader already uses the shared parser and large body typography from #1207. Its visible bar is 4dp high relative to this frame, its generic Material icons differ from Figma, and the shared thread code and quote treatments differ from the reader example. This is a presentation change, with no new read, navigation or clipboard contract.

## Design

- Offset only the reader bar's top gap by 4dp so the visible title, vectors, inset rule and body start align at Figma's coordinates. Keep the existing 48dp button hit areas, filename ellipsis and 20dp content gutter. Use `ic_thread_back` and `ic_thread_overflow`, whose paths match the node's supplied SVGs, with `onSurface` and `primary` tints.
- Add a reader presentation choice to `MarkdownTextStyle`, defaulting to the current thread presentation. The reader selects it and keeps the same parser, `UriHandler` routing, list layout and body roles. Its fenced/indented code uses a full-width 8dp `surfaceContainer` panel with 12dp inset, 13sp/20sp monospace `onSurfaceVariant` text and horizontal scroll. The reader's quote has a 3dp `outlineVariant` rule, a 12dp text gap and regular `bodyLarge` `onSurfaceVariant` text. Thread code/quote behavior remains as it is.
- Keep the reader's six menu actions, whole-note copy formats, Refresh, open/save handoffs, notices, file bounds and UTF-8 decoding untouched. A plain reader code panel matches the inspected frame; whole-note copy remains in the menu.
- Keep the current scroll column so long Markdown can be reached. At narrow width, the filename truncates inside the bounded center slot and code scrolls horizontally. Android font scaling expands text without fixed body height.

## State and concurrency model

No new state or jobs. `MarkdownReaderMenu` still owns only its expansion state. `RefreshableMarkdownReader` keeps the existing composition-scoped read and cancellation behavior; the visual variant is an immutable style value passed through `MarkdownText`.

## Error handling

No new I/O or failures. `readMarkdownAttachment` and `readLinkedMarkdown` retain their bounds and strict decoding; Refresh failure retains the visible document and notice. Menu actions retain their current callbacks and result notices.

## Testing strategy

- First add failing shared Compose assertions for the reference's bar/body geometry, vector slots, reader code/quote style and narrow/enlarged-text reachability, while retaining existing reader action tests. Run the affected test classes under `testDebugUnitTest` before and after the code change.
- Check existing `MarkdownTypographyTest`, `MarkdownTextTest`, `MarkdownLinkTapTest`, `ThreadCanvasPaletteTest` and reader menu tests for thread/reader isolation and routing. Run `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin` for shared-test compilation and forced Spotless check.
- Capture the reader on a real emulator at 412 × 892 with the exact Figma Markdown, plus compact, enlarged-text and menu states. Save Figma/emulator side-by-side and labelled overlay or difference artifacts for the PR. Use a device-only capture test because Robolectric cannot prove pixels. No new live-Claude scenario: this changes an existing reader's presentation, not an operator flow or daemon interaction.

## Open questions

- Figma has no menu, compact or enlarged-text frame. Use the current Material menu and verify reachability/clipping; record the absent states in the PR evidence.
- If the existing `ic_thread_back` or `ic_thread_overflow` diverges from the supplied SVG, import the supplied vector rather than substituting another Material icon. Inspection found their path data identical.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/markdown-reader-screen.md` § “What it does” and `docs/knowledge/features/markdown-text.md` § “Public surface” / “Block dispatch” with the reader-only code and quote appearance, top-bar vector/geometry and visual evidence. The ticket has no separate documentation-only acceptance criterion.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary. `readMarkdownAttachment` and `readLinkedMarkdown` retain the 256 KiB bound and strict UTF-8 decoding before `MarkdownText` receives daemon-authored text. The new presentation dispatch renders `Text`, never markup or a WebView.
- [Tokens and credentials] No credential path is touched. Existing reader logs remain content-free; the new presentation logs no document values.
- [File and storage] No path, provider, picker or file-write change. `rememberNoteSaver` and `openNoteInAnotherApp` keep their existing destinations and permissions.
- [Android attack surface] No exported component, intent filter or new URI handling. `routeMarkdownLink` remains the sole link routing path and its allowlist is unchanged.
- [Cryptography and network] No cryptographic, frame, relay or transport changes.
- [Errors and telemetry] No new error text or telemetry. Existing notices and structured event logs remain intact.
- [Concurrency] The new style has no mutable state or coroutine. The existing composition scope still cancels Refresh on screen exit.
- [Threat model] Hostile daemon-authored Markdown remains length-bounded, text-rendered and link-routed as before. Screen capture and accessibility exposure are existing platform behavior outside this visual ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-30

## Revisions

- 2026-09-30: Focused existing tests showed that `CodeBlock` exposes a per-block copy action and syntax-highlighted, language-labelled fences in the reader. Preserve that current behavior for labelled fences. Only unlabelled fences and indented blocks take the Figma plain-panel treatment; tapping the panel copies that block's bounded source, so the existing copy action remains reachable without adding visible chrome absent from node `553:2574`. `MarkdownTextStyle` still selects the reader variant, and the whole-note menu is unchanged.
- 2026-09-30: Android's default trimmed line boxes made the reference paragraph and list shorter than the Figma frame despite matching text roles. Apply untrimmed line-height boxes only to reader blocks and headings; keep the thread ramp untouched.
- 2026-09-30: Bound the reader code panel's explicit minimum height to two lines. The content itself may grow naturally, but a hostile newline count cannot multiply a large minimum layout constraint. The panel's tap uses the same `MAX_CLIPBOARD_CHARS` bound as the existing copy control.
- 2026-09-30: Resolved the open reference-state questions: the node has no menu, compact or enlarged-text child, so the current Material menu is retained and checked on the full Pixel 8 emulator at 412 × 892 and 320 × 700 / 1.5× text. The supplied back and overflow SVG path data exactly match `ic_thread_back` and `ic_thread_overflow`; no new asset is needed. Pixel comparisons are stored under `app/src/androidTest/assets/markdown-reader-1291/`.

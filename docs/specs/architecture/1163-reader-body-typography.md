# Reader body typography (#1163)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` — `MarkdownText`, `MarkdownBlock`, `ListBlock`, `BlockQuoteBlock`, `TableBlock`, `CodeBlock`, `routeMarkdownLink`: recursive style seams and retained special-element/link handling.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` — `MarkdownReaderScreen`, `LinkedMarkdownReaderDestination`, `readBoundedFile`: shared reader call and existing bounded loading.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` — `AssistantMessage`, `StreamingAssistantBodyView`: both thread paths use renderer defaults.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — `AppTypography`: standard M3 bodyMedium and bodyLarge tokens.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt` — `render`: native-font measurement, formatting, code copying and horizontal scrolling coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkTapTest.kt` — `tapLink`: real link interactions for finished and streaming replies.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt` — `show`, `longContent_scrollsUnderAFixedBar`: shared reader, clipboard menus and vertical scrolling coverage.
- `docs/knowledge/features/markdown-text.md` (Public surface, Link safety), `docs/knowledge/features/markdown-reader-screen.md` (What it does, reader bound): preserve renderer safety and both entry routes.
- `docs/knowledge/features/development-verification.md` (Where a screen test goes): shared AndroidJUnit4 tests with native graphics for text measurement.

Codegraph supplied entry points but no useful callers/callees; source search confirmed three production `MarkdownText` consumers and the recursive helper calls.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574

Read design context and its screenshot: a single scrolling markdown column below the fixed name bar, with `Schemes/On Surface` text. Paragraph `553:2772` and list `553:2774` use M3 bodyLarge (16sp/24sp, 0.5sp tracking), with 12dp block and 6dp list gaps. Keep existing heading, table, code chrome/highlighting/copy controls and italic quotes as the ticket requires; the older reference's plain code tile and nonitalic quote are outside this typography change.

## Context and design

One deliverable: reader-specific body presentation through the existing renderer. Add an immutable `MarkdownTextStyle` value containing a `TextStyle`, block spacing and list-item spacing; `MarkdownText` receives a trailing optional style defaulting to bodyMedium and 8dp/4dp. Pass it explicitly through `MarkdownBlock`, `ListBlock` and `BlockQuoteBlock`, including recursive children and ordinary list markers. Quote paragraphs copy only the italic attribute onto the selected body style. Fallback prose follows the body style. `HeadingBlock`, `TableBlock`, `CodeBlock`, inline parsing and link routing retain their existing styles and behavior.

The sole reader call supplies bodyLarge and 12dp/6dp. Attachment and linked-note destinations already converge there. Both thread calls remain on defaults. No new dependencies, state, jobs, flows, I/O or error branches; composition and cancellation behavior are unchanged. Existing content-free reader action logs remain the lifecycle evidence; do not log on rendering/recomposition.

## Scope check

Expected roughly 350–450 written lines including proof and this plan, consistent with size S; #768's implementation added 158 lines plus its plan. Two production files, one exported value type, seven consumer call sites updated across the renderer/helper signatures, three acceptance criteria, zero new error branches. All six boundaries hold. Remote feature branches were refreshed and none overlap the planned production or existing test files. No open design questions.

## Testing strategy

- First add shared Compose tests that measure actual `GetTextLayoutResult` styles and unclipped bounds for reader and default rendering, including ordered/unordered/task items, markers, nested list blocks and nested quote paragraphs. Assert italic quotes and retained heading/table/code styles. Reader checks must fail at 14sp before implementation.
- Measure finished and streaming message presentation to pin the default style at the production consumers.
- Exercise reader-styled formatting, links, code copy and horizontal scrolling; run existing `MarkdownTextTest`, `MarkdownReaderScreenTest` and `MarkdownLinkTapTest` for interaction regressions and vertical scrolling/copy menus.
- Use AndroidJUnit4 and native graphics in shared tests. Run scoped `testDebugUnitTest`, `spotlessApply`, `lint`, `assembleDebug`, and `compileDebugAndroidTestKotlin`. Compare body layout with the reference. No daemon behavior is added, so no rung-3/rung-4 scenario is required.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/markdown-text.md` (Public surface) and `docs/knowledge/features/markdown-reader-screen.md` (What it does) to describe the reader styling and retained thread defaults.

## Security review

**Verdict:** PASS

- [Trust boundaries] `MarkdownBlock` still renders untrusted document text as native Compose text; a caller-owned style value changes no parsing or trust decision. `readBoundedFile` and linked-note loading retain the existing 256 KiB bound, and `TableBlock` retains its row/column caps.
- [Tokens / cryptography] No credentials, key storage, entropy or Noise code is touched; style values contain only typography and dimensions.
- [File/storage / Android surface] No path, intent, provider or manifest changes. `MarkdownReaderScreen` retains existing explicit copy/save actions; style selection cannot trigger them.
- [Network/I/O] No new network operation. `routeMarkdownLink` retains its allowlist and callback opt-in; reader styling supplies no workspace-path callback. Tests retain inert path links and external web link behavior.
- [Logs/telemetry] No document text, links or filenames are added to logs. Existing action logs stay unchanged; style changes add no event worth logging.
- [Concurrency] Immutable style propagation introduces no shared mutable state or coroutine. Parser memoisation remains keyed on markdown.
- [Threat alignment] Hostile markdown gains no executable sink or resource multiplier. Relay, disk-secret and screenshot/accessibility defenses are untouched because this change only selects existing text styles and gaps; no new threat is deferred.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27

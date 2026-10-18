# Reader shared code panels

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownBlock`, `ReaderCodeBlock`, `CodeBlock` and code extraction; reuse the existing panel without changing its consumers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt`: `CopyTextControl` delegates original text to `setBoundedText` and its 100,000-character clipboard limit.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `MarkdownReaderScreen` supplies typography to both attachment and linked-note readers; its overlay owns header pointer input.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt`: reference geometry, roles, horizontal scrolling and physical header interception checks.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTypographyTest.kt`: attachment, linked-note and thread typography coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt`: parser-to-copy exactness, highlighting, headers and stationary copy controls.
- `docs/knowledge/features/markdown-reader-screen.md`, `markdown-text.md` and `markdown-text-internals.md`: preserve bounded original-source copying, native-graphics measurement and overlay interception; measure panels rather than their padded text.
- `docs/knowledge/features/development-verification-gates.md`: shared Compose tests run with native Robolectric graphics; complete suites belong to the dispatcher.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2770 and shared Simple Code https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=134-4890 (context and screenshots inspected 2026-10-18).

The reader code panel is a full-width rounded rectangle with Background fill, a 1dp Primary Container border, 6dp corners, 16dp horizontal/12dp vertical content padding and a visible lower-right copy glyph. Shared code uses 12sp/20sp monospace; the existing labelled variant retains its language header and syntax colours. Reuse `CodeBlock` and its existing copy asset and rail, preserving horizontal scrolling required by the ticket.

## Change

Route every fenced and indented block through the existing copyable `CodeBlock`, preserving the parser's extracted text and optional language. Remove the now-unused private `ReaderCodeBlock` and imports, and have `MarkdownReaderScreen` request `bodySmall` with 20sp lines (12sp text). Do not change shared `CodeBlock`, its signature, thread/tool presentation, body/heading typography, block spacing, link admission or reader header input. Remote branches #1766 and #2279 overlap `MarkdownText.kt` in separate streaming/link blocks; keep this edit local. One useful deliverable is consistent reader code-panel rendering and copying, with its regression proof; forecast roughly 400 written lines across two production files, three test files and this plan, no new exported declarations or signature migrations, three criteria and no new reject branches.

## State transitions and identity reuse

None: this changes stateless block dispatch and typography, reusing the existing composition-owned scroll state and copy callback. No job, flow, persistence, routing identity or connection lifecycle changes.

## Testing strategy

Write assertions first and observe focused failures before changing production. Extend `MarkdownTextTest` to cover reader labelled, unlabelled and indented headers/copy controls, exact block text including whitespace, the clipboard bound, and horizontal scrolling with a stationary copy rail for all forms. Keep existing thread assertions. Extend `MarkdownTypographyTest` to assert 12sp/20sp monospace for all forms through attachment and linked-note readers. Adapt `MarkdownReaderDesignTest` to shared panel bounds and roles, verify physical copy taps before and beneath the header, and retain existing layout/interaction coverage. Native shared tests supply operator-facing interaction proof without a Claude turn; inspect for affected screenshot goldens and refresh only if assertions use them. Run the affected classes plus parsing, streaming and tool-panel regression coverage on Linux with counted XML and retained raw reports. Run lint, assembleDebug, instrumented Kotlin compilation, Spotless and the final merged-main pre-verify gate; dispatcher owns complete suites.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] Untrusted note code remains literal `Text` through the existing parser and `CodeBlock`; `CopyTextControl` receives extracted original text, never rendered spans. Preserve extraction, reader byte limits and prose long-run safeguards. Add exact whitespace and bounded-copy regression assertions.
- [Tokens, secrets and credentials] No credentials are created or accessed. User-initiated copies may contain private note text; the existing clipboard bound remains enforced in `setBoundedText`, with no new automatic copy path.
- [Files and storage] No file paths, storage writes, cache changes or backup changes are introduced; existing reader loading and bounds remain unchanged.
- [Android attack surface] No components, intents, providers or WebViews change. A covered copy control must not receive physical taps through `MarkdownReaderTopBar`; update the positive-control interception test to target the visible shared copy glyph.
- [Cryptography] No changes to transport, key management, randomness or cryptographic operations.
- [Network and I/O] No network calls are added; copy remains the existing bounded clipboard operation. Code cannot become a link or executable markup.
- [Errors, logs and telemetry] Do not log code, note text, paths or clipboard payloads. Shared copy intentionally has no content logging; no new error branch is introduced.
- [Concurrency] No new coroutines or asynchronous state. Existing composition-owned panel scrolling and event callbacks stay in use.
- [Threat model] Relay interference and token theft stay behind unchanged transport/storage boundaries. Hostile daemon text remains inert and bounded at reader admission and clipboard output. Screenshots/accessibility and explicit clipboard exposure retain the current user-visible policy; header interception prevents accidental covered-copy activation.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-18

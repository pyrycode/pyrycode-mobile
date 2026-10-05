# Stabilized streaming markdown (#1766)

## Files read

- `MessageBubble.kt`: `AssistantMessage`, `StreamingAssistantBody`, `StreamingAssistantBodyView` and `nextStreamingRevealLength`; retain the source and composition-lifetime clocks.
- `MarkdownText.kt`: `MarkdownText`, `MarkdownBlock`, `appendInlineChildren`, `fencedCodeText` and `routeMarkdownLink`; share the GFM walker, spacing, code chrome and link routing.
- `MarkdownTextParsingTest.kt`, `MarkdownTextTest.kt`, `MessageBubbleTest.kt` and `ThreadStreamingRevealTest.kt`: existing parser, formatting, rapid-arrival and reopening contracts.
- `InteractiveStreamE2ETest.kt`, `DeterministicInteractiveStreamE2ETest.kt`, `scripts/e2e-emulator.sh` and `docs/e2e-interactive-stream.md`: live and scripted harness seams.
- `docs/knowledge/features/message-bubble.md`, `message-bubble-testing.md`, `markdown-text.md`, `markdown-text-internals.md` and `development-verification-gates.md`: source-copy ownership, unsupported literal fallback, single-tilde flanking and Robolectric clock/width constraints.
- Sibling `pyrycode/docs/protocol-mobile.md`, Security model: daemon-authored text remains untrusted; no wire change.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read the thread and assistant instance `I533:1956;132:4539`, including screenshots. Assistant messages are left-aligned, with 20dp horizontal/16dp vertical padding, 12dp block gaps, M3 bodyMedium text and the existing assistant fill/onSecondaryContainer roles. This changes arrival presentation within that established body, with no new asset or container treatment.

## Context

Raw revealed prefixes currently expose pending punctuation and put the caret through the parser. Streaming already shares the settled GFM renderer. Introduce temporary presentation data and reuse completed blocks without changing message content or clipboard payloads. No new library or decision record is needed.

One deliverable, five acceptance criteria, one streaming consumer, at most five new internal types, no public signature migration. Forecast: about 1450 written lines including plan, helpers, tests and live registration; under 1600. There is no state machine with new error/reject branches. Overlaps with #1674, #1682, #1689, #1690, #1691, #1693, #1695, #1727, #1735, #1756 and #1761 are confined to independent live methods and additive curated registration; no dependency on their changes.

## Design

Keep the existing GFM AST and `MarkdownBlock` renderer. A helper beside the streaming consumer caches parsed blocks and temporary inline presentation. It parses only the mutable suffix on append, retaining the exact AST/source/presentation objects for completed earlier blocks. The last top-level block always remains mutable; parser grouping therefore keeps continuing lists, quotes, fences and pending table headers together. Non-append replacement resets the local cache.

Use the parser's inline regions and completed code/link nodes to distinguish literal or escaped source from pending structure. Hide unpaired supported emphasis/strike delimiters; present an unclosed code span's arrived body literally; show only an incomplete inline link's label without link annotations. Completed constructs retain the existing inline walker. Pending pipe headers present their actual cell text separated by spaces, with structural pipes and a partial separator hidden. A table is released only by a complete matching actual separator, never an invented row. Code fence bodies stay in the parser's code block, including blank lines, until their actual closer.

The shared block container preserves `MarkdownTextStyle` spacing and grouping. Key cached blocks and pass stable block objects so Compose skips unchanged earlier blocks. A scoped internal observation seam counts suffix parsing and committed block compositions in controlled tests; it does not collect text. Keep the caret in its own composition beside/below the trailing rendered block, outside the AST, code text and link spans. It reserves its footprint while blinking, and follows that block's growth.

Caught-up complete markdown uses identical AST/block/inline rendering at the same available width when streaming clears; only the caret disappears. Completion with backlog takes the existing immediate full-source settled path. Malformed final source also takes that path and its existing literal fallback. Retained message source is never rewritten.

## State and concurrency model

No ViewModel, flow, dispatcher or socket change. `StreamingAssistantBody` retains both Unit-keyed `produceState` jobs: 33ms word-aligned steps with the outstanding 15-tick deadline, latest content through `rememberUpdatedState`, and the independent 500ms blink. Both cancel on composition exit. The parse cache is UI-local remembered state, used synchronously on the composition thread and discarded on exit; pre-open seed and reopening remain unchanged.

## Error handling

The existing total parser and unsupported-node fallback remain authoritative. Pending presentation does not synthesize link destinations or delimiters. Final malformed source is rendered from the original source. No new network or I/O boundary or UI error state is added.

## Testing strategy

Test first: add deterministic pending-source/AST cases and observe their failure before implementation. Unit coverage pins nested/escaped emphasis, both strike forms, backtick runs, incomplete versus complete links, literal code/unsupported source, pending headers/separators, real fence closure, cache reset and continuing-list mutability.

Clock-controlled shared Compose tests prove pending text and untappable links, complete formatting/link targets, fenced blank lines and following prose, pending-to-real table transition, caret separation, no earlier-block parse/composition increments on append or blink, equal-width complete transition and immediate malformed/backlog completion. Run existing word-step, rapid-arrival, reopen, MarkdownText, MarkdownLinkTap, typography, bubble layout/palette/selection and scripted render coverage appropriate to this body.

Add `InteractiveStreamE2ETest.interactiveTurn_markdownReply_rendersFormattedBody`, one real Claude turn with constrained emphasis, inline code, fence and table markers; assert formatted semantic spans and all expected text after settlement. Register it in the full curated suite. This belongs under androidTest because it needs the real daemon/relay/Claude stack. Extend the scripted stream fixture/scenario with a deterministic formatted twin where the harness supports it. Run focused JVM/shared tests, scripted `stream`, lint, assembleDebug, compileDebugAndroidTestKotlin and forced spotlessCheck. The dispatcher owns the fresh full live suite; its report must include executed/failed/skipped counts and this named method passing. Pending live evidence is explicitly handed off, never claimed green.

## Open Questions

- None. Parser edge cases are pinned by unit tests; any discovered contract change will be recorded below before departing from this design.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/decisions/0002-markdown-renderer-library.md`, Consequences → Streaming will share the same renderer: streaming already shared it; #1766 adds stabilization and completed-block reuse.
- `docs/knowledge/features/message-bubble.md` and `docs/knowledge/features/markdown-text.md`, streaming descriptions: document pending syntax, block reuse and malformed-final behavior.
- `docs/e2e-interactive-stream.md`, scenario/evidence descriptions: record the new scenario and the fresh full live result supplied by the dispatcher.

## Security review

**Verdict:** PASS

- [Trust boundaries] SHOULD FIX: partial destinations must never gain a link annotation. Only the actual complete inline-link AST reaches `appendInline`'s link arm; pending labels are literal, covered by annotation and tap tests. Fence/code punctuation must bypass pending masks.
- [Tokens] No credentials enter these helpers. Message source and clipboard ownership stay with `Message`; no token generation/storage changes.
- [Files/storage] No source-derived path, cache filename or disk write is introduced. The cache is per-composition memory; actual workspace link taps retain `routeMarkdownLink` and daemon confinement.
- [Android attack surface] No exported component, intent, provider, WebView or permission change. Render only native Compose text and existing links.
- [Cryptography] Noise and key storage are untouched; no new randomness or secret comparison.
- [Network/I/O] Existing decoded-message/frame limits and bounded table dimensions remain in force. No frame, URL or timeout change; do not amplify tables by adding rows.
- [Errors/logs] Never log source, labels, destinations or decrypted bytes. Any lifecycle diagnostics use static event codes/counts only; test counters collect no content.
- [Concurrency] Cache is confined to composition; existing producers cancel on disposal and arrivals never restart their delay/deadline. No background task or cross-message sharing.
- [Threat model] A malicious relay cannot gain plaintext through this UI-only change. Hostile daemon markdown stays within the total GFM walker and bounded table fan-out; incomplete and unsafe links cannot trigger navigation. Rooted-device credential theft and screenshot/accessibility leakage remain under the existing protocol/device protections; this adds no persistence or disclosure channel.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05

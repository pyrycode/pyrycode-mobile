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
- `docs/e2e-interactive-stream.md`, Deterministic mode / replay-order: describe the replayed initial-user echo before thinking, which establishes reply placement before the offline fragment.

## Security review

**Verdict:** PASS

- [Trust boundaries] SHOULD FIX: partial destinations must never gain a link annotation. Only the actual complete inline-link AST reaches `appendInline`'s link arm; pending labels are literal, covered by annotation and tap tests. Fence/code punctuation must bypass pending masks.
- [Tokens] No credentials enter these helpers. Message source and clipboard ownership stay with `Message`; no token generation/storage changes.
- [Files/storage] No source-derived path, cache filename or disk write is introduced. The cache is per-composition memory; actual workspace link taps retain `routeMarkdownLink` and daemon confinement.
- [Android attack surface] No exported component, intent, provider, WebView or permission change. Render only native Compose text and existing links.
- [Cryptography] Noise and key storage are untouched; no new randomness or secret comparison.
- [Network/I/O] Existing decoded-message/frame limits and bounded table dimensions remain in force. No frame, URL or timeout change; do not amplify tables by adding rows. The rework adversarial pass found a quadratic unmatched-bracket scan and suffix-sized per-region protection storage (MUST FIX). The revised design uses a single-pass bracket stack and region-local protection arrays, with deterministic input-length work/storage bounds in `unmatchedBracketsHaveLinearScanWorkAndRegionLocalStorage`; no source-derived suffix copy is allocated per region.
- [Errors/logs] Never log source, labels, destinations or decrypted bytes. Any lifecycle diagnostics use static event codes/counts only; test counters collect no content.
- [Concurrency] Cache is confined to composition; existing producers cancel on disposal and arrivals never restart their delay/deadline. No background task or cross-message sharing.
- [Threat model] A malicious relay cannot gain plaintext through this UI-only change. Hostile daemon markdown stays within the total GFM walker and bounded table fan-out; incomplete and unsafe links cannot trigger navigation. Rooted-device credential theft and screenshot/accessibility leakage remain under the existing protocol/device protections; this adds no persistence or disclosure channel.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05

## Revisions

### 2026-10-05 — verifier rework on PR #1810

- Findings 1–6: a partial following list marker does not establish an immutable boundary. Keep its preceding list in the mutable suffix until the marker resolves. Protect GFM autolinks and HTML literal tokens from pending masks. A blank line or newline-closed invalid separator closes a candidate table header; outer cells are removed only at actual structural pipe offsets, and backslashes inside code do not escape closing backtick runs. Include setext-shaped separator prefixes and route paragraph overrides through the common inline entry point so quotes retain their existing style. Match link-label brackets in a single forward pass that skips escaped punctuation and protected code spans.
- Finding 7 changes the Security review: hostile repeated unmatched brackets caused quadratic composition-thread work, and per-region protection arrays amplified memory by the suffix length. MUST FIX before handoff: use a bracket stack with one visit per scanned character, and allocate protection only for each disjoint inline region. Deterministic work/capacity counters carry no content and tests bound both by input length. Existing masks remain one pair per suffix; no network/storage contract changes. With these mitigations the revised security design is PASS; the elevated effort assessment remains appropriate.
- Add prefix-by-prefix ordered-list, literal punctuation, closed prose, escaped/code pipe, quoted/setext header and code-bearing link-label regressions. Controlled Compose tests compare grouping and annotated text through settlement and exercise inert pending links. The existing live scenario and curated selection remain; focused scripted stream execution is builder-owned and fresh full live acceptance remains dispatcher-owned.

### 2026-10-05 — re-review of `54e549aa` on PR #1810

- Finding 1: a real `TABLE` AST with a matching separator is formatted at EOF, with optional outer pipes and no final newline. It still stays in the mutable suffix so appending rows retains normal grouping. Unit cases cover every outer-pipe combination; controlled Compose cases compare cell text, styles and bounds at the same width through settlement.
- The shared parser also recognizes the matching short separator `--- | --` as a table. Prior fixtures incorrectly classified it as pending; use a still-incomplete colon cell for their pending assertions and cover the short valid form in the EOF settlement cases. The renderer's grammar remains authoritative.
- Finding 2: pending literal code takes precedence over parent formatting's delimiter filtering and single-tilde pairing. A formatting node whose closing delimiter belongs to pending code cannot supply formatting yet; retain that literal punctuation and render its other children normally. Actual code and formatting closure restore the existing renderer's styles, with regressions for both bold forms, both italic forms and both strike forms.
- Finding 3: only an unescaped bang encountered by the forward inline scan marks an image opener. The existing escape skip enforces backslash parity without another suffix scan or allocation. Pending links after escaped bangs retain the renderer's literal prefix and plain, inert label; actual closure supplies the original link target. Unit parity cases and controlled annotation/tap transitions cover this boundary.
- Security re-review: PASS. These changes add no destination, persistence, I/O or logging path; incomplete links remain inert, and scanner work/storage retain their input-length bounds. No new exported types or call-site migration. The focused scripted `stream` rerun remains builder-owned; fresh full curated live counts and named-method evidence remain dispatcher-owned.

### 2026-10-06 — re-review of `1dbe9415` on PR #1810

- Findings 2–3: an EOF horizontal rule can still become a continuing list item, so retain its list predecessor in the mutable suffix until the line closes. Actual `TABLE` recognition takes precedence over the pending pipe grammar; its separator closes the header and its cells keep the shared renderer's interpretation, including literal backticks around a pipe the parser splits. Pending headers remain code/escape-aware; arrived body rows retain inline stabilization.
- Finding 4: flush the streaming-state change before advancing the paused clock, wait for settlement, and assert caret absence before comparing text, styles, targets and bounds. Reassess all EOF, pending-code and escaped-bang transitions using that synchronized helper. Prefix-by-prefix list/rule cases and equal-width table/list settlement cases reproduce the defects before the repair.
- Security re-review: PASS. Recognized headers use the existing total renderer and scheme allowlist, with no synthetic destination or new I/O. The cache remains composition-local, and the existing linear scanner bounds remain covered. No new exported types, dependencies or production call-site migration; current written work remains below 1600 lines. The only new overlap is #1783's separate live/scripted methods in `DeterministicInteractiveStreamE2ETest`; shared-file additions remain local.
- Finding 1: the replay opener omitted Claude's `isReplay` initial user echo. The same failure was diagnosed in #1783 (`a824b728`): idle placement can insert the user confirmation between reply deltas and split the expected reply. Restore that causal opener and pin it with the existing harness test shape; preserve the visible cross-delta order and uniqueness assertions. These local fixture/test additions mirror #1783 so neither ticket needs its production changes. Focused `replay-order` and formatted `stream` execution remain builder-owned. Documentation stage: update `docs/e2e-interactive-stream.md`, Deterministic mode / replay-order, to describe the initial-user echo before thinking.

### 2026-10-06 — re-review of `f30f5e97` on PR #1810

- Finding 1: after Android's real Copy click, `assertFinishedReplySystemCopy` waits within its existing timeout for the independently known `cobalt` clipboard value before asserting it. Compose's clipboard callback uses a coroutine; an immediate idle read is insufficient evidence of the completed write. Keep the unrelated baseline, real pointer selection, platform popup and exact selected-word assertion. The first focused reproduction failed in Android clipboard setup with a platform attribution exception; the next unchanged run passed. No platform exception is caught or hidden by this repair.
- Finding 2: a parser cell closed by a pipe or newline cannot gain inline content from later appends. Preserve its shared-renderer text and styles; only a body cell whose end touches source EOF receives pending masks. A table itself stays mutable for new rows. Unit and synchronized equal-width Compose tests cover backticks and all six emphasis/strike siblings at EOF and after a newline, plus completed cells beside a genuinely growing code/emphasis/link cell.
- Security re-review: PASS. Closed cells retain the existing renderer's fallback and link allowlist, while incomplete destinations in the growing cell remain inert. No synthetic destination, new persistence, I/O, dependency, exported type or signature change. Scanner work and region-local storage decrease; existing linear-bound probes remain required. No in-flight branch overlaps the two repaired implementation files. Written work remains below 1600 lines. Focused `selection-copy` and formatted `stream` are builder-owned; fresh full curated live evidence and prose updates remain dispatcher/documentation handoffs.

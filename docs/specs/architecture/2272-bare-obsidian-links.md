# Bare Obsidian links in reply prose (#2272)

## Files read

- `docs/knowledge/features/markdown-text.md`: streaming settlement and literal bare-URL contracts.
- `docs/knowledge/features/markdown-text-internals.md`: inline sibling traversal, admission before annotation, and exact-target routing.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `appendInlineChildren`, `appendInline`, `appendLeaf`, `SyntheticallyClosable`, and `routeMarkdownLink` share rendering and activation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdown.kt`: `StreamingTail` and `probeInline` identify synthetic punctuation but do not prove bare-URL completion.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ObsidianLinks.kt`: `isSafeObsidianLink` and `openObsidianLink` supply unchanged admission and Android delivery.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextParsingTest.kt`: parser token and table-cell contracts.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkRoutingTest.kt`: exact routing and workspace exclusion.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkTapTest.kt`: native Compose annotations and intercepted Android launches.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdownTextTest.kt`: successive source updates, caret and settlement coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt`: existing bare-HTTPS exclusion.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`, Security model: daemon-authored content is untrusted; no wire changes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected design context and screenshot: a dark thread with assistant message panels, body-medium prose and primary-coloured actions. This change reuses the authored-link `MaterialTheme.colorScheme.primary` and underline, within the existing text layout; it adds no geometry, assets or theme tokens.

## Context

One useful behaviour: admitted bare Obsidian prose opens the same external application as an authored link. Punctuation, streaming completion and exclusions are boundaries of that behaviour. #2280 landed in #2295 and is reused unchanged. Remote branch #1766 overlaps the renderer, but its issue is closed and current main already contains the newer parser-led streaming implementation; no active dependency was found. #2270 is independent coordination and its whole-code-span rule, if merged, must be preserved.

Forecast: approximately 650 written lines including production, tests and this plan, no new public types, at most one internal extraction helper, no changed public signatures, five acceptance criteria and no new admission state machine. This remains one behaviour below the numeric ceilings. No decision record is needed.

## Design

Keep the parser and allowlist unchanged. Add a small pure extraction helper under the components package returning exact source ranges for admitted Obsidian candidates. Match an ASCII-case-insensitive scheme after content start, whitespace, an opening parenthesis or quote, and preserve the lowercase action through shared admission. A candidate ends at real whitespace or its containing inline-content boundary; adjacent lexer tokens do not terminate it.

Integrate extraction into `appendInlineChildren` for ordinary prose token runs. Inspect the whole whitespace-delimited candidate before annotating any part, including tokens beyond a run, so an unsafe suffix cannot expose an admitted prefix. Reuse one private link-appending helper for authored and bare links, retaining colour, underline and `UriHandler` listener. Code-span/block dispatch and authored-label extraction remain separate from bare recognition.

Validate the complete candidate first with `isSafeLinkScheme`. Only if refused, remove the maximal trailing run of `. , ; ) ! ?`, ASCII quotes or closing curly quotes, then validate that remainder once. Admitted punctuation remains part of the exact target. Removed punctuation stays visible outside the annotation. Never decode or normalize the target.

## State and concurrency model

Pure synchronous rendering on the existing Compose path; no jobs, flows, I/O or persistent state are introduced. Carry the actual arriving source-end offset from `appendLeaf` through private inline rendering context. Compare candidate ends in original-source coordinates, discounting synthetic characters. A real boundary before that end permits linking; touching that end keeps the candidate inert. The caret is appended afterwards. Settled rendering has no pending-end guard. Existing screen cancellation and connection lifecycle are unchanged.

## State transitions and identity reuse

| Event | Required check |
| --- | --- |
| Successive independently admitted growing prefixes | `StreamingMarkdownTextTest.bareObsidianPrefixes_waitForRealCompletion` keeps `file=No` and `file=Note` inert. |
| Unsafe suffix arrives after a valid prefix | Same test keeps the full refused target inert through settlement. |
| Real whitespace, Markdown closer or cell separator arrives | `StreamingMarkdownTextTest.bareObsidianBoundaries_completeParagraphListAndTableTails` links only the full target. |
| Synthetic closer or caret blink | The streaming tests require no premature annotation and unchanged visible text/caret. |
| Reply settles without a trailing boundary | `StreamingMarkdownTextTest.bareObsidianSettlement_linksTheCompleteTarget` switches to settled rendering and verifies the exact annotation. |
| Source replacement or repeated rendering | Streaming tests replace source on the same composition, proving no retained candidate state. |

There is no conversation/session identity state, storage restore or socket ownership in this change.

## Error handling

Refused and unfinished candidates remain ordinary text without link styling or actions. Activation reuses `routeMarkdownLink` and `openObsidianLink`, including their admission recheck, generic failure notices and content-free lifecycle log. Never log a candidate or reply text. Workspace callbacks never receive an Obsidian URL.

## Testing strategy

Write failing focused extraction/parser and Compose cases before production changes. Unit cases cover starts, multiple candidates, adjacent tokens, escaping, every punctuation decision, rejected suffixes and unchanged other schemes. Routing tests preserve exact admitted strings and exclude workspace routing. Native-graphics Robolectric Compose tests cover paragraph/list/table annotation ranges, styles, activation, code/label exclusions and streaming transitions. Run affected existing `MarkdownTextParsingTest`, `MarkdownLinkRoutingTest`, `StreamingMarkdownTest`, `MarkdownTextTest`, `MarkdownLinkTapTest` and `StreamingMarkdownTextTest`, plus the new focused extraction class. No device-only test or live Claude obligation is introduced: `MarkdownLinkTapTest` intercepts Android ACTION_VIEW and already covers failure paths.

Record Linux source revision and raw counted XML results. Run lint, assembly, shared-test instrumented Kotlin compilation, formatting and final `pre-verify.py --gradle`. Complete acceptance gates remain dispatcher-owned; macOS is outside this ticket's verification scope.

## Open Questions

None: the refined issue supplies the punctuation and completion contracts.

## Security review

**Verdict:** PASS

- **Trust boundaries:** daemon text becomes actionable only through the shared admission function. Whole-candidate validation and the pending-end guard prevent unsafe or growing prefixes from gaining an action. Tests must attempt unknown-key, malformed-escape and non-note suffix bypasses.
- **Tokens:** no generation, storage or credential access; exact targets are held only in annotations and never logged.
- **Files and storage:** no file access or persistence. Scheme admission and unchanged routing prevent Obsidian targets from becoming workspace reads.
- **Android attack surface:** reuse the existing ACTION_VIEW opener and safe failure notices; no exported component, intent filter, provider or WebView is added.
- **Cryptography:** no cryptographic operations or transport changes.
- **Network and I/O:** extraction is bounded by existing rendered input and linear source scanning, with at most two admission attempts per candidate; no requests occur until a user activates a link.
- **Errors/logs:** reuse static opening outcomes; never log source text, URLs, vault names, note paths or platform exceptions.
- **Concurrency:** candidates are recomputed from each immutable source snapshot. Synthetic characters and caret presentation cannot establish completion; there are no new jobs or shared mutable caches.
- **Threat model:** hostile daemon text is handled by strict admission, exclusions and explicit user activation. Malicious-relay interference, rooted-device token theft and UI capture retain existing transport/storage/platform controls; this renderer change creates no new channel for them.

**Reviewer:** builder self-review per `builder/security-review.md`  
**Date:** 2026-10-19

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

- **Trust boundaries:** daemon text becomes actionable only through the shared admission function. Whole-candidate validation and the pending-end guard prevent unsafe or growing prefixes from gaining an action. The original review missed full/collapsed/shortcut reference labels: these must be protected alongside inline links, autolinks, images and code, both during overlay selection and recursive dispatch. Reference constructs retain their existing inert presentation; no authored-label subtree runs bare recognition. Finished/streaming native tests assert absent annotations, styles and activation for every reference form with a refused authored destination. Tests also attempt unknown-key, malformed-escape, non-note suffix and embedded-web-target bypasses.
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

## Revisions

### 2026-10-19 — token traversal and focused execution

The renderer overlays admitted source ranges while walking siblings, rather than joining only childless runs: a URL can contain parser punctuation or an interior Markdown node without becoming a truncated target. Only range boundaries in ordinary leaf tokens are eligible at a given level; code and authored-label subtrees remain protected. Single-tilde interiors recurse through the same walker so their styling survives a link. `appendLeaf` already has the real source end, so `StreamingMarkdown.kt` needs no production edit. The managed launcher accepts one Mobile test class per invocation; run the seven affected classes individually.

### 2026-10-19 — preserve whole bare web URLs

The rejection regression exposed an active Obsidian substring inside `https://example.com/(obsidian://open?file=Note)`. An opening parenthesis within an existing GFM web-autolink token is not a prose candidate start. Reject starts inside `GFM_AUTOLINK`, while still allowing an Obsidian candidate to span other tokens. The same native regression covers the `www` form; its initial failed report is retained as `app/src/test/resources/markdown-2272/embedded-web-red.xml`. This closes a boundary of the existing bare-web exclusion without changing admission.

The equivalent angle-bracket probe exposed the same problem in `AUTOLINK` labels. That authored construct now appends its unchanged literal source without bare recognition; `angle-web-red.xml` retains the failing proof. Run the rejection matrix in finished and streaming renderers. This is the same container-exclusion invariant, not another behaviour.

### 2026-10-19 — parser probe bounds

The new parser test incorrectly assumed the union of URL-intersecting tokens equals the URL: TEXT tokens can also contain the surrounding prose. Preserve the multi-token assertion and require extraction within the real paragraph/list/cell leaf to return exactly the URL range, while allowing surrounding characters in the tokens. The production overlay already handles those partial leaves; its 30 Markdown text and 23 routing screen cases passed. `parser-boundary-red.xml` retains this test-assumption failure.

### 2026-10-19 — verifier repairs: composite endpoints and reference labels

Finding 1 exposed the endpoint restriction in `appendInlineChildren`: a complete admitted URL ending at an emphasis, strong or strike node must consume that entire node verbatim, including its delimiters. Permit fully consumed composite endpoints while retaining recursive dispatch for partial composite boundaries and the code/authored-label veto. `MarkdownTextParsingTest.admitted bare targets can end in composite inline nodes`, `MarkdownLinkTapTest.bareRanges_endingInCompositeNodes_preserveExactTextAndAndroidTargets` and `StreamingMarkdownTextTest.bareCompositeEndpoints_waitForCompletion_andSettleToExactTargets` cover paragraph/list/table targets, sibling constructs, exact ranges, Android delivery, real termination and settlement without premature activation.

Finding 2 exposed missing reference-label protection. Share one authored-label container set between `appendInline` and `protectsBareLink`, including full/collapsed references, shortcut references, `LINK_TEXT`, `LINK_LABEL` and autolinks. Preserve their literal source rather than recursing into bare recognition. The parser test pins all three forms; `finishedReferenceLabels_neverActivateEmbeddedBareCandidates` and `streamingReferenceLabels_neverActivateEmbeddedBareCandidates` prove absent actions with workspace opt-in. The security review above now names this previously missed boundary explicitly.

Both repairs enforce the original exact-target/exclusion invariant, with no new public types, consumer signature changes or independent behaviour. The repair is forecast at approximately 350 written lines including tests, plan and raw evidence, keeping the full branch below 1600. Remote #2270 overlaps the code-span arm and link helper; keep these edits local to prose overlay and label dispatch. #1766 remains a closed historical overlap. The Figma frame was inspected again; primary/underline treatment and geometry remain as authored links.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/markdown-text.md`, Streaming and Testing — explain real source completion versus lexer boundaries, punctuation, synthetic closers and caret; record final counted Linux acceptance once dispatcher gates pass.
- Pending documentation stage: `docs/knowledge/features/markdown-text-internals.md`, Inline dispatch and Link safety — document the narrow bare-Obsidian exception, exact source ranges across composite nodes, unchanged routing/styling, punctuation fallback and code/authored-label exclusions, preserving literal bare web/email behaviour.

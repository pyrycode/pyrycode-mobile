# Link completed URL-only code spans (#2270)

## Files read

- `docs/knowledge/features/markdown-text.md` and `markdown-text-internals.md`: inline-code styling, shared admission and the synthetic-closer guard.
- `docs/knowledge/features/thread-screen.md`: assistant replies use the shared renderer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `appendInline`, `appendSyntheticallyClosed`, `rememberMarkdownUriHandler`, `routeMarkdownLink` and `isSafeLinkScheme`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ObsidianLinks.kt`: existing strict Obsidian admission and launch failure ownership from #2271's children.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt`: annotations and code styles.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkTapTest.kt`: actual link activation, platform handler and controlled Obsidian launch failures.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdownTextTest.kt`: pending constructs, caret exclusion and settlement parity.
- `../pyrycode/docs/protocol-mobile.md`, Security model: hostile daemon text is untrusted; this change touches no wire contract.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected design context and screenshot: dark thread, alternating content-sized message bubbles, bodyMedium prose and actions beside messages. This ticket reuses the existing monospace/surfaceContainer inline-code treatment and primary/underline authored-link treatment; no geometry or new tokens are introduced.

## Context

URL-only code currently requires copying. One useful behavior ships here: explicitly completed code spans containing one admitted external target become tappable. The styling, rejection cases, activation and streaming tests prove that behavior together. Bare prose remains separate (#2272). #2271 is closed and its admission/routing code is merged.

Forecast: about 350 written lines including tests and this plan, one production file, no exported types or changed consumer signatures, three acceptance criteria and one new admission decision. The #1050 renderer/routing slice added 118 and deleted 4 lines; this ticket adds broader rendering/streaming matrices. Both scope measurements remain within all ceilings. Remote #1766 overlaps `MarkdownText.kt`; its older streaming changes are already superseded in main, and these local inline edits need no in-flight design.

## Design

In `appendInline`'s `CODE_SPAN` arm, retain the exact existing displayed code string and its outer monospace/background span. Trim a separate candidate; link only when nonempty, containing no whitespace and admitted by unchanged `isSafeLinkScheme`. Do not use workspace-path admission, even when the renderer has that callback. The target is the trimmed candidate verbatim, without decoding or normalization.

Move the existing authored-link annotation construction into a private `appendLinkText` builder helper used by both arms. It retains exactly the same primary colour, underline, `LinkAnnotation.Url` and `UriHandler.openUri` listener. Authored-link admission stays where it is. The synthetic-closer guard stays before dispatch, so an unfinished span never reaches the new decision. Fenced blocks and bare URL dispatch stay unchanged. No ViewModel, state/event type, dependency or DI change.

## State and concurrency model

No new state, jobs or flows. The existing source-keyed parsing and streaming cache drive rendering on the composition thread; existing handlers own activation and current callbacks. Background/socket lifecycle remains unchanged.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Synthetic backtick closer becomes a real closer, with single/multiple backticks | `codeSpanUrl_waitsForItsRealCloser_andSettlesUnchanged` |
| Caret blinks after a completed span; next block freezes it; settled renderer takes over | `codeSpanUrl_waitsForItsRealCloser_andSettlesUnchanged` |
| Repeated activation of the same target, finished and streaming | `finishedCodeSpanUrls_useAuthoredLinkRouting`, `streamingCodeSpanUrls_useAuthoredLinkRouting` |

There is no new identity-bearing or lifecycle state; rotation/recollection rebuild the same source-derived annotation through the existing renderer.

## Error handling

Rejected or empty candidates retain ordinary code rendering. Accepted targets use the existing route: web/mailto go through the ambient handler; Obsidian uses the existing safe opener and static failure notices. Never catch/read/log URL-bearing exception text here.

## Testing strategy

Write failing shared Compose tests before production. `MarkdownTextTest` checks exact single annotation range including edge spaces, case/escape-preserving targets, monospace/background and authored-link colour/underline, an admission/rejection matrix including tabs/newlines, relative paths with workspace opt-in, and unchanged URL-only fences. Preserve the bare URL regression unchanged. `MarkdownLinkTapTest` activates finished/streaming code links through existing controlled handlers and checks Obsidian failure notices. `StreamingMarkdownTextTest` proves incomplete spans inert, real completion active, caret out of range and settled parity. These focused native-graphics Compose checks run on Linux under Robolectric; no new device-only check or real-Claude evidence is needed.

Run affected `MarkdownTextTest`, `MarkdownLinkTapTest`, `StreamingMarkdownTextTest`, `MarkdownTextParsingTest`, `MarkdownLinkRoutingTest`, `StreamingMarkdownTest` and the shared Obsidian admission tests. Run lint, assembly, shared-test instrumented compilation, formatting and final pre-verification. Retain raw XML, counts and tested source revision outside the worktree. Complete gates belong to the dispatcher; macOS is outside this ticket's verification scope.

## Open Questions

None.

## Security review

**Verdict:** PASS

- **Trust boundaries:** MUST FIX addressed in design: daemon-authored code must never become an arbitrary protocol action. Require unchanged `isSafeLinkScheme`, nonempty candidate and no internal whitespace; do not admit workspace paths. Test rejected Obsidian actions, encoded unsafe values and unsafe schemes.
- **Tokens:** No token storage or credential handling. Link targets can contain private content and must never be logged.
- **Files/storage:** No reads, writes, caching or path resolution added. Relative markdown code stays inert even with workspace opt-in.
- **Android attack surface:** No new component/intent filter. Reuse the existing opener only after explicit activation and routing revalidation.
- **Cryptography:** No crypto changes; Noise primitives, keys and nonces are untouched.
- **Network/I/O:** No new fetch or transport. Existing external opener owns launch failures; annotation construction performs no I/O.
- **Errors/logs:** Existing static Obsidian notices remain authoritative. No URL, message content or exception text enters diagnostics.
- **Concurrency:** MUST FIX addressed in design: synthetic streaming completion must not create a link. Preserve the pre-dispatch guard and test completion, caret blink, frozen-block reuse and settlement.
- **Threat model:** Hostile daemon text is gated before external activation. Malicious relay behavior, rooted-device token theft and UI screenshots/accessibility remain under existing transport/storage/platform controls; this renderer creates no additional storage or telemetry surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-19

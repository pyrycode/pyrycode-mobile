# Inert rejected explicit Markdown links (#2279)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `appendInline`, `rememberMarkdownUriHandler`, `routeMarkdownLink`, `markdownLinkPath`, and `isSafeLinkScheme` define rendering and routing.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownConversions.kt`: `AllowlistedLinkProvider.renderLink` and `AutolinkProvider` already share external admission.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkTapTest.kt`: finished/streaming assistant and reader routing controls.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt`: annotation inspection and the unchanged bare-URL regression.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdownTextTest.kt`: streaming completion, pending destinations and settlement coverage.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkRoutingTest.kt` and `MarkdownConversionsTest.kt`: path identity and HTML allowlist controls.
- `docs/knowledge/features/markdown-text-internals.md`: Link safety and Markdown-path links explain why handler capability must preserve the workspace opt-in without changing recursive walker signatures.
- `docs/knowledge/features/markdown-text.md`, `markdown-reader-screen.md`, and `streaming-assistant-turns.md`: shared rendering consumers and reader defaults.
- `docs/knowledge/features/development-verification-gates.md`: shared Compose tests run on Linux through Robolectric.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot, plus assistant message child `I533:1956;132:4477`.
The dark thread has left-aligned assistant bubbles, ordinary bodyMedium prose and secondary-container content colours. This change removes the primary-colour underline from rejected labels so they inherit surrounding text; admitted links keep their existing Material primary/underline treatment. Bubble layout, actions and spacing do not change.

## Context

The renderer currently promises a link even when routing silently refuses it. This is one independently useful behaviour: explicit-link affordance agrees with admission across the shared renderer. Workspace, HTML, prose and code checks protect that behaviour's boundaries rather than adding deliverables. No decision record is needed.

Remote branches #1766 and #2273 overlap `MarkdownText.kt`; #1766's handler/streaming seams are already present in this checkout and #2273 changes the default block gap. Keep this patch local to handler admission and the explicit-link arm; neither overlap is a dependency.

Forecast: approximately 400 written lines including plan and tests, one production file, no exported declarations or public signature changes, no consumer migration, three acceptance criteria, and no new state-machine reject branches. The #1050 analogue's MarkdownText/routing slice was 118 additions and four deletions; extra coverage here proves visible annotations and streaming transitions.

## Design

Give the private remembered URI handler an explicit admission method, while retaining `UriHandler` at recursive walker boundaries. It admits `isSafeLinkScheme(target)` or a `markdownLinkPath(target)` only when the current workspace callback is non-null. `appendInline` consults that capability before `withLink`; rejected labels append directly. Non-routing inline-text conversion retains the external allowlist fallback. Tap handling continues through `routeMarkdownLink`, including its independent admission guard.

Reuse `isSafeLinkScheme` unchanged for rendering, routing and HTML: http/https/mailto remain admitted, and no new URL normalisation is introduced. Preserve parsed target bytes and label extraction. Bare prose, code spans, pending synthetic link closers and HTML conversion stay on their existing paths. No new dependency, UI state, event type or ViewModel is needed.

## State and concurrency model

No jobs or flows are added. The remembered handler reads the callback through `rememberUpdatedState` during both rendering and activation; reading admission during composition must subscribe the rendered links to opt-in changes. Existing streaming cache/reveal ownership and socket background cancellation are unchanged.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Finished and streaming assistant render | `finishedExplicitLinks_agreeWithRoutingAndHtml` / `streamingExplicitLinks_agreeWithRoutingAndHtml` inspect ranges/styles and activate admitted targets; denied taps do nothing. |
| Partial destination becomes complete, then settles with the same message ID | `aStreamingDestination_becomesInertWhenRejected_andStaysInertOnSettlement` checks pending, rejected, and finished states. |
| Workspace opt-in added, replaced, removed with the same source | `workspaceOptInChanges_refreshAnnotationsAndUseTheCurrentCallback` checks annotations and current callback identity. |
| Reader/default caller omits workspace opt-in | Existing `withoutTheOptIn_aNotesLinkStaysInert` and `reader_links_keep_web_routing_and_inert_workspace_paths` now require no annotation/style and tap no-op. |

No connection, process-restoration or session identity state is introduced; ordinary recreation constructs the same handler from current inputs.

## Error handling

Rejected schemes and unopted workspace paths are ordinary inert labels, with no error notice. There is no new I/O or exception path. No target or message content is logged.

## Testing strategy

Write failing shared Compose regressions before production changes, then run `MarkdownLinkTapTest` on Linux. Inspect exactly one annotation per admitted label, its unchanged URL and exclusive label range, primary/underline styling, no denied annotation/style, native pointer taps on denied labels, and recorded external/workspace callbacks. Add Obsidian/content coverage to existing HTML and routing deny controls. Keep `a_bare_url_renders_as_text_and_is_not_a_link` untouched and run `MarkdownTextTest`, existing streaming rendering and parser tests, `MarkdownLinkRoutingTest`, and `MarkdownConversionsTest`. Shared tests provide focused native UI coverage without a new recording or device-only test. Retain raw red/green XML and commands/revisions/counts in the run evidence folder and link it in the PR. Complete gates belong to the dispatcher.

Run lint, assembleDebug, instrumented Kotlin compilation, Spotless apply/check, then merge main, push, run final assembly and `scripts/pre-verify.py --gradle` before opening the PR.

## Open Questions

None.

## Security review

**Verdict:** PASS

- **Trust boundaries:** Reject daemon-authored explicit destinations before adding an actionable annotation. Both admission and activation use `isSafeLinkScheme`/`markdownLinkPath`; test mixed-case admitted and rejected schemes. Existing text bounds and parsing remain unchanged.
- **Tokens, secrets and credentials:** No credential reads, writes, storage or logging are added; fixture targets contain no secrets.
- **Files and storage:** Workspace paths retain daemon-owned resolution and confinement. The phone only invokes the opted-in callback with the existing stripped path; it never opens a local file.
- **Android attack surface:** No components, intent filters, providers or WebViews are added. Only the current http/https/mailto allowlist can reach the external URI handler. Reader workspace labels have no actionable annotation.
- **Cryptography:** No cryptographic code or Noise state is touched.
- **Network and I/O:** No new request path or frame handling is added. External admission uses the existing predicate without widening it or canonicalising targets.
- **Errors, logs and telemetry:** Rejection is inert rendering, not a new classified error. Never log destination, path or message text; no new log event is needed for this render-only guard.
- **Concurrency:** SHOULD FIX: test callback removal/replacement so a retained annotation cannot invoke stale capability. Activation rechecks current routing independently; composition observes callback state to remove annotations.
- **Threat model:** Against `pyrycode/docs/protocol-mobile.md` Security model, hostile daemon targets are handled at annotation and routing boundaries. Malicious relay flooding/ordering, rooted-device token theft and screenshot/accessibility leakage remain in their existing transport/storage/UI owners; this patch creates no new exposure or work in those areas.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-18

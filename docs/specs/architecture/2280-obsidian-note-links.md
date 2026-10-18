# Obsidian note links (#2280)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `isSafeLinkScheme`, `MarkdownUriHandler`, `routeMarkdownLink` and `appendInline`; merged #2279 supplies shared render admission.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownConversions.kt`: `AllowlistedLinkProvider` and `AutolinkProvider` share external admission.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt`: `openAttachment` drops exceptions unread and returns static notices.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/TransientErrorNotice.kt`: `enqueue` preserves FIFO, occurrence identity and accessibility-adjusted timeouts.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `errorNotices` receives local attachment failures.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `MarkdownReaderScreen` owns its error queue and composition scope.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkTapTest.kt`: existing finished/streaming admission, exact targets, reader and workspace opt-in coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadTransientErrorTest.kt`: existing FIFO, repeated notices, disposal, placement and accessibility assertions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/MarkdownLinkRoutingTest.kt`: workspace versus external routing.
- `app/src/androidTest/AndroidManifest.xml`: test-only components; no production handler or visibility declaration required.
- `docs/knowledge/features/markdown-text-internals.md`: explicit link annotations and inert bare/code text share the inline walker.
- `docs/knowledge/features/markdown-reader-screen.md`: reader workspace paths remain inert; external links use the shared renderer.
- `docs/knowledge/features/thread-screen.md`: local failures use the existing transient Error pill.
- Desktop `src/shared/externalLink.ts` and `externalLink.test.ts`: exact read-only Obsidian contract and matrix; Mobile retains its own web/mailto rule.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6618

Design context and screenshots inspected on 2026-10-18. Thread messages retain the authored-link primary colour and underline. Failures reuse the inert, right-aligned Error pill: `errorContainer` background, `error` text, `bodySmall`, existing small shape and 8dp/4dp padding; no new layout, icon or dialog.

## Context

Assistant-authored Obsidian links currently remain inert. One behavior slice admits only read-only note opening and safely handles the Android launch. Validation without a usable opener, or an opener without admission and notices, is plumbing with one consumer and stays in this ticket. No wire contract changes or decision record are needed.

Initial and written-plan forecasts: approximately 900 written lines, two new internal production declarations (notice enum and composition local), no required public consumer signature changes, five acceptance criteria, fewer than ten validation/launch rejection categories. The native handler is test-only. In-flight overlaps #1766, #2175, #2278, #2284 and #2285 are unrelated layout/style or resource additions; edits stay local and additive.

## Design

- Extend `isSafeLinkScheme` with a pure Obsidian validator in `ObsidianLinks.kt` beside the renderer. Match the raw scheme case-insensitively and the remainder's exact `//open` prefix with only an optional query. Reject fragments, authority/path tricks and raw encoded separators.
- Split raw query pairs on `&`, then the first `=`. Compare keys literally to the four allowed keys. Ignore empty pairs and validate every repeated occurrence and every value. Decode percent-byte runs with a UTF-8 decoder configured to REPORT malformed/unmappable input, leaving literal plus unchanged. Validate decoded file/path last segments after backslash normalization and trailing slash removal, without modifying the original.
- `openObsidianLink(context, target)` rechecks admission, starts `ACTION_VIEW` with the original URI, and adds `NEW_TASK` only for non-Activity contexts. Return a static `MarkdownLinkNotice` on launch failure. No package lookup, visibility declaration or workspace-note routing.
- `rememberMarkdownUriHandler` continues forwarding web/mailto to `LocalUriHandler`, but invokes the Android opener for admitted Obsidian targets. A `LocalMarkdownLinkFailure` callback delivers static notices to the nearest screen. `ThreadScreen` and `MarkdownReaderScreen` provide their existing queue sinks; no widely used composable parameters change.
- Rendering and HTML inherit the expanded shared admission. Existing bare prose and code-span dispatch stay unchanged. HTML normalization is checked against malformed/encoded targets so it cannot bypass raw Obsidian admission.

## State and concurrency model

Validation and Android launch are synchronous on the UI tap. The failure callback enqueues into the existing composition-owned screen scope and existing `TransientErrorNoticeState`; no ViewModel, flow, I/O job or persisted state is added. `rememberUpdatedState` prevents a retained link listener using an old screen callback. Screen disposal cancels the scope and clears active/queued notices; background socket ownership is unaffected.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Finished and streaming explicit link activation | `MarkdownLinkTapTest` finished/streaming admission matrix, including unchanged URI and rejected inert labels |
| Streaming completion and settlement | Existing rejected-settlement and opt-in tests plus admitted Obsidian matrix |
| Repeated launches and both failure outcomes while a notice is active | New `ThreadTransientErrorTest` Obsidian launch/queue test, queued behind an attachment-style failure |
| Recomposition changes failure sink or workspace callback | `rememberUpdatedState`; existing workspace callback tests plus failure-sink replacement check |
| Screen exit/re-entry with queued failures | Existing `ThreadTransientErrorTest.leavingTheScreen_cancelsActiveAndQueuedErrors`; new notices use the same scope/queue |
| Configuration/process recreation | No link/launch state is retained; only a new user activation starts another intent |

## Error handling

Denied targets are inert and start nothing. `ActivityNotFoundException` returns “Obsidian is not installed”; any other launch exception returns “Could not open link”. Exceptions are dropped without reading messages or printing stacks. Diagnostics contain only static event/outcome codes, never URL, query, decoded values or exception text.

## Testing strategy

Write failing tests first and observe the missing admission. Port Desktop's complete Obsidian matrix into focused JVM coverage, extending it for empty/repeated pairs, missing equals, literal plus, all four keys, strict invalid UTF-8 and all-value checks. Route admitted targets with and without workspace opt-in and prove exact callback values; deny every rejected target. Preserve Mobile web/mailto admission.

Extend the existing finished/streaming shared screen matrix to verify one exact URL annotation, colour/underline, activation, inert rejected labels and HTML parity. Keep `MarkdownTextTest.a_bare_url_renders_as_text_and_is_not_a_link` unchanged. Robolectric opener tests cover Activity/non-Activity flags and exceptions whose message accessor throws. Thread tests exercise real link activation into visible queued errors, including repeated equal failures, timeout and coexistence with existing errors. Existing attachment, conversion, routing and transient-error classes are focused checks.

Add one device-only test class proving real Android intent delivery to a controlled activity declared solely in the test APK. A test context pins dispatch to the test APK after recording the untouched outgoing intent; the activity reports received action/data to a dynamically registered test receiver. This needs real Android resolution/delivery, which Robolectric cannot establish. No installed Obsidian, daemon or Claude turn is used. Run the class via the UI gate and retain counted XML, raw logs, source revision and Linux/Android identities in the persistent evidence directory.

Run focused JVM/shared classes, lint, APK assembly, instrumented Kotlin compilation, Spotless apply/check, then push before final merged-main assembly and `scripts/pre-verify.py --gradle`. Complete suites remain dispatcher-owned.

## Open Questions

None. Desktop's implementation and merged #2279 resolve the contract and integration seams.

## Security review

**Verdict:** PASS

- [Trust boundaries] MUST FIX addressed in design: shared raw-target admission guards rendering, HTML and activation. Duplicate parameters and malformed UTF-8 cannot hide an unsafe last occurrence. Launch rechecks admission. Scheme comparison must reject Unicode lookalikes; use a lowercased string compared exactly with the ASCII scheme, rather than Kotlin's broader `ignoreCase` equivalence.
- [Tokens] No credentials or new storage; query values never enter notices or logs.
- [Files/storage] No local file access. Colon, unsafe last-segment extensions and separator tricks are rejected; normalization is validation-only.
- [Android attack surface] Only admitted `open` intents leave the app; no production exported components or package queries. Controlled exported handler exists only in the test APK.
- [Cryptography] No cryptography or wire changes.
- [Network/I/O] No network work; existing bounded message rendering remains unchanged. URI decoding uses strict UTF-8 rather than Android's permissive decoder.
- [Errors/logging] MUST FIX addressed in design: catch exceptions unread and emit only static outcomes and resource copy. Test with hostile exception message accessors.
- [Concurrency] Reuse composition-owned queues; updated callbacks avoid stale screen sinks, and disposal cancels notices. No background launch jobs.
- [Threat model] Hostile daemon targets are confined to read-only Obsidian opening or existing Mobile web/mailto admission. Relay, rooted-device storage and screenshot/keyboard protections remain existing transport/platform responsibilities; this feature adds no sensitive storage or entry surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-18

## Revisions

- 2026-10-18: The first native run executed one case and exposed a test-handler process crash: the test APK excludes Kotlin runtime classes supplied by the instrumented application. The controlled handler is now a small platform-only Java activity, avoiding a dependency or production manifest change. Intent delivery and unchanged-URI assertions remain identical.
- 2026-10-18: Adversarial inspection found that Kotlin's `ignoreCase` accepts Unicode scheme lookalikes that Desktop's URL parser rejects. Admission now compares the lowercased scheme string with ASCII `obsidian`; `schemeCaseFolding_neverAdmitsUnicodeLookalikes` guards dotless i, dotted I and long s. HTML reads inline and reference destinations from their authored AST nodes before normalization, preserving the same raw-target contract.

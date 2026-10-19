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
- Workspace fallback remains a separate trust boundary: after removing fragment/line suffixes, `markdownLinkPath` checks for a scheme after leading-whitespace removal used only for detection, alongside the existing `://` rejection. Malformed opaque (`obsidian:open`) and single-slash (`obsidian:/open`) targets cannot become workspace reads. Accepted workspace paths are returned without whitespace normalization.

## State and concurrency model

Validation and Android launch are synchronous on the UI tap. The failure callback enqueues into the existing composition-owned screen scope and existing `TransientErrorNoticeState`; no ViewModel, flow, I/O job or persisted state is added. `rememberUpdatedState` prevents a retained link listener using an old screen callback. Screen disposal cancels the scope and clears active/queued notices; background socket ownership is unaffected.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Finished and streaming explicit link activation | `MarkdownLinkTapTest` finished/streaming admission matrix, including unchanged URI and rejected inert labels |
| Rejected URI shapes with whitespace and workspace opt-in | `MarkdownLinkRoutingTest.malformedNoteUris_neverFallThroughToTheWorkspaceReader`, `MarkdownLinkTapTest.finishedMalformedUris_stayInertWithWorkspaceOptIn` and `streamingMalformedUris_stayInertWithWorkspaceOptIn`; existing opt-in and path tests preserve legitimate workspace activation |
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

- [Trust boundaries] MUST FIX addressed in design: shared raw-target admission guards external rendering, HTML and activation. The independent workspace admission must also reject URI shapes, including whitespace-prefixed opaque and single-slash schemes; `markdownLinkPath` detects their schemes after validation-only leading-whitespace removal. Both `MarkdownUriHandler.accepts` and `routeMarkdownLink` use that guard. Duplicate parameters and malformed UTF-8 cannot hide an unsafe last occurrence. Launch rechecks admission. Scheme comparison must reject Unicode lookalikes; use a lowercased string compared exactly with the ASCII scheme, rather than Kotlin's broader `ignoreCase` equivalence.
- [Tokens] No credentials or new storage; query values never enter notices or logs.
- [Files/storage] Obsidian admission performs no local file access: decoded note paths reject colons, unsafe extensions and separator tricks, with validation-only normalization. Legitimate workspace links retain the existing opted-in daemon `read_workspace_file` flow and its confinement; rejected URI-shaped targets must reach neither that flow nor Android. Regression tests cover whitespace, URI variants and unchanged workspace line/fragment suffix handling.
- [Android attack surface] Only admitted `open` intents leave the app; no production exported components or package queries. Controlled exported handler exists only in the test APK.
- [Cryptography] No cryptography or wire changes.
- [Network/I/O] No network work; existing bounded message rendering remains unchanged. URI decoding uses strict UTF-8 rather than Android's permissive decoder.
- [Errors/logging] MUST FIX addressed in design: catch exceptions unread and emit only static outcomes and resource copy. Test with hostile exception message accessors.
- [Concurrency] Reuse composition-owned queues; updated callbacks avoid stale screen sinks, and disposal cancels notices. No background launch jobs.
- [Threat model] Hostile daemon targets can use admitted read-only Obsidian opening, existing Mobile web/mailto admission or the existing opted-in workspace reader for schemeless Markdown paths. Rejected URI shapes cannot cross into that reader through fallback. Relay, rooted-device storage and screenshot/keyboard protections remain existing transport/platform responsibilities; this feature adds no sensitive storage or entry surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-18

## Revisions

- 2026-10-19: Verifier finding 1 at `3122dcfb5` identifies the deliberately unfinished `EscapingFixture` running independently under normal Gradle discovery. Mark only that fixture with a test-only `ControlledRunnerFixture` category and always exclude its Vintage tag in `unitTests.all`; the explicit launcher retains its injected watchdog and no tag exclusion. Add `ordinaryDiscoveryExcludesControlledRunnerFixture` before the repair to prove the tag-filtered launcher neither starts the fixture nor arms a deadline, while the existing unfiltered Vintage regression still proves immediate fail-closed teardown. Run the affected Gradle class selection including nested classes to verify the actual discovery boundary, alongside all watchdog regressions. Keep timeout limits, graphics assertions, product code and native evidence unchanged. One repair with its regression, approximately 45 additional written lines, no production exports, consumers or error branches; the branch remains below 1600 written lines. The old `feature/gate-tooling-cleanup` overlap adds the already-shipped retention exclusion; no dependency. Security self-review: test-only discovery metadata crosses no product trust boundary and cannot disable the watchdog for application tests. Complete gates remain dispatcher-owned and pending.
- 2026-10-19: Recovery directs focused repair of JVM execution tracking after the retained `jvm-__axddbh` invocation halted with a glow deadline while both SDK executors were idle and the Gradle worker was shutting down. Read `PairCodeScreenFieldLayoutTest`, `PairCodeScreenFormStatesTest`, `PairCodeScreenGlowTest`, `RobolectricTestApp`, `TestTimeoutWatchdog`, invocation metadata and the full timeout dump together. First reproduce the named method, then the affected classes and, if isolation passes, their preceding sequence under ordinary Linux admission. Capture Vintage unique IDs, start/finish/skip/abort status, runner thread ownership, exception types and container/plan teardown; retain all results. Repair only the demonstrated execution-lifecycle invariant, with controlled regression tests in `TestTimeoutWatchdogTest`: terminal executions cannot retain an armed deadline, reused identities cannot be expired by an earlier execution, and an actually running timeout still interrupts and fails, including grace expiry and success after interruption. Keep the 180-second limit, 30-second grace, graphics assertions and original feature/native evidence. No product behavior changes or new production exports; this is the requested verification repair with its regression proof. Forecast at most 500 additional written lines, keeping the branch below 1600. No in-flight branch overlaps the watchdog. Complete Linux JVM/build, UI, scripted and independent review gates remain pending with the dispatcher. Test-only diagnostics contain execution identities and exception types, never product link targets or exception messages; the existing Security review remains applicable.
- 2026-10-19: Controlled red-first execution ran 13 watchdog tests, with nine expected failures. `vintageRunnerExceptionRetiresItsUnfinishedChild` drives the actual Vintage launcher: a child starts on a separate SDK-like thread, the runner throws, and Vintage finishes only the failed container and engine. Use per-execution records, cancelable futures and synchronized terminal/expiry ownership; retire skipped children as well as finished ones. Container/plan teardown with still-running children cancels their deadlines and fails closed immediately as an execution-protocol error, retaining identities and the original exception type rather than inventing a rendering hang. A timeout returning ABORTED or SUCCESSFUL must still fail; only a recorded failure may recover after interruption. Duplicate starts cannot replace an armed execution, and timeout counts belong to occurrences rather than reusable IDs. The historical full-suite initiating exception remains unclassified until a complete dispatcher gate supplies fresh evidence.

- 2026-10-18: Lint identified a configuration-stale `LocalContext.getString` call in the reader sink. Both screen sinks now resolve their static notice copy with `stringResource` during composition, following the existing reader attachment-notice map. Remember callbacks by the resolved copy and queue/scope so unchanged copy preserves the composition-local sink identity during streaming. The retained URI listener still reads the current sink; no suppression or configuration flag was added.
- 2026-10-18: Final main merge includes #2285's 16dp reader block spacing. Resolved the shared `MarkdownReaderScreen` call by retaining that upstream spacing inside this ticket's failure-sink provider; link behavior and notice layout are unchanged.
- 2026-10-18: `malformedNoteUris_neverFallThroughToTheWorkspaceReader` failed with a leading-space Obsidian URI ending in `.md`. The existing path heuristic mistook malformed URI shapes for workspace paths. `markdownLinkPath` now leaves targets containing `://` to external admission, where malformed targets are rejected; ordinary schemeless workspace paths and their line/fragment suffixes retain their behavior. Finished/streaming screen probes include the leading-space angle-bracket destination.
- 2026-10-18: The first native run executed one case and exposed a test-handler process crash: the test APK excludes Kotlin runtime classes supplied by the instrumented application. The controlled handler is now a small platform-only Java activity, avoiding a dependency or production manifest change. Intent delivery and unchanged-URI assertions remain identical.
- 2026-10-18: Adversarial inspection found that Kotlin's `ignoreCase` accepts Unicode scheme lookalikes that Desktop's URL parser rejects. Admission now compares the lowercased scheme string with ASCII `obsidian`; `schemeCaseFolding_neverAdmitsUnicodeLookalikes` guards dotless i, dotted I and long s. HTML reads inline and reference destinations from their authored AST nodes before normalization, preserving the same raw-target contract.
- 2026-10-18: Verifier finding 1 demonstrated that the earlier `://` correction missed whitespace-prefixed `obsidian:open` and `obsidian:/open` targets ending in a Markdown extension. Extend scheme detection in `markdownLinkPath` to inspect a leading-whitespace-stripped view without rewriting the returned workspace path or external target. Routing probes cover six whitespace prefixes, opaque/single/double/triple-slash shapes, other schemes and location suffixes; finished/streaming screen probes assert inert labels, absent styles/annotations, no tap callbacks and HTML parity. Existing workspace opt-in and exact-target checks remain required. Repair forecast: under 100 additional written lines, no new exported declarations or signature changes, one existing rejection branch; the whole ticket stays below 1600 lines. Current overlaps #1766, #2278 and #2284 change other renderer blocks, not this fallback guard.
- 2026-10-18: The first repaired screen run passed the streaming matrix, but repeated pointer taps in the finished matrix reached a vector draw where Robolectric legacy graphics returned a null bitmap. Run both new pointer-tap matrices with `@GraphicsMode(NATIVE)`, using the documented graphics setting and retaining all annotation, styling, callback and HTML assertions. The 18-test run (17 passed, one graphics failure) remains retained separately.
- 2026-10-18: Native graphics passed the streaming matrix but exposed a magnifier disposal failure in the finished matrix: rapid repeated taps at the same position were entering text-selection gestures. Advance input event timestamps by 1,000ms before each new malformed-target tap so every matrix case is an independent activation. Retain all cases, pointer taps and assertions; existing helper callers retain their timing through a default parameter. Preserve the second 17/18 result. Lint passed separately with no errors; the earlier bundled compiler/lint heap exhaustion is execution evidence, not a source change.
- 2026-10-18: Final pre-verify detected that main advanced with #2284's shared reader code panels. Resolve the import conflict by keeping this ticket's `LocalContext` and upstream's removal of the deleted reader-panel imports; retain the link-failure provider around the reader while using upstream's shared code typography. Admission, opening and error-queue behavior are unchanged. Repeat focused renderer/routing coverage, native delivery and final checks on the merged source.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/markdown-text-internals.md`, "Link safety — scheme allowlist" and "Markdown-path links (since #1050)": describe exact Obsidian admission, authored HTML destinations, unchanged bare/code behavior and rejection of whitespace-prefixed URI shapes before workspace fallback.
- Pending documentation stage: `docs/knowledge/features/markdown-reader-screen.md`, "Linked note, live (since #1050)" and failure-notice description: distinguish Obsidian ACTION_VIEW/NEW_TASK opening from workspace reads and document both static notices in the existing thread/reader queues.
- Pending documentation stage: the same feature overviews' testing sections: retain Linux/native evidence references and malformed-URI routing/rendering regression coverage.
- Pending documentation stage: `docs/knowledge/features/development-verification-gates.md`, per-test JVM timeout description: explain terminal deadline cancellation, occurrence-specific timeout state, immediate failure on unfinished container/plan executions and controlled-runner fixture discovery isolation; retain the Linux focused evidence and pending complete-gate distinction.

# #2119 — Diagnose and repair the live markdown-note scenario

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_markdownLink_opensLiveNoteInReader`, `notePrompt`, `assertLinkOpensNote`, `assertRefreshShowsNote`, `allowPromptsUntil`, `answerChat`, and `assertPeerAnswers` define the two-turn proof and its setup.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `recorded`, `allowOnce`, and `awaiting` establish permission handling and bounded peer waits.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RememberedEffortStage.kt`: existing static phase diagnostics provide the pattern, without payload logging.
- `docs/knowledge/features/markdown-reader-screen.md`: the linked note is freshly fetched on every open and Refresh; navigation must retain the underlying thread.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: counted XML and matched daemon revision are required; cleanup focus is not causal evidence.
- `docs/e2e-interactive-stream.md`: The ladder and credential seam define the real-daemon, real-Claude rung-3 path.

## Context

The #2057 full live gate recorded this method failing and then passing on the same tree, but historical logs and XML are unavailable on this host. Its GitHub gate comment identifies the occurrence, not the stack or cause. An untouched focused run on Mobile `e7a553c3d6d374ed47fabe5c2079fa9e7df80ae1`, daemon `8091a4730d42f43029eb0b955442dc300106030f`, and Claude Code 2.1.280 passed 1/1 without skips. The XML and logcat are under `build/dispatcher-tests/live-ge7rh4_o/`; both opens and Refresh logged success. This is a baseline, not a diagnosis.

## Design

First add test-only, content-free diagnostics local to the named scenario. `MarkdownNoteStage` and `markdownNoteStep` identify peer connection, phone setup, creation, peer readiness, opening the thread, first send/completion/open, rewrite send/completion, Refresh, Back, and reopen. Record start, complete, or failure, preserving the original exception object and stack. Do not add retries, increase timeouts, log fixture text, or change shared helper signatures. A phase wrapper runs its block once.

Capture a fresh failure and passing comparison. Only an evidenced mechanism may receive a localized harness or non-visual repair; append its contract and regression under Revisions before implementing that repair. No production design is selected from the passing baseline. A visual or ordering repair requires renewed refinement, and a daemon/relay prerequisite requires its owner's blocker.

The remote overlap scan found shared-file branches #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092, #2099, #2100, #2102, #2104, #2112, #2114, and #2117. Their changes concern other scenarios or helpers; keep this scenario's edits local and preserve their work.

Estimated total written work remains within 550 lines, with two new internal test types at most, no production API migration, three acceptance criteria and one deliverable: an evidenced repair of this scenario. Diagnostic tests alone do not complete the ticket.

## State and concurrency model

Diagnostics hold no asynchronous state and own no jobs. Existing Compose waits and peer coroutine scopes remain unchanged; the peer still closes in `finally`. Both real-Claude turns and permission handling remain in place.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| A stage succeeds | `MarkdownNoteStageTest.successRunsOnceAndRecordsCompletion` |
| A stage throws or is cancelled | `MarkdownNoteStageTest.failurePreservesTheOriginalCauseAndNeverRetries`, `cancellationPropagatesUnchanged` |
| The scenario runs again | Its run-unique fixture names remain unchanged; each phase call is independent. |
| Peer rewrites the open note | Existing live method requires Refresh to show the second marker and exclude the first. |
| Back and original-link reopen | Existing live method requires composer return and a fresh second-marker read. |

## Error handling

Log only static phase/outcome labels. Rethrow the original failure so XML retains the earliest failing stack. Record failure before scenario cleanup. Existing reader failure and stale-marker assertions remain unchanged.

## Testing strategy

Write phase regression tests first and observe their red result before implementing diagnostics. Run focused JVM tests and compile instrumented tests. Fresh investigative live execution uses the authorized builder-only launcher and reads the selected method's raw XML/logcat; its device-only reason is real Claude, remote filesystem rewriting, Noise/relay transport, and Android navigation together. The mechanism repair needs its own before/after regression. Run focused checks, lint, assembly, forced formatting, then merge main, push and run final `scripts/pre-verify.py --gradle`.

Dispatcher-owned acceptance remains pending: a fresh passing **full** live gate must explicitly execute this method, retain tested Mobile/daemon revisions, raw XML/log references and executed/failed/skipped counts before documentation and merge. A focused pass cannot substitute.

## Open Questions

Resolved by the controlled matcher regression below. The historical occurrence's exact trigger remains unproven because its stack is unavailable.

## Revisions

### 2026-10-12 — Rendered-link qualification

The baseline and immediate-predecessor live selections passed (1/1 and 2/2, no skips); they do not diagnose the historical flake. A controlled real-Compose fixture established a localized harness defect at the FirstOpen/Reopen tap in `assertLinkOpensNote`: the exact `notePrompt` includes the requested link label, but user bubbles render plain text. When that prompt and the assistant link are composed together, the original substring matcher selects the prompt first. `performFirstLinkClick` then throws “No link found that matches the predicate”; repeating the same first-node selection cannot satisfy the original wait while both nodes remain composed.

`MarkdownNoteLinkTest.thePromptContainingTheRequestedLinkDoesNotCaptureTheAssistantLinkTap` uses the unchanged shared `notePrompt` and `markdownNoteLinkMatcher`, with real user and assistant `MessageBubble`s. Before repair it executed once and failed at the tap, with no skips. Retained stack/count evidence is `app/src/test/resources/markdown-note-2119/matcher-red.xml`; original raw XML is `app/build/test-results/testDebugUnitTest/TEST-de.pyryco.mobile.e2e.MarkdownNoteLinkTest.xml`. This proves the helper mechanism, not the unavailable historical trigger.

Repair only the matcher: require a link annotation whose rendered label equals the requested label, within the same message-bubble ancestry. Keep substring text eligibility, both real-Claude turns, all reader/filename/stale assertions and every deadline. Share the exact prompt and matcher with the controlled tests. Cover first open, reopen after the rewrite, and a plain or incompletely revealed label without any link; the latter must remain ineligible rather than allowing an early tap. No production or visual change. Security re-review: PASS; annotation inspection is test-only, uses no network or paths, and logs no text. Static phase diagnostics retain the earliest failure for future live occurrences.

The identical native Android 13 test also failed with the original matcher (1 executed, 1 failed, 0 skipped, the same no-link stack), then the repaired whole regression class passed 3/3 without skips. Retained native XML and source/revision/phase attribution are in `app/src/test/resources/markdown-note-2119/`, including `evidence.txt`. The final focused JVM selection passed 43/43 without skips. Full dispatcher live acceptance remains pending; no post-repair focused live run is claimed.

## Security review

**Verdict:** PASS for the diagnostic design; review any later repair in Revisions.

- Trust boundaries: `markdownNoteStep` accepts a static enum and records no daemon or fixture data; reader decoding and path confinement remain on the existing path.
- Tokens: pairing and live credentials remain in the authorized launcher and existing stores; no new credential reads or logging.
- Files/storage: no new app persistence, fixture paths, cache, or production writes. Raw harness evidence is test output.
- Android attack surface: no component, intent, provider, exported surface, or WebView changes.
- Cryptography: existing Noise handshake, keys, nonce ownership and relay TLS are unchanged.
- Network/I/O: existing bounded peer and Compose waits remain; no widened limits or added requests.
- Errors/logging: static phase/outcome only; original failures propagate, but diagnostic records never interpolate messages, paths, payloads, tokens or stack text.
- Concurrency: a phase invokes once synchronously, preserves cancellation, and cannot create a detached job; peer cleanup remains guaranteed.
- Threat model: relay delay/drop still fails bounded waits; hostile daemon frames retain existing decoding. Rooted-device token theft and UI-side content leakage are outside this test-only change, with existing Keystore and native reader controls unchanged.

**Reviewer:** builder, self-review per `builder/security-review.md`.
**Date:** 2026-10-12.

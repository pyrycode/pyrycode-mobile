# Snackbar routing guard (#1750)

## Files read

- `app/build.gradle.kts`: unit-test configuration and source sets provide the normal `check` integration.
- `gradle/libs.versions.toml`: the Kotlin version supplies the matching test-only compiler parser.
- `ThreadScreen.kt`: `ThreadScreen` permits only attachment Saved and `ModalUiState.Dismissed` snackbar routes.
- `MarkdownReaderScreen.kt`: `MarkdownReaderScreen` permits only the Saved arm of `rememberNoteSaver`.
- `ArchivedDiscussionsScreen.kt`: `ArchivedDiscussionsScreen` permits only `ArchivedDiscussionsEffect.RestoreSucceeded`.
- `ChannelListScreen.kt`: `ChannelListScreen` has no snackbar after #1748.
- `docs/knowledge/features/development-verification-gates.md`: source checks belong in normal JVM verification; executed counts are required evidence.
- `docs/knowledge/features/thread-screen.md`, `markdown-reader-screen.md`, `archived-discussions-screen.md`, and `channel-list-screen.md`: migrations #1747–#1749 landed; no presentation or lifetime changes belong here.

## Context

All three blockers are closed and their migrations are on the starting tree. The remaining four routes are non-errors. A future screen must not silently introduce a snackbar failure route. This is one test-gate deliverable, estimated at roughly 350–450 written lines including the plan and controls, with no production edits, exported production types, consumer changes, or error-state branches. No overlapping remote feature branch changes the planned files.

## Design

Add `SnackbarRoutingGuard` and `SnackbarRoutingGuardTest` under `app/src/test/java/de/pyryco/mobile/verification/`. Use Kotlin's compiler PSI parser, as a test-only dependency aligned with the existing Kotlin version, rather than a keyword or call-count heuristic. Walk every production Kotlin/Java source root configured by Android, including build-type and flavor roots. Supply those roots through a Gradle unit-test system property and register their source files as task inputs, so source-only changes invalidate cached test results.

Every Kotlin `showSnackbar` reference must sit inside an explicitly classified syntax region in its owning screen and function. Classifications pin the complete attachment callback (message derivation and Saved-only conditional), the complete dismissed-modal branch (reason derivation and keyed effect), or the complete restore-success branch (success type and resource). Branch classifications also pin the enclosing `when` subject. Compare syntax tokens, ignoring only whitespace and comments. This retains string-template expressions, callable references, backtick names and aliased imports in the inspected syntax. Any unmatched reference fails with its source path and offset. Java snackbar references have no classifications and fail closed.

The allowlist documents why each route is a non-error. New notices require an explicit routing classification and positive/negative controls in this test gate; changing source shape requires reviewing the classification. No Error-pill or snackbar production code changes. This is a local source contract, not whole-program dataflow analysis or a replacement for review of message producers.

## State and concurrency model

The gate runs synchronously in the existing JVM test task. Its parser environment is disposed after each test; it introduces no app state, flows, jobs, or runtime dependencies.

## Error handling

Missing root configuration or an empty production-source scan fails rather than producing a vacuous pass. Unclassified snackbar references are assertion failures. Unsupported Java routes are rejected rather than guessed safe.

## Testing strategy

Write controls first and observe the added error-route control fail against an initially permissive guard. Then implement classification and run all controls plus the real production-source assertion. Controls cover all permitted routes, a new screen with neutral-looking failure copy, an added error arm in an existing screen, reversed/widened Saved guards, altered message derivation, restore failure, changed modal reason, callable references/aliases, comments/formatting, and string interpolation. Tests are JVM-only, so no device, scripted, or real-Claude scenario is needed for this gate-only ticket.

Run focused `testDebugUnitTest`, lint, assembleDebug, and Spotless. After the last merge of main and push, run the complete JVM suite, assembleDebug, and `scripts/pre-verify.py --gradle` with the draft PR body. Retain positive and negative-control XML/results under test resources so the evidence survives the worktree.

## Open Questions

None.

## Documentation handoff

Pending for the documentation stage: document how the guard runs, its permitted routes, and how a new notice is classified in `docs/knowledge/features/development-verification-gates.md` under “Gradle and source checks”.

# #1684 — Interrupted-upload live proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes`, `documentFixture`, `DOCUMENT_BYTES` and `CUT_AFTER_CHUNK`; unchanged failed-send and retry contracts.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `keyStore`; the live peer consumes the merged identity repair.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceKeyStore.kt`: `identityFor` and `loadOrCreate`; process-local pairing identity and defensive key copies.
- `docs/knowledge/features/attachment-upload.md`: three-chunk interruption and exact-byte retrieval coverage.
- `docs/knowledge/features/development-verification-test-scheduling.md`: sequential token reuse requires retained identity; isolated compilation is not live proof.
- `docs/e2e-interactive-stream.md`: interrupted-upload coverage and Verification status; counted full-suite evidence and dispatcher ownership.
- `docs/specs/architecture/1697-phone-attachment-live-proof.md`: nearest verification-only analogue and merged repair provenance.

## Change

Record a no-code resolution after shared peer identity repairs #1698/#1686. The checkout starts at `fb2ca8f1a30ae115f46efba7764d22b587e115ef`, containing both repairs and #1697's counted live baseline. Independent comparison with failing base `acf0f6591cb7d956ae2584da1eda1c5d1ddd0c4b` finds the named scenario body unchanged, with unchanged 100000-byte fixture target and cut-after-first-chunk constant. Preserve all assertions, waits and cleanup. No residual upload defect is established by the old unnamed coroutine timeout; no additional implementation or regression test is justified. Only this plan changes. The fetched remote branches have no overlap with this writable path, so there is no implementation dependency. Scope is one verification deliverable, two acceptance criteria, under 100 written lines, no new types, consumer updates or reject branches.

## Testing strategy

Independently parse retained dispatcher XML and adjacent process evidence:

- `2026-10-04T02-57-03-837Z_real-claude-gate_#1686.log`: 53 executed, 53 passed, 0 failed, 0 errors, 0 skipped; process exit 0.
- `2026-10-04T04-03-12-661Z_real-claude-gate_#1697.log`: 53 executed, 53 passed, 0 failed, 0 errors, 0 skipped; process exit 0.

Both reports under `/Users/juhanailmoniemi/Workspace/Projects/pyrycode-mobile-agents/logs/` contain exactly one passing `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes` testcase. Published provenance: [#1686 gate](https://github.com/pyrycode/pyrycode-mobile/issues/1686#issuecomment-5976044606) and [#1697 gate](https://github.com/pyrycode/pyrycode-mobile/issues/1697#issuecomment-5976504334). These are retained full-suite passes, not a new #1684 run.

The preserved scenario attaches a roughly 100 KB document using 45000-byte chunks, cuts after the first chunk, retains composer text/file and observes zero peer user messages. Restoring the link and retrying completes a real turn; peer history must contain exactly one user message naming exactly one attachment ID, whose retrieved bytes match the fixture SHA-256 digest. No skip, timeout increase or weaker assertion is introduced.

Run existing focused peer-identity/readiness/redial/wait JVM tests, lint, debug assembly and forced Spotless checks. No device test or scripted run is required for this plan-only change. Keep `needs-real-claude` and set the PR's Live tests to `all`: the dispatcher must run a fresh full live suite for #1684 after verification, record executed/failed/error/skipped counts and process status, and explicitly confirm this named method ran and passed. Pending live execution is a later-stage handoff, never a claimed candidate pass.

## Documentation handoff

- Pending documentation stage: update `docs/e2e-interactive-stream.md`, Verification status, with #1684's no-code resolution, shared repairs #1698/#1686, and counted named-method proof. Record the fresh dispatcher candidate revision, report paths, executed/failed/error/skipped counts, process status and explicit named-method pass; distinguish it from the independently counted retained #1686/#1697 baselines.

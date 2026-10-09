# #1969 — Restore model-change live proof after daemon repair

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`, `interactiveTurn_attentionDot_followsARealTurn`, and their settings/read helpers define the inherited proof.
- `scripts/android-test-gate.py`: focused live selection, isolated daemon build, credential ownership and counted XML evidence.
- `scripts/e2e-emulator.sh`: daemon/mobile revision reporting and unchanged live scenario selection.
- `docs/knowledge/features/development-verification.md` and its emulator-evidence/test-scheduling topics: focused execution cannot establish full-suite fixture isolation; zero execution is unverified.
- `docs/knowledge/features/thread-composer-footer-testing.md`: model settings confirmation uses a fresh connection reply.
- `docs/knowledge/features/thread-screen-testing-foreground-read-tracking.md`: retain durable read confirmation rather than inferring a read from list state.
- Daemon `cmd/pyry/pool_adapters.go` (`settingsUpdaterAdapter.UpdateSettings`) and `cmd/pyry/session_model_selection.go` (`offeredModel`, `runSettingsFor`): merged #3017 validates offered identities and projects stored families back onto published rows.
- Sibling daemon `docs/protocol-mobile.md`, `model_list` and `session_settings`: authoritative offered-choice/readback contract; no mobile wire change.

## Change

This is proof-only work. Keep both inherited methods, their helpers, assertions and deadlines unchanged. The previous unchanged-main run at mobile `07d6c93d23a53500b4de6c7cca0baba50b9ba7eb` and daemon `55f1f1839c72ebd140679ddcb3c1db3d2d30c0d3` executed two tests: attention passed, model-change failed after X's explicit Fable pick. Daemon #3017 fixes publication/validation/readback consistency; its implementation is `179b080ad74664d70cad5c1d0a7fe9c02eed173b`, merged at `b799ba5afb8d86b79f1d1eb20c737c15a632db5f`. Run the existing proof against that merged daemon in an isolated source worktree and record evidence here and in the PR. No production code, new test or UI behavior is needed.

Sizing: one deliverable, two acceptance criteria, about 80 written lines including evidence/PR, zero new exported declarations, consumer updates or reject branches. No planned source edits overlap in-flight branches; other live-test branches share the inherited test file but it is preserved here.

## Testing strategy

Run both named inherited methods together through `scripts/android-test-gate.py live --tests` with the merged daemon source. Existing real-Claude instrumentation is necessary to prove settings acknowledgement and phone/peer durable reads. Preserve model announcement/inheritance, exact published-value acknowledgement, reopen persistence and X/Y isolation; preserve attention phone/peer confirmation, row isolation and permission waiting/answer checks. Read fresh XML and report revisions, executed/passed/failed/skipped counts and both named outcomes. Historical red evidence is linked from the issue's builder comment; do not rerun the known defective daemon or weaken its regression.

Run builder lint, assembly, Android-test compilation and forced Spotless checks. After the final merge of main, push and run assembly plus `scripts/pre-verify.py --gradle` against the intended PR body. No new logic requires a new unit test.

## Dispatcher live handoff

The second acceptance criterion remains pending until a fresh **full** dispatcher live gate after verification. Set `## Live tests` to `all` in the PR so the gate cannot select only the focused methods. Keep `needs-real-claude` on #1969. The dispatcher must use daemon #3017's merged result and report actual daemon/mobile revisions, full executed/failed/skipped counts and explicit passes for both inherited methods. Focused success does not satisfy full-suite acceptance.

## Revisions

2026-10-08: The isolated daemon worktree build omitted `vcs.revision` even with `-buildvcs=true`. A separate clean clone at the same merged commit produces versioned metadata. Use its binary/source for the final focused proof and dispatcher handoff; preserve all mobile scenarios and settings contracts.

2026-10-09: Blocker #1989 is closed and its regression coverage is merged through PR #1995. Diagnosis established daemon ownership: #3026 restores continuous legacy history across runtime-only receipts, and #3029 aligns legacy unread targets and read confirmation. Resume the unchanged focused pair using a clean daemon clone at `a39c72739eb2e811708a67b08906614a4316b834`, which includes all three daemon repairs. Preserve the historical failed runs below; this ticket still requires its own fresh full dispatcher live gate after verification. No implementation, scenario, helper, assertion or deadline changes are planned.

## Execution evidence and blocker

2026-10-08: The versioned focused run used mobile `81ad5e795114655f53f8105b4a45643a51402d2a`, daemon `b799ba5afb8d86b79f1d1eb20c737c15a632db5f` (`vcs.modified=false`) and Claude 2.1.280. It exited 1: 2 executed, 1 passed, 1 failed, 0 errors/skipped. `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` passed. `interactiveTurn_attentionDot_followsARealTurn` failed at A's phone-confirmed read before B's permission/peer-read checks. Diagnostic: `rows=2 unidentified=0 malformed=0 gaps=0 versions=2 checkpoint_reaches_target=false read_reaches_target=false`.

Fresh XML is retained in `build/dispatcher-tests/live-c7calbjd/dispatcher.xml` and `0-TEST-pixel2Api33Atd-_app-.xml`. The earlier `live-4errldb5` run at mobile `a1e388ce1e5060b6f8506560252ff6f90159842e` used the same daemon source without binary VCS metadata and produced the same counts/outcomes. Copies of both runs are under `/tmp/builder-1969/proof-20261008/`.

Filed and linked [#1989](https://github.com/pyrycode/pyrycode-mobile/issues/1989) for diagnosis/repair of the repeated attention failure. It is an implementation dependency, not a split child. Repository ownership of the defect remains unproven; no mobile workaround, assertion change, timeout increase or ignored method was introduced. This proof-only ticket waits for that repair. The fresh full dispatcher live gate and its explicit passes remain unverified.

Builder lint, debug assembly and Android-test compilation passed. Production, tests and scripts remain identical to `origin/main`.

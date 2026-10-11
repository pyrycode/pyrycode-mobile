# Investigate scripted durable catch-up closure (#2069)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DurableGapProof.kt`: `cacheBaseline`, `missPages` and `catchUp` retain the multi-page fixture and independent rendered-marker assertion.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/DurableHistoryProbe.kt`: passive request, page and row observations do not initiate demand.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/UnrecognizedRowSentinel.kt`: the repository wrapper records existing requests and delivered snapshots.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryCoverage.kt`: `receivedUnsigned` counts every received durable identity and joins spans only through actual receipt.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryDisplayProjection.kt`: `projectDisplay` retains hidden gaps while selecting eligible adjacent-span and oldest-edge markers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `threadContent` combines coverage, held rows and queue eligibility, then prepares and paces complete projections.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryDisplayProjectionTest.kt` and `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryProjectionWorkerTest.kt`: current projection and supersession controls.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-oldest-end-history-demand.md`, **The oldest-end history demand**: display eligibility never grants receipt or originates a request.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`: durable positions, overlapping-page reconciliation and independent cursors.
- `docs/e2e-interactive-stream.md`, **Scripted ping durable-gap proof (#1842)**: retain both ordered methods, 120-post batches and independent marker closure.
- `docs/specs/architecture/2070-scripted-reply-checkpoint.md` and retained `read-2070` evidence: original daemon filters queued-send identities; merged daemon #3167 accounts for them. Its passing durable twin is a control to reproduce independently here.
- Sibling `pyrycode/docs/protocol-mobile.md`, **Security model**: authenticated receipt never implies complete history or qualified sight.
- Installed Compose `ActionsKt.performScrollToNode`: exhausted lazy-list search throws the specific absence assertion; successful search returns normally.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and the dark thread screenshot. Message bubbles occupy chronological rows with side actions, small session rules, an overlaid title bar and fixed composer. Preserve the existing Material theme tokens, assets and `HistoryGapRow` geometry; this ticket changes observation and regression evidence only unless diagnosis identifies a Mobile causal defect.

## Context

The original daemon and reviewed Mobile branch recover the fixture's text but time out waiting for displayed marker closure. A non-rendering durable identity omitted by a daemon pager can leave a genuine hole despite content recovery. #2070 now records a passing pair against merged daemon #3167; independent coverage/marker evidence must establish whether the two failures share that cause. No decision record is needed.

One deliverable, four criteria, forecast at most 650 written lines including probes, diagnostics, plan and retained counted evidence. No exported production types, signature migrations or new state-machine rejects are planned. Remote feature overlap check found no overlap in the proposed proof/probe/projection files. #2070 and daemon #3167 are closed; their merged repair is available for isolated test binaries.

## Design

Capture the unchanged ordered pair on the configured sources as a control. Add content-free diagnostics around newest receipt, each completed physical pull, fixture recovery and the independent final marker observation. Correlate page counts and durable spans/gaps with `projectDisplay` on the passive held snapshot and actual Compose marker search; record only static phase names, counts, durable numbers and booleans. Do not emit content, cursors, routing ids, hashes of sensitive content or credentials.

Reproduce against the exact original daemon revision and compare with the merged #3167 daemon, using isolated source archives under `/tmp/builder-2069/` and the configured relay. Do not change the live sibling checkout. If an omitted non-rendering receipt explains the surviving eligible marker, retain the real repaired daemon fixture already exported by #2070 and add coverage/projection regressions through Mobile's actual decoder, reducer and merge. Mobile must continue displaying that unresolved marker on the original incomplete page. Link the external owner rather than adding a Mobile completeness workaround. If diagnosis instead identifies a Mobile defect, record the causal transition and smallest repair under Revisions before implementation.

Keep `catchUp`'s content-recovery loop, physical pull budget, final independent marker-closure wait, deadlines and all chronology, uniqueness, settled reply and cached-row assertions intact. No ignore, retry or automatic older demand is introduced. Retain fresh counted pair XML and invocation/revision/artifact metadata under `app/src/androidTest/assets/durable-2069/`.

## State and concurrency model

Existing `viewModelScope`, coverage `StateFlow`, worker `mapLatest`, frame pacing and the single request slot retain ownership. Diagnostics only read passive snapshots and saved coverage. Unit probes restore serialized coverage into fresh values and drive actual received pages; no new production flow, coroutine or persistence state is introduced. Existing background socket-close behavior remains authoritative.

## State transitions and identity reuse

| Event | Test / evidence |
| --- | --- |
| Received text with an omitted non-rendering durable id | `ScriptedDurableGapCoverageTest` negative control retains eligible marker despite complete rows |
| Overlap fills non-rendering hole; duplicate/reordered pages | `ScriptedDurableGapCoverageTest` repaired receipt probes require joined spans and marker disappearance without another demand |
| Reconnect / restored coverage with same conversation and held content | Serialized restore probe retains unresolved gaps, chronological neighbours and settled reply |
| Hidden gap / unknown oldest edge / empty and one-entry pages | Coverage probes distinguish authoritative metadata from eligible markers; existing `HistoryDisplayProjectionTest` controls |
| Daemon restart, retained durable home, emptied replay and physical reader pulls | Unchanged ordered deterministic pair; exactly one newest availability ask and one older page per pull |
| Screen exit or projection supersession | Existing `ThreadHistoryProjectionWorkerTest.mainProgressInvariant_pendingWorkerAllowsDraftEdit_latestInputWins_andExitCancels` |

## Error handling

An unresolved eligible gap remains visible and cannot be certified by recovered text. Unknown or hidden coverage remains authoritative. Absence detection accepts only Compose's specific exhausted-search assertion; other failures propagate. Add diagnostic context to timeout failures without changing deadlines or interpreting diagnostic projections as actual rendered state.

## Testing strategy

Observe a controlled positive coverage/marker regression fail with the original omitted receipt before using the repaired real daemon fixture. Keep an explicit negative control permanently. Probe overlapping, repeated, reordered, empty and one-entry pages and reconnect/restoration through production coverage/projection and merge functions. Run the new class, existing coverage/projection/worker tests and existing shared `ThreadScreenHistoryTest` demand/settlement coverage.

Run the original ordered `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted ping` selection with source overrides for exact original and repaired daemon archives. The device-only reason is real authenticated daemon/relay delivery, restart and physical reader input; the controlled regression belongs in unit tests. Retain nonzero executed/passed/failed/errors/skipped counts, app commit, source revisions and paths. #1833 retains the rung-3 offline-read operator proof. Dispatcher owns scripted-all and must report the durable-gap method's executed/passed/failed/skipped counts.

Run lint, assembleDebug, Android-test compilation when the proof changes, Spotless apply and forced check. Push coherent commits, merge main once, then run final assembly and `scripts/pre-verify.py --gradle` before opening the PR. No full unit/device/scripted suite is builder-owned.

## Open Questions

- Resolved: original daemon filtering leaves genuine eligible holes at queued-send identities. Mobile coverage, projection and exhaustive absence detection remain correct; daemon #3167 supplies the repair.

## Documentation handoff

Pending documentation stage:

- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`, **The oldest-end history demand**: record the causal transition and why content recovery cannot replace receipt coverage.
- `docs/e2e-interactive-stream.md`, **Scripted ping durable-gap proof (#1842)** and **Verification status**: record fresh original/repaired pair evidence, revisions and artifact paths, preserving #1833 ownership; add the dispatcher scripted-all durable method's counted result when available.

## Security review

**Verdict:** PASS (investigation contract; review any concrete Mobile repair in Revisions).

- [Trust boundaries] `HistoryCoverage.receivedUnsigned` accepts actual decoded durable ids only. MUST preserve the negative control for omitted non-rendering ids; text, live event ids and marker visibility cannot grant coverage.
- [Tokens] No key/token generation, storage or lookup. Diagnostics and retained evidence never contain credentials, pairing records or keys.
- [Files and storage] Fixed test asset paths and isolated test source archives only. Production cache encryption, app-private namespaces, atomic writes and backup rules are unchanged.
- [Android attack surface] No exported component, intent, permission, provider, WebView or push change.
- [Cryptography] Noise variant, AEAD nonce ownership and Keystore wrapping remain unchanged.
- [Network and I/O] No new wire verb, decoder allowance, timeout, transport or retry. Use only the existing test daemon identity and FIFO managed-device gate.
- [Errors and logs] SHOULD FIX: retain static-phase diagnostic evidence with durable numeric coverage and projected/observed marker counts. Never emit text, payloads, cursors, routing ids or decrypted bytes; sanitized XML strips raw test output.
- [Concurrency] Diagnostics never originate requests or subscribe to a second history stream. Original page/coverage/row state owners remain unchanged; restore and overlap probes prevent transient content recovery from certifying completeness.
- [Threat model] Relay omission/delay/replay remains conservative through durable holes. Hostile daemon omission cannot hide a genuine marker. Token theft and UI screenshot/accessibility/keyboard surfaces gain no new production exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

## Revisions

### 2026-10-11 — Correlated evidence establishes the sibling owner

The exact original daemon `b75aea7948b3` reproduces both original failures at Mobile `6b7f96f23`: 2 executed, 0 passed, 2 failed, 0 errors/skips. After three physical pulls the durable fixture has 245 held rows, including every post and the settled reply. Saved coverage still has spans `1–242`, `244–249`, `251–495`, with holes at durable ids 243 and 250. `projectDisplay` selects anchors 242 and 249. Exhaustive Compose search succeeds and finds two composed markers. The final absence wait correctly times out; neither projection nor absence detection is stale.

Content-free metadata extracted from the isolated retained daemon history identifies 243 as `send_accepted` and 250 as `send_delivered`. `queuedSendHistory.delivered` appends the delivery fact, while original `legacyHistoryType` and `legacyRuntimeFact` both exclude these types; `newHistoryPager` therefore filters them from the received page while retaining their surrounding durable positions. Text recovery cannot bridge those missing identities. Closed [daemon #3167](https://github.com/pyrycode/pyrycode/issues/3167), merged at `a3ca005f05e9`, owns the repair: validated queued-send facts become non-rendering info-banner receipts on legacy routes. No Mobile production workaround is needed or permitted.

The controlled omitted-receipt test failed with expected continuous `1–7`, actual `1–2` and `4–7`. Restoring the real repaired page's delivery receipt closes the marker without replacing any read content. The reducer intentionally retains an invisible info-banner row; compare read content rather than all held metadata rows. Five new probes plus the existing coverage, projection, worker and shared demand classes executed 64 tests, all passed with zero failures/errors/skips. Numeric diagnostics in the existing proof and passive page tap preserve every fixture, demand, timing and independent rendered assertion. The probe reset test also checks foreign conversation exclusion and repeated begin/end.

Security re-review: PASS. The concrete change adds test-only numeric observations and fixed synthetic contract probes. It introduces no runtime allowlist, new receipt identity, sight, persistence, wire shape, transport, credential access or production lifecycle state. A hostile omission remains a visible eligible gap. Sanitized evidence retains statuses and static failure categories only, without raw XML output or daemon payloads. Forecast remains below 650 written lines and zero exported production declarations or migrations.

The configured older daemon `a536d17b1e18` also passed both methods in the initial control, but it predates the implicated queued-send facts and is not acceptance against the original defect. Final repaired-daemon comparison and dispatcher scripted-all evidence remain separate checks.

### 2026-10-11 — Repaired ordered pair closes both markers

The unchanged pair against daemon `a3ca005f05e9` and relay `a7a05d1f123d` at app `6b7f96f23` executed/passed 2, with zero failures/errors/skips. The first older page now supplies 200 receipts rather than 198; the two additional non-rendering rows account for the original missing ids. Two pulls recover the fixture and join coverage into `1–495`. Projection has no marker, and exhaustive Compose search reports absence without another request. The application APK hash matches the original failing run; only test evidence assets differ in the test APK.

Counted XML, numeric observations, raw-hole metadata, commands, full source revisions, APK/binary hashes and original artifact paths are retained under `app/src/androidTest/assets/durable-2069/`. The repaired raw result lives in `build/dispatcher-tests/scripted-ed0na53x/0-TEST-pixel2Api33Atd-_app-.xml`. Dispatcher scripted-all must independently execute and pass the durable method against the repaired daemon; no full gate result or fresh rung-3 live result is claimed here.

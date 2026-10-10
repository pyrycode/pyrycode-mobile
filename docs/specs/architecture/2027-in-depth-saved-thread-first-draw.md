# In-depth saved-thread first draw (#2027)

## Files read

- `SavedThreadFirstDrawDeviceTest.kt`: `Probe`, `repository`, `openAndMeasure`, `withFixture`; measured cold restore through exact viewport text and committed frame.
- `FileConversationCache.kt`: `readDecodedThread`, `decodeThreadRecord`, `parseThreadInstant`; landed #2026 allocation repair and unchanged validation boundaries.
- `docs/specs/architecture/2026-saved-thread-restore-latency.md`: diagnosis, partial misses and isolated acceptance evidence.
- `docs/knowledge/features/thread-screen-testing.md`: Allocation margin and retained evidence (#2018); isolated proof and historical misses cannot be replaced by whole-class passes.
- `docs/knowledge/features/conversation-cache-layout.md`: exact-byte freshness, decoder-only configuration and legacy timestamp fallback.
- Agents `scripts/android-test-gate.py`: `device_hold`, UI selection and `run_on_device`; in-depth uses all non-e2e tests across two managed-device shards, with animations disabled.

## Design source

N/A: verification/latency work preserves existing visual design. Refinement explicitly requires no new Figma anchor.

## Context

#2026 merged at `79b212e949b840b3aad5f96137bd6ad4377a35b9`. Its JSON and timestamp allocation repairs passed isolated and regular UI evidence, but subsequent #2006 isolated runs missed at 1402 and 2054 ms. The latter cache worker measured 1201 ms wall / 488 ms CPU, unlike #2026's 278 / 269 ms passing worker. This suggests scheduling pressure without establishing a cause. The original in-depth sweep missed at 1577 ms; its teardown focus record does not establish focus loss. No decision record is expected.

## Design

First compare fresh isolated first-draw execution and the existing in-depth non-e2e selection on the configured Pixel 2 API 33 ATD, from this worktree. Use existing FIFO device coordination and unchanged gate animation settings. Retain XML, selected-method logcats, command, revision, exit and counts for every run, including misses. Do not substitute another performance endpoint or discard misses on retry.

If a residual failure remains, use cumulative restore/snapshot/complete/committed-draw measurements, worker wall/CPU and lifecycle evidence to establish whether production work or the test environment causes it. Record the selected repair in Revisions before implementing it. Prefer a local allocation/work repair in at most one production file, or a demonstrated lifecycle correction in the test. If the cause belongs to agents pipeline tools, hand off to that repository rather than edit its tools here. Evidence alone suffices if the landed repair resolves the residual failure; no artificial code change.

Preserve 20 ordinary messages, 18000 fragmented displayed rows, 36000 durable entries and 18000 spans; fresh cache/repository construction on first open and fresh ViewModel/composition with retained repository on reopen; exact saved rows and marker anchors; zero offline and one held connected newest-page request per opening. The timer remains monotonic wall time through a committed frame containing the exact newest text wholly inside the viewport, with the unchanged 1000 ms bound.

Sizing: one deliverable, three criteria, forecast at most 450 written lines, zero exported types/signature migrations, unchanged reject branches. Recount after diagnosis. No numeric in-flight feature branch overlaps the device test or cache file at initial fetch.

## State and concurrency model

Existing cold repository collection, IO dispatcher/mutex, scoped writers, ViewModel ownership and Android frame callbacks remain. No new job, flow, state or lifecycle behavior is selected before evidence establishes a defect. Device runs use the existing FIFO hold; timing never excludes work within the specified opening.

## State transitions and identity reuse

| Event | Verification |
| --- | --- |
| Ordinary/fragmented, offline/held newest, fresh repository | Isolated `savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`, four first opens |
| Fresh ViewModel/composition, same repository | Same method, four reopens |
| Injected 3000 ms restore delay | Isolated `slowRestore_negativeControlRejectsTheSameFirstDrawBound`, identical bound rejected |
| In-depth non-e2e device selection | Both named methods, fresh counted XML/logcat; retain unrelated failures separately and reassess scope |

## Error handling

Existing persistence validation, independent metadata rejection, exact-byte freshness and classified failures remain authoritative. No logging of daemon text, identities, cursors, paths, document bytes or credentials. Evidence logs contain static fixture labels, counts and durations. Any new unrelated failure requires renewed scope assessment, not a neighboring production fix.

## Testing strategy

Use the existing isolated method as the regression; watch any remaining miss before repairing its established cause. New logic requires a focused regression before implementation, with cache/proof tests if production persistence changes. Device-only reason: real disk, coroutine scheduling and Android committed frames cannot be verified by Robolectric virtual time.

Run isolated first draw, isolated negative control and ticket-required in-depth selection with unchanged configured managed device/contract. Record executed/passed/failed/skipped/error counts and all eight phase tuples; retain fresh XML/logcat under `/tmp/builder-2027/`, with previous sweep and #2006 misses alongside them. Then lint, assemble, forced formatting and final `scripts/pre-verify.py --gradle` after merging main and pushing. Dispatcher owns the configured regular UI gate, which must execute and pass both named methods with nonzero counts; the post-merge main sweep remains dispatcher-owned. No live daemon/Claude scenario is needed.

## Open Questions

- Which timed phase causes any remaining miss, and is it production latency or a demonstrated environment defect? Resolve with comparative evidence in Revisions.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/thread-screen-testing.md`, “Allocation margin and retained evidence (#2018)”: #2027 sweep miss, relationship to #2026, established residual cause or evidence of resolution, and counted builder/dispatcher results. Preserve historical misses and distinguish regular UI gate from in-depth selection. Documentation records evidence and does not obtain device runs.

## Security review

**Verdict:** PASS

- [Trust boundaries] `readDecodedThread` retains complete row/metadata/proof validation. No persisted input becomes trusted merely to accelerate a test; any repair must retain rejection coverage.
- [Tokens] Synthetic fixture only; no credential generation, storage or lookup. No live connection or account is needed.
- [Files/storage] `withFixture` uses app-private no-backup storage and hashed host/conversation paths. Existing atomic writes and unencrypted conversation-cache policy stay unchanged; evidence contains synthetic content only.
- [Android attack surface] No component, intent, provider, permission or WebView change. The existing screen renders inert text; frame measurement observes it only.
- [Cryptography] No Noise, key, nonce or hash algorithm changes. Canonical persisted proofs remain unchanged.
- [Network/I/O] Offline opens make no request; connected newest responses remain held. No transport, URL or limit changes.
- [Errors/logs] Retain content-free timing/count logs; never add message text, decrypted bytes, cursors, identifiers, tokens or raw error payloads.
- [Concurrency] ViewModel store teardown and observer disposal remain mandatory; no new work escapes its owning scope. Device coordination is reused without changing pipeline tools.
- [Threat model] Relay delay and hostile daemon input defenses remain with existing transport/cache validation owners. Rooted token theft and screenshot/accessibility leakage are unchanged; this synthetic-fixture verification does not handle secrets or expand their exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10

## Revisions

### 2026-10-10 — Residual reproduced; retain every case and worker lifecycle

On plan revision `383323251`, isolated first draw exited 1 with 1/0/1/0/0 executed/passed/failed/skipped/errors (XML timestamp 2026-10-10T05:56:50). Fragmented offline first-open cumulative phases were 711/997/1135/1264 ms; cache phases 1/342/33/35/216 ms, IO worker 631 ms wall / 328 ms CPU. Separate control exited 0, 1/1/0/0/0 (2026-10-10T05:57:11), at 3018/3142/3153/3261 ms. Evidence is retained under `/tmp/builder-2027/baseline-isolated/` and `baseline-control/`, alongside copied post-#2026 misses. Animations were disabled, so their setting does not explain this reproduction. The in-depth comparison is running against this unchanged APK.

The method currently aborts on its first timing miss, hiding later case timings. Collect each probe and assert the unchanged bound across all eight after fixture cleanup; no case is retried and every miss fails the method. Add content-free IO/default-worker queue/wall/CPU and ViewModel installation/teardown events to distinguish current measured work from prior-opening cleanup. Capture the probe before each cache read so a late completion cannot mark another opening's restore time. This is diagnostic instrumentation, not an established production repair, and adds no exported type or timing exclusion. Existing isolated failure is the red regression. Security review remains PASS: logs use only fixture labels and durations, ownership and validation remain unchanged. Estimated total remains below 450 lines.

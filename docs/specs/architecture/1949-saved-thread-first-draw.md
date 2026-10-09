# #1949 — Saved thread first draw

## Files read

- `docs/knowledge/features/thread-screen-how-it-works-state.md`: complete content and read evidence remain together through worker preparation and Android frame pacing (#1968).
- `docs/knowledge/features/caching-conversation-repository.md`: restore uses worker scheduling (#1966) and collection-owned deferred writes (#1967).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `receivedThread`, `threadItems`, `threadContent`, `historySeed`; saved rows and newest requests have independent lifetimes.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeThreadSnapshot`, `readHistoryPosition`; both currently decode saved rows and coverage.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `readThread`, `readHistoryPosition`, `readThreadRecord`; real disk restore validates optional coverage without making row readability depend on it.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryDisplayProjection.kt`: `projectDisplay` retains exact gap anchors.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFragmentedHistoryDeviceTest.kt`: existing sparse responsiveness and reader-pull proof.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/FragmentedHistoryFixture.kt`: durable fragmented fixture with 18,000 spans.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryProjectionWorkerTest.kt`: controlled projection, cancellation and marker contracts.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFramePacingDeviceTest.kt`: real worker/frame scheduling setup.

## Design source

N/A: processing latency only. The ticket explicitly preserves shipped appearance, loading treatment, styling and navigation; no visual design changes.

## Context

The merged prerequisites remove main-thread processing and synchronous persistence from the snapshot path, but do not prove the first drawn row within 1,000 ms. This ticket measures the remaining path before choosing a repair. It adds no cache schema or retention change. No decision record is needed. No overlapping in-flight branches touched the candidate files at planning time.

## Design

Add a managed-device regression alongside the existing fragmented-history check. Generate and persist two fixtures before timing: 20 ordinary saved rows, and 18,000 displayed rows with 36,000 durable entries / 18,000 spans. Each case creates a fresh `FileConversationCache` and `CachingConversationRepository`, and opens a newly created `ThreadViewModel` using production `ThreadContentScheduling` and `ThreadScreen`. Reopening creates a new ViewModel and composition against the same repository. Exercise offline and a connected delegate whose newest response remains held.

Start a monotonic wall-clock timer before repository/cache construction and ViewModel construction. A test-owned draw modifier around the production screen calls `drawContent` and records the first frame with the exact newest message's placed semantics inside the message viewport. It must observe real row text and bounds, never empty-state or loading semantics. Record cumulative restore, snapshot, complete-state and draw times with fixture sizes. Test polling/idleness only waits for recorded evidence; it cannot set the timestamp.

A negative control inserts 3,000 ms into the cache read in this same timed path and verifies the identical 1,000 ms assertion rejects it. Controlled tests hold newest-page completion and prove the saved rows, marker anchors and ask counts independently. Existing reader-only paging coverage remains in the focused checks. If the baseline meets the bound, ship tests only. If it fails, use the phase measurements to remove redundant open-path work locally, revise this plan with the measured bottleneck and exact replacement contract before implementation.

## State and concurrency model

Production scheduling remains owned by `viewModelScope`; cache IO uses its injected IO dispatcher, snapshot and display preparation use the existing workers, and publication uses the Android UI frame clock. Test collectors and held request jobs are cancelled when the ViewModel store clears. Draw probes and timing are test-only and reset for each opening. Offline cannot dial any host; the connected fixture has no network credentials and a deliberately held history response.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Fresh cache/repository restore, ordinary and fragmented | `savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen` |
| Leave and reopen the same conversation | Same device method; fresh ViewModel/composition and reset draw probe |
| Newest response held while saved rows arrive | Same device method and controlled `savedRowsDoNotWaitForNewestResponse` |
| Artificially slow cache restore | `slowRestore_negativeControlRejectsTheSameFirstDrawBound` |
| Marker projection and reader pulls | Existing `sparseFragmentedRestore_opensEditsAndScrolls_onePagePerPull` and `ThreadHistoryProjectionWorkerTest` |
| Reconnect and peer turn integration | Existing live `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`; fresh dispatcher execution pending |

## Error handling

The regression preserves cache read fallback and existing repository error classification. A missed draw deadline fails with fixture counts and cumulative phase times, never message content, cursor, credentials or cache paths. Cancellation ends a held request normally.

## Testing strategy

Write and run the timing/held-response tests before any production repair. Device-only reason: real app-private disk IO, worker/main scheduling, actual Android frame drawing and monotonic elapsed time cannot be proven by Robolectric's virtual clock. Run the new device class through the focused Android 13 managed-device task and inspect fresh XML counts. Run controlled saved-row tests and existing projection/paging tests, lint, assemble, Android-test compilation, formatting and the final pre-verify gate. Retain the existing rung-3 offline-read scenario and list it in the PR for dispatcher live acceptance. Controlled device fixtures are the deterministic latency proof; the live scenario is integration coverage.

## Open Questions

- Does the merged baseline meet the 1,000 ms bound? Resolve with real device phase measurements and record any design change under Revisions.

## Security review

**Verdict:** PASS

- Trust boundaries: fixtures cross the existing `FileConversationCache` validation boundary and use typed `ThreadItem` values. No parser or daemon text rendering contract changes.
- Tokens/secrets: the delegate cannot connect; no pairing material, tokens or credential lookup is needed.
- Files/storage: fixtures live in a unique test directory under `noBackupFilesDir`, retain hashed host/conversation filenames and atomic production writes, and are removed only by the test that owns them. Existing content-at-rest policy remains unchanged.
- Android surface: no exported component, intent, provider, push or WebView changes. The test wraps the existing screen in instrumentation only.
- Cryptography: Noise, TLS and Keystore are untouched; fixture identifiers are not security randomness.
- Network/IO: held newest completion uses a cancellable deferred; no network endpoint or retry policy changes. Disk failure still follows production classified fallback.
- Logs/telemetry: record only static case names, sizes and elapsed times. Never record row text, opaque cursors, identifiers, secrets or decrypted bytes.
- Concurrency: ViewModel stores and composition own all collection/worker lifetime; draw probes reset before each open and held requests cancel on exit. Avoid caching decoded snapshots across unrelated writes or destinations without explicit invalidation.
- Threat model: relay delays must not withhold saved rows; held-response tests exercise that property. Hostile input still uses production validation. Rooted disk theft and UI screenshot/accessibility exposure remain the existing product threat model, with no added exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-09

### Decode reuse follow-up review

PASS: the new `DecodedThread` retains one app-private document's text, rows and validated history under `FileConversationCache.mutex`. Path plus exact newly read bytes establish reuse; missing/changed documents and mutations retire the value, and unreadable documents withhold both rows and position. It adds no credential, Android surface, crypto, network, telemetry or coroutine boundary. Optional metadata remains independently classified and rows remain independently validated; direct content proofs still run even when no legacy aliases exist. Host isolation, same-size/same-time replacement, malformed metadata, unreadable replacement and removal have explicit regression controls. Rooted storage access and screenshot exposure remain the existing product threat model.

## Revisions

### 2026-10-09 — Baseline latency and draw evidence

Ordinary first/reopens measured 132–190 ms. The full fragmented fixture failed to draw within 15 seconds: restoration/coverage work had not delivered a snapshot, while the device logged allocation-blocking GC. `historyIdentity` and `cachedThreadRowProof` format each SHA-256 digest byte through `String.format`, multiplying allocations over repeated coverage validation and restore reads. Replace only these digest-to-hex encodings with Kotlin's existing `ByteArray.toHexString` pattern, preserving every input byte, full SHA-256 digest and lowercase encoding. Add compatibility assertions against the previous formatter and persisted row representation. Re-measure before deciding whether any further repair is needed. This is two production files, no new exported declaration and no consumer update.

The test-owned parent draw modifier did not observe updates in child render layers. Replace it with a root `OnDrawListener` that checks exact newest text, placement and viewport bounds and registers an Android frame-commit callback for that draw. Only the committed frame records the timestamp; test idleness and StateFlow emission never establish draw evidence. The 3-second negative control measured 3,395 ms and the identical bound assertion rejected it.

### 2026-10-09 — Reuse one validated decode across restore readers

The hash-only repair measured 2,954 ms: first row read returned at 1,379 ms, repository snapshot at 2,795 ms, complete content at 2,888 ms and committed newest-row frame at 2,954 ms. Both `historySeed` and snapshot restoration read rows and coverage separately. Preserve those consumers and their independent jobs, but let `FileConversationCache` retain **one** validated decoded thread (rows plus retained history position) under its existing mutex. Every read still reads the exact current document bytes from disk; reuse requires the same document path and byte-identical text, never timestamps or file length. A changed document, missing file, host switch or any mutation retires the decoded value. Keep one entry, so this adds no unbounded cache and changes no durable retention policy or schema. Separate FileConversationCache instances still observe one another's writes because reads always inspect bytes.

Decode domain rows and validate their identities once, then decode optional metadata independently and retain claims against those rows once. Malformed optional metadata still leaves rows readable; unreadable rows still withhold both rows and position. Keep the writer's existing decoding behavior. Skip legacy-binding work only when `legacyKeys` is empty: there are then no aliases to prove; direct retained-row proofs remain mandatory. Add tests for reuse, same-length external replacement, host isolation, malformed metadata and removal. New state belongs only to the existing cache instance, guarded by its mutex; no scope or job is added. No signatures or exported types change. Final forecast remains under 900 lines total, two production files, zero new exported declarations, zero simultaneous consumer updates and fewer than ten branches.

Security review supplement: reuse compares actual bytes after a real disk read, so permission failure, file removal, malformed replacements and same-size/same-time replacement cannot borrow a prior reading. Mutations clear the retained decoded content before running. No decoded content is logged, persisted elsewhere or shared across host paths. Tests cover the confused-host and stale-read cases. Verdict remains PASS.

### 2026-10-09 — Batch-local digest allocation

Decode reuse measured 925 ms offline first open and 487 ms offline reopen, but fresh online restore still drew at 1,071 ms (rows 658 ms; snapshot 858 ms; content 989 ms). Retained direct proofs create a JCA `MessageDigest` separately for every identity and row. `historyRowProofs` will instead own one local digest for its synchronous batch and pass it to a new internal overload of `cachedThreadRowProof`; the original one-argument function stays available unchanged. `MessageDigest.digest` resets between inputs, preserving independent full SHA-256 proofs. No digest escapes the batch, crosses a suspension point or is shared across threads. Add a multi-row parity assertion and keep the 1,000 ms bound unchanged. This adds one internal overload, updates one consumer, and keeps the two-production-file scope and line forecast within the sizing table. Security review remains PASS: inputs, algorithm and proof encoding are unchanged, and mutable digest state is confined to one synchronous invocation.

The final focused Android 13 run executed all three selected tests without failures or skips. Ordinary cases drew in 122–201 ms; full fragmented cases drew in 814–906 ms on fresh instances and 525–547 ms on reopening. The deliberately delayed path drew at 3,220 ms and failed the same latency assertion as expected. Exact per-case cumulative restore/snapshot/content/drawn timings are recorded in the PR and device logcat evidence. Full live integration remains the dispatcher's gate.

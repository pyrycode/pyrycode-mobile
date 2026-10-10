# #2070 — Diagnose and repair the scripted reply checkpoint

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadReadEvidence.kt`: `received`, `presentedAs`, `checkpoint` bind exact versions and enforce receipt barriers.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `recordReadEnvelope`, `observeSnapshot`, `mergeHistoryPage` supply contextual evidence and presented rows.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `reduceOrderedHistoryPage` distinguishes nonvisual facts and changed row claims.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound` records evidence after the live fold.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` selects the displayed reply, then proves foreground and peer reads.
- `app/src/test/java/de/pyryco/mobile/data/repository/ThreadReadClaimsTest.kt`: exact-version, malformed-entry, numeric-gap and finalized-turn controls.
- `docs/knowledge/features/remote-conversation-repository.md`: **Daemon conversation read marks**; runtime receipts account for IDs without granting sight, and daemon confirmation can clamp the request.
- `docs/specs/architecture/1989-runtime-read-receipts.md`: shipped runtime-receipt and clamp controls must remain intact.
- Sibling `pyrycode/docs/protocol-mobile.md`: **Marking a conversation read**, envelope durable identities and **Security model** are the wire authority.
- `docs/e2e-interactive-stream.md` and builder `device-tests.md`: deterministic twin and counted device evidence requirements.

## Design source

N/A: evidence and test observation only. Preserve the existing visual design, layout and navigation.

## Context

On main `427d0594e`, the displayed scripted reply has no checkpoint despite three version bindings and zero malformed/unidentified counts. That summary does not identify the reply's binding or numeric holes. Diagnose the actual projection before changing receipt or observation behavior. No decision record is needed.

## Design

Retain the ordered `scripted ping` selection and all existing assertions, deadlines and coverage checks. First capture its unchanged baseline. Add content-free test diagnostics for the selected reply's binding, claim IDs, receipt IDs/classifications, holes, stored gaps and connection changes. These distinguish missing receipt identity/continuity from a lost exact version and a transient collector observation. Do not log row content, payloads, routing identities, credentials or keys.

Once the causal transition is observed, reproduce it synchronously through the real projection/reducer in a controlled unit regression before repairing the smallest Mobile-owned fold or observation seam. Record the concrete diagnosis and repair contract under Revisions before implementation. An external defect instead gets its owning blocker; never bypass the barrier to accommodate it. No exported type, signature migration, new dependency or broad reducer refactor is planned.

Sizing: one deliverable, four criteria, forecast at most 650 written lines including plan, probes, diagnostics and evidence; zero exported production types or consumer migrations; existing reject branches preserved. Recount after diagnosis. `origin/feature/1766` overlaps the deterministic test file only in its separate multi-delta method; keep this ticket's edits local. No confirmed dependency.

## State and concurrency model

The connection-scoped repository collector and atomic projection retain ownership. No new production scope or flow is planned. Read evidence is ephemeral and distinct from qualified sight. Tests use `runTest` and test-owned cancellation. The existing foreground viewport and correlated confirmation remain authoritative.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Diagnosed receipt/version transition, completed reply, delayed observation | New controlled `scriptedReplyRetainsItsExactCheckpoint` regression plus the unchanged ordered ping method |
| Unaccounted receipt and malformed/unknown receipt | New negative control beside the causal regression; existing `knownMalformedEntryAndReceivedGapBlockEvenLaterVisibleContent` and runtime receipt controls |
| Held newer unseen version, replay and overlapping history | New causal probes plus `oldPresentedStreamingVersionDoesNotAcknowledgeLaterUnseenDeltas`, `overlapOrderAndRepeatedPagesPreserveExactClaims` and runtime receipt probes |
| Fresh connection, reused conversation on another host, receipt without sight | Existing runtime receipt, `ConversationReadMarksTest` and `ReadCheckpointRetriesTest` controls |
| Foreground reply then list-only unseen post then peer confirmation | Full unchanged ping method; no phone reopen after the post |

## Error handling

Unknown/malformed entries, missing live identities, numeric holes and unresolved stored gaps remain barriers. No new UI error or result type. A failed checkpoint reports static diagnostic categories and numeric evidence only; sending or fetching cannot establish sight.

## Testing strategy

Run `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted ping` with the installed SDK, unchanged ordered pair on managed Pixel 2 API 33 ATD. Device execution is necessary for authenticated daemon/relay delivery, real foreground presentation and peer attention synchronization. It uses zero Claude turns and preserves the existing rung-3 proofs without changes.

Observe a controlled causal test fail before repair, then run it and the invariant probes green together with `ThreadReadClaimsTest`, `ThreadReadRuntimeReceiptsTest`, `ConversationReadMarksTest`, `ReadCheckpointRetriesTest` and `ThreadReadSubscriptionTest`. Retain fresh counted XML, invocation, app/daemon/relay revisions and artifact paths for baseline and repaired pair. Report the #2069 durable-gap result independently. Dispatcher owns scripted-all, including explicit ping executed/passed/failed/skipped counts; no full-suite pass is claimed while #2069 fails.

Run focused tests, lint, debug assembly, Android test compilation if its source changes, Spotless apply and forced check. Commit/push, merge main once, then final assembly and `scripts/pre-verify.py --gradle` before PR creation.

## Open Questions

- Which exact reply version and received ID sequence produces the null checkpoint? Resolve from original paired execution and contextual projection evidence.
- Is the causal repair Mobile-owned? External ownership requires a linked blocker instead of a workaround.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/remote-conversation-repository.md`, **Daemon conversation read marks** — record the established causal transition and retained barriers.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, **Verification status** — retain counted ordered-pair evidence and separate #2069 outcome; add dispatcher scripted-all ping outcome when available.

## Security review

**Verdict:** PASS (investigation contract; re-review the concrete repair in Revisions).

- [Trust boundaries] `recordReadEnvelope` and strict history decoding remain the receipt boundary. MUST preserve exact-version claims and every unknown/malformed/missing-identity/numeric/stored-gap barrier; negative controls are required before handoff.
- [Tokens] No credential access, generation or storage change. Test diagnostics must never include pairing records or tokens.
- [Files and storage] Only synthetic regression data and counted evidence at fixed test paths; no production persistence or backup change. Receipt claims and sight remain outside cache.
- [Android attack surface] No component, intent, permission, push, WebView or navigation change.
- [Cryptography] Noise authentication, nonce ownership and Keystore wrapping are unchanged.
- [Network and I/O] No new wire shape, runtime allowlist, timeout or retry. Only existing isolated test binaries and managed device are used.
- [Errors and logs] SHOULD FIX: replace the inconclusive failure summary with content-free receipt/version diagnostics. No message text, payload, identifier or decrypted bytes may enter diagnostics.
- [Concurrency] Keep contextual reduction and atomic state ownership. A collector subscription, reconnect or fetched page cannot invent sight; controlled probes distinguish state loss from scheduling.
- [Threat model] Relay omission/delay/replay remains conservative through receipt and exact-version barriers. Hostile daemon frames remain strictly decoded. Token theft and screenshot/accessibility/keyboard leakage surfaces are unchanged; no new surface is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

## Revisions

### 2026-10-11 — Exact original daemon proves external ownership

The configured sibling daemon is `a536d17b1e18`, older than the supplied failing `b75aea7948b3`. The original ordered pair against the configured checkout passed both methods (2 executed/passed, zero failed/errors/skips). That is a control, not repaired acceptance: it lacks the queued-send facts introduced later by daemon #2972.

Fetch and archive the exact original daemon revision into an isolated `/tmp/builder-2070/daemon-original` source snapshot, without changing the live sibling checkout. Run the same ordered pair against it, with only content-free checkpoint observation added to the test. Both methods fail (2 executed, 0 passed, 2 failed, zero errors/skips). The reply is settled with one delta, bound to claims 9 and 10. Two observations ten seconds apart show received IDs 2,3,4,5,6,7,9,10,11,12, the hole (7,9), zero unknowns, unidentified entries and stored gaps. It is not a lost version or a transient collector emission.

Sanitized raw history establishes ID 8 as `send_delivered`, inserted between the user message at 7 and assistant delta at 9. `queuedSendHistory.delivered` appends it; `legacyHistoryType` and `legacyRuntimeFact` exclude it, so neither live publication nor `newHistoryPager` accounts for its identity. `send_accepted` at ID 1 is also excluded, but the internal ID 8 hole alone explains this checkpoint rejection. Existing open [daemon #3167](https://github.com/pyrycode/pyrycode/issues/3167) owns this precise defect; reuse it and link it natively as this issue's blocker. No Mobile workaround or circular #2069 dependency is appropriate.

Retain `ScriptedPingReadEvidenceTest` as two active controlled probes: repeated projection observation cannot repair the hole despite an exact settled binding; only an understood synthetic receipt can close it, while unknown/malformed entries and an unseen content version remain blocked across duplicate/overlapping receipt. These certify the required Mobile barrier, not a shipped daemon repair. The pre-repair red proof is the exact original device pair. On resume, add the repaired daemon's real receipt fixture and perform the controlled repair comparison and original ordered pair before opening a PR.

Evidence lives under `app/src/androidTest/assets/read-2070/`: counted XML for both method names and both daemon controls, sanitized reply evidence, raw ID/type/length metadata and `invocations.json` containing commands, counts, commits, hashes and original artifact paths. The original archive has no Git metadata, so its gate revision prints unavailable; the manifest records the verified archive source revision and binary hash explicitly. #2069 still owns the final durable-gap acceptance. Its original failure is reported independently; the older-daemon passing pair does not resolve it.

Security re-review: PASS. No production change, new receipt classifier or allowance, wire shape, new sight, persistence, transport, credential or lifecycle change. Diagnostics contain only static categories, booleans, counts and durable numbers. Synthetic positive receipt accounting cannot bind an unseen version. All real assertions, deadlines and ordered selection stay intact. Forecast now below 450 written lines including probes and retained evidence; zero new exported production declarations or migrations.

This is a recovery branch pending daemon #3167, not a completed repair. Normal builder completion checks, repaired original pair, dispatcher scripted-all and documentation remain pending after the blocker closes.

Recovery validation: the two new probes and existing read-claims, runtime-receipt, marks, retries and subscription classes executed 52 tests, all passed with zero failures/errors/skips. `assembleDebug`, `compileDebugAndroidTestKotlin` and forced `spotlessCheck` passed. Retain the focused unit command and counted XML with the paired evidence. Lint and final post-merge pre-verification remain for the successful resumed handoff.

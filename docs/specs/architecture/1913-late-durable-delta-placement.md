# Late durable delta placement (#1913)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `mergeRows`, `deltaRows`, `withJoinedSegments`, `mergeUnsignedHistoryRows` and cache/signed entry points define identity, insertion and held-content ownership.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `mergeHistoryPage`, `ProjectionState`, `observeSnapshot` and `remove` own conversation-local atomic placement evidence.
- `app/src/test/java/de/pyryco/mobile/data/repository/UnsignedHistoryTest.kt`: retained regression and scope fixtures exercise exact unsigned positions and projection replacement.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryReconciliationTest.kt`: page-cut permutations, held separators, cache/reconnect and settled-turn contracts.
- `app/src/test/java/de/pyryco/mobile/data/repository/ThreadProjectionTest.kt`: first modern history delivery must keep pending-own-echo placement and held metadata.
- `app/src/test/java/de/pyryco/mobile/data/repository/UnsignedHistoryCacheTest.kt`: host-keyed cache restore and reconnect keep received ordering independent of live identity.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`: current held-position limitation, atomic snapshots and distinct own-echo exception.
- `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`: atomization must preserve held text, separators and ended-turn settlement.
- `docs/specs/architecture/1786-history-page-reconciliation.md`: indexed insertion and legacy/collision behavior remain intact.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Joining a page to the live stream and Security model distinguish scoped durable identities from replay counters.

## Context

An assistant delta can be held without a durable placement claim. Reverse-arriving pages can strand it after a user separator even once its earlier durable position arrives. Repair this single reconciliation behavior without UI, wire or persistence changes. No decision record is needed.

The sketch and written plan forecast about 500 written lines including tests, deleted/replaced lines and this plan, zero new exported types, one production caller updated and three acceptance criteria. Existing signed/cache entry points keep their contracts. This fits every sizing limit. Codegraph reports no callers for the extension; repository search confirms the projection caller and one test caller. Remote feature-branch inspection found no overlapping in-flight branches in the two production files.

## Design

Pass newly received placement identities separately from the accumulated unsigned ordering map into `mergeUnsignedHistoryRows`. Derive them inside `ThreadProjection.mergeHistoryPage` by subtracting this conversation's prior claims from the page's authoritative order. Existing claims win conflicting later values.

After atomizing held and incoming segments, identify held assistant atoms with first durable evidence on this page. Remove only those atoms from the anchored base and reinsert their held versions at the incoming delta's durable slot. Preserve the held atom's text, timestamp, attribution and streaming state, even when the incoming twin differs. A delta inside a folded segment can thus split away without losing adjacent held atoms. Previously durable atoms and live-only atoms without new evidence remain anchors in their existing relative order. Ordinary rows remain anchored; pending own user echoes retain their separate existing first-delivery exception.

Use exact `ULong` bounds for repaired atoms; durable bounds take precedence over provisional sequence neighbours or shared-page neighbours. Sequence identifies a delta and permits adjacent same-turn rejoining, but does not invent durable placement. Preserve indexed slot insertion, legacy whole-turn handling, renderer-key repair and lifecycle hint handling. Rejoin only adjacent increasing same-turn pieces, never across a visible separator. Cache and signed merges supply no relocation eligibility.

## State and concurrency model

No new state type, flow, scope, dispatcher or job. Compute first-evidence eligibility, merge rows and store accumulated claims in the existing `state.update` lambda, so a CAS retry recalculates against the latest generation. Publish rows and unsigned positions together. Retain ended-turn recording and the post-merge settle pass. Evidence remains conversation-local in the host-owned projection and is cleared by `remove` or projection replacement; live replay alone creates no claim.

## Error handling

No new rejection branch or I/O boundary. Existing page reduction isolates malformed entries and negotiated capability gates. Conflicting later claims leave prior values and established held positions intact. Empty/duplicate pages are idempotent. Reuse existing content-free history request diagnostics; the pure reducer emits no payload or message logs.

## Testing strategy

Enable and extend `UnsignedHistoryTest.lateDurableEvidence_doesNotStrandLiveDeltaBeyondItsHistorySeparator` first; observe the expected assertion failure before implementation. Cover ids 1–4 and the four boundary-spanning unsigned ids, and assert the intermediate state immediately after seq 1 receives its durable claim.

Add small invariant probes using the existing unsigned fixtures: every contiguous page cut and arrival permutation, prefix/middle/suffix overlaps, held folded segments, different incoming text, settled turns, duplicate/empty pages and conflicting later claims. After each merge assert unique delta/separator identity, held text/state, prior durable relative order, fixed old claims and the relative order of unevidenced live-only atoms. Check final convergence after all relevant claims and exact replay idempotence. Extend existing scope fixtures for conversations, host-owned projection replacement, live replay and durable reload.

Run focused `testDebugUnitTest` for `UnsignedHistoryTest`, `HistoryReconciliationTest`, `HistoryPageReducerTest`, `ThreadProjectionTest`, assistant segment/attribution tests and cache/reconnect tests. Read fresh JUnit XML counts and confirm the retained regression executed and passed. Run lint, assembleDebug, Spotless apply/check, then merge main, commit and push before the full unit suite, assembleDebug and `scripts/pre-verify.py --gradle`. This data-only behavior needs no new Compose/device/live scenario; dispatcher owns its later deterministic gates.

## Open Questions

None. If probes expose a conflict between provisional sequence neighbours and durable bounds, durable ordering controls the newly evidenced atom; retained anchors do not move.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, under “History pages fold into the same thread (#645)”, replacing the #1913 limitation and blanket held-position wording with the first-durable-evidence exception. Distinguish it from pending-own-echo placement and preserve the durable/replay identity distinction.

## Security review

**Verdict:** PASS

- [Trust boundaries] First evidence must come from `reduceOrderedHistoryPage.unsignedOrder`, never timestamps, renderer keys or replay event ids. `mergeHistoryPage` subtracts prior scoped claims; conflicting claims cannot grant a second relocation.
- [Tokens, secrets and credentials] No credential handling is added; reducer/projection operations only use decoded thread rows and numeric positions. Existing Keystore and revocation paths remain untouched.
- [Files and storage] No file paths, disk writes or persistence schema changes; relocation permission is in-memory in the host-owned conversation projection. Existing app-private cache ownership remains intact.
- [Android attack surface] No components, intents, providers, deep links or WebViews change; daemon text uses the existing inert renderer.
- [Cryptography] No primitive, key, nonce or handshake changes; existing Noise authentication protects the plaintext boundary.
- [Network and I/O] No socket, cap, TLS, timeout or reconnect changes. Hostile late numeric claims cannot overwrite accumulated values or move already durable atoms.
- [Errors, logs and telemetry] No payloads, message text, decrypted bytes, identities or credentials are newly logged. Existing history request diagnostics cover the operation.
- [Concurrency] Eligibility must be derived inside the same CAS update as rows and claims; deriving it outside would let concurrent pages authorize a second repair. The design enforces this and adds no coroutine or mutex.
- [Threat model] Delayed/reordered relay traffic and hostile daemon replay are probed through page permutations, duplicates and conflicting claims. Conversation/host isolation and replacement are explicit probes. Token theft and screenshot/accessibility leakage stay under the existing Keystore/render policies; this data-only repair adds no exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08

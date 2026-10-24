# Compact initial and older root pages

## Files read

- `data/network/ThreadQueryPayloads.kt`: `ThreadQueryRequest`, `decodeThreadQueryReply`, `decodeThreadSummary`; extend the existing bounded wire boundary compatibly.
- `data/network/ThreadQueryDecoder.kt`: `decode`, `assemblyMetadata`, `metadata`; preserve correlation, assembly and separate terminal ownership.
- `data/repository/AuthoritativeThreadQueries.kt`: `dispatch`, `accept`, `retire`; reuse the single serialized request lane and abandoned-request draining.
- `data/repository/AuthoritativeThreadStore.kt`: `applyBatch`, `merge`, `certifyQuery`, `AuthoritativeThreadSnapshot`; retain complete facts independently of compact metadata.
- `data/repository/ExpansionDetailValidity.kt`: `observeDetails`; source revision and subtree freshness are independent observations.
- `data/repository/RemoteConversationRepository.kt`: authenticated support and query wiring; supply opt-in test selection without changing production advertisement.
- `ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectThread`; consume current root metadata and retain deferred contents/read barriers.
- `data/cache/SavedAuthoritativeThread.kt`: `toSavedAuthoritativeThread`; only existing complete facts and certificates cross the cache seam.
- `ToolBodyQueriesTest`, `DeferredAuthoritativeScreenTest`, `deferredLargeSnapshot`: reuse production demand and generated 8333-item proof.
- `docs/knowledge/features/remote-conversation-repository.md`, `remote-conversation-repository-reads-and-thread-store.md`, `conversation-cache.md`, `authoritative-reader-paging.md`: receipt is not sight, omitted detail is not empty success, and complete coverage cannot certify a compact join.
- Wire authority: supplied Core `docs/protocol-mobile.md`, **Compact projection**, **Bounded expansion reads**, and **Security model**.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read structured context and its screenshot with the Figma skill. Preserve the dark thread, existing compact tool headlines/chevrons and expanded inline content, Material typography and scheme tokens. No component, asset or geometry changes are needed.

## Context

Root query replies currently decode only full items and certify complete coverage. This slice admits mixed compact initial/older replies while complete-host delivery remains usable. #2432 owns nonzero catch-up/live; #2433 owns advertisement, production activation and reconnect selection; #2393 owns genuine paired capture/replay. No decision record is needed.

Overlap scan found historical/shared remote edits on #1947, #2175, #2325, #2462 and #2493; #2493 adds generation capture and metadata recovery, not compact ingestion. Keep remote wiring additive and local; no design dependency.

## Design

Add defaulted projection selection to root requests only; compact pages additionally carry their certified epoch. Existing callers remain complete. Decode full non-tool roots and bounded tool/agent summaries through existing validators; reject headers on complete replies, nonroot compact rows and wrong projection on every physical or assembled reply. Only separate empty terminal markers carry coverage.

Store compact headers separately from `items`, which retains full facts for persistence. Expose one newest-revision identity through `expansionItems`, with compact tuple ordering and immutable kind/parent validation. At equal revisions current compact metadata owns freshness, while exact retained full bodies remain available only if detail validity permits. Feed source observations through `observeDetails` in both directions, including same-revision freshness changes. Preserve summary unknown/zero/false/empty fields verbatim.

Keep compact root coverage/checkpoint separate from complete coverage/checkpoint. Compact markers certify only their window; older pages require matching compact epoch/continuation, merge contiguous windows and never advance complete certificates or detail completion. Root receipt and assembly completion do not certify any window. Save only the already retained full facts and complete certificates, with no schema change.

The repository supplies an internal default-off compact-root switch gated by current authenticated thread/interactive/expansion/compact support. The serialized lane selects version-zero compact initialization and explicit older pages. Repeated synchronization in this slice uses version zero rather than implementing #2432 nonzero catch-up. No automatic older walk or closed-detail demand is added.

## State and concurrency model

No new coroutine, dispatcher or background downloader. Reuse synchronized store mutations, connection-owned query lane, current inbound collector and expiry child. Cancellation/background socket closure retires the lane and drains abandoned work through existing fences. Compact metadata and certificates live in the host/conversation runtime snapshot and reset with its generation/incarnation.

## State transitions and identity reuse

| Event | Required focused proof |
| --- | --- |
| Initial mixed/reordered/duplicate roots | Repository ordering probe, one newest row per identity, zero closed detail traffic/conversion |
| Repeated revisions, newer full facts and same-revision freshness changes | Retention probe preserves full source/cache facts while invalidating obsolete detail independently |
| Saved full facts arrive after compact metadata; repeated restore during explicit refresh | `restoreInvariantSameRevisionSavedBodiesMustMatchCurrentCompactFreshnessBeforeOpening` and `restoreInvariantCompleteHostKeepsSavedFullBodiesWithoutCompactFreshness` |
| Saved complete certificate arrives after compact certification | `restoreInvariantCompleteCertificateSurvivesCompactFirstThroughCacheWithoutBecomingActive` |
| Explicit older demand, tied boundary, terminal empty page | Paging probe checks exclusive continuation, merged root coverage and bounded request count |
| Wrong conversation/request/projection/epoch | Decoder/repository attribution probes leave certificates unchanged |
| Interrupted oversized non-tool assembly, failure/cancel/disconnect | Fragment and retirement probes leave root/detail certificates unchanged |
| Reset or replacement owner with colliding IDs | Existing generation/connection fences plus focused compact owner probe |
| Recollection/closed rows and explicit expansion | Mounted generated fixture has no unopened detail transfer/decode/conversion; existing body/child and sight tests retain expansion coverage |

## Error handling

Reuse static malformed/attribution/coverage/capacity/sequence/application/timeout/cancelled/disconnected classification and retry/drain behavior. Invalid data certifies nothing. No raw daemon text, identities, cursors or payloads enter logs; existing inline retry/offline styling remains.

## Testing strategy

Write causal red repository tests before production changes, then add invariant probes and a mounted production-delivery method using `deferredLargeSnapshot`. Run affected decoder/repository/detail/presentation classes, the affected shared method on Android, and scripted `tool` on Linux. Device execution is required by the ticket; no new device-only source is planned. Retain original XML, logs, candidate/source/binary identity and executed/passed/failed/skipped counts outside the worktree. Run lint, assembly, instrumented compilation, formatting and final pre-verify after merging main. Dispatcher owns complete configured acceptance; #2393 owns paired evidence.

The former numeric forecast is obsolete planning metadata. Preserve behavior and acceptance through the saved-detail validity repair and the bounded Core agent-summary contract followed by its Mobile consumer. The existing ticket/PR remains the integration owner; #2432, #2433 and #2393 keep their separate scopes.

## Open Questions

None. Scope excludes compact nonzero/live/reconnect activation, local-history storage work and new background detail fetching.

## Documentation handoff

Pending documentation stage, supplied to #2393: compact initial/older selection, tuple merge, full-fact retention and independent root/item/body/child/fragment completion for `docs/knowledge/features/thread-screen.md`, `docs/knowledge/features/remote-conversation-repository.md` and `docs/knowledge/features/conversation-cache.md`. Supply counted focused/native/scripted outcomes, candidate/source/binary revisions and raw-evidence locations for `docs/e2e-interactive-stream.md`. Documentation cannot replace missing evidence.

## Security review

**Verdict:** PASS

- Trust boundary: `decodeThreadQueryReply` and `ThreadQueryDecoder` enforce projection, root shape, bounded summary text and request/epoch attribution before store admission. MUST FIX addressed in design: a complete marker or assembled payload cannot certify compact scope.
- Tokens: no new credentials; exact unsigned query tokens retain the existing numeric decoder and are never logged.
- Files/storage: existing cache seam persists full app-private facts only; compact summaries/certificates and expansion buffers remain runtime state. No new path, schema, backup policy or write mechanism.
- Restore freshness: MUST FIX from review. Newly admitted saved full facts must reconcile against current compact observations before body availability or read sight. Equal source revision does not establish equal subtree freshness; unknown or different freshness keeps the saved fact but invalidates body/child knowledge and requires explicit-open refresh. Saved-body fallback always requires matching known freshness, including after separately fetched detail is evicted. Disk admission cannot replace the current compact observation or its certificate. Compatible saved complete certificates belong only in the retained complete fields after compact selection.
- Android surface: no component, intent, provider, WebView or external action changes; summary text uses existing inert row presentation.
- Cryptography: reuse authenticated Noise session and existing vendored primitives unchanged.
- Network/I/O: preserve envelope/assembly bounds, timeout and serialized admission; reject compact data before full conversion. Relay delay/reorder cannot manufacture a matching successful marker.
- Errors/logs: reuse static content-free codes; never log payloads, decrypted bodies, headlines, request scope or credentials.
- Concurrency: existing synchronized host/conversation generation/incarnation fences, collector cancellation and abandoned-request draining own all work. No additional jobs or locks.
- Certificate ownership: MUST FIX addressed in the final design. `certifyQuery`, `older` and `dispatch` bind compact root coverage to the current query connection; a replacement cannot reuse or union the old lane's certificate before successful initialization. Child/restore merges also validate immutable identities against retained compact roots.
- Threat model: malicious relay remains bounded by existing Noise/framing/timeout controls; hostile daemon summaries are validated and inert. Rooted-device theft and UI screenshot/accessibility leakage are unchanged existing platform concerns, outside this root-page slice.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-24 (restore boundary re-review)

## Revisions

2026-10-23, source inspection before implementation: existing read/attention consumers use the current snapshot checkpoint and coverage. Give that active certificate an explicit projection, retaining the complete checkpoint/coverage separately when compact selection starts; `toSavedAuthoritativeThread` uses only the retained complete certificate. This implements separate root/complete certification without migrating read consumers or extending persistence. `expansionItems` supplies tuple ordering to existing presentation; no UI source edit is needed. The authenticated lane's default-false compact supplier is wired from granted support, which production cannot grant until #2433 changes advertisement. No additional repository constructor flag is required. Revised forecast remains below 1500 written lines across six production files.

2026-10-23, causal repository probe: six executed, five passed, one failed because `observeDetails` ignored a higher source revision in a second batch at the same captured query version. Admit higher revisions at equal capture versions, while retaining replay suppression for equal/older revisions. Prefer newer held full facts over an older stored observation when comparing a later header, including cache restoration between batches. Keep these regressions with compact ingestion; this adds a small seventh production edit and stays within the admitted line boundary. Removal clears the retained complete certificate as well as the active one, preserving durable retirement fences.

2026-10-23, ownership review: compact root certificates also carry the query lane's connection identity. Replacement lanes cannot demand older pages from another connection's certificate or union its window before their own successful version-zero initialization. This is incoming-page ownership, leaving activation and reconnect resume selection to #2433. The replacement-connection probe covers the fence. Child-reader and restore identity checks include held compact roots, so omitted full facts cannot bypass immutable kind/parent checks.

2026-10-23, final activation-boundary review supersedes the earlier flag decision: `NoiseIkSession.readResp` retains the ack's capability set directly. Add an internal default-false `suppliedCompactRootPages` constructor option as well as the authenticated capability gates. Only the focused supplied fixture opts in; even an unexpected compact grant cannot change production's default complete selection before #2433. The production-default regression exercises the omitted constructor option. No handshake or advertisement changes are included.

2026-10-23, focused native run: 1 executed, 1 failed at the positive open-demand control after all closed-page assertions passed. The assertion ran before Compose delivered the click's recomposition/effect. Wait for Compose idle after the explicit open before driving the test scheduler and asserting the request. Preserve every negative counter and the exact positive count; no production or assertion relaxation is involved.

2026-10-24, verifier findings 1 and 3: add reverse-arrival cache regressions for compact-first/saved-full-second, including same-revision freshness mismatch, explicit body demand, read-sight rejection, exact saved fact retention and unchanged compact certificates. Reconcile only newly admitted saved facts through detail validity, preserving current compact observations. Retain a compatible saved complete checkpoint/coverage separately when compact coverage is active. Repair forecast adds about 160 written lines to the measured 1215-line existing diff, below 1600; no new exported declaration or consumer migration is planned. The in-flight #2493 store edit adds a generation-capture helper away from restore; no dependency or overlapping block change is required.

2026-10-24, verifier finding 2 remains a contract question: the supplied Core protocol's Compact projection and Bounded expansion reads omit `task_id`/`task_link` from `ThreadSummary`, while `recordedTask` requires both for the task roster and background placement. A cold compact join has no full facts from which to retain them. Do not invent a task identity from a root ID or fetch closed content. Refinement must define the producer-owned summary task identity/placement contract and its Core prerequisite, or explicitly define another supported identity model for these consumers. Preserve the existing 8333-item proof and its historical failures; it does not prove the missing navigation/lifecycle behavior.

2026-10-24, retention probe for finding 1: 13 executed, 12 passed, 1 failed after a successful explicit refresh followed by dropping fetched body contents using the existing eviction state shape. Refresh clears body invalidation, so restore-time reconciliation alone allowed stale saved fallback to regain availability. Require known matching subtree freshness whenever `hasToolBody` falls back from a summary to saved full facts. Reuse the same parsed freshness fact in admission and availability; complete full-item delivery still needs no compact freshness token. The probe remains in the reverse-arrival regression; no retention service or schema changes are needed.

## Manual cache-validity follow-up — 2026-10-24

The stopped Linux candidate and its descendant regression are preserved. Fresh macOS author execution reproduced that exact regression as 1 executed/1 failed, and a separate query-custody control failed when stale disk arrival advanced the current body-query serial. These are author checks, not Linux or independent acceptance.

Keep stale saved-body revision evidence separate from source invalidation and fetched detail. On restore, walk the admitted compact ancestry once: an older or freshness-incompatible full ancestor cannot certify its retained full descendants. Persist the full facts unchanged. Availability may use a separately admitted exact body, while evicting that body must not revive the stale saved fallback. Restore does not replace current compact observations, alter root/complete certificates, cancel a valid body/child query or invalidate its independently fetched completion. Current full-source replacement may clear only its own stale saved-body evidence.

This state is runtime-only inside detail validity; no cache schema, migration, capabilities, background demand, wire contract, network route, lifecycle ownership, credentials or diagnostics change. Exact generation/incarnation/epoch, request revision/serial and terminal-marker checks remain in the existing store/query lane. Text and bodies stay on existing bounded inert rendering paths. Security assessment for this follow-up is author-only; independent review remains required.

Focused tests retain same-revision and reverse-arrival coverage and add older-parent descendants plus stale restore during/after body and child queries. Full configured/Linux, mounted/native/tool and final integration acceptance are still pending. The missing Core task-summary producer/contract and Mobile navigation/lifecycle consumer remain unresolved, so this cache repair alone does not complete #2431 or authorize normal documentation.

## Accepted task-association consumer — 2026-10-24

Core #3851 independently accepted producer `ea1dcb1fce648fc80b2cf8962f15a718477e2fa8`, completed its documentation, and merged PR #3853 as `6de0b4282b5735b5188a7c492683fefae08fa116`. The protocol's Bounded expansion reads section now supplies optional exact `task_id` on compact root and direct-child summaries. Nonempty selects that task, explicit empty clears it, and omission makes the current association unknown. Preserve whitespace and Unicode exactly. Existing row identity, session, parent/order, activity/status and ending order own navigation, placement and lifecycle; association establishes no detail availability or completion.

Consume that scalar only from validated summaries. Reuse the existing complete-content association path for complete rows and the existing pure projection for task counts, launch markers and background placement. Neither older retained full facts nor fetched bodies may fill an unknown/cleared summary association. No inventory, guessed task identity, body hydration, request, capability, default activation, cache schema, coroutine, storage or logging is added. Both compact roots and child replies share the existing summary decoder.

Test first through the production compact repository boundary: cold initial and older joins, exact opaque identities, running/terminal lifecycle and marker identity, nonempty/empty/omitted replacements over saved full facts, rejected malformed scalar kinds, and direct-child summary delivery after explicit opening. Retain zero unopened body/child requests and full conversions. Extend the mounted 8333-item proof with a real summary association and its navigation marker without relaxing the original zero-detail assertions. Focused author checks, Linux/native/tool proof and independent configured acceptance remain distinct.

Security review for this consumer (author, before implementation): PASS. The existing authenticated query and row-owner fences remain the admission boundary. A summary task identity is opaque inert data, validated as a JSON string under existing envelope bounds, never normalized, evaluated, logged or used to grant content/certificate authority. Invalid kinds reject the whole reply. Summary presence cannot borrow older content. No new network, filesystem, credential, Android component, mutable shared state or persistent field is introduced. Independent source/security review is still required.

## Consumer author proof — 2026-10-24

Consumer source `27e2d4cc596d68e305a4336ddd40c3551e803550` passed131 fresh affected tests on macOS and131 on Linux across the decoder, root repository, task projection and mounted screen classes, with zero failures/errors/skips. Four new association/validation methods failed causally before the repair and passed afterward. The generated8333 mounted proof now carries the exact producer association and exercises Go to agent while retaining zero unopened detail requests, bodies/child pages and full conversions across initial/older pages.

The required Linux managed Android method and scripted `tool` scenario each executed1/passed1 with zero failures/errors/skips. Runtime Core source/embedded binary was `5ba6b8dacc0916533df8b8d9c60090190a9e6f4d`, actual binary SHA256 `e3b9beb7b2817b15bf400029686ec79b134f7324fbe3248be20da53cab1729a1`; Relay `9939bc1ff8afec5021c09862e6f14dadba33ad42`, SHA256 `27ec11505393dd62fad8c9a2cb672775b230e031b7240ac9c11fddc847324d10`. Both report clean embedded VCS identity. The accepted compact producer contract is the separate Core3851/PR3853 source evidence above; this tool run exercises existing complete delivery and does not prove compact runtime activation. Built APK hashes are retained without installed-byte readback.

All prior attempts remain at their own identities. The first association fixture had three Android logging setup errors plus one validation failure; after correcting setup all four failed at intended assertions. An invalid mounted-test import then stopped expanded checks at compilation on both systems, with zero tests executed. An early Linux summary mistakenly counted stale05:56 XMLs; the preserved explicit correction records zero fresh execution for that compile attempt. A missing agents path also stopped the first native setup before execution. Corrected attempts passed as counted above; no original failure becomes a pass.

Private evidence remains in the board-watch workspace under `work/2431-consumer-*`, including raw XML, logs, source snapshots and binary metadata. Linux native/tool21-entry manifest SHA256 is `33ed0e445b5ccfc5684b7693206063fd1eb5f2b643d838ef8d9bf64fbad3505c`, with separately retained actual-binary metadata. Final pre-verification, independent source/security and complete configured acceptance, then actual normal documentation remain distinct handoff requirements. The original stopped Fractal builder and dirty descendant probe are preserved.

## Confirmed sibling repair plan — 2026-10-24

Normal independent review at `df3a8fed61108d3f4725df22330077ce890a5b3f` confirmed the prior three findings fixed, then identified two remaining cases. Preserve that FAIL and all original evidence. Exact-ticket manual custody was reacquired at revision85 after the normal verifier finished; no current ticket worker, queue entry or checkout user remains. The old dirty predecessor archive remains untouched.

First add causal repository regressions for a saved full child whose saved subset omits its ancestor, followed by the first compact ancestor. Retain the full child, reject stale saved-body read qualification, demand refresh only on explicit opening, and prevent eviction from reviving the saved body. Keep independently admitted fetched body/child work and both compact and complete certificates intact. Extend first-known ancestry reconciliation only as needed; complete-host behavior stays unchanged.

Add direct-child regressions for current summary metadata competing with an equal-revision saved full child in both arrival orders. Exercise exact nonempty, explicitly empty and omitted task associations, plus matching parent freshness and mismatching child freshness. A current validated summary must win structural/task presentation at equal revision while the full fact remains saved separately. An older summary must not replace a newer full source revision. Assert task roster, launch/navigation and lifecycle projection, explicit-open/read qualification, query custody, zero unopened body demand and unchanged root/complete certificates. Reuse the existing repository fixture and projection rather than introducing a new association path.

The intended implementation is confined to existing expansion winner selection and detail-validity reconciliation, with focused tests and this plan. No wire, storage schema, logging, background demand, production activation, coroutine or UI layout change is needed. Preserve current Core association semantics and existing ownership/revision/retirement fences. Write failing tests first, retain their raw counts, then run affected Linux shared/repository tests, the required mounted Android method and scripted tool through normal admission. Reuse unchanged historical proof only at its original source identity. Final integration/pre-verification and normal independent acceptance/documentation remain required.

Security review for this repair design (author, before test/implementation): PASS. The two MUST FIX cases cross retained-disk/current-network authority: saved facts cannot establish current detail or override current summary association. The design keeps authenticated request ownership, exact opaque task identity, source revision, freshness and completion certificates separate. New negative probes cover missing ancestry and equal revisions without fetching closed content or exposing payloads. No credential, filesystem path, component, logging or protocol surface is added. Independent source/security review still owns acceptance.

The first current-main affected run executed176 tests:175 passed and the existing `placementInvariantCertifiedPageCannotInventOrderForRetainedFullFact` failed. Equal-revision child metadata must not invent placement for a full fact that has no order. Refine the design before further implementation: retain the full fact's typed order independently when adapting current equal-revision child-summary metadata. Keep the exact summary payload and current association/freshness, with full facts still saved separately; no absent/cleared association is borrowed. The existing DTO adapter can carry this bounded optional-field presentation choice without a wire/schema change. Preserve the existing zero extra row and zero full-conversion assertions. Replace only the expansion-object identity assertion, whose representation changes, with explicit retained full-object identity, absent-order, current-summary association and roster assertions. Retain the176-test failure and run this strengthened placement regression causally before the refinement. Security review (author, prospective): PASS; placement authority and current association/freshness remain distinct, with all request/revision/parent/kind fences unchanged.

Implementation self-review found that reconciling every retained full fact after every compact batch could re-mark a separately replaced current full descendant stale when an unrelated older root arrives. Add an interleaved older-page regression after current full-source replacement. Scope new-ancestry reconciliation to the first admitted observation of the missing summary ancestor, and walk only its descendants. Reuse the same observation seam for root and child summaries, preserving fetched query custody and existing stale evidence. Ordinary later compact pages must not re-invalidate an unrelated current source. This is a refinement of the same source-validity requirement, not a new feature or relaxation; retain its causal result and previous failures. Author prospective security review remains PASS under these ownership constraints.


## Confirmed sibling author results — 2026-10-24

Final tested app/test source `a487ffb5a38fdcbef5bc98df95f0955dcb139c27` passed246 fresh tests across fourteen affected Linux repository, projection, screen and decoder classes, with0 failures/errors/skips. The corrected missing-ancestor and three equal-revision task-association regressions first failed4/4 before repair, then passed4/4. Initial rejected child fixtures and diagnostic attempts remain setup failures, not causal product proof. The first176-test affected run retained175 passes and1 real placement failure. Strengthening that existing regression reproduced it before the placement refinement. A further causal older-page interleaving failed before first-known-ancestor scoping; the five selected methods then passed5/5. All raw attempts remain retained.

Required managed Android `generated8333SourceDeliversOnlyClosedRootsAcrossInitialAndOlderPages` and scripted `interactiveTurn_seededChannel_toolStepRunsThenCompletes` each executed1/passed1, with0 failures/errors/skips. The native/tool20-entry manifest is `34fc6cea5f55358e7866b9ec3059be898a27e43830c1a9716343aba06d4f9e12`. Actual clean embedded Core source `5ba6b8dacc0916533df8b8d9c60090190a9e6f4d`/binarySHA `e3b9beb7b2817b15bf400029686ec79b134f7324fbe3248be20da53cab1729a1` and Relay `9939bc1ff8afec5021c09862e6f14dadba33ad42`/binarySHA `27ec11505393dd62fad8c9a2cb672775b230e031b7240ac9c11fddc847324d10` are separate from the accepted compact producer contract. Built APK hashes are retained without installed-byte readback. The tool checks complete delivery; no runtime compact activation or extra per-process cleanup is claimed.

The first final pre-verification stopped because main advanced; retain that failed integration guard. Ordinary merge `16d47f85ef459920aef35387c0a6897f4eb2b9f7` adds main `ff5c2ba36` (#2327 documentation/evidence), leaving tested app/test/build inputs identical. Required pre-verification with `--gradle` then passed current-main integration, Spotless and all Kotlin compilation. Final result-only documentation and posted-body checks do not claim another fresh test execution.

Private raw XML/logs, causal/setup failures, source commits and manifests are retained in the Linux isolated `manual-2431-followup-evidence` and the watch's follow-up handoff bundle. The original stopped Fractal archive and earlier159-entry author bundle remain untouched. Prior normal df3 configured counts are5581 JVM/250 UI passed and scripted24/20/4 with4 independently accepted inherited failures; earlier watch22/2 counted scenario groups and is superseded. Those previous gates do not accept this new repair. Normal independent source/security and configured acceptance, followed by actual documentation, remain required.

## Third independent review repair plan (2026-10-24)

Normal review at `e27a187d23957aa7b46e845482fe973113154c8f` confirms all previous findings fixed and identifies two further authority cases. Requirements remain relevant: complete prose must retain exact read qualification, while a successful current child requery cannot revive stale saved detail. No acceptance or security constraint is removed. Keep #2432/#2433/#2393 ownership and original failed evidence.

Before production edits, add causal repository regressions for complete prose with an equal-revision child summary without optional freshness, its current full redelivery, and complete-host delivery. Separately cover failed continuation followed by explicit restart with changed or omitted child freshness, late saved-full restore, explicit body refresh and eviction. Assert current metadata, stale/read rejection, preserved full facts and separate certificates.

The minimal repair preserves current summary metadata and existing body invalidation/stale-full fences while recognizing independently available complete non-tool contents. Equal-revision child requery retains structural summary rules but replaces its optional subtree freshness from the newly authenticated page, including omission. No protocol, storage schema, background demand, logging, timeout or ownership change. Run causal reds before edits, affected Linux classes, required existing generated navigation/native and scripted tool controls, formatting and final current-main preverify. Normal independent acceptance and documentation remain required.

Security review: prior PASS is conditional on the repaired boundaries, not a verdict for these changes. Retained presentation is not current detail authority; complete prose cannot borrow stale tool/agent freshness, and evicted fetched detail cannot revive a stale saved fallback. Recheck these exact cases and record actual red/green outcomes and all failures before handoff.

# #2367 — Remove legacy runtime conversation content

## Files read

- `data/repository/RemoteConversationRepository.kt`: `onInbound`, `routeInbound`, authoritative queries and pending-send registration separate supplied facts from raw reconstruction.
- `data/repository/ThreadProjection.kt`, `HistoryDisplayProjection.kt`, `QueueProjection.kt`, `BackgroundTaskProjection.kt`: obsolete row reduction, settlement and inferred ordering.
- `data/repository/CachingConversationRepository.kt`: `ThreadWriter`, `persistAuthoritative`, restore and retirement fencing share a file with the retired row observer.
- `data/repository/ConversationRepository.kt`, `StableConversationRepository.kt`, `ThreadSnapshotSource.kt`: legacy content and snapshot API surface.
- `data/repository/FakeConversationRepository.kt`: demo content and raw history implementations and their retained presentation consumers.
- `data/repository/MessageCommands.kt`, `ConversationCommands.kt`: pending sends and controls must lose their dependency on raw projection, without losing command delivery.
- `data/repository/ReadCheckpointRetries.kt`: retain source-qualified authoritative retries, remove unowned legacy qualification.
- `data/repository/RelayRepositoryCoordinator.kt`, `data/network/NoiseIkSession.kt`: host-bound authoritative stores and replay advertisement wiring.
- `ui/conversations/thread/ThreadViewModel.kt`, `ThreadHistoryRows.kt`, `ThreadHistoryDemand.kt`: negotiated presentation and obsolete paging/folding branches.
- `di/AppModule.kt`, `di/HostConversationSource.kt`: destination ownership, notification enrichment and content scheduling consumers.
- `androidTest/.../e2e/DurableGapProof.kt`: authoritative saved baseline and real query recovery already exist alongside the retired proof.
- `RemoteConversationRepositoryAuthoritativeTest`, `AuthoritativeThreadQueriesTest`, `NegotiatedThreadViewModelTest`: retained route, identity and recovery probes.
- `docs/specs/architecture/2366-authoritative-shared-fixtures.md` and merged prerequisite `3363ad514`: explicit producer provenance replaces content-driven live-state inference; do not revive it.
- `docs/knowledge/INDEX.md` and feature topics `remote-conversation-repository-reads-and-thread-store`, `remote-conversation-repository-live-stream-and-modals`, `remote-conversation-repository-control-sends`, `caching-conversation-repository`, `thread-screen-oldest-end-history-demand`: distinguish historical compatibility descriptions from current source-owned authoritative behavior.
- Core `docs/protocol-mobile.md` and ADR 042 are the wire and ownership contracts; the owner's immediate-removal decision supersedes the compatibility interval.

## Design source

No visual redesign. Remove an inactive production content route while retaining the existing authoritative rendering, navigation, gestures and controls. No Figma change is needed.

## Context

Required-thread production activation is merged, and #2366 has now migrated the shared supported fixtures. Retaining the alternate raw-message runtime makes obsolete frames and unbound test entry points able to reconstruct content. Remove this feature completely, retaining dormant storage definitions only where #2364 still requires them. Frozen history and prior evidence remain untouched. No new decision record is needed.

One behavior ships: authoritative content is the only runtime source. Decoder removal without its sole consumers and mixed proofs would not be independently useful. The ticket is a grandchild and already carries `needs-human:sizing`. The forecast exceeds 1600 written lines and ten consumers; preserve the verification floor and continue under the split-depth exception. No new exported types or rejection branches are planned. Four acceptance criteria remain intact.

Overlapping branches: #1947, #2175, #2266, #2325, #2334, #2338 and #2344. Their authoritative paging, restart, allowance and control work is separate from the obsolete branches being removed. Keep shared edits local and preserve supported changes during integration; none is a required blocker.

## Design

`RemoteConversationRepository` retains its single inbound collector and authoritative query/update admission. Remove raw message/chunk/history decoding, content event reduction, replay/resync/backfill and their exclusive DTOs. Preserve session-qualified transient state, modals, prompts, attachments, settings and controls. Obsolete frames fall through without updating content, previews or read proof and without sending legacy requests. Required capability failure remains the coordinator's daemon update-required verdict.

Delete `ThreadProjection`, runtime history merge/order inference and their sole consumers. Remove raw content/snapshot/history APIs from repository facades rather than retaining empty-success implementations. Keep storage-required serialized definitions detached until #2364. Remove raw queue/roster settlement and minted-id ledgers. Sends register local pending identity with the authoritative store; an ack alone never accepts a user item. Queue/task actions use supplied authoritative targets.

Strip legacy row persistence from `CachingConversationRepository` while preserving authoritative restore, upsert coalescing, retirement flushing, generation checks, indexed opening and older demand. No runtime observer reads or writes legacy rows or coverage. Keep attachment retrieval and deletion behavior.

The ViewModel keeps negotiated presentation, reveal/follow-newest, grouping, pinning, status and editing. Delete raw snapshot subscription, `ThreadFold` intake, gap/coverage walks and legacy checkpoint paths. Retain useful literal presentation inputs in tests without invoking removed production reduction. Demo/test doubles that still own supported content must supply the authoritative route or literal presentation inputs, never recreate the deleted fallback.

Delete exclusive tests, fixture publishers and skipped legacy cases. Preserve all supported assertions in mixed helpers. `DurableGapProof` becomes authoritative-only, retaining saved certification, recovery receipts, reset/page checks and exact rendered order. Both deterministic and live callers stay enabled.

## State and concurrency model

No new scope or mutex. The connection collector, query expiry and fragment expiry remain connection-owned and cancel together. The authoritative store remains host-owned across reconnect. Cache writes retain collection ownership and existing non-cancellable retirement cleanup; deleting legacy branches must not change lock ordering. ViewModel jobs stay in `viewModelScope`. `ReadCheckpointRetries` retains owner-qualified pending work and its application cancellation path.

Stop-refusal correlations expire after 30 seconds through jobs in the existing connection scope. Replacement, refusal, send failure and connection end cancel and remove the corresponding jobs. An old expiry cannot remove a newer attempt's request identity.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Raw obsolete frames on bound or unbound connections | `RemoteConversationRepositoryAuthoritativeTest` rejects content mutation and legacy requests. |
| Duplicate/revised items, different arrival order, overlapping pages | `AuthoritativeThreadStoreTest` and `AuthoritativeThreadQueriesTest` preserve supplied identity/order and both held sides. |
| Disconnect/reconnect with held newer facts | `AuthoritativeThreadQueriesTest` and `NegotiatedThreadViewModelTest` retain checkpoint and exact source ownership. |
| Reset with reused epoch, late fragments or replies | Existing reset-generation probes remain green; cache retirement tests retain durable fences. |
| Same conversation on different hosts or replacement source | Negotiated route and read-retry ownership probes retain isolation. |
| Local send, ack, then matching daemon item | Authoritative pending-send tests retain device/message matching and feedback until acceptance. |
| Older demand, reopen, repeated collection and cancellation | Authoritative cache/window tests retain supplied boundaries, older facts and observation cleanup. |
| Offline durable gap and daemon restart | Retain deterministic durable-gap method and live offline/reconnect caller, with authoritative baseline, real query receipts and rendered order. |
| Successful stops for distinct targets, expiry, same-target replacement and stale refusal | `RemoteConversationRepositoryStopTaskTest.successfulDistinctTargetsExpireWithoutDisconnectAndRejectStaleRefusals` and `expiredOlderAttemptCannotRetireReplacementForTheSameTask`, with the retained cancellation/disconnect probes. |

## Error handling

Keep authoritative decoder/store outcomes, retirement rejection, control failures and read retries unchanged. Unsupported hosts retain update-required treatment. Deleted runtime APIs have no empty answer or fallback. Logs remain content-free static outcomes and counts; never emit message text, payloads, keys, credentials or decrypted bytes.

## Testing strategy

Add obsolete-frame regression first and observe the expected failure. Run affected unit/shared-screen classes individually through `scripts/gradle-gate.py testDebugUnitTest --tests`. Retain invariant probes for ownership, duplicate/order convergence, reset/reconnect fences and pending sends. Delete dedicated legacy algorithm checks rather than weakening supported assertions.

Run the focused deterministic durable-gap method, relevant recorded client scenario(s) and one relevant scripted reconnect scenario through the managed Android gate. Device execution is required for actual storage, transport recovery and native rendered-order evidence. Preserve counted XML, raw evidence and exact Mobile/daemon revisions. If deleting the legacy branch changes the authoritative live observation, obtain the actual named live offline/reconnect run before verifier PASS; otherwise preserve existing live evidence for unchanged assertions. No new restart acceptance is added. Complete configured gates remain dispatcher-owned.

Run lint, APK assembly, instrumented compilation, Spotless apply/check, merge main, commit/push, then final assembly and `scripts/pre-verify.py --gradle`. This dispatch runs on macOS; another operating system is outside this ticket's verification scope under the current policy.

## Documentation handoff

Pending documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`, supported route/store sections: replace obsolete runtime compatibility descriptions.
- `docs/knowledge/features/remote-conversation-repository-live-stream-and-modals.md`, live stream and replay sections: explain authoritative content versus retained session-owned transient state.
- `docs/knowledge/features/remote-conversation-repository-control-sends.md`, send/queue/task sections: retain controls and pending-send acceptance while removing legacy settlement instructions.
- `docs/knowledge/features/caching-conversation-repository.md`, observer/restore/write sections: document authoritative-only runtime persistence and the storage-only retirement handoff to #2364.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`, paging/gap sections and affected linked feature topics: remove legacy reader instructions, preserve authoritative local/server demand.
- `docs/e2e-interactive-stream.md` and `docs/recorded-tests.md`, affected scenario inventories: record authoritative-only helper coverage and any inventory changes. Preserve frozen archives, past specs and historical evidence.

## Open Questions

None requiring a product decision. Record implementation changes to this contract under Revisions.

## Security review

**Verdict:** PASS

- [Trust boundaries] Preserve `AuthoritativeThreadRoute`, query decoder and `SessionLiveAdmission` as explicit admission boundaries. Obsolete content frames must be inert even without a host; test that path directly.
- [Tokens] No credential generation/storage changes. Never log pairing records or send payloads.
- [Files and storage] Preserve app-private authoritative cache atomicity and retirement fences. Dormant legacy storage definitions remain for #2364, with no runtime reader/writer.
- [Android attack surface] No exported component, intent, provider, push or WebView changes; daemon text continues through existing text presentation.
- [Cryptography] Keep `Noise_IK_25519_ChaChaPoly_BLAKE2s`, per-connection keys/nonces and vendored implementation unchanged. Removing replay advertisement changes no crypto state.
- [Network and I/O] Retain transport caps, timeouts, query deadlines and reconnect backoff. Missing thread capability remains update-required; no raw fallback may bypass it.
- [Errors and logs] Preserve static diagnostics and bounded authoritative decoding; no raw frame, prose, token or key logging.
- [Concurrency] Preserve captured generation and exact owner checks in cache/query/read paths, including cancellation and reused-epoch fencing. Remove unowned legacy retries while retaining authoritative attempts.
- [Threat model] Relay delay/reorder/flood remains bounded by existing encrypted transport and query assembly. Hostile daemon content still passes strict authoritative decoding. Rooted-device token theft and UI screenshot/accessibility leakage retain existing Keystore/platform posture; this removal adds no exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-21

## Revisions

### 2026-10-21 — Verifier removal-migration repair

- Repair the five verifier findings as one incomplete producer/consumer migration, retaining every existing scene, viewport and recovery assertion. The split-depth exception and verification floor still apply.
- Select queued evidence from current-owner authoritative `user_message` facts with `queued` status, binding text and device/message identity to the same fact. Delivered evidence records cannot prove queue state.
- Supply literal rows to viewport fixtures and explicit Agent-child presentation ownership for reopened storage rows. The existing dormant-storage round-trip assertion remains until #2364. Neither fixture may reintroduce runtime reconstruction.
- Design overrides subscribe to authored task facts. Capture translations preserve tool status, numeric parent links, result detail and supported live progress, unrecognized fields and canonical refusal identity. Capped/unreported panel-only presentation states remain explicit component scenes where authoritative content has no such metadata.
- Delete the unused lifecycle fold and exclusive started/updated/roster production DTOs and codec tests. Independent-peer diagnostic DTOs belong in shared test sources; supported progress decoding and dormant storage definitions remain.
- Bound stop refusal correlations by connection-owned timeout jobs, cancelled on replacement, refusal, send failure and disconnect. Tests must prove distinct-target cleanup and stale-refusal rejection without raw roster callbacks or authoritative content mutation.
- Security review addendum: timeout jobs inherit the connection scope, carry only local correlation identity, and never log daemon text. No new wire admission, storage or cryptographic behavior is introduced. Existing accepted live offline/reconnect observations remain unchanged; focused scripted, capture and unit evidence is required before handoff.
- Managed admission does not catalog the old manual `stream-queued-echo` scenario. Retain the ordinary stream method and execute the repaired queued-prompt method alongside it in `scripted stream`, using the existing background-agent runner pattern with independent phones, daemons and release fences. Preserve named XML counts for both methods; focused runner tests reject missing/skipped outcomes, retain earlier failures and preserve the inherited admission descriptor.
- The affected capture run executed seven methods, passing six; `rowAndNoticeFramesAt412By892` exposed the historical truncation flag that authoritative presentation does not carry. Preserve that exact scene with literal rows, then reopen the subscribed screen for its existing outcome/control assertions. Capped/unreported panel metadata is likewise a literal component scene. Supported task counts and compact task rows still come from authored facts and session-owned progress.

### 2026-10-21 — Runtime removal and evidence migration

- Keep ThreadItem.mergeIdentity beside the dormant storage proof definitions until #2364; no runtime content observer reads legacy cached rows.
- Independent producer-observation DTOs move to the shared test source set. Production retains authoritative content and supported transient/control decoders.
- Preserve generic viewport scrolling/composition assertions with literal rows; retire legacy receipt assertions and their native overrides. The database-backed authoritative first-draw proof remains.
- Peer history/attachment assertions now consume ThreadItemDto instead of raw history. Their retained raw-producer peer still needs an explicit authoritative query connection strategy and strict recorded/native verification before handoff. It must not silently query an unnegotiated peer or weaken watermark/attachment assertions.
- Remaining removal audit: eliminate obsolete UI history-key/gap compatibility consumers and stale exclusive fixture helpers while retaining authoritative/presentation assertions. Complete affected class checks, deterministic durable-gap/scripted and recorded checks, final merge, final pre-verify and PR before claiming completion.
- The focused scripted run on 2870f1185 passed ping but skipped the durable-gap method because its stale Ignore still described a legacy-only cache proof. Remove that annotation now that the shared proof uses authoritative saved certification and recovery receipts. Retain the skipped result as history and require fresh counted execution before acceptance.


### 2026-10-21 — Continuation audit

- Main integration preserves the reviewed restart evidence from #2246.
- The independent producer peer keeps its original diagnostic history requests in test sources. Its exact watermark and attachment assertions retain the captured boundary; those observations never populate or certify phone content. Runtime history DTOs and domain page APIs are deleted.
- Remove raw completion readers, legacy unread tokens and their storage collectors. Recovery notices and session cost use the existing authoritative effects and certified facts. Missing shown metadata cannot fall back to legacy receipt.
- Remove gap markers and demand plumbing because there is no authoritative production producer. Retain oldest-end paging, gesture, retry and scroll coverage.
- Remove queue matching and background placement inference from the app APK. Literal presentation fixtures prepare rows in shared test sources, while runtime rows come from authoritative presentation. Keep actual grouping, navigation, row identity and viewport assertions.
- The demo repository also supplies complete authored facts through a demo-owned negotiated route; it does not consume raw content frames. Existing demo content/chip tests caught the missing route and remain in the verification set.
- Retire the native history-tail capture that drove deleted raw-history requests. Shared presentation checks retain loading, retry, unavailable and offline rows; documentation stage must update that capture inventory.

### 2026-10-21 — Final integration and focused device evidence

- Main `40c6341c0` is merged, preserving #2373 navigation readiness alongside #2246 restart evidence. Implementation candidate is `a32966895985c909b50c9900861be4abc36319ce`.
- Resolve the earlier peer-query question by retaining the independent producer's original diagnostic raw-history boundary in test sources. Phone content and certification remain authoritative; original watermark, attachment-byte and exact-order assertions stay intact. Genuine recorded fixtures and compatibility expectations are unchanged.
- The final scripted ping run executed and passed both selected methods, including `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_durableGapCatchUp`: 2 executed, 2 passed, 0 failed/skipped. Daemon source/binary revision is `fbd5f2c9cd99c2c5864f4772c4e85f74d7a8dbfe`.
- Recorded offline/reconnect and phone-attachment scenarios executed and passed: 2 executed, 2 passed, 0 failed/skipped. These are replay evidence, not a fresh real-Claude observation. The authoritative branch and named live offline/reconnect caller retain their original recovery/rendered-order observations; verifier must assess retained live evidence for those unchanged assertions.
- Preserve raw XML, phone recovery/rendered-order logs, historical failures/skips and checksums privately under the agents repository's `logs/evidence/2367/builder-a3296689/`; daemon/relay diagnostics remain under `logs/evidence/2367/harness/fault-55577a8db2c428691324be469004470c/`. Commit content-free counts and evidence hashes with the implementation handoff. Complete configured gates remain dispatcher-owned.
- The final host-attention class exposed a fixture still negotiating only interactive support and omitting authoritative shown metadata on peer read updates. Supply thread support and explicit shown facts on those updates, retaining every unread/clamped-read/late-list assertion. This changes test evidence only; runtime and recorded/live observation are unchanged.

### 2026-10-21, manual continuation

- Juhana requested direct manual ownership through Documentation. The automatic builder was stopped with its source preserved as 39716cb01. A separate manual worktree continues the same feature branch. No refused foreign-ticket comment is retried.
- Review confirmed all five removal findings and the stop-correlation repair. Final formatting was corrected. The last unused production roster constant was removed. Current main d2130e229 is merged, retaining the independently accepted earlier-session Agent work.
- Fresh manual checks passed all 210 Python tests, 24 focused JVM/shared-screen methods, nine affected Android capture/subscription methods and both scripted stream methods. No selected method was skipped. The queued-message method binds text and phone device/message identity to one admitted current-owner fact. The formerly failing row-and-notice capture now passes with its original assertions.
- Content-free source identities, named methods, counts and XML hashes are in `2367-manual-validation.json`. Private raw reports and historical failed results are retained at the evidence root named there. The original evidence file remains attributed to its original revisions.
- Documentation must also describe the 30 second connection-owned stop-refusal correlation lifetime and the two independently counted stream methods. Existing genuine offline/reconnect observation and strict recording comparisons remain unchanged. Complete configured independent verification remains necessary before Documentation.
- Integrated assembly and lint pass. Final pre-verification confirms merged main, clean formatting, the retained security review and compilation of every Kotlin source set. This is the builder handoff, not an accepted verifier result.

### 2026-10-21, supported prompt preview coverage

- Independent source review identified two supported prompt-preview tests deleted with the obsolete history-preview class. Their production path remains in `HostConversationSource`.
- Restore the original permission title/text, first-question wire order, blank prompt, blank first question and other-modal fallback assertions in the retained host-attention test class. Retain the original privacy assertions. Do not restore history APIs or alter production behavior.
- Remove the unused denial, queue and notice reconstruction helpers from shared producer diagnostics. Retain the DTOs used by independent peers and the used session-boundary mapper. Remove the exclusive unused compaction DTO and private mapper helpers.
- Run the focused host-attention class through normal test admission. Keep the in-flight verifier candidate unchanged until its verdict is recorded. Independent verification remains required for the resulting test-only repair.

### 2026-10-21, complete unit gate follow-through

- The first complete unit run executed 5326 methods and failed 62. Preserve its raw report and the same-candidate rerun. The verifier is comparing the failing selection against main.
- Group the remaining migration work by retained presentation scenes, session-owned connection fixtures, and supported notification/diagnostic assertions. Supply prepared presentation rows and authoritative session facts where removed inputs previously supplied them. Retire only assertions dedicated to removed behavior. Preserve viewport, ownership, privacy, cancellation and supported control assertions.
- Use focused classes to validate each observed failure group. Do not publish over the in-flight verifier candidate. After its result, publish the checked manual repair and require fresh independent acceptance of the new candidate.

### 2026-10-21, rejected candidate and retained mixed proofs

- The independent verifier rejected 7b839c6a5. All 62 failed unit methods failed again on that candidate and passed on main. These are PR regressions. Full phone and scripted verification did not follow the red JVM gate. Preserve all three reports without relabelling the failures.
- Repair the remaining mixed fixtures with authored presentation rows and current producing-session envelopes. Restore the two supported permission/question preview tests and remove only unused shared-test reconstruction mappers. Retain DTOs used by the independent producer peer.
- Session replacement in the retained protocol is a producing-session clear snapshot or authoritative conversation summary. The removed raw transition and raw turn-end events cannot establish ownership or refresh readings. Current turn state explicitly reports idle. Cross-session tests retain predecessor rejection, current-source acceptance, request-id conflicts, reconnect isolation and current-settings fallback. Remembered data carries its actual producer. A timestamp or correlated request does not turn predecessor data into current data.
- Read cancellation uses the supplied shown version, never the removed raw latest-entry watermark. Preserve outstanding prompts, new-prompt delivery and host isolation.
- Obsolete raw queue and message pushes cannot manufacture queued, delivered or dropped diagnostic settlement. Retain send/ack/failure/privacy assertions and the actual drop command target. Documentation must remove old raw-derived diagnostic settlement claims.
- Removing an authoritative screen owner leaves obsolete literal task controls inert. Current recorded-target controls, one-shot delivery, owner transfer and reentry assertions remain.
- Group A freshly executed and passed 192 selected JVM/shared-screen methods without skips. Group B and complete independent verification remain pending. No accepted verifier result or Documentation transition is claimed.

### 2026-10-21, held settings repair found by retained mixed proof

- With complete ownership metadata and proper fixture cleanup, 153 of 156 Group B methods passed. The coordinator's unchanged latest-settings retention assertion failed: accepted supplied settings were never copied into the host holder used during a disconnected gap. Preserve that assertion and write only already-admitted settings to the existing holder before publishing the live reading. This restores retained display state without restoring raw transition delivery.
- Strengthen the same real-coordinator test: the disconnected gap keeps an explicitly held reading; replacement clears both holders; a correlated reply from the retired producer repopulates neither; a valid successor reply becomes the latest held reading.
- The two remaining Agent failures were unavailable-reading fixture errors: an empty settings target was also incorrectly used as an empty producing-session tag. Keep the unavailable target empty and retain the actual successor as the producer. Preserve unknown-display and retired-confirmation assertions.
- Security review addendum, PASS: the one retained-state write runs only after authenticated current-session admission and successful decoding. It uses the existing in-memory host holder, not disk, and replacement already clears that holder. No new request, scope, coroutine, trust decision, log field or storage mechanism is introduced. The retired correlated-reply assertion protects that boundary.

- The final Group B retry freshly executed and passed all 185 methods across nine classes, including all 29 authoritative session-live ownership methods. Zero failures, errors or skips. Group A previously passed 192 methods before the subsequent session-fixture and held-settings changes. Full exact-candidate independent verification remains required. All failed, formatting-only and watchdog-aborted runs remain retained with their original outcomes.

### 2026-10-22, native consumer migration

- The second independent candidate passed all 5328 JVM methods but failed two native consumers against main. Preserve the full UI report, exact rerun, main comparison and FAIL verdict. The model-change failure is inherited; scanner and background-agent passed their same-candidate rerun. Complete scripted acceptance is still pending.
- Migrate the standalone permission method to the existing authoritative coordinator route with the actual paired host and device identity. Require genuinely negotiated thread support. Keep the same native arm/confirm separation, physical pointer safety, tool side-effect witness, displayed completed reply, idle state and strict full replay checks. Capture the current daemon exchange genuinely; preserve the old legacy asset and strict CHANGED comparison. Review every changed request and causal response before selecting a replacement. Never synthesize capabilities or supplied content.
- Expose only the replay engine's existing generated identity mapping for the recorded device identity. This does not normalize request payloads or relax causal matching. Keep changed/extra payload and ownership rejection coverage.
- The save scene must retrieve actual text bytes through the existing attachment fixture and long-press the loaded file row. Retain the production document-result path, one picker, destination bytes and confirmation geometry. Do not change production attachment timing or extend waits.
- Freshly run both named native methods. Run focused strict replay checks after shared setup changes. Then require complete independent verification, including the deterministic durable-gap method, before Documentation.
- Security review addendum: changes are test-only except removal of an unused field and stale comments. Host/device provenance comes from the real paired session. Captures stay private until secret scanning and explicit semantic review. The isolated daemon receives only configured test authentication; preserve managed device/test admission and source attribution.

### 2026-10-22, genuine native permission replacement reviewed

- The exact save scene passed once with the real loaded-row gesture, one document picker, original confirmation geometry and byte-for-byte destination verification.
- The first actual permission capture passed the prompt and confirmation but failed the displayed reply because its standalone graph still passed a bare connection repository. It remains a failure with its original source. Install the production stable wrapper with the coordinator's negotiated route. Collect the busy signal so the retained idle assertion observes real state.
- Genuine capture on 1a22302d7 then passed the exact permission method. Its fresh strict replay passed the same method. Both executed one method with no skips. The historical legacy fixture fails the explicit thread requirement, and the strict contract remains CHANGED. Preserve that overall nonzero refresh result.
- Explicit semantic review in `2367-permission-recording-review.md` accepts only the genuinely captured authoritative replacement. It checks both correlated catch-ups, canonical phone device/message identity, the exact settled assistant and matching successful terminal, genuine modal identity and remote resolution, unchanged product mutations and retained native assertions. No synthetic thread evidence or relaxed matcher is introduced. Preserve the historical asset under `recorded/retired/` and copy the accepted candidate without changing its bytes. Final configured independent acceptance remains required.

The following stopped-worker plan is preserved for audit. The manual migration, genuine recording review and final configured independent acceptance above govern the completed repair.

### 2026-10-22 — Retained native consumer migration

- The second verdict confirms all 5328 JVM methods pass but identifies two native consumers still assuming the removed graph. These remain repairs of the same fixture migration at the final split depth; no independently useful runtime slice or new production behavior is added.
- Bind `RecordedPermissionTest` to its authenticated host and exact registered device public-key identity, advertise the production capabilities and give `ThreadViewModel` the same host. Replay must bind that captured device through the existing exact identity mapping. Capture genuine thread queries, replies and session-owned permission frames; never manufacture authoritative evidence.
- Refresh `permission-allow.json` only from a committed-source genuine permission capture. Preserve modal visibility, separate arm/confirm taps, pre-confirmation absent witness, post-confirmation file effect, completed exact reply and complete strict replay. Retain old/fresh replay results and strict request-contract differences for explicit review. A changed contract stays CHANGED, not passing compatibility. Add focused managed capture using the existing host admission, Android custody and restricted-login helpers.
- `threadSavedFrameAt412By892` waits for the translated row's actual loaded attachment, then invokes that row's Save action. Keep the production document-result path, nonempty destination, exactly one picker and every confirmation geometry assertion. No production attachment or design change is required.
- Correct obsolete settlement/cache comments and remove the unused `pendingNewest` field. Run the exact failing native methods, affected recording unit tests and focused scripted ping (including counted durable-gap execution). Complete configured acceptance and independent security review remain dispatcher/verifier-owned; Linux is outside this macOS dispatch.
- Security addendum, PASS: authenticated host/device binding narrows admission. Replay identity access must name an identity present in captured frames and preserves complete payload matching. Recording stays test-only and rejects credentials; managed capture keeps the original host descriptor and device cleanup. No production trust, storage, crypto, concurrency or permission boundary changes.
- Documentation stage: update `docs/recorded-tests.md`, standalone permission route and capture/compatibility sections, to describe the supported authoritative graph and reviewed recording replacement. Preserve the historical fixture and failed results as evidence.

### 2026-10-22, newest-main suggestion fixture integration

- The verifier integrated main 4cf670c96a5b586cf03a2997c5f27ed52e855cca into published candidate 9612cbc721903a3f67204c482e2335458ce5f345. Its combined build stopped before JVM execution because the newly added `ReplySuggestionReadsTest.Source` still overrides removed `observeMessages`. This is a new integration finding, not a failed counted JVM method. Preserve the compiler report and original source.
- Bind that retained double to the existing `FixtureThread` negotiated route and supply authored canonical user facts for echo observations. Remove the obsolete override instead of restoring the removed API. Preserve every current-source replacement, retired-source rejection, exact text, duplicate echo, session and newer-clear assertion in all four methods. Use distinct canonical IDs for duplicate text.
- Run the exact affected class through normal test admission, then require complete configured independent acceptance of the integrated candidate. The genuine permission recording and its original source attribution remain unchanged. No production behavior, replay tolerance or security boundary changes.

### 2026-10-22, orphan-publisher retirement and complete snapshot fixture

- The third independent verdict accepts both native repairs and genuine recording review, but rejects the integrated compile break. It also identifies one remaining cleanup pattern: private legacy publishers whose last callers were deleted. Remove the named orphan functions and their exclusive state/imports. Verify actual references before deletion and retain every used obsolete-frame rejection probe and independent producer diagnostic. Remove the unused passive legacy-owner constructor. Correct remaining queue and attachment settlement descriptions.
- The suggestion helper proof observes complete replacement snapshots. The shared append-preserving store fixture retains an omitted duplicate, so it cannot model that proof's replacement input. Its first two focused runs each executed four methods and failed the echo completion assertion. Supply explicit complete canonical snapshots directly in this observation-only double, with the actual Source connection identity. Keep the original exact-owner assertion and all duplicate, text, session and newer-clear checks. No production store or runtime API changes are needed.
- Compile all affected source sets after unused-helper retirement. Fresh complete independent JVM, UI and scripted acceptance remains required. Keep all failed and earlier passing evidence with its actual source.

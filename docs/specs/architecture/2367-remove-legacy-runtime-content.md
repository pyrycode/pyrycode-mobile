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

# #2043: authoritative daemon item store

## Files read

- `data/network/ThreadUpdatePayloads.kt`: `ThreadItemDto` and `ThreadUpdateDto` retain exact JSON facts and patch presence.
- `data/network/ThreadUpdateDecoder.kt`: `decode`, `expire`, `discard`, `abandon` supply complete/pending/repair outcomes.
- `data/network/ThreadPayloadAssembler.kt`: assembly is connection-confined and bounded; pending data is never an item.
- `data/repository/RemoteConversationRepository.kt`: `onInbound`, `routeInbound` and the connection collector own dispatch and teardown.
- `data/repository/ThreadSnapshotSource.kt`: legacy snapshot consumers stay unchanged.
- `data/repository/ThreadProjection.kt`: existing raw rows and read evidence remain separate.
- `docs/knowledge/features/remote-conversation-repository.md` and `remote-conversation-repository-reads-and-thread-store.md`: one inbound consumer, cold observable fan-out.
- `docs/knowledge/features/conversation-repository.md` and `thread-screen.md`: renderer migration is outside this data-only change.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` and `docs/specs/architecture/2042-thread-update-decoding.md`: reuse the codec and wire its idle expiry and teardown.
- Daemon `docs/protocol-mobile.md`, supplied thread delivery contract, full items and live updates: authoritative field/application contract. Local sibling predates the section; read current GitHub main.
- Daemon ADR 042: daemon owns attribution, ordering and activity; client stores facts. No new decision record needed.

## Context

Provide the additive store consumed later by #1984 sync and #1985 rendering. Production activation and combined live proof stay with #1988; pages/DTOs stay with #2040. No UI-visible change, persistence, scheduling or raw-history conversion. No overlapping in-flight branches touch the planned production files.

## Design

Add `AuthoritativeThreadStore.kt` with an internal snapshot, operation outcome enum and store; add `AuthoritativeThreadRoute.kt` for connection-owned decoding/application. Modify only the repository dispatch/lifecycle and add a defaulted source-host argument (no caller migration). Four new top-level types, no new dependency. Forecast: about 1100 written lines, five criteria, zero simultaneous consumer changes; rejection categories remain under ten.

The store keys snapshots by host/conversation; each contains epoch, immutable complete DTO items, completed version, resumable checkpoint, generation and repair/reset flags. Ordered items sort solely by supplied nonzero order; unordered items remain unordered. All attribution, unknown fields/status/kind/content and required summaries remain verbatim. Full items replace only strictly older revisions; batch overlap preserves newer held facts and batch omission removes nothing. Batch validation/application is atomic and metadata does not certify history coverage.

Changes replace named whole values, including explicit clears. Reject immutable id/kind fields, inconsistent revision replacement, malformed known fields and invalid history numbers. Append only a string suffix to a user/assistant content object's existing string text, preserving other content fields. Base revision must match and target revision must be newer. No stale patch is an idempotent success. Validate positive item id/revision, revision at least id, version at least revision and all history numbers below 2^53; optional clear values remain represented exactly.

Live and supplied batch application may raise completed progress, never a resumable checkpoint. `establishComplete` explicitly merges supplied full facts and certifies a version at least all completed progress in the current generation, clearing repair. Repair remains sticky across subsequent valid live updates. Reset-needed clears only the affected conversation, increments generation and blocks application until `reset` explicitly installs an epoch. Every application/certification takes a captured generation so stale work, including reused epochs, cannot repopulate a reset. Reset clears checkpoint and items and starts repair-needed until complete certification.

The separate repository route requires a nonempty supplied host identity and both negotiated capabilities. It intercepts only the three new verbs before legacy unread/replay/projection side effects. Pending never mutates completed facts; failures mark repair for their owning conversation (unknown owner conservatively repairs known host snapshots). Fresh reset/repair certification discards that conversation's assembly through route operations. Production hello defaults remain unchanged.

## State and concurrency model

Store operations are synchronous and synchronized, publish one atomic StateFlow snapshot map and never suspend. Observable reads map/distinct the selected host/conversation. The route serializes decoder calls, reset and certification. An expiry child job on the repository's connection scope ticks every second, using the decoder's monotonic deadline; collector termination cancels it and abandons the decoder, including cancellation before collection starts. Background socket close follows that same termination path. No new dispatcher/I/O. Process death drops in-memory state; configuration changes/recollection only read it.

## State transitions and identity reuse

| Event | Probe/test |
| --- | --- |
| Reordered/overlapping batches, duplicate full delivery, omitted held rows, empty/single batch | `batchesPreserveNewerAndAbsentItemsAcrossEveryOverlap` |
| Equal ids on hosts/conversations | `identitiesNeverShareState` |
| Queued/lost/dropped item delivered later | `unorderedFactsStayAbsentUntilDelivery` |
| Replay append/change, missing base, older/newer base | `patchesRequireExactBaseAndNeverRepeatSuffix` |
| Missing append then valid live progress | `repairStaysStickyUntilExplicitCompleteCertification` |
| Unknown kind/status/content, explicit clears | `changesRetainPresenceAndReplaceWholeContent` |
| Unexpected epoch, stale work, repeated reset/reused epoch | `resetRejectsStaleGenerationAndIsolatesConversation` |
| Interrupted continuation, expiry while idle, close/reconnect | supplied-pump tests `continuationsNeverPublishPartialAndAbandonmentRepairs`, `idleExpiryRepairs`, `resetDiscardsPendingAssembly` |
| Recollection/configuration change | `observableSnapshotsAreAtomicAndScoped` |

## Error handling

Static outcome codes: applied, ignored, repair-needed, reset-needed and stale-generation. Invalid semantics are repair-needed with held facts/progress unchanged; unknown epoch invalidates only its key. Codec failure reasons are already static. Diagnostics use debug-only RelayLog static event/outcome codes, never host/conversation strings, payloads, content, summaries or exceptions. Snapshot string representation is redacted.

## Testing strategy

Write store invariant tests first and observe red compilation before implementation. Supplied-pump repository tests use channels and runTest/backgroundScope, test both capability gates, legacy row isolation, continuation completion/failure/expiry/reset and cancellation. Run affected existing raw-event, read-evidence and codec tests, lint, assembleDebug, Spotless, and final pre-verify compile checks. No shared/device tests or real-Claude scenario: inactive data contract only; installation/renderer/live proof remain #1988.

## Open Questions

None.

## Security review

**Verdict:** PASS

- Trust boundaries: `ThreadUpdateDecoder` admits bounded complete JSON; store validates semantic application. Unknown content is retained inert with no renderer or executable sink.
- Tokens: no credentials acquired, stored or logged; snapshot/DTO diagnostic strings redact content.
- Files/storage: in-memory only, no paths, backups or disk writes.
- Android attack surface: no components, intents, providers, WebViews or Android imports added.
- Cryptography: existing Noise framing unchanged; no new cryptographic operations.
- Network/I/O: existing bounded codec reused; idle expiry and abandonment reclaim fragments. Total retained conversation size remains ADR 042's deferred policy, outside this slice.
- Errors/logs: static codes only, no exception messages or daemon-authored strings.
- Concurrency: synchronous serialized mutation, atomic snapshots, captured generation rejects stale work; expiry belongs to cancellable connection scope.
- Threat model: malicious relay reordering/loss cannot repeat suffixes or certify completeness; hostile frames fail validation. Rooted-device token theft/UI screenshots remain existing platform protections, no new credential/UI surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

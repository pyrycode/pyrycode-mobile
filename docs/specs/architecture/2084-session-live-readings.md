# Session-scoped authoritative live readings (#2084)

## Files read

- `data/network/MobileWireModels.kt`: `Envelope` lacks supplied live metadata; preserve default serialization.
- `data/network/ConversationsPayload.kt`: `ConversationSummaryDto` must consume the daemon's `current_session_id` without treating a stored binding as a running process.
- `data/repository/RemoteConversationRepository.kt`: `onInbound` is the single connection-owned admission point; live state must bypass read evidence, replay and durable folds.
- `data/repository/AuthoritativeLiveProgress.kt` and `ui/conversations/thread/AuthoritativeThreadPresentation.kt`: independent transient inputs joined by recorded item/session identity.
- `data/repository/AuthoritativeThreadRoute.kt` and `AuthoritativeThreadStore.kt`: supplied items remain independently durable and host-scoped.
- `data/repository/HostReadings.kt`, status projections, `SessionSettingsCommands`, `QuestionBatchProjection` and `BackgroundTaskProjection`: retain existing domain decoding and selectively clear session readings.
- `data/model/ModalEvent.kt` and `ModalUiState.kt`: host prompt accumulation needs a conversation-only retirement event that never submits an answer.
- `ui/conversations/thread/ThreadViewModel.kt`: existing held-phase, settings and request identity guards consume the repaired readings.
- `docs/knowledge/features/thread-screen.md`, `remote-conversation-repository.md` and `mobile-protocol-v2-wire-layer.md`: host-held readings survive reconnect; account usage must survive session replacement, and live state never grants sight.
- Daemon `docs/protocol-mobile.md`, Session-scoped live state and Message envelope; `docs/knowledge/decisions/042-daemon-built-thread.md`, Sessions, agents, messages, read marks: authoritative contract. Daemon #3195 is closed; its delivered envelope conversation identity is required here.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The inspected dark frame has the existing translucent header, chronological content and bottom composer, with a primary/bodySmall Thinking reading above the input. Reuse the existing Material 3 status and prompt presentations; this ticket changes their state source and ownership, not their geometry or assets.

## Context

Durable thread items outlive sessions; activity, settings and actionable requests do not. The supplied route is already gated by authenticated host plus both negotiated capabilities. Add admission and reconciliation there, retaining legacy delivery. Production activation and combined daemon/live proof remain #1988; pending/queue reconciliation remains #1986. Preserve #2078's legacy echo baseline. No in-flight feature branches overlap the planned files. No decision record is required.

## Design

Defaulted envelope metadata preserves omitted, explicit null and producing-session provenance without changing unrelated constructors or outbound legacy bytes. A connection-local admission ledger routes only by nonempty envelope conversation identity, validates any payload identity for agreement and validates clear shape before touching state. It compares opaque sessions by equality only.

Authoritative summaries establish the binding. A new tagged clear may establish a replacement; fresh state cannot move a binding by itself. Retired named sessions are rejected before projection writes or reply completion. Missing provenance cannot borrow a binding or replace tagged state. Explicit null establishes no-session state only through summary/clear reconciliation. Track family clear/fresh acceptance so duplicate clears cannot erase fresh state.

On replacement retire session readings selectively, retaining thread items, pending echoes, queue state and pairing/account rate state. Each family clear resets only that family. Reuse existing domain decoders, with separate transient tool/task progress rather than durable row writes. Inactive retry and compaction remain valid fresh readings. Settings are held on the supplied route so unsolicited and correlated readings share the same provenance guard. Correlated replies cannot bypass admission.

Permission retirement removes the owning conversation's requests through the host fold, retaining answered identities. Question retirement clears held batches and retains answered/retired nonces. Outbound prompt actions require an outstanding admitted identity. Existing ViewModel callbacks carry request IDs/generations and cannot act on a replacement. History never reaches these live stores.

## State and concurrency model

The existing connection-scope inbound collector serializes reconciliation. Projection maps remain StateFlows; synchronous admission/clear/fresh processing owns no additional jobs. Settings/progress flows are held in memory, never cached. ViewModel collection and actions remain owned by viewModelScope. Teardown discards the connection ledger; reconnect constructs a new ledger and accepts only supplied outstanding state. Background socket cancellation remains LifecycleConnectionDriver's responsibility.

## State transitions and identity reuse

| Event | Required production-handler regression |
| --- | --- |
| Summary/replacement and stale push/reply | `summariesEstablishIdentityWithoutInventingActivityAndOldTagsCannotReturn`, `taggedSettingsRepliesAndUpdatesCannotSettleRetiredSessionRequests` and `retiredMcpCorrelationCannotRestoreAnUnavailableReading` |
| Equal settings refresh, including confirmation before acknowledgement | `equalSettingsRepliesKeepPermissionPollingUntilConfirmation` and `successfulPushBeforeAckStillSettlesAnEqualRefresh` |
| Progress matched to active supplied items, including reused IDs in another session | `suppliedItemProgressIsVisibleWithoutLegacyRosterAndRetiresByFamilyAndSession` |
| Clear, fresh, repeated clear | `everyStatusFamilyClearsOnlyItselfAndDuplicateClearKeepsFresh` |
| Omitted/null/string session | `missingAndNullProvenanceCannotReplaceTaggedReading` and `reconnectWithoutProvenanceCannotReplaceHostHeldTaggedReadings` |
| Equal session identities across conversations/hosts | `nullAndMissingOwnersRemainIsolatedAcrossConversationsAndHosts` |
| Malformed/conflicting envelope/payload identity | `malformedConversationIdentityNeverFallsBackToPayload`, `invalidPayloadIdentityAndClearShapeCannotMoveTheSession` and `malformedClearFlagsCannotEraseAReading` |
| Inactive reconnect readings | `inactiveRetryAndCompactionRepairLostEdgesWithoutDurableRowsOrReadEvidence` |
| Answer/dismissal, history replay and reconnect | `answeredQuestionsStayInertAndRetiredTapsCannotActOnTheReplacement`, `permissionClearsRetireOnlyTheOwnerWithoutSendingAnswersOrResurrectingHistory`, `answeredItemReplayAndNewConnectionNeverCreateActionableRequests` and `suppliedSessionReplacementUpdatesActivityAndRetiresRenderedPromptCallbacks` |
| Session replacement with durable/queue/account state | `replacementPreservesItemsQueuePendingAndAccountRate` |
| Recollection/configuration change | `suppliedSessionReplacementUpdatesActivityAndRetiresRenderedPromptCallbacks`: remove and remount the screen around held requests, without action replay |

## Error handling

Drop invalid provenance, identity, clear shape and retired readings without fallback or waiter completion. Existing decoders continue dropping malformed family payloads. Diagnostics use static event/outcome codes only, never identifiers, prompt text, reading content or parser exceptions. Prompt send failure retains existing domain error handling.

## Testing strategy

Write decoded wire-envelope handler probes first and run them red before implementation. Exercise every enumerated family through the supplied route, including correlated settings, prompt ownership and transient progress. Add a supplied Robolectric screen regression using the production repository and ViewModel, alongside the existing authoritative screen tests. Run focused legacy repository/session/prompt coverage, lint, assembleDebug, affected shared-test compilation, Spotless, and final pre-verify after merging main. Record nonzero execution and zero failures. No device-only or real-Claude scenario is added: #1988 explicitly owns `InteractiveStreamE2ETest.interactiveTurn_newSession_rendersSessionBoundaryDelimiter` and current-session status freshness.

## Open Questions

None. The delivered conversation-bearing contract was resolved by daemon #3195.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed by validating envelope/payload ownership before clear, projection mutation or correlation. Settings payload `session_id` must be a string and a nonempty write target must equal the producing envelope session; an empty settings read remains unavailable/read-only, while update acknowledgements require the producing session target. This guard precedes reading publication and waiter completion. Missing and null provenance remain distinct; a stored session never supplies authority.
- Tokens: no new credential generation/storage. Answer nonces remain inert existing wire identities; retired nonces cannot authorize a send.
- Files/storage: new state is connection-local heap state. Existing durable/cache writes are excluded from the live path.
- Android attack surface: no manifest, exported component, intent, WebView or permission change; existing secure prompt presentation is retained.
- Cryptography: source host comes from the authenticated connection, preserving the existing Noise_IK_25519_ChaChaPoly_BLAKE2s transport and nonce lifecycle.
- Network/I/O: existing bounded decrypted envelopes and send boundaries remain; no new transport/provider installation.
- Errors/logs: static outcomes only; exceptions and daemon-authored content/identifiers are not logged. Existing display bounds remain in the reused UI boundary.
- Concurrency: admission occurs before waiter completion on the one inbound collector; connection teardown and existing scopes own cancellation. No new timer or unowned job.
- Threat model: malformed authenticated daemon frames are dropped; the relay can delay/drop but cannot forge authenticated ownership. Token theft and existing screenshot/accessibility posture are unchanged. Production combined proof is out of scope and owned by #1988.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12

## Revisions

- 2026-10-12: The permission probe demonstrated that the legacy replay-zero stream loses a supplied prompt before subscription. Hold the authoritative host fold in the remote repository and have the supplied coordinator read that current state, forwarding local prompt actions into it. Legacy connections keep their event fold. This is a state-source correction with no visual change or production activation; the ownership and logging security boundaries remain unchanged.

- 2026-10-12: Host-held model/facts/context/menu readings outlive a connection. Keep their producing binding and retired-session fences beside HostReadings; keep clear/fresh acceptance connection-local. Reconnect probes reject untagged replacements and retired readings without manufacturing activity. Decode the clear flag and summary binding as raw JSON kinds before admission to avoid primitive coercion. Capability-aware prompt collection reselects at the owning pump's Open edge. These changes preserve the reviewed ownership boundary and introduce no new I/O or persistent storage.

- 2026-10-12: A delayed MCP refusal probe failed after replacement because the retired ask remained in its correlation ledger. Family retirement also drops the owning conversation's MCP/model-list asks and model-menu request guards. This keeps untagged legacy refusal replies from restoring session-local flags after a clear. The probe drives the real request and inbound refusal; error/provenance logging remains content-free.

- 2026-10-12 (verifier rework): Preserve settings arrival ordinals independently of value equality so repeated unchanged correlated reads drive `settlePermission`; `equalSettingsRepliesKeepPermissionPollingUntilConfirmation` and `successfulPushBeforeAckStillSettlesAnEqualRefresh` exercise production repository + ViewModel orderings. The missed settings payload write-target boundary is now checked by `SessionLiveAdmission.admit`, with reads, acknowledgements and malformed/correlated targets covered by `settingsPayloadIdentityCannotAuthorizeAnotherSessionOrCompleteItsReply`; the Security review above records this correction. The existing elevated risk remains appropriate.

- 2026-10-12 (verifier rework): `AuthoritativeLiveProgress` and `observeAuthoritativeProgress` supply both tool/task readings independently of the legacy roster. The new `projectAuthoritativeThread` overload joins readings only to active items whose recorded session matches the producing session; clear/fresh processing changes no item, revision or checkpoint. `suppliedItemProgressIsVisibleWithoutLegacyRosterAndRetiresByFamilyAndSession` covers fresh, duplicate clear, replacement and retired progress on the supplied screen. Existing three-argument presentation callers retain their contract; #1988 owns collection/activation.

- 2026-10-12 (verifier rework): Replace the text-only history stand-in with daemon `notice` / `prompt_answered` permission and question records. Replay decoded item envelopes and construct a fresh connection, repository and ViewModel in `answeredItemReplayAndNewConnectionNeverCreateActionableRequests` and `suppliedSessionReplacementUpdatesActivityAndRetiresRenderedPromptCallbacks`. Historical answers remain inert, and only supplied outstanding identities authorize new actions without replay.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository.md`, Status projections: authoritative routing, family retirement, settings arrival ordinals and payload write-target validation; preserve durable/pending/queue/account state and exclude live state from read evidence.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`, Application payloads: envelope conversation ownership and distinct omitted/null/string session provenance, plus settings payload target validation and static diagnostics.
- `docs/knowledge/features/relay-repository-coordinator.md`, prompt collection; `docs/knowledge/features/thread-screen.md`, supplied presentation: held actionable state, answered-history/reconnect boundaries and `AuthoritativeLiveProgress` inputs. Record the boundaries with #1986, #1988 and #1947.

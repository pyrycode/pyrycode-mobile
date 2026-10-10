# Switch a channel's agent from Run configuration

## Files read

- `ThreadViewModel.kt`: `runConfigFlow`, `runConfig`, `onModelSelected`, `sessionSettings` and `conversations` own settings projection and writes.
- `ThreadUiState.kt`: `ThreadModelChoice`, `ThreadRunConfig.selectedChoice`, `selectedMetadata` and `modelLabel` currently assume an own-agent menu.
- `ThreadScreen.kt`: `ThreadStatusArea`, the footer overlay and `StatusSheet` share model selection.
- `StatusSheet.kt`: `ModelSection` uses optional selected-agent metadata to keep identical wire values from marking another agent’s row; existing callers remain source-compatible.
- `MobileModal.kt`: `MobileModal` provides client-controlled confirmation and dismissal callbacks.
- `EffortRecall.kt`: `offer` consumes selected metadata; widening visible rows must not widen recall's vocabulary.
- `SwitchAgentCommands.kt`: `switchAgent` settles only after a matching conversation update; sanitized typed failures do not promise rollback.
- `Conversation.kt`: `Conversation.agent` and `currentSessionId` establish successor identity.
- `ConversationListProjection.kt`: `applySnapshot` and `upsertConversation` must retain the authoritative transition's session identity omitted by metadata DTOs.
- `StableConversationRepository.kt`: `switchToLiveOrHeld` cancels detached delegate reads and emits held settings through the disconnected gap.
- `SessionSettingsCommands.kt`: `readSessionSettings` correlates the authenticated reply to the named conversation; transition invalidation cancels the previous read.
- `ModelMenuExpectations.kt`: shared live assertions count full-list ambiguity and require a rendered row before expecting a mark.
- `InteractiveStreamE2ETest.kt`: `usableRows`, `awaitAnnouncedMark` and `inheritedModelLabel` consume the same bounded display-name contract as the merged picker.
- `ThreadViewModelAgentModelMenuTest.kt`: existing inherited/default, overflow and agent-scoping assertions.
- `docs/knowledge/features/thread-screen-how-it-works-state.md`: the model-menu agent filter couples menu projection to effort recall.
- `docs/knowledge/features/mobile-modal.md`: reuse shell tokens, callbacks and exported close asset; no new modal geometry.
- `../pyrycode/docs/protocol-mobile.md`: `switch_agent` and Security model are authoritative wire/security contracts.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=578-3442 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=578-3248

The confirmation uses the shared mobile modal: target-agent title and close glyph, divider, centered bodyMedium copy, outlined Cancel and filled Switch. Reuse the existing shell's modalContainer/onPrimaryContainer and primary action tokens. The switching status uses the existing animated status glyph and primary bodySmall text, naming the outgoing note during wrap-up. Agent and model names are dynamic; the client adds the slower-first-reply warning alongside cost and full-bypass warnings. Retain Run configuration `600:1694`, opened from the footer Tune icon. All three current frames were read with design context and screenshots on re-entry. The ticket explicitly requires the shared MobileModal shell; its current full-height presentation is reused, including its existing exported close glyph, rather than introducing separate compact-modal geometry.

## Context

The daemon publishes both agents' models. An own-agent pick remains a settings write; an other-agent pick needs explicit confirmation and the already merged repository operation. One UI deliverable; no protocol, history or message-fold changes. No decision record required. Remote feature-branch inspection found no overlaps in the planned production files.

## Design

Add source-compatible row-agent metadata to `ThreadModelChoice`. Project known-agent rows in daemon order, hide default metadata, cap rendering at 32, and count full-list overflow plus daemon drops for both conversation agents. Use bounded inert daemon display names. Selection/default resolution and metadata filter by the conversation agent, including candidates beyond the cap.

Add one `ThreadAgentSwitch` carrier (source agent, picked choice, sending flag) to `ThreadRunConfig`, plus Confirm/Dismiss events on `ThreadEvent`. The ViewModel owns a hot nullable switch flow and a failure flag. Run configuration keeps `onModelSelected`; an other-agent pick opens the shared confirmation without writing. Confirm claims sending synchronously and sends exactly one `switchAgent` call with the route conversation, row agent and raw value. Effort is supplied only when nonempty and published by the target row. Pending label comes from the captured choice; model, effort, permission and refusal-switch-back writes are blocked during switching. No optimistic agent update.

After any observed agent change, accept settings only from that conversation's fresh current session. This prevents old saved choices, effective effort, permission/capability readings and recall crossing the agent transition, including switches initiated by another client. Before any switch, ordinary reset handling retains its existing settings projection. Repository success clears switching state and requests fresh settings; unavailable readings remain unknown until that successor read arrives.

## State and concurrency model

All claims and writes run on Main in `viewModelScope`; the switch flow participates in runConfigFlow, independently of reset progress and ordinary pendingModel. No new dispatcher or detached jobs. Dismissal clears confirmation only, never a sent operation. ViewModel destruction cancels the operation; socket closure settles through the repository's typed unavailable result. Process death does not restore a command or retry it. Shared conversation observation remains one subscription.

## State transitions and identity reuse

| Event | Contract and deterministic coverage |
| --- | --- |
| Own-agent pick | Existing settings write; `ownAgentPickUsesSettings` |
| Other-agent pick, Cancel/close/Back | No operation; `confirmationDismissSendsNothing`, shared UI dismissal coverage |
| Repeated Confirm/model/effort taps | One operation; `supportedEffortAndRepeatedConfirmSendOnce` |
| Wrap-up, restart, reset end, settings clear/stale read | Pending survives; `progressAndStaleSettingsDoNotSettleSwitch` and UI precedence coverage |
| Refusal/local failure/disconnect | Clear pending, restore confirmed choice, static error without rollback claim; `failuresRestoreConfirmedChoice` |
| Failure without a rendered selected row | Preserve bounded standalone display only; `unlistedFailureRestoresDisplayInBothDirections`, `overflowFailureRestoresDisplayInBothDirections`, `inheritedFallbackFailureRestoresDisplay` and five Tune UI counterparts |
| Fresh reading or replacement during/after sending | Retire display permanently; `freshReadingDuringSwitchRetiresPreviousDisplay`, `replacementRetiresFailureDisplayPermanently` |
| Confirmed agent update and successor read | Own-agent metadata only in both directions; `successUsesFreshSuccessorSettings` |
| External agent update or repeated switch back | Mask stale settings; `externalAgentChangeRejectsOldSessionReadings` |
| Ordered transition/update, later rename/snapshot, then switch back | Retain authoritative successor identity through the real repository; `realProjectionKeepsSuccessorSettingsWritableThroughUpdatesAndSnapshotsInBothDirections` |
| Switched agent, disconnect, replacement remote without a transition, held then fresh settings | Non-held conversation-correlated settings establish authority when the new summary omits session identity; `switchedAgentReconnectAcceptsOnlyFreshReplacementSettings` |
| Acknowledged ordinary model write, delayed read, external agent/session replacement | Invalidate the write identity without settling a phone-local switch; `acknowledgedModelWriteIsInvalidatedByExternalAgentReplacement` |
| Settlement, fresh settings, newer external selection, unavailable settings | Retire success/failure display fallbacks permanently; `authoritativeReadingsRetireSuccessAndFailureFallbacks` |
| Recollection/configuration change | Retain ViewModel command, no resend; `recollectionDoesNotResend` |
| Process death/route exit | No persisted command and owning scope cancellation; `viewModelClearCancelsSwitch` |

## Error handling

Use `Result<Unit>` from the existing repository. Classify sanitized SwitchAgentFailure categories in content-free logs; never render daemon text or exception messages. The client-owned error says switching failed and hand-over/backlog effects may already have occurred. No automatic retry. A new deliberate model pick clears the error.

## Testing strategy

Write red projection/routing tests before implementation, then focused ViewModel tests with a controllable fake result and fresh/stale settings and conversation flows. Extend existing agent-menu expectations for the approved merged list. Shared Robolectric UI tests open Run configuration from the footer Tune icon and exercise confirmation actions, pending labels/status precedence and errors. Run existing footer, StatusSheet, connection/status and model/effort tests affected by the projection. No new device-only test: these interactions need no real IME/storage/pixels. File or reuse the specified real-daemon multi-agent rung-3 follow-up before handoff; it owns harness prerequisites and counted live evidence. This ticket claims deterministic coverage only.

## Open Questions

None.

## Security review

**Verdict:** PASS

- [Trust boundaries] `toChoice` bounds/strips daemon display text through `inert`; Compose Text renders names, never markup, URLs, keys or logs. Model write values remain verbatim. Unknown-agent and ambiguous selectable rows cannot trigger switching.
- [Tokens] No credentials are read, created, stored or logged; reuse paired encrypted repository transport.
- [Files/storage] No daemon text enters a path or storage key, and no command is persisted.
- [Android surface] No exported component, intent, permission or WebView changes. MobileModal reuses its existing dismissal and window policies.
- [Cryptography] Noise and Keystore unchanged; no keys, nonces or crypto code added.
- [Network/I/O] Reuse #1117's authenticated/capability-gated switch operation, teardown and confirmed correlation. No retry or new socket.
- [Errors/logs] Only static event/outcome/category codes; never model labels/values, message bodies, tokens, payloads or exception text. Static failure copy makes no rollback promise.
- [Concurrency] Main-thread synchronous sending claim precedes suspension; reentrant confirms cannot duplicate operations. Pending is independent of reset/settings clears; viewModelScope cancels on destruction.
- [Session identity] After connection replacement, a non-held conversation-correlated reply establishes authority when the current summary has no transition binding; a known transition binding still requires an exact match, and an absent conversation or held reading establishes no authority. Ordinary pending model writes bind to their initiating agent/session and are invalidated on replacement. Metadata DTOs omit session IDs. Preserve the authenticated transition's binding on the same conversation row through snapshots/upserts; row removal and connection destruction discard it. Capture only a bounded label and optional radio identity for display; never settings metadata. Retire that snapshot permanently on a fresh non-held reading or agent/session replacement, including replacement during sending; returning to an old identity cannot revive it. No session IDs or labels are logged or persisted.
- [Threat model] Relay delay/drop cannot forge completion through reset progress; repository confirmation remains authoritative. Hostile daemon labels are bounded inert text. Rooted token theft and accessibility/screenshot exposure retain existing transport/OS controls, outside this UI change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

## Revisions

2026-10-10: Implementation inspection found a contradictory product contract. Merged PR #1196 removed Model, Effort and Permission footer buttons; `ThreadComposerFooter` now renders Context, Actions, Attach and Run configuration only. `footerMenu(Model)` and the shared callback remain as older projections, but no operator can open that footer model menu. The supplied Figma switching frame and Run configuration frame agree with the current footer. The planned two-entry-point UI proof cannot be implemented while retaining the approved layout. Route for refinement: restrict selection to Run configuration, or supply a new footer-model affordance and Figma anchor. Partial projection/switch state code is retained on this branch; UI proof, live follow-up and PR handoff are unfinished.

Preservation checks: focused `ThreadViewModelAgentModelMenuTest` and `ThreadViewModelAgentSwitchTest` executed 24 tests, all passed with zero skips. `spotlessApply` passed. These establish partial ViewModel behavior only; no shared UI proof or final builder gate has run.

2026-10-10 (refined re-entry): The updated issue limits all model selection and live proof to Run configuration, resolving the former footer-affordance contradiction. Continue the preserved implementation and add `ThreadAgentSwitchUiTest` for both directions, dismissal routes, pending status/control labeling, failure and successor readings. The existing shared full-height modal is the required styling source; the compact confirmation frame supplies content, typography and actions. No in-flight file overlaps found on re-entry. Total forecast remains about 1200 written lines, one exported carrier plus two event objects, no consumer migration and eight outcome branches.

2026-10-10 (UI proof): Red tests exposed the old radio mark during a pending switch and loss of the confirmed label when settings disappear before a refusal. Run configuration now marks the captured target under its applying header and disables every settings control during the send. The private switch carrier retains the previous inert selected row as a display-only fallback on failure; missing successor settings never borrow its effort/permission metadata. The shared screen test exercises these edges; the unit probe is `failureAfterLostSettingsRestoresConfirmedLabel`. Security review remains PASS: the fallback is inert, agent-scoped, never written or persisted.

2026-10-10 (selection identity): `confirmedRadioMarkIsScopedToAgentEvenWhenValuesCoincide` failed because the radio projection compared value alone. Add optional selected-agent metadata to `StatusSheet`/`StatusSheetContent` and forward it only from `ThreadScreen`. Two production call sites change; all existing constructors and other calls keep their defaults. No new exported type and no simultaneous fixture migration. The raw row-agent pair owns the radio mark as well as the command routing.

2026-10-10 (existing routing contract): Focused effort-recall and held-permission tests require the existing `onModelSelected` write path while no menu is available. Preserve that own-agent settings write; a published menu still requires exactly one known row, and only a captured other-agent row can switch. Unknown/ambiguous published rows remain inert. The Codex model-selection fixture now explicitly tags its row Codex, rather than relying on the source-compatible Claude default. No broad fixture migration.

2026-10-10 (verifier rework): Findings 1–4 require five existing label expectations to follow bounded daemon display names, retention of transition identity across metadata-only conversation updates, permanent retirement of superseded settlement labels, and matching live assertion consumers. `ConversationListProjection` now preserves the known session ID on existing rows in both upserts and full snapshots; the ViewModel's exact successor-session check stays intact. The real-repository probe drives Claude → Codex → Claude with ordered transition/update frames, stale and fresh settings, later rename/snapshot pushes and a successor-addressed effort write. Fresh non-held settings for the fallback's agent retire it; later unavailable settings cannot resurrect it. `dropdownLabel` returns the display name for both agents and retains its argument for source compatibility; all six existing assertion consumers were checked; live radio expectations use the merged visible list and agent/value identity. Security review remains PASS. Forecast is below 1500 total written lines, with no new exported types or required consumer migration.

Rework overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879 and #1896 touch other scenarios in `InteractiveStreamE2ETest`; their diffs do not restructure the model-menu helpers. Keep the edits local.


2026-10-11 (verifier rework): Findings 1–3 expose connection replacement, an ordinary pending write outliving its agent/session, and live helpers mistaking overflow metadata for a rendered radio. `runConfigFlow` accepts fresh conversation-correlated settings when a replacement connection’s summary has no session binding, while rejecting absent conversations, held readings and mismatches to a known transition. A private pending-model carrier binds each ordinary pick and refusal-switch-back to its agent/session; replacement invalidates it independently of the phone-local agent-switch operation. Extract the live menu expectation functions to shared test source so JVM probes cover unique hidden matches and ambiguity spanning the cap; ambiguity still counts all own-agent rows, but display/marks require visibility. Security review remains PASS after rechecking all categories: transport authentication/correlation and cancellation stay authoritative, held/old-session data cannot enable writes, no new logs, storage, credentials or Android surface. This repair adds no exported production type or consumer migration and no layout change. Existing overlapping e2e branches still change other scenarios, so keep helper edits local.

2026-10-11 (restoration rework, finding 1): Replace the row-only settlement fallback with a private confirmed-display snapshot: bounded inert label, agent/session binding and optional represented row identity. Capture `modelLabel` independently of `selectedChoice` before confirmation. Project the label through `modelLabel` and the standalone `modelSelectionNote` when no row represents it; expose a radio identity only while its own-agent row is rendered. The snapshot provides no effort, permission, capabilities or writability. Retire it permanently on fresh non-held settings or agent/session replacement, including a replacement during an outstanding operation. Keep the existing reconnect and external-switch authority rules.

Deterministic coverage: `unlistedFailureRestoresDisplayInBothDirections`, `overflowFailureRestoresDisplayInBothDirections` and `inheritedFallbackFailureRestoresDisplay` exercise lost settings then refusal/local failure, one send, missing stale metadata and fresh-reading retirement; Tune UI counterparts exercise each applicable direction. `replacementRetiresFailureDisplayPermanently` covers agent/session replacement and reuse; `freshReadingDuringSwitchRetiresPreviousDisplay` covers supersession before failure. Security self-review remains PASS across all categories: the private snapshot bounds label text with `inert`, retains no settings authority, persists/logs nothing and changes no transport, credentials, crypto, storage or Android surface. Figma Run configuration `600:1694` was reread with design context and screenshot; existing bodyMedium standalone copy and modal styling are retained. Incremental repair forecast: about 350 written lines, no exported type or required consumer migration, one existing failure contract repaired. No in-flight branch overlaps were found in the three affected production files.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/thread-screen-how-it-works-state.md`, “The model-menu agent filter”, for the merged menu, retained own-agent selection/effort scope, and fresh authority across connection replacement; `docs/knowledge/features/thread-composer-footer-effort-recall.md` for the unchanged own-agent recall contract. Live scenario and counted live evidence belong to follow-up #2052, not this deterministic implementation.

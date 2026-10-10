# Switch a channel's agent from either model picker

## Files read

- `ThreadViewModel.kt`: `runConfigFlow`, `runConfig`, `onModelSelected`, `sessionSettings` and `conversations` own settings projection and writes.
- `ThreadUiState.kt`: `ThreadModelChoice`, `ThreadRunConfig.selectedChoice`, `selectedMetadata` and `modelLabel` currently assume an own-agent menu.
- `ThreadScreen.kt`: `ThreadStatusArea`, the footer overlay and `StatusSheet` share model selection.
- `MobileModal.kt`: `MobileModal` provides client-controlled confirmation and dismissal callbacks.
- `EffortRecall.kt`: `offer` consumes selected metadata; widening visible rows must not widen recall's vocabulary.
- `SwitchAgentCommands.kt`: `switchAgent` settles only after a matching conversation update; sanitized typed failures do not promise rollback.
- `Conversation.kt`: `Conversation.agent` and `currentSessionId` establish successor identity.
- `ThreadViewModelAgentModelMenuTest.kt`: existing inherited/default, overflow and agent-scoping assertions.
- `docs/knowledge/features/thread-screen-how-it-works-state.md`: the model-menu agent filter couples menu projection to effort recall.
- `docs/knowledge/features/mobile-modal.md`: reuse shell tokens, callbacks and exported close asset; no new modal geometry.
- `../pyrycode/docs/protocol-mobile.md`: `switch_agent` and Security model are authoritative wire/security contracts.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=578-3442 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=578-3248

The confirmation uses the shared mobile modal: target-agent title and close glyph, divider, centered bodyMedium copy, outlined Cancel and filled Switch. Reuse the existing shell's modalContainer/onPrimaryContainer and primary action tokens. The switching status uses the existing animated status glyph and primary bodySmall text, naming the outgoing note during wrap-up. Agent and model names are dynamic; the client adds the slower-first-reply warning alongside cost and full-bypass warnings. Retain the existing picker layouts from Options overlay `533:1958` and Run configuration `600:1694`, read with screenshots before planning.

## Context

The daemon publishes both agents' models. An own-agent pick remains a settings write; an other-agent pick needs explicit confirmation and the already merged repository operation. One UI deliverable; no protocol, history or message-fold changes. No decision record required. Remote feature-branch inspection found no overlaps in the four production files.

## Design

Add source-compatible row-agent metadata to `ThreadModelChoice`. Project known-agent rows in daemon order, hide default metadata, cap rendering at 32, and count full-list overflow plus daemon drops for both conversation agents. Use bounded inert daemon display names. Selection/default resolution and metadata filter by the conversation agent, including candidates beyond the cap.

Add one `ThreadAgentSwitch` carrier (source agent, picked choice, sending flag) to `ThreadRunConfig`, plus Confirm/Dismiss events on `ThreadEvent`. The ViewModel owns a hot nullable switch flow and a failure flag. Both picker entry points keep `onModelSelected`; an other-agent pick opens the shared confirmation without writing. Confirm claims sending synchronously and sends exactly one `switchAgent` call with the route conversation, row agent and raw value. Effort is supplied only when nonempty and published by the target row. Pending label comes from the captured choice; model, effort, permission and refusal-switch-back writes are blocked during switching. No optimistic agent update.

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
| Confirmed agent update and successor read | Own-agent metadata only in both directions; `successUsesFreshSuccessorSettings` |
| External agent update or repeated switch back | Mask stale settings; `externalAgentChangeRejectsOldSessionReadings` |
| Recollection/configuration change | Retain ViewModel command, no resend; `recollectionDoesNotResend` |
| Process death/route exit | No persisted command and owning scope cancellation; `viewModelClearCancelsSwitch` |

## Error handling

Use `Result<Unit>` from the existing repository. Classify sanitized SwitchAgentFailure categories in content-free logs; never render daemon text or exception messages. The client-owned error says switching failed and hand-over/backlog effects may already have occurred. No automatic retry. A new deliberate model pick clears the error.

## Testing strategy

Write red projection/routing tests before implementation, then focused ViewModel tests with a controllable fake result and fresh/stale settings and conversation flows. Extend existing agent-menu expectations for the approved merged list. Shared Robolectric UI tests exercise both picker entry points, confirmation actions, pending labels/status precedence and errors. Run existing footer, StatusSheet, connection/status and model/effort tests affected by the projection. No new device-only test: these interactions need no real IME/storage/pixels. File or reuse the specified real-daemon multi-agent rung-3 follow-up before handoff; it owns harness prerequisites and counted live evidence. This ticket claims deterministic coverage only.

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
- [Threat model] Relay delay/drop cannot forge completion through reset progress; repository confirmation remains authoritative. Hostile daemon labels are bounded inert text. Rooted token theft and accessibility/screenshot exposure retain existing transport/OS controls, outside this UI change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10

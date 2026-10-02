# #1338: mark every chat holding a permission prompt as waiting

## Files read

- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState.resolve` takes one `ModalUiState`; it switches to the host's outstanding prompt list.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `HostConversationConnection.modal`, the prompt collector in `launchAttention`, `promptKeys` and `Held.modal` read one prompt; they switch to the list.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `reconcile` wires `coordinator.currentModal` into each `HostConversationConnection`; `currentModal` is the selection projection of the single prompt, with no production reader.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `currentModal` maps `hostModals` to `latestOutstanding`, kept by #1337 only until this ticket.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt`: `HostModalState.outstanding` (#1337) is the list the readers take; `latestOutstanding` is the single-prompt value to remove.
- Desktop `src/renderer/src/store/modalPrompts.ts` `selectHasOutstandingFor`: a chat waits when any outstanding prompt belongs to it.

Overlap: #1361 adds `rowsAdded` and a row-count collector to `ConversationAttention.kt` and `HostConversationSource.kt`; both edits here are in other functions.

## Design source

Figma https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-3902 (the row's status dot). The row already draws `WaitingForAnswer`; visuals do not change, so no fidelity check applies.

## Change

`HostConversationConnection.modal: StateFlow<ModalUiState>` becomes `modals: StateFlow<HostModalState>`, defaulted to an empty `HostModalState`, and the registry wires `coordinator.hostModals` into it. `Held` keeps the host's `outstanding` list, `promptKeys` emits one `modal:<modalId>` key per outstanding prompt with a non-blank conversation, so each prompt alerts once and a newly added second prompt alerts on its own. `HostAttentionState.resolve(prompts: List<ModalUiState.Open>, batches)` marks a conversation waiting when any prompt in the list has that non-blank conversation id, matching `selectHasOutstandingFor`. Answering A removes A's prompt from `outstanding` (the #1337 fold), so A's waiting clears and B's stays. The single-prompt value goes: `RelayRepositoryCoordinator.currentModal`, `RelayConnectionRegistry.currentModal` and `HostModalState.latestOutstanding` are removed. Threads read `hostModals` through `scopedTo` and are untouched.

## Testing strategy

- `ConversationAttentionTest`: `resolve` with prompts in A and B marks both waiting; dropping A's prompt leaves only B; a blank-conversation prompt waits for no row. The existing single-prompt cases move to the list signature.
- `HostConversationSourceAttentionTest`: two prompts held by one host mark both conversations waiting and raise two `Prompt` alerts, once each; re-emitting the same list alerts nothing; removing A's prompt clears A and keeps B. Existing cases move from `modal` to `modals`.
- `RelayRepositoryCoordinatorTest` and `RelayConnectionFactoryTest`: assertions on the removed `currentModal` read `hostModals.outstanding` instead; `ModalUiStateTest` drops the `latestOutstanding` case.

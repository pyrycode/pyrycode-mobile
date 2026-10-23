# Thread screen — wiring

Destination and dependency binding for the [thread screen](thread-screen.md).

## Wiring

### Koin binding

```kotlin
// di/AppModule.kt
viewModel { get<ThreadDestinationFactory>().thread(get(), get()) }
```

The factory receives the entry's `SavedStateHandle` and the app-scoped
`ComposerDraftStore` ([#789](../codebase/789.md)). It no longer receives
`AppPreferences` — [#807](../codebase/807.md) removed the thread's last read of it
(`defaultModel` / `defaultEffort`), so no thread-destination state reads a device
preference any more; see [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing).
It reads the exact `serverId` and binds a `StableConversationRepository` to that
retained bundle's coordinator stream; `ThreadViewModel` continues to read the
host-local `conversationId`. The bundle also supplies connection state, live
session events, the current permission modal and its answer/cancel callbacks, and
Stop. See [destination dependencies](dependency-injection-host-conversation-source.md#destination-ownership).

Messages, sessions, queue state, Send, Reset session, queue drop, Send now and existing
repository-backed actions stay with that owner. Switching compatibility selection
while a thread or permission prompt is open cannot redirect reads or writes.
Reconnect replaces the concrete repository beneath the same owner facade; an A
outage leaves B usable. During disconnect/handshake, existing empty/default reads,
unavailable actions and error handling remain in effect without using B as a
fallback. Overlay Retry passes the captured bundle to the registry's
[lifecycle-checked exact-host Retry](dependency-injection-host-conversation-source.md#exact-host-retry-and-lifecycle).

With `useRelay = false`, the explicit `demo` destination uses the existing fake
singleton, a connected fake connection source, empty live events, hidden modal
state and inert default control callbacks, even with real hosts saved.

### Destination block

`PyryNavHost` registers `Routes.CONVERSATION_THREAD` with
`Routes.hostArguments()`, reconstructs its `HostConversationTarget`, and calls
`HostDestination` before resolving `koinViewModel<ThreadViewModel>()`. The guard
waits for saved-host initialization; unknown/removed owners return to the list.
Both route arguments survive saved back-stack restoration. Each entry owns its
ViewModel, so equal conversation ids on A and B remain distinct. The temporary
flat-list adapters capture selection only at entry, then consume host-qualified
navigation; see [navigation ownership](navigation.md#how-it-works).

Since [#661](question-batch-modal.md), this same destination block also collects `vm.questionModal` for the
conversation's held [clarification batch](question-batch-modal.md). **Through #1305's plan this was drawn
as a gate-shaped `Dialog` directly beside the `ThreadScreen` call, not a `ThreadScreen` parameter — #1305
superseded that placement.** The batch now renders inline, inside the message stream, so it *is* a
`ThreadScreen` parameter: `questionState = questionModal` and `onQuestionEvent = { event, generation ->
vm.onQuestionEvent(event, generation) }` are passed straight into the `ThreadScreen(...)` call here, and the
process-lifetime picks live in the app-scoped `QuestionDraftStore` rather than this destination's own state
— see [Question batch modal § Placement](question-batch-modal.md#placement-inline-in-threadscreen-since-1305)
and [§ Batch ownership](question-batch-modal.md#batch-ownership-process-lifetime-drafts-source--and-request-bound-sends).

The destination collects the ViewModel's state, connection/live indicators and
permission state with `collectAsStateWithLifecycle()`, and passes callbacks and
error flows into the stateless `ThreadScreen`. `ThreadNavigation.PopBack` remains
collected in `LaunchedEffect(vm)` at the route, where the `NavController` lives.
[#883](../../specs/architecture/883-retire-literal-screen.md) removed the
`literal_screen/{serverId}/{conversationId}` destination this block used to reach
from the overflow menu's "Show the literal screen" item, once the daemon dropped
the server-side screen-snapshot render path — the destination no longer collects
`vm.isStalled` either, since that flag's only consumer was the retired stall
promotion banner (see [Stall state](stall-state.md)).

The guard also wraps the screen in `HostWorkspaceRepository`, providing
`LocalWorkspacePickerRepository` for the route host. This covers the nested
[WorkspacePicker](workspace-picker.md)'s own recents/folder-creation reads and
writes as well as `vm::onWorkspacePicked`. A correct ViewModel binding alone
cannot prevent a descendant's global Koin lookup from consulting another host.
The facade follows reconnect and preserves the picker's existing generic failure
UI and cancellation behavior. Production-route picker tests exercise this boundary;
see [DI testing](dependency-injection.md#testing).

### Composer drafts and attachments

Split out on 2026-09-24 into [Thread screen — composer drafts and attachments](thread-screen-composer-drafts-and-attachments.md): `Composer draft ownership` (the `ComposerDraftStore` text map, its creation/restore/clear/eviction rules) and `Composer pending attachments` (the parallel attachment map, the `AttachmentReader` trust boundary, the [#933](https://github.com/pyrycode/pyrycode-mobile/issues/933) picker and strip, and the [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934) paste path that joins the same sink). Both subsections kept their heading and anchor.

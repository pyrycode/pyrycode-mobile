# ChannelListViewModel — related documents and ticket history

Part of [ChannelListViewModel](channel-list-viewmodel.md). Historical specs describe the
implementation at their ticket's time; current ownership is described by the topics below.

## Related

- [Host source identity and snapshots](dependency-injection-host-conversation-source.md#host-identity-and-snapshots) and [exact-host repository access](dependency-injection-host-conversation-source.md#exact-host-repository-access): host-local ids and fresh repository resolution.
- [ChannelListScreen](channel-list-screen.md): host-qualified row opening, selection, folds, creation and host editing.
- [Controller and list regression coverage](channel-list-viewmodel-testing.md): colliding-host fixtures, creation disconnect races, changed-only channel writes and partial-failure retry.
- [Host editor](host-editor.md): shared host editing/unpair state and guarded transitions.
- [Mobile modal callers](mobile-modal-callers.md#callers): surviving standalone components and current bindings.
- [Thread Edit channel](thread-overflow-menu-viewmodel-dispatcher.md#editchannel--channeleditorcontroller-1561): production owner of `ChannelEditorController`; the controller and its state types retain their `ui.conversations.list` package after #1582 removes list ownership.
- [System prompt editor](system-prompt-editor.md): Channel info's separate prompt editor, bound to the stable thread repository.
- [Conversation repository](conversation-repository.md), [app preferences](app-preferences.md), [paired server store](paired-server-store.md) and [dependency injection](dependency-injection.md): data and ownership seams.
- [Navigation compatibility](navigation.md#temporary-flat-list-compatibility): the unreachable discussion list's remaining selected-host adapter.
- [Interactive stream ladder](../../e2e-interactive-stream.md): existing real-Claude coverage; #1582 adds no live scenario.

The [#1582 cleanup plan](../../specs/architecture/1582-remove-unreached-list-editors.md)
records removal of list editor events, routes, hosting, state and the unused conversation pen API.
Earlier #827/#828 chat editor and #667/#1021 channel editor specs remain historical references;
their list wrappers are retired. #1561 extracted the retained channel controller for thread Edit.
Workspace editing (#905), Add workspace (#904), host editing (#744/#745/#751), and creation
(#958/#1189) survive the conversation-editor cleanup.

# Remove unreached list conversation editors (#1582)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt`: `HostChannelListState`, editor flows and entry points; retain shared channel state types in this package.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelEditorController.kt`: `ChannelEditorController` owns prompt reads and changed-only write/retry handling; its behavior stays unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: `ChannelListEvent`, `ChannelEditorModal`, `ChatEditorModal`; no reachable row emits either open event.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `PyryNavHost` list event dispatch.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt`: `TreeConversationRow` optional pen; host and workspace controls are separate APIs.
- `app/src/main/res/values/strings.xml`: list conversation pen descriptions only.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt`: shared colliding-host fixture and controller regressions currently exercised through list wrappers.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt`: list modal hosting tests and pen-free row-opening regression.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelChannelEditTest.kt`: retained thread Edit ownership and disconnect handling.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenOverflowTest.kt`: retained thread Edit and Rename modal hosting.
- `docs/knowledge/features/channel-list-screen.md` and `channel-list-viewmodel-testing.md`: host-local targets, colliding IDs, disconnect races and partial-failure retry coverage must survive retirement.
- `docs/knowledge/features/thread-screen.md` and `navigation.md`: thread owns the reachable conversation editor routes.

## Design source

No reachable visual change: #1563 already removed conversation pens. This retires unreachable hosting and an unused row API, while retaining existing row geometry and host controls. No new design is required.

## Change

Remove the list's chat editor state type, both conversation editor fields and flows, their open/submit/archive/dismiss methods, events, destination dispatch and modal bindings. Simplify the remaining host/workspace editor combination. Keep `ChannelEditorController`, `ChannelEditorState` and `ChannelPromptReading` in their current package with unchanged behavior. Remove only `TreeConversationRow`'s optional pen parameters/rendering and the two unused accessibility resources. Retire list-only modal/chat-editor tests and migrate shared channel-machine assertions to a directly constructed controller using the existing colliding-host repository fixture. Trim mixed create/edit tests to surviving list creation behavior; controller and thread tests cover the channel lifecycle separately. The no-pen screen assertion uses row semantics rather than removed resources.

Overlaps: #1775 adds host-prompt handling beside these list edits; #1735 changes thread navigation, #1603 and branch `1283-notice-placement` change unrelated strings. All are local, independent edits and require no blocker.

Sizing: one cleanup deliverable, no new exported types or failure branches, three acceptance criteria. Forecast under 1100 inserted/modified lines with removed lines counted separately, matching the refiner's estimate and smaller than the cited #738 analogue. The 43 test invocations plus two production dispatch arms exceed the consumer ceiling; the floor rule keeps regression migration with its sole consuming cleanup, as the ticket explicitly records.

## Testing strategy

First migrate the channel-machine tests and verify them against the unchanged controller before removing list ownership. This deletion adds no new logic requiring a new red assertion. Preserve host targeting, prompt availability/byte limits/redaction, changed-only writes, mute and partial-failure retry, archive, invalid/unavailable/in-flight/dismissal tests. Add direct lifecycle assertions if thread tests leave a gap. Retain list create and disconnect checks, host controls, selection and host-specific tap coverage. Assert conversation rows have no content-description controls in their unmerged subtrees, while row opening works across selection changes.

Run focused `HostChannelListViewModelTest`, `ThreadViewModelChannelEditTest`, thread Rename unit coverage, `ChannelListScreenTest` and `ThreadScreenOverflowTest`; lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. No device-only test or live scenario is introduced; dispatcher-owned full gates remain pending.

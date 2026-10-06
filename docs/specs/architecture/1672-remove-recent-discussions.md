# #1672 — Remove the unreachable Recent discussions screen

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt`: `DiscussionListScreen`, its events, private row/dialog helpers and previews are exclusive to the dead destination.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt`: `DiscussionListViewModel`, both state/navigation models, pending promotion types, `derivedChannelName` and `displayLabel` have no surviving consumers.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `PyryNavHost` registers the unreachable destination; `Routes` retains its constant. Reachable tree navigation already collects host-qualified targets.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `appModule` binds the dead ViewModel independently of shared repositories and thread destinations.
- `app/src/main/res/values/strings.xml`: only the dead screen uses `discussion_list_title`, `discussion_list_empty` and the four `promote_dialog_*` resources; the thread still uses `save_as_channel_action`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreenTest.kt`: obsolete confirmation-dialog coverage to delete.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt`: obsolete flat projection/promotion coverage to delete.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostDiscussionListViewModelTest.kt`: obsolete host promotion coverage to delete.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenNavigationTest.kt`: `hostStreamsBackReopenAndRestorationKeepDestinationIdentity` currently reopens via the dead destination.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`: existing Save as channel modal-shell tests already prove captured chat names, null/blank `New channel`, trimmed submission and cancellation without writes.
- `docs/knowledge/features/navigation.md`, `channel-list-screen.md`, `discussion-list-screen.md` and `discussion-list-viewmodel.md`: document the deliberate unreachable retention after #731/#738; current source confirms that history.
- `docs/knowledge/features/save-as-channel-dialog.md`: thread promotion is an independent reachable modal with its own contract and tests.

Codegraph caller queries were supplemented with Kotlin/resource searches because the index omits some Compose/constructor references. The only surviving consumer to rewrite is the navigation test; graph and DI wiring are removed. In-flight #1729 overlaps `MainActivity.kt` and `AppModule.kt` in independent sharing-shortcut blocks, so removal remains local and needs no dependency.

## Design source

N/A: the ticket explicitly authorizes deleting an unreachable destination without a Figma section. No visible screen or reachable flow changes.

## Change

Delete the two dead production files with their exclusive types/helpers, three obsolete test files, destination block, route constant, Koin binding/imports and six exclusive strings. Keep `ConversationRow`, `HostConversationTarget`, shared repositories/promotion and `save_as_channel_action`. Rewrite the navigation method to return to `CHANNEL_LIST` before each reopened host target and use `ChannelListViewModel` for both. Preserve its same-ID/different-host ViewModel assertions, duplicate suppression, Back/reopen and saved-state restoration checks. Add a graph regression asserting the retired `discussions` destination is absent; it must fail before removal. No new type, state, failure branch, dependency or logging lifecycle is introduced.

Sizing: approximately 65 inserted/rewritten lines including this plan, no new exported declarations, one surviving consumer, three acceptance criteria and zero reject branches. The larger deletions are obsolete production/tests, matching #883's removal shape rather than new implementation work.

## Testing strategy

First run the new graph-removal assertion red against the registered destination, then remove it and run `LiteralScreenNavigationTest`, `ThreadViewModelTest` and `ThreadOverflowMenuTest` green. Existing thread modal tests supply all required promotion coverage without copying the dead screen's default. Run the rewritten navigation method on the managed Android 13 device, checking fresh XML executed/failure/skipped counts; the shared test remains in `sharedTest` rather than adding a device-only class. Run lint, debug assembly, Android-test compilation, Spotless apply/check, then merge main, commit/push and run the whole unit/shared suite, debug assembly and `scripts/pre-verify.py --gradle` before PR creation. No live scenario is required for deletion of an unreachable destination.

## Documentation handoff

Pending for the documentation stage:

- `app/src/androidTest/assets/design-1220/README.md`, **Unreachable in source**: remove only the Discussion list row; preserve all other historical audit evidence.
- `docs/knowledge/features/channel-list-screen.md`, **What it does**: replace the current claim that `Routes.DISCUSSION_LIST` / `DiscussionListScreen` remain registered with their removal in #1672.
- `docs/knowledge/features/navigation.md`, **What it does**, **Host-qualified destinations**, **Temporary flat-list compatibility**, and relevant test descriptions: remove the registered discussion destination/adapter from current navigation descriptions and update route counts and navigation-test consumers. Preserve historical audit evidence and frozen ticket archives.

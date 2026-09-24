# #883 — Retire the literal-screen action from the thread

Short plan: the change is a deletion. It adds no type, state or failure mode.

## Files read

- `ui/conversations/thread/ThreadOverflowMenu.kt` → `ThreadOverflowMenu` — drop the first item and the `onShowLiteralScreen` parameter.
- `ui/conversations/thread/ThreadTopAppBar.kt` → `ThreadTopAppBar` and its two previews — drop the pass-through `onShowLiteralScreen`.
- `ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` — drop `onShowLiteralScreen`, the `StallPromotionBanner` call and the `isStalled` parameter, whose only consumer was the banner.
- `MainActivity.kt` → the `CONVERSATION_THREAD` destination in `PyryNavHost` (stops collecting `vm.isStalled`, drops the navigate lambda), the `LITERAL_SCREEN` composable, `Routes.LITERAL_SCREEN` and `Routes.literal`.
- `di/AppModule.kt` → the `LiteralScreenViewModel` `viewModel {}` binding in `appModule` and `ThreadDestinationFactory.literal`.
- Deleted: `ui/conversations/thread/LiteralScreenSurface.kt`, `ui/conversations/thread/LiteralScreenViewModel.kt`, `ui/conversations/components/StallPromotionBanner.kt`.
- `res/values/strings.xml` — `thread_stall_promotion_message`, `cd_thread_stall_promotion`, `thread_overflow_show_literal_screen`, `literal_screen_*`: used only by the removed surfaces.
- `test/.../di/RelayConnectionFactoryTest.kt` → `destinationBindingsKeepCollidingIdsOnTheirHostAcrossSelectionAndReconnect` — resolves `LiteralScreenViewModel` from Koin to prove per-host routing of `request_snapshot`; the literal arms go, the thread-VM routing assertions stay.
- `androidTest/.../thread/ThreadOverflowMenuTest.kt`, `ThreadScreenOverflowTest.kt` — assert the item; `LiteralScreenNavigationTest.kt`, `LiteralScreenSurfaceTest.kt`, `StallPromotionBannerTest.kt`, `test/.../LiteralScreenViewModelTest.kt` — tests of removed surfaces, deleted.
- `../pyrycode/docs/protocol-mobile.md` § Screen snapshot (v2) — why: the daemon always answers `server.binary_offline`.

In-flight overlap check: no other `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread frame shows the header bar (back, title, overflow), the message list and the input area with no banner between header and list; the design has neither the literal-screen item nor a stall banner. Nothing is added, so there is no new visual to match.

## Change

Remove both entry points and everything behind them. The overflow menu loses its first item; every other item keeps its order and its promotion/`mutationsSupported` gating unchanged. `ThreadScreen` no longer renders `StallPromotionBanner`, and since `isStalled` fed only that banner the screen parameter and `MainActivity`'s collection of it go too. Out of scope and untouched per the ticket: `ConversationRepository.requestScreenSnapshot`, `data/network/SnapshotPayload.kt`, `observeStall` and `ThreadViewModel.isStalled` (still covered by `ThreadViewModelTest`).

KDoc in `CompactingIndicator`, `ApiRetryIndicator` and `QueuedMessageRow` names `[StallPromotionBanner]` as historical precedent. Those three files stay untouched to keep the production file count at the eight the estimate names; the references become unresolved KDoc links, which is not a build or lint error.

## Testing strategy

- RED first: `ThreadOverflowMenuTest` asserts the "Show the literal screen" text does not exist, in both the promoted and the unpromoted menu (a string literal, since the resource is removed). The `mutationsSupported = false` test drops its positive assertion of the item; the tap test for it is deleted.
- `ThreadScreenOverflowTest.tapping_overflow_icon_renders_all_six_menu_items` drops the item's assertion.
- `RelayConnectionFactoryTest`: remove the literal VMs, their requests and the snapshot replies. Host B's outbound list, which held only B's `request_snapshot`, is asserted empty instead, which keeps the "A's actions do not leak to B" check.
- Scoped `testDebugUnitTest` on `RelayConnectionFactoryTest`, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`, and the focused device run of `ThreadOverflowMenuTest` and `ThreadScreenOverflowTest`.
- No rung-3 scenario: this removes an operator-facing flow rather than shipping one.

## Revisions

**2026-09-24, merging `main`.** `LiteralScreenNavigationTest` is kept rather than deleted. Only two of its four tests touched the literal screen, and `main` has since extended the other two, the workspace-picker owner tests, for #904, #899 and #685. The literal-screen steps come out: in `hostStreamsBackReopenAndRestorationKeepDestinationIdentity`, the overflow-menu trips to `LITERAL_SCREEN` on hosts A and B, replaced on B by a state restoration of the thread; and the `Routes.literal` navigation in `unknownAndRemovedHostsReturnToListWithoutResolvingAnotherHost`. The file and class keep their name, so `main`'s lines stay on the path it uses. `LiteralScreenSurfaceTest` and `StallPromotionBannerTest` are still deleted.

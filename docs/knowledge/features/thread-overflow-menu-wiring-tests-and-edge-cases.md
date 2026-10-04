# ThreadOverflowMenu — wiring, tests and edge cases

Split out of [ThreadOverflowMenu](thread-overflow-menu.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [ThreadOverflowMenu](thread-overflow-menu.md); see that document for what it does, its edge cases and its links.

## Configuration / wiring

`ThreadTopAppBar` owns only chrome and reports its IconButton's live `boundsInWindow()` through
`onOverflowAnchorChanged`. `ThreadScreen` hosts `ThreadOverflowMenu` over the Scaffold, subtracts
`layerOrigin` from those bounds and supplies `Modifier.imePadding()`. Below placement starts 4dp
below the button, aligns at anchor-left minus 12dp with 8dp edge clamps, and scrolls in the remaining
space above the IME. All rows, gates and dismiss-before-action routing remain owned by the menu.
Current state is read on each recomposition; no cached report or captured conversation controls rows.
`onBackgroundTasks = { backgroundTasksOpen = true }` remains UI-local. The existing string resources,
`cd_more_actions`, `ThreadEvent` sink and shared `MEMORY_PLUGIN_DOCS_URL` remain unchanged.

Opening the header clears the footer control; opening a footer clears the header. Slash suggestions
stand down for either menu. The same-window scrim consumes outside taps without routing an action
or moving composer focus. Ordinary Compose `BackHandler` lets the IME consume Back first on Android 13.
The header therefore registers an `OnBackInvokedCallback` at `PRIORITY_OVERLAY` only while mounted,
uses `rememberUpdatedState` for dismissal and unregisters on disposal. Application-level platform Back
opt-in is required, including for isolated ComponentActivity hosts; activity-only opt-in left registration
disabled. The shared overlay BackHandler remains the activity fallback. Reader and footer behavior stays
with their existing hosts.

## Tests

Coverage spans the menu composable, the mounted thread screen and the ViewModel dispatcher.

For #1631, `ThreadOverflowMenuTest` checks the actual row bounds to prove Background tasks
follows Channel info (and precedes memory installation when offered), across channels/chats
and mutations on/off. Presence assertions alone cannot prove order. Its callback log pins
dismiss-before-open and confirms no `ThreadEvent` is dispatched. `BackgroundTaskPanelTest`
uses the mounted thread to check zero-task empty and never-reported readings, menu dismissal
and the retained Actions opener. These shared tests run under Robolectric.


### Reset wording and retained content

`ThreadScreenOverflowTest` asserts the literal “Reset session” menu label and
its dismissal after `ThreadEvent.NewSession`. Its
`resetFailure_showsFixedMessageAndRetainsThread` case injects the payload-free
error signal, then checks the literal failure snackbar and retained message.
Resource-derived expected text alone would also pass with an incorrect resource
value. Repository failed-send and ViewModel error tests cover the send-to-signal
path; the screen fixture covers presentation.

### `ThreadOverflowMenuTest.kt` (shared Compose, Robolectric)

Lives at `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt`. Mirrors [`WorkspacePickerSheetTest`](workspace-picker-sheet.md)'s scaffold: `createComposeRule` + `PyrycodeMobileTheme` wrapper + i18n-safe label lookup via `InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)`.

  [#382](../codebase/382.md) had every `ThreadOverflowMenu(...)` call site in this file pass the (then-required) `onShowLiteralScreen`; [#883](../../specs/architecture/883-retire-literal-screen.md) removed the parameter along with its dedicated tap test, and dropped the positive assertion of the item from the two render-order tests below.
- **`channel_menu_items_render_in_documented_order_when_expanded`** ([#204](../codebase/204.md) split) — renders with `isPromoted = true`; asserts Reset session, Edit, Archive, Channel info and Install memory plugin are displayed, `save_as_channel_action` `assertDoesNotExist()`, and — since [#1561](https://github.com/pyrycode/pyrycode-mobile/issues/1561) — Rename itself `assertDoesNotExist()` too, since a channel's Rename slot now reads Edit. Since [#883](../../specs/architecture/883-retire-literal-screen.md), also asserts the retired "Show the literal screen" label `assertDoesNotExist()` — a literal string, since the resource itself was deleted.
- **`discussion_menu_items_render_in_documented_order_when_expanded`** ([#204](../codebase/204.md) split) — renders with `isPromoted = false`; asserts `save_as_channel_action` + the five common labels are displayed, `_install_memory_plugin` `assertDoesNotExist()`, and — since #1561 — Edit `assertDoesNotExist()` too, since a discussion's Rename slot stays Rename. Pre-#204 the file had one `menu_items_render_in_documented_order_when_expanded` test that asserted only the five common items; #204 split it across the two `isPromoted` variants. Since [#883](../../specs/architecture/883-retire-literal-screen.md), also asserts the retired "Show the literal screen" label `assertDoesNotExist()` in this variant too — **asserting its absence in *both* the promoted and unpromoted tests is what proves the retirement is complete regardless of promotion.** Order is verified by actual row bounds; presence and routing alone cannot establish order.
- **`mutation_actions_are_hidden_when_mutations_unsupported`** ([#508](../codebase/508.md)) — renders with `mutationsSupported = false, isPromoted = true`; asserts Reset session / Rename / Edit / Change workspace / Archive each `assertDoesNotExist()` (since #1561 both of Rename's and Edit's labels are checked, since this case is promoted and Edit is the label that would otherwise show) and Channel info / Install memory plugin each `assertIsDisplayed()` (they survive the gate). [#883](../../specs/architecture/883-retire-literal-screen.md) dropped this test's former positive assertion of "Show the literal screen" surviving the gate, along with the item itself. The two render-order tests above exercise the defaulted `mutationsSupported = true` path unchanged — no edit needed to prove the supported case.
- **`tapping_<item>_dismisses_then_dispatches_event`** (one per item, eight total since #1561 — five for the common items, plus `_save_as_channel`, the no-event variant `_install_memory_plugin`, and `tapping_edit_in_a_channel_dismisses_then_dispatches_edit_channel`) — uses the **combined-log recorder pattern**: a single `val log = mutableListOf<String>()` with `onDismiss = { log.add("dismiss") }` and `onEvent = { log.add("event:$it") }`. After `performClick()`, asserts `log == listOf("dismiss", "event:NewSession")` (or the corresponding event). The combined-log assertion is the directly-testable expression of "dismisses the dropdown *before* firing its `ThreadEvent`" — two separate recorders (a counter + a list) couldn't pin the ordering inside one `onClick` callback. Four of the five common-item tests still pass `isPromoted = true`; **Rename's own now passes `isPromoted = false`** (#1561 — Rename only renders on a discussion, so its tap test moved there), and the new Edit test passes `isPromoted = true` and asserts `log == listOf("dismiss", "event:EditChannel")`. The save-as-channel test passes `isPromoted = false`.
- **`tapping_install_memory_plugin_dismisses_and_opens_docs_url`** ([#204](../codebase/204.md)) — uses the same combined-log shape for `onDismiss`, but injects a fake `UriHandler` via `CompositionLocalProvider(LocalUriHandler provides fakeHandler) { ThreadOverflowMenu(...) }` to capture the URL. Asserts `log == listOf("dismiss")` (proves both dismiss-fired *and* no-event-fired in one equality) and `fakeHandler.openedUris == listOf(MEMORY_PLUGIN_DOCS_URL)`. The fake is a six-line file-local `class RecordingUriHandler : UriHandler { val openedUris = mutableListOf<String>(); override fun openUri(uri: String) { openedUris += uri } }` — same direct-test-double idiom as `RecordingRepo` (see [Lessons learned in #204](../codebase/204.md#lessons-learned) for the `CompositionLocalProvider`-inside-the-theme placement rule).

### `ThreadViewModelTest.kt` additions (unit, `./gradlew test`)

Two new `@Test` methods (L497-530 in the file post-#251). Both re-use the post-[#253](../codebase/253.md) `TestScope.makeVm(handle, repository, source, prefs)` receiver helper and a new private `RecordingRepo : ConversationRepository` test double.

- **`onOverflowEvent_archive_callsRepositoryArchiveOnceWithCurrentConversationId`** — constructs VM with `RecordingRepo` and `SavedStateHandle("conversationId" to "seed-channel-personal")`; subscribes to drive the `WhileSubscribed` flow; calls `vm.onOverflowEvent(ThreadEvent.Archive)`; asserts `repo.archiveCalls == listOf("seed-channel-personal")`. **Renamed + expanded in [#227](../codebase/227.md)** → `onOverflowEvent_archive_archivesClosesSheetAndPopsBack`: now also asserts `channelInfoOpen` ends `false` and exactly one `ThreadNavigation.PopBack` is collected. #227 added the `delete` family of VM tests (delete-tap, delete-cancel, delete-confirm) + a one-shot-nav test alongside it — see [`thread-screen.md`'s Testing section](thread-screen-testing.md#testing) and [the per-ticket notes](../codebase/227.md).
- **`onOverflowEvent_otherCases_doNotCallArchive`** — same setup; calls `onOverflowEvent` with each of the non-`Archive` cases; asserts `repo.archiveCalls.isEmpty()`. Iteration list shrank post-[#141](../codebase/141.md) (which made `Rename` mutate state). **Not extended** in [#204](../codebase/204.md) to include `SaveAsChannel` — `SaveAsChannel` is a `Unit` arm and trivially satisfies `archiveCalls.isEmpty()`; adding it would not catch a future bug `SaveAsChannel → repository.archive(...)` doesn't already catch.

**Why `RecordingRepo`, not `FakeConversationRepository`?** Direct call-shape assertion (`repo.archiveCalls == listOf(id)`) is one indirection less coupled than projecting the archive's effect through `observeConversations` and asserting on the resulting set. See [the per-ticket Lessons learned note](../codebase/251.md#lessons-learned) for the full rationale and the `flowOf(emptyList())` / `flowOf(null)` non-emitting-stub gotcha.

`ThreadOverflowMenuTest`, `ThreadScreenOverflowTest` and `ThreadFrameTest` cover button semantics,
row order/gates, current-report replacement, routing, live anchor movement, outside dismissal without
tap-through, Back, focus preservation and menu exclusivity. `TaskCountPillKeyboardDeviceTest` covers
real IME preservation and compact last-row scrolling: semantic `assertIsDisplayed` can pass while the
keyboard covers a row, so the device test checks physical bounds above the IME and pointer activation.
Capture helpers use `openHeaderMenu` and await Channel info; same-window menus never add a popup root.

## Edge cases / limitations

- **Mounted in production since [#252](../codebase/252.md); `Rename` wired in [#141](../codebase/141.md); context-aware items added in [#204](../codebase/204.md); `SaveAsChannel` wired in [#142](../codebase/142.md).** Tapping the `MoreVert` overflow icon opens the menu; tapping an item closes the menu and dispatches the corresponding `ThreadEvent` through `ThreadViewModel.onOverflowEvent` (or, for **Install memory plugin**, fires `LocalUriHandler.openUri(MEMORY_PLUGIN_DOCS_URL)` directly). **The original menu actions gained observable effects by [#540](../codebase/540.md)**: `Archive` closes any open [`ChannelInfoSheet`](channel-info-sheet.md) and archives the conversation via its own `sendArchive()` ([#556](../codebase/556.md)): on success it pops back to the channel list (close-and-pop since [#227](../codebase/227.md); it was archive-in-place through #252) and the conversation leaves the main list list-driven, via `observeConversations` re-emitting — no explicit removal call; on a caught `RelayErrorException` / `IllegalStateException` it surfaces a fixed-string snackbar (`archive_failed`) and stays on the thread instead of popping, `Rename` (a discussion only, since [#1561](https://github.com/pyrycode/pyrycode-mobile/issues/1561)) opens the [`RenameDialog`](rename-dialog.md) (whose Save tap routes through `RenameSubmit(name)` → `repository.rename(id, name)`), `EditChannel` (a channel only, #1561, in Rename's slot) opens [`EditChannelModal`](mobile-modal-callers.md#callers) off a shared [`ChannelEditorController`](channel-list-viewmodel.md#channeleditorcontroller-667--1561) — see [Thread screen — the sheets § EditChannelModal hosting](thread-screen-how-it-works-sheets.md#editchannelmodal-hosting-post-1561) — `SaveAsChannel` opens the [`SaveAsChannelDialog`](save-as-channel-dialog.md) (whose Save tap routes through `SaveAsChannelSubmit(name, workspace)` → `repository.promote(id, name, resolveWorkspace(name, workspace))`), `ChannelInfo` opens the [`ChannelInfoSheet`](channel-info-sheet.md) (host wired in [#226](../codebase/226.md)), `ChangeWorkspace` opens the [`WorkspacePicker`](workspace-picker.md) (since [#208](../codebase/208.md); whose pick routes through `onWorkspacePicked(path)` → `sendChangeWorkspace(path)` → `repository.changeWorkspace(id, path)`, success list-driven via the chip re-labelling, failure a fixed-string snackbar `change_workspace_failed` since [#561](../codebase/561.md)), `NewSession` sends the fire-and-forget `new_session` frame via `repository.startNewSession(id)` and surfaces a not-connected snackbar on failure (since [#540](../codebase/540.md); see § ViewModel dispatcher), and **Install memory plugin** opens the docs URL. No item is a no-op `Unit` arm any longer. (The `Delete` / `DeleteConfirm` / `DeleteDismiss` events added in [#227](../codebase/227.md) are dispatched from the [`ChannelInfoSheet`](channel-info-sheet.md), not from this menu.)
- **Install requires two conditions.** Discussions never get the install item; channels get it only for aggregate `Absent` with no installed provider. Unknown, omitted, unavailable, enabled and disabled reports suppress it.
- **Single sink — sealed dispatch is the dominant event shape this composable speaks.** A future menu item with a payload (e.g. a context-sensitive "Move to channel X" with an id) migrates that case to `data class`; the sink stays `(ThreadEvent) -> Unit`. Background tasks is another UI-local exception: `onBackgroundTasks()` opens the screen-owned panel without a VM event. The install-memory-plugin item also bypasses the sink — its side effect is a UI-layer `LocalUriHandler.openUri(...)` call with no VM authority, so it doesn't route through `onEvent`.
- **Text-only Actions rows.** Shared bodySmall typography, 12/6dp row insets, 2dp column inset and 6dp corners follow Options overlay `533:1958` by Juhana’s #1666 decision. No selected indicator or subset caption is drawn.
- **No dividers or section headers.** Archive has no destructive emphasis; Delete remains in Channel info behind confirmation.
- **No case lands on a no-op `Unit` arm as of [#540](../codebase/540.md)** (was one post-#208, after `ChangeWorkspace` routed to the picker; `NewSession` was the last holdout, wired in [#540](../codebase/540.md)). The dismiss-before-handler ordering was already correctness-neutral for a no-op event and remains so now that every event drives a real side effect.
- **`Install memory plugin` may throw `ActivityNotFoundException` on devices with no browser.** `LocalUriHandler.openUri(...)` delegates to a platform `Intent.ACTION_VIEW`; without a handler the platform throws. The implementation does not try/catch — same posture as [`SessionBoundaryDelimiter`](session-boundary-delimiter.md)'s identical `openUri(MEMORY_PLUGIN_DOCS_URL)` call. If the no-browser failure mode is observed in practice, a single fix can cover both call sites; the discriminator (no observed failure yet) per the project's [evidence-based fix selection](../../../CLAUDE.md) principle means no defensive code today.

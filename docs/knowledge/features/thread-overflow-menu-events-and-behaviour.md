# ThreadOverflowMenu — events and behaviour

Split out of [ThreadOverflowMenu](thread-overflow-menu.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [ThreadOverflowMenu](thread-overflow-menu.md); see that document for what it does, its edge cases and its links.

## ThreadEvent

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent
    data class RenameSubmit(val name: String) : ThreadEvent       // added in #141
    data object RenameDismiss : ThreadEvent                       // added in #141
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object Delete : ThreadEvent                              // added in #227
    data object DeleteConfirm : ThreadEvent                       // added in #227
    data object DeleteDismiss : ThreadEvent                       // added in #227
    data object ChannelInfo : ThreadEvent
    data object ChannelInfoDismiss : ThreadEvent                  // added in #226
    data object SaveAsChannel : ThreadEvent                       // added in #204
    data class SaveAsChannelSubmit(                                // added in #142
        val name: String,
        val workspace: WorkspaceChoice,
    ) : ThreadEvent
    data object SaveAsChannelDismiss : ThreadEvent                 // added in #142
}

enum class WorkspaceChoice { DEDICATED, SCRATCH }                  // top-level, added in #142

sealed interface ThreadNavigation {                               // top-level, added in #227
    data object PopBack : ThreadNavigation
}
```

Co-located with `ThreadViewModel` at the top of `ThreadViewModel.kt` (above the `ThreadUiState` data class), not in its own file — the dispatcher (`onOverflowEvent`) is the only consumer in this slice. Placement follows the consumer; the screen-level precedent `DiscussionListEvent` / `ChannelListEvent` declare next to the screen file but that did not fit because in this slice the screen does not yet consume the sealed surface.

- **Mixed `data object` and `data class` since [#141](../codebase/141.md); two `data class` cases since [#142](../codebase/142.md).** Twelve `data object` cases (menu-item taps and dialog/sheet-dismiss/confirm cases, parameterless — `NewSession`, `Rename`, `RenameDismiss`, `ChangeWorkspace`, `Archive`, `Delete` / `DeleteConfirm` / `DeleteDismiss` (added in [#227](../codebase/227.md)), `ChannelInfo`, `ChannelInfoDismiss` (added in [#226](../codebase/226.md)), `SaveAsChannel`, `SaveAsChannelDismiss`) and two `data class` cases (`RenameSubmit(val name: String)` from [#141](../codebase/141.md), and `SaveAsChannelSubmit(val name: String, val workspace: WorkspaceChoice)` from [#142](../codebase/142.md) — the dialog-Save dispatches carrying the user-trimmed new name plus, for save-as-channel, the radio-selected workspace choice). The [#251](../codebase/251.md) reservation for "a future menu item with a payload migrates that case to `data class`" cashed in at #141; #142 followed the same shape, with each dialog family staying contiguous in the sealed surface (`Rename, RenameSubmit, RenameDismiss`; `SaveAsChannel, SaveAsChannelSubmit, SaveAsChannelDismiss`; `Delete, DeleteConfirm, DeleteDismiss`), parameter-bearing case in the middle when present. **`Delete`'s confirm is `data object DeleteConfirm`, not `*Submit`** ([#227](../codebase/227.md)): the `*Submit` cases carry validated form input, whereas an irreversible delete has no payload — `Confirm`/`Dismiss` reads correctly for a no-input confirmation (see [the per-ticket rationale](../codebase/227.md#patterns-established)).
- **`ThreadNavigation` is a separate top-level sealed interface, co-located in `ThreadViewModel.kt`** ([#227](../codebase/227.md)). Single-member `data object PopBack`, exposed off the VM as a one-shot `navigationEvents: Flow<ThreadNavigation>` (`Channel(BUFFERED).receiveAsFlow()`) and collected in `MainActivity` — **not** part of `ThreadEvent` (that surface is UI→VM events; `ThreadNavigation` is VM→NavHost effects). Mirrors `ChannelListNavigation`. See [`ThreadScreen`](thread-screen-how-it-works-sheets.md#channelinfosheet-archivedelete--pop-back-nav-post-227) for the collection site.
- **`WorkspaceChoice` is a top-level `enum class` co-located in `ThreadViewModel.kt`** ([#142](../codebase/142.md)). Two variants: `DEDICATED` (move to `~/pyry-workspace/channels/<slug>/`) and `SCRATCH` (preserve the discussion's existing scratch cwd). Top-level placement (not nested inside `ThreadEvent` or `SaveAsChannelDialogState`) because two callers from different packages consume it — the dialog (in `ui/conversations/components/`) imports the enum, and the VM/event (in `ui/conversations/thread/`) defines it. The one cross-package import is the price of putting the enum where it semantically belongs (a `ThreadEvent` payload type).
- **No `InstallMemoryPlugin` event** despite the channel-only **Install memory plugin** item ([#204](../codebase/204.md)). The item's only side effect is `LocalUriHandler.current.openUri(MEMORY_PLUGIN_DOCS_URL)`, which is a UI-layer concern with no VM state mutation; routing through `ThreadEvent` would add a hop with no purpose. The discriminator versus `SaveAsChannel` (which *does* exist as an event, and gained the Submit/Dismiss companions at [#142](../codebase/142.md)) is the VM authority: the promote-dialog flow needs the VM to own the dialog flag and the `repository.promote(id, name, workspace)` launcher; the docs-URL handler does not.
- **`data object` semantics: `toString` is the load-bearing auto-generation.** The Compose-test combined-log assertion `log == listOf("dismiss", "event:NewSession")` reads cleanly because `data object NewSession.toString() == "NewSession"`; plain `object` would produce `"NewSession@<hashcode>"`.
- **Conversation id is sourced from `state.value.conversationId` at dispatch time** inside `onOverflowEvent`, not embedded on the events — same one-grep convention as `sendMessage`. The two payload-bearing cases carry their input from the UI (`RenameSubmit.name`, `SaveAsChannelSubmit.name` + `.workspace`); the dialog is the trim/selection authority for both, so the field text and radio state are local to the dialog composable and arrive at the VM already validated.
- **Scope is overflow-only.** Existing plain handler methods on `ThreadViewModel` (`sendMessage`, `retry`, `onWorkspaceChipTapped`, `onWorkspacePicked`, `onWorkspacePickerDismissed`, `onModelSelected`) are **not** migrated to `ThreadEvent` in this slice. Option (ii) from the convention question on [#203](https://github.com/pyrycode/pyrycode-mobile/issues/203); option (iii) — migrate all six alongside the overflow work — was rejected as oversized.

## What it does

Renders one M3 `DropdownMenu` containing the five common `DropdownMenuItem`s, wrapped by two mutually-exclusive context-aware items ([#204](../codebase/204.md)) and preceded by the unconditional **Show the literal screen** item ([#382](../codebase/382.md)). Each item's `onClick` calls `onDismiss()` **before** its action — `onEvent(ThreadEvent.X)`, or `uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL)` (install-memory-plugin), or `onShowLiteralScreen()` (show-literal-screen):

| Order             | When                | String resource                            | Label              | Side effect                                                |
| ----------------- | ------------------- | ------------------------------------------ | ------------------ | ---------------------------------------------------------- |
| 1 (always)        | —                   | `R.string.thread_overflow_show_literal_screen` | Show the literal screen | `onShowLiteralScreen()` (no event — pure navigation, #382) |
| 2 (discussion)    | `!isPromoted`       | `R.string.save_as_channel_action`          | Save as channel…   | `onEvent(ThreadEvent.SaveAsChannel)`                       |
| 3 (mutations)     | `mutationsSupported` | `R.string.thread_overflow_new_session`     | New session        | `onEvent(ThreadEvent.NewSession)`                          |
| 4 (mutations)     | `mutationsSupported` | `R.string.thread_overflow_rename`          | Rename             | `onEvent(ThreadEvent.Rename)`                              |
| 5 (mutations)     | `mutationsSupported` | `R.string.thread_overflow_change_workspace`| Change workspace…  | `onEvent(ThreadEvent.ChangeWorkspace)`                     |
| 6 (mutations)     | `mutationsSupported` | `R.string.thread_overflow_archive`         | Archive            | `onEvent(ThreadEvent.Archive)`                             |
| 7 (always)        | —                   | `R.string.thread_overflow_channel_info`    | Channel info       | `onEvent(ThreadEvent.ChannelInfo)`                         |
| 8 (channel)       | `isPromoted`        | `R.string.thread_overflow_install_memory_plugin` | Install memory plugin | `uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL)` (no event)    |

Since [#508](../codebase/508.md) the **four mutation items — New session, Rename, Change workspace…, Archive (orders 3–6) — are wrapped in a single `if (mutationsSupported) { … }` block** because they are contiguous. In relay mode (`mutationsSupported == false`) each throws or is a misleading no-op, so all four are hidden (AC#1); the non-mutating items (Show literal screen, Save as channel… — `promote` is implemented on the remote, Channel info, Install memory plugin) survive the gate. `mutationsSupported` is `true` end-to-end in fake mode (the default binding), so this changes nothing there. See the sibling gate on [`ChannelInfoSheet`](channel-info-sheet.md)'s Actions section, resolved as **hide** on both surfaces in #508.

Final orders, after the conditional branches collapse (fake mode / `mutationsSupported == true`):

- **Discussion (`isPromoted == false`):** Show the literal screen → Save as channel… → New session → Rename → Change workspace… → Archive → Channel info. Seven items, no install-memory-plugin.
- **Channel (`isPromoted == true`):** Show the literal screen → New session → Rename → Change workspace… → Archive → Channel info → Install memory plugin. Seven items, no save-as-channel.

In **relay mode (`mutationsSupported == false`)** the four mutation items drop out: a discussion shows Show the literal screen → Save as channel… → Channel info (three items); a channel shows Show the literal screen → Channel info → Install memory plugin (three items).

The **Show the literal screen** item is an unconditional `DropdownMenuItem` at the **top** of the `DropdownMenu` body, before the conditionals; the two context-aware items render as two `if` blocks below it — `if (!isPromoted) { ... }` prepended; `if (isPromoted) { ... }` appended. A `when (isPromoted)` over the entire menu body was considered and rejected — it would either duplicate the five common items in both branches or collapse to the same `if` pair around two extra items, and the `if`-pair shape directly expresses the AC wording ([per-ticket rationale](../codebase/204.md#patterns-established)).

**`save_as_channel_action` is reused, not duplicated** ([#204](../codebase/204.md)). The same `strings.xml:11` key serves [`DiscussionListScreen`](discussion-list-screen.md)'s long-press promote menu ([#25](../codebase/25.md)) and this menu's first item — same English copy, same semantic action, one localization entry.

**`MEMORY_PLUGIN_DOCS_URL` is reused too.** The `internal const val` at `SessionBoundaryDelimiter.kt:37` (introduced in [#135](../codebase/135.md) for the empty-thread install affordance) is imported via `de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL`. The constant's home stays at the boundary delimiter; a parallel `private const val` in `ThreadOverflowMenu.kt` was considered and rejected — the Phase 3+ swap-to-real-install-endpoint becomes a one-grep edit by keeping a single source of truth.

Dismiss-before-handler ordering is load-bearing for two reasons:

1. The Compose-test combined-log assertion (`log == listOf("dismiss", "event:NewSession")`) pins it explicitly — see [Tests](thread-overflow-menu-wiring-tests-and-edge-cases.md#tests).
2. Downstream host wiring opens dialogs / sheets / nav transitions from these events; the menu must be closed before the new surface mounts to avoid Compose layout layering issues. Inverting the order ("emit the event, let the host close the menu") would push close-coordination into every event handler.

`DropdownMenu`'s built-in `onDismissRequest = onDismiss` covers the outside-tap / back-press / scrim-tap paths; explicit item taps are the only path that calls dismiss themselves.

Same dismiss-before-handler pattern as `DiscussionListScreen.kt:209-212` (`onClick = { menuExpanded = false; onSaveAsChannel() }` from [#25](../codebase/25.md)) — that's the only other production `DropdownMenu` call site in the codebase.

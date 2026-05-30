# 208 — Wire "Change workspace…" overflow item to the Workspace Picker

**Ticket:** [#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) — `feat(ui/thread)` · Size **XS** · split from #143

## Context

On the conversation thread, the overflow menu already shows a **Change workspace…** item (`ThreadEvent.ChangeWorkspace`, added by #204). The item is currently inert: in `ThreadViewModel.onOverflowEvent` it falls into the no-op `when` arm.

Everything else is already in place. #137 wired the `WorkspacePicker` host (delivered by #220) onto the thread surface for the **workspace chip** tap, and with it:

- `ThreadUiState.workspacePickerVisible: Boolean`
- `pendingWorkspacePicker: MutableStateFlow<Boolean>` + its arm in the top-level `state` `combine`
- `WorkspacePicker(visible = …, onPicked = …, onDismiss = …)` rendered in `ThreadScreen`
- `onWorkspacePicked(path)` → `repository.changeWorkspace(...)` (covered by tests)
- `onWorkspacePickerDismissed()` → clears the flag (covered by tests)

The only remaining work is to route `ThreadEvent.ChangeWorkspace` into `pendingWorkspacePicker.value = true`, exactly as `onWorkspaceChipTapped()` already does. The overflow item then opens the same picker the chip opens. No new state, no new flow, no new picker call, no new handler.

> **Two triggers, one arm.** `ThreadEvent.ChangeWorkspace` is fired from **two** UI sites today, both currently no-op: the overflow menu item (`ThreadOverflowMenu.kt:50-56`) and the Channel Info sheet's "Change workspace" row (`ThreadScreen.kt:269-272`, which fires `ChangeWorkspace` then `ChannelInfoDismiss`). The single new `when` arm serves both — after this change, both entry points open the picker. This is intended (no extra work), but call it out so code-review isn't surprised that the Channel Info row also "lights up."

> **Reality vs. ticket AC — read this.** The ticket AC describes the surviving no-op arm as `NewSession, ChannelInfo -> Unit`. That is stale: on current `main`, `ThreadEvent.ChannelInfo` already has its own arm (`ThreadEvent.ChannelInfo -> pendingChannelInfo.value = true`). The actual no-op arm today is `NewSession, ChangeWorkspace -> Unit` (two cases only). After this change the surviving no-op arm is **`ThreadEvent.NewSession -> Unit`** (one case). The routing intent is identical to the AC; only the residual case list differs. Do not re-add a `ChannelInfo` no-op branch.

## Design source

**Figma (picker sheet):** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-2
**Figma (overflow menu, trigger location):** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Node `20:2` ("Workspace Picker Sheet") is an M3 modal bottom sheet: a drag handle, a "Choose workspace" title (`title-large`) with a trailing close (×) icon, a "Recent" section listing monospace workspace paths each with a "last used …" subtitle (and a `secondary-container` "default" chip on the scratch entry), then an "Other" section whose single row is "Create new folder under pyry-workspace". Node `16:8` is the thread screen whose overflow (`more_vert`) menu hosts the **Change workspace…** item. **This ticket renders no new UI.** The picker is the existing #220 component (`WorkspacePicker` → `WorkspacePickerSheet` → `CreateFolderDialog`) and the menu item is the existing #204 item; both were design-reviewed when they landed. This change only connects the existing item to the existing picker via the ViewModel — there is nothing new to lay out, style, or token. The visual-fidelity check is therefore covered upstream by #220 (picker) and #204 (menu item).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:239-286` — `onOverflowEvent(event)` `when`. **Edit site:** the no-op arm at **lines 282-284** (`ThreadEvent.NewSession, ThreadEvent.ChangeWorkspace, -> Unit`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:212-214` — `onWorkspaceChipTapped()`; its one-line body (`pendingWorkspacePicker.value = true`) is exactly what the new arm does. Mirror it; do **not** introduce a parallel flow.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:104,151-190` — `pendingWorkspacePicker` declaration and its arm inside the top-level `state` `combine` (it maps to `ThreadUiState.workspacePickerVisible`). Confirms the flag already flows to the UI; no wiring needed.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:751-766` — `onOverflowEvent_otherCases_doNotCallArchive`. **Edit:** delete the `vm.onOverflowEvent(ThreadEvent.ChangeWorkspace)` line (line 761); keep `NewSession`. Intent ("non-archive events don't call archive") still holds.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:768-783` — `onOverflowEvent_channelInfo_opensSheetWithoutArchiving`. This is the **shape to copy** for the new test: `RecordingRepo`, launch a `state` collector, `advanceUntilIdle()`, emit the event, assert a `state.value` flag.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1005-1076` — `RecordingRepo` fake. Use it for the new test. Note `changeWorkspace` is `TODO("not used")`; that is fine — `ChangeWorkspace` only flips a flag and makes **no** repository call.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:573-625` — existing `onWorkspacePicked…` / `onWorkspacePickerDismissed…` tests. Do **not** duplicate these; the picked/dismiss handlers are already covered.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — read to **confirm** the `WorkspacePicker(visible = state.workspacePickerVisible, onPicked = …, onDismiss = …)` render call already exists. **Do not modify this file.** (Not re-read during this spec run; the ticket AC and the ViewModel both confirm the render call is present.)
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt` — the existing #220 picker host. Reused as-is; **do not modify.**

## Design

Single edit in `ThreadViewModel.onOverflowEvent` — split `ChangeWorkspace` out of the no-op arm into its own arm. Contract sketch (not full bodies):

```kotlin
// new arm — mirrors onWorkspaceChipTapped()
ThreadEvent.ChangeWorkspace -> pendingWorkspacePicker.value = true

// surviving no-op arm (ChannelInfo already has its own arm elsewhere in the when)
ThreadEvent.NewSession -> Unit
```

That is the entire production change (~2-3 lines net). No edits to `ThreadUiState`, the `state` `combine`, `ThreadScreen`, `WorkspacePicker`, or any handler method.

## State + concurrency model

No change. `pendingWorkspacePicker` is an already-existing `MutableStateFlow<Boolean>` combined into the single `StateFlow<ThreadUiState>` exposed by the ViewModel. Setting `.value = true` is a synchronous, idempotent flag flip on the Main thread inside `onOverflowEvent`; the existing `combine` re-emits `ThreadUiState(workspacePickerVisible = true)` and the already-rendered `WorkspacePicker` recomposes visible. No new `viewModelScope.launch`, no dispatcher, no coroutine. Single source of state preserved.

## Error handling

None applicable — the change is a state-flag flip with no I/O. The downstream failure surface (the actual `repository.changeWorkspace` call on pick) is unchanged and already owned by `onWorkspacePicked` (#137). Dismissal path unchanged.

## Testing strategy

Unit only (`./gradlew test`); no instrumented test (no UI change).

1. **New** `ThreadViewModelTest.onOverflowEvent_changeWorkspace_opensWorkspacePicker` — copy the `onOverflowEvent_channelInfo_opensSheetWithoutArchiving` shape:
   - Build the VM with `RecordingRepo` and a `SavedStateHandle` carrying `"seed-channel-personal"`.
   - `launch { vm.state.collect {} }`, then `advanceUntilIdle()` (the flag only surfaces in `state` while subscribed — `WhileSubscribed`).
   - `vm.onOverflowEvent(ThreadEvent.ChangeWorkspace)`, then `advanceUntilIdle()`.
   - Assert `vm.state.value.workspacePickerVisible` is `true`. (Optionally also assert `repo.archiveCalls.isEmpty()` to match sibling tests; not required.)
   - Cancel the collector.
2. **Edit** `onOverflowEvent_otherCases_doNotCallArchive` — remove the `ChangeWorkspace` emission (line 761), leaving `NewSession`. Assertion (`repo.archiveCalls.isEmpty()`) unchanged.
3. **Do not** add or duplicate picked/dismiss tests — already covered.

## Open questions

None. Mechanically unambiguous; the only subtlety is the stale-AC residual-arm note in Context (surviving no-op is `NewSession -> Unit`, not `NewSession, ChannelInfo -> Unit`).

# WorkspaceChip

Stateless M3 `AssistChip` (#137) rendered at the top of a [`ThreadScreen`](thread-screen.md) body when the conversation is a **fresh, unpromoted discussion with zero messages**. Surfaces the chosen workspace as a basename label and routes tap → the existing [`WorkspacePicker`](workspace-picker.md) host. Disappears once the first real `ThreadItem.MessageItem` lands; design doc recommended this over a read-only downgrade for cleanliness.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `WorkspaceChip.kt`. Sibling to [`WorkspacePicker`](workspace-picker.md) — the chip is one of three planned trigger points for the picker host (the other two: [Channel List](channel-list-screen.md) FAB long-press from #221, [Thread screen](thread-screen.md) overflow "Change workspace…" from #208).

## Shape

```kotlin
@Composable
fun WorkspaceChip(
    workspaceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- **`public` (no `internal`).** Consumed from the sibling `ui/conversations/thread/` package.
- **`workspaceLabel: String` is the already-derived display text**, not the raw `cwd`. The ViewModel resolves `(Conversation.cwd, Conversation.workspaceLabel)` → display text at the flow boundary via the shared [`workspaceDisplayName`](#workspacelabel-derivation) rule; composables never see the raw path or the raw nullable label.
- **`onClick: () -> Unit`.** The whole chip surface is the click target; the consumer wires this to the ViewModel's `onWorkspaceChipTapped` handler, which flips `pendingWorkspacePicker` to `true`.
- **`modifier: Modifier = Modifier`.** Forwarded directly to the underlying `AssistChip`. The caller (`ThreadScreen`) is responsible for the surrounding `Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)` row.

## What it renders

An M3 `AssistChip` with a leading `Icons.Outlined.Folder` icon and label `"Workspace: $workspaceLabel (change)"`. No styled spans — the `(change)` parenthetical is plain text, hinting that the surface is tappable. No new colors, no theme additions; `AssistChip` defaults carry the correct surface/outline tokens.

Since [`#722`](https://github.com/pyrycode/pyrycode-mobile/issues/722), the label `Text` carries `maxLines = 1` and `overflow = TextOverflow.Ellipsis`, so a maximum-length label (daemon-authored, bounded to `MAX_WORKSPACE_LABEL_CHARS`) truncates on one line instead of growing the chip's height or clipping. The sink stays a plain `Text` — never `MarkdownText`, never a WebView — so a markup-looking label renders as literal characters.

`leadingIcon` uses `contentDescription = null` — the label `Text` already conveys the affordance and `AssistChip` announces `Role.Button` with the full label string. A future a11y audit may add an `onClickLabel = "Change workspace"` on the chip's clickable; until then the default ("Activate") + label readback is sufficient.

## `workspaceLabel` derivation

Since [`#722`](https://github.com/pyrycode/pyrycode-mobile/issues/722), derived by the shared, non-`Conversation`-receiver function `de.pyryco.mobile.ui.workspace.workspaceDisplayName(cwd: String, label: String?): String` (`app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt`), called from `ThreadViewModel`'s `combine` lambda as `workspaceDisplayName(cwd = conv?.cwd ?: "", label = conv?.workspaceLabel)`:

```kotlin
fun workspaceDisplayName(cwd: String, label: String?): String {
    val chosen = label?.takeIf { it.isNotBlank() }
    if (chosen != null) return chosen.take(MAX_WORKSPACE_LABEL_CHARS)
    return if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) {
        "scratch"
    } else {
        cwd.substringAfterLast('/').ifEmpty { cwd }
    }
}
```

This replaced the file-private `Conversation.workspaceLabel()` extension that lived in `ThreadViewModel.kt` from [`#137`](../codebase/137.md) through #722 — the same two cwd-derived arms, moved unchanged, now reachable from [#641](https://github.com/pyrycode/pyrycode-mobile/issues/641)'s tree rows and [#723](https://github.com/pyrycode/pyrycode-mobile/issues/723)'s Settings subtitle instead of being private to the thread. It takes two scalars rather than a `Conversation` precisely so a caller with no conversation in hand (Settings' bare `defaultWorkspace: String` preference) can still call it. `thread-screen-how-it-works-state.md` records why the old extension and the domain's nullable `Conversation.workspaceLabel: String?` property (landed by [#720](https://github.com/pyrycode/pyrycode-mobile/issues/720)) were a parens-only, easy-to-confuse pair; `workspaceDisplayName` shares no identifier with either.

**Rule, in order:**

1. A non-blank `label` wins, **unconditionally** — including over the scratch sentinel, since the protocol keys a label by workspace and scratch is nameable. The label is opaque daemon-authored text: rendered **verbatim**, never trimmed, case-folded, escaped or otherwise rewritten, except for the length clamp below.
2. Else an empty `cwd` or the `DEFAULT_SCRATCH_CWD = "~/.pyrycode/scratch"` sentinel → `"scratch"`.
3. Else `cwd.substringAfterLast('/')`, or the whole `cwd` when that segment is empty (the degenerate trailing-slash case, `"foo/"` → `"foo/"`, preserved unchanged — fresh-discussion paths never have trailing slashes, since `createWorkspaceFolder` shapes them as `"pyry-workspace/$name"`).

Arms 2 and 3 are intentionally identical to [`FakeConversationRepository`](conversation-repository.md)'s `bumpWorkspace` no-bound-workspace check at the data layer.

**Length clamp — the render-path safety control for #720's opaque label.** `MAX_WORKSPACE_LABEL_CHARS = 128` (same file, `internal const val`) bounds a chosen label with `.take(128)` before it reaches Compose text layout. The wire protocol's daemon-side 128-byte bound is documented as a size limit and **not** a safety property, so an in-session hostile or buggy daemon can send an arbitrarily long label; without a client-side bound, `Text`'s `maxLines = 1` would still force Compose to measure the whole paragraph on the UI thread — an ANR vector. 128 UTF-16 units is an upper bound on 128 UTF-8 bytes, so the clamp never truncates a protocol-conformant label and only fires on a non-conformant one. This is the single shared boundary: `ThreadUiState.workspaceLabel` and every other future consumer of `workspaceDisplayName` receive bounded text only, so no per-consumer clamp is needed.

Behaviour summary (unlabelled rows asserted by `ThreadViewModelTest`'s #137-era tests and `WorkspaceDisplayNameTest`; labelled rows new in #722, asserted by `WorkspaceDisplayNameTest`):

| `cwd` | `label` | Display text |
| --- | --- | --- |
| `""` | `null` | `"scratch"` |
| `"~/.pyrycode/scratch"` (= `DEFAULT_SCRATCH_CWD`) | `null` | `"scratch"` |
| `"pyry-workspace/my-app"` | `null` | `"my-app"` |
| `"~/Workspace/Projects/X"` | `null` | `"X"` |
| `"X"` (no slash) | `null` | `"X"` |
| any | `null`, `""`, or all-whitespace | falls through to the `cwd`-derived rows above |
| `"~/.pyrycode/scratch"` | `"Design system"` | `"Design system"` — arm 1 is unconditional, even over scratch |
| any | a label over 128 chars | that label's first 128 characters |

The label is a `String` field on [`ThreadUiState`](thread-screen.md), defaulted to `"scratch"` so the initial-value (pre-emission) frame paints the chip with the safe sentinel. `ThreadUiState.workspaceLabel` is display text only: the raw `cwd` continues to reach `ChannelInfoSheet` through the untouched `ThreadUiState.workspacePath`, and the label is never logged or used as an identity/path.

## Visibility gate

The chip's render condition is **inlined at the call site** in `ThreadScreen.kt`, not folded into a hidden boolean on `ThreadUiState`:

```kotlin
if (!state.isPromoted && !state.hasMessages) {
    WorkspaceChip(
        workspaceLabel = state.workspaceLabel,
        onClick = onWorkspaceChipTapped,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
```

Two reasons for keeping the `&&` at the call site: (a) the predicate is the trivial conjunction of two raw fields, no extraction overhead; (b) `isPromoted` and `hasMessages` are useful raw signals for siblings to re-read without having to derive from a hidden combined boolean — [`EmptyThreadState`](empty-thread-state.md) (#138, landed) gates on `!hasMessages` alone, and the imminent [`#208`](https://github.com/pyrycode/pyrycode-mobile/issues/208) overflow entry point will read the same signals independently.

`hasMessages` counts only `ThreadItem.MessageItem` — a `ThreadItem.SessionBoundary` that the chip itself triggers (via `changeWorkspace` → new session → boundary in the stream) does NOT flip the gate. The chip stays visible until the user actually sends a message.

## How tap routes through

`ThreadScreen` passes the chip's `onClick` to a flat `onWorkspaceChipTapped: () -> Unit` callback on its own signature, which the destination block binds to `vm::onWorkspaceChipTapped`. The VM handler is a synchronous flag flip — no coroutine, no repository call:

```kotlin
private val pendingWorkspacePicker = MutableStateFlow(false)

fun onWorkspaceChipTapped() {
    pendingWorkspacePicker.value = true
}
```

`pendingWorkspacePicker` is combined into `state` alongside `observeConversations(All)` and `observeMessages(conversationId)`, surfacing as `ThreadUiState.workspacePickerVisible`. `ThreadScreen` reads that field and renders [`WorkspacePicker`](workspace-picker.md) as a `Scaffold` sibling (the picker's `ModalBottomSheet` lives in its own window — source-order placement doesn't affect Z-order). Same wiring shape as [`ChannelListViewModel`](channel-list-viewmodel.md) / [`ChannelListScreen`](channel-list-screen.md) from #221.

On `onPicked(path)`, the VM clears the flag and calls `sendChangeWorkspace(path)` — since [#561](../codebase/561.md) a dedicated one-shot send (`RelayErrorException` + `IllegalStateException` caught, `CancellationException` rethrown first), not the shared `launchGuardedRepoCall`. On success the returned `Session` is discarded — the `Conversation.cwd` update propagates back via the `observeConversations` re-emission and `workspaceLabel` recomputes; the chip needs no explicit handling. On a caught failure, `changeWorkspaceErrorChannel` fires a payload-free `Unit` that [`ThreadScreen`](thread-screen.md) renders as a fixed-string snackbar (`change_workspace_failed`) — never the caught exception's message. On `onDismiss`, the VM clears the flag only — no repository call.

## Previews

Three `@Preview`s at the bottom of `WorkspaceChip.kt`:

- **`WorkspaceChipLightPreview`** — `PyrycodeMobileTheme(darkTheme = false) { Surface { WorkspaceChip(workspaceLabel = "scratch", onClick = {}, modifier = …) } }`. Exercises the sentinel label.
- **`WorkspaceChipDarkPreview`** — same shape with `darkTheme = true, uiMode = Configuration.UI_MODE_NIGHT_YES` and `workspaceLabel = "my-app"`. Exercises both the dark theme and the basename branch.
- **`WorkspaceChipLongLabelPreview`** (new in #722) — a `MAX_WORKSPACE_LABEL_CHARS`-length label rendered through production's own `Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)` chain, not the bare wrap-content shape the other two use. Demonstrates AC4: the label's `Text` now carries `maxLines = 1` and `overflow = TextOverflow.Ellipsis`, so a maximum-length label truncates on one line instead of growing the chip's height or clipping at its trailing edge. The other two previews wrap content and would not show this — Compose layout needs a device or a preview render, so there is no JVM-level assertion for the truncation behaviour.

All three previews wrap in `Surface` so the chip renders against the theme's surface color rather than the bare `showBackground = true` white/black. Required by AC5 (#137) / AC4 (#722); the chip is never rendered in isolation in production — these are the regression artifact for the chip's visual appearance.

## Edge cases / limitations

- **Empty `conversationId` degenerate path is benign.** If `SavedStateHandle` is missing the nav arg, `conversationId = ""`; the fake's `observeMessages("")` returns `emptyList()` and `observeConversations` won't match. Result: `hasMessages = false`, `isPromoted = false`, `workspaceLabel = "scratch"` — the chip renders. The path is structurally unreachable in production (every nav edge passes a real id); calling `changeWorkspace("", path)` would throw in the fake (unknown id), but the chip's tap can only fire when the user is already on a real conversation's thread.
- **Picker dismiss mid-write does not cancel `changeWorkspace`.** If the user dismisses the picker (clears the flag) while a `changeWorkspace` coroutine is in-flight, the launch continues on `viewModelScope` — it's not tied to the picker's visibility. The conversation's `cwd` still updates; the chip label re-renders if still visible. Acceptable; the picker's single-invocation guarantee (see [`WorkspacePicker`](workspace-picker.md)) means each `onPicked` corresponds to exactly one in-flight write.
- **`changeWorkspace` on an empty thread creates a `SessionBoundary` immediately.** Picking a workspace before any message lands produces a [`SessionBoundary`](session-boundary-delimiter.md) in the stream right away; once the user sends a message, the thread renders `[boundary, message1]`. Since #1498 this boundary renders the same "New session — <time>" copy as any other reset rather than naming the workspace, so the earlier open question about redundant "Workspace changed to X" copy in this path no longer applies. The chip's visibility logic already handles this correctly: boundaries don't count toward `hasMessages`.
- **Chip-to-message-list spacing in the empty state is unstyled.** No spacer between the chip's padded row and the message-list slot below. Since [`#138`](../codebase/138.md) the [`EmptyThreadState`](empty-thread-state.md) prompt fills the `weight(1f)` slot whenever `!hasMessages` and provides the visual anchor below the chip; the prompt's own `padding(horizontal = 24.dp)` plus its `Alignment.Center` placement carry the spacing without an explicit spacer.
- **No `AnimatedVisibility` on the gate.** The chip pops in/out instantly when `hasMessages` flips. Consistent with the rest of the screen's compose-state transitions; revisit if a designer requests a fade.
- **`changeWorkspace` failures are handled since [#561](../codebase/561.md).** Both the request/reply server-error case (`RelayErrorException`, e.g. the daemon rejecting an out-of-`$HOME` path) and the not-connected case (`IllegalStateException`) surface a transient `change_workspace_failed` snackbar and leave the user on the thread — see § How tap routes through. `WorkspacePicker`'s own `createWorkspaceFolder` failures (a different repository call, triggered *inside* the picker sheet before a path ever reaches the chip's `onPicked`) remain unhandled; see [`WorkspacePicker`](workspace-picker.md) § Error handling.

## Related

- Ticket notes: [`../codebase/137.md`](../codebase/137.md) (this slice), [`../codebase/220.md`](../codebase/220.md) (the picker host), [`../codebase/221.md`](../codebase/221.md) (the Channel List FAB long-press — the canonical wiring pattern this slice mirrors)
- Spec: `docs/specs/architecture/137-workspace-chip-empty-new-discussion-thread.md`; label-first rule: `docs/specs/architecture/722-show-workspace-labels-in-the-conversation-thread.md`
- Shared display rule: `de.pyryco.mobile.ui.workspace.workspaceDisplayName` (`app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt`), added by [`#722`](https://github.com/pyrycode/pyrycode-mobile/issues/722) — see § `workspaceLabel` derivation. Domain source: [`Conversation.workspaceLabel: String?`](data-model.md), the nullable opaque label landed by [`#720`](https://github.com/pyrycode/pyrycode-mobile/issues/720) and kept live on the owning host's projection by [`#721`](https://github.com/pyrycode/pyrycode-mobile/issues/721). Other planned consumers: [#641](https://github.com/pyrycode/pyrycode-mobile/issues/641) (tree rows), [#723](https://github.com/pyrycode/pyrycode-mobile/issues/723) (Settings subtitle) — not yet migrated onto the shared function.
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — Conversation Thread Screen canvas frame. The rendered Figma shows a populated thread, not the empty-state variant with the chip; chip styling is anchored by the ticket's "M3 `AssistChip` with leading folder icon" instruction rather than a pixel-exact reference.
- Consumer: [Thread screen](thread-screen.md) — the chip's only call site; see also the screen's `ThreadUiState` widening for the four new fields (`isPromoted`, `hasMessages`, `workspaceLabel`, `workspacePickerVisible`) introduced by this slice.
- Host opened by the tap: [Workspace picker](workspace-picker.md). Underlying surfaces: [Workspace picker sheet](workspace-picker-sheet.md), [Create folder dialog](create-folder-dialog.md).
- Data layer touched: [`Conversation.cwd` + `DEFAULT_SCRATCH_CWD`](data-model.md), [`changeWorkspace(conversationId, workspace)`](conversation-repository.md).
- Wire + failure surface: [`#560`](../codebase/560.md) (`RemoteConversationRepository.changeWorkspace` v2 round-trip), [`#561`](../codebase/561.md) (`onWorkspacePicked` moved off the shared [`launchGuardedRepoCall`](guarded-repo-launch.md) onto `sendChangeWorkspace` + `changeWorkspaceErrors`).
- Downstream / open:
  - [`#208`](https://github.com/pyrycode/pyrycode-mobile/issues/208) — thread overflow "Change workspace…" reuses `ThreadUiState.workspacePickerVisible` + `onWorkspacePicked` / `onWorkspacePickerDismissed`. Both fields are pre-introduced by this slice; #208 adds the third trigger only.
  - [`#138`](../codebase/138.md) — empty-state copy ([`EmptyThreadState`](empty-thread-state.md)) renders **below** the chip in the same `Column`; chip owns the topmost body slot. Landed.
  - Open: suppress `SessionBoundary` emission when `changeWorkspace` is called on an empty thread. Requires either a new repository method or a behaviour change on `changeWorkspace`. Out-of-scope here.

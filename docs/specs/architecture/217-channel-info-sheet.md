# Spec: ChannelInfoSheet stateless composable (#217)

## Context

The Channel Info bottom sheet is the entry point for channel-level actions (rename, change workspace, archive, delete, install memory plugin) and the place where channel metadata (workspace path, timestamps, counts, channel ID) is surfaced. This ticket lands the **stateless composable** only — a `ChannelInfoUiModel` data class plus a `ChannelInfoSheet` composable that wraps an M3 `ModalBottomSheet` and renders the Figma-locked layout. No repository binding, no time-formatting, no entry-point integration; those land in the follow-up host ticket.

The design lives in Figma node `20:48` (locked). The sheet has four logical regions:

1. Title row (channel name + close affordance)
2. **About** section — 5 label / value rows
3. **Memory** section — 1 row with empty-state CTA or installed-plugins list
4. **Actions** section — 2×2 grid of M3 FilledTonalButton-style cells
5. Footer — low-opacity `Channel ID: <id>` line, long-press to copy via `ClipboardManager`

Same stateless / preview-first convention as the recently-landed `WorkspacePickerSheet` (#212).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48

Vertical `Column` inside an M3 `ModalBottomSheet` over `surfaceContainerLow`. Title row uses `titleLarge` for the conversation name with a trailing `Icons.Filled.Close`. Each section uses a `labelLarge` header (`onSurfaceVariant`) followed by rows of left-aligned `bodyLarge` label / right-aligned value pairs; the workspace value is rendered in `FontFamily.Monospace` 12sp `onSurfaceVariant`, all other right-side values in `bodyMedium` `onSurfaceVariant`. The Memory row's empty state is `None` text + an M3 `TextButton` with an `Add` icon and "Install" label (`primary`). Actions render as four M3 `FilledTonalButton`s in a 2×2 grid (`secondaryContainer` background, `onSecondaryContainer` content). Footer is `Roboto Mono` 11sp at `onSurfaceVariant` with `alpha(0.5f)`.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheet.kt` — same package, same M3 `ModalBottomSheet` + preview-first pattern; mirror it exactly. Note in particular: the public composable wraps the content composable, the content composable is what the preview renders (because `ModalBottomSheet` itself does not preview), and both `internal` visibility.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:7,44` — only existing call site of `Modifier.combinedClickable(onClick = ..., onLongClick = ...)` in this codebase. Use the same import path and call shape.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:272-303` — `PyrycodeMobileTheme` is the wrapper for previews.
- `gradle/libs.versions.toml:13` — Compose BOM `2026.02.01`; `TextOverflow.StartEllipsis` (Compose 1.7+) is available.

## Public API

Two new `internal` declarations in `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt`:

```kotlin
internal data class ChannelInfoUiModel(
    val conversationName: String,
    val workspacePath: String,
    val createdLabel: String,
    val lastActivityLabel: String,
    val sessionCount: Int,
    val messageCount: Int,
    val memoryPlugins: List<String>,
    val channelId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelInfoSheet(
    model: ChannelInfoUiModel,
    onRename: () -> Unit,
    onChangeWorkspace: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onInstallMemoryPlugin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
)
```

Behavior contract:

- `ChannelInfoSheet` wraps an M3 `ModalBottomSheet(onDismissRequest = onDismiss, ...)` containing a single `ChannelInfoSheetContent` composable. The split between `ChannelInfoSheet` and an `internal fun ChannelInfoSheetContent(model, callbacks...)` exists so the preview can render the content over a `Surface` instead of trying to preview `ModalBottomSheet`. Mirror `WorkspacePickerSheet` / `WorkspacePickerSheetContent`.
- The composable does **no formatting**. `createdLabel` / `lastActivityLabel` arrive pre-rendered.

## Layout

A top-level `Column(Modifier.fillMaxWidth())` inside the sheet, with these children in order:

1. **TitleRow** — `Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = CenterVertically)`.
   - `Text(text = model.conversationName, modifier = Modifier.weight(1f), style = titleLarge, color = onSurface)`.
   - `IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = onSurfaceVariant) }`.
2. **SectionHeader("About")** — private helper, see "Internal helpers" below.
3. **About rows** — five `AboutRow` instances:
   - `("Workspace", value = model.workspacePath, valueIsPath = true)` — see "Workspace row" below.
   - `("Created", model.createdLabel)`.
   - `("Last activity", model.lastActivityLabel)`.
   - `("Total sessions", model.sessionCount.toString())`.
   - `("Total messages", model.messageCount.toString())`.
4. **SectionHeader("Memory")**.
5. **MemoryRow(plugins = model.memoryPlugins, onInstall = onInstallMemoryPlugin)** — see "Memory row" below.
6. **SectionHeader("Actions")**.
7. **ActionsGrid** — `Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp))` with two `Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp))` rows:
   - Row 1: `ActionCell("Rename", onRename)`, `ActionCell("Change workspace", onChangeWorkspace)`.
   - Row 2: `ActionCell("Archive", onArchive)`, `ActionCell("Delete", onDelete)`.
8. **Footer** — see "Footer + clipboard" below.

### Internal helpers

All `private @Composable`, all in the same file:

- `SectionHeader(text: String)` — `Text(text, Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), style = labelLarge, color = onSurfaceVariant)`. Identical to `WorkspacePickerSheet.SectionHeader`. (Don't extract a shared helper across files for this ticket — touch only this file.)
- `AboutRow(label: String, value: String, valueIsPath: Boolean = false)` — `Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = CenterVertically)` containing:
  - Left: `Text(label, style = bodyLarge, color = onSurface)`.
  - Spacer: `Spacer(Modifier.weight(1f))` or use `Arrangement.SpaceBetween` on the Row; either is fine — pick the one that lets the right-side text shrink correctly (with `weight(1f, fill = false)` on the right text for the path case).
  - Right: see "Workspace row" for `valueIsPath = true`, otherwise `Text(value, style = bodyMedium, color = onSurfaceVariant, textAlign = TextAlign.End)`.
- `ActionCell(label: String, onClick: () -> Unit, modifier: Modifier)` — `FilledTonalButton(onClick = onClick, modifier = modifier) { Text(label, style = labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }`. The caller passes `Modifier.weight(1f)` from inside the Row scope. Use M3 default `FilledTonalButton` colors and shape — defaults already produce `secondaryContainer` / `onSecondaryContainer` and a pill-ish shape that matches Figma `20:96`'s `rounded-[100px]` intent. Do not override `colors` or `shape`. Accept the minor `ButtonDefaults.ContentPadding` vs Figma `py-[10px]` delta; visual fidelity is preserved.

### Workspace row

The workspace path renders right-aligned in `FontFamily.Monospace` 12sp `onSurfaceVariant`, and may ellipsize at the **start** so the rightmost path segment stays visible (the user cares about the leaf folder, not the home prefix):

```kotlin
Text(
    text = model.workspacePath,
    modifier = Modifier.weight(1f, fill = false),
    style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.StartEllipsis,
    textAlign = TextAlign.End,
)
```

`TextOverflow.StartEllipsis` is available in Compose 1.7+ which the project's `composeBom = 2026.02.01` resolves to. If the developer hits a resolution issue (unlikely; covered by the BOM), fall back to `TextOverflow.MiddleEllipsis` and flag.

### Memory row

Outer `Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = CenterVertically, horizontalArrangement = Arrangement.SpaceBetween)`:

- Left: `Text("Memory plugins", style = bodyLarge, color = onSurface)`.
- Right: branch on `plugins.isEmpty()`:
  - **Empty** — `Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = CenterVertically)` containing `Text("None", style = bodyMedium, color = onSurfaceVariant)` and `TextButton(onClick = onInstall, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Install", style = labelLarge) }`. M3 `TextButton` defaults its content color to `primary`, matching Figma `20:84`.
  - **Non-empty** — `Column(horizontalAlignment = Alignment.End)` of plugin names, each `Text(name, style = bodyMedium, color = onSurfaceVariant, textAlign = TextAlign.End)`. No Install button in this state. Rationale: AC ("the trailing content lists plugin names") replaces the empty-state content with the list; surfacing install-additional from this sheet is out of scope for #217.

### Footer + clipboard

```kotlin
val clipboard = LocalClipboardManager.current
val haptics = LocalHapticFeedback.current
Box(
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = 24.dp, end = 24.dp, top = 24.dp)
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = {
                clipboard.setText(AnnotatedString(model.channelId))
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        ),
) {
    Text(
        text = "Channel ID: ${model.channelId}",
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.alpha(0.5f),
    )
}
```

Notes:

- `indication = null` so long-press doesn't paint a ripple over the low-opacity footer.
- `onClick = {}` is intentional — AC specifies only long-press copies.
- `combinedClickable` requires `@OptIn(ExperimentalFoundationApi::class)` on the enclosing composable. Same opt-in is in `ConversationRow.kt:7`.
- `LocalClipboardManager` (legacy stable API) per AC: "via the platform ClipboardManager (resolved through LocalClipboardManager)". Do not migrate to `LocalClipboard` / `Clipboard.setClipEntry` even though the newer API is available.

### Bottom padding

The Figma frame has `pb-[24px]` at the bottom of the sheet. M3 `ModalBottomSheet` already adds system-bar insets at the bottom; add a single `Spacer(Modifier.height(24.dp))` after the footer to match the 24.dp inner padding above the inset. Same pattern as `WorkspacePickerSheetContent`'s trailing `Spacer(Modifier.height(24.dp))`.

## State + concurrency model

Pure stateless composable. No `viewModelScope`, no `StateFlow`, no coroutines. The only Compose-managed state is the M3 `SheetState` (hoisted as a default parameter) and the `MutableInteractionSource` `remember`'d for the footer's indication-less clickable. Clipboard write is synchronous via `LocalClipboardManager`.

## Error handling

None applicable. Long-press copy via `LocalClipboardManager.setText` cannot throw under the documented API; no try/catch.

## Testing strategy

No unit or instrumented tests in this ticket. AC requires only a `@Preview`. Tests land with the host ticket where the composable is bound to the repository. Provide two `@Preview` composables:

- `ChannelInfoSheetPreview` — light theme, `widthDp = 412`.
- `ChannelInfoSheetDarkPreview` — dark theme, `widthDp = 412`, `uiMode = Configuration.UI_MODE_NIGHT_YES`.

Both render `ChannelInfoSheetContent(model = SAMPLE, ...)` wrapped in `PyrycodeMobileTheme` + `Surface(color = surfaceContainerLow)`, mirroring `WorkspacePickerSheetPreview`. `SAMPLE` is a `ChannelInfoUiModel` populated with the exact values from AC:

- `conversationName = "kitchenclaw refactor"`
- `workspacePath = "~/Workspace/Projects/KitchenClaw"`
- `createdLabel = "3 weeks ago"`
- `lastActivityLabel = "2 hours ago"`
- `sessionCount = 12`
- `messageCount = 347`
- `memoryPlugins = emptyList()`
- `channelId = "ch_a8f3c2d1e9b7"`

## Open questions

- **Plugin list visual treatment (non-empty case).** AC defers to architect for `20:78`. This spec calls for a right-aligned `Column` of `bodyMedium` plugin names. If the host ticket reveals a different layout need (e.g. chip rendering, count + tap-to-expand), iterate there. The empty-state path is the one all current callers will exercise.
- **Action cell text overflow.** "Change workspace" is the longest label in the grid. If the cell narrows on small screens, the spec uses `maxLines = 1, overflow = Ellipsis`. If during implementation the label visibly truncates at `widthDp = 412`, drop one letter-spacing notch or wrap to two lines via `maxLines = 2`; visual fidelity is the binding constraint.

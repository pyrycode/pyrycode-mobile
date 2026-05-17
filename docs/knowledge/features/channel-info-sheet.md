# ChannelInfoSheet

Stateless M3 modal bottom sheet (#217) that surfaces channel-level metadata and entry points for the channel-action affordances (rename, change workspace, archive, delete, install memory plugin). Renders Figma `20:48`: a sheet-title row with a close icon, an **About** section with five label/value rows, a **Memory** section with the empty-state `None` + `Install` CTA (or the installed-plugin list), an **Actions** 2×2 grid, and a low-opacity footer that long-press-copies the channel ID. Emits six callbacks (`onRename`, `onChangeWorkspace`, `onArchive`, `onDelete`, `onInstallMemoryPlugin`, `onDismiss`) and owns no state of its own — repository wiring, time-label formatting, sheet visibility, and entry-point integration all live in the follow-up host ticket.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ChannelInfoSheet.kt`. Second **sheet** in that package alongside [`WorkspacePickerSheet`](./workspace-picker-sheet.md).

## Shape

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

- **`internal`** — consumed only within the module; same posture as [`WorkspacePickerSheet`](./workspace-picker-sheet.md) / `ThemePickerDialog`.
- **`sheetState` defaulted but exposed** — host (follow-up ticket) needs to drive `sheetState.hide()` for animated-close before invoking `onDismiss`; defaulting keeps the preview / one-off uses one-line. `skipPartiallyExpanded = true` because the content height is bounded.
- **No formatting in the composable.** `createdLabel` / `lastActivityLabel` arrive pre-rendered from the caller — the host computes them from `Conversation.lastUsedAt` (and an architect-resolved created timestamp). Numeric counts are formatted as `Int.toString()` at the call site inside the row.
- **No nullable callbacks.** Every cell, the close icon, and the install button always render, so every callback is always wired. The non-empty memory branch does not expose an install affordance — install-more-from-this-sheet is out of scope.

A peer `internal` composable `ChannelInfoSheetContent(model, callbacks...)` carries the body; `ChannelInfoSheet` is the `ModalBottomSheet` shell that delegates into it. Same shell + `*Content` split as [`WorkspacePickerSheet`](./workspace-picker-sheet.md) — previews target the content because the modal scrim + animation machinery don't render in the IDE preview pane.

## What it does

Single `Column(fillMaxWidth)` inside the `ModalBottomSheet`:

1. **`TitleRow(title = model.conversationName, onClose = onDismiss)`** — `Row(fillMaxWidth.padding(start = 16, end = 4, top = 4, bottom = 12))` with weighted `Text(titleLarge, onSurface)` + trailing `IconButton(Icons.Filled.Close, contentDescription = "Close", tint = onSurfaceVariant)`.
2. **`SectionHeader("About")`** — `labelLarge` on `onSurfaceVariant`, `padding(start = 24, end = 16, top = 12, bottom = 4)`. Identical helper shape to `WorkspacePickerSheet.SectionHeader`; deliberately not extracted into a shared file in this ticket — touch only this file.
3. **Five `AboutRow`s**: `Workspace` (with `valueIsPath = true`), `Created`, `Last activity`, `Total sessions`, `Total messages`. See "Row contracts" below.
4. **`SectionHeader("Memory")`** then **`MemoryRow(plugins, onInstall)`**. See "Memory row" below.
5. **`SectionHeader("Actions")`** then **`ActionsGrid`** — `Column(padding(start = 16, end = 16, top = 4), spacedBy(8.dp))` over two `Row(fillMaxWidth, spacedBy(8.dp))`s: row 1 = `Rename` | `Change workspace`, row 2 = `Archive` | `Delete`. Each cell is `FilledTonalButton(onClick = …, modifier = Modifier.weight(1f))` with `Text(labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)`.
6. **`Footer(channelId)`** — see "Footer + clipboard" below.
7. **`Spacer(height = 24.dp)`** — bottom inner padding above the system-inset that `ModalBottomSheet` already applies. Same trailing-spacer pattern as `WorkspacePickerSheetContent`.

### Row contracts

| Helper | Layout | Visual |
|---|---|---|
| `AboutRow(label, value)` | `Row(fillMaxWidth.padding(horizontal = 16, vertical = 8))` with leading `Text(label, bodyLarge, onSurface)`, weighted `Spacer`, trailing `Text(value, bodyMedium, onSurfaceVariant, TextAlign.End)` | Right value right-aligns; numeric values render as `Int.toString()` at the call site |
| `AboutRow(label, value, valueIsPath = true)` | Same row, no spacer — right `Text` is itself `Modifier.weight(1f)` so it occupies all remaining width | Right text is `bodyMedium.copy(FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)` on `onSurfaceVariant`, `maxLines = 1`, `overflow = TextOverflow.StartEllipsis`, `TextAlign.End` — start-ellipsis keeps the rightmost path segment visible (users care about the leaf folder, not the home prefix) |

### Memory row

Outer `Row(fillMaxWidth.padding(horizontal = 16, vertical = 8), Arrangement.SpaceBetween)`, leading `Text("Memory plugins", bodyLarge, onSurface)`, trailing branches on `plugins.isEmpty()`:

- **Empty** — `Row(spacedBy(8.dp))` with `Text("None", bodyMedium, onSurfaceVariant)` + `TextButton(onClick = onInstall, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp))` containing `Icon(Icons.Outlined.Add, contentDescription = null, size = 16.dp)` + `Spacer(width = 4.dp)` + `Text("Install", labelLarge)`. M3 `TextButton` defaults content color to `primary`.
- **Non-empty** — `Column(horizontalAlignment = Alignment.End)` of plugin names, each `Text(name, bodyMedium, onSurfaceVariant, TextAlign.End)`. No install affordance in this state.

### Footer + clipboard

`Box(fillMaxWidth.padding(start = 24, end = 24, top = 24).combinedClickable(...))` wrapping `Text("Channel ID: $channelId", bodySmall.copy(FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp), onSurfaceVariant, Modifier.alpha(0.5f))`. Combined-clickable uses `indication = null` (no ripple over the low-opacity text), `onClick = {}` (intentional — AC restricts copy to long-press), and `onLongClick` that calls `clipboard.setText(AnnotatedString(channelId))` on `LocalClipboardManager.current` followed by `haptics.performHapticFeedback(HapticFeedbackType.LongPress)` on `LocalHapticFeedback.current`. `combinedClickable` requires `@OptIn(ExperimentalFoundationApi::class)` on the enclosing private `Footer` composable (same import as the precedent in [`ConversationRow.kt`](./conversation-row.md)).

`LocalClipboardManager` is the legacy stable Compose API; the spec explicitly retains it over the newer `LocalClipboard` / `Clipboard.setClipEntry`. The deprecation warning at the call site is expected and accepted — migration is a follow-up the day the legacy API is removed, not now.

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` | `ModalBottomSheet` default sheet bg |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title text, row labels |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Section headers, About values, close-icon tint, footer text |
| `Schemes/primary` | `colorScheme.primary` | `Install` text button content |
| `Schemes/secondary-container` | `colorScheme.secondaryContainer` | `FilledTonalButton` default container for `ActionCell` |
| `Schemes/on-secondary-container` | `colorScheme.onSecondaryContainer` | `FilledTonalButton` default content for `ActionCell` |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Title Large` | `typography.titleLarge` | Conversation name in `TitleRow` |
| `Static/Label Large` | `typography.labelLarge` | Section headers, `Install` label, `ActionCell` labels |
| `Static/Body Large` | `typography.bodyLarge` | About row left labels, Memory row left label |
| `Static/Body Medium` | `typography.bodyMedium` | About row right values, `None`, plugin names |
| Roboto Mono 12sp | `bodyMedium.copy(FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)` | Workspace path value |
| Roboto Mono 11sp | `bodySmall.copy(FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)` | Footer `Channel ID: …` line |

`ActionCell` uses `FilledTonalButton` defaults for shape and colors — the M3 defaults already produce `secondaryContainer` / `onSecondaryContainer` and a pill-ish shape matching Figma `20:96`'s `rounded-[100px]` intent. No `shape` or `colors` overrides; the minor `ButtonDefaults.ContentPadding` vs Figma `py-[10px]` delta is accepted as visually negligible.

## Recomposition / stability

- All six callback params are `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No mutable state inside the public composable. The only `remember` is the `MutableInteractionSource` for the footer's indication-less `combinedClickable` (an internal helper of the `Footer` composable, not part of the public contract) and the defaulted `rememberModalBottomSheetState(...)` parameter, which the host can override.
- `memoryPlugins: List<String>` is stable when the caller passes an immutable list from the eventual repository projection — Compose treats `List<String>` as stable when the value reference is stable.

## Configuration

- **No new dependencies.** `ModalBottomSheet`, `FilledTonalButton`, `TextButton`, `combinedClickable`, `LocalClipboardManager`, `LocalHapticFeedback`, `TextOverflow.StartEllipsis` (Compose 1.7+, resolved by `composeBom = 2026.02.01`), and `Icons.Outlined.Add` / `Icons.Filled.Close` (already on the classpath via `material-icons-extended`, wired by [#131](../codebase/131.md)) all ship in the existing BOM. No `gradle/libs.versions.toml` edit.
- **No new string resources.** Literals inline (`"About"`, `"Memory"`, `"Actions"`, the five About-row labels, `"Memory plugins"`, `"None"`, `"Install"`, the four action labels, `"Channel ID: $channelId"`, `"Close"` content description). First-localisation pass migrates everything together.

## Preview

Two `@Preview`s, one per theme, both `widthDp = 412` and `showBackground = true` — the dark variant adds `uiMode = Configuration.UI_MODE_NIGHT_YES`. Both render `ChannelInfoSheetContent(model = SAMPLE_MODEL, ...)` wrapped in `PyrycodeMobileTheme(darkTheme = …) { Surface(color = surfaceContainerLow, contentColor = onSurface) { Column(padding(top = 12.dp)) { … } } }` — identical wrap shape to [`WorkspacePickerSheet`](./workspace-picker-sheet.md)'s previews so the surface colour matches the runtime `ModalBottomSheet` container and the top spacer substitutes for the drag-handle gap.

`SAMPLE_MODEL` is a file-private `ChannelInfoUiModel` populated with the exact values from AC: `conversationName = "kitchenclaw refactor"`, `workspacePath = "~/Workspace/Projects/KitchenClaw"`, `createdLabel = "3 weeks ago"`, `lastActivityLabel = "2 hours ago"`, `sessionCount = 12`, `messageCount = 347`, `memoryPlugins = emptyList()`, `channelId = "ch_a8f3c2d1e9b7"`. Only the empty-plugins path is rendered — the non-empty branch is structurally simple and ships unpreviewed until the host ticket lights up a real plugin source. No empty-state-or-error preview otherwise.

## Tests

None in this ticket — AC requires only a `@Preview`. Compose UI tests and any repository-binding tests land with the host ticket, where the composable is wired to real data; the precedent for stateless-component tests in this package is [`WorkspacePickerSheetTest.kt`](./workspace-picker-sheet.md#tests) (five `androidTest/` Compose UI tests targeting the `*Content` seam).

## Edge cases / limitations

- **Workspace value start-ellipsizes, not middle.** `TextOverflow.StartEllipsis` keeps the rightmost path segment visible — for `~/Workspace/Projects/KitchenClaw`, narrow widths collapse the leading prefix and preserve `…/KitchenClaw`. Fallback to `TextOverflow.MiddleEllipsis` only if a Compose-BOM resolution issue surfaces (none observed at `composeBom = 2026.02.01`).
- **`Change workspace` is the longest action label.** Rendered with `maxLines = 1, overflow = TextOverflow.Ellipsis` inside `FilledTonalButton`'s default content padding. At `widthDp = 412` it does not visibly truncate; if a future smaller breakpoint forces truncation, the spec allows dropping one letter-spacing notch or wrapping to `maxLines = 2`.
- **Non-empty memory layout is provisional.** The right-aligned `Column` of plugin names is the architect's read of Figma `20:78` for this ticket; the host ticket may iterate (chip rendering, count + tap-to-expand) once a real plugin source is wired. The empty-state path is the one all current callers exercise.
- **Delete cell carries no destructive emphasis.** Per AC and Figma `20:96`, the cell uses the same `FilledTonalButton` defaults as the other three — `secondaryContainer` / `onSecondaryContainer`, no `error`-tinted variant. A destructive-confirmation flow is a separate concern owned by the host (a confirmation dialog overlays before the actual delete fires).
- **Footer copy is long-press only.** Single-tap is wired (`onClick = {}` is intentional) but does nothing; the absence of a ripple (`indication = null`) is deliberate so the low-opacity footer doesn't paint a high-contrast ripple over itself. The haptic feedback on long-press is the user-facing confirmation that the copy fired.
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the caller doesn't need to wire either separately.

## Related

- Ticket notes: [`../codebase/217.md`](../codebase/217.md)
- Spec: `docs/specs/architecture/217-channelinfosheet-stateless-composable.md`
- Parent: split from [#144](https://github.com/pyrycode/pyrycode-mobile/issues/144) (Channel Info bottom sheet — host + sheet bundle).
- Sibling sheet: [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212) — same `ModalBottomSheet` shell + `*Content` body split, same `internal` visibility posture, same preview wrap. Worth reading first if you're picking up this file.
- Sibling stateless-component conventions: [`ConversationRow`](./conversation-row.md) (precedent for the `combinedClickable` long-press shape used by `Footer`), [`ConnectionBanner`](./connection-banner.md), [`ToolCallRow`](./tool-call-row.md).
- Downstream / open:
  - **Host (follow-up ticket, open)** — wires this sheet into every channel-info entry point (Channel List long-press, Thread overflow → Channel info…). Owns sheet-visibility `Boolean`, collects the per-channel projection that produces a `ChannelInfoUiModel` (workspace path, session and message counts derived from the repository, pre-rendered relative-time labels from a shared helper), routes each callback to the appropriate downstream surface (rename dialog, [`WorkspacePicker`](./workspace-picker.md), archive confirmation, delete confirmation, memory-plugin install flow).
  - Open: non-empty memory plugin layout — revisit when a real plugin source ships and a designer locks the precise list / chip / expander treatment against Figma `20:78`.
  - Open: destructive-emphasis on `Delete` action — owned by the host's confirmation dialog, not this sheet.
  - Open: migrate `LocalClipboardManager` → `LocalClipboard` / `Clipboard.setClipEntry` once the legacy API is removed.
  - Open: localise the inline literals alongside the rest of `ui/conversations/components/`'s first `strings.xml` pass.

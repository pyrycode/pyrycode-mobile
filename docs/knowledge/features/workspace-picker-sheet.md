# WorkspacePickerSheet

Stateless modal bottom sheet (#212) that lets a user pick a workspace folder for a conversation. Renders Figma `20:2`: a `"Choose workspace"` title row with a trailing close icon, an optional **Recent** section listing caller-supplied paths in mono, and a single **Other** row labelled `"Create new folder under pyry-workspace…"`. Emits four callbacks and owns no state of its own — sheet visibility, the path list, scratch / dedup filtering, and the create-folder follow-up dialog all live in the host (#207, open).

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `WorkspacePickerSheet.kt`. First **sheet** in that package — siblings are all rows + bubbles.

## Shape

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerSheet(
    recent: List<String>,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
)
```

- **`internal`** — consumed only within the module; same posture as [`ThemePickerDialog`](./app-preferences.md).
- **`sheetState` defaulted but exposed** — host (#207) needs to drive animated-close before invoking `onDismiss`; defaulting keeps preview / one-off uses one-line. `skipPartiallyExpanded = true` because this is a short content list, not a half-sheet.
- **No nullable callbacks** — Other always renders, so `onCreateNew` is always wired.
- **`recent: List<String>` arrives already filtered.** Scratch sentinel and dedup are the caller's responsibility; this composable renders what it's given, in order.

A peer `internal` composable `WorkspacePickerSheetContent(recent, onPick, onCreateNew, onDismiss)` carries the body — `WorkspacePickerSheet` is the `ModalBottomSheet` shell that delegates into it. The split exists so previews and Compose UI tests can render the content directly (the modal scrim + animation machinery don't render in the IDE preview pane and aren't wired into `createComposeRule()`-style tests). Same architectural shape M3 samples use for sheet previews.

## What it does

Single `Column(fillMaxWidth)` inside the `ModalBottomSheet`:

1. **`TitleRow(onClose = onDismiss)`** — `"Choose workspace"` in `titleLarge` filling the row, trailing `IconButton(Icons.Filled.Close)` with `contentDescription = "Close"` and `tint = onSurfaceVariant`. Padding `start = 16, end = 4, top = 4, bottom = 12` per Figma `20:5`.
2. **Recent section, conditional on `recent.isNotEmpty()`** — `SectionHeader("Recent")` then one `RecentRow(path, onClick = { onPick(path) })` per entry. Empty `recent` suppresses the header entirely (no empty state, no placeholder text) — the host renders Other only.
3. **`SectionHeader("Other")`** — always rendered.
4. **`CreateNewRow(onClick = onCreateNew)`** — the single Other row.
5. **`Spacer(height = 24.dp)`** — bottom padding per Figma `pb=24`.

`ModalBottomSheet`'s default `BottomSheetDefaults.DragHandle` paints the M3 drag pill at the top; the composable doesn't override it.

### Row contracts

| Helper | Layout | Visual |
|---|---|---|
| `SectionHeader(text)` | `Text(text, padding(start = 24, end = 16, top = 12, bottom = 4))` | `labelLarge` in `onSurfaceVariant` |
| `RecentRow(path, onClick)` | `Row(fillMaxWidth.clickable(onClick).padding(horizontal = 16, vertical = 12), arrangement = spacedBy(16), verticalAlignment = CenterVertically)` | Leading `Icons.Outlined.Folder` 24dp in `primary`; `Text(path)` weighted `1f`, `bodyMedium`, `FontFamily.Monospace`, `maxLines = 1`, `TextOverflow.Ellipsis` |
| `CreateNewRow(onClick)` | Same row shape as `RecentRow` | Leading `Icons.Outlined.CreateNewFolder` 24dp in `primary`; `Text("Create new folder under pyry-workspace…")` in `bodyLarge` |

Both rows attach `Modifier.clickable` unconditionally — same idiom as [`ConnectionBanner`](./connection-banner.md) / [`ToolCallRow`](./tool-call-row.md). The leading icons set `contentDescription = null` because the adjacent `Text` carries the semantic content (a TalkBack user hears the path, not `"path, folder icon"`).

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex, no custom text styles. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` (sheet bg) | `ModalBottomSheet` default |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title, row text |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Section headers, close-icon tint |
| `Schemes/primary` | `colorScheme.primary` | Leading folder icons |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Title Large` | `typography.titleLarge` | `"Choose workspace"` |
| `Static/Label Large` | `typography.labelLarge` | `"Recent"`, `"Other"` |
| `Static/Body Large` | `typography.bodyLarge` | `"Create new folder under pyry-workspace…"` |
| Roboto Mono 14sp | `typography.bodyMedium` + `FontFamily.Monospace` | Recent row paths |

The trailing ellipsis on the Create label is the literal U+2026 character (`"…"`), not three dots — matches Figma `20:47` verbatim. Same convention as [`ConnectionBanner`](./connection-banner.md)'s em-dash in `"Offline — tap to retry"`.

## Recomposition / stability

- All four callback params are `() -> Unit` / `(String) -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No internal mutable state, no `LaunchedEffect`, no `DisposableEffect`, no `rememberSaveable`. The only `remember` is the defaulted `rememberModalBottomSheetState(...)` parameter, which the host can override.
- `recent: List<String>` is stable when the caller passes an immutable list from the [`recentWorkspaces()`](./conversation-repository.md) flow — `ImmutableList` is not used because the Compose compiler treats `List<String>` as stable when the value reference is stable, which it is for `StateFlow`-derived snapshots.

## Configuration

- **No new dependencies.** `ModalBottomSheet` + `rememberModalBottomSheetState` + `SheetState` all ship in `androidx.compose.material3` already in the BOM; `Icons.Outlined.Folder` / `Icons.Outlined.CreateNewFolder` are in `material-icons-extended` (added by [#131](../codebase/131.md) for `ToolCallRow`). No `gradle/libs.versions.toml` edit.
- **No new string resources.** Literals inline (`"Choose workspace"`, `"Recent"`, `"Other"`, `"Create new folder under pyry-workspace…"`, `"Close"`); first-localisation pass migrates everything together.

## Preview

Two `@Preview`s, one per theme, both `widthDp = 412` and `showBackground = true` — the dark variant adds `uiMode = Configuration.UI_MODE_NIGHT_YES`. Both target `WorkspacePickerSheetContent` (not the modal) wrapped in `PyrycodeMobileTheme(darkTheme = …) { Surface(color = surfaceContainerLow, contentColor = onSurface) { Column(padding(top = 12.dp)) { … } } }`. Sample data is a file-private `SAMPLE_RECENTS = listOf("~/Workspace/Projects/KitchenClaw", "~/Workspace/Projects/pyrycode", "~/Workspace/Projects/pyrycode-mobile", "~/Workspace/personal")` — four entries per AC.

The `Surface(color = surfaceContainerLow)` preview wrap simulates the `ModalBottomSheet`'s default container colour so the preview matches what the runtime sheet paints; the top `Spacer` substitutes for the drag-handle gap. An empty-recents preview is not rendered — the empty-state behaviour is exercised by the Compose UI test (`recent_header_is_omitted_when_recent_is_empty`).

## Tests

Five Compose UI tests in `androidTest/.../WorkspacePickerSheetTest.kt` (`createComposeRule()` + `AndroidJUnit4`, no MockK / Turbine — matches `ConversationAvatarTest.kt`). All target `WorkspacePickerSheetContent` (not the `ModalBottomSheet`) because the modal machinery requires an attached `Activity` host:

- `recent_rows_render_full_paths` — `"Recent"` header is displayed, each sample path is displayed.
- `recent_row_tap_invokes_onPick_with_full_path` — `picked::add` captures the tapped path; asserts the exact full-path string round-trips through `onPick`.
- `recent_header_is_omitted_when_recent_is_empty` — `emptyList()` input; `"Recent"` does not exist, `"Other"` and the Create label still render.
- `create_new_row_invokes_onCreateNew_on_tap` — invocation count via `var invoked = 0` capture.
- `close_icon_invokes_onDismiss_on_tap` — invocation via `hasContentDescription("Close")` lookup.

Defensive coverage — the AC doesn't require Compose tests, but the four interactive paths plus the empty-state branch are the entire surface, so the cost is small.

## Edge cases / limitations

- **No subtitle on `RecentRow`** — the AC's `"basename + full path subtitle"` proposal was a speculation (carried `"(architect to confirm against Figma)"`); the locked Figma shows the **full path** in mono as the primary line, so the spec resolved Deviation 1 by rendering the path only and dropping the `"Last used X ago"` subtitle. Drives from the data: [`recentWorkspaces()`](./conversation-repository.md) returns `Flow<List<String>>` with no per-entry timestamp ([#209](../codebase/209.md)), so adding the subtitle would require reshaping the repository contract — out of scope for an S-sized stateless-composable ticket. Recoverable later: extend `recentWorkspaces()` to `Flow<List<RecentWorkspaceEntry>>` with `(path, lastUsedAt)`, then add the relative-time line under the primary.
- **No `default` chip on any row** — Figma `20:34/20:36/20:37` shows a `default` pill on the scratch row. Scratch is filtered out by the caller per the Technical Notes, so the chip never renders; not implemented here. If a future design re-surfaces scratch, the chip wires in as a child of `RecentRow`.
- **No filesystem browser, no auto-discovered Projects** — MVP renders only the two sections defined in the AC. The "browse my filesystem" entry point is a deliberate omission.
- **Section headers don't render leading affordances** — no icon, no chevron, no overflow menu. Plain `labelLarge` text per Figma.
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the caller doesn't need to wire either separately.

## Related

- Ticket notes: [`../codebase/212.md`](../codebase/212.md)
- Spec: `docs/specs/architecture/212-workspacepickersheet-stateless-composable.md`
- Parent: split from [#206](https://github.com/pyrycode/pyrycode-mobile/issues/206) (itself split from #143).
- Upstream:
  - [Conversation repository](./conversation-repository.md) — `recentWorkspaces(): Flow<List<String>>` ([#209](../codebase/209.md)) supplies the Recent list; `createWorkspaceFolder(name): String` ([#210](../codebase/210.md)) is what the host invokes after the Create-new-folder follow-up dialog returns.
  - Sibling stateless-component conventions: [`ConversationRow`](./conversation-row.md), [`ConnectionBanner`](./connection-banner.md), [`ToolCallRow`](./tool-call-row.md) — file-private spacing posture, always-`clickable` rows, public / `*Content` split for preview-targeting.
  - Sibling stateless-dialog convention: `ThemePickerDialog` in `ui/settings/` — `internal` visibility, hoisted state, `onDismiss` callback shape.
- Downstream / open:
  - **Host (#207, open)** — wires this sheet into the Channel List FAB and the "Save as channel" flow. Collects `recentWorkspaces()`, dedupes / filters scratch, owns sheet visibility, owns the Create Folder follow-up dialog (Figma `19:44`) that calls `createWorkspaceFolder` on submit and selects the returned path via `onPick`.
  - **Create Folder follow-up sheet (Figma `19:44`)** — separate composable owned by the host. This ticket renders only the entry point (`onCreateNew()` callback).
  - Open: surface `"Last used X ago"` subtitle once `recentWorkspaces()` carries per-entry timestamps. Requires the data-layer shape change above.
  - Open: localise the inline literals — deferred to the codebase's first `strings.xml` pass.

# 212 — `WorkspacePickerSheet` stateless composable (Figma 20:2)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:1-122` — the closest existing example in this package: stateless composable, `ListItem`-based, two `@Preview`s (Light + Dark) using `PyrycodeMobileTheme`. Mirror the structure (file layout, preview wiring, package-private helpers) here.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ThemePickerDialog.kt:1-75` — the project's existing reference for a stateless, design-system dialog with `onDismiss` / `onConfirm` callbacks. Picker-sheet semantics differ (no pending state, no confirm button), but the params-shape convention, `internal` visibility, and import discipline are the same. Note: do NOT mirror the `R.string.*` extraction — per ticket Technical Notes, all labels in this composable stay inline.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:35,262,391` — the established convention for code/path text in this codebase: `fontFamily = FontFamily.Monospace`. Use the same on recent-row primary text (Figma `20:19/20:27/20:35` use Roboto Mono).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationAvatar.kt` (skim) — confirms the package convention for leading icons inside `ListItem`-based rows (`Modifier.size(...)`, M3 icon-tinting). Same shape applies to the folder leading icons here.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` (skim, ~30 lines) — `PyrycodeMobileTheme` entry point used by all `@Preview`s in this codebase. Wrap the new previews in the same.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — confirms `AppTypography = Typography()` (M3 defaults). All typography references in this spec resolve through `MaterialTheme.typography.*` — no custom text styles.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:70-100` — confirms the read-side data shape (`recentWorkspaces(): Flow<List<String>>` — paths only, no timestamps). This is what gates the "Last used X ago" subtitle deviation called out below.
- `gradle/libs.versions.toml` (lines around `androidx-compose-material3`) — confirms `androidx.compose.material3.material3` is already present; `ModalBottomSheet` and `rememberModalBottomSheetState` ship in the same artifact. No new dependency.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-2

Dark Material 3 modal bottom sheet titled "Choose workspace" (titleLarge) with a close (X) icon on the trailing edge; a "Recent" labelLarge section header above three rows of `[folder icon] [full path in Roboto Mono] / [last-used subtitle]`, then an "Other" labelLarge section header above a single row `[create-folder icon] [Create new folder under pyry-workspace…]`. Drag handle pill at the top is M3's default `BottomSheetDefaults.DragHandle`. One row in the Figma also carries a `default` chip (secondary-container slot) — that row is the scratch row, filtered out by the caller per ticket Technical Notes, so the chip is not implemented here.

## Context

`WorkspacePickerSheet` is the first **sheet** composable in `ui/conversations/components/` (the package currently holds rows and bubbles only). Its single responsibility is to render the picker chrome with two sections — Recent (a list of paths) and Other (one action row) — and emit callbacks. State hoisting, sheet visibility, repository binding, and entry-point integration all live in #207 (the host composable that wires this sheet into the Channel List FAB and the "Save as channel" flow).

Read side (`recentWorkspaces(): Flow<List<String>>`, #209) and write side (`createWorkspaceFolder(name): String`, #210) are merged. The host (#207) will collect the read-side flow, dedupe / filter scratch, and pass `List<String>` to this composable. The Create-new-folder follow-up sheet (Figma `19:44`) is a separate composable owned by the host — this ticket renders only the entry point (`onCreateNew()` callback).

The contract is intentionally narrow: stateless, no `ViewModel`, no repository, no side effects beyond invoking the supplied callbacks. The host owns sheet state.

## Design

### Composable signature

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheet.kt`

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

Notes on the signature:

- **Visibility `internal`.** Same as `ThemePickerDialog`. The host (#207) lives in a sibling package; the composable is consumed only within the module.
- **`modifier: Modifier = Modifier`.** Standard Compose hygiene; allows the host to forward semantics / test tags if needed.
- **`sheetState` defaulted but exposed.** The host needs to drive sheet visibility (e.g., animate-close before invoking `onDismiss`). Defaulting it keeps the preview / one-off uses one-line; exposing it keeps the host's animation control. `skipPartiallyExpanded = true` because this is a short, scrollable list, not a half-sheet — matches Figma's full-content layout.
- **No nullable callbacks.** All three are required (recent picker, create-new, dismiss). The composable always renders the Other section, so `onCreateNew` is always wired.
- **`recent: List<String>` arrives already filtered.** Per ticket Technical Notes, scratch sentinel and dedup are the caller's responsibility. The composable renders what it is given, in order.

### Layout structure

The composable is a single `ModalBottomSheet` whose `onDismissRequest` is the `onDismiss` callback. Content is one `Column` (the sheet's own scaffolding provides the rounded corners, surface color, and drag handle).

```kotlin
ModalBottomSheet(
    onDismissRequest = onDismiss,
    modifier = modifier,
    sheetState = sheetState,
    // drag handle: default BottomSheetDefaults.DragHandle (matches Figma 20:3/20:4)
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleRow(onClose = onDismiss)            // "Choose workspace" + X icon
        if (recent.isNotEmpty()) {
            SectionHeader(text = "Recent")
            recent.forEach { path ->
                RecentRow(path = path, onClick = { onPick(path) })
            }
        }
        SectionHeader(text = "Other")
        CreateNewRow(onClick = onCreateNew)
        Spacer(modifier = Modifier.height(24.dp))  // bottom padding (Figma pb=24)
    }
}
```

The five `private @Composable` helpers (`TitleRow`, `SectionHeader`, `RecentRow`, `CreateNewRow`, and any leading-icon wrappers) live in the same file, scoped `private`. They are not exported and exist to keep the top-level composable readable. Each is a small layout primitive — keep their bodies under ~20 lines.

### Child composable contracts (signatures + visual summary, not bodies)

- `private fun TitleRow(onClose: () -> Unit)` — a `Row` with `Text("Choose workspace", style = MaterialTheme.typography.titleLarge)` filling the row's remaining width, followed by an `IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }`. Padding per Figma `20:5` (pl=16, pr=4, py top=4 bottom=12).
- `private fun SectionHeader(text: String)` — a `Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)` with padding per Figma `20:11`/`20:39` (pl=24, pr=16, pt=12, pb=4).
- `private fun RecentRow(path: String, onClick: () -> Unit)` — `Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp))` containing the leading folder icon (`Icons.Outlined.Folder`, size 24.dp, tint `MaterialTheme.colorScheme.primary`) and a single `Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)` weighted `1f`. **No subtitle** (see § Deviations).
- `private fun CreateNewRow(onClick: () -> Unit)` — same layout shape as `RecentRow`, with `Icons.Outlined.CreateNewFolder` as the leading icon and `Text("Create new folder under pyry-workspace…", style = MaterialTheme.typography.bodyLarge)`. The label is the literal string from Figma `20:47` (ending ellipsis is part of the Figma string; render it as a literal `"…"` character, not three dots, to match the design).

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex values, no custom text styles. Figma `Schemes/*` slots map 1:1:

| Figma slot                       | Compose slot                                 | Used by                          |
|----------------------------------|----------------------------------------------|----------------------------------|
| `Schemes/surface-container-low`  | `colorScheme.surfaceContainerLow` (sheet bg) | `ModalBottomSheet` default       |
| `Schemes/on-surface`             | `colorScheme.onSurface`                      | Title, row primary text          |
| `Schemes/on-surface-variant`     | `colorScheme.onSurfaceVariant`               | Section headers, drag handle, X  |
| `Schemes/primary` (icon tint)    | `colorScheme.primary`                        | Leading folder icons             |

Typography:

| Figma style       | Compose style                       | Used by                                  |
|-------------------|-------------------------------------|------------------------------------------|
| `Static/Title Large`  | `typography.titleLarge`         | "Choose workspace"                       |
| `Static/Label Large`  | `typography.labelLarge`         | "Recent", "Other" section headers        |
| `Static/Body Large`   | `typography.bodyLarge`          | "Create new folder under pyry-workspace…"|
| Roboto Mono 14sp      | `typography.bodyMedium` + `FontFamily.Monospace` | Recent row path text  |

`PyrycodeMobileTheme` already wires the M3 color scheme via dynamic color on Android 13+; the sheet inherits surface colors automatically.

### `@Preview`s

Two previews following the `ConversationRow.kt` convention — one Light, one Dark — both `widthDp = 412` (the standard preview width in this codebase). Each wraps the sheet content in `PyrycodeMobileTheme(...)` and renders the **content lambda only**, not the `ModalBottomSheet` itself (the modal scrim and animation machinery do not render in the IDE preview; previewing the sheet content directly is the established pattern for M3 sheets).

To make this work without duplicating the sheet body, refactor the body into a private `WorkspacePickerSheetContent(...)` composable that takes the same params minus `sheetState` and is what `WorkspacePickerSheet` delegates to inside the `ModalBottomSheet` content lambda. The preview targets `WorkspacePickerSheetContent` directly. This is the same pattern Material 3 samples use for sheet previews.

Sample data for the previews:

```kotlin
private val SAMPLE_RECENTS = listOf(
    "~/Workspace/Projects/KitchenClaw",
    "~/Workspace/Projects/pyrycode",
    "~/Workspace/Projects/pyrycode-mobile",
    "~/Workspace/personal",
)
```

Two previews:
- `WorkspacePickerSheetPreview` — Light, 4 recents + Create row.
- `WorkspacePickerSheetDarkPreview` — Dark (`uiMode = Configuration.UI_MODE_NIGHT_YES`), same data.

An "empty recents" preview is not required (the AC asks for 3–4 sample recent rows + Create action only). The empty-recents branch is exercised by the compose-test layer in a future ticket; here, AC #2's empty-state behavior is validated by code review reading the `if (recent.isNotEmpty()) { ... }` guard.

## State + concurrency model

None. The composable is pure: it reads its params, emits callbacks. No `remember`, no `LaunchedEffect`, no `viewModelScope`. The only `remember`-style call is the defaulted `rememberModalBottomSheetState(...)` parameter, which the host can override.

## Error handling

None. No failure modes — pure rendering. Empty `recent` is a valid input (Recent header is suppressed; only Other renders). Caller is responsible for upstream validation.

## Testing strategy

- **Unit tests:** none required by AC. `./gradlew test` covers no logic here — the composable has no branchable logic beyond the empty-recents header guard.
- **Compose tests:** not required by this ticket's AC. When the host (#207) lands, its compose-test will exercise the integrated path. If a developer wants a defensive compose-test for this composable in isolation, the natural shape is:
  - `recent_rows_render_full_paths_and_invoke_onPick_on_tap`
  - `recent_header_is_omitted_when_recent_is_empty`
  - `create_new_row_invokes_onCreateNew_on_tap`
  - `close_icon_invokes_onDismiss_on_tap`
  Each test wraps `WorkspacePickerSheetContent` (not the modal) in `setContent { PyrycodeMobileTheme { ... } }`, uses `onNodeWithText` for label lookups, and asserts callback invocation via a `var invoked = false` capture pattern (matches existing test conventions in this repo — no MockK, no Turbine).
- **Preview verification:** the developer renders both previews in the IDE preview pane and verifies visually against Figma `20:2` before opening the PR.

## Deviations from ticket AC

**Deviation 1 — recent row subtitle.** AC #2 reads: *"label is the path basename followed by the full path as a smaller subtitle (architect to confirm against Figma 20:2)"*. The Figma actually shows the **full path** in Roboto Mono as the primary line and *"Last used 2h ago"* as the subtitle. The parenthetical "(architect to confirm)" in the AC is the explicit license to reconcile this.

Resolution: render the **full path** as the only line — primary, mono, single-line with ellipsis overflow. Drop the "Last used X ago" subtitle. Rationale:

1. The contract receives `List<String>` (paths only); the `recentWorkspaces()` repository surface (#209) does not carry per-entry timestamps. Implementing "Last used X ago" would require extending the data shape, which is out of scope for an S-sized stateless-composable ticket.
2. The Figma primary line is the full path in mono — this matches the data we have exactly. The basename-primary + full-path-subtitle proposal in the AC was a speculation; the Figma is the locked truth.
3. The "Last used X ago" subtitle is recoverable in a follow-up: extend `recentWorkspaces()` to return `Flow<List<RecentWorkspaceEntry>>` with `(path, lastUsedAt)`, then add the `Text(formatRelativeTime(entry.lastUsedAt))` line under the primary. That follow-up should not gate this ticket — the picker is usable and visually 90% faithful without it.

The developer MUST NOT invent a basename-derived primary line (e.g., `path.substringAfterLast('/')`) — that is not what Figma shows, and a mono full-path is more legible at the typical width of these strings (`~/Workspace/Projects/X`).

**Deviation 2 — `default` chip on scratch row.** Figma `20:34/20:36/20:37` shows a `default` pill (`Schemes/secondary-container` background) next to the `~/pyry-workspace/scratch` row. Per ticket Technical Notes, *"the 'scratch' sentinel filtering is the caller's responsibility — `recent` arrives already filtered"*. Since scratch never appears in `recent`, the chip never renders. Do not implement the chip. If a future design decision re-surfaces scratch in this list, the chip wires in as a child of `RecentRow` then.

**Deviation 3 — no `Icons.Filled.Close` content-description string-resource.** Per ticket Technical Notes (*"No string-resource extraction in this ticket"*), the close icon's `contentDescription = "Close"` is inline. The i18n pass that lifts all labels to `strings.xml` covers this too.

## Open questions

None. All resolved by the Figma + deviation rationale above. If the developer encounters anything ambiguous during implementation, the answer is: render exactly what Figma `20:2` shows, with the documented deviations above; do not invent visual elements.

# ChannelInfoSheet

Stateless M3 modal bottom sheet (#217) that surfaces channel-level metadata and entry points for channel actions. Renders Figma `20:48`: a title row, an **About** section, a **Memory** section showing the current session's provider report, an **Actions** section, and a footer that long-press-copies the channel ID. The **thread-overflow** host landed in [#226](../codebase/226.md) (tap overflow → **Channel info** opens the populated sheet); the **Channel List long-press** entry point is still open. Memory search retrieves stored knowledge; provider detection does not mean the plugin captures this conversation or restores earlier context.

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
    val memorySearch: MemorySearchReport,
    val channelId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelInfoSheet(
    model: ChannelInfoUiModel,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onInstallMemoryPlugin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    mutationsSupported: Boolean = true,               // new in #508 — false in relay mode hides the whole Actions section
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
)
```

- **`mutationsSupported` ([#508](../codebase/508.md))** is defaulted `= true` and forwarded verbatim into the `ChannelInfoSheetContent` body (which also takes a trailing `mutationsSupported: Boolean = true` — its gate site). It sits **after** `modifier` on the shell (before the also-defaulted `sheetState`) to satisfy Lint `ComposeParameterOrder` (defaulted-after-`modifier`; see [[compose-parameter-order-lint-defaulted-after-modifier]]) — `ChannelInfoSheetContent` has no `modifier` param, so its trailing defaulted param is fine. The default is a preview/test seam only — production threads `state.mutationsSupported` from [`ThreadScreen`](thread-screen.md). **It is a param, deliberately not a `ChannelInfoUiModel` field** — the model is pure display content; a capability flag is a separate concern, and putting it on the model would drag `toChannelInfoUiModel()` + `ThreadScreenMapperTest` into scope for no benefit.

- **`internal`** — consumed only within the module; same posture as [`WorkspacePickerSheet`](./workspace-picker-sheet.md) / `ThemePickerDialog`.
- **`sheetState` defaulted but exposed** — host (follow-up ticket) needs to drive `sheetState.hide()` for animated-close before invoking `onDismiss`; defaulting keeps the preview / one-off uses one-line. `skipPartiallyExpanded = true` because the content height is bounded.
- **No formatting in the composable.** `createdLabel` / `lastActivityLabel` arrive pre-rendered from the caller — the [#226](../codebase/226.md) host computes them via the app-wide `formatRelativeTime` helper: last-activity from `Conversation.lastUsedAt`, created from the **earliest collected `ThreadItem`'s timestamp** (there is no persisted `createdAt` on `Conversation` — deferred to a schema ticket). Both render the em-dash `"—"` when their source is absent. Numeric counts are formatted as `Int.toString()` at the call site inside the row.
- **No nullable callbacks.** Rendered controls have wired callbacks. The Install button appears only for confirmed absence; `ThreadScreen` opens `MEMORY_PLUGIN_DOCS_URL` through `LocalUriHandler` when tapped.

A peer `internal` composable `ChannelInfoSheetContent(model, callbacks...)` carries the body; `ChannelInfoSheet` is the `ModalBottomSheet` shell that delegates into it. Same shell + `*Content` split as [`WorkspacePickerSheet`](./workspace-picker-sheet.md) — previews target the content because the modal scrim + animation machinery don't render in the IDE preview pane.

## What it does

Single `Column(fillMaxWidth)` inside the `ModalBottomSheet`:

1. **`TitleRow(title = model.conversationName, onClose = onDismiss)`** — `Row(fillMaxWidth.padding(start = 16, end = 4, top = 4, bottom = 12))` with weighted `Text(titleLarge, onSurface)` + trailing `IconButton(Icons.Filled.Close, contentDescription = "Close", tint = onSurfaceVariant)`.
2. **`SectionHeader("About")`** — `labelLarge` on `onSurfaceVariant`, `padding(start = 24, end = 16, top = 12, bottom = 4)`. Identical helper shape to `WorkspacePickerSheet.SectionHeader`; deliberately not extracted into a shared file in this ticket — touch only this file.
3. **Five `AboutRow`s**: `Workspace` (with `valueIsPath = true`), `Created`, `Last activity`, `Total sessions`, `Total messages`. See "Row contracts" below.
4. **`SectionHeader("Memory")`** then **`MemoryRow(model.memorySearch, onInstall)`**. See "Memory row" below.
5. **`SectionHeader("Actions")`** then **`ActionsGrid`** — a full-width `Rename` button above a row of `Archive` and `Delete`. Each cell is a `FilledTonalButton` with one-line text. The section is hidden when `mutationsSupported` is false; About, Memory and the Channel-ID footer remain visible.
6. **`Footer(channelId)`** — see "Footer + clipboard" below.
7. **`Spacer(height = 24.dp)`** — bottom inner padding above the system-inset that `ModalBottomSheet` already applies. Same trailing-spacer pattern as `WorkspacePickerSheetContent`.

### Row contracts

| Helper | Layout | Visual |
|---|---|---|
| `AboutRow(label, value)` | `Row(fillMaxWidth.padding(horizontal = 16, vertical = 8))` with leading `Text(label, bodyLarge, onSurface)`, weighted `Spacer`, trailing `Text(value, bodyMedium, onSurfaceVariant, TextAlign.End)` | Right value right-aligns; numeric values render as `Int.toString()` at the call site |
| `AboutRow(label, value, valueIsPath = true)` | Same row, no spacer — right `Text` is itself `Modifier.weight(1f)` so it occupies all remaining width | Right text is `bodyMedium.copy(FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)` on `onSurfaceVariant`, `maxLines = 1`, `overflow = TextOverflow.StartEllipsis`, `TextAlign.End` — start-ellipsis keeps the rightmost path segment visible (users care about the leaf folder, not the home prefix) |

### Memory row

Outer `Row(fillMaxWidth.padding(horizontal = 16, vertical = 8), Arrangement.SpaceBetween)` keeps the `Memory plugins` label and right-aligned value. It branches on the report rather than the length of a plugin list:

- **Confirmed absent, with no installed provider** — `None` and the `Install` text button. The button opens the shared memory-plugin docs URL.
- **Providers reported** — one right-aligned name and status per provider. The daemon's display name is inert text, capped at 120 characters and one line with ellipsis; a blank name becomes `Unnamed provider`. `Not installed` and `Disabled` take precedence over effective availability. Only installed, enabled and available renders `Memory search available`; installed and unavailable renders `Memory search unavailable`, and a remaining unknown state renders `Status unknown`. A listed provider never gets an Install control.
- **No providers, without confirmed absence** — `Status unknown` for unknown or omitted reports; otherwise `Memory search unavailable`. Neither state says `None` or offers Install. An omitted report defaults to `MemorySearchReport.Unknown` upstream.

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

- All five callback params are `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No mutable state inside the public composable. The only `remember` is the `MutableInteractionSource` for the footer's indication-less `combinedClickable` (an internal helper of the `Footer` composable, not part of the public contract) and the defaulted `rememberModalBottomSheetState(...)` parameter, which the host can override.
- The model carries the current session's `MemorySearchReport` directly from `ThreadUiState.runConfig.memorySearch`. Replacing the report or changing conversations recomposes the sheet without retaining the previous provider list.

## Configuration

- **No new dependencies.** `ModalBottomSheet`, `FilledTonalButton`, `TextButton`, `combinedClickable`, `LocalClipboardManager`, `LocalHapticFeedback`, `TextOverflow.StartEllipsis` (Compose 1.7+, resolved by `composeBom = 2026.02.01`), and `Icons.Outlined.Add` / `Icons.Filled.Close` (already on the classpath via `material-icons-extended`, wired by [#131](../codebase/131.md)) all ship in the existing BOM. No `gradle/libs.versions.toml` edit.
- **No new string resources.** The section labels, memory states and three action labels remain inline; a first-localisation pass can migrate them together.

## Preview

Two `@Preview`s, one per theme, both `widthDp = 412` and `showBackground = true` — the dark variant adds `uiMode = Configuration.UI_MODE_NIGHT_YES`. Both render `ChannelInfoSheetContent(model = SAMPLE_MODEL, ...)` wrapped in `PyrycodeMobileTheme(darkTheme = …) { Surface(color = surfaceContainerLow, contentColor = onSurface) { Column(padding(top = 12.dp)) { … } } }` — identical wrap shape to [`WorkspacePickerSheet`](./workspace-picker-sheet.md)'s previews so the surface colour matches the runtime `ModalBottomSheet` container and the top spacer substitutes for the drag-handle gap.

`SAMPLE_MODEL` uses an explicit absent report, so the previews show `None` and `Install`. Provider and unknown states are exercised by `ThreadScreenChannelInfoTest`.

## Tests

None in #217 — that AC required only a `@Preview`. The precedent for stateless-component tests in this package is [`WorkspacePickerSheetTest.kt`](./workspace-picker-sheet.md#tests) (five `androidTest/` Compose UI tests targeting the `*Content` seam). The host-side coverage landed with the [#226](../codebase/226.md) thread-overflow host: a JVM unit test over the host's pure `ThreadUiState.toChannelInfoUiModel(now)` mapper (`ThreadScreenMapperTest` — labels, counts, em-dash fallbacks, with an injected `now`) and a shared Compose `ThreadScreenChannelInfoTest` that renders this sheet with `channelInfoOpen = true` and asserts the per-button event dispatch. Since [#227](../codebase/227.md) the **Archive** button emits `ThreadEvent.Archive` and **Delete** emits `ThreadEvent.Delete` (Rename / Change workspace still emit-then-dismiss; close still dismiss-only) — and three new dialog tests cover the `DeleteConfirmationDialog` (title + interpolated-body display, confirm **Delete** → `DeleteConfirm`, **Cancel** → `DeleteDismiss`). [#508](../codebase/508.md) adds `actions_section_is_hidden_when_mutations_unsupported` to `ThreadScreenChannelInfoTest` — drives `ThreadScreen` with `channelInfoOpen = true` and `channelInfoState().copy(mutationsSupported = false)`, asserts `"Rename"` / `"Change workspace"` / `"Archive"` / `"Delete"` each `assertDoesNotExist()` and `"About"` + the workspace path `assertIsDisplayed()` (read-only info survives). The existing tests use the state default `mutationsSupported = true` and continue to see the Actions section — no edit needed. **Since [#884](https://github.com/pyrycode/pyrycode-mobile/issues/884)** the Actions-heading half of that assertion is scoped to the sheet — `onNode(hasText("Actions") and hasAnyAncestor(isDialog())).assertDoesNotExist()`, with a preceding `onNode(hasText("About") and hasAnyAncestor(isDialog())).assertIsDisplayed()` proving the scope actually matches the sheet rather than matching nothing — because the [composer footer](thread-composer-footer.md) added its own unscoped `"Actions"` button, and a plain `onNodeWithText("Actions").assertDoesNotExist()` would fail on that unrelated node instead of proving this sheet's own section is hidden. This sheet's product behaviour is unchanged; the fix is test-only.

## Edge cases / limitations

- **Workspace value start-ellipsizes, not middle.** `TextOverflow.StartEllipsis` keeps the rightmost path segment visible — for `~/Workspace/Projects/KitchenClaw`, narrow widths collapse the leading prefix and preserve `…/KitchenClaw`. Fallback to `TextOverflow.MiddleEllipsis` only if a Compose-BOM resolution issue surfaces (none observed at `composeBom = 2026.02.01`).
- **Provider names are untrusted display text.** Keep their 120-character, one-line bound and do not turn them into links or log values. `ThreadScreenChannelInfoTest` checks the name bound, available and disabled states, unknown after a conversation change, absent-only Install, and its URL.
- **Delete cell carries no destructive emphasis.** Per AC and Figma `20:96`, the cell uses the same `FilledTonalButton` defaults as the other two — `secondaryContainer` / `onSecondaryContainer`, no `error`-tinted variant. The destructive-confirmation flow is owned by the host: since [#227](../codebase/227.md) tapping **Delete** emits `ThreadEvent.Delete`, which opens a Material 3 `DeleteConfirmationDialog` layered over this still-open sheet — only its confirm calls `repository.delete(...)`. (That confirm button is itself a plain `TextButton` with no `error` tint, per the #78 convention; a non-blocking NIT.)
- **Footer copy is long-press only.** Single-tap is wired (`onClick = {}` is intentional) but does nothing; the absence of a ripple (`indication = null`) is deliberate so the low-opacity footer doesn't paint a high-contrast ripple over itself. The haptic feedback on long-press is the user-facing confirmation that the copy fired.
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the caller doesn't need to wire either separately.

## Related

- Ticket notes: [`../codebase/217.md`](../codebase/217.md) (stateless composable), [`../codebase/226.md`](../codebase/226.md) (thread-overflow host), [`../codebase/227.md`](../codebase/227.md) (Archive/Delete wiring), [`../codebase/508.md`](../codebase/508.md) (`mutationsSupported` relay-mode gate hiding the Actions section)
- Spec: `docs/specs/architecture/217-channelinfosheet-stateless-composable.md`, `docs/specs/architecture/508-hide-unavailable-conversation-actions.md`
- Parent: split from [#144](https://github.com/pyrycode/pyrycode-mobile/issues/144) (Channel Info bottom sheet — host + sheet bundle).
- Sibling sheet: [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212) — same `ModalBottomSheet` shell + `*Content` body split, same `internal` visibility posture, same preview wrap. Worth reading first if you're picking up this file.
- Sibling stateless-component conventions: [`ConversationRow`](./conversation-row.md) (precedent for the `combinedClickable` long-press shape used by `Footer`), [`ConnectionBanner`](./connection-banner.md), [`ToolCallRow`](./tool-call-row.md).
- Downstream / open:
  - **Thread-overflow host ([#226](../codebase/226.md)) ✅** — wires this sheet into the thread overflow → **Channel info** entry point. [`ThreadScreen`](./thread-screen.md) owns visibility, assembles `ChannelInfoUiModel` from the conversation and current session report, and routes its actions. The absent-only `onInstallMemoryPlugin` opens the shared docs URL through `LocalUriHandler`.
  - **Channel List long-press host (open)** — the other channel-info entry point (long-press a channel row) is not yet wired.
  - Open: non-empty memory plugin layout — revisit when a real plugin source ships and a designer locks the precise list / chip / expander treatment against Figma `20:78`.
  - Open: destructive-emphasis on the host's `DeleteConfirmationDialog` confirm button — it ships as a plain `TextButton` (per the #78 convention) since [#227](../codebase/227.md); `MaterialTheme.colorScheme.error` on the confirm label is the M3 touch if the team ever wants it (a deferred, non-blocking NIT). The Delete *cell* on this sheet stays a plain `FilledTonalButton`.
  - Open: migrate `LocalClipboardManager` → `LocalClipboard` / `Clipboard.setClipEntry` once the legacy API is removed.
  - Open: localise the inline literals alongside the rest of `ui/conversations/components/`'s first `strings.xml` pass.

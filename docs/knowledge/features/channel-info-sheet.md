# ChannelInfoSheet

Stateless M3 modal bottom sheet (#217) that surfaces channel-level metadata and entry points for channel actions. Aligns with the dark Figma `20:48` reference inspected on 2026-09-29: a title row, an **About** section with a read-only **Folder** path, a **Session** section (#1346) showing the agent's reported version, permission mode and Claude's own cost estimate, a **Memory** section showing the current session's provider report, an **Actions** section, and a footer that long-press-copies the channel ID. The reference's Workspace row and Change workspace button conflict with the 2026-09-28 product decision and are omitted. The **thread-overflow** host landed in [#226](../codebase/226.md) (tap overflow → **Channel info** opens the populated sheet); the **Channel List long-press** entry point is still open. Memory search retrieves stored knowledge; provider detection does not mean the plugin captures this conversation or restores earlier context.

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
    val agent: ConversationAgent = ConversationAgent.Claude,
    val sessionFacts: SessionFacts? = null,
    val sessionCostUsd: Double? = null,
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

Single scrollable `Column(fillMaxWidth)` inside the `ModalBottomSheet`:

1. **`TitleRow(title = model.conversationName, onClose = onDismiss)`** — `Row(fillMaxWidth.padding(start = 16, end = 4, top = 4, bottom = 12))` with weighted `Text(titleLarge, onSurface)`, capped at two lines with ellipsis, + trailing `IconButton(Icons.Filled.Close, contentDescription = "Close", tint = onSurfaceVariant)`.
2. **`SectionHeader("About")`** — `labelLarge` on `onSurfaceVariant`, `padding(start = 24, end = 16, top = 12, bottom = 4)`. Identical helper shape to `WorkspacePickerSheet.SectionHeader`; deliberately not extracted into a shared file in this ticket — touch only this file.
3. **Five `AboutRow`s**: read-only `Folder` (with `valueIsPath = true`), `Created`, `Last activity`, `Total sessions`, `Total messages`. The model retains the `workspacePath` field name as its folder-path source. See "Row contracts" below.
4. **`SessionSection(model)`** (#1346) — `SectionHeader("Session")` then two or three rows. See "Session section" below.
5. **`SectionHeader("Memory")`** then **`MemoryRow(model.memorySearch, onInstall)`**. See "Memory row" below.
6. **`SectionHeader("Actions")`** then **`ActionsGrid`** — equal-width `Rename` and `Archive` cells above full-width `Delete`. Each cell is a `FilledTonalButton` with one-line text. The section is hidden when `mutationsSupported` is false; About, Session, Memory and the Channel-ID footer remain visible. No workspace action or Settings storage section appears.
7. **`Footer(channelId)`** — see "Footer + clipboard" below.
8. **`Spacer(height = 24.dp)`** — bottom inner padding above the system-inset that `ModalBottomSheet` already applies. The body scrolls vertically so actions and footer remain reachable with enlarged text.

### Row contracts

| Helper | Layout | Visual |
|---|---|---|
| `AboutRow(label, value)` | `Row(fillMaxWidth.padding(horizontal = 16, vertical = 8))` with equal weighted label and value; the value has 8 dp start padding | Right value right-aligns, wraps to two lines, then ellipsizes; numeric values render as `Int.toString()` at the call site |
| `AboutRow(label, value, valueIsPath = true)` | Same row, with an intrinsic-width `Folder` label and a weighted value using the remaining width after 16 dp spacing | Right text is `bodyMedium.copy(FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)` on `onSurfaceVariant`, `maxLines = 1`, `overflow = TextOverflow.StartEllipsis`, `TextAlign.End` — start-ellipsis keeps the leaf visible while the wider slot avoids truncating a path that fits |

### Session section (#1346)

Mirrors desktop's `ChannelInfoSheetView` Session block, drawn after About with the sheet's own `SectionHeader`/`AboutRow`-shaped `SessionRow` (no frame in Figma `20:48` draws it — the ticket says so, and the section reuses existing geometry and theme tokens only). It always renders, even with `model.sessionFacts == null` — only the cost row is conditional on `model.sessionCostUsd != null`. That asymmetry is why the section pushes Actions down by a fixed amount regardless of whether facts have arrived yet; see "A test that clicks below About scrolls first" below.

- **Version row**, labelled `"Claude version"` or `"Codex version"` by `model.agent` (`ConversationAgent.Codex` vs. everything else).
- **`"Reported permission mode"` row.** This is `SessionFacts.permissionMode` — Claude's own claim about what mode it is running under. It reaches this row only; `ThreadViewModel`'s `runningModel` flow (see [Thread composer footer](thread-composer-footer.md)) deliberately never reads it, and it is carried on `ThreadUiState.reportedSessionFacts`, **not** `ThreadRunConfig` — the Status sheet's permission control and the composer footer read `runConfig.permissionMode` (the settings reading) and can never see this claim. A `ThreadViewModel` test (`reportedFacts_reachTheStateButNotThePermissionReading`) pins the separation.
- **`"Cost (Claude's estimate)"` row**, present only when `model.sessionCostUsd != null`, showing `formatSessionCost(cost)` → `"$0.42 est."` (`%.2f`, `Locale.ROOT` — desktop's `toFixed(2)`). The "est." wording and the parenthetical label are load-bearing: the figure is Claude's own unverified running-session total from `turn_end.cost_usd_total` (see [Live-session events § `TurnEnd`](live-session-events.md)), never summed, never the app's accounting.

Both reported strings go through `internal fun reportedSessionValue(raw: String?, flaggedTruncated: Boolean): ReportedSessionValue` before reaching `Text`: ISO-control and Unicode-format code points (bidi overrides included) become spaces, the result is trimmed, then cut at 256 code points via `offsetByCodePoints` so a cut never splits a surrogate pair. An all-control or empty result reads as `text = null` → `SessionRow` shows `"Not reported"`. `truncated` is `true` when this cut fired *or* when `SessionFacts.truncatedFields` names the field (`claude_code_version` / `permission_mode`, matching the protocol's `session_facts` table) — either one shows a `"Truncated"` `labelSmall` line under the value. The cost row builds its `ReportedSessionValue` directly (`truncated = false`) since a formatted dollar figure is never cut. Same inert-rendering shape as [`UsageLimitIndicator`](usage-limit-indicator.md)'s `usageLimitStatusLabel`, independently implemented here because the cut length and format-character rule differ — `CHANNEL_INFO_AGENT_VERSION_TAG` / `CHANNEL_INFO_SESSION_COST_TAG` `testTag`s are the device suites' handles onto the two values.

**A test that clicks below About in `ChannelInfoSheetContent` scrolls to it first.** At Robolectric's 320×731dp, the always-drawn Session header and its two "Not reported" rows push Actions' "Delete" off-viewport, and `performClick()` on an off-screen node misses. `ThreadScreenChannelInfoTest.tapping_delete_emits_delete` and step 7 of `InteractiveStreamE2ETest.interactiveTurn_deleteConversation_removesFromListAndClosesThread` both call `performScrollTo()` before the click now; the plan's `## Revisions` records this as the contract going forward. As of landing, the sheet's `Install`/`Rename`/`Archive` taps in `ThreadScreenChannelInfoTest` do **not** yet follow the contract — they still pass because nothing pushes them further down, but a later section inserted above them (desktop parity work tends to add one) would need the same `performScrollTo()` fix.

`CLAUDE_CODE_VERSION_FIELD` is a private const duplicated between this file and `ThreadViewModel.kt` (its `runningModel`'s own `reportedText` cut uses the identical field name) — left unshared on purpose, since sharing it would add a dependency from the view model on this file for one string literal.

### Memory row

Outer `Row(fillMaxWidth.padding(horizontal = 16, vertical = 8), Arrangement.SpaceBetween)` keeps the `Memory plugins` label and right-aligned value. It branches on the report rather than the length of a plugin list:

- **Confirmed absent, with no installed provider** — `None` and the `Install` text button. Below 360 dp width or at font scale 1.3 and above, `None` sits above the button in the right slot. The button opens the shared memory-plugin docs URL.
- **Providers reported** — one right-aligned name and status per provider. The daemon's display name is inert text, capped at 120 characters and two lines with ellipsis; a blank name becomes `Unnamed provider`. Status may wrap to two lines. `Not installed` and `Disabled` take precedence over effective availability. Only installed, enabled and available renders `Memory search available`; installed and unavailable renders `Memory search unavailable`, and a remaining unknown state renders `Status unknown`. A listed provider never gets an Install control.
- **No providers, without confirmed absence** — `Status unknown` for unknown or omitted reports; otherwise `Memory search unavailable`. Neither state says `None` or offers Install. An omitted report defaults to `MemorySearchReport.Unknown` upstream.

### Footer + clipboard

`Box(fillMaxWidth.padding(start = 24, end = 24, top = 24).combinedClickable(...))` wrapping `Text("Channel ID: $channelId", bodySmall.copy(FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp), onSurfaceVariant, Modifier.alpha(0.5f))`. Combined-clickable uses `indication = null` (no ripple over the low-opacity text), `onClick = {}` (intentional — AC restricts copy to long-press), and `onLongClick` that calls `clipboard.setText(AnnotatedString(channelId))` on `LocalClipboardManager.current` followed by `haptics.performHapticFeedback(HapticFeedbackType.LongPress)` on `LocalHapticFeedback.current`. `combinedClickable` requires `@OptIn(ExperimentalFoundationApi::class)` on the enclosing private `Footer` composable (same import as the precedent in [`ConversationRow.kt`](./conversation-row.md)).

`LocalClipboardManager` is the legacy stable Compose API; the spec explicitly retains it over the newer `LocalClipboard` / `Clipboard.setClipEntry`. The deprecation warning at the call site is expected and accepted — migration is a follow-up the day the legacy API is removed, not now.

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` | Explicit `ModalBottomSheet.containerColor` and preview surface |
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
| Roboto Mono 12sp | `bodyMedium.copy(FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)` | Read-only Folder path value |
| Roboto Mono 11sp | `bodySmall.copy(FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)` | Footer `Channel ID: …` line |

`ActionCell` uses `FilledTonalButton` defaults for shape and colors — the M3 defaults produce `secondaryContainer` / `onSecondaryContainer` and the reference's pill shape. The sheet supplies a custom centered 32 × 4 dp drag handle with 12 dp above and 8 dp below; the default M3 handle area placed the title about 20 px too low in a device capture. The handle uses `onSurfaceVariant` at 40% opacity. The final 412 × 892 capture places the sheet top within 4 px of the bottom-aligned reference.

## Recomposition / stability

- All five callback params are `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No product state inside the public composable. The body remembers only its scroll position; the footer remembers a `MutableInteractionSource` for indication-less `combinedClickable`, and the shell defaults `rememberModalBottomSheetState(...)`, which the host can override.
- The model carries the current session's `MemorySearchReport` directly from `ThreadUiState.runConfig.memorySearch`. Replacing the report or changing conversations recomposes the sheet without retaining the previous provider list.

## Configuration

- **No new dependencies.** `ModalBottomSheet`, `FilledTonalButton`, `TextButton`, `combinedClickable`, `LocalClipboardManager`, `LocalHapticFeedback`, `TextOverflow.StartEllipsis` (Compose 1.7+, resolved by `composeBom = 2026.02.01`), and `Icons.Outlined.Add` / `Icons.Filled.Close` (already on the classpath via `material-icons-extended`, wired by [#131](../codebase/131.md)) all ship in the existing BOM. No `gradle/libs.versions.toml` edit.
- **No new string resources.** The section labels, memory states and three action labels remain inline; a first-localisation pass can migrate them together.

## Preview

Two `@Preview`s, one per theme, both `widthDp = 412` and `showBackground = true` — the dark variant adds `uiMode = Configuration.UI_MODE_NIGHT_YES`. Both render `ChannelInfoSheetContent(model = SAMPLE_MODEL, ...)` wrapped in `PyrycodeMobileTheme(darkTheme = …) { Surface(color = surfaceContainerLow, contentColor = onSurface) { Column(padding(top = 12.dp)) { … } } }` — identical wrap shape to [`WorkspacePickerSheet`](./workspace-picker-sheet.md)'s previews so the surface colour matches the runtime `ModalBottomSheet` container and the top spacer substitutes for the drag-handle gap.

`SAMPLE_MODEL` uses an explicit absent report, so the previews show `None` and `Install`. Provider and unknown states are exercised by `ThreadScreenChannelInfoTest`.

The [committed visual evidence](../../../app/src/androidTest/assets/channel-info-1266/channel-info-comparison.png) places the Figma render beside a dark emulator capture and labelled 50% overlay at 412 × 892. The reference node is 412 × 596, bottom-aligned in that viewport. The isolated Figma node rounds its bottom corners; the Android sheet meets the viewport bottom. Compact 320 × 692 provider and enlarged-text captures sit beside the comparison.

## Tests

`ThreadScreenChannelInfoTest` checks the populated Folder row, absence of retired workspace controls, memory availability and provider states, supported callbacks, and the `mutationsSupported` gate. The Actions-heading absence assertion is scoped to the sheet dialog because the composer also has an unrelated Actions control. At compact width and enlarged text, shared Compose tests check that bounded values remain readable and the close, install and action controls remain reachable.

`ChannelInfoSessionSectionTest` (#1346, Robolectric, `app/src/sharedTest`) checks the Session section: the "Claude version" vs. "Codex version" label by agent; "Not reported" for absent facts and for an all-control-character value; a 300-code-point value cut to 256 with a surrogate pair intact at the boundary plus the "Truncated" tag; `truncatedFields` tagging each row independently of the local cut; the cost row rendering `"$0.42 est."` when present and absent when `null`; and that control characters render inert. `reportedSessionValue` / `formatSessionCost` are asserted directly as pure helpers in the same class. `TurnEndPayloadsTest` and `ThreadViewModelSessionReadingsTest` cover the decode and the lifetime of `sessionCostUsd` — see [Live-session events § `TurnEnd`](live-session-events.md).

`ChannelInfoCaptureTest` renders the real sheet on the managed Android 13 emulator. Its [result XML](../../../app/src/androidTest/assets/channel-info-1266/device-results.xml) records three executed tests with no failures or skips. The [412 × 892 comparison](../../../app/src/androidTest/assets/channel-info-1266/channel-info-comparison.png) and 320 × 692 provider and enlarged-text captures provide visual checks for sheet geometry and clipping. The capture also exposed the default handle spacing and equal-width Folder truncation, both corrected in the implementation; content assertions alone did not reveal them.

## Edge cases / limitations

- **Folder value start-ellipsizes, not middle.** `TextOverflow.StartEllipsis` keeps the rightmost path segment visible — for `~/Workspace/Projects/KitchenClaw`, narrow widths collapse the leading prefix and preserve `…/KitchenClaw`. Fallback to `TextOverflow.MiddleEllipsis` only if a Compose-BOM resolution issue surfaces (none observed at `composeBom = 2026.02.01`).
- **Provider names are untrusted display text.** Keep their 120-character, two-line bound and do not turn them into links or log values. `ThreadScreenChannelInfoTest` checks the name bound, available and disabled states, unknown after a conversation change, absent-only Install, and its URL.
- **Delete cell carries no destructive emphasis.** Per AC and Figma `20:96`, the cell uses the same `FilledTonalButton` defaults as the other two — `secondaryContainer` / `onSecondaryContainer`, no `error`-tinted variant. The destructive-confirmation flow is owned by the host: since [#227](../codebase/227.md) tapping **Delete** emits `ThreadEvent.Delete`, which opens a Material 3 `DeleteConfirmationDialog` layered over this still-open sheet — only its confirm calls `repository.delete(...)`. (That confirm button is itself a plain `TextButton` with no `error` tint, per the #78 convention; a non-blocking NIT.)
- **Footer copy is long-press only.** Single-tap is wired (`onClick = {}` is intentional) but does nothing; the absence of a ripple (`indication = null`) is deliberate so the low-opacity footer doesn't paint a high-contrast ripple over itself. The haptic feedback on long-press is the user-facing confirmation that the copy fired.
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the caller doesn't need to wire either separately.

## Related

- Ticket notes: [`../codebase/217.md`](../codebase/217.md) (stateless composable), [`../codebase/226.md`](../codebase/226.md) (thread-overflow host), [`../codebase/227.md`](../codebase/227.md) (Archive/Delete wiring), [`../codebase/508.md`](../codebase/508.md) (`mutationsSupported` relay-mode gate hiding the Actions section)
- Spec: `docs/specs/architecture/217-channelinfosheet-stateless-composable.md`, `docs/specs/architecture/508-hide-unavailable-conversation-actions.md`
- Parent: split from [#144](https://github.com/pyrycode/pyrycode-mobile/issues/144) (Channel Info bottom sheet — host + sheet bundle).
- Sibling sheet: [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212) — same `ModalBottomSheet` shell + `*Content` body split, same `internal` visibility posture, same preview wrap. Worth reading first if you're picking up this file.
- Sibling stateless-component conventions: [`ConversationRow`](./conversation-row.md) (precedent for the `combinedClickable` long-press shape used by `Footer`), [`ConnectionBanner`](./connection-banner.md), [`ToolCallRow`](./tool-call-row.md).
- Session section ([#1346](https://github.com/pyrycode/pyrycode-mobile/issues/1346)): [Live-session events](live-session-events.md) (the `turn_end.cost_usd_total` decode `sessionCostUsd` is built from), [`UsageLimitIndicator`](usage-limit-indicator.md) (the inert-text precedent `reportedSessionValue` independently follows).
- Downstream / open:
  - **Thread-overflow host ([#226](../codebase/226.md)) ✅** — wires this sheet into the thread overflow → **Channel info** entry point. [`ThreadScreen`](./thread-screen.md) owns visibility, assembles `ChannelInfoUiModel` from the conversation and current session report, and routes its actions. The absent-only `onInstallMemoryPlugin` opens the shared docs URL through `LocalUriHandler`.
  - **Channel List long-press host (open)** — the other channel-info entry point (long-press a channel row) is not yet wired.
  - Open: non-empty memory plugin layout — revisit when a real plugin source ships and a designer locks the precise list / chip / expander treatment against Figma `20:78`.
  - Open: destructive-emphasis on the host's `DeleteConfirmationDialog` confirm button — it ships as a plain `TextButton` (per the #78 convention) since [#227](../codebase/227.md); `MaterialTheme.colorScheme.error` on the confirm label is the M3 touch if the team ever wants it (a deferred, non-blocking NIT). The Delete *cell* on this sheet stays a plain `FilledTonalButton`.
  - Open: migrate `LocalClipboardManager` → `LocalClipboard` / `Clipboard.setClipEntry` once the legacy API is removed.
  - Open: localise the inline literals alongside the rest of `ui/conversations/components/`'s first `strings.xml` pass.

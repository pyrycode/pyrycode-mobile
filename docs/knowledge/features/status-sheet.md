# StatusSheet

Run configuration is a stateless [shared mobile modal](mobile-modal.md) opened from the
[composer footer](thread-composer-footer.md). Its body shows published Model and Effort radio
choices, separate reported readings, then the retained Permission control. The earlier
bottom-sheet implementation is historical.

## Shape

Since #1195, `StatusSheet` uses `MobileDismissModal(title = "Run configuration", actionLabel = "Done")`. The shell supplies the header, separator, close control, scrolling body and pinned Done footer. `StatusSheetContent` renders the body only; the old `SheetState` parameter and bottom-sheet chrome are gone. Close, Done and Back call `onDismiss` without a settings write.

```kotlin
@Composable
fun StatusSheet(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String?,
    onModelSelected: (String) -> Unit,
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    pending: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    effortNote: String? = null,
    running: ThreadRunningModel = ThreadRunningModel(),
    contextPercent: Int? = null,
    permissionMode: String = "",
    permissionChoices: List<Pair<String, String>> = emptyList(),
    onPermissionSelected: (String) -> Unit = {},
    permissionPending: Boolean = false,
    modelSelectionNote: String? = null,
)
```

**`running: ThreadRunningModel = ThreadRunningModel()` ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891))** — what claude announced it is running for the latest turn (`model`) and, when reported, its build (`build`), both already-inert [`ThreadReportedText`](thread-composer-footer.md#running-model-891)`?` values assembled by the ViewModel from [#890](https://github.com/pyrycode/pyrycode-mobile/issues/890)'s `observeAnnouncedModel` / `observeSessionFacts` readings. `model == null` is the sheet's cue to render the unavailable note — it is never inferred from `selectedModel` or `enabled`, and defaulting the parameter to `ThreadRunningModel()` (both fields `null`) means an unwired caller renders the honest unavailable state, not a blank space. See [§ `RunningModelSection`](status-sheet-readings.md#runningmodelsection).

**`contextPercent: Int? = null` ([#946](https://github.com/pyrycode/pyrycode-mobile/issues/946))** — Claude's reported context-window `percentage`, verbatim, from the same `ThreadRunConfig.contextPercent` the [composer footer's `Cxt:` segment](thread-composer-footer.md#context-usage-segment-946) reads, so the two surfaces cannot disagree. `null` is the unavailable state — never `0`, and never derived from any token count. See [§ `ContextWindowSection`](status-sheet-readings.md#contextwindowsection).

**`effortNote: String? = null` ([#889](https://github.com/pyrycode/pyrycode-mobile/issues/889))** — the resolved [`EffortNote`](thread-composer-footer.md#applied-effort-889) string, or `null` when nothing needs explaining. `ThreadScreen` passes `state.runConfig.effortNote?.text(state.agent)` ([#1115](https://github.com/pyrycode/pyrycode-mobile/issues/1115) added the `agent` argument, naming the conversation's own agent instead of a fixed "Claude"); the sheet itself never sees the enum or touches a daemon-sourced string here, only the client-owned resource text the caller already resolved. Rendered as one `Caption` below the effort chips — see [§ `EffortChipRow`](#effortchiprow).

**[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed `yoloEnabled` and `onYoloToggled`.** The later #1196 Run configuration layout takes `permissionMode`, `permissionChoices`, `onPermissionSelected` and `permissionPending` instead.

**Through [#807](../codebase/807.md)** this signature took `selectedModel: Model`, `onModelSelected: (Model) -> Unit`, `selectedEffort: Effort` and `onEffortSelected: (Effort) -> Unit` — the sheet iterated `Model.entries` / `Effort.entries` directly. **#807 deleted every reference to those enums from this file.** The sheet is now a dumb renderer of pre-sanitized choices the ViewModel assembles from the daemon's own readings ([`ThreadModelChoice`](thread-composer-footer.md#sourcing) / `ThreadEffortChoice`, both defined in `ThreadViewModel.kt`); `StatusSheet.kt` imports nothing from `data/` and never sees a raw `ModelMenuRow`.

- **Public** (no `internal`) — same posture as the public shell in [`WorkspacePickerSheet`](workspace-picker-sheet.md) post-#220. The sheet has one production consumer ([`ThreadScreen`](thread-screen.md)) but the visibility decision tracks the sibling sheets, not consumer count.
- **No nullable callbacks** — every section in this sheet (four post-[#230](../codebase/230.md)) always renders, so all callbacks are always wired.
- **`choices: List<ThreadModelChoice>` and `selectedModel: String?`** — ordinary published rows in conversation-agent order, with the `default` row omitted. `ThreadScreen` passes `runConfig.selectedChoice?.value`, so a missing settings reply, unmatched explicit identifier, or unresolved inherited choice marks no radio. A pending or confirmed explicit value selects only a row with exactly the same raw `value`; a pending `default` is not inherited. For confirmed Claude inheritance (`""` or `"default"`), `selectedChoice` resolves the hidden default row's nonempty, non-placeholder `resolvedModel` only when exactly one ordinary row in the *full* published menu has the same concrete identifier; checking only the 32 rendered rows could falsely select one when another match is hidden. Codex has no inherited resolution. The separate Running model reading never decides this selection. `effortChoices` follows the saved or pending row's own levels, or the hidden default metadata for inheritance, even if no visible row resolves.
- **`modelSelectionNote: String?`** — when settings are confirmed but no visible row can represent the choice, the sheet shows the unmatched identifier as inert text or the client-owned "Model unavailable" for unresolved inheritance. Before settings arrive it shows no selection note or marked radio. The published `default` row remains available internally for inherited effort and permission support, not as a radio or write option.
- **`menuAvailable: Boolean` and `notListedModels: Int` (#807)** — the [#601](../codebase/601.md) honest-unavailable idiom extended to the Model section: `menuAvailable = false` renders "Model list unavailable"; `true` with empty `choices` renders "This server published no selectable models." (a different, equally legal reading). `notListedModels` sums the producer's own `droppedModels` (never recomputed) and this client's own render cap (`hiddenChoices`, § below) — reported, never inferred from `choices.size`.
- **`pending: Boolean` and `enabled: Boolean` (#807)** — `pending` is true while a model/effort write has been sent but not yet confirmed by a fresh settings reading; the Model and Effort section headers gain a `"· applying…"` suffix and their controls disable. `enabled` is the read-only gate: `false` when `SessionSettings.sessionId` is `""` (the daemon has no session to address), disabling the same controls with no suffix. The two compose independently (`enabled && !pending`).
 **No `tokenPercent` / `tokensUsed` / `tokensTotal` parameters.** [#230](../codebase/230.md) added them as `Int = 0`-defaulted display fields; [#601](../codebase/601.md) removed all three along with the render they fed, because the daemon had not yet served mobile a real figure and a hardcoded `73%` could never warn an operator about an actually-filling context window. Through #601 `ThreadUiState` still carried `tokenPercent` / `tokensUsed` / `tokensTotal` (populated from `STUB_TOKEN_PERCENT = 73` / `STUB_TOKENS_USED = 146_000` / `STUB_TOKENS_TOTAL = 200_000` at the VM) — the sheet simply stopped reading them, which is what let #601 ship without a `ThreadViewModel` edit. [#603](../codebase/603.md) has since deleted the fields and the `STUB_*` constants outright, once both #601 and [#602](../codebase/602.md) (the retired status row, see [Thread composer footer](thread-composer-footer.md)) had stopped reading them. [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) split into [#945](https://github.com/pyrycode/pyrycode-mobile/issues/945) (the daemon reading, `ConversationRepository.observeContextUsage`) and this ticket, [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946), which re-added exactly one parameter — `contextPercent: Int?`, Claude's own reported percentage, never a token count — see [§ `ContextWindowSection`](status-sheet-readings.md#contextwindowsection) below; token figures and a severity bar remain a deliberate, documented divergence from Figma node `20:151`, not a re-add of the deleted stub fields.

A peer `internal` composable carries the body, mirroring `StatusSheet`'s full parameter list:

```kotlin
@Composable
internal fun StatusSheetContent(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String?,
    onModelSelected: (String) -> Unit,
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    pending: Boolean,
    enabled: Boolean,
    effortNote: String? = null,
    running: ThreadRunningModel = ThreadRunningModel(),
    contextPercent: Int? = null,
    permissionMode: String = "",
    permissionChoices: List<Pair<String, String>> = emptyList(),
    onPermissionSelected: (String) -> Unit = {},
    permissionPending: Boolean = false,
    modelSelectionNote: String? = null,
)
```

The shell and body remain separate so previews and Compose tests can render the body directly.

## What it does

The current group order is Model, Effort, Running model, Context window, then the retained Permission section. Model rows and two-column Effort rows use `selectableGroup` and row-level radio semantics around a 20 dp tertiary circle; the circle adds no visible control padding. Only published choices appear, in supplied order, with the confirmed or pending visible choice marked. There is no Default row. The shared scroll area accommodates compact widths, enlarged text and long lists while Done stays pinned. Each selection invokes its existing callback once, then the host dismisses the modal.

The [current dark Figma frame](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=600-1694) shows the designed sections at 412 × 892. It has no Permission, pending, rejection, long-list or unavailable-reading state; production preserves those behaviours in the modal. The 2026-09-28 emulator and Figma captures and [labelled overlay](../../../../app/src/androidTest/assets/status-1195/figma-emulator-overlay.png) compare the same logical viewport. Aligning below Android's status bar exposed the accumulated section-spacing difference; 34 dp Compose group spacing aligned the rendered section positions. Android system bars and Permission remain visible differences.

### `pending` and `enabled` (#807)

`sectionTitle(base, pending) = if (pending) "$base · applying…" else base` — a file-private helper applied to the Model and Effort headers only, so a tap that has been sent but not yet confirmed by a fresh settings reading is visible in the section title itself, not just inferred from greyed controls. Both sections' `enabled` argument is `enabled && !pending`: `enabled = false` (an empty `SessionSettings.sessionId` — the daemon has no session to address) disables the controls with no suffix; `pending` disables them **with** the suffix. The two states compose rather than being mutually exclusive.

### `ModelSection`

```kotlin
@Composable
private fun ModelSection(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String?,
    selectionNote: String?,
    onModelSelected: (String) -> Unit,
    enabled: Boolean,
)
```

`selectionNote` renders above the rows when settings are available but the confirmed or pending value cannot be represented; the note is inert text, never a selectable row. No menu renders `"Model list unavailable"`; a published menu with no ordinary rows renders `"This server published no selectable models."` Otherwise the `selectableGroup` contains only ordinary rows, each `selected = choice.value == selectedModel`, where `selectedModel` is the resolved visible choice or `null`. A positive `notListedModels` adds the reported shown/not-listed caption; the count is not inferred from `choices.size`.

### `ModelRow`

```kotlin
@Composable
private fun ModelRow(
    choice: ThreadModelChoice,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
)
```

`Row(Modifier.fillMaxWidth().selectable(selected, enabled, onClick, role = Role.RadioButton).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.Top)` with three children:

1. `RadioButton(selected = selected, enabled = enabled, onClick = null)` — default M3 colors. **`onClick = null` is required**, not optional — the row's `selectable` modifier owns the click semantics; passing the handler to both fires the callback twice. The lint-clean shape is `onClick = null` on the inner `RadioButton`.
2. `Spacer(width = 12.dp)`.
3. `Column` with two `Text`s:
   - Title `Text(text = choice.label, style = bodyLarge, color = onSurface, maxLines = 2, overflow = Ellipsis)` — Claude uses the ASCII family from the raw published `value` (one `claude-` prefix removed, leading ASCII letters, initial capital), falling back to inert `displayName` if no family derives. Codex uses inert published `displayName` verbatim. The derived family is bounded and made inert too; the raw value remains the write argument. `maxLines` guards the layout.
   - Detail `Text(text = choice.detail, style = bodySmall, color = onSurfaceVariant, maxLines = 1, overflow = Ellipsis)`, rendered only `if (choice.detail.isNotEmpty())` — `choice.detail` is the row's own `resolvedModel`, inert, and blanked by the ViewModel when it says nothing `choice.label` doesn't already say. Replaces the pre-#807 private `Model.description()` mapping table (below) — the row's own `resolvedModel` is the honest equivalent: what this family currently resolves to, rather than three Figma-derived taglines for three device enum entries that no longer drive this sheet.

`Alignment.Top` (not `CenterVertically`) is load-bearing: the detail text wraps to multiple lines on narrow widths; centring the radio against the wrapped block looks broken. Top-aligning the radio to the title baseline keeps the visual anchor stable across detail heights.

### `Modifier.selectable(role = Role.RadioButton)` on the row, not on the radio

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
        .padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.Top,
) {
    RadioButton(selected = selected, enabled = enabled, onClick = null)
    // ...
}
```

This is the canonical M3 shape for a radio row whose tappable region is the whole row, not just the radio control. Two reasons:

1. **Touch target.** The radio circle is ~20dp; the row (title + description + padding) is ~56dp+. Without `selectable` on the row, taps outside the radio do nothing.
2. **Semantics.** `role = Role.RadioButton` on `selectable` flows TalkBack semantics through the parent — the row is announced as a radio button, and the inner `RadioButton(onClick = null)` doesn't double-announce.

### Model descriptions — retired by #807

Through [#807](../codebase/807.md) `ModelRow` rendered a second line from a private file-local `Model.description(): String` extension, a fixed three-entry mapping table (`OPUS_4_7 → "best for complex work"`, `SONNET_4_6 → "faster, cheaper"`, `HAIKU_4_5 → "fastest"`) per Figma nodes `20:117/122/127`. **#807 deleted the extension and the table.** There is no fixed vocabulary left to map descriptions onto; `ThreadModelChoice.detail` (the row's own `resolvedModel`, made inert) is the honest replacement — see [§ `ModelRow`](#modelrow) above.

### `RunningModelSection` (#891)

Split into [StatusSheet — running model and context window readings](status-sheet-readings.md) on
2026-09-24 to keep this document under the 50000-byte cap the docs guard enforces. Moved there verbatim.

### `EffortRadioRows`

The selected model's published effort levels appear in supplied order, two radio rows per
line. An empty list states that no levels were published. Row-level selection semantics
surround the 20 dp visual mark; the existing effort note appears beneath the rows.

### `YoloRow` — retired by #650

Through [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) this file drew a `YoloRow` composable between Effort and Context window: a full-width `toggleable` row (`Role.Switch`) with a two-line label and an M3 `Switch(onCheckedChange = null)`. It carried a known, unfixed gap since [#807](../codebase/807.md) — its own `enabled` parameter was overloaded to mean the switch's checked state, not whether the control was interactive, so the switch never visibly disabled on a read-only session or during a pending model/effort write. **#650 deleted `YoloRow`, its call site, its section header and the `androidx.compose.foundation.selection.toggleable` / `androidx.compose.material3.Switch` imports outright** — the gap closed by removing the row rather than by threading `enabled`/`pending` through it. The daemon-confirmed permission mode later returned to Run configuration in #1196. The intervening footer implementation used a separate gate: its `footerControlEnabled(Permission, …)` reads `runConfig.writable` and `pendingPermission` from the start.

### `ContextWindowSection`

Split into [StatusSheet — running model and context window readings](status-sheet-readings.md) on
2026-09-24 to keep this document under the 50000-byte cap the docs guard enforces. Moved there verbatim,
including the `#601`-era `formatTokens` / `progressColor` deletion note.

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex, no custom text styles. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` (sheet bg) | `ModalBottomSheet` default |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title, row titles, Context window label |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Section header, model row details, close icon, unselected `FilterChip` label, Context window caption |
| `Schemes/secondary-container` | `colorScheme.secondaryContainer` | Selected `FilterChip` background |
| `Schemes/on-secondary-container` | `colorScheme.onSecondaryContainer` | Selected `FilterChip` label |
| `Schemes/outline` | `colorScheme.outline` | Unselected `FilterChip` 1dp border |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Title Large` | `typography.titleLarge` | `"Run configuration"` |
| `Static/Label Large` | `typography.labelLarge` | `"Model"` / `"Effort"` / `"Context window"` section headers |
| `Static/Body Large` | `typography.bodyLarge` | Model row titles, Context window label |
| `Static/Body Small` | `typography.bodySmall` | Model row details, Context window caption |

[#601](../codebase/601.md) removed four rows this file no longer draws: `surface-container-highest` (progress-bar track), `primary` / `warning` / `error` (progress-bar fill, threshold-driven). `warning` was still consumed elsewhere through #601 — `ThreadStatusRow`'s `tokenPercentColor` threshold helper — but [#602](../codebase/602.md) has since deleted that helper too, so `warning` no longer has a consumer in this feature area; see [warning-color.md](warning-color.md) for its other uses.

## Recomposition / stability

- All three callback params (`onModelSelected`, `onEffortSelected`, `onDismiss`) are `(T) -> Unit` / `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed `onYoloToggled` with no successor callback.
- No internal mutable state, no `LaunchedEffect`, no `DisposableEffect`, no `rememberSaveable`. The only `remember` is the defaulted `rememberModalBottomSheetState(...)` parameter (plus, since [#807](../codebase/807.md), `rememberScrollState()` for the body's `verticalScroll`), which the host can override.
- **Since [#807](../codebase/807.md):** `selectedModel: String?`, `selectedEffort: String` and the `choices` / `effortChoices` lists replace the pre-#807 `selectedModel: Model` / `selectedEffort: Effort` enum pair. `String` and `List<ThreadModelChoice>` / `List<ThreadEffortChoice>` (both immutable `data class`es) are Compose-stable, so the row / chip composables still skip recomposition when their inputs are unchanged — the stability property is preserved, only the types moved off the device enums.
- **From [#601](../codebase/601.md) to [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946), `ContextWindowSection()` was parameterless and read only `MaterialTheme`**, recomposing on theme change alone. [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) re-added one `Int?` parameter (`contextPercent`) — a stable primitive-nullable type, so the section now also recomposes when the reading changes, which is the correct behaviour (a live reading is not a constant, unlike the deleted `tokenPercent` stub was). No lambda captures, no unstable types, no `remember` needed. `LinearProgressIndicator`, its deferred `progress = { lambda }` read, and the `progressColor` / `formatTokens` helpers that fed it stay gone — #946 did not restore them; see [§ `ContextWindowSection`](status-sheet-readings.md#contextwindowsection).
- **`ThreadRunningModel` / `ThreadReportedText` ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891))** are both immutable `data class`es (`ThreadUiState.kt`), Compose-stable like the pre-existing `ThreadModelChoice` / `ThreadEffortChoice`; `RunningModelSection` recomposes only when the announced model or build actually changes, no `remember` needed.

## Configuration

- **No new dependencies.** `ModalBottomSheet` + `rememberModalBottomSheetState` + `SheetState` + `RadioButton` + `FilterChip` all ship in `androidx.compose.material3` already in the BOM (`composeBom = 2026.02.01`); `Modifier.selectable` / `selectableGroup` are in `androidx.compose.foundation.selection`; `Icons.Filled.Close` is in `material-icons-extended`. No `gradle/libs.versions.toml` edit across [#254](../codebase/254.md), [#229](../codebase/229.md), [#230](../codebase/230.md), [#601](../codebase/601.md), [#807](../codebase/807.md), [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891), or [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946). [#601](../codebase/601.md) removed the `LinearProgressIndicator` import (with its `gapSize` / `drawStopIndicator` params) — no dependency change, just an unused import gone; [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) does not restore it. [#807](../codebase/807.md) added `Modifier.verticalScroll` + `rememberScrollState()`, both already in `androidx.compose.foundation` — no new dependency. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed the `Switch` (`androidx.compose.material3`) and `toggleable` (`androidx.compose.foundation.selection`) imports with `YoloRow` — no new dependency, one gone. [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891) added `AnnotatedString` / `buildAnnotatedString` / `withStyle` / `SpanStyle` / `FontStyle` (`androidx.compose.ui.text*`) and `Modifier.testTag` (`androidx.compose.ui.platform`) — both already in the Compose UI artifact, no new dependency.
- **String resources.** Through [#889](https://github.com/pyrycode/pyrycode-mobile/issues/889) every string in this file was an inline literal (`"Run configuration"`, `"Model"`, `"Effort"`, `"Context window"`, the Context-window caption, `"Close"`, the [#807](../codebase/807.md) fallback copy, and the [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) permission strings this file no longer carries); `effortNote` was already resolved by the caller, no `stringResource` call in this file. **[#891](https://github.com/pyrycode/pyrycode-mobile/issues/891) is the file's first `strings.xml` extraction**, not one more inline literal: `status_sheet_running_model` (`"Running model"`), `status_sheet_running_model_unavailable` (`"Not announced yet"`), `status_sheet_running_build` (`"Claude Code %1$s"`), `status_sheet_running_truncated` (`" (truncated)"`) — a claude-authored value is a format *argument* to the last two, never the format string itself. **[#946](https://github.com/pyrycode/pyrycode-mobile/issues/946)** moved the Context-window label off its pre-#946 inline literal too, into `status_sheet_context_used` (`"%1$d%% used"`, `contextPercent` as the format argument) and `status_sheet_context_unavailable` (`"Context usage unavailable"`) — the same five-string batch that added `thread_footer_context` / `thread_footer_context_unavailable` / `cd_context_usage` / `cd_context_usage_unavailable` for the [composer footer's segment](thread-composer-footer.md#context-usage-segment-946).

## Hosting in `ThreadScreen`

Split into [StatusSheet — hosting, tests and edge cases](status-sheet-hosting-tests-and-edge-cases.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — Hosting in `ThreadScreen`, Preview, Tests and Edge cases / limitations — moved there verbatim, headings and anchors intact.

## Related

- [StatusSheet — running model and context window readings](status-sheet-readings.md) — `RunningModelSection` (#891) and `ContextWindowSection` (#946), split out to keep this document under the size cap.
- Ticket notes: [`../codebase/254.md`](../codebase/254.md) (shell + Model section), [`../codebase/229.md`](../codebase/229.md) (Effort +, since retired, YOLO sections), [`../codebase/230.md`](../codebase/230.md) (Context window section, stub), [`../codebase/601.md`](../codebase/601.md) (Context window section, "unavailable" render), [`../codebase/807.md`](../codebase/807.md) (Model + Effort sections re-sourced off the daemon's `observeSessionSettings` + `observeModelMenu` readings, retiring the `Model` / `Effort` device enums from this file entirely). YOLO section retirement: [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), spec `docs/specs/architecture/650-composer-permission-mode.md`. Applied-effort explanation caption: [#889](https://github.com/pyrycode/pyrycode-mobile/issues/889), which owns the `EffortNote` design — see [Thread composer footer § Applied effort](thread-composer-footer.md#applied-effort-889). Running model section: [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891), spec `docs/specs/architecture/891-status-sheet-running-model.md`; sources off [#890](https://github.com/pyrycode/pyrycode-mobile/issues/890)'s `observeAnnouncedModel` / `observeSessionFacts` readings — see [Conversation repository](conversation-repository.md) and [Thread composer footer § Running model](thread-composer-footer.md#running-model-891) for `reportedText` / `ThreadReportedText` / `ThreadRunningModel`. Context window section's reported percentage: [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946), spec `docs/specs/architecture/946-context-usage-footer.md`; sources off [#945](https://github.com/pyrycode/pyrycode-mobile/issues/945)'s `observeContextUsage` — see [Thread composer footer § Context usage segment](thread-composer-footer.md#context-usage-segment-946), the sibling surface reading the identical value.
- Specs: `docs/specs/architecture/254-statussheet-scaffold-model-section.md`, `docs/specs/architecture/229-statussheet-effort-yolo.md`, `docs/specs/architecture/230-status-sheet-context-window.md`, `docs/specs/architecture/601-status-sheet-context-usage-unavailable.md`, `docs/specs/architecture/807-thread-run-config-from-daemon.md`, `docs/specs/architecture/650-composer-permission-mode.md`, `docs/specs/architecture/889-applied-effort-footer.md`, `docs/specs/architecture/891-status-sheet-running-model.md`, `docs/specs/architecture/946-context-usage-footer.md`
- Parent: split from [#228](https://github.com/pyrycode/pyrycode-mobile/issues/228) / [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146).
- Upstream:
  - [Thread screen](thread-screen.md) — the host. Owns the `rememberSaveable`-hoisted `sheetVisible` flag, mounts `StatusSheet` as a `Scaffold` sibling alongside the existing [`WorkspacePicker`](workspace-picker.md), and wires `onModelSelected = vm::onModelSelected, onEffortSelected = vm::onEffortSelected` at `MainActivity` (both retyped to `(String) -> Unit` by [#807](../codebase/807.md)). Since [#601](../codebase/601.md) it no longer reads `tokenPercent` / `tokensUsed` / `tokensTotal` off `state` at this call site — `onDismiss` is the trailing argument now. Since [#544](../codebase/544.md) it also collects `sessionSettingsErrors` into the shared snackbar host — see § Hosting in `ThreadScreen` above. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed the `onYoloToggled = vm::onYoloToggled` binding with no successor at this call site — the equivalent binding, `onPermissionModeSelected = vm::onPermissionModeSelected`, sits on `ThreadComposerFooter`, not `StatusSheet`. [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891) added `running = state.runConfig.running` at this same call site; [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) added `contextPercent = state.runConfig.contextPercent` beside it — no other argument changed by either ticket.
  - [`../codebase/544.md`](../codebase/544.md) — sends the Model/Effort controls' changes to the daemon (`ConversationRepository.setSessionSettings`, [#543](../codebase/543.md)) and reverts + snackbars on failure; [#807](../codebase/807.md) re-routed the addressed session id from `Conversation.currentSessionId` to `SessionSettings.sessionId` but kept the send/revert shape — the sheet's own composable is untouched either way. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) built the permission control's own write path (`ThreadViewModel.sendPermissionMode`) beside this one rather than inside it, since posture writes need the settle rule and single-field `yolo`/`permission_mode` exclusivity this shared path does not.
  - [Thread composer footer](thread-composer-footer.md) — the current entry point (its trailing icon fires `{ sheetVisible = true }` internally inside `ThreadScreen`) and, since [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), the former home of the permission control now shown in Run configuration. Through [#807](../codebase/807.md) the opener was the single-line `ThreadStatusRow`'s `onExpandClick` (shipped in [#145](../codebase/145.md) as a hoisted placeholder); that row rendered the `73%` stub via `tokenPercentColor` through #601 (removed by the sibling ticket [#602](../codebase/602.md)) and was re-sourced onto `ThreadRunConfig` by #807 before [#808](../codebase/808.md) retired it in favour of the footer's buttons plus this sheet.
  - [App preferences](app-preferences.md) — the `Model` + `Effort` enums + both [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) and (since [#229](../codebase/229.md)) public `Effort.label()` extensions. Through [#807](../codebase/807.md), `AppPreferences.defaultModel` / `defaultEffort` backed the VM's `selectedModelFlow` / `selectedEffortFlow`; **#807 deleted that consumer** — the two flows now back only Settings' own device-default picker, the same "write-live, one consumer" posture `defaultYolo` already had. `defaultYolo` itself is untouched by [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) — the ticket explicitly keeps Settings' "Default YOLO" preference as-is and feeds it into nothing on this screen.
  - [Conversation repository](conversation-repository.md) — `observeSessionSettings` (#590) and `observeModelMenu` (#791/#792), the two daemon readings [#807](../codebase/807.md) folded into `ThreadRunConfig`; [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) added `SessionSettings.permissionMode` and `ModelMenuRow.supportsAutoMode` to the same two readings and a `permissionMode` write parameter to `setSessionSettings`. This sheet never sees any of those types directly; the ViewModel is the trust boundary.
  - [Warning color slot](warning-color.md) — `MaterialTheme.colorScheme.warning` ([#119](../codebase/119.md)) was consumed at the 50-95% band of `progressColor` (Context window fill) until [#601](../codebase/601.md) deleted it, and at the same band of `tokenPercentColor` (status row text) until [#602](../codebase/602.md) deleted that helper too.
  - [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) + [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)) — sibling sheets following the same shell + `*Content` split. Three identical `TitleRow` / `SectionHeader` privates now live in the package; extraction is deferred until the first divergent-shape ticket lands.
- Sibling / downstream:
  - **[#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) — shipped, split.** Split into [#945](https://github.com/pyrycode/pyrycode-mobile/issues/945) (the daemon reading, `ConversationRepository.observeContextUsage`, decoding Claude's own `percentage` rather than a client-side recomputation off `ThreadUiState.tokenPercent` / `tokensUsed` / `tokensTotal` — [#603](../codebase/603.md) had already deleted those fields and the `STUB_*` constants outright, so #591's design worked from real wire data, not a restore) and [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) (this file's `contextPercent` parameter and `ContextWindowSection` render — see [§ `ContextWindowSection`](status-sheet-readings.md#contextwindowsection)). **Still open:** [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946)'s Rework 1 removed the phone's on-subscription `request_context_usage` ask outright (it deadlocked a connection mid-turn — see [Remote conversation repository — live stream, modal seams and the replay cursor § `context_usage`](remote-conversation-repository-live-stream-and-modals.md#context_usage--the-context-usage-reading-945)); a conversation now shows "unavailable" until its next turn ends on the current connection, including an idle conversation opened for the first time. Restoring an ask is blocked on the daemon fix, [pyrycode/pyrycode#2563](https://github.com/pyrycode/pyrycode/issues/2563) (open) — no mobile ticket exists yet to pick it back up.
  - **`Section` abstraction across the sections** — still deferred per [#230](../codebase/230.md). `SectionHeader + <body>` pairs now live in the `Column`, three since [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) retired the YOLO section; the body shape still diverges (radio rows / chip row / label+caption block), so a `SheetSection(header: String, body: @Composable () -> Unit)` thin wrapper might be the right abstraction. The architect's call for [#230](../codebase/230.md) was "still not yet — wait for a fifth occurrence or a real shape divergence".
  - Settings model-picker / effort-picker (`ui/settings/ModelPickerDialog.kt`, `EffortPickerDialog.kt`) — still iterate the `Model` / `Effort` device enums for Settings' own defaults-for-new-conversations feature, out of [#807](../codebase/807.md)'s scope. They reuse [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) / `Effort.label()`; the retired private `Model.description()` extension here was never shared with them.
  - Settings YOLO cleanup — the dormant `mutableStateOf(false)` row at `SettingsScreen.kt:80, 169` and the dormant `AppPreferences.defaultYolo` flow remain and can still be removed in a separate ticket. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) deleted `ThreadViewModel.yoloEnabled` and its single-writer invariant along with `StatusSheet`'s toggle, so that constraint no longer applies to a future "default YOLO for new conversations" feature — such a feature would design against the permission-mode write path (`onPermissionModeSelected` / `sendPermissionMode`) this ticket added instead.
- Figma: [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (full Run-configuration sheet; this doc covers the shell + the Model section region at `20:113/117/122/127`, the Effort region at `20:130/131–140`, and the Context window region at `20:151-155`. The design's YOLO region at `20:142+` is no longer rendered by this file — see [Thread composer footer § Permission mode](thread-composer-footer.md#permission-mode-650)). **The Running model section ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891)) has no node of its own** — the ticket asked for the sheet's existing section/row style and theme tokens beneath the Model section, not a new Figma region; see [§ `RunningModelSection`](status-sheet-readings.md#runningmodelsection).

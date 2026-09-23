# StatusSheet

Stateless Material 3 `ModalBottomSheet` (shell + Model section [#254](../codebase/254.md); Effort + YOLO sections [#229](../codebase/229.md); Context window section [#230](../codebase/230.md), rendered as an honest "unavailable" state since [#601](../codebase/601.md); re-sourced off the daemon's own published configuration by [#807](../codebase/807.md); YOLO section retired by [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650)) that hosts the Status Sheet — the surface a user opens to inspect or change per-conversation run configuration. Through [#807](../codebase/807.md) the opener was a tap anywhere on the single-line `ThreadStatusRow`; [#808](../codebase/808.md) retired that row and moved the opener to the [composer footer](thread-composer-footer.md)'s trailing icon. **[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) replaced the sheet's YOLO toggle with a permission button in that same footer** (`FooterControl.Permission`, showing the daemon-confirmed `SessionSettings.permissionMode` — see [Thread composer footer § Sourcing — Permission mode](thread-composer-footer.md#permission-mode-650)), rather than relocating the toggle: the sheet no longer offers any permission control. Renders Figma node `20:100`: a `"Run configuration"` title row with a trailing close icon, over sections in order — **Model** (`selectableGroup`-wrapped radio rows for the daemon's own published models, each pairing the row's inert `displayName` with an inert `resolvedModel` detail when it says something the label does not), **Running model** (since [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891): what claude announced it is actually running for the latest turn, plus its build, kept apart from the Model section above — never derived from or falling back to the selection; see [§ `RunningModelSection`](#runningmodelsection)), **Effort** (a `Row` of `FilterChip`s for the *selected* model's own published `effortLevels`, empty when it publishes none), and **Context window** (since [#601](../codebase/601.md): a `bodyLarge` `"Context usage unavailable"` label + `bodySmall` caption, no progress bar — the daemon does not serve mobile a real figure yet; see [§ `ContextWindowSection`](#contextwindowsection)). **Through [#807](../codebase/807.md) the Model and Effort sections iterated the three-entry `Model` and five-entry `Effort` device enums** (Opus 4.7 / Sonnet 4.6 / Haiku 4.5, and `low`/`medium`/`high`/`xhigh`/`max`) — this phone's guesses at a vocabulary the daemon actually publishes per conversation, and a value the server never published was refused server-side. #807 deleted that sourcing outright; see [§ Shape](#shape) and [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing) for the replacement.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `StatusSheet.kt`. Third **sheet** in that package after [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) and [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)); follows their shell + `*Content` split verbatim.

## Shape

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String,
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
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
)
```

**`running: ThreadRunningModel = ThreadRunningModel()` ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891))** — what claude announced it is running for the latest turn (`model`) and, when reported, its build (`build`), both already-inert [`ThreadReportedText`](thread-composer-footer.md#running-model-891)`?` values assembled by the ViewModel from [#890](https://github.com/pyrycode/pyrycode-mobile/issues/890)'s `observeAnnouncedModel` / `observeSessionFacts` readings. `model == null` is the sheet's cue to render the unavailable note — it is never inferred from `selectedModel` or `enabled`, and defaulting the parameter to `ThreadRunningModel()` (both fields `null`) means an unwired caller renders the honest unavailable state, not a blank space. See [§ `RunningModelSection`](#runningmodelsection).

**`effortNote: String? = null` ([#889](https://github.com/pyrycode/pyrycode-mobile/issues/889))** — the resolved [`EffortNote`](thread-composer-footer.md#applied-effort-889) string, or `null` when nothing needs explaining. `ThreadScreen` passes `state.runConfig.effortNote?.let { stringResource(it.textRes()) }`; the sheet itself never sees the enum or touches a daemon-sourced string here, only the client-owned resource text the caller already resolved. Rendered as one `Caption` below the effort chips — see [§ `EffortChipRow`](#effortchiprow).

**[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed `yoloEnabled: Boolean` and `onYoloToggled: (Boolean) -> Unit`** — the sheet's permission control moved to the [composer footer](thread-composer-footer.md#permission-mode-650) rather than being retyped here; there is no successor parameter.

**Through [#807](../codebase/807.md)** this signature took `selectedModel: Model`, `onModelSelected: (Model) -> Unit`, `selectedEffort: Effort` and `onEffortSelected: (Effort) -> Unit` — the sheet iterated `Model.entries` / `Effort.entries` directly. **#807 deleted every reference to those enums from this file.** The sheet is now a dumb renderer of pre-sanitized choices the ViewModel assembles from the daemon's own readings ([`ThreadModelChoice`](thread-composer-footer.md#sourcing) / `ThreadEffortChoice`, both defined in `ThreadViewModel.kt`); `StatusSheet.kt` imports nothing from `data/` and never sees a raw `ModelMenuRow`.

- **Public** (no `internal`) — same posture as the public shell in [`WorkspacePickerSheet`](workspace-picker-sheet.md) post-#220. The sheet has one production consumer ([`ThreadScreen`](thread-screen.md)) but the visibility decision tracks the sibling sheets, not consumer count.
- **`sheetState` defaulted but exposed** — the host can drive an animated close before invoking `onDismiss` if needed; defaulting keeps the host wiring one-line. `skipPartiallyExpanded = true` because the body is a short list, not a half-sheet.
- **No nullable callbacks** — every section in this sheet (four post-[#230](../codebase/230.md)) always renders, so all callbacks are always wired.
- **`choices: List<ThreadModelChoice>` and `selectedModel` / `selectedEffort: String` (#807)** — the daemon's own published rows, in the daemon's own order, and the write arguments (`ModelMenuRow.value` / an effort level) verbatim. Selection compares `choice.value == selectedModel` — a `String` identity comparison, not an enum one, because there is no fixed vocabulary to type against any more. `effortChoices: List<ThreadEffortChoice>` is the *selected* row's own levels, not a fixed five-entry list; empty is a positive "this model publishes no effort control."
- **`menuAvailable: Boolean` and `notListedModels: Int` (#807)** — the [#601](../codebase/601.md) honest-unavailable idiom extended to the Model section: `menuAvailable = false` renders "Model list unavailable"; `true` with empty `choices` renders "This server published no models" (a different, equally legal reading). `notListedModels` sums the producer's own `droppedModels` (never recomputed) and this client's own render cap (`hiddenChoices`, § below) — reported, never inferred from `choices.size`.
- **`pending: Boolean` and `enabled: Boolean` (#807)** — `pending` is true while a model/effort write has been sent but not yet confirmed by a fresh settings reading; the Model and Effort section headers gain a `"· applying…"` suffix and their controls disable. `enabled` is the read-only gate: `false` when `SessionSettings.sessionId` is `""` (the daemon has no session to address), disabling the same controls with no suffix. The two compose independently (`enabled && !pending`).
 **No `tokenPercent` / `tokensUsed` / `tokensTotal` parameters.** [#230](../codebase/230.md) added them as `Int = 0`-defaulted display fields; [#601](../codebase/601.md) removed all three along with the render they fed, because the daemon has never served mobile a real figure and a hardcoded `73%` could never warn an operator about an actually-filling context window. Through #601 `ThreadUiState` still carried `tokenPercent` / `tokensUsed` / `tokensTotal` (populated from `STUB_TOKEN_PERCENT = 73` / `STUB_TOKENS_USED = 146_000` / `STUB_TOKENS_TOTAL = 200_000` at the VM) — the sheet simply stopped reading them, which is what let #601 ship without a `ThreadViewModel` edit. [#603](../codebase/603.md) has since deleted the fields and the `STUB_*` constants outright, once both #601 and [#602](../codebase/602.md) (the retired status row, see [Thread composer footer](thread-composer-footer.md)) had stopped reading them. [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) (blocked on daemon-side pyrycode PR #1215) re-adds parameters here when there is a real figure to carry; see [§ Edge cases / limitations](status-sheet-hosting-tests-and-edge-cases.md#edge-cases--limitations).

A peer `internal` composable carries the body, mirroring `StatusSheet`'s full parameter list:

```kotlin
@Composable
internal fun StatusSheetContent(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    pending: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    effortNote: String? = null,
    running: ThreadRunningModel = ThreadRunningModel(),
)
```

`StatusSheet` is the `ModalBottomSheet` shell that delegates into it. The split exists so previews and Compose UI tests can render the content directly — the modal scrim + animation machinery don't render in the IDE preview pane and aren't wired into `createComposeRule()`-style tests. Same architectural shape M3 samples use for sheet previews, and the same shape `WorkspacePickerSheet` and `ChannelInfoSheet` follow.

## What it does

Single `Column(fillMaxWidth)` inside the `ModalBottomSheet`, scrolling since [#807](../codebase/807.md) (`Modifier.verticalScroll(rememberScrollState())` — the daemon's own render cap is not a wire constant, so a long menu must not clip the sections below it):

1. **`TitleRow(title = "Run configuration", onClose = onDismiss)`** — `titleLarge` in `onSurface` filling the row, trailing `IconButton(Icons.Filled.Close)` with `contentDescription = "Close"` and `tint = onSurfaceVariant`. Padding `start = 16, end = 4, top = 4, bottom = 12` per Figma `20:104`.
2. **`SectionHeader(text = sectionTitle("Model", pending))`** — `labelLarge` in `onSurfaceVariant`, padding `start = 24, end = 16, top = 12, bottom = 4` per Figma `20:113`. Same shape as [`WorkspacePickerSheet`](workspace-picker-sheet.md)'s `"Recent"` / `"Other"` headers. `sectionTitle` (#807) appends `" · applying…"` while `pending` is true — see [§ `pending` and `enabled`](#pending-and-enabled-807) below.
3. **`ModelSection(choices, menuAvailable, notListedModels, selectedModel, onModelSelected, enabled = enabled && !pending)`** ([#807](../codebase/807.md), replacing the `Model.entries.forEach` iteration) — a `Column(Modifier.selectableGroup())` of `ModelRow`s over the daemon's own published `choices`, in the daemon's own order, or an [honest-unavailable](#contextwindowsection) note when `choices` is empty; see [§ `ModelRow`](#modelrow) below.
4. **`SectionHeader(text = stringResource(R.string.status_sheet_running_model))`** then **`RunningModelSection(running)`** ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891)) — what claude announced, labelled apart from the selection above and never derived from it; see [§ `RunningModelSection`](#runningmodelsection) below. This is the one header in the sheet sourced from `stringResource` rather than an inline literal — see [§ Configuration](#configuration).
5. **`SectionHeader(text = sectionTitle("Effort", pending))`** ([#229](../codebase/229.md)) — same padding + style as the Model header per Figma `20:130`.
6. **`EffortChipRow(effortChoices, selectedEffort, onEffortSelected, enabled = enabled && !pending)`** ([#229](../codebase/229.md); re-sourced by [#807](../codebase/807.md)) — a `Row` of `FilterChip`s over the *selected model's own* published effort levels; see [§ `EffortChipRow`](#effortchiprow) below. Immediately after it, `effortNote?.let { Caption(text = it) }` ([#889](https://github.com/pyrycode/pyrycode-mobile/issues/889)) — one line explaining why the selected chip is not Claude's applied value, omitted entirely when there is nothing to explain.
7. **`SectionHeader(text = "Context window")`** ([#230](../codebase/230.md)) — same padding + style as above per Figma `20:151`.
8. **`ContextWindowSection()`** ([#230](../codebase/230.md); parameterless since [#601](../codebase/601.md)) — read-only "unavailable" label + caption, no progress bar; see [§ `ContextWindowSection`](#contextwindowsection) below.
9. **`Spacer(height = 24.dp)`** — bottom inset, matching the trailing spacer on the sibling sheets.

**[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) deleted the `"YOLO mode"` header and `YoloRow`** that sat between Effort and Context window ([#229](../codebase/229.md)), along with the `Switch` it rendered and the `androidx.compose.foundation.selection.toggleable` import it alone used. The sheet's permission control did not move to a new section here — it lives only in the [composer footer](thread-composer-footer.md#permission-mode-650) now.

`ModalBottomSheet`'s default `BottomSheetDefaults.DragHandle` paints the M3 drag pill at the top; the composable doesn't override it.

### `pending` and `enabled` (#807)

`sectionTitle(base, pending) = if (pending) "$base · applying…" else base` — a file-private helper applied to the Model and Effort headers only, so a tap that has been sent but not yet confirmed by a fresh settings reading is visible in the section title itself, not just inferred from greyed controls. Both sections' `enabled` argument is `enabled && !pending`: `enabled = false` (an empty `SessionSettings.sessionId` — the daemon has no session to address) disables the controls with no suffix; `pending` disables them **with** the suffix. The two states compose rather than being mutually exclusive.

### `ModelSection`

```kotlin
@Composable
private fun ModelSection(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    enabled: Boolean,
)
```

Replaces the pre-#807 direct `Model.entries.forEach` iteration. Three readings, kept apart per the [#601](../codebase/601.md) honest-unavailable idiom: `choices.isEmpty() && !menuAvailable` renders `"Model list unavailable"`; `choices.isEmpty() && menuAvailable` renders `"This server published no models"` (a different, equally legal reading — claude offered nothing); otherwise a `Column(Modifier.selectableGroup())` of `ModelRow`s, each `selected = choice.value == selectedModel`. When `notListedModels > 0` a trailing `Caption` reads `"${choices.size} shown · $notListedModels not listed"` — `notListedModels` is reported by the caller (`ThreadRunConfig.droppedModels + hiddenChoices`, both from the ViewModel), never recomputed from `choices.size`, which is what lets this say "3 of 47" instead of presenting a shortened menu as complete.

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
   - Title `Text(text = choice.label, style = bodyLarge, color = onSurface, maxLines = 2, overflow = Ellipsis)` — `choice.label` is the daemon's own `displayName`, already made inert by the ViewModel (see [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing)). `maxLines` guards the layout against a label the daemon bounded but did not shape.
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

```kotlin
@Composable
private fun RunningModelSection(running: ThreadRunningModel)
```

Not in Figma node `20:100` — the design has no running-model row; this section reuses the node's existing section/row style and theme tokens rather than inventing new ones (see [§ Design source in the architecture doc](https://github.com/pyrycode/pyrycode-mobile/blob/main/docs/specs/architecture/891-status-sheet-running-model.md)). Two claude-reported values, each a [`ThreadReportedText`](thread-composer-footer.md#running-model-891)`?` already made inert by [`ThreadViewModel.reportedText`](thread-composer-footer.md#running-model-891):

- **`running.model == null`** → `UnavailableNote(stringResource(R.string.status_sheet_running_model_unavailable))` — `"Not announced yet"`, the same honest-unavailable idiom [#601](../codebase/601.md) established for Context window, reused here rather than a blank row or a fallback to `selectedModel`.
- **`running.model != null`** → a `bodyLarge`/`onSurface` `Text` carrying `withTruncationMark(model.text, model.truncated, ...)` (an `AnnotatedString`), tagged `Modifier.testTag(RUNNING_MODEL_TEST_TAG)` — the anchor the rung-3 `InteractiveStreamE2ETest` scenario waits on and reads. **No `maxLines`/`overflow`**: the ViewModel's 128-character inert bound already caps the length, and an ellipsis would clip exactly the truncation mark that says characters are missing.
- **`running.build != null`** → a `Caption`-style `bodySmall`/`onSurfaceVariant` line, `stringResource(R.string.status_sheet_running_build, build.text)` (`"Claude Code %1$s"`) through the same `withTruncationMark` treatment. `build == null` (claude reported an empty `claude_code_version`) omits the line entirely rather than rendering an empty caption.

**`withTruncationMark(text, truncated, mark): AnnotatedString`** (file-private) appends the client-owned italic `" (truncated)"` mark (`status_sheet_running_truncated`) as **text**, not a separate icon or `contentDescription`, so it is both visible and read by TalkBack as part of the line — pinned by `a_truncated_value_carries_a_visible_and_announced_mark` in [Tests](status-sheet-hosting-tests-and-edge-cases.md#tests). Named `withTruncationMark` rather than `reportedText` — the verifier's PR #936 review flagged that name colliding (across packages, but confusingly) with `ThreadViewModel.reportedText`, the function that produces the `ThreadReportedText` this one renders. The build line's `Caption` call goes through a same-named `Caption(text: AnnotatedString)` overload added alongside `Caption(text: String)` (which now just wraps it in `AnnotatedString(text)`) — added on the same review round so the build line's style lives in one place instead of repeating `Caption`'s modifier/style inline.

**`RUNNING_MODEL_TEST_TAG`** is a top-level `const val`, not `internal` — the rung-3 `InteractiveStreamE2ETest` (a different Gradle source set, `androidTest`) imports it directly rather than duplicating the literal tag string. It sits on a plain `Text` inside `RunningModelSection`'s `Column` with no merging parent above it, so the default (unmerged) semantics tree finds it — the plan's one open question, resolved during implementation with no change needed.

**`SessionFacts.permissionMode` is never read here or anywhere in this section.** The claimed permission posture claude reports alongside its build is deliberately not rendered — see [Edge cases / limitations](status-sheet-hosting-tests-and-edge-cases.md#edge-cases--limitations) for why, and [Thread composer footer § Permission mode](thread-composer-footer.md#permission-mode-650) for the one permission reading this sheet is allowed to reflect.

### `EffortChipRow`

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortChipRow(
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    enabled: Boolean,
)
```

**Through [#807](../codebase/807.md)** this iterated `Effort.entries` (`LOW, MEDIUM, HIGH, XHIGH, MAX`) — a fixed five-entry device vocabulary — regardless of which model was selected. **#807 replaced that with the *selected model's own* published levels**: `effortChoices` is `ThreadRunConfig.effortChoices` at the call site, i.e. the selected `ThreadModelChoice.effortChoices` — empty when that model publishes none. Empty renders an [`UnavailableNote`](#contextwindowsection) — `"No effort levels published for this model."` — rather than five now-meaningless chips.

**`selectedEffort` (since [#889](https://github.com/pyrycode/pyrycode-mobile/issues/889)) is `ThreadRunConfig.selectedEffort`, not a bare saved reading** — the same pending-then-applied-then-saved value the [composer footer's effort button](thread-composer-footer.md#applied-effort-889) shows, so the chip row and the button agree by construction. The caller passes the matching `effortNote` alongside it (§ Shape above); this composable itself takes no part in resolving either — it renders whatever `String` / `String?` it is given.

`Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp))` iterating `effortChoices` — `FilterChip(selected = effort.value == selectedEffort, enabled = enabled, onClick = { onEffortSelected(effort.value) }, label = { Text(effort.label, maxLines = 1, overflow = Ellipsis) })`. No leading icon, no trailing icon.

- **`FilterChip` defaults match Figma `20:131–140` exactly — no `FilterChipDefaults.filterChipColors(...)` override.** Selected: `secondaryContainer` background + `onSecondaryContainer` label, no border. Unselected: 1dp `outline` border + transparent background + `onSurfaceVariant` label. The default `FilterChipDefaults.filterChipBorder(...)` paints the unselected outline; the selected state replaces border with background fill automatically.
- **`effortChoices.forEach`, not `Effort.entries.forEach`.** The wire order the selected row published, not a fixed enum's source order — a model reordering or narrowing its own levels propagates without a UI edit, same principle the pre-#807 `Effort.entries.forEach` claimed for the device enum.
- **Labels are `effort.label` — the level itself, made inert by the ViewModel** (see [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing)), not a resolved `Effort.label()` extension call. `Effort.entries` / `Effort.label()` still exist for Settings' own device-default picker (see [app-preferences.md](app-preferences.md)) but have no consumer in this file any more.
- **A11y.** `FilterChip` owns `Role.Button` with selection state; `selectableGroup()` lets TalkBack announce the group size.

### `YoloRow` — retired by #650

Through [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) this file drew a `YoloRow` composable between Effort and Context window: a full-width `toggleable` row (`Role.Switch`) with a two-line label and an M3 `Switch(onCheckedChange = null)`. It carried a known, unfixed gap since [#807](../codebase/807.md) — its own `enabled` parameter was overloaded to mean the switch's checked state, not whether the control was interactive, so the switch never visibly disabled on a read-only session or during a pending model/effort write. **#650 deleted `YoloRow`, its call site, its section header and the `androidx.compose.foundation.selection.toggleable` / `androidx.compose.material3.Switch` imports outright** — the gap closed by removing the row rather than by threading `enabled`/`pending` through it. The daemon-confirmed permission mode it stood in for now renders in the [composer footer](thread-composer-footer.md#permission-mode-650), which never had this gap: its `footerControlEnabled(Permission, …)` reads `runConfig.writable` and `pendingPermission` from the start.

### `ContextWindowSection`

```kotlin
@Composable
private fun ContextWindowSection()
```

Since [#601](../codebase/601.md), parameterless — it takes no token figures and reads only `MaterialTheme`. `Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp))` with two children:

1. Label `Text("Context usage unavailable", style = bodyLarge, color = onSurface)` — literal, not `stringResource` (every other piece of copy in this file is an inline literal). Replaces the Figma `20:152` figure; there is no daemon-served number to show.
2. Caption `Text("When full, oldest messages get dropped from claude's view (delimiter still shows; old messages stay in your scroll).", style = bodySmall, color = onSurfaceVariant)` — Figma `20:155`, **byte-identical to the pre-#601 caption**, including the lowercase `claude's`.

Read-only — no event surface, no callback, same as before #601. No auto-close (it's a display, not a picker) — taps inside the section do nothing.

**Wording is desktop-sourced, not invented.** pyrycode#1214 captured the desktop DOM on the same `stream-json` runner mobile ships against — `"...Context windowContext usage unavailableWhen full, oldest messages get dropped..."` — which pins both the exact text and the fact that desktop keeps the caption alongside it.

**Deliberate, spec'd Figma divergence.** Figma node `20:100`/`20:151` has no "unavailable" variant — it's the node the pre-#601 stub literally came from (`73% used (146K of 200K tokens)` over a filled bar). This section intentionally does not match that node; what survives is the header, the caption, and the layout/padding/typography. Flagged as a Figma-side gap worth filling, not blocking. See `docs/specs/architecture/601-status-sheet-context-usage-unavailable.md` § Design source.

#### Deleted in #601: `formatTokens` and `progressColor`

Both file-private helpers — `formatTokens(n: Int) = "${n / 1000}K"` and a threshold `progressColor(percent: Int): Color` (`< 50 → primary`, `< 95 → warning`, `else → error`, the same boundaries `ThreadStatusRow.tokenPercentColor` used but for fill chroma rather than text emphasis) — existed solely to render the stub figure and its `LinearProgressIndicator`. #601 deleted both along with their only call sites (verified by grep: zero references anywhere in `app/src`), plus the `LinearProgressIndicator` import, `androidx.compose.ui.graphics.Color`, and `de.pyryco.mobile.ui.theme.warning`. `tokenPercentColor` itself was deleted from `ThreadStatusRow` in [#602](../codebase/602.md), and the row it lived on was retired outright in [#808](../codebase/808.md) — the two helpers were always deliberately unshared (see [§ Edge cases / limitations](status-sheet-hosting-tests-and-edge-cases.md#edge-cases--limitations)), so neither deletion orphaned the theme slot.

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
- **Since [#807](../codebase/807.md):** `selectedModel: String`, `selectedEffort: String` and the `choices` / `effortChoices` lists replace the pre-#807 `selectedModel: Model` / `selectedEffort: Effort` enum pair. `String` and `List<ThreadModelChoice>` / `List<ThreadEffortChoice>` (both immutable `data class`es) are Compose-stable, so the row / chip composables still skip recomposition when their inputs are unchanged — the stability property is preserved, only the types moved off the device enums.
- **Since [#601](../codebase/601.md): `ContextWindowSection()` is parameterless and reads only `MaterialTheme`**, so it recomposes on theme change alone — where previously (via `tokenPercent` / `tokensUsed` / `tokensTotal`) it also recomposed on any context-figure change. Those figures were constants, so the practical delta before #601 was zero, but the direction is now correct. No lambda captures, no unstable types, no `remember` needed. `LinearProgressIndicator`, its deferred `progress = { lambda }` read, and the `progressColor` / `formatTokens` helpers that fed it are gone along with the params.
- **`ThreadRunningModel` / `ThreadReportedText` ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891))** are both immutable `data class`es (`ThreadUiState.kt`), Compose-stable like the pre-existing `ThreadModelChoice` / `ThreadEffortChoice`; `RunningModelSection` recomposes only when the announced model or build actually changes, no `remember` needed.

## Configuration

- **No new dependencies.** `ModalBottomSheet` + `rememberModalBottomSheetState` + `SheetState` + `RadioButton` + `FilterChip` all ship in `androidx.compose.material3` already in the BOM (`composeBom = 2026.02.01`); `Modifier.selectable` / `selectableGroup` are in `androidx.compose.foundation.selection`; `Icons.Filled.Close` is in `material-icons-extended`. No `gradle/libs.versions.toml` edit across [#254](../codebase/254.md), [#229](../codebase/229.md), [#230](../codebase/230.md), [#601](../codebase/601.md), [#807](../codebase/807.md), [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), or [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891). [#601](../codebase/601.md) removed the `LinearProgressIndicator` import (with its `gapSize` / `drawStopIndicator` params) — no dependency change, just an unused import gone. [#807](../codebase/807.md) added `Modifier.verticalScroll` + `rememberScrollState()`, both already in `androidx.compose.foundation` — no new dependency. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed the `Switch` (`androidx.compose.material3`) and `toggleable` (`androidx.compose.foundation.selection`) imports with `YoloRow` — no new dependency, one gone. [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891) added `AnnotatedString` / `buildAnnotatedString` / `withStyle` / `SpanStyle` / `FontStyle` (`androidx.compose.ui.text*`) and `Modifier.testTag` (`androidx.compose.ui.platform`) — both already in the Compose UI artifact, no new dependency.
- **String resources.** Through [#889](https://github.com/pyrycode/pyrycode-mobile/issues/889) every string in this file was an inline literal (`"Run configuration"`, `"Model"`, `"Effort"`, `"Context window"`, the Context-window caption, `"Close"`, the [#807](../codebase/807.md) fallback copy, and the [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) permission strings this file no longer carries); `effortNote` was already resolved by the caller, no `stringResource` call in this file. **[#891](https://github.com/pyrycode/pyrycode-mobile/issues/891) is the file's first `strings.xml` extraction**, not one more inline literal: `status_sheet_running_model` (`"Running model"`), `status_sheet_running_model_unavailable` (`"Not announced yet"`), `status_sheet_running_build` (`"Claude Code %1$s"`), `status_sheet_running_truncated` (`" (truncated)"`) — a claude-authored value is a format *argument* to the last two, never the format string itself.

## Hosting in `ThreadScreen`

Split into [StatusSheet — hosting, tests and edge cases](status-sheet-hosting-tests-and-edge-cases.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — Hosting in `ThreadScreen`, Preview, Tests and Edge cases / limitations — moved there verbatim, headings and anchors intact.

## Related

- Ticket notes: [`../codebase/254.md`](../codebase/254.md) (shell + Model section), [`../codebase/229.md`](../codebase/229.md) (Effort +, since retired, YOLO sections), [`../codebase/230.md`](../codebase/230.md) (Context window section, stub), [`../codebase/601.md`](../codebase/601.md) (Context window section, "unavailable" render), [`../codebase/807.md`](../codebase/807.md) (Model + Effort sections re-sourced off the daemon's `observeSessionSettings` + `observeModelMenu` readings, retiring the `Model` / `Effort` device enums from this file entirely). YOLO section retirement: [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), spec `docs/specs/architecture/650-composer-permission-mode.md`. Applied-effort explanation caption: [#889](https://github.com/pyrycode/pyrycode-mobile/issues/889), which owns the `EffortNote` design — see [Thread composer footer § Applied effort](thread-composer-footer.md#applied-effort-889). Running model section: [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891), spec `docs/specs/architecture/891-status-sheet-running-model.md`; sources off [#890](https://github.com/pyrycode/pyrycode-mobile/issues/890)'s `observeAnnouncedModel` / `observeSessionFacts` readings — see [Conversation repository](conversation-repository.md) and [Thread composer footer § Running model](thread-composer-footer.md#running-model-891) for `reportedText` / `ThreadReportedText` / `ThreadRunningModel`.
- Specs: `docs/specs/architecture/254-statussheet-scaffold-model-section.md`, `docs/specs/architecture/229-statussheet-effort-yolo.md`, `docs/specs/architecture/230-status-sheet-context-window.md`, `docs/specs/architecture/601-status-sheet-context-usage-unavailable.md`, `docs/specs/architecture/807-thread-run-config-from-daemon.md`, `docs/specs/architecture/650-composer-permission-mode.md`, `docs/specs/architecture/889-applied-effort-footer.md`, `docs/specs/architecture/891-status-sheet-running-model.md`
- Parent: split from [#228](https://github.com/pyrycode/pyrycode-mobile/issues/228) / [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146).
- Upstream:
  - [Thread screen](thread-screen.md) — the host. Owns the `rememberSaveable`-hoisted `sheetVisible` flag, mounts `StatusSheet` as a `Scaffold` sibling alongside the existing [`WorkspacePicker`](workspace-picker.md), and wires `onModelSelected = vm::onModelSelected, onEffortSelected = vm::onEffortSelected` at `MainActivity` (both retyped to `(String) -> Unit` by [#807](../codebase/807.md)). Since [#601](../codebase/601.md) it no longer reads `tokenPercent` / `tokensUsed` / `tokensTotal` off `state` at this call site — `onDismiss` is the trailing argument now. Since [#544](../codebase/544.md) it also collects `sessionSettingsErrors` into the shared snackbar host — see § Hosting in `ThreadScreen` above. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed the `onYoloToggled = vm::onYoloToggled` binding with no successor at this call site — the equivalent binding, `onPermissionModeSelected = vm::onPermissionModeSelected`, sits on `ThreadComposerFooter`, not `StatusSheet`. [#891](https://github.com/pyrycode/pyrycode-mobile/issues/891) added `running = state.runConfig.running` at this same call site — no other argument changed.
  - [`../codebase/544.md`](../codebase/544.md) — sends the Model/Effort controls' changes to the daemon (`ConversationRepository.setSessionSettings`, [#543](../codebase/543.md)) and reverts + snackbars on failure; [#807](../codebase/807.md) re-routed the addressed session id from `Conversation.currentSessionId` to `SessionSettings.sessionId` but kept the send/revert shape — the sheet's own composable is untouched either way. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) built the permission control's own write path (`ThreadViewModel.sendPermissionMode`) beside this one rather than inside it, since posture writes need the settle rule and single-field `yolo`/`permission_mode` exclusivity this shared path does not.
  - [Thread composer footer](thread-composer-footer.md) — the current entry point (its trailing icon fires `{ sheetVisible = true }` internally inside `ThreadScreen`) and, since [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), the sole home of the permission control this sheet used to host as `YoloRow`. Through [#807](../codebase/807.md) the opener was the single-line `ThreadStatusRow`'s `onExpandClick` (shipped in [#145](../codebase/145.md) as a hoisted placeholder); that row rendered the `73%` stub via `tokenPercentColor` through #601 (removed by the sibling ticket [#602](../codebase/602.md)) and was re-sourced onto `ThreadRunConfig` by #807 before [#808](../codebase/808.md) retired it in favour of the footer's buttons plus this sheet.
  - [App preferences](app-preferences.md) — the `Model` + `Effort` enums + both [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) and (since [#229](../codebase/229.md)) public `Effort.label()` extensions. Through [#807](../codebase/807.md), `AppPreferences.defaultModel` / `defaultEffort` backed the VM's `selectedModelFlow` / `selectedEffortFlow`; **#807 deleted that consumer** — the two flows now back only Settings' own device-default picker, the same "write-live, one consumer" posture `defaultYolo` already had. `defaultYolo` itself is untouched by [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) — the ticket explicitly keeps Settings' "Default YOLO" preference as-is and feeds it into nothing on this screen.
  - [Conversation repository](conversation-repository.md) — `observeSessionSettings` (#590) and `observeModelMenu` (#791/#792), the two daemon readings [#807](../codebase/807.md) folded into `ThreadRunConfig`; [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) added `SessionSettings.permissionMode` and `ModelMenuRow.supportsAutoMode` to the same two readings and a `permissionMode` write parameter to `setSessionSettings`. This sheet never sees any of those types directly; the ViewModel is the trust boundary.
  - [Warning color slot](warning-color.md) — `MaterialTheme.colorScheme.warning` ([#119](../codebase/119.md)) was consumed at the 50-95% band of `progressColor` (Context window fill) until [#601](../codebase/601.md) deleted it, and at the same band of `tokenPercentColor` (status row text) until [#602](../codebase/602.md) deleted that helper too.
  - [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) + [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)) — sibling sheets following the same shell + `*Content` split. Three identical `TitleRow` / `SectionHeader` privates now live in the package; extraction is deferred until the first divergent-shape ticket lands.
- Sibling / downstream:
  - **[#591](https://github.com/pyrycode/pyrycode-mobile/issues/591)** (blocked on daemon-side pyrycode PR #1215) — serves a real context figure and re-adds parameters to `StatusSheet` / `StatusSheetContent` / `ContextWindowSection` to render it in place of the "unavailable" text this ticket ships. [#603](../codebase/603.md) has since deleted `ThreadUiState.tokenPercent` / `tokensUsed` / `tokensTotal` and the `STUB_*` constants outright, so #591 designs the shape it needs against real wire data rather than restoring these — see [`../codebase/603.md`](../codebase/603.md).
  - **`Section` abstraction across the sections** — still deferred per [#230](../codebase/230.md). `SectionHeader + <body>` pairs now live in the `Column`, three since [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) retired the YOLO section; the body shape still diverges (radio rows / chip row / label+caption block), so a `SheetSection(header: String, body: @Composable () -> Unit)` thin wrapper might be the right abstraction. The architect's call for [#230](../codebase/230.md) was "still not yet — wait for a fifth occurrence or a real shape divergence".
  - Settings model-picker / effort-picker (`ui/settings/ModelPickerDialog.kt`, `EffortPickerDialog.kt`) — still iterate the `Model` / `Effort` device enums for Settings' own defaults-for-new-conversations feature, out of [#807](../codebase/807.md)'s scope. They reuse [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) / `Effort.label()`; the retired private `Model.description()` extension here was never shared with them.
  - Settings YOLO cleanup — the dormant `mutableStateOf(false)` row at `SettingsScreen.kt:80, 169` and the dormant `AppPreferences.defaultYolo` flow remain and can still be removed in a separate ticket. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) deleted `ThreadViewModel.yoloEnabled` and its single-writer invariant along with `StatusSheet`'s toggle, so that constraint no longer applies to a future "default YOLO for new conversations" feature — such a feature would design against the permission-mode write path (`onPermissionModeSelected` / `sendPermissionMode`) this ticket added instead.
- Figma: [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (full Run-configuration sheet; this doc covers the shell + the Model section region at `20:113/117/122/127`, the Effort region at `20:130/131–140`, and the Context window region at `20:151-155`. The design's YOLO region at `20:142+` is no longer rendered by this file — see [Thread composer footer § Permission mode](thread-composer-footer.md#permission-mode-650)). **The Running model section ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891)) has no node of its own** — the ticket asked for the sheet's existing section/row style and theme tokens beneath the Model section, not a new Figma region; see [§ `RunningModelSection`](#runningmodelsection).

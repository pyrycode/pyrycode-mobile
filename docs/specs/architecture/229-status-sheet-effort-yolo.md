# 229 — StatusSheet: Effort + YOLO sections

Issue: [pyrycode/pyrycode-mobile#229](https://github.com/pyrycode/pyrycode-mobile/issues/229). Size: S. Label: `security-sensitive` (drives a self-review pass — see § Security review).

## Context

Sibling slices #253 + #254 landed the StatusSheet shell with a single **Model** section, the `ThreadUiState.selectedModel: Model` field, the `ThreadViewModel.onModelSelected(Model)` per-conversation override, and the model-override pattern (private `MutableStateFlow<Model?>` pre-combined with `appPreferences.defaultModel` into a `selectedModelFlow`).

This slice layers two more sections beneath Model in the same composable:

1. **Effort** — five-option single-choice control. Pattern mirrors `selectedModel`: per-conversation override over `appPreferences.defaultEffort`, no mutation of Settings.
2. **YOLO mode** — single boolean toggle. **Deliberately diverges** from the Model/Effort pattern: it ignores `appPreferences.defaultYolo` entirely and always initialises to `false`. The dormant `defaultYolo` preference and the non-functional Settings row (`SettingsScreen.kt:80, 169`) stay as dead code — do not wire them, do not remove them.

The architectural constraint behind YOLO's divergence: the StatusSheet must be the **single writer** of `ThreadViewModel.yoloEnabled`. AC has an explicit `git grep` check that no other call site writes the field. Code review enforces this; there is no runtime guard (and we don't need one — see § Security review).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100

Node `20:100` is the full "Run configuration" sheet. The Model region (top) was implemented by #254; the Context window region (bottom) is #230's slice. This slice ships the two middle regions:

- **Effort** — `label-large` "Effort" header (same paddings as the existing Model header), then a horizontal `Row` of five rounded-rect chips (radius 8dp, padding `horizontal = 12.dp, vertical = 6.dp`, 8dp horizontal gap, container `padding(horizontal = 16.dp, vertical = 4.dp)`). Unselected chips: 1dp `outline` border, transparent background, `onSurfaceVariant` label colour. Selected chip: `secondaryContainer` background, no border, `onSecondaryContainer` label colour. M3 `FilterChip` defaults match exactly — use it.
- **YOLO mode** — `label-large` "YOLO mode" header, then a row (`padding(horizontal = 16.dp, vertical = 8.dp)`, `Arrangement.SpaceBetween`, `Alignment.CenterVertically`) with a two-line label on the left (title `body-large` `onSurface` "Auto-accept tool calls", supporting `body-small` `onSurfaceVariant` "Claude runs commands without asking for confirmation. Use carefully.") and an M3 `Switch` on the right.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt:1-216` — full file. The `SectionHeader`, `TitleRow`, `ModelRow` shape, and the three `@Preview` composables to extend. Note `SectionHeader`'s padding (`start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp`) — reuse verbatim for the two new headers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:41-170` — current `ThreadUiState` (`selectedModel`, `effort: String`, `tokenPercent`), `selectedModelFlow` pattern, `modelOverride: MutableStateFlow<Model?>`, `onModelSelected`. Mirror the override pattern for Effort; diverge for YOLO.
- `app/src/main/java/de/pyryco/mobile/data/preferences/Effort.kt:1-3` — the `Effort` enum (`LOW, MEDIUM, HIGH, XHIGH, MAX`).
- `app/src/main/java/de/pyryco/mobile/ui/settings/EffortPickerDialog.kt:77-84` — the existing `internal fun Effort.label(): String` extension. Reuse it; do **not** reintroduce the enum-to-string mapping in `StatusSheet.kt`. Note: it's `internal` to `de.pyryco.mobile.ui.settings`; you can either widen it to `public` (preferred — symmetric with `Model.label()`) or move it. Spec recommends widening to a top-level `public fun Effort.label()` in the same file, since the developer should not introduce a third location.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:49-64` — `defaultEffort: Flow<Effort>` (already fallback-safe to `Effort.HIGH`) and the dormant `defaultYolo: Flow<Boolean>` + `setDefaultYolo(...)`. The dormant preference is **not** consumed by this slice — do not import it into `ThreadViewModel`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:63-93, 216-226` — `onModelSelected` parameter, the `bottomBar` call into `ThreadStatusRow` (currently reads `state.effort` — needs `state.selectedEffort.label()`), and the `StatusSheet(...)` block to extend with the two new callbacks. Note the four previews (lines 282-468) don't pass `onModelSelected` and never set `effort` explicitly — they'll keep compiling.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt:36-78` — confirms `effort: String` parameter shape stays; `ThreadScreen` adapts the type with `.label()`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:197-216` — the `CONVERSATION_THREAD` route call site; add the two new callbacks alongside `onModelSelected = vm::onModelSelected`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:80, 159-171` — confirms the dormant "Default YOLO" row uses `var defaultYolo by remember { mutableStateOf(false) }` (local-only state, no writer into `ThreadViewModel`). This file must **not** be edited.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1-708` — full test file. Lines 192-216 are the two initial/post-subscription assertions that need updating (drop the `"high" == state.effort` strings; add `Effort.HIGH == state.selectedEffort`). Lines 218-290 are the canonical `selectedModel` test shape — mirror for `selectedEffort`. Lines 592-597 are the `makeVm` helper (no signature change needed; `prefs` param already there).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt:1-115` — full file. Existing 4 tests stay; add ~6 more in the same style.

## Design

### `ThreadUiState` — `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`

Replace `val effort: String = "high"` with `val selectedEffort: Effort = Effort.HIGH`. Add `val yoloEnabled: Boolean = false`. Order: keep the new fields adjacent to `selectedModel` for readability — `selectedModel`, `selectedEffort`, `yoloEnabled`, `tokenPercent` (the `tokenPercent` field stays untouched; #230 owns it). Add the `Effort` import.

This is a breaking rename of one field on a data class. Edit fan-out: 1 read site in `ThreadScreen.kt:87` (`effort = state.effort` → `effort = state.selectedEffort.label()`), 2 read sites in `ThreadViewModelTest.kt` (lines 201, 213) — both update to enum equality on `selectedEffort`. Both production previews in `ThreadScreen.kt` (lines 282-468) build `ThreadUiState(...)` without passing the field, so they inherit the new default.

### `ThreadViewModel` — same file

Add three new private flows + one Koin-injected dependency (`AppPreferences` is already a constructor parameter from #253):

- `private val effortOverride = MutableStateFlow<Effort?>(null)` — `null` ⇒ "use the Settings default", mirroring `modelOverride`.
- `private val selectedEffortFlow: Flow<Effort> = combine(appPreferences.defaultEffort, effortOverride) { default, override -> override ?: default }` — mirrors `selectedModelFlow`.
- `private val yoloEnabled = MutableStateFlow(false)` — hardcoded `false` initial value; **no read of `appPreferences.defaultYolo`**. This is intentional and load-bearing for the AC.

#### Combining without exceeding `combine`'s 5-arg overload

`combine`'s typed overloads top out at five `Flow<T>` arguments. The existing main combine already uses all five (`conversations, items, picker, rename, selectedModelFlow`). Adding two more sources would force the vararg `combine(vararg Flow<*>, transform: (Array<*>) -> R)` overload, which loses type safety.

Instead, fold the three run-config flows into one upstream combine, then plug that into the main combine as the fifth source:

```kotlin
private data class RunConfig(
    val model: Model,
    val effort: Effort,
    val yoloEnabled: Boolean,
)

private val runConfigFlow: Flow<RunConfig> = combine(
    selectedModelFlow,
    selectedEffortFlow,
    yoloEnabled,
) { model, effort, yolo -> RunConfig(model, effort, yolo) }
```

The main combine then replaces `selectedModelFlow` with `runConfigFlow` and destructures inside the lambda (`runConfig.model`, `runConfig.effort`, `runConfig.yoloEnabled`). `RunConfig` is `private` to the file — not exposed.

Drop the `STUB_EFFORT` companion constant (the `companion object` keeps `STUB_TOKEN_PERCENT` for now — #230 owns that). The `effort = STUB_EFFORT` line in the main combine becomes `selectedEffort = runConfig.effort, yoloEnabled = runConfig.yoloEnabled`.

#### Public mutators

- `fun onEffortSelected(effort: Effort) { effortOverride.value = effort }` — mirrors `onModelSelected`. No `viewModelScope.launch` (synchronous `StateFlow.value` write). No `appPreferences.setDefaultEffort(...)` call — explicit AC: "does not mutate `AppPreferences`".
- `fun onYoloToggled(enabled: Boolean) { yoloEnabled.value = enabled }` — single writer of the field. No preference write.

**No `reset()` / `clear()` / nullable setters** — there is no public API path that resets YOLO without going through `onYoloToggled`. This is the architectural-invariant half of the AC's single-writer requirement; the other half is the `git grep` check.

#### Fallback semantics

For Effort: same two-layer fallback as Model. The data-class default `Effort.HIGH` feeds `stateIn(WhileSubscribed).initialValue`; `appPreferences.defaultEffort` maps unparseable stored values to `Effort.HIGH` (`AppPreferences.kt:52`).

For YOLO: `yoloEnabled: MutableStateFlow<Boolean>` is always-emitting, initial value `false`. The combine pipeline has no `null` state to recover from.

### `StatusSheet` — `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt`

Extend the public composable and the internal content composable with two new parameters each, **appended** after `onModelSelected` (additive, so any callers not yet updated still compile — though the only caller is `ThreadScreen`):

- `selectedEffort: Effort`
- `onEffortSelected: (Effort) -> Unit`
- `yoloEnabled: Boolean`
- `onYoloToggled: (Boolean) -> Unit`

Inside `StatusSheetContent`, after the existing Model `Column` and before `Spacer(modifier = Modifier.height(24.dp))`, add two new sections in order:

1. **`SectionHeader(text = "Effort")`** followed by `EffortChipRow(selectedEffort = selectedEffort, onEffortSelected = onEffortSelected)`.
2. **`SectionHeader(text = "YOLO mode")`** followed by `YoloRow(enabled = yoloEnabled, onToggled = onYoloToggled)`.

The closing `Spacer(modifier = Modifier.height(24.dp))` stays at the bottom of the `Column`.

#### `EffortChipRow` (new private composable in `StatusSheet.kt`)

Signature: `private fun EffortChipRow(selectedEffort: Effort, onEffortSelected: (Effort) -> Unit)`. Behaviour:

- A `Row` with `Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp)` and `Arrangement.spacedBy(8.dp)`.
- Iterates `Effort.entries` in declaration order (LOW, MEDIUM, HIGH, XHIGH, MAX). For each value, renders a `FilterChip` with `selected = (effort == selectedEffort)`, `onClick = { onEffortSelected(effort) }`, `label = { Text(effort.label()) }`. The chip inherits M3's default `FilterChipDefaults.filterChipColors()` — selected → `secondaryContainer` background + `onSecondaryContainer` label, unselected → `outline` border + transparent background + `onSurfaceVariant` label, which matches Figma `20:131–140`.
- No leading icon, no trailing icon. The default `FilterChipDefaults.filterChipBorder(...)` provides the 1dp outline on unselected; the selected state replaces border with background fill automatically.

Reuse `de.pyryco.mobile.ui.settings.label` (widen `EffortPickerDialog.kt:77` from `internal` to top-level `public fun Effort.label()` and import it). Rationale: same surface cost as `Model.label()` and avoids a second label mapping. Out-of-scope for this ticket to also migrate `SettingsViewModel`'s consumption — the visibility widening is sufficient.

`FilterChip` does the right thing for accessibility — it owns `Role.Button` with selection state. Wrapping in `selectableGroup()` lets TalkBack announce "in group of 5".

#### `YoloRow` (new private composable in `StatusSheet.kt`)

Signature: `private fun YoloRow(enabled: Boolean, onToggled: (Boolean) -> Unit)`. Behaviour:

- A `Row` with `Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onToggled).padding(horizontal = 16.dp, vertical = 8.dp)`, `verticalAlignment = Alignment.CenterVertically`.
- Left: a `Column(modifier = Modifier.weight(1f))` with two `Text`s:
  - Title: `"Auto-accept tool calls"`, `MaterialTheme.typography.bodyLarge`, `MaterialTheme.colorScheme.onSurface`.
  - Supporting: `"Claude runs commands without asking for confirmation. Use carefully."`, `MaterialTheme.typography.bodySmall`, `MaterialTheme.colorScheme.onSurfaceVariant`.
- A `Spacer(modifier = Modifier.width(16.dp))`.
- Right: `Switch(checked = enabled, onCheckedChange = null)` — `null` because the surrounding `toggleable` owns the click, so the whole row is the touch target (matching the `ModelRow` selectable pattern and M3 list-item conventions).

Strings inline (matching `StatusSheet`'s existing convention of inlining "Model", "Run configuration", and model descriptions; no `strings.xml` extraction this slice).

#### Previews

Update the three existing `@Preview` composables (`StatusSheetOpusPreview`, `StatusSheetSonnetPreview`, `StatusSheetHaikuPreview`) to pass the new parameters (`selectedEffort = Effort.HIGH`, `onEffortSelected = {}`, `yoloEnabled = false`, `onYoloToggled = {}`) so they keep compiling.

Add two new previews to satisfy the AC ("`@Preview` shows the sheet with both Effort variations (e.g. low and max) and YOLO on/off"):

- `StatusSheetEffortLowYoloOffPreview` — `selectedModel = Model.OPUS_4_7`, `selectedEffort = Effort.LOW`, `yoloEnabled = false`. Light mode.
- `StatusSheetEffortMaxYoloOnPreview` — `selectedModel = Model.OPUS_4_7`, `selectedEffort = Effort.MAX`, `yoloEnabled = true`. Light mode.

Both follow the existing `StatusSheetOpusPreview` shape (wrap `StatusSheetContent` in `PyrycodeMobileTheme { Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) { ... } } }`, `widthDp = 412`).

### `ThreadScreen` — `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`

Three edits:

1. **Parameter list** (lines 54-67): add `onEffortSelected: (Effort) -> Unit = {}` and `onYoloToggled: (Boolean) -> Unit = {}` after `onModelSelected`. Add `import de.pyryco.mobile.data.preferences.Effort` and import the widened `de.pyryco.mobile.ui.settings.label` (note: there are now two `label` extensions in scope — `Model.label()` from `data.preferences` and `Effort.label()` from `ui.settings`; both resolve unambiguously by receiver type).
2. **`bottomBar` call into `ThreadStatusRow`** (line 87): change `effort = state.effort` → `effort = state.selectedEffort.label()`.
3. **`StatusSheet` invocation** (lines 216-225): extend with the two new parameters. Like the existing model handler, close the sheet on toggle/selection — single-section actions complete on a single tap. Concretely, the developer adds two more callback wrappers that call the upstream callback and then set `sheetVisible = false`. Behaviour parity with `onModelSelected`'s auto-close.

The four `@Preview` composables (lines 282-468) need no changes — none pass the new callbacks; defaults apply. `effort` is not currently passed in any preview's `ThreadUiState(...)` constructor; the new `selectedEffort` default (`Effort.HIGH`) inherits transparently.

### `MainActivity` — `app/src/main/java/de/pyryco/mobile/MainActivity.kt:197-216`

Add two lines to the `ThreadScreen(...)` call site at the `CONVERSATION_THREAD` route, alongside `onModelSelected = vm::onModelSelected`:

- `onEffortSelected = vm::onEffortSelected,`
- `onYoloToggled = vm::onYoloToggled,`

No imports change.

## State + concurrency model

- **Hot/cold:** Same shape as #253. `effortOverride` and `yoloEnabled` are hot `MutableStateFlow`s owned by the ViewModel. `selectedEffortFlow` and `runConfigFlow` are cold combines folded into the existing `state: StateFlow<ThreadUiState>` via `stateIn(WhileSubscribed(5_000))`.
- **Dispatcher:** No change. Synchronous `MutableStateFlow.value` writes on whichever dispatcher the caller invokes the mutator on (Main, in practice). No I/O added.
- **Shutdown / lifetime:** Both override flows are plain `MutableStateFlow`s GC'd with the ViewModel. No `onCleared()` override. Phase 0 documented behaviour: configuration change recreates the ViewModel, override is lost; the user's effort selection re-derives from `appPreferences.defaultEffort` and YOLO re-derives from `false`. Phase 4 will persist per-conversation.
- **YOLO reset across rotation is desirable behaviour, not a bug.** A user who toggled YOLO on, then rotated the device, sees it back to `false`. Given YOLO's intentional friction (only changeable via the StatusSheet, not the status row), this resets to the safer state. We do NOT need `rememberSaveable` or any ViewModel survival mechanism for YOLO this slice — that's a Phase 4 design call.
- **Override-precedence semantics:** `effortOverride ?: appPreferences.defaultEffort` — once the user picks an effort in the sheet, subsequent changes to the Settings default do not move this conversation's effort. Matches the Model semantics in #253.

## Error handling

No new failure modes. `appPreferences.defaultEffort` is already fallback-safe (`Effort.HIGH` on unparseable stored values, `AppPreferences.kt:52`). Both new mutators are synchronous `StateFlow.value` writes — they cannot throw. The Settings YOLO row stays dead code; it has no path into this slice.

## Testing strategy

Unit tests only for `ThreadViewModel`; instrumented Compose tests for `StatusSheet`. Matches #253/#254 split exactly.

### `ThreadViewModelTest` — `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`

Update the two existing initial/post-subscription assertions (lines 195-216): the `"high" == state.effort` checks become `Effort.HIGH == state.selectedEffort`, and add `assertEquals(false, state.yoloEnabled)` to both.

Add the following test functions in the existing JUnit 4 / `runTest` / `withTimeout` style (developer writes the function bodies per project idiom):

- **`selectedEffort_followsAppPreferencesDefault`** — given an `AppPreferences` with `setDefaultEffort(Effort.LOW)` called before VM construction, after subscribing assert `state.selectedEffort == Effort.LOW`. Mirrors `selectedModel_followsAppPreferencesDefault` (line 218).
- **`selectedEffort_reemitsWhenAppPreferencesDefaultChanges`** — subscribe, assert `Effort.HIGH`, call `prefs.setDefaultEffort(Effort.MAX)`, assert `Effort.MAX`. Mirrors the model version (line 232).
- **`onEffortSelected_overridesPerConversationWithoutMutatingPreferences`** — pre-condition `prefs.defaultEffort.first() == Effort.HIGH`, call `vm.onEffortSelected(Effort.MAX)`, assert `state.selectedEffort == Effort.MAX` AND `prefs.defaultEffort.first() == Effort.HIGH` (unchanged). This is the AC's "does not mutate AppPreferences" verification line for Effort.
- **`onEffortSelected_overrideWinsOverSubsequentDefaultChange`** — call `onEffortSelected(Effort.MAX)`, then `prefs.setDefaultEffort(Effort.LOW)`, assert `state.selectedEffort == Effort.MAX` (override sticks).
- **`yoloEnabled_initialValueIsFalseRegardlessOfAppPreferencesDefault`** — pre-set `prefs.setDefaultYolo(true)`, construct the VM, subscribe, assert `state.yoloEnabled == false`. This is the AC's "YOLO ignores any app-level preference" verification line — it must explicitly check that `defaultYolo = true` does NOT leak into the ViewModel.
- **`onYoloToggled_flipsStateAndDoesNotMutatePreferences`** — pre-condition `prefs.defaultYolo.first() == false`, call `vm.onYoloToggled(true)`, assert `state.yoloEnabled == true` AND `prefs.defaultYolo.first() == false` (still unchanged). Then `vm.onYoloToggled(false)`, assert `state.yoloEnabled == false`.
- **`yoloEnabled_remainsFalseWhenAppPreferencesDefaultYoloChanges`** — subscribe, assert `false`, call `prefs.setDefaultYolo(true)`, advance, assert `state.yoloEnabled == false` (no subscription, intentionally). Mirror image of the model `reemitsWhenChanges` test but proving the opposite.

Six new tests + two updated tests + the two existing initial-value tests get one new assertion line each. ~120-150 LOC of test code total.

### `StatusSheetTest` — `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt`

Existing 4 tests need their `StatusSheetContent(...)` calls extended to pass the new parameters (developer can default them to `selectedEffort = Effort.HIGH, onEffortSelected = {}, yoloEnabled = false, onYoloToggled = {}`). Add:

- **`renders_effort_section_with_all_five_chips`** — render with `selectedEffort = Effort.HIGH`. Assert `hasText("Effort")` is displayed. Assert each of `"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"` is displayed.
- **`tapping_low_chip_invokes_onEffortSelected_with_low`** — render with `selectedEffort = Effort.HIGH`, capture into `mutableListOf<Effort>()`. `onNode(hasText("low")).performClick()`. Assert exactly `[Effort.LOW]` was reported. (One representative chip is enough; the LOW/MEDIUM/HIGH/XHIGH/MAX mapping is exhaustively covered by the iteration over `Effort.entries`.)
- **`selected_effort_chip_reports_selected_semantics`** — render with `selectedEffort = Effort.MAX`. Use `onNode(isSelectable() and hasAnyDescendant(hasText("max"))).assertIsSelected()`. Sister assertion: a non-selected chip reports `assertIsNotSelected`.
- **`renders_yolo_section_with_title_and_supporting_text`** — assert `hasText("YOLO mode")` + `hasText("Auto-accept tool calls")` + `hasText("Claude runs commands without asking for confirmation. Use carefully.")` are displayed.
- **`tapping_yolo_row_when_off_invokes_onYoloToggled_with_true`** — render with `yoloEnabled = false`, capture into `mutableListOf<Boolean>()`, click the row (`onNode(hasText("Auto-accept tool calls")).performClick()` works since `toggleable` owns the whole row). Assert exactly `[true]` was reported.
- **`tapping_yolo_row_when_on_invokes_onYoloToggled_with_false`** — render with `yoloEnabled = true`, click the row, assert `[false]` was reported.

Six new tests added. ~80-100 LOC of test code.

`./gradlew test` covers ViewModel tests; `./gradlew connectedAndroidTest` covers the StatusSheet tests. Both must pass.

### Single-writer invariant — what the test suite does NOT cover

The AC's `git grep` line ("the only writer is the StatusSheet event handler on `ThreadViewModel`") is a structural invariant of the codebase, not a runtime property. It is enforced by:

1. **Code review** — the dispatcher's code-reviewer agent reads the diff and grep-checks the invariant.
2. **The ViewModel's public surface** — `yoloEnabled: MutableStateFlow<Boolean>` is `private`; the only `public` mutator is `onYoloToggled(Boolean)`. There is no setter, no reset path, no DataStore wire.

We do not add a test asserting "no other call site writes `yoloEnabled`" because the field is `private` to the ViewModel — by Kotlin's visibility rules, no other call site **can** write it without changing the visibility. The grep AC catches accidental visibility-widening; the test suite catches behaviour.

## Open questions

- **`Effort.label()` visibility widening.** Spec recommends widening `EffortPickerDialog.kt:77` from `internal` to a top-level `public fun Effort.label()` in the same file (or moving it next to the `Effort` enum). If the developer finds the move triggers a Spotless rule about same-file extensions, document the alternate placement in the PR description. The architect's bias: one-line visibility change matching `Model.label()`'s shape.
- **Strings extraction.** Inline strings ("Effort", "YOLO mode", "Auto-accept tool calls", "Claude runs commands without asking for confirmation. Use carefully.") match `StatusSheet`'s existing inline convention ("Run configuration", "Model", model descriptions). If the project starts a `strings.xml` migration, this slice's strings come with that migration in one batch — not piecemeal.
- **Sheet auto-close on toggle.** The Model section auto-closes the sheet on selection (#254). Effort selection should too (single-tap completes the action). YOLO is a Switch toggle — auto-closing on toggle would feel jarring (the user may want to toggle, see the result, toggle back). Spec recommends: Effort auto-closes, YOLO does **not** auto-close. The developer wires only `onEffortSelected` (not `onYoloToggled`) into the close-then-callback wrapper at `ThreadScreen.kt:216-225`.

## Security review

**Verdict:** PASS

This ticket's threat model centres on a single architectural invariant: the StatusSheet must be the only path to enabling YOLO (auto-accept tool calls). YOLO is intentionally surfaced behind a sheet, not on the status row, so the user must take a deliberate action and confirm what they're enabling. The AC enumerates the invariant and the `git grep` enforcement.

**Findings:**

- **[Trust boundaries]** No findings. The boundary is internal: `ThreadViewModel.yoloEnabled` is `private`, and the single public mutator (`onYoloToggled`) takes a `Boolean` driven directly by the M3 `Switch`. No untrusted input crosses into the field — there is no parse step, no network message, no deep link. The Settings YOLO row (`SettingsScreen.kt:80, 169`) is local `mutableStateOf(false)` and has no path into `ThreadViewModel`; the dormant `appPreferences.defaultYolo` (`AppPreferences.kt:59-64`) is verified by the `yoloEnabled_initialValueIsFalseRegardlessOfAppPreferencesDefault` test to have zero effect on the field. Spec explicitly forbids importing `defaultYolo` into `ThreadViewModel`.
- **[Tokens / secrets / credentials]** N/A — no tokens, no secrets, no credentials touched.
- **[File / storage operations]** N/A — no file I/O. The dormant `defaultYolo` DataStore key stays untouched; this slice does not read it or write it.
- **[Inter-process / Android attack surface]** No findings. No `Activity` / `Service` / `BroadcastReceiver` / `ContentProvider` / WebView added. No new `<intent-filter>`. No `PendingIntent`. The composable can only be triggered by an in-process user gesture from `ThreadScreen`.
- **[Cryptographic primitives]** N/A — no crypto.
- **[Network & I/O]** N/A — no network calls, no I/O. All state is in-memory `MutableStateFlow`s with the ViewModel's lifetime.
- **[Error messages / logs / telemetry]** No findings. No new log calls added. No telemetry. The two new `MutableStateFlow.value` writes cannot throw, so no error path exists to inspect.
- **[Concurrency]** No findings. The two new mutators are synchronous `StateFlow.value` writes — no coroutine launched, no scope to leak. The new `runConfigFlow` and `selectedEffortFlow` are folded into the existing `stateIn(WhileSubscribed(5_000))`, inheriting its cancellation semantics. No new mutexes. No check-then-mutate races (every update is a single `.value = ...` assignment, not a read-then-write).
- **[Threat model alignment]** No findings on this slice. Mobile-specific threats considered:
  - **UI screenshot leakage of YOLO state** — the user is choosing to enable YOLO; a screenshot capturing the state is not a confidentiality breach. Out of scope.
  - **Accessibility-service eavesdropping** — TalkBack reads the YOLO Switch state aloud (`Role.Switch` semantics). This is correct behaviour; the user controls when the sheet is visible. No mitigation needed.
  - **Malicious deep link triggering YOLO** — no deep link path added; out of attack surface.
  - **Process death restoring YOLO on** — Phase 0 deliberately does NOT persist YOLO across configuration change or process death. The reset-to-`false` behaviour is the safer default. If Phase 4 adds persistence, that future ticket inherits the security review.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-17

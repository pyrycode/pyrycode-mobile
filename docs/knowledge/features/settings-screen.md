# Settings screen

Sectioned settings screen at the `settings` route. Visual treatment is locked to Figma node `17:2` (since #64). Phase 0 visual skeleton — every row destination, every toggle's persistence, and every subtitle's data source is deferred to Phase 3+ tickets.

## What it does

Replaces the prior `SettingsPlaceholder` stub with a scrollable list of seven labelled sections enumerating the canonical settings surface:

1. **Connection** — `Server`, `Pair another server`.
2. **Appearance** — `Theme` (subtitle reads "System default" / "Light" / "Dark" from the `AppPreferences.themeMode` flow since #86; tapping the row opens an M3 single-choice `ThemePickerDialog` since #87 that writes through `SettingsViewModel.onSelectTheme(...)` → `AppPreferences.setThemeMode(...)`), `Use wallpaper colors` (since #89 — VM-backed M3 `Switch` row whose `checked` state mirrors `AppPreferences.useWallpaperColors`; tapping calls `SettingsViewModel.onToggleUseWallpaperColors(...)` → `AppPreferences.setUseWallpaperColors(...)`, and the composition-root collector from #88 repaints `PyrycodeMobileTheme(dynamicColor = …)` live. SDK-gated: on Android < 12 the switch renders `enabled = false` with `supporting` text "Requires Android 12 or newer" — structurally unreachable in production today since Min SDK 33 > S = 31, but implemented per AC).
3. **Defaults for new conversations** — `Default model`, `Default effort`, `Default YOLO`, `Default workspace`.
4. **Notifications** — `Push notifications when claude responds`, `Notification sound`.
5. **Memory** — `Installed memory plugins`, `Manage per-channel memory`.
6. **Storage** — `Archived discussions` (headline updated #94 — taps navigate to the in-app [Archived Discussions screen](archived-discussions-screen.md) via the `onOpenArchivedDiscussions` callback; the prior `"11 archived"` subtitle placeholder was dropped), `Clear cache`.
7. **About** — Version (live `BuildConfig.VERSION_NAME` / `VERSION_CODE` since #90), `Open source · github.com/pyrycode/pyrycode-mobile` (taps launch the platform browser via `Intent.ACTION_VIEW` since #90), `Privacy policy`, `License: MIT` (taps open the in-app [License screen](license-screen.md) since #91).

Each row has one of five trailing-affordance shapes: chevron, M3 `Switch`, outlined "Add" pill, `OpenInNew` icon, or none. The full row inventory (headlines, subtitle literals, trailing per row) is enumerated in [`../codebase/64.md`](../codebase/64.md).

## How it works

Stateless Composable with two local-only placeholder switch states (`defaultYolo`, `pushNotifications`), one `LocalContext` capture for external-URL dispatch, (since #87) one local `var showThemeDialog by remember { mutableStateOf(false) }` for the Theme picker open-state, and (since #89) one `val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` near the top of the body for the Use-wallpaper-colors row's enabled/disabled gate. Both persisted-state pairs (`themeMode` / `onSelectTheme`, `useWallpaperColors` / `onToggleUseWallpaperColors`) are hoisted to [`SettingsViewModel`](settings-viewmodel.md); no other repository or ViewModel is touched.

```kotlin
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    useWallpaperColors: Boolean,
    onSelectTheme: (ThemeMode) -> Unit,
    onToggleUseWallpaperColors: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenArchivedDiscussions: () -> Unit,
    modifier: Modifier = Modifier,
)
```

All seven leading parameters are required, in that order. Parameter ordering since #89 groups state values together (`themeMode`, `useWallpaperColors`), then event callbacks together (`onSelectTheme`, `onToggleUseWallpaperColors`), then nav callbacks, then `modifier` — matches the Compose stdlib's grouping convention. `themeMode` lost its `ThemeMode.SYSTEM` default in #87 once the row became interactive — when the value drives a writable affordance, every call site must wire it explicitly so a forgotten preview can't render a stale literal. `onOpenLicense` (added #91), `onSelectTheme` (added #87), `onOpenArchivedDiscussions` (added #94), and the `useWallpaperColors` / `onToggleUseWallpaperColors` pair (added #89) likewise carry no defaults — the rule is: navigation/event callbacks → no default; static display state with a neutral safe value → default OK, but the moment that display state gains a writable counterpart, drop the default. Production wires both VM-backed pairs from `composable(Routes.SETTINGS)` (see [Settings ViewModel](settings-viewmodel.md)).

Skeleton:

- `Scaffold` with an M3 `TopAppBar` — `title = stringResource(R.string.settings_title)` ("Settings"), `navigationIcon` is the canonical back-arrow `IconButton` (`Icons.AutoMirrored.Filled.ArrowBack` + `R.string.cd_back`) wired to the `onBack` callback. Same shape as `DiscussionListScreen`.
- Body is a `Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp))` — `Column` + `verticalScroll`, not `LazyColumn`, because the row count is fixed at ~18 items and the heterogeneous row shapes make item-keying noisy with no recycling benefit.
- Two `remember { mutableStateOf(...) }` switch states, declared inside the `Scaffold` content lambda: `defaultYolo = false`, `pushNotifications = true`, plus (since #87) `showThemeDialog = false` for the Theme picker open-state. None are `rememberSaveable` — config-change persistence for the two remaining placeholders is Phase 3's job via additional fields on [`SettingsViewModel`](settings-viewmodel.md); the Theme dialog's open-state deliberately resets on re-entry so a stale picker can't reopen on top of a screen the user navigated back to. The previously-third `materialYou = true` local was deleted in #89 once the row became real persisted state — see § Use-wallpaper-colors row below.
- The Appearance → Theme row's `supporting` text is sourced from the `themeMode` parameter via an `internal` `ThemeMode.label()` extension at the bottom of `SettingsScreen.kt` (`SYSTEM → "System default"`, `LIGHT → "Light"`, `DARK → "Dark"`). Visibility was widened from `private` to `internal` in #87 so `ThemePickerDialog.kt` (same package) imports the same definition for its three radio labels — single source of truth for the row subtitle and the dialog options. The strings are hardcoded, deliberately not routed through `strings.xml`.
- The Theme row's `onClick` flips `showThemeDialog = true` (#87). When `showThemeDialog`, an `if`-guarded `ThemePickerDialog(selected = themeMode, onConfirm = { mode -> onSelectTheme(mode); showThemeDialog = false }, onDismiss = { showThemeDialog = false })` block sits above the body `Column`. See `ThemePickerDialog` below.
- The Use-wallpaper-colors row (#89) sits directly beneath the Theme row in the Appearance section. Inline `SettingsRow(headline = stringResource(R.string.settings_use_wallpaper_colors), supporting = if (!dynamicColorSupported) stringResource(R.string.settings_use_wallpaper_colors_unsupported) else null, trailing = { Switch(checked = useWallpaperColors, onCheckedChange = onToggleUseWallpaperColors, enabled = dynamicColorSupported) })`. No row-level `onClick` — consistent with the `defaultYolo` / `pushNotifications` switch rows. `Switch.enabled = false` handles both the visual disabled state and the no-op tap behaviour on Android < 12 — the `onCheckedChange` callback is passed unconditionally because M3's `Switch` ignores it when `enabled = false`. The label string diverges from Figma node `17:2` (which still shows the legacy "Use Material You dynamic color" copy) — follow the ticket body, which clarified the user-facing name to "Use wallpaper colors" since the Figma was drawn.
- One `val context = LocalContext.current` captured at the top of the `Scaffold` content lambda alongside the switch states (#90). Closed over by the About-section Open-source row's `onClick` to dispatch `Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_REPO_URL))` via `context.startActivity(...)`. `SOURCE_REPO_URL` is a file-private top-level `const val` declared at the bottom of the file. Unguarded — no `try`/`catch` for `ActivityNotFoundException`; the failure mode is irrelevant on min SDK 33+ where a default browser is universal.
- About-section Version row reads `BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE` inline at composition time (#90), substituted into the same `headline` / `supporting` slots the locked Figma row treatment requires. `BuildConfig` is generated via `buildFeatures.buildConfig = true` in `app/build.gradle.kts` (AGP 8.x defaults the toggle to `false`, so the flag must stay on). No `remember`; `BuildConfig.*` are compile-time `const val`s.

### Private composables in the same file

- `SettingsSectionHeader(text)` — `Text` with `MaterialTheme.typography.labelLarge` in `MaterialTheme.colorScheme.primary`, padding `(start=16, end=16, top=16, bottom=4)`.
- `SettingsRow(headline, supporting?, trailing?, onClick?)` — wraps M3 `ListItem` with `headlineContent`, `supportingContent`, `trailingContent`. When `onClick != null`, the `ListItem` modifier becomes `Modifier.clickable(onClick)`; otherwise the row is non-interactive. `colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)` is set explicitly so dark-mode list rows track the same `surface` token as the surrounding `Scaffold`.
- `ChevronIcon()` — 20dp `Icons.AutoMirrored.Filled.KeyboardArrowRight`, decorative (`contentDescription = null`).
- `ExternalLinkIcon()` — 18dp `painterResource(R.drawable.ic_open_in_new)`, decorative. Drawable is a stock Material `open_in_new` vector at 24dp viewport, sized down via `Modifier.size(18.dp)`. Not in `material-icons-core` / `material-icons-extended` because the spec bans new icon packages — the asset ships as a per-file vector drawable instead.
- `AddPill(onClick)` — `TextButton` (not `OutlinedButton`) with 16dp leading `Icons.Default.Add`, `Spacer(width=4.dp)`, and "Add" text in `MaterialTheme.typography.labelLarge`. The architect's spec called `OutlinedButton` with a fallback to `TextButton` if the default border was too prominent; the developer picked `TextButton` outright after the eyeball check against the Figma screenshot. The `+` icon takes `contentDescription = stringResource(R.string.cd_add_memory_plugin)` because it's the only labelled interactive control inside the pill.

### `ThemePickerDialog` (sibling file)

`internal` composable in `ui/settings/ThemePickerDialog.kt` (#87). M3 `AlertDialog` with `title = stringResource(R.string.settings_theme_dialog_title)` ("Theme") over a `Column(Modifier.selectableGroup())` of three `Row`s — one per `ThemeMode.entries`. Each row carries `Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = { pending = mode }).padding(vertical = 8.dp)` plus `RadioButton(selected, onClick = null)` (the row owns the gesture) + 8.dp `Spacer` + `Text(mode.label(), style = bodyLarge)`. Buttons: `confirmButton = TextButton { onConfirm(pending) }` labelled `android.R.string.ok`; `dismissButton = TextButton { onDismiss() }` labelled `android.R.string.cancel`. `onDismissRequest = onDismiss`, so scrim taps and back-press route through the same path as Cancel — `pending` is discarded. Pending choice: `var pending by remember(selected) { mutableStateOf(selected) }` — the `selected` key resets the radio when the parent reopens the dialog after the persisted value changed in the background. Why OK / Cancel over tap-to-commit: the entire app's color scheme repaints on confirm, so an explicit confirm step matches AC #2's "confirming" / "cancelling" wording and avoids accidental-tap repaints.

### Strings

Six string ids in `strings.xml`: `settings_title` ("Settings"), `cd_add_memory_plugin` ("Add memory plugin"), `settings_theme_dialog_title` ("Theme", added #87 for the `ThemePickerDialog` title), `settings_use_wallpaper_colors` ("Use wallpaper colors", added #89 for the Material You row headline), `settings_use_wallpaper_colors_unsupported` ("Requires Android 12 or newer", added #89 for the disabled-pre-S `supporting` line), and the pre-existing `cd_back`. The dialog's OK / Cancel buttons reuse `android.R.string.ok` / `android.R.string.cancel` — no app-side strings for those. **The remaining row headlines and subtitles are hardcoded inline as Kotlin `String` literals** — per the ticket they're intentional scaffolding placeholders that Phase 3 will replace wholesale by data-layer wiring, so routing them through `strings.xml` would create work the resource files can't usefully own. Extracting the Use-wallpaper-colors headlines in #89 is the exception: the row became real persisted state, the helper string had to land somewhere localisable, and the precedent for extracting "row that became real" was set by `archived_discussions_settings_row` in #94. The three Theme labels (`"System default"` / `"Light"` / `"Dark"`) live as the body of the `internal` `ThemeMode.label()` extension and are reused by both the row subtitle and the dialog rows.

## Configuration / usage

Mounted at the `settings` route in `PyryNavHost` (`MainActivity.kt`):

```kotlin
composable(Routes.SETTINGS) {
    val vm = koinViewModel<SettingsViewModel>()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
    SettingsScreen(
        themeMode = themeMode,
        useWallpaperColors = useWallpaperColors,
        onSelectTheme = vm::onSelectTheme,
        onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
        onBack = { navController.popBackStack() },
        onOpenLicense = { navController.navigate(Routes.LICENSE) },
        onOpenArchivedDiscussions = { navController.navigate(Routes.ARCHIVED_DISCUSSIONS) },
    )
}
```

`vm.themeMode` and `vm.useWallpaperColors` are `StateFlow`s, so the no-arg `collectAsStateWithLifecycle()` overload is used — no `initialValue` argument needed. The same underlying `appPreferences.themeMode` / `appPreferences.useWallpaperColors` flows are also collected at the `setContent` root (drive `darkTheme: Boolean` / `dynamicColor: Boolean` for `PyrycodeMobileTheme(...)`); a single `setThemeMode` / `setUseWallpaperColors` write fans out to both collectors. See [Settings ViewModel](settings-viewmodel.md) for the VM surface and [App preferences](app-preferences.md) for the flow contract.

Entry point is the trailing settings-gear `IconButton` in `ChannelListScreen`'s `TopAppBar` (via `ChannelListEvent.SettingsTapped` → `navController.navigate(Routes.Settings)`).

## Edge cases / limitations

- **No persistence on the two remaining placeholder switches.** Toggle the `Default YOLO` or `Push notifications` switch, leave the screen, come back — the switch resets to its AC-specified default. This is intentional; the switches are visual stubs. Don't add `rememberSaveable` "just in case" — Phase 3 will route them through additional fields on [`SettingsViewModel`](settings-viewmodel.md) (which already hosts `themeMode` since #87 and `useWallpaperColors` since #89). The Theme row and the Use-wallpaper-colors row, by contrast, persist end-to-end — see § Appearance above.
- **Use-wallpaper-colors initial-state surprise.** A user upgrading from a pre-#89 build will see the switch jump from "on" (the prior cosmetic stub initial state) to "off" (the persisted default from #88). This is correct: the placeholder was never persisted, so there's nothing to migrate, and the AC explicitly defaults to OFF so the brand palette renders unless the user opts in. Worth knowing only so a future "the Material You switch turned itself off after the update!" report doesn't trigger a bisect.
- **Most non-switch row taps are still no-ops** including the "Add" pill, the `Privacy policy` link, and every Connection / Appearance / Defaults / Notifications / Memory row. No nav, no toast. The Storage section's "Archived discussions" row (since #94 — navigates to the in-app [Archived Discussions screen](archived-discussions-screen.md)), the About-section Open-source row (since #90 — launches `https://github.com/pyrycode/pyrycode-mobile` in the platform browser), and the About-section License row (since #91 — navigates to the in-app [License screen](license-screen.md)) are the exceptions. Sub-screens and the remaining external-link handlers are Phase 3+ tickets.

## Previews

Two `@Preview`s, both `private`, both at `widthDp = 412` to match Figma's frame width and the rest of the codebase:

- `SettingsScreenLightPreview` — `PyrycodeMobileTheme(darkTheme = false) { SettingsScreen(themeMode = ThemeMode.SYSTEM, useWallpaperColors = false, onSelectTheme = {}, onToggleUseWallpaperColors = {}, onBack = {}, onOpenLicense = {}, onOpenArchivedDiscussions = {}) }`.
- `SettingsScreenDarkPreview` — same with `darkTheme = true`.

Both previews must pass `themeMode` and `onSelectTheme` explicitly since #87 dropped the `themeMode` default, `onOpenArchivedDiscussions` since #94, and `useWallpaperColors` + `onToggleUseWallpaperColors` since #89. No dedicated preview for the disabled-pre-S branch — Compose `@Preview` doesn't easily simulate a lower runtime SDK level, and the disabled path is structurally simple (M3 default-disabled colors + helper-text supporting line); a curious developer can eyeball it by flipping `dynamicColorSupported = false` temporarily.

`SettingsViewModel` is unit-tested at `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — see [Settings ViewModel § Testing](settings-viewmodel.md). The screen itself has one instrumented test file (`app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt`, the project's first `createComposeRule()` test, landed with #90), pinning the About-section wiring — `versionRow_rendersBuildConfigVersionName` (substring match against the live `BuildConfig.VERSION_NAME`, so the test stays correct when `versionName` bumps) and `openSourceRow_hasClickAction`. Both `performScrollTo()` before asserting because the About section sits below the default viewport. #87 updated the two `setContent` blocks to pass the new required `themeMode` + `onSelectTheme` parameters explicitly; no new test methods were added — the picker-dialog UI is intentionally uncovered by Compose tests at this slice (the VM tests cover the contract that matters; visual fidelity goes through previews + manual run).

## Related

- Spec: `docs/specs/architecture/64-settings-screen.md`; About-row wiring specs `docs/specs/architecture/90-settings-about-version-row-and-open-source-row.md`, `docs/specs/architecture/91-settings-in-app-license-viewer.md`; Theme-row subtitle spec `docs/specs/architecture/86-theme-mode-preference.md`; Theme picker dialog spec `docs/specs/architecture/87-settings-theme-picker-dialog.md`; Use-wallpaper-colors switch spec `docs/specs/architecture/89-settings-use-wallpaper-colors-switch.md`
- Ticket notes: [`../codebase/64.md`](../codebase/64.md) (visual skeleton), [`../codebase/90.md`](../codebase/90.md) (About Version + Open-source wiring), [`../codebase/91.md`](../codebase/91.md) (License row → in-app viewer), [`../codebase/86.md`](../codebase/86.md) (Theme row subtitle → `AppPreferences.themeMode`), [`../codebase/87.md`](../codebase/87.md) (Theme picker dialog + `SettingsViewModel`), [`../codebase/89.md`](../codebase/89.md) (Use-wallpaper-colors switch + VM extension)
- Hosted ViewModel: [Settings ViewModel](settings-viewmodel.md)
- License sub-screen: [License screen](license-screen.md)
- Archived discussions sub-screen (since #94): [Archived Discussions screen](archived-discussions-screen.md)
- Figma node: `17:2` — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2
- Phase 1 stub it replaces: ticket #16 (`SettingsPlaceholder` in `MainActivity`)
- Entry point: [Channel list screen](channel-list-screen.md) — settings-gear `IconButton` in the `TopAppBar`
- Sibling back-nav screen pattern: [Discussion list screen](discussion-list-screen.md) — same `TopAppBar` + `IconButton(onBack)` shape

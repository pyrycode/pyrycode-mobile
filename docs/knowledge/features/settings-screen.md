# Settings screen

Sectioned settings screen at the `settings` route. Visual treatment is locked to Figma node `17:2` (since #64). Phase 0 visual skeleton — every row destination, every toggle's persistence, and every subtitle's data source is deferred to Phase 3+ tickets.

## What it does

Replaces the prior `SettingsPlaceholder` stub with a scrollable list of seven labelled sections enumerating the canonical settings surface:

1. **Connection** — `Server`, `Pair another server`.
2. **Appearance** — `Theme`, `Use Material You dynamic color`.
3. **Defaults for new conversations** — `Default model`, `Default effort`, `Default YOLO`, `Default workspace`.
4. **Notifications** — `Push notifications when claude responds`, `Notification sound`.
5. **Memory** — `Installed memory plugins`, `Manage per-channel memory`.
6. **Storage** — `Archived conversations`, `Clear cache`.
7. **About** — Version (live `BuildConfig.VERSION_NAME` / `VERSION_CODE` since #90), `Open source · github.com/pyrycode/pyrycode-mobile` (taps launch the platform browser via `Intent.ACTION_VIEW` since #90), `Privacy policy`, `License: MIT`.

Each row has one of five trailing-affordance shapes: chevron, M3 `Switch`, outlined "Add" pill, `OpenInNew` icon, or none. The full row inventory (headlines, subtitle literals, trailing per row) is enumerated in [`../codebase/64.md`](../codebase/64.md).

## How it works

Pure stateless Composable with three local-only switch states and one `LocalContext` capture for external-URL dispatch. No `ViewModel`, no DI wiring, no repository touched, no I/O beyond the About-section browser launch.

```kotlin
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Skeleton:

- `Scaffold` with an M3 `TopAppBar` — `title = stringResource(R.string.settings_title)` ("Settings"), `navigationIcon` is the canonical back-arrow `IconButton` (`Icons.AutoMirrored.Filled.ArrowBack` + `R.string.cd_back`) wired to the `onBack` callback. Same shape as `DiscussionListScreen`.
- Body is a `Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp))` — `Column` + `verticalScroll`, not `LazyColumn`, because the row count is fixed at ~18 items and the heterogeneous row shapes make item-keying noisy with no recycling benefit.
- Three `remember { mutableStateOf(...) }` switch states, declared inside the `Scaffold` content lambda: `materialYou = true`, `defaultYolo = false`, `pushNotifications = true`. Intentionally **not** `rememberSaveable` — config-change persistence is Phase 3's job via a `SettingsViewModel` + `DataStore`.
- One `val context = LocalContext.current` captured at the top of the `Scaffold` content lambda alongside the switch states (#90). Closed over by the About-section Open-source row's `onClick` to dispatch `Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_REPO_URL))` via `context.startActivity(...)`. `SOURCE_REPO_URL` is a file-private top-level `const val` declared at the bottom of the file. Unguarded — no `try`/`catch` for `ActivityNotFoundException`; the failure mode is irrelevant on min SDK 33+ where a default browser is universal.
- About-section Version row reads `BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE` inline at composition time (#90), substituted into the same `headline` / `supporting` slots the locked Figma row treatment requires. `BuildConfig` is generated via `buildFeatures.buildConfig = true` in `app/build.gradle.kts` (AGP 8.x defaults the toggle to `false`, so the flag must stay on). No `remember`; `BuildConfig.*` are compile-time `const val`s.

### Private composables in the same file

- `SettingsSectionHeader(text)` — `Text` with `MaterialTheme.typography.labelLarge` in `MaterialTheme.colorScheme.primary`, padding `(start=16, end=16, top=16, bottom=4)`.
- `SettingsRow(headline, supporting?, trailing?, onClick?)` — wraps M3 `ListItem` with `headlineContent`, `supportingContent`, `trailingContent`. When `onClick != null`, the `ListItem` modifier becomes `Modifier.clickable(onClick)`; otherwise the row is non-interactive. `colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)` is set explicitly so dark-mode list rows track the same `surface` token as the surrounding `Scaffold`.
- `ChevronIcon()` — 20dp `Icons.AutoMirrored.Filled.KeyboardArrowRight`, decorative (`contentDescription = null`).
- `ExternalLinkIcon()` — 18dp `painterResource(R.drawable.ic_open_in_new)`, decorative. Drawable is a stock Material `open_in_new` vector at 24dp viewport, sized down via `Modifier.size(18.dp)`. Not in `material-icons-core` / `material-icons-extended` because the spec bans new icon packages — the asset ships as a per-file vector drawable instead.
- `AddPill(onClick)` — `TextButton` (not `OutlinedButton`) with 16dp leading `Icons.Default.Add`, `Spacer(width=4.dp)`, and "Add" text in `MaterialTheme.typography.labelLarge`. The architect's spec called `OutlinedButton` with a fallback to `TextButton` if the default border was too prominent; the developer picked `TextButton` outright after the eyeball check against the Figma screenshot. The `+` icon takes `contentDescription = stringResource(R.string.cd_add_memory_plugin)` because it's the only labelled interactive control inside the pill.

### Strings

Three string ids in `strings.xml`: `settings_title` ("Settings"), `cd_add_memory_plugin` ("Add memory plugin"), and the pre-existing `cd_back`. **All row headlines and subtitles are hardcoded inline as Kotlin `String` literals** — per the ticket they're intentional scaffolding placeholders that Phase 3 will replace wholesale by data-layer wiring, so routing them through `strings.xml` would create work the resource files can't usefully own.

## Configuration / usage

Mounted at the `settings` route in `PyryNavHost` (`MainActivity.kt`):

```kotlin
composable(Routes.Settings) {
    SettingsScreen(onBack = { navController.popBackStack() })
}
```

Entry point is the trailing settings-gear `IconButton` in `ChannelListScreen`'s `TopAppBar` (via `ChannelListEvent.SettingsTapped` → `navController.navigate(Routes.Settings)`).

## Edge cases / limitations

- **No persistence.** Toggle a switch, leave the screen, come back — the switch resets to its AC-specified default. This is intentional; the screen is a visual stub. Don't add `rememberSaveable` "just in case" — Phase 3 replaces these with `StateFlow`-backed values from a `SettingsViewModel`.
- **Material You toggle is cosmetic.** Flipping it doesn't actually call back into `PyrycodeMobileTheme(dynamicColor = ...)`. `Theme.kt` defaults `dynamicColor = false`; that wiring is Phase 3.
- **Most non-switch row taps are still no-ops** including the "Add" pill, the `Privacy policy` link, and every Connection / Appearance / Defaults / Notifications / Memory / Storage row. No nav, no toast. The Open-source row (since #90) is the lone exception — it launches `https://github.com/pyrycode/pyrycode-mobile` in the platform browser. Sub-screens and the remaining external-link handlers are Phase 3+ tickets.
- **License row is intentionally still dead.** #90 wired the Version and Open-source rows but explicitly left License alone; an in-app license viewer lands in its own sibling ticket. Do not add an `onClick` here without that ticket on the board.

## Previews

Two `@Preview`s, both `private`, both at `widthDp = 412` to match Figma's frame width and the rest of the codebase:

- `SettingsScreenLightPreview` — `PyrycodeMobileTheme(darkTheme = false) { SettingsScreen(onBack = {}) }`.
- `SettingsScreenDarkPreview` — same with `darkTheme = true`.

No unit tests — no ViewModel, no state machine, no business logic to assert. One instrumented test file lands with #90: `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` (the project's first `createComposeRule()` test), with two `@Test`s pinning the About-section wiring — `versionRow_rendersBuildConfigVersionName` (substring match against the live `BuildConfig.VERSION_NAME`, so the test stays correct when `versionName` bumps) and `openSourceRow_hasClickAction`. Both `performScrollTo()` before asserting because the About section sits below the default viewport.

## Related

- Spec: `docs/specs/architecture/64-settings-screen.md`; About-row wiring spec `docs/specs/architecture/90-settings-about-version-row-and-open-source-row.md`
- Ticket notes: [`../codebase/64.md`](../codebase/64.md) (visual skeleton), [`../codebase/90.md`](../codebase/90.md) (About Version + Open-source wiring)
- Figma node: `17:2` — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2
- Phase 1 stub it replaces: ticket #16 (`SettingsPlaceholder` in `MainActivity`)
- Entry point: [Channel list screen](channel-list-screen.md) — settings-gear `IconButton` in the `TopAppBar`
- Sibling back-nav screen pattern: [Discussion list screen](discussion-list-screen.md) — same `TopAppBar` + `IconButton(onBack)` shape

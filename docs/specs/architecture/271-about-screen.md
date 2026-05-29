# Dedicated About screen (#271)

## Context

The About information renders today as an inline section at the bottom of `SettingsScreen`
(`SettingsScreen.kt:255-274`): a `SettingsSectionHeader("About")` over four rows — Version
(`BuildConfig.VERSION_NAME` / `build <GIT_SHA>`), Open source (opens the repo in an external
browser), Privacy policy (clickable but inert), and "License: MIT". This ticket promotes those
four rows into a dedicated, navigable `AboutScreen` and replaces the inline section with a single
tappable "About" entry that opens it.

Pure UI/navigation reorganization — no backend, no ViewModel, no repository. The screen is static:
its only dynamic data (`VERSION_NAME`, `GIT_SHA`) are compile-time `BuildConfig` reads. It follows
the locked sub-screen pattern already used by Archived discussions (Settings → Storage → dedicated
screen), implemented in `ArchivedDiscussionsScreen.kt` + the `Routes.ARCHIVED_DISCUSSIONS` NavHost
entry. Blocks #270 (the a11y pass), which will cover the new screen once it exists.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The body reuses the four About rows exactly as they render today inside the Settings frame (17-2):
a non-clickable two-line Version row (`Version <name>` headline, `build <sha>` supporting), a
clickable "Open source · github.com/pyrycode/pyrycode-mobile" row with the `ic_open_in_new` trailing
icon, a clickable-but-inert "Privacy policy" row with the same trailing icon, and a non-clickable
"License: MIT" row — all M3 `ListItem`s on `colorScheme.surface`. The screen chrome (a back-arrow
`TopAppBar` titled "About" above a scrollable column) follows the locked sub-screen pattern from the
Archive frame (18-2), as implemented in `ArchivedDiscussionsScreen.kt`. No new Figma frame exists —
this is a verbatim relocation of already-specced content, so the visual contract is "renders
identically to the current inline rows, under sub-screen chrome."

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:255-274` — the four About rows
  being moved; copy them verbatim into `AboutScreen`. Lines 289-304 (`SettingsRow`) and 315-322
  (`ExternalLinkIcon`) are the helpers they depend on; line 354 is `SOURCE_REPO_URL`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt:58-91` — the locked
  sub-screen chrome to mirror: `Scaffold` + `TopAppBar(title, navigationIcon = back-arrow
  IconButton)` with `Icons.AutoMirrored.Filled.ArrowBack` and `contentDescription = cd_back`. Copy
  the chrome shape; `AboutScreen` has no ViewModel/Event/effects/snackbar — drop all of that.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:219-268` — the `Routes.SETTINGS` and
  `Routes.ARCHIVED_DISCUSSIONS` NavHost blocks and the `Routes` object (273-281). The About route +
  wiring mirror the Archived discussions shape exactly.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` — the four
  About-content tests (`versionRow_rendersBuildConfigVersionName`,
  `versionRow_rendersSupportingTextWithGitSha`, `openSourceRow_hasClickAction`,
  `licenseRow_hasNoClickAction`) move to `AboutScreenTest`; the per-test `setContent` param block is
  the harness to replicate.
- `app/src/main/res/values/strings.xml:9,18,34` — `cd_back`, `settings_title`,
  `archived_discussions_settings_row` for the new-string naming convention.
- `app/build.gradle.kts:46,49,71` — confirms `versionName`, the `GIT_SHA` `buildConfigField`, and
  `buildConfig = true` already exist; no Gradle change in this ticket.

## Design

### Package & files

All three production files live in / under `de.pyryco.mobile`:

1. **`ui/settings/AboutScreen.kt`** — NEW. Public `@Composable fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier)`.
2. **`MainActivity.kt`** — EDIT. New route + NavHost composable + `onOpenAbout` wiring.
3. **`ui/settings/SettingsScreen.kt`** — EDIT. Replace the inline About section with a single entry; add the `onOpenAbout` param; relocate two helpers.

### Helper sharing decision (share `SettingsRow`, move `ExternalLinkIcon`)

The ticket leaves share-vs-duplicate to the architect. Decision:

- **`SettingsRow`** is used by *every* Settings row and by all four About rows — genuinely shared.
  Bump its visibility `private` → `internal` (keep it defined in `SettingsScreen.kt`). `AboutScreen`,
  same package + module, references it directly. This keeps the locked row treatment single-sourced
  (a future row-shape change can't drift between the two screens) and holds the production file count
  at exactly 3.
- **`ExternalLinkIcon`** is used *only* by the Open source + Privacy rows, both of which leave
  Settings. **Move** it (as `private`) into `AboutScreen.kt`. Defining it in a file that no longer
  uses it would be backwards.
- **`SOURCE_REPO_URL`** (the Open source row's target) is used only by that row — **move** it into
  `AboutScreen.kt` as a `private const`.
- **`ChevronIcon`**, `AddPill`, `SettingsSectionHeader` stay `private` in `SettingsScreen.kt` —
  unchanged; the new "About" entry row reuses `ChevronIcon` in place.

This is extraction, not the forbidden "refactor unrelated Settings rows": `SettingsRow`'s body is
untouched (one visibility keyword), and the moved helper/const belong to the rows being extracted.

### `AboutScreen` composable contract

```
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier)
```

- `Scaffold(topBar = TopAppBar(...))` mirroring `ArchivedDiscussionsScreen.kt:58-72`:
  - `title = { Text(stringResource(R.string.about_title)) }`
  - `navigationIcon` = `IconButton(onClick = onBack)` wrapping `Icons.AutoMirrored.Filled.ArrowBack`,
    `contentDescription = stringResource(R.string.cd_back)`.
- Body: a `Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()))`
  holding the four rows in order. Use a scrollable `Column` (not `LazyColumn`) — the content is four
  fixed static rows; this matches how they render in `SettingsScreen` today, so the extraction is
  byte-for-byte faithful. **No inner `SettingsSectionHeader`** — the TopAppBar title "About" replaces
  the section header that grouped them inline.
- The four rows are copied verbatim from `SettingsScreen.kt:256-274`, preserving exact text and
  click behavior:
  1. `SettingsRow(headline = "Version ${BuildConfig.VERSION_NAME}", supporting = "build ${BuildConfig.GIT_SHA}")` — no `onClick`.
  2. `SettingsRow(headline = "Open source · github.com/pyrycode/pyrycode-mobile", trailing = { ExternalLinkIcon() }, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_REPO_URL))) })` — capture `val context = LocalContext.current` at the top of the composable body.
  3. `SettingsRow(headline = "Privacy policy", trailing = { ExternalLinkIcon() }, onClick = {})` — **keep `onClick = {}` exactly** (clickable but inert; per AC do NOT wire it up).
  4. `SettingsRow(headline = "License: MIT")` — no `onClick`, no trailing (per AC do NOT add a license viewer).
- Two `@Preview` functions (light + dark), `AboutScreen(onBack = {})` inside `PyrycodeMobileTheme`, mirroring the SettingsScreen preview pattern.

### `SettingsScreen` edits

- Add parameter `onOpenAbout: () -> Unit` immediately after `onOpenArchivedDiscussions` (before `modifier`).
- Remove `val context = LocalContext.current` (line 92) — after the Open source row leaves, nothing else uses it.
- Replace the entire About block (`SettingsSectionHeader("About")` + four rows, lines 255-274) with:
  ```
  SettingsSectionHeader("About")
  SettingsRow(
      headline = stringResource(R.string.about_settings_row),
      trailing = { ChevronIcon() },
      onClick = onOpenAbout,
  )
  ```
  **Keep the `SettingsSectionHeader("About")`.** Dropping it would leave a header-less "About" row
  immediately below "Clear cache", making it read as a Storage item. The mild "About" header / "About"
  row repetition is the standard M3 section-title-plus-entry idiom and is the safer choice.
- `SettingsRow`: `private` → `internal`.
- Delete `ExternalLinkIcon` (moved) and `SOURCE_REPO_URL` (moved).
- Remove now-unused imports: `android.content.Intent`, `android.net.Uri`,
  `androidx.compose.ui.platform.LocalContext`, `androidx.compose.ui.res.painterResource`,
  `de.pyryco.mobile.BuildConfig`. (`painterResource` left with `ExternalLinkIcon`; `BuildConfig` left
  with the Version row; verify no other usage before deleting each — `R` stays, it's used elsewhere.)
- Add `onOpenAbout = {}` to both `@Preview` call sites.

### `MainActivity` edits

- Add `const val ABOUT = "about"` to the `Routes` object.
- In the `Routes.SETTINGS` composable, thread `onOpenAbout = { navController.navigate(Routes.ABOUT) }`
  into the `SettingsScreen(...)` call (alongside the existing `onOpenArchivedDiscussions`).
- Add a new NavHost entry mirroring the Archived block, minus the ViewModel:
  ```
  composable(Routes.ABOUT) {
      AboutScreen(onBack = { navController.popBackStack() })
  }
  ```
- Add `import de.pyryco.mobile.ui.settings.AboutScreen`.

### Navigation & back behavior

`navController.navigate(Routes.ABOUT)` pushes About onto the back stack. The TopAppBar back arrow
calls `onBack` → `popBackStack()` → returns to Settings. System back is handled automatically by
`NavHost` (pops the back stack), so it also returns to Settings — no explicit `BackHandler` needed.
This is identical to how Archived discussions behaves; both AC back paths are satisfied by the
default NavHost wiring.

### New string resources

Add to `app/src/main/res/values/strings.xml`:

- `<string name="about_title">About</string>` — TopAppBar title.
- `<string name="about_settings_row">About</string>` — the Settings entry row headline.

The four *content* rows keep their literal strings (verbatim extraction; converting them to resources
would be an unrequested content change). The new chrome (title + entry) uses resources to match the
`settings_title` / `archived_*` convention.

## State + concurrency model

None. `AboutScreen` is stateless apart from `rememberScrollState()` for the scroll position.
`BuildConfig.VERSION_NAME` / `GIT_SHA` are compile-time `const` reads — stable, no recomposition
triggers. The Open source `onClick` captures the stable `LocalContext` and needs no `remember`. No
`viewModelScope`, no `StateFlow`, no coroutines.

## Error handling

Inherited unchanged from #90: the Open source `onClick` fires `Intent.ACTION_VIEW` for an `https://`
URI, resolved by the platform browser, **unguarded** (no try/catch). Do not add error handling — on
API 33+ a browser is universal, and #90 deliberately left this unwrapped. The Privacy row stays inert
(`onClick = {}`). No new failure modes are introduced by the move.

## Testing strategy

All coverage is instrumented (`./gradlew connectedAndroidTest`) — Compose UI assertions, consistent
with `SettingsScreenTest`. No `./gradlew test` (unit) coverage needed.

**NEW `app/src/androidTest/java/de/pyryco/mobile/ui/settings/AboutScreenTest.kt`** — port the four
About-content scenarios, with the trivial harness `setContent { PyrycodeMobileTheme { AboutScreen(onBack = {}) } }`
(rows are few and on-screen, but keep the `performScrollTo()` before each assertion to stay robust):

- `versionRow_rendersBuildConfigVersionName` — node `hasText("Version ${BuildConfig.VERSION_NAME}", substring = true)` exists.
- `versionRow_rendersSupportingTextWithGitSha` — node `hasText("build ${BuildConfig.GIT_SHA}", substring = true)` exists.
- `openSourceRow_hasClickAction` — node `hasText("Open source", substring = true)` asserts `hasClickAction()`.
- `licenseRow_hasNoClickAction` — `onNodeWithText("License: MIT")` asserts `hasClickAction().not()`.
- `backArrow_invokesOnBack` — pass a recording `onBack`, click `onNodeWithContentDescription(<cd_back string>)`, assert it fired.

**EDIT `SettingsScreenTest.kt`:**

- Delete the four moved tests (version, gitSha, openSource, license) — they now live in `AboutScreenTest`.
- Add `onOpenAbout = {}` to the two surviving `setContent` blocks (`wallpaperColorsRow_…`, `archivedDiscussionsRow_…`).
- Add `aboutRow_navigatesOnClick`: pass a recording `onOpenAbout`, find the entry row with the matcher
  `hasText("About") and hasClickAction()` (the `hasClickAction()` filter disambiguates the clickable
  row from the non-clickable `SettingsSectionHeader("About")` text), `performScrollTo()`, `performClick()`,
  assert the lambda fired. `ListItem` merged-descendant semantics expose the click action on the
  headline-text node, so this resolves to a single node.

NavHost-level navigation is not unit-testable here (no existing NavHost test); the composable-level
`onOpenAbout` callback test plus manual verification cover the wiring, consistent with #90/#93.

### Manual verification

Per AC: `./gradlew installDebug`, open Settings, tap the new "About" row, confirm the About screen
opens with the four rows; tap Open source → external browser loads the repo; press the TopAppBar back
arrow and (separately) the system back gesture → both return to Settings. Note this in the PR
description.

## Open questions

None.

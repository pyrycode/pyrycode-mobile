# About screen

Dedicated, navigable screen showing app version + build SHA, the open-source repo link, a privacy-policy row, and the MIT license line. Reached from Settings → "About" since #271. Static, stateless screen + a NavHost entry — no ViewModel, no repository, no backend. Shipped under `ui/settings/` (alongside `SettingsScreen` and `ArchivedDiscussionsScreen`) because discoverability follows the entry point, not the entity. The content is a verbatim extraction of the four About rows that rendered inline at the bottom of `SettingsScreen` from #90 through #271; this screen changed *where* they live, not *what* they say or do.

## What it does

Lists four rows, in order, under the standard sub-screen chrome (back-arrow `TopAppBar` titled "About"):

1. **Version** — headline `"Version ${BuildConfig.VERSION_NAME}"`, supporting `"build ${BuildConfig.GIT_SHA}"`. Non-clickable. `GIT_SHA` is the abbreviated commit SHA injected at build time (since #165 — was `VERSION_CODE` before; see [Settings screen § About Version row](settings-screen.md)), falling back to the literal `"unknown"` outside a git checkout.
2. **Open source · github.com/pyrycode/pyrycode-mobile** — clickable; launches the platform browser via `Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_REPO_URL))` on `context.startActivity(...)`. Trailing `ExternalLinkIcon` (the 18dp `ic_open_in_new` vector). Unguarded — no `try`/`catch` for `ActivityNotFoundException`; on Min SDK 33+ a browser is universal (inherited from #90).
3. **Privacy policy** — clickable-but-inert (`onClick = {}`), trailing `ExternalLinkIcon`. Deliberately not wired up per the #271 AC — promoting the row didn't change its behavior.
4. **License: MIT** — non-clickable text-only row (no trailing, no ripple). No license viewer — the in-app `LicenseScreen` from #91 was deleted in #163 and was **not** revived by #271.

## How it works

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- `Scaffold(topBar = TopAppBar(...))` mirroring [`ArchivedDiscussionsScreen`](archived-discussions-screen.md): `title = { Text(stringResource(R.string.about_title)) }`, `navigationIcon` = `IconButton(onClick = onBack)` wrapping `Icons.AutoMirrored.Filled.ArrowBack` with `contentDescription = stringResource(R.string.cd_back)`.
- Body is a `Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()))` holding the four rows. A scrollable `Column`, **not** `LazyColumn` — four fixed static rows, matching how they rendered in `SettingsScreen`, so the extraction is byte-for-byte faithful.
- **No inner `SettingsSectionHeader`** — the TopAppBar title "About" replaces the section header that grouped the rows inline.
- `val context = LocalContext.current` captured at the top of the body, closed over by the Open source row's `onClick`.

### Shared / moved helpers

- **`SettingsRow`** is reused directly — it was bumped `private` → `internal` in `SettingsScreen.kt` (#271) so this same-package screen single-sources the locked row treatment. `AboutScreen` does **not** redefine it. See [Settings screen § Private composables](settings-screen.md).
- **`ExternalLinkIcon`** (`private @Composable`, 18dp `painterResource(R.drawable.ic_open_in_new)`, decorative) and **`SOURCE_REPO_URL`** (`private const val = "https://github.com/pyrycode/pyrycode-mobile"`) were **moved into `AboutScreen.kt`** from `SettingsScreen.kt` in #271 — both are used only by the rows that left Settings.

### Strings

Two ids added to `strings.xml` in #271: `about_title` = "About" (TopAppBar) and `about_settings_row` = "About" (the Settings entry-row headline — a separate id from `about_title` so the two surfaces can diverge). `cd_back` reused. The four content rows keep their hardcoded Kotlin literals — verbatim extraction; routing them through `strings.xml` would be an unrequested content change (same scaffolding-placeholder rule the rest of Settings follows).

## Versioning

`versionName` (`app/build.gradle.kts`) is `1.0.<resolved versionCode>`
([#1722](../../specs/architecture/1722-version-name-build-number.md)). The default
code is the Git commit count (`git rev-list --count HEAD`, requiring a full
clone), falling back to 1 when Git cannot supply it. `-PversionCode=N` overrides
that code and produces `1.0.N`; for example, code 3901 yields `1.0.3901`.
Read the already resolved code rather than resolving the count again, so the
name and code stay paired under overrides. The version still has exactly three
decimal parts, no leading zeros and no suffix
([#1007](../../specs/architecture/1007-client-version-format.md)). The format
matters beyond this screen: `AppModule.mobileClientVersion()` sends
`pyrycode-mobile/$versionName` as both the [Noise `hello`'s
`client_version`](noise-ik-session.md) and the [relay socket's
`User-Agent`](relay-ws-transport.md). Hosted daemons compare `client_version`
against a configured minimum and reject a version they cannot parse (pyrycode's
`docs/protocol-mobile.md` § `hello`, "`client_version` format", pyrycode#2576) —
which is why the format is strict rather than free text. This screen keeps
showing the bare `versionName` with no prefix; only the wire value carries
`pyrycode-mobile/`. `ClientVersionTest` (`di/`) checks the bound value against
the spec's format rules directly, rather than trusting `versionName`'s literal
by inspection. The About row's generated-value assertion checks consumption;
it cannot catch a fixed version name that still satisfies the format. Inspect
release manifest or APK metadata for both the default and an override to check
the code/name pair.

For #1722, the verifier's fresh release APK checks confirm default
`4004 / 1.0.4004` and override `3901 / 1.0.3901`. Android 15 Settings' App info
page visibly shows `version 1.0.3901` for the installed override release APK;
installed-package evidence also confirms that code/name pair and absence of
`DEBUGGABLE`. Both packaged metadata and the actual Settings display are
verified. See the [passing verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/1736#issuecomment-5979213734)
for the preserved metadata, Settings XML and screenshot evidence.

## Configuration / usage

Mounted at `Routes.ABOUT` (`"about"`) in [`PyryNavHost`](navigation.md):

```kotlin
composable(Routes.ABOUT) {
    AboutScreen(onBack = { navController.popBackStack() })
}
```

Sole entry point: the [Settings screen](settings-screen.md) About section's "About" row, wired via the `onOpenAbout: () -> Unit` callback (`onOpenAbout = { navController.navigate(Routes.ABOUT) }`). No VM resolution, no `collectAsStateWithLifecycle`, no `onEvent` — the static screen drops all of it. No deep-link, no back-stack policy beyond the default `popBackStack()` on `onBack`.

Both the TopAppBar back arrow (`onBack` → `popBackStack()`) and the system back gesture (handled automatically by `NavHost`) return to Settings — no explicit `BackHandler` needed.

## Edge cases / limitations

- **Privacy policy row is inert.** `onClick = {}` — no nav, no toast, no URL. Wiring it (and any privacy-policy destination) is a future ticket; #271 deliberately preserved the inline row's no-op behavior.
- **No license viewer.** "License: MIT" is text-only. The in-app `LicenseScreen` + its route + bundled `assets/LICENSE` + `R.string.license_title` were all deleted in #163 and not revived here.
- **Open source `Intent` is unguarded.** No `ActivityNotFoundException` handling — irrelevant on Min SDK 33+ where a default browser is universal (inherited unchanged from #90).
- **Bottom-padding asymmetry vs Settings (deliberate).** The body `Column` omits the `.padding(bottom = 32.dp)` that `SettingsScreen`'s scroll column carries. Four fixed rows won't scroll on a phone; flagged as a non-blocking NIT in code review and kept intentional. See [`codebase/271.md`](../codebase/271.md) § Lessons learned.

## Previews

Two `@Preview`s, both `private`, both `widthDp = 412`, mirroring the `SettingsScreen` preview pattern:

- `AboutScreenLightPreview` — `PyrycodeMobileTheme(darkTheme = false) { AboutScreen(onBack = {}) }`.
- `AboutScreenDarkPreview` — same with `darkTheme = true`.

## Testing

Shared screen test (`./gradlew test`, Robolectric; also compiled for device runs) — `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/AboutScreenTest.kt`, 5 methods with the trivial harness `setContent { PyrycodeMobileTheme { AboutScreen(onBack = {}) } }` (each asserts after a `performScrollTo()` to stay robust):

- `versionRow_rendersBuildConfigVersionName` — node `hasText("Version ${BuildConfig.VERSION_NAME}", substring = true)` exists.
- `versionRow_rendersSupportingTextWithGitSha` — node `hasText("build ${BuildConfig.GIT_SHA}", substring = true)` exists (matcher reads the live constant, so it's correct on both dev builds with a real SHA and CI builds where `GIT_SHA == "unknown"`).
- `openSourceRow_hasClickAction` — `hasText("Open source", substring = true)` asserts `hasClickAction()`.
- `licenseRow_hasNoClickAction` — `onNodeWithText("License: MIT")` asserts `hasClickAction().not()`.
- `backArrow_invokesOnBack` — recording `onBack`, click `onNodeWithContentDescription("Back")`, assert it fired.

The first four ported verbatim out of `SettingsScreenTest` (where they tested the inline rows); `backArrow_invokesOnBack` is new. NavHost-level navigation is not unit-tested (no NavHost test exists); the composable-level `onOpenAbout` callback test in `SettingsScreenTest.aboutRow_navigatesOnClick` plus manual verification cover the wiring, consistent with #90/#93. Device-gated steps were pending an emulator at merge — see [`codebase/271.md`](../codebase/271.md) § Verification.

## Related

- Ticket note: [`../codebase/271.md`](../codebase/271.md)
- Spec: `docs/specs/architecture/271-dedicated-about-screen.md`
- Entry point: [Settings screen](settings-screen.md) About section "About" row (`onOpenAbout` callback)
- Navigation: [Navigation](navigation.md) — route `about`
- Sibling sub-screen (chrome this mirrors): [Archived discussions screen](archived-discussions-screen.md)
- About-row history this extraction preserves: #90 (Version + Open source), #163 (License row text-only + `LicenseScreen` deletion), #165 (Version supporting line → `BuildConfig.GIT_SHA`)
- Versioning: `versionName`'s `MAJOR.MINOR.PATCH` format and its use in the `hello`/`User-Agent` — [#1007 spec](../../specs/architecture/1007-client-version-format.md), [Noise session](noise-ik-session.md), [relay transport](relay-ws-transport.md)
- Figma: no dedicated frame — content specced inside the Settings frame `17:2` (https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2); sub-screen chrome follows the Archive frame `18:2`
- Follow-up: #270 (a11y pass — covers this screen once it exists); wiring the Privacy policy row is unscheduled

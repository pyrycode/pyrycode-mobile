# #64 — Settings screen matching Figma 17:2

Replace the `SettingsPlaceholder` stub with a sectioned Settings screen that mirrors Figma node 17:2: M3 `TopAppBar` + back nav, vertically scrollable body, seven labelled sections of stub rows (chevron / Switch / "Add" pill / external-link / none), no persistence, no ViewModel.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:175-189` — current `composable(Routes.Settings)` block + `SettingsPlaceholder`; the wiring you replace and the stub you delete.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:193-200` — `Routes` object; confirms `Routes.Settings = "settings"` and that no nav arguments are involved.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:52-99` — canonical pattern for **back-nav screen with `Scaffold` + `TopAppBar` + body**. The new screen mirrors this skeleton; lift the `Icons.AutoMirrored.Filled.ArrowBack` + `IconButton` + `stringResource(R.string.cd_back)` shape verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:49-90` — companion reference; same `@OptIn(ExperimentalMaterial3Api::class)` + `TopAppBar` idiom, slightly different (no back button — uses logo + action). Use as a tiebreaker only.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:257-279` — `PyrycodeMobileTheme(darkTheme, dynamicColor, content)` signature for the two `@Preview` composables.
- `app/src/main/res/values/strings.xml` — confirm `R.string.cd_back` exists (used by `DiscussionListScreen`); add any new string ids you introduce (title, content-descriptions for the icon buttons / "Add" / external-link). Do **not** hardcode user-facing strings.

Codegraph confirmed `SettingsPlaceholder` has exactly one caller (`MainActivity.PyryNavHost`); no fan-out.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

Full-screen `Schemes/surface` column. Top: small `TopAppBar` with leading `ArrowBack` (24dp icon in a 48dp container) and `title-large` "Settings". Body: vertically scrollable column of seven sections; each section starts with a `label-large` header in `Schemes/primary` (16dp horizontal, 16dp top / 4dp bottom padding), followed by `ListItem`-shaped rows (16dp horizontal / 10dp vertical, `body-large` headline in `on-surface`, optional `body-small` subtitle in `on-surface-variant`) with a trailing 20dp chevron, 18dp `OpenInNew`, M3 `Switch` (52×32), outlined "Add" pill (rounded-100, leading 16dp `+` icon, `label-large` primary text), or no trailing affordance. No dividers between rows; section headers are the visual seam.

## Context

Phase 1's #16 wired the `Settings` route to a literal placeholder. Figma 17:2 is the locked design for the screen. This ticket lands the **visual skeleton only** — every row's destination, every toggle's persistence, and every subtitle's data source are Phase 3+ tickets. Subtitle literals ("juhana-mac-2026", "Opus 4.7", build hash, "11 archived", etc.) are intentional placeholders, **not** wired to `BuildConfig` / `DataStore` / any repository.

No ViewModel, no Koin module, no repository touched. The screen is a pure stateless composable plus three local-only `mutableStateOf` switches.

## Design

### Public surface (single new file)

`app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt`:

```kotlin
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

That is the *only* `public` symbol the file exports. No state class, no event sealed type, no ViewModel — there is no hoisted state to flow through `MainActivity` and no navigation other than back. Keep the surface minimal so future tickets can split this into a stateful screen + stateless content without a public API change.

### Private composables in the same file

Use M3 `ListItem` as the row primitive. Its three slots (`headlineContent`, `supportingContent`, `trailingContent`) match Figma's row anatomy. Defaults handle padding adequately; AC #6 explicitly permits M3 defaults.

Private helpers (rough shapes — bodies are the developer's; signatures only here):

- `SettingsSectionHeader(text: String)` — `Text` with `MaterialTheme.typography.labelLarge` and `MaterialTheme.colorScheme.primary`; padding `start=16.dp, end=16.dp, top=16.dp, bottom=4.dp`. Matches Figma node `17:10/17:23/17:36/...`.
- `SettingsRow(headline, supporting?, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null)` — wraps `ListItem` with `headlineContent = { Text(headline) }`, optional `supportingContent`, optional `trailingContent`. When `onClick != null`, wrap the `ListItem` in `Modifier.clickable { onClick() }`. Used by every row in the screen.
- Trailing-content lambdas (defined inline at call sites or as small `@Composable` helpers — developer's choice):
  - Chevron: `Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))`.
  - External link: `Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))`.
  - Switch: `Switch(checked, onCheckedChange)` — `Switch` is already 52×32 by M3 defaults; no sizing needed.
  - "Add" pill: `OutlinedButton(onClick = {}, contentPadding = ButtonDefaults.ContentPadding) { Icon(Icons.Default.Add, ...); Spacer(width=4.dp); Text("Add", style = labelLarge) }`. `OutlinedButton`'s default shape is rounded-pill — matches Figma's `rounded-[100px]`. Specify `colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)` if the default isn't already primary.

The chevron and external-link icons are decorative; per Compose accessibility guidance, set `contentDescription = null` when the row's headline text already conveys the action. The "Add" button's icon **does** need a `contentDescription` (it's an interactive control); use a new string id (`R.string.cd_add_memory_plugin` or similar).

### Body skeleton

Inside `Scaffold { inner -> }`, body is:

```kotlin
Column(
    modifier = Modifier
        .padding(inner)
        .verticalScroll(rememberScrollState())
        .fillMaxSize()
        .padding(bottom = 32.dp), // matches Figma 17:9 pb=32
) {
    SettingsSectionHeader("Connection")
    SettingsRow(headline = "Server", supporting = "juhana-mac-2026", trailing = Chevron, onClick = {})
    SettingsRow(headline = "Pair another server", trailing = Chevron, onClick = {})
    SettingsSectionHeader("Appearance")
    // … etc per Acceptance Criteria, section by section, in the order listed.
}
```

`Column` + `verticalScroll` over `LazyColumn`: the list is fixed at ~24 items, the heterogeneous row types make LazyColumn item-keying noisy, and there's no recycling benefit. Match the simpler primitive.

### Section + row inventory

Take the AC list as the source of truth. Render sections in this order with exactly these rows; subtitle/trailing semantics are PO-locked:

| Section | Row | Subtitle | Trailing |
|---|---|---|---|
| Connection | Server | `juhana-mac-2026` | Chevron |
| Connection | Pair another server | — | Chevron |
| Appearance | Theme | `System` | Chevron |
| Appearance | Use Material You dynamic color | — | Switch (default **on**) |
| Defaults for new conversations | Default model | `Opus 4.7` | Chevron |
| Defaults for new conversations | Default effort | `high` | Chevron |
| Defaults for new conversations | Default YOLO | `off` | Switch (default **off**) |
| Defaults for new conversations | Default workspace | `scratch` | Chevron |
| Notifications | Push notifications when claude responds | — | Switch (default **on**) |
| Notifications | Notification sound | `Default` | Chevron |
| Memory | Installed memory plugins | `0 plugins` | "Add" pill (outlined, leading `+`) |
| Memory | Manage per-channel memory | — | Chevron |
| Storage | Archived conversations | `11 archived` | Chevron |
| Storage | Clear cache | — | Chevron |
| About | Version 0.1.0 | `build a8f3c2d` | — (none) |
| About | Open source · github.com/pyrycode/pyrycode-mobile | — | `OpenInNew` icon |
| About | Privacy policy | — | `OpenInNew` icon |
| About | License: MIT | — | — (none) |

Section-header label color is `MaterialTheme.colorScheme.primary` — this is the Figma "Schemes/primary" token, not a custom palette.

Hardcode the headline / subtitle literals as `String` literals inline at each call site. Per the ticket, these are intentional scaffolding placeholders; do **not** route them through `strings.xml` (resources are reserved for content that will eventually be localised, and these will be replaced wholesale by data wiring in Phase 3 anyway). The screen *title* "Settings", the `cd_back` content description, and any new content-descriptions for the trailing controls **do** belong in `strings.xml`.

### MainActivity wiring change

Edit `MainActivity.kt`:

1. Remove the `private fun SettingsPlaceholder(...)` definition at lines 181–189.
2. Remove unused imports left behind: `androidx.compose.foundation.layout.Column`, `androidx.compose.material3.TextButton` (verify nothing else in the file still references them before removing — `Column` and `TextButton` should both become orphaned, but check). The `Text` import is still used by the `ConversationThread` placeholder at line 173.
3. Add `import de.pyryco.mobile.ui.settings.SettingsScreen`.
4. Replace the body of `composable(Routes.Settings)` (line 175–177) with:

```kotlin
composable(Routes.Settings) {
    SettingsScreen(onBack = { navController.popBackStack() })
}
```

That is the entire MainActivity diff — ~3 net lines changed plus an import.

## State + concurrency model

Three local switch states inside `SettingsScreen`:

```kotlin
var materialYou by remember { mutableStateOf(true) }
var defaultYolo by remember { mutableStateOf(false) }
var pushNotifications by remember { mutableStateOf(true) }
```

These exist *purely* so the toggles animate visually when tapped during manual QA. They are not persisted across recompositions caused by configuration changes (no `rememberSaveable`) — that's intentional. Phase 3 tickets will replace these with `DataStore`-backed flows via a `SettingsViewModel`. Adding `rememberSaveable` now would be premature plumbing.

No `viewModelScope`, no `Flow`s collected, no coroutines launched. Pure synchronous composition; no concurrency surface to specify.

## Error handling

None. There are no IO calls, no parsing, no network, no permissions. The screen cannot fail to render. Row taps are no-ops (or a single `Log.d` TODO marker per AC #5).

If the developer adds any conditional crash paths "just in case" (null-checks on hardcoded literals, try/catch around `Log.d`, etc.), strip them in review — they violate "Don't add error handling, fallbacks, or validation for scenarios that can't happen."

## Testing strategy

**Two `@Preview` composables**, both `private`, both in `SettingsScreen.kt`:

- `SettingsScreenLightPreview` — `PyrycodeMobileTheme(darkTheme = false) { SettingsScreen(onBack = {}) }`, `@Preview(name = "Settings — Light", showBackground = true, widthDp = 412)`.
- `SettingsScreenDarkPreview` — same with `darkTheme = true`, `@Preview(name = "Settings — Dark", showBackground = true, widthDp = 412)`.

`widthDp = 412` matches Figma's frame width and the rest of the codebase's previews (see `DiscussionListScreen.kt:171`).

**No unit tests.** No ViewModel, no state machine, no business logic — a `runTest`-backed unit test would only re-assert the AC table above. The two `@Preview`s plus a manual `./gradlew assembleDebug` are sufficient verification.

**No instrumented tests** (no `ComposeTestRule`). Each tap is a no-op; there is nothing to assert.

**Verification checklist for the developer (do this before reporting done):**

1. `./gradlew assembleDebug` builds without warnings.
2. Render both previews in Android Studio's Compose Preview pane. Confirm visually: section order matches the table; each row's trailing affordance matches; switches start in their AC-specified positions; nothing is cropped or overflowing.
3. `./gradlew installDebug` on an emulator (or device), launch app, navigate to Settings via the channel-list top-bar action, confirm scrolling works, back arrow returns to the channel list, the three switches toggle on tap, all other taps are silent.
4. Toggle the system into dark mode; re-enter Settings; confirm `Schemes/primary` section headers stay legible against `Schemes/surface`.

## Open questions

- **"Open source · github.com/..." row overflow.** Figma allows two-line wrap (`whitespace-nowrap` is *not* set on this `<p>`). M3 `ListItem` headline by default wraps to two lines. No action required — leave default. Calling this out so the developer doesn't over-tighten with `maxLines = 1`.
- **"Add" button colors.** Figma renders `Add` text in `Schemes/primary` with no visible border (it's a `rounded-[100px]` pill but the screenshot at 412dp shows it borderless). `OutlinedButton`'s default uses `colorScheme.outline` for the border. If the developer's eyeball check against the screenshot says the M3 default border is too prominent, downgrade to `TextButton` with manual end-padding; either is acceptable. Don't add a custom shape modifier.
- **Theme.kt's `dynamicColor` default is `false`.** The "Use Material You dynamic color" switch in this stub is purely cosmetic — toggling it does **not** actually flip `PyrycodeMobileTheme(dynamicColor = ...)`. That wiring is Phase 3. Do not extend the stub to call back into Theme.

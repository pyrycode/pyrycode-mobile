# Scanner Connecting screen — architecture spec (#62)

## Context

Figma node `32:20` defines a Scanner — Connecting state variant: the surface a user sees after a successful QR scan while the pairing handshake is in flight. CameraX + relay integration is deferred to Phase 4, so the current `ScannerScreen.kt` transitions instantly to ChannelList with no intermediate state. Pre-building this visual now decouples Figma-fidelity work from network integration — when the real connecting window lands in Phase 4, the screen is already drawn.

Pure UI scaffolding: no data layer, no DI, no NavHost wiring. The screen exposes the server address as a parameter; the future caller owns the handshake state and transitions.

Phase 1.5 Figma-anchored catchup wave, sibling to #61 (Scanner Denied — already merged) and #60 (Scanner main polish — in flight on `feature/60`, touches `ScannerScreen.kt`, no file overlap with this ticket).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-20

Centered vertical column on `colorScheme.surface`: a 48dp indeterminate `CircularProgressIndicator` sits at roughly the vertical midpoint, followed by a centered headline ("Connecting to your pyrycode server…") and a monospace server-address line beneath it. All chrome (background, indicator track, text colors) binds to M3 theme slots — no hardcoded color or `TextStyle`.

**Intentional deviation from Figma:** the Figma frame shows a "Pair with pyrycode" `TopAppBar` with a back affordance. This screen does NOT render it. Rationale: `ScannerScreen.kt` and `ScannerDeniedScreen.kt` both omit the top bar (see #61's spec for the same precedent), the AC enumerates exactly three rendered elements (indicator + headline + address), and AC#4 forbids navigation. The top bar is a NavHost-level concern that will arrive when this screen is wired into the real handshake flow in Phase 4.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreen.kt:1-168` — closest structural sibling and the primary template. Mirror its imports, the `Surface(color=…) { Column(fillMaxSize().systemBarsPadding().padding(…)) { … } }` shell, the `@Preview` pair (light + dark, `widthDp = 412, heightDp = 892`, both wrapped in `PyrycodeMobileTheme`), and the kwarg ordering on `Text`.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:1-95` — secondary reference for the same `Surface`/`systemBarsPadding`/`verticalArrangement = Arrangement.Center` convention (more applicable here than `ScannerDeniedScreen`, which uses `Spacer(weight = 1f)` to pin actions to the bottom — this screen has no bottom actions, so center-arrangement fits).
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:257-279` — `PyrycodeMobileTheme(darkTheme: Boolean, dynamicColor: Boolean = false, content: …)` signature; previews wrap content here.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:55-91` — confirms `colorScheme.surface`, `colorScheme.onSurface`, `colorScheme.onSurfaceVariant`, `colorScheme.primary` are all defined for the dark scheme (the Figma reference renders against dark). Light scheme is the symmetric block at lines 17-53.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` (if present) — verify `bodyLarge` and `bodyMedium` resolve to the typography Figma binds (16sp/24sp Regular and 14sp/20sp Regular respectively, M3 defaults).
- `gradle/libs.versions.toml` — confirm `androidx.compose.material3` is already on classpath (`CircularProgressIndicator` is a Material 3 component). No new dependency should be needed.
- Spec `docs/specs/architecture/61-scanner-denied-screen.md` — the precedent for the "intentional deviation: omit Figma TopAppBar" rationale; cross-reference if a reviewer questions the omission.

## Design — file & composable surface

**One new file:** `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerConnectingScreen.kt`. No other files change. Not wired into `MainActivity` / NavHost (explicit in ticket scope discipline).

### Public surface

```kotlin
@Composable
fun ScannerConnectingScreen(
    serverAddress: String,
    modifier: Modifier = Modifier,
)
```

- `serverAddress` is the only required parameter — the host/port string the user just paired with (e.g. `"home.lan:7117"`). The screen does not validate, parse, or transform it; it renders verbatim.
- `modifier` is optional and forwarded to the root `Surface` so callers can constrain the screen in tests / hosts.
- No `onBack`, no `onCancel`, no callbacks of any kind. AC#4 is explicit: "no data fetching, no navigation, no side effects."

### Layout structure

```
Surface(color = colorScheme.surface, modifier = modifier.fillMaxSize())
└── Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    )
    ├── CircularProgressIndicator(modifier = Modifier.size(48.dp))   ← M3 indeterminate by default
    ├── Spacer(height = 24.dp)
    ├── Text("Connecting to your pyrycode server…",
    │        style = MaterialTheme.typography.bodyLarge,
    │        color = MaterialTheme.colorScheme.onSurface,
    │        textAlign = TextAlign.Center)
    ├── Spacer(height = 12.dp)
    └── Text(serverAddress,
             style = MaterialTheme.typography.bodyMedium,
             fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center)
```

`Arrangement.Center` vertically centers the three children as a block, which matches the Figma frame: the indicator+text cluster sits at roughly 43%–58% of the canvas height (top=384..520 of 892). Pixel-perfect vertical positioning isn't required — visual center is the spec.

### Indicator — use M3 built-in, do NOT hand-roll

Compose Material 3's `CircularProgressIndicator()` with no `progress` parameter renders an indeterminate animation: a ~270° open arc rotating continuously. This is exactly the visual the Figma node 32:20 / 34:2 frame depicts (the Figma asset is a static 3-cubic-Bézier path baked at one frame of the animation as an export workaround — there is no expectation to reproduce that path in Compose).

Defaults to bind:
- Size: `Modifier.size(48.dp)` — matches Figma's 48dp ProgressArc node.
- Color: M3 default is `colorScheme.primary`. Don't override.
- Track color: M3 default is `colorScheme.surfaceVariant` (the faint backing ring). Don't override; the Figma reference shows only the active arc, which matches the M3 default at scale.
- Stroke width: M3 default (4.dp). Don't override.

If the developer is tempted to pass `color =`, `trackColor =`, or `strokeWidth =` — don't. The M3 defaults are the closest visual match to the Figma asset and keep all theming on `colorScheme`. AC#5 demands no hardcoded color literals; the safest way to satisfy it is to call zero theming arguments.

### Typography binding (verify, do not guess)

- **Headline** (`"Connecting to your pyrycode server…"`): Figma binds Roboto Regular 16sp/24sp with 0.15 tracking on `schemes/on-surface`. Closest M3 role is `bodyLarge` (Regular 16sp/24sp, 0.5 letterSpacing — tracking differs slightly from Figma's 0.15, but weight + size match and the visual difference at 16sp is sub-perceptible). Use `MaterialTheme.typography.bodyLarge`. Do not inline a `TextStyle` to chase the 0.15 tracking — AC#2 demands a named role.
- **Server address** (`"home.lan:7117"`): Figma binds Roboto **Mono** Regular 14sp/20sp with 0.25 tracking on `schemes/on-surface-variant`. M3 `bodyMedium` matches exactly (Regular 14sp/20sp, 0.25 letterSpacing) — only the font family differs. Use `MaterialTheme.typography.bodyMedium` for the role AND pass `fontFamily = FontFamily.Monospace` as a top-level `Text` parameter. The `fontFamily` parameter on `Text` officially overrides only the family in the passed `style`, which preserves the named-role binding (AC#3) while satisfying the monospace requirement.

Do NOT write `style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)` — both forms compile and look identical, but the top-level `Text(fontFamily = …)` argument is clearer about the intent ("role + family override") and avoids tripping a code-review concern about inlined `TextStyle.copy(…)` looking like a hardcoded override.

`FontFamily.Monospace` is `androidx.compose.ui.text.font.FontFamily.Monospace` — Compose's platform-monospace alias (resolves to `monospace` on Android, which on AOSP devices renders as Droid Sans Mono / Roboto Mono depending on version). No font file needs to be bundled; do not add a `res/font/` resource for this ticket.

### Color binding summary

| Element | Slot |
|---|---|
| Root Surface background | `colorScheme.surface` |
| `CircularProgressIndicator` active arc | `colorScheme.primary` (M3 default — don't pass `color =`) |
| `CircularProgressIndicator` track | `colorScheme.surfaceVariant` (M3 default — don't pass `trackColor =`) |
| Headline | `colorScheme.onSurface` |
| Server address | `colorScheme.onSurfaceVariant` |

No hardcoded color literals. No `Color(0xFF…)` anywhere in the file.

### Spacing

- `padding(horizontal = 24.dp, vertical = 32.dp)` on the outer Column — matches `ScannerScreen.kt`'s exact values.
- `Spacer(height = 24.dp)` between indicator and headline — matches `ScannerScreen.kt`'s indicator-to-text gap.
- `Spacer(height = 12.dp)` between headline and address — Figma shows top=460 (headline baseline-ish) to top=500 (address) ≈ 40px gap, minus ~24px headline line height ≈ 12-16dp visual gap. 12.dp is the cleaner Material spacing token; if the dark preview looks tight against the Figma screenshot, the developer may bump to 16.dp. Both are acceptable.

## State + concurrency model

None. The screen is stateless and side-effect-free:

- No `remember` (no local state — the indicator's animation is internal to `CircularProgressIndicator` and runs as long as the composable is in composition).
- No `LaunchedEffect`, `DisposableEffect`, or coroutine usage in this file.
- No `ViewModel`.
- No `Context` / `LocalContext` usage.

This matches `ScannerScreen.kt` and `ScannerDeniedScreen.kt` exactly, and the Phase 0 "fake data only" stance — the screen is a pure function from `serverAddress` to UI.

## Error handling

N/A. There is no I/O, no network call, and no parse step in this screen. The screen is the *waiting* surface for the connecting state; success/failure transitions are caller-owned and arrive in Phase 4.

## Testing strategy

The three existing Figma-anchor screens (`WelcomeScreen.kt`, `ScannerScreen.kt`, `ScannerDeniedScreen.kt`) ship with no unit or instrumented tests beyond the `@Preview` composables — the previews function as the visual-fidelity check, and the AC for #62 explicitly calls them out. Match that bar exactly.

**Required (AC#6):**

- `@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)` → `PyrycodeMobileTheme(darkTheme = false) { ScannerConnectingScreen(serverAddress = "home.lan:7117") }`
- `@Preview(name = "Dark", …, uiMode = Configuration.UI_MODE_NIGHT_YES)` → `PyrycodeMobileTheme(darkTheme = true) { ScannerConnectingScreen(serverAddress = "home.lan:7117") }`
- Both `private`, both at the bottom of the file, both matching the kwarg ordering used by `ScannerDeniedScreen.kt`.
- Use the same `"home.lan:7117"` literal the Figma node 32:20 uses — keeps the side-by-side preview/Figma comparison apples-to-apples.

**Not required (do not add):**

- No `androidTest` `ComposeTestRule` test. Existing onboarding screens have none; adding one here is scope creep.
- No unit test. There's no logic to assert.
- No screenshot/snapshot test. The project hasn't adopted a snapshot testing library.

**Manual verification (developer's responsibility before opening the PR):**

1. `./gradlew assembleDebug` — must compile clean.
2. `./gradlew lint` — must pass without new findings on this file.
3. Open the file in Android Studio Preview pane and visually compare both light and dark previews against the Figma screenshot. Fetch the Figma reference yourself via `mcp__plugin_figma_figma__get_screenshot(fileKey: "g2HIq2UyPhslEoHRokQmHG", nodeId: "32:20")` — do not rely on this spec's prose alone. The dark preview is the primary check (Figma frame is dark); light is a derived variant of the same M3 bindings.

## Open questions

- **Headline-to-address spacing — 12.dp or 16.dp?** Either is acceptable. Developer picks whichever lands closer to the Figma screenshot in the dark preview. Don't churn on this in code review.
- **Letter-spacing on the headline.** Figma binds 0.15sp; M3 `bodyLarge` ships 0.5sp. The 0.35sp delta is sub-perceptible at 16sp on a phone screen. Spec resolution: use `bodyLarge` as-is, don't override letter-spacing. If a future visual-QA pass flags it, that's a Theme.kt-level decision (override `bodyLarge` for the whole app), not a per-screen `TextStyle.copy(…)`.

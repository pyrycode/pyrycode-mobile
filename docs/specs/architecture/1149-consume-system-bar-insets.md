# Consume system-bar insets once (#1149)

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `MainActivity.onCreate`, `PyryNavHost`: production Scaffold padding and route boundary.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` → `WelcomeScreen`: 168 dp logo offset, 104 dp logo and 28 dp title gap; footer bottom padding is 16 dp.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` → `ScannerViewport`: system bars and 18 dp header padding around a 48 dp Back target.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` → `PairCodeScreen`: matching header, system bars followed by IME padding, scrollable form.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListScreen`: list already relies on the activity for bar clearance.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`: nested Scaffold and IME-aware composer.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` → `SettingsScreen`: nested Scaffold and M3 top bar.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenInsetsTest.kt` → `applyBars`: nonzero inset injection pattern; existing proof omits the activity.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` → `ime`: real test IME selection/restoration.
- `app/src/androidTest/java/de/pyryco/mobile/StartupWorkspaceMigrationTest.kt` → `launch`: real activity with controlled startup dependencies.
- `docs/knowledge/features/navigation.md` § Configuration and `paste-code-dialog.md` § Form and rendering: stale double-padding claims are documentation handoff.
- `docs/knowledge/features/welcome-screen.md`, `scanner-screen.md`, `channel-list-screen.md`, `thread-screen.md`, `settings-screen.md`: preserve existing visual components and route contracts.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes / Compose evidence: synthetic bars for JVM proof; real device and visible test IME for screenshots.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

Read design context and screenshots for all three nodes. Pair and scanner share a titleLarge Pairing header, back arrow and inversePrimary divider on surface; pair uses filled M3 fields and primary pill actions, scanner a rounded camera viewport. Welcome places the existing 92×104 dp logo 168 dp below the safe area, above headlineLarge copy, with bottom actions/footer; existing atmospheric gradients and theme roles remain intact. Native 48 dp Back targets keep their existing 18 dp container offset; the title sits 28 dp below the safe area.

## Change

Append `consumeWindowInsets(innerPadding)` after `padding(innerPadding)` on the production `PyryNavHost` modifier. The activity reserves system-bar space and declares that same space consumed, so descendant system-bar modifiers and M3 scaffolds add only remaining insets. IME padding still reserves keyboard height beyond the already reserved bottom inset. No negative spacing, screen changes, new API, state, jobs, I/O, failure branches or logging are needed for this layout-only correction; existing startup lifecycle logs remain in place.

Size check: one deliverable, one production file, approximately 400 total written lines including activity/device proof and this plan, no new exported production declarations, one call site, two acceptance criteria, zero error branches. The refiner's 250-line estimate omits some real-activity screenshot setup; the work remains small and below every boundary. No in-flight feature branch touches `MainActivity.kt` after fetching origin. Codegraph context identified the boundary; its qualified `MainActivity.onCreate` callees query returned no results, so source inspection supplied the details.

## Testing strategy

- Add a shared regression launching `MainActivity`, inject nonzero status/navigation insets into its Compose view, and assert Welcome title geometry (logo top + 104 + 28 dp) and footer position. Observe the double-inset failure before applying the fix, then verify green; vary insets to catch a fixed compensation.
- Add focused device evidence through the production activity at 412×892 and 360×800 dp, recording display density and measured system/IME insets alongside fresh PNGs. Verify Welcome geometry, scanner/pair header parity and clearance, both fields and Pair/Cancel with a genuinely visible test IME, and list/thread/settings control clearance using the existing fake-backed test environment. Device-only because this requires shell display configuration, actual IME visibility and saved pixels. Restore device settings and dependency overrides after each test.
- Run the touched shared/device classes, `spotlessApply`, `lint`, `assembleDebug`, and `compileDebugAndroidTestKotlin`; inspect fresh device XML for executed counts and skips. The dispatcher owns full regression and live gates. This is an inset correction to existing flows, not a new daemon interaction; no new rung-3/rung-4 scenario is introduced.

## Documentation handoff

Pending for documentation stage: Replace “harmless double-padding” in `docs/knowledge/features/navigation.md` § Configuration with once-only ownership; reconcile `docs/knowledge/features/paste-code-dialog.md`'s matching claim in § Form and rendering.

## Security review

**Verdict:** PASS

- Trust boundaries / Android surface: `MainActivity.onCreate` consumes OS-provided layout dimensions only; no intent, permission, pairing validation or destination contract changes.
- Tokens / storage / crypto / network: the production modifier reads no credentials and performs no I/O. Existing encrypted stores, parser, fingerprint confirmation and transport remain untouched. Tests use empty or synthetic test state; captures must contain no real pairing payloads or messages.
- Errors / logs: no new failure modes or payload logging. Device evidence records dimensions/inset sizes and synthetic screen content only.
- Concurrency: no new coroutine or state ownership. Consumption follows Scaffold padding through recomposition, including inset changes; the startup job and connection lifecycle are unchanged.
- Threat model: no new relay/daemon data entry point, disk data, WebView, exported component or cryptographic operation. Keyboard/screenshot evidence runs solely in the isolated test app/device; no production credentials are used.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27

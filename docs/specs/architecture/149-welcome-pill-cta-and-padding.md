# Spec — Welcome pill CTA shape + hero top padding (#149)

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

LogoCenterCursor variant of the Welcome screen. Two visual properties are corrected here against the locked node: the primary CTA at node `6:41` is a fully-rounded pill (`rounded-[28px]` on a 56dp-tall button) — not the M3-default ~20dp medium-component shape — and the outer container at node `6:32` declares `pt-[168px]` for the distance from the screen top down to the hero block. Everything else on the screen already matches per #57's code review and is out of scope.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` — the only production file modified. Both edits land in this file; current `Button` at line 105 needs a `shape` argument, current hero `Column` at line 67 needs its `padding(top = 136.dp)` changed to `168.dp`. Keep the existing layout tree, imports order, both `@Preview` composables, and the `WelcomeScreen(onPaired, onSetup, modifier)` signature exactly.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/WelcomeScreenTest.kt` — the four instrumented tests (`pairedCta_isDisplayed`, `setupCta_isDisplayed`, `tappingPairedCta_invokesOnPairedOnly`, `tappingSetupCta_invokesOnSetupOnly`) match by text only ("I already have pyrycode", "Set up pyrycode first"). The shape + padding changes don't affect those nodes' discoverability, so the tests must keep passing without edits. Do not modify this file.
- `docs/specs/architecture/57-welcome-screen-figma-polish.md` — the spec that produced the current screen, including the existing 136.dp value (numerical layout dp, not a theme token) and the rationale for placing top padding under `systemBarsPadding()`. This ticket inherits all of #57's conventions (no `Color(0x…)` literals, no `ButtonDefaults.buttonColors(...)` override, no new imports beyond the shape addition below).

## Context

#57 shipped the Figma-anchored Welcome screen with two residual deltas vs node 6:32 that code-review flagged for follow-up rather than rework:

1. The primary CTA `Button` uses the M3-default shape (`Shapes.medium`, ~20dp rounded corner). Figma node 6:41 specifies `rounded-[28px]` — i.e. a fully-rounded pill on the 56dp-tall button (since `corner = height / 2` ⇒ pill).
2. The hero `Column`'s top padding is `136.dp` (under `systemBarsPadding()`). Figma node 6:32 specifies `pt-[168px]` on the outer container.

Both deltas are one-property mechanical fixes inside `WelcomeScreen.kt`. No new types, no new drawables, no callsite changes, no test additions.

## Design

### File scope

- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt`
- **Add new file:** none
- **Add import:** `androidx.compose.foundation.shape.RoundedCornerShape`

No other files change. `MainActivity.kt` still calls `WelcomeScreen(onPaired = ..., onSetup = ...)`; the public signature is unchanged.

### Change 1 — Pill shape on the primary CTA

The `Button(onClick = onPaired, ...)` at `WelcomeScreen.kt:105-119` gets an explicit `shape = RoundedCornerShape(28.dp)` argument. The Button's `Modifier.height(56.dp)` is unchanged; the resulting silhouette is a 56×fullWidth pill because `corner = height / 2`.

Contract:

```kotlin
Button(
    onClick = onPaired,
    modifier = Modifier.fillMaxWidth().height(56.dp),
    shape = RoundedCornerShape(28.dp),
) { /* unchanged children: Icon + Spacer + Text */ }
```

The `TextButton` for the secondary CTA gets **no shape change** — the M3 text-button surface is invisible (no fill), so its corner radius is visually irrelevant. Figma node 6:43 also declares `rounded-[28px]` on the secondary, but in code that's a no-op once the fill is absent; do not add a shape there.

`ButtonDefaults.buttonColors(...)` is NOT introduced; the existing theme-token color resolution (the table in #57) still produces the correct fill (`colorScheme.primary` ⇒ `#9dcbfc`) and label (`colorScheme.onPrimary` ⇒ `#035`).

### Change 2 — Hero top padding 136.dp → 168.dp

The hero `Column` at `WelcomeScreen.kt:66-68` changes from `Modifier.padding(top = 136.dp)` to `Modifier.padding(top = 168.dp)`. Nothing else moves: `systemBarsPadding()` stays on the outer column, the `28.dp` vertical hero spacing stays, the `32.dp` horizontal screen padding stays.

**Resolution of the ticket's "systemBarsPadding() interaction" question:** use a **direct `168.dp` after `systemBarsPadding()`**, not `168.dp − statusBarHeight`. Rationale:

- It matches the AC's literal value (`pt-[168px]` ⇒ `padding(top = 168.dp)`) with no inset math.
- It is structurally identical to the existing pattern in this file (the prior `136.dp` was also placed below `systemBarsPadding()`).
- The Android Compose convention in this codebase is to interpret Figma top values as "below the safe area." Variability in actual status-bar height across devices is absorbed by `systemBarsPadding()`; the design value is the design value.
- The alternative (`168.dp − statusBarHeight`) would require `WindowInsets.statusBars` math inside the layout, which is over-engineering for a one-line padding change and would diverge from #57's idiom.

If a future ticket decides Figma's `pt-[168px]` should instead represent the absolute distance from the device top (inset-aware), that's a screen-wide convention change worth its own ticket — out of scope here.

### Imports

One added import:

```kotlin
import androidx.compose.foundation.shape.RoundedCornerShape
```

No imports removed. Alphabetical placement: between `androidx.compose.foundation.layout.width` and `androidx.compose.material3.Button`.

### Out of scope (do not implement)

- Any change to the secondary `TextButton`'s shape, color, or modifier chain.
- Any change to the footer alignment, the radial-glow brush, the drawables, or the typography slots.
- `MainActivity.kt`, navigation, or any other consumer surface.
- Introducing a shared `PillButton` composable or a project-wide `Shapes.pill` token — premature abstraction for a single button. If a second pill button is added later, the abstraction can land then.
- Updating `WelcomeScreenTest.kt` — text matchers are unaffected.

## State + concurrency model

None. The composable remains pure and stateless; no `remember`, no `LaunchedEffect`, no `ViewModel`. `RoundedCornerShape(28.dp)` is a cheap value constructor; no `remember` wrapping needed.

## Error handling

N/A — no I/O, no fallible computation.

## Testing strategy

- **Existing unit / instrumented coverage stays passing.** The four `WelcomeScreenTest` cases match by visible text; neither the shape nor the padding changes the text-node tree, so all four must keep passing without edits. The developer should run `./gradlew connectedAndroidTest` (device required) to confirm.
- **Lint + build.** `./gradlew lint` and `./gradlew assembleDebug` must remain clean. No suppressions expected; the new import resolves through `androidx-compose-foundation` which is already on the classpath.
- **Preview verification.** The light + dark `@Preview` composables (`WelcomeScreenLightPreview`, `WelcomeScreenDarkPreview` at 412×892) must render. The dark preview is the canonical comparison surface against the Figma screenshot.
- **No new test cases.** A shape argument and a padding-dp value have no meaningful unit-test surface; pixel-fidelity is checked at code-review via the AC4 Figma screenshot comparison.

## Open questions

- **None.** The two AC items have a clean, mechanical mapping; the systemBarsPadding() interaction is resolved above. If the developer's dark preview visually undersells the pill silhouette or the hero-top distance vs the Figma screenshot, that's a pixel-grade tune, not a design ambiguity — adjust the dp and move on.

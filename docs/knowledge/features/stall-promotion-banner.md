# Stall promotion banner — `StallPromotionBanner`

The **UI half of the stall reaction** ([#396](../codebase/396.md), split from #373): a stateless
composable that, while the active conversation is stalled, raises the always-available
"show the literal screen" snapshot action to a **prominent CTA at the top of the thread**, so the user
is guided to the parser-independent live view instead of staring at silence. The screen snapshot is the
**floor** of pyrycode ADR-025's safe-degradation strategy; this banner is what surfaces it when
structured parsing degrades and the remote claude stops making forward progress.

The signal it renders is the **data half** — [`ThreadViewModel.isStalled`](stall-state.md)
([#395](../codebase/395.md)). This component adds **no data access** and **no new data path**: it
receives the already-derived flag as a hoisted boolean (exactly as
[`ConnectionBanner`](connection-banner.md) receives `ConnectionState`) and, on tap, triggers the
**same** `onShowLiteralScreen` navigation the [thread overflow menu](thread-overflow-menu.md)'s "Show the
literal screen" item (#382) already calls — see [What it does](#what-it-does).

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `StallPromotionBanner.kt`.

## Shape

```kotlin
@Composable
fun StallPromotionBanner(
    isStalled: Boolean,
    onShowLiteralScreen: () -> Unit,
    modifier: Modifier = Modifier,
)
```

The two load-bearing params (`isStalled`, `onShowLiteralScreen`) carry no defaults. The composable is a
**pure function of the boolean** — no `ViewModel` reference, no flow collection, no `remember`, no
`LaunchedEffect`, no `ThreadUiState` field. Statelessness is an AC, not a style choice.

## What it does

- **`if (!isStalled) return`** — emits nothing when not stalled (zero composition, zero height),
  mirroring [`ConnectionBanner`](connection-banner.md) / [`ThinkingIndicator`](thinking-indicator.md).
  The banner clears the instant the flag flips back to `false` (AC #3) because it holds no local state.
- When `true`, renders a full-width clickable `Surface` whose `onClick = onShowLiteralScreen`, containing
  a `Column` of two `Text`s:
  - the explanatory line `thread_stall_promotion_message` ("Claude seems to have stalled. See what it's
    doing right now:"), `MaterialTheme.typography.bodyMedium`;
  - the action label, which **reuses** `thread_overflow_show_literal_screen` ("Show the literal screen"),
    `MaterialTheme.typography.labelLarge`.
- **The tap reuses the already-shipped snapshot action (AC #4).** `onShowLiteralScreen` is the identical
  callback the overflow item triggers — it navigates to the `literal_screen/{conversationId}`
  destination (#382) that renders [`LiteralScreenSurface`](literal-screen-surface.md). No new
  `ThreadEvent`, no new repository surface, no new data path.
- **Accessibility** — the `Surface` carries `Modifier.semantics(mergeDescendants = true) {
  contentDescription = … }` sourced from `cd_thread_stall_promotion` ("Claude seems to have stalled.
  Show the literal screen."). Merging descendants makes TalkBack announce the banner once as a single
  node *and* makes it a single click target; the two `Text`s are subsumed under that accessible name.

### Styling (design-owed)

`color = MaterialTheme.colorScheme.tertiaryContainer` / `contentColor = onTertiaryContainer` —
prominent and attention-drawing, but deliberately **not** the error-red (`errorContainer`) of
[`ConnectionBanner`](connection-banner.md)'s `Offline` state: a stall is a degrade **fallback**, not an
error. The Figma `16-8` frame has no stall treatment drawn yet (same design-owed status as the sibling
stream-UI tickets #386 / #388 / [#407](../codebase/407.md)); until it lands the visual follows the app's
Material 3 banner idiom, exactly as `ThinkingIndicator` shipped its M3 spinner default. When the frame
arrives, re-tune container/typography here — no contract change.

### Spacing constants

Three file-private `val`s at the top of `StallPromotionBanner.kt` — no raw `.dp` literal in the body,
the same named-constant posture as [`ConnectionBanner`](connection-banner.md) /
[`ThinkingIndicator`](thinking-indicator.md):

```kotlin
private val BannerVerticalPadding = 12.dp
private val BannerHorizontalPadding = 16.dp
private val BannerRowGap = 4.dp
```

## Placement in the thread

[`ThreadScreen`](thread-screen.md) renders the banner directly **below**
[`ConnectionBanner`](connection-banner.md) in the content `Column` (`ThreadScreen.kt:123`), above the
workspace chip / empty state / message list — i.e. **outside** the scrolling `LazyColumn`:

```kotlin
Column {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    StallPromotionBanner(isStalled = isStalled, onShowLiteralScreen = onShowLiteralScreen)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(…) else LazyColumn(reverseLayout = true, …) { … }
    // ThinkingIndicator at the foot …
}
```

Why this seam:

- A persistent top banner is the **most prominent** surface and the direct analogue of
  `ConnectionBanner`: connection-degraded → top banner; parse-degraded/stalled → top banner. Being
  outside the `LazyColumn`, it stays pinned while the stall holds rather than scrolling away.
- This is the **counterpoint to [`ThinkingIndicator`](thinking-indicator.md)** (#407), which sits at the
  **foot** of the same content `Column`. A stall promotion is a "do this now" recommendation (top); a
  thinking indicator is an at-work status (foot). The two are in practice mutually exclusive — a
  `turn_state` event clears the stall, and `isThinking` *is* a `turn_state` phase — so no either/or
  coordination logic is specified; an explicit one is a design follow-up.

## Wiring

The flag is threaded as a **defaulted hoisted boolean**, sibling to `connectionState` / `isThinking` —
**not** a `ThreadUiState` field:

- **`ThreadViewModel`** exposes it as a sibling `StateFlow` beside `isThinking`, sourced from the
  **already-injected** repository — **no constructor / DI / interface change** (`observeStall` is on the
  `ConversationRepository` interface from #395):

  ```kotlin
  val isStalled: StateFlow<Boolean> =
      repository.observeStall(conversationId)
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)
  ```

  No extra operator: #395 already applies `distinctUntilChanged` in the remote impl and the facade
  defaults to `false`, so `false` covers both "no live connection" and "not stalled".
- **`ThreadScreen`** gains `isStalled: Boolean = false` in its trailing-defaults block, after `modifier`
  (`ThreadScreen.kt:75`). Defaulting it keeps the in-file previews + the androidTest call sites + all
  other callers compiling untouched; only the live caller sets it.
- **`MainActivity`** collects it in the `CONVERSATION_THREAD` destination exactly parallel to
  `isThinking` and passes it in:

  ```kotlin
  val isStalled by vm.isStalled.collectAsStateWithLifecycle()   // MainActivity.kt:350
  …
  ThreadScreen(…, isThinking = isThinking, isStalled = isStalled, …)   // :365
  ```

See [Stall state](stall-state.md) for the upstream data path (the #395 inbound `stall` decode →
`observeStall` projection) that produces this flag.

## Recomposition / stability

- `isStalled: Boolean` is a stable param ⇒ `StallPromotionBanner` is **skippable** and recomposes only
  when the flag flips. `onShowLiteralScreen` is the same remembered nav lambda the overflow item uses.
- No internal mutable state, no `remember`, no side effect, no coroutine — pure projection of the boolean
  to a rendered (or absent) banner.
- `ThreadScreen` gains one stable `Boolean` param; the banner is a single wrap-height surface above the
  weighted list region — no impact on the `LazyColumn`'s item recomposition.

## Preview

Two `@Preview`s, one per theme, both `showBackground = true`, `widthDp = 412` — the dark variant adds
`uiMode = Configuration.UI_MODE_NIGHT_YES`. Each wraps `PyrycodeMobileTheme(darkTheme = …) { Surface {
StallPromotionBanner(isStalled = true, onShowLiteralScreen = {}) } }`. Both show the **active**
(`isStalled = true`) state so the visual is reviewable — the `false` case renders nothing and needs no
preview.

## Configuration

- **No new dependencies.** Existing Compose Material 3 imports only. No `gradle/libs.versions.toml` edits.
- **Two new string resources** in `res/values/strings.xml`: `thread_stall_promotion_message` (visible
  explanatory line) and `cd_thread_stall_promotion` (content description). The CTA **label** reuses the
  existing `thread_overflow_show_literal_screen` — the same string the overflow item shows.

## Edge cases / limitations

- **Visual is design-owed.** The stall treatment is **not yet drawn** in
  [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8). Until it lands the visual
  follows the app's M3 banner idiom (tertiary-container surface + explanatory line + CTA label); re-tune
  container/typography/copy/placement when the frame arrives — no contract change.
- **No "finished" vs "recovered mid-turn" distinction.** This UI treats the stall as a single boolean:
  promote while `true`, un-promote while `false`. *Any* forward-progress event clears the stall (per
  [#395](stall-state.md)), which un-promotes the banner — the correct, minimal UX for a degrade
  recommendation that should exist only while the stall condition holds. (Resolves #395's *Open
  Question 1*.)
- **No animation.** The show/hide is an instant early-return swap, matching `ConnectionBanner`. A
  fade-in (`AnimatedVisibility`) is a design-owed nicety deferred with the Figma frame.
- **a11y `role = Role.Button` (open, NIT).** Code review flagged that the clickable `Surface` carries a
  `contentDescription` but no `role = Role.Button`, so TalkBack reads it as text + "double-tap to
  activate" rather than identifying it as a button. **Consistent with the `ConnectionBanner` precedent**
  (also a clickable `Surface` with no role), so non-blocking; deferred to whenever the 16-8 treatment is
  drawn.
- **Stale-`true`-on-resume (known, accepted).** Like `isThinking` / `connectionState`, the upstream
  `isStalled` (`stateIn(WhileSubscribed(5_000))`) can momentarily read a stale value on re-foreground
  after a long background. This is a transient "right-now" posture deliberately not handled in the
  stateless composable; see [Thinking indicator § Limitations](thinking-indicator.md).

## Related

- Ticket notes: [`../codebase/396.md`](../codebase/396.md) (this component) ·
  [`../codebase/395.md`](../codebase/395.md) (the data/repository half it consumes).
- Spec: `docs/specs/architecture/396-stall-promotion-snapshot-cta.md`.
- Upstream signal: [Stall state](stall-state.md) — `ThreadViewModel.isStalled` / `observeStall`, the
  inbound `stall` decode this banner renders.
- The action it promotes: [LiteralScreenSurface](literal-screen-surface.md) (#381 render), the thread
  entry point + `literal_screen/{conversationId}` navigation ([#382](../codebase/382.md)), the data path
  ([#375](../codebase/375.md)). The banner is a **second** entry point into the same destination — it
  introduces no new path.
- Host: [Thread screen](thread-screen.md) — threads `isStalled` as another flat sibling parameter and
  mounts the banner below `ConnectionBanner`.
- Idioms mirrored: [ConnectionBanner](connection-banner.md) (clickable-`Surface` early-return show/hide,
  file-private spacing `val`s), [Thinking indicator](thinking-indicator.md) (sibling-`StateFlow`,
  defaulted-hoisted-boolean, merged-`semantics`, design-owed M3 default, light/dark previews — the
  foot-of-list counterpoint to this top banner).
- Parent: split from [#373](https://github.com/pyrycode/pyrycode-mobile/issues/373); grandparent
  [#370](https://github.com/pyrycode/pyrycode-mobile/issues/370).
- Server SSOT: pyrycode#624 (stall transport), #638 (`stall` wire vocabulary, onset-only), #639 (bridge
  + interactive-only fan-out), tui-driver #141 v1.3.0, ADR-025 § Safe degradation, EPIC pyrycode#596.
</content>

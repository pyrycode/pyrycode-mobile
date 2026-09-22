# 643 — Apply the mobile chat header and composer layout

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` — the `Scaffold` arrangement this ticket rearranges: the `topBar` / `bottomBar` slots, the content `Column`, and the foot-of-list block holding the one-slot indicator `when` and `InterruptAffordance`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` → `ThreadTopAppBar` — the stock `TopAppBar` the design replaces; its three slots and their descriptions are the contract to carry over.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → both `ThreadInputBar` overloads — the composer's current shape (divider, surface, `imePadding`, pill, mic stub, send button).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt` → `ThreadStatusRow` — mount point moves; the composable itself is untouched. Its own `padding(horizontal = 16.dp, vertical = 4.dp)` already reproduces the design footer's `px-16 / pt-4`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/InterruptAffordance.kt` → `InterruptAffordance` — the standalone control this ticket retires from the screen; file left alone.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt`, `.../ApiRetryIndicator.kt`, `.../CompactingIndicator.kt` → each carries its own `IndicatorHorizontalPadding = 16.dp`. That number is why the status area needs a 4dp gutter adjust rather than the full 20dp (see Design).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListTopBar`, `ChannelListBarEntry` and the file-private bar constants — the nearest shipped bar in this design language, and the source of the "design offset less the touch slack" derivation reused here.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `MainActivity.onCreate`, `PyryNavHost` — settles the inset dispute (see Context).
- `app/src/main/res/values/strings.xml` → `cd_back`, `cd_more_actions`, `cd_send_message`, `cd_thread_interrupt`, `cd_thread_thinking`, `cd_voice_input`, `voice_input_toast` — the five descriptions the suites pin, plus the two mic strings this ticket retires.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadRenderTest.kt` → `interrupt_shownWhileBusy_invokesOnTap_goneAfterTurnEnd` — the existing proof of the interrupt path; it asserts the stop description is absent before a turn, present and *clickable* across `thinking` and `responding`, and gone after `turn_end`. The send/stop precedence below is chosen so this test keeps passing against the relocated control.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` → `start`, `awaitReady` — mounts the real `ThreadScreen`; `awaitReady` matches the seed name as *semantics* text, which survives visual truncation.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenOverflowTest.kt` → the overflow suite drives `cd_more_actions` and the menu items; the `Box`-anchored menu must survive the bar rewrite.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`, `.../DeterministicInteractiveStreamE2ETest.kt` → both use `CD_SEND_MESSAGE` as the *thread-arrival marker* before typing anything, then type and click it. The send variant must therefore exist (even disabled) on an idle empty composer.
- `docs/knowledge/features/thread-input-bar.md` § "Shape", § "IME handling", § "`ThreadScreen` mount point" — the two-overload convention, and why `imePadding()` must stay on the composer rather than the screen root.
- `docs/knowledge/features/thread-screen-how-it-works-overlays-and-app-bar.md` § "Interrupt-affordance placement (post-#459)", § "`ThreadTopAppBar` — Figma `16:8` chrome" — records the stacking as interim and design-owed, and carries the stale inset claim this ticket disproves.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

A dark full-bleed chat frame on a 412dp reference width with a 20dp content gutter (372dp of content). The `Top bar` (`533:1948`) is a hand-drawn row — a 24dp back glyph, a `M3/title/large` conversation title in `Schemes/on-primary-container`, a 24dp vertical-ellipsis overflow — closed by a 1dp full-width rule, not Material's stock app bar. The `Message area` (`533:1956`) fills the space between that rule and the composer. The `Input area` (`533:1957`) is a gap-8 column of a `Status area` (a small spinner plus a `M3/body/small` `Schemes/primary` status line on the left, a contextual action chip on the right), an `Input large` field — a 6dp-cornered translucent container holding the placeholder and, overlapping its right edge, a 48dp `Message input button` drawn as a 28dp filled circle-chevron — and an `Input footer` row of `body-small` `Schemes/primary` entries inset a further 16dp.

## Context

`ThreadTopAppBar` still draws Material's stock `TopAppBar`, and the foot of the thread's content `Column` still stacks `ThinkingIndicator` above a standalone `InterruptAffordance`. Both are recorded as interim and explicitly owed to this Figma pass. This ticket applies the supplied frame to the header, the vertical placement of the message area, and the composer's three-part structure, and retires the standalone stop control onto the composer's send button — the placement desktop's #678 already settled.

**The inset dispute is resolved by current code, and `ChannelListTopBar`'s KDoc is the correct note.** `MainActivity`'s root `Scaffold` declares no `topBar` and no `bottomBar`, so the `innerPadding` it hands to `PyryNavHost` is its whole `contentWindowInsets` — the system bars. Every destination is therefore already padded past the status and navigation bars, and the stock `TopAppBar`'s `TopAppBarDefaults.windowInsets` was applying a second status-bar inset on top of that. The hand-rolled bar declares no window insets of its own. The claim in `thread-screen-how-it-works-overlays-and-app-bar.md` § "`ThreadTopAppBar` — Figma `16:8` chrome" that the outer `Scaffold` "doesn't consume the top inset" is stale; the documentation stage owns the correction (see Documentation handoff).

No ADR is warranted — this is one screen's layout, not a new architectural rule.

## Design

Three production files change. No new public type, no ViewModel change, no repository or wire change.

### `ThreadTopAppBar` — hand-rolled bar plus rule

Signature and callbacks are unchanged; only the body is replaced. `TopAppBar` gives way to a `Column` of a content `Row` and a `HorizontalDivider`, mirroring `ChannelListTopBar`'s structure in the same design language.

- Back and overflow keep 48dp `IconButton` touch targets around the design's 24dp glyphs, and the bar's own paddings are the design's offsets **less the touch slack** `(48dp - 24dp) / 2` — the identical derivation `ChannelListTopBar` uses, so both bars land their glyphs on the same gutter. The constants are file-private here; the list's are file-private there and deliberately not shared.
- The title takes `weight(1f)` between the two controls with `maxLines = 1` and `TextOverflow.Ellipsis`, so an over-long display name truncates inside its own slot and can never overlap or cover either control (AC #1). It keeps its `clickable` + `Role.Button` semantics so the channel-info sheet still opens, and takes `MaterialTheme.typography.titleLarge` in `colorScheme.onPrimaryContainer` per the design.
- The overflow keeps its `Box { IconButton; ThreadOverflowMenu }` wrap — that is what anchors the menu beneath its own glyph (#252); losing it would reopen that bug.
- The rule is `colorScheme.outlineVariant` at 0.60 alpha. **Recorded divergence:** the design names `Schemes/inverse-primary` at 60%, which is the light-scheme primary tone and reads as a tinted rule only against the dark reference frame. `outlineVariant` is M3's divider role and is exactly what the shipped `ChannelListTopBar` maps this same rule to, so the two bars stay identical under both schemes.

### `ThreadScreen` — `Scaffold` arrangement

The content `Column` keeps `ConnectionBanner`, `StallPromotionBanner`, the optional `WorkspaceChip`, the weighted `EmptyThreadState` / `LazyColumn` branch and `QueuedBacklog`, all in their current order and with their current modifiers — so the reverse-layout auto-scroll, the above-delimiter dimming, the banners, the chip, the backlog and the permission modal keep their positions and behaviour (AC #2, AC #4). It **loses** its two trailing children: the one-slot indicator `when` and `InterruptAffordance`.

The `bottomBar` slot becomes the design's `Input area` — one `Column` owning the composer's surface, its IME lift and the design's vertical rhythm:

```kotlin
// bottomBar: surface + imePadding() + top 12dp + bottom 16dp, Arrangement.spacedBy(8.dp)
ThreadStatusArea(apiRetry, isCompacting, isThinking)   // private; the moved one-slot `when`
ThreadInputBar(onSend = onSendMessage, isBusy = isBusy, onInterrupt = onInterrupt, …)
ThreadStatusRow(model = …, effort = …, onExpandClick = { sheetVisible = true }, …)
```

- **The 12dp gap above the status area** is the composer column's top padding, so it holds whether or not an indicator is showing — the design's `Message area` → `Input area` gap (AC #2).
- **The 20dp content gutter** is applied per child rather than to the column, because the three indicators bring their own 16dp horizontal padding. The status area's children are therefore inset by `ContentGutter - IndicatorOwnPadding` = 4dp so their content lands on the same 20dp gutter as the field and the footer, with the indicator files untouched. `ThreadStatusRow`'s own 16dp reproduces the design footer's further `px-16` once the 20dp gutter is applied to it.
- **The status area is the relocated one-slot mutual exclusion**, moved not rewritten: the same `when` arms in the same precedence (api-retry wins, then compaction, then thinking), each still driven by its own current flag. It lives in a private `ThreadStatusArea` composable in `ThreadScreen.kt` purely so the `bottomBar` lambda stays readable. The design's trailing contextual-action slot stays empty until #675 — no inert placeholder is emitted.
- **No standalone stop control remains** anywhere on the screen, so the waiting signal and the stop affordance can no longer stack (AC #3). `InterruptAffordance` keeps its file and its own tests; it simply has no call site on this screen.
- Because the composer sits in `bottomBar` and paints `colorScheme.surface`, the message area is the only scrolling region and scrolls under a composer that stays put (AC #2). `imePadding()` moves from `ThreadInputBar`'s own column to this one, so the whole input area lifts as a unit above the keyboard while the header and list stay stationary — the rule `thread-input-bar.md` § "IME handling" pins, applied to the enlarged composer.

### `ThreadInputBar` — `Input large`, with a send/stop button

Both overloads keep their two-overload shape and gain `isBusy: Boolean = false` and `onInterrupt: () -> Unit = {}` (defaulted, so the four existing previews stay one-liners). The composable loses its top `HorizontalDivider`, its own `background(surface)` and its own `imePadding()` — all three now belong to the composer column above it; the design draws no rule above the input area.

The pill becomes the design's `Input large`: a `Surface` at `RoundedCornerShape(6.dp)` in `surfaceContainerHigh` with `heightIn(min = 52.dp)`, holding the `BasicTextField` and, at its trailing edge, the 48dp message-input button. The `BasicTextField` configuration — value, `bodyLarge` text style, `cursorBrush`, `maxLines = 5`, `ImeAction.Send` and its `KeyboardActions` — is carried over verbatim.

**The mic stub is removed.** The design's `Input large` holds exactly one button slot, and the ticket forbids exposing inert controls before their dependent features land — the mic only raises a "Voice input — Phase 6" `Toast`. `cd_voice_input` and `voice_input_toast` go with it; no test references either. This also drops the composable's `LocalContext` dependency.

**The message-input button carries one of two actions**, and the precedence is explicit:

| `text` | `isBusy` | description | action | enabled |
|---|---|---|---|---|
| non-blank | either | `cd_send_message` | existing send path | yes |
| blank | true | `cd_thread_interrupt` | existing interrupt path | yes |
| blank | false | `cd_send_message` | — | no |

Text present wins over the in-flight turn deliberately: sending while the agent is busy is a shipped path (the daemon queues it and `QueuedBacklog` renders it, #461/#467), so a stop variant that pre-empted a typed message would silently remove the only tap that reaches it. The stop variant therefore owns the button exactly when the composer is empty, which is the state anyone reaching for stop is in. This also keeps both existing suites honest: the e2e tests find `cd_send_message` on an idle empty composer as their thread-arrival marker, and `ScriptedThreadRenderTest` finds `cd_thread_interrupt` clickable across `thinking` and `responding` with an untouched field.

The glyph follows the design's 28dp filled circle-chevron: `Icons.Filled.ArrowCircleUp` tinted `colorScheme.primary` inside a container-less 48dp `IconButton`, and `Icons.Filled.StopCircle` for the stop variant — the same silhouette, so the button reads as one control in two states. Both are in the already-declared `material-icons-extended`.

## State + concurrency model

No new state and no new coroutines. `ThreadInputBar`'s stateful overload keeps its `rememberSaveable` draft; `sheetVisible` / `overflowExpanded` keep their `rememberSaveable` in `ThreadScreen`. `isBusy`, `onInterrupt`, `isThinking`, `apiRetry` and `isCompacting` are already `ThreadScreen` parameters collected at `MainActivity`; they are simply forwarded one slot lower. No `ThreadViewModel` change, no DI change, no new `Flow`.

Recomposition: the composer column's children are pure functions of already-stable parameters (`Boolean`s, an `ApiRetryStatus`, `String`s and caller-owned lambdas), so moving them from the content column to `bottomBar` changes which subtree recomposes on a flag flip but adds no new unstable capture.

## Error handling

None added. The send path keeps its existing guard (`ThreadViewModel.sendMessage`'s blank early-return behind `launchGuardedRepoCall`) and the interrupt path keeps `vm::onInterrupt` untouched. No new I/O, no new failure mode, no new user-visible error surface. No daemon-authored text reaches a new sink: the title is the existing `state.displayName` rendered through the same plain `Text`, now length-bounded by `maxLines = 1` + ellipsis.

## Testing strategy

Unit tests are not the right instrument — every change is layout and semantics. One new instrumented file, `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameTest.kt`, run focused on the managed API 33 device:

- **`longTitle_truncatesAndLeavesBothControlsReachable`** — mount `ThreadScreen` with a display name far wider than the slot; assert the back and overflow descriptions are both displayed, that neither control's bounds intersect the title's, and that tapping the title fires `onTitleClick`. Pins AC #1's truncation and non-overlap.
- **`header_backAndOverflowKeepTheirActions`** — tapping back fires `onBack`; tapping the overflow opens the menu beneath its glyph. Pins AC #1's two descriptions and the `Box` anchor against the bar rewrite.
- **`inputButton_sendsWhenTextPresent`** — stateless `ThreadInputBar` with text and `isBusy = true`; assert the node carries `cd_send_message` and clicking it fires `onSend`, not `onInterrupt`. Pins the precedence row that keeps queue-while-busy reachable.
- **`inputButton_stopsWhileBusyWithEmptyField`** — stateless `ThreadInputBar` with blank text and `isBusy = true`; assert `cd_thread_interrupt` is present, enabled, and fires `onInterrupt`; with `isBusy = false` it is absent.
- **`busyThread_hasExactlyOneStopControl`** — mount `ThreadScreen` with `isBusy` and `isThinking` both set; assert exactly one node carries `cd_thread_interrupt` and that the thinking description is present alongside it. Pins AC #3's "no standalone foot-of-list stop control remains".

Existing coverage carries the rest and must stay green unchanged: `ScriptedThreadRenderTest` (the interrupt lifecycle through the real fold), `ThreadScreenOverflowTest`, `ThreadScreenChannelInfoTest`, `ThreadScreenModalTest`, `ThinkingIndicatorTest`, `ThreadStatusRowTest`, `QueuedBacklogTest`, `StallPromotionBannerTest`, `ScriptedConnectionBannerTest`, `ScriptedApiRetryTest`, `ScriptedCompactingTest`.

No new rung-3 scenario: this ticket ships no new operator-facing flow — it relocates controls whose live behaviour the existing `ping` / `spinner` scenarios already exercise, and the ticket assigns live verification of the reframed screen to #679.

`@Preview` coverage: the existing `ThreadScreen` and `ThreadInputBar` previews are the light/dark render check against the Figma screenshot; `ThreadInputBar`'s four gain no new variants beyond a busy-state preview for the stop glyph.

## Documentation handoff

Pending for the documentation stage — no doc file is edited by this ticket:

- `docs/knowledge/features/thread-screen-how-it-works-overlays-and-app-bar.md` — § "`ThreadTopAppBar` — Figma `16:8` chrome" (the hand-rolled bar replacing the stock `TopAppBar`, **and** the inset-ownership correction: the outer `MainActivity` `Scaffold` *does* pad past the system bars, so `ChannelListTopBar`'s KDoc is the accurate note), § "Thinking-indicator placement (post-#407)" and § "Interrupt-affordance placement (post-#459)" (both indicators now mount in the composer's status area; the standalone control is gone).
- `docs/knowledge/features/thread-input-bar.md` — § "Shape" (the two new parameters and the retired mic stub), § "IME handling" (`imePadding()` moves to the composer column), § "`ThreadScreen` mount point" (the three-part `Input area`).
- `docs/knowledge/features/interrupt-affordance.md` — § "Placement & wiring" (the send button's stop variant replaces the standalone control on the thread screen; the composable and its file remain).

## Open questions

- **Rule colour token.** Resolved in Design: `outlineVariant` at 0.60, matching the shipped `ChannelListTopBar` rather than the design's literal `Schemes/inverse-primary`. Revisit only if a design pass rules the other way.
- **Send/stop precedence when both apply.** Resolved in Design: text wins. Confirm during implementation that `ScriptedThreadRenderTest` stays green against the relocated control.
- **Field container colour.** The design's `Text area` is a raw `rgba(0,51,85,0.41)` rather than a `Schemes/*` variable. Kept at `surfaceContainerHigh`, the token the shipped composer already uses, so the field stays legible under the light scheme. Record the divergence if the render disagrees with the screenshot.

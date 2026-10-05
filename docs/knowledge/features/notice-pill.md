# Notice pill — `NoticePill`

The shared pill shape for [`ThreadTopOverlay`](thread-top-overlay.md)'s usage, MCP,
pairing, Offline Retry and session-error notices. [#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043) added the task-count caller,
the thread status band's task-count pill, hosted on `ThreadScreen` itself rather than the overlay. Figma
`347:6617`'s `Pill` component set, variants **Default** and **Error**.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`).

## Shape

```kotlin
@Composable
internal fun NoticePill(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String = text,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    shadowElevation: Dp = PillShadow,
    leadingIcon: ImageVector? = null,
    maxLines: Int = Int.MAX_VALUE,
)
```

[#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043) added `shadowElevation`, defaulted to the
existing `PillShadow` constant so [`ThreadTopOverlay`](thread-top-overlay.md)'s overlay callers are
unaffected — see [§ Caller contracts](#caller-contracts) below for the
task-count caller that overrides it to `0.dp`.

A `Surface` (6dp `RoundedCornerShape`, `shadowElevation` for the overlay's drop shadow) holding a `Row`
(8dp horizontal / 4dp vertical padding, 8dp gap, centre-aligned): a `bodySmall`, right-aligned `Text` in
`Modifier.weight(1f, fill = false)` — so the pill hugs short text and wraps long text while a trailing X
stays visible — and, when `onDismiss != null`, an 8dp exported close image as its own clickable node
(`Role.Button`, `contentDescription = R.string.thread_notice_dismiss`, "Dismiss notice"). Compose's minimum
touch-target expansion gives the X a 48dp tap area without growing the pill's drawn size — a deliberate
divergence from the usual 48dp `IconButton` wrapper, kept to match Figma's compact pill height.

The themed `bodySmall` (12/16sp) style uses `LineHeightStyle.Alignment.Center`
and `LineHeightStyle.Trim.None`, retaining the full top and bottom line-height space.
At font scale 1, both variants have a 16dp single-line text box centred within a
24dp visible background, with 4dp padding above and below. There is no fixed pill
height: wrapping and font scaling grow the line box naturally, subject to the
caller's `maxLines` and ellipsis. See [shared typography](shared-typography.md).

**Two variants, colours from `MaterialTheme.colorScheme`:** Default is `primaryContainer` /
`onPrimaryContainer`; Error is `errorContainer` / `error`, as Figma paints it. `isError` selects between
them; there is no third state. Semantics: `Modifier.semantics(mergeDescendants = true) { contentDescription
= contentDescription }` on the outer `Surface`, defaulting to the visible `text` — the merged node reads as
one TalkBack stop, the same "wording has one source" idiom every status-row indicator already uses (see
[Usage-limit indicator](usage-limit-indicator.md), [Resetting indicator](resetting-indicator.md)). When
`onClick != null` the whole pill is a clickable `Surface(onClick = onClick, ...)`; otherwise a plain
`Surface`.

## Caller contracts

- **Usage pill** ([`ThreadTopOverlay`](thread-top-overlay.md#the-usage-pill)): `onDismiss` is non-`null`
  only when [`usageLimitIsWarning`](usage-limit-indicator.md#shape) is true for the reading being shown —
  every other reading, including an unrecognised `status`, gets `onDismiss = null` and cannot be hidden.
  `onClick` is always `null` here; tapping the pill's body does nothing. Default `shadowElevation`.
- **Pairing pill** ([`ThreadTopOverlay`](thread-top-overlay.md#the-pairing-or-offline-pill)): `onClick` starts the same
  re-pair flow the pre-#1002 `RePairButton` started (`onRePair`, bound at `MainActivity` to
  `navController.navigate(Routes.pairCode(target.serverId))`); `onDismiss` is always `null` — a rejected
  pairing is never hideable, matching the ticket's "never dismissible" requirement for anything that is not
  the one named warning status. Default `shadowElevation`.
- **Task-count pill** ([Thread screen § Thinking-indicator placement](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643),
  [#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043)): drawn inside `ThreadStatusArea` in the
  `bottomBar`, not by `ThreadTopOverlay`. `onClick` opens the
  same `BackgroundTaskPanel` the count-free top menu opens; `onDismiss` is always `null` — a
  running-task count is not something the user can wave away. `shadowElevation = 0.dp`: Figma `568:3162`
  sits in the page flow, not over the messages, so it carries none of the overlay's drop shadow.
- **Session-error pill** ([Thread top overlay](thread-top-overlay.md#the-session-error-pill-1678)):
  `isError = true`, default overlay shadow and wrapping, with no `onClick`, `onDismiss`
  or `leadingIcon`. Its visible text and content description are the same fixed
  Claude/Codex resource; daemon bytes never become copy. Repository state owns its
  lifetime; the component starts no timeout.
- **Turn-outcome pill** ([Turn-outcome indicator](turn-outcome-indicator.md)): inert error variant in the
  input status area, with a leading outcome icon, no shadow and a two-line ellipsized label. The full
  bounded label remains its content description. It has no click or dismiss callback.

The component itself does not know which caller it serves. Its error flag, callbacks,
shadow, optional leading icon and line limit let the outcome reuse the same shape.

## Testing

[`NoticePillTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/NoticePillTest.kt)
uses native graphics to measure both text-only variants: 16dp text, 24dp background
and centred 4dp insets at density/font scale 1. Its second test checks 1.5× font
scaling, wrapping to a two-line cap (48dp text / 56dp background), and click/dismiss
routing. The [retained component XML](../../../app/src/androidTest/assets/pill-1757/jvm/TEST-de.pyryco.mobile.ui.conversations.components.NoticePillTest.xml)
records 2 executed/passed, 0 failed and 0 skipped on 2026-10-05; the
[focused caller reports](../../../app/src/androidTest/assets/pill-1757/jvm/)
record 31 executed/passed in total, 0 failed and 0 skipped, including
`ThreadTopOverlayTest`, `TaskCountPillTest`, `ThreadActivityIndicatorVisualTest`
and `ScriptedTurnOutcomeTest`.

A 16dp leading icon can hold the row at the intended height even when Compose
trims the text box. Measure text-only variants and scaled wrapping independently;
a caller's correct overall height alone cannot catch this defect. Also distinguish
the painted background from expanded touch targets, especially for a clickable
Surface or the dismiss action.

[`NoticePillCaptureTest.bothVariantsAt412By892`](../../../app/src/androidTest/java/de/pyryco/mobile/design/NoticePillCaptureTest.kt)
hosts both production variants in MainActivity through the design harness. The
[retained nonblank real-bar PNG](../../../app/src/androidTest/assets/pill-1757/both-variants.png)
and [configuration sidecar](../../../app/src/androidTest/assets/pill-1757/both-variants.txt)
record a 412×892 viewport, density/font scale 1, hardware acceleration,
`syntheticBars=false` and 24px top/bottom system bars. The
[measurements](../../../app/src/androidTest/assets/pill-1757/measurements.txt)
exclude shadows and touch targets: Default `(16,80)-(183,104)` and Error
`(16,128)-(167,152)` each have a 24px painted height, matching the retained
[Figma Default](../../../app/src/androidTest/assets/pill-1757/figma-default.png) and
[Error](../../../app/src/androidTest/assets/pill-1757/figma-error.png) exports.
The [capture XML](../../../app/src/androidTest/assets/pill-1757/capture-results.xml)
records that method executed/passed in the focused API 35 real-bar run
(`requireRealSystemBars=true`): 1 executed, 0 failed, 0 skipped. The verifier
compared retained exports; fresh remote Figma revisions were unavailable.
See [Compose evidence](development-verification-compose-evidence.md) for why a
passing synthetic-bar capture does not establish fresh pixel evidence.

The light/dark previews (`widthDp = 412`) stack a long dismissible usage-style
label above a short clickable error-style pairing label. Task-count placement
coverage remains described in [Thread screen § Thinking-indicator placement](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643).

## Security

No daemon-authored text reaches this file directly — its call sites pass already-sanitised text
(`usageLimitLabel(reading)`, the local `R.string.thread_re_pair` resource, or the client-owned plural
`R.plurals.thread_task_count` formatted against a device-side `Int` count, or the turn-outcome arm's
client-owned recovery copy, `turnRecoveryNotice`, which compares daemon tokens but never renders them,
[#1357](turn-outcome-indicator.md)). `NoticePill` renders `text`
as a plain `Text` argument only, same as every sibling status-row indicator; it performs no further
sanitisation of its own; see [Usage-limit indicator § Security](usage-limit-indicator.md#security) for why
the caller's sanitisation bound is the one that matters (the pill wraps instead of `maxLines`-capping, so
the caller, not this file, must keep the text short).

## Related

- Hosts: [Thread top overlay](thread-top-overlay.md) — the usage and pairing pills. [Thread screen §
  Thinking-indicator placement](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
  — the task-count pill, inside `ThreadStatusArea`.
- Callers' content: [Usage-limit indicator](usage-limit-indicator.md) (`usageLimitLabel`,
  `usageLimitIsWarning`), the pairing-error string `R.string.thread_re_pair` (unchanged from
  [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843)), and `ThreadUiState.backgroundTaskCount`
  (fed by `observeLiveBackgroundTaskCount`, pinned by `ThreadViewModelBackgroundTasksTest`; see
  [Thread screen § Background-tasks panel placement](thread-screen-how-it-works-overlays-and-app-bar.md#background-tasks-panel-placement-post-678)).
- Specs: `docs/specs/architecture/1002-thread-top-overlay-pills.md`,
  `docs/specs/architecture/1043-task-count-pill.md`.
- Figma: [`347:6617`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=347-6617)
  (the `Pill` component set), [`568:3162`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-3162)
  (the in-band task-count pill instance).

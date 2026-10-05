# Notice pill — `NoticePill`

The shared pill shape for [`ThreadTopOverlay`](thread-top-overlay.md)'s attention, usage, MCP,
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
    containerColor: Color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
    mergeDescendants: Boolean = true,
)
```

[#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043) added `shadowElevation`, defaulted to the
existing `PillShadow` constant so [`ThreadTopOverlay`](thread-top-overlay.md)'s overlay callers are
unaffected — see [§ Caller contracts](#caller-contracts) below for the
task-count caller that overrides it to `0.dp`.

A `Surface` (6dp `RoundedCornerShape`, `shadowElevation` for the overlay's drop shadow) holding a `Row`
(8dp horizontal / 4dp vertical padding, 8dp gap, centre-aligned): a `bodySmall`, right-aligned `Text` in
`Modifier.weight(1f, fill = false)` — so the pill hugs short text and wraps long text while a trailing X
stays visible — and, when `onDismiss != null`, an 8dp exported X drawable as its own clickable node
(`Role.Button`, `contentDescription = R.string.thread_notice_dismiss`, "Dismiss notice"). Compose's minimum
touch-target expansion gives the X a 48dp tap area without growing the pill's drawn size — a deliberate
divergence from the usual 48dp `IconButton` wrapper, kept to match Figma's compact pill height.

**Two variants, colours from `MaterialTheme.colorScheme`:** Default is `primaryContainer` /
`onPrimaryContainer`; Error is `errorContainer` / `error`, as Figma paints it. `isError` selects between
them by default; callers may override `containerColor` and `contentColor` without changing geometry
or unrelated callers. Semantics: `Modifier.semantics(mergeDescendants = mergeDescendants) { contentDescription
= contentDescription }` on the outer `Surface`, defaulting to the visible `text` — the merged node reads as
one TalkBack stop with the default `mergeDescendants = true`, the same "wording has one source" idiom every status-row indicator already uses (see
[Usage-limit indicator](usage-limit-indicator.md), [Resetting indicator](resetting-indicator.md)). When
`onClick != null` the whole pill is a clickable `Surface(onClick = onClick, ...)`; otherwise a plain
`Surface`.

## Caller contracts

- **Attention pill** ([Thread top overlay](thread-top-overlay.md#the-attention-pill-1735)):
  overrides container/content colors for Waiting and Finished, keeps the overlay shadow and uses
  two-line ellipsis without an X. The inert inner surface has a 24dp minimum height; a separate
  parent button adds 24dp touch space upward while reporting only visible height to the stack.
  This preserves the 12dp gap below and the neighboring dismiss action. `mergeDescendants = false`
  lets that parent own the label and description as one accessible button. A nested merging surface
  would hide them from the clickable target even while text and pointer tests passed.
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

`ThreadAttentionNoticeTest` verifies default-compatible color customization through the attention
caller, native-graphics surface and target bounds, two-line sanitized names and stacking.
Its combined selector requires the parent tag, bounded text, description and click action together
for Waiting, Finished and count variants. Test actual touch bounds separately from visible bounds,
including the adjacent usage dismiss target. Measure width against the actual viewport's gutters.

The [`ThreadTopOverlay`](thread-top-overlay.md) callers are covered indirectly through
[`ThreadTopOverlayTest`](thread-top-overlay.md#testing) (Robolectric, `app/src/sharedTest/.../thread/`)
rather than a standalone Compose test of `NoticePill` in isolation — the component has no behaviour worth
pinning apart from how each caller drives it (variant selection, dismiss wiring, wrap, shadow). Two
`@Preview`s (light/dark, `widthDp = 412`) stack a long usage-style label with a dismiss X above a short
error-style pairing label with `onClick`, matching the dark-theme Robolectric render the plan's Phase B
recorded against Figma `533:1956`. The task-count caller is covered by `TaskCountPillTest`
(`app/src/sharedTest/.../thread/`, `@GraphicsMode(NATIVE)`) — see [Thread screen § Thinking-indicator
placement](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
for what it pins.

## Security

Call sites own sanitization before text reaches this file
(`notificationTitle` for attention names, `usageLimitLabel(reading)`, the local `R.string.thread_re_pair` resource, or the client-owned plural
`R.plurals.thread_task_count` formatted against a device-side `Int` count, or the turn-outcome arm's
client-owned recovery copy, `turnRecoveryNotice`, which compares daemon tokens but never renders them,
[#1357](turn-outcome-indicator.md)). `NoticePill` renders `text`
as a plain `Text` argument only, same as every sibling status-row indicator; it performs no further
sanitisation of its own; see [Usage-limit indicator § Security](usage-limit-indicator.md#security) for why
the caller's sanitisation bound is the one that matters (the default line limit permits wrapping, so callers must bound hostile text and choose
a suitable `maxLines`).

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

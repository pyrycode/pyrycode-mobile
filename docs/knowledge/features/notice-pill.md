# Notice pill — `NoticePill`

The shared pill shape for [`ThreadTopOverlay`](thread-top-overlay.md)'s two notices
([#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002)): claude's usage-limit report and the
pairing-error notice. Figma `347:6617`'s `Pill` component set, variants **Default** and **Error**.

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
)
```

A `Surface` (6dp `RoundedCornerShape`, `shadowElevation` for the overlay's drop shadow) holding a `Row`
(8dp horizontal / 4dp vertical padding, 8dp gap, centre-aligned): a `bodySmall`, right-aligned `Text` in
`Modifier.weight(1f, fill = false)` — so the pill hugs short text and wraps long text while a trailing X
stays visible — and, when `onDismiss != null`, a 14dp `Icons.Filled.Close` glyph as its own clickable node
(`Role.Button`, `contentDescription = R.string.thread_notice_dismiss`, "Dismiss notice"). Compose's minimum
touch-target expansion gives the X a 48dp tap area without growing the pill's drawn size — a deliberate
divergence from the usual 48dp `IconButton` wrapper, kept to match Figma's compact pill height.

**Two variants, colours from `MaterialTheme.colorScheme`:** Default is `primaryContainer` /
`onPrimaryContainer`; Error is `errorContainer` / `error`, as Figma paints it. `isError` selects between
them; there is no third state. Semantics: `Modifier.semantics(mergeDescendants = true) { contentDescription
= contentDescription }` on the outer `Surface`, defaulting to the visible `text` — the merged node reads as
one TalkBack stop, the same "wording has one source" idiom every status-row indicator already uses (see
[Usage-limit indicator](usage-limit-indicator.md), [Resetting indicator](resetting-indicator.md)). When
`onClick != null` the whole pill is a clickable `Surface(onClick = onClick, ...)`; otherwise a plain
`Surface`.

## Two call sites, two contracts

- **Usage pill** ([`ThreadTopOverlay`](thread-top-overlay.md#the-usage-pill)): `onDismiss` is non-`null`
  only when [`usageLimitIsWarning`](usage-limit-indicator.md#shape) is true for the reading being shown —
  every other reading, including an unrecognised `status`, gets `onDismiss = null` and cannot be hidden.
  `onClick` is always `null` here; tapping the pill's body does nothing.
- **Pairing pill** ([`ThreadTopOverlay`](thread-top-overlay.md#the-pairing-pill)): `onClick` starts the same
  re-pair flow the pre-#1002 `RePairButton` started (`onRePair`, bound at `MainActivity` to
  `navController.navigate(Routes.pairCode(target.serverId))`); `onDismiss` is always `null` — a rejected
  pairing is never hideable, matching the ticket's "never dismissible" requirement for anything that is not
  the one named warning status.

The component itself does not know which caller it is serving — `isError` and the two nullable callbacks
are the whole contract, so a future third notice reuses this file rather than growing a new one.

## Testing

Covered indirectly through [`ThreadTopOverlayTest`](thread-top-overlay.md#testing) (Robolectric,
`app/src/sharedTest/.../thread/`) rather than a standalone Compose test of `NoticePill` in isolation — the
component has no behaviour worth pinning apart from how the overlay drives it (variant selection, dismiss
wiring, wrap). Two `@Preview`s (light/dark, `widthDp = 412`) stack a long usage-style label with a dismiss X
above a short error-style pairing label with `onClick`, matching the dark-theme Robolectric render the
plan's Phase B recorded against Figma `533:1956`.

## Security

No daemon-authored text reaches this file directly — both call sites pass already-sanitised text
(`usageLimitLabel(reading)`, or the local `R.string.thread_re_pair` resource). `NoticePill` renders `text`
as a plain `Text` argument only, same as every sibling status-row indicator; it performs no further
sanitisation of its own; see [Usage-limit indicator § Security](usage-limit-indicator.md#security) for why
the caller's sanitisation bound is the one that matters (the pill wraps instead of `maxLines`-capping, so
the caller, not this file, must keep the text short).

## Related

- Host: [Thread top overlay](thread-top-overlay.md) — the only caller, drives both variants.
- Callers' content: [Usage-limit indicator](usage-limit-indicator.md) (`usageLimitLabel`,
  `usageLimitIsWarning`) and the pairing-error string `R.string.thread_re_pair`, unchanged from
  [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843).
- Spec: `docs/specs/architecture/1002-thread-top-overlay-pills.md`.
- Figma: [`347:6617`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=347-6617)
  (the `Pill` component set).

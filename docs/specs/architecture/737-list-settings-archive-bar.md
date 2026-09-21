# 737 — The mobile list's own settings and archive bar

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListScreen`,
  `ChannelListEvent`, `CHANNEL_LIST_TEST_TAG`, `TreeGutter`, `SECTION_RULE_ALPHA` — the screen this slice
  restructures; the gutter and rule alpha the new bar reuses.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CHANNEL_LIST` `composable` block and
  `Routes.ARCHIVED_DISCUSSIONS` — the route's `when (event)` gains one branch, pointed at a destination the
  Settings route already navigates to from `onOpenArchivedDiscussions`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `onEvent` — its
  `when` over `ChannelListEvent` is exhaustive, so a new variant has to be named there too.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeRowMinHeight`,
  `TreeSectionHeader` — the precedent this bar follows: Figma's pointer-sized geometry is honoured for the
  glyph's *position*, while the touch target is held to 48dp, and a Figma glyph is matched with the nearest
  Material icon rather than a vendored drawable.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` →
  `topAppBar_rendersTitleAndSettingsAction_whenLoaded` (reds on this change, rewritten),
  `settingsGear_emitsSettingsTapped`, `arrivalMarker_isCarriedByEveryDrawOfTheList_exactlyOnce` — the
  four-draw walk the new bar test mirrors.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `CD_OPEN_SETTINGS` and step 9
  of `interactiveTurn_archiveRestore_roundTripsListMembership` — the live scenario AC-3 names. It taps the
  list's settings entry by content description, so that description must survive the restructure verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` → its logo `Icon` — passes
  `contentDescription = null`, so `R.string.cd_pyrycode_logo` has exactly one consumer, the bar being retired.
- `docs/knowledge/features/channel-list-screen.md` § "`Scaffold` + `TopAppBar` chrome" — records that the
  outer `Scaffold` in `MainActivity` carries system-bar insets, which decides the new bar's inset handling.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Node `15-8` marks its own `Top App Bar` frame hidden and hosts the mobile Sidebar adaptation `133-259`. That
adaptation's `Top bar` is a column: a `Buttons` row holding a 24dp `gear-solid` at the leading content edge and
a 24dp `box-archive-solid` 52dp further in (28dp of air between the glyph boxes), then 20dp of gap, then a
one-pixel rule spanning the bar's full 360dp content width. Both glyphs draw in `Schemes/Primary` (`#9dcbfc`
dark) — `MaterialTheme.colorScheme.primary`, the tint the retired logo already used. The bar sits on the same
20dp horizontal gutter as the tree below it, 24dp below the frame's top, with 28dp between its rule and the
first section header. Nothing else is in the bar: no app name, no logo, no title.

## Context

The supplied design retires the app's generic top app bar on the list and gives the list its own chrome. This
slice draws that bar and wires its two entries; `#738` takes the floating action button and the compatibility
flat-list state, and `#715` rebinds archive browsing to a specific host. Archive browsing itself already
exists — this adds a second door to it, not a new destination.

No ADR is warranted: this is a screen-local chrome change that introduces no new contract.

## Design

`ChannelListScreen` keeps its `Scaffold`. Only the `topBar` slot's content changes, from the M3 `TopAppBar` to
a file-private `ChannelListTopBar`. That is the whole structural move, and it is what makes the first
acceptance criterion fall out for free: the `topBar` slot is drawn by the `Scaffold` above the body branch, so
it renders on all four draws — the loading and error texts, the empty placeholder and the assembled tree —
without the branch on `hostState.hosts` being touched. The arrival marker stays where `#736` put it, on the
`Scaffold`'s `modifier`, so `CHANNEL_LIST_TEST_TAG` remains one node on every draw.

`ChannelListTopBar` is a `Column`:

- a `Row` of two 48dp `IconButton`s — `Icons.Default.Settings` emitting `SettingsTapped`,
  `Icons.Default.Archive` emitting the new `ArchiveTapped` — each with a 24dp `Icon` tinted
  `MaterialTheme.colorScheme.primary` and its own `contentDescription`;
- a `HorizontalDivider` in `outlineVariant` held back to the existing `SECTION_RULE_ALPHA`, the same treatment
  the tree's between-sections rule already gives the design's identically-styled rectangle.

**Geometry.** Figma's glyphs are 24dp with their centres 32dp and 84dp from the screen edge; touch needs 48dp,
as `TreeRowMinHeight` already records for the tree rows. Wrapping each 24dp glyph in a 48dp `IconButton` adds
12dp of slack on every side, so the row is inset by `TreeGutter - 12.dp` and spaced by 4dp, which lands both
glyph centres exactly where the design puts them while giving each entry a full 48dp target. The same 12dp of
vertical slack is subtracted from the design's 24dp top offset and its 20dp gap above the rule.

**Insets.** M3's `Scaffold` gives the body a top padding equal to the `topBar`'s measured height when a
`topBar` is present, and leaves the window inset to the bar itself. The retired `TopAppBar` applied
`TopAppBarDefaults.windowInsets` on top of the status-bar padding `MainActivity`'s outer `Scaffold` already
applies to the whole `PyryNavHost`; the plain `Column` applies none, so the doubled status-bar inset goes with
the old bar and the new one sits where the design draws it.

**Contracts.**

```kotlin
sealed interface ChannelListEvent {
    /** The list's own archive entry — the same destination Settings' archived-discussions row opens. */
    data object ArchiveTapped : ChannelListEvent
    // … TreeRowTapped, TreeFoldToggled, SettingsTapped, CreateDiscussionTapped, LongPressFab,
    //    WorkspacePicked, WorkspacePickerDismissed unchanged …
}

@Composable
private fun ChannelListTopBar(onEvent: (ChannelListEvent) -> Unit)
```

**Wiring.** `MainActivity`'s `Routes.CHANNEL_LIST` `when (event)` gains
`ChannelListEvent.ArchiveTapped -> navController.navigate(Routes.ARCHIVED_DISCUSSIONS)`, one line beside the
existing `SettingsTapped` branch and the same call the Settings route's `onOpenArchivedDiscussions` already
makes — one destination, two doors, no second route. `ChannelListViewModel.onEvent`'s exhaustive `when` names
`ArchiveTapped` alongside `SettingsTapped` in the inert branch: the navigation host handles both.

**Strings.** `cd_open_settings` ("Open settings") stays verbatim — the live scenario's `CD_OPEN_SETTINGS`
mirrors it, and renaming it would mean moving that constant. A new `cd_open_archive` ("Open archive") names the
second entry; it is deliberately distinct from the Archived screen's own `archived_title` and from Settings'
`archived_discussions_settings_row`, so no existing device-suite matcher can collide with it.
`R.string.cd_pyrycode_logo` loses its only consumer with the logo and is deleted; `app_name` stays — the
manifest label still uses it.

## State + concurrency model

None. The bar is stateless: it takes `onEvent` and emits. No new job, flow, dispatcher or scope.

## Error handling

None. Both entries are local navigation to destinations that already exist; neither reaches the data layer,
the wire or the daemon, and neither renders daemon-authored text.

## Testing strategy

`ChannelListScreenTest` (Compose UI, `app/src/androidTest/`) — device-run, scoped to the class:

- `topAppBar_rendersTitleAndSettingsAction_whenLoaded` is rewritten as a four-draw walk in the shape of
  `arrivalMarker_isCarriedByEveryDrawOfTheList_exactlyOnce`: drive one composition through loading, error, the
  empty placeholder and the tree, asserting on each draw that both entries are displayed and that the app name
  and the logo's description do not exist. Without the four-draw walk a bar placed in the tree's scroll
  container would pass on the one draw a single-state test happened to pick.
- `archiveEntry_emitsArchiveTapped` — the new entry emits its own event, mirroring
  `settingsGear_emitsSettingsTapped`, which is kept unchanged as the guard on `cd_open_settings` surviving.

No unit test: the bar introduces no logic off the UI thread, and the `ViewModel`'s new `when` arm is the inert
`-> Unit` the navigation host already owns for `SettingsTapped`.

No e2e change. AC-3's proof is that
`InteractiveStreamE2ETest.interactiveTurn_archiveRestore_roundTripsListMembership` still walks, which it does
because step 9 taps `CD_OPEN_SETTINGS` and that description is carried forward verbatim onto the new settings
entry. Only its stale "list top-bar settings button" comment is touched. A dedicated rung-3 scenario that
reaches Archived through the *list's own* archive entry is filed as a follow-up in the `#481`/`#482` shape
rather than landed here: the destination it would reach is already live-covered through the Settings door, and
the new entry adds one `navigate` call, so a second live run buys a route assertion the device test already
makes at the event boundary.

**Visual check.** `@Preview` composables already exist for the tree in light and dark and for the no-hosts
draw; all three now render the bar and are compared against the Figma screenshot of `133-259` before the PR.

## Open questions

- Whether `Icons.Default.Archive` reads as the design's `box-archive-solid` at 24dp. Resolved at
  implementation against the Figma screenshot; `material-icons-extended` is already a dependency, and the
  tree rows set the precedent of matching a FontAwesome glyph with the nearest Material icon rather than
  vendoring a drawable.

## Documentation handoff

Pending the documentation stage — not written by this ticket:

- `docs/knowledge/features/channel-list-screen.md` § "`Scaffold` + `TopAppBar` chrome (#21) + `ChannelListFab`
  (#22 → #221)": replace what it says about the old bar with the list's own bar and the two entries it carries.
- `docs/knowledge/features/navigation.md`: record that archive browsing is reachable from the list as well as
  from Settings.

# #736 — A list arrival marker and create-chat helpers the device suites own

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListScreen`,
  `TREE_CHANNEL_ROW_TEST_TAG` / `TREE_CHAT_ROW_TEST_TAG`, `ChannelListFab` — the screen the marker goes on,
  the tag convention #731 minted, and the control the two creation helpers drive.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListUiState`,
  `ChannelListViewModel.state`, `ChannelListViewModel.hostState` — the four states the marker has to survive,
  and the fact that the tree's rows and the button's visibility come from **two independent** `StateFlow`s.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CHANNEL_LIST` `composable` — the one
  production call site, which passes no `modifier`, so the screen's own root is the single application point.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → all eight scenarios plus the
  two `@Ignore`d manual ones, `awaitConnected`, the companion's `CD_NEW_DISCUSSION` — the 33 sites to move.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` →
  `setTree`, `conversationRows_carryTheirSectionTier`, `emptyState_rendersPlaceholder_whenThereAreNoHosts` —
  the screen-test harness the four-draw proof extends, and #731's own tag proof to mirror.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` → confirmed it
  waits on the seeded channel's name, never on the button; untouched here, as the ticket states.
- `docs/knowledge/features/channel-list-screen.md` § "Tier test tags" — the reasoning this marker copies
  rather than re-deriving: app-authored literal, no daemon text, invisible to TalkBack, `testTag` already a
  production-side mechanism in this codebase (`ScannerScreen`'s reticle and hint).
- `docs/e2e-interactive-stream.md` § "What rung 3 is made of" — where the documentation stage records the
  new handles; read to size the handoff, not edited here.

## Design source

N/A — nothing the operator sees changes. No `## Figma` section on the ticket, and the visual-fidelity check
is intentionally skipped: the diff adds a semantics-only `testTag` and rewrites test bodies.

## Context

Every scenario in `InteractiveStreamE2ETest` decides it has landed on the channel list by waiting for the
floating action button's content description, and the ones needing a fresh chat tap or long-press that same
button — 33 sites in one class at `744d94e`. #738 removes the button, and with it both handles. The live gate
runs the whole class against real claude, so a marker that stops matching reds all eight scenarios and spends
real turns discovering it. This slice moves the suites onto replacement handles **while the button is still
there**, so the handles are proven live before anything is removed; #738 then edits two helpers.

No ADR is warranted — this reuses #731's decision (app-authored `testTag` as the device suites' durable
handle) rather than making a new one.

## Design

**The marker.** One `internal const val CHANNEL_LIST_TEST_TAG: String = "channel-list"` beside #731's two row
tags in `ChannelListScreen.kt`, applied in exactly one place: the `Scaffold`'s `modifier` in
`ChannelListScreen`. That is the screen's root, above the `when (state)` that chooses between the loading
text, the error text and the empty placeholder, and above the `ConversationTree` branch — so all four draws
carry it by construction, not by four separate applications that could drift apart. The one production call
site passes no `modifier`, so nothing upstream can displace it, and no other destination composes this screen.

Its name says the destination, not the chrome: nothing in it refers to the button, the top bar, or the
`ChannelListUiState` case that #738 retires, which is what lets it survive that ticket and the later list
tickets (#642, #664, #715) without a second migration. Like #731's row tags it is an app-authored literal,
carries no daemon text, and `testTag` is invisible to accessibility services.

**The three helpers**, private to `InteractiveStreamE2ETest`:

- `awaitChannelList()` — waits on `hasTestTag(CHANNEL_LIST_TEST_TAG)` at `LIST_TIMEOUT_MS`. Replaces all 20
  arrival waits, including the two in the `@Ignore`d manual scenarios.
- `createChat()` — waits for the create control, then taps it. Replaces the 11 taps.
- `openWorkspacePicker()` — waits for the create control, then long-presses it. Replaces the 2 long-presses.

The two creation helpers are the only places in the class that name `CD_NEW_DISCUSSION`; each carries its own
wait inline rather than sharing a third `awaitCreateControl()` helper, precisely so that the count stays at
two and #738 has two bodies to edit. The constant itself stays in the companion with a comment saying so.

**The deliberate weakening, and why it is safe here.** Waiting for the button implied the list had loaded —
the button draws only on `Loaded` / `Empty`. The marker matches on all four draws, so that implication is
gone; the two creation helpers carry it instead, which is the only place it was load-bearing (acting on a
control before it is drawn would fail the drive, not the wait).

Two sites are arrival waits followed by an **absence** assertion rather than by a creation: the delete
scenario's post-delete `assertCountEquals(0)` and the archive scenario's post-archive one. Those are left as
they are. Since #731 the button's visibility is computed from `ChannelListViewModel.state` while the tree's
rows come from `ChannelListViewModel.hostState` — two independent `StateFlow`s — so the old button wait never
implied the rows had drawn either; the non-vacuity of both assertions rests on their own stated argument (the
repository projection lands before `PopBack` fires) and on the presence observation each scenario makes on
the same surface earlier, neither of which this change touches. No such failure has been observed, so no
defence is added for one.

**State, concurrency, errors.** None. The production change adds a semantics property; no new state, no new
coroutine, no new failure mode. The helpers are synchronous test-thread calls in the shape the class already
uses.

## Testing strategy

- **`ChannelListScreenTest` (device, Compose):** one new test drives the screen through all four draws over a
  hoisted `mutableStateOf` holder, asserting on each that exactly one node carries the marker **and** that the
  draw really is the one intended (the loading text, the error text carrying its message, the empty
  placeholder string, a tree row). The count assertion is the evidence for "applied in exactly one place"; the
  four distinguishing assertions are what stop the test passing while rendering the same draw four times.
- **`InteractiveStreamE2ETest` (rung 3, live):** the migration's own proof is the live gate, which AC-4 makes
  explicit — the class runs at its current minimum with the button, its content description and both of its
  paths unchanged. That is the dispatcher's post-verifier run, not mine.
- Focused device run of the new screen test plus `compileDebugAndroidTestKotlin` for the e2e class, then
  `lint` and `assembleDebug`. No unit test: there is no new logic to unit-test.
- No rung-3 scenario is added or removed — this ticket is not an operator-facing flow, it is the handle those
  flows hold.

## Documentation handoff

Pending for the documentation stage, from the ticket's own handoff section:

- `docs/e2e-interactive-stream.md` § "What rung 3 is made of" — record the arrival marker and the two creation
  helpers, so the next list ticket reads the handle rather than the button.
- `docs/knowledge/features/channel-list-screen.md` — add the marker to the tag inventory beside #731's
  `TREE_CHANNEL_ROW_TEST_TAG` / `TREE_CHAT_ROW_TEST_TAG` in the "Tier test tags" section.

Not edited in this ticket.

## Sizing

One boundary line exceeded — 33 consumer call sites against a limit of 10 — and the split-depth gate forbids
the split (`parent 732 grandparent 641`). `needs-human:sizing` is applied with the full measurement and the
split that would otherwise have been proposed. Every other line passes: 1 production file, ~270 lines of total
written work, 0 new exported types, 4 acceptance criteria, 0 reject branches.

## Open questions

- Whether the two absence-assertion sites eventually want a content-bearing wait rather than a bare arrival
  wait. Deferred deliberately: no such flake has been observed, and the old marker gave them no more than the
  new one does. If #738 or a later list ticket sees one, that is the evidence to act on.

# Thread top overlay — `ThreadTopOverlay`

The **notice surface** for the thread ([#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002)):
claude's usage-limit report, the pairing-error notice and Offline Retry, drawn as a right-aligned stack of
[`NoticePill`](notice-pill.md)s pinned over the top of the message area — replacing the two arms they used
to share with live turn status inside `ThreadStatusArea`.

Package: `de.pyryco.mobile.ui.conversations.thread`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`).

## Why this moved (#1002)

Since 2026-09-24 the Max plan's seven-day window has been past 75%, so claude attaches an
`allowed_warning` usage report to *every* turn until the window resets. `ThreadStatusArea`'s pre-#1002
ladder ranked that reading above resetting, compaction, the turn outcome and thinking/running-tool, so
while the reading was live none of those ever rendered — "Running Bash…", the wrap-up phase and
"Interrupted" were all invisible. Three `InteractiveStreamE2ETest` methods
(`interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`,
`interactiveTurn_newSession_rendersSessionBoundaryDelimiter`,
`interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`) failed on every branch as a result.
Moving notices out of the ladder into a pinned overlay — instead of, say, re-ranking the ladder — keeps
both signals visible at once: live turn status in the status row, notices as pills above the messages. See
[Resetting indicator § Placement](resetting-indicator.md#placement-in-the-thread) for the ladder that
remains.

## Shape

```kotlin
@Composable
internal fun ThreadTopOverlay(
    usageLimit: UsageLimitReading?,
    usageLimitDismissed: Boolean,
    onDismissUsageLimit: () -> Unit,
    showRePair: Boolean,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier,
    connectionState: ConnectionState = ConnectionState.Connected,
    onRetryConnection: () -> Unit = {},
    agent: ConversationAgent = ConversationAgent.Claude,
)
```

`agent` ([#1115](https://github.com/pyrycode/pyrycode-mobile/issues/1115)) is `ThreadScreen`'s own
`state.agent`, forwarded unchanged to `usageLimitLabel` below. Defaulting to `Claude` — `Conversation.agent`'s
own default — keeps every prior call site and preview compiling unchanged.

Emits nothing when there is no pill to show (`usageLimit == null || usageLimitDismissed`, and
`!showRePair`, and `connectionState != Offline`) — the overlay is an overlap (`Box` alignment, not a layout slot), so an empty overlay costs
nothing and the message area draws exactly as if it were absent. Otherwise a `Column(horizontalAlignment =
End, verticalArrangement = spacedBy(12.dp))` — Figma `541:2446`'s 12dp pill gap — with, top to bottom:

### The usage pill

When `usageLimit != null && !usageLimitDismissed`: `NoticePill(text = usageLimitLabel(usage, agent), isError =
!usageLimitIsWarning(usage), onDismiss = if (warning) onDismissUsageLimit else null)`. See [Usage-limit
indicator](usage-limit-indicator.md) for `usageLimitLabel` / `usageLimitIsWarning` and the wording
guarantees behind them, including how `agent` ([#1115](https://github.com/pyrycode/pyrycode-mobile/issues/1115))
names the conversation's own agent in the lead string instead of a fixed "Claude".

### The pairing or Offline pill

When `showRePair`: `NoticePill(text = stringResource(R.string.thread_re_pair), isError = true, onClick =
onRePair)` — the local string resource "Pairing error - Re-pair", never daemon text (a code comment on this
line makes the point explicit, since every other pill in this file carries claude-authored text). Carries
forward [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843)'s contract unchanged: `showRePair`
comes from `ThreadViewModel.rePairAvailable`, `true` exactly when this thread's own host is in the
rejected-pairing state; `onRePair` is bound at `MainActivity` to
`navController.navigate(Routes.pairCode(target.serverId))`. This pill has no dismiss X — a rejected pairing
is never hideable, unlike a warning reading.

Usage sits above the lower action, matching Figma `533:1956`. When `connectionState` is Offline and `showRePair` is false, the lower action is the error-toned [Retry pill](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910). Its 48 dp clickable box extends below the visible 24 dp pill, preserving the 12 dp gap and avoiding the usage dismiss target. The visible pill is currently 144 dp wide; the verifier noted that it extends farther left than the reference. `showRePair` wins when pairing is rejected, since a network retry cannot repair that state.

## Placement in `ThreadScreen`

The message area — either `EmptyThreadState` or the `LazyColumn` — is wrapped in a `Box(Modifier
.fillMaxWidth().weight(1f))`; the child takes `fillMaxSize()`, and `ThreadTopOverlay` is drawn after it,
`Modifier.align(Alignment.TopEnd).padding(start = ComposerGutter, top = TopOverlayTopGap, end =
ComposerGutter)`. `TopOverlayTopGap = 8dp` — Figma pins the overlay flush to the message area's top edge;
the plan's one open question kept 8dp instead so the first pill doesn't touch the app bar, and a dark-theme
Robolectric render at 412dp confirmed the match against Figma `533:1956` (see the ticket's Revisions). Being
an overlap rather than a `Column` child is load-bearing: the overlay draws *over* the scrolling messages, so
it never reserves layout space and the list never reflows as pills appear or clear.

`ThreadScreen` gains two new defaulted parameters that feed the overlay:

```kotlin
dismissedUsageLimits: Set<UsageLimitDismissals.Key> = emptySet(),
onDismissUsageLimit: (UsageLimitReading) -> Unit = {},
```

The screen stays stateless — it computes `usageLimitDismissed = usageLimit?.dismissalKey() in
dismissedUsageLimits` and binds the X to the reading it is currently showing
(`onDismissUsageLimit = { usageLimit?.let(onDismissUsageLimit) } }`). Defaulting both keeps every prior
`ThreadScreen(` call site and preview compiling unchanged; only `MainActivity` and any test asserting
dismissal need the new arguments.

Offline Retry is suppressed while `showRePair` is true; see [connection status placement](thread-screen-how-it-works-overlays-and-app-bar.md#connection-status-placement).

## Dismissal — `UsageLimitDismissals`

```kotlin
class UsageLimitDismissals {
    val dismissed: StateFlow<Set<Key>>
    fun dismiss(reading: UsageLimitReading)
    data class Key(val status: String, val limitType: String, val resetsAt: Long)
}
internal fun UsageLimitReading.dismissalKey(): UsageLimitDismissals.Key
```

Package: `de.pyryco.mobile.ui.conversations.thread` (`UsageLimitDismissals.kt`). App-process heap only, the
same idiom as [`ComposerDraftStore`](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership)
— a plain class, one `MutableStateFlow<Set<Key>>` mutated with `update { it + key }`, bound `single {
UsageLimitDismissals() }` in `AppModule.kt` beside the draft store.

**The key is the reading, never the conversation or the host.** A usage limit belongs to the *account*, so
dismissing a reading in one thread hides the same reading in every open thread — deliberate, not an
oversight. `Key` is `(status, limitType, resetsAt)`; a change to any of the three is a different key, so the
pill returns (the window reset, or a new limit type started reporting). A changed `utilization` alone keeps
the same key, so ticking utilization on an already-dismissed reading does not resurrect its pill.

**Never persisted, never logged.** No `DataStore`, no `SavedStateHandle`, no `rememberSaveable` — a process
restart shows every currently-live reading again. The key holds daemon-authored strings and the account's
quota posture, so no part of it may reach a log line, an exception message or a crash report (see [Usage-
limit indicator § Security](usage-limit-indicator.md#security)).

`MainActivity`'s thread destination injects the singleton via `koinInject()`, collects `dismissed` with
`collectAsStateWithLifecycle()`, and passes `dismissedUsageLimits = dismissed` /
`onDismissUsageLimit = dismissals::dismiss` into `ThreadScreen`.

## State + concurrency model

No coroutine is launched anywhere in this slice. `UsageLimitDismissals` holds one `MutableStateFlow`,
mutated with `update` (compare-and-set) on the main thread from a click. The usage reading's own flow, its
30 s re-read ticker (see [Usage-limit indicator § Wiring](usage-limit-indicator.md#wiring--a-30-s-re-read-ticker-not-a-timer-from-resets_at))
and `rePairAvailable` are all untouched by #1002 — only where their values are *rendered* changed.

## Testing

- **Robolectric** `ThreadTopOverlayTest` (`app/src/sharedTest/.../thread/`):
  - no reading and no re-pair → no pill nodes, no dismiss X;
  - an `allowed_warning` reading → a pill with the label and an X; tapping the X (against a test-held
    dismissal set wired to `onDismissUsageLimit`) hides it; giving the reading a new `resetsAt` shows it
    again;
  - a `rejected`-style and an unrecognised status → a pill shown, no X;
  - the usage pill's bounds sit above the pairing pill's when both show; tapping the pairing pill fires
    `onRePair` exactly once;
  - **the regression proof (AC #4):** with an `allowed_warning` reading live, "Running Bash…" still renders
    in the status row (a busy state with an open tool row), the wrapping-up reset label still renders, and
    "Turn interrupted" still renders — written first, and it fails against the pre-#1002 ladder.
- **JVM unit** `UsageLimitDismissalsTest` — see [Usage-limit indicator §
  Testing](usage-limit-indicator.md#testing).
- **Kept unchanged**, `ThreadScreenRePairTest`: still finds the pairing notice by its `R.string.thread_re_pair`
  text and clicks it, still checks the connection banner is withheld — the test needed no change because it
  asserts by text, not by composable identity, so it holds whether the notice is a status-row button or a
  Top-overlay pill.
- **Rung 3 (live):** no new scripted or e2e scenario of its own. The acceptance proof is that the three
  `InteractiveStreamE2ETest` methods named in [Why this moved](#why-this-moved-1002) pass again under
  `python3 scripts/android-test-gate.py live` while the account's usage reading is `allowed_warning` — they
  needed no code change, since moving the reading off the status slot they assert against is the whole fix.
  The dismiss affordance has no rung-4 twin: a scripted `rate_limited` frame drives the same pill the
  Robolectric coverage above already exercises.

## Security

Builder self-review **PASS**, full text in the [architecture
doc](../../specs/architecture/1002-thread-top-overlay-pills.md#security-review). No new trust boundary: the
daemon-authored fields reach Compose only through `usageLimitLabel` (the same three render-or-decline
helpers as before #1002), and the one new branch on `status` (`usageLimitIsWarning`, exact equality) only
ever adds or withholds a dismiss X — every other status, recognised or not, is an Error pill that cannot be
hidden, so a hostile daemon gains nothing by sending `"allowed_warning"` that it could not already do with
the benign `"allowed"`. `UsageLimitDismissals` is heap-only (see [Dismissal](#dismissal--usagelimitdismissals)
above) — no disk, no backup-eligible state, no log. No intent, deep link, provider or WebView is added; the
overlay's only actions are hiding a notice locally or opening the existing re-pair screen the user already
had one tap away. Dismissal tells the daemon nothing.

## Related

- Content: [Notice pill](notice-pill.md) — the shared pill composable both notices render through.
- Usage reading: [Usage-limit indicator](usage-limit-indicator.md) — the label/warning helpers, the
  dismissal key extension, and the removed status-row arm's history.
- Pairing notice: [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843) — the rejected-pairing
  signal (`ThreadViewModel.rePairAvailable`) and the Re-pair priority over Offline Retry, described in [connection status placement](thread-screen-how-it-works-overlays-and-app-bar.md#connection-status-placement).
- Host: [Thread screen](thread-screen.md) — the `Box` overlap this composable draws into, and the two new
  defaulted parameters.
- Status ladder left behind: [Resetting indicator § Placement](resetting-indicator.md#placement-in-the-thread)
  — the current `ThreadStatusArea` ladder, now turn-status only.
- Idiom mirrored: [Composer draft ownership](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership)
  (`ComposerDraftStore`) — the app-process-heap `Koin single` shape `UsageLimitDismissals` clones.
- Spec: `docs/specs/architecture/1002-thread-top-overlay-pills.md`.
- Figma: [`533:1956`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=533-1956)
  (message area with the Top overlay), [`347:6617`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=347-6617)
  (the Pill component set).

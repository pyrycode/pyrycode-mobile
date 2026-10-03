# Usage-limit indicator — `usageLimitLabel`

The **UI half of the usage-limit reading** ([#804](https://github.com/pyrycode/pyrycode-mobile/issues/804),
split from #653, consumes [#802](usage-limit-state.md)): claude's own `rate_limited` report, rendered so a
turn that stalls on a usage limit says why instead of leaving the user watching a spinner.

**[#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002) removed the `UsageLimitIndicator`
composable and its status-row arm.** Since 2026-09-24 the account's seven-day window has been past 75%, so
claude attaches an `allowed_warning` report to *every* turn — with the reading ranked above resetting,
compaction, the turn outcome and thinking/running-tool, none of those ever rendered, and three live e2e
methods failed on every branch (see [Thread top overlay](thread-top-overlay.md#why-this-moved-1002) for the
failure). The reading now draws as a pill in [`ThreadTopOverlay`](thread-top-overlay.md), pinned over the
message area instead of sharing the status row. What remains in this file is the **label and warning-status
helpers** the pill calls; [`ThreadStatusArea`](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
no longer takes a `usageLimit` parameter at all.

The signal these helpers render is the **data half** —
[`ThreadViewModel.usageLimit`](usage-limit-state.md) — a sibling `StateFlow<UsageLimitReading?>` beside
`apiRetry` / `isCompacting`. Neither helper does data access: each receives the already-decoded
`UsageLimitReading` as a parameter.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/UsageLimitIndicator.kt`).

## The copy is client-owned, picked by lookup, never drawn

A `rate_limited` frame is **not proof that a turn was blocked** — the one measured non-benign status
(`allowed_warning`) rode an account whose turns all ran normally. Through
[#1519](https://github.com/pyrycode/pyrycode-mobile/issues/1519) the pill ported desktop's
`usageLimitNotice` rule (`src/renderer/src/screens/conversation/usageLimitNotice.ts` in pyrycode-desktop):
every character the pill draws is **client-owned copy**, picked from a fixed set of string resources by
**exact-equality lookup** on claude's `status` and `limitType` — neither value is ever appended, logged or
drawn. "Usage limit reached" is reserved for `status == "rejected"` exactly; every other status, known or
not, reads "Nearly at usage limit", because over-claiming would tell an operator they are blocked while
their turns keep running. Only `limitType == "five_hour"` or `"seven_day"` names a window; any other value,
including an empty one, adds nothing. This is the render sibling that supersedes, *for this pill's copy
only*, the "no behaviour branches on `limit_type`" and "the label never branches on `status`" rules recorded
in [Usage-limit state § Security](usage-limit-state.md#security) and
[#804](https://github.com/pyrycode/pyrycode-mobile/issues/804) — see that section for the current wording
of both rules.

**#1519 also removed**: the percent spent, the "Claude reports usage-limit status: …" attribution, and the
conversation's agent name in the lead
([#1115](https://github.com/pyrycode/pyrycode-mobile/issues/1115)) — `usageLimitStatusLabel` and
`usageLimitSpentPercent` lost their only caller and are gone, and `ThreadTopOverlay` dropped its `agent`
parameter with them (see [Thread top overlay](thread-top-overlay.md)). The pill now reads identically for
every `ConversationAgent`.

## Shape

```kotlin
@Composable
internal fun usageLimitLabel(reading: UsageLimitReading): String

internal fun usageLimitText(
    reading: UsageLimitReading,
    now: Instant,
    timeZone: TimeZone,
    resources: Resources,
): String

internal fun usageLimitIsWarning(reading: UsageLimitReading): Boolean =
    reading.status == "allowed_warning"
```

`usageLimitText` is the whole copy rule as a plain (non-`@Composable`) pure function, so it can be pinned by
a Robolectric unit test against real string resources without composing anything (see
[Testing](#testing)). `usageLimitLabel` is the thin composable wrapper [`NoticePill`](notice-pill.md)'s
`text` is built from — no early return, since [`ThreadTopOverlay`](thread-top-overlay.md) already gates the
call on a non-`null`, non-dismissed reading. It captures `now` with `remember(reading) { Clock.System.now() }`
and delegates with `TimeZone.currentSystemDefault()` and `LocalResources.current`; there is no locale
parameter any more; see [Shape § reset formatting](#formatusagelimitreset) for why.

`usageLimitIsWarning` is unchanged by #1519: **exact** equality against `"allowed_warning"`, the one named
status besides upstream's benign `allowed`. Any other value — an unrecognised status, a case or whitespace
variant such as `"Allowed_Warning"` or `"allowed_warning "` — is **not** a warning, so the pill renders Error
with no dismiss X. This and the lead/window/reset lookups inside `usageLimitText` are the only branches on
`status`/`limitType` anywhere on the render path.

## `usageLimitText`'s three clauses

`usageLimitText` builds one string in a fixed order — lead, window, reset — appending each clause only when
it applies, never a separate render-or-decline helper per clause:

| Clause | Rule |
|---|---|
| Lead | `thread_usage_limit_reached` ("Usage limit reached") when `status == "rejected"` exactly, else `thread_usage_limit_nearly` ("Nearly at usage limit") |
| Window | `when (limitType)`: `"five_hour"` → `thread_usage_limit_five_hour`, `"seven_day"` → `thread_usage_limit_seven_day`, anything else (including `""`) → nothing |
| Reset | From `formatUsageLimitReset` (below); `thread_usage_limit_resets` (", resets %1$s") when the reset is `null`-dated (today), `thread_usage_limit_resets_on` (", resets %1$s at %2$s") otherwise, nothing when it declines |

`utilization` and `truncatedFields` are not read by `usageLimitText` at all — the percent-spent figure is
gone and claude's own truncation marker no longer has a caller.

### `formatUsageLimitReset`

```kotlin
internal data class UsageLimitReset(val date: String?, val time: String)

internal fun formatUsageLimitReset(resetsAt: Long, now: Instant, timeZone: TimeZone): UsageLimitReset?
```

The same range guard as before #1519 — `null` when `resetsAt <= 0` (0 = claude reported no reset, not the
epoch — tested first), when it is already past, or when it lies more than `MAX_RESET_HORIZON_SECONDS` (31
days) ahead, checked **before** any date is constructed so a year-40000 value never reaches a date
constructor. What changed is the output: instead of a localized SHORT time/date string, it returns a
`UsageLimitReset(date, time)` in a **fixed shape, zero-padded from the local date fields directly** —
`time` is always `HH:MM`, `date` is `DD.MM.YYYY` or `null` when the reset falls on today's local date —
matching desktop's `formatMessageTime` discipline rather than a `Locale`-dependent formatter. There is
accordingly no `Locale` parameter any more. "Today" is the local calendar date compared via
`toLocalDateTime(timeZone).date`, not a 24-hour difference, so a reset ten minutes past local midnight
already carries tomorrow's date even though less than a day has elapsed in UTC terms.

## Placement — the Top overlay, not the status ladder (post-#1002)

From #804 to #1002 this reading was a `ThreadStatusArea` arm, ranked between api-retry and resetting. It no
longer is: [#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002) moved it into
[`ThreadTopOverlay`](thread-top-overlay.md), a right-aligned stack of pills pinned over the top of the
message area, above `ThreadStatusArea`'s status ladder entirely — see [Thread screen § The arm
order](thread-screen-how-it-works-list-and-status-row.md#the-arm-order-1311) for the current ladder
(connection, resetting, api-retry, compacting, stall, turn outcome, then thinking/working/running tool —
[#1311](https://github.com/pyrycode/pyrycode-mobile/issues/1311) moved resetting above api-retry and added
the stall arm), which carries only live turn status. `ThreadTopOverlay` draws the usage pill above the
pairing-error pill when both are showing; see
that document for the overlay's own composition and the pairing pill it shares the stack with.

The reading is drawn as a [`NoticePill`](thread-top-overlay.md#the-usage-pill): Default with a dismiss X when
`usageLimitIsWarning(reading)`, Error with no X otherwise — so an unrecognised status is never hideable. The
arm is raised by a non-`null`, non-dismissed reading — the benign clear and the read-time expiry are
already applied upstream in
[`UsageLimitProjection`](usage-limit-state.md#the-clear-is-session-scoped--the-expiry-is-the-readings-second-way-down),
so nothing here or in the overlay re-derives that rule; `resetsAt` is read again only by
`formatUsageLimitReset` to build the reset clause, and `status`/`limitType` only as `usageLimitText`'s and
`usageLimitIsWarning`'s exact-equality lookup keys (see [Shape](#shape)). Dismissal is described below.

## Dismissal — `UsageLimitDismissals` (#1002)

The X on a warning pill hides that specific reading — see [Thread top overlay §
Dismissal](thread-top-overlay.md#dismissal--usagelimitdismissals) for the holder (`UsageLimitDismissals`,
an app-process `Koin single` keyed on `(status, limitType, resetsAt)`, no conversation and no host in the
key) and its wiring into `ThreadScreen`. The short version: hiding a reading in one thread hides the same
reading in every thread, because the usage limit belongs to the account, not the conversation; a change to
any of the three key fields is a new reading and the pill returns, while a changed `utilization` alone
keeps the same key and stays hidden. Nothing is persisted or logged.

## Wiring — a 30 s re-read ticker, not a timer from `resets_at`

`ThreadViewModel.usageLimit` is a sibling `StateFlow` beside `apiRetry` / `isCompacting`, but unlike them
it does not simply `.stateIn()` the repository flow:

```kotlin
val usageLimit: StateFlow<UsageLimitReading?> =
    usageLimitRereads()
        .flatMapLatest { repository.observeUsageLimit(conversationId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)
```

**Why a ticker is load-bearing here, unlike every sibling arm.**
[`UsageLimitProjection.isReadable`](usage-limit-state.md#the-clear-is-session-scoped--the-expiry-is-the-readings-second-way-down)
applies the expiry rule **only when read** — an already-subscribed collector gets no emission at the
`resets_at` deadline itself, because nothing pushes a frame at that moment. A ViewModel that only
`.stateIn()`'d the repository flow, the way `apiRetry`/`isCompacting` do, would keep an expired reading on
screen indefinitely, until some unrelated frame happened to re-subscribe it. `usageLimitRereads()` is a
private cold ticker (`internal const val USAGE_LIMIT_REREAD_MS = 30_000L`) emitting `Unit` immediately and
then every 30 s; each tick re-subscribes via `flatMapLatest`, which re-evaluates `isReadable` against the
current clock. A displayed reading therefore leaves within ≤30 s of its `resets_at` **without this layer
re-deriving the expiry rule**, and **with no delay ever computed from `resets_at`** — claude's unvalidated
number never becomes a scheduling input. `StateFlow` equality suppresses identical re-reads, so the ticks
do not recompose the screen while nothing has changed. The ticker runs only while the screen is subscribed
(`WhileSubscribed(5_000)`) and is cancelled with its collector.

`MainActivity` collects it via `vm.usageLimit.collectAsStateWithLifecycle()` beside `apiRetry` /
`isCompacting`, and `ThreadScreen` threads it as a defaulted `usageLimit: UsageLimitReading? = null`
parameter — every pre-#804 call site and preview keeps compiling unchanged. Unchanged by #1002: the VM
signal, the ticker and this parameter are exactly as before; only the arm that *consumed* `usageLimit`
inside `ThreadScreen` moved, from `ThreadStatusArea` to `ThreadTopOverlay`.

## Testing

- **Robolectric** `UsageLimitTextTest.kt` (new, #1519, `app/src/sharedTest/.../components/`) pins
  `usageLimitText` against real string resources with a fixed `now` in UTC: the exact text for
  `rejected`/`allowed_warning`/unknown status crossed with `five_hour`/`seven_day`/unknown/empty limit type,
  and for a reset later today, on another day, and `0` (`theLead_*`, `theWindow_*`, `theReset_*`). A hostile-
  and near-miss-values test feeds `<b>x</b>`, trailing/leading-space and case variants of both `status` and
  `limitType` (`rejected `, `Rejected`, `REJECTED`, `rejected_new`, `seven_day `, `SEVEN_DAY`, `five_hour\n`,
  `constructor`, …) and asserts the rendered text never contains the input and that every near miss takes
  the fallback arm — proving the lookups are exact `==`, with no trim, case fold, prefix or substring test.
- **JVM unit** `UsageLimitIndicatorFormatTest.kt` dropped its status- and percent-pass-through tests
  (`usageLimitStatusLabel`/`usageLimitSpentPercent` are gone) and keeps every `formatUsageLimitReset` range-
  guard case (zero/negative/past/absurd-future), now asserting the `UsageLimitReset(date, time)` shape —
  `UsageLimitReset(null, "15:30")` for later today, `UsageLimitReset("29.09.2026", "08:30")` for another day
  — plus a case in a non-UTC zone (`Europe/Helsinki`) pinning that "today" is compared by **local calendar
  date**, not a 24-hour difference: a reset ten minutes past local midnight already carries tomorrow's date.
- **JVM unit** `ThreadViewModelTest`'s `usageLimit_*` group: initial `null` with a plain fake; a reading
  then its clear; scoping to the VM's own conversation id; and the case the ticker exists for —
  `usageLimit_expiredReading_leavesOnTheNextReRead`, which flips a controllable fake's `expired` flag
  **without emitting**, asserts the value is unchanged immediately (no upstream push at the deadline), then
  `advanceTimeBy(USAGE_LIMIT_REREAD_MS)` and asserts it clears. **Uses `runCurrent`/`advanceTimeBy`, never
  `advanceUntilIdle`** — the ticker's `delay` is perpetual while the flow is collected, so
  `advanceUntilIdle` would chase it forever and the test would hang.
- **JVM unit** `UsageLimitDismissalsTest` (new, #1002): starts empty; `dismiss(reading)` adds its key;
  a changed `status`, `limitType` or `resetsAt` is a different key and is not dismissed; a changed
  `utilization` alone keeps the same key and stays dismissed; `usageLimitIsWarning` is `true` only for
  exactly `"allowed_warning"`.
- **Rung 2 scripted** `ScriptedUsageLimitTest`, updated by #1519 to the new copy (and by #1002 before it):
  still pushes through `ScriptedThreadHarness.pushRateLimited(status, limitType, resetsAt, utilization,
  truncatedFields)`, but the expected label is now built from the client-owned resources and no longer
  includes the spent figure; `reading_showsTheClientCopy_besideTheThinkingLabel` additionally asserts
  claude's raw `"allowed_warning"` status never appears in the tree. The three former mutual-exclusion
  cases (thinking, api-retry, compaction) still assert **both** the usage pill and the status-row signal
  render at once, since the reading no longer competes for the status slot.
- **`benignFrame_clearsTheArm`'s sync point** (#1010): `ScriptedThreadHarness` pumps frames on
  `Dispatchers.IO`, off the Compose clock, so `composeRule`'s idling does not wait for a pushed frame to
  fold. `awaitDisplayed(signal)` is only a real sync point if `signal` was **not already on screen** before
  the frame under test — otherwise it returns immediately and an absence assertion right after it can race
  the frame. The test pushed `turn_state thinking` *before* the benign `allowed` frame it was meant to
  gate on, so the wait was a no-op; it moved to push `thinking` *after* that frame instead, matching the
  shape `pastResetsAt_neverShows` already used. No production change: `UsageLimitProjection.apply` already
  cleared on any benign reading regardless of `limit_type`.
- **Robolectric** `ThreadTopOverlayTest` (`app/src/sharedTest/.../thread/`) — see [Thread top
  overlay § Testing](thread-top-overlay.md#testing) for the pill-rendering and dismissal cases, and for the
  status-row regression proof (AC #4 from #1002: a running tool, the resetting wrap-up phase and "Turn
  interrupted" each still render with a live `allowed_warning` reading, which fails against the pre-#1002
  ladder). `theUsagePill_readsTheSameForEveryAgent` (renamed by #1519 from
  `theUsagePill_namesTheConversationsAgent`) now asserts a Claude and a Codex conversation render the exact
  same text, and that neither "Codex" nor a "%" figure ever appears.
- **Design** `ThreadDesignCaptureTest`'s `threadStatusFramesAt412By892`/`compactAt320By700`, recaptured for
  #1519 against Figma `568:3139` — see [the design capture
  index](../../../app/src/androidTest/assets/design-1220/thread/index.md#task-count-pill--5683139-with-the-usage-limit-and-pairing-error-pills)
  for the comparison. "Nearly at usage limit - 7-day window" hugs its text at the right on one line at
  412x892, and wraps (not truncates) to two lines at 320x700/150%.
- No rung-3/4 scenario for the reading itself: live behaviour is
  [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679) per the original ticket, same posture as
  the sibling arms recorded in `docs/e2e-interactive-stream.md`. #1002's own live proof is indirect: the
  three `InteractiveStreamE2ETest` methods named in [Thread top overlay](thread-top-overlay.md) pass again
  under `python3 scripts/android-test-gate.py live` while the account's reading is `allowed_warning`,
  because the reading no longer occupies the status slot those methods assert against.

## Lessons learned

- **A JVM unit test formatting a localized `SHORT` time with `Locale.US` can fail on newer JDKs** — the
  platform inserts a narrow no-break space before AM/PM that a naive string-equality assertion does not
  expect. `UsageLimitIndicatorFormatTest` formats against `Locale.GERMANY` instead (24-hour, no AM/PM
  marker), the same fix [`SessionBoundaryDelimiterTest`](session-boundary-delimiter.md) already carries —
  pick a locale without an AM/PM marker for this class of test rather than special-casing the space
  character.
- **`strings.xml` values that must keep a leading space need an explicit quoted string** —
  `thread_usage_limit_five_hour` / `_seven_day` / `_resets` / `_resets_on` are declared as
  `" - 5-hour window"` / `" - 7-day window"` / `", resets %1$s"` / `", resets %1$s at %2$s"` (quotes
  included in the XML) so Android's resource parser does not collapse the leading space, which would glue
  the clause onto the preceding one with no separator.

## Security

Builder self-review **PASS** for #1519 (full pass recorded in the [architecture
plan](../../specs/architecture/1519-usage-limit-pill-client-copy.md#security-review)): `status` and
`limitType` enter `usageLimitText` only as `==`/`when` keys selecting a string-resource id — no branch ever
appends either value to the built text, so markup, bidi controls or newlines in them cannot reach the
`Text`, and the hostile-/near-miss-values test in [Testing](#testing) pins it. Exact equality (not a trim,
case fold, prefix or substring test) means a near-miss status such as `rejected_something_new` cannot pull
the pill into "Usage limit reached", the overclaim the two-lead design exists to avoid; a value the daemon
truncated to fit its 256-byte field cap cannot land on an exact key by accident either, since both keys are
under 10 characters. `resetsAt` is still claude's unvalidated number and `formatUsageLimitReset`'s range
guard still runs before any `Instant`/`LocalDateTime` is built — unchanged by #1519 beyond its return type.
`utilization` and `truncatedFields` are no longer read on this path at all, so the only remaining untrusted
inputs are `status` and `limitType`, now rendered as nothing but lookup keys. Nothing is logged — the
account's quota posture must not be. See [Usage-limit state §
Security](usage-limit-state.md#security) for the KDoc carrying the same rule on `UsageLimitReading` itself.

Before #1519: builder self-review **PASS** (full pass recorded in the [architecture
plan](../../specs/architecture/804-usage-limit-status-arm.md#security-review)). The only daemon-authored
text reaching Compose was `status`, crossing exactly one render boundary (`usageLimitStatusLabel`, since
removed) before landing as a `%1$s` argument of a `Text`.

**#1002's own review (PASS, full text in the [architecture doc](../../specs/architecture/1002-thread-top-overlay-pills.md#security-review)):**
a hostile daemon that sends `"allowed_warning"` gains nothing it could not already do with `"allowed"` — it
can already clear the reading. The one SHOULD-FIX raised was render-side, not trust-boundary: `NoticePill`
wraps its text instead of the old composable's `maxLines = 2`, so [`ThreadTopOverlay`](thread-top-overlay.md)
must keep building the pill's text only from `usageLimitLabel`, never from raw `status`/`limitType`. Since
\#1519 the bound is a fixed set of short client-owned clauses rather than a capped daemon string.
`UsageLimitDismissals` is heap-only (a Koin `single`, no `SavedStateHandle`, no `rememberSaveable`), so the
dismissal key — itself daemon-authored strings plus the account's quota posture — never enters
backup-eligible saved-instance state and is never logged.

## Related

- Upstream signal: [Usage-limit state](usage-limit-state.md) — `ThreadViewModel.usageLimit` /
  `observeUsageLimit`, the `rate_limited` decode this file's helpers render, and the read-time expiry rule
  the 30 s ticker exists to re-apply.
- Presentation (post-#1002): [Thread top overlay](thread-top-overlay.md) — the pinned pill stack that
  replaced the status-row arm, and its dismissal holder `UsageLimitDismissals`. [Notice pill](notice-pill.md)
  — the shared pill composable, Default/Error variants, that both this reading and the pairing-error notice
  render through.
- Host: [Thread screen](thread-screen.md) — still threads `usageLimit` as a flat sibling parameter, now
  consumed by `ThreadTopOverlay` rather than `ThreadStatusArea`.
- Idioms mirrored: [API-retry indicator](api-retry-indicator.md) and
  [Compacting indicator](compacting-indicator.md) (the row idiom, the early-return totality, the
  precedence-lives-in-the-screen posture) — this arm was the first in the family to render an icon rather
  than a spinner, since a report is not progress; that idiom now lives in `NoticePill`, not a status-row
  arm. [Thinking-progress state](thinking-progress-state.md) and [Thinking indicator](thinking-indicator.md)
  (the closest sibling in carrying daemon-originated numbers, though that arm carries no daemon text and
  needs no display cap). [Turn-outcome indicator](turn-outcome-indicator.md)
  ([#805](https://github.com/pyrycode/pyrycode-mobile/issues/805)) — the other arm to render an icon rather
  than a spinner, and the first to carry three independent daemon-authored strings through one sanitizer at
  once.
- Spec: `docs/specs/architecture/804-usage-limit-status-arm.md`,
  `docs/specs/architecture/1002-thread-top-overlay-pills.md` (the move to the Top overlay),
  `docs/specs/architecture/1519-usage-limit-pill-client-copy.md` (the client-owned copy rewrite).
- Server SSOT: `internal/protocol/interactive.go` (`RateLimitedPayload`), `docs/protocol-mobile.md §
  rate_limited` — cited, not restated.

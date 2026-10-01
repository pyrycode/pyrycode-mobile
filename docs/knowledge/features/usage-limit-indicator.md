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

## Wording is the whole risk here

A `rate_limited` frame is **not proof that a turn was blocked** — the one measured non-benign status
(`allowed_warning`) rode an account whose turns all ran normally. Every string this file emits is
therefore **attributed reportage**, never a verdict: the lead reads "<agent> reports usage-limit status:
…", naming the conversation's own agent ([#1115](https://github.com/pyrycode/pyrycode-mobile/issues/1115),
via [`agentName`](thread-top-overlay.md#the-usage-pill) — client-owned text picked from `ConversationAgent`,
never daemon text), and nothing anywhere in the copy says "limited", "blocked", "reached" or "lifted". `status` renders as
an opaque label credited to claude; no branch anywhere on the render path compares it against a value —
[#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002)'s `usageLimitIsWarning` is the sole
exception, and it only ever picks a pill variant, never wording (see below). `limitType` is not rendered at
all — the acceptance criteria do not require it, and leaving it out keeps the untrusted surface to one
string.

## Shape

```kotlin
@Composable
internal fun usageLimitLabel(reading: UsageLimitReading, agent: ConversationAgent): String

internal fun usageLimitIsWarning(reading: UsageLimitReading): Boolean =
    reading.status == "allowed_warning"
```

`agent` ([#1115](https://github.com/pyrycode/pyrycode-mobile/issues/1115)) is `ThreadUiState.agent` — see
[Data model § the agent name](data-model.md) — passed down from `ThreadTopOverlay`'s own `agent` parameter.
It only selects which client-owned name opens the lead string; it is never compared against `status` or any
other daemon-authored field.

`usageLimitLabel` is the composable [`NoticePill`](notice-pill.md)'s `text` is built from — no early
return, since [`ThreadTopOverlay`](thread-top-overlay.md) already gates the call on a non-`null`, non-
dismissed reading. `now` is captured with `remember(reading) { Clock.System.now() }`; time zone and locale
come from the system defaults at composition. `usageLimitIsWarning` is a plain (non-`@Composable`) pure
function — **exact** equality against `"allowed_warning"`, the one named status besides upstream's benign
`allowed`. Any other value — an unrecognised status, a case or whitespace variant such as `"Allowed_Warning"`
or `"allowed_warning "` — is **not** a warning, so the pill renders Error with no dismiss X. Nothing else on
the render path ever compares `status` to a value; this is the only branch, and it exists solely to choose
a pill variant.

## Three render-or-decline helpers, not a mapper

Three `internal` pure functions in `UsageLimitIndicator.kt`, each declining to `null` rather than
throwing, clamping or defaulting:

| Helper | Contract |
|---|---|
| `usageLimitStatusLabel(status, truncatedFields)` | Strips ISO-control and Unicode format characters (`Cf`, including bidi overrides that could visually reorder the client-owned clauses that follow) to spaces, trims, cuts to `MAX_STATUS_CHARS = 40`. An ellipsis marks a cut made here **or** one the daemon reported in `truncatedFields` — claude's cut text is never presented as complete. `null` when nothing printable is left. |
| `usageLimitSpentPercent(utilization)` | `null` when absent, non-finite, or outside `0.0..1.0`; otherwise `(utilization * 100).roundToInt()`. **An absent value is never rendered as `0`** — that would show a fresh window as an exhausted one — and an out-of-range value is declined, never clamped into range. |
| `formatUsageLimitReset(resetsAt, now, timeZone, locale)` | `null` when `resetsAt <= 0` (0 = claude reported no reset, not the epoch — tested first), when it is already past, or when it lies more than `MAX_RESET_HORIZON_SECONDS` (31 days) ahead — the range check runs **before** any date is constructed, so a year-40000 value never reaches a date constructor. Otherwise a localized SHORT time when the reset falls on today's local date, else a localized SHORT date + time. |

The label is assembled from three client-owned string resources (`thread_usage_limit_label` /
`_label_no_status`, `_spent`, `_resets`), appending the optional `· N% spent` / `· resets …` clauses only
when the matching helper is non-`null`.

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
so nothing here or in the overlay re-reads `resetsAt`/`status` beyond `usageLimitIsWarning`'s one
comparison. Dismissal is described below.

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

- **JVM unit** `UsageLimitIndicatorFormatTest.kt` pins the three render-or-decline helpers directly
  (status pass-through, control/bidi stripping, the 40-char cut, the daemon's own `truncatedFields`
  ellipsis; percent absent/in-range/out-of-range/non-finite; reset zero/negative/past/absurd-future/
  same-day/other-day). Untouched by #1002 — the helpers `usageLimitLabel` calls are the same three
  functions the old composable called.
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
- **Rung 2 scripted** `ScriptedUsageLimitTest`, updated by #1002: still pushes through
  `ScriptedThreadHarness.pushRateLimited(status, limitType, resetsAt, utilization, truncatedFields)`, but
  the three former mutual-exclusion cases (thinking, api-retry, compaction) now assert **both** the usage
  pill and the status-row signal render at once, since the reading no longer competes for the status slot.
  The rest (verbatim status, a benign `allowed` clearing it, the reset/percent edge cases) keep their prior
  assertions, found by content description rather than status-row position.
- **`benignFrame_clearsTheArm`'s sync point** (#1010): `ScriptedThreadHarness` pumps frames on
  `Dispatchers.IO`, off the Compose clock, so `composeRule`'s idling does not wait for a pushed frame to
  fold. `awaitDisplayed(signal)` is only a real sync point if `signal` was **not already on screen** before
  the frame under test — otherwise it returns immediately and an absence assertion right after it can race
  the frame. The test pushed `turn_state thinking` *before* the benign `allowed` frame it was meant to
  gate on, so the wait was a no-op; it moved to push `thinking` *after* that frame instead, matching the
  shape `pastResetsAt_neverShows` already used. No production change: `UsageLimitProjection.apply` already
  cleared on any benign reading regardless of `limit_type`.
- **Robolectric** `ThreadTopOverlayTest` (new, #1002, `app/src/sharedTest/.../thread/`) — see [Thread top
  overlay § Testing](thread-top-overlay.md#testing) for the pill-rendering and dismissal cases, and for the
  status-row regression proof (AC #4: a running tool, the resetting wrap-up phase and "Turn interrupted"
  each still render with a live `allowed_warning` reading, which fails against the pre-#1002 ladder).
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
  `thread_usage_limit_spent` / `_resets` are declared as `" · %1$d%% spent"` / `" · resets %1$s"` (quotes
  included in the XML) so Android's resource parser does not collapse the leading space, which would glue
  the optional clause onto the preceding one with no separator.

## Security

Builder self-review **PASS** (full pass recorded in the
[architecture plan](../../specs/architecture/804-usage-limit-status-arm.md#security-review)). The only
daemon-authored text reaching Compose is `status`, and it crosses exactly one render boundary
(`usageLimitStatusLabel`) before landing as a `%1$s` argument of a `Text` — never markup, a URL, an
attribute, a filename, a cache key or a log. `resets_at` and `utilization` are range-checked before any
date or figure is built from them, never clamped. Nothing branches on `status` beyond the helpers'
`null`/non-`null` result and `usageLimitIsWarning`'s exact-equality check, and nothing is logged — the
account's quota posture must not be.

**#1002's own review (PASS, full text in the [architecture doc](../../specs/architecture/1002-thread-top-overlay-pills.md#security-review)):**
a hostile daemon that sends `"allowed_warning"` gains nothing it could not already do with `"allowed"` — it
can already clear the reading. The one SHOULD-FIX raised was render-side, not trust-boundary: `NoticePill`
wraps its text instead of the old composable's `maxLines = 2`, so [`ThreadTopOverlay`](thread-top-overlay.md)
must keep building the pill's text only from `usageLimitLabel`, never from raw `status`/`limitType` — the
bound stays the label's own (40 status characters plus two short client-owned clauses). `UsageLimitDismissals`
is heap-only (a Koin `single`, no `SavedStateHandle`, no `rememberSaveable`), so the dismissal key — itself
daemon-authored strings plus the account's quota posture — never enters backup-eligible saved-instance state
and is never logged.

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
  `docs/specs/architecture/1002-thread-top-overlay-pills.md` (the move to the Top overlay).
- Server SSOT: `internal/protocol/interactive.go` (`RateLimitedPayload`), `docs/protocol-mobile.md §
  rate_limited` — cited, not restated.

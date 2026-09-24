# #1002 — Notices move to a Top overlay of pills above the messages

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, `ThreadStatusArea`, `RePairButton` — the status ladder that loses its usage arm and its Re-pair slot, and the content `Column` whose message area gains the overlay.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/UsageLimitIndicator.kt` → `UsageLimitIndicator`, `usageLimitStatusLabel`, `usageLimitSpentPercent`, `formatUsageLimitReset` — the label and its three render-or-decline helpers, which the usage pill reuses unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `UsageLimitReading` — `status` / `limitType` / `resetsAt` form the dismissal key.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerDraftStore.kt` → `ComposerDraftStore` — the app-process in-memory store idiom (a plain class, a Koin `single`, a `MutableStateFlow` written with `update`) the dismissal holder mirrors.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `single { ComposerDraftStore() }` binding the new holder sits beside.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `CONVERSATION_THREAD` destination's `ThreadScreen` call — where the singleton is injected and bound.
- `app/src/sharedTest/.../thread/ScriptedUsageLimitTest.kt`, `ScriptedThreadHarness.kt`, `ThreadScreenRePairTest.kt`, `RunningToolIndicatorTest.kt` — existing proofs over these surfaces; the usage tests' mutual-exclusion claims invert under this ticket.
- `docs/knowledge/features/usage-limit-indicator.md` § "Wording is the whole risk here", § Security — the label is attributed reportage; `status` is opaque and nothing logs the reading. Both carry over to the pill.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`, `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`, `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` — masked today by the usage arm; they drive `MainActivity`, so they get the Koin-bound holder.

No in-flight feature branch touches these files (A2 check run 2026-09-24).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=533-1956 (message area with `Top overlay` 541:2446) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=347-6617 (Pill component set).

The `Top overlay` is an absolutely positioned column pinned to the top of the message area (left 0 / right 0 / top 0), `items-end`, 12dp gap, over a drop shadow (0, 6, blur 4, 60% black); messages scroll beneath it. Each `Pill` is a 6dp-radius row, 8dp horizontal / 4dp vertical padding, 8dp gap, hugging a right-aligned `M3/body/small` label that wraps at the available width. **Default**: `Schemes/Primary Container` fill, `Schemes/On Primary Container` label, trailing 8dp X glyph. **Error**: `Schemes/Error Container` fill, `Schemes/Error` label, no X. Usage pill above the pairing pill.

## Context

Since 2026-09-24 claude attaches an `allowed_warning` usage report to every turn. The status row's ladder ranks the usage arm above resetting, compaction, turn outcome and thinking/running tool, so those never render, and three live e2e methods fail on every branch. Notices (the usage report and the pairing error) move out of the status row into a pinned overlay of pills; the status row keeps only live turn status: api-retry → resetting → compacting → turn outcome → thinking/running tool.

## Design

### `NoticePill` — `ui/conversations/components/NoticePill.kt` (new)

```kotlin
@Composable
internal fun NoticePill(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String = text,
    onClick: (() -> Unit)? = null,     // whole pill clickable (the pairing pill)
    onDismiss: (() -> Unit)? = null,   // non-null → trailing X
)
```

A `Surface` (6dp `RoundedCornerShape`, shadow elevation for the overlay's drop shadow; `onClick` overload when `onClick != null`) holding a `Row` (8/4 padding, 8dp gap, centre-aligned). Colours from `MaterialTheme.colorScheme`: `primaryContainer`/`onPrimaryContainer` for Default, `errorContainer`/`error` for Error, as Figma paints them. The `Text` is `bodySmall`, `TextAlign.End`, in `Modifier.weight(1f, fill = false)` so it hugs short text and wraps long text while the X stays visible. Semantics: `mergeDescendants` with `contentDescription` on the pill; the X is its own clickable `Icon(Icons.Filled.Close)` node with a local `contentDescription` (`thread_notice_dismiss`), `Role.Button`. Its layout stays small to match Figma; Compose's minimum-touch-target hit expansion gives it the 48dp tap area without growing the pill.

### `ThreadTopOverlay` — `ui/conversations/thread/ThreadTopOverlay.kt` (new)

```kotlin
@Composable
internal fun ThreadTopOverlay(
    usageLimit: UsageLimitReading?,
    usageLimitDismissed: Boolean,
    onDismissUsageLimit: () -> Unit,
    showRePair: Boolean,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Emits nothing when there is no pill to show (no reading or a dismissed one, and no re-pair). Otherwise a `Column(horizontalAlignment = End, spacedBy(12.dp))`:

1. **Usage pill** when `usageLimit != null && !usageLimitDismissed`: text from `usageLimitLabel(usageLimit)`; `isError = !usageLimitIsWarning(usageLimit)`; `onDismiss = onDismissUsageLimit` only when it is a warning.
2. **Pairing pill** when `showRePair`: `thread_re_pair` text, `isError = true`, `onClick = onRePair`.

### `UsageLimitIndicator.kt` (modified)

The status-row `UsageLimitIndicator` composable and its previews are removed (its only caller is `ThreadStatusArea`). Added:

- `@Composable internal fun usageLimitLabel(reading: UsageLimitReading): String` — exactly the label the removed composable built (attributed lead, spent clause, reset clause) through the same three helpers.
- `internal fun usageLimitIsWarning(reading: UsageLimitReading): Boolean = reading.status == "allowed_warning"` — the **one** named status besides upstream's `allowed`, used only to pick the dismissible pill. Exact equality: `"Allowed_Warning"`, `"allowed_warning "` or any other value is an Error pill with no X.

### `UsageLimitDismissals` — `ui/conversations/thread/UsageLimitDismissals.kt` (new)

```kotlin
class UsageLimitDismissals {
    val dismissed: StateFlow<Set<Key>>
    fun dismiss(reading: UsageLimitReading)
    data class Key(val status: String, val limitType: String, val resetsAt: Long)
}
internal fun UsageLimitReading.dismissalKey(): UsageLimitDismissals.Key
```

App-process heap only, a Koin `single` beside `ComposerDraftStore`. No conversation or host in the key: dismissing a reading hides that same reading in every thread. A change of `status`, `limitType` or `resetsAt` yields a new key, so the pill returns; a changed `utilization` alone does not. `dismiss` writes with `update { it + key }`. Not persisted, not logged.

### `ThreadScreen` (modified)

- New defaulted parameters: `dismissedUsageLimits: Set<UsageLimitDismissals.Key> = emptySet()` and `onDismissUsageLimit: (UsageLimitReading) -> Unit = {}`. The screen stays stateless; it computes `usageLimitDismissed = usageLimit?.dismissalKey() in dismissedUsageLimits` and binds the X to the reading it is showing.
- The message area (either `EmptyThreadState` or the `LazyColumn`) is wrapped in a `Box(Modifier.fillMaxWidth().weight(1f))`; the child takes `fillMaxSize`, and `ThreadTopOverlay` is drawn after it, `Alignment.TopEnd`, padded horizontally by `ComposerGutter` and 8dp from the top. The overlay is an overlap, so it never takes layout space.
- `ThreadStatusArea` loses `usageLimit`, `showRePair` and `onRePair`; its `Row` + `RePairButton` branch and the `StatusActionGap` / `RePairButton*` constants go. KDoc ladder rewritten: api-retry → resetting → compacting → turn outcome → thinking/running tool, with a line saying notices live in the Top overlay.
- `ConnectionBanner` stays suppressed while `showRePair` is true.

### `MainActivity` / `AppModule`

`single { UsageLimitDismissals() }`. The thread destination injects it (`koinInject`), collects `dismissed` with `collectAsStateWithLifecycle`, and passes `dismissedUsageLimits` plus `onDismissUsageLimit = dismissals::dismiss`.

### Strings

`thread_notice_dismiss` ("Dismiss notice") for the X's content description. The existing usage-limit strings and `thread_re_pair` are reused.

## State + concurrency model

No coroutine is launched. `UsageLimitDismissals` holds one `MutableStateFlow<Set<Key>>`, mutated with `update` (compare-and-set) on the main thread from a click. The screen reads it through `collectAsStateWithLifecycle` in `MainActivity`; the harness and screen tests pass a plain set. The usage reading's flow, its 30 s re-read ticker and `rePairAvailable` are untouched.

## Error handling

No new failure mode. Every untrusted reading field still crosses only the three render-or-decline helpers; a reading whose status is unrecognised renders as an Error pill with no X, so nothing unknown can be hidden. The dismissal key compares raw values for equality only; nothing parses them.

## Testing strategy

- **JVM unit** `UsageLimitDismissalsTest` (new, `app/src/test/.../thread/`): initially empty; dismissing adds the reading's key; a changed status, limit type or reset time is not dismissed; a changed utilization is still dismissed; `usageLimitIsWarning` is true only for exactly `allowed_warning`.
- **Robolectric** `ThreadTopOverlayTest` (new, `app/src/sharedTest/.../thread/`) over `ThreadScreen`:
  - no reading and no re-pair → no pill nodes, no dismiss X;
  - `allowed_warning` → pill with the label, an X; tapping X (with a test-held set wired to `onDismissUsageLimit`) hides it; a new `resetsAt` shows it again;
  - `rejected` and an unknown status → pill shown, no X;
  - usage pill bounds sit above the pairing pill's; tapping the pairing pill fires `onRePair` once;
  - **AC 4** — with an `allowed_warning` reading live: "Running Bash…" renders in the status row (busy + open tool row); the wrapping-up reset label renders; "Turn interrupted" renders. Written first; fails against the current ladder.
- **Updated** `ScriptedUsageLimitTest`: the three mutual-exclusion tests (thinking, api-retry, compaction) now assert both signals display at once; others keep their assertions (label by content description). The harness passes nothing new — defaults are an empty dismissal set.
- **Kept** `ThreadScreenRePairTest` unchanged: it finds the pill by its text, clicks it, and checks the banner is withheld.
- **Rung 3**: no new scenario. The three existing live methods named in Context are the acceptance proof under `python3 scripts/android-test-gate.py live` (dispatcher-run, `needs-real-claude`). The dismiss affordance has no rung-4 twin: a scripted `rate_limited` frame renders the same pill the Robolectric tests already cover.

## Open questions

- Top padding of the overlay: Figma pins it at the message area's top edge; the implementation uses 8dp so the first pill does not touch the app bar. Revisit if the screenshot comparison disagrees.

## Documentation handoff

Pending for the documentation stage: update the status ladder described in `docs/knowledge/features/resetting-indicator.md` and `docs/knowledge/features/thinking-indicator.md`, and describe the pill surface and its dismissal in `docs/knowledge/features/usage-limit-indicator.md`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The daemon-authored fields reach Compose only through `usageLimitLabel`, which keeps the three render-or-decline helpers (`usageLimitStatusLabel` strips control and bidi characters and cuts at 40 characters; percent and reset are range-checked before use). The pill renders the result as `Text` only. The one new branch on `status` is exact equality in `usageLimitIsWarning`, and its only effect is to add an X; every other value, including an unrecognised one, is an Error pill that cannot be hidden. A hostile daemon that says `allowed_warning` gains nothing it lacks today: it can already clear the reading with `allowed`.
- [Trust boundaries] SHOULD FIX — the pill text now wraps instead of the old `maxLines = 2`. The bound is the label's own: at most 40 status characters plus two short client-owned clauses. Phase B must build the pill's text only from `usageLimitLabel`, never from raw `status` or `limitType`.
- [Tokens, secrets] No findings. The ticket stores no credential; the re-pair tap navigates to the existing code-pair route exactly as `onRePair` does today.
- [File / storage] No findings. `UsageLimitDismissals` is heap in a Koin `single`: no disk, no `SavedStateHandle`, no `rememberSaveable`, so nothing enters the backup-eligible saved-instance bundle. It does not survive a process restart, by design.
- [Storage growth] No findings. The key set grows by one entry per tap on the X, and only warning readings get an X, so growth is bounded by user action, not by daemon frames.
- [Android attack surface] No findings. No intent, deep link, pending intent, provider or WebView is added. The overlay covers the top of the message list; a tap there reaches the pill, and the only pill actions are hiding a notice or opening the existing re-pair screen the user already had one tap away.
- [Crypto] No findings — not touched.
- [Network & I/O] No findings — no send path is added; dismissal is local and tells the daemon nothing.
- [Logs] No findings. Nothing is logged: the account's quota posture and the key's daemon-authored strings must not reach Logcat, an exception message or a crash report. Phase B adds no log call.
- [Concurrency] No findings. No coroutine is launched. `dismiss` writes with `MutableStateFlow.update`; the flow is deliberately shared across thread screens, since hiding a reading in one thread hides it in all.
- [Threat model] OUT OF SCOPE — screenshot and accessibility-service exposure of the usage label is unchanged from #804 (the same text was already on screen) and belongs to no open ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

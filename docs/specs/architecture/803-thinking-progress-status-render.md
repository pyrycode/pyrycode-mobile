# #803 — Show claude's thinking-token reading in the thread status area

The render half of #801's `thinking_progress` decode: the thread's thinking arm stops being a bare
indefinite spinner and carries claude's live token reading, so a three-minute turn reads as *working*
rather than *wedged*. Split from #653; the data slice is #801, already merged.

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` | `ThinkingIndicator` | The arm this slice extends; its early-return / merged-`semantics` / spacing-`val` idiom is the template. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ApiRetryIndicator.kt` | `ApiRetryIndicator`, `isRenderableCounter` | The only sibling that renders a daemon-supplied **number**. Its render-or-decline gate and its "no `remember`-cached label, no `derivedStateOf`" rule are both load-bearing here. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` | `ThreadScreen`, `ThreadStatusArea` | The one status slot and its three-way `when`. This slice adds a parameter and no arm. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` | `isCompacting`, `apiRetry`, `isThinking` | The sibling-`StateFlow` hoist posture the new flow clones verbatim. |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt` | the `CONVERSATION_THREAD` destination | Collects each sibling flow and passes it in; two lines to add. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` | `ThinkingProgress`, `observeThinkingProgress` | The element type and the defaulted seam #801 landed. Its KDoc owns the carry-verbatim / non-monotonic rules. |
| `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` | `observeThinkingProgress` | The facade's `switchToLive` is what makes the reading host-scoped and drops it across a reconnect — AC #1's "and its host" needs no code of its own. |
| `docs/knowledge/features/thinking-progress-state.md` | whole | #801's overview. Names the render-side obligations this slice inherits: must not block interaction on the reading, must tolerate a long-lived one, and owns the UI-leakage half of the security posture. |
| `docs/knowledge/features/compacting-indicator.md` | whole | The nearest analogue (#597) and the shape the estimate was taken against. |
| `docs/knowledge/features/thinking-indicator.md` | whole | The arm's own overview — records that its visual is design-owed and why it uses `stringResource` rather than Kotlin literals. |
| `app/src/androidTest/.../ScriptedCompactingTest.kt`, `ScriptedThreadHarness.kt` | `pushCompacting`, `compactingEnvelope`, `start` | The rung-2 shape AC #4 names, and the two seams a `pushThinkingProgress` must clone. |
| `app/src/test/.../ThreadViewModelTest.kt` | `CompactingControllableRepo`, the `isCompacting_*` trio | The delegating-double idiom and the three-test shape the new hoist mirrors. |
| `../pyrycode/docs/protocol-mobile.md` | § `thinking_progress` | The frame contract. Cited, not restated — its five measured consumer hazards are what the wording rules below exist to respect. |

Codegraph note: `codegraph_context` on this task returned unrelated settings-ViewModel tests and no thread
symbols, so the reading list above came from `grep` + the feature overviews. The index looks stale for
`ui/conversations/`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 — `Status area` (`111:3525`).

A 741×32 band: a left-aligned `Status` group (`112:3530`) holding a 14×16 vector glyph, an 8dp gap and a
`M3/body/small` label reading "Thinking..." in `Schemes/Primary`, with 8dp trailing and 4dp vertical
padding; a `Button small` instance sits at the band's right edge as the contextual-action slot (#675's,
still unfilled). This slice changes **only that label's text**. The band's layout, the slot arbitration
and the trailing gap are untouched.

Two **pre-existing** divergences between the drawn frame and the shipped arm are noted, not altered
(they predate this ticket, shipped with #407/#643): the shipped arm draws an indeterminate
`CircularProgressIndicator` rather than the frame's static vector glyph, and colours its label
`onSurfaceVariant` rather than the frame's `Schemes/Primary`. Both belong to the design-owed follow-up
already recorded in `thinking-indicator.md` § Edge cases and in `compacting-indicator.md`; changing
either here would be adjacent-code refactoring in a slice whose ticket says it changes the arm's content
and not the band. Typography already matches — the shipped `bodySmall` **is** `M3/body/small`.

## Context

`ThinkingIndicator` renders a fixed "Thinking…" label beside an indefinite spinner. After three minutes
that is indistinguishable from a hang, which is the exact gap #1386 opened the wire frame to close:
`thinking_progress` is claude's **only** mid-turn proof of life on the stream-json surface. #801 decoded
it to `ThreadViewModel`-reachable state and rendered nothing. This slice renders it.

No ADR is warranted: the arm, the hoist posture and the precedence rule are all established; this adds a
parameter and a second label variant to an existing component.

## Design

### The wording, and what it may not claim

The two render-boundary rules come from the frame contract, not from taste:

1. The reading is cumulative within **one inference request**, not within a turn, and restarts near zero
   at every request boundary — the committed capture's single turn contains four restarts (5→184, 4→167,
   3→126, 1→197). So the label may not present the number as a turn total.
2. The per-line deltas a client receives **do not sum** to the turn's total (674 arrived as 243 across 8
   frames) and no field reports the residue. So nothing may be accumulated, and no denominator exists —
   no "N of M", no percentage, no bar.

Chosen strings, both plain local resources with an integer argument and no daemon-authored text:

| Name | Value |
|---|---|
| `thread_thinking_progress_label` | `Thinking… ~%1$d tokens this step` |
| `cd_thread_thinking_progress` | `Claude is thinking, about %1$d tokens into its current reasoning step` |

`this step` is the load-bearing half of the visible string: it scopes the count to the current inference
request, which is the one thing a bare "~184 tokens" beside "Thinking…" would let a reader silently
misread as a turn total. `~` marks it as claude's estimate. There is no "of", no "%", and no second
number anywhere in either string, so neither can grow a denominator by later edit. `estimatedTokensDelta`
is **not rendered at all** — it is a rate reading, and putting it on screen is the most direct invitation
to the summing error rule 2 forbids.

### `ThinkingIndicator` — one Row, one spinner, two label variants

```kotlin
@Composable
fun ThinkingIndicator(
    isThinking: Boolean,
    modifier: Modifier = Modifier,
    progress: ThinkingProgress? = null,
)
```

`progress` is optional and lands after `modifier` (Compose lint `ComposeParameterOrder`, #508), exactly
where `isCompacting` sits on `ThreadScreen`. `null` = no reading, and renders today's label verbatim.

**Single call site for the spinner is the anti-flicker mechanism and the one real trap of this slice.**
Writing the natural `if (progress != null) Row { Spinner; Text(a) } else Row { Spinner; Text(b) }` gives
Compose two distinct groups: the first reading to arrive disposes the `CircularProgressIndicator` and
composes a new one, restarting its rotation — a visible hitch precisely at the moment AC #2 cares about.
So the composable keeps **one** `Row` containing **one** `CircularProgressIndicator`, and only the
`Text`'s `text` argument and the row's `contentDescription` vary. That is also `ApiRetryIndicator`'s
structure, for the same reason.

No `remember`-cached label, no `derivedStateOf`, no local state: a cached label would freeze a changing
reading, the inverse of the point (the #593/#594 rule, restated in `ApiRetryIndicator`'s KDoc).

### Display sanity gate — render-or-decline, never a clamp

A file-private extension mirroring `ApiRetryStatus.Attempt.isRenderableCounter`:

```kotlin
private const val MAX_PLAUSIBLE_THINKING_TOKENS = 1_000_000L
private fun ThinkingProgress.isRenderableReading(): Boolean =
    estimatedTokens in 0..MAX_PLAUSIBLE_THINKING_TOKENS
```

A reading outside the band renders the **plain** "Thinking…" arm — the less-specific-but-true form — and
is never rewritten. This is layout safety at the render boundary, not a second decoder: #801 deliberately
declined a lower-bound rejection at decode, on the carry-verbatim rule every sibling mapper follows, so
the number reaching Compose can legally be negative or arbitrarily large. A negative token count is
meaningless to a reader, and a 19-digit `Long.MAX_VALUE` would push the band past the contextual-action
slot. Declining both is the same render-or-decline posture #594 shipped, and it keeps the carry-verbatim
rule intact because nothing upstream changes.

The ceiling is picked against a real quantity rather than a round guess: the largest documented
extended-thinking budget for one request is ~64k tokens, so 1,000,000 leaves better than an order of
magnitude of headroom while capping the rendered number at seven digits. Exceeding it is not an error.

### `ThreadStatusArea` — the arm changes, the arbitration does not

`ThreadScreen` gains `thinkingProgress: ThinkingProgress? = null` in its trailing-defaults block and
hands it to `ThreadStatusArea`, which passes it to the existing `else` arm:

```kotlin
else -> ThinkingIndicator(isThinking = isThinking, modifier = slot, progress = thinkingProgress)
```

The `when` keeps its order and its two guards, so AC #3 holds structurally: a live reading cannot reach
the screen while api-retry or compaction is live, because the reading rides the arm those two already
pre-empt. No new arm, nothing to stack.

**Visibility stays governed by `isThinking` alone.** A reading does not independently raise the arm. The
ticket scopes this slice to extending the existing arm, `turn_state` owns the thinking phase (#406), and
making a reading a second showing condition would be a new arm wearing the old one's name. Recorded as a
resolved open question below.

### `ThreadViewModel` — a fourth sibling hoist

```kotlin
val thinkingProgress: StateFlow<ThinkingProgress?> =
    repository
        .observeThinkingProgress(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)
```

A verbatim clone of `isCompacting`'s hoist over the already-injected repository — no constructor, DI or
interface change. `null` covers no live connection, no `interactive` capability, and no frame yet.
AC #1's "and its host" needs no code: `StableConversationRepository.observeThinkingProgress` already
routes through `switchToLive`, whose `flatMapLatest` drops the previous connection's projection when the
host changes.

`MainActivity` collects it beside `isCompacting` and passes it through — two lines.

## State + concurrency model

One flow, no new jobs. `stateIn(viewModelScope, WhileSubscribed(5_000), null)` — the scope cancels with
the `ViewModel`, and the 5s grace matches every sibling so a rotation does not re-subscribe. The upstream
is cold (`RemoteConversationRepository.observeThinkingProgress` is a `.map { it[id] }.distinctUntilChanged()`
over a retained `MutableStateFlow`), so there is no subscribe-before-push hazard and no replay to miss.
No dispatcher choice: the projection does no work off the collector.

**The upstream `distinctUntilChanged` is load-bearing here, and no further operator may be added.** It is
what makes AC #2's "a repeated reading holds without flicker" true — an identical `ThinkingProgress` never
re-emits, so the label is not rewritten. Because `ThinkingProgress` is a `data class`, a *falling* reading
is a different value and does reach the screen, which is the other half of AC #2. Adding a second dedup,
a `derivedStateOf`, or any max-guard would break one half or the other.

Background close: `LifecycleConnectionDriver` closes the relay on background; the facade drops the
projection and the flow reads `null` on return, which renders today's arm. That is correct — a held
reading would report the depth of a think that has since finished.

## Error handling

No new failure modes. Nothing here parses, does I/O, or suspends; every input is an already-decoded
`ThinkingProgress?`. The two non-happy inputs are `null` (render today's arm) and an implausible reading
(render today's arm, per the gate above). Neither throws, neither logs, and neither can block
interaction — the composer, the interrupt control and `StallPromotionBanner` are all outside this slot,
so a stuck or hostile reading cannot trap the operator. Nothing on this path is logged at all, so no
reading, conversation id or host reaches Logcat.

## Testing strategy

Failing-test-first on each layer.

**Unit — `ThreadViewModelTest` (`./gradlew testDebugUnitTest`).** A `ThinkingProgressControllableRepo`
delegating double in `CompactingControllableRepo`'s shape, then three cases mirroring the `isCompacting`
trio:

- initial value is `null` against a plain `FakeConversationRepository` (AC #1, second half — the fake
  inherits the interface's `flowOf(null)` default and needs no override).
- a rising reading, then a **falling** one, then an equal repeat: the flow reports each new value and is
  never clamped upward (AC #2, and the non-monotonic contract). Pushes are interleaved with
  `advanceUntilIdle()` — a `StateFlow` projection conflates two pushes drained in one turn, which is the
  measured trap recorded in `thinking-progress-state.md` § Test idiom.
- the hoist observes only its own `conversationId` (AC #1 scoping).

**Compose UI — `ThinkingIndicatorTest` (`app/src/androidTest/`).** Two cases the scripted harness cannot
reach as directly, driven on the composable's own parameter:

- an implausible reading (negative, and `Long.MAX_VALUE`) declines to the plain `cd_thread_thinking` arm
  rather than rendering a number.
- a plausible reading renders `cd_thread_thinking_progress` and the plain description is gone.

**Scripted regression — `ScriptedThinkingProgressTest` (AC #4).** New file in `ScriptedCompactingTest`'s
shape on `ScriptedThreadHarness`, driven through the real `RemoteConversationRepository` fold. Needs a
`pushThinkingProgress(estimatedTokens, estimatedTokensDelta)` helper plus a `thinkingProgressEnvelope`
cloning `compactingEnvelope`, and one added argument in `start()`. Cases:

- a reading decorates the thinking arm; the plain description is gone (AC #1).
- a falling reading updates, then an identical repeat holds (AC #2).
- api-retry beats a live reading, and compaction beats a live reading — asserting the losing description
  `assertDoesNotExist` in both, which is the mutual-exclusion claim (AC #3).
- a conversation that never receives a frame renders exactly as today (AC #1, second half).

Assertions are on resolved `cd_*` strings via `onNodeWithContentDescription` with tolerant `waitUntil`
timeouts — never counts or timing, the ladder-doc rule.

**No rung-3 or rung-4 scenario.** Live behaviour is #679's, named by AC #4 itself. Not an operator-facing
flow gap: this slice ships no new interaction, only a label variant on a shipped affordance.

The anti-flicker property is guaranteed **structurally** (one `Row`, one spinner call site) rather than by
assertion — Compose's test API cannot assert node identity across a recomposition, and a test that
claimed to would be proving something weaker than it reads. The KDoc carries the reason so the structure
is not "simplified" later.

## Documentation handoff

The ticket body carries no **Documentation handoff** section and no documentation-only acceptance
criterion. Nothing is owed beyond the documentation stage's normal work, which for this slice is: a new
`docs/knowledge/features/` overview for the rendered arm (sibling to `compacting-indicator.md`, and the
render-half counterpart to the existing `thinking-progress-state.md`), a link from
`thinking-progress-state.md`'s "Rendering the reading in the status area is a **sibling slice** … not yet
filed" line, which this ticket resolves, and a `Coverage:` line in `docs/e2e-interactive-stream.md` for
the new rung-2 scenario. **Pending — documentation stage.** No shared doc is edited by this ticket.

## Open questions

1. **Does a live reading raise the arm when `turn_state` is not `thinking`?** Resolved: **no** — see
   § `ThreadStatusArea`. Visibility stays `isThinking`'s.
2. **Is the content description varied, or held stable?** Resolved: **varied**, so a TalkBack user gets
   the reading a sighted user gets. The plain `cd_thread_thinking` remains the description whenever there
   is no renderable reading, which is what keeps AC #1's "renders exactly as it does today" literal. No
   `liveRegion` is added — its absence is a pre-existing, family-wide gap already folded into the
   design-owed a11y follow-up, and adding it on one of four affordances would be inconsistent.
3. **Grouping separators in the rendered number?** Resolved: **no** — `%1$d` through `stringResource`
   formats with the configuration locale (so locale-specific digits come free) but inserts no separators.
   At the measured magnitudes (1–~200) separators are moot, and at the gate's ceiling the number stays
   seven digits. Noted as a limitation for the overview rather than solved with a locale-keyed formatter
   whose `remember` would risk freezing the label.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No new boundary. The untrusted→trusted crossing is `decodeThinkingProgress` in
  `RemoteConversationRepository`, audited and shipped by #801; this slice consumes the already-decoded
  `ThinkingProgress`, whose two fields are both `Long`. **No daemon-authored string exists on this path
  at all** — the routing `conversation_id` stays a map key in the repository projection and never reaches
  the value type — so the "new inbound verb carrying text into Compose" hazard is structurally absent,
  not merely handled. Nothing is rendered as markup, a URL, a filename or a log line; both strings are
  local resources taking an integer argument. The one genuinely untrusted quantity is the integer's
  *magnitude*, and § Display sanity gate is the explicit, single-symbol boundary for it.
- **[Tokens, secrets, credentials]** Not applicable — this slice reads no token, key or credential, and
  writes no storage of any kind. `EncryptedSharedPreferences` and the Keystore stores under `data/crypto/`
  are untouched.
- **[File / storage operations]** Not applicable — no filesystem access, no persistence, no cache write.
  The reading is in-memory and connection-scoped; nothing survives process death, so no atomic-write or
  `allowBackup` question arises.
- **[Inter-process / Android attack surface]** Not applicable — no new `Activity`/`Service`/`Receiver`,
  no `intent-filter`, no `PendingIntent`, no `ContentProvider`, no WebView. The one Compose surface is an
  existing non-exported screen.
- **[Cryptographic primitives]** Not applicable — no RNG, no hashing, no comparison against a secret, and
  no contact with the `Noise_IK` handshake or its nonce schedule.
- **[Network & I/O]** Not applicable — this slice opens no socket and changes no `OkHttpClient`,
  timeout, TLS or backoff setting. It is a pure consumer of a projection over the existing single inbound
  collector, so it adds no frame-size, relay-URL or reconnect surface.
- **[Error messages, logs, telemetry]** No findings, and deliberately: **nothing on this path logs**, so
  no reading, `conversation_id` or host reaches Logcat in debug or release. Adding a per-frame log here
  would be a cross-conversation correlation leak — the rule #801 already applies at the decode arm, and
  the reason a "log the reading to debug the render" instinct must be refused in Phase B.
- **[Concurrency]** No findings. One `stateIn` over `viewModelScope`, cancelled with the `ViewModel`; no
  new coroutine, no mutex, no check-then-act on shared state, no `NonCancellable`. The flow is cold
  upstream and shared only within its own `ViewModel`, so no data crosses screens. Mid-write process
  death is not reachable — there is no write.
- **[Threat model alignment]** Three named:
  - *Hostile relay* — content-blind and on-path; it can drop, delay or reorder. Dropping or delaying a
    `thinking_progress` frame degrades to the plain "Thinking…" arm, which is the honest rendering, and
    **no stall, failure or error presentation is reachable from silence** — AC #2's rule and the wire
    contract's point 5. Reordering can deliver a lower reading after a higher one, which is
    indistinguishable from the genuine restart behaviour and is carried verbatim by design.
  - *Hostile or crashed daemon* — can pin a reading indefinitely by sending one frame and no clearing
    event, and can send an absurd magnitude. The first is inherited, deliberately undefended (a
    client-side timeout is exactly the "infer something from a gap" the contract forbids) and bounded by
    this slice's structural mitigation: the arm occupies one slot inside the composer's top band, and the
    interrupt control, the composer and `StallPromotionBanner` all stay live beside it, so the operator
    is never trapped. The second is the display sanity gate. Flood is O(1) in memory upstream (the
    projection is a keyed replace) and O(1) here (one recomposition of one `Text`).
  - *UI-side leakage* — the arm discloses that a conversation is actively reasoning, and now roughly how
    deep, to a screenshot, an overlay or an accessibility service. This is the render-side half
    `thinking-progress-state.md` § Security explicitly hands to this slice. **Accepted, not deferred:**
    the disclosure is strictly narrower than the thread content already on screen above it, and it is the
    same posture `CompactingIndicator` (#597) and `ApiRetryIndicator` (#594) shipped in this identical
    slot. A `FLAG_SECURE` treatment would be a whole-thread decision, not a per-affordance one, and
    belongs to a screenshot-policy ticket that does not exist yet.
- **[Out of scope]** Family-wide `liveRegion` a11y and the design-owed visual retune (spinner-vs-glyph,
  `onSurfaceVariant`-vs-`Schemes/Primary`) — both pre-existing across all four status affordances, both
  already recorded in `thinking-indicator.md` and `compacting-indicator.md` for the design-owed
  follow-up. Neither is a security finding; named here so the deferral is explicit rather than silent.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

# Spec #594 — Render claude's API-retry status (attempt N/M) in the thread

Ticket: [#594](https://github.com/pyrycode/pyrycode-mobile/issues/594) · Split from #582 · size `s` ·
`security-sensitive`

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The host frame is the Conversation Thread: a dark single-column thread of message bubbles between a
top bar and the composer, with the tool row, session delimiter, and memory-plugin line already drawn.
The **status-affordance slot itself is not drawn** — there is no retry, thinking, or stall treatment
in the frame, the same design-owed gap already recorded for `ThinkingIndicator` (#406) and
`StallPromotionBanner` (#396). This ticket inherits their treatment: the app's existing Material 3
progress idiom (small indeterminate `CircularProgressIndicator` + `bodySmall` label on
`onSurfaceVariant`), placed in the existing foot-of-list slot. No contract change when the frame
lands.

## Files to read first

| Path | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:57-70` | `observeApiRetry` contract + its `flowOf(NotRetrying)` default. The interface is **already final** — do not touch it. |
| `.../data/repository/ConversationRepository.kt:288-317` | `ApiRetryStatus` sealed type: `NotRetrying` / `AttemptUnknown` / `Attempt(current, total)`. Note the KDoc line "no clamping — #594 bounds display" — that's this ticket's mandate. |
| `.../data/network/InteractivePayloads.kt:149-180` | `ApiRetryPayloadDto` wire shape `{conversation_id, active, current, total}` (needed for the harness envelope builder) and `toStatus()`'s three cases — this is **why** `0/0` and negatives never arrive as `Attempt`. |
| `.../ui/conversations/components/ThinkingIndicator.kt:24-93` | **The closest precedent — clone its shape.** Private dimension `val`s, early-return-when-inactive, `semantics(mergeDescendants = true) { contentDescription = … }`, two light/dark `@Preview`s. |
| `.../ui/conversations/components/StallPromotionBanner.kt:30-51` | Second precedent for the design-owed KDoc paragraph and the early-return idiom. |
| `.../ui/conversations/thread/ThreadViewModel.kt:325-340` | `isStalled` — the **exact** hoist to clone (`repository.observeX(conversationId).stateIn(...)`, no extra operator). |
| `.../ui/conversations/thread/ThreadScreen.kt:92-120` | Signature's defaulted-parameter block; `modifier` is at :100, `isThinking`/`isStalled` at :101-102. New param goes **after** `modifier` (#508). |
| `.../ui/conversations/thread/ThreadScreen.kt:300-315` | The foot-of-list status slot: `QueuedBacklog` → `ThinkingIndicator` → `InterruptAffordance`. This is the single slot the retry status contends for. |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt:355-382` | The `collectAsStateWithLifecycle` + argument-passing block — two added lines, mirroring `isStalled`. |
| `app/src/main/res/values/strings.xml:37,54-57` | `archived_tab_channels` (`%1$d` positional-integer precedent) and the `thread_thinking_label` / `cd_thread_thinking` / stall pair (label + `cd_` naming convention). |
| `app/src/androidTest/.../thread/ScriptedThreadHarness.kt:95-136` | `start()`'s `ThreadScreen` composition (needs one added argument) and the `push*` helper style. |
| `app/src/androidTest/.../thread/ScriptedThreadHarness.kt:278-288` | `turnStateEnvelope` — the private envelope-builder shape to clone for `api_retry`. |
| `app/src/androidTest/.../thread/ThinkingIndicatorTest.kt:1-60` | **The assertion idiom:** resolve the `cd_*` string from `InstrumentationRegistry…targetContext.getString(...)` and assert via `onNodeWithContentDescription`, not by text. |
| `app/src/androidTest/.../thread/ScriptedToolRowTest.kt` (110 lines) | Size + structure anchor for the new `Scripted*Test.kt` sibling. |
| `docs/e2e-interactive-stream.md:835-870` | The `## Follow-ups to ticket` → `Coverage:` list. One appended entry; no `api_retry` mention exists there yet. |
| `app/src/androidTest/.../thread/ScriptedThreadHarness.kt` — do **not** rewire | The harness graph (real `RemoteConversationRepository` → `ThreadViewModel` → `ThreadScreen`) is already correct. Additive only. |

## Context

`api_retry`'s data half landed on `main` in #593 (PR #595, `022c0b8`): the capability-gated wire event
is decoded into a per-conversation projection exposed as
`ConversationRepository.observeApiRetry(conversationId): Flow<ApiRetryStatus>`, already defaulted
through `StableConversationRepository`. Nothing observes it yet, so a multi-minute API-retry storm
still renders as the indefinite generic thinking spinner — indistinguishable from normal reasoning.

This slice is the render half, and it is the exact shape of the shipped stall pair (#395 reduced the
wire event to observable state; #396 rendered it). **No repository, interface, or DI change.**

## Design

Four production `.kt` files — one new component, three small edits. Same shape as #396 (5 files /
~129 production lines, of which one new component at 105).

### 1. `ui/conversations/components/ApiRetryIndicator.kt` (new, ~110 lines)

Sibling of `ThinkingIndicator`, in the same package and the same M3 idiom.

```kotlin
@Composable
fun ApiRetryIndicator(
    status: ApiRetryStatus,
    modifier: Modifier = Modifier,
)
```

- **Early-returns on `ApiRetryStatus.NotRetrying`**, mirroring `ThinkingIndicator`'s
  `if (!isThinking) return`. This keeps the composable a total, pure function of its input even though
  § 3's precedence means the screen normally won't call it in that state — the same
  defence-in-depth posture as `ThinkingIndicator`.
- Renders a `Row` of `CircularProgressIndicator` + `Text`, with
  `semantics(mergeDescendants = true) { contentDescription = … }` so it reads as **one** merged node
  (AC #1).
- Two rendered cases, selected by § 2's predicate:
  - **counter shown** — `thread_api_retry_label` / `cd_thread_api_retry`, both formatted with
    `current` and `total`.
  - **counter-less** — `thread_api_retry_label_unknown` / `cd_thread_api_retry_unknown`. Reached by
    `AttemptUnknown` **and** by an `Attempt` that fails the predicate.
- Declares its own private `val`s for padding / spinner size / gap. **Do not** refactor
  `ThinkingIndicator`'s private constants into a shared file — `StallPromotionBanner` duplicated its
  own, and CLAUDE.md's "don't refactor adjacent code while you're there" applies.
- Two `@Preview`s (light + dark), cloning `ThinkingIndicator.kt:70-93`.

**Passing the repository-package `ApiRetryStatus` into a `components/` composable is established** —
`MessageBubble(message: Message)`, `SessionBoundaryDelimiter(boundary: ThreadItem.SessionBoundary)`,
and `QueuedBacklog(queued: List<QueuedMessage>)` all do it. Do not introduce a UI-layer mirror type.

**No dedup, no memoisation.** `Attempt` is a `data class`, so a climbed counter is a different value
and recomposition follows for free (#593 made that structural equality load-bearing). Do **not** add
`distinctUntilChanged`, `derivedStateOf`, or a bare `remember { }` around the formatted label — any of
those would freeze a climbing counter and break AC #1.

### 2. The display sanity gate (AC #2) — private to `ApiRetryIndicator.kt`

A private predicate on `ApiRetryStatus.Attempt`, plus a private named bound const, both KDoc'd where
they sit (AC #2's "documented where the fallback happens"):

```kotlin
private fun ApiRetryStatus.Attempt.isRenderableCounter(): Boolean
```

Renderable iff `current in 1..total && total <= MAX_PLAUSIBLE_ATTEMPTS`, with
`MAX_PLAUSIBLE_ATTEMPTS = 99`.

That single condition covers all three rejects the AC names:

| Rejected shape | Caught by |
|---|---|
| unparsed `0/0`, or any non-positive counter | `current in 1..total` (lower bound) |
| incoherent `9/3` | `current in 1..total` (upper bound) |
| absurd `1/2147483647` | `total <= MAX_PLAUSIBLE_ATTEMPTS` |

`current >= 1 && current <= total` also implies `total >= 1`, so no separate lower bound on `total` is
needed.

**Bound rationale to record in the KDoc:** claude's API-retry budget is single-digit in practice; 99
leaves an order of magnitude of headroom while keeping each number to two digits, which is what keeps
the label inside the narrow foot-of-list row at the 412dp reference width. It is a
**render-or-decline** gate, never a clamp — the ticket is explicit that "declining to render an
unusable counter is not the same as clamping or rewriting it; prefer the former", and clamping here
would re-import at the display layer exactly the server-data rewrite #593 refused at decode.

Note that `0/0` and negatives **cannot actually arrive** as `Attempt` — `toStatus()` already folds
them to `AttemptUnknown` (`InteractivePayloads.kt:175-180`). The lower bound is defence-in-depth
against the type permitting what the mapper forbids, and AC #2's "neither `0/0` … reaches the screen"
is satisfied twice over. Do not add a second mapper or re-derive the upstream logic.

### 3. `ThreadScreen.kt` — one new parameter, one precedence decision (~8 lines)

Add a defaulted parameter in the block after `modifier` (`ComposeParameterOrder` lint, #508); place it
beside `isStalled` to keep the status signals grouped:

```kotlin
apiRetry: ApiRetryStatus = ApiRetryStatus.NotRetrying,
```

The default keeps all ~9 existing `ThreadScreen(` call sites compiling untouched (AC #4) — only
`MainActivity` and the harness gain an argument.

At the existing status slot (`ThreadScreen.kt:310`), replace the bare `ThinkingIndicator(...)` call
with an explicit either/or so the mutual exclusion is single-sourced and readable:

- when `apiRetry == ApiRetryStatus.NotRetrying` → `ThinkingIndicator(isThinking = isThinking, …)`
- otherwise → `ApiRetryIndicator(status = apiRetry, …)`

Carry a comment stating the rule: **one status slot; retry wins whenever active**, because the signal
is conversation-level and outlives the thinking phase, so the two never stack (AC #1) and the retry
status shows regardless of turn state.

Precedence lives **here**, not in the ViewModel. Do not suppress `isThinking` at its source: it is
defined as the `turn_state` phase, other tests assert it directly, and overloading it would make the
VM's contract lie. `InterruptAffordance` below the slot is untouched — an in-flight turn is still
interruptible while retrying.

### 4. `ThreadViewModel.kt` — one hoisted `StateFlow` (~18 lines with KDoc)

```kotlin
val apiRetry: StateFlow<ApiRetryStatus> =
    repository.observeApiRetry(conversationId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ApiRetryStatus.NotRetrying,
    )
```

A verbatim clone of `isStalled` (`ThreadViewModel.kt:333-340`) — sibling `StateFlow` beside
`connectionState` / `isThinking` / `isStalled`, **not** a `ThreadUiState` field, because the stateless
screen takes transient cross-cutting signals as separate parameters. No constructor, DI, or interface
change. No extra operator: the remote impl already applies `distinctUntilChanged` and the facade
already defaults to `NotRetrying`. `NotRetrying` covers both "no live connection" and "not retrying".

### 5. `MainActivity.kt` (2 lines) and `strings.xml` (4 lines)

`MainActivity`: one `collectAsStateWithLifecycle` beside `isStalled` (:360) and one argument beside
`isStalled =` (:378).

Four strings, following `archived_tab_channels`'s positional-integer precedent (`%1$d`) — **positional
args, never concatenation**:

| Name | Value |
|---|---|
| `thread_api_retry_label` | `Retrying — attempt %1$d/%2$d` |
| `thread_api_retry_label_unknown` | `Retrying…` |
| `cd_thread_api_retry` | `Claude is retrying, attempt %1$d of %2$d` |
| `cd_thread_api_retry_unknown` | `Claude is retrying, attempt count unknown` |

Both `cd_*` variants are load-bearing beyond a11y: they are the primary test assertion handle (§
Testing), matching how `ThinkingIndicatorTest` asserts `cd_thread_thinking` rather than label text.

## State + concurrency model

- **No new coroutine, dispatcher, or scope.** The only new job is `stateIn` on `viewModelScope` with
  `WhileSubscribed(5_000)` — identical lifecycle to `isStalled`; screen exit unsubscribes and the
  upstream cold flow is cancelled after the 5s grace.
- **Single source of state.** `apiRetry` is a hoisted `StateFlow` read once per composition; the
  component holds no local state and nothing parallel exists.
- **Hot vs cold:** `observeApiRetry` is correctly cold per-collector over the repository's shared
  `MutableStateFlow`. Leave it cold — do not introduce a `SharedFlow`.
- **No subscribe-before-push hazard here**, unlike `isThinking`. `isThinking`'s upstream
  (`liveSessionEvents`) is `replay = 0`, which is why the harness's `awaitReady()` exists;
  `observeApiRetry` projects a retained `MutableStateFlow`, and the repository's inbound collector runs
  unconditionally on the harness scope, so a push before subscription is still observed. Keep pushes
  after `start()` anyway, for uniformity with every other `push*`.

## Error handling

There is no new failure mode to surface — this layer only reads an already-decoded, total sealed type.

| Condition | Layer that handles it | Result on screen |
|---|---|---|
| malformed / undecodable `api_retry` frame | #593's decode (already shipped) | frame dropped; screen unchanged |
| retrying, counter unparsable by the daemon (`0/0`) | #593's `toStatus()` → `AttemptUnknown` | counter-less "Retrying…" |
| retrying, counter incoherent or absurd | § 2's display gate | counter-less "Retrying…" |
| falling edge (with a stale counter on the wire) | #593's `toStatus()` discards the counter | reverts per AC #3 |
| no `api_retry` frame ever | facade / interface default `NotRetrying` | today's behaviour exactly |

No banner, dialog, snackbar, or log call. A bad counter degrades to the less-specific-but-true status;
nothing is hidden and no retry onset is ever dropped.

## Testing strategy

**Rung 2 only** — `ScriptedThreadHarness` (`androidTest`), which drives envelopes through the **real**
repository fold → `ThreadViewModel` → `ThreadScreen`. Rungs 3 and 4 cannot cover this (see § Coverage
doc). No new `test/` unit test: the data half is covered by #593, and § 2's predicate is exercised
through the real render path, matching #396's androidTest-only posture.

**Harness additions (additive; no graph rewiring):**

- `start()` gains one argument: `apiRetry = vm.apiRetry.collectAsState().value`.
- `fun pushApiRetry(active: Boolean, current: Int, total: Int)` — a thin `pump.push`, plus a private
  `apiRetryEnvelope(...)` builder cloning `turnStateEnvelope`'s shape with
  `type = "api_retry"` and payload `{conversation_id, active, current, total}`.

**New `ScriptedApiRetryTest.kt`** (sibling of `ScriptedToolRowTest`). Scenarios as behaviour, not
code — assert via `onNodeWithContentDescription` on the resolved `cd_*` strings, and use
`assertDoesNotExist` on the *other* status's description for every mutual-exclusion claim:

*AC #1 — replaces the thinking label, tracks updates, turn-state-independent*
- push `turn_state` `thinking`, assert the thinking node is displayed; then push active `3/10` → the
  `3/10` retry node is displayed **and** the thinking node does not exist.
- push `turn_state` `idle`, then active `3/10` → the retry node is still displayed (proves the status
  is conversation-level, not gated on the turn phase).
- push active `3/10`, then active `4/10` → the `4/10` node is displayed and the `3/10` one does not
  exist (proves no dedup was introduced).

*AC #2 — every unusable counter falls back*
- active `0/0` → counter-less node displayed; assert no node with text containing `0/0`.
- active `9/3` → counter-less node displayed; assert no node with text containing `9/3`.
- active `1/2147483647` → counter-less node displayed; assert no node with text containing
  `2147483647`.

*AC #3 — clears cleanly*
- `thinking` + active `3/10`, then `active = false` → the thinking node is displayed again and no
  retry node exists.
- active `3/10` + `turn_state` `idle`, then `active = false` → neither node exists.

*AC #4 — no regression*
- `turn_state` `thinking` with no `api_retry` frame ever → the thinking node is displayed and no retry
  node exists.

**Verification commands** (worktree needs both env vars — `local.properties` is gitignored):

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew compileDebugAndroidTestKotlin   # androidTest is NOT compiled by test/lint/assembleDebug
ANDROID_HOME=~/Library/Android/sdk ./gradlew test lint spotlessCheck
```

`connectedAndroidTest` needs a device; run the scripted class alone if one is attached.

## Coverage doc (AC #5)

Append one entry to the `Coverage:` list under `## Follow-ups to ticket`
(`docs/e2e-interactive-stream.md:845`), in the established prose style. It must record:

- **rung 2 — shipped (#594)**: both edges and both counter cases through the real fold.
- **rungs 3 and 4 — not coverable, with the reason**: the daemon emits `api_retry` only from the
  PTY-runner detector family, while production runs the stream-json interactive runner, which has no
  emitter. So real claude cannot be made to produce the frame inside a test budget (rung 3), and rung
  4's `fakeclaude` swaps claude but keeps the real daemon — still no emitter — so injecting the frame
  there would be a daemon change, out of scope for a client-only ticket.

This is the only file outside `app/src/` and this spec that the developer touches. Do **not** write
`docs/knowledge/codebase/594.md` — the documentation phase owns it.

## Open questions

1. **Is 99 the right bound?** It is a judgement call, deliberately generous and documented at the
   predicate. If claude's retry budget ever legitimately exceeds it, the symptom is benign (a real
   retry renders counter-less) and the fix is a one-line const change. Not worth a config knob.
2. **Should the retry status also outrank `StallPromotionBanner`?** Out of scope. The stall banner
   lives in a different slot (`ThreadScreen.kt:200`, above the list) and the two signals are
   independent, so they can legitimately co-render. Not touched here; if the pairing reads badly in
   practice it is a follow-up PO ticket.
3. **Em dash in `thread_api_retry_label`.** Matches the ticket's wording ("Retrying — attempt N/M")
   and the app's existing typography (`Opus 4.7 · high`). If ktlint/lint objects to the literal, use
   the escaped form rather than downgrading to a hyphen.

## Security review

**Verdict:** PASS

**Findings:**

- **[1. Trust boundaries]** No findings. This slice adds **no** trust boundary: the untrusted→trusted
  crossing already happened at #593's single `decodeApiRetry` boundary, and what reaches here is a
  closed sealed `ApiRetryStatus` carrying two `Int`s and **no `String`** — the routing
  `conversation_id` stays a map key in the repository projection and structurally cannot reach this
  layer (`ConversationRepository.kt:294-296`). So no daemon-supplied text — banner, screen scrape, or
  otherwise — can reach a `Text` composable through this arm, which is the property that makes the
  whole render slice cheap to secure. The one new decision point, § 2's `isRenderableCounter`, is a
  single named private predicate in one file, not scattered validation, and it is a *narrowing* gate
  (data is displayed or declined, never transformed), so no caller downstream can be confused about
  whether it holds validated data.
- **[2. Tokens, secrets, credentials]** Not applicable, by design decision: the slice reads an
  already-authenticated, already-Noise-decrypted projection and adds no credential creation, storage,
  comparison, rotation, or revocation surface. No token, key, or fingerprint is read, rendered, or
  passed. No `String` compare of any kind occurs, so the constant-time rule has no site.
- **[3. File / storage]** Not applicable, by design decision: nothing is persisted. `apiRetry` is a
  `stateIn` cache over an in-memory, connection-scoped `MutableStateFlow`; no `DataStore` key, no
  file, no `rememberSaveable`. A retry state therefore cannot survive process death into a later
  session, and no `allowBackup` / `dataExtractionRules` exclusion arises. Deliberately: persisting a
  transient status would risk showing a stale "Retrying" after a cold start, which is the exact
  stuck-status failure AC #3 forbids.
- **[4. Inter-process / Android attack surface]** No findings, and one item worth stating rather than
  waving through. No `Activity`, `Service`, `BroadcastReceiver`, `ContentProvider`, `PendingIntent`,
  `WebView`, deep link, or `intent-filter` is added or altered; `MainActivity`'s diff is two lines
  inside an existing composable and changes nothing about export or intent handling. The one real
  IPC-adjacent surface this slice *does* create is the **accessibility semantics node** (`cd_*`
  contentDescription): it is readable by any installed accessibility service, so a hostile one could
  read it. Accepted, because the node's content is bounded by construction — a fixed local format
  string plus at most two `Int`s that passed § 2's `1..99` gate — so it can leak "claude is retrying at
  attempt 3 of 10" and nothing more. No conversation name, message body, workspace path, or
  daemon-supplied text enters the description. This is a deliberate contrast with, e.g., the top bar,
  which necessarily exposes the conversation name.
- **[5. Cryptographic primitives]** Not applicable, by design decision: no RNG (nothing random — the
  spinner is Compose's own indeterminate animation), no key, no hash, no derivation, and no comparison
  of an attacker-controlled value against a secret. § 2's comparisons are integer range checks against
  a compile-time const, not secret comparisons, so `MessageDigest.isEqual` has no site.
- **[6. Network & I/O]** No findings. The slice opens no connection, builds no `OkHttpClient`, sets no
  timeout or TLS config, lifts no frame-size cap, and adds no I/O or suspension point — it only
  collects an existing cold flow. It cannot back-pressure the connection: `stateIn` conflates, so a
  hostile daemon flooding rising edges at high rate cannot queue unboundedly against this collector;
  the worst case is recomposition churn of a single `Row`, bounded by Compose's own frame pacing, on a
  connection the peer already holds. `WhileSubscribed(5_000)` also means an off-screen thread stops
  collecting entirely.
- **[7. Error messages, logs, telemetry]** No findings, and this is the one category where the slice
  must actively *not* regress #593. **MUST-NOT-log, and the spec adds no log call anywhere:** the
  counter, the payload, and `conversation_id`. This matters even though the counter is "just two
  ints" — `current`/`total` are **screen-derived** data that crossed the tui-driver substrate seal,
  which is precisely why pyrycode#1074 and #593 were labelled `security-sensitive`, and the uniform
  no-log posture is what keeps them out of Logcat in every build variant (the repository, `#406`'s
  hoist, and `#396`'s all log nothing). No `Timber`/`Log` call, no crash-reporter breadcrumb, no
  telemetry, no analytics — so no consent question arises. All user-facing text is a fixed local
  string resource; no exception message, no server field, and no stack trace can reach a `Text`,
  a `Toast`, or the un-secured `Activity` window a snackbar would draw in (contrast
  `ThreadScreen.kt:129-134`, which had to reason about exactly that for the modal-send path).
- **[8. Concurrency]** No findings. Exactly one new job (`stateIn` on `viewModelScope`), owned and
  cancelled by the `ViewModel` — nothing outlives it, so no leak. No mutex, so no lock-ordering
  question. No check-then-mutate on shared state: the component is a pure function of its parameter
  and holds no `MutableState`, so the TOCTOU item has no site, and there is deliberately no
  `MutableState` written from a `LaunchedEffect`. On the checklist's hot-vs-cold item — the one place
  this design could have gone wrong: `observeApiRetry` must stay **cold per-collector**; a hot
  `SharedFlow` here would let one conversation's thread observe another's retry state as screens are
  swapped through the facade's `flatMapLatest`, which is a genuine cross-screen data leak. The spec
  keeps it cold and says so (§ State + concurrency). Shutdown safety: nothing is written, so there is
  no partial state to recover; a mid-retry process death simply re-renders from `NotRetrying`.
- **[9. Threat model alignment]** No findings; two threats named and addressed, one deferred.
  (a) **Hostile-but-authenticated daemon sending an extreme or incoherent counter** — this is #593's
  deliberately deferred SHOULD FIX, and § 2 is the fix: `9/3` and `2147483647` are declined rather
  than clamped, so no server data is rewritten and no layout can be broken. Calibrated per the ticket:
  the SSOT states `current`/`total` are bounded and pre-sanitized server-side and that no `api_retry`
  field carries banner or screen text, so this is defence-in-depth plus plain layout safety, not a
  workaround for honest-daemon output — hence a 3-condition predicate and no clamping, sanitising, or
  new error path. (b) **Accessibility-service eavesdropping** — addressed in category 4: bounded
  content by construction. (c) **UI screenshot leakage / screen-overlay attacks** — #593 handed these
  to this ticket as "relevant only once #594 renders". Both are **OUT OF SCOPE** and unchanged by this
  slice: the thread window already renders conversation names and full message bodies, so a
  `FLAG_SECURE` or overlay-hardening decision is a whole-window policy question that this status row
  neither creates nor worsens (it adds strictly less sensitive content than the bubbles above it).
  Filing that as a window-policy ticket is PO's call, not a precondition here.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-30

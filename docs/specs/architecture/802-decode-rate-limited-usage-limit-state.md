# 802 — decode the `rate_limited` wire event as conversation-observable usage-limit state

Decode-only slice. Rendering the reading in the status area is a sibling slice of the same split.

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `data/network/InteractivePayloads.kt` | `CompactingPayloadDto`, `ApiRetryPayloadDto.toStatus`, `ModelListRowDto` | The DTO file this slice extends. `toStatus` is the "mapper owns the edge semantics" precedent; `ModelListRowDto.truncatedFields` the nullable-array one. |
| `data/repository/ConversationRepository.kt` | `ApiRetryStatus`, `observeCompacting`, `observeApiRetry` | Where the reading type and the defaulted seam go, and the KDoc shape they follow. |
| `data/repository/RemoteConversationRepository.kt` | `onInbound`'s `TYPE_COMPACTING` arm, `compactingConversations`, `decodeCompacting`, `observeCompacting`, the constructor's defaulted params | The arm, projection, decoder and override this slice clones — and the defaulted-trailing-param convention that keeps the clock injection off every call site. |
| `data/repository/StableConversationRepository.kt` | `switchToLive`, `observeCompacting` | The one-line pass-through shape. |
| `data/network/MobileWireCodec.kt` | `MobileJson` | `explicitNulls = false` / `ignoreUnknownKeys = true` — why an omitted key and an explicit `null` collapse, which is correct for both nullable fields here. |
| `data/cache/ConversationCache.kt` | — | Checked and **not** wired into `RemoteConversationRepository`: no projection is persisted. Load-bearing for the security review's storage finding. |
| `app/src/test/…/RemoteConversationRepositoryTest.kt` | the `#596` block, `compactingEnvelope`, `compactingProbe`, `collectCompacting` | The block the new cases sit beside and the fixture/probe/collector trio to mirror. |
| `app/src/test/…/StableConversationRepositoryTest.kt` | the `#596` block, the inline `ConversationRepository` double | The facade cases and the double that needs a `pushUsageLimit` sibling. |
| `docs/knowledge/features/remote-conversation-repository-thread-observables.md` § `observeCompacting` | — | Owning topic. Records `compacting` as the *narrowest* sibling arm because it carries "no daemon-supplied text or number of any kind". This frame is the opposite — the security delta of this ticket. |
| `docs/knowledge/features/conversation-repository.md`, `stable-conversation-repository.md` | — | The defaulted-seam and facade contracts. |
| `../pyrycode/docs/protocol-mobile.md` § `rate_limited` | — | **Wire SSOT, cited not restated.** Six fields, the benign-status falling edge, the two unvalidated numbers, the `null`-vs-`0` trap, the SECURITY paragraph. |
| `../pyrycode/internal/protocol/interactive.go` | `RateLimitedPayload` | Go field types: `ResetsAt int64`, `Utilization *float64`, `TruncatedFields []string`. Settles `Long` vs `Int` — see Design 1. |
| `../pyrycode-desktop/src/renderer/src/store/usageLimitStore.ts` | `selectUsageLimitFor`, `clearUsageLimitFor` | The expiry and clear rules, per Technical Notes. Read for the rules, not the store shape. |

**Design source:** not applicable — decode-only, lands no pixel. The ticket body carries no `## Figma`
section and none is owed; the sibling render slice owns the visual contract.

## Context

`rate_limited` has been emitted since pyrycode#1410 and mobile drops it: no DTO, no arm in `onInbound`'s
`when`. A turn that stalls on a usage limit says nothing about why.

This is the sixth member of the capability-gated interactive-state family (`stall`, `queue_state`,
`api_retry`, `compacting`, `model_list`) and follows #596 structurally. **Two things make it not a
copy of #596**, and they are what the design has to earn:

1. **It is the first state arm carrying daemon-authored text and unvalidated numbers into a domain
   type.** The thread-observables overview records `compacting` as the narrowest arm precisely because
   it carries neither. The obligations that follow are § Security review's subject, discharged partly
   here and partly by the render sibling.
2. **The state needs a second way down.** The wire's clearing edge is session-scoped, so a warning
   raised before a `/clear` or a session eviction is never followed by a clearing frame. The expiry
   rule is what stops such a reading standing forever.

**No ADR is warranted** — every decision applies an existing one (ADR 0001 for `kotlinx-datetime` in
`data/`; #351's connection-scoped repository; #596's arm shape). The documentation phase owns the
feature-overview fold.

## Sizing — one line of the boundary is exceeded, stated rather than smoothed over

Measured against the written plan: **4** production source files (≤5), **1** new exported type (≤5),
**0** consumer call sites needing simultaneous update (≤10), **5** acceptance criteria (≤5), **3**
reject branches (≤10) — and **~870 lines of total written work against the 800 ceiling**. The overage
is ~9% and sits **entirely in this plan document** (367 lines, of which the mandated security-review
format is the largest section); production plus tests is ~505, in line with the refiner's ~480
estimate and with the #596 analogue's 400.

**It is not split, because the floor rule wins over the ceiling here.** Every candidate seam produces a
child whose only consumer is its sibling in the same family: a DTO-and-mapper slice is called by
nothing outside this ticket, and an expiry-only slice modifies one method of its sibling while the
sibling alone would ship a state that never comes down — the exact bug the ticket exists to foreclose.
Per the builder's floor rule, a one-consumer slice is part of its sibling, and the merged ticket is
built with the overage stated even when it exceeds a line of the table. The ceiling protects a budget
miss (one continuation leg); the floor protects a ticket that cannot be verified on its own, which no
resume fixes.

## Design

Four production files, no new file, no new package.

### 1. `data/network/InteractivePayloads.kt` — DTO and mapper

```kotlin
@Serializable
internal data class RateLimitedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val status: String,
    @SerialName("limit_type") val limitType: String,
    @SerialName("resets_at") val resetsAt: Long,
    val utilization: Double? = null,
    @SerialName("truncated_fields") val truncatedFields: List<String>? = null,
)

/** null == the benign falling edge (clear this conversation's entry); non-null == a reading to hold. */
internal fun RateLimitedPayloadDto.toReading(): UsageLimitReading?
```

- **`resetsAt` is `Long`, and that is load-bearing.** The Go field is `int64` and the contract admits
  year-40000 values (~1.2e12 unix seconds, an order of magnitude past `Int32`). An `Int` would fail the
  *structural decode* on exactly the out-of-range value AC #3 requires be **carried** — the criterion
  silently violated by a type choice.
- **Two nullable fields, each defaulted `= null`** following `ModelListRowDto.truncatedFields`. The
  daemon always emits both keys, so the default only covers a non-conforming producer; `MobileJson`'s
  `explicitNulls = false` collapsing omitted with explicit-`null` is correct here, unlike
  `effective_effort` whose three states had to stay apart. An out-of-contract `[]` on
  `truncated_fields` decodes to an empty list rather than being punned to `null`.
- **Every other field stays strict-required** (the `CompactingPayloadDto` posture): a missing or
  wrong-typed field fails the decode and the one envelope drops.
- **`toReading()` owns the benign comparison** — the `toStatus()` precedent rather than `compacting`'s
  `if (active)`: here a mapper exists (five wire fields collapse into a four-field reading plus a
  routing key), so the edge decision belongs in it and a second `if` in the arm would encode the rule
  twice. The benign literal is a private `const` in this file, compared at exactly one call site.
- **Nothing else is compared, narrowed, normalised, trimmed, lower-cased, allow-listed, clamped or
  range-checked.** Both strings and both numbers cross verbatim.

### 2. `data/repository/ConversationRepository.kt` — reading type and seam

```kotlin
data class UsageLimitReading(
    val status: String,
    val limitType: String,
    val resetsAt: Long,
    val utilization: Double?,
    val truncatedFields: List<String>?,
)

fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> = flowOf(null)
```

Declared beside `ApiRetryStatus` per Technical Notes — no new file.

- **A `data class`, not a sealed family.** `ApiRetryStatus` is sealed because its wire shape collapses
  into three meanings a `when` must cover; this payload has one meaning with five fields. `data` is
  load-bearing for `ModelMenu`'s reason: structural equality is what makes `distinctUntilChanged`
  behave.
- **`conversationId` is deliberately absent** — it stays the map key, the rule `ApiRetryStatus` already
  states, so the routing key cannot be copied into what the render sibling holds and draws from.
- **`null` at the flow is "no readable reading"**, covering three upstream facts a consumer need not
  tell apart: nothing arrived, a benign frame cleared it, or `resets_at` passed. A degenerate but
  present reading (`status = ""`) is a real reading and is **not** collapsed to `null`.
- **Defaulted on the interface** like its four siblings, which absorbs the `FakeConversationRepository`
  and inline-double cascade: **zero consumer call sites**.

### 3. `data/repository/RemoteConversationRepository.kt`

- **A defaulted trailing constructor param** `private val now: () -> Instant = Clock.System::now` — a
  supplier for `negotiatedCapabilities`' reason (read at each evaluation, not at construction), and
  defaulted so all 20 existing construction sites compile unchanged. `kotlinx.datetime.Instant` rather
  than a bare seconds `Long` is the type-level defence against desktop's documented
  milliseconds-vs-seconds hazard: `Instant.epochSeconds` is the only route to a number here.
- **An eighth connection-scoped projection**
  `MutableStateFlow<Map<String, UsageLimitReading>>(emptyMap())` — the payload-carrying `Map` shape of
  `apiRetryByConversation`, not `compactingConversations`' membership `Set`. Written only from the one
  `init` inbound collector through the atomic `update`. Empty per connection (#351), which also gives
  desktop's pairing-scoped clear for free: a new connection is a new repository.
- **One demux arm, `TYPE_RATE_LIMITED`, inside the same `CAPABILITY_INTERACTIVE` gate:**

  ```kotlin
  decodeRateLimited(envelope)?.let { (conversationId, reading) ->
      usageLimitsByConversation.update { if (reading == null) it - conversationId else it + (conversationId to reading) }
  }
  ```

  Routing is **strictly the payload's own `conversation_id`**, which satisfies AC #2 structurally
  including its different-`limit_type` clause: `limit_type` is read by no control-flow path, so pairing
  the clear to it is not expressible. Like the `api_retry`/`queue_state` siblings it folds no thread row
  and does **not** touch `stalledConversations` in either direction (AC #5) — a usage-limit report is
  neither a stall nor forward progress, and clearing one here would hand a daemon a lever for
  suppressing the stall indicator.
- **`decodeRateLimited(envelope): Pair<String, UsageLimitReading?>?`** mirrors `decodeCompacting`: one
  `try { … } catch (IllegalArgumentException) { null }` (`SerializationException ⊂` it). The two
  nullability levels say different things and the KDoc says so at the declaration — **outer** `null` is
  "malformed, drop the envelope, hold what we have", **inner** is "benign, clear this entry". Returning
  mapped domain values keeps the untrusted DTO from escaping the boundary.
- **The projection method is the only read surface, and where the expiry lives:**

  ```kotlin
  override fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> =
      usageLimitsByConversation.map { it[conversationId]?.takeIf(::isReadable) }.distinctUntilChanged()
  ```

  with private `isReadable(r) = r.resetsAt == 0L || now().epochSeconds < r.resetsAt`. Four properties:

  - **The zero test comes first.** `0` means claude reported no reset, not the epoch; folded into the
    comparison it would read as "expired in 1970" and make every unreported reading invisible on
    arrival.
  - **The boundary is `<`**, so a reading is unreadable *at* `resets_at` as well as after it. A negative
    `resets_at` is a past instant and unreadable immediately — the honest reading of an unvalidated
    number, since the wire rejects none either.
  - **One comparison at read time; no timer, nothing scheduled, allocated or iterated from
    `resets_at`.** Consequence, stated rather than discovered: a subscribed collector gets no
    spontaneous emission at the deadline — it re-evaluates on the next upstream change, while a
    collector subscribing after it reads `null` at once, because a `StateFlow` replays its current
    value through the `map`. The render sibling owns any recomposition cadence and must not re-derive
    the rule.
  - **Expiry is not eviction** — the entry stays and merely stops being readable, which is what keeps
    the projection free of a timer. No consumer can observe the difference.
- **A `TYPE_RATE_LIMITED = "rate_limited"` companion constant** beside `TYPE_COMPACTING`.

### 4. `data/repository/StableConversationRepository.kt`

```kotlin
override fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> =
    switchToLive<UsageLimitReading?>(null) { it.observeUsageLimit(conversationId) }
```

`null` while no connection is live is the same value an unheard conversation produces, so a consumer
has one absent case. The `flatMapLatest` switch is also the host-isolation mechanism spelled out on
`observeModelMenu`: a quota posture belongs to an account, so dropping the previous connection's
projection is what stops one account's reading being attributed to the next.

## State + concurrency model

No new coroutine, scope or subscription. The arm rides the **one** long-lived inbound collector
launched in `init` on the connection-scoped `scope`, so there is a single writer and the set/clear
transitions cannot race. The write is a pure replace or removal reading no held state — no
check-then-act window even in principle — through the atomic `update` that matches the siblings'
memory-visibility posture.

`observeUsageLimit` is a cold projection over the shared `StateFlow`, collected on the consumer's
scope, so N collectors share the one inbound consumer and cancellation is the collector's own. The
clock supplier runs on the collector's thread inside `map`; `Clock.System.now()` is thread-safe. State
dies with the connection (#351); nothing is persisted.

## Error handling

| Failure | Result |
|---|---|
| Missing / wrong-typed required field; `resets_at` unreadable as `Long`; `utilization` not a number | `SerializationException ⊂ IllegalArgumentException` → `decodeRateLimited` returns `null` → one envelope dropped, held reading stands, collector survives (AC #1). |
| `interactive` not negotiated | The gate never enters the arm (AC #1). |
| Benign `status` | Not an error — the clearing edge (AC #2). |
| `utilization` absent | Not an error — `null`, distinct from `0.0` (AC #3). |
| `utilization` / `resets_at` out of any plausible range | Not an error — carried verbatim, unclamped (AC #3); an out-of-range `resets_at` still feeds the expiry honestly. |

Nothing throws out of the arm, nothing is logged, and the caught throwable is discarded rather than
surfaced — kotlinx-serialization can quote the offending input, and that input is claude-authored text.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`). This slice lands no pixel and no operator-facing flow,
so no rung-3 scenario is owed and none is deferred. Cases sit beside the `compacting` block in each
file, reusing the `envelope` / `probe` / `collect…` helper trio.

`RemoteConversationRepositoryTest` — a `rateLimitedEnvelope(...)` fixture, a `rateLimitedProbe(id, raw)`
for malformed shapes, a `collectUsageLimit` collector, and a repository built with a fixed-`Instant`
clock:

- Absent until a frame lands; the first frame surfaces all five fields verbatim (AC #1).
- A malformed payload drops and a later valid frame still surfaces — collector survived (AC #1).
  Probes: missing `conversation_id`; `conversation_id` as a number; missing `status`; `resets_at` as an
  object. **Not** a quoted primitive — kotlinx's tree decoder accepts those even with
  `isLenient = false` (measured, per the `ApiRetryPayloadDto` KDoc), so it would decode green and prove
  nothing.
- The `interactive` gate closed, and closed-with-another-token, both block the decode (AC #1).
- A benign frame clears the holder's entry and leaves a second conversation's untouched (AC #2).
- A benign frame whose `limit_type` differs from the warning it follows still clears — the observed
  `five_hour`-clears-`seven_day` pairing (AC #2).
- Absent `utilization` stays `null` and `0.0` stays `0.0`, asserted apart (AC #3).
- `utilization` of `-0.5` and `2.5`, and `resets_at` negative and year-40000, carried verbatim (AC #3).
  The year-40000 case is also the `Long`-not-`Int` regression guard: it fails the decode under an `Int`.
- Expiry: readable before `resets_at`; a collector subscribing after it reads `null`; `resets_at = 0`
  stays readable at a far-future clock; exactly at `resets_at` is unreadable (AC #4).
- A `rate_limited` neither raises nor clears a stall and folds no thread row (AC #5), asserted against
  an already-stalled conversation — the `compacting_inertTowardNeighbours` shape.

`StableConversationRepositoryTest` — a `pushUsageLimit` on the inline double, then `null` while no
connection is live, delegation to the live repository, and no leak across a connection switch.

## Documentation handoff

The ticket body carries no **Documentation handoff** section and no documentation-only acceptance
criterion. The documentation phase owns folding this arm into
`docs/knowledge/features/remote-conversation-repository-thread-observables.md` (a new
`observeUsageLimit` section beside `observeCompacting`) and
`docs/knowledge/features/conversation-repository.md`, plus any new topic document for the usage-limit
state itself. **Pending for the documentation stage** — not touched by this ticket.

## Open questions

1. **Does the render sibling need `truncated_fields`?** Desktop's #1319 declined to carry it, on the
   ground that its render slice renders no daemon-authored string. Mobile's sibling is unwritten and
   AC #1 says "surfaces its fields verbatim", so this slice carries it. Resolve by confirming the field
   reaches the decoded reading and forces no consumer to use it.
2. **Should an already-expired reading be dropped at write time rather than hidden at read time?** The
   plan says no, following desktop. Resolve by confirming the read-time comparison satisfies AC #4 in
   tests without a second rule at the write.

## Revisions

**2026-09-22 — implementation.** The design shipped unchanged; both Open Questions resolved without
moving it, and one test-level defect was found and fixed.

1. **Open question 1 — does the render sibling need `truncated_fields`?** Resolved **yes, carry it**.
   `RateLimitedPayloadDto.truncatedFields` decodes to `UsageLimitReading.truncatedFields` and is
   asserted verbatim by `usageLimit_absentUntilAFrameArrives_thenSurfacesEveryFieldVerbatim`. It is
   nullable, so a consumer that has no use for it ignores it at no cost, while a consumer that renders
   either string has the one signal that says the text was cut. Desktop's contrary call rested on its
   render slice drawing no daemon string, which is a fact about that slice and not about this decode.
2. **Open question 2 — drop an expired reading at write time instead of hiding it at read time?**
   Resolved **no**, as planned. `isReadable` at the single read surface satisfies AC #4 in four tests
   (`stopsBeingReadableOnceResetsAtHasPassed`, `exactlyAtResetsAt_isUnreadable`,
   `zeroResetsAt_neverExpires`, and the negative-`resets_at` half of
   `outOfRangeResetsAt_isCarriedNotRejected`) with no second rule at the write. A write-time drop would
   also have needed a timer to fire at the deadline, which is exactly what the `resets_at`-is-never-a-
   scheduling-input finding forbids.
3. **Test defect found and fixed (no production change).** Two of the new cases pushed a raising and a
   clearing frame inside **one** `runCurrent()` batch. `MutableStateFlow` conflates, so the collector
   observed neither edge — `usageLimit_inertTowardNeighbours_keepsStallAndFoldsNoThreadRow` caught it
   by asserting an emission *count* (`expected:<3> but was:<1>`). Both cases now run each edge in its
   own batch and assert the intermediate state. Recorded because the failure mode is silent: the same
   test asserting only the *final* value would have passed green while proving nothing about either
   edge.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** MUST FIX — one explicit boundary, `decodeRateLimited`; the untrusted
  `RateLimitedPayloadDto` never escapes `data/network` and downstream holds the parsed
  `UsageLimitReading`. But this is the **first** arm in the family carrying daemon-authored text and
  unvalidated numbers into a domain type, so three rules must hold. *(a)* `conversationId` stays the
  map key and is **absent from `UsageLimitReading`** (the `ApiRetryStatus` rule), so the routing key is
  structurally unable to reach what the render sibling holds and draws from. *(b)* `status` and
  `limit_type` cross **verbatim** — never normalised, trimmed, lower-cased, allow-listed or
  shape-checked, since narrowing either drops the first real limit that fires; they are lookup keys for
  client-owned copy, not content. *(c)* **No length bound is added here**: the daemon bounds both
  strings at construction and `OkHttpRelayTransport`'s 65519-byte frame contract bounds the envelope
  ahead of any parse, so a third bound would defend a failure that cannot reach this code, and the
  carried `truncated_fields` is how a consumer learns a value lost characters.
- **[Trust boundaries]** MUST FIX — **no behaviour may branch on `status` beyond the single benign
  comparison**, which the protocol states as a MUST NOT: the value set beyond the benign one is almost
  entirely unmeasured, so a severity ladder or an "is this really blocking" test would be a
  security-relevant branch on model-influenced text. Enforced by one private `const` read at exactly one
  call site inside `toReading`, with no other comparison of `status` or `limit_type` in the slice. Same
  for `utilization`: a number invites a threshold far more strongly than an opaque label does, and this
  frame is a report, never a control input — it is copied and read by no control-flow path.
- **[Tokens]** No findings — no token, key or credential is read, written, derived or compared. The
  reading is nonetheless **operator-sensitive**: `status` + `limit_type` disclose the account's quota
  posture, a fact about the operator rather than about this frame, which is why the logging finding
  below is absolute rather than stylistic.
- **[File / storage]** No findings — nothing touches disk. The projection is in-memory on a
  connection-scoped repository (#351), and `RemoteConversationRepository` holds no reference to
  `ConversationCache` / `FileConversationCache`, so no reading is persisted or can outlive the
  connection that reported it. No path is built from a daemon string, so there is no traversal, TOCTOU,
  atomic-write or `allowBackup` surface. Persisting a reading would be a real defect — it would survive
  the connection scope that is this slice's whole clear mechanism — so this is a property to keep, not
  merely an absence.
- **[IPC / Android attack surface]** No findings — no `Activity`, `Service`, `BroadcastReceiver`,
  `<intent-filter>`, deep link, `PendingIntent`, content provider or WebView, and no FCM path. No UI
  sink at all.
- **[Cryptographic primitives]** No findings — no RNG, hash, KDF, key or nonce; the slice runs behind
  the already-authenticated Noise channel and touches no part of the handshake, key schedule or AEAD
  framing. The one comparison it performs uses plain `==` **deliberately**, not a constant-time compare:
  nothing here is a secret and nothing is unguessable, so `MessageDigest.isEqual` would claim a property
  it lacks.
- **[Network & I/O]** No findings — no new socket, request, timeout, TLS config or URL; decode-only, so
  no outbound verb, no lifted frame cap, and the supervisor's backoff untouched. Memory exhaustion from
  a flooding relay is bounded by construction: the entry is keyed by `conversation_id`, so a flood costs
  **one bounded record per distinct id** rather than an unbounded append per frame, and the
  connection-scoped repository returns it to zero at every reconnect — the posture
  `queuedByConversation` and `modelMenusByConversation` already ship. No eviction policy is built: it
  would defend an unobserved failure and add a second lifetime to keep in agreement with the connection
  scope.
- **[Error messages, logs, telemetry]** MUST FIX — **nothing in the arm or the decoder may log, and the
  caught throwable must be discarded rather than surfaced.** kotlinx-serialization quotes offending
  input in its messages, so logging the catch puts claude-authored text in Logcat; logging
  `conversation_id` is a cross-conversation correlation leak (the `compacting` arm's own rule); and
  logging the two strings discloses the account's quota posture. Not even a content-free count of what a
  clear dropped — a count is the first crack in a property that has to be total to be worth anything.
  Enforced by `catch (e: IllegalArgumentException) { null }` with no logging statement anywhere in the
  new code, uniform with all six sibling arms. No telemetry, metric or diagnostic seam is added.
- **[Concurrency]** No findings — no new coroutine, scope, job or subscription. Single writer on the one
  inbound collector; the write is a pure replace or removal reading no held state, so there is no
  check-then-act shape even in principle. `observeUsageLimit` is **cold** per collector and keyed by the
  caller's own id, so no reading leaks across screens; cancellation is the collector's own.
  `Clock.System.now()` is thread-safe. `LifecycleConnectionDriver`'s background close drops the
  connection and with it the repository — the intended reset, with no partial state to recover.
- **[Concurrency]** SHOULD FIX (accepted, documented) — the expiry compares against **wall-clock** time,
  so a device clock moved backwards can make an expired reading readable again. Inherent: `resets_at` is
  a wall-clock unix instant, so a monotonic clock would be the wrong comparand, and desktop carries the
  identical property. Blast radius is one stale row the next frame corrects. Accepted rather than
  defended, and named on `observeUsageLimit` so a later reader does not re-derive it as a bug.
- **[Threat model alignment]** MUST FIX — **`resets_at` must never become a scheduling input.** A delay
  computed from it can be negative (fires immediately, and spins if a handler re-arms) or past a timer's
  clamp, which *also* fires immediately rather than never — so a hostile or merely wrong value becomes a
  busy loop. Enforced by: expiry is **one comparison at read time**; nothing schedules, delays,
  allocates or iterates from it. Related and enforced by the same design: `0` is tested **before** the
  comparison, so an unreported reset is not read as "expired in 1970".
- **[Threat model alignment]** SHOULD FIX — **hostile daemon frame:** every frame is decoded
  defensively (strict DTO, drop-on-malformed) behind the `CAPABILITY_INTERACTIVE` gate, kept **inside
  the arm** so a non-interactive phone decodes nothing even from a daemon that ignored the server-side
  fan-out gate — defence in depth, uniform with all six siblings, asserted by two tests. A daemon naming
  another conversation lands its reading under **that** id and is read by nothing: a reader only asks
  for an id it can select, there is no whole-map read surface and no fallback to an active conversation.
- **[Threat model alignment]** OUT OF SCOPE — **malicious / compromised relay, ordering:** the relay is
  content-blind but on-path and can drop, delay or reorder frames, so a reordered clear could take down
  a fresher warning and a dropped clear leaves a warning standing. No ordering gate is built on the
  envelope's `event_id`: the failure has not been observed, every sibling state arm has the identical
  exposure and ships without one, and the expiry rule already gives a dropped clear a way down. The
  protocol states the ceiling — a wrong or hostile value costs **at most one misleading row**. Revisit
  only on an observed failure, and then for the family rather than this arm alone.
- **[Threat model alignment]** OUT OF SCOPE — **render-boundary obligations**, owned by the sibling
  render slice of this split: `status` and `limit_type` rendered as **inert text**, never fed to an HTML
  sink, an attribute, a URL, a filename, a cache key or a lookup path; `resets_at` formatted defensively
  rather than trusted as a date (the protocol's named realistic bug); copy that claims neither that the
  turn was blocked nor that a clear is the limit lifting. This slice has no UI sink, so the obligation is
  **inherited rather than discharged** — named explicitly so it is not lost between the two slices. The
  naming keeps the distinction visible meanwhile: `UsageLimitReading` and `observeUsageLimit` say
  "reading", never "rate limited" — desktop's deliberate `usageLimitStore`-not-`rateLimitStore` choice.
- **[Threat model alignment]** OUT OF SCOPE — **UI-side leakage** (screenshot, accessibility service,
  screen overlay) of a surface disclosing the account's quota posture: the render sibling's to weigh.
  **Account-wide scope:** the underlying limit is account-wide while the frame names one conversation;
  this slice keeps the daemon's scoping and does **not** fan a reading out to sibling conversations,
  because inventing a wider scope would attribute to a conversation a reading nothing observed for it.
  **Token theft from disk:** not applicable — nothing is persisted.

Every MUST FIX is addressed **in this plan** (§ Design 1–4) rather than deferred, so no revision round
was needed; each is restated at the symbol that enforces it so the verifier can check it landed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

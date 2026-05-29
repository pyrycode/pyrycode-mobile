# Spec: Auto-archive idle discussions after 30 days (#267)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:5-15` — the `Conversation` data class. The rule reads `isPromoted` + `lastUsedAt` and writes `archived`; no other fields are touched.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:35-49` — `observeConversations` filter `when` block. The `Archived` branch (`conv.archived`) and the `!archived` predicates on `Channels`/`Discussions` already exist (from #93). Auto-archive must converge on this **same** observable state — do not add a parallel "hidden" path.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:142-160` — `archive()` / `unarchive()` bodies. The single-`state.update { … }` flag-flip is the exact pattern `sweep()` mirrors (idempotent flag mutation, no `lastUsedAt` touch).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:441-469` — the three seed discussions (`seed-discussion-b` @ 2026-05-09, `seed-discussion-a` @ 2026-05-11, `seed-discussion-archived` @ 2026-04-15, `archived = true`). These are the fixtures the sweep **integration** test drives against a fixed reference time.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:20-122` — the interface + `ConversationFilter`. **Note:** `sweep()` is *not* added here. It is a fake-only primitive (see Design § "Where the seam lives").
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RelativeTime.kt:10-40` — the established `kotlin.time` idiom in this repo: `import kotlin.time.Duration.Companion.days`, a `now: Instant = Clock.System.now()` parameter default, `delta = now - instant` (yields a `kotlin.time.Duration`), compared with `<` / `>=`. The predicate uses exactly this shape. **No new dependency** — `kotlinx-datetime` and `kotlin.time` are both already in use.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:17-25` — `observeConversations_emitsExpectedSeeds_initially_for_all_filters` (All=6, Channels=3, Discussions=2, Archived=1). **These must stay green** — see the "No construction-time sweep" decision; the reference time is always caller-supplied, so construction does not auto-archive anything.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:330-408` — `archive_movesConversation_…_andRetainsInStore` + `archive_isIdempotent`. The new sweep tests mirror these assertion shapes (leaves Discussions, appears in Archived, retained in All; idempotent).
- `docs/specs/architecture/93-archived-conversation-retention.md` — the ancestor decision: archive became a reversible `archived` flag with the `ConversationFilter.Archived` branch + filter matrix. This ticket reuses that machinery wholesale.

**Prior art (cross-project, read via QMD if useful, not in this repo):** `pyrycode-docs/specs/architecture/219-auto-archive-predicate.md` — the canonical Go `ShouldArchive(c, now) bool`. This ticket is its Kotlin port. The Go project split the *predicate* (#219) from the *sweep loop / daemon ticker* (#220/#237/#242/#243) only because of real timer wiring; there is no real timer here, so predicate **and** the triggered sweep stay in one ticket (per the issue body).

## Context

`Conversation.lastUsedAt` exists but nothing expires on it: unpromoted discussions accumulate forever. The data layer already has reversible `archive()`/`unarchive()` and an `archived` flag that `observeConversations` filters on (#93), but no automatic idle policy.

This ticket adds the policy as a **pure predicate** plus a **triggered sweep** that flips the existing `archived` flag — so auto-archived discussions land on the identical observable state as manually-archived ones (reachable under `Archived` + `All`, still `unarchive()`-able). Fake-data only; mirrors the established archive pattern in one file.

## Design

### Where the seam lives

The reference time is a **caller-supplied parameter**, never `Clock.System.now()` read inside the rule. This is the same shape `RelativeTime` already uses (`now: Instant = Clock.System.now()` defaulted at the call boundary) and the same shape as the Go `ShouldArchive(c, now)`. It makes the boundary tests deterministic with literal `Instant`s — no fake clock, no scheduler.

Two new declarations in `FakeConversationRepository.kt`, plus one new public method on the class:

1. **Threshold constant** — a single named value, top-level in the file, `internal` so both the rule and its test reference one source of truth (AC: "single named constant, not a magic literal scattered across rule and tests"). `Duration` is not const-able, so it is a `val`, not `const val`:

   ```kotlin
   internal val ARCHIVE_IDLE_THRESHOLD: Duration = 30.days
   ```

2. **Pure predicate** — top-level `internal` function (mirrors the Go free function; `internal` keeps it a data-layer detail while letting the same-module test call it directly):

   ```kotlin
   internal fun shouldArchive(conversation: Conversation, referenceTime: Instant): Boolean
   ```

   Behavior: short-circuit `false` if `conversation.isPromoted`; otherwise `referenceTime - conversation.lastUsedAt >= ARCHIVE_IDLE_THRESHOLD`. Reads two fields, returns a `Bool`, cannot fail. The boundary is **inclusive** (`>=`, not `>`) — exactly-30-days-idle archives. Asserted by the boundary matrix test below.

3. **Triggered sweep** — public `suspend` method on `FakeConversationRepository` (consistent with the other `suspend` mutators):

   ```kotlin
   suspend fun sweep(referenceTime: Instant): Int
   ```

   Applies `shouldArchive` to every record in one `state.update { … }`, flipping `archived = true` on each unpromoted-and-not-already-archived match, and returns the count **newly** archived. Mirrors Go's `Sweep(reg, now) int`.

**`sweep` is added to the concrete fake only, not to `ConversationRepository`.** Auto-archive in the real backend (Phase 4) is server-side and autonomous (the Go daemon sweeps on its own ticker); a client-triggered `sweep` would be meaningless on the remote impl. Keeping it off the interface also means the test stubs that implement `ConversationRepository` (`RecordingRepo` ×3, `stubRepo`) need **zero** changes — this is what makes the ticket additive with no consumer cascade.

### `sweep` body shape

Single `state.update`. Inside the update lambda, `mapValues` over the records, copying `archived = true` onto matches. Count via a captured `var` that is **reset to 0 at the top of the lambda** — `MutableStateFlow.update` runs its lambda in a compare-and-set loop and may re-invoke it under contention; resetting per-attempt keeps the returned count correct (the final successful application wins). Contract sketch (≤ the body the developer writes; do not expand into per-field detail):

- `if (!conv.archived && shouldArchive(conv, referenceTime))` → `record.copy(conversation = conv.copy(archived = true))`, increment count; else return `record` unchanged.
- Do **not** mutate `lastUsedAt` or `isPromoted` — archiving is a lifecycle transition, not usage (matches the #93 `archive()` decision).
- Idempotent + no spurious re-emit: when nothing matches, every value is the same `ConversationRecord` instance → the new map is `equals` to the old → `StateFlow` does not re-emit (same property the existing `archive()`/`delete()` rely on).

### No construction-time sweep

`FakeConversationRepository` does **not** call `sweep(Clock.System.now())` at construction (nor derive archived-ness at read time). Two reasons:

1. **Determinism / no time-bomb.** The live seed discussions are dated May 2026. A construction-time sweep with real `now` would auto-archive them once real wall-clock crosses their +30-day mark, silently breaking the seed-count tests (`Discussions=2`, `Archived=1`) within days. A caller-supplied reference time keeps every existing test stable forever.
2. **Same Phase split as the Go port.** The *triggering* (a scheduler / lifecycle hook deciding when to sweep with `Clock.System.now()`) is the analog of the Go daemon-ticker wiring (#243), which was deliberately a separate slice. There is no real timer in Phase 1, so the trigger is deferred to Phase 4. The predicate + sweep primitive are complete and test-exercised now; this is **not** dead code, it is a primitive whose live trigger lands with the backend.

### Read-time derivation was considered and rejected

Deriving `archived` purely at read time inside `observeConversations` (without mutating the flag) would make `unarchive()` futile: the next read would instantly re-derive an idle discussion as archived, so the user could never restore it. The issue body explicitly wants auto-archived discussions to remain `unarchive()`-able. Flipping the stored flag (converging on manual-archive state) preserves that; a derivation does not.

### Imports

Add `import kotlin.time.Duration` (for the `val` type) and `import kotlin.time.Duration.Companion.days`. `Instant`, `Clock` are already imported. The `referenceTime - conversation.lastUsedAt → Duration` subtraction needs no extra import (it resolves off the existing `kotlinx.datetime.Instant` import, exactly as `RelativeTime.kt` does).

## State + concurrency model

No change to the state model. The single `MutableStateFlow<Map<String, ConversationRecord>>` remains the only source of truth. `sweep()` mutates via one `state.update { … }`; observers re-emit on the next collection because the map identity changes (only when something actually flips). No new scopes, dispatchers, or hot/cold conversions. `sweep` is `suspend` for surface symmetry with the other mutators but does not actually suspend.

## Error handling

None added. `shouldArchive` cannot fail (reads two fields). `sweep` cannot fail (composes a pure predicate with an in-memory map update); it returns `Int`, not a `Result` — there is no failure slot to fill. Unknown-id handling is not relevant — `sweep` operates over the whole store, not a single id, so there is no "unknown conversation" path.

## Testing strategy

All unit tests (`./gradlew test`); pure data-layer, no instrumented coverage. All new tests in `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` (keeps the footprint to one production + one test file).

### Boundary matrix — pins the rule (AC #2, #3, #4)

Call `shouldArchive(conv, referenceTime)` directly with tiny inline `Conversation` literals (only `isPromoted` + `lastUsedAt` matter; supply dummy values for the other required fields, or a one-line local `conv(promoted, lastUsedAt)` helper for readability). Anchor a single fixed `now = Instant.parse("2026-06-01T12:00:00Z")` and express each `lastUsedAt` as `now - <duration>` so the boundary arithmetic reads cleanly. Express each row as a scenario, not a pre-written test body:

| Scenario | `isPromoted` | `lastUsedAt` | Expect |
|---|---|---|---|
| promoted, 365 days idle | `true` | `now - 365.days` | `false` (promoted never archives) |
| unpromoted, exactly 30 days idle | `false` | `now - 30.days` | `true` (inclusive boundary) |
| unpromoted, 29d 23h idle | `false` | `now - (29.days + 23.hours)` | `false` (just under) |
| unpromoted, just over threshold | `false` | `now - (30.days + 1.minutes)` | `true` (locks the `>=` direction — catches an accidental flip to `<=`) |

Reference `ARCHIVE_IDLE_THRESHOLD` (not a literal `30.days`) where the assertion is about the threshold itself, satisfying the "named constant, not scattered literal" AC.

### Sweep integration — pins observable convergence (AC #1, #2)

Drive `sweep()` against the default repo's seeds with a fixed reference time `Instant.parse("2026-07-01T00:00:00Z")` (≥30 days after every live discussion's `lastUsedAt`, and after the channels' too — so promoted-exemption is exercised through the sweep):

- **Before:** `Discussions`=2, `Archived`=1, `Channels`=3, `All`=6 (sanity baseline).
- `val n = repo.sweep(Instant.parse("2026-07-01T00:00:00Z"))` → assert `n == 2` (only the two live discussions are newly archived; the already-archived seed is not re-counted).
- **After:** `Discussions`=0; `Archived`=3 and contains both `seed-discussion-a` and `seed-discussion-b`; `Channels`=3 (promoted channels untouched despite being >30 days idle); `All`=6 (retention — nothing deleted). Assert the two swept ids are present in `Archived` + `All` and absent from `Discussions` — this is AC #1 verbatim.

### Idempotence (mirrors `archive_isIdempotent`)

A second `sweep` with the same reference time returns `0` and leaves `Archived` at 3 (no duplicate membership, no spurious re-emit).

### Convergence proof — swept items are on the real flag path

After the sweep, `repo.unarchive("seed-discussion-a")` moves it back into `Discussions` (and out of `Archived`). This proves auto-archive lands on the identical `archived` flag manual archive uses — not a parallel hidden path — and that swept discussions stay reachable + restorable (the issue body's explicit requirement).

## Open questions

- **Re-archive after `unarchive`, on a later sweep.** Because `sweep` reads `lastUsedAt` (not "was-manually-restored"), unarchiving an idle discussion and then sweeping again with a still-future reference time will re-archive it. This is correct per the predicate's definition and harmless in Phase 1 (no automatic re-trigger exists). A "respect manual unarchive" carve-out (e.g. bump `lastUsedAt` on unarchive, or a sticky flag) is a Phase-4 concern for when a live scheduler exists — out of scope here. Flagged so review does not mistake it for a bug.

## Out of scope

- **The live trigger** (scheduler / app-lifecycle hook that calls `sweep(Clock.System.now())`) — deferred to Phase 4 alongside the real backend, mirroring the Go predicate-vs-daemon-wiring split. No real timer exists in Phase 1.
- Adding `sweep` to the `ConversationRepository` interface — fake-only; the remote impl sweeps server-side.
- A configurable / exported threshold knob — the AC pins 30 days; an exported knob is premature (Evidence-Based Fix Selection).
- Any UI change — no screen consumes `sweep` yet; `observeConversations` already renders the `Archived` filter unchanged.
- A `Clock` interface for injection — `referenceTime: Instant` *is* the injection point.

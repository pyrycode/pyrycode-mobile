# #603 — Delete the context-window stub state from `ThreadViewModel`

**Size:** XS · **Type:** deletion (net-negative diff, no new production code) · **Security-sensitive:** no

Split from #584. Siblings: #601 (freed the Status sheet), #602 (freed the status row), #591 (serves the
real figure — blocked on daemon-side pyrycode PR #1215).

## Design source

N/A — not UI-visible. Both rendering surfaces were freed upstream by #601 and #602, both merged, so this
lands with no user-visible change; only dead state is removed. The one `ThreadStatusRow` edit is a KDoc
clause and explicitly does not change what that row renders. Code-review should skip the visual-fidelity
check for this ticket.

## Files to read first

Line refs verified against `dc380aa` (current `main` tip, post-#589). The ticket body verified against
`897314e`; `git diff 897314e..dc380aa` touches none of the four files below, so every ref in the body
still holds exactly — no `+N` shift to apply.

| Path | What to extract |
| --- | --- |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:83-112` | `ThreadUiState` declaration. The three fields to delete are `:108-110`, sandwiched between `yoloEnabled` (`:107`) and `mutationsSupported` (`:111`) — both stay. |
| `…/ThreadViewModel.kt:240-275` | The `combine` block's `ThreadUiState(…)` construction. Assignments to delete are `:261-263`. Also confirms the claim below: the `stateIn` `initialValue` at `:269-274` already omits all three fields — **do not edit it**. |
| `…/ThreadViewModel.kt:918-941` | The `companion object` (`:930-935`) holding only the three `STUB_*` constants plus their `Phase 4 swap point` comment. Note the **second, unrelated** `Phase 4 swap point` comment immediately below at `:938-939` (`AUTO_SUGGESTED_CHANNEL_NAME`) — out of scope, leave it. |
| `…/ThreadScreen.kt:669-745` and `:855-899` | The 5 `@Preview` `ThreadUiState(…)` literals. Constructions open at `:675`, `:698`, `:721`, `:861`, `:884`; token args at `:680-682`, `:703-705`, `:739-741`, `:866-868`, `:889-891`. In every one the token triple is the *last* three args. |
| `…/ThreadStatusRow.kt:34-45` | The class KDoc. `:39-41` carries the clause naming `ThreadViewModel.STUB_TOKEN_PERCENT`. Read the whole KDoc — the surrounding sentences establish what must survive. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1684-1712` | The two test methods. Their surviving assertions (model / effort / yolo) and the comment at `:1689` explaining why the first one has no collector. |
| `docs/knowledge/features/thread-status-row.md:15-17,152,161,177-182,195,202` | Why #602 deliberately left this state in place, and what it says #603 owns. Context only — **do not edit this or any other doc** (see § Documentation). |
| `CLAUDE.md` | Conventions: sealed `UiState`, stateless composables, "touch only what's necessary". |

**Do not use codegraph for coverage on this ticket.** Measured this run: `codegraph_context` for this
task returned `ConversationRepository.delete` and friends — it latched onto the literal word "delete"
and produced nothing relevant. Separately, `codegraph_callers` is known to miss `@Composable` call sites
in this tree (measured on `ThreadStatusRow` during #602). The grep in AC 5 is the coverage proof; treat
it as the instrument, not as a formality.

## Context

`ThreadViewModel` carries three constants marked *"Phase 4 swap point: replace with backend AgentStatus
flow."* The swap never happened. They are assigned into `ThreadUiState.tokenPercent` / `tokensUsed` /
`tokensTotal` and were rendered as fact by the status row and the Status sheet. #601 replaced the
sheet's render with "Context usage unavailable"; #602 dropped the row's segment. Both merged, both
deliberately left `ThreadViewModel` untouched so each could ship standalone. Nothing reads the fields
now. This ticket deletes them.

#591 reintroduces state when it has a real figure to carry. Leaving a fake in place until then just
re-invites the same bug.

## Design

Pure deletion. No new types, no new files, no signature changes to any public composable, no wire work.

### `ThreadUiState` — remove three fields

Delete `tokenPercent`, `tokensUsed`, `tokensTotal` (`ThreadViewModel.kt:108-110`). All three are
`Int = 0`-defaulted. The class keeps every other field, including `yoloEnabled` and `mutationsSupported`
that bracket them.

**Do not replace them with a nullable, a sealed `Unavailable | Known(...)`, or a placeholder "ready for
#591".** There is exactly one possible value today — unavailable — and modelling a variant that cannot
occur is speculative generality. #591 designs the shape it needs against real wire data.

### `ThreadViewModel` — remove the population and the constants

Delete the three `combine`-block assignments (`:261-263`) and the entire `companion object`
(`:930-935`). The companion holds those three constants and their comment and nothing else, so it is
removed outright rather than left as an empty block.

Verified safe: nothing in `app/src` references `ThreadViewModel.Companion` or any `STUB_TOKEN*` symbol
from code — the only tree-wide hit outside the constants themselves is the `ThreadStatusRow` KDoc prose
handled below.

### `ThreadScreen` previews — drop the token args

Strip the three token args from each of the 5 preview `ThreadUiState(…)` literals. In every case they
are the trailing args, so the preceding arg (`items = …` in four of them, the `queuedMessages` block in
the third) becomes the last — keep its trailing comma. This repo's ktlint config enforces trailing
commas; `spotlessApply` will fix it if the comma is dropped by accident.

### `ThreadStatusRow` KDoc — one clause

The KDoc's second sentence group currently reads (`:39-41`):

> The percentage backing it was never measured — it was `ThreadViewModel.STUB_TOKEN_PERCENT`, a
> constant — so this row renders two segments rather than editorialising about a fabricated number.

Three constraints, in priority order:

1. The symbol name `STUB_TOKEN_PERCENT` is gone.
2. The sentence still explains **why** the row renders two segments rather than the three Figma `16:58`
   specifies: the percentage was hardcoded and never measured.
3. No code in the file changes, and no other sentence in the KDoc changes meaning.

A wording that satisfies all three — not binding, any equivalent is fine:

> The percentage backing it was never measured — it was a hardcoded constant, deleted in #603 — so this
> row renders two segments rather than editorialising about a fabricated number.

**Rewrapping the affected lines is expected and is not a violation of "nothing else changes."** That
constraint is about meaning, not about line breaks: a shorter clause reflows the `:39-41` wrap. Preserve
the surrounding ~100-column wrap width used by the rest of the KDoc.

### Edit order — consumer-first

Free the consumers before deleting the state, so the tree compiles at every intermediate step:

1. `ThreadScreen.kt` previews (still compiles — the fields are still there, just defaulted).
2. `ThreadViewModelTest.kt` assertions + renames (same).
3. `ThreadStatusRow.kt` KDoc (comment-only, order-independent).
4. `ThreadViewModel.kt` fields + `combine` assignments + `companion object` — **last**.

Reversing this leaves a broken tree between steps for no benefit.

### Why the compiler will not find the call sites for you

`ThreadUiState` is constructed at 19 sites tree-wide (one is the `data class` declaration itself):

| Location | Sites | Passes token args? |
| --- | --- | --- |
| `ThreadScreen.kt` previews | 5 | **yes — this ticket edits them** |
| `ThreadViewModel.kt` (decl, `combine`, `stateIn` initial) | 3 | decl + `combine` only |
| `androidTest` (6 files) | 7 | no |
| `ThreadScreenMapperTest.kt` | 3 | no |
| `ThreadViewModelTest.kt` | 1 | no |

The 13 sites outside `ThreadScreen.kt`'s previews all use named args and none pass token args, so
deleting *defaulted* fields leaves them compiling untouched. **Silence from the build is the predicted
outcome, not evidence of coverage.** Same defaults-hide-staleness shape that bit #601.

## State + concurrency model

Unchanged. No `viewModelScope` job, `StateFlow`, dispatcher, or cancellation behaviour is touched. The
`combine` → `stateIn(WhileSubscribed(5_000))` pipeline keeps its arity, its upstream flows and its
initial value; it emits a `ThreadUiState` with three fewer fields.

One incidental, positive effect: the emitted `ThreadUiState` loses three constant-valued fields, so
`data class` equality is computed over a slightly smaller tuple. Since the removed values were constant,
no emission that was previously distinct becomes conflated — `distinctUntilChanged` semantics anywhere
downstream are unaffected. Recomposition behaviour of every composable reading this state is unchanged
(none of them read the removed fields — that is what #601 and #602 established).

## Error handling

No failure modes added or removed. The deleted fields fed no branch, no validation, no clamp — #602
already deleted the last thing that did (`tokenPercentColor`'s `coerceIn(0, 100)`). There is no
runtime path to change.

## Testing strategy

No new tests. Two existing JVM unit tests in `ThreadViewModelTest` are edited.

**`state_initialValue_includesDefaultModelEffortAndTokenPercentDefaults` (`:1685`)** — drop the three
trailing `assertEquals(0, …)` token assertions. Keeps `selectedModel` / `selectedEffort` / `yoloEnabled`
and its no-collector comment.

**`state_postSubscription_emitsDefaultModelEffortAndTokenPercent` (`:1699`)** — drop the three trailing
`assertEquals(73 / 146_000 / 200_000, …)` assertions. Keeps the collector, `advanceUntilIdle()`, the
same three surviving assertions, and `collector.cancel()`.

**Rename both** so the name stops advertising token coverage. `…EffortAndYolo` on both is one pair that
works; any name that stops claiming the deleted assertions is acceptable.

**Keep both tests — they do not collapse into duplicates.** After stripping, their assertions coincide,
but they pin different things: `state_initialValue_…` reads the *pre-subscription* `stateIn` initial
value (the `data class` defaults), while `state_postSubscription_…` collects and `advanceUntilIdle()`s
to prove the `combine` block emits the preference-derived config. The values match only because the
preference default happens to be `OPUS_4_7` / `HIGH`. Deleting either loses real coverage — this is
exactly the "these look like duplicates now" reflex that has to be resisted.

### Gates

```
./gradlew testDebugUnitTest --tests "de.pyryco.mobile.ui.conversations.thread.ThreadViewModelTest"
./gradlew test lint spotlessCheck
./gradlew compileDebugAndroidTestKotlin
```

The single-class task is the fast loop; bare `test` is an aggregate and rejects `--tests`.

**`compileDebugAndroidTestKotlin` is not optional here, and it is the one gate the AC list misses.**
`test`, `lint` and `assembleDebug` do not compile `androidTest`. Seven `ThreadUiState(…)` sites live
there, and this ticket deletes fields from that class — so the AC's three gates could all go green with
`androidTest` broken. The grep says those seven sites pass no token args, so the expected result is a
clean compile; run it anyway to convert an inference into a fact. It is cheap.

No instrumented (device) run and no live rung are needed: there is no daemon interaction to exercise,
and no rendered output changes.

### The grep is the acceptance test

```
grep -rni "tokenPercent\|tokensUsed\|tokensTotal\|STUB_TOKEN" app/src --include="*.kt"
```

Must return zero hits. **The `STUB_TOKEN` term is load-bearing — do not drop it when running this.**
The narrower three-field grep is case-sensitive and does not match `STUB_TOKEN_PERCENT`, and
`ThreadStatusRow.kt:40` carries that symbol in plain backtick prose rather than a KDoc `[link]`, so
neither the compiler nor `lint` nor `spotlessCheck` sees it either. Without the widened term every gate
goes green while a dangling reference to a deleted constant survives in production source. `STUB_TOKEN`
as a prefix covers all three constants in one term.

Verified this run: the widened grep currently matches exactly the four files this ticket touches and
nothing else in `app/src`. A bare `token` grep would over-match hard against push tokens, pairing tokens
and Noise key material — do not widen further.

## Documentation

**Not this ticket's AC.** The developer's worktree should mutate only `app/src` and this spec file; the
knowledge base is the documentation phase's to update after merge. Recording the surface here so that
phase does not work from an undercount.

The ticket body names four stale lines. The actual surface is wider — these assert in the present tense
that the fields still exist, and go stale the moment this lands:

- `docs/knowledge/features/thread-status-row.md` — `:15`, `:17`, `:141-143`, `:152`, `:161`, `:169`,
  `:173`, `:177`, `:179-182`, `:195`, `:202`, `:214`
- `docs/knowledge/features/status-sheet.md` — `:30`, `:339`, `:351`, `:366`
- `docs/knowledge/features/thread-screen.md` — `:986-987` (names both test methods and their
  `tokenPercent` assertions; the renames make these wrong too)

`docs/knowledge/features/connection-status-line.md:98` is **not** stale — it references
`tokenPercentColor`, which #602 already deleted, and is out of scope for #603.

Historical records — `docs/knowledge/codebase/*.md` and `docs/specs/architecture/*.md`, including
`230-status-sheet-context-window.md:201,206` and `253-thread-viewmodel-selected-model.md:136` which
name the two tests by their pre-rename names — describe what was true at their time and must **not** be
rewritten.

## Open questions

None. Ordering is fully discharged: #601 and #602 freed both rendering surfaces and #608 (the
`ThreadScreen.kt` file-overlap blocker) merged as `897314e`. #608 added no `@Preview`, so the risk that
prompted the block did not materialise. The architect file-overlap check re-ran against all
`origin/feature/*` branches on `dc380aa` and found no branch touching any of the four files.

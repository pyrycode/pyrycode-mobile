# #1909 — Unsigned history identity and repository ordering

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `HistoryEntry` and `HistoryPage` retain raw replay content and newest-first page order.
- `app/src/main/java/de/pyryco/mobile/data/network/HistoryPayloads.kt`: `HistoryEntryDto` and `toHistoryPage` are the numeric/timestamp boundary.
- `app/src/main/java/de/pyryco/mobile/data/network/ReadMarkIdSerializer.kt`: strict uint64 JSON parsing already rejects quoted, fractional, negative and overflowing tokens.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `reduceOrderedHistoryPage`, `mergeRows`, delta identities and contextual compaction positions.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `mergeHistoryPage`, `withFilledDividerOrder` and `observeSnapshot` publish one atomic generation.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadSnapshotSource.kt`: `ThreadSnapshot` supplies cache consumers with signed positions today.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryCoverage.kt`: `received` certifies signed coverage and must remain conservative for unsupported pages.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: signed snapshot order feeds existing cache joins; this consumer is not migrated.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `launchHistoryAsk`, `recordCoverage` and `historySeed` independently settle/save/restore the signed walk.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: the existing background-agent scenario closes its child run before terminal rendering; navigation preserves that closed state.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocksScreenTest.kt`: `finishedRosterReplacementKeepsMarkerAndNavigationInCollapsedRun` proves navigation does not expand a run.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryCacheReworkTest.kt`: production ViewModel and fresh file-cache regressions for conservative completeness.
- `app/src/test/java/de/pyryco/mobile/data/network/HistoryPayloadsTest.kt`, `data/repository/HistoryReconciliationTest.kt`, `ThreadProjectionTest.kt` and `RemoteConversationRepositoryTest.kt`: current decoding, reconciliation, contextual folds and request failure routing.
- `docs/knowledge/features/data-model.md` and `remote-conversation-repository-reads-and-thread-store-history-paging.md`: preserve the contextual fold and held-row placement; a row key is not coverage proof.
- Sibling `pyrycode/docs/protocol-mobile.md`, “A history entry”, “Joining a page to the live stream” and “Security model”: authoritative wire contract. The dispatch worktree resolves this sibling via `PYRYCODE_SRC`.

## Context

Signed history ids cannot represent the daemon's full uint64 identity space. This foundation exposes exact unsigned identity and repository order while leaving persisted coverage/cache/paging migration to dependent work. No read command, viewport checkpoint, UI change or wire producer change belongs here. No decision record is needed.

Sizing: about 750–950 written lines including the plan and probes, six production files, no new public type, at most three internal helper declarations and fewer than ten simultaneous consumer updates. Three acceptance criteria and four numeric rejection classes fit the ticket limits. Existing signed construction sites remain; three signed-only fixture reads may need explicit nullable projection assertions. Remote feature branches have no overlap with the planned production files.

## Design

`HistoryEntry.unsignedId: ULong` is authoritative and positive. A secondary signed `id: Long` constructor preserves existing positional/named fixtures in their positive range. The signed `id: Long?` reading returns the exact lower-range value or null above `Long.MAX_VALUE`, never a substituted identity. Raw payload/type/timestamp are unchanged.

`HistoryEntryDto.id` uses the existing strict `ReadMarkIdSerializer` to preserve uint64 numeric tokens. Mapping rejects zero via the positive domain contract. Invalid pages fail the awaiting request, leaving the inbound collector alive.

`ReducedHistoryPage` carries unsigned row/delta order and claim sets from the contextual fold. Signed `order`/`claims` views expose representable values only. The reducer still reverses daemon-authoritative page order and deduplicates through existing folds. Unrecognized history row keys use the exact unsigned decimal id, retaining lower-range keys.

`mergeRows` compares unsigned positions in its durable-position tree without reordering held rows. Keep the existing signed merge entry point for compatibility and translate positive signed positions to unsigned internally. A separate unsigned merge entry point serves `ThreadProjection`; cache joins remain signed consumers.

`ProjectionState` holds unsigned order per conversation. Divider identity transfer carries unsigned order unchanged. `observeSnapshot` publishes unsigned and representable signed positions alongside rows/suppression from the same generation. The source repository supplies host scope and the observation argument supplies conversation scope; these maps are not globally scoped identity stores. Live connection/ring ids and row keys never create claims.

`HistoryCoverage.received` declines claims from a whole page containing an unrepresentable id and marks coverage unknown with a sticky `unsignedIncomplete` compatibility flag. Later signed terminal pages cannot clear this uncertainty. `ThreadViewModel` prevents terminal walk settlement for incomplete signed coverage, retaining the request cursor and a demandable failure state; side and gap asks reopen a previously complete walk. Cache writes, cache reads and ViewModel seeding reject `atStart` whenever this flag is present. Existing lower-range behavior and opaque cursor handling remain; the additive flag records omitted-content uncertainty without migrating any ids or persisted unsigned claims.

## State and concurrency model

No new jobs, flows, scopes or dispatchers. The existing coverage record gains only the omitted-content compatibility flag, defaulted for prior cache documents. `ThreadProjection` continues its atomic `MutableStateFlow.update` merge with the current generation's held rows and unsigned order. Snapshot derivation reads both maps and rows together. Connection shutdown/remove discards the projection exactly as before; durable numeric identities remain daemon-authored and reusable across reconnect, never synthesized from connection-local state.

## Error handling

Strict structural decoding rejects noninteger/non-numeric, negative and overflowing identities; positive domain validation rejects zero. Exceptions have static content-free messages and stay within the existing awaiting request Result boundary. Malformed per-entry payloads still cost only their existing reducer entry. Timestamp parsing semantics remain unchanged. Unsigned-incomplete terminal settlement reuses the existing permanent-failure walk state, retaining a cursor and ordinary reader demand. A static `history_completeness_unavailable` event classifies this compatibility rejection. No logs contain ids, timestamps or raw payload text.

## Testing strategy

First run a decoder regression against current code and observe the upper-range failure, then implement. Add unit probes using actual production decoding/reduction/projection:

- Exact identity at 1, signed maximum, signed maximum + 1 and unsigned maximum, retaining raw payload and timestamps; invalid token shapes and signed construction compatibility.
- All arrival permutations of disjoint equal/reversed-clock pages across the boundary, empty/singleton pages, start/middle/end overlap and repeated delivery, preserving held rows on both sides.
- Folded assistant deltas across the boundary with duplicate/replayed deltas and tool separators, plus contextual compaction divider identity transfer.
- Same row/numeric keys in different conversations and projection instances; live-only rows have no durable claims. Reconnect by constructing a fresh projection between deliveries.
- Signed snapshot/reducer views omit upper ids. Fresh upper-only/mixed pages and previously complete coverage cannot certify completeness through ViewModel settlement, cache write/read or reopen. Later signed terminal pages retain unsigned uncertainty; nonterminal cursor progression remains usable.
- Correlated malformed-id request followed by a valid upper-id request proves collector survival.

Run focused decoder, reducer, history/projection, coverage/cache and remote-repository unit classes with nonzero counts; lint, assembleDebug and forced Spotless. No new operator-facing scenario is required: this is a data foundation. Rework finding 2 requires the existing device-only `background-agent` scripted scenario, whose isolated daemon/live network and emulator semantics cannot run on Robolectric. Preserve its final child ownership/uniqueness assertions and explicitly open the child run that the scenario closed; also run existing `BackgroundAgentBlocksScreenTest` coverage for the navigation contract. After the final merge/push run the whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle` against the prepared PR body.

## Open Questions

None. Persisted unsigned coverage/cache/paging consumers are explicitly dependent deliverables.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, history-page fold/join explanation: document authoritative unsigned row/delta order and conservative signed consumers.
- `docs/knowledge/features/data-model.md`: document positive `HistoryEntry.unsignedId`, compatible signed construction and nullable signed identity projection.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] MUST FIX addressed in design: default numeric coercion or signed wrap could certify a different history entry. `ReadMarkIdSerializer` strictly parses uint64 JSON tokens; `HistoryEntry` rejects zero and signed construction rejects nonpositive ids. Probe malformed and boundary values.
- [Tokens, secrets and credentials] No secret fields, credential storage or authentication changes. Numeric history ids are not capabilities; never use them outside host/conversation scope.
- [Files and storage] MUST FIX addressed after verifier finding 1: declining page claims alone did not constrain the independently saved `atStart`. Persist sticky `unsignedIncomplete` beside unknown coverage, preserve it through signed terminal pages and row binding/retention, and guard receive, walk settlement, cache save/read and ViewModel seed. Fresh upper-only/mixed pages and previously complete coverage are tested through real `FileConversationCache` instances and ViewModels. No unsigned persisted identity migration is introduced; #1910 owns it.
- [Android attack surface] No Android component, intent, deep link, keyboard, provider or WebView changes; existing inert thread render paths remain.
- [Cryptography] No cryptographic primitive, key or nonce changes. Existing Noise authentication keeps the relay outside the plaintext identity boundary.
- [Network and I/O] Existing envelope caps, request timeouts, TLS and reconnect policy remain. Strict parsing rejects hostile numeric tokens without logging their content; request failure does not terminate inbound collection.
- [Errors, logs and telemetry] Reuse static rejection messages and existing content-free request diagnostics. No raw payload, message text, credential or decoder exception text is logged.
- [Concurrency] Unsigned positions and rows commit in the same existing CAS update; snapshots derive both compatibility and authoritative maps from one generation. No coroutine or mutex added.
- [Threat model] Malicious relay reorder/delay remains handled by Noise and existing replay/reconciliation; hostile daemon identities are rejected at the strict decoder/domain boundary. Token theft and UI screenshot/accessibility leakage are unchanged by this data-only work and remain with existing Keystore/render policy.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07

## Revisions

- 2026-10-07: Kotlin erases `ULong` and `Long` constructor parameters to the same JVM signature. Put `unsignedId` last in the primary constructor, retaining the original signed secondary constructor's parameter order. Named unsigned construction and all existing signed fixtures remain supported; identity/projection contracts are unchanged.
- 2026-10-07: Compilation identified two signed-only read-mark fixture reads as well as the fake-history accumulator; use `requireNotNull` on their unchanged lower-range inputs. No constructor fixtures or production read-mark paths migrate.
- 2026-10-07: The reverse singleton/live-overlap probe exposed pre-existing late placement behavior also reproduced with ids 1–4. File #1913 and retain `lateDurableEvidence_doesNotStrandLiveDeltaBeyondItsHistorySeparator` ignored against it; repairing provisional live placements would change reconciliation outside this representation ticket. The unsigned overlap probe establishes the held live delta's durable position before surrounding pages and still tests both page orders, replay and exact claims.

- 2026-10-07: Verifier finding 1 proved that separately persisted `atStart` bypassed the page-claim guard. Add sticky omitted-unsigned-content uncertainty and guard walk receive/save/settle/restore, including side asks after complete history and later signed terminal pages. Three production ViewModel/cache regressions failed first on missing unknown coverage. Security review updated to cover both completeness channels. Local additive overlap exists with #1818 and #1842 in `ThreadViewModel`; scripted test method also shares its file with #1731, #1766, #1818, #1833, #1842 and #1904.

- 2026-10-07: Verifier finding 2 identified missing terminal child semantics in the scripted background-agent test. The scenario closes its child run, while current `ThreadScreen` navigation only scrolls and completion retains the closed state, as asserted by `BackgroundAgentBlocksScreenTest`. Explicitly tap that run before the unchanged final child display, ownership and uniqueness assertions. No production background-agent behavior changes.

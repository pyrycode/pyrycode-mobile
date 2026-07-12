# Spec #578 — populate `Conversation.currentSessionId` on the live wire path

**Size:** S (comfortably; ~15 production lines in 1 file + ~3 focused unit tests — near the XS/S line. PO sized S; kept.)
**Security-sensitive:** yes (session ids are sensitive; the fold feeds a wire-targeting id). Security review pass at the end of this doc — verdict **PASS**.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:437-454` — the `TYPE_SESSION_TRANSITION` demux arm. **This is the one call site you edit** — add a sibling write inside the existing `interactive`-gated `.let` block, alongside `appendSessionBoundary`.
- `RemoteConversationRepository.kt:587-593` — `decodeSessionTransition`; returns `Pair<String, ThreadItem.SessionBoundary>`. The `boundary` already carries `newSessionId` — no decode-signature change needed.
- `RemoteConversationRepository.kt:922-957` — `upsertConversation` / `removeConversation`; the `projection.update { … }` CAS idiom your new helper mirrors. Note `removeConversation`'s idempotent-`filterNot` posture (element-equal list ⇒ StateFlow conflation ⇒ no re-emit) — your helper relies on the same property for the absent-conversation no-op.
- `RemoteConversationRepository.kt:143-156` — the `projection` field KDoc: `MutableStateFlow<List<Conversation>?>`, `null` until the first `conversations` snapshot loads. Your write must be a safe no-op against `null`.
- `RemoteConversationRepository.kt:959-966` — `observeConversations`; how the projection surfaces (`filterNotNull().map { project(…) }`). This is what the AC #4 test asserts against.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:5-14` — the `Conversation` data class; `currentSessionId: String`. `.copy(currentSessionId = …)` is the write.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:232-262` — `SessionTransitionPayloadDto` (`new_session_id` field) + `toBoundary()`. **Read the KDoc at :247-251** — on `idle_evict` the wire carries the evicted id verbatim in both `previous`/`new`; both copy through unchanged. Informs the "write verbatim, no reason-branching" decision below.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:250-259` — `ThreadItem.SessionBoundary` (carries `newSessionId`).
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:4162-4350` — the #336 fold tests + the `sessionTransitionEnvelope(convId, prevId, newId, reason, [workspaceCwd], [occurredAt])` helper. Your new tests slot in here and reuse this helper.
- `RemoteConversationRepositoryTest.kt:70-103` — `seed_surfacesMappedListSortedByLastUsedDescending`; shows how to seed a `conversations` snapshot and asserts `currentSessionId == ""` at line 99. **Your AC #4 test builds on this fixture** (seed a snapshot first, then push the transition) — unlike the #336 thread tests, which need no prior snapshot.
- Memory lessons (not in codegraph): `remote-repo-test-runcurrent-not-advanceuntilidle` — drive push→demux→project→emit with `runCurrent()`, never `advanceUntilIdle()` (no timers on this path); `two-folds-repo-wins-vm-dormant` — the canonical fold lives in the repo; the ViewModel copy is id-guard-suppressed, so test the repo fold.

## Context

Session-scoped mutations on the relay path go out with an **empty** session id: `set_session_settings` reads `conv?.currentSessionId ?: ""` (`ThreadViewModel.kt:256`) and forwards it verbatim (`RemoteConversationRepository.kt:1631`). `Conversation.currentSessionId` is hardcoded `""` on every live decode path — the v2 `conversations` summary and create/update replies omit session identity (v2 payload-shape SSOT), so `toConversation()` defaults it. The daemon answers `session.not_found` and the UI reverts.

The **one** place the phone can learn the live session id on the relay path today is the `session_transition` event, whose payload carries `new_session_id`. The #336 fold (commit `35dfbfb`) already routes that event by `conversation_id` and decodes a `ThreadItem.SessionBoundary` — but folds a **thread-boundary row only**, explicitly leaving session-identity resolution out of scope. This ticket adds the sibling write: fold `new_session_id` into the projection `Conversation.currentSessionId`.

Downstream consumers are unchanged — once the projection carries the id it flows to the UI state and onto the wire with **no consumer-side edit** (Technical Notes; confirmed by codegraph — `currentSessionId` is read at `ThreadViewModel.kt:256`, no other writers on the live path).

## Design

### The write

Extend the existing `TYPE_SESSION_TRANSITION` arm (`RemoteConversationRepository.kt:437-454`). Inside the already-present `if (CAPABILITY_INTERACTIVE in negotiatedCapabilities())` gate and the `decodeSessionTransition(envelope)?.let { (conversationId, boundary) -> … }` block, add a second write next to `appendSessionBoundary`:

```
appendSessionBoundary(conversationId, boundary)
updateCurrentSessionId(conversationId, boundary.newSessionId)   // new — sibling fold
```

`boundary.newSessionId` is the decoded `new_session_id`; no change to `decodeSessionTransition`'s return type.

New private helper, mirroring `upsertConversation`'s `projection.update` idiom but **field-updating an existing entry only** (never inserting):

```
private fun updateCurrentSessionId(conversationId: String, newSessionId: String)
// projection.update { current ->
//     current?.map { if (it.id == conversationId) it.copy(currentSessionId = newSessionId) else it }
// }
```

Behavior contract (asserted by the tests below):
- **Present conversation** → its `currentSessionId` becomes `newSessionId`; `observeConversations` re-emits the updated `Conversation` (AC #1, #4).
- **Absent conversation** → `map` returns an element-equal list (every entry taken via the `else it` identity branch), so `StateFlow` conflation suppresses re-emission and no entry is created (AC #2). No `conversationId` guard needed — the map *is* the guard.
- **`null` projection** (pre-first-snapshot) → `current?.map` is `null`; setting `null` over `null` is a no-op, and `observeConversations` filters `null` anyway. Safe.

### Why verbatim, no reason-branching

Write `boundary.newSessionId` for **every** reason — no `when (boundary.reason)`. Rationale:
- Mirrors `appendSessionBoundary`'s verbatim posture and the `toBoundary()` "copy `new_session_id` through unchanged" contract.
- The useful path — `clear` — carries the freshly-rotated-to session id; writing it is exactly right.
- `idle_evict` carries the evicted id verbatim (InteractivePayloads.kt:250); writing it equals the already-active id in the common case → element-equal → no-op. `workspace_change` has **no server source today** (never fires; see Open questions).
- A reason branch would be a state-machine arm for an unobserved need — against evidence-based fix selection and the #659 server-side precedent ("don't pre-build a defense for an unobserved need").

### Two sibling writes, different absent-conversation behavior (call this out to reviewers)

`appendSessionBoundary` writes to `threadByConversation` **unconditionally** — an orphan boundary for a conversation no list-collector observes "simply sits unread in the map" (the #336 posture). `updateCurrentSessionId` writes to the list `projection` and **must not** create a phantom list entry (AC #2). The asymmetry is intentional: the thread map tolerates orphan rows; the conversation list must not gain phantom conversations. Do not "unify" the two.

### Single source of state

Preserved. `projection` remains the sole `StateFlow<List<Conversation>?>`; the new write is one more `projection.update` alongside the four existing folds (snapshot, upsert, remove, workspace). No parallel mutable state.

## State + concurrency model

- No new coroutine, no new `StateFlow`, no dispatcher change. The write runs on the existing single inbound-consumer path (`onInbound`), same as `appendSessionBoundary`.
- `MutableStateFlow.update` is an atomic CAS, so a concurrent authoritative `conversations` snapshot (which replaces the whole list) and this in-place field update retry-merge rather than clobber — identical to `upsertConversation`'s concurrency contract.
- Ordering of `appendSessionBoundary` vs `updateCurrentSessionId` is irrelevant: they touch disjoint `StateFlow`s (`threadByConversation`, `projection`).

## Error handling

No new failure surface. Decode/parse failures are already absorbed by `decodeSessionTransition`'s `try/catch (IllegalArgumentException)` (SerializationException ⊂ IAE) → `null` → the `.let` block (including the new write) never runs. An unrecognized `reason` yields `toBoundary() == null` → same drop. The new write executes only on an already-validated boundary, so it cannot throw (`String.copy` field-set, `List.map`).

## Capability gating & logging (AC #3)

- **Gate:** the new write sits inside the existing `if (CAPABILITY_INTERACTIVE in negotiatedCapabilities())` block, so it inherits the interactive-only gate identically to the #336 boundary fold. A non-interactive phone folds nothing.
- **Logging:** the helper adds **zero** log calls. `newSessionId` / `conversationId` never touch a log sink — the security posture inherited from the #336 fold (session ids are sensitive; a logged id is a leak).

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"`). Plain-JVM `runTest`; add tests in the #336 block (~line 4350), reusing `FakeSessionPump`, `sessionTransitionEnvelope(…)`, `conversationsEnvelope(…)`, `collectConversations(…)`, and driving with `runCurrent()` (never `advanceUntilIdle()` — no timers). **Each test must seed a `conversations` snapshot first** so the target conversation exists in the projection (contrast with the #336 thread tests, which need no snapshot).

Scenarios (bullet form; write bodies in the project idiom):

- **AC #1/#4 — present conversation gets the id, and it surfaces.** Seed a snapshot with `c1` (`currentSessionId == ""`). Push `sessionTransitionEnvelope("c1", "s1", "s2", "clear")`. Assert `observeConversations(All)`'s last emission has `c1.currentSessionId == "s2"` (and `c1` is still present, unduplicated).
- **AC #2 — absent conversation is a no-op.** Seed a snapshot with `c1` only. Push `sessionTransitionEnvelope("c2", "s1", "s2", "clear")` (c2 absent). Assert the last emission is unchanged: exactly `[c1]`, `c1.currentSessionId == ""`, no `c2` entry. (Optionally assert emission count didn't grow, pinning the conflation-suppresses-no-op contract.)
- **AC #3 — capability gate closed.** Construct with `negotiatedCapabilities = { emptySet() }`. Seed a snapshot with `c1`. Push a well-formed `clear` transition for `c1`. Assert `c1.currentSessionId` stays `""` (fold suppressed). Mirror `sessionTransition_capabilityGateClosed_foldsNothing` (:4204).

Optional (nice-to-have, not required by ACs): a `null`-projection transition (push a transition before any snapshot) emits nothing and does not crash — pins the `current?.map` null-safety.

No instrumented tests; no UI change (the consumers already read `currentSessionId`).

## Open questions

### Load-bearing: does #545's happy path also need a server change? — **Determined: YES; a `pyrycode/pyrycode` ticket is filed.**

The ticket's explicit architect question: whether the *initial* conversation load needs a daemon-emitted session id, so the `conversations` snapshot carries session identity **before any `session_transition` fires**.

**Determination — required.** Evidence, primary sources:
- **#545's body** defines its happy path as *"pair, open a session, change a setting from the Status sheet"* — **no `/clear` / new-session step**, so no `session_transition` fires before the mutation.
- **Server-side #659/#657** (the `session_transition` producer): the daemon emits `session_transition` **only** on `/clear` (carries a usable `new_session_id`) and eviction (`NewID` left **empty** — "an eviction has no successor session"). Startup reconcile "rotates directly, so no spurious boot signal"; `workspace_change` has **no server source today**. There is **no** session-start / on-connect emit.
- The v2 `conversations` summary **omits** session identity (v2 payload SSOT), so `toConversation()` defaults `currentSessionId` to `""`.

Therefore a phone that pairs → opens → changes a setting, with no `/clear` in between, **can never resolve a live session id** via this fold — the fold is necessary but not sufficient for #545's happy path as written. This is not speculative: it is proven by the emit-trigger set, and it is a real product gap (configuring the current session without first clearing is the normal flow, and it fails with `session.not_found` today).

**Action taken (keeps #578 scoped to the mobile fold):**
- Filed **`pyrycode/pyrycode#940`** requesting the v2 `conversations` summary payload carry the active session id per conversation (so `toConversation()` maps it instead of defaulting `""`). No existing open server ticket covered this (#738/#741 are the CLOSED `conversation_id`-stamping concern, a different field).
- Posted an informational note on #545 recording that it depends on **both** #578 (this fold, landing now — covers the post-transition case) **and** `pyrycode/pyrycode#940` (initial-load identity — covers the pre-transition case). Cross-repo blocker relations aren't wired via `addBlockedBy`; the dependency is documented in prose.

**#578 remains correct and lands regardless** — it is the only mechanism by which the phone learns the live session id after a `/clear`/new-session on the relay path, and consumers already source it.

### Minor (deferred): `idle_evict` writing a stale/empty id
If the wire ever delivers an empty `new_session_id` (or the evicted id after the session is gone), the fold writes it verbatim, potentially resetting `currentSessionId` to a dead/empty value. No emptiness guard is added — evidence-based fix selection: no observed empty-`new_session_id`, and the reset floor is `""` (the pre-fold state), not a true regression. Revisit only if observed. This is the same class of gap as the initial-load question above (session-start emits no id) and is not fixable in this fold.

## Security review (security-sensitive)

Adversarial self-review of this design. Assume a **buggy or hostile daemon** post-handshake (the Noise_IK peer is authenticated but may misbehave). Verdict per category, then overall.

- **Trust boundary / routing (cross-conversation leak).** The write routes strictly by the payload's own `conversation_id` (`decodeSessionTransition` returns it; the map keys on `it.id == conversationId`). A boundary structurally cannot cross-route into another conversation's entry — identical to `appendSessionBoundary`'s enforced-in-code routing (RemoteConversationRepository.kt:444). No new cross-route surface. A hostile daemon could set conversation A's `currentSessionId` to any string, but the daemon **owns all session state and enforces its own authorization** on `set_session_settings`; the phone can only ever target sessions the daemon told it about. No privilege escalation — same trust model as every other decoded field. *PASS.*
- **Injection / phantom entry.** `updateCurrentSessionId` uses `List.map` over existing entries — it **cannot create** a projection entry. A `session_transition` for an unknown `conversation_id` is a no-op (AC #2), so a hostile daemon cannot inject a phantom conversation via this path. *PASS.*
- **Sensitive-data logging / leak.** The helper adds no logging; `newSessionId` / `conversationId` never reach a log sink (AC #3). Inherits the #336 never-log posture. *PASS.*
- **Capability gating (fail-closed).** The write is inside the existing `interactive` gate; a non-interactive phone (which never advertised the capability) folds nothing even if a daemon sends a spurious `session_transition` — defence in depth, mirroring the sibling live-session / stall / queue-state arms. *PASS.*
- **DoS / resource.** One `List.map` per validated `session_transition` on the existing inbound path; no unbounded growth (the projection size is bounded by the conversation count, unchanged). Malformed payloads are dropped upstream at the decode boundary before the write runs. *PASS.*

**Overall verdict: PASS.** No FAIL findings; no spec revision required. The design adds no new inbound handler, no new dispatch policy, no crypto/nonce/net surface — it reuses an already-gated, already-routed fold and adds one projection field-update with strict present-only semantics.

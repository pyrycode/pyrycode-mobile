# #192 — `SessionBoundary` carries authored trigger reason + workspace path

Data-layer only. No UI changes.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:71-90` — `ThreadItem` sealed interface, `SessionBoundary` (4 fields today), `BoundaryReason` enum. The new field goes on `SessionBoundary`; the enum is unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:49-80` — `observeMessages` projection and the private `buildThreadItems`. Today `buildThreadItems` walks sorted messages and hard-codes `reason = BoundaryReason.Clear` for every derived boundary. This is the function whose hard-coding the ticket removes.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:249-253` — `ConversationRecord` (private data class: `conversation`, `sessions`, `messages`). This is where the new boundary-metadata map gets added.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:255-422` — the seed `companion object`: `buildSeedRecords`, three `seedChannel(...)` calls, the `SeedSession` private fixture type (line 442), and `seedChannel` itself (line 450). These are the authoring sites for the per-historical-session reason+path data.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:44-80` and `:180-215` — existing boundary tests. The `observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary` test at :180 passes its own `initialMessages` (no seed authoring) and currently asserts `BoundaryReason.Clear`. That assertion must continue to hold via the fallback path described in **Design**.
- `docs/knowledge/features/conversation-repository.md:39-101` — the deferred follow-up note that explicitly says eager boundary reason metadata "can be added to `Session` or `ConversationRecord` and threaded through here." This ticket is the *when*. (Read-only — documentation phase owns this doc.)
- `docs/knowledge/codebase/9.md:32` — same deferral, viewed from #9's side. Cross-check: keep the design tight against the constraints #9 named.
- `CLAUDE.md` (repo root) — "Conversations model" section, specifically the bullet that names the three delimiter triggers (`/clear`, idle-evict, workspace change) and the per-delimiter UX. The three seed channels in this ticket each model one trigger.

## Context

`BoundaryReason` already enumerates the three variants (`Clear`, `IdleEvict`, `WorkspaceChange`) the delimiter UI (#135, downstream) needs to render. But `FakeConversationRepository.buildThreadItems` derives boundaries purely from `Message.sessionId` deltas and tags every one as `Clear`. The thread stream therefore cannot exercise the `WorkspaceChange` or `IdleEvict` branches, and `SessionBoundary` has no field to carry the new workspace path that `WorkspaceChange` needs.

Gap is data-layer only:
1. `ThreadItem.SessionBoundary` needs an optional workspace-path field (populated iff `reason == WorkspaceChange`).
2. Seed data needs an authoring channel for per-session trigger reasons and the new workspace path on `WorkspaceChange`.
3. `buildThreadItems` needs to read that authored data instead of hard-coding `Clear`.
4. Across the three seeded channels, the projection must surface at least one boundary of each variant.

Mutator-side eager emission (`startNewSession` / `changeWorkspace` writing authored boundaries with the true reason at mutation time) stays explicitly out of scope — same posture #9 established. The storage shape introduced here is forward-compatible with that follow-up but does not implement it.

## Design

### 1. Add `workspaceCwd: String?` to `ThreadItem.SessionBoundary`

In `ConversationRepository.kt`, extend `SessionBoundary` with one new defaulted-nullable field:

```kotlin
data class SessionBoundary(
    val previousSessionId: String,
    val newSessionId: String,
    val reason: BoundaryReason,
    val occurredAt: Instant,
    val workspaceCwd: String? = null,
) : ThreadItem
```

Documented invariant in KDoc immediately above the class: **`workspaceCwd` is non-null iff `reason == BoundaryReason.WorkspaceChange`**. For `Clear` and `IdleEvict`, callers must observe `null`. This is asserted in tests (see **Testing strategy**) but not enforced by `require(...)` at the construction site — the data class stays a plain DTO, consistent with the rest of `data/`.

Defaulted to `null` so:
- The existing test `observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary` (line 180) keeps compiling unchanged. Its `assertEquals(BoundaryReason.Clear, b.reason)` continues to hold via the fallback described in §3; the new field defaults to `null`, which matches the invariant for `Clear`.
- No other call site constructs `SessionBoundary` today (verified via `codegraph_impact SessionBoundary`: 3 symbols, all in `ConversationRepository.kt`). The only producer is `buildThreadItems` in the fake repo.

Field name rationale: `workspaceCwd` (not `cwd`, not `newWorkspaceCwd`). `Conversation.cwd: String` is the precedent for the type name; the `workspace` prefix disambiguates on a boundary type whose "cwd" would be otherwise ambiguous. "New" semantics is implicit — a boundary is always a transition. The developer may pick `newWorkspaceCwd` instead if they prefer; the ticket only constrains the shape and the invariant, not the exact identifier.

### 2. Storage: extend `SeedSession` and `ConversationRecord`

The reason + path metadata lives at the seed authoring site (`SeedSession`) and is flattened into a private map on `ConversationRecord` keyed by the **new (successor) session id**. The map key choice is forward-compatible with mutator-emitted boundaries (`mintNewSession` would write `boundariesBySessionId[newSessionId] = AuthoredBoundary(reason, workspaceCwd)`).

#### `SeedSession` (private fixture type at FakeConversationRepository.kt:442)

Add two nullable fields describing the boundary that follows this historical session:

```kotlin
private data class SeedSession(
    val sessionId: String,
    val claudeSessionUuid: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val messages: List<SeedMessage>,
    val nextBoundaryReason: BoundaryReason? = null,
    val nextWorkspaceCwd: String? = null,
)
```

Semantics: `nextBoundaryReason` answers "why did the session immediately after this one begin?" `nextWorkspaceCwd` is the workspace path the *next* session started in, populated iff `nextBoundaryReason == WorkspaceChange`. Both nullable so the `initialMessages` constructor path (which bypasses `seedChannel`) and any future history entries that intentionally lack authored metadata fall through to the default in §3.

(Naming alternative for the developer: `triggerForNextSession` / `workspaceForNextSession`. Keep one name pair consistent across both fields.)

Authoring invariant the developer enforces by visual inspection in the seed declarations — not by `require(...)`:
- If `nextBoundaryReason == WorkspaceChange` → `nextWorkspaceCwd` must be non-null.
- If `nextBoundaryReason in {Clear, IdleEvict}` → `nextWorkspaceCwd` must be null.
- If `nextBoundaryReason == null` → `nextWorkspaceCwd` must be null (no authored boundary at all).

#### `ConversationRecord` (private data class at FakeConversationRepository.kt:249)

Add one new field — a map from successor-session id to the authored boundary metadata:

```kotlin
private data class ConversationRecord(
    val conversation: Conversation,
    val sessions: Map<String, Session>,
    val messages: List<Message> = emptyList(),
    val boundariesBySessionId: Map<String, AuthoredBoundary> = emptyMap(),
)

private data class AuthoredBoundary(
    val reason: BoundaryReason,
    val workspaceCwd: String?,
)
```

`AuthoredBoundary` is a private supporting type next to `ConversationRecord`. Key for the outer map is the **new session id** the boundary leads into (the session that "started for this reason").

Default `emptyMap()` so:
- Discussion records (built by `seedDiscussion`) need no change.
- The `initialMessages` override in the public constructor (line 24-31) keeps the existing record's `boundariesBySessionId` unchanged.
- Mutator-created sessions (out-of-scope here) currently produce no map entry, falling back to derived `Clear`. Same observable behaviour as today.

#### `seedChannel` (FakeConversationRepository.kt:450)

`seedChannel` builds the per-channel `boundariesBySessionId` by walking `history` and pairing each `SeedSession` whose `nextBoundaryReason != null` with the **next** session id in the channel's chronological order — i.e. the next entry in `history` if there is one, otherwise the current session id.

Behaviour summary (no body):

- For each historical `SeedSession` with `nextBoundaryReason != null`, compute the successor session id and add an entry `boundariesBySessionId[successorId] = AuthoredBoundary(reason, workspaceCwd)`.
- Pass the resulting map through to the constructed `ConversationRecord`.

With the current seed shape (each channel has exactly 1 historical session), the successor is always the current session id, so each authored entry has the channel's current session id as the key.

### 3. Update `buildThreadItems` to consult the map

Change `buildThreadItems` to take the map alongside `messages`:

```kotlin
private fun buildThreadItems(
    messages: List<Message>,
    boundariesBySessionId: Map<String, AuthoredBoundary>,
): List<ThreadItem>
```

Behaviour change at the boundary construction site (today: lines 67-74):

- On detecting a transition (`prior != null && prior != message.sessionId`), look up `boundariesBySessionId[message.sessionId]`:
  - If present: emit `SessionBoundary(reason = authored.reason, workspaceCwd = authored.workspaceCwd, ...)`.
  - If absent: emit `SessionBoundary(reason = BoundaryReason.Clear, workspaceCwd = null, ...)` — the unchanged default. This preserves the existing `observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary` test path (which seeds messages via `initialMessages`, producing no map entries) and matches mutator-emitted transition behaviour until eager emission lands.

`previousSessionId`, `newSessionId`, `occurredAt` are unchanged from today.

`observeMessages` (lines 49-53) updates by one line — pass `record.boundariesBySessionId` (or `emptyMap()` if the record is missing, which won't happen here because the early return at line 51 already handles unknown ids). Suggested shape:

```kotlin
override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
    state.map { records ->
        val record = records[conversationId] ?: return@map emptyList()
        buildThreadItems(record.messages, record.boundariesBySessionId)
    }
```

### 4. Concrete seed assignments

Assign one variant per channel so the three boundaries across the seeded channels collectively cover the full enum. The assignment also tells a thematically consistent story per channel:

| Channel id | Historical → current transition | `nextBoundaryReason` | `nextWorkspaceCwd` |
| --- | --- | --- | --- |
| `seed-channel-personal` | `seed-session-personal-1` → `seed-session-personal` (2026-04-28 → 2026-05-05) | `Clear` | `null` |
| `seed-channel-pyrycode-mobile` | `seed-session-pyrycode-mobile-1` → `seed-session-pyrycode-mobile` (2026-05-03 → 2026-05-10) | `WorkspaceChange` | `"~/Workspace/pyrycode-mobile"` |
| `seed-channel-joi-pilates` | `seed-session-joi-pilates-1` → `seed-session-joi-pilates` (2026-04-30 → 2026-05-08) | `IdleEvict` | `null` |

Why these picks:

- **Personal → `Clear`**: a "personal" channel where the user just `/clear`'d at the end of the first session — most common reason, natural for a low-stakes personal context.
- **Pyrycode Mobile → `WorkspaceChange`**: a code-focused channel where workspace switches are plausible. The new workspace equals the channel's current `cwd` (`~/Workspace/pyrycode-mobile`) — read it as "user wrapped the previous session in a different workspace, then switched to pyrycode-mobile to begin the next session." Path matches the conversation's existing `cwd` so no other field needs to change.
- **Joi Pilates → `IdleEvict`**: this channel already exercises the sleeping-indicator path via `currentSessionEndedAt = Instant.parse("2026-05-09T03:00:00Z")` (seeded at line 389). The "this channel sees idle eviction" story is consistent end-to-end — the historical session also ended idle, the next session was started by the user re-engaging, and the current session has gone idle again. Reuses the channel's existing posture.

`workspaceCwd` is `null` for the two non-`WorkspaceChange` boundaries. The single `WorkspaceChange` boundary carries the path string.

### 5. Out of scope

- **No changes to `Session` or `Conversation` models.** Reason metadata is repository-private (`ConversationRecord` + `AuthoredBoundary`), not part of the cross-package data model. Keeps `data/model/` portable per CLAUDE.md "Don't" rules; keeps the diff narrow.
- **No eager boundary emission from `startNewSession` / `changeWorkspace`.** Same deferral as #9. The storage shape introduced here is the natural target for that follow-up (mutator writes into `boundariesBySessionId[newSessionId]`); building it now is feature creep.
- **No `require(...)` runtime enforcement** of the `workspaceCwd ↔ WorkspaceChange` invariant. The invariant is documented in KDoc and asserted in tests; runtime guards in DTOs are not the codebase pattern.
- **No UI changes.** Delimiter rendering of the three label variants is #135's work.
- **No discussion-side seed changes.** Discussions have one session each — no boundaries to author.

## State + concurrency model

Unchanged. `observeMessages` is still a `state.map { ... }` projection over the single `MutableStateFlow<Map<String, ConversationRecord>>`. Adding `boundariesBySessionId` to `ConversationRecord` is part of the same state holder; observers re-emit on every state change as before. No new flows, no new dispatchers, no new cancellation surface.

## Error handling

The only new failure shape is "authored reason / path violates the `WorkspaceChange ↔ workspaceCwd != null` invariant." Three lines of defence:

1. Seed-author discipline: the three concrete seed entries in §4 are correct by construction.
2. KDoc on `SessionBoundary.workspaceCwd` documents the invariant.
3. Tests (see below) assert both directions of the invariant for each seeded channel.

No runtime `require(...)`; the data class stays a plain DTO. If a future seed author breaks the invariant, tests fail fast at build time.

The fallback path in `buildThreadItems` (no entry → `Clear` + `null`) is not an error path — it's the legitimate behaviour for the `initialMessages` constructor path and for mutator-minted sessions.

## Testing strategy

Unit tests in `FakeConversationRepositoryTest.kt` (JVM `./gradlew test`). No instrumented tests.

**New test cases.** Each is a small scenario over the seeded data; describe inputs + expected assertions, developer writes them in the file's existing `runBlocking { ... }` style (matching the file's prevailing `assertEquals` / `assertTrue` idiom — no MockK, no fakes-of-fakes; the real `FakeConversationRepository` is the system under test).

- **`seededPersonalChannel_boundary_isClear_withNullWorkspaceCwd`**: collect `observeMessages("seed-channel-personal").first()`, filter to `SessionBoundary`, take the single boundary; assert `reason == BoundaryReason.Clear` and `workspaceCwd == null`.
- **`seededPyrycodeMobileChannel_boundary_isWorkspaceChange_withSeededPath`**: same shape for `"seed-channel-pyrycode-mobile"`; assert `reason == BoundaryReason.WorkspaceChange` and `workspaceCwd == "~/Workspace/pyrycode-mobile"`.
- **`seededJoiPilatesChannel_boundary_isIdleEvict_withNullWorkspaceCwd`**: same shape for `"seed-channel-joi-pilates"`; assert `reason == BoundaryReason.IdleEvict` and `workspaceCwd == null`.
- **`seededChannels_collectivelyExerciseAllBoundaryReasons`**: aggregate the three boundaries (one per seeded channel) into a set of `reason` values; assert the set equals `setOf(BoundaryReason.Clear, BoundaryReason.IdleEvict, BoundaryReason.WorkspaceChange)`. Acts as a guard against silent regressions if future seed edits collapse two channels onto the same reason.

**Existing tests.** Must continue to pass without modification (verify locally — do not edit):

- `seededChannels_emitExactlyOneBoundary_betweenTwoSessions` (line 44) — boundary *count* per channel is unchanged.
- `seededChannels_messagesAreChronologicallyOrdered` (line 61) — ordering invariant unchanged.
- `seededChannels_haveTwoSessionsInHistory_endingWithCurrentSessionId` (line 82) — session history shape unchanged.
- `observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary` (line 180) — uses `initialMessages` (no authoring), exercises the fallback in `buildThreadItems`; `BoundaryReason.Clear` continues to hold. Optionally extend by adding one line: `assertNull(b.workspaceCwd)` — strictly an enrichment, not required by ACs. (Recommended for symmetry with the new tests, but not blocking.)
- `observeMessages_emitsSeededToolMessage_withStructuredPayload` (line 146) — operates on `seed-channel-pyrycode-mobile`; the additional `WorkspaceChange` boundary metadata does not affect message ordering or content. Unchanged.

Run `./gradlew test` to validate; `./gradlew lint` for catch-all.

## Open questions

- **Field naming on `SessionBoundary`** (`workspaceCwd` vs `newWorkspaceCwd`) and on `SeedSession` (`nextBoundaryReason` vs `triggerForNextSession`): the developer may pick either. The spec recommends `workspaceCwd` / `nextBoundaryReason` for brevity but the alternative names carry the same semantics — keep one pair consistently within each call site.
- **Should the existing `observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary` test grow an `assertNull(b.workspaceCwd)` assertion?** Recommendation: yes, one line, for symmetry. Not required by ACs. If it adds noise, skip it.
- **Forward-compat sanity (informational, not action)**: when eager mutator-side emission lands later, the natural shape is `mintNewSession(reason: BoundaryReason, workspaceCwd: String?)` writing `boundariesBySessionId[newSessionId] = AuthoredBoundary(reason, workspaceCwd)` inside the existing `state.update { ... }` block. No reshape of the storage chosen here is needed for that step.

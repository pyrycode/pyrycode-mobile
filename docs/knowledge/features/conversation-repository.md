# ConversationRepository — data-layer contract

The single interface UI ViewModels consume, implemented by the in-memory fake and the relay-backed repositories.

Package: `de.pyryco.mobile.data.repository` (`app/src/main/java/de/pyryco/mobile/data/repository/`).

Normal builds bind the [`StableConversationRepository`](stable-conversation-repository.md) facade over the live [`RemoteConversationRepository`](remote-conversation-repository.md). The Phase 1 `FakeConversationRepository` remains the explicit demo/test selection. See [Phase 1 implementation](#phase-1-implementation--fakeconversationrepository) below.

## Shape

See [interface and reading types](conversation-repository-shape.md#shape) for the
repository API and the semantics of its returned values, including the host
current/default prompt pair and the separate nullable channel prompt.
[Host prompt contract](conversation-repository-conventions.md#host-system-prompt-contract-1774)
explains required strings, empty clear, reset and failed results.

## Conventions

Split into [ConversationRepository — data-layer contract — Conventions](conversation-repository-conventions.md) on 2026-09-23 to keep this document under the 50000-byte cap the docs guard enforces. That section moved there verbatim, headings and anchors intact.

## `ThreadItem` — why a sealed wrapper

The thread screen renders messages chronologically and inserts a horizontal-rule delimiter at each session boundary (`/clear`, idle-evict, workspace change — see CLAUDE.md → "Conversations model"). Since #1578 the delimiter draws only its rule / label / rule row, at full opacity like every other row; the memory-plugin install affordance stays in the thread overflow menu and the channel info sheet, not on the boundary itself.

The stream interleaves both kinds of row in order, so the consumer never paginates manually across `sessionHistory`. Design notes:

- `MessageItem` **wraps** `Message` rather than having `Message` implement `ThreadItem` directly — keeping a `data/repository/` type out of `data/model/`'s parent chain preserves the layer direction.
- `SessionBoundary` carries both `previousSessionId` and `newSessionId`. Previous anchors the delimiter to the messages above it; new identifies the session the thread continues into.
- `SessionBoundary`'s identity is `(previousSessionId, newSessionId, occurredAt)`, not the session pair
  alone — invariant, unique within a thread ([#775](../codebase/775.md)). `ThreadScreen`'s `LazyColumn`
  keys a boundary row on exactly these three fields, so a duplicate triple crashes it; the pair alone is
  not unique, because an idle-evicted session keeps its id and every eviction of it is `A->A`. Uniqueness
  is a producer obligation, not construction-enforced — both thread writers (the live lane's
  `appendSessionBoundary` and the history merge's `holdsBoundary`) skip a boundary the thread already
  holds — documented in KDoc and asserted in tests, the same posture as `UnrecognizedMessage.id`.
- `BoundaryReason` lists exactly the three triggers CLAUDE.md names. No speculative `Manual` / `Other` / `Unknown` — add a value if and when a fourth trigger lands.
- `SessionBoundary.workspaceCwd` (#192) carries the new workspace path on the `WorkspaceChange` variant only. Same defaulted-nullable-last-field pattern as `Message.toolCall` (#191); see [`../codebase/192.md`](../codebase/192.md).

## What `observeMessages` does not do

- **No `PagingData`.** "Paginates transparently" in the ticket AC means the consumer doesn't drive pagination; it does not mean lazy windowing in the contract. The Phase 1 fake emits the full list; Phase 4 can window internally and emit a growing prefix without changing the signature. If the channel list or thread grows past hundreds of rows in practice, switching to Paging 3 is a contract change at that point.
- **No `Flow<Result<…>>`.** Element type is `ThreadItem`, not `Result<ThreadItem>`. Failures terminate the flow.
- **No eager boundary emission from `startNewSession` / `changeWorkspace`.** Mutators still mint new sessions without writing an authored `SessionBoundary` to storage; the projection still derives boundaries from message session-id deltas. The two non-derived inputs that *do* exist (since #192) are seed-side: each `SeedSession` declares the `nextBoundaryReason` (and, on `WorkspaceChange`, the `nextWorkspaceCwd`) of the boundary that follows it, flattened into a per-record `boundariesBySessionId: Map<String, AuthoredBoundary>` keyed by **successor session id** and consulted by `buildThreadItems`. Mutator-minted sessions and the `initialMessages` constructor path produce no map entries; the projection falls back to `BoundaryReason.Clear` + `workspaceCwd = null` for those — same observable behaviour as pre-#192 for those paths. The deferred follow-up (lifting `mintNewSession` to accept `(reason, workspaceCwd)` and write `boundariesBySessionId[newSessionId] = AuthoredBoundary(...)` inside the existing `state.update { ... }` block) is unchanged in scope — but its target shape is now in place, so it stays a pure-additive change when it lands.
- **No `.distinctUntilChanged()`.** Consistent with `observeConversations`. Both projections re-emit on every `state.update {}` — even mutations that don't affect the observed slice. If a future chatty mutator (per-token streaming append) makes this matter, fix with a subtype-aware dedupe in the projection, not a sibling state holder.

## Dependency wiring

`kotlinx-coroutines-core 1.10.2` is pinned in `gradle/libs.versions.toml` and declared as `implementation(libs.kotlinx.coroutines.core)`. Compose and lifecycle pull coroutines onto the runtime classpath transitively, but `data/` declares its own pin so the contract isn't coupled to a transitive BOM bump.

`-core` (not `-android`) keeps the data layer JVM-portable for the Compose Multiplatform walk-back (`Main` dispatcher comes in via the UI layer's `-android` transitive).

## Phase 1 implementation — `FakeConversationRepository`

Split into [ConversationRepository — data-layer contract — Phase 1 fake implementation](conversation-repository-fake-implementation.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. That section, Phase 1 implementation — `FakeConversationRepository`, moved there verbatim, headings and anchors intact.

## What's deliberately absent

- **No `getConversation(id)` / `searchConversations` / `archiveBatch`.** Add when a real use case needs them. (The idle-archive `sweep(referenceTime)` (#267) is a *policy* sweep over the whole store, not a caller-supplied `archiveBatch(ids)` — and it lives on the fake only, not the interface.)
- **No companion / factory.** Construction is Koin's job.
- **No `@Throws` annotation.** Kotlin doesn't enforce checked exceptions; doc-comments will name thrown types when concrete impls land.
- **No tests at the interface level.** An interface without an implementation can't be unit-tested. Behavioural tests live with `FakeConversationRepository` in `FakeConversationRepositoryTest.kt` (#4).

## Related

Split into [ConversationRepository — data-layer contract — Related](conversation-repository-related.md) on 2026-09-24 to keep this document under the 50000-byte cap the docs guard enforces. That section moved there verbatim, headings and anchors intact.

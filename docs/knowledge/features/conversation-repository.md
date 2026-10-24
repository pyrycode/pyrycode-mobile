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

## Content and presentation

The exact host's `NegotiatedThread` supplies authoritative snapshots and query
admission. Metadata/live-state/control methods stay on `ConversationRepository`.
Runtime `observeMessages`, raw history/snapshot APIs and inference are removed;
there is no empty-success fallback. [Thread-store ownership](remote-conversation-repository-reads-and-thread-store.md)
and [screen demand](thread-screen-oldest-end-history-demand.md) describe the route.

`ThreadItem` remains a sealed presentation/storage value for messages, supplied
session dividers and notices. Supported rendering consumes authored daemon facts;
literal component fixtures can prepare rows without invoking raw reduction. The
[delimiter](session-boundary-delimiter.md) still draws rule / label / rule only.
Dormant serialized legacy types remain until #2364, without runtime readers.

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

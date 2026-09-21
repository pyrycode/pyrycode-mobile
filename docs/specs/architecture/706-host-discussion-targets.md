# Host-qualified discussion navigation and promotion (#706)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt` — `DiscussionListViewModel`, `PendingPromotion`, `derivedChannelName`: preserve the flat contract and captured-name fallback.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` — `HostConversationTarget`, `hostNavigationEvents`: reuse the host target and separate navigation boundary from #705.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` — `HostConversationSnapshot`, `snapshots`, `repositoryFor`: source-owned active partitions, cached rows and exact live access.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — `appModule`, `conversationRepositoryModule`, `hostConversationModule`: inject the existing shared source and demo singleton.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` — `launchGuardedRepoCall`: preserve classified failures and cancellation propagation.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` — `RelayLog.d`: debug-only, content-free diagnostics and JVM sink seam.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt` — existing compatibility projection, promotion and guarded failure assertions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` — host fixtures, selected facade, actual Koin definition resolution and action-job cancellation assertions.
- `docs/knowledge/features/discussion-list-viewmodel.md`, Promotion confirmation flow and Wiring — clear before send, captured source name, no success navigation.
- `docs/knowledge/features/dependency-injection.md`, Host identity and snapshots / Exact-host repository access — cached status never authorizes a mutation.
- `docs/knowledge/features/channel-list-viewmodel.md`, State projection — host snapshots remain separate from selected-host compatibility state.
- `docs/knowledge/features/development-verification.md`, Establish the change surface / JVM logging and formatting — examine real callers and capture logs in JVM tests.
- `docs/knowledge/features/guarded-repo-launch.md` — unexpected failures remain fail-fast; cancellation must precede IllegalStateException handling.
- `gradle/libs.versions.toml` — existing coroutine, JUnit and Koin dependencies suffice.
- Upstream `pyrycode/docs/protocol-mobile.md`, Identifiers, Application message types and Security model — authoritative unchanged wire contract, read in the canonical sibling checkout.

## Design source

N/A — additive ViewModel/DI contract only, as confirmed in refinement. UI producers, visuals and routing belong to #641/#636; no render changes in this slice.

## Context and size

Discussion actions currently identify a conversation inside the selected repository only. The shared source from #704 and target from #705 allow a host-qualified contract without migrating the current screen.

One deliverable: the independently testable host-qualified discussion contract. Estimate about 560 written lines (110 production, 355 tests/helpers, 95 plan), two production files, two new exported data types, one simultaneous consumer update (`appModule`), three acceptance criteria, and fewer than ten reject/error branches. #705 cost 746 inserted / 2 deleted scoped lines; discussion needs no preview subscriptions or preference reads. No new dependency or ADR is needed.

Codegraph context found the owning symbols, but impact/callers omitted external constructors. Fallback search found one production construction site and 15 existing JVM sites; an optional source parameter preserves the latter. After fetching origin, all remote numeric feature branches were checked against the two production paths and the new test path; no overlap was found.

## Design

- Add `HostDiscussionListState(hosts: List<HostConversationSnapshot> = emptyList(), pendingPromotion: PendingHostPromotion? = null)` and `PendingHostPromotion(target: HostConversationTarget, sourceName: String?)` in the ViewModel file.
- Add optional `hostSource: HostConversationSource? = null` to `DiscussionListViewModel`. Actual DI passes the shared source; repository-only fixtures retain their existing behavior and expose no invented host identity.
- `hostState` projects snapshots directly, preserving host order, metadata, complete chats, object identity, paths and cached/empty hosts. It does not flatten, sort, truncate, recache or subscribe to messages.
- `onHostRowTapped(target)` emits the exact target on `hostNavigationEvents`, a separate buffered channel. The legacy bare-id navigation channel is untouched.
- `requestHostPromotion(target)` finds the current chat in the exact source host and captures its target/name. Unknown hosts or absent chats do not open a request.
- `confirmHostPromotion()` atomically takes and clears pending before launching. Inside `launchGuardedRepoCall`, recheck current source chat membership, then resolve `repositoryFor(target.serverId)` immediately before promoting. Use only that repository, the captured id, `derivedChannelName(capturedName)` and `workspace = null`.
- `cancelHostPromotion()` clears pending without calling a repository. Source host/chat removal clears pending; later reappearance cannot revive it. Promotion completion is reflected only by source list updates, with no optimistic row removal or success navigation.
- Existing `state`, `onEvent`, `PendingPromotion`, and `navigationEvents` keep their selected-host/fake semantics. Host and legacy pending state cannot consume each other's confirmation.

## State and concurrency model

The source remains app-owned. Host projection combines its hot snapshots with VM-owned pending state and shares eagerly in `viewModelScope`, so invalidation still runs without a screen subscriber. This inexpensive projection adds no repository collection; the legacy state retains `WhileSubscribed(5_000)`.

Actions run on Main through `viewModelScope`. Pending consumption has no suspension and uses atomic flow operations. Membership and live lookup occur inside the launched action without intervening suspension. A disconnect after lookup can still fail in the existing repository guard. VM teardown cancels projection, navigation and promotions; source ownership/disposal stays in Koin. No new dispatcher or background socket owner is introduced.

## Error handling

Missing chat and unavailable repository reject without sending. Repeated confirmation with no pending request is inert. Action logs use static event names/rejection codes only; no host ids, paths, names, exception messages or payloads. Log promotion start, completion, failure, request, cancellation and invalidation through `RelayLog.d`.

`launchGuardedRepoCall` retains its quiet handling of `RelayErrorException`, `IllegalStateException` and `UnsupportedOperationException`. A surrounding log-and-rethrow block propagates cancellation without logging it as failure and leaves unexpected failures subject to the existing fail-fast behavior. No new UI error surface or wire behavior is added.

## Testing strategy

New `HostDiscussionListViewModelTest` uses the real shared source with independent host flows, mutable exact lookup availability and a selected-host facade; fakes record calls and action jobs. Write/run tests RED before production code, then prove GREEN:

- Exact case-sensitive hosts with colliding ids/paths; complete ordered active chats, local metadata, separate status legs, empty/silent hosts and cached disconnected rows; exact navigation pairs and no bare-id leakage.
- Captured name despite source rename and compatibility selection switch; named/null/blank fallback, null workspace, only the captured host called, clear before the repository starts, duplicate confirmation and cancel.
- Host/chat removal (including archive/promotion) clears pending without subscribers; reappearance cannot revive it. Confirm rechecks current membership and fresh availability, including stale connected indicators and held-handshake/disconnect states.
- Guarded failures, cancellation action-job state, VM cancellation, content-free logs, no optimistic removal and repository-driven completion.
- Legacy selected-host projection/events remain usable and isolated. Resolve the real `appModule` ViewModel binding with fake selection; only demo appears and promotion updates the existing fake singleton.

Run both discussion JVM classes, `spotlessApply`, `lint`, `assembleDebug`, and the plan's docs guard. No instrumented files or UI rendering change. Dispatcher owns full regression gates; this contract needs no real-Claude run. Operator-facing follow-ups retain #676/#673.

## Open questions

None. Use eager host projection to satisfy removal invalidation even while unobserved; keep legacy sharing unchanged.

## Documentation handoff

Pending for the documentation stage: The documentation stage updates `docs/knowledge/features/discussion-list-viewmodel.md` under Promotion confirmation flow and Wiring to describe host-qualified pending state, navigation and the temporary flat-screen compatibility boundary.

## Security review

**Verdict:** PASS

- **Trust boundaries:** `HostConversationTarget` preserves exact host/id; source membership authorizes the captured chat, and `repositoryFor` alone establishes current host availability. Colliding ids, display names and paths never select another host.
- **Tokens/secrets:** This contract holds snapshots, targets and source names only. It adds no token access, credential storage or lifecycle; existing pairing/Keystore owners remain responsible.
- **File/storage operations:** Workspace paths pass through as data; promotion sends null workspace. No file access, path interpretation, persistence or backup change.
- **Android attack surface:** No component, intent, deep link, WebView or UI renderer is added. #636/#641 own route and rendering consumers; this stream never degrades a target to a bare id.
- **Cryptography:** No handshake, primitive, key, nonce or secret comparison changes. Existing Noise transport remains authoritative.
- **Network/I/O:** The existing repository performs the unchanged promotion verb. Fresh exact-host lookup rejects disconnected/handshaking targets regardless of cached rows/status; transport timeout/backoff policies remain with existing owners.
- **Errors/logging:** All new diagnostics are static events/codes in debug-only `RelayLog.d`. Error text and captured names are never logged or surfaced by this path.
- **Concurrency:** Eager VM-scoped invalidation plus send-time checks handle stale pending data; atomic clear precedes launch. Cancellation is rethrown before classified error handling, and no job outlives the VM.
- **Threat alignment:** Cross-host confusion and stale-live lookup are addressed here. Relay drop/delay, hostile frame decoding and token theft remain handled by existing transport/codec/Keystore boundaries; no changes to those surfaces. Future visual leakage review belongs to #641 and host route handling to #636.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

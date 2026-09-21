# 729 — Group mobile conversations by host and workspace

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` | `HostChannelListEntry` | The host-scoped entry this slice widens; its three existing fields stay exactly as they are. |
| ” | `ChannelListViewModel.hostState`, `observeHostEntry` | The projection the grouping rides. `observeHostEntry` runs once per snapshot emission per host and is where the pure grouping call lands — its `entry.copy(recentChatLastMessages = …)` in the preview `combine` carries the groups forward without recomputing them. |
| ” | `HostChannelListState`, `HostConversationTarget` | The surrounding state shapes; `HostConversationTarget` is the existing `(serverId, conversationId)` pair the new row type must not duplicate or contradict. |
| `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` | `workspaceDisplayName`, `MAX_WORKSPACE_LABEL_CHARS` | The one display rule (#722) this slice applies. Its KDoc already states the result is text only — never an identity, a path or a log line — and its clamp is the length bound on untrusted daemon text. |
| `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` | `HostConversationSnapshot`, `HostConversationSource.reconcile` | Where `channels` / `chats` come from: already filtered to non-archived and split by `isPromoted`, collected per host from `ConversationFilter.All`, so a relabel reaches the snapshot without this slice subscribing to anything. |
| `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` | `Conversation.cwd`, `Conversation.workspaceLabel`, `DEFAULT_SCRATCH_CWD` | The grouping key, the #720 nullable label, and the sentinel the display rule's scratch arm keys on. |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt` | the `ChannelListViewModel.hostState` collectors | Confirms production reads only `workspacePickerServerId` off `hostState` today — widening the entry changes no call site. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` | `Fixture`, `Host`, `Repo`, `row` | The fake host-source rig the new tests extend. `row` hardcodes one `cwd` and no label, so it needs optional parameters; `Host.available` is the seam that denies `repositoryFor` while the source keeps collecting rows. |
| `docs/knowledge/features/channel-list-viewmodel.md` | § State projection, § Testing | Records that the projection slices but never re-sorts, that `(serverId, conversation.id)` identifies a row and that equal ids or paths on two hosts must never share a cross-host map — the isolation rule this slice extends to workspace groups. Also the source of the default-argument affordance convention used on the two new fields. |
| `docs/specs/architecture/722-show-workspace-labels-in-the-conversation-thread.md` | § Design, § Security review | The rule's contract and the reason a second formatter is forbidden; names #641's workspace rows as an intended consumer of the shared function. |
| `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md` | `conversations`, `conversation_updated` rows | Wire SSOT for `cwd` and `workspace_label`: the label is an opaque string echoed verbatim whose byte bound is a size limit and not a safety property. Cited, not restated. |

## Design source

**Figma:** N/A — the ticket carries no `## Figma` section and states that nothing in this slice renders. The tree shape it must support is the Channel List design's Sidebar adaptation (`15-8` → `133-259`); the rendering slice in the #641 family owns the pixel work and the visual-fidelity check. This slice is view-model state only.

## Context

`hostState` exposes one `HostChannelListEntry` per host carrying that host's flat `channels` and `chats` plus a recent-three chat slice and its previews. The design renders a three-level tree — host, workspace, conversation — under a Channels section and a Chats section. This slice produces that tree as state and nothing else; no consumer reads it yet.

Every input already exists. #720 decodes `workspace_label` onto `Conversation`, #721 applies an incoming `workspace_updated` to every row of the owning host's projection that shares the path, `HostConversationSource` collects each host's `ConversationFilter.All` stream, and #722 shipped the one display rule. The only missing piece is the grouping.

No ADR is warranted: this adds two data classes and a pure function inside an existing package, no new architectural seam.

## Design

**New file** — `app/src/main/java/de/pyryco/mobile/ui/conversations/list/HostWorkspaceGroup.kt`, package `de.pyryco.mobile.ui.conversations.list`:

```kotlin
data class HostConversationRow(val serverId: String, val conversation: Conversation)

data class HostWorkspaceGroup(
    val serverId: String,
    val cwd: String,
    val displayName: String,
    val conversations: List<HostConversationRow>,
)

internal fun groupConversationsByWorkspace(serverId: String, conversations: List<Conversation>): List<HostWorkspaceGroup>
```

`HostConversationRow` carries the owning host beside the unchanged `Conversation`, so a row exposes its `serverId`, its own `conversation.id` and the exact `cwd` without a second lookup. It deliberately mirrors `HostConversationTarget`'s `(serverId, conversationId)` pairing rather than replacing it — the target is an action address, the row is a state element.

**Identity.** The group key is the `(serverId, cwd)` pair, with `cwd` compared exactly — no trimming, no normalisation, no case folding. Two hosts holding the same path are two groups. `displayName` is text and never an identity: a rename or a clear changes only that text, so no conversation moves between groups, and a list key built from `displayName` would collapse two distinct workspaces that share an operator-chosen name. KDoc on the field states this at the point a consumer would be tempted.

**Grouping.** `conversations.groupBy { it.cwd }` then a `map` to groups. `groupBy` returns a `LinkedHashMap`, so keys land in first-encounter order and each group's members keep their source order — which is exactly "a group takes the position of its first conversation" and "conversations keep the order their host snapshot supplies". The projection introduces no sort key of its own, keeping the contract `recentDiscussions_orderingFollowsUpstream` pins.

**Display name.** `workspaceDisplayName(cwd, rows.first().workspaceLabel)` — the shared rule, with its scratch and basename fallbacks and its length clamp. The label is read from the group's *first* conversation: #721 keeps every row on a host that shares the path on the same label, so the group is uniform, and the first row is already the row that decides the group's position. No second display rule is written, and no raw `workspaceLabel` is copied into `displayName`.

**Widened state** — `ChannelListViewModel.kt`:

```kotlin
data class HostChannelListEntry(
    val host: HostConversationSnapshot,
    val recentChats: List<Conversation>,
    val chatCount: Int,
    val recentChatLastMessages: Map<String, Message> = emptyMap(),
    val channelGroups: List<HostWorkspaceGroup> = emptyList(),
    val chatGroups: List<HostWorkspaceGroup> = emptyList(),
)
```

Two fields, one per rendered section, each grouping the snapshot's **full** active list — `host.channels` and `host.chats`, not the recent-three slice. `recentChats`, `chatCount` and `recentChatLastMessages` stay exactly as they are; later slices in this family retire them. Defaults follow the affordance convention the overview records for the two existing defaulted fields.

`observeHostEntry` computes both groups once when it builds `entry`, before the preview `combine`. The existing `entry.copy(recentChatLastMessages = …)` carries them through unchanged, so a preview emission never recomputes a group. The flat `ChannelListUiState` flow is untouched.

## State + concurrency model

No new job, scope, flow or subscription. `groupConversationsByWorkspace` is a pure synchronous function called inside the existing `flatMapLatest` on `hostSource.snapshots`, sharing `hostState`'s `stateIn(viewModelScope, WhileSubscribed(5_000), …)` lifetime and its existing cancellation path. A relabel arrives as a new snapshot emission and re-runs the same pure projection; it triggers no `repositoryFor` call and no `observeLastMessage` resubscription of its own, which is what "without re-subscribing" requires. Grouping is a linear pass over a list the projection already holds, on the same dispatcher the existing `.take(3)` and `.size` derivations use.

## Error handling

The projection has no failure mode: pure list traversal over data already in memory, no I/O, no parse, no nullable dereference. `Conversation.cwd` is non-nullable and `workspaceLabel` is handled by `workspaceDisplayName`'s null arm. No new reject branch, so no new log call — the existing `event=host_channel_list_projected count=…` line still covers the projection, and a per-group count would fire on every snapshot emission per host while telling nobody anything. Upstream list failures keep collapsing where they already do, inside `HostConversationSource`.

## Testing strategy

JVM only (`./gradlew testDebugUnitTest`), over the existing fake host source in `HostChannelListViewModelTest`. Nothing renders, so no device test. `row` gains optional `cwd` and `label` parameters so existing call sites are unchanged.

1. **Grouping shape, order and host isolation.** One host with interleaved paths (`/w/alpha`, `/w/beta`, `/w/alpha`) so a group must gather non-adjacent rows and still take its first member's position; more than three chats so the assertion proves the full list is grouped rather than the recent-three slice; an archived row that must not appear. Assert group order, per-group membership order, that each row carries the owning `serverId` and its own conversation id, and that `cwd` survives verbatim including the fixture's untrimmed shape. A case-distinct second host holding the same paths gets its own groups with its own `serverId`.
2. **Display names and relabel isolation, with no repository available.** Run with the host's `repositoryFor` denied while the source still collects rows, so `observeHostEntry` short-circuits before any preview subscription — grouping must still be complete, which is the proof it subscribes to nothing. Cover the rule's arms through the group: a label wins, a blank label falls back to the basename, unlabelled scratch reads `scratch`, labelled scratch shows its label. Then emit a rename on one host and assert only that group's `displayName` changed while its `cwd`, position and membership held and the same-path group on the other host was untouched; then emit a clear and assert the basename fallback returns. Assert the captured `RelayLog` output contains no label or path content.

## Open questions

- Whether the two sections should later share one group list with a promoted flag instead of `channelGroups` / `chatGroups`. Resolved during the design: the supplied tree renders two independent sections, and a single list would force every consumer to re-split. Two fields.

## Documentation handoff

Pending for the documentation stage, from the ticket's own handoff section: fold the tree projection into `docs/knowledge/features/channel-list-viewmodel.md`, in its state section, recording that the group key is the `serverId` and exact `cwd` pair and that the display name is text only and never an identity. Not written by this ticket.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** Two daemon-authored values cross into view-model state here, and they cross differently. `workspaceLabel` is untrusted text bound for Compose: the design reaches it **only** through `workspaceDisplayName`, whose `MAX_WORKSPACE_LABEL_CHARS` clamp is the length bound on a hostile or buggy daemon — the protocol's byte bound is a size limit, not a safety property. Copying a raw `Conversation.workspaceLabel` into `displayName`, or writing a second formatter, would drop that bound; the design forbids both. `cwd` crosses as an **identity**, not as text, and is compared with exact `String` equality. The boundary is a single pure function, `groupConversationsByWorkspace`, not scattered across the projection.
- **[Trust boundaries]** SHOULD FIX — a downstream consumer could key a `LazyColumn` on `displayName`, which a hostile or merely careless daemon collapses by giving two distinct workspaces the same label. The design's guard is that `serverId` and `cwd` sit on the group beside `displayName`, with KDoc naming the pair as the key and the display field as text only. A value class to enforce it at the type level is not warranted for a failure mode nobody has observed. The verifier should check the KDoc landed on the fields, not just in this plan.
- **[File / storage operations]** No finding, and the reason is worth stating rather than waving through: `cwd` is a path on the *daemon's* filesystem that the phone never resolves. This slice groups by it and copies it verbatim; it must never reach a `java.io.File`, a `Uri`, a filename or a cache key, so no canonicalisation or boundary check applies and no TOCTOU window exists. A later slice that turns a group into an action sends `cwd` on the wire — that constraint travels with the field.
- **[Error messages, logs, telemetry]** SHOULD FIX — `cwd` and `displayName` are the two fields most likely to be "helpfully" added to a log line, and both are forbidden: the label is operator content and the path is host-identifying. The design adds no new log call at all, and the existing host projection logs a bare count. Test 2 asserts the captured `RelayLog` output holds no label or path content, matching the existing `logs.none { "sensitive" in it }` assertions in this test class.
- **[Network & I/O]** No finding, and the AC that reads as a correctness requirement is also the security property. Had grouping been built with a per-group subscription, a daemon that renames a workspace repeatedly would drive one resubscription per label change — a subscription-amplification lever reachable from inside the session. A pure projection over the existing snapshot has no such lever; Test 2 pins it by producing complete groups with `repositoryFor` denied.
- **[Concurrency]** No finding. `groupConversationsByWorkspace` is pure and synchronous, holds no shared mutable state, allocates only a local `LinkedHashMap`, launches nothing and is called inside the existing `flatMapLatest` on `viewModelScope`. Cancellation and shutdown behaviour are unchanged, so `LifecycleConnectionDriver`'s background close needs no new handling. Group count is bounded by the host's conversation count — the same list already held and already rendered — so a daemon sending many distinct paths gains no amplification over what it already has; if that list ever needs a bound it belongs upstream in `HostConversationSource`, not here.
- **[Tokens, secrets, credentials]** Not applicable — no token, key or pairing material is read or written. `HostConversationSnapshot` carries host identity around conversation records and no pairing secrets, and this slice adds no new field to it.
- **[Cryptographic primitives]** Not applicable — no randomness, hashing, key material or comparison against a secret. `groupBy`'s use of `String.hashCode` is a collection detail, not a security primitive: `HashMap` resolves buckets by `equals`, so distinct paths cannot merge, and the quadratic-insert collision case is bounded by one host's conversation count.
- **[Inter-process / Android attack surface]** Not applicable — no intent, deep link, pending intent, content provider or push path is touched. There is no WebView in this repo and the eventual render sink is a Compose `Text`; the design produces no markup, URL or file reference from either field.
- **[Threat model alignment]** A malicious relay is content-blind but on-path: it can drop, delay or reorder a `conversation_updated`, which could leave a stale label on screen. That is label *application* ordering, owned by #721, and out of scope here — this slice displays whatever the snapshot holds. UI-side leakage (screenshots, accessibility eavesdropping, overlays) is also out of scope: nothing renders in this slice; the rendering slice in the #641 family owns it. Hostile daemon frames and token theft are addressed above or unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

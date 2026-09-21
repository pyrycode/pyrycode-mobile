# 715 — Bind Archive browsing and restore to its host

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `Routes`, its `SETTINGS` / `ARCHIVED_DISCUSSIONS` constants and the `settings` / `settingsArguments` / `settingsOwner` trio, the `Routes.ARCHIVED_DISCUSSIONS` destination block, `HostDestination`, `HostWorkspaceRepository` — the destination this ticket re-owns, and the two shapes (path-segment ownership, the bounce guard) it adopts.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt` → `ArchivedDiscussionsViewModel`, `ArchivedDiscussionsUiState`, `ArchivedDiscussionsEvent.RestoreRequested`, `ArchivedDiscussionsEffect` — the restore path, already repository-parameterised; only *which* repository is wrong today.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt` → `ArchivedDiscussionsScreen`, its `TopAppBar` title and `LoadedBody` — the header the owning host must become identifiable in, and the `effects` parameter whose defaulting precedent the new one copies.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory` and its `repository` / `thread` / `literal` / `settings` / `hosts` members, and the `viewModel { ArchivedDiscussionsViewModel(get()) }` binding — the exact-host seam (#636) and the one binding that still resolves the compatibility repository. `settings`'s own KDoc names #715 as the ticket that rebinds the archived count.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive`, `live`, `unarchive` — why a host-bound facade can never reach another host: cold reads `flatMapLatest` over *that host's* `currentRepository`, and one-shots throw `IllegalStateException` rather than fall back when it holds `null`.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `connectionFor`, `hostConnections` — `connectionFor` is keyed on the saved-host map, so a disconnected-but-saved host still resolves; that is what makes `HostDestination`'s guard safe across reconnects.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `archivedDiscussionCount`, `SettingsHost`, `SettingsHostRow.name` — the count projection that inherits its host from the constructor, and the display-name fallback rule this ticket repeats in the factory.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` → `SettingsScreen`, `SettingsRow` — `SettingsRow.onClick` is already nullable and draws no ripple when absent, which is the affordance the no-owner Archive entry uses.
- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` → `MAX_WORKSPACE_LABEL_CHARS` — the shared clamp for attacker-influenceable display text.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `unarchive`, `sendArchiveToggle`, `TYPE_UNARCHIVE_CONVERSATION` — the request/reply verb and its `conversation_updated` reply, which is what "removes that row only after server confirmation" already rests on.
- `app/src/androidTest/.../ui/settings/SettingsNavigationTest.kt` → `start`, `select`, `openSettings`, `assertOwner` — the production-route harness this ticket's device test copies, including its hostile server id.
- `app/src/androidTest/.../ui/conversations/thread/NavigationPeer.kt` → `send`, `row` — the in-process Noise peer; it serves one non-archived row and answers no archive verb today.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_archiveRestore_roundTripsListMembership` and its `ARCHIVED_ROW` / `ARCHIVED_TITLE` anchors — it reaches Archive by **tapping** the Settings row, not by route, so it is unaffected by the route change as long as that row stays tappable under an owner.
- `docs/knowledge/features/archived-discussions-screen.md` — the whole screen, its effect channel and the recorded divergence that Settings' count is discussion-only by design; that divergence is preserved here.
- `docs/knowledge/features/navigation.md` § "Host-qualified destinations", § "Host availability" — the capture-once rule and what `HostDestination`'s guard does.
- `docs/specs/architecture/749-settings-destination-host-owner.md` — the immediate blocker: the owner this destination inherits, the text-safety rule, and the `SettingsHost` no-record-field constraint.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2 (Settings entry: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2)

Node 18:2 is a `Schemes/surface` column: a 48dp back-arrow slot beside an `M3/title/large` "Archived" on `Schemes/on-surface`; a two-tab row (`M3/label/large`, selected on `Schemes/on-surface`, unselected on `Schemes/on-surface-variant`) over a 2dp `Schemes/primary` underline and a `Schemes/outline-variant` bottom border; then 16/12dp rows of `M3/title/medium` name over a 75%-opacity `M3/body/small` "Archived …" line with a 40dp trailing restore slot. All of that ships already and is preserved verbatim.

One AC-driven deviation, in the shape #749's two took: the frame draws exactly one host and headlines the bar "Archived" alone, while the ticket's Figma note requires the owning host to stay identifiable in the header. The bar's title becomes two lines — "Archived" unchanged in `titleMedium` weight terms (the existing `TopAppBar` title style) with the owner's resolved name beneath it in `labelMedium` on `onSurfaceVariant`, the same subordinate treatment `HostOwnerBadge` uses on the Settings host rows. Nothing else in the frame moves.

## Context

`ArchivedDiscussionsViewModel` already takes its repository as a constructor parameter, but Koin hands it the compatibility `ConversationRepository` — the facade that re-aims itself at whichever host was selected last. With two hosts paired, Archive therefore lists *the selected host's* archived rows no matter whose Settings it was opened from, and `unarchive` lands on whichever host is selected at the moment of the tap. Because conversation ids are host-local, two hosts can hold the same id, so the same tap can restore the wrong conversation on the wrong host and still report success.

#749 gave the Settings destination an explicit owner and #636 established the exact-host repository seam (`ThreadDestinationFactory.repository(serverId)`, a `StableConversationRepository` bound to one host's `currentRepository`). This ticket carries that ownership one destination further: the Archive route takes the owner from the Settings destination it was opened from, and every read and every write on the screen goes through that host's facade. The count on the Settings row that leads here moves to the same owner in the same change — it is the same defect one screen earlier, and #749's own KDoc defers it to this ticket by name.

No ADR is warranted: this repeats the destination-ownership decision already recorded for #636/#749 rather than making a new one.

## Design

### Route

`Routes.ARCHIVED_DISCUSSIONS` becomes `"archived_discussions/{serverId}"` — a **required path segment**, unlike `Routes.SETTINGS`'s optional query argument. Settings needs the absent-owner case because an unpaired phone goes there to pair; Archive has no such case, and a route that cannot express "no owner" is the cheapest way to keep one from being invented. Three helpers join the existing set, named for the route constant they serve:

- `Routes.archive(serverId: String): String` — `"archived_discussions/${Uri.encode(serverId)}"`, the same per-component encoding `Routes.thread` and `Routes.settings` use.
- `Routes.archiveArguments(): List<NamedNavArgument>` — one `NavType.StringType` argument, no default.
- `Routes.archiveOwner(arguments: Bundle?): String` — reads it back, mirroring `Routes.settingsOwner`.

The destination **is** wrapped in `HostDestination`, unlike Settings and like thread/literal: bouncing an unknown or removed host to the channel list is exactly AC1's "unknown/removed hosts cannot fall back to another host's archive", and the guard already exists. Its `hasHost` check resolves off the registry's saved-host map, so a merely disconnected host keeps the screen and its reconnect is invisible to the route.

The Settings block reads its own owner back with `Routes.settingsOwner(backStackEntry.arguments)` and passes `onOpenArchivedDiscussions = owner.takeIf { it.isNotEmpty() }?.let { { navController.navigate(Routes.archive(it)) } }` — the capture happens once, at tap time, from the route that already holds it.

### The no-owner Settings entry

`SettingsScreen.onOpenArchivedDiscussions` becomes `(() -> Unit)?`. `SettingsRow` already applies `Modifier.clickable` only for a non-null `onClick`, so a Settings destination that owns no host draws the row inert — no ripple, no navigation — rather than offering a tap that must then be rejected. This is the nullable-callback shape the row was built with; it is not a default, and every existing call site passes a non-null lambda unchanged.

### Bindings

`ThreadDestinationFactory` gains one method and one private helper, in the shape of its existing `literal`:

```kotlin
fun archive(handle: SavedStateHandle): ArchivedDiscussionsViewModel   // logs archive_destination_bound
private fun hostLabel(serverId: String): Flow<String>                // "" when the host is not saved
```

`archive` reads `serverId` from the entry's `SavedStateHandle` and builds `ArchivedDiscussionsViewModel(repository(serverId), hostLabel(serverId))`. `repository(serverId)` is #636's seam verbatim — a `StableConversationRepository` over that host's `currentRepository` — which is what makes the two AC2 guarantees structural rather than defensive: a cold read can only ever project that host's rows, and a one-shot with no live connection throws `IllegalStateException` instead of reaching for another host's.

`hostLabel` maps the existing private `hosts()` projection to the owner's resolved display name — `displayName` when non-blank, else the server id — the same fallback `SettingsHostRow.name` owns. Deliberately duplicated rather than shared, the way `Conversation.displayName()` is duplicated per #177: sharing it would mean exporting a resolution helper for one caller, and the rule is one line. Only the resolved `String` crosses into the ViewModel, so no `SettingsHost` — and therefore no record type — reaches this screen at all.

`settings(handle, preferences, repository)` drops its injected compatibility `repository` parameter and builds `SettingsViewModel(preferences, repository(owner), owner, hosts())`. Under a blank owner that facade is backed by a permanently-`null` repository: `observeConversations` emits `emptyList()`, so the count reads 0, which is the honest answer for a destination that owns no host. `SettingsViewModel` itself is untouched — its count projection already consumes whatever repository it is handed.

Koin: `viewModel { get<ThreadDestinationFactory>().archive(get()) }`, replacing `viewModel { ArchivedDiscussionsViewModel(get()) }`.

### ViewModel and screen

`ArchivedDiscussionsViewModel` gains a second constructor parameter `hostLabel: Flow<String> = flowOf("")` and exposes `val host: StateFlow<String>`, a `stateIn(WhileSubscribed(STOP_TIMEOUT_MILLIS), "")` lift of it. The default keeps the seventeen existing test construction sites compiling and follows `ThreadViewModel`'s precedent for defaulted collaborators; Koin always passes it explicitly. It is a separate flow rather than a field on `Loaded` because the header is drawn outside the state branch and must be stable across `Loading` / `Error` / `Loaded`.

`ArchivedDiscussionsScreen` gains `hostName: String = ""` (defaulted for the three `@Preview`s, as `effects` already is) and renders it as the `TopAppBar` title's second line when non-blank, clamped with `take(MAX_WORKSPACE_LABEL_CHARS)`, `maxLines = 1`, ellipsis overflow. `UiState`, `Event`, `Effect`, `LoadedBody` and `ArchiveRow` are untouched.

## State + concurrency model

- One new flow per destination: `host`, on `viewModelScope` via `stateIn(WhileSubscribed(5_000L))` — subscribed only while Archive is composed. No new job, no new scope, no manual cancellation path.
- `hostLabel` is cold by construction (it maps `hosts()`, which joins one `store.list()` read per registry emission), so two Archive entries on the back stack do not share a projection and nothing collects while the screen is gone.
- The restore path is unchanged: one `viewModelScope.launch` per tap, `CancellationException` rethrown before the typed catches.
- Reconnect is absorbed inside `StableConversationRepository`: `flatMapLatest` over the owner's `currentRepository` re-subscribes the new connection's projection and drops the old one. The route argument is unaffected, so restoration and compatibility-selection changes move neither the owner nor the repository.
- `LifecycleConnectionDriver`'s background `close()` takes the owner's `currentRepository` to `null`; the list falls to `emptyList()` and a restore tapped in that window throws `IllegalStateException` at call entry rather than dialling anything.

## Error handling

- Owner not saved, or unpaired while Archive is open → `HostDestination` logs `host_destination_rejected code=unknown_host` and returns to the channel list. No other host's rows are ever rendered.
- Owner saved but not connected → `emptyList()` from the cold read (the existing empty-tab copy), and `RestoreFailed` from a restore, via the unchanged `IllegalStateException` arm.
- Server `error` reply to `unarchive_conversation` → `RelayErrorException` → the unchanged payload-free `RestoreFailed`; the server-supplied message is still never read.
- A throwing upstream still reaches the existing `Error(message)` state; that path is untouched.
- Settings under a blank owner → count 0 and an inert Archive row. No new user-facing error surface.

## Testing strategy

Unit — `app/src/test/.../ui/settings/ArchivedDiscussionsViewModelTest.kt`, extending the existing rig (the new parameter is defaulted, so the seventeen existing construction sites are untouched):

- the owner's label reaches `host`, and a later emission (a rename) replaces it while the flow stays subscribed;
- an absent owner yields `""`, and the screen's header is asserted to omit it — the "unknown host names nothing" half of AC1 at this layer;
- restore still calls `unarchive` on exactly the repository the VM was constructed with when a second, differently-populated repository exists — the colliding-id case at unit scope: both repositories hold the same conversation id, only the constructed one is called.

Device, production route — new `app/src/androidTest/.../ui/settings/ArchiveNavigationTest.kt`, copying `SettingsNavigationTest`'s Koin + `RelayConnectionRegistry` + `NavigationPeer` harness, its `select(serverId)` helper and its hostile `"A /?#%"` owner id:

- **owner survives selection change and restoration** — two hosts each holding an archived conversation under the *same* id; open A's Settings, tap the real "Archived discussions" row, assert the route argument is A's exact id and that A's archived row is on screen while B's is not; flip compatibility selection to B and assert both still hold; `emulateSavedInstanceStateRestore()` and assert the same; Back then reopen under B and assert the new capture is B.
- **restore reaches only the owner** — tap restore on A's row, assert A's peer received `unarchive_conversation` for that id and **B's peer received no archive verb at all**, that the snackbar is the success copy, and that the row leaves the list only after the peer's `conversation_updated` reply.
- **a removed owner cannot reroute** — remove A's record while Archive is open and assert the destination leaves for the channel list rather than rendering B's rows.

`NavigationPeer` gains an optional archived row and an `unarchive_conversation` arm answering `conversation_updated` with `is_archived:false`; both are opt-in constructor parameters so `LiteralScreenNavigationTest` and `SettingsNavigationTest` see an unchanged peer.

`SettingsScreenTest`'s one `SettingsScreen` call site compiles unchanged against the nullable parameter; one case is added asserting the row is not clickable when the callback is null.

Rung 3/4 e2e: none added. `InteractiveStreamE2ETest.interactiveTurn_archiveRestore_roundTripsListMembership` reaches Archive by tapping the Settings row rather than by route and is preserved unchanged; #676 owns the rung-3 two-host archive/restore follow-up, per AC3.

## Documentation handoff

Pending for the documentation stage, per the ticket's own section — the builder writes none of these:

- `docs/knowledge/features/archived-discussions-screen.md` — one-host Archive ownership, the host-identified header, the host-bound repository binding, and the correction of the overview's obsolete statement that relay restore is disabled. The documented discussion-only Settings count is preserved and stays as written.
- `docs/knowledge/features/navigation.md` — the `archived_discussions/{serverId}` route, the capture from the Settings destination's own owner, and why this destination *does* take `HostDestination`'s bounce guard where Settings does not.

## Open questions

- Whether `HostDestination`'s bounce is reached on an unpair while Archive is open, or whether the registry's own teardown empties the list first and leaves a blank screen instead. The device test's removal case settles it; if the bounce does not fire, the fallback is an explicit guard in the Archive block, recorded here as a `## Revisions` entry.
- Whether the two-line `TopAppBar` title fits the existing bar height at the largest supported font scale without clipping. If it does not, the owner moves to a `SecondaryTabRow`-adjacent line rather than growing the bar.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings on the id. The route-supplied `serverId` crosses into three places and all three are exact, case-sensitive equality lookups — `registry.connectionFor`, `PairedServerCollectionStore.loadById` (inside `HostDestination`'s guard) and the match in `hostLabel`. It is never dialled, opened, used as a path, filename or cache key, and never logged. One **new** value does reach Compose: the owner's display name in the header, which originates in a scanned QR payload or locally-entered host metadata that `parsePairingPayload` validates for *shape* but not for *length*. It is clamped with `take(MAX_WORKSPACE_LABEL_CHARS)` inside `ArchivedDiscussionsScreen` — at the render boundary, not in `hostLabel`, so a future caller of the screen cannot bypass it — then drawn as plain `Text` with `maxLines = 1` and ellipsis. The archived rows' conversation names *are* daemon-authored, but that path is `ArchiveRow`'s and is untouched here; this ticket adds no new inbound verb and no new daemon-authored text.
- **[Tokens, secrets, credentials]** No findings, resting on one load-bearing constraint. `hostLabel` maps the existing `hosts()` projection, whose upstream `store.list()` returns `PairedServerEntry` records carrying the pairing token and the server static key. `hosts()` already copies three display fields out explicitly (#749's own SHOULD FIX), and `hostLabel` narrows that further to a single resolved `String`, so no `SettingsHost`, `PairedServerEntry` or `PairedServer` reaches `ArchivedDiscussionsViewModel` or the screen at all — neither of which has a redacting `toString`. The verifier must check that the ViewModel's new parameter stays `Flow<String>` and never widens to a record or to `SettingsHost`. Nothing is created, stored, rotated, revoked or compared; the Keystore-backed store is only read.
- **[File / storage operations]** No findings. Nothing is written. `Routes.archive` builds a navigation route, not a filesystem path, and per-component `Uri.encode` is what keeps a `/` or `?` inside a server id from becoming extra route syntax or a different destination's pattern. This is proven prior art rather than an assumption: `Routes.thread` and `Routes.literal` already round-trip the hostile `"A /?#%"` id through a path segment under `LiteralScreenNavigationTest`, and the new route reuses that shape rather than #749's query argument. No `allowBackup` or storage-scope change.
- **[Inter-process / Android attack surface]** No findings. The graph declares no `deepLinks`, so no third-party app can supply the owner id, and the design does not depend on that staying true: an externally supplied id can at worst be unknown, and `HostDestination` sends an unknown host to the channel list rather than to any host's archive. No exported component, `PendingIntent`, content provider or WebView is added or touched.
- **[Cryptographic primitives]** Not applicable. No RNG, hash, KDF, AEAD or handshake code is added or touched; the vendored `noise-java` stack and `NoiseIkSession` are untouched. The single new comparison is a server-id equality match — a non-secret identifier, so `String.equals` is correct and a constant-time compare would be the wrong tool.
- **[Network & I/O]** No findings. No socket, frame cap, timeout, TLS setting, certificate policy or backoff is changed. Binding the destination to a host does **not** dial it: `repository(serverId)` reads the registry's entry map and wraps that host's existing `currentRepository` flow, and `hosts()` collects an already-hot registry flow plus a store read. Opening Archive for a backgrounded host therefore cannot reopen a socket `LifecycleConnectionDriver` closed. The subscribe-time `list_conversations` refresh on a live session is unchanged in kind — what changes is only *which* host receives it, which is the defect being fixed.
- **[Error messages, logs, telemetry]** No findings. The one new log is a content-free `archive_destination_bound` debug event in the existing `thread_destination_bound` / `settings_destination_bound` shape, carrying no value. The host label is never interpolated into a log line, and the header renders it through a bare `Text` rather than a format string, so no identity rides into a resource argument. No telemetry, no release-build verbose logging.
- **[Concurrency]** No findings, and one TOCTOU deliberately designed out. The owner's repository is resolved **once**, at ViewModel construction, into a facade over that host's own `currentRepository` — not looked up per tap via something like `HostConversationSource.repositoryFor`, which would leave a gap between deciding the host and writing to it. Because the facade is bound to one host's flow, there is no state a removal or a selection change can move underneath a pending restore. If the owner is removed mid-restore, `HostDestination` unmounts the destination, `viewModelScope` is cancelled, and the existing `CancellationException` rethrow — which precedes the typed catches because `j.u.c.CancellationException` extends `IllegalStateException` on the JVM — keeps that teardown inert rather than surfacing a false `RestoreSucceeded`. One new `stateIn(WhileSubscribed(5_000L))` flow over a cold upstream; no mutex, no shared mutable state, no `runBlocking`, no work outliving the ViewModel.
- **[Threat model — hostile relay]** Addressed. The relay is content-blind inside the Noise session and can only drop, delay or reorder. A dropped `unarchive_conversation` reply leaves the row in place and fires no snackbar: success is gated on the reply, never on the send, so latency degrades into silence rather than into a false success. It cannot cross hosts — A's session can only write into A's repository, so B's identically-numbered row is unreachable from A's connection by construction, not by a check.
- **[Threat model — token theft from disk]** Addressed. No new copy of a credential record leaves Keystore-backed storage; the only thing this ticket adds to memory is the host's display name, which the Settings screen one tap back already holds.
- **[Threat model — UI-side leakage]** Accepted, AC-mandated exposure. The Archive header now shows and announces the owning host's name to screenshots, overlays and accessibility services. The name is not authentication material, and #749 already displays that name plus the full server id and relay URL on the screen this one is reached from. `FLAG_SECURE` on screens showing pairing identity has never been in scope on this pipeline and would be its own ticket.
- **[Trust boundaries]** SHOULD FIX — a blank owner must never reach `Routes.archive`. `"archived_discussions/"` matches no destination and Navigation Compose throws on it, so a future caller that forgets the check turns a no-host Settings into a crash. Not attacker-reachable (no deep links, and the only caller derives the argument from its own route), but it is a confused-implementer trap. The guard is that the Settings block builds the callback *only* for a non-empty owner and passes `null` otherwise, which `SettingsRow` renders inert — and it must be proven, not assumed: `SettingsScreenTest` asserts the row has no click action under a null callback. The verifier should check that assertion exists and that no `Routes.archive` call site can pass a blank id.
- **[Trust boundaries]** SHOULD FIX — `HostDestination`'s guard is the only thing standing between an unknown owner and an empty-but-rendered Archive screen. Under an unknown id the host-bound facade emits `emptyList()`, which renders as a plausible "No archived discussions" rather than as an error, so a guard that silently failed to fire would look like a correct empty archive instead of a defect. The device test's removal case must assert the *departure* from the destination, not merely the absence of the other host's rows.
- **[Threat model — oversized stored identifier]** OUT OF SCOPE, named so it is not silently re-discovered. A hostile QR confirmed through the fingerprint gate could store a megabyte-long `serverId`, which now also rides in this route's saved-instance-state `Bundle` exactly as it already does for `Routes.thread`, `Routes.literal` and `Routes.settings`. Blast radius is unchanged; the fix belongs at the pairing boundary in `parsePairingPayload`, filed as **#752**, and stays there per the builder's scope rule.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

## Revisions

**2026-09-21 — Open Questions resolved; no design change.**

- `HostDestination`'s bounce **does** fire on an unpair while Archive is open: removing the owner's
  record takes the destination to the channel list rather than leaving an empty-but-rendered archive.
  Proven on the managed API 33 device by `ArchiveNavigationTest`'s removal case, which asserts the
  departure rather than the absence of the other host's rows, for the reason the security review
  gives. The explicit in-block guard the plan held in reserve is not needed and was not built.
- The two-line `TopAppBar` title renders without clipping at the device's default font scale in all
  three device runs. It was **not** exercised at the largest supported font scale — that is a gap in
  what these runs prove, not a passing result: the fallback of moving the owner beside the tab row
  stays available if a later accessibility pass finds the bar too tight.

**2026-09-21 — measured size, against the plan's own estimate.** Actual total written work is
~723 added lines including this plan, against the ~600 forecast in § Size check and the ticket's
~560 estimate — inside the 800-line boundary but above both forecasts. One underestimate accounts
for nearly all of it: the device test came in at 309 lines rather than the ~230 allowed, because its
Koin + registry + `NavigationPeer` harness is ~130 lines before its first assertion and was again
counted as reuse rather than as the duplication it is. That is the same miss #749 recorded, at the
same magnitude, which makes it a property of this harness rather than an estimating slip — a shared
device-harness fixture would remove it from three tickets at once. Production files (5), exported
types (0), call sites (2), acceptance criteria (3) and reject branches (0) all landed as forecast.

**2026-09-21 — the destination has two doors, and the plan only counted one.** Verifier MUST FIX on
PR #757. § Design's Route section changed `Routes.ARCHIVED_DISCUSSIONS` from a navigable route into a
route *pattern*, but the plan's reading list covered only the callers it expected to find — `Routes`,
the destination block, `HostDestination` and the Settings row. The channel list's own archive entry,
`PyryNavHost`'s `ChannelListEvent.ArchiveTapped` arm, still navigated to the constant. Navigation
matched the pattern and bound the literal text `{serverId}` as the owner, `HostDestination` resolved
neither `hasHost` nor `isSavedHost`, and the tap bounced back to the list it came from — an
unconditional, operator-facing affordance drawn beside the settings gear on every draw of the list.

The arm now captures the selected host the way the gear one line above it does:
`destinations.selectedServerId()?.let { navController.navigate(Routes.archive(it)) }`. The two doors
capture from deliberately different sources — this one reads selection at tap time, Settings' row
inherits the owner its own destination already holds — and neither can pass a blank id, so § Security
review's first SHOULD FIX still holds across both. With no host selected the tap does nothing rather
than reaching for a route segment that matches no destination.

Two lessons, both about what the plan's reading list is for. Changing the *shape* of a shared
constant is a fan-out change even when its declaration is one line and the edit looks additive:
`codegraph_callers` on `Routes.ARCHIVED_DISCUSSIONS` would have listed both arms, and § A1's edit
fan-out check was skipped because two call sites is obviously under the boundary — the check was read
as a sizing gate rather than as the enumeration it also is. And the second door was invisible to the
whole suite: `ChannelListScreenTest.archiveEntry_emitsArchiveTapped` asserts only that the tap emits
its event, never where it navigates, while every route-level test — `ArchiveNavigationTest` and the
preserved `InteractiveStreamE2ETest.interactiveTurn_archiveRestore_roundTripsListMembership` alike —
reaches Archive through the Settings door. A green suite proved the door the tests walk through, not
the destination. `ArchiveNavigationTest.theChannelListsArchiveEntryOpensTheSelectedHostsArchive` now
covers the other one, and asserts it follows selection on a second tap.

**2026-09-21 — the owner leaves the app bar; the Open Question is closed by construction rather than
by measurement.** Verifier SHOULD FIX on PR #757, and the plan's own second Open Question. The
previous entry recorded the two-line `TopAppBar` title as unverified at large font scale, with
"move the owner beside the tab row" held in reserve. Taking the fallback is the better answer than
measuring the risk: M3's small `TopAppBar` is a fixed 64dp container, and at a 2.0 font scale the
`titleLarge` line plus the `labelMedium` line exceed it — this was the first two-line bar title in
the codebase, so nothing carried the risk. The owner now renders directly below the bar and above
`SecondaryTabRow`, still outside the `when (state)` branch so the header reads the same in Loading,
Error and Loaded. A `Text` in a `Column` grows with the font scale instead of being clipped by a
fixed height, which retires the question rather than deferring it to an accessibility pass. Figma
18:2's bar, which draws "Archived" alone, is now matched exactly; the AC's "keep the owning host
identifiable in the Archive header" is met one line lower. The clamp, the `maxLines`/ellipsis and the
render-boundary placement § Security review requires are all unchanged.

**2026-09-21 — preview coverage for the owner line.** Verifier SHOULD FIX on PR #757: all three
previews called the screen without `hostName`, so the one new visual element the PR adds rendered in
none of them. The Discussions preview now passes an ordinary short name and the Channels preview a
name long enough to drive the ellipsis, so both the common case and the overflow the clamp exists for
are visible without a device.

## Size check

Production `.kt` under `app/src/main/`: 5 (`MainActivity`, `AppModule`, `SettingsScreen`, `ArchivedDiscussionsViewModel`, `ArchivedDiscussionsScreen`) — at the boundary. Total written work ≈ 600 lines. New exported types: 0. Consumer call sites needing simultaneous update: 2 (`ArchivedDiscussionsScreen` in `MainActivity`, the `viewModel { }` binding) — both new parameters are defaulted or nullable, so no existing call site is forced. Acceptance criteria: 3. Reject branches: 0 new. Within every line of the one-ticket boundary; no split.

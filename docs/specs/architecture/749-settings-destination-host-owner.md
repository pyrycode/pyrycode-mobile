# 749 — Own the mobile Settings destination by an explicit host

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `Routes`, `PyryNavHost`'s `Routes.SETTINGS` block, `HostDestination`, `ChannelListEvent.SettingsTapped` — the destination this ticket re-owns, and the two shapes (`Routes.hostArguments`, `HostDestination`) that already express exact-host ownership for thread/literal.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `SettingsViewModel` — the injected `connectionStatus` that follows compatibility selection, i.e. the defect.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` → `SettingsScreen`, `SettingsRow`, `SettingsSectionHeader` — the Connection section's current `serverLabel` row + status line, and the row component the new identity row reuses.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory`, its `thread`/`literal` methods and the `viewModel { }` bindings — where destination ownership is captured from a `SavedStateHandle` today.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `hostConnections`, `connectionFor`, `connectionStatus` — `hostConnections` publishes each saved host's own display name and `status` flow and re-emits on every store revision; `connectionStatus` is the compatibility projection this ticket stops using for Settings.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationConnection` — the per-host identity + status descriptor the new projection maps from. It carries no relay URL, which is why the store is read alongside it.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` → `PairedServerCollectionStore.list`, `PairedServerEntry`, `PairedServer` — `relayUrl` and `displayName` live here; `PairedServer.toString` is redacted, and `token`/`serverStaticPublicKey` must never reach the screen.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` → `ConnectionStatusLine` — the existing two-part status row, reused verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `boundedText`, its `IdentityRow` doc — the prior art for rendering a server identity + relay address, including the clamp and the "caller passes display text, never a token" rule.
- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` → `MAX_WORKSPACE_LABEL_CHARS` — the shared 128-char display clamp for attacker-influenceable text.
- `app/src/main/java/de/pyryco/mobile/data/network/PairingPayloadParser.kt` → `parsePairingPayload`, `isValidRelayOrigin` — establishes that `serverId` and `relayUrl` are validated for *shape* but not for *length* at the pairing boundary.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → the list's own bar and `R.string.cd_open_settings` — the gear whose tap captures the owner.
- `app/src/androidTest/.../ui/conversations/thread/LiteralScreenNavigationTest.kt` → its `start(live)` harness, `NavigationPeer` wiring and `select(serverId)` helper — the production-route test shape this ticket's device test copies.
- `docs/knowledge/features/settings-viewmodel.md` § "How it works" — records that `connectionStatus` is forwarded **verbatim, no `stateIn` re-wrap**, because the upstream is already hot. The new owner flow is *not* in that class: it is a derived projection and does need `stateIn`.
- `docs/knowledge/features/navigation.md` § "Host-qualified destinations", § "Host availability" — the capture-once rule and why `HostDestination`'s bounce-to-list guard exists; this destination deliberately does not use it.
- `docs/knowledge/features/settings-screen.md`, `docs/knowledge/features/dependency-injection.md` — section inventory and the destination-ownership seam.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Connection section is a `Schemes/primary` `M3/label/large` header, then a two-line row — `M3/body/large` on `Schemes/on-surface` over `M3/body/small` on `Schemes/on-surface-variant` — carrying the host's name, with the `● Relay ● Pyrycode` dot pair tucked inside the same supporting block and a trailing chevron; a plain "Pair another server" row follows. This ticket keeps that treatment and its `ListItem`/`ConnectionStatusLine` components, and extends the supporting block from one line (the name) to the name-plus-id-plus-URL the AC names.

Two deliberate deviations, both AC-driven:

- The frame's literal headline "Server" becomes the **host's own name** (its server id when unnamed), with the id and relay URL moving into the supporting lines. The frame draws exactly one host; the AC requires the row to identify *which* host, and the Technical Notes require the row to be reusable for every other saved host in the next slice — a row headlined "Server" repeated per host would not identify anything.
- The trailing chevron is dropped. AC1 requires all four facts to be inert display text; a chevron announces navigation this slice does not ship (opening a host is the next slice, editing the one after).

## Context

`MainActivity`'s Settings block resolves its server label once through the compatibility `PairedServerStore.load()` (the *most recently saved* record, truncated to 12 characters), and `AppModule` hands `SettingsViewModel` the registry's compatibility `connectionStatus` projection, which re-aims itself whenever selection changes. Neither names a host. With two hosts paired, opening Settings for one and then changing selection silently re-points what the screen describes, and every host-scoped action a later slice adds to this screen would inherit that ambiguity.

This ticket gives the Settings destination the ownership the thread and literal destinations already carry: the gear captures the current host's exact server id into the route, the destination keeps that id across Back, restoration and selection changes, and the Connection section says whose settings these are.

Two shapes here are new relative to `Routes.hostArguments()` / `HostDestination`: the route is owned by a **server id alone** (no conversation id), and it must **tolerate an absent or unknown owner** by staying open rather than bouncing to the channel list — Settings is where an unpaired phone goes to pair.

App-wide settings stay app-wide. `archivedDiscussionCount` and the default-workspace picker keep their compatibility bindings, per the ticket's explicit deferral to #715 and #714.

No ADR is warranted: this repeats the destination-ownership decision already recorded for #636/#738 rather than introducing a new one.

## Design

### Route

`Routes.SETTINGS` becomes `"settings?serverId={serverId}"` — an **optional query argument** with `defaultValue = ""`, not a path segment, because a path segment cannot carry the empty owner AC3 requires. Three helpers join the existing `thread`/`literal`/`hostArguments`/`target` set:

- `Routes.settings(serverId: String?): String` — the bare `"settings"` when null/empty, otherwise `"settings?serverId=${Uri.encode(serverId)}"`. Same per-component encoding discipline as `Routes.thread`, so reserved characters in an id cannot become route syntax.
- `Routes.settingsArguments(): List<NamedNavArgument>` — one `NavType.StringType` argument with `defaultValue = ""`.
- `Routes.settingsOwner(arguments: Bundle?): String` — reads it back, mirroring `Routes.target`.

The gear's handler becomes `ChannelListEvent.SettingsTapped -> navController.navigate(Routes.settings(destinations.selectedServerId()))`: the capture happens once, at tap time, from the same adapter the list's other compatibility consumers use. The destination is **not** wrapped in `HostDestination` — its bounce-to-`channel_list` guard is exactly the behaviour AC3 forbids here.

### Host projection and ownership resolution

`ThreadDestinationFactory` gains a `settings(handle, preferences, repository)` method in the shape of its existing `thread`/`literal` methods: it reads `serverId` from the entry's `SavedStateHandle`, logs the existing `*_destination_bound` event shape, and builds the ViewModel. It also gains a private projection of every saved host's identity + live status:

```kotlin
fun settingsHosts(): Flow<List<SettingsHost>>   // demo mode yields the single DEMO_SERVER_ID entry
```

built by mapping `registry.hostConnections` (which re-emits on every store revision, so renames and pair/unpair land) and joining one `store.list()` read per emission for the `relayUrl` and `displayName` the registry does not carry. A `Flow`, not a `StateFlow`: the join suspends, and the ViewModel is the component with a scope to lift it in.

`SettingsHost` is a small `ui.settings` transport shape — `serverId`, `displayName: String?`, `relayUrl`, `status: StateFlow<ConnectionStatus>` — so the ViewModel stays free of `di`/registry types and its tests need only `MutableStateFlow`s.

`SettingsViewModel`'s constructor changes: `connectionStatus: StateFlow<ConnectionStatus>` is **removed** (it is the defect) and replaced by `ownerServerId: String` plus `hosts: Flow<List<SettingsHost>>`. Everything else — the seven preference pairs, `archivedDiscussionCount`, the workspace-picker triple — is untouched.

The VM exposes one new flow, `host: StateFlow<SettingsHostState>`, resolved by exact case-sensitive id:

| Condition | State |
| --- | --- |
| `ownerServerId` is blank | `Unpaired` — synchronously, never observing `hosts` |
| owner found in the emitted list | `Owned(serverId, displayName, relayUrl, status)`, following that host's own status flow |
| owner absent from the emitted list (including an empty list) | `Unknown` |
| non-blank owner, before the first emission | `Resolving` |

`Resolving` is the `stateIn` initial value and renders nothing, so the join's first-read latency cannot flash "no longer paired". The blank-owner short-circuit keeps AC3's no-host case correct on the first frame. `Owned` exposes `val name: String` — `displayName` when non-blank, else `serverId` — so the fallback is resolved and asserted in one place rather than inside a composable.

Implementation is a `flatMapLatest` over `hosts` into either the owner's `status.map { … }` or a constant, lifted with `stateIn(viewModelScope, WhileSubscribed(STOP_TIMEOUT_MILLIS), Resolving)` — the same idiom as the eight sibling projections. It is emphatically not the verbatim-forwarding shape `connectionStatus` used; that exemption applies only to an already-hot upstream.

### UI

New `app/src/main/java/de/pyryco/mobile/ui/settings/HostIdentityRow.kt`: a stateless `HostIdentityRow(name, serverId, relayUrl, status, modifier)` that draws the Figma's two-line row through the existing `SettingsRow` treatment — name as headline, `serverId` then `relayUrl` as supporting lines — with `ConnectionStatusLine` beneath at the Connection section's current padding. No `onClick`, no trailing content. It is a separate file, and takes four already-resolved display values rather than a `SettingsHostState`, precisely so the next slice can repeat it per saved host.

`SettingsScreen` swaps `connectionStatus: ConnectionStatus, serverLabel: String` for `host: SettingsHostState`, and gains `onPairServer: () -> Unit` for the "Pair another server" row that is inert today — AC3 requires that entry to be usable in the no-host state, which is the state in which it is the only useful thing on the screen. The Connection section renders `HostIdentityRow` for `Owned`, a supporting-text-only row for `Unknown` / `Unpaired`, and nothing for `Resolving`; the pairing row always follows. Every other section is untouched.

`MainActivity`'s Settings block drops the `PairedServerStore` injection, the `serverLabel` state and its `LaunchedEffect`, collects `vm.host`, and passes `onPairServer = { navController.navigate(Routes.SCANNER) }` — the same destination the channel list's pairing entry uses (#738).

### Text safety

`name`, `serverId` and `relayUrl` all originate in a scanned QR payload or in locally-entered host metadata, and `parsePairingPayload` bounds neither id nor URL in length. Each is clamped with `take(MAX_WORKSPACE_LABEL_CHARS)` at the row boundary — the rule `EditHostModal`'s `boundedText` records — before reaching text layout, then rendered as plain `Text` with `maxLines = 1` and ellipsis overflow. The relay URL is display text only: nothing on this screen opens or dials it. No value is interpolated into a log.

## State + concurrency model

- One new flow, on `viewModelScope` via `stateIn(WhileSubscribed(5_000L))` — subscribed only while Settings is composed, released 5 s after it leaves. No new job, no new scope, no manual cancellation path.
- `flatMapLatest` over `hosts` means a host-list re-emission cancels the previous owner's status collection before starting the next; no two status collectors overlap.
- The `store.list()` join runs inside the `map` operator on the collector's context, i.e. `viewModelScope`'s dispatcher. It is a Keystore-backed decrypt of a small record set, executed once per store revision while the screen is open.
- `LifecycleConnectionDriver`'s background `close()` moves the owner's status to its offline value and moves it back on foreground; the owner id itself is a nav argument and is unaffected. Removing the owner's record mid-screen takes it to `Unknown` and leaves the screen open.

## Error handling

- Owner not saved → `Unknown` with its own copy; never a fallback to another host's identity or status.
- Destination owns no host → `Unpaired` with its own copy; app-wide sections and the pairing entry stay usable.
- A throwing `hosts` upstream (an unreadable paired-server blob makes `list()` return empty rather than throw, so this is defence in depth) → `catch` to `Unknown`, matching the "supportive-metadata projections swallow upstream errors" rule the archived-count projection established. The screen is never torn down by it.
- No user-facing error surface is added; there is no write on this path.

## Testing strategy

Unit — `app/src/test/.../ui/settings/SettingsViewModelTest.kt`, extending the existing rig; `makeVm` swaps its defaulted `connectionStatus` parameter for defaulted `ownerServerId` + `hosts`, so the 40 existing tests compile unchanged:

- owner resolved among two saved hosts, with the other host's name, id, URL and status all absent from the result;
- owner unchanged across a host-list re-emission that reorders the list (the selection-change analogue at this layer, where selection is not observable at all);
- owner's rename and status change both reaching `Owned` while the flow stays subscribed;
- unnamed owner falling back to its server id, and a blank-string display name treated as unnamed;
- unknown owner → `Unknown`; last host removed under a non-blank owner → `Unknown`;
- blank owner → `Unpaired`, and still `Unpaired` after a host appears;
- initial value is `Resolving` for a non-blank owner before the first emission.

Device, production route — new `app/src/androidTest/.../ui/settings/SettingsNavigationTest.kt`, copying `LiteralScreenNavigationTest`'s Koin + `RelayConnectionRegistry` + `NavigationPeer` harness and its `select(serverId)` helper:

- **owner survives selection and restoration** — two hosts on a live registry, select A, tap the real gear by `R.string.cd_open_settings`, assert the route argument is A's exact id and the section shows A's name/id/URL; close B's supervisor so the two hosts hold different statuses; flip selection to B and assert both the identity and the status still describe A; `emulateSavedInstanceStateRestore()` and assert the same; Back then reopen under B's selection and assert the new capture is B.
- **unknown and absent owners keep the screen open** — navigate to `Routes.settings("ghost")` and assert the unknown copy plus no saved host's name, id or URL anywhere on screen, with the Appearance section still rendered; remove both hosts, open `Routes.settings(null)`, assert the no-host copy and that the pairing row reaches the scanner.

Server ids in the device test carry reserved characters (`A /?#%`, as `LiteralScreenNavigationTest` uses) so query encoding is proven, not assumed.

`SettingsScreenTest`'s three existing call sites are updated for the new parameters, with one added assertion per non-`Owned` state.

Rung 3/4 e2e: none. This is a navigation-ownership and presentation slice with no wire traffic; #676 owns the rung-3 two-host settings-management run, per AC4.

## Documentation handoff

Pending for the documentation stage, per the ticket's own section:

- `docs/knowledge/features/navigation.md` — the `settings?serverId={serverId}` optional-argument route, the gear's capture, and why this destination deliberately does not use `HostDestination`'s bounce guard.
- `docs/knowledge/features/settings-screen.md` — the Connection section's host identity row, the two non-owned states, the newly wired pairing entry, and the two Figma deviations.
- `docs/knowledge/features/settings-viewmodel.md` — `connectionStatus` removed, `ownerServerId` + `hosts` added, `host: StateFlow<SettingsHostState>` and why it is a `stateIn` projection rather than the verbatim forward its predecessor was.
- `docs/knowledge/features/dependency-injection.md` — `ThreadDestinationFactory.settings` and the host projection that joins `hostConnections` with the collection store.

All four state which Settings consumers stay app-wide or compatibility-bound pending #714 and #715. The builder writes none of these.

## Open questions

- Whether Navigation Compose round-trips a percent-encoded reserved character through an optional **query** argument as cleanly as it does through the path segments `Routes.thread` uses. The device test's hostile id settles it; if it does not, the fallback is a path segment plus an explicit sentinel for the empty owner, recorded here as a `## Revisions` entry.
- Whether `Resolving` is ever observable in practice on a warm store, or only theoretically. It stays regardless — the cost is one branch and the alternative is a wrong-copy flash.

## Revisions

**2026-09-21 — Open Questions resolved; no design change.**

- Navigation Compose does round-trip a percent-encoded reserved character through the optional
  query argument, and it does match the bare `"settings"` route against the
  `"settings?serverId={serverId}"` pattern via the argument's empty default. Proven on the managed
  API 33 device by `SettingsNavigationTest`, whose owner id is `A /?#%` and whose second case opens
  Settings with no host saved. The path-segment-plus-sentinel fallback the plan held in reserve is
  not needed and was not built.
- `SettingsHostState.Resolving` never became observable in the device runs — the join resolved
  inside the first frame both times. It stays, as the plan said it would: the cost is one branch,
  and the alternative is a wrong-copy flash on a cold or slow store read.

**2026-09-21 — measured size, against the plan's own estimate.** Actual total written work is
~1025 lines, not the ~750 forecast in § Size check, and above the 800-line boundary. The three
underestimates, in order of size: the device test's Koin + registry + `NavigationPeer` harness is
~110 lines before its first assertion (copied from `LiteralScreenNavigationTest`, which the
estimate treated as reuse rather than duplication); the view-model tests came in at 160 rather
than 120; and this plan at 174 rather than 160. File count, exported types, call sites, acceptance
criteria and reject branches all landed inside their limits as forecast. Recorded here because the
boundary's calibration depends on real numbers, not on the forecast that cleared it.

## Size check

Production `.kt` under `app/src/main/`: 5 (`MainActivity`, `SettingsViewModel`, `SettingsScreen`, `HostIdentityRow`, `AppModule`) — at the boundary. Total written work ≈ 750 lines. New exported types: 3 (`SettingsHost`, the `SettingsHostState` family, `HostIdentityRow`). Consumer call sites: 10 (`SettingsScreen` ×6, `SettingsViewModel` ×2, `Routes.SETTINGS` ×2) — at the boundary. Acceptance criteria: 4. Reject branches: 3. Within every line of the one-ticket boundary; no split.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The boundary is one exact, case-sensitive `serverId` equality match against the saved-host list, and nothing else is done with the route-supplied id — it is never dialled, opened, used as a path or key, or logged. The three values that reach the screen (`displayName`, `serverId`, `relayUrl`) originate in the QR payload or local metadata, which `parsePairingPayload` validates for *shape* (non-blank, `ws`/`wss` scheme, non-empty host) but **not for length**; `HostIdentityRow` clamps all three with `take(MAX_WORKSPACE_LABEL_CHARS)` at its own boundary before text layout, per the rule `EditHostModal`'s `boundedText` records. Note what does **not** apply here: no daemon-authored text is on this path at all — the identity fields never come off the wire, so the "new inbound verb carrying daemon text into Compose" rule is not what guards this screen; the same clamp is applied anyway because QR-authored text is equally attacker-influenceable.
- **[Trust boundaries]** SHOULD FIX — `settingsHosts()` reads `PairedServerEntry`, which carries `token` and `serverStaticPublicKey` alongside the three display fields. The projection must copy the three fields explicitly inside the `map` so no `PairedServerEntry` or `PairedServer` escapes into `SettingsHost`, `SettingsHostState` or the composable. This is load-bearing rather than stylistic: `PairedServer.toString` is redacted, but `SettingsHost` and `Owned` are ordinary data classes whose generated `toString` would render every field into a crash trace. Never give either of them a record-typed field; the verifier must check that they hold four and four scalars respectively.
- **[Tokens, secrets, credentials]** No findings. No token or key is created, stored, rotated, revoked, compared or displayed on this path; no new persistence is introduced and the Keystore-wrapped store is only read. The single new comparison is `it.serverId == ownerServerId` — a non-secret identifier match, so `String.equals` is correct here and a constant-time compare would be the wrong tool.
- **[File / storage operations]** No findings by design decision: the relay URL is display text only. It is never opened, dialled, canonicalised, or used as a path, filename or cache key — the live endpoint stays the stored record read through the relay supervisor. No file is written, so there is no atomicity, TOCTOU or partial-state question; `allowBackup` and storage scope are untouched.
- **[Inter-process / Android attack surface]** No findings. The graph declares no `deepLinks` (recorded in `navigation.md` § Edge cases), so no third-party app can supply the owner id. The design does not depend on that staying true: because the id is used only for exact equality against saved ids, an externally supplied one can at worst resolve to `Unknown`, which renders no host's identity or status. No exported component, `PendingIntent`, content provider or WebView is involved, and `onPairServer` navigates to an in-graph route rather than constructing an `Intent` from any stored value.
- **[Cryptographic primitives]** Not applicable. No RNG, hash, KDF, AEAD or handshake code is added or touched; the Noise stack and `NoiseIkSession` are untouched, and no key or nonce is read, derived or compared.
- **[Network & I/O]** No findings. No socket, frame, timeout, TLS setting, certificate policy or backoff is changed. Subscribing to a host's `status` flow does **not** dial it — those are the coordinators' existing hot flows, so opening Settings for a backgrounded host cannot reopen a socket that `LifecycleConnectionDriver` closed.
- **[Error messages, logs, telemetry]** No findings. The one new log is a content-free `settings_destination_bound` debug event in the existing `thread_destination_bound` shape, carrying no value. Both new user-facing strings are static resources with **no** format arguments, so no identity or relay address can be embedded in one and announced by TalkBack — deliberately unlike `edit_host_unpair_confirm_body`, which does take `%1$s`. No telemetry, no release-build verbose logging. `SettingsHostState` is never interpolated into a log line.
- **[Concurrency]** No findings. One flow on `viewModelScope` with `WhileSubscribed(5_000L)`; `flatMapLatest` guarantees at most one owner-status collector and cancels it on the emission that a removal itself produces, so a removed host cannot leave a live collector. No mutex, no shared mutable state, no check-then-act across a suspension point, no `runBlocking`, no work outliving the `ViewModel`. `hosts` is deliberately cold so two Settings back-stack entries do not share a projection. Nothing is written, so process death mid-read loses nothing.
- **[Threat model — hostile relay]** Addressed. An on-path relay can force the owner's status to `Offline`/`Reconnecting`; the screen reports that honestly and reveals nothing further. No relay-supplied bytes are parsed on this path.
- **[Threat model — token theft from disk]** Addressed. No new copy of the credential record leaves Keystore-backed storage; the projection holds in memory only the same three display fields the channel-list tree already holds.
- **[Threat model — UI-side leakage]** Accepted, AC-mandated exposure. The Connection section now shows the **full** server id and the relay URL where it previously showed a 12-character prefix, which widens what a screenshot, screen overlay or accessibility service can harvest. Neither value is authentication material — the token and the server static key are, and neither appears on this screen or in any type it touches — and `EditHostModal` already displays both in full one tap away. `FLAG_SECURE` on screens showing pairing identity has never been in scope on this pipeline and would be its own ticket.
- **[Threat model — oversized stored identifier]** OUT OF SCOPE, and named so it is not silently re-discovered. A hostile QR confirmed through the fingerprint gate could store a megabyte-long `serverId`, which would then ride in this route's saved-instance-state `Bundle`. The exposure is pre-existing and unchanged in blast radius: `Routes.thread` and `Routes.literal` already carry `serverId` in exactly the same way. The fix belongs at the pairing boundary — a length bound in `parsePairingPayload`, alongside its existing `MISSING_FIELD` / `INVALID_RELAY` checks — not in this ticket's diff. Filed as **#752** and left there per the builder's scope rule.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

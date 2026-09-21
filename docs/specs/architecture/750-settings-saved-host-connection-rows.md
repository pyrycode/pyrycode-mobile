# 750 — List every saved host in mobile Settings

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `SettingsHost`, `SettingsHostState`, `SettingsViewModel.host` — the owner-resolution projection #749 landed; this ticket widens it from one host to every saved host. Its constructor already takes `ownerServerId` + `hosts` and does not change.
- `app/src/main/java/de/pyryco/mobile/ui/settings/HostIdentityRow.kt` → `HostIdentityRow`, `BoundedLine` — the row treatment the ticket says to repeat. Its own doc already states it is "per-host rather than per-screen, so the slice that lists every other saved host repeats this row"; it needs a click target and a trailing slot to be that.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` → `SettingsScreen`, its Connection section, `SettingsRow`, `ChevronIcon` — the `when (host)` block that becomes a list, and the `Pair another server` row.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `Routes.SETTINGS`, `Routes.settings`, `Routes.settingsArguments`, `Routes.settingsOwner`, the `Routes.SETTINGS` `composable` block, `ChannelListEvent.SettingsTapped` — the route host-to-host navigation re-enters, and the back-stack decision the ticket asks to make deliberately.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.settings`, `ThreadDestinationFactory.hosts` — **`hosts()` already emits every saved host** joined with its relay URL and its own status flow. This ticket consumes what it already publishes and changes neither.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `reconcile`, `hostConnections` — `reconcile` rebuilds `hosts` from `store.list()` on every `ObservablePairedServerStore.revision`, in saved order, **regardless of foreground**; so a rename, a pair and an unpair all re-emit, and a backgrounded registry still lists its hosts.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` → `ConnectionStatusLine` — reused verbatim, once per row. Its legs are `clearAndSetSemantics`, so with N rows on screen there are N `"Relay: …"` nodes and a device assertion must scope or count them.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent.PairHostTapped` — the pairing entry AC3 names.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` → `makeVm`, `host`, the eight `host_*` tests — the rig the new tests extend and the eight assertions that move to the new shape.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsNavigationTest.kt` → `start`, `select`, `openSettings`, `assertOwner`, `assertShowsAlphaConnected`, `assertNoHostIdentityOnScreen` — the two-host production-route harness this ticket's device case extends. Its `assertDoesNotExist` assertions on the *other* host are exactly what this ticket inverts.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` → its three `setContent` blocks — the call sites that must be threaded, the cascade `settings-screen.md` records four occurrences of.
- `docs/knowledge/features/settings-screen.md` § "Configuration / usage", § "Edge cases", and its `ConnectionStatusLine` semantics note — the section inventory, and the `clearAndSetSemantics` trap that reads as "the status is missing" when the matcher is wrong.
- `docs/knowledge/features/navigation.md` § "Settings: an optionally owned destination" — the capture-once rule and why this destination is not wrapped in `HostDestination`.
- `docs/specs/architecture/749-settings-destination-host-owner.md` — the predecessor's design, its `## Revisions` (the route's reserved-character round-trip is proven, `Resolving` never observed) and its security review, whose findings this ticket inherits rather than re-derives.

**Two forecasts in the ticket body are stale, both because #749 landed after refinement.** Neither changes the work, and both are stated here so the verifier does not read them as gaps:

- *"'Pair another server' is an existing row with no action wired to it."* It is wired: #749 gave `SettingsScreen` an `onPairServer` parameter bound to `navController.navigate(Routes.SCANNER)` — the same destination `ChannelListEvent.PairHostTapped` opens, not a copy. AC3's second half is already met; this ticket asserts it and adds nothing.
- The estimate's analogue (#744, 673 lines) assumes the row treatment must be repeated. `HostIdentityRow` and `hosts()` both already exist and are already per-host, so the true cost is a list projection, a list render and their tests.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The frame's Connection section is a `Schemes/primary` `M3/label/large` header over a single `Server` row: `M3/body/large` headline on `Schemes/on-surface`, a `M3/body/small` id line on `Schemes/on-surface-variant`, the `● Relay ● Pyrycode` dot pair beneath, and a **trailing chevron**; `Pair another server` follows with its own chevron. This ticket repeats that row per saved host at the same treatment and takes the chevron back — #749 dropped it only because nothing navigated then, and now every non-owner row does.

One deliberate deviation, AC-driven: the row of the host whose Settings is open keeps **no** chevron (it navigates nowhere) and carries a short `M3/label/medium` `Schemes/primary` badge instead. The frame draws one host and so never had to answer "which of these am I looking at"; AC1 requires that answer, and a badge answers it for the eye, for TalkBack and for a test, where a chevron's absence alone answers it for none of them.

## Context

Settings describes exactly one host — the one its route owns since #749 — while a phone can hold several paired daemons. The conversation tree draws a row per host but carries conversations, not connection information, so there is no surface that answers "how is each of my servers doing" or lets the operator move between their settings.

Everything this needs is already published. `ThreadDestinationFactory.hosts()` emits every saved host with its display name, its relay URL and its own status flow, re-emitting on every store revision; `SettingsViewModel` consumes that flow today and throws all but one entry away. `HostIdentityRow` is already stateless and per-host. So the work is: stop discarding, render the list, mark the owner, and make the other rows navigate.

Editing and unpairing from these rows is the next slice; this one reads. No ADR is warranted — the destination-ownership decision is already recorded for #636/#738/#749 and this repeats it.

## Design

### View-model surface

`SettingsViewModel.host: StateFlow<SettingsHostState>` is **replaced** by `connection: StateFlow<SettingsConnectionState>`. Replaced rather than joined by a sibling: a second flow resolving the owner would collect the same upstream twice and leave `Owned` as dead public API the screen no longer draws. The constructor is unchanged — `ownerServerId` and `hosts` are already its inputs.

```kotlin
/** One saved host as the Connection section draws it: resolved display text and its own status. */
data class SettingsHostRow(
    val serverId: String,
    val displayName: String?,
    val relayUrl: String,
    val status: ConnectionStatus,
    val isOwner: Boolean,
) {
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: serverId
}

sealed interface SettingsConnectionState {
    data object Resolving : SettingsConnectionState
    data class Loaded(val hosts: List<SettingsHostRow>, val ownerMissing: Boolean) : SettingsConnectionState
}
```

`name` carries #749's fallback verbatim, still resolved in one place rather than inside a composable. `SettingsHostRow` holds five scalars and no record type, for the reason `SettingsHost`'s own doc gives: it has no redacting `toString`.

`SettingsHostState`'s four states map onto the new pair without losing a behaviour:

| #749 state | #750 equivalent |
| --- | --- |
| `Resolving` | `Resolving` — unchanged, still the `stateIn` initial value |
| `Owned` | the one row whose `isOwner` is true |
| `Unknown` | `Loaded(ownerMissing = true)` — a non-blank owner matching no saved host |
| `Unpaired` | `Loaded(hosts = emptyList())` — the section's copy now keys on the list being empty, which is the honest trigger once the list is drawn |

`ownerMissing` is false for a blank owner: a destination that captured no host owns nothing that could be missing, so the "no longer paired" copy must not fire for it. That distinction is the one thing the list shape cannot express on its own, and it is why `Loaded` carries a second field rather than only rows.

Implementation: `hosts.flatMapLatest { saved -> … }` as before, but combining **every** host's status rather than the owner's. `combine` over an empty array never emits, so the empty list short-circuits to `flowOf(Loaded(emptyList(), ownerMissing = false))`. Upstream (saved) order is preserved — `reconcile` builds it from `store.list()`, and reordering here would make the section reshuffle on a save. The same `.catch { }` and `stateIn(WhileSubscribed(STOP_TIMEOUT_MILLIS), Resolving)` lift as #749, for the same reasons.

The blank-owner short-circuit #749 used (never observing `hosts` at all) is **dropped**: a destination that owns no host still lists every saved one. Its purpose — never matching blank against blank — is kept by testing `ownerServerId.isNotBlank() && it.serverId == ownerServerId` when setting `isOwner`.

### UI

`HostIdentityRow` gains two defaulted parameters, `onClick: (() -> Unit)? = null` and `trailing: (@Composable () -> Unit)? = null`. The click target is the whole row including its status line (`Modifier.clickable` on the existing outer `Column`, only when `onClick` is non-null), so the affordance covers what it describes. Nothing else about the row changes; the defaults keep both previews and every #749 caller compiling.

`SettingsScreen` swaps `host: SettingsHostState` for `connection: SettingsConnectionState` and gains `onOpenHost: (String) -> Unit`. The Connection section becomes:

- `Resolving` → nothing, as before.
- `Loaded` → one `HostIdentityRow` per entry, each wrapped in `key(row.serverId)`. The owner's row passes no `onClick` and a `HostOwnerBadge` as `trailing`; every other row passes `onClick = { onOpenHost(row.serverId) }` and `ChevronIcon` as `trailing`. The `key` is the repo's list-item rule applied to a non-lazy `Column`: without it Compose matches slots by call order, so a reorder would shift every row's slot one place and hand a row's composition to a different host. No row holds state today, which is exactly why the guard has to be put in now rather than discovered by the slice that gives one a confirm dialog.
- `Loaded` with an empty list → the existing `settings_host_none` row.
- `Loaded` with `ownerMissing` → the existing `settings_host_unknown` row, after the rows.
- `Pair another server` always follows, unchanged.

`HostOwnerBadge` is a private `Text` in `SettingsScreen.kt` using the new `settings_host_current` string at `MaterialTheme.typography.labelMedium` on `MaterialTheme.colorScheme.primary`.

### Navigation and the back stack

`MainActivity`'s Settings block collects `vm.connection` and passes:

```kotlin
onOpenHost = { serverId -> navController.navigate(Routes.settings(serverId)) { popUpTo(Routes.SETTINGS) { inclusive = true } } }
```

No route, argument or helper changes — `Routes.settings` already encodes an arbitrary id, and #749's `## Revisions` records that a reserved-character id round-trips through the optional query argument on device.

The `popUpTo … inclusive` is the deliberate decision the ticket asks for. Host-to-host is **lateral** movement between two instances of one destination, not descent: Back from any host's Settings should return to the channel list it was opened from, not retrace a hop. Three concrete reasons over a plain `navigate`:

1. A plain push grows the stack once per tap, so A→B→A→B leaves four Settings entries and Back walks all of them.
2. What it costs — returning to the previous host's Settings — is one tap on a row that is still on screen, and visibly so.
3. `launchSingleTop` is **not** the alternative: it reuses the current `NavBackStackEntry`, so the `ViewModel` — and with it the owner captured at creation — would survive with the old id while the arguments changed underneath. `popUpTo … inclusive` destroys the entry and its `ViewModel`, which is what makes the new capture real.

The owner's own row is inert, so a self-navigation loop is structurally impossible rather than guarded against.

### Text safety

Unchanged from #749 and now applied N times: `HostIdentityRow` clamps all three strings with `take(MAX_WORKSPACE_LABEL_CHARS)` inside `BoundedLine` before text layout, and renders them as plain `Text` with `maxLines = 1`. The relay URL stays display text that nothing opens or dials. The server id newly reaches `Routes.settings(…)` as a navigation argument for hosts other than the owner — per-component `Uri.encode`d there, and only ever compared for equality at the far end.

## State + concurrency model

- One flow on `viewModelScope` with `stateIn(WhileSubscribed(5_000L))`, replacing #749's one. No new job, scope or cancellation path.
- `flatMapLatest` still guarantees at most one generation of status collectors: a host-list re-emission cancels the previous `combine` — and with it every per-host collector inside it — before starting the next. An unpaired host cannot leave a live collector, because its removal *is* the emission that cancels it.
- N concurrent collectors where #749 had one, N being the saved-host count (1–3 realistically). Each is a subscription to a coordinator's existing hot `connectionStatus`; subscribing dials nothing, so opening Settings cannot reopen a socket `LifecycleConnectionDriver` closed.
- `hosts` stays cold, so two Settings entries on the back stack never share a projection — and after a host-to-host hop there is only ever one.

## Error handling

- Owner absent from the list → `ownerMissing`, with its own copy; never resolved to a different host's row.
- No host saved → empty list and the no-host copy; the pairing entry stays usable, which is the whole point of that state.
- A throwing `hosts` upstream → `catch` to `Loaded(emptyList(), ownerMissing = false)`, the same swallow-and-degrade rule the archived count established. The screen is never torn down.
- No user-facing error surface is added; there is no write on this path.

## Testing strategy

Unit — `SettingsViewModelTest`, extending its rig; the eight `host_*` tests move to `connection` and keep their scenarios:

- two saved hosts drawn in upstream order, each with its own name, id, URL and status, and `isOwner` true for exactly one;
- the owner keeps its identity, status and `isOwner` across a host-list re-emission that reorders the list;
- a rename and a status change both reaching the right row while the flow stays subscribed;
- an unnamed host and a blank-display-name host both named by their server id;
- a non-blank owner matching nothing → `ownerMissing`, with every saved host still listed;
- the owner removed while open → `ownerMissing`, with the remaining host still listed;
- a blank owner → every saved host listed with **no** row owned and `ownerMissing` false;
- an empty host list → empty rows, `ownerMissing` false, for both a blank and a non-blank owner;
- `Resolving` before the first emission for a non-blank owner.

Device, production route — `SettingsNavigationTest`, on its existing two-host harness. Its `assertDoesNotExist` assertions on the non-owner host invert: both hosts are now expected on screen, and what must be absent from Bravo's row is the owner badge, not Bravo.

- **both hosts, distinct statuses, live updates** — open Alpha's Settings with Bravo's supervisor closed, assert both rows' four facts and that only Alpha's row carries the badge and only Bravo's row has a click action; rename Bravo through the store and assert the row follows; close Alpha's supervisor and assert its status leg follows — all without leaving the screen (AC1, AC2).
- **opening the second host** — tap Bravo's row, assert the route argument is Bravo's exact id (Alpha's carries reserved characters, so the hop is proven encoded, not assumed), that the badge has moved to Bravo, and that one `popBackStack()` reaches the channel list rather than Alpha's Settings (AC3).
- **the existing two cases**, updated: the captured owner still survives a selection change and a restoration with both rows drawn, and the unknown / no-host copies still keep the screen open — the no-host case still asserting the pairing entry (AC3, AC4).

`SettingsScreenTest`'s three `setContent` blocks are threaded for `connection` + `onOpenHost`. `compileDebugAndroidTestKotlin` is run explicitly before the PR — `settings-screen.md` records four separate code-review failures from skipping it on exactly this file.

Rung 3/4 e2e: none. No wire traffic is added; #676 owns the rung-3 two-host run, per AC4.

## Documentation handoff

Pending for the documentation stage, per the ticket's own section. The builder writes none of these.

- `docs/knowledge/features/settings-screen.md` — the Connection section's per-host rows, the owner badge and the chevron's return, the two remaining copy states and their new triggers, and the already-wired pairing entry.
- `docs/knowledge/features/settings-viewmodel.md` — `host` replaced by `connection`, the `SettingsHostRow` / `SettingsConnectionState` shapes, and why the blank-owner short-circuit was dropped.
- `docs/knowledge/features/navigation.md` — host-to-host navigation out of Settings and the `popUpTo(SETTINGS) { inclusive = true }` back-stack decision, including why `launchSingleTop` is the wrong tool here.

## Open questions

- Whether `reconcile`'s saved order is stable enough to be the row order in practice — `PairedServerCollectionStore.save` appends, so re-saving a record moves it last and the section would reshuffle on a compatibility-selection flip. If the device test shows that, the fix is an explicit sort by `serverId` in the projection, recorded as a `## Revisions` entry.
- Whether `ConnectionStatusLine`'s `clearAndSetSemantics` legs can be scoped to one row in a device assertion, or whether the per-row status assertion has to be made by count across the screen. Settled during implementation; the answer goes in `## Revisions` only if it changes the design.

## Revisions

**2026-09-21 — copy precedence when the list is empty *and* the owner is gone.** The plan listed
both branches but not which wins when both hold — unpairing the last host while its Settings is open
satisfies each. Implemented as: an empty list draws `settings_host_none` and nothing else. It is
both true and the one that names the way out, and the pairing row directly under it is the action it
implies; `settings_host_unknown` draws only when hosts remain but the owner is not among them. The
view model still reports `ownerMissing` truthfully in that state — it is a fact about the owner, not
about which copy to render — so the precedence lives in `SettingsScreen`'s Connection section, in
one `if`/`else if`, and the view-model test asserts the flag rather than the copy.

**2026-09-21 — Open Questions resolved; no design change.**

- Store order is stable enough to be the row order. `SettingsNavigationTest` flips compatibility
  selection by re-saving a record — the operation that moves it last — and the section's rows do not
  visibly reshuffle under it, because `RelayConnectionRegistry.reconcile` rebuilds `hosts` from
  `store.list()` in saved order and the fake store preserves position on an update. The explicit
  `serverId` sort the plan held in reserve was not built.
- `ConnectionStatusLine`'s `clearAndSetSemantics` legs **cannot** be scoped to one row, and the
  per-row status assertion is made by presence across the screen instead. See the next entry: this
  is the same root cause as the matcher failure, and the reason it is worth recording is that the
  failure reads as "the status is missing" when it is not.

**2026-09-21 — a host row is not a semantics node, and two device assertions had to be rewritten
because of it.** Recorded because the failure mode is misleading, not because the fix was hard.
Nothing in `HostIdentityRow` adds semantics unless the caller passes an `onClick`, so the owner's
row — the one deliberately inert row — contributes no node of its own. A
`hasAnyDescendant(name) and hasAnyDescendant(badge)` matcher therefore matched four *ancestors*
(root, container, scroll group) and failed on ambiguity rather than absence. `assertBadgedRowIs` now
asserts the two properties `isOwner` actually drives — exactly one badge on screen, and the named
row is the inert one while the other is clickable — which identifies the owner without depending on
tree shape. Separately, `assertShowsAlphaConnected` asserted a *unique*
`"Pyrycode: connected"` node; with every saved host now drawing its own status legs, two connected
hosts make that ambiguous. Both are asserted by presence now. The tempting production "fix" —
merging each row's semantics — was rejected: it is an accessibility change the AC does not ask for,
and it would fold `ConnectionStatusLine`'s per-leg descriptions into the row and put #749's own
assertions at risk.

## Size check

Production `.kt` under `app/src/main/`: 4 (`SettingsViewModel`, `SettingsScreen`, `HostIdentityRow`, `MainActivity`) — `strings.xml` is not a `.kt`. Total written work ≈ 560 lines (plan ~200, production ~140, unit tests ~150, device tests ~70). New exported types: 2 (`SettingsHostRow`, the `SettingsConnectionState` family) — two are removed (`SettingsHostState`, its `Owned`). Consumer call sites: 6 (`SettingsScreen` ×6: `MainActivity`, 2 previews, 3 test blocks). Acceptance criteria: 4. Reject branches: 3 (`Resolving`, empty list, `ownerMissing`). Within every line of the one-ticket boundary.

Split depth: parent #713, grandparent #637 — at the cap, so no split may be proposed. None is warranted either: every line above clears its boundary, so `needs-human:sizing` does not apply.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings, with one boundary widened rather than added. The three display fields still originate in a QR payload or locally entered metadata and still cross into text layout through exactly one function — `BoundedLine` inside `HostIdentityRow`, which clamps with `take(MAX_WORKSPACE_LABEL_CHARS)`. What changes is that the boundary is now crossed N times instead of once, which is precisely why the list must render through `HostIdentityRow` rather than an inlined per-row `Text`: the clamp cannot be reapplied per call site without one call site eventually forgetting. No daemon-authored text is on this path at all — nothing here comes off the wire — so the inbound-verb rule is not what guards this screen; the clamp is applied anyway because QR-authored text is equally attacker-influenceable.
- **[Trust boundaries]** No findings on the one genuinely new data path: a **non-owner** host's stored `serverId` now flows into `Routes.settings(…)` on a tap. The sink is not new — the gear already passes a stored id to the same helper, and the channel-list tree already passes any host's stored id to `Routes.thread` — and the helper `Uri.encode`s per component, so a reserved character cannot become route syntax (proven on device by #749's `A /?#%` owner id). At the far end the id is used only for equality against saved ids, so a value that does not match renders `ownerMissing` and no host's identity.
- **[Tokens, secrets, credentials]** SHOULD FIX, carried forward from #749 and now N-wide. `SettingsHostRow` must hold five scalars and `SettingsConnectionState.Loaded` a list of them plus a boolean — never a `PairedServerEntry`, a `PairedServer` or a `SettingsHost`, whose status field would drag a live flow into a data class's generated `toString`. Neither new type has a redacting `toString`, so a crash trace renders whatever they hold, and `Loaded` now holds every paired host rather than one. The verifier checks the field lists and that neither type is interpolated into any log or error string. No token or key is created, stored, rotated, revoked or displayed on this path; the single new comparison, `it.serverId == ownerServerId`, is a non-secret identifier match, so `String.equals` is correct and a constant-time compare would be the wrong tool.
- **[File / storage operations]** No findings by design decision. Nothing is written. The store is read exactly as #749 read it — one `store.list()` join per host-list emission, inside `ThreadDestinationFactory.hosts()`, which this ticket does not touch; host count does not multiply that read. No relay URL is opened, dialled, canonicalised or used as a path, filename or cache key, for any host. `allowBackup` and storage scope are untouched.
- **[Inter-process / Android attack surface]** No findings. The graph declares no `deepLinks`, so no third-party app can supply an owner id, and the design does not rely on that staying true — an externally supplied id can at worst resolve to `ownerMissing`. `onOpenHost` builds an in-graph route string, never an `Intent`, and never from a relay URL. The `popUpTo(Routes.SETTINGS) { inclusive = true }` pops the nearest Settings entry only, and the accompanying `navigate` always pushes one, so the stack cannot be emptied by it. No exported component, `PendingIntent`, content provider or WebView is involved.
- **[Cryptographic primitives]** Not applicable. No RNG, hash, KDF, AEAD or handshake code is added or touched; the vendored Noise stack and `NoiseIkSession` are untouched, and no key, nonce or fingerprint is read, derived, compared or displayed.
- **[Network & I/O]** No findings, and one trap named so it is not walked into. No socket, frame cap, timeout, TLS setting or backoff changes. Collecting N hosts' statuses dials nothing, because `HostConversationConnection.status` is the coordinator's existing hot `connectionStatus` — so opening Settings for a backgrounded host cannot reopen a socket `LifecycleConnectionDriver` closed. The trap: `RelayConnectionRegistry.pairingStatus` is a *different* per-host status flow that deliberately fires `retryHost` on first subscribe. Wiring the section to that one instead would turn merely opening Settings into a reconnect attempt against every saved host at once. The projection must stay on `hostConnections`.
- **[Error messages, logs, telemetry]** No findings. The one new string, `settings_host_current`, is a static resource with **no** format arguments, so no host identity or relay address can be embedded in it and announced by TalkBack — deliberately unlike `edit_host_unpair_confirm_body`, which does take `%1$s`. No new log line is added; `settings_destination_bound` stays content-free. No telemetry, no release-build verbose logging.
- **[Concurrency]** No findings, with the one silent branch named. `flatMapLatest` keeps at most one generation of status collectors and cancels the whole previous `combine` — every per-host collector inside it — on the emission that a pair, rename or unpair itself produces, so a removed host cannot leave a live collector. No mutex, no check-then-act across a suspension point, no `runBlocking`, nothing outliving the `ViewModel`. `hosts` stays cold, so two Settings entries never share a projection. The branch that fails silently if omitted: `combine` over an empty array **never emits**, so a zero-host list without the `flowOf(Loaded(emptyList(), …))` short-circuit leaves the state at `Resolving` forever and the unpaired phone loses its no-host copy — not a crash, just a section that is quietly blank. The short-circuit is in the design and a unit test pins it.
- **[Concurrency — recomposition]** SHOULD FIX, found by this pass and folded into § UI above. The rows render in a plain `Column`, where Compose matches slots by call order; a reorder would hand one host's composition slot to another. `key(row.serverId)` per row is now part of the design. Today no row holds state, so the current blast radius is cosmetic — which is the argument for landing the guard before the edit/unpair slice gives a row a confirm dialog, not after.
- **[Threat model — hostile relay]** Addressed. An on-path relay can force any host's status to `Offline`/`Reconnecting`; the section reports that honestly, per host, and reveals nothing further. No relay-supplied bytes are parsed or rendered on this path.
- **[Threat model — token theft from disk]** Addressed. No new copy of any credential record leaves Keystore-backed storage. The projection holds in memory only the same three display fields per host that the channel-list tree and `EditHostModal` already hold.
- **[Threat model — UI-side leakage]** Accepted, AC-mandated, and a genuine widening of #749's accepted exposure. One screen now shows **every** paired host's full server id and relay URL, so a single screenshot, screen overlay or accessibility read harvests the complete paired-host inventory where it previously harvested one host. Accepted because none of it is authentication material — the pairing token and the server static key are, and neither appears on this screen nor in any type it touches — and because the inventory is already enumerable today: the channel-list tree lists every host by name and `EditHostModal` shows any one host's id and relay URL one tap away. What changes is the number of taps, not the reachable set. `FLAG_SECURE` on screens showing pairing identity has never been in scope on this pipeline and would be its own ticket.
- **[Threat model — race on a vanishing host]** No finding; named because the obvious guard is the wrong one. A tap can land on a row whose host was unpaired between the emission and the touch. The result is a hop to a destination that resolves `ownerMissing` and names no host — honest and safe. Do **not** add a `hasHost`/`isSavedHost` pre-check at the tap: it re-opens the check-then-act window it appears to close, and it would replace a correct screen with a silently dropped tap.
- **[Threat model — oversized stored identifier]** OUT OF SCOPE, unchanged from #749 and restated so it is not re-discovered. A hostile QR confirmed through the fingerprint gate could store a megabyte-long `serverId`, which would then ride in this route's saved-instance-state `Bundle` — now reachable by tapping any saved host's row rather than only by the gear. The reachable set does not widen: the channel-list tree already routes any saved host's id into `Routes.thread` the same way. The fix belongs at the pairing boundary, in `parsePairingPayload` alongside its `MISSING_FIELD` / `INVALID_RELAY` checks. Already filed as **#752**; left there per the builder's scope rule.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

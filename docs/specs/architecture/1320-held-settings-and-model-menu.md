# #1320 — Keep the footer's settings and model menu across a reconnect

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/HostReadings.kt` → `HostReadings`, `whileOpen`, `close` — the #1317 pairing-scoped holder this ticket extends.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt` → `observeSessionSettings`, `sessionSettingsRead` — the `onStart { emit(null) }` head and the read whose success becomes the held value.
- `app/src/main/java/de/pyryco/mobile/data/repository/ModelMenuProjection.kt` → `modelMenusByConversation`, `askedModelMenus`, `modelListAsks`, `apply`, `observe`, `askForModelMenu` — the map moves to the holder; the ask ledgers stay per connection.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → constructor `hostReadings`, `modelMenuProjection`, `sessionSettingsCommands` — the only construction site of both classes.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `hostReadings`, `onConnection`, `close` — already owns one holder per host and threads it into each connection; no change needed.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive`, `observeSessionSettings`, `observeModelMenu` — the facade the thread holds.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `repository(serverId, bundle)` — one facade per host, built with that host's `hostReadings`; this is what keeps two hosts apart.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runConfig`, `forLiveSession` — how a `SessionSettings` with `permissionMode = ""` becomes the footer state.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig.writable` — true whenever a session id is present.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` → `hostReadings_*` cases, `openInteractiveConnection` — the #1317 two-connection and two-host test shape to mirror.
- `app/src/test/java/de/pyryco/mobile/data/repository/HostReadingFrames.kt` — shared frame fixtures; gains a `model_list` and a `session_settings` reply.
- `docs/knowledge/features/relay-repository-coordinator.md` § `HostReadings` — the #1317 rule that a held reading lives for the pairing and `close()` is the only drop.

No in-flight `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957

The composer input area. Visuals do not change: this ticket only changes when the footer's existing model, effort and permission labels have a value, so no layout, token or component work follows. (The Figma MCP server was not authorised in this session; nothing here depends on reading the node.)

## Context

On mobile every foreground is a new connection. The run-settings reading and the model menu are both rebuilt from nothing per connection, so the footer's model and effort blank, `writable` turns false, and the model menu empties until the new connection's replies land. #1317 introduced `HostReadings`, a per-host, pairing-scoped holder, for five pushed readings. This ticket adds the last successful `SessionSettings` and the model menu to it, following desktop's `invalidate()` rule for settings on the `connected` edge.

No ADR needed: this extends the #1317 decision to two more readings.

## Design

### `HostReadings`

Two new pieces of state, both keyed by conversation id and both behind the existing `whileOpen` gate so `close()` drops them like the other five:

- `internal val modelMenus: MutableStateFlow<Map<String, ModelMenu>>` — the map formerly private to `ModelMenuProjection`.
- a private `MutableStateFlow<Map<String, SessionSettings>>` of the last successful settings reply per conversation.

New surface:

- `fun observeModelMenu(conversationId: String): Flow<ModelMenu?>` — the held menu, `distinctUntilChanged`, `null` after `close()`.
- `fun observeHeldSessionSettings(conversationId: String): Flow<SessionSettings?>` — the held reading *invalidated*: `copy(permissionMode = "", memorySearch = MemorySearchReport.Unknown)`; `null` when none is held or after `close()`. A held settings reading is only ever exposed in this invalidated form; only a reply on the current connection is shown whole.
- `internal fun holdSessionSettings(conversationId: String, settings: SessionSettings)` — replace the held reading.

### `SessionSettingsCommands`

Gains a `readings: HostReadings` constructor parameter.

- The subscription head becomes the held invalidated reading (`observeHeldSessionSettings(id).first()`), which is `null` when nothing is held — today's behaviour for a fresh holder.
- A successful `readSessionSettings` stores its reply with `holdSessionSettings` before emitting it. A failed read and the non-`interactive` branch still emit `null` and leave the held reading as it was (the ticket's "last successful" rule).
- `settingsRevision` and the re-read triggers stay per connection and unchanged.

### `ModelMenuProjection`

Gains a `readings: HostReadings` constructor parameter; `modelMenusByConversation` is removed in favour of `readings.modelMenus`.

- `apply` writes into `readings.modelMenus` and records the conversation in a new per-connection `heardModelMenus` set.
- `observe` reads `readings.observeModelMenu(id)` with the same `onStart { askForModelMenu(id) }`.
- `askForModelMenu` guard 3 changes from "the map holds a menu" to "this connection heard a menu" (`heardModelMenus`), so a held menu does not suppress the new connection's ask — the AC's "the new connection asks again as it does today". `askedModelMenus`, `modelListAsks` and `send` stay per connection.

### `RemoteConversationRepository`

Passes its existing `hostReadings` into both constructions. Its default (`HostReadings(now)` per repository) keeps constructions without a coordinator connection-scoped, so the head stays `null` and the menu starts empty as before.

### `StableConversationRepository`

A private helper `switchToLiveOrHeld(held: Flow<T>, select)` = `currentRepository.flatMapLatest { repo -> repo?.let(select) ?: held }`.

- `observeSessionSettings`: with `heldReadings`, the live repository's read while connected, `heldReadings.observeHeldSessionSettings(id)` in the gap. Without it, unchanged `switchToLive(null)`.
- `observeModelMenu`: with `heldReadings`, the live repository's menu while connected (which reads the same holder and still triggers that connection's ask), `heldReadings.observeModelMenu(id)` in the gap. Without it, unchanged.

The live path still goes through the connection's repository because the subscription is what issues the settings read and the model-list ask on that connection.

### `RelayRepositoryCoordinator`

No change: it already owns one `HostReadings` per host, threads it into each connection and closes it on unpair or re-pair.

## State + concurrency model

- Both new maps are `MutableStateFlow` written with atomic `update`. The menu map has one writer (the connection's inbound collector); the settings map is written from the settings read's collector coroutine. A superseded read is cancelled by `flatMapLatest` before it can store.
- Across a reconnect the old connection's scope is cancelled before the new one is published, so an old connection's late reply cannot store into the holder after the new one has started (its waiter is swept on teardown).
- `close()` flips the existing `open` flag; reads switch to `null` immediately, including a facade collector sitting in the disconnected gap.
- No new scope, job or dispatcher.

## Error handling

- A failed or ungated settings read emits `null` as today and does not touch the held reading.
- A malformed `model_list` stores nothing, as today; the held menu stands.
- No logs are added: every value here is claude- or daemon-authored, the #1317 `HostReadings` posture.

## Testing strategy

Unit tests only (no UI change):

- `RelayRepositoryCoordinatorTest`, through a `StableConversationRepository(coordinator.currentRepository, coordinator.hostReadings)`:
  - settings across two connections: full reply on connection 1 → gap shows the invalidated reading → connection 2's head is the invalidated reading (model, effort, session id kept; permission `""`; memory search `Unknown`) → connection 2 sent its own `request_session_settings` → its reply replaces the whole reading.
  - a fresh coordinator's first subscription head is `null`.
  - model menu across two connections: menu held through the gap and into connection 2; connection 2 still sends `request_model_list`; a `model_list` on connection 2 replaces the menu.
  - a `session_transition` on connection 2 re-reads and the reply replaces the held reading (existing trigger still fires).
  - two hosts: host A's held settings and menu never appear through host B; `close()` on A drops both, including for a collector in the gap.
- `StableConversationRepositoryTest`: with a `HostReadings` and no live repository, the facade reports the held invalidated settings and the held menu, and `null` after `close()`; existing compat cases unchanged.
- A `ThreadViewModel` test on `ThreadRunConfig`: an invalidated held reading keeps `savedModel`, `savedEffort`, `writable`; `permissionMode` is `""` and `memorySearch` `Unknown`; the following full reply restores the permission mode.
- Existing `RemoteConversationRepositoryTest` (`SessionSettingsCommands`, `ModelMenuProjection` coverage), `RemoteConversationRepositoryRunReadingsTest` and `StableConversationRepositoryTest` run unchanged.

Not operator-facing in the rung-3 sense: no new flow, only a reconnect state change — no e2e scenario.

## Open questions

- Should the gap (disconnected) show the full held reading rather than the invalidated one? Chosen: invalidated, so a held permission mode is never shown as confirmed and the gap and the new connection's head agree. #1319 already greys out the footer while disconnected.

## Documentation handoff

Pending for the documentation stage: fold the two new held readings into the `HostReadings` sections of `docs/knowledge/features/relay-repository-coordinator.md` and the `StableConversationRepository` overview (the settings and model-menu reads are no longer purely switched when a holder is given).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — no new decode path. Settings still cross at `toSessionSettings` and menus at `ModelMenuProjection`'s `decodeModelList`; the holder stores only those decoded domain values. Routing is unchanged: a settings reply is filed under the id this phone asked for, a menu under the payload's own `conversation_id`. Rendering is unchanged (`ThreadViewModel`'s `inert` path).
- [Trust boundaries — host attribution] No findings — the risk this label is about. One `HostReadings` per coordinator, which is per host and per pairing; `AppModule`'s `repository(serverId, bundle)` builds each host's facade with that host's holder, so host B's facade cannot read host A's holder. Unpair and re-pair close the coordinator, and `close()` flips `whileOpen`, so a facade collector parked in the disconnected gap switches to `null` at once. Covered by the two-host and close tests.
- [Trust boundaries — confirmed-looking permission] No findings — a held settings reading is only ever exposed through `observeHeldSessionSettings`, which blanks `permissionMode` and resets `memorySearch` to `Unknown`; only a reply on the live connection reaches a consumer whole. `writable` staying true while disconnected is gated by #1319's connection check on every write.
- [Tokens, secrets] No findings — nothing secret is held; no token or key path is touched.
- [File / storage] No findings — in memory only, process lifetime at most; `CachingConversationRepository` delegates both reads without caching them, so nothing reaches disk.
- [Android attack surface] No findings — no intents, deep links, providers, WebViews or push paths touched.
- [Crypto] No findings — not touched.
- [Network & I/O] SHOULD FIX (accepted as-is) — the menu map's keys come from daemon-authored `model_list` payloads, so a hostile daemon could grow it; that was already true per connection and now lives for the pairing. The paired daemon is the authenticated peer (Noise IK) and is already able to flood within one connection, so this adds no new attacker. The ask ledger stays per connection, so asks per connection remain bounded by the conversations subscribed.
- [Logs] No findings — no logs added; held values are claude/daemon-authored and the #1317 no-log posture holds.
- [Concurrency] No findings — writes use atomic `MutableStateFlow.update`. A settings read runs in the collector's coroutine and is cancelled by the facade's `flatMapLatest` when the connection changes; in the narrow window where a reply resumes after the switch, the worst case is storing the *same host's* valid reply, which is then only exposed invalidated. No new scope or job.
- [Threat model] OUT OF SCOPE — a hostile relay can only delay or drop frames; delay now leaves the held (invalidated) reading on screen rather than a blank, which #1319's connection gate already covers. No follow-up needed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01

# Spec #398 — wire live two-part connection status into the Settings Connection section

**Ticket:** [#398](https://github.com/pyrycode/pyrycode-mobile/issues/398) — `feat(ui/settings)` · size:s · split from #390 (slice B; renders the component + green token shipped by #397, `blockedBy #397` which has landed on `main`).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=90-4

A horizontal row of two legs — **Relay** (dot + label) and **Pyrycode** (dot + label) — 8 px circular dots, M3 body-small labels in `Schemes/on-surface-variant`, each dot green when its leg is up and `error` red when down. The reference screenshot shows the green-Relay + red-Pyrycode "relay reachable, no daemon behind it" diagnostic that motivates the whole epic. Rendered under the **Server** row of the **Connection** section in the Settings frame [`17-2`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2). Design landed 2026-06-08 (resolves the prior "design owed" note from #397).

**This slice draws nothing new.** The line's visuals are owned by `ConnectionStatusLine` (shipped in #397), which renders each leg as dot + name + a short textual state label (e.g. "Connected", "Reachable") — slightly richer than node `90-4`, which shows only dot + name. That extra label is intentional (a11y / colour-blind legibility, owned by #397), **not** a deviation introduced here. #398 only places that component under the Server row and feeds it the live flow.

## Context

The app has no positive connection indicator — `Connected` is conveyed only by the **absence** of `ConnectionBanner`. Live phone↔daemon testing on 2026-06-08 surfaced a daemon-not-on-relay bug that presented only as a generic "Offline," with no hint of which leg was broken. #397 built the reusable `● Relay   ● Pyrycode` component and its green "up" token. This slice wires the **live data path**: surface the already-live combined status onto `SettingsViewModel`, inject it through Koin, collect it at the screen host, and render the component under the Server row.

Everything consumed already exists on `main`: `RelayRepositoryCoordinator.connectionStatus: StateFlow<ConnectionStatus>` (from #391/#392), `SettingsViewModel`, the Settings screen + its Connection/Server row, and `ConnectionStatusLine` (#397). No transport, crypto, model, or server change. Both signals are already honest — the relay's `4404 no-server` close surfaces as `RelayLinkStatus.DaemonAbsent` (rendered green by #397's mapper, AC#2), and `PyrycodeLinkStatus.Connected` is reached only after the Noise handshake completes.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:21-86` — the constructor (`appPreferences`, `conversationRepository`) and the existing flow declarations. Add a third constructor param and expose the forwarded flow. Note: every other flow here uses `stateIn(WhileSubscribed)` **because its upstream is a cold DataStore flow** — that does not apply to the new one (see Design).
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:88-111` — **read-only.** `currentRepository` (88-89) is the precedent: a hot `StateFlow` the coordinator publishes that consumers fetch verbatim. `connectionStatus` (106-111) is the flow this slice surfaces — already `stateIn(scope, SharingStarted.Eagerly, …)` on the coordinator's process-lifetime scope, so `.value` is always correct.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:78-97` — the coordinator singleton (78-88, `createdAtStart = true`, exists regardless of the `USE_RELAY_REPOSITORY` flag), the `currentRepository`-fetch precedent (93: `StableConversationRepository(get<RelayRepositoryCoordinator>().currentRepository)`), and the `SettingsViewModel` factory (97). Mirror the precedent on line 97.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:389-423` — the `Routes.SETTINGS` composable: where the VM is obtained and each flow is `collectAsStateWithLifecycle()`'d into `SettingsScreen`. `collectAsStateWithLifecycle` is already imported (line 33). Add one collect + one argument.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:47-152` — the stateless signature (47-72; note **no state param is defaulted** — only `modifier`), and the Connection section (141-152): `SettingsSectionHeader("Connection")` → Server `SettingsRow` (142-147) → "Pair another server" row (148-152). Render the component between those two rows. Previews at 327-385 need the new arg.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt:100-113` — **read-only.** The public composable to render: `ConnectionStatusLine(status: ConnectionStatus, modifier: Modifier = Modifier)`. Pure presentation; do not re-implement its visuals.
- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionStatus.kt` (whole, 13 lines) — `data class ConnectionStatus(val relay: RelayLinkStatus, val pyrycode: PyrycodeLinkStatus)`. Plus `RelayLinkStatus.kt` / `PyrycodeLinkStatus.kt` for the case names used in test/preview constants (`RelayLinkStatus.{Connected,Connecting,Reconnecting(…),DaemonAbsent,Offline}`, `PyrycodeLinkStatus.{Handshaking,Connected,Down}`). **Read-only.**
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:60-63, 573-618` — the `makeVm` helper (60-63, where the new defaulted param goes so the 40+ existing tests stay untouched) and the `stubRepo()` fake. New tests follow the existing `runTest(dispatcher)` + `launch { collect }` + `advanceUntilIdle()` shape.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt:24-133` — three `SettingsScreen(...)` call sites (28, 64, 101) that each need the new arg to compile. They assert on other rows, so any constant value works.

## Design

Four production files modified, zero new files, zero new exported types. The unit of work is "thread one already-live `StateFlow` through the existing MVI chain (VM → DI → host → stateless screen) into an existing component."

### 1. `SettingsViewModel` — expose the forwarded flow (production)

Add the coordinator's flow as a constructor param and re-expose it **verbatim** as a public property:

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    conversationRepository: ConversationRepository,
    val connectionStatus: StateFlow<ConnectionStatus>,   // forwarded; see "no stateIn" below
) : ViewModel() { /* unchanged */ }
```

**Inject the `StateFlow`, not the whole coordinator** (resolving the ticket body's two phrasings in favour of the `currentRepository` precedent it cites). Rationale: the VM needs exactly this one signal — passing the concrete `RelayRepositoryCoordinator` would widen the dependency to `start()`/`close()`/`currentRepository` and force the unit test to construct the real 6-arg coordinator (dragging in `RelayTransport`, the pump factory, etc.). The narrow flow keeps the VM test a one-liner and mirrors `StableConversationRepository`, which takes `coordinator.currentRepository`, not the coordinator.

**No `stateIn`.** Unlike the sibling flows here (which `stateIn` a *cold* DataStore flow), `coordinator.connectionStatus` is already a hot `StateFlow` shared `Eagerly` on the coordinator's process-lifetime scope. Re-wrapping it in `viewModelScope.stateIn(WhileSubscribed)` would add a redundant layer with a worse initial value and zero benefit. Forward it directly (constructor `val`, or a body `val connectionStatus = …` assignment — equivalent; pick the constructor `val` for minimality).

### 2. `AppModule` — one-line DI (production)

```kotlin
viewModel {
    SettingsViewModel(get(), get(), get<RelayRepositoryCoordinator>().connectionStatus)
}
```

The third argument fetches the flow off the concrete coordinator singleton, exactly as line 93 does for `currentRepository`. **Not** `get(), get(), get()` — `StateFlow<ConnectionStatus>` is not a registered Koin type, and this avoids a new interface/binding (per AC#1). `RelayRepositoryCoordinator` is already imported in this file (line 24).

### 3. `SettingsScreen` — render the component under the Server row (production)

Add `connectionStatus: ConnectionStatus` as a **required** param (consistent with the screen's no-default convention for state params), placed near the other Connection-related inputs in the signature. Render the component between the Server row and the "Pair another server" row:

```kotlin
SettingsRow(headline = "Server", supporting = "juhana-mac-2026", trailing = { ChevronIcon() }, onClick = {})
ConnectionStatusLine(
    status = connectionStatus,
    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
)
SettingsRow(headline = "Pair another server", trailing = { ChevronIcon() }, onClick = {})
```

`start = 16.dp` aligns the line with the `ListItem` text inset so it reads as belonging to the Server row; `top`/`bottom` are small breathing room — confirm against the `17-2` frame and adjust if the developer's own `get_design_context` pull on `90-4`/`17-2` shows otherwise. New imports: `de.pyryco.mobile.data.model.ConnectionStatus` and `de.pyryco.mobile.ui.conversations.components.ConnectionStatusLine`. Update both `@Preview` functions (327-385) with a literal, e.g. `connectionStatus = ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down)` so the previews showcase the diagnostic state.

### 4. `MainActivity` — collect at the host (production)

In the `Routes.SETTINGS` composable (389-423), alongside the existing collects:

```kotlin
val connectionStatus by vm.connectionStatus.collectAsStateWithLifecycle()
```

and pass `connectionStatus = connectionStatus` into `SettingsScreen(...)`. This keeps the screen stateless — the value flows in via param, collected lifecycle-aware at the host (AC#3). New import: `de.pyryco.mobile.data.model.ConnectionStatus` (only if the type is referenced by name; the `by`-delegate form may not need it — let the compiler decide).

## State + concurrency model

- **Single source of state:** the coordinator owns the only `connectionStatus` `StateFlow` (hot, `Eagerly`, process-scope). The VM forwards the same instance; no parallel/derived copy is created. No new `viewModelScope` job, no new `StateFlow`, no dispatcher choice — the flow is consumed, not produced, here.
- **Collection:** lifecycle-aware via `collectAsStateWithLifecycle` at the Settings host (stops re-rendering when Settings is below `STARTED`). The upstream coordinator flow keeps running regardless — intended; it is a shared singleton signal, not VM-owned.
- **Recomposition:** `ConnectionStatus` and both leg sealed types are stable (`data class` of `data object`/`data class` cases), so passing `connectionStatus` recomposes only `ConnectionStatusLine` on change, and that composable is skippable when the value is unchanged. No lambda-stability concern (none added).

## Error handling

None at this layer. `connectionStatus` is a **total** `StateFlow<ConnectionStatus>` that never completes or throws — the coordinator already folds every relay/pump failure into the sealed leg types (`RelayLinkStatus.Offline`, `PyrycodeLinkStatus.Down`, etc.), which render as red dots. The failure modes *are* the data. Do **not** add `.catch` / try-catch / a fallback `initialValue` in the VM — the coordinator's flow already carries a correct initial value (`ConnectionStatus(relayStatus.value, PyrycodeLinkStatus.Down)`).

**Expected non-bug:** in the default debug build the `USE_RELAY_REPOSITORY` flag is OFF, so no real relay connection is ever driven and the line reads `Offline / Down` (both red). That is honest — the phone genuinely has no relay link in that build. Against a live, paired daemon (relay mode) the dots reflect reality and update live. This is correct behaviour, not a defect to "fix" by faking a connected state.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`); the wiring is observable at the `SettingsViewModel` boundary, so no new instrumented test is required. AC#4 is satisfied by a unit test (test-first: write it red against the current 2-arg constructor before adding the param).

- **`makeVm` helper:** add a third param with a default so the existing 40+ tests are untouched —
  `connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down))`.
- **New test — re-exposes the injected value:** construct the VM with `MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected))`; assert `vm.connectionStatus.value` equals that `ConnectionStatus`.
- **New test — reflects upstream re-emission (the "live" guarantee):** inject a `MutableStateFlow`, start a collector on `vm.connectionStatus`, emit a different `ConnectionStatus` (e.g. relay `DaemonAbsent`, pyrycode `Down`), `advanceUntilIdle()`, assert `vm.connectionStatus.value` updated. This proves the VM forwards the live flow rather than snapshotting it.
- **androidTest compile fix:** the three `SettingsScreen(...)` call sites in `SettingsScreenTest.kt` (28, 64, 101) each gain `connectionStatus = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)` (or any constant). Those tests assert on unrelated rows, so the value is non-load-bearing; this is a mechanical compile fix, not new coverage.

Gate: `./gradlew check` green (AC#4).

## Open questions

- **Exact inset / vertical spacing** under the Server row: the `start = 16.dp` / `top = 4.dp` / `bottom = 8.dp` above is a reasonable default matching the `ListItem` text inset; the developer should sanity-check it against a fresh `get_design_context`/screenshot of `90-4` within `17-2` and nudge if the lock shows different padding. Non-blocking — visual polish, not behaviour.

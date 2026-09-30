# #1318 — Thread reports the host connected only after its handshake

## Files read

- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread` — the anonymous `ConnectionStateSource` whose `observe()` reads `bundle.supervisor.observe()`, the relay leg alone. The one line that changes.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → `RelayLinkStatus.toConnectionState` — the existing relay-only mapping; the new two-leg mapping sits beside it and falls back to it.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `connectionStatus` — the eager `StateFlow<ConnectionStatus>` combining the supervisor's relay leg with the pump-derived pyrycode leg; the new source.
- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionStatus.kt`, `PyrycodeLinkStatus.kt` — the two-leg type; `PyrycodeLinkStatus.Connected` is reached only after the Noise handshake.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt` → `toConnectionState_mapsLegacyCasesIdentityAndDaemonAbsentToOffline` — the existing mapping test the new one sits beside.

Overlap: `feature/1305` edits `ThreadDestinationFactory.thread` (question drafts) in different lines; additive, build through.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-1740

The thread's existing Connecting indicator (`ConnectionStatusIndicator`), unchanged. No visual change: this ticket only changes when the thread reports Connecting versus Connected. (Figma MCP was unauthenticated in this run; the ticket states the visuals do not change.)

## Change

Add `internal fun ConnectionStatus.toConnectionState(): ConnectionState` beside the relay-only mapping in `RelayConnectionSupervisor.kt`: relay `Connected` with pyrycode `Connected` → `Connected`; relay `Connected` with pyrycode `Handshaking` or `Down` → `Connecting`; every other relay value → `relay.toConnectionState()` (so `Idle` stays `Connected`, `Reconnecting(n)` keeps its countdown, `Offline`/`DaemonAbsent`/`PairingRejected`/`UpdateRequired` stay `Offline`). In `ThreadDestinationFactory.thread`, `observe()` becomes `bundle.coordinator.connectionStatus.map { it.toConnectionState() }` (with `distinctUntilChanged` implied by the `StateFlow` consumer in `ThreadViewModel.stateIn`). Retry, Re-pair (`pairingRejected`) and `repositoryAvailable` are untouched, so Reconnecting, Offline Retry and Re-pair behave as today. The supervisor's own `observe()` is untouched; its other consumers keep the relay-only view.

## Testing strategy

- New unit test in `RelayConnectionSupervisorTest` over every `RelayLinkStatus` × `PyrycodeLinkStatus` pair: relay Connected pairs give Connected only with pyrycode Connected, Connecting otherwise; every other relay value gives its relay-only mapping regardless of the pyrycode leg.
- Existing thread connection tests (`ThreadViewModel*` connection tests, `RelayConnectionFactoryTest`) run unchanged through the `ConnectionStateSource` seam.

## Documentation handoff

None named by the ticket.

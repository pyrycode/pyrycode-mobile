# #1324 — Stop redialling a host that closes with a protocol mismatch

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → `runLoop`, `haltUntilRetry`, the close-code constants in the companion — the only file that changes.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt` → `rejectedPairingClose_4401or4426_haltsRedial`, `rejectedPairing_explicitRetryDialsOnce_andARepeatedRejectionHaltsAgain`, `nonDaemonClose_followsExistingReconnectPath_neverDaemonAbsent` — the halt tests the new ones mirror, and the neighbour-code test that keeps other codes on backoff.
- `../pyrycode/docs/protocol-mobile.md` § Error codes — `4421` is "Protocol mismatch (unknown `type`, bad `v`, malformed envelope)"; it is also the daemon's close for a missing first `noise_init` within 10 s.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910

Offline thread; visuals do not change. The halt publishes the existing `RelayLinkStatus.Offline`, so the existing Offline pill with Retry and the host-row reconnect render as they do today.

## Change

`runLoop` gains a fourth close classification: `Down.code == PROTOCOL_MISMATCH_CLOSE` (4421, a new companion constant) sets a `protocolMismatch` flag, and the post-dial `when` routes it to `haltUntilRetry { RelayLinkStatus.Offline }` alongside the 4401/4426 and 4412 halts. `haltUntilRetry` already drains stale retry signals, waits without timeout and redials exactly once per `retry()`; `close()` cancels the wait and the next `connect()` starts fresh. No new status type: desktop's `DEFAULT_FATAL_CLOSE_CODES` ends supervision and offers Reconnect, and Offline + Retry is mobile's equivalent. The code is compared only, never logged (the class's no-log contract). Every other code keeps its current branch.

The 10 s missing-first-frame timeout also closes with 4421, so a slow network can now halt rather than retry. Desktop accepts the same trade-off and the ticket asks to copy it; the user's Retry recovers.

Overlap: branch `feature/1318` adds a new top-level function in the same file; no shared block.

## Testing strategy

New unit tests in `RelayConnectionSupervisorTest`, fake transport, `runTest`:

- A 4421 close (after a stale retry while connected) reports `Offline`, clears `currentConnection`, and makes no further dial across ten minutes of virtual time.
- `retry()` after the halt dials exactly once; a second 4421 halts again with no more dials.

`nonDaemonClose_followsExistingReconnectPath_neverDaemonAbsent` and the existing 4401/4426/4412 tests cover the unchanged codes.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the relay-supervisor overview's list of halting close codes should add 4421 → Offline.

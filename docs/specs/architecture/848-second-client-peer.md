# #848 — a turn started from another client continues on the phone

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_pingPrompt_streamsPingReplyIntoThread`, `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`, helpers `awaitChannelList`, `awaitConnected`, `createChat`, `renameOpenThread`, `scrollListTo`, `twoHostArg` — the scenario rides these; the new method sits beside them.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `ARG_RELAY_URL`, `ARG_SERVER_ID`, `ARG_SERVER_STATIC_PUBLIC_KEY` — the peer reuses host A's pairing values, only the token differs.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/PingReplyAssertions.kt` → `PING_PROMPT`, `pingReplyMatcher` — exact-"ping" inside a message bubble; its KDoc explains why queued rows and the title can never match.
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt` → `OkHttpRelayTransport`, `defaultClient` — the peer's socket; `connect()` then `TransportEvent.Up` on `events`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt` → `NoiseSessionPump` (`start`, `state`, `inbound`, `send`, `close`) — handshake + decrypt loop on an already-Up transport; the peer is its sole `inbound` consumer.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt` → `NoiseSessionFactory` — needs a `DeviceStaticKeyStore` and a `PairedServerStore`; the peer supplies in-memory ones.
- `app/src/main/java/de/pyryco/mobile/data/crypto/DeviceStaticKeyStore.kt`, `PairedServerStore.kt` → the two interfaces the peer implements in test sources.
- `app/src/main/java/de/pyryco/mobile/data/crypto/KeystoreDeviceStaticKeyStore.kt` → derives X25519 through noise-java `Noise.createDH("25519")`; the throwaway key uses the same primitive.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `sendMessage` — the `send_message` envelope shape (`SendMessagePayloadDto`, ack by `in_reply_to`) the peer copies.
- `scripts/e2e-emulator.sh` → pairing parse, `phone_pair_code`, the LIVE curated `TEST_TARGET`, `GRADLE_TEST_ARGS` — where the peer's token is minted and passed.
- `scripts/android-test-gate.py` → `main` (`minimum = 8` on live); `scripts/test_android_test_gate.py`, `scripts/test_e2e_emulator_gradle.py` — the gate floor and the invocation tests that must stay green.
- `../pyrycode/docs/protocol-mobile.md` § Static keys — mobile side (the daemon learns the device key per handshake, so any throwaway key works with a fresh token), § `queue_state` (fan-out to every interactive conn; multi-device `message_id` rule), `conversation_updated` (#2159 auto-naming from a first message), `turn_end` (`conversation_id`).
- `docs/e2e-interactive-stream.md` § Live mode, § Pre-ship gate — curated list and floor reasoning (#740, #847 kept the floor at 8; this ticket's AC raises it).

## Design source

**Figma:** N/A — test infrastructure only; no UI changes.

## Context

The daemon serves every paired device the same conversations. Nothing in the live gate proves that a turn started from a second device (the desktop, in practice) shows up on the phone. This ticket adds a bounded protocol peer — a second paired device on the test daemon — and one rung-3 scenario that uses it. The peer is shared infrastructure for later live scenarios (#673's remaining children: phone replies/queueing, offline reading and reconnect).

The peer lives in `app/src/androidTest` and is built from the app's own `OkHttpRelayTransport`, `NoiseSessionFactory`/`NoiseIkSession` and `NoiseSessionPump`. A host-side peer was rejected: Noise IK has no ready host implementation in the harness's Python, and the daemon's Go simulated phone lives under `internal/` and cannot be imported. Zero production files change.

## Design

### Peer: `e2e/SecondClientPeer.kt` (androidTest)

```kotlin
class SecondClientPeer(pairing: PairedServer) : AutoCloseable {
    suspend fun open(timeoutMs: Long)                           // connect → Up → start pump → Open
    suspend fun sendMessage(conversationId: String, text: String) // send_message, await ack; throws on error
    suspend fun awaitFrame(conversationId: String, type: String, timeoutMs: Long): Envelope
    override fun close()
}
```

- **Own device identity.** A throwaway X25519 keypair generated in memory (`Noise.createDH("25519").generateKeyPair()`), never the phone's Keystore key; a file-private `DeviceStaticKeyStore` hands it out. A file-private single-record `PairedServerStore` holds the peer's `PairedServer` (host A's `serverId`, relay URL and static key, the peer's own token); every mutating member errors — the factory calls only `load()`.
- **Connect.** `OkHttpRelayTransport(pairing, NoiseClientInfo("e2e-peer", …), defaultClient())`, `connect()`, await `TransportEvent.Up` (a `Down` fails fast with its code only), then `NoiseSessionPump(...).start()` and await `PumpState.Open`; `Closed` fails with the category-only cause.
- **Observe.** One collector coroutine on a peer-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)` drains `pump.inbound` into a `MutableStateFlow<List<Envelope>>`. `awaitFrame` waits until an envelope of `type` whose payload's `conversation_id` equals the id is present. Waits on the recorded list, so a frame that arrives before the wait begins is not lost.
- **Send.** `Envelope(id = next, type = "send_message", payload = SendMessagePayloadDto(conversationId, uuid, text))`; await an `ack` or `error` with `in_reply_to == id` in the recorded list. An `error` throws naming only its `code`.
- **Close.** `pump.close()` (closes session keys and transport), cancel the scope. Idempotent.
- Nothing logs the token, the key or any payload text.

### Scenario: `interactiveTurn_peerStartedTurn_continuesOnPhone` (InteractiveStreamE2ETest)

1. `awaitChannelList()`, `awaitConnected()`; snapshot the phone's conversation ids from host A's repository (the `RelayConnectionRegistry` read `assertHostHoldsConversation` uses).
2. `createChat()` → thread open; resolve the new conversation id as the one not in the snapshot.
3. Rename the chat to `e2e848-<millis>` with `renameOpenThread` **before** any message, so the daemon's first-message auto-naming (#2159) never fires and the row stays findable by a unique name.
4. Open the peer with the `peerToken` argument, `sendMessage(id, PING_PROMPT)`.
5. **AC2.** Peer `awaitFrame(id, "turn_end")` — the peer observes the conversation's frames (AC1). Phone: `awaitDisplayedPingReply`, then `onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(1)`.
6. **AC3.** Back to the list, open the chat row `hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(uniqueName)`, then wait for the reply and assert: exactly one ping reply bubble, and exactly one node carrying the exact `PING_PROMPT` text inside the thread's scrollable list (`hasAnyAncestor(hasScrollToNodeAction())`), which counts a delivered bubble and an inline queued row alike and excludes the top bar.
7. `finally`: `peer.close()`.

One real Claude turn.

### Harness: `scripts/e2e-emulator.sh`

- Rung 3 / LIVE only (not DETERMINISTIC): after host A's pairing, mint a third token `pyry pair -pyry-name=$PYRY_NAME --name=$PAIR_NAME-peer` to `pair-peer.out`, parse with a new `pair_token <pair-out>` function that prints exactly `PEER_TOKEN=<quoted>` (same payload-line detection as the first parse). Never logged.
- Pass `-Pandroid.testInstrumentationRunnerArguments.peerToken=…` in its own `if [ -n "${PEER_TOKEN:-}" ]` block **after** the two-host block, so the existing invocation tests' argument windows are unchanged.
- Append the new method to the LIVE curated `TEST_TARGET` (11 methods, 4 turns); refresh the adjacent comments and PASS line.

### Gate: `scripts/android-test-gate.py`

- `LIVE_MINIMUM = 11` module constant; the live branch uses it (AC4: the required executed count rises to the curated list's size).
- `test_android_test_gate.py`: the pass path pads the recorded eight-case fixture with synthetic curated-method cases up to `gate.LIVE_MINIMUM` (the recorded fixture itself stays byte-for-byte); a new case asserts a ten-test live report now fails the floor.
- `test_e2e_emulator_gradle.py`: a new case asserts `peerToken` is passed when `PEER_TOKEN` is set and absent otherwise; `test_e2e_emulator_two_host.py` (or a sibling) covers `pair_token` on a payload and on no payload.

## State + concurrency model

The peer's scope is test-owned and cancelled in `close()`; every suspend call runs under `runBlocking { withTimeout(...) }` in the test, the idiom the class already uses. The pump's own dispatcher is its default. No production state is touched.

## Error handling

Peer failures throw `AssertionError`/`IllegalStateException` with category-only messages (transport `Down` code, pump close category, server error `code`). A timeout surfaces as `TimeoutCancellationException` from `withTimeout`, the class's existing failure shape.

## Testing strategy

- `./gradlew compileDebugAndroidTestKotlin` proves the peer and scenario compile against the app classes.
- `python3 -m unittest` over the touched `scripts/test_*.py` proves the floor, the token parse and the argument wiring.
- The scenario itself spends a real Claude turn, so it is executed only by the dispatcher's post-verifier `python3 scripts/android-test-gate.py live` (`needs-real-claude`). No rung-4 twin: the daemon's scripted `fakeclaude` harness has no second-token seam today, and the ticket does not ask for one.

## Documentation handoff (pending — documentation stage)

`docs/e2e-interactive-stream.md`: describe the peer fixture (`SecondClientPeer`, the `peerToken` argument, throwaway key, one per scenario, closed in `finally`) and how a scenario uses it; add the scenario to the rung-3 list and § Live mode's curated list; update § Pre-ship gate's executed-test count (11, floor 11) and turn cost (four ping turns).

## Open questions

- Whether the phone folds a turn it did not start without duplicating the reply on reopen is exactly what the scenario measures; a red live run on AC2/AC3 is a product finding to file, not a harness fix (Scope Discipline).

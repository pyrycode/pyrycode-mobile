# #847 — e2e: two paired hosts with colliding conversation ids stay separate

One new rung-3 scenario on `InteractiveStreamE2ETest`, a second isolated test daemon in `scripts/e2e-emulator.sh`, and a graph-rebuild seam in the androidTest-only `E2eTestApplication`. No production source file changes.

## Files read

- `scripts/e2e-emulator.sh` → the `LIVE` / default relay-URL branch (`PHONE_RELAY_URL`, `DAEMON_RELAY_URL`), the DETERMINISTIC "pre-seed the scripted backend" step (per-instance `conversations.json` path vs the per-user `config.json`), step 3's daemon spawn, the pairing parse, `cleanup`, and the `LIVE` `TEST_TARGET` list. Every harness change lands here.
- `scripts/android-test-gate.py` → `main`: a live run derives `PYRY_NAME=PAIR_NAME=e2e-auto-<hex>` and floors the executed count at 8. The floor stays (#740 § Revisions); only the list grows.
- `scripts/test_e2e_emulator_gradle.py`, `scripts/test_e2e_emulator_cleanup.py` → both lift blocks of the script and run them under `set -u`. The cleanup block must tolerate an unset second-daemon pid, and the Gradle invocation block's output must stay byte-identical when no second host is configured.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `awaitChannelList`, `awaitConnected`, `interactiveTurn_renameConversation_relabelsTopBarAndListRow` (the rename drive reused verbatim), the companion's string constants. The scenario and its helpers land here.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` → the close / connect / await pattern (`supervisor.close()` then `coordinator.currentRepository.first { it == null }`; `connect()` then `first { it != null }`), used per host here. It also records why an idle `ConnectionState.Connected` after `close()` cannot prove a reconnect.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `onCreate`'s relay branch and `tappedRelayRepositoryModule`. The restart seam lands here because this class owns the module list.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `appModule`: the `DataStore<Preferences>` single has no `onClose`; `RelayConnectionRegistry` is `createdAtStart` with `onClose { dispose() }`; `LifecycleConnectionDriver` is `createdAtStart` and calls `ProcessLifecycleOwner.get().lifecycle.addObserver`, which must run on the main thread.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `connectionFor`, `reconcile` (the selection is the **last saved** host, so pairing host B makes B the selection until B is removed), `dispose`.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` → `PairedServerCollectionStore.remove` / `loadById`; `PairedServerEntry.displayName`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.SCANNER` composable (checks `CAMERA`, else launches the system request), `onPasteCode` → `Routes.PAIR_CODE`, and `PairCodePhase.Complete` → navigate to `CHANNEL_LIST` popping the graph.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt`, `PairCodeViewModel.kt`, `ScannerScreen.kt` → the "Host name" / "Pairing code" fields, "Pair", "Confirm pairing", the three paste links ("Trouble scanning? Paste the pairing code instead", "Paste the pairing code instead", "Paste code instead").
- `app/src/main/java/de/pyryco/mobile/data/network/PairingPayloadParser.kt` → `parsePairingPayload` accepts `ws` and `wss` relays, so a re-encoded payload works on the local-relay default rung 3 as well as on `LIVE`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `treeSection`: each section draws a `TreeHostRow` per host, the fold key is `(section, serverId)`, a collapsed host emits none of its rows, and a row's tap target carries the row's own `serverId`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `foldActionLabel` (`cd_tree_row_collapse` / `cd_tree_row_expand`), `TreeSectionHeader`'s `cd_tree_section_pair_host`.
- `../pyrycode/internal/conversations/conversation.go` → `Conversation`: `current_session_id` is `omitempty` (an unbound conversation is a legal state), `is_promoted` always serialized; `registry.go` → the `{"conversations":[…], "workspace_labels":{…}}` envelope. `cmd/pyry/main.go` → `sanitizeName` keeps `[A-Za-z0-9_.-]`, so `<name>-b` is its own instance directory.
- `docs/knowledge/features/navigation.md` § the gate note → "two-host navigation/reconnect … remain #673"; the two-host archive/restore case is #676 and stays out of scope.

## Design source

N/A — test and harness only; no surface changes.

## Context

The phone keys rows, threads and its cache by `(serverId, conversationId)` (#731, #795–#798), but no live run has paired a second host, and daemon-minted ids never collide by chance. This ticket seeds the collision and drives it through pairing, navigation, rename, per-host link cycling and a graph rebuild.

## Design

### Harness (`scripts/e2e-emulator.sh`), non-`DETERMINISTIC` modes only

1. **Guard, before anything is spawned.** The two-host seed writes under the real HOME, so it refuses a `PYRY_NAME` that does not start with `e2e-` or holds a character outside `[A-Za-z0-9_.-]`. All three defaults (`e2e-live`, `e2e-emulator`, `e2e-auto-<hex>`) pass. `PYRY_NAME_B` is `${PYRY_NAME}-b`; `PAIR_NAME_B` is `${PAIR_NAME}-b`.
2. **Seed (new step before the daemons).** One run-unique conversation id (`python3 uuid4`) and two names, `e2e847-a-<epoch>` and `e2e847-b-<epoch>`. `seed_collision_conversation <instance-dir> <id> <name> <cwd>` merges one promoted, unbound row (no `current_session_id`) into `<instance-dir>/conversations.json`. It keeps every other row and top-level key, replaces a row with the same id, and writes `0600` through a temp file plus `os.replace` inside a `0700` directory. Merge rather than overwrite, because `e2e-live` / `e2e-emulator` are reused across manual runs.
3. **Second daemon.** Spawned beside the first with `-pyry-name="${PYRY_NAME_B}"`, the same relay URL and flags as that mode's first daemon, its own `daemon-b.log`, and `DAEMON_B_PID` killed in `cleanup` through `${DAEMON_B_PID:-}`.
4. **Second pairing.** `pyry pair -pyry-name="${PYRY_NAME_B}"` into `pair-b.out`. `phone_pair_code <pair-out> <phone-relay>` finds the same payload line the first parse accepts, replaces only `relay` with `PHONE_RELAY_URL`, and prints `SERVER_ID_B=…` and `PAIR_CODE_B=…` (base64url, no padding) as `shlex`-quoted assignments.
5. **Instrumentation arguments.** Appended to `GRADLE_TEST_ARGS` only when `SERVER_ID_B` is set: `serverIdB`, `pairCodeB`, `collisionConversationId`, `collisionNameA`, `collisionNameB`. The first host's four arguments are unchanged.
6. **List.** `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` joins the `LIVE` `TEST_TARGET` list (nine → ten, still 3 turns), with the adjacent comments and PASS line updated. Default rung 3 runs the whole class and so runs it too.

### `E2eTestApplication`

`fun rebuildGraph()` — the restart an instrumented test can perform. On the main thread it takes the running `DataStore<Preferences>` and the `LifecycleConnectionDriver`, removes the driver from `ProcessLifecycleOwner`, `stopKoin()` (whose `onClose` disposes the registry and the host sources), then `startKoin` with `appModule`, `tappedRelayRepositoryModule()` and a module declaring the carried `DataStore`, with `allowOverride(true)` stated explicitly. The pairing is not re-saved: both hosts are already in the Keystore-backed store, which is the state under test.

### Scenario `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`

Every step is a presence or absence check on an exact, run-unique name. There is no count, order or geometry check.

1. List arrival, `awaitConnected`, host A's seeded row (name A) displayed.
2. Grant `CAMERA` through `uiAutomation.grantRuntimePermission`; tap the Channels header's "Pair another host, Channels"; tap the paste link (substring `code instead`, matched by all three scanner states); enter `HOST_B_NAME` and `pairCodeB` in the fields labelled "Host name" and "Pairing code"; "Pair"; "Confirm pairing"; list arrival; name B's row displayed.
3. `assertEachUnderOwnHost(nameA, nameB)`: collapse host A's Channels row by its fold description and require A's name absent and B's present, then re-expand. Do the same the other way round for host B. Host labels come from the store (`displayName`, else `R.string.unnamed_host`).
4. `assertRowOpensOwnThread(name, other)` for each: tap the row named `name`; at the thread (send button) the top bar shows `name` and `other` is absent; Back; list arrival.
5. Rename A from its thread to `e2e847-renamed-<millis>` (#537's drive). The top bar re-labels; back on the list the new name is present, name A is absent and name B is present. Rerun steps 3 and 4 with the new name.
6. For each host id: close that host's supervisor, await its coordinator's repository `null`; `connect()`, await non-`null`. Then rerun steps 3 and 4.
7. Restart: move the rule's activity to `DESTROYED`, `rebuildGraph()`, `ActivityScenario.launch(MainActivity)`, list arrival, `awaitConnected`, both rows displayed. Then rerun steps 3 and 4.
8. `finally`: `PairedServerCollectionStore.remove(serverIdB)` on whatever graph is current, and close the relaunched scenario. Removal returns the registry selection to host A for the scenarios that follow.

## State + concurrency model

- All waits are `composeTestRule.waitUntil` or `runBlocking { withTimeout { flow.first { … } } }` on the instrumentation thread, as in both existing e2e classes. The graph swap runs in `runOnMainSync`.
- Between `DESTROYED` and relaunch no activity is resumed, so the old ViewModels are cleared, not retained. `recreate()` would retain them, and they hold the disposed graph. `ProcessLifecycleOwner` may or may not dispatch `ON_STOP` in that gap. Either way the new driver's `addObserver` or the relaunch's `ON_START` calls `connect()` on the new registry.
- `AndroidComposeTestRule` locates compose roots process-wide, so the relaunched activity is queried through the same rule.

## Error handling

- A missing two-host argument fails the scenario at once, through `requireNotNull` with a message naming the argument and the script.
- The harness `die`s before spawning on a refused name, a seed write failure, a `pyry pair` failure for B, or a payload it cannot parse. Each message points at the private log and never echoes payload content.
- Host B removal sits in `finally`, so a red scenario cannot leave the other scenarios' selection on host B.

## Testing strategy

- **The scenario is the proof** of all four criteria. It is compiled by `./gradlew compileDebugAndroidTestKotlin`, and the live suite runs as the dispatcher's post-verifier gate (`needs-real-claude`). It spends no Claude turn: pairing, navigation, rename and link cycling are daemon round-trips.
- **Harness unit tests** under `scripts/`, in the existing lift-a-block style: `seed_collision_conversation` keeps other rows and keys, replaces a same-id row, and leaves the file `0600`; `phone_pair_code` swaps only `relay`, emits unpadded base64url and rejects output with no payload; the Gradle invocation adds the five arguments only when `SERVER_ID_B` is set, and is otherwise byte-identical; `cleanup` passes with `DAEMON_B_PID` unset.
- No focused managed-device run: the scenario needs two live daemons and a relay, which only the harness provides.

## Open questions

- Does the real daemon serve a thread and a rename for an **unbound** seeded channel (no `current_session_id`)? `omitempty` and the registry's own "freshly created conversation" wording say yes. If the live run shows otherwise, bind a run-unique session id and record it here as a revision.
- The pairing-name field and the host-B label: `HOST_B_NAME` shares no substring with the conversation names, so exact matching cannot confuse a host row with a conversation row.

## Documentation handoff

Pending for the documentation stage. In `docs/e2e-interactive-stream.md`: add this scenario to the rung-3 list and to § Live mode's curated list; update § Pre-ship gate's executed-test count (nine → ten; the turn cost is unchanged because the scenario spends no Claude turn); replace the sentence that points two-host navigation at #673 with a pointer to #847; and narrow the § Follow-ups #673 bullet accordingly.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The only new untrusted input into the app is `pairCodeB`, which enters through the production `parsePairingPayload` behind the fingerprint confirm, the same boundary a pasted code crosses. The harness-side parse accepts only a line that decodes to a JSON object carrying `server`, `token` and `server_static_pubkey`, and rewrites only `relay`.
- [Tokens] SHOULD FIX, addressed in the design. `PAIR_CODE_B` carries the pairing token, and it now also travels as an instrumentation argument, exactly as host A's `token` already does. `phone_pair_code` must print only the two assignments, the harness must never `log` either value (only `serverIdB`, as it logs A's), and `pair-b.out` stays in the private `WORK_DIR`. The Kotlin side never logs the argument.
- [Tokens / lifecycle] No findings. Host B's credentials leave the phone in `finally` through `PairedServerCollectionStore.remove`, whose bound eviction also drops B's cache and drafts. The daemon-side device record lives in the per-run `-b` instance directory, the same lifetime as host A's.
- [File / storage] Resolved by revision: a MUST FIX on the first pass, fixed by adding Design step 1, then re-reviewed. The seed writes under the **real** HOME. `PYRY_NAME` is validated before any write (prefix `e2e-`, sanitised charset, so no `/` or `..`), and the path is built only from it. That validation plus merge-not-overwrite is the deterministic guard that no production instance's `conversations.json` is written or clobbered. Writes are `0600` through `os.replace` in a `0700` directory; `config.json` (per-user) is never touched.
- [Inter-process] No findings. There is no new exported component, intent or deep link. The `CAMERA` grant goes to the app under test on the managed emulator only.
- [Crypto] No findings. The id comes from `uuid4` (it needs to be unique, not secret), and no crypto is added or touched.
- [Network] No findings. Host B uses the mode's own relay URL: `wss://` on `LIVE`, with the insecure flag never set on that path, and loopback `ws://` only on default rung 3, as for host A.
- [Logs] No findings. Only names, the conversation id and server ids reach stdout; die messages name log paths, never content.
- [Concurrency] SHOULD FIX, addressed in the design. The old graph's lifecycle observer would otherwise keep calling a disposed registry, so `rebuildGraph` removes it. A second `DataStore` over `app_prefs` would throw, so the running instance is carried. The swap runs on the main thread because `addObserver` requires it.
- [Threat model] OUT OF SCOPE. The two-host archive/restore and unpair scenarios remain #676. Phone-reply continuity remains #673.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

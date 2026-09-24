# #687 — live proof that the permission control reflects the running child

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → the rung-3 class the new method joins. #545's `hostRepository`, `freshSettings`, `awaitFooter`, `pickFooterOption`, `openChatRow`, `leaveThread`, `sendFromPhone`; #847's `pairHostByCode` (the paste-a-code pairing of a second host) and its `finally` removal from `PairedServerCollectionStore`; #849's use of `SecondClientPeer.awaitPermissionModal` / `allowOnce`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `SecondClientPeer(PairedServer)`: builds its own device from a token, server id, relay URL and server static key, so it can dial any paired host, not only host A.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onPermissionModeSelected`, `sendPermissionMode` (sets `pendingPermission` synchronously, before the write), `settlePermission` (re-reads until the requested mode or `PERMISSION_SETTLE_WINDOW_MS`), `finishPermissionWrite`. The constructor takes no preference store other than `rememberedEffort`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `PermissionModeOption` (labels), `permissionModeLabel` (the control is drawn only when it is non-null), the permission `FooterButton` with click label `thread_footer_change_permission` and `pending = pendingPermission != null`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` → `PermissionModalOverlay`: title, prompt and context render as plain text in a `MobileGateModal` whose footer carries only Cancel (`modal_cancel`).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the production `ThreadViewModel` wiring: `rememberedEffort = preferences.asRememberedEffortStore()` is the only preference input.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeSessionFacts`, `SessionFacts` (claude's claim, never the confirmed reading).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelPermissionTest.kt` → the #650 cases and the `ScriptedRepo` fixture the two new cases extend.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` → how a JVM test builds `AppPreferences` over a temp-file `DataStore`.
- `scripts/e2e-emulator.sh` → the #847 second daemon, `phone_pair_code`, `pair_token`, the #850 revision lines, `cleanup`, the `LIVE` curated list. `scripts/android-test-gate.py` → `LIVE_MINIMUM`, the live gate removing `ANTHROPIC_API_KEY`.
- `../pyrycode/internal/e2e/realclaude/` → `spawnPermissionDaemon` (operator-bypass args, `-pyry-idle-timeout=0`), `writeStdioPermissionPromptConfig`, `WithWorktreeAuthenticated` (isolated HOME, OAuth token plus a copy of `~/.claude.json`), `TestInteractiveStreamSessionSettingsReportsConfirmedPermissionMode` (the flow this mirrors), `writeHandoffShapedNote`.
- `../pyrycode/internal/sessions/pool.go` → the minted-session construction: "a minted session inherits the same operator pass-through the bootstrap has". This decides the conversation route below.
- `../pyrycode/docs/protocol-mobile.md` § Session settings → `yolo: true` is the only spelling of bypass on the write half; a reading with `permission_mode ""` and `yolo: false` is unknown, not enforced; posture writes are refused on a dormant session.
- `../pyrycode-mobile-agents/dispatcher/README.md` § Billing → the dispatcher's spawn environment keeps `CLAUDE_CODE_OAUTH_TOKEN`.

## Design source

N/A — test-only ticket: one rung-3 scenario, two JVM cases and harness wiring. No UI change.

## Context

pyrycode#2510 (`475c406a`) makes `session_settings` report the mode Claude last confirmed for the current child. #650 labels the footer permission control from that reading alone. No live test proves the phone against a real operator-bypass child, or that the phone does not take a no-op `default` acknowledgement for the applied mode.

**Credential route.** The dedicated daemon runs under an isolated HOME and authenticates Claude with `CLAUDE_CODE_OAUTH_TOKEN`, inherited from the live gate's environment. The dispatcher's spawn environment keeps it, and `android-test-gate.py` removes only `ANTHROPIC_API_KEY`. A manual run may use `ANTHROPIC_API_KEY` instead; the OAuth route is the gate's. As `WithWorktreeAuthenticated` does, the OAuth route copies the operator's `~/.claude.json` into the isolated HOME (read-only on the source, mode 0600 on the copy). No credential is written to disk.

**Conversation route: `createDiscussion` on the dedicated host.** The pool builds a minted session from the same template as the bootstrap, so its spawn base carries the operator pass-through and its child launches in bypass. Binding to the bootstrap session would need a seeded registry and is not needed. The PR records this route. If the live gate shows otherwise, the first-reading assertion fails with the reported mode.

**The dedicated instance fails the method, not the run.** A missing credential, an unverifiable daemon revision or a missing fixture capability must fail this one method with a message naming it. The script therefore never `die`s over them. It records the first unmet prerequisite and passes it to the test as an argument. The other nineteen live methods still run.

## Design

### Script: `scripts/e2e-emulator.sh` (rung 3 and LIVE, not DETERMINISTIC)

A new section after the #847 second daemon, "2e. the dedicated operator-bypass daemon (#687)":

1. **Revisions.** Beside the #850 lines, log `claude revision: <claude --version or unavailable>`.
2. **Prerequisites**, in order; the first unmet one sets `BYPASS_UNMET` to a sentence naming it and skips the rest of the section:
   - a credential: `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` non-empty; on the OAuth route, `~/.claude.json` readable;
   - the daemon revision: `DAEMON_REVISION` non-empty, `PYRYCODE_SRC` set, and `git -C "$PYRYCODE_SRC" merge-base --is-ancestor 475c406a "$DAEMON_REVISION"` true (unknown revision and "lacks 475c406a" are different messages);
   - fixture capabilities: `claude` on PATH, the instance name passes `two_host_name_ok`, the isolated HOME and config write succeed, the daemon answers `pyry status` within 15 s, and both `pyry pair` calls parse.
3. **Fixture.** `BYPASS_HOME=$(mktemp -d /tmp/pyry-e2e-byp.XXXXXX)` (short, for the socket path). Under it: `.pyry/config.json` = `{"interactive_runner":"stream-json","stdio_permission_prompt":true}` (umask 077), the `~/.claude.json` copy, and the workdir `work/`. The operator's `~/.pyry/config.json` is never opened for writing.
4. **Daemon.** `env HOME=$BYPASS_HOME PYRY_MOBILE_V2=1 PYRY_RELAY_URL=$DAEMON_RELAY_URL [PYRY_ALLOW_INSECURE_RELAY=1 off LIVE] $PYRY_BIN -pyry-name=${PYRY_NAME}-bypass -pyry-workdir=$BYPASS_HOME/work -pyry-idle-timeout=0 -- --dangerously-skip-permissions --permission-prompt-tool stdio`. Log to `$WORK_DIR/daemon-bypass.log`. The primary daemons are untouched.
5. **Pairing.** Under `HOME=$BYPASS_HOME`, mint a phone pairing (no `--allow-remote-permissions`) through `phone_pair_code`, and a peer pairing `--allow-remote-permissions` through `pair_token`. Both helpers gain an optional variable-name argument, so existing calls print exactly what they print today. The peer also needs the server static key, which `pair_token` prints when asked.
6. **Token file.** A fresh 32-hex token from `secrets.token_hex`, written 0600 to `$BYPASS_HOME/outside/e2e687-<uuid>.txt`: outside the workdir, in a 0700 directory.
7. **Arguments.** Either `bypassUnmet=<sentence>`, or `bypassServerId`, `bypassPairCode`, `bypassPeerToken`, `bypassServerStaticPublicKey`, `bypassTokenFile`, `bypassToken`. None of the pairing values is logged.
8. **Cleanup.** `cleanup` kills `BYPASS_PID`, deletes the token file, and removes `BYPASS_HOME` whatever the exit code. The HOME holds a copy of `~/.claude.json`, so it is never kept for debugging. The daemon log stays in `WORK_DIR` under the existing rule.
9. **Gate.** Append the method to the `LIVE` list. Update the counts in the comments to 20 methods and 16 turns, and extend the PASS line.

### Script: `scripts/android-test-gate.py`

`LIVE_MINIMUM` goes from 19 to 20.

### Live method: `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`

Two real turns. Steps:

0. **Prerequisites.** If `bypassUnmet` is present, fail with it. If any other bypass argument is missing, fail naming it.
1. **Pair.** Pair the dedicated host via `pairHostByCode(code, BYPASS_HOST_NAME)`, then create and rename a chat on it through `hostRepository(bypassServerId)`. Open the chat and send `PING_PROMPT`, which uses no tools. Await the reply.
2. **AC 1.** `leaveThread`, reopen. A fresh reading reports `bypassPermissions`. The settled permission control reads Bypass approvals.
3. **AC 2, acknowledgement.** Pick Manual approval. Wait until the control is pending; this proves the tap sent a write. Then wait until it settles on Bypass approvals again. The time from pending to settled must be at least `PERMISSION_SETTLE_WINDOW_MS - 5 s`. A refusal clears pending at once, so this shows the write was acknowledged and the settle loop ran to expiry without ever reading `default`. A fresh reading still reports `bypassPermissions`.
4. **AC 2, choices.** For Plan, Bypass approvals, then Manual approval: pick it, poll fresh readings until one reports the mode, then wait for the settled footer label. Each fresh reading must carry the conversation's first `sessionId`. The same session and no user turn in between is the phone-side evidence of "same child". The daemon's own gate (pyrycode#2510) checks the child pid.
5. **AC 3.** Send a prompt that asks for one Read of the token file's absolute path and nothing else. The prompt contains the path, not the token. Assert that the prompt does not contain the token.
   - The phone's modal overlay shows a node whose text contains the file's base name or the word `Read`. The matcher is scoped to the dialog by an ancestor that holds the modal's Cancel control, so the phone's own message bubble cannot match.
   - The peer, paired `--allow-remote-permissions` to the dedicated host, waits for the permission modal and calls `allowOnce`.
   - An assistant bubble containing the token renders.
6. **finally.** Close the peer and remove the dedicated host from `PairedServerCollectionStore`. The chat lives only on the dedicated instance, which the script removes.

Helper changes, all private to the class and default-preserving:

- `hostRepository(serverId = ARG_SERVER_ID's value)` and `freshSettings(conversationId, serverId = …)`;
- `pairHostByCode(pairCode, hostName = HOST_B_NAME)`;
- a new `bypassArg(key)` that fails naming the key and the script;
- a new `awaitPermissionReading(serverId, conversationId, mode, sessionId)` that polls `freshSettings` until the mode appears, and fails with the last reading;
- constants for the argument keys, the chat prefix `e2e687-`, the host name, and the Read prompt template.

The permission option labels come from `PermissionModeOption.<entry>.label`; `androidTest` sees `internal`.

### JVM: `ThreadViewModelPermissionTest`, two cases

- **(a) `aNoOpDefaultAck_leavesTheConfirmedBypass_withNoPendingMark`.** Confirmed `bypassPermissions`/`yolo = true`, with every refresh answering the same. Choosing `default` sends exactly one `permissionMode = "default"` write. The ack is logged. After the settle window, the label is Bypass approvals, `pendingPermission` is null, `permissionMode` is still `bypassPermissions`, and the settle logged `expired`.
- **(b) `anUnknownReading_showsNoControl_despiteAFactsClaimAndDefaultYolo`.** An `AppPreferences` over a temp-file `DataStore` has `setDefaultYolo(true)` and feeds `rememberedEffort = asRememberedEffortStore()`, which is the production wiring. `ScriptedRepo` emits `SessionFacts(permissionMode = "default")`. The reading has a non-empty session id, `permission_mode ""` and `yolo = false`. `permissionModeLabel` is null, so no control is drawn, and choosing any mode sends nothing.

`ScriptedRepo` gains a `facts` flow behind `observeSessionFacts`; `collectedVm` gains an optional `rememberedEffort` parameter.

## State + concurrency model

Test-side only. Repository reads run in `runBlocking { withTimeout(...) }` from the instrumentation thread, as #545's helpers do. Every fresh reading is a new cold collection that ends at its first value. The peer is closed in `finally`. Script processes are owned by the EXIT trap.

## Error handling

Each unmet prerequisite is a named failure of the one method, never a skip and never a script abort. Timeouts in `awaitFooter` and `awaitPermissionReading` report what was last shown or read. The host removal in `finally` runs in `runBlocking` on the current graph, as #847 does.

## Testing strategy

- **JVM:** the two new cases, red first: (a) fails while the fixture cannot answer refreshes with bypass, and (b) fails while `ScriptedRepo` has no facts flow. Run with `testDebugUnitTest --tests ThreadViewModelPermissionTest`.
- **Live (rung 3):** the new method is compiled (`compileDebugAndroidTestKotlin`) but not run here. It needs real Claude, and the dispatcher's post-verifier live gate runs it. `bash -n` checks the script. The script's new Python helpers are exercised against sample `pyry pair` output locally.
- **Existing tests for the ticket's other deterministic cases:**
  - pending: `label_staysOnTheConfirmedReading_whilePendingAndAfterTheAck`;
  - acknowledgement: that test's ack half and `settle_rereadsAtOnce_thenEvery500ms_andStopsOnTheRequestedMode`;
  - refusal: `refusalOrSendFailure_rereadsOnce_andSurfacesTheFailureSignal`;
  - dormant session (`""` reading, hidden control): `label_followsTheReading_andIsHiddenWithoutAConfirmation` and `nothingIsSent_withoutASessionOrWithoutAConfirmedMode`;
  - reconnect: `aNullReading_hidesTheButton_andCancelsTheRetries` and `aLateAckFromThePreviousContext_startsNoSettle`;
  - session replacement: `aSessionReset_hidesTheOldMode_untilTheNewSessionReports_butKeepsModelAndEffort` and `aReadingForAnotherSession_cancelsTheRetries`.

## Documentation handoff (pending — documentation stage)

- `docs/e2e-interactive-stream.md`: describe the new method, the dedicated operator-bypass daemon and its prerequisites (the `CLAUDE_CODE_OAUTH_TOKEN` credential, a daemon revision containing `475c406a` checked through `PYRYCODE_SRC`), and the new counts: 20 live methods, 16 real turns.
- `docs/knowledge/features/thread-composer-footer.md`, the "Live coverage" bullet, and `docs/knowledge/features/thread-composer-footer-testing.md`, the line "No rung-3 real-daemon scenario landed in #650": name `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` instead of #687.

## Open questions

- Does the phone's modal title or prompt carry the file path or the tool name? pyrycode's #2474 capture shows the title has characters outside a tight charset but not which ones. The matcher accepts either the base name or `Read`. Only the live gate can confirm.
- Does the first reading after reopening already report `bypassPermissions`? The method polls briefly (`awaitPermissionReading`) rather than taking one read, because the confirmation comes from the child's `system/init`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings in the app: no production code changes. In the test, daemon-authored text (the modal title and prompt, and Claude's reply) is only substring-matched, never logged, and never put in a failure message. `awaitFooter` reports only footer labels, which `permissionModeLabel` has already made inert. In the script, `claude --version` output is untrusted text going into the log. SHOULD FIX: clamp it to `[A-Za-z0-9 ._()+-]` and 80 characters before logging, or log `unavailable`.
- [Tokens] No findings. `CLAUDE_CODE_OAUTH_TOKEN` reaches the dedicated daemon only by environment inheritance. It is never written to disk, never logged, and never an instrumentation argument. The dedicated host's pairing codes and peer token follow the existing `PAIR_CODE_B` / `PEER_TOKEN` handling: they are passed as arguments and never logged. Their identity store lives in `BYPASS_HOME`, so deleting that directory revokes them. The witness token comes from `secrets.token_hex(16)` (CSPRNG). It authorizes nothing, so it can be an argument; it is still not logged.
- [File / storage] SHOULD FIX in Phase B:
  - `cleanup`'s `rm -rf` of the bypass HOME runs only when the variable is non-empty and starts with `/tmp/pyry-e2e-byp.`.
  - The config, the `~/.claude.json` copy and the token file are written under `umask 077`, inside the `mktemp -d` (0700) directory. The directory is never `$HOME`, so the operator's `~/.pyry/config.json` cannot be the target.
  - Every path is built by the script from `mktemp` and a UUID. No external input reaches a path.
- [File / storage] OUT OF SCOPE: a SIGKILLed run skips the EXIT trap and leaves the 0700 temp HOME, with its `~/.claude.json` copy, in `/tmp`. The existing isolated-HOME paths have the same limit. No ticket; it is named here only.
- [Android surface] No findings. No manifest, intent or deep-link change. The dedicated host is paired through the existing paste-a-code flow and removed from `PairedServerCollectionStore` in `finally`.
- [Crypto] No findings. The Noise path is unchanged; the one new random value uses `secrets`.
- [Network & I/O] No findings. On LIVE the dedicated daemon dials the production relay without `PYRY_ALLOW_INSECURE_RELAY`, as the primary daemons do. Its operator-bypass child is reachable only by the two devices this run paired to it. Its workdir is an isolated temp directory, and the only prompts it receives are the test's fixed ping and Read prompts. The instance lives for the whole live run. OUT OF SCOPE: it could be started only for this method, but the harness boots every daemon before the single Gradle invocation. Nothing picks that up.
- [Logs] SHOULD FIX in Phase B: every `BYPASS_UNMET` sentence is static text plus at most the daemon revision hash. The script never uses `set -x`. The dedicated daemon's log stays in `WORK_DIR` under the existing kept-on-failure rule.
- [Concurrency] No findings. `BYPASS_PID` is recorded immediately after the spawn and killed by `cleanup`. A prerequisite that fails after the spawn kills the daemon on the spot before recording the failure. The test closes the peer in `finally`.
- [Threat model] OUT OF SCOPE: giving the phone itself `--allow-remote-permissions` belongs to #966. The phone stays unprivileged here, and only the peer answers.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

- **2026-09-24, Phase B: the unmet prerequisite travels as a static code.** The plan passed `bypassUnmet` as a sentence. An instrumentation argument goes through `adb shell am instrument -e`, where spaces are not safe, so the script passes a code such as `no_credential` or `revision_lacks_475c406a`. It logs the full sentence on the host. The test maps the code to the same meaning in `BYPASS_UNMET_REASONS` and fails with it. No design change otherwise.
- **2026-09-24, Phase B: section placement.** The dedicated daemon starts in a new section "4a", after the primary pairings, rather than "2e". It reuses `DAEMON_REVISION` and `PHONE_RELAY_URL`, which are set by then. Every step lives in one function, `start_bypass_daemon`, so an unmet prerequisite can stop that function without stopping the script under `set -e`.
- **Local smoke run.** Outside the plan: `start_bypass_daemon` was run against the installed `pyry`, which contains `475c406a`, with a dummy relay URL. It started the daemon with the operator-bypass argv, answered `pyry status`, minted both pairings and wrote the 0600 files in a 0700 HOME. The HOME was removed afterwards. Each unmet branch (`no_credential`, `revision_unavailable`, `revision_unverifiable`, `revision_lacks_475c406a`, `claude_missing`) was also exercised. The first open question (the modal's wording) and the conversation route still need the live gate.

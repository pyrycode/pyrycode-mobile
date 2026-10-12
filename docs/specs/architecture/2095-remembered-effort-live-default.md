# Remembered effort live default (#2095)

## Files read

- `scripts/e2e-emulator.sh`: `DAEMON_COMMAND` lets real primary children inherit operator Claude settings; scripted children already have an isolated HOME.
- `scripts/test_e2e_emulator_gradle.py`: executes extracted shell construction against controlled executables, without a daemon or credentials.
- `InteractiveStreamE2ETest.kt`: `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel`, `assertRecalledThenApplied`, `freshSettings`; the latter requests a new correlated non-held reading.
- `SessionSettingsCommands.kt`: `readSessionSettings` correlates each fresh request; `observeSessionSettings` first emits a held reading that the harness excludes.
- `SessionSettingsPayloads.kt`: `readEffectiveEffort` preserves omitted/null/string meanings.
- `RunConfigurationWait.kt`: `awaitRunConfiguration` and its persistence confirmation remain required.
- `docs/knowledge/features/thread-composer-footer-effort-recall.md`: acknowledgement/persistence and first-send barriers are independent of applied facts.
- `docs/e2e-interactive-stream.md`: Model and effort settings round trip requires two first turns and empty saved models.
- Daemon `cmd/pyry/session_router.go`: `resolveBoundEffectiveEffort` projects the exact child's nullable `QueryAppliedSettings` result without a saved-effort fallback.
- Daemon `internal/relay/v2session_settings.go`: `handleRequestSessionSettings` encodes confirmed null separately from unavailable.
- Daemon `internal/streamsup/runner_control.go`: `QueryAppliedSettings` owns request and child correlation.
- Daemon `internal/sessions/session.go`: `composeSpawnArgs` preserves operator launch model and appends per-session settings afterward.

## Context and demonstrated cause

The retained #2088 prefix used Mobile `b44067bbd991b7d68e0418f88cc2a9e721671c61`, daemon `8091a4730d42f43029eb0b955442dc300106030f`, Claude 2.1.280 and `pixel2Api33Atd`: 59 executed, 57 passed, 2 failed, 0 skipped. The exact remembered-effort method failed. The relevant Mobile scenario/decoder and daemon resolver are unchanged at this branch's baseline `cfc5911723bad305dcf2486283521915dce91b84`.

The diagnosis adds the surviving conversation history and Claude transcript to the originally retained logs. The first chat's `model_list` default row resolves to `claude-sonnet-5` and offers low/medium/high/xhigh/max, while its Haiku row offers no effort levels. Its `model_announced` and assistant transcript both identify `claude-haiku-4-5-20251001` as the actual first-turn model. The launch had `--effort low` and no model flag. Thus the harness selected Low from the recommended default row while letting the ambient launch default select a model with no effort support. `NotReported` is the decoded confirmed-null child fact, not a missing key or an optimistic saved choice.

Phone stages finish recall/persistence, then `ChatFirstReply` at 00:02:34.815 and fail `ChatAppliedReading` at 00:02:34.848. Phone turn-end reread occurred earlier at 00:02:34.609. Daemon history records a successful first `turn_end` at 21:02:36.227881Z, before cleanup restarts the child. Phone and host clocks differ; use ordering within each trace, not absolute cross-clock subtraction. The correlated settings query and non-held filter exclude the suggested stale-cache explanation. Waiting longer or for another turn cannot make Haiku's null an applied Low.

Sanitized evidence and source hashes will be retained under `app/src/test/resources/e2e/2095/`; raw inputs remain private at the paths listed there. No production defect or daemon repair is established. No ADR is needed.

Overlaps: #1766 changes live selectors; #2088 and #2090 change evidence cleanup in `e2e-emulator.sh`. Their blocks are independent of primary launch construction.

## Change

Pin the primary real-Claude test daemon's inherited launch model to `sonnet`, matching the effort-capable recommended row observed in this installation. Add the passthrough `--model` argument on both real launch paths (local relay and LIVE). Keep scripted launch arguments unchanged. This is launch configuration, never a saved Mobile model or a replacement applied fact. Explicit per-session model arguments remain later in daemon composition and override this base. Do not edit the scenario, wait helper, decoder, deadlines, persistence, graph restart, fresh fixtures, first-turn/footer checks, explicit-choice protection or cleanup. The launch regression executes the actual shell construction with an inherited Haiku fixture: before the repair the constructed real launch has no model, after it names Sonnet. Cover both real modes and the unchanged scripted lane.

## Testing strategy

Run the new shell construction regressions red then green, adjacent script command/lifecycle tests, shell syntax, focused existing settings decoder tests, lint, assembleDebug and forced Spotless. No new device-only test is needed: the existing remembered-effort method requires real graph/storage/network execution and remains the live proof. The ticket assigns live execution to the dispatcher; no focused live retry substitutes for that proof. PR `Live tests` is `all` because the primary launch is shared.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, Model and effort settings round trip / remembered effort acknowledgement barrier: explain the real primary launch default and retain the fresh dispatcher full-live proof. Record Mobile/daemon revisions, Claude version, XML/logcat paths and executed/failed/skipped counts, explicitly confirming the remembered-effort method ran and passed. The archived failure is diagnosis evidence, not a passing gate.

## Security review

**Verdict:** PASS

- Trust boundaries: `readEffectiveEffort` and daemon private-query validation remain unchanged; a fixed launch model cannot turn saved effort into an applied fact.
- Tokens/secrets: no login lookup, authentication changes or credential copies; controlled launch tests use a clean fixture environment.
- Files/storage: no persistent operator settings edits; retain only selected static model/menu/turn metadata and source hashes, never raw prompts, replies, paths from payloads or private settings.
- Android attack surface: no component, intent, permission, accessibility or UI change.
- Cryptography: Noise/Keystore and keys are unchanged.
- Network/I/O: relay transport, deadlines and query correlation remain unchanged; deterministic tests launch no daemon or Claude process.
- Errors/logs: sanitized evidence omits decrypted text, tokens and keys. Test assertions retain static configuration facts only.
- Concurrency: no new job or lifecycle state; existing first-send/persistence barriers and teardown remain intact.
- Threat model: relay delay/drop and hostile frames retain existing bounded decode/query behaviour; disk token theft and UI leakage gain no new surface because this change adds neither secret storage nor UI.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12

## Revisions

- 2026-10-12: Added the exact daemon `decodeAppliedSettings` source to the diagnosis: only an explicit raw null returns a successful nil effort; missing/invalid fields are unavailable. The private response itself was not retained, so its null is established through the exact validated projection/decoder and the phone assertion, while the actual unsupported model is independently retained in history and the Claude transcript. The historical origin of the ambient Haiku selection is not retained and is not needed to explain the menu-versus-launch mismatch.
- 2026-10-12: Live evidence return is explicit. Before documentation/merge, the dispatcher must run a fresh full live gate (`Live tests: all`). On the artifact return, the builder will commit `app/src/test/resources/e2e/2095/live-result.json` containing sanitized revision/version metadata, full counts, target-method result and durable XML/logcat references. Keep `needs-live-artifacts` until that record is committed; no fresh live pass is claimed now.

## Fresh combined live evidence, 2026-10-12

The operator ran the unchanged managed full live gate while both Mobile dispatchers
remained paused. All 65 curated tests executed and passed, with zero failures, errors,
skips or retries. The remembered-effort method and the other two effort methods passed.
`app/src/test/resources/e2e/2095/live-result.json` records the exact revisions, Claude
version, counted result and private XML/logcat references with hashes.

The combined candidate includes this launch repair, reply identity and reconnect
repairs, the Send now viewport repair and an experimental native Copy action. This
is complete integration evidence, not an isolated branch run or proof that the
historical Copy flake is repaired. HP and wider reliability acceptance remain open.

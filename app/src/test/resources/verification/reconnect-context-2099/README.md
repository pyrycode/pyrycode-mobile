# Reopened-context reconnect investigation (#2099)

This is a diagnostic checkpoint. **The reported timeout's cause and a behavioral repair remain unproved.** These captures must not be described as full-live acceptance.

## Counted captures

| Capture | Executed | Passed | Failed | Skipped | Target method |
| --- | ---: | ---: | ---: | ---: | --- |
| Unchanged baseline, isolated target | 1 | 1 | 0 | 0 | Passed |
| Instrumented historical 13-method prefix | 13 | 11 | 2 | 0 | Passed |
| Registration/unpair and graph-rebuild prefix | 3 | 3 | 0 | 0 | Passed |
| Eight deliberate restore/cut cycles after offline peer turn | 1 | 1 | 0 | 0 | Passed |

The last capture is a bounded diagnostic probe that aborts on any failed operation. Its eight extra cycles were removed after capture; it is not a retry-until-pass repair. The original link cut/restore, offline peer `turn_end` and `context_usage`, absent phone reading before reopening, and repository reading plus footer percentage after reopening without sending remain unchanged.

Baseline Mobile was `cfc5911723bad305dcf2486283521915dce91b84`. Instrumented captures used `c69586d17086dd0e0cfa7e86df2e1444887cf059` with the exact additional source patch in each provenance JSON. Daemon was `8091a4730d42f43029eb0b955442dc300106030f`, Claude 2.1.280, managed `pixel2Api33Atd`. Production source between the ticket's `b44067bbd991` and baseline is unchanged; the intervening #2094 change only changed shortcut tests/documentation.

## Evidence and limits

Sanitized XML retains suite counts and every selected testcase outcome. The raw XML/logcat and daemon logs remain privately retained under:

- `/tmp/builder-2099/baseline/` (phone only; the passing harness deleted its daemon log).
- `/tmp/builder-2099/diagnostic-prefix-compiled/` (13-method prefix, raw phone evidence under `phone/`).
- `/tmp/builder-2099/registration-prefix/` (three-method prefix).
- `/tmp/builder-2099/cancellation-probe/` (eight-cycle probe).

Provenance JSON records raw artifact SHA-256 values, source revisions, exact tested dirty patch, selection and process status. The daemon extracts keep only allowlisted static events, canonical conversation UUIDs and hashed connection identifiers. Phone extracts keep only our static stage/boolean/status evidence. No credentials, message bodies or full connection identifiers are included. A connection hash is correlation metadata, never authorization.

The instrumented target retained its registered bundle and selected target throughout close and restore. Socket presence, authenticated repository availability and the asynchronous daemon-status projection are separate observations. A passing completion can still snapshot `daemon=Handshaking` while `repository=true`: `currentRepository.value` checks the actual Open pump synchronously, whereas the status projection can lag. These snapshots are non-atomic and are not evidence of unauthenticated publication. Phone/host clocks also differ; correlate the validated conversation UUID and event sequence before comparing timestamps.

The historical failing phone XML/logcat directories named in #2099 no longer exist locally. Earlier prefix instrumentation records the target passing, and daemon logs remain under `/tmp/builder-2089/instrumented-prefix-evidence/` and `prefix-stalled/`. A historical daemon sequence matching create/rename, phone close, peer-only send and push has no accepted phone handshake during the subsequent timeout-sized window; without the missing phone identity/stage evidence its scenario attribution and transport cause remain unconfirmed. No MCP causal relationship is established.

The fresh prefix's independent failures are #2112 (`backgroundAgent_followsBottomUntilFinished`, absent local_agent) and #2113 (`stopBackgroundTask_completesThenRepliesAgain`, absent held-Bash roster). Both were filed separately in Backlog with priority:low; they are not repaired or skipped here.

## Required continuation

Capture a **failing** invocation with these bounded diagnostics, correlated raw phone/daemon evidence and exact revisions; identify the demonstrated cause; add the focused behavioral red/green regression and localized repair. A diagnostic red/green check or a passing invocation does not substitute for that work.

After repair and verification, the dispatcher must run its fresh full live gate and explicitly pass the target. Retain full-suite and named-method executed/failed/skipped counts, raw XML/logcat, Mobile/daemon revisions and correlated daemon artifacts before merge. Those acceptance artifacts are still pending.

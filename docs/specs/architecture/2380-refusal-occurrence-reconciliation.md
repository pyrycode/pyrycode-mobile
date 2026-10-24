# #2380: reconcile additional refusal-switch timeout sightings

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel`, its reply/control deadline and fresh settings assertion.
- `scripts/e2e-emulator.sh` and `scripts/e2e-fixtures/refusal.jsonl`: the isolated fakeclaude refusal input and scenario selection.
- `docs/knowledge/features/model-refusal-row.md`: **Required daemon thread identity** distinguishes live arming from restored content.
- `docs/e2e-interactive-stream.md`: **Scenarios (#454)** and the accepted #2374 Linux proof.
- `docs/specs/architecture/2374-refusal-publication-proof.md`: producer regression, native counts, identities and retained limitations.
- Core `cmd/pyry/operator_message_history.go`, `internal/thread/sends.go` and `cmd/pyry/accepted_delivery_test.go`: delivery reference, permanent identity and real publication schedules.

## Design source

N/A: evidence reconciliation only. Existing visuals, production code, tests,
fixtures, assertions and deadlines remain unchanged; no design change is proposed.

## Change

Record how the additional Linux occurrences on #2246 and #2373 relate to the
causal repair already delivered by #2374 / Core #3725. Retain original failed
and passing evidence, verify manifests and recount selected XML, compare relevant
current source and inputs with the accepted repaired-source proof, then append
the reconciliation and exact artifact references here. A common observed reset
supports compatibility with the controlled defect; it cannot establish missing
historical batching or which half of a combined predicate failed.

The single deliverable is an evidence record, estimated at at most 250 written
lines in this specification, with no new exported declarations, consumer edits
or state-machine branches. Scope remains within all five builder ceilings.
The branch overlap scan found no overlap on this new specification. No duplicate
producer implementation or speculative Mobile repair is planned.

## Testing strategy

Reuse #2374's accepted Linux `scripted refusal` execution when the method,
production path, refusal fixture and relevant harness inputs agree. Confirm its
raw and counted XML each selects the exact method, with 1 executed, 1 passed,
0 failed and 0 skipped; retain device, source, binary and APK identities plus
phone/daemon evidence. Recount Core's before/after controlled publication cases.
Preserve the scripted reply, exactly one clickable control, disappearance after
tapping, fresh unheld settings returning `haiku`, live-only arming and 90000 ms
reply/control deadline. No added test logic needs a red/green implementation cycle.

Run required builder format, lint, assembly and final pre-verification checks on
Linux. Complete configured acceptance belongs to the dispatcher and remains
pending for this candidate. Existing failures remain failures. No extra real
Claude turn or macOS execution is needed. Request only a demonstrably missing
focused proof; allocate a distinct residual defect only if evidence establishes it.

## State transitions and identity reuse

None: this change records evidence and introduces no lifecycle or identity state.
The reused proof must retain the existing live-only recovery authority.

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md`, **Scenarios (#454)** / refusal and **Follow-ups to ticket** / #1360: link this reconciliation, retain both additional failed sightings and exact retries, and reuse #2374's repaired-source proof with its limits.
- `docs/knowledge/features/model-refusal-row.md`, **Required daemon thread identity**: link the additional occurrence reconciliation without asserting unknown original publication boundaries or turning restored history into live authority.

## Security review

**Verdict:** PASS. Reviewer: builder self-review, 2026-10-24.

- Trust boundaries: downloaded bundles are untrusted evidence. Verify hashes and source identities, read them as data, and never execute embedded commands. `onLiveRefusalEvent` retains authority for model writes; restored content gains none.
- Tokens and credentials: use retained deterministic fakeclaude evidence; obtain no provider login and publish no pairing material, tokens, decrypted messages or environment dumps.
- Files and storage: retain raw captures outside Git in the private ticket evidence directory with owner-only access and a manifest; commit only counts, hashes, static diagnostic event names and provenance. Preserve source bundles and their manifests.
- Android attack surface: no component, permission, intent, provider or WebView changes are made.
- Cryptography: no Noise, key, nonce, Keystore or signing change is made. Binary hashes identify artifacts; they are not new authentication claims.
- Network and I/O: no URL, frame handling, size cap, TLS, retry or timeout change is made. Matching evidence cannot authorize weakening transport or native checks.
- Errors and logs: retain failures privately; publish only content-free diagnostics and explicitly mark absent historical information unknown.
- Concurrency: no application job or flow changes are made. Reusing accepted proof avoids duplicate execution; required build checks still use normal admission and cleanup custody.
- Threat model: relay dropping/reordering, rooted-device theft, hostile frames and UI leakage protections are unchanged. This reconciliation claims no new coverage of those threats.

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

## Reconciliation results (2026-10-24)

Both additional sightings are compatible with the supported producer mechanism.
Neither establishes a distinct residual Mobile defect. Core #3725's repair is
merged through PR #3726 at `3d5379ec4b778c569c27b7639a2ae495b32cee0d`,
an ancestor of this run's supplied Core `64ef0e0fba71a723b7fdc97141cfc144f6355664`.
Mobile #2374 is closed and merged through PR #2528; its authoritative verifier
record accepts the regression and downstream proof.

### Additional historical occurrences

Counts below select only the exact refusal method from each retained XML report.
The recorded sighting timestamps identify fail-then-pass classification, rather
than the instant the daemon reset occurred. Both ran on Linux `build-hp-1`.

| Occurrence | Executed | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| #2246 original scripted-all | 1 | 0 | 1 | 0 |
| #2246 exact retry | 1 | 1 | 0 | 0 |
| #2373 original scripted-all | 1 | 0 | 1 | 0 |
| #2373 exact retry | 1 | 1 | 0 | 0 |

- **#2246:** Mobile `bce55074fc40033374927827a4c3c52adbdb1fab`. Failed scenario custody is `evidence/harness/fault-9cab0a1e3ab0804bcbc65f8237f25199/`; the daemon logs `baseline_required` and teardown 4414 at `2026-10-21T13:54:11.265Z`. Retained refusal phone log records peer close 4414 and a `ComposeTimeoutException` after 90000 ms, with no armed-offer event. Counted failure is `logs/verifier-gate__2246_6.log`; exact retry is `logs/2026-10-21T13-55-51-644Z_verifier-gate-rerun__2246.log`.
- **#2373:** Mobile `0934df51ee60ba8e51a8a09322e63d07f81e9704`. Failed scenario custody is `evidence/harness/fault-baac91bb1b2fe9adc1a5ff5c330ab0f6/`; the daemon logs the same reset/teardown at `2026-10-21T15:05:05.646Z`. Counted failure is `logs/verifier-gate__2373_6.log`; exact retry is `logs/2026-10-21T15-06-46-268Z_verifier-gate-rerun__2373.log`. This fetched bundle does not contain the original refusal phone log or raw device XML; counted XML and primary daemon/relay evidence remain retained.

Both failed harness input records identify old Core source/embedded revision
`2c68b595440dd7e8e843af9cff0d9a708bcc81a7` and binary SHA-256
`d93c56662d0072e8aec9d1466bee3f6845f7cdc55247273f177b48b209bdb6ac`.
That source's `operatorMessageHistory` appends the delivery message before
`sends.delivered` appends its link, without a delivery-time acceptance reference.
This is the publication opportunity addressed by the controlled regression.

The traces support correlation with that defect, not a reconstruction of the
original append/follower/publisher boundaries. Missing history segments leave
batching unknown. The original combined predicate requires both reply and
clickable control; the historical reports do not identify which operand was
missing at timeout. Absence of an armed-offer log alone is not proof of cause.
The exact retries establish intermittency only. Suite-order and machine-load
causation or independence remain unproved; no separate residual failure has
been demonstrated that warrants a wider investigation or second implementation.

### Regression and accepted native proof reused

Recounted Core `cmd/pyry/testdata/accepted_delivery/{before,after}.jsonl`:
before repair, 2 leaf cases executed / 1 passed / 1 failed / 0 skipped; after,
2 / 2 / 0 / 0. `TestAcceptedDeliveryLiveRefusal/separated_segment` fails before
repair with baseline required, one retirement and no live refusal. Both repaired
schedules retain exactly one live refusal, no retirement and no duplicate delivery.
The regression drives the real producer, forward reader, source-backed store
and publisher, forces a physical segment cut, and verifies replacement-peer
catch-up never replays an old refusal as live. The final reviewed repair also
removes the intermediate written outcome consistently across fold modes.

Reuse #2374's Linux `python3 scripts/android-test-gate.py scripted refusal`:
**1 executed / 1 passed / 0 failed / 0 skipped**, raw device time **5.221 s**.
Raw and counted XML agree on the exact method. Device `pixel2Api33Atd` uses
API 33 Google ATD x86_64 image revision 1 and emulator 37.2.12 (build 16428233).
Tested Mobile source is `585e5734e234d399250e51a78d6124605e0ba3b8`;
Core is `64ef0e0fba71a723b7fdc97141cfc144f6355664` and relay is
`2fd9338dd1662dbd68f3710a33e6a8cc4e7a0bc1`, both embedded and clean.
Binary SHA-256 values are respectively
`9d1b74bbfa1163c2043ecdbb53b321c3574cfaf60af4bdced90a62c447476d66` and
`79c5e14b4b52c10c6b5523ca56f8d3354b2d6bbcb2672be68588f199a1b61973`.
`2374-native/result.json` retains both APK hashes as well.

Compared that tested Mobile source to this branch's starting head
`c4997c02a9cf2379ac6826248594e63d9fba8279`: production and shared tests are
unchanged, as are the exact refusal method, constants and refusal fixture.
The method text SHA-256 is
`0cd4165fb19127401a223b8ad4b8567a056c2f6e84d1b5c058f3bfd908367b2e`.
The only intervening executable edits concern the separate reply-suggestion
method and its scenario inputs. The refusal selector and harness route are
unchanged. This ticket edits only this specification, so no missing focused
refusal check is established. The accepted proof preserves every assertion
and the original deadline. Phone evidence records armed, sent and acknowledged;
the repaired isolated daemon trace has no baseline-required reset.

### Evidence custody and acceptance boundary

Private retained root on Fractal:
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2380/builder-20261024/`.
`reconciliation.json` contains selected counts; `RECONCILIATION-SHA256SUMS`
covers the retained files. Downloads preserve original `run.json`, manifests,
source paths and missing-file pointers. Original bundle names replace `#` with
`__` in log paths.

| Source | Run ID | Bundle digest / checked manifest |
| --- | --- | --- |
| #2246 failure and retry | `3ffde847fd61222a970908d87e301247ccf81924940db07cb3da772153afcede` | `c8919380727b3483cd957d04aebfbb609f0018a407c4f6ec0eee506fb1e6a247`; 166 entries matched |
| #2373 failure and retry | `795b38bd82594fad5cf8942dabda47efc0395ff1c30c65fbc8306247a7a56ccf` | `656cbff3c01bdc3235cb545a7178819d60d3d8cb0afd5eaa82f1ab32f0c5fd63`; 138 entries matched |
| #2374 accepted review | `3f500fe4972a2be1906490cd018c366ec994c89c61a8dd1eadbe16cf8721decf` | Authoritative `stages-2374.json`, PASS at `7d4e02691ec82367f563f93cba08920620313606` |
| #2374 focused native proof | `2374-native/` copied from `2374/bounded-20261024/` | 15 native and 18 handoff manifest entries matched |

The copied native directory retains raw XML, counted XML, phone log, phase
timestamps, daemon/relay/fault logs, runtime inputs and build/device metadata.
Historical bundles retain failures and retry summaries. Scenario-specific
daemon-b, answer and bypass logs were unavailable, as declared by their inputs.
No missing capture is claimed as proof. Core before/after JSON is copied under
`core-publication/`. Raw captures remain private and outside Git.

#2374's accepted configured gates are prior evidence, including its explicitly
accepted inherited failures; they are not acceptance for this candidate.
This branch's complete configured dispatcher gates and documentation handoff
remain pending. No real Claude turn was used; macOS is outside this Linux
ticket's verification scope.

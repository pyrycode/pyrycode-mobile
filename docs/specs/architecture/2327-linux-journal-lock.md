# #2327: Linux Gradle journal-lock recovery

## Files read

- `scripts/agent-tool.py`: forwards managed builds through Mobile admission.
- `scripts/test-admission.py`: `main` validates inherited lock identity; `drain_group` retains custody until owned work exits.
- `scripts/test_test_admission.py`: `AdmissionTests` covers serialization, nesting, descriptor identity and cancellation cleanup.
- Agents `scripts/gradle-gate.py`: `launch` executes the pinned Gradle/JDK client without a detached build worker.
- `docs/knowledge/features/development-verification-gates.md`: containers must configure the same host-backed admission lock; cache reuse and cleanup stay enabled.
- HP `/work/Projects/.manual-2323-lock-recovery/`: host ownership, deployment correction, controlled contention and admitted APK build evidence.
- [HP diagnosis](https://github.com/pyrycode/pyrycode-mobile/issues/2327#issuecomment-6290582719) and [Fractal activation](https://github.com/pyrycode/pyrycode-mobile/issues/2327#issuecomment-6295410964): operator-owned rollout and its separate acceptance boundary.

## Context

The original Linux sweep failed before device execution at Mobile `7a58afac133204acba692a7c9567f59e224de4f1`. The current owner cannot be inferred from its historical PID. Host diagnosis instead correlated a live Gradle journal owner with the native Android service and that service's admission owner. Both containers shared the Gradle cache, but the dispatcher used `/work/Projects/.pyry-test-admission.lock` and native Android defaulted to `/home/agent/.local/share/pyry-fleet/test-admission.lock`. Distinct lock inodes admitted concurrent cache users.

The operator already repaired the persistent native Quadlet configuration on HP and Fractal by setting `PYRY_TEST_ADMISSION_LOCK=/work/Projects/.pyry-test-admission.lock`. Activation waited for existing workers to finish. This is a deployed host configuration correction, with no outstanding runner source repair to implement in Mobile. The withdrawn diagnostic-route task remains failed/withdrawn, superseded by the manual diagnosis; it is not a passing check. No new blocker is needed for completed deployment work. This ticket retains the recovery record for ordinary review and pipeline acceptance.

## Change

Retain a bounded, hashed evidence fixture in `scripts/e2e-fixtures/2327-journal-lock/`: the original failure, host ownership and effective configuration, private-journal contention before/after rollout, admission regression output, and the successful admitted HP APK build with its original summary. Add a receipt that identifies source paths, verified hashes, revisions, counts and evidence limitations. Raw artifacts remain byte-for-byte; interpretation belongs in the receipt and this plan. Do not copy unrelated application logs or runtime credentials. The Mobile admission and Gradle implementations already enforce their contracts when deployment supplies the shared lock, so no runtime code change is warranted.

This is one deliverable: retain the demonstrated startup-contention repair and its regression evidence. Forecast: fewer than 650 written lines including this plan and raw logs, zero exported declarations, zero changed consumers and zero new state-machine branches. The original estimate assumed two production edits; the evidenced repair required zero. No in-flight feature branch overlaps these unique evidence paths.

## Evidence contract

- HP boot `f8694279-2f0d-4fb7-a4a3-d9b1a94dc629`: live journal owner PID 8226/startTicks 49937064 and admission owner PID 6583/startTicks 49927100 share the native-service cgroup and PID namespace. This identifies participating native work outside this builder's namespace, not a demonstrated surviving detached worker or unrelated cache owner. The operator waited for its normal completion before restart.
- Journal identity: device/inode `66306:1082682`; native default admission `66306:3566050`; dispatcher admission `66306:1100311` (the last identity is operator-reported). These observations establish the configuration mismatch, not the identity of historical owner PID 279774.
- The cross-container private POSIX journal surrogate failed before rollout: native exited 0, dispatcher exited 1 with `BlockingIOError`, one journal acquisition. After rollout both exited 0 with two sequential journal acquisitions. Private holds protected the real workloads during the comparison. Surrogate success is distinct from a Gradle build.
- HP admitted APK comparison at Mobile `3c57ef21efaefb487de2c16e7543325f0a53ec38` succeeded in 86.705 seconds wall time (Gradle 1m25s), with 27 executed tasks and 13 from cache. The retained stderr reports admission waiting rounded to 0 seconds. It ran zero device tests.
- Fractal evidence is operator-reported in the linked activation comment: Mobile `89b2fb23dd5f16b51ba98d007746bf7dbd39b22d`, agents `e2f17c0ca3173e2991cfbd5d853fd3f6dc8239d0`, shared admission device/inode `2050:12099325`, 139 seconds waiting behind legitimate work, seven passing regressions, APK build 1m13s with 18 executed tasks and 22 from cache. Its raw host directory is not mounted here; do not describe it as locally inspected.
- Preserve unknowns: HP's exact historical agents revision and original sweep owner lifetime are absent from the retained host artifacts. Current agents revision does not retroactively establish either. These gaps do not erase the controlled deployed-mismatch reproduction.

## Testing strategy

Verify every retained byte against its source hash before committing. Re-run only `AdmissionTests` on Linux and report executed/passed/failed/skipped counts. The existing suite proves nested progress, serialization, descriptor validation and cancellation custody. The retained real-container before/after comparison is the regression for the already-deployed configuration fix; local fixture replay cannot substitute for it.

Run required formatting, lint and APK assembly, then merge main, push and run final APK assembly plus `scripts/pre-verify.py --gradle`. Preserve output and distinguish new branch checks from the earlier operator runs. Complete JVM/UI/scripted coverage and the later in-depth main sweep remain dispatcher-owned and pending. macOS is outside this Linux ticket's verification scope. No live-Claude or device-only test is added or run by this builder.

## State transitions and identity reuse

None: this branch retains evidence and changes no runtime state or identity rules. Existing `AdmissionTests` cover repeated acquisition, nested inheritance, stale descriptors, waiter cancellation and owned-child cleanup; the deployed correction makes all participating containers use the same lock identity.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/development-verification-gates.md`, shared admission guidance — record the deployed cache/admission identity mismatch, the host correction and evidence boundaries. Keep raw evidence linked to this ticket's fixture and preserve the separate dispatcher acceptance results when available.
- Pending agents/deployment documentation owner: record the persistent Quadlet environment requirement and the Podman 4.9 `.container.d` limitation reported by the operator. No agents or host configuration is edited by this Mobile branch.

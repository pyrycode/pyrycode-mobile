# Saved-thread readiness proof (#2143)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: `awaitDeviceReadiness`, `measureSavedThreads`, `openAndMeasure` and `Probe` separate pre-opening environment preparation from committed-frame measurement.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/EmulatorCpuReadiness.kt`: `awaitEmulatorCpuIdle` and `parseEmulatorCpuTicks` retain the readiness threshold and deadline.
- `app/src/test/java/de/pyryco/mobile/e2e/EmulatorCpuReadinessTest.kt`: existing responsive busy-window tests do not exercise held acquisition or reading.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`: isolated passes cannot diagnose a suite miss; preserve every historical miss and every cold committed-frame assertion.
- `docs/knowledge/features/development-verification.md`: device results require completed counts and retained raw evidence.
- `origin/feature/2009`: the corresponding three source files and sampling provenance from PR #2080 provide an existing cancellation repair to reuse without its unrelated attention/live work.
- Original #2143 sweep XML/stderr: one saved-thread failure, both controls passed, and a 30000 ms coroutine timeout; original detailed phase artifacts are unavailable.

## Context

The original sweep at `b107f97a743a508fb9182fdc165b3e084180e250` executed 1608 tests, passed 1607 and failed the saved-thread first-draw method. Its 30-second exception identifies the readiness deadline, not the separate one-second committed-draw assertion. No measured-path edit occurs in the supplied intervening range. Fresh baseline logs show all eight openings and both controls completing; that counterevidence does not identify the historical cause.

The synchronous shell acquisition and pipe reading have an independently testable defect: a coroutine deadline cannot bound a blocking call. Demonstrate that defect locally and reuse PR #2080's exact cancellation repair, retaining uncertainty about whether the historical failure involved held sampling or responsive busy windows. No production change or decision record is needed.

Overlap: #2009 / PR #2080 modifies the same three files. Reuse its identical sampling changes and controls; identical changes need no competing design when either branch merges.

## Design

All changes remain in test sources. Reuse `withEmulatorCpuSample`, `readEmulatorCpuTicks`, the suspending sample contract in `awaitEmulatorCpuIdle`, the device nonblocking reader, bounded phase diagnostics, and regression tests from PR #2080. First extract unchanged synchronous sampling into the same test seam for a compiling red acquisition/pipe comparison, then replace it with the existing repair. No new production types, dependencies, fixture preparation or measured work are introduced.

`withEmulatorCpuSample` bounds the caller independently of synchronous Binder acquisition. Late descriptor delivery after cancellation closes the descriptor without reading it. `readEmulatorCpuTicks` consumes at most a 4096-byte first CPU line and yields while the nonblocking Android reader reports no data. Success, malformed input, reading cancellation and late acquisition each release the owned descriptor.

Device readiness logs name the case and acquisition/read/window phase, sampling durations and the last twelve windows/samples on failure. A deadline still throws and fails the test; diagnostics never establish successful measurement or convert a failure to a skip. Keep the existing opening/worker/frame logs and exact eight-case probe.

## State and concurrency model

Acquisition runs in a test-owned IO worker scope, canceled on completion or caller cancellation. The caller suspends independently of a Binder call that can ignore interruption. Cancellable continuation resource cleanup owns late acquisition results. Reading runs in the caller context with a suspension on empty nonblocking reads; `use` closes an acquired descriptor. This changes no ViewModel, StateFlow, repository or application lifecycle state.

## State transitions and identity reuse

| Event | Test |
| --- | --- |
| Deadline during held acquisition; descriptor arrives afterward | `heldAcquisitionCannotHoldTheReadinessDeadline`, `cancellationDuringAcquisitionClosesTheLateSourceWithoutReadingIt` |
| Deadline/cancellation during held reading | `heldReadClosesItsSourceAtTheReadinessDeadline`, `cancellationDuringReadClosesTheSource`, device `heldCpuPipeCancelsAndClosesItsDescriptor` |
| Success across repeated samples | `readyWindowsCloseEverySource` |
| Malformed/partial/overlong/EOF input | `malformedReadFailsAndClosesTheSource`, `partialNonblockingReadsUseOnlyTheBoundedCpuLine`, `eofAndOverlongCpuLinesRejectReadiness`, existing parser tests |
| Busy window resets readiness; responsive continuous load exhausts deadline | Existing `busyWindowRestartsTheFourConsecutiveIdleWindows`, `continuouslyBusyDeviceFailsInsteadOfChangingTheDrawBound` |
| Cold first open and reopen in both connection modes and fixtures | Original first-draw method and delayed-projection twin, eight cases each |

## Error handling

Acquisition or parsing errors and the 30000 ms timeout propagate as test failures with bounded readiness diagnostics. Four consecutive 250 ms windows at >=80% idle remain required. The unchanged 1000 ms draw bound begins before cold cache/repository and ViewModel construction. No retries, warming, threshold adjustment or skip conversion is allowed.

## Testing strategy

Retain the original failure and the fresh full in-depth baseline at the supplied revision. Run the extracted synchronous acquisition regression red and the real Android held-pipe control red before installing the reused correction. Then execute all `EmulatorCpuReadinessTest` methods and the entire affected device class, inspecting fresh XML counts and case logs. The device-only reason is real Binder/shell/descriptor behavior, disk IO and committed Android frames, which Robolectric cannot prove.

Run the in-depth suite again with `UI_GATE_FULL=1 UI_DEVICE_ALL=1 python3 scripts/android-test-gate.py ui` to compare the same two-shard configuration. Preserve revision/configuration/source hashes, named-method counts, all eight draw timings and bounded readiness/opening/worker logs in content-free test assets; raw XML and logcats survive under the persistent agents evidence volume. The configured regular UI gate and later main sweep remain dispatcher-owned and must be handed off explicitly.

Run focused unit tests, lint, assembly, Android-test compilation, formatting, and final `assembleDebug` / `pre-verify.py --gradle` after the final main merge. No live Claude or scripted scenario is required: application behavior and live harness code are unchanged.

## Open Questions

- Did held acquisition/reading cause the original sweep timeout? Unresolved historical cause: its detailed phase logs are missing. Fresh passing runs and controlled failures cannot answer it; retain that limitation in evidence and the PR.
- Does the reused repair reproduce and pass locally? Resolve through the red/green acquisition and Android pipe comparisons before handoff.

## Documentation handoff

Pending documentation stage: `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`, saved-thread first-draw testing: record the original failing counts, fresh comparison evidence, controlled sampling defect and cancellation repair. Preserve the distinction between responsive readiness exhaustion, held sampling and measured latency, and the unresolved historical cause.

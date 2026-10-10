# Answer-host setup evidence (#1899)

`historical.stderr.txt` is a sanitized excerpt of the retained #1731 dispatcher logs. The full logs
remain in the agents repository's `logs/` directory under the recorded basenames and hashes.
It records the initial named failure and same-tree rerun, not a repaired-branch live pass.

At historical mobile revision `a7a4b484d7260c4dc7418b85df9c10f2b7245398`, `pairAnswerHost` calls
`awaitChannelList`, then compatibility `awaitConnected`, then camera grant and `pairHostByCode`.
Only `awaitConnected` has a synchronous coroutine timeout in that setup. The remaining UI waits
throw ComposeTimeoutException, while PairCodeViewModel handles its own connection deadline as
state. Thus the recorded 30000 ms coroutine timeout is the preceding-host readiness wait, before
answer-host pairing starts. `RelayConnectionRegistry.observe` follows the latest surviving saved
host. The lower-level reason that host failed to connect is not recoverable from the retained
artifacts; neither the unopened peer's status nor post-teardown activity state supplies it.

`AnswerHostSetupTest.offlinePrecedingHostDoesNotBlockAnswerHostPairing` exercises that same live
helper with compatibility held Offline and a fixture-owned saved preceding host. The real native
paste-code/fingerprint flow must save/name the exact new record and verify it once before list
navigation. Only network readiness is controlled; it does not claim to prove a Noise handshake or
Claude round trip. Cleanup removes only the fixture's unique ids and restores Koin definitions.

Focused command (run before and after removing only the unrelated readiness wait):

```bash
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.onboarding.AnswerHostSetupTest' \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e --console=plain
```

## Controlled red

The focused command exited 1 with **1 executed, 1 failed, 0 errors, 0 skipped** at
`2026-10-10T01:04:42`. The named regression failed with the historical 30000 ms coroutine timeout;
its target verification was never reached. The compatibility wait was still present. The source
JUnit SHA-256 is `8ad6f8f7d2fa6f1a48b0c110fbeea60bc36ceffce6bc2ba6e1f3666896ee2526`.
`red.xml` is a sanitized one-case summary; the original is retained at
`/tmp/builder-1899/red-device.xml`. The device was managed `pixel2Api33Atd`, API 33 / AOSP ATD.

## Live acceptance

Builder controlled evidence is separate from live acceptance. The dispatcher must separately run
the fresh full live gate and retain counted XML plus the explicit question-answer method pass,
mobile/daemon revisions and copied per-test artifacts. No fresh live result is claimed here.

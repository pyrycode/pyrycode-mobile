# #1480: give the bypass test's Read an upstream-latency budget

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`:
  `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` step 5, its only call of
  `awaitReadPrompt`, and the companion's `REPLY_TIMEOUT_MS`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `awaitPermissionModal` and
  `awaitFrame`. `awaitFrame` reads the `received` state flow, which accumulates every frame since `open`,
  so a `modal_shown` that arrived before the wait started is still returned. No peer change is needed.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt`: `awaitPeer` times out with
  `TimeoutCancellationException` from `withTimeout`.

## Change

Step 5 waits for the host's permission modal for the chat first, through
`peer.awaitPermissionModal(chat.id, UPSTREAM_PERMISSION_TIMEOUT_MS)`, a new 360 000 ms companion constant
(6 minutes). A `TimeoutCancellationException` there becomes an `AssertionError` saying Claude raised no
permission request within that many ms of the send. Only then is the phone judged: `awaitReadPrompt` keeps
its `REPLY_TIMEOUT_MS` waits, and a `ComposeTimeoutException` or `AssertionError` from it becomes an
`AssertionError` saying the host raised the modal and the phone did not render it within
`REPLY_TIMEOUT_MS`, with the original as cause. The peer then allows the same modal id it already holds.
The #977 `mark` is taken after the modal wait, as before. Other `REPLY_TIMEOUT_MS` callers are untouched.

## Testing strategy

Live-only: the method runs in the real-Claude gate. Compile with `compileDebugAndroidTestKotlin`. The
PR's `## Live tests` says `all`, because the ticket's last criterion asks for a full live-suite run.

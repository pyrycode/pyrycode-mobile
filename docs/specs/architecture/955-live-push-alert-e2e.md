# #955 — Live push wakes the backgrounded app and posts one alert (rung 3)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`: the analogue. Its `runningToolPeer` and
    `holdToolOnPermission` pattern hold a real turn open on a permission prompt that only the peer can answer.
  - `setHostLink` / `cycleHostLink` / `hostRepository`: they read `bundle.coordinator.currentRepository`
    (null means the link is down). This is the signal for "the app closed its link" and "the wake reopened it".
  - `answerChat`: creates a chat and gives it a run-unique name through the host repository, with no UI.
  - `awaitConnected`, `awaitChannelList`, and the companion's timeouts and `RUNNING_TOOL_PROMPT`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `sendMessage`,
  `awaitPermissionModal`, `allowOnce`, `awaitFrame`. The peer starts a turn and answers its prompt.
- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt`
  - `AttentionNotifier.post`: channel `ATTENTION_CHANNEL_ID`, fixed text `notification_turn_completed` /
    `notification_prompt`, one tag per conversation and id 0.
  - `NotificationTap.pendingIntent`: `NEW_TASK | CLEAR_TASK` to `MainActivity`. A tap therefore creates a
    fresh activity, and its `onCreate` reads the target.
  - The tag `digest` is private. The test does not recompute it. The tap proves which conversation the
    alert belongs to.
- `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt`: `onStop` closes all hosts.
  `onPushWake` connects them for the wake window and is a no-op in the foreground.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `onCreate` reads `NotificationTap.target` only
  for a fresh activity.
- `docs/knowledge/features/push-messaging-service.md` § Attention alerts: the order is dedupe, then the
  foreground, enabled and permission gates. The ledger is what holds "at most once" across a reconnect.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md` § Attention alerts: a
  reconnect that re-shows the same prompt re-emits it, and the notifier's ledger suppresses the repeat.
- `../pyrycode/cmd/pyry/push_wake.go` → `pushWaker.wakeAbsent`: the daemon (v0.23.0+) wakes each fcm
  device with a push token and no open session. It does this on `TurnEnd` and on a surfaced prompt, and
  **coalesces per device for 30 s** after a successful wake.
- `../pyrycode-relay/internal/relay/push_wake.go`: the relay rate-limits wakes per server id, with a burst
  of 6. Two wakes per run fit.
- `app/build.gradle.kts` → `managedDevices.localDevices["pixel2Api33Atd"]`: `systemImageSource = "aosp-atd"`.
- `scripts/e2e-emulator.sh`: the LIVE `TEST_TARGET` curated list.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM`, and `managed_avd`, which globs the AVD name
  `dev33_aosp_atd_*`.
- `scripts/test_android_test_gate.py`:
  - `test_managed_avd_is_found_only_for_the_managed_device` pins that AVD name.
  - `test_live_floor_matches_the_curated_list` ties the floor to the list.

## Design source

**Figma:** N/A. The notification and the permission prompt use Android's native surfaces, and the tap
opens the existing thread screen (https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8). The
ticket adds no UI.

## Context

#685 is proven only with fakes. The sender side has shipped: relay#130 is deployed, and daemon v0.23.0
includes #2564. This ticket adds the first real proof: a push through FCM from the production relay
reaches a backgrounded app on an emulator. The managed device's `aosp-atd` image has no Play services and
cannot obtain an FCM token. The device therefore moves to `google-atd`, which is still headless. That
change applies to every gate that boots `pixel2Api33Atd`: `ui`, `scripted` and `live`.

## Design

### Device image

Set `systemImageSource = "google-atd"` in `app/build.gradle.kts` and update its comment. The Gradle
device name `pixel2Api33Atd` stays the same, so no task name, gate or dispatcher command changes. The AVD
that Gradle creates is renamed from `dev33_aosp_atd_*` to the google image's name, so `managed_avd` in the
gate script and its unit test follow. The exact stem is confirmed from the AVD that the setup task creates,
and is recorded under Revisions if it differs. Update the `aosp-atd` prerequisite comment in
`scripts/e2e-emulator.sh`.

### Two scenarios on `InteractiveStreamE2ETest`

Both scenarios apply only in LIVE mode, because only the production relay can send FCM. Each starts with
`Assume.assumeTrue` that the phone's relay URL (`ARG_RELAY_URL`) is `wss://`. The default whole-class
loopback run therefore skips them rather than failing. The LIVE gate never skips them, and it rejects
skips.

**`interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`** (AC1, one claude turn):

1. `runningToolPeer()` + `holdToolOnPermission(peer, RUNNING_TOOL_PROMPT)`: the phone has a fresh chat
   open with a real turn held on a permission prompt. The phone sees that prompt in the foreground, so
   its alert is spent there and is never posted later (dedupe before the gates).
2. Rename the chat to a run-unique name through `hostRepository().rename`, so the tapped thread can be
   identified.
3. `awaitPushRegistered()`. Details are under Helpers.
4. `sendAppToBackground()`, then wait until the host's `currentRepository` is null. That proves the
   driver closed the link, so the daemon sees the phone as absent.
5. The peer calls `allowOnce`, then `awaitFrame(conversationId, "turn_end")`. The turn ends while the
   phone is absent, so the daemon sends `push_wake`, the relay sends FCM, `onPushWake` runs, the host
   reconnects, the missed `turn_end` is replayed, and a `TurnCompleted` alert is raised.
6. `awaitAlerts(expectedText = notification_turn_completed)`: exactly one active notification on
   `ATTENTION_CHANNEL_ID`, with the turn-completed text.
7. Send that notification's `contentIntent`. This is exactly what the system sends on a tap. Then wait for
   a thread whose top bar shows the run-unique name, with the send control present and the channel-list
   marker absent.

**`interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`** (AC2, one claude turn):

1. `awaitChannelList` + `awaitConnected`, then create a run-unique chat through the host repository (the
   `answerChat` shape on the first host), open the peer, and run `awaitPushRegistered()`.
2. `sendAppToBackground()`, then wait until the link is down.
3. The peer sends `RUNNING_TOOL_PROMPT` into the chat, then `awaitPermissionModal`. The prompt surfaces
   while the phone is absent, so the daemon wakes it.
4. `awaitAlerts(notification_prompt)` returns exactly one alert, and its `postTime` is recorded.
5. Wait until the host's `currentRepository` is non-null, meaning the wake is connected, then run
   `cycleHostLink`. This is a second reconnect inside the wake window, and the daemon shows the
   still-outstanding prompt again on it.
6. Wait until the reconnected repository shows the prompt again (Open question 2). Then assert that
   exactly one notification is active and that it has the **same `postTime`**. A second `notify` for the
   same tag would replace the notification and change its `postTime`, so an unchanged value proves the
   notification was not posted a second time. The count alone cannot tell that apart.
7. `finally`: the peer allows the prompt, waits for `turn_end` and closes.

### Helpers (test-only, private)

- `awaitPushRegistered()`:
  1. Polls `AppPreferences.pushToken.first()` until it is non-null. A fresh read on each loop avoids the
     #968 lost-emission hang.
  2. On a timeout it fails with: "no FCM token — does the device image have Play services? (#955)". This
     is the AC's "report and stop" signal.
  3. Then it runs `cycleHostLink(serverId)`, so the #365 connect-time re-registration has certainly sent
     the token to the daemon.
  4. Finally it sleeps out the rest of the daemon's 30 s per-device wake coalescing window, measured from
     when this scenario first saw the phone connected. The phone was connected from then on, apart from
     the scenario's own instant cycle, so the daemon cannot have woken it since. A wake from an earlier
     scenario therefore cannot suppress this scenario's wake.
- `sendAppToBackground()` presses Home through the instrumentation's `uiAutomation`, as the operator
  does. `ActivityScenario.moveToState(CREATED)` would put an androidx.test activity in front, in this
  process, and `ProcessLifecycleOwner` would still count the process as started.
- `awaitAlerts(text)` polls `NotificationManager.activeNotifications` (the app's own notifications) until
  the list is non-empty. It then asserts that there is exactly one notification, on
  `ATTENTION_CHANNEL_ID`, with `EXTRA_TEXT == text`, and returns it.
- Each scenario calls `NotificationManager.cancelAll()` at its start and in `finally`. At the end it also
  finishes every activity the tap created: the tap's `CLEAR_TASK` destroys the rule's activity, so the
  rule cannot close the new one.

### Gate scripts

- `scripts/e2e-emulator.sh`: both methods join the LIVE `TEST_TARGET` list, with a comment naming #955
  and the two turns they add.
- `scripts/android-test-gate.py`: add `LIVE_MINIMUM += 2` with a #955 comment. Update the `managed_avd`
  glob.
- `scripts/test_android_test_gate.py`: update the managed-AVD fixture name.
  `test_live_floor_matches_the_curated_list` keeps the list and the floor equal.

## State + concurrency model

This is test-only work. It adds no production state. Waits use `runBlocking` + `withTimeout` on Koin
singletons (`RelayConnectionRegistry`, `AppPreferences`) and `composeTestRule.waitUntil`, as the class
already does.

## Error handling

Every wait fails with the stage it reached:
- no token (image);
- link never dropped (background did not happen);
- link never re-opened (no push arrived: relay, FCM project or daemon version);
- notification missing, or the wrong count or text;
- thread not opened.

These let the live report tell an environmental gap from an app regression.

## Testing strategy

- The two new methods are rung-3 real-claude scenarios. They run only in the dispatcher's
  `python3 scripts/android-test-gate.py live` after verifier, because the ticket is `needs-real-claude`.
  There is no rung-4 twin: the scripted harness uses a loopback relay that cannot send FCM, and #685
  already covers synthetic delivery.
- Builder checks:
  - `./gradlew compileDebugAndroidTestKotlin`, `lint`, `assembleDebug`, and `spotlessApply`.
  - `python3 -m unittest discover -s scripts -p 'test_android_test_gate.py'`.
  - Provisioning the `google-atd` device, plus one focused `scripted ping` run. This shows the new image
    boots and still runs the other gates' harness.
  - A focused live run of the two methods, if the time budget allows. If it does not, the PR says so.

## Documentation handoff

Pending for the documentation stage:
- `docs/e2e-interactive-stream.md`:
  - Live mode and the Pre-ship gate: the curated list grows from 27 to 29 methods and 29 to 31 turns,
    and `LIVE_MINIMUM` rises from 27 to 29.
  - How to run: the prerequisite changes to the `google-atd` image.
  - A paragraph for the push scenarios.
- `docs/knowledge/features/push-messaging-service.md`: #955 is no longer "still-blocked", and the live
  proof exists.

## Open questions

1. Does the ATD image have a launcher, so that Home really backgrounds the app? If the link never drops
   after Home, fall back to starting the system Settings activity through a shell `am start`, which also
   puts another process's activity in front.
2. How can the test tell that the second reconnect has processed the re-shown prompt? Prefer a
   repository-observable prompt state for the conversation. If none is exposed, wait a bounded settle
   period after the cycle, and record that choice here.
3. The exact AVD stem for `google-atd`. Confirm it from the provisioned AVD.

## Revisions

**2026-09-24, during the build. Resolves the three open questions.**

1. **Background.** `sendAppToBackground` starts the system Settings activity with a shell
   `am start -W -a android.settings.SETTINGS`. It does not press Home. A string scan of the ATD system
   image found `FallbackHome` but no launcher package, so Home may have nowhere to go. Settings is
   always present, because `FallbackHome` lives in it. Starting it moves another process's activity in
   front, which is exactly what the operator's switch to another app does. The helper still waits until
   the host link drops, and fails with "the app did not go to the background" if it never does.
2. **Re-shown prompt.** `RelayRepositoryCoordinator.currentModal` is retained across a reconnect, and
   the raw modal stream is private, so neither can show the re-show. The test instead subscribes to
   `HostConversationSource.alerts` before `cycleHostLink` and waits for the prompt alert for this
   conversation. That flow re-emits on a reconnect that re-shows the prompt, as its docs say. It then
   waits `NOTIFIER_SETTLE_MS` (2 s), because the notifier handles the alert on its own dispatcher. The
   post-time assertion comes after that wait.
3. **AVD stem.** The setup task created `dev33_google_atd_arm64-v8a_Pixel_2`, which is the glob
   `dev33_google_atd_*_Pixel_2.ini`. The old `dev33_aosp_atd_*` AVD stays on disk. The gate never
   boots it, and the unit test now checks that too.

AC1's chat is created and named through `answerChat` and opened with `openChatRow`, the #967 shape, rather
than through `holdToolOnPermission` followed by a rename. This gives the same held prompt with one fewer
step. `awaitPushRegistered` waits out a 32 s coalescing window, not 31 s, to allow for clock skew.

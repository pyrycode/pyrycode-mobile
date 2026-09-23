# #685 — Mobile attention alerts and tap-to-thread

## Files read

- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource.launchAttention`, `updateAttention`, `Held` — where each host's live events, modal and question batches are folded under one monitor; the new alert signal is emitted from the same collectors.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt` → `HostAttentionState.completed`, `isCounted` — the fold already counts a turn once per turn id (in-memory `counted`, persisted `positions`); a turn is "newly completed" exactly when `counted[id]` changes.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt` → `ModalUiState.Open.modalId` / `conversationId`; `data/model/QuestionBatch.kt` → `QuestionBatch.questionBatchId` — the prompt identities.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `createdAtStart` `LifecycleConnectionDriver` binding; `hostConversationModule` (the `HostConversationSource` single); `ThreadDestinationFactory.isSavedHost`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `MainActivity.onCreate`, `PyryNavHost`, the `CHANNEL_LIST` and `SETTINGS` route blocks, `Routes.thread`, `HostDestination`, `openThread`; the scanner block's `rememberLauncherForActivityResult(RequestPermission())` pattern.
- `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt` → the push-wake window; a process started by a push has no activity and begins backgrounded.
- `app/src/main/java/de/pyryco/mobile/push/PyryMessagingService.kt` + `app/src/test/.../push/PyryMessagingServiceTest.kt` — the Robolectric-against-the-app-graph test pattern.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `notificationsEnabled` (default `true`).
- `app/src/test/java/de/pyryco/mobile/robolectric/RobolectricTestApp.kt` — Robolectric's Application loads `appModule` + `conversationRepositoryModule(useRelay = false)`, so an eager binding in `appModule` is created in every Robolectric test and must be inert there. It never runs `PyryApp.onCreate`, so the notification channel is created by the notifier, not by `PyryApp`.
- `app/src/test/.../di/HostConversationSourceAttentionTest.kt` → `withSource`, `end` — harness the alert tests extend.
- `app/src/sharedTest/.../ui/settings/SettingsNavigationTest.kt` — the `PyryNavHost`-on-the-production-graph harness the tap-navigation test copies.
- `docs/knowledge/features/push-messaging-service.md` — `onMessageReceived` never reads the message; alerts must not change that.
- `docs/knowledge/features/app-preferences.md` § `notificationsEnabled`.

In-flight overlap check: no other `origin/feature/*` branch touches the planned files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The existing conversation thread screen (top app bar with back arrow and title, message bubbles, composer). This ticket adds no visual surface: the tap opens that screen unchanged, the notification and the permission prompt are Android's native surfaces, and the Settings switch is the existing "Push notifications when claude responds" row.

## Context

Desktop shipped native alerts, tap navigation and replay dedup (#391–#393, #514). Mobile already folds per-host attention (#877) and has the saved switch (#268) and the FCM wake (#361). This ticket joins them: an Android notification when a turn completes or a prompt becomes outstanding while the app is backgrounded, and a tap that opens that exact host's thread. No FCM sender exists yet, so production alerts fire only during a foreground-to-background grace or a future push-wake window; proof is by fakes and Robolectric (#955 is the live proof).

## Design

### 1. The alert signal — `HostConversationSource.alerts`

`attention` carries states, not identities, so it cannot dedupe per turn or per prompt. The source gains a second output:

```kotlin
data class AttentionAlert(val serverId: String, val conversationId: String, val kind: Kind, val key: String) {
    enum class Kind { TurnCompleted, Prompt }
}
val alerts: SharedFlow<AttentionAlert>   // hot, no replay, bounded buffer, DROP_OLDEST
```

- **Turn:** in the live-event collector of `launchAttention`, inside the `updateAttention` change block (so only a current generation emits), a `TurnEnd` whose fold changed `counted[conversationId]` emits `TurnCompleted` with `key = turnId`. This reuses the fold's once-per-turn rule exactly (blank/oversized ids never count; a re-delivered turn is not counted; persisted `positions` recognise the latest turn after a restart). Emitted regardless of viewing: a thread composition can stay alive in the background.
- **Prompt:** in the modal/batches collector, the current prompt keys are `"modal:" + modalId` for an `Open` modal with a non-blank `conversationId`, and `"batch:" + questionBatchId` per batch. `Held` remembers the previous key set; only keys new since the last emission are emitted, so a `StateFlow` re-emission of the same prompt emits nothing. A reconnect that hides and re-shows the same prompt re-emits it; the notifier's ledger (below) drops it.
- Emission is `tryEmit` under the existing monitor — non-suspending, no new lock.

### 2. The publisher — `notifications/AttentionNotifier.kt` (new)

```kotlin
class AttentionNotifier(
    context: Context,
    alerts: Flow<AttentionAlert>,
    notificationsEnabled: Flow<Boolean>,
    isForeground: () -> Boolean,
    ledgerFile: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) { fun dispose() }
```

One collector on its own `CoroutineScope(SupervisorJob() + dispatcher)`, processing alerts sequentially:

1. **Dedupe first.** `AlertLedger` (internal, same file) holds SHA-256 hex digests of `(serverId, conversationId, kind, key)` — length-prefixed fields so no two tuples collide by concatenation. A digest already present → drop. New digest → record and persist (bounded to the newest 512, one digest per line, written to a temp file then renamed). Loaded lazily on the first alert. Persisting is what makes "at most once" hold across a reconnect's replay, repeated `modal_shown`, a new wake window and process death.
2. **Then gate.** Foreground (`isForeground()`) → skip. `notificationsEnabled.first()` false → skip. `POST_NOTIFICATIONS` not granted → skip. Because dedupe precedes the gates, an alert suppressed by a gate is still spent: a prompt seen in the foreground, or arriving while alerts were off, never alerts later.
3. **Post.** Channel `attention` (created idempotently before each post; name and description from strings). Notification tag = hex digest of `(serverId, conversationId)`, id `0` — one notification per conversation per host, so the same conversation id on two hosts gives two notifications, and a later alert for a conversation replaces its earlier one. Text is fixed copy from `strings.xml` — `notification_turn_completed` / `notification_prompt` — with the app name as title; no conversation name, no daemon text, no FCM field. `setAutoCancel(true)`, small icon `ic_pyry_logo`.
4. **Tap intent.** `NotificationTap` (object, same file): `ACTION_OPEN_CONVERSATION` plus `EXTRA_SERVER_ID` / `EXTRA_CONVERSATION_ID`, explicit component `MainActivity`, `Intent.setIdentifier(tag)` so each conversation's `PendingIntent` is distinct, flags `NEW_TASK | CLEAR_TASK`; `PendingIntent.getActivity(..., FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)`. `NotificationTap.target(intent): HostConversationTarget?` parses it back: matching action, both extras non-blank strings of at most `MAX_TAP_ID_CHARS` (256), else `null`.

Wiring in `appModule`, next to the driver:

```kotlin
single(createdAtStart = true) { AttentionNotifier(androidContext(), get<HostConversationSource>().alerts, get<AppPreferences>().notificationsEnabled, { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(STARTED) }, File(androidContext().noBackupFilesDir, "attention_alerts")) } onClose { it?.dispose() }
```

`createdAtStart` because a push can start the process with no activity. In the fake-repository graph (Robolectric, demo) the source emits nothing, so it stays inert.

### 3. Tap navigation — `MainActivity`

- `onCreate` reads `NotificationTap.target(intent)` only when `savedInstanceState == null` (a rotation must not re-navigate) and passes it to `PyryNavHost(openTarget = …)` only when the start destination is `CHANNEL_LIST` (an unpaired phone ignores it).
- `PyryNavHost` gains `openTarget: HostConversationTarget? = null`. A `LaunchedEffect(openTarget)` navigates `Routes.thread(target)` on top of `CHANNEL_LIST` only if `ThreadDestinationFactory.isSavedHost(serverId)` (the store, not the live registry); otherwise it logs `event=notification_tap_rejected code=unknown_host` and stays on the list. `HostDestination` re-checks as before.
- A deleted conversation opens the thread's existing unknown-conversation state, and Back (app bar or system) lands on the `CHANNEL_LIST` that is always beneath it — a usable list.
- The tap path never sends a command and never touches a modal: it only navigates.

### 4. Permission prompt — `MainActivity` + `AppPreferences`

- `AppPreferences.notificationPermissionAsked: Flow<Boolean>` (default `false`) and `suspend fun setNotificationPermissionAsked()`.
- A private `rememberNotificationPermissionRequest(): () -> Unit` in `MainActivity` wraps `rememberLauncherForActivityResult(RequestPermission())`; invoking it marks asked and launches only when the permission is not already granted. The result callback does nothing: denial keeps the saved preference and changes nothing else.
- Settings route: `onTogglePushNotifications = { enabled -> vm.onTogglePushNotifications(enabled); if (enabled) request() }`.
- Channel-list route: `LaunchedEffect(Unit)` asks once when `shouldAskNotificationPermission(enabled, granted, asked)` — `enabled && !granted && !asked` — holds.

## State + concurrency model

- `alerts` is emitted under `HostConversationSource`'s monitor with `tryEmit`; the flow is `MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)`. The notifier subscribes in its constructor at `startKoin`, before any host can connect.
- The notifier's scope is app-lifetime, cancelled by `dispose()` (Koin `onClose`). The ledger is touched only by the single collector, so it needs no lock. Disk I/O on `Dispatchers.IO`.
- Foreground is read from `ProcessLifecycleOwner`'s current state at alert time.

## Error handling

- Ledger read failure → start empty (logged `event=attention_alert_ledger outcome=read_failed`); write failure → keep in memory, log `outcome=write_failed`. Neither throws into the collector.
- `NotificationManagerCompat.notify` guarded by the permission check; a `SecurityException` is caught and logged.
- Malformed tap extras → `null` target → channel list.

## Logging

Content-free, through `RelayLog`: `event=attention_alert outcome=posted|duplicate|foreground|disabled|no_permission kind=turn|prompt`, `event=notification_tap_accepted`, `event=notification_tap_rejected code=unknown_host|malformed`. No ids, no digests, no text.

## Testing strategy

- `app/src/test/.../di/HostConversationSourceAttentionTest.kt` (extend): a newly completed turn emits one `TurnCompleted`; a re-delivered turn emits nothing; an open modal emits one `Prompt`, the same modal re-published emits nothing; a batch emits one `Prompt`; the same conversation id on two hosts emits two alerts with different `serverId`.
- `app/src/test/.../notifications/AttentionNotifierTest.kt` (new, Robolectric): posts one notification with fixed copy when backgrounded + enabled + granted; nothing when foreground, disabled or permission denied; the same alert twice posts once; a fresh notifier over the same ledger file drops a replayed alert (process death); two hosts sharing a conversation id post two notifications; the content intent targets `MainActivity` with the host and conversation extras and is immutable; `NotificationTap.target` rejects wrong action, blank and oversized extras.
- `app/src/test/.../data/preferences/` — `notificationPermissionAsked` defaults `false` and persists (beside the existing preference tests).
- `app/src/sharedTest/.../notifications/NotificationTapNavigationTest.kt` (new, Robolectric, `PyryNavHost` on the production graph per `SettingsNavigationTest`): a saved host's target opens `Routes.thread` and Back lands on `channel_list`; an unsaved host's target stays on `channel_list`.
- No rung-3 scenario: nothing sends FCM, and the live background proof is #955, blocked on the sender.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/push-messaging-service.md` — fold in the publisher (`AttentionNotifier`), its gates (dedupe-then-gate order, foreground, `notificationsEnabled`, `POST_NOTIFICATIONS`), the ledger, and the tap route.
- `docs/knowledge/features/app-preferences.md` § `notificationsEnabled` — remove the "write-live but read-dead" note; add `notificationPermissionAsked`.

## Open questions

- Whether Robolectric's `ShadowNotificationManager` reports the posted notification's tag, so the two-hosts test can assert distinct tags rather than a count.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — alerts derive only from `HostConversationSource`, which is fed by authenticated Noise sessions; `PyryMessagingService.onMessageReceived` is untouched and still reads no field. Daemon-authored ids (`conversationId`, `turnId`, `modalId`, `questionBatchId`) are used only as equality keys and hashed into the ledger; the notification text is fixed `strings.xml` copy, so no daemon text reaches the notification shade.
- [Tokens] No findings — nothing secret is created, stored or logged. The tap intent carries a server id and a conversation id, neither a credential.
- [File / storage] No findings — the ledger lives in `noBackupFilesDir` (never backed up), at a fixed name; no untrusted input forms a path. It stores SHA-256 digests only, is bounded to 512 lines, and is written temp-then-rename so a kill mid-write leaves the old file.
- [Android attack surface] SHOULD FIX (addressed in design) — `MainActivity` is exported, so any app can send `ACTION_OPEN_CONVERSATION` with forged extras. `NotificationTap.target` validates action, type, non-blank and length; `PyryNavHost` accepts only a saved host via `isSavedHost`; the tap only navigates, so a forged extra can at worst open a thread the user could open from the list. The `PendingIntent` is `FLAG_IMMUTABLE` with an explicit component. No new exported component, intent filter or deep link.
- [Crypto] No findings — `MessageDigest.getInstance("SHA-256")` for non-secret dedupe keys only.
- [Network & I/O] No findings — no network path changes.
- [Logs] No findings — log lines carry static outcome codes only; no ids, digests or text.
- [Concurrency] No findings — `tryEmit` under the existing monitor adds no lock order; the notifier's single collector owns the ledger; its scope is cancelled on Koin close.
- [Threat model] OUT OF SCOPE — a lock-screen leak of conversation content is avoided by the fixed copy; per-conversation naming in notifications would need a separate decision. A future FCM sender contract (#955) must keep push payload-free.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

### 2026-09-24 — during implementation

- **Blank-conversation batches.** `promptKeys` drops a question batch with a blank `conversationId`, as it already dropped a blank-conversation modal: its tap could never route, since `NotificationTap.target` refuses a blank id. Covered in `aPromptAlertsOncePerModalOrBatchAndABlankConversationPromptAlertsNothing`.
- **Where the prompt is proven.** `rememberNotificationPermissionRequest` is `internal` (not private) so `app/src/test/.../NotificationPermissionPromptTest.kt` can drive it under Robolectric with `createAndroidComposeRule<ComponentActivity>()` and assert the request through `ShadowActivity.lastRequestedPermission`; it also pins the `shouldAskNotificationPermission` truth table. It stays out of `app/src/sharedTest`: Robolectric shadows do not exist on the device, and a device run would raise the real system dialog. For the same reason `NotificationTapNavigationTest` seeds `notification_permission_asked = true`.
- **Open question resolved.** The two-hosts notifier test asserts the two posted notifications' tap targets (`NotificationTap.target` over each `contentIntent`), which proves distinct per-host notifications without depending on `ShadowNotificationManager` exposing tags.

### 2026-09-24 — rework (verifier UI gate)

- **Device tests and the channel-list prompt.** The verifier's `ui` gate failed five device cases (`StartupWorkspaceMigrationTest`, `PairCodeScreenTest#cancelToolbarAndAndroidBackReturnToCallerWithoutSaving`): on a fresh device the channel list's one-time prompt raised `GrantPermissionsActivity` over the test activity, leaving no Compose root. The product prompt is unchanged. Device tests that reach the channel list now call `grantNotificationPermission()` (new, `app/src/androidTest/.../NotificationPermission.kt`, a `uiAutomation` grant like the existing `CAMERA` one) before it composes: from `@Before` in `StartupWorkspaceMigrationTest`, inside the one `PairCodeScreenTest` case, and from the class `init` block in `InteractiveStreamE2ETest` and `DeterministicInteractiveStreamE2ETest`, whose compose rule launches `MainActivity` before `@Before`. A grant also skips the `notificationPermissionAsked` write, so `StartupWorkspaceMigrationTest`'s exact update counts hold. The shared `SettingsNavigationTest`, `ArchiveNavigationTest` and `LiteralScreenNavigationTest` seed `notification_permission_asked = true`, as `NotificationTapNavigationTest` does, so the in-depth device run of `app/src/sharedTest` does not hit the dialog either.

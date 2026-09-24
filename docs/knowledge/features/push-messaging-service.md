# Push messaging service — FCM token capture and the push trust boundary

`de.pyryco.mobile.push` is the Android half of push ([#361](../codebase/361.md)). It captures and
persists each FCM token and turns a received push into a payload-free
[`LifecycleConnectionDriver.onPushWake()`](lifecycle-connection-driver.md) call. There is no push
sender: the daemon stores tokens (`internal/relay/handlers/register_push_token.go`) but nothing in
pyrycode or pyrycode-relay sends FCM yet, so a push cannot wake the app in production. This package is
in place and tested against a synthetic message; it has never received a live one.

[§ Attention alerts and the tap route](#attention-alerts-and-the-tap-route-685) below (#685) is the
alert *publisher* this wake feeds: it needs no push to fire, only a saved host that is still connected
when the app backgrounds, so it is proven complete against fakes and Robolectric even though no push
has ever reached the phone. [#955](https://github.com/pyrycode/pyrycode-mobile/issues/955) is the
still-blocked live end-to-end proof.

## Why the SDK is unconditional but push can still be off

`firebase-messaging` (via the Firebase BoM, no `firebase-analytics`) is an unconditional
`implementation` dependency in `app/build.gradle.kts`. Only the Google Services Gradle plugin stays
conditional on `app/google-services.json` being present ([#579](../codebase/579.md)). Without the
file no `FirebaseApp` exists; the SDK's init `ContentProvider` logs and returns; `PyryMessagingService`
is never created by the system, and no token or message is ever delivered. Push is simply off — the
app never calls Firebase, so it never throws for lacking credentials. `./gradlew assembleDebug test`
passes both with the file present and with it moved aside.

## The two collaborators

```kotlin
// push/PyryMessagingService.kt
class PyryMessagingService : FirebaseMessagingService(), KoinComponent {
    override fun onNewToken(token: String)               // → get<PushTokenSink>().onNewToken(token)
    override fun onMessageReceived(message: RemoteMessage) // → get<LifecycleConnectionDriver>().onPushWake()
}

class PushTokenSink(
    private val preferences: AppPreferences,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun onNewToken(token: String)   // launches AppPreferences.setPushToken on its own app-lifetime scope
    fun dispose()                   // cancels the scope; Koin onClose
}
```

The system constructs `FirebaseMessagingService` outside Compose and outside Koin's own object
graph, so both collaborators are resolved via `KoinComponent`/`get()` rather than constructor
injection — the one class in the app that reaches into the service locator instead of being wired.

**`onMessageReceived` never reads `message`.** No `data`, no `notification`, no `from`, no message
id. No sender contract exists yet, so every field is untrusted and permanently ignored: a push can
wake the saved hosts and nothing else — it can never become a command, pairing data, a host
selection or UI text. `PyryMessagingServiceTest` proves this with a `RemoteMessage` carrying a
pairing-shaped data value, a command-shaped data value (`"rm -rf …"`), and a `gcm.n.*`
notification-shaped key side by side: the only observed effect is one `connect()` on a recording
controller, and the stored push token is unchanged.

**Only a data message reaches `onMessageReceived` while the app is backgrounded.** A notification
message is delivered to the system tray by the OS instead and never reaches this service. The
future sender must send a **data** message for a background wake to work at all — recorded here and
in the PR body since there is no daemon/relay code yet to enforce it.

**`PushTokenSink` exists because the write must outlive the service instance.** `FirebaseMessagingService`
may be destroyed as soon as `onNewToken` returns, so the write runs on the sink's own
`CoroutineScope(SupervisorJob() + Dispatchers.IO)`, a Koin `single` cancelled only on `dispose()`
(app-lifetime, not service-lifetime). A DataStore `IOException` is caught and logged content-free
(`event=push_token_stored outcome=io_failure`); nothing downstream depends on that particular
write succeeding — the next rotation, or the [#365](relay-repository-coordinator-seams-and-passthroughs.md#connect-time-fcm-push-token-re-registration-365)
path on the next connect, sends whatever is currently stored. The service never reads the current
token at startup; `onNewToken` alone covers both the first token and every later rotation.

Manifest: `<service android:name=".push.PyryMessagingService" android:exported="false">` with the
`com.google.firebase.MESSAGING_EVENT` intent filter. `exported="false"` means only the in-process
Firebase SDK can deliver to it — no external app can invoke it directly.

## Logging

`event=push_token_stored outcome=success|io_failure` and `event=push_wake`, both through
debug-gated `RelayLog`. The token itself, and every `RemoteMessage` field, appear in no log line.

## Wiring

```kotlin
// di/AppModule.kt
single { PushTokenSink(get()) } onClose { it?.dispose() }
```

`LifecycleConnectionDriver` is already a resolvable Koin singleton created at `startKoin`
([#302](lifecycle-connection-driver.md)); the service resolves it directly, adding no new binding.

## Testing

`PyryMessagingServiceTest` runs under Robolectric in `app/src/test` (not `app/src/androidTest`) —
it drives an Android `Service` class, not a Compose screen, so it does not belong in
`app/src/sharedTest` either. Two lessons from getting it running:

- **Robolectric's `Robolectric.buildService(PyryMessagingService::class.java).create()` constructs
  the service without an initialised `FirebaseApp`.** No fake or stub `FirebaseApp` is needed for
  this test.
- **The test message is built from a `Bundle`, not `RemoteMessage.Builder`.** A `Bundle` lets the
  test carry `gcm.n.*` notification-shaped keys next to the pairing- and command-shaped data
  entries in one message, which is what the "never reads the payload" assertion needs to cover.

`LifecycleConnectionDriverTest` covers the wake-window behaviour the service triggers; see
[Lifecycle driver § Testing](lifecycle-connection-driver.md#testing).

## Attention alerts and the tap route (#685)

`notifications/AttentionNotifier.kt` posts an Android notification for each `AttentionAlert` that
[`HostConversationSource.alerts`](dependency-injection-host-conversation-source.md#attention-alerts-685)
emits — a turn the attention fold counted for the first time, or a prompt newly outstanding, on any
saved host. It needs no push: the source emits from the same live-event and modal/batch collectors that
already drive `attention`, so an alert can fire the moment the app backgrounds while a host is still
connected. That window is narrow in production today — `LifecycleConnectionDriver` closes every host on
`onStop`, and only a push wake reopens one, and nothing sends push yet (above) — which is why #955,
the live end-to-end proof, stays blocked. This package's own tests (`AttentionNotifierTest`,
`HostConversationSourceAttentionTest`) prove the pipeline with fakes instead.

**Dedupe runs before the gates, not after.** `AttentionNotifier.handle` records an alert's digest in
`AlertLedger` first; only a digest new to the ledger is even considered for the foreground, switch and
permission checks below. So an alert that arrives while the app is foregrounded, or while alerts are
off, is spent — it can never post later once background/enabled/permission line up. The ledger holds
`SHA-256` digests over length-prefixed `(serverId, conversationId, kind, key)`, one per line in
`noBackupFilesDir/attention_alerts`, capped at the newest 512, written temp-then-rename. This is what
holds "at most once" across a reconnect's replay, a repeated `modal_shown` frame, a new wake window and
process death — the same digests survive a process restart because the file does.

**The gates, checked in this order, each read fresh (none is cached):** not foregrounded
(`ProcessLifecycleOwner`'s state `< STARTED`) → `AppPreferences.notificationsEnabled` → `POST_NOTIFICATIONS`
granted.

**The notification itself** is fixed `strings.xml` copy only (`notification_turn_completed` /
`notification_prompt`, title = app name) — no conversation name, no daemon text, no push-message field
ever reaches it. Tag = `SHA-256(serverId, conversationId)`, id `0`: one notification per conversation per
host, so the same conversation id on two hosts posts two notifications, and a later alert for the same
conversation replaces the earlier one instead of stacking.

**The tap** carries only a server id and a conversation id, via `NotificationTap`'s explicit-component,
`FLAG_IMMUTABLE` `PendingIntent` naming `MainActivity` and `ACTION_OPEN_CONVERSATION`. `MainActivity` is
exported, so `NotificationTap.target(intent)` treats every extra as untrusted: wrong action, missing
extras, a blank id, or an id over `MAX_TAP_ID_CHARS` (256) all parse to `null`. `PyryNavHost` then accepts
the parsed target only when `ThreadDestinationFactory.isSavedHost(serverId)` — the saved-host store, not
the live connection registry — and opens `Routes.thread(target)` **above** `CHANNEL_LIST`, so Back (or a
conversation deleted since the alert) always lands on a usable list. The read happens once, in
`MainActivity.onCreate`, only when `savedInstanceState == null` — a rotation does not re-navigate. The
tap only navigates: it never sends a command or answers a prompt. See
[Navigation § What it does](navigation.md#what-it-does) for the route itself.

**The permission prompt** is Android's own `POST_NOTIFICATIONS` request, asked from two places sharing
`MainActivity.rememberNotificationPermissionRequest`: the Settings switch (turning
"Push notifications when claude responds" **on** — see [Settings screen](settings-screen.md#what-it-does)),
and once from the channel list, gated by `shouldAskNotificationPermission(enabled, granted, asked)` —
true only while the switch is on, the permission isn't granted yet, and
[`AppPreferences.notificationPermissionAsked`](app-preferences.md) is
still false. Both places mark that preference before launching the system dialog, whether or not the
user grants it; a denial keeps the saved switch value and changes nothing about foreground use. The
switch defaults on, so without the channel-list ask most users would never see the prompt.

**Two points the verifier flagged as non-blocking, still open:** a malformed tap intent is rejected but
not logged — the plan's `code=malformed` log line was never wired, only `code=unknown_host` is; and the
`POST_NOTIFICATIONS` grant check is written twice, once in `MainActivity.notificationsPermitted` and
once in `AttentionNotifier.permitted`, rather than shared. Neither has caused an observed failure.

Wiring (`di/AppModule.kt`, next to the lifecycle driver):

```kotlin
single(createdAtStart = true) {
    AttentionNotifier(
        context = androidContext(),
        alerts = get<HostConversationSource>().alerts,
        notificationsEnabled = get<AppPreferences>().notificationsEnabled,
        isForeground = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
        ledgerFile = File(androidContext().noBackupFilesDir, "attention_alerts"),
    )
} onClose { it?.dispose() }
```

`createdAtStart` for the same reason as the lifecycle driver above: a push can start the process with no
activity, and the notifier must already be collecting `alerts` before a wake's hosts connect. In the
fake-repository graph (Robolectric, demo) `HostConversationSource` emits nothing on `alerts`, so the
notifier stays inert without any special case.

Manifest: `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` (new; Android 13+
requires the runtime prompt above regardless of the manifest entry).

### Logging (#685)

`event=attention_alert outcome=posted|duplicate|foreground|disabled|no_permission kind=turn|prompt`,
`event=notification_tap_accepted`, `event=notification_tap_rejected code=unknown_host`,
`event=notification_permission_answered granted=…`, `event=attention_alert_ledger outcome=read_failed|write_failed`.
No id, digest or notification text appears in any of these lines.

### Testing (#685)

- `HostConversationSourceAttentionTest` (`app/src/test`) proves the alert-emission rules in
  [dependency-injection-host-conversation-source.md § Attention alerts](dependency-injection-host-conversation-source.md#attention-alerts-685):
  once per counted turn, once per prompt key, two hosts sharing a conversation id alerting twice.
- `AttentionNotifierTest` (`app/src/test`, Robolectric) proves the notifier end to end against a real
  `NotificationManager` shadow: fixed copy, the dedupe-before-gates order, a fresh notifier over the same
  ledger file dropping a replayed alert (process death), and the posted `contentIntent`'s parsed target.
- `NotificationTapNavigationTest` (`app/src/sharedTest`) drives `PyryNavHost` on the production Koin
  graph, per the `SettingsNavigationTest` pattern: a saved host's target opens the thread above
  `CHANNEL_LIST`; an unsaved host's target stays on `CHANNEL_LIST`.
- `NotificationPermissionPromptTest` stays in `app/src/test`, not `app/src/sharedTest`, even though it
  drives a Composable (`rememberNotificationPermissionRequest`, made `internal` for this): a
  `sharedTest`/device run would raise Android's real permission dialog, which Robolectric's shadow
  intercepts instead. `NotificationTapNavigationTest` and the shared screen-navigation suites
  (`SettingsNavigationTest`, `ArchiveNavigationTest`, `LiteralScreenNavigationTest`) all seed
  `notification_permission_asked = true` in setup so the one-time channel-list prompt never fires mid-test.
- Device tests that reach the channel list on a fresh emulator must grant `POST_NOTIFICATIONS` before it
  composes, or the system permission dialog covers the Compose root and every assertion after it fails
  with "No compose hierarchies found". `app/src/androidTest/.../NotificationPermission.kt`'s
  `grantNotificationPermission()` (a `uiAutomation` grant, like the existing `CAMERA` one) is called from
  `StartupWorkspaceMigrationTest`'s `@Before`, from the one `PairCodeScreenTest` case that reaches the
  list, and from the `init` block of both e2e test classes (their compose rule opens `MainActivity`
  before `@Before` runs). A granted permission skips the `notificationPermissionAsked` write, so
  `StartupWorkspaceMigrationTest`'s exact preference-write counts still hold.

## Related

- [Lifecycle connection driver](lifecycle-connection-driver.md) — owns the background wake window
  `onPushWake()` opens and the guarantee that a wake connects every saved host and no others.
- [Relay repository coordinator § Connect-time FCM push-token re-registration](relay-repository-coordinator-seams-and-passthroughs.md#connect-time-fcm-push-token-re-registration-365) —
  the #365 path that sends the stored token on `Open`, and the #361 rotation that keeps it current
  on every already-open host.
- [App preferences § Phase 4 FCM push key](app-preferences.md) — `pushToken`/`setPushToken`, the
  DataStore key this package writes and the coordinator reads.
- [Dependency injection § Attention alerts](dependency-injection-host-conversation-source.md#attention-alerts-685) —
  where `AttentionNotifier`'s input, `HostConversationSource.alerts`, is emitted and deduplicated per
  turn/prompt before this package's own consumer-side dedupe ledger.
- [App preferences § `notificationPermissionAsked`](app-preferences.md) —
  the one-ask-ever preference `AttentionNotifier`'s permission prompt reads and writes.
- [Navigation](navigation.md#what-it-does) — the `conversation_thread` route a notification tap opens.
- [Settings screen](settings-screen.md#what-it-does) — the existing switch this ticket's permission
  prompt piggybacks on; no new row was added.
- Spec: `docs/specs/architecture/685-mobile-attention-alerts.md` (§ Design, § Security review — verdict
  PASS, § Revisions for the blank-conversation-prompt fix and the two rework rounds' device-test fixes).
- Spec: `docs/specs/architecture/361-fcm-push-wake.md` (§ Design, § Security review — verdict PASS,
  § Revisions for the two open questions above).
- README `### Firebase` — where `app/google-services.json` and the conditional plugin are recorded.

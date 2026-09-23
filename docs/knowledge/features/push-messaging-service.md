# Push messaging service — FCM token capture and the push trust boundary

`de.pyryco.mobile.push` is the Android half of push ([#361](../codebase/361.md)). It captures and
persists each FCM token and turns a received push into a payload-free
[`LifecycleConnectionDriver.onPushWake()`](lifecycle-connection-driver.md) call. There is no push
sender: the daemon stores tokens (`internal/relay/handlers/register_push_token.go`) but nothing in
pyrycode or pyrycode-relay sends FCM yet, so notifications do not work end to end. This package is
in place and tested against a synthetic message; it has never received a live one.

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

## Related

- [Lifecycle connection driver](lifecycle-connection-driver.md) — owns the background wake window
  `onPushWake()` opens and the guarantee that a wake connects every saved host and no others.
- [Relay repository coordinator § Connect-time FCM push-token re-registration](relay-repository-coordinator-seams-and-passthroughs.md#connect-time-fcm-push-token-re-registration-365) —
  the #365 path that sends the stored token on `Open`, and the #361 rotation that keeps it current
  on every already-open host.
- [App preferences § Phase 4 FCM push key](app-preferences.md) — `pushToken`/`setPushToken`, the
  DataStore key this package writes and the coordinator reads.
- Spec: `docs/specs/architecture/361-fcm-push-wake.md` (§ Design, § Security review — verdict PASS,
  § Revisions for the two open questions above).
- README `### Firebase` — where `app/google-services.json` and the conditional plugin are recorded.

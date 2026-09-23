# #361 — Register mobile push tokens and wake the saved hosts

## Files read

- `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt` → `LifecycleConnectionDriver` — stateless today; `onPushWake()` forwards to `connect()`. Gains the background wake window.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `RelayConnectionRegistry.connect` / `close` — `connect()` returns early while its `foreground` flag is set and otherwise dials every saved host; `close()` is the resumable background teardown. Both `@Synchronized`; the registry never calls back into the driver, so driver-lock → registry-lock is the only order.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `reregisterPushTokenOnOpen`, constructor `pushToken` supplier — the #365 one-shot registration on `Open`. Becomes a per-connection observation of the token stream.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt` → `RelayConnectionFactory` / `RelayConnectionBundle` — thread the token source into every bundle's coordinator.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `appModule` — wires `pushToken` from `AppPreferences` and constructs the driver (`createdAtStart`).
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `pushToken` (`Flow<String?>`), `setPushToken` — the persistence the service writes.
- `app/src/test/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriverTest.kt` — recording controller + `LifecycleRegistry.createUnsafe` harness to extend.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` → `newEnv`, the #365 tests — fake pump harness for the rotation test.
- `app/src/test/java/de/pyryco/mobile/robolectric/RobolectricTestApp.kt` — Robolectric starts the real `appModule`; the service test overrides the driver binding.
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — #579's conditional google-services plugin; no Firebase SDK yet.
- `docs/knowledge/features/lifecycle-connection-driver.md` § Edge cases & limitations — assigns the push wake's background lifetime to this ticket; `connect()` stays the wake call.

Overlap: `AppModule.kt` is also touched by in-flight #686, #883, #932 (different blocks); edits here stay local to the push-token and driver definitions.

## Design source

N/A — no UI. The ticket adds no screen, row or visual state.

## Context

The daemon stores push tokens but nothing sends FCM yet (pyrycode `8591b0b1`), so this ticket delivers the Android half only: obtain and persist the token, keep every open host's registration current on rotation, and make a received push wake the saved hosts for a bounded background window. Notifications cannot work end to end until a daemon/relay sender exists.

## Design

### Dependencies (build config)

- `gradle/libs.versions.toml`: `firebaseBom = "34.19.0"`; libraries `firebase-bom` (`com.google.firebase:firebase-bom`) and `firebase-messaging` (`com.google.firebase:firebase-messaging`, version from the BoM). No analytics.
- `app/build.gradle.kts`: `implementation(platform(libs.firebase.bom))`, `implementation(libs.firebase.messaging)`. The SDK is unconditional; only the google-services plugin stays conditional (#579). Without the JSON no `FirebaseApp` exists, the SDK's init provider logs and returns, and no token or message is ever delivered — push is simply off.

### `push/PyryMessagingService.kt` (new)

```kotlin
class PyryMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String)              // → get<PushTokenSink>().onNewToken(token)
    override fun onMessageReceived(message: RemoteMessage) // → get<LifecycleConnectionDriver>().onPushWake()
}
class PushTokenSink(preferences: AppPreferences, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    fun onNewToken(token: String)   // launch setPushToken on its own app-lifetime scope
    fun dispose()                   // cancels the scope (Koin onClose)
}
```

- The service resolves both collaborators from Koin (`KoinComponent`, created by the system outside Compose). It makes no Firebase call of its own and never reads the current token at startup — `onNewToken` covers the first token and every rotation.
- `onMessageReceived` never touches `message` (no `data`, `notification`, `from`, id). The payload therefore can never become a command, pairing data, host selection or UI text.
- `PushTokenSink` exists because the write must outlive the service instance: `FirebaseMessagingService` may be destroyed as soon as the callback returns. Its scope is app-lifetime (Koin `single`, cancelled on close). An `IOException` from DataStore is caught and logged content-free; the next rotation or app start does not depend on it (#365 simply sends nothing).
- Logs: `event=push_token_stored outcome=success|io_failure`, `event=push_wake`. Never the token, never message fields.
- Manifest: `<service android:name=".push.PyryMessagingService" android:exported="false">` with the `com.google.firebase.MESSAGING_EVENT` intent filter.

### Rotation on open hosts — coordinator takes a token stream

`RelayRepositoryCoordinator(pushToken: suspend () -> String?)` becomes `pushTokens: Flow<String?> = flowOf(null)`. `reregisterPushTokenOnOpen` keeps its name and its wait for the first `Open`/`Closed`; on `Open` it collects `pushTokens.filterNotNull().distinctUntilChanged()` and sends each token through `registerPushToken`, catching non-cancellation failures per token so one failure does not end the collection. It runs on the connection's child scope, so a drop cancels it and the next connection starts a fresh one that sends the then-current token (the #365 path for an offline host). `RelayConnectionFactory`/`RelayConnectionBundle` pass the same `Flow<String?>` through; `AppModule` passes `get<AppPreferences>().pushToken`.

Result: a new token persisted by the sink re-registers on every host whose connection is open, without a reconnect; hosts that are offline pick it up on their next `Open`.

### Background wake window — driver

```kotlin
class LifecycleConnectionDriver(
    controller: RelayConnectionController,
    lifecycle: Lifecycle,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val wakeWindow: Duration = PUSH_WAKE_WINDOW, // 30.seconds
) : DefaultLifecycleObserver {
    fun onPushWake()   // backgrounded & no window open → connect() + start window; otherwise no-op
    fun dispose()      // cancels the window scope (Koin onClose)
}
```

- The driver tracks its own `foreground` flag from `onStart`/`onStop` (a process started by a push never reaches `ON_START`, so it is background by construction) and one `wakeJob`. All state changes are `@Synchronized` — `onPushWake` arrives on an FCM worker thread, lifecycle callbacks on main, the window expiry on the dispatcher.
- `onPushWake`: foreground → nothing. A window already open → nothing (no duplicate connect, window not extended). Otherwise `controller.connect()` and launch `delay(wakeWindow)` then `expireWake(job)`.
- `expireWake` closes only if this job is still the current window and the app is still background, then clears the window. After expiry a later push opens a fresh window.
- `onStart` cancels and clears any window, then `connect()` (a no-op for the registry if the wake already dialed). The foreground now owns the connections until the next `onStop`.
- `onStop` is unchanged (`close()`).

## State + concurrency model

- Driver: one `CoroutineScope(SupervisorJob() + dispatcher)` owned by the driver, cancelled by `dispose()` from Koin `onClose`. Only the window timer runs there. Lock order: driver → registry, never reverse.
- Sink: its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, cancelled by `dispose()`.
- Coordinator: token collection lives on the per-connection child scope, cancelled by `teardownActive`. The collector is launched off the non-suspending `onConnection` critical section, as today.

## Error handling

- DataStore write failure → caught `IOException`, logged content-free, token not stored.
- Registration failure on one token → swallowed as in #365 (token never logged); collection continues.
- Firebase not initialised → the service is never created and no token arrives; our code makes no Firebase call that could throw.

## Testing strategy

- `LifecycleConnectionDriverTest` (JVM, virtual time via `StandardTestDispatcher(testScheduler)`):
  - background wake → `[connect]`, then after 30 s → `[connect, close]`; nothing before the window ends.
  - foreground wake → no extra call.
  - repeated wakes inside the window → one connect; close still at 30 s after the first wake (not extended).
  - foreground during the window → no close at the window's end; the next `onStop` closes.
  - wake after the window expired → a new connect/close cycle.
  - existing tests keep passing (existing `pushWakeWhileBackgrounded` moves onto a test dispatcher).
- `RelayRepositoryCoordinatorTest`:
  - existing #365 tests move from `pushToken = { … }` to `pushTokens = flowOf(…)`.
  - rotation: three coordinators share one `MutableStateFlow` token; two reach `Open`, one stays offline. Rotating sends exactly one register with the new token on each open pump and nothing for the offline one; connecting the offline one afterwards registers the new token once.
- `PyryMessagingServiceTest` (Robolectric, `app/src/test`, since it drives an Android `Service` class, not a screen): overrides the driver with one over a recording controller and a background `LifecycleRegistry`.
  - a `RemoteMessage` with data `{pair: <pyrycode pairing-shaped URL>, command: "rm -rf …"}` and a notification-shaped key yields exactly `[connect]` on the controller and leaves the stored push token unchanged.
  - `onNewToken("tok")` persists through `AppPreferences.pushToken`.
- Build: `assembleDebug` and scoped tests with `app/google-services.json` present and moved aside.

No rung-3 scenario: there is no push sender, so no operator-facing flow exists to exercise live; background delivery is proven by the unit/Robolectric tests above, and the PR states no live FCM message was delivered.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/lifecycle-connection-driver.md` § Edge cases & limitations: replace the "Targeted push wake and its background lifetime remain #361" entry with the shipped behaviour — all saved hosts are woken, the 30 s background window (not extended by repeat wakes, cancelled by foregrounding), and the payload is ignored because no sender contract exists.
- README `### Firebase`: push is enabled only when `app/google-services.json` is present; the wake must be a data message (a notification message goes to the tray while backgrounded and never reaches `onMessageReceived`); no daemon or relay sender exists yet.

## Open questions

- Does `RemoteMessage.Builder` still exist in firebase-messaging 25.x for the test? If not, construct the message from a `Bundle`.
- Does Robolectric tolerate constructing `FirebaseMessagingService` without an initialised `FirebaseApp`? If not, test the service's two overrides through `Robolectric.buildService` differently and record it here.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the only untrusted input is the FCM `RemoteMessage`; `PyryMessagingService.onMessageReceived` never dereferences it, so the boundary is a single method that discards the payload. The downstream seam `LifecycleConnectionDriver.onPushWake()` takes no parameters and `RelayConnectionController` carries no data, so no push-controlled value can reach pairing, host selection, commands or Compose. The Robolectric test asserts this with pairing- and command-shaped data.
- [Tokens] No findings for logging — the token appears in no log line (`PushTokenSink` logs outcome only; the coordinator's per-token catch logs nothing). OUT OF SCOPE — storage: the token stays in the existing `AppPreferences` DataStore chosen by #364. It is a delivery address, not a credential (sending requires the sender's Firebase credentials), so plaintext app-private storage is acceptable; the DataStore file is under `filesDir` and rides `allowBackup`, but a restored token is superseded by `onNewToken` on the new install. Revocation (daemon-side unregister) belongs to the future sender ticket.
- [File / storage] No findings — no path is built from input; the only write is a DataStore key.
- [Android attack surface] No findings — the service is `android:exported="false"`; only the Firebase SDK in-process delivers `MESSAGING_EVENT`. No activity, receiver, deep link or pending intent is added. Push wakes the connection and carries no content, per the checklist's push rule.
- [Crypto] No findings — no primitive touched; wake reconnects use the existing Noise stack.
- [Network & I/O] SHOULD FIX (accepted, noted) — whoever can send FCM to this app (holder of the project's sender credentials) can wake every paired host repeatedly; each window is bounded at 30 s and not extended, so a flood costs at most one open window at a time. Connections only go to already saved pairings.
- [Logs] No findings — `event=push_wake` and `event=push_token_stored outcome=…` only, through debug-gated `RelayLog`.
- [Concurrency] No findings — the window timer, lifecycle edges and FCM callbacks serialize on the driver lock; the expiry checks job identity and foreground state under that lock, so a stale timer cannot close a foregrounded app and repeated wakes cannot open a second window. Token collection is cancelled with its connection; the sink's scope outlives the service by design and is cancelled on Koin close.
- [Threat model] OUT OF SCOPE — the sender (daemon/relay FCM delivery) and any targeted single-host wake from a payload contract; to be defined by a future pyrycode/pyrycode-relay ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

- 2026-09-24 (build): both open questions are resolved, and the design is unchanged.
  - The service test builds its `RemoteMessage` from a `Bundle`. That lets it carry `gcm.n.*` notification keys next to the pairing- and command-shaped data.
  - Robolectric's `buildService(...).create()` constructs `FirebaseMessagingService` without an initialised `FirebaseApp`.
  - The driver keeps its existing no-logs contract, so the only push log line is the service's `event=push_wake`.

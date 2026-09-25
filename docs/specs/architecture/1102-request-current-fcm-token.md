# #1102 — Ask FCM for the current token instead of waiting only for `onNewToken`

## Files read

- `app/src/main/java/de/pyryco/mobile/push/PyryMessagingService.kt` → `PushTokenSink` — the one write path for the token; the new request stores through its `onNewToken`. `PyryMessagingService.onNewToken` stays the rotation path.
- `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt` → `LifecycleConnectionDriver` — the existing process-foreground observer. Left untouched: the refresher is a separate observer, so it cannot delay `connect()`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `createdAtStart` driver and notifier singles, and `single { PushTokenSink(get()) }` — the wiring pattern the refresher follows.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `pushToken`, `setPushToken` — the stored token the refresher checks before asking.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → `RelayLog.d/w`, `sink` — debug-gated content-free logging; `sink` is the seam the test uses to prove the token never reaches a log line.
- `app/src/test/java/de/pyryco/mobile/push/PyryMessagingServiceTest.kt` → `newToken_isPersisted_andDoesNotWake` — the `runTest` + temp-folder DataStore + same-scheduler `PushTokenSink` pattern (#953). A `first { predicate }` under a clock against a DataStore can park forever; tests read with a plain `first()` after `advanceUntilIdle()`.
- `app/src/test/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriverTest.kt` → `LifecycleRegistry.createUnsafe` as the lifecycle seam on plain JVM.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `awaitPushRegistered` — the fixed Play-services guess this ticket replaces.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` — the e2e process loads `appModule`, so the new `PushTokenSource` binding resolves there.
- `docs/knowledge/features/push-messaging-service.md` — "The service never reads the current token at startup"; the premise this ticket reverses.

In-flight overlap: `feature/1076` also edits `InteractiveStreamE2ETest.kt`, in other scenarios; this change is local to `awaitPushRegistered` and the companion constants.

## Context

The live gate saw both #955 push scenarios fail with no stored FCM token ten minutes after install, then pass on the same image. The app only stores a token when FCM calls `onNewToken`; if FCM's first mint fails on a freshly booted device, the app waits on FCM's own retry backoff and cannot be woken by push until it succeeds. A real phone is exposed too. The fix: the app asks for its current token itself, and the live test's timeout reports what that request returned.

No ADR needed; the documentation handoff below covers the reversed statement.

## Design

New file `push/PushTokenRefresher.kt`:

```kotlin
/** The current FCM registration token, asked for directly (#1102). */
interface PushTokenSource {
    /** False when no FirebaseApp exists; [currentToken] is then never called. */
    fun isAvailable(): Boolean
    /** The current token. Throws the request's failure. */
    suspend fun currentToken(): String
}

class FirebasePushTokenSource(context: Context) : PushTokenSource
// isAvailable = FirebaseApp.getApps(context).isNotEmpty()
// currentToken = FirebaseMessaging.getInstance().token awaited via suspendCancellableCoroutine
//   (no coroutines-play-services dependency; one Task listener)

class PushTokenRefresher(
    storedToken: Flow<String?>,
    source: PushTokenSource,
    sink: PushTokenSink,
    lifecycle: Lifecycle,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    requestTimeout: Duration = PUSH_TOKEN_REQUEST_TIMEOUT, // 60 s
) : DefaultLifecycleObserver {
    fun start()                         // addObserver(this) and refresh() — "at start", even in a push-started background process
    override fun onStart(owner: LifecycleOwner) // refresh() — each return to the foreground
    fun dispose()                       // cancels the scope; Koin onClose
}
```

`refresh()` (private) launches one job on the refresher's own scope, unless one is still in flight. The job, in order:

1. `source.isAvailable()` false → return. The guard runs before any `FirebaseMessaging.getInstance()` call, which throws without a `FirebaseApp`.
2. `storedToken.first()` non-empty → return. Rotation stays `onNewToken`'s job.
3. `withTimeoutOrNull(requestTimeout) { source.currentToken() }`. A token → `sink.onNewToken(token)`, the same write `PyryMessagingService.onNewToken` uses. An exception → logged by class name only; a timeout → logged. Nothing is stored and the next `onStart` retries. `CancellationException` is rethrown, not swallowed.

The timeout exists so a request that never completes cannot hold the in-flight guard and block every later retry.

Wiring in `AppModule`, beside the sink:

```kotlin
single<PushTokenSource> { FirebasePushTokenSource(androidContext()) }
single(createdAtStart = true) {
    PushTokenRefresher(get<AppPreferences>().pushToken, get(), get(), ProcessLifecycleOwner.get().lifecycle).also { it.start() }
} onClose { it?.dispose() }
```

`createdAtStart` for the driver's reason: a push can start the process with no activity.

E2E: when `awaitPushRegistered` times out, it resolves `PushTokenSource` from Koin and reports one of: no `FirebaseApp` in this process; the request's exception class and message; the request did not complete within its bound; or that a token did arrive but none was stored. The token is never included.

## State + concurrency model

- One `CoroutineScope(SupervisorJob() + dispatcher)` owned by the refresher, cancelled in `dispose()`.
- The in-flight job is guarded under the object's lock (`onStart` on main, the job on IO). `start()` and the first `ON_START` on a normal launch collapse to one request.
- `onStart` only launches; it never suspends or blocks, so `LifecycleConnectionDriver.onStart`'s `connect()` is unaffected. The two observers are independent.

## Error handling

Every failure of the request is caught in the job (except cancellation), logged content-free, and retried at the next foreground. The sink's own `IOException` handling covers a failed write. Nothing reaches UI.

Logging, through debug-gated `RelayLog`: `event=push_token_requested outcome=success|failure|timeout` with `error=<exception simple class name>` on failure. No log line when push is off or a token is already stored. The token appears in no log line.

## Testing strategy

`app/src/test/java/de/pyryco/mobile/push/PushTokenRefresherTest.kt`, plain JVM, `runTest` with a `StandardTestDispatcher(testScheduler)` for the refresher, the sink and a temp-folder DataStore (the #953 pattern), a `LifecycleRegistry.createUnsafe` lifecycle, and a fake `PushTokenSource` that counts calls and returns scripted results. `RelayLog.sink` captures log lines.

- `start()` in a background (CREATED) lifecycle requests once and a success stores the token; no log line contains it.
- A failure stores nothing; moving to STARTED requests again and that success is stored.
- A stored token → no request, at start or on foreground.
- `isAvailable()` false → `currentToken()` never called.
- `start()` then an immediate `ON_START` while the first request is pending → one request.

`app/src/test/java/de/pyryco/mobile/push/FirebasePushTokenSourceTest.kt`, Robolectric: without `google-services.json`'s `FirebaseApp`, `isAvailable()` is false.

The e2e message change is compiled by `compileDebugAndroidTestKotlin`; the two live push scenarios run in the post-verifier real-Claude gate (AC 3, `needs-real-claude`).

## Open questions

- Whether a `Task` listener needs an executor off main: `addOnCompleteListener` without an executor runs on main, which only resumes the continuation. Resolve in Phase B.

## Documentation handoff (pending, documentation stage)

- `docs/knowledge/features/push-messaging-service.md`: remove "The service never reads the current token at startup; `onNewToken` alone covers both the first token and every later rotation." Describe `PushTokenRefresher`: it asks FCM for the current token at start and on each foreground while none is stored and a `FirebaseApp` exists, stores through `PushTokenSink`, and retries a failure at the next foreground.
- `docs/knowledge/features/app-preferences.md`, the `pushToken` entry: name `PushTokenRefresher` as a writer alongside `PushTokenSink.onNewToken` (both write through the sink).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the only new input is the FCM SDK's own token, from the in-process Firebase client, stored through the existing `PushTokenSink` exactly as `onNewToken` stores it. No push payload, daemon frame or intent is read; `PyryMessagingService.onMessageReceived` is untouched.
- [Tokens] No new storage — the token lands in the same DataStore key via `PushTokenSink`; its at-rest storage was accepted in #361 and is unchanged here (OUT OF SCOPE to revisit). SHOULD FIX (verify in Phase B): the new `RelayLog` lines carry the exception's simple class name only, never its message or the token; the unit test asserts no captured log line contains the token.
- [Tokens — e2e message] The e2e failure message carries the exception's class and message. FCM failure messages are status codes (`SERVICE_NOT_AVAILABLE`, `AUTHENTICATION_FAILED`), not the token; a success reports only that a token arrived. Test-only code, instrumented builds only.
- [File / storage] No findings — no new file or path; DataStore write path unchanged.
- [Android surface] No findings — no new component, intent filter or pending intent; the refresher is an in-process lifecycle observer.
- [Crypto] Not applicable — no primitive is used or added.
- [Network & I/O] No findings — the request is the Firebase SDK's own call to Google; it is bounded by `requestTimeout` so a hung request cannot block later retries. It runs only while no token is stored, so a stored-token device makes no request; a failing device asks at most once per foreground plus once at process start.
- [Logs] No findings beyond the SHOULD FIX above — release builds emit nothing (`RelayLog` gate).
- [Concurrency] No findings — one owned scope cancelled on Koin close; an in-flight guard under the object lock; `CancellationException` rethrown; the foreground callback only launches and never blocks the driver's `connect()`.
- [Threat model] No new threat — a hostile relay or daemon cannot reach this path. Token theft from disk is unchanged from #361 (OUT OF SCOPE).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

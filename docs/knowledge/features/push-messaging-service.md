# Push messaging service — FCM token capture and the push trust boundary

`de.pyryco.mobile.push` is the Android half of push ([#361](../codebase/361.md)). It captures and
persists each FCM token and turns a received push into a payload-free
[`LifecycleConnectionDriver.onPushWake()`](lifecycle-connection-driver.md) call. The daemon stores
tokens (`internal/relay/handlers/register_push_token.go`) and, from pyry v0.23.0
(`cmd/pyry/push_wake.go`), asks the relay to wake a device that has no open session, on `TurnEnd` and on
a surfaced prompt, coalescing per device for 30 s; pyrycode-relay#130 sends the FCM data message and is
deployed. A push therefore does wake the app in production, on a device image with Play services.

[§ Attention alerts and the tap route](#attention-alerts-and-the-tap-route-685) below (#685) is the
alert *publisher* this wake feeds: it needs no push to fire, only a saved host that is still connected
when the app backgrounds, so it was proven complete against fakes and Robolectric before any push had
reached the phone. [#955](https://github.com/pyrycode/pyrycode-mobile/issues/955) is the live
end-to-end proof: a real FCM push from the production relay wakes the backgrounded app both for a turn
that ended while it was away and for a permission prompt that surfaced while it was away, and each posts
exactly one notification whose tap opens the right thread —
`InteractiveStreamE2ETest#interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread` and
`InteractiveStreamE2ETest#interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`, LIVE
only (see [docs/e2e-interactive-stream.md § Live mode](../../e2e-interactive-stream.md#live-mode-rung-3-live-relay)).
This package's own tests below still cover the same pipeline against fakes, for the deterministic gates.
Both scenarios flaked once in the #1076 gate run on a fresh install with no token stored ten minutes
after boot, then passed on the same image — diagnosed as FCM's first mint failing with no
`PushTokenRefresher` (below, #1102) yet in place to ask again; `awaitPushRegistered`'s timeout message
now reports an in-process token request's own outcome instead of guessing at Play services.

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
path on the next connect, sends whatever is currently stored.

**[#1102](https://github.com/pyrycode/pyrycode-mobile/issues/1102) added a second writer: `PushTokenRefresher` asks FCM for the current token itself, rather than
wait only for `onNewToken`.** `onNewToken` alone left a phone unreachable by push whenever FCM's first
mint failed on a freshly booted device — the app then waited on FCM's own retry backoff with no token
stored. `PushTokenRefresher` (`push/PushTokenRefresher.kt`) is a `DefaultLifecycleObserver` on the
process `Lifecycle`: it asks once at `start()` (a push can start the process with no activity) and again
on every `onStart` (each return to the foreground), but only while `AppPreferences.pushToken` is empty
and a `FirebaseApp` exists (`PushTokenSource.isAvailable()`, checked before any
`FirebaseMessaging.getInstance()` call, which throws without one). A successful request stores through
`PushTokenSink.onNewToken` — the same write path the service's own callback uses — so `pushToken`
still has exactly one writer *shape*, just two callers. A failure or a request past
`PUSH_TOKEN_REQUEST_TIMEOUT` (60 s) is logged by exception class only and leaves nothing stored, so the
next foreground retries; the 60 s bound exists so a hung request cannot hold the in-flight guard and
block that retry. Once a token is stored, rotation stays `onNewToken`'s job alone — the refresher never
requests again on that install. `FirebasePushTokenSource.currentToken()` wraps the FCM `Task` in
`suspendCancellableCoroutine`; its completion listener runs on main with no executor, but it only resumes
the continuation, so the refresher's own request stays on its own dispatcher.

Manifest: `<service android:name=".push.PyryMessagingService" android:exported="false">` with the
`com.google.firebase.MESSAGING_EVENT` intent filter. `exported="false"` means only the in-process
Firebase SDK can deliver to it — no external app can invoke it directly.

## Logging

`event=push_token_stored outcome=success|io_failure` and `event=push_wake`, both through
debug-gated `RelayLog`. `PushTokenRefresher` adds `event=push_token_requested
outcome=success|failure|timeout`, with `error=<exception simple class name>` on failure — no line at
all when a token is already stored or push is off. The token itself, and every `RemoteMessage` field,
appear in no log line.

## Wiring

```kotlin
// di/AppModule.kt
single { PushTokenSink(get()) } onClose { it?.dispose() }
single<PushTokenSource> { FirebasePushTokenSource(androidContext()) }
single(createdAtStart = true) {
    PushTokenRefresher(
        storedToken = get<AppPreferences>().pushToken,
        source = get(),
        sink = get(),
        lifecycle = ProcessLifecycleOwner.get().lifecycle,
    ).also { it.start() }
} onClose { it?.dispose() }
```

`LifecycleConnectionDriver` is already a resolvable Koin singleton created at `startKoin`
([#302](lifecycle-connection-driver.md)); the service resolves it directly, adding no new binding.
`PushTokenRefresher` is `createdAtStart` for the same push-can-start-the-process reason as the
lifecycle driver and the attention notifier below — it must already be observing the process
`Lifecycle` before a push-started process reaches `onStart`. It is a separate observer from
`LifecycleConnectionDriver`, not a call site on it: `onStart` only launches a coroutine and never
suspends, so the refresher cannot delay `connect()`.

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
- **`newToken_isPersisted_andDoesNotWake` must read the token after the write has finished, not
  race it with a wall-clock wait** ([#953](https://github.com/pyrycode/pyrycode-mobile/issues/953)).
  The old test called `withTimeout(5_000) { preferences().pushToken.first { it == "fcm-rotated" } } }`
  against the real `app_prefs` DataStore while `PushTokenSink.onNewToken` wrote to it on its own
  `Dispatchers.IO` scope. Under load this timed out even at a 60 s wait: the write had landed —
  `event=push_token_stored outcome=success` logged every time, and a fresh `pushToken.first()` after
  the timeout returned the rotated token — but the collector had subscribed to a *fresh* DataStore
  while its first write was still running, read `null`, and then never received the update; it stayed
  parked in DataStore 1.1.7's in-memory `StateFlow`. `first { predicate }` under `withTimeout` against
  a DataStore is not a harmless "wait a bit": if the first read overlaps the first write, it can wait
  forever, and raising the timeout does not help. The test now runs in `runTest`, builds its own
  `AppPreferences` over a `PreferenceDataStoreFactory.create` DataStore in a `TemporaryFolder`, and
  gives both that DataStore and a same-scheduler `PushTokenSink` a `StandardTestDispatcher(testScheduler)`
  so `advanceUntilIdle()` runs the write to completion before a plain `first()` reads it — no predicate,
  no clock. The DataStore's scope must be its own `CoroutineScope(StandardTestDispatcher(testScheduler) + Job())`,
  cancelled at the end, not `runTest`'s `backgroundScope`: `advanceUntilIdle()` does not drain
  `backgroundScope` work (see [Development verification § Test scheduling and harnesses](development-verification.md#test-scheduling-and-harnesses),
  #824), so a DataStore scoped there never runs its write actor and a post-write read sees the old
  value. The same lost-emission window is possible in production if a `pushToken` collector starts
  while a rotation write is in flight; that is out of scope here and filed as its own issue
  ([#968](https://github.com/pyrycode/pyrycode-mobile/issues/968)).

`LifecycleConnectionDriverTest` covers the wake-window behaviour the service triggers; see
[Lifecycle driver § Testing](lifecycle-connection-driver.md#testing).

`PushTokenRefresherTest` (plain JVM, `app/src/test`) covers `PushTokenRefresher` against a fake
`PushTokenSource` and a same-scheduler `PushTokenSink`/DataStore (the #953 pattern above), with a
`LifecycleRegistry.createUnsafe` process-lifecycle stand-in. Two lessons from getting it running:

- **`LifecycleRegistry.createUnsafe(Owner())` with a throwaway owner fails partway through a test**
  with "LifecycleOwner … already garbage collected" — the registry holds its owner only weakly. Keep
  a strong reference to the owner for the test's whole body, not just at construction.
- **`advanceUntilIdle()` runs virtual time past any `withTimeoutOrNull` bound inside the code under
  test.** A test meant to catch the refresher mid-request (to prove a start and an immediate
  foreground collapse to one call) that drives time with `advanceUntilIdle()` instead silently runs
  the request past `PUSH_TOKEN_REQUEST_TIMEOUT` and exercises the timeout path instead of the
  in-flight one. Use `runCurrent()` to advance only to the next scheduled point and keep the request
  genuinely pending.

`FirebasePushTokenSourceTest` (Robolectric) proves `isAvailable()` is false with no `FirebaseApp` —
the same "Robolectric doesn't construct one" fact `PyryMessagingServiceTest` below already relies on.

## Attention alerts and the tap route (#685)

`notifications/AttentionNotifier.kt` posts an Android notification for each `AttentionAlert` that
[`HostConversationSource.alerts`](dependency-injection-host-conversation-source.md#attention-alerts-685)
emits — a turn the attention fold counted for the first time, or a prompt newly outstanding, on any
saved host. It needs no push: the source emits from the same live-event and modal/batch collectors that
already drive `attention`, so an alert can fire the moment the app backgrounds while a host is still
connected. That window is narrow in production — `LifecycleConnectionDriver` closes every host on
`onStop`, and only a push wake reopens one. #955's live run proved the reopened window reaches this
notifier: the daemon's `push_wake` (above) brings FCM in, `onPushWake` reconnects the host, the missed
event replays on the fresh connection, and the alert posts from there — one notification, whether the
missed event is a `TurnEnd` or a newly outstanding prompt. This package's own tests
(`AttentionNotifierTest`, `HostConversationSourceAttentionTest`) prove the pipeline with fakes for the
deterministic gates.

**Dedupe runs before the gates, not after.** `AttentionNotifier.handle` records an alert's digest in
`AlertLedger` first; only a digest new to the ledger is even considered for the foreground, switch, mute
and permission checks below. So an alert that arrives while the app is foregrounded, while alerts are
off, or while its conversation is muted, is spent — it can never post later once
background/enabled/unmuted/permission line up. The ledger holds
`SHA-256` digests over length-prefixed `(serverId, conversationId, kind, key)`, one per line in
`noBackupFilesDir/attention_alerts`, capped at the newest 512, written temp-then-rename. This is what
holds "at most once" across a reconnect's replay, a repeated `modal_shown` frame, a new wake window and
process death — the same digests survive a process restart because the file does.

**The gates, checked in this order, each read fresh (none is cached):** not foregrounded
(`ProcessLifecycleOwner`'s state `< STARTED`) → `AppPreferences.notificationsEnabled` → muted (below,
\#1022) → `POST_NOTIFICATIONS` granted.

### The muted gate (#1022)

`isMuted: (serverId, conversationId) -> Boolean` is a constructor parameter, injected because alerts
arrive from every saved host, not only the selected one — the lookup must read the alert's own host's
rows. `AppModule` wires it to a top-level `internal fun List<HostConversationSnapshot>.isMuted(serverId,
conversationId): Boolean` (`AttentionNotifier.kt`) over `HostConversationSource.snapshots.value`: true
only when the snapshot whose `serverId` matches holds a row — in `channels` or `chats` — with that id and
`muted == true`. Keying by host first means the same conversation id muted on one host never silences
that id on another. A host with no snapshot yet, or a conversation missing from its host's last known
rows, is **not** muted — failing open, since posting an unwanted alert is the safer wrong answer than
staying silent. One consequence: [`HostConversationSource.snapshots` excludes archived
rows](dependency-injection-host-conversation-source.md#host-identity-and-snapshots), so an archived muted
conversation reads as missing here and still alerts; accepted because archived conversations are not
expected to produce turns. Because this gate sits after the ledger dedup like the others, a muted alert
is recorded and spent — unmuting afterward never replays it.

### The agent lookup (#1116)

`agentOf: (serverId, conversationId) -> ConversationAgent?` is wired the same way as `isMuted` above — a
constructor parameter reading `HostConversationSource.snapshots.value` through a top-level `internal fun
List<HostConversationSnapshot>.agentOf(serverId, conversationId): ConversationAgent?` (`AttentionNotifier.kt`),
checking the matching host first, then `channels + chats`. It differs from `isMuted` at the not-found case:
a missing host or a missing row returns `null` rather than a fallback agent, because `null` selects the
neutral copy below instead of silently mislabeling the notification as Claude's.

**The notification itself** is fixed `strings.xml` copy naming the conversation's agent (#1116): a Claude
conversation reads exactly as before (`notification_turn_completed` / `notification_prompt`), a Codex
conversation gets `notification_turn_completed_codex` / `notification_prompt_codex`, and a conversation the
agent lookup above returns `null` for gets the neutral `notification_turn_completed_neutral` /
`notification_prompt_neutral` ("A reply finished" / "An answer is needed") — a lookup miss reads as unknown,
never as an assumed Claude. Title is still the app name. No daemon-authored conversation name or
push-message field ever reaches it; the agent name is one of these fixed, client-owned strings. Tag =
`SHA-256(serverId, conversationId)`, id `0`: one notification per conversation per host, so the same
conversation id on two hosts posts two notifications, and a later alert for the same conversation replaces
the earlier one instead of stacking.

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
    val source = get<HostConversationSource>()
    AttentionNotifier(
        context = androidContext(),
        alerts = source.alerts,
        notificationsEnabled = get<AppPreferences>().notificationsEnabled,
        isMuted = { serverId, conversationId -> source.snapshots.value.isMuted(serverId, conversationId) },
        agentOf = { serverId, conversationId -> source.snapshots.value.agentOf(serverId, conversationId) },
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

`event=attention_alert outcome=posted|duplicate|foreground|disabled|muted|no_permission kind=turn|prompt`,
`event=notification_tap_accepted`, `event=notification_tap_rejected code=unknown_host`,
`event=notification_permission_answered granted=…`, `event=attention_alert_ledger outcome=read_failed|write_failed`.
No id, digest or notification text appears in any of these lines.

### Testing (#685)

- `HostConversationSourceAttentionTest` (`app/src/test`) proves the alert-emission rules in
  [dependency-injection-host-conversation-source.md § Attention alerts](dependency-injection-host-conversation-source.md#attention-alerts-685):
  once per counted turn, once per prompt key, two hosts sharing a conversation id alerting twice.
- `AttentionNotifierTest` (`app/src/test`, Robolectric) proves the notifier end to end against a real
  `NotificationManager` shadow: fixed copy, the dedupe-before-gates order, a fresh notifier over the same
  ledger file dropping a replayed alert (process death), the posted `contentIntent`'s parsed target, and
  (#1022) the muted gate — a muted conversation's turn and prompt both post nothing, unmuting after the
  alert was already spent by the ledger does not replay it, and the same conversation id muted on one
  host still alerts on another. `List<HostConversationSnapshot>.isMuted`'s own table (muted in `channels`,
  muted in `chats`, unmuted row, id missing from the host's rows, host with no snapshot) is a plain unit
  test beside it, not Robolectric. (#1116) A Codex conversation's turn and prompt post the Codex copy, a
  conversation missing from the lookup posts the neutral copy, a Claude conversation still reads exactly
  as before, and the channel description reads neutrally.
  `List<HostConversationSnapshot>.agentOf`'s own table (Codex in `channels`, Codex in `chats`, a Claude
  row, id missing from the host's rows, host missing entirely) is a plain unit test beside `isMuted`'s.
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
- Spec: `docs/specs/architecture/1022-attention-notifier-muted-gate.md` — the muted gate's design and its
  fail-open rationale.
- [Data model § `Conversation`](data-model.md#conversation) — the `muted` field (#999) this gate reads,
  and the Edit channel checkbox (#1021) that writes it.
- Spec: `docs/specs/architecture/361-fcm-push-wake.md` (§ Design, § Security review — verdict PASS,
  § Revisions for the two open questions above).
- Spec: `docs/specs/architecture/1102-request-current-fcm-token.md` — `PushTokenRefresher`'s design,
  the #1076 gate flake it fixes, and the Phase B revision resolving the `Task` listener/executor
  question.
- README `### Firebase` — where `app/google-services.json` and the conditional plugin are recorded.

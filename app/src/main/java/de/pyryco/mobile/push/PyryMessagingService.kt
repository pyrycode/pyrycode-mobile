package de.pyryco.mobile.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.lifecycle.LifecycleConnectionDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.IOException

/**
 * The push trust boundary (#361). The system creates this service outside Compose, so its two
 * collaborators come from Koin. It makes no Firebase call of its own: without `google-services.json`
 * no `FirebaseApp` exists, nothing is ever delivered here, and push is simply off.
 *
 * A message is never read. No sender contract exists yet, so every field — data, notification,
 * sender, id — is untrusted and ignored: a push can only wake the saved hosts, never become a
 * command, pairing data, a host selection or UI text. Only a data message reaches
 * [onMessageReceived] while the app is backgrounded; a notification message goes to the tray.
 */
class PyryMessagingService :
    FirebaseMessagingService(),
    KoinComponent {
    override fun onNewToken(token: String) {
        get<PushTokenSink>().onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        RelayLog.d { "event=push_wake" }
        get<LifecycleConnectionDriver>().onPushWake()
    }
}

/**
 * Persists each new FCM token. App-lifetime rather than service-scoped: the service may be destroyed as
 * soon as its callback returns, and the write must still land. Open connections observe the stored
 * token and re-register it (see `RelayRepositoryCoordinator`), so persisting is the whole job here.
 */
class PushTokenSink(
    private val preferences: AppPreferences,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun onNewToken(token: String) {
        scope.launch {
            try {
                preferences.setPushToken(token)
                // The token itself is never logged.
                RelayLog.d { "event=push_token_stored outcome=success" }
            } catch (e: IOException) {
                RelayLog.w { "event=push_token_stored outcome=io_failure" }
            }
        }
    }

    /** Cancels any pending write. Koin calls this on close. */
    fun dispose() {
        scope.cancel()
    }
}

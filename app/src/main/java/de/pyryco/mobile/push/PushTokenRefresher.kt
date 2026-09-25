package de.pyryco.mobile.push

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long one current-token request may take before it counts as failed and the next foreground retries. */
val PUSH_TOKEN_REQUEST_TIMEOUT: Duration = 60.seconds

/** The current FCM registration token, asked for directly (#1102). */
interface PushTokenSource {
    /** False when no `FirebaseApp` exists; [currentToken] is then never called. */
    fun isAvailable(): Boolean

    /** The current token. Throws the request's failure. */
    suspend fun currentToken(): String
}

/** Firebase's own token request. Without `google-services.json` no `FirebaseApp` exists and push is off. */
class FirebasePushTokenSource(
    private val context: Context,
) : PushTokenSource {
    override fun isAvailable(): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    // FirebaseMessaging.getInstance() throws without a FirebaseApp: callers check isAvailable() first.
    override suspend fun currentToken(): String =
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val error = task.exception
                when {
                    error != null -> continuation.resumeWithException(error)
                    task.isCanceled -> continuation.cancel()
                    else -> continuation.resume(task.result)
                }
            }
        }
}

/**
 * Asks FCM for the current token at start and on each return to the foreground, while none is stored
 * (#1102). `onNewToken` alone left a phone unreachable by push until FCM's own retry succeeded whenever
 * its first mint failed. A token is stored through [PushTokenSink], the path `onNewToken` uses; a
 * failure is logged and retried at the next foreground. Rotation stays `onNewToken`'s job.
 *
 * A separate observer from `LifecycleConnectionDriver`: [onStart] only launches, so it never delays the
 * driver's `connect()`. At most one request is in flight; a start and the first foreground collapse.
 */
class PushTokenRefresher(
    private val storedToken: Flow<String?>,
    private val source: PushTokenSource,
    private val sink: PushTokenSink,
    private val lifecycle: Lifecycle,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeout: Duration = PUSH_TOKEN_REQUEST_TIMEOUT,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var inFlight: Job? = null

    /** Registers on the (process) [lifecycle] and asks once now: a push can start the process in the background. */
    fun start() {
        lifecycle.addObserver(this)
        refresh()
    }

    override fun onStart(owner: LifecycleOwner) {
        refresh()
    }

    @Synchronized
    private fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = scope.launch { requestIfMissing() }
    }

    private suspend fun requestIfMissing() {
        if (!source.isAvailable()) return
        if (!storedToken.first().isNullOrEmpty()) return
        val token =
            try {
                withTimeoutOrNull(requestTimeout) { source.currentToken() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class name only: neither the token nor the failure's message is logged.
                RelayLog.w { "event=push_token_requested outcome=failure error=${e::class.java.simpleName}" }
                return
            }
        if (token == null) {
            RelayLog.w { "event=push_token_requested outcome=timeout" }
            return
        }
        RelayLog.d { "event=push_token_requested outcome=success" }
        sink.onNewToken(token)
    }

    /** Cancels any pending request. Koin calls this on close. */
    fun dispose() {
        scope.cancel()
    }
}

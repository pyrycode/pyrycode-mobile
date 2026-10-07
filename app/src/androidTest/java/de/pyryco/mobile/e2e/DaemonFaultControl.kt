package de.pyryco.mobile.e2e

import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.TimeMark

/** Controls only the first daemon process owned by this e2e harness invocation. */
internal class DaemonFaultControl {
    private val port =
        requireNotNull(InstrumentationRegistry.getArguments().getString("daemonFaultPort")) {
            "the test daemon fault port is missing"
        }.toInt()

    fun stop() = request("stop")

    fun start() = request("start")

    /** Returns a deadline safely before the earliest passive dial after the actual capped backoff begins. */
    fun stopUntilRetryWindow(
        supervisor: RelayConnectionSupervisor,
        assertOfflinePill: () -> Unit,
    ): TimeMark =
        runBlocking {
            val failures =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    OfflineRetryWindow.awaitDeadline(supervisor)
                }
            try {
                stop()
                assertOfflinePill()
                failures.await()
            } finally {
                failures.cancel()
            }
        }

    fun recoveryTimeRemaining(deadline: TimeMark): Long = OfflineRetryWindow.remainingMs(deadline)

    private fun request(action: String) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("10.0.2.2", port), 5_000)
            socket.soTimeout = 15_000
            socket.getOutputStream().write("POST /$action HTTP/1.0\r\nContent-Length: 0\r\n\r\n".toByteArray())
            val status = socket.getInputStream().bufferedReader().readLine()
            check(status?.startsWith("HTTP/1.0 200") == true) { "test daemon $action failed: $status" }
        }
    }
}

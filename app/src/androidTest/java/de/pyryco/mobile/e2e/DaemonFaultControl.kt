package de.pyryco.mobile.e2e

import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket

/** Controls only the first daemon process owned by this e2e harness invocation. */
internal class DaemonFaultControl {
    private val port =
        requireNotNull(InstrumentationRegistry.getArguments().getString("daemonFaultPort")) {
            "the test daemon fault port is missing"
        }.toInt()

    fun stop() = request("stop")

    fun start() = request("start")

    /** Six real failed dials put the supervisor in its 24–36 second capped wait. */
    fun stopUntilRetryWindow(
        supervisor: RelayConnectionSupervisor,
        assertOfflinePill: () -> Unit,
    ) = runBlocking {
        val failures =
            async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                withTimeout(90_000) {
                    var previous: RelayLinkStatus = supervisor.relayStatus.value
                    var count = 0
                    supervisor.relayStatus.first { current ->
                        if (
                            (previous is RelayLinkStatus.Connected || previous is RelayLinkStatus.Connecting) &&
                            (
                                current is RelayLinkStatus.Reconnecting ||
                                    current is RelayLinkStatus.DaemonAbsent ||
                                    current is RelayLinkStatus.Offline
                            )
                        ) {
                            count++
                        }
                        previous = current
                        count >= 6
                    }
                }
            }
        try {
            stop()
            assertOfflinePill()
            failures.await()
        } finally {
            failures.cancel()
        }
    }

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

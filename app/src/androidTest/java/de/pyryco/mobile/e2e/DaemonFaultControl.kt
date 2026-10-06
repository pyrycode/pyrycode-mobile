package de.pyryco.mobile.e2e

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    fun posts(
        name: String,
        prefix: String,
        count: Int,
    ) = request(
        "posts",
        buildJsonObject {
            put("name", name)
            put("prefix", prefix)
            put("count", count)
        }.toString(),
    )

    /** Returns a deadline safely before the earliest passive dial after the sixth failure. */
    fun stopUntilRetryWindow(
        supervisor: RelayConnectionSupervisor,
        assertOfflinePill: () -> Unit,
    ): Long =
        runBlocking {
            val failures =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(90_000) {
                        var previous: RelayLinkStatus = supervisor.relayStatus.value
                        var count = 0
                        var cappedWaitObservedAt = 0L
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
                            (count >= 6).also { if (it) cappedWaitObservedAt = SystemClock.elapsedRealtime() }
                        }
                        // The capped wait lasts at least 24 s. Leave 4 s for observer scheduling jitter.
                        cappedWaitObservedAt + 20_000L
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

    fun recoveryTimeRemaining(deadline: Long): Long =
        (deadline - SystemClock.elapsedRealtime()).also {
            check(it > 0) { "the passive reconnect window elapsed before Retry recovery" }
        }

    private fun request(
        action: String,
        body: String = "",
    ) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("10.0.2.2", port), 5_000)
            socket.soTimeout = if (action == "posts") 120_000 else 15_000
            val bytes = body.toByteArray()
            socket.getOutputStream().write("POST /$action HTTP/1.0\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes)
            val status = socket.getInputStream().bufferedReader().readLine()
            check(status?.startsWith("HTTP/1.0 200") == true) { "owned test daemon control failed" }
        }
    }
}

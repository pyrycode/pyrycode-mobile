package de.pyryco.mobile.e2e

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.recording.RecordedSuiteNative
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeMark

/** Controls only the first daemon process owned by this e2e harness invocation. */
internal class DaemonFaultControl {
    private val port =
        requireNotNull(InstrumentationRegistry.getArguments().getString("daemonFaultPort")) {
            "the test daemon fault port is missing"
        }.toInt()

    fun beginTest() = request("test-begin")

    fun endTest() = request("test-end")

    fun stop() = request("stop")

    fun start() = request("start")

    fun releaseStream() = request("stream-release")

    fun finishStream() = request("stream-finish")

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

    /** Returns a deadline safely before the earliest passive dial after the actual capped backoff begins. */
    fun stopUntilRetryWindow(
        supervisor: RelayConnectionSupervisor,
        assertOfflinePill: () -> Unit,
    ): TimeMark =
        runBlocking {
            val failures =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    OfflineRetryWindow.awaitDeadline(supervisor) { backoff ->
                        Log.i(
                            "DaemonFaultControl",
                            "event=offline_retry_capped_backoff attempt=${backoff.attempt} " +
                                "observed_lag_ms=${backoff.startedAt.elapsedNow().inWholeMilliseconds} " +
                                "proof_budget_ms=20000 passive_min_ms=24000",
                        )
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

    fun recoveryTimeRemaining(deadline: TimeMark): Long =
        OfflineRetryWindow.remainingMs(deadline).also {
            Log.i("DaemonFaultControl", "event=offline_retry_recovery_budget remaining_ms=$it")
        }

    private fun request(
        action: String,
        body: String = "",
    ) {
        RecordedSuiteNative.control(action, body) {
            checkedDaemonFaultRequest(action, { Log.i("DaemonFaultControl", "event=daemon_fault_request $it") }) {
                exchangeDaemonFaultRequest("10.0.2.2", port, action, body)
            }
        }
    }
}

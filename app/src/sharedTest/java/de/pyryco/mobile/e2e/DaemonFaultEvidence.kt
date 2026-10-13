package de.pyryco.mobile.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.TimeSource

internal fun checkedDaemonFaultRequest(action: String, record: (String) -> Unit, exchange: () -> String) {
    val started = TimeSource.Monotonic.markNow()
    val response = try {
        exchange()
    } catch (failure: Throwable) {
        record("${daemonFaultEvidence(action, "", started.elapsedNow().inWholeMilliseconds)} failure=transport")
        throw failure
    }
    val evidence = daemonFaultEvidence(action, response, started.elapsedNow().inWholeMilliseconds)
    record(evidence)
    check(daemonFaultHttpStatus(response) == 200) { "owned test daemon control failed $evidence" }
}

internal fun daemonFaultHttpStatus(response: String): Int =
    Regex("HTTP/1\\.[01] ([0-9]{3})(?: |\\r|\\n|$)").find(response)?.takeIf { it.range.first == 0 }
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 100..599 } ?: 0

/** Publish a fixed vocabulary only; response text and nested exception messages stay private. */
internal fun daemonFaultEvidence(action: String, response: String, elapsedMs: Long): String {
    val safeAction = action.takeIf { it in setOf("test-begin", "test-end", "posts", "stop", "start", "stream-release", "stream-finish") } ?: "unknown"
    val payload = runCatching { Json.parseToJsonElement(response.substringAfter("\r\n\r\n", "")).jsonObject }.getOrNull()
    val generation = runCatching { payload?.get("generation")?.jsonPrimitive?.longOrNull }.getOrNull()?.takeIf { it >= 0 } ?: -1
    val phase = runCatching { payload?.get("phase")?.jsonPrimitive?.content }.getOrNull()
        ?.takeIf {
            it in setOf("stopped", "spawning", "spawned", "waiting", "ready", "disabled", "registration_timeout", "exited", "stop_timeout")
        } ?: "unknown"
    return "action=$safeAction http_status=${daemonFaultHttpStatus(response)} generation=$generation " +
        "phase=$phase elapsed_ms=${elapsedMs.coerceAtLeast(0)}"
}

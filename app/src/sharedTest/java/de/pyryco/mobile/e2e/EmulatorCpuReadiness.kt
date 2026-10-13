package de.pyryco.mobile.e2e

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import kotlin.coroutines.resumeWithException

internal suspend fun <T : Closeable> withEmulatorCpuSample(
    acquire: () -> T,
    read: suspend (T) -> Pair<Long, Long>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Pair<Long, Long> {
    // Binder acquisition may ignore interruption. Bound the caller without joining that worker;
    // cancellation owns any descriptor returned later through resume's resource cleanup.
    val worker = CoroutineScope(Job() + dispatcher)
    val source =
        suspendCancellableCoroutine<T> { continuation ->
            continuation.invokeOnCancellation { worker.cancel() }
            worker.launch {
                try {
                    continuation.resume(acquire()) { _, resource, _ -> resource.close() }
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } finally {
                    worker.cancel()
                }
            }
        }
    return source.use { read(it) }
}

/** A nonblocking reader returns zero while held, and -1 at EOF. Only the first CPU line is used. */
internal suspend fun readEmulatorCpuTicks(readAvailable: (ByteArray, Int, Int) -> Int): Pair<Long, Long> {
    val bytes = ByteArray(4096)
    var size = 0
    while (true) {
        val count = readAvailable(bytes, size, bytes.size - size)
        require(count >= 0) { "device CPU counters unavailable" }
        if (count == 0) {
            delay(10)
            continue
        }
        val end = (size until size + count).firstOrNull { bytes[it] == '\n'.code.toByte() }
        size += count
        if (end != null) return parseEmulatorCpuTicks(bytes.decodeToString(0, end))
        require(size < bytes.size) { "device CPU counters exceed bound" }
    }
}

internal fun parseEmulatorCpuTicks(line: String): Pair<Long, Long> {
    val fields = line.trim().split(Regex("\\s+"))
    require(fields.firstOrNull() == "cpu" && fields.size >= 9) { "device CPU counters unavailable" }
    val ticks = fields.drop(1).take(8).map { requireNotNull(it.toLongOrNull()) { "invalid device CPU counter" } }
    return ticks.sum() to (ticks[3] + ticks[4])
}

internal suspend fun awaitEmulatorCpuIdle(
    sample: suspend () -> Pair<Long, Long>,
    onWindow: (Long) -> Unit = {},
) {
    withTimeout(30000) {
        var before = sample()
        var consecutive = 0
        while (true) {
            delay(250)
            val after = sample()
            val total = after.first - before.first
            val idle = after.second - before.second
            val percent = if (total > 0) idle * 100 / total else 0
            onWindow(percent)
            consecutive = if (percent >= 80) consecutive + 1 else 0
            if (consecutive == 4) return@withTimeout
            before = after
        }
    }
}

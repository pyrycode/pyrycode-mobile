package de.pyryco.mobile.e2e

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal fun parseEmulatorCpuTicks(line: String): Pair<Long, Long> {
    val fields = line.trim().split(Regex("\\s+"))
    require(fields.firstOrNull() == "cpu" && fields.size >= 9) { "device CPU counters unavailable" }
    val ticks = fields.drop(1).take(8).map { requireNotNull(it.toLongOrNull()) { "invalid device CPU counter" } }
    return ticks.sum() to (ticks[3] + ticks[4])
}

internal suspend fun awaitEmulatorCpuIdle(
    sample: () -> Pair<Long, Long>,
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

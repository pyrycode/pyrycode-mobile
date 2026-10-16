package de.pyryco.mobile.recording

/** Memory-only capture. A rejected frame invalidates the entire candidate, including earlier frames. */
internal class SuiteCapture(
    private val maxBytes: Int = 20_000_000,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val steps = mutableListOf<SuiteStep>()
    private var previous: Long? = null
    private var bytes = 0
    private var rejected = false

    @Synchronized fun observe(step: SuiteStep) {
        if (rejected) return
        try {
            step.message?.let { requireSafe(it.payload) }
            val now = clockMs()
            val delay = previous?.let { now - it } ?: 0
            require(delay >= 0)
            val event = step.copy(delayMs = delay)
            bytes += event.toString().toByteArray().size
            require(bytes <= maxBytes && steps.size < 50000)
            steps += event
            previous = now
        } catch (_: Exception) {
            rejected = true
            steps.clear()
        }
    }

    @Synchronized fun completed(success: Boolean): List<SuiteStep> {
        check(success && !rejected) { "Recorded suite capture rejected or incomplete" }
        val snapshot = steps.toList()
        RecordedSuiteTape(snapshot)
        return snapshot
    }
}

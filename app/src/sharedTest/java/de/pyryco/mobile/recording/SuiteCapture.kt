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
    private var rejectedType = "none"

    @Synchronized fun observe(step: SuiteStep) {
        if (rejected) return
        try {
            val normalized = step.message?.let { capturedSuiteFrame(step.direction, it) }
            normalized?.let { requireSafeSuiteFrame(step.direction, it) }
            requireSafe(step.detail)
            val now = clockMs()
            val delay = previous?.let { now - it } ?: 0
            require(delay >= 0)
            val event = step.copy(message = normalized, delayMs = delay)
            bytes += event.toString().toByteArray().size
            require(bytes <= maxBytes && steps.size < 50000)
            steps += event
            previous = now
        } catch (_: Exception) {
            rejected = true
            rejectedType = step.message?.type?.takeIf { it.matches(Regex("[a-z_:-]{1,100}")) } ?: step.direction
            steps.clear()
        }
    }

    @Synchronized fun send(
        owner: String,
        frame: de.pyryco.mobile.data.network.Envelope,
        operation: () -> Boolean,
    ): Boolean {
        val index = steps.size
        observe(SuiteStep("client", owner, frame))
        return try {
            val accepted = operation()
            if (!rejected) steps[index] = steps[index].copy(accepted = accepted)
            accepted
        } catch (error: Throwable) {
            rejected = true
            steps.clear()
            throw error
        }
    }

    @Synchronized fun completed(success: Boolean): List<SuiteStep> {
        check(success && !rejected) { "Recorded suite capture rejected or incomplete: $rejectedType" }
        val snapshot = steps.toList()
        RecordedSuiteTape(snapshot)
        return snapshot
    }

    @Synchronized fun diagnostic(): List<SuiteStep>? =
        if (rejected || steps.isEmpty() ||
            steps.any { it.message?.type == "debug_bundle_chunk" }
        ) {
            null
        } else {
            steps.toList()
        }
}

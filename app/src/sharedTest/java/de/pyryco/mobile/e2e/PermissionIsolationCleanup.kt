package de.pyryco.mobile.e2e

/** Keep phone work alive through the replay fence without replacing an earlier scenario failure. */
internal inline fun <T> withPermissionIsolationCleanup(
    closePeer: () -> Unit,
    beforeHostRemoval: () -> Unit,
    removeHost: () -> Unit,
    scenario: () -> T,
): T {
    var scenarioFailure: Throwable? = null
    try {
        return scenario()
    } catch (error: Throwable) {
        scenarioFailure = error
        throw error
    } finally {
        // Preserve captured peer-close order; only host removal disposes the phone connection.
        val peerFailure = runCatching(closePeer).exceptionOrNull()
        val fenceFailure = runCatching(beforeHostRemoval).exceptionOrNull()
        val hostFailure = runCatching(removeHost).exceptionOrNull()
        val failures = listOfNotNull(scenarioFailure, peerFailure, fenceFailure, hostFailure)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
            throw first
        }
    }
}

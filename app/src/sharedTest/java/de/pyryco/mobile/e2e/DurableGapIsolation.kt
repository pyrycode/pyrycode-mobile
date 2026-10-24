package de.pyryco.mobile.e2e

/** One scenario's ownership of the deliberately disconnected phone and diagnostic resources. */
internal class DurableGapIsolation(
    private val restore: () -> Unit,
    private val closePeer: () -> Unit,
    private val endProbe: () -> Unit,
) {
    private var disconnectAttempted = false

    fun sever(disconnect: () -> Unit) {
        disconnectAttempted = true
        disconnect()
    }

    fun run(body: DurableGapIsolation.() -> Unit) {
        var failure: Throwable? = null

        fun finish(action: () -> Unit) {
            try {
                action()
            } catch (secondary: Throwable) {
                val primary = failure
                if (primary == null) {
                    failure = secondary
                } else if (primary !== secondary) {
                    primary.addSuppressed(secondary)
                }
            }
        }

        try {
            body()
        } catch (primary: Throwable) {
            failure = primary
        }
        // Failure-path recovery cannot make a failed proof pass, even if normal reconnect failed.
        if (failure != null && disconnectAttempted) finish(restore)
        finish(closePeer)
        finish(endProbe)
        failure?.let { throw it }
    }
}

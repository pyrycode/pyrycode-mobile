package de.pyryco.mobile

/** Test-only teardown owner for the scanner's host, camera and executor fixture. */
internal class ScannerTransitionCleanup : AutoCloseable {
    private val actions = mutableListOf<() -> Unit>()

    fun onClose(action: () -> Unit) {
        actions.add(action)
    }

    override fun close() {
        var failure: Throwable? = null
        for (action in actions.asReversed()) {
            try {
                action()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

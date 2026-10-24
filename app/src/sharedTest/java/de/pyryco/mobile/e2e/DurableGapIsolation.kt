package de.pyryco.mobile.e2e

/** One scenario's ownership of the deliberately disconnected phone and diagnostic resources. */
internal class DurableGapIsolation(
    private val restore: () -> Unit,
    private val closePeer: () -> Unit,
    private val endProbe: () -> Unit,
) {
    fun sever(disconnect: () -> Unit) = disconnect()

    fun run(body: DurableGapIsolation.() -> Unit) {
        try {
            body()
        } finally {
            closePeer()
        }
    }
}

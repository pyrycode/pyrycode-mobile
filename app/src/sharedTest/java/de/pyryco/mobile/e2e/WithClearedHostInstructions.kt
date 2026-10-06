package de.pyryco.mobile.e2e

/** Clear only a harness host, confirm before session creation, and restore its exact captured text. */
fun <T> withClearedHostInstructions(
    read: () -> String,
    write: (String) -> Unit,
    block: () -> T,
): T {
    val original = read()
    var failure: Throwable? = null
    try {
        write("")
        check(read().isEmpty()) { "isolated host instructions were not cleared" }
        return block()
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        try {
            write(original)
            check(read() == original) { "isolated host instructions were not restored" }
        } catch (restoreFailure: Throwable) {
            val scenarioFailure = failure
            if (scenarioFailure == null) throw restoreFailure
            scenarioFailure.addSuppressed(restoreFailure)
        }
    }
}

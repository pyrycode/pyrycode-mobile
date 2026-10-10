package de.pyryco.mobile.e2e

/** Content-free collision-scenario evidence, collected before Activity teardown. */
internal inline fun <T> collisionRenameStep(
    diagnostic: () -> String,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: Throwable) {
        try {
            failure.addSuppressed(AssertionError("collision rename: ${diagnostic()}"))
        } catch (diagnosticFailure: Throwable) {
            failure.addSuppressed(diagnosticFailure)
        }
        throw failure
    }

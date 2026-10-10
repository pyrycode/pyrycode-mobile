package de.pyryco.mobile.e2e

/** Capture before mutation and always attempt exact restoration on the isolated harness host. */
internal fun <T> withHostPromptRestoration(
    readOriginal: () -> String,
    restoreOriginal: (String) -> Unit,
    block: () -> T,
): T {
    val original = readOriginal()
    try {
        return block()
    } finally {
        restoreOriginal(original)
    }
}

package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServer

/**
 * Outcome of [parsePairingPayload] (#320): the untrusted scanned string crosses into a trusted
 * record only through [Success]; every reject path yields [Failure] and persists nothing.
 */
sealed interface PairingParseResult {
    data class Success(
        val server: PairedServer,
    ) : PairingParseResult

    /**
     * [reason] is a fixed byte-safe category label for logging only — never a field VALUE, never
     * the scanned bytes, and never the user-facing copy (the UI maps all failures to one fixed
     * string).
     */
    data class Failure(
        val reason: String,
    ) : PairingParseResult
}

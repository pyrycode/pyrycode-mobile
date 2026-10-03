package de.pyryco.mobile.ui.onboarding

import android.content.Intent

/**
 * A pairing code handed to a test build at launch, so a hands-on check opens the pair-code screen with the
 * code filled in instead of typing a 300-character code through the device's key events
 * (`scripts/hands-on.sh pair`). It only fills the fields: the operator still taps Pair and confirms the
 * fingerprint, exactly as after a paste.
 *
 * Release builds never read it: [from] returns null unless `debug`. The code carries the pairing token, so
 * it is never logged and never travels in a navigation route.
 */
internal class PairingPrefill(
    val code: String,
    val name: String,
) {
    override fun toString() = "PairingPrefill([REDACTED])"

    companion object {
        const val EXTRA_CODE = "de.pyryco.mobile.extra.PAIRING_CODE"
        const val EXTRA_NAME = "de.pyryco.mobile.extra.HOST_NAME"

        /** Well above any code `parsePairingPayload` accepts, so only junk is refused here. */
        private const val MAX_CHARS = 4096

        fun from(
            intent: Intent?,
            debug: Boolean,
        ): PairingPrefill? {
            if (!debug) return null
            val code = intent?.getStringExtra(EXTRA_CODE)?.trim()
            if (code.isNullOrEmpty() || code.length > MAX_CHARS) return null
            val name =
                intent
                    .getStringExtra(EXTRA_NAME)
                    ?.trim()
                    ?.take(MAX_CHARS)
                    .orEmpty()
            return PairingPrefill(code, name)
        }
    }
}

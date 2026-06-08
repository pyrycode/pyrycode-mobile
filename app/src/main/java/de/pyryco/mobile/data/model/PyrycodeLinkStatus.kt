package de.pyryco.mobile.data.model

/**
 * The pyrycode-leg half of the two-part connection status (#392): the **relay → daemon**
 * end-to-end session-readiness leg, derived from the Noise session pump's lifecycle. Unlike the
 * relay leg ([RelayLinkStatus], which is socket-level), this leg reaches [Connected] **only** after
 * the `Noise_IK` handshake completes — never on bare socket-up — so the status surface never shows a
 * false "connected" while the encrypted session is not yet live (the false green that bit live
 * testing on 2026-06-08).
 *
 * Portable (no `android.*`); lives beside [ConnectionState] / [RelayLinkStatus]. [ConnectionStatus]
 * holds it verbatim alongside the relay leg for the Settings connection-status line (#390).
 */
sealed class PyrycodeLinkStatus {
    /** The Noise handshake is in flight (`noise_init` sent, awaiting `noise_resp`). */
    data object Handshaking : PyrycodeLinkStatus()

    /** The Noise handshake completed; the end-to-end encrypted session is live. */
    data object Connected : PyrycodeLinkStatus()

    /** The session ended, or there is no live session yet (no pump between connections). */
    data object Down : PyrycodeLinkStatus()
}

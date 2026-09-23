package de.pyryco.mobile.data.model

/**
 * The relay-leg half of the two-part connection status (#391). It models the **phone → relay** leg
 * only: a single socket-level signal that additionally branches the relay's `4404 "no server"` close
 * into a distinct [DaemonAbsent] (relay reachable, no daemon registered behind it) — a state the
 * legacy single-signal [ConnectionState] cannot express.
 *
 * Introduced alongside [ConnectionState] (Strangler Fig): the legacy 4-case surface the connection
 * banner consumes is *derived* from this leg, and #392 zips this leg with the pyrycode-session-readiness
 * leg into the eventual combined model. Portable (no `android.*`); lives beside [ConnectionState].
 */
sealed class RelayLinkStatus {
    /** Relay reachable and the socket is up. */
    data object Connected : RelayLinkStatus()

    /** Deliberately not dialing: initial (pre-connect), unpaired, or intentionally closed. Distinct
     *  from [Connected] so the two derived surfaces can diverge — the banner stays hidden (idle is not
     *  an error) while the Settings status line reads honestly not-connected rather than green. */
    data object Idle : RelayLinkStatus()

    /** A dial is in progress. */
    data object Connecting : RelayLinkStatus()

    /** Backing off before the next dial, [secondsRemaining] until it fires. */
    data class Reconnecting(
        val secondsRemaining: Int,
    ) : RelayLinkStatus()

    /** Relay reachable but no daemon is registered behind it (the `4404` close). */
    data object DaemonAbsent : RelayLinkStatus()

    /** The host refused this pairing's credential (the `4401` invalid-token or `4426` handshake-failed
     *  close). Only a re-pair recovers, so the supervisor has stopped redialling; an explicit retry or
     *  the next foreground dials once more. Static: carries no relay- or daemon-supplied data (#841). */
    data object PairingRejected : RelayLinkStatus()

    /** Unreachable / sustained unavailability. */
    data object Offline : RelayLinkStatus()
}

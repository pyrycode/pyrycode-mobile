package de.pyryco.mobile.ui.onboarding

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

internal const val PAIRING_VERIFICATION_DEADLINE_MS = 30_000L

/** The outcome of waiting for a saved pairing to authenticate (#1385), shared by the code and QR paths. */
internal sealed interface PairingVerification {
    data object Connected : PairingVerification

    enum class Failure(
        val message: String,
        val retryable: Boolean,
        val code: String,
    ) : PairingVerification {
        Unavailable(UNAVAILABLE_MESSAGE, true, "daemon_absent"),
        Deadline(UNAVAILABLE_MESSAGE, true, "deadline"),
        Rejected("Pairing rejected. The saved host is retained. Cancel, then pair manually with a fresh code.", false, "pairing_rejected"),

        // A retry cannot help until the app is updated (#1008); the host's minimum is not shown here.
        UpdateRequired("Pairing saved. This app is too old for this host. Update the app, then retry.", true, "update_required"),
    }
}

private const val UNAVAILABLE_MESSAGE = "The host is temporarily unavailable. The pairing is saved. Retry to wait again, or Cancel."

/**
 * Waits up to [PAIRING_VERIFICATION_DEADLINE_MS] for the saved [server] to authenticate, mirroring desktop's
 * `createPairingVerification`. Success needs both the relay and the encrypted session; relay blips keep
 * waiting. On a [retry], an absence already held from the last wait is feedback, not a new failure, so a
 * `DaemonAbsent` fails the wait only after some other status has been seen.
 */
internal suspend fun verifySavedPairing(
    server: PairedServer,
    observe: (PairedServer) -> Flow<ConnectionStatus?>,
    retry: Boolean = false,
): PairingVerification {
    var ignoreAbsence = retry
    val outcome =
        withTimeoutOrNull(PAIRING_VERIFICATION_DEADLINE_MS) {
            observe(server)
                .mapNotNull { status ->
                    val relay = status?.relay
                    if (relay != RelayLinkStatus.DaemonAbsent) ignoreAbsence = false
                    when {
                        relay == RelayLinkStatus.Connected && status?.pyrycode == PyrycodeLinkStatus.Connected ->
                            PairingVerification.Connected
                        relay == RelayLinkStatus.DaemonAbsent && !ignoreAbsence -> PairingVerification.Failure.Unavailable
                        relay == RelayLinkStatus.PairingRejected -> PairingVerification.Failure.Rejected
                        relay is RelayLinkStatus.UpdateRequired -> PairingVerification.Failure.UpdateRequired
                        else -> null
                    }
                }.firstOrNull()
        }
    // A status stream that ends cannot verify the host; report it like the deadline.
    return outcome ?: PairingVerification.Failure.Deadline
}

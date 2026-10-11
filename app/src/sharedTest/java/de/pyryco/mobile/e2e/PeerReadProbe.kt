package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Failure evidence for B's peer read; the caught row assertion retains its stage and semantics. */
internal fun peerReadProbeFailure(
    failure: AssertionError,
    confirmed: ULong,
    peerCheckpoint: ULong,
    conversationId: String,
    repository: () -> ConversationRepository?,
    daemonMarks: () -> ConversationReadMarks?,
    projectedUnread: () -> Boolean,
): AssertionError {
    val phoneRepository = samplePeerReadProbe(repository)
    val phone = phoneRepository?.let { samplePeerReadProbe { runBlocking { it.observeReadMarks(conversationId).first() } } }
    val daemon = samplePeerReadProbe(daemonMarks)
    val projection = samplePeerReadProbe(projectedUnread)
    return AssertionError(
        "event=peer_read_probe daemon_confirmed_target=${confirmed >= peerCheckpoint} " +
            "phone_repository_available=${phoneRepository != null} " +
            "phone_marks_available=${phone != null} daemon_marks_available=${daemon != null} " +
            "daemon_read_covers_latest=${daemon?.readUpTo?.let { read -> daemon.latestEntryId?.let { it <= read } } ?: "unknown"} " +
            "phone_received_confirmation=${phone?.readUpTo?.let { it >= confirmed } ?: "unknown"} " +
            "phone_read_covers_latest=${phone?.readUpTo?.let { read -> phone.latestEntryId?.let { it <= read } } ?: "unknown"} " +
            "phone_latest_exceeds_daemon=${phone?.latestEntryId?.let { latest ->
                daemon?.latestEntryId?.let { latest > it }
            } ?: "unknown"} " +
            "projected_unread=${projection ?: "unknown"}",
        failure,
    )
}

/** A failed sample never masks the row assertion or prevents the remaining independent samples. */
private fun <T> samplePeerReadProbe(sample: () -> T): T? =
    try {
        sample()
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        null
    } catch (_: AssertionError) {
        null
    }

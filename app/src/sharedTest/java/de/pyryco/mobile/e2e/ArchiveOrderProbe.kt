package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus

/** Fixed scenario labels; titles, ids and payloads never enter this evidence. */
internal enum class ArchiveOrderStage {
    ListArrival,
    CompatibilityReadiness,
    InitialList,
    CreateA,
    RenameA,
    LeaveA,
    BeforeB,
    CreateB,
    RenameB,
    ArchiveB,
    MembershipB,
    ReopenA,
    ArchiveA,
    MembershipA,
    OpenArchive,
    DiscussionTab,
    RelativeOrder,
    FirstRow,
}

internal data class ArchiveOrderObservation(
    val targetRegistered: Boolean,
    val targetSelected: Boolean,
    val status: ConnectionStatus?,
    val repositoryPresent: Boolean,
    val rowsLoaded: Boolean,
    val repositoryGeneration: Int = 0,
)

/** Bounded test-only state evidence, attached to the original failure before fixture cleanup. */
internal class ArchiveOrderProbe(
    private val observe: () -> ArchiveOrderObservation,
    private val record: (String) -> Unit,
    private val elapsedMs: () -> Long,
) {
    private val lock = Any()
    private val events = ArrayDeque<String>()
    private var stage = ArchiveOrderStage.ListArrival
    private var samples = 0

    fun <T> step(
        next: ArchiveOrderStage,
        block: () -> T,
    ): T {
        synchronized(lock) { stage = next }
        event("start")
        return try {
            block().also { event("complete") }
        } catch (failure: Throwable) {
            event("failure")
            val evidence = synchronized(lock) { events.joinToString("\n") }
            failure.addSuppressed(AssertionError("archive order observations (non-atomic):\n$evidence"))
            throw failure
        }
    }

    fun sample() {
        synchronized(lock) {
            if (samples >= MAX_EVENTS) return
            samples++
        }
        event("observe")
    }

    private fun event(outcome: String) {
        val current = synchronized(lock) { stage }
        val observation =
            try {
                val state = observe()
                "target_registered=${state.targetRegistered} target_selected=${state.targetSelected} " +
                    "relay=${relayCode(state.status?.relay)} daemon=${daemonCode(state.status?.pyrycode)} " +
                    "repository=${state.repositoryPresent} rows_loaded=${state.rowsLoaded} " +
                    "repository_generation=${state.repositoryGeneration.coerceIn(0, MAX_GENERATION)}"
            } catch (_: Throwable) {
                "observation=unavailable"
            }
        val elapsed =
            try {
                elapsedMs().coerceIn(0L, MAX_ELAPSED_MS)
            } catch (_: Throwable) {
                0L
            }
        val line = "event=archive_order stage=${current.name} outcome=$outcome elapsed_ms=$elapsed $observation"
        synchronized(lock) {
            if (events.size == MAX_EVENTS) events.removeFirst()
            events.addLast(line)
        }
        try {
            record(line)
        } catch (_: Throwable) {
            // Diagnostic output cannot replace a scenario failure or leak a logger's error message.
        }
    }

    private fun relayCode(status: RelayLinkStatus?): String =
        when (status) {
            null -> "Missing"
            RelayLinkStatus.Idle -> "Idle"
            RelayLinkStatus.Connecting -> "Connecting"
            RelayLinkStatus.Connected -> "Connected"
            is RelayLinkStatus.Reconnecting -> "Reconnecting"
            RelayLinkStatus.DaemonAbsent -> "DaemonAbsent"
            RelayLinkStatus.PairingRejected -> "PairingRejected"
            is RelayLinkStatus.UpdateRequired -> "UpdateRequired"
            RelayLinkStatus.Offline -> "Offline"
        }

    private fun daemonCode(status: PyrycodeLinkStatus?): String =
        when (status) {
            null -> "Missing"
            PyrycodeLinkStatus.Handshaking -> "Handshaking"
            PyrycodeLinkStatus.Connected -> "Connected"
            PyrycodeLinkStatus.Down -> "Down"
        }

    private companion object {
        const val MAX_EVENTS = 32
        const val MAX_GENERATION = 1_000
        const val MAX_ELAPSED_MS = 600_000L
    }
}

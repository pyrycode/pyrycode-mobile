package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import kotlin.time.TimeMark

internal data class OfflineRetrySnapshot(
    val registered: Boolean = false,
    val selectedTarget: Boolean = false,
    val repositoryReady: Boolean = false,
    val relay: RelayLinkStatus? = null,
    val daemon: PyrycodeLinkStatus? = null,
    val attempt: Int? = null,
    val backoffElapsedMs: Long? = null,
    val recoveryRemainingMs: Long? = null,
) {
    // Some status variants contain remote text. Only their static type enters evidence.
    fun describe(): String =
        "registered=$registered selected_target=$selectedTarget repository_ready=$repositoryReady " +
            "relay=${relay?.javaClass?.simpleName ?: "absent"} daemon=${daemon?.javaClass?.simpleName ?: "absent"} " +
            "attempt=$attempt backoff_elapsed_ms=$backoffElapsedMs recovery_remaining_ms=$recoveryRemainingMs"
}

internal class OfflineRetryDiagnostics(
    private val snapshot: () -> OfflineRetrySnapshot,
    private val emit: (String) -> Unit = {},
) {
    fun enter(stage: OfflineRetryStage) {
        runCatching { emit("event=offline_retry_stage stage=${stage.name} ${state()}") }
    }

    fun failed(
        stage: OfflineRetryStage,
        failure: Throwable,
    ) {
        val evidence = "event=offline_retry_failure stage=${stage.name} ${state()}"
        failure.addSuppressed(AssertionError(evidence))
        runCatching { emit(evidence) }
    }

    private fun state(): String = runCatching { snapshot().describe() }.getOrDefault("state=unavailable")
}

internal inline fun <T> offlineRetryStep(
    stage: OfflineRetryStage,
    diagnostics: OfflineRetryDiagnostics,
    block: () -> T,
): T {
    diagnostics.enter(stage)
    return try {
        block()
    } catch (failure: Throwable) {
        diagnostics.failed(stage, failure)
        throw failure
    }
}

internal fun offlineRetrySnapshot(
    supervisor: RelayConnectionSupervisor?,
    coordinator: RelayRepositoryCoordinator?,
    selectedTarget: Boolean,
    deadline: TimeMark?,
): OfflineRetrySnapshot {
    val backoff = supervisor?.backoffState?.value
    return OfflineRetrySnapshot(
        registered = supervisor != null,
        selectedTarget = selectedTarget,
        repositoryReady = coordinator?.currentRepository?.value != null,
        relay = supervisor?.relayStatus?.value,
        daemon = coordinator?.connectionStatus?.value?.pyrycode,
        attempt = backoff?.attempt,
        backoffElapsedMs = backoff?.startedAt?.elapsedNow()?.inWholeMilliseconds,
        recoveryRemainingMs = deadline?.let { (-it.elapsedNow()).inWholeMilliseconds },
    )
}

internal fun restoreOfflineRetryDaemon(
    diagnostics: OfflineRetryDiagnostics,
    scenarioFailure: Throwable?,
    restore: () -> Unit,
) {
    try {
        offlineRetryStep(OfflineRetryStage.RestoreDaemon, diagnostics, restore)
    } catch (cleanupFailure: Throwable) {
        if (scenarioFailure == null) throw cleanupFailure
        scenarioFailure.addSuppressed(cleanupFailure)
    }
}

package de.pyryco.mobile.e2e

import kotlinx.coroutines.CancellationException

internal enum class DirectShareStage {
    Fixtures,
    PeerAdmission,
    ListArrival,
    Readiness,
    RepositoryX,
    CreateX,
    RenameX,
    RepositoryY,
    CreateY,
    RenameY,
    PeerHistory,
    OpenX,
    ShortcutPublication,
    Capture,
    Staging,
    SourceDeletion,
    UnsentHistory,
    Send,
    PermissionHandling,
    HistoryX,
    Retrieval,
    HistoryY,
}

internal inline fun <T> directShareStep(
    stage: DirectShareStage,
    snapshot: () -> String,
    record: (String) -> Unit,
    block: () -> T,
): T {
    val event = "event=direct_share_stage stage=$stage"
    runCatching { record("$event status=started") }
    try {
        return block().also { runCatching { record("$event status=completed") } }
    } catch (failure: Throwable) {
        // The snapshot contains only caller-authored state codes/counts, never operation messages.
        val state = runCatching { snapshot().take(512) }.getOrDefault("diagnostic=unavailable")
        val evidence = "$event status=failed; $state"
        runCatching { record(evidence) }
        // Preserve the original cause; a deadline must fail the attempt rather than become a redial retry.
        failure.addSuppressed(AssertionError(evidence))
        if (failure is CancellationException) throw AssertionError(evidence, failure)
        throw failure
    }
}

package de.pyryco.mobile.e2e

import kotlinx.coroutines.withTimeout

/** Static operation names: diagnostics never contain settings, prompts or pairing material. */
internal enum class ReconnectFooterStage {
    AwaitList,
    AwaitConnection,
    ClearRememberedEffort,
    FixtureRepository,
    CreateChat,
    RenameChat,
    InitialSettings,
    OpenChat,
    FirstSend,
    FirstReply,
    FirstFooter,
    PreCutRepository,
    PreCutContext,
    CutLink,
    HeldContext,
    RestoreLink,
    ReconnectedFooter,
    ReconnectedSettings,
    Announcement,
    ReconnectedMenu,
    ReconnectedMark,
    ReconnectedEffort,
    ReconnectedPermission,
    SecondRepository,
    SettledContext,
    SecondSend,
    SecondReply,
    SecondContext,
    SecondFooter,
    ChangedModelMenu,
    ChangedModelPick,
    ChangedModelFooter,
    ChangedModelSettings,
    CleanupSettings,
    CleanupRememberedEffort,
}

internal suspend fun <T> reconnectFooterFixture(
    timeoutMs: Long,
    record: (String) -> Unit,
    snapshot: () -> String,
    create: suspend () -> T,
    rename: suspend (T) -> T,
): T =
    withTimeout(timeoutMs) {
        val created = reconnectFooterStep(ReconnectFooterStage.CreateChat, record, snapshot) { create() }
        reconnectFooterStep(ReconnectFooterStage.RenameChat, record, snapshot) { rename(created) }
    }

internal inline fun <T> reconnectFooterScenario(
    cleanup: () -> Unit,
    block: () -> T,
): T {
    var primaryFailure: Throwable? = null
    return try {
        block()
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        try {
            cleanup()
        } catch (cleanupFailure: Throwable) {
            val original = primaryFailure
            if (original == null) throw cleanupFailure
            original.addSuppressed(cleanupFailure)
        }
    }
}

internal inline fun <T> reconnectFooterStep(
    stage: ReconnectFooterStage,
    record: (String) -> Unit,
    snapshot: () -> String,
    block: () -> T,
): T {
    record("event=reconnect_footer stage=${stage.name} outcome=start ${snapshot()}")
    return try {
        block().also { record("event=reconnect_footer stage=${stage.name} outcome=complete ${snapshot()}") }
    } catch (failure: Throwable) {
        val state = snapshot()
        record("event=reconnect_footer stage=${stage.name} outcome=failure $state")
        throw AssertionError("reconnect footer stage=${stage.name} failed; $state", failure)
    }
}

package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

internal enum class PermissionHeldStage {
    PriorPeer, ListArrival, Connection, Conversations, ChatCreation, Composer, ChatIdentity,
    CurrentPeer, Send, PermissionArrival, WaitingDisplay, Approval, RunningDisplay, TurnEnd, LabelRemoval,
}

internal fun <T> permissionHeldStep(
    stage: PermissionHeldStage,
    snapshot: () -> String,
    record: (String) -> Unit,
    block: () -> T,
): T {
    val event = "event=permission_held_stage stage=$stage"
    record("$event status=started")
    try {
        return block().also { record("$event status=completed") }
    } catch (failure: Throwable) {
        record("$event status=failed")
        if (failure is TimeoutCancellationException || failure is ComposeTimeoutException ||
            failure is AssertionError && failure.cause is TimeoutCancellationException
        ) {
            throw AssertionError("Permission-held tool stage=$stage timed out; ${snapshot()}", failure)
        }
        throw failure
    }
}

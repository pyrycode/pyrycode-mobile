package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/** Fixed labels only: no permission content or pairing material enters diagnostics. */
internal enum class PermissionIsolationStage(
    val label: String,
) {
    PairPhone("pair answer host on phone"),
    CreateA("create and name conversation A"),
    CreateB("create and name conversation B"),
    OpenPeer("open answer peer"),
    OpenA("open A on phone"),
    SendA("send A permission turn"),
    AwaitA("await A modal_shown on peer"),
    DrawA("draw A permission on phone"),
    LeaveA("leave A while permission outstanding"),
    OpenB("open B on phone"),
    SendB("send B permission turn"),
    AwaitB("await B modal_shown on peer"),
    DrawB("draw B permission on phone"),
    LeaveB("leave B while permission outstanding"),
    ReopenA("reopen A on phone"),
    RedrawA("draw A permission after B raised its own"),
    ArmA("arm Allow once in A"),
    ConfirmA("confirm Allow once in A"),
    DismissA("await A phone answer modal_dismissed on peer"),
    RemoveA("remove A permission after phone answer"),
    LeaveAnsweredA("leave answered A"),
    ReopenB("reopen outstanding B on phone"),
    RedrawB("draw B permission after A answer"),
    AllowB("peer allows B and awaits modal_dismissed"),
    RemoveB("remove B permission after peer answer"),
    EndB("await B turn_end"),
}

/** Retain the exact deadline and cause; read content-free peer state only when a wait expires. */
internal inline fun <T> permissionIsolationStep(
    stage: PermissionIsolationStage,
    linkState: () -> String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("permission-isolation step '${stage.label}' timed out; ${linkState()}", e)
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("permission-isolation step '${stage.label}' timed out; ${linkState()}", e)
    }

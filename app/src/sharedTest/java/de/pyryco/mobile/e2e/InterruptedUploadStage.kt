package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.util.Collections
import java.util.IdentityHashMap

/** Fixed operation names: never derive these from attachment or message content. */
internal enum class InterruptedUploadStage {
    Fixtures,
    PeerAdmission,
    ChannelList,
    SetupReadiness,
    CreateChat,
    PeerHistory,
    OpenChat,
    AttachmentSelection,
    ArmCut,
    Send,
    ChunkCut,
    ComposerRetention,
    UnsentHistory,
    Reconnect,
    RetryReadiness,
    Retry,
    TurnCompletion,
    FinalHistory,
    Retrieval,
}

internal suspend inline fun <T> interruptedUploadStep(
    stage: InterruptedUploadStage,
    links: () -> String,
    record: (String) -> Unit,
    block: () -> T,
): T {
    val callerContext = currentCoroutineContext()
    record("event=interrupted_upload stage=${stage.name} outcome=start")
    return try {
        block().also { record("event=interrupted_upload stage=${stage.name} outcome=pass") }
    } catch (e: Throwable) {
        // The caller's deadline cancels this context; an operation's deadline only cancels its child.
        if (e is CancellationException && !callerContext.isActive) throw e
        callerContext.ensureActive()
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        if (e !is Exception && e !is AssertionError && e !is ComposeTimeoutException) throw e

        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = e
        var timeout = false
        while (cause != null && seen.add(cause)) {
            if (cause is TimeoutCancellationException || cause is ComposeTimeoutException) {
                timeout = true
                break
            }
            cause = cause.cause
        }
        val outcome = if (timeout) "timeout" else "failed"
        val detail = "event=interrupted_upload stage=${stage.name} outcome=$outcome; ${links()}"
        record(detail)
        throw AssertionError(detail, e)
    }
}

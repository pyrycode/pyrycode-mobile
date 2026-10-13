package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

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

internal inline fun <T> interruptedUploadStep(
    stage: InterruptedUploadStage,
    links: () -> String,
    record: (String) -> Unit,
    block: () -> T,
): T {
    record("event=interrupted_upload stage=${stage.name} outcome=start")
    return try {
        block().also { record("event=interrupted_upload stage=${stage.name} outcome=pass") }
    } catch (e: TimeoutCancellationException) {
        val detail = "event=interrupted_upload stage=${stage.name} outcome=timeout; ${links()}"
        record(detail)
        throw AssertionError(detail, e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ComposeTimeoutException) {
        val detail = "event=interrupted_upload stage=${stage.name} outcome=timeout; ${links()}"
        record(detail)
        throw AssertionError(detail, e)
    } catch (e: AssertionError) {
        val detail = "event=interrupted_upload stage=${stage.name} outcome=failed; ${links()}"
        record(detail)
        throw AssertionError(detail, e)
    } catch (e: Exception) {
        val detail = "event=interrupted_upload stage=${stage.name} outcome=failed; ${links()}"
        record(detail)
        throw AssertionError(detail, e)
    }
}

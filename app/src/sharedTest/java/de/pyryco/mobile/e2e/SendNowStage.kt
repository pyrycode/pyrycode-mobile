package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/** Fixed operation labels; never derive these from a prompt or a wire frame. */
internal enum class SendNowStage(
    val label: String,
) {
    AwaitList("await phone channel list"),
    AwaitConnection("await phone connection"),
    ReadConversations("read phone conversation list"),
    CreateChat("create phone chat"),
    AwaitComposer("await phone composer"),
    ReadChatId("read new chat identity"),
    SendWarmup("send phone warmup"),
    AwaitWarmup("await phone warmup reply"),
    ReadCapabilities("read fresh session capabilities"),
    OpenPeer("open peer"),
    SendHeldTurn("send held foreground turn"),
    AwaitHeldTool("await held Bash progress"),
    QueueMarker("queue phone marker"),
    DrawQueuedMarker("draw phone queued marker"),
    ObserveQueuedMarker("observe peer queued marker"),
    AwaitSendNow("await phone Send now control"),
    TapSendNow("tap phone Send now"),
    ObserveQueueRemoval("observe peer queue removal"),
    AwaitTurnEnd("await original turn end"),
    DrawDeliveredMarker("draw one delivered phone marker"),
    ReadDeliveredRows("read final phone rows"),
}

internal inline fun <T> sendNowStep(
    stage: SendNowStage,
    linkState: () -> String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("Send now step '${stage.label}' timed out; ${linkState()}", e)
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("Send now step '${stage.label}' timed out; ${linkState()}", e)
    } catch (e: AssertionError) {
        if (e.cause !is TimeoutCancellationException) throw e
        throw AssertionError("Send now step '${stage.label}' timed out; ${linkState()}", e)
    }

package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.decodeReplySuggestion
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Test-thread progress, with a lazy content-free witness on failure only. */
internal class ReplySuggestionProgress(
    private val evidence: () -> String = { "peer=not-used" },
) {
    var stage: ReplySuggestionStage = ReplySuggestionStage.ChannelList
        private set

    fun at(next: ReplySuggestionStage) {
        stage = next
    }

    inline fun <T> run(block: () -> T): T =
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            throw timedOut(e)
        } catch (e: ComposeTimeoutException) {
            throw timedOut(e)
        }

    fun timedOut(cause: Throwable): AssertionError = AssertionError("reply-suggestion checkpoint=$stage timed out; ${evidence()}", cause)
}

/** Summarize an authenticated peer snapshot without retaining or printing frame content. */
internal fun replySuggestionWireEvidence(frames: List<Envelope>): String {
    val suggestions = frames.filter { it.type == "reply_suggestion" }
    val decoded = suggestions.mapNotNull { if (it.eventId == null) decodeReplySuggestion(it.payload) else null }
    val offers = decoded.count { it.suggestedReply != null }
    val clears = decoded.size - offers
    val users =
        frames.count {
            it.type == "message" && ((it.payload as? JsonObject)?.get("role") as? JsonPrimitive)?.content == "user"
        }
    val ends = frames.count { it.type == "turn_end" }
    return "offers=$offers clears=$clears malformed=${suggestions.size - decoded.size} user_messages=$users turn_ends=$ends"
}

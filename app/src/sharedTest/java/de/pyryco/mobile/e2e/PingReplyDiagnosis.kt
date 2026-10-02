package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * #1456: when a live scenario's wait for a follow-up ping's reply bubble runs out, which layer lost the reply.
 * Each read is reduced to a count or a boolean here, so the failure message never carries claude-authored
 * text. Pure, so the JVM test drives every verdict without a device.
 */

/** What each layer held for one conversation when the reply wait ran out. */
internal data class PingReplyEvidence(
    /** `turn_end` frames the second client recorded for the conversation. */
    val peerTurnEnds: Int,
    /** Whether the second client recorded an assistant reply reading `ping` after the turns before it. */
    val peerSawReply: Boolean,
    /** Whether the phone's live repository held an assistant `ping` row; null when it could not be read. */
    val repositoryHoldsReply: Boolean?,
    /** Nodes matching `pingReplyMatcher()` in the unmerged tree. */
    val replyNodes: Int,
    /** Whether the single matching node was displayed; false when none or several matched. */
    val replyDisplayed: Boolean,
)

/**
 * The failure for a ping reply that never displayed, naming the first layer that did not hold it.
 * [expectedTurnEnds] is the `turn_end` count the peer holds once the ping's turn has ended.
 */
internal fun PingReplyEvidence.failure(
    expectedTurnEnds: Int,
    cause: Throwable? = null,
): AssertionError {
    val peerHeld = peerTurnEnds >= expectedTurnEnds || peerSawReply
    val layer =
        when {
            !peerHeld -> "host: the second client recorded neither the ping turn's turn_end nor its reply"
            repositoryHoldsReply == null -> "phone repository: unreadable, no live repository or its thread did not emit"
            !repositoryHoldsReply -> "phone repository: the second client got the reply, the phone's live repository holds none"
            replyNodes == 0 -> "thread screen: the repository holds the reply, no reply bubble is composed"
            replyNodes > 1 -> "thread screen: $replyNodes reply bubbles are composed"
            else -> "thread list: the reply bubble is composed but not displayed"
        }
    return AssertionError(
        "the ping reply never displayed; $layer " +
            "(peer turn_end $peerTurnEnds/$expectedTurnEnds, peer reply $peerSawReply, " +
            "repository reply $repositoryHoldsReply, reply nodes $replyNodes, displayed $replyDisplayed)",
        cause,
    )
}

/**
 * Whether [frames], one conversation's recorded frames in arrival order, carry an assistant reply reading
 * `ping` after the [afterTurnEnds]th `turn_end`: one turn's `assistant_delta` text joined in `seq` order, or
 * an assistant `message`. Frames before that `turn_end` belong to the turns the reply follows.
 */
internal fun followUpPingReplyRecorded(
    frames: List<Envelope>,
    afterTurnEnds: Int,
): Boolean {
    val turnEnds = frames.withIndex().filter { it.value.type == "turn_end" }.map { it.index }
    val later =
        when {
            afterTurnEnds <= 0 -> frames
            turnEnds.size < afterTurnEnds -> return false
            else -> frames.drop(turnEnds[afterTurnEnds - 1] + 1)
        }
    val deltaReplies =
        later
            .filter { it.type == "assistant_delta" }
            .groupBy { it.field("turn_id") }
            .values
            .map { deltas -> deltas.sortedBy { it.field("seq")?.toIntOrNull() ?: 0 }.joinToString("") { it.field("text").orEmpty() } }
    val messageReplies = later.filter { it.type == "message" && it.field("role") == "assistant" }.map { it.field("text").orEmpty() }
    return (deltaReplies + messageReplies).any { it.isPingReply() }
}

/** Whether [items], the phone repository's thread, holds an assistant row reading `ping`. */
internal fun holdsPingReply(items: List<ThreadItem>): Boolean =
    items.any { it is ThreadItem.MessageItem && it.message.role == Role.Assistant && it.message.content.isPingReply() }

private fun String.isPingReply(): Boolean = trim().equals("ping", ignoreCase = true)

// A diagnostic read must not throw over a malformed frame, so a non-primitive field reads as absent.
private fun Envelope.field(name: String): String? = ((payload as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

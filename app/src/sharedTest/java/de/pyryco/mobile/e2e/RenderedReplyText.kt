package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Fixture reply text as rendered, shared by the device gate and its deterministic screen probes. */
internal fun ComposeContentTestRule.renderedReplyText(expected: String): String {
    val replyPart = expected.split(" ").map { hasText(it, substring = true) }.reduce { left, right -> left or right }
    return onAllNodes(replyPart, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .joinToString(" ") { node ->
            node.config
                .getOrElse(SemanticsProperties.Text) { emptyList() }
                .joinToString(" ")
                .trim()
        }
}

/** Repository readiness and final assertions shared with probes of the complete device check. */
internal fun List<ThreadItem>.hasCompletedReplay(expected: String): Boolean =
    replaySegments().let { segments ->
        segments.isNotEmpty() && segments.all { !it.isStreaming } && segments.joinToString("") { it.content } == expected
    }

internal fun ComposeContentTestRule.assertOrderedReplay(
    items: List<ThreadItem>,
    expected: String,
    prompt: String,
) {
    assertEquals(expected, renderedReplyText(expected))
    val rows = items.filterIsInstance<ThreadItem.MessageItem>()
    // Preserve retained order; sorting by seq could hide a reordered replay.
    val segments = items.replaySegments()
    assertTrue("reply must be complete", items.hasCompletedReplay(expected))
    assertTrue("every reply segment must retain delta identities", segments.all { it.segment?.deltas?.isNotEmpty() == true })
    assertEquals("reply must belong to one wire turn", 1, segments.map { it.segment?.turnId }.distinct().size)
    assertEquals(expected, segments.joinToString("") { it.content })
    assertEquals(listOf(0, 1, 2), segments.flatMap { it.segment?.deltas.orEmpty() }.map { it.seq })
    val users = rows.filter { it.message.role == Role.User && it.message.content == prompt }
    assertEquals("initial user echo must appear exactly once", 1, users.size)
    // Durable history may put the echo between segments, but never after the completed reply.
    assertTrue(
        "initial user must precede reply completion",
        rows.indexOf(users.single()) < rows.indexOfLast { it.message.role == Role.Assistant },
    )
}

private fun List<ThreadItem>.replaySegments(): List<Message> =
    filterIsInstance<ThreadItem.MessageItem>().map { it.message }.filter { it.role == Role.Assistant }

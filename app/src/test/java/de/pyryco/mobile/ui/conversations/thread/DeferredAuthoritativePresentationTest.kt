package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredAuthoritativePresentationTest {
    @Test fun closureInvariantDefersAll8333HeldContentsUntilDemanded() {
        val snapshot = deferredLargeSnapshot()
        val converted = mutableListOf<Long>()
        val p = projectDeferredAuthoritativeThread("host", "conversation", snapshot, onFullConversion = { converted += it })
        assertTrue(converted.isEmpty())
        val root =
            p.rows
                .filterIsInstance<ThreadRow.Delivered>()
                .first()
                .messageId()
        val folded = foldToolRuns(p.rows, emptySet())
        val closed = p.demandRows(folded, emptySet())
        assertEquals(3, closed.size) // launch, root headline and child-run headline
        assertTrue(converted.isEmpty())
        val open = p.demandRows(p.rows, setOf(root))
        assertEquals(8334, open.size)
        assertEquals(8332, converted.size) // root body and prose; nested tool bodies remain closed
        assertSame(snapshot, p.sourceSnapshot)
        assertEquals(snapshot.items.size, p.shownRowRevisions.size)
        converted.clear()
        assertEquals(closed, p.demandRows(folded, emptySet()))
        assertTrue(converted.isEmpty())
    }

    private fun ThreadRow.Delivered.messageId() = (item as ThreadItem.MessageItem).message.id

    @Test fun groupedAncestorInvariantSeparatelyPlacedNestedAgentCannotEscapeOuterClosure() {
        val converted = mutableListOf<Long>()
        val p = projectDeferredAuthoritativeThread("host", "conversation", deferredNestedSnapshot(), onFullConversion = { converted += it })
        val outer = "${p.scope}:2"
        val inner = "${p.scope}:5"
        val closed = p.demandRows(foldToolRuns(p.rows, setOf(inner)), setOf(inner))
        assertEquals(3, closed.size)
        assertTrue(converted.isEmpty())
        assertEquals(4, p.demandRows(foldToolRuns(p.rows, setOf(outer)), emptySet()).count { it is ThreadRow.Delivered })
        assertTrue(converted.isEmpty())
        val opened = p.demandRows(foldToolRuns(p.rows, setOf(outer, inner)), setOf(inner))
        assertEquals(listOf(5L, 6L), converted)
        assertTrue(opened.any { it is ThreadRow.AgentStartMarker && it.agentId == "${p.scope}:3" })
        converted.clear()
        p.demandRows(foldToolRuns(p.rows, setOf(inner)), setOf(inner))
        assertTrue(converted.isEmpty())
    }

    @Test fun summaryInvariantPreservesHeadlinesAndStatusWithoutCopyingExpandedInputOrOutput() {
        val snapshot = deferredLargeSnapshot()
        val p = projectDeferredAuthoritativeThread("host", "conversation", snapshot)
        val tool = p.rows.filterIsInstance<ThreadRow.Delivered>().first { it.listKey(0).endsWith(":2") }
        val call = (tool.item as ThreadItem.MessageItem).message.toolCall ?: error("missing summary")
        assertEquals(mapOf("file_path" to "closed-file"), call.inputFields)
        assertEquals("", call.output)
        assertTrue(!p.suppliesReadSight(tool, emptySet()))
        assertTrue(p.suppliesReadSight(tool, setOf("${p.scope}:2")))
        val opened = p.demandRows(listOf(tool), setOf("${p.scope}:2"))
        assertEquals(0, opened.size) // its loaded ancestor has been omitted
        val full =
            p
                .demandRows(
                    p.rows,
                    setOf("${p.scope}:2"),
                ).filterIsInstance<ThreadRow.Delivered>()
                .first { it.listKey(0).endsWith(":2") }
        val fullCall = (full.item as ThreadItem.MessageItem).message.toolCall ?: error("missing body")
        assertEquals("hidden-output", fullCall.output)
        assertEquals("hidden-input", fullCall.inputFields["body"])
    }

    @Test fun nestedInvariantClosedAncestorsOverrideRetainedBodyAndRunExpansion() {
        val facts =
            authoritativeSnapshot(authoritativeAgent(1), authoritativeAgent(2, parent = 1, task = null), authoritativeItem(3, parent = 2))
        val converted = mutableListOf<Long>()
        val p = projectDeferredAuthoritativeThread("host", "conversation", facts, onFullConversion = { converted += it })
        val roots =
            p.rows
                .filterIsInstance<ThreadRow.Delivered>()
                .filter { it.isToolRow() }
                .map { it.messageId() }
        val withoutNested = p.rows.filterNot { it is ThreadRow.Delivered && it.messageId() == roots[1] }
        assertEquals(2, p.demandRows(withoutNested, setOf(roots[1])).size)
        assertTrue(converted.isEmpty())
        converted.clear()
        val nestedClosed = p.demandRows(p.rows, setOf(roots[0]))
        assertEquals(4, nestedClosed.size)
        assertEquals(listOf(1L, 3L), converted)
        converted.clear()
        assertEquals(4, p.demandRows(p.rows, roots.toSet()).size)
        assertEquals(listOf(1L, 2L, 3L), converted)
        converted.clear()
        val folded = foldToolRuns(p.rows, emptySet())
        p.demandRows(folded, roots.toSet())
        assertEquals(listOf(1L, 2L, 3L), converted)
    }

    @Test fun retentionInvariantClosureCannotSupplySightOrChangeCoverageAndSendFeedback() {
        val snapshot =
            authoritativeSendTail(
                "sending",
            ).copy(items = listOf(authoritativeAgent(1), authoritativeItem(2, parent = 1)), checkpoint = 4)
        val p = projectDeferredAuthoritativeThread("host", "conversation", snapshot)
        val rows = p.demandRows(p.rows, emptySet())
        assertTrue(rows.last() is ThreadRow.Queued)
        assertEquals(2, p.shownRowRevisions.size)
        val marks = ConversationReadMarks(threadSupported = true, lastShownVersion = 2u, readUpTo = 0u, latestEntryId = null)
        assertEquals(null, p.readCheckpoint(p.newestReadKey, marks, emptyMap()))
        assertSame(snapshot, p.sourceSnapshot)
        assertEquals(4L, p.certifiedCheckpoint)
    }

    @Test fun identityInvariantReplacementCannotDemandCollidingItemIdsAndReopenUsesNewestRevision() {
        val old =
            projectDeferredAuthoritativeThread(
                "host",
                "conversation",
                authoritativeSnapshot(authoritativeAgent(1), authoritativeItem(2, parent = 1)),
            )
        val root =
            old.rows
                .filterIsInstance<ThreadRow.Delivered>()
                .first()
                .messageId()
        val replacements =
            listOf(
                Triple("other", "conversation", old.sourceSnapshot),
                Triple("host", "other", old.sourceSnapshot),
                Triple("host", "conversation", old.sourceSnapshot.copy(epoch = "replacement")),
                Triple("host", "conversation", old.sourceSnapshot.copy(generation = old.sourceSnapshot.generation + 1)),
            )
        replacements.forEach { (host, conversation, snapshot) ->
            val p = projectDeferredAuthoritativeThread(host, conversation, snapshot)
            val rows = p.demandRows(foldToolRuns(p.rows, emptySet()), setOf(root))
            assertEquals(3, rows.size)
            assertTrue(!p.suppliesReadSight(rows.filterIsInstance<ThreadRow.Delivered>().first(), setOf(root)))
        }
        val revised =
            old.sourceSnapshot.copy(
                items =
                    listOf(
                        authoritativeAgent(1, active = false, endedOrder = 5),
                        authoritativeItem(2, parent = 1, rev = 8, content = """{"text":"newest"}"""),
                    ),
            )
        val p = projectDeferredAuthoritativeThread("host", "conversation", revised)
        val demanded = p.demandRows(p.rows, setOf(root)).filterIsInstance<ThreadRow.Delivered>()
        assertEquals("newest", (demanded.last().item as ThreadItem.MessageItem).message.content)
        assertEquals(0, p.tasks.liveCount)
        assertTrue(
            p.rows
                .filterIsInstance<ThreadRow.AgentStartMarker>()
                .single()
                .finished,
        )
        assertEquals(8L, p.shownRowRevisions[demanded.last().listKey(0)])
    }
}

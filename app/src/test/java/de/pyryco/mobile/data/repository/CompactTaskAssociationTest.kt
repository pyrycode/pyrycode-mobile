package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.toSavedAuthoritativeThread
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.decodeThreadQueryReply
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.authoritativeAgent
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.projectDeferredAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompactTaskAssociationTest {
    private val oldSink = RelayLog.sink

    @Before fun quiet() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restore() {
        RelayLog.sink = oldSink
    }

    private fun agent(
        id: Long = 17,
        revision: Long = id,
        task: String? = "task-$id",
        active: Boolean = true,
        parent: Long = 0,
    ) = JsonObject(
        CompactRootFixture.header(id, rev = revision, kind = "agent") +
            mapOf(
                "parent" to JsonPrimitive(parent),
                "active" to JsonPrimitive(active),
                "status" to JsonPrimitive(if (active) "running" else "finished"),
                "session" to JsonPrimitive("old-session"),
                "ended_order" to JsonPrimitive(if (active) 0 else 80),
            ) + task?.let { mapOf("task_id" to JsonPrimitive(it)) }.orEmpty(),
    )

    @Test fun coldInitialAndOlderAgentSummariesKeepExactTasksAndNavigationWithoutDetails() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            val exact = " \t任务-é\n "
            f.push(f.batch(headers = listOf(agent(task = exact))))
            f.push(f.marker())
            runCurrent()
            assertTrue(f.repo.requestOlderAuthoritativeThread("c"))
            f.push(f.batch(headers = listOf(agent(5, task = "older", active = false))))
            f.push(f.marker(1, 10, false))
            runCurrent()
            val converted = mutableListOf<Long>()
            val p =
                projectDeferredAuthoritativeThread("h", "c", f.repo.authoritativeThreads.current("h", "c"), onFullConversion = {
                    converted +=
                        it
                })
            assertEquals(listOf("older", exact), p.tasks.tasks.map { it.taskId })
            assertEquals(1, p.tasks.liveCount)
            val launches = p.rows.filterIsInstance<ThreadRow.AgentStartMarker>()
            assertEquals(2, launches.size)
            assertEquals(setOf("${p.scope}:5", "${p.scope}:17"), launches.map { it.agentId }.toSet())
            assertTrue(launches.single { it.agentId.endsWith(":5") }.finished)
            assertFalse(launches.single { it.agentId.endsWith(":17") }.finished)
            assertEquals(80L, p.backgroundBlocks.getValue("${p.scope}:5").settledOrder)
            assertEquals("msg:${p.scope}:17", p.rows.last().listKey(0))
            assertTrue(converted.isEmpty())
            assertTrue(f.details.isEmpty())
        }

    @Test fun replacementEmptyAndUnknownNeverBorrowRetainedFullAssociation() =
        runTest {
            val store = AuthoritativeThreadStore()
            store.establishComplete("h", "c", "e", 100, listOf(authoritativeAgent(17, task = "saved-task")), 0)
            for ((offset, task) in listOf("current", "", null, " \t ", "reassociated").withIndex()) {
                val f = CompactRootFixture(backgroundScope, store = store)
                f.repo.openAuthoritativeThread("c")
                runCurrent()
                f.push(f.batch(headers = listOf(agent(revision = 17L + offset, task = task))))
                f.push(f.marker())
                runCurrent()
                val snapshot = store.current("h", "c")
                val p = projectDeferredAuthoritativeThread("h", "c", snapshot)
                val expected = task?.takeUnless { it.isEmpty() }?.let(::listOf).orEmpty()
                assertEquals("replacement $offset", expected, p.tasks.tasks.map { it.taskId })
                assertEquals(expected.size, p.rows.filterIsInstance<ThreadRow.AgentStartMarker>().size)
                assertEquals(expected.size, p.backgroundBlocks.size)
                assertEquals(1, snapshot.items.size)
                assertTrue(f.details.isEmpty())
            }
        }

    @Test fun malformedAssociationRejectsBothRootAndDirectChildRepliesWithoutCoercion() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            for (value in listOf(JsonPrimitive(17), JsonPrimitive(true), JsonNull, JsonObject(emptyMap()), JsonArray(emptyList()))) {
                val malformed = JsonObject(agent() + ("task_id" to value))
                assertNull(decodeThreadQueryReply("thread_items", f.batch(headers = listOf(malformed))))
                val child = JsonObject(malformed + ("parent" to JsonPrimitive(1)))
                val raw =
                    CompactRootFixture.obj(
                        """{"conversation_id":"c","epoch":"e","version":100,"item_id":1,"rev":1,"children":[$child],"final":false}""",
                    )
                assertNull(decodeThreadQueryReply("thread_children", raw))
            }
        }

    @Test fun equalRevisionChildSummaryReplacesSavedTaskAssociation() = childSummaryOverSavedFull(" current-child ")

    @Test fun equalRevisionChildSummaryClearsSavedTaskAssociation() = childSummaryOverSavedFull("")

    @Test fun equalRevisionChildSummaryDoesNotBorrowUnknownTaskAssociation() = childSummaryOverSavedFull(null)

    private fun childSummaryOverSavedFull(task: String?) = runTest {
        for (restoreFirst in listOf(false, true)) {
            val f = CompactRootFixture(backgroundScope)
            val store = f.repo.authoritativeThreads
            val parent = ThreadItemDto.decode(JsonObject(authoritativeAgent(17, parent = 0).raw + ("subtree_version" to JsonPrimitive(90))))
            val fullChild = ThreadItemDto.decode(
                JsonObject(authoritativeAgent(18, task = "old-child", active = false, parent = 17).raw +
                    ("subtree_version" to JsonPrimitive(50))),
            )
            val saved = AuthoritativeThreadSnapshot(epoch = "e", items = listOf(parent, fullChild), completedVersion = 90, checkpoint = 90)
                .toSavedAuthoritativeThread()
            if (restoreFirst) store.restore("h", "c", saved.toSnapshot(), 0)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(JsonObject(agent() + ("subtree_version" to JsonPrimitive(90))))))
            f.push(f.marker())
            runCurrent()
            assertTrue(f.details.isEmpty())
            f.repo.demandAgentChildren("c", this, store.current("h", "c"), setOf(17))
            val childrenRequest = f.details.single()
            val child = JsonObject(agent(18, task = task, active = false, parent = 17) + ("subtree_version" to JsonPrimitive(90)))
            for (final in listOf(false, true)) {
                val children = if (final) "" else child.toString()
                val more = if (final) ",\"more\":false" else ""
                f.push(
                    CompactRootFixture.obj(
                        """{"conversation_id":"c","epoch":"e","version":100,"item_id":17,"rev":17,"children":[$children],"final":$final$more}""",
                    ), childrenRequest, "thread_children",
                )
            }
            runCurrent()
            if (!restoreFirst) {
                assertEquals(AuthoritativeThreadOutcome.Applied, store.restore("h", "c", saved.toSnapshot(), 0))
            }
            val snapshot = store.current("h", "c")
            val current = snapshot.expansionItems.single { it.id == 18L }
            assertTrue("current child metadata wins over equal-revision saved content", current.summaryPayload != null)
            val projection = projectDeferredAuthoritativeThread("h", "c", snapshot)
            val expected = setOf("task-17") + task?.takeUnless { it.isEmpty() }?.let(::setOf).orEmpty()
            assertEquals(expected, projection.tasks.tasks.map { it.taskId }.toSet())
            assertEquals(1, projection.tasks.liveCount)
            assertEquals(expected.size, projection.rows.filterIsInstance<ThreadRow.AgentStartMarker>().size)
            if (!task.isNullOrEmpty()) {
                assertTrue(projection.tasks.tasks.single { it.taskId == task }.isFinished)
                assertEquals(80L, projection.backgroundBlocks.getValue("${projection.scope}:18").settledOrder)
            }
            assertFalse("matching parent freshness cannot certify mismatching child freshness", snapshot.hasToolBody(current))
            assertEquals(saved.items, snapshot.toSavedAuthoritativeThread().items)
            assertEquals(listOf("thread_read_children"), f.details.map { it.type })
            val row = projection.rows.filterIsInstance<ThreadRow.Delivered>().single { it.listKey(0).endsWith(":18") }
            assertFalse(projection.suppliesReadSight(row, setOf(row.listKey(0))))
            assertFalse(projection.matchesReadContents(row.listKey(0), row.item, 18))
            f.repo.demandToolBodies("c", this, snapshot, setOf(18))
            val bodyRequest = f.details.last()
            assertEquals(listOf("thread_read_children", "thread_read_tool_body"), f.details.map { it.type })
            val opening = store.current("h", "c")
            store.restore("h", "c", saved.toSnapshot(), 0)
            assertEquals(opening.bodyLoads, store.current("h", "c").bodyLoads)
            assertEquals(opening.detailValidity.serial(18), store.current("h", "c").detailValidity.serial(18))
            for (final in listOf(false, true)) {
                val content = if (final) "" else ",\"content\":{\"output\":\"current child body\"}"
                f.push(
                    CompactRootFixture.obj(
                        """{"conversation_id":"c","epoch":"e","version":100,"item_id":18,"rev":18,"kind":"agent","final":$final$content}""",
                    ), bodyRequest, "thread_tool_body",
                )
            }
            runCurrent()
            val refreshed = store.current("h", "c")
            assertTrue(refreshed.hasToolBody(current))
            assertFalse(refreshed.copy(bodyContents = emptyMap()).hasToolBody(current))
            assertEquals(snapshot.checkpoint, refreshed.checkpoint)
            assertEquals(snapshot.coverage, refreshed.coverage)
            assertEquals(snapshot.completeCheckpoint, refreshed.completeCheckpoint)
            assertEquals(snapshot.completeCoverage, refreshed.completeCoverage)
            assertEquals(expected, projectDeferredAuthoritativeThread("h", "c", refreshed).tasks.tasks.map { it.taskId }.toSet())
        }
    }

    @Test fun explicitChildPageCarriesNestedTaskLifecycleWithoutBodyDemand() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(agent())))
            f.push(f.marker())
            runCurrent()
            assertTrue(f.details.isEmpty())
            f.repo.demandAgentChildren("c", this, f.repo.authoritativeThreads.current("h", "c"), setOf(17))
            val request = f.details.single()
            val child = agent(18, task = "nested", active = false, parent = 17)
            f.push(
                CompactRootFixture.obj(
                    """{"conversation_id":"c","epoch":"e","version":100,"item_id":17,"rev":17,"children":[$child],"final":false}""",
                ),
                request,
                "thread_children",
            )
            f.push(
                CompactRootFixture.obj(
                    """{"conversation_id":"c","epoch":"e","version":100,"item_id":17,"rev":17,"children":[],"final":true,"more":false}""",
                ),
                request,
                "thread_children",
            )
            runCurrent()
            val p = projectDeferredAuthoritativeThread("h", "c", f.repo.authoritativeThreads.current("h", "c"))
            assertEquals(
                setOf("task-17", "nested"),
                p.tasks.tasks
                    .map { it.taskId }
                    .toSet(),
            )
            assertEquals(1, p.tasks.liveCount)
            assertTrue(
                p.tasks.tasks
                    .single { it.taskId == "nested" }
                    .isFinished,
            )
            assertEquals(setOf("${p.scope}:17", "${p.scope}:18"), p.backgroundBlocks.keys)
            assertEquals(listOf("thread_read_children"), f.details.map { it.type })
        }
}

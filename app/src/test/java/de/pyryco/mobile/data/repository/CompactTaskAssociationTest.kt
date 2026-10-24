package de.pyryco.mobile.data.repository

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

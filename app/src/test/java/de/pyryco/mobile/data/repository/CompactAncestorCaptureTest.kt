package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.CompactRootFixture.Companion.header
import de.pyryco.mobile.data.repository.CompactRootFixture.Companion.message
import de.pyryco.mobile.data.repository.CompactRootFixture.Companion.obj
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.projectDeferredAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompactAncestorCaptureTest {
    private val oldSink = RelayLog.sink

    @Before fun quiet() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLog() {
        RelayLog.sink = oldSink
    }

    @Test fun olderRootAncestorKeepsCurrentTool() = runTest { current(false, "tool_call") }

    @Test fun olderRootAncestorKeepsCurrentAgent() = runTest { current(false, "agent") }

    @Test fun olderRootAncestorKeepsCurrentProse() = runTest { current(false, "assistant_message") }

    @Test fun olderChildAncestorKeepsCurrentTool() = runTest { current(true, "tool_call") }

    @Test fun olderChildAncestorKeepsCurrentAgent() = runTest { current(true, "agent") }

    @Test fun olderChildAncestorKeepsCurrentProse() = runTest { current(true, "assistant_message") }

    @Test fun firstRootAncestorStillFencesSavedBodyAfterFetchedEviction() = runTest { saved(false) }

    @Test fun firstChildAncestorStillFencesSavedBodyAfterFetchedEviction() = runTest { saved(true) }

    private fun full(kind: String) =
        ThreadItemDto.decode(
            JsonObject(
                message(20, rev = 110) +
                    mapOf("kind" to JsonPrimitive(kind), "parent" to JsonPrimitive(17)),
            ),
        )

    private fun assertRead(
        snapshot: AuthoritativeThreadSnapshot,
        usable: Boolean,
    ) {
        val p = projectDeferredAuthoritativeThread("h", "c", snapshot)
        val expanded = setOf("${p.scope}:10", "${p.scope}:17", "${p.scope}:20")
        val row = p.demandRows(p.rows, expanded).filterIsInstance<ThreadRow.Delivered>().single { it.listKey(0).endsWith(":20") }
        assertEquals(usable, p.suppliesReadSight(row, expanded))
        assertEquals(usable, p.matchesReadContents(row.listKey(0), row.item, 110))
    }

    private fun assertCertificates(
        before: AuthoritativeThreadSnapshot,
        after: AuthoritativeThreadSnapshot,
    ) {
        assertEquals(before.coverage, after.coverage)
        assertEquals(before.checkpoint, after.checkpoint)
        assertEquals(before.completeCoverage, after.completeCoverage)
        assertEquals(before.completeCheckpoint, after.completeCheckpoint)
    }

    private fun TestScope.start(childPage: Boolean): Pair<CompactRootFixture, Envelope> {
        val f = CompactRootFixture(backgroundScope)
        f.repo.openAuthoritativeThread("c")
        runCurrent()
        val root = if (childPage) 10L else 90L
        f.push(f.batch(headers = listOf(header(root, rev = 90, kind = "agent", facts = ",\"subtree_version\":90")), version = 90))
        f.push(f.marker(lower = root, upper = 101, version = 90))
        runCurrent()
        if (childPage) {
            f.repo.demandAgentChildren("c", this, f.repo.authoritativeThreads.current("h", "c"), setOf(10))
            return f to f.details.single()
        }
        assertTrue(f.repo.requestOlderAuthoritativeThread("c"))
        return f to f.roots.last()
    }

    private fun TestScope.ancestor(
        f: CompactRootFixture,
        request: Envelope,
        childPage: Boolean,
        version: Long = 100,
    ) {
        val summary =
            JsonObject(
                header(17, kind = "agent", facts = ",\"subtree_version\":$version") +
                    ("parent" to JsonPrimitive(if (childPage) 10 else 0)),
            )
        if (childPage) {
            val scope = "\"conversation_id\":\"c\",\"epoch\":\"e\",\"version\":$version,\"item_id\":10,\"rev\":90"
            f.push(obj("""{$scope,"children":[$summary],"final":false}"""), request, "thread_children")
            f.push(obj("""{$scope,"children":[],"final":true,"more":false}"""), request, "thread_children")
        } else {
            f.push(f.batch(headers = listOf(summary), version = version), request)
        }
        runCurrent()
    }

    private suspend fun TestScope.current(
        childPage: Boolean,
        kind: String,
    ) {
        val (f, request) = start(childPage)
        val store = f.repo.authoritativeThreads
        val full = full(kind)
        assertEquals(AuthoritativeThreadOutcome.Applied, store.applyBatch("h", "c", "e", 110, listOf(full), 0))
        val before = store.current("h", "c")
        assertRead(before, true)
        ancestor(f, request, childPage)
        val after = store.current("h", "c")
        assertEquals(full, after.items.single { it.id == 20L })
        assertTrue("older first-known ancestry cannot revoke current $kind", after.hasToolBody(full))
        assertRead(after, true)
        assertCertificates(before, after)
        val evicted = after.copy(bodyContents = emptyMap(), bodyLoads = emptyMap())
        assertTrue(evicted.hasToolBody(full))
        assertRead(evicted, true)
        val demandCount = if (childPage) 1 else 0
        assertEquals("unopened rows add no demand", demandCount, f.details.size)
        f.repo.demandToolBodies("c", this, after, setOf(20))
        runCurrent()
        assertEquals("current contents need no body refresh", demandCount, f.details.size)
        // A genuinely newer ancestor observation must still invalidate these contents.
        val changed =
            ThreadItemDto.decodeSummary(
                JsonObject(
                    header(17, rev = 17, kind = "agent", facts = ",\"subtree_version\":120") +
                        ("parent" to JsonPrimitive(if (childPage) 10 else 0)),
                ),
            )
        store.applyBatch("h", "c", "e", 120, listOf(changed), 0)
        val invalidated = store.current("h", "c")
        assertFalse(invalidated.hasToolBody(full))
        assertRead(invalidated, false)
        assertCertificates(after, invalidated)
    }

    private suspend fun TestScope.saved(childPage: Boolean) {
        val (f, request) = start(childPage)
        val store = f.repo.authoritativeThreads
        val full = full("tool_call")
        // Even a saved revision/capture newer than the arriving summary is not live authority.
        store.restore("h", "c", AuthoritativeThreadSnapshot(epoch = "e", items = listOf(full), completedVersion = 110, checkpoint = 110), 0)
        val before = store.current("h", "c")
        ancestor(f, request, childPage)
        val stale = store.current("h", "c")
        assertFalse(stale.hasToolBody(full))
        assertRead(stale, false)
        assertCertificates(before, stale)
        assertEquals(if (childPage) 1 else 0, f.details.size)
        if (!childPage) {
            f.push(f.marker(lower = 1, upper = 90, older = false), request)
            runCurrent()
        }
        val fetchBase = store.current("h", "c")
        f.repo.demandToolBodies("c", this, fetchBase, setOf(20))
        val body = f.details.last()
        assertEquals("thread_read_tool_body", body.type)
        for (final in listOf(false, true)) {
            val content = if (final) "" else ",\"content\":{\"output\":\"current\"}"
            val scope = "\"conversation_id\":\"c\",\"epoch\":\"e\",\"version\":110"
            f.push(obj("""{$scope,"item_id":20,"rev":110,"kind":"tool_call","final":$final$content}"""), body, "thread_tool_body")
        }
        runCurrent()
        val fetched = store.current("h", "c")
        assertTrue(fetched.hasToolBody(full))
        assertRead(fetched, true)
        val evicted = fetched.copy(bodyContents = emptyMap(), bodyLoads = emptyMap())
        assertFalse(evicted.hasToolBody(full))
        assertRead(evicted, false)
        assertCertificates(fetchBase, fetched)
    }
}

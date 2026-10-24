package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
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
class CompactChildAuthorityTest {
    private val oldSink = RelayLog.sink

    @Before fun quiet() { RelayLog.sink = { _, _, _ -> } }
    @After fun restoreLog() { RelayLog.sink = oldSink }

    private fun parent() = ThreadItemDto.decode(obj("""{"id":17,"rev":17,"order":17,"parent":0,"kind":"agent","status":"done","shown":true,"active":false,"content":{},"subtree_version":90}"""))

    private fun child(kind: String = "assistant_message", freshness: Long? = null) = ThreadItemDto.decode(
        JsonObject(CompactRootFixture.message(20) + mapOf("kind" to JsonPrimitive(kind), "parent" to JsonPrimitive(17)) + freshness?.let { mapOf("subtree_version" to JsonPrimitive(it)) }.orEmpty()),
    )

    private fun page(f: CompactRootFixture, request: Envelope, kind: String, freshness: Long?, version: Long, more: Boolean = false) {
        val raw = obj("""{"id":20,"rev":20,"order":20,"parent":17,"kind":"$kind","status":"done","active":false,"shown":true,"headline":"child","detail_omitted":true${freshness?.let { ",\"subtree_version\":$it" }.orEmpty()}}""")
        val scope = "\"conversation_id\":\"c\",\"epoch\":\"e\",\"version\":$version,\"item_id\":17,\"rev\":17"
        f.push(obj("""{$scope,"children":[$raw],"final":false}"""), request, "thread_children")
        val cursor = if (more) ",\"cursor\":{$scope,\"order\":20,\"id\":20}" else ""
        f.push(obj("""{$scope,"children":[],"final":true,"more":$more$cursor}"""), request, "thread_children")
    }

    private fun assertRead(snapshot: AuthoritativeThreadSnapshot, usable: Boolean) {
        val p = projectDeferredAuthoritativeThread("h", "c", snapshot)
        val row = p.rows.filterIsInstance<ThreadRow.Delivered>().single { it.listKey(0).endsWith(":20") }
        assertEquals(usable, p.suppliesReadSight(row, setOf(row.listKey(0))))
        assertEquals(usable, p.matchesReadContents(row.listKey(0), row.item, 20))
    }

    @Test fun compactSummaryKeepsCompleteProseReadAuthority() = runTest { prose(true) }
    @Test fun completeHostSummaryKeepsCompleteProseReadAuthority() = runTest { prose(false) }

    private suspend fun TestScope.prose(compact: Boolean) {
        val store = AuthoritativeThreadStore()
        val full = child()
        store.establishComplete("h", "c", "e", 90, listOf(parent(), full), 0)
        val f = CompactRootFixture(backgroundScope, store = store, compact = compact)
        f.repo.openAuthoritativeThread("c")
        runCurrent()
        if (compact) {
            f.push(f.batch(headers = listOf(CompactRootFixture.header(17, kind = "agent", facts = ",\"subtree_version\":90"))))
            f.push(f.marker())
        } else {
            f.push(JsonObject(f.batch(items = listOf(parent().raw, full.raw)).filterKeys { it !in setOf("headers", "projection") }))
            f.push(JsonObject(f.marker().filterKeys { it != "projection" }))
        }
        runCurrent()
        val before = store.current("h", "c")
        assertRead(before, true)
        f.repo.demandAgentChildren("c", this, before, setOf(17))
        page(f, f.details.single(), "assistant_message", null, 100)
        runCurrent()
        val summarized = store.current("h", "c")
        assertEquals(full.raw, summarized.items.single { it.id == 20L }.raw)
        assertTrue("current equal-revision prose remains complete", summarized.hasToolBody(summarized.expansionItems.single { it.id == 20L }))
        assertRead(summarized, true)
        assertEquals(before.coverage, summarized.coverage)
        assertEquals(before.checkpoint, summarized.checkpoint)
        assertEquals(before.completeCoverage, summarized.completeCoverage)
        assertEquals(before.completeCheckpoint, summarized.completeCheckpoint)
        store.applyBatch("h", "c", "e", 101, listOf(full), 0)
        val redelivered = store.current("h", "c")
        assertRead(redelivered, true)
        assertEquals("prose never demands a tool body", 1, f.details.size)
    }

    @Test fun refreshedChildFreshnessCannotReviveSavedBodyAfterEviction() = runTest { refresh(105) }
    @Test fun omittedChildFreshnessCannotReviveSavedBodyAfterEviction() = runTest { refresh(null) }

    private suspend fun TestScope.refresh(freshness: Long?) {
        val store = AuthoritativeThreadStore()
        store.establishComplete("h", "c", "e", 90, listOf(parent()), 0)
        val f = CompactRootFixture(backgroundScope, store = store)
        f.repo.openAuthoritativeThread("c")
        runCurrent()
        f.push(f.batch(headers = listOf(CompactRootFixture.header(17, kind = "agent", facts = ",\"subtree_version\":90"))))
        f.push(f.marker())
        runCurrent()
        val before = store.current("h", "c")
        f.repo.demandAgentChildren("c", this, before, setOf(17))
        page(f, f.details.last(), "tool_call", 50, 100, true)
        runCurrent()
        f.repo.demandAgentChildren("c", this, store.current("h", "c"), setOf(17), loadMore = 17)
        val failed = f.details.last()
        f.push(obj("""{"code":"thread.query_failed","message":"unavailable","retryable":true}"""), failed, "error")
        runCurrent()
        f.repo.demandAgentChildren("c", this, store.current("h", "c"), setOf(17), retry = 17)
        page(f, f.details.last(), "tool_call", freshness, 110)
        runCurrent()
        val queried = store.current("h", "c")
        val saved = child("tool_call", 50)
        store.restore("h", "c", AuthoritativeThreadSnapshot(epoch = "e", items = listOf(saved), completedVersion = 90, checkpoint = 90), 0)
        val restored = store.current("h", "c")
        assertEquals(queried.detailValidity.serial(20), restored.detailValidity.serial(20))
        assertEquals(queried.childPages, restored.childPages)
        assertFalse(restored.hasToolBody(restored.expansionItems.single { it.id == 20L }))
        assertRead(restored, false)
        f.repo.demandToolBodies("c", this, restored, setOf(20))
        val body = f.details.last()
        assertEquals("thread_read_tool_body", body.type)
        for (final in listOf(false, true)) {
            val content = if (final) "" else ",\"content\":{\"output\":\"current\"}"
            f.push(obj("""{"conversation_id":"c","epoch":"e","version":110,"item_id":20,"rev":20,"kind":"tool_call","final":$final$content}"""), body, "thread_tool_body")
        }
        runCurrent()
        val refreshed = store.current("h", "c")
        val row = refreshed.expansionItems.single { it.id == 20L }
        assertTrue(refreshed.hasToolBody(row))
        assertRead(refreshed, true)
        val evicted = refreshed.copy(bodyContents = emptyMap(), bodyLoads = emptyMap())
        assertFalse("freshness=$freshness cannot revive saved freshness 50", evicted.hasToolBody(row))
        assertRead(evicted, false)
        assertEquals(freshness, row.subtreeFreshness)
        assertEquals(listOf(saved), refreshed.items.filter { it.id == 20L })
        assertEquals(before.coverage, refreshed.coverage)
        assertEquals(before.checkpoint, refreshed.checkpoint)
        assertEquals(before.completeCoverage, refreshed.completeCoverage)
        assertEquals(before.completeCheckpoint, refreshed.completeCheckpoint)
    }
}

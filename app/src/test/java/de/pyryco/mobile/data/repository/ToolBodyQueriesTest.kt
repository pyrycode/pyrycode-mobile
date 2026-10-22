package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.projectDeferredAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class ToolBodyQueriesTest {
    private val oldSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun quietLog() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLog() {
        RelayLog.sink = oldSink
        assertTrue(logs.none { "private" in it || "Read file" in it })
    }

    private class Fixture(
        val kind: String = "tool_call",
        val store: AuthoritativeThreadStore = AuthoritativeThreadStore(),
        val now: () -> Long = { 0L },
    ) {
        val sent = mutableListOf<Envelope>()
        var support = true
        var sendWorks = true
        var id = 0L
        val lane =
            AuthoritativeThreadQueries("h", store, AuthoritativeThreadRoute("h", store), { true }, { ++id }, {
                sent += it
                sendWorks
            }, now, expansionEnabled = { support })

        init {
            if (store.current("h", "c").epoch == null) {
                store.reset("h", "c", "e")
                store.establishComplete("h", "c", "e", 23, listOf(tool(kind = kind)), store.current("h", "c").generation)
            }
        }

        fun demand(
            consumer: Any = this,
            retry: Long? = null,
        ) = lane.bodies("c", consumer, store.current("h", "c"), setOf(17), retry)

        fun load() = store.current("h", "c").bodyLoads[17L to 23L]

        fun reply(
            final: Boolean = false,
            request: Envelope = sent.last(),
            rev: Long = 23,
        ) = Envelope(
            90,
            "thread_tool_body",
            "now",
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c","epoch":"e","version":41,"item_id":17,"rev":$rev,"kind":"$kind","final":$final${if (final) "" else ",\"content\":{\"input\":\"\",\"output\":\"body\"}"}}""",
            ),
            inReplyTo = request.id,
        )

        fun error(request: Envelope = sent.last()) =
            Envelope(
                91,
                "error",
                "now",
                MobileJson.parseToJsonElement("""{"code":"thread.query_failed","message":"private","retryable":true}"""),
                inReplyTo = request.id,
            )

        fun body(): JsonObject {
            val snapshot = store.current("h", "c")
            val item = snapshot.items.single()
            return snapshot.bodyContents[item.id to item.rev] ?: item.content as JsonObject
        }
    }

    @Test fun completionInvariantDataIsStagedAndOnlyMatchingMarkerEnrichesWithoutCoverage() =
        runTest {
            val f = Fixture()
            val before = f.store.current("h", "c")
            f.demand()
            assertEquals(BodyLoad.Loading, f.load())
            assertEquals("thread_read_tool_body", f.sent.single().type)
            f.lane.accept(f.reply())
            assertNull(f.body()["output"])
            f.lane.accept(f.reply(true))
            assertEquals(JsonPrimitive("body"), f.body()["output"])
            val after = f.store.current("h", "c")
            assertSame("body enrichment preserves source facts", before.items, after.items)
            assertEquals(before.checkpoint, after.checkpoint)
            assertEquals(before.completedVersion, after.completedVersion)
            assertEquals(before.coverage, after.coverage)
            assertEquals(before.items.single().order, after.items.single().order)
            assertEquals(before.items.single().parent, after.items.single().parent)
            f.lane.releaseBodies(f)
            f.demand()
            assertEquals(1, f.sent.size)
        }

    @Test fun demandInvariantEqualConsumersCoalesceAndOneRetirementCannotCancelAnother() =
        runTest {
            val f = Fixture()
            val second = Any()
            repeat(3) { f.demand() }
            f.demand(second)
            assertEquals(1, f.sent.size)
            f.lane.releaseBodies(f)
            f.lane.accept(f.reply())
            f.lane.accept(f.reply(true))
            assertEquals(JsonPrimitive("body"), f.body()["output"])
        }

    @Test fun cancelledDataCannotPublishAndSharedLaneDrainsBeforeReuse() =
        runTest {
            val f = Fixture()
            f.demand()
            val abandoned = f.sent.single()
            f.lane.accept(f.reply())
            f.lane.releaseBodies(f)
            f.demand()
            assertEquals(1, f.sent.size)
            val foreignMarker = f.reply(true, abandoned)
            f.lane.accept(
                foreignMarker.copy(payload = JsonObject((foreignMarker.payload as JsonObject) + ("version" to JsonPrimitive(42)))),
            )
            assertEquals("a different capture cannot release the abandoned slot", 1, f.sent.size)
            f.lane.accept(f.reply(true, abandoned))
            assertNull(f.body()["output"])
            assertEquals(2, f.sent.size)
            f.lane.accept(f.reply(request = abandoned))
            assertNull(f.body()["output"])
            f.lane.accept(f.reply())
            f.lane.accept(f.reply(true))
            assertEquals(JsonPrimitive("body"), f.body()["output"])
        }

    @Test fun refusalAndSendFailureAreStickyUntilExplicitDemandedRetry() =
        runTest {
            for (sendFailure in listOf(false, true)) {
                val f = Fixture()
                f.sendWorks = !sendFailure
                f.demand()
                if (!sendFailure) f.lane.accept(f.error())
                assertEquals(BodyLoad.Retry, f.load())
                repeat(3) { f.demand() }
                assertEquals(1, f.sent.size)
                assertNull(f.body()["output"])
                assertFalse(f.store.current("h", "c").repairNeeded)
                f.sendWorks = true
                f.demand(retry = 17)
                assertEquals(2, f.sent.size)
            }
        }

    @Test fun ownershipInvariantChangedRevisionEpochDeletionAndForeignHostCannotEnrich() =
        runTest {
            for (change in listOf("revision", "epoch", "deletion", "disconnect")) {
                val f = Fixture()
                f.demand()
                val request = f.sent.single()
                f.lane.accept(f.reply())
                when (change) {
                    "revision" -> f.store.applyBatch("h", "c", "e", 24, listOf(tool(24)), f.store.current("h", "c").generation)
                    "epoch" -> f.store.reset("h", "c", "next")
                    "deletion" -> f.store.remove(requireNotNull(f.store.captureRemoval("h", "c", 17)))
                    else -> f.lane.abandon()
                }
                f.lane.accept(f.reply(true, request))
                assertTrue(
                    change,
                    f.store
                        .current("h", "c")
                        .bodyContents
                        .isEmpty(),
                )
                assertTrue(
                    change,
                    f.store
                        .current("h", "c")
                        .items
                        .none { (it.content as? JsonObject)?.get("output") != null },
                )
                assertTrue(
                    f.store
                        .current("other", "c")
                        .items
                        .isEmpty(),
                )
            }
        }

    @Test fun unsupportedAndCompleteRetainedBodiesIssueNoReads() =
        runTest {
            val f = Fixture()
            f.support = false
            f.demand()
            assertTrue(f.sent.isEmpty())
            f.support = true
            f.store.applyBatch("h", "c", "e", 24, listOf(tool(24, false)), f.store.current("h", "c").generation)
            f.demand()
            assertTrue(f.sent.isEmpty())
        }

    @Test fun queuedDemandCoalescesAndCollapseRemovesItBeforeLaneBecomesFree() =
        runTest {
            val f = Fixture()
            f.lane.open("c")
            repeat(3) { f.demand() }
            assertEquals(1, f.sent.size)
            f.lane.releaseBodies(f)
            f.lane.accept(f.error())
            assertEquals(1, f.sent.size)
            assertNull(f.body()["output"])
            assertNull(f.load())
        }

    @Test fun attentionRetryPreservesQueuedBodyEvenWhenRepairWasAlreadyMarked() =
        runTest {
            val f = Fixture()
            f.store.requestRepair("h", "c")
            f.lane.refreshAttention("c")
            val catchUp = f.sent.single()
            f.demand()
            val loading = f.store.current("h", "c")
            assertEquals(BodyLoad.Loading, f.load())
            f.lane.accept(f.error(catchUp))
            assertSame(loading, f.store.current("h", "c"))
            assertEquals(listOf("thread_catch_up", "thread_read_tool_body"), f.sent.map { it.type })
            f.lane.accept(f.reply())
            f.lane.accept(f.reply(true))
            assertEquals(JsonPrimitive("body"), f.body()["output"])
            assertNull(f.load())
            assertEquals("thread_catch_up", f.sent.last().type)
        }

    @Test fun fetchedBodyExpansionPreservesLiveOptionalNullClears() =
        runTest {
            val f = Fixture()
            AuthoritativeThreadRoute("h", f.store).accept(
                Envelope(
                    80,
                    "thread_item_changed",
                    "now",
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"c","epoch":"e","version":24,"item_id":17,"rev":24,"base_rev":23,"changes":{"session":null,"parent":null}}""",
                    ),
                ),
            )
            val held =
                f.store
                    .current("h", "c")
                    .items
                    .single()
            assertEquals(24L, held.rev)
            assertEquals(JsonNull, held.raw["session"])
            assertEquals(JsonNull, held.raw["parent"])
            f.demand()
            f.lane.accept(f.reply(rev = 24))
            f.lane.accept(f.reply(true, rev = 24))
            val p = projectDeferredAuthoritativeThread("h", "c", f.store.current("h", "c"))
            val row = p.demandRows(p.rows, setOf("${p.scope}:17")).single() as ThreadRow.Delivered
            assertEquals("body", (row.item as ThreadItem.MessageItem).message.toolCall?.output)
            assertSame(
                held,
                f.store
                    .current("h", "c")
                    .items
                    .single(),
            )
        }

    @Test fun withdrawnExpansionGrantCannotPublishStagedData() =
        runTest {
            val f = Fixture()
            f.demand()
            f.lane.accept(f.reply())
            f.support = false
            f.lane.accept(f.reply(true))
            assertNull(f.body()["output"])
            assertEquals(23L, f.store.current("h", "c").checkpoint)
        }

    @Test fun matchingAgentBodyAndAuthoritativeEmptyContentsAreAvailable() =
        runTest {
            for (kind in listOf("tool_call", "agent")) {
                val f = Fixture(kind)
                f.demand()
                val request = f.sent.single()
                val empty =
                    JsonObject(
                        (f.reply().payload as JsonObject) + ("content" to MobileJson.parseToJsonElement("""{"input":"","output":""}""")),
                    )
                f.lane.accept(f.reply().copy(payload = empty))
                f.lane.accept(f.reply(true))
                assertTrue(
                    f.store.current("h", "c").hasToolBody(
                        f.store
                            .current("h", "c")
                            .items
                            .single(),
                    ),
                )
                assertEquals(JsonPrimitive(""), f.body()["output"])
                // A reused source revision cannot erase its now-complete body or change placement.
                f.store.applyBatch("h", "c", "e", 41, listOf(tool(kind = kind)), f.store.current("h", "c").generation)
                assertEquals(JsonPrimitive(""), f.body()["output"])
                assertEquals(1, f.sent.size)
                assertEquals(1L, request.id)
            }
        }

    @Test fun timeoutIsFailureAndCannotAutomaticallyLoop() =
        runTest {
            val f = Fixture { testScheduler.currentTime }
            val expiry = backgroundScope.launch { f.lane.runExpiry() }
            f.demand()
            advanceTimeBy(31000)
            runCurrent()
            assertEquals(BodyLoad.Retry, f.load())
            f.demand()
            assertEquals(1, f.sent.size)
            expiry.cancel()
        }

    @Test fun connectionIdentityCollisionCannotAdmitARetiredOwnersLateBody() =
        runTest {
            val old = Fixture()
            old.demand()
            val oldRequest = old.sent.single()
            old.lane.accept(old.reply())
            old.lane.abandon()
            val replacement = Fixture(store = old.store)
            replacement.demand()
            assertEquals(oldRequest.id, replacement.sent.single().id)
            old.lane.accept(old.reply(true, oldRequest))
            assertTrue(
                old.store
                    .current("h", "c")
                    .bodyContents
                    .isEmpty(),
            )
            replacement.lane.accept(replacement.reply())
            replacement.lane.accept(replacement.reply(true))
            assertEquals(JsonPrimitive("body"), replacement.body()["output"])
        }

    @Test fun fragmentedProductionLaneStagesTheCompleteRendererUntilItsSeparateMarker() =
        runTest {
            val f = Fixture()
            f.demand()
            val raw = f.reply().payload as JsonObject
            val text = raw.toString()
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .apply {
                        update("thread_tool_body".toByteArray())
                        update(0.toByte())
                    }.digest(text.toByteArray())
                    .toHexString()
            for (index in 0..1) {
                val offset = if (index == 0) 0 else text.length / 2
                val data = if (index == 0) text.take(text.length / 2) else text.drop(offset)
                f.lane.accept(
                    f.reply().copy(
                        payload =
                            JsonObject(
                                (raw - setOf("content", "final", "kind")) +
                                    mapOf(
                                        "data" to JsonPrimitive(data),
                                        "continuation" to
                                            MobileJson.parseToJsonElement(
                                                """{"update_id":"$hash","index":$index,"offset":$offset,"total_bytes":${text.length},"final":${index == 1}}""",
                                            ),
                                    ),
                            ),
                    ),
                )
                assertTrue(
                    f.store
                        .current("h", "c")
                        .bodyContents
                        .isEmpty(),
                )
            }
            f.lane.accept(f.reply(true))
            assertEquals(JsonPrimitive("body"), f.body()["output"])
        }

    @Test fun hostsWithoutExpansionKeepTheirCompleteDeliveryPresentation() =
        runTest {
            val f = Fixture()
            f.support = false
            val full =
                ThreadItemDto.decode(
                    JsonObject(
                        tool(24, false).raw + (
                            "content" to
                                MobileJson.parseToJsonElement(
                                    """{"name":"Read","input":"future input","output":"future output"}""",
                                )
                        ),
                    ),
                )
            f.store.applyBatch("h", "c", "e", 24, listOf(full), f.store.current("h", "c").generation)
            val p = projectDeferredAuthoritativeThread("h", "c", f.store.current("h", "c"))
            val row = p.demandRows(p.rows, setOf("${p.scope}:17")).single() as ThreadRow.Delivered
            val tool = requireNotNull((row.item as ThreadItem.MessageItem).message.toolCall)
            assertEquals("Read file", tool.input)
            assertEquals("", tool.output)
            f.demand()
            assertTrue(f.sent.isEmpty())
        }

    companion object {
        internal fun tool(
            rev: Long = 23,
            missing: Boolean = true,
            kind: String = "tool_call",
        ): ThreadItemDto =
            ThreadItemDto.decode(
                MobileJson.parseToJsonElement(
                    """{"id":17,"rev":$rev,"kind":"$kind","order":5,"parent":0,"status":"done","active":false,"shown":true,"summary":"Read file","detail_omitted":$missing,"content":{"name":"Read"}}""",
                ) as JsonObject,
            )
    }
}

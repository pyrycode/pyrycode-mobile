package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.toSavedAuthoritativeThread
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.CompactRootFixture.Companion.header
import de.pyryco.mobile.data.repository.CompactRootFixture.Companion.message
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.projectDeferredAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompactRootPagesTest {
    private val oldSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun quiet() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restore() {
        RelayLog.sink = oldSink
        assertTrue(logs.none { "Root " in it || "retained" in it || "Held full" in it })
    }

    @Test fun activationInvariantProductionDefaultRemainsCompleteEvenWithAnUnexpectedCompactGrant() =
        runTest {
            val sent = mutableListOf<de.pyryco.mobile.data.network.Envelope>()
            val repo =
                RemoteConversationRepository(
                    object : SessionPump {
                        override val inbound = kotlinx.coroutines.flow.MutableSharedFlow<de.pyryco.mobile.data.network.Envelope>()

                        override fun send(envelope: de.pyryco.mobile.data.network.Envelope): Boolean {
                            sent += envelope
                            return true
                        }
                    },
                    backgroundScope,
                    negotiatedCapabilities = { setOf("thread", "interactive", "thread_expansion", "thread_compact") },
                    authoritativeHostId = "h",
                )
            assertTrue(repo.openAuthoritativeThread("c"))
            assertNull((sent.single().payload as kotlinx.serialization.json.JsonObject)["projection"])
        }

    @Test fun connectionInvariantReplacementCannotPageOrUnionAnotherConnectionsRootCertificate() =
        runTest {
            val store = AuthoritativeThreadStore()
            val old = CompactRootFixture(backgroundScope, store = store)
            old.repo.openAuthoritativeThread("c")
            runCurrent()
            old.push(old.batch(headers = listOf(header(17))))
            old.push(old.marker())
            runCurrent()
            val replacement = CompactRootFixture(backgroundScope, store = store)
            replacement.repo.openAuthoritativeThread("c")
            runCurrent()
            assertFalse("a new connection has not certified its own compact window", replacement.repo.requestOlderAuthoritativeThread("c"))
            replacement.push(replacement.batch(headers = listOf(header(30)), version = 110))
            replacement.push(replacement.marker(20, 111, version = 110))
            runCurrent()
            assertEquals(20uL, store.current("h", "c").coverage?.lowerOrder)
            assertTrue(replacement.repo.requestOlderAuthoritativeThread("c"))
            assertEquals(JsonPrimitive(20), (replacement.roots.last().payload as kotlinx.serialization.json.JsonObject)["before_order"])
        }

    @Test fun identityInvariantChildPageCannotReparentAnotherCompactRoot() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(header(17, kind = "agent"), header(18))))
            f.push(f.marker())
            runCurrent()
            val before = f.repo.authoritativeThreads.current("h", "c")
            f.repo.demandAgentChildren("c", this, before, setOf(17))
            val request = f.details.single()
            val child = kotlinx.serialization.json.JsonObject(header(18, rev = 80) + ("parent" to JsonPrimitive(17)))
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
            val after = f.repo.authoritativeThreads.current("h", "c")
            assertTrue(after.childPages.isEmpty())
            assertEquals(BodyLoad.Retry, after.childLoads[17L to 17L])
            assertEquals(before.coverage, after.coverage)
            assertEquals(before.checkpoint, after.checkpoint)
        }

    @Test fun orderingInvariantMixedInitialRootsUseTupleOrderAndNewestIdentityOnce() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            assertTrue(f.repo.openAuthoritativeThread("c"))
            runCurrent()
            assertEquals(JsonPrimitive("compact-v1"), (f.roots.single().payload as kotlinx.serialization.json.JsonObject)["projection"])
            f.push(f.batch(listOf(message(30), message(10)), listOf(header(20, 10, 80), header(15, 10), header(20, 10, 70))))
            runCurrent()
            val snapshot = f.repo.authoritativeThreads.current("h", "c")
            assertEquals(listOf(10L, 15L, 20L, 30L), snapshot.expansionItems.map { it.id })
            assertEquals(80L, snapshot.expansionItems.single { it.id == 20L }.rev)
            assertTrue(f.details.isEmpty())
            f.push(f.marker())
            runCurrent()
            assertEquals(1, f.roots.size)
            val converted = mutableListOf<Long>()
            val p =
                projectDeferredAuthoritativeThread(
                    "h",
                    "c",
                    f.repo.authoritativeThreads.current("h", "c"),
                    onFullConversion = { converted += it },
                )
            val prose = p.rows.filterIsInstance<ThreadRow.Delivered>().first { it.listKey(0) == "msg:${p.scope}:10" }
            assertTrue(p.suppliesReadSight(prose, emptySet()))
            assertTrue(p.matchesReadContents(prose.listKey(0), prose.item, 10))
            assertFalse(p.matchesReadContents(prose.listKey(0), prose.item, 11))
            assertEquals(listOf(10L, 30L), converted)
        }

    @Test fun completionInvariantOnlyRootMarkerAdmitsBoundedOlderDemandAndEmptyTerminalStops() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(listOf(message(30)), listOf(header(20, 10))))
            runCurrent()
            assertFalse(f.repo.requestOlderAuthoritativeThread("c"))
            f.push(f.marker())
            runCurrent()
            val initial = f.repo.authoritativeThreads.current("h", "c")
            assertEquals("compact-v1", initial.coverage?.projection)
            assertNull("root success is not saved complete-item coverage", initial.toSavedAuthoritativeThread().coverage)
            assertEquals(0L, initial.toSavedAuthoritativeThread().checkpoint)
            assertTrue(initial.bodyContents.isEmpty() && initial.childPages.isEmpty())
            assertTrue(f.repo.requestOlderAuthoritativeThread("c"))
            assertTrue(f.repo.requestOlderAuthoritativeThread("c"))
            runCurrent()
            assertEquals(2, f.roots.size)
            val page = f.roots.last().payload as kotlinx.serialization.json.JsonObject
            assertEquals(JsonPrimitive("e"), page["epoch"])
            assertEquals(JsonPrimitive(10), page["before_order"])
            f.push(f.batch(listOf(message(7, 5)), listOf(header(6, 5))))
            f.push(f.marker(5, 10))
            runCurrent()
            assertEquals(
                listOf(6L, 7L, 20L, 30L),
                f.repo.authoritativeThreads
                    .current("h", "c")
                    .expansionItems
                    .map { it.id },
            )
            assertEquals("completion never walks history", 2, f.roots.size)
            assertTrue(f.repo.requestOlderAuthoritativeThread("c"))
            runCurrent()
            f.push(f.marker(5, 5, false))
            runCurrent()
            assertFalse(f.repo.requestOlderAuthoritativeThread("c"))
            assertEquals(3, f.roots.size)
            assertTrue(f.details.isEmpty())
        }

    @Test fun retentionInvariantOmittedDetailsPreserveNewestFullFactsAndCacheCertificate() =
        runTest {
            val store = AuthoritativeThreadStore()
            val full =
                ThreadItemDto.decode(
                    CompactRootFixture.obj(
                        """{"id":17,"kind":"tool_call","order":17,"rev":80,"parent":0,"status":"done","active":false,"shown":true,"summary":"Held full","content":{"input":{"secret":"retained"},"output":"kept"},"subtree_version":90}""",
                    ),
                )
            store.establishComplete("h", "c", "e", 90, listOf(full), 0)
            val saved = store.current("h", "c").toSavedAuthoritativeThread()
            val f = CompactRootFixture(backgroundScope, store = store)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            assertEquals(JsonPrimitive(0), (f.roots.single().payload as kotlinx.serialization.json.JsonObject)["version"])
            f.push(f.batch(headers = listOf(header(17, rev = 70))))
            f.push(f.marker())
            runCurrent()
            val current = store.current("h", "c")
            assertEquals(full.raw, current.items.single().raw)
            assertEquals(80L, current.expansionItems.single().rev)
            assertEquals(saved.items, current.toSavedAuthoritativeThread().items)
            assertEquals(saved.checkpoint, current.toSavedAuthoritativeThread().checkpoint)
            assertTrue(current.hasToolBody(current.expansionItems.single()))
            assertTrue(f.details.isEmpty())
        }

    @Test fun summaryInvariantUnknownAndExplicitZeroFactsRemainDifferentAndFreshnessInvalidatesSameRevision() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(
                f.batch(
                    headers =
                        listOf(
                            header(17, facts = ",\"subtree_version\":50"),
                            header(18, facts = ",\"public_child_count\":0,\"has_public_children\":false,\"error_summary\":\"\""),
                        ),
                ),
            )
            f.push(f.marker())
            runCurrent()
            val before = f.repo.authoritativeThreads.current("h", "c")
            assertNull(before.expansionItems.single { it.id == 17L }.raw["has_public_children"])
            assertEquals(JsonPrimitive(false), before.expansionItems.single { it.id == 18L }.raw["has_public_children"])
            assertEquals(JsonPrimitive(""), before.expansionItems.single { it.id == 18L }.raw["error_summary"])
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(header(17, facts = ",\"subtree_version\":51")), version = 101))
            runCurrent()
            val after = f.repo.authoritativeThreads.current("h", "c")
            assertEquals(17L, after.expansionItems.single { it.id == 17L }.rev)
            assertTrue(after.detailValidity.serial(17) > before.detailValidity.serial(17))
            assertTrue(17L in after.detailValidity.children)
            f.push(f.batch(headers = listOf(header(17, rev = 30, facts = ",\"subtree_version\":52")), version = 101))
            runCurrent()
            val newer = f.repo.authoritativeThreads.current("h", "c")
            assertTrue(
                "a higher revision in the same captured query still invalidates descendants",
                newer.detailValidity.serial(17) > after.detailValidity.serial(17),
            )
            f.push(f.batch(headers = listOf(header(17, rev = 20, facts = ",\"subtree_version\":50")), version = 101))
            runCurrent()
            assertEquals(
                30L,
                f.repo.authoritativeThreads
                    .current("h", "c")
                    .expansionItems
                    .single { it.id == 17L }
                    .rev,
            )
            assertTrue(f.details.isEmpty())
        }

    @Test fun ownerInvariantForeignMarkersAndRetiredConnectionsCannotCertifyOrWalk() =
        runTest {
            for (mutation in listOf(
                "projection" to JsonPrimitive("complete"),
                "epoch" to JsonPrimitive("other"),
                "conversation_id" to JsonPrimitive("other"),
            )) {
                val f = CompactRootFixture(backgroundScope)
                f.repo.openAuthoritativeThread("c")
                runCurrent()
                f.push(f.batch(headers = listOf(header(17))))
                runCurrent()
                val marker = kotlinx.serialization.json.JsonObject(f.marker() + mutation)
                f.push(marker)
                runCurrent()
                assertFalse(f.repo.requestOlderAuthoritativeThread("c"))
                assertEquals(
                    0L,
                    f.repo.authoritativeThreads
                        .current("h", "c")
                        .checkpoint,
                )
                assertNull(
                    f.repo.authoritativeThreads
                        .current("h", "c")
                        .coverage,
                )
                assertTrue(
                    f.repo.authoritativeThreads
                        .current("h", "other")
                        .expansionItems
                        .isEmpty(),
                )
                assertEquals(1, f.roots.size)
            }
            val store = AuthoritativeThreadStore()
            val old = CompactRootFixture(backgroundScope, store = store)
            old.repo.openAuthoritativeThread("c")
            runCurrent()
            val retired = old.roots.single()
            old.repo.cancelAuthoritativeThread("c")
            old.push(old.batch(headers = listOf(header(17))), retired)
            old.push(old.marker(), retired)
            runCurrent()
            assertTrue(store.current("h", "c").expansionItems.isEmpty())
            val replacement = CompactRootFixture(backgroundScope, host = "other", store = store)
            replacement.repo.openAuthoritativeThread("c")
            runCurrent()
            replacement.push(replacement.batch(headers = listOf(header(17, rev = 80))))
            replacement.push(replacement.marker())
            runCurrent()
            assertEquals(
                80L,
                store
                    .current("other", "c")
                    .expansionItems
                    .single()
                    .rev,
            )
            assertTrue(store.current("h", "c").expansionItems.isEmpty())
        }

    @Test fun completeHostInvariantProjectionRemainsAbsentAndFullDeliveryStillCertifies() =
        runTest {
            val f = CompactRootFixture(backgroundScope, compact = false)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            assertNull((f.roots.single().payload as kotlinx.serialization.json.JsonObject)["projection"])
            f.push(kotlinx.serialization.json.JsonObject(f.batch(listOf(message(30))).filterKeys { it !in setOf("projection", "headers") }))
            f.push(kotlinx.serialization.json.JsonObject(f.marker(10, 101, false).filterKeys { it != "projection" }))
            runCurrent()
            val snapshot = f.repo.authoritativeThreads.current("h", "c")
            assertEquals(100L, snapshot.checkpoint)
            assertEquals(10uL, snapshot.coverage?.lowerOrder)
            assertEquals(listOf(30L), snapshot.items.map { it.id })
            assertTrue(f.details.isEmpty())
        }

    @Test fun heldFactsInvariantOlderHeaderCannotInvalidateNewerFullFactsRestoredBetweenBatches() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(header(17))))
            f.push(f.marker())
            runCurrent()
            val full =
                ThreadItemDto.decode(
                    CompactRootFixture.obj(
                        """{"id":17,"kind":"tool_call","order":17,"rev":80,"parent":0,"status":"done","active":false,"shown":true,"summary":"Held full","content":{"output":"kept"},"subtree_version":90}""",
                    ),
                )
            f.repo.authoritativeThreads.restore(
                "h",
                "c",
                AuthoritativeThreadSnapshot(
                    epoch = "e",
                    items = listOf(full),
                    completedVersion = 90,
                    checkpoint = 90,
                ),
                0,
            )
            val held = f.repo.authoritativeThreads.current("h", "c")
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(header(17, rev = 70)), version = 101))
            runCurrent()
            val after = f.repo.authoritativeThreads.current("h", "c")
            assertEquals(held.detailValidity.serial(17), after.detailValidity.serial(17))
            assertTrue(after.hasToolBody(after.expansionItems.single()))
            assertEquals(full.raw, after.items.single().raw)
        }
}

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

    @Test fun restoreInvariantSameRevisionSavedBodiesMustMatchCurrentCompactFreshnessBeforeOpening() =
        runTest {
            for (kind in listOf("tool_call", "agent")) {
                for (freshness in listOf(50L, 90L, null)) {
                    val f = CompactRootFixture(backgroundScope)
                    f.repo.openAuthoritativeThread("c")
                    runCurrent()
                    f.push(f.batch(headers = listOf(header(17, facts = ",\"subtree_version\":90", kind = kind))))
                    f.push(f.marker())
                    runCurrent()
                    val store = f.repo.authoritativeThreads
                    val before = store.current("h", "c")
                    val raw =
                        kotlinx.serialization.json.JsonObject(
                            header(17, kind = kind).filterKeys { it !in setOf("headline", "detail_omitted") } +
                                mapOf(
                                    "summary" to JsonPrimitive("Held full"),
                                    "content" to CompactRootFixture.obj("""{"output":"kept"}"""),
                                ) +
                                (freshness?.let { mapOf("subtree_version" to JsonPrimitive(it)) } ?: emptyMap()),
                        )
                    val saved =
                        AuthoritativeThreadSnapshot(
                            epoch = "e",
                            items = listOf(ThreadItemDto.decode(raw)),
                            completedVersion = 90,
                            checkpoint = 90,
                        ).toSavedAuthoritativeThread()
                    assertEquals(AuthoritativeThreadOutcome.Applied, store.restore("h", "c", saved.toSnapshot(), 0))
                    val restored = store.current("h", "c")
                    assertEquals(saved.items, restored.toSavedAuthoritativeThread().items)
                    assertEquals(before.checkpoint, restored.checkpoint)
                    assertEquals(before.coverage, restored.coverage)
                    assertEquals(before.detailValidity.observations, restored.detailValidity.observations)
                    val current = restored.expansionItems.single()
                    val fresh = freshness == 90L
                    assertEquals(fresh, restored.hasToolBody(current))
                    val p = projectDeferredAuthoritativeThread("h", "c", restored)
                    val row = p.rows.filterIsInstance<ThreadRow.Delivered>().single()
                    val key = row.listKey(0)
                    if (!fresh) {
                        assertTrue(17L in restored.detailValidity.children)
                        assertEquals(before.completedVersion, restored.detailValidity.floors[17])
                        assertFalse(p.suppliesReadSight(row, setOf(key)))
                        assertFalse(p.matchesReadContents(key, row.item, 17))
                    }
                    assertTrue("restore never demands detail", f.details.isEmpty())
                    f.repo.demandToolBodies("c", this, restored, setOf(17))
                    assertEquals(if (fresh) 0 else 1, f.details.size)
                    assertEquals(before.coverage, store.current("h", "c").coverage)
                    assertEquals(before.checkpoint, store.current("h", "c").checkpoint)
                    // Re-reading the same saved facts must not retire an in-flight explicit read.
                    val opening = store.current("h", "c")
                    store.restore("h", "c", saved.toSnapshot(), 0)
                    assertEquals(opening.detailValidity, store.current("h", "c").detailValidity)
                    if (!fresh) {
                        val request = f.details.single()
                        for (final in listOf(false, true)) {
                            val content = if (final) "" else ",\"content\":{\"output\":\"refreshed\"}"
                            f.push(
                                CompactRootFixture.obj(
                                    """{"conversation_id":"c","epoch":"e","version":100,"item_id":17,"rev":17,"kind":"$kind","final":$final$content}""",
                                ),
                                request,
                                "thread_tool_body",
                            )
                        }
                        runCurrent()
                        val refreshed = store.current("h", "c")
                        assertTrue(refreshed.hasToolBody(refreshed.expansionItems.single()))
                        assertEquals(saved.items, refreshed.toSavedAuthoritativeThread().items)
                        assertEquals(before.coverage, refreshed.coverage)
                        assertEquals(before.checkpoint, refreshed.checkpoint)
                        val withoutFetchedBody = refreshed.copy(bodyContents = emptyMap(), bodyLoads = emptyMap())
                        assertFalse(
                            "dropping fetched detail cannot certify the stale saved fact",
                            withoutFetchedBody.hasToolBody(withoutFetchedBody.expansionItems.single()),
                        )
                    }
                }
            }
        }

    @Test fun restoreInvariantStaleSavedFactsPreserveCurrentFetchedBodyWork() =
        runTest {
            for (completeBeforeRestore in listOf(false, true)) {
                val f = CompactRootFixture(backgroundScope)
                f.repo.openAuthoritativeThread("c")
                runCurrent()
                f.push(f.batch(headers = listOf(header(17, facts = ",\"subtree_version\":90"))))
                f.push(f.marker())
                runCurrent()
                val store = f.repo.authoritativeThreads
                f.repo.demandToolBodies("c", this, store.current("h", "c"), setOf(17))
                val request = f.details.single()

                fun finishBody() {
                    for (final in listOf(false, true)) {
                        val content = if (final) "" else ",\"content\":{\"output\":\"current fetched body\"}"
                        f.push(
                            CompactRootFixture.obj(
                                """{"conversation_id":"c","epoch":"e","version":100,"item_id":17,"rev":17,"kind":"tool_call","final":$final$content}""",
                            ),
                            request,
                            "thread_tool_body",
                        )
                    }
                }
                if (completeBeforeRestore) {
                    finishBody()
                    runCurrent()
                }
                val before = store.current("h", "c")
                val full =
                    ThreadItemDto.decode(
                        CompactRootFixture.obj(
                            """{"id":17,"kind":"tool_call","order":17,"rev":17,"parent":0,"status":"done","active":false,"shown":true,"summary":"Saved","content":{"output":"old"},"subtree_version":50}""",
                        ),
                    )
                val saved = AuthoritativeThreadSnapshot(epoch = "e", items = listOf(full), completedVersion = 90, checkpoint = 90)
                store.restore("h", "c", saved, 0)
                val restored = store.current("h", "c")
                assertEquals(before.detailValidity.serial(17), restored.detailValidity.serial(17))
                assertEquals(before.bodyLoads, restored.bodyLoads)
                assertEquals(before.bodyContents, restored.bodyContents)
                if (!completeBeforeRestore) {
                    finishBody()
                    runCurrent()
                }
                val after = store.current("h", "c")
                assertTrue(after.hasToolBody(after.expansionItems.single()))
                assertFalse(after.copy(bodyContents = emptyMap()).hasToolBody(after.expansionItems.single()))
                assertEquals(listOf(full), after.items)
                assertEquals(before.checkpoint, after.checkpoint)
                assertEquals(before.coverage, after.coverage)
                assertEquals(1, f.details.size)
            }
        }

    @Test fun restoreInvariantOlderSavedParentPreservesCurrentFetchedChildren() =
        runTest {
            for (completeBeforeRestore in listOf(false, true)) {
                val f = CompactRootFixture(backgroundScope)
                f.repo.openAuthoritativeThread("c")
                runCurrent()
                f.push(f.batch(headers = listOf(header(17, rev = 30, kind = "agent", facts = ",\"subtree_version\":90"))))
                f.push(f.marker())
                runCurrent()
                val store = f.repo.authoritativeThreads
                f.repo.demandAgentChildren("c", this, store.current("h", "c"), setOf(17))
                val request = f.details.single()

                fun finishChildren() {
                    f.push(
                        CompactRootFixture.obj(
                            """{"conversation_id":"c","epoch":"e","version":100,"item_id":17,"rev":30,"children":[],"final":true,"more":false}""",
                        ),
                        request,
                        "thread_children",
                    )
                }
                if (completeBeforeRestore) {
                    finishChildren()
                    runCurrent()
                }
                val before = store.current("h", "c")
                val full =
                    ThreadItemDto.decode(
                        CompactRootFixture.obj(
                            """{"id":17,"kind":"agent","order":17,"rev":17,"parent":0,"status":"done","active":false,"shown":true,"summary":"Saved","content":{},"subtree_version":50}""",
                        ),
                    )
                store.restore("h", "c", AuthoritativeThreadSnapshot(epoch = "e", items = listOf(full), completedVersion = 90), 0)
                val restored = store.current("h", "c")
                assertEquals(before.detailValidity.serial(17), restored.detailValidity.serial(17))
                assertEquals(before.childLoads, restored.childLoads)
                assertEquals(before.childPages, restored.childPages)
                if (!completeBeforeRestore) {
                    finishChildren()
                    runCurrent()
                }
                val after = store.current("h", "c")
                assertTrue(after.hasChildPage(after.expansionItems.single()))
                assertEquals(1, f.details.size)
                assertEquals(listOf(full), after.items)
            }
        }

    @Test fun restoreInvariantCompactAfterRestoreCannotReviveDescendantBodyWhenFetchedDetailIsEvicted() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            val store = f.repo.authoritativeThreads
            val root =
                ThreadItemDto.decode(
                    CompactRootFixture.obj(
                        """{"id":17,"kind":"agent","order":17,"rev":17,"parent":0,"status":"done","active":false,"shown":true,"summary":"Saved","content":{},"subtree_version":50}""",
                    ),
                )
            val child =
                ThreadItemDto.decode(
                    kotlinx.serialization.json.JsonObject(
                        message(20) + mapOf("kind" to JsonPrimitive("tool_call"), "parent" to JsonPrimitive(17)),
                    ),
                )
            val saved = AuthoritativeThreadSnapshot(epoch = "e", items = listOf(root, child), completedVersion = 90, checkpoint = 90)
            store.restore("h", "c", saved, 0)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(header(17, rev = 30, kind = "agent", facts = ",\"subtree_version\":90"))))
            f.push(f.marker())
            runCurrent()
            val before = store.current("h", "c")
            assertFalse(before.hasToolBody(before.expansionItems.single { it.id == 20L }))
            f.repo.demandToolBodies("c", this, before, setOf(20))
            val request = f.details.single()
            for (final in listOf(false, true)) {
                val content = if (final) "" else ",\"content\":{\"output\":\"refreshed\"}"
                f.push(
                    CompactRootFixture.obj(
                        """{"conversation_id":"c","epoch":"e","version":100,"item_id":20,"rev":20,"kind":"tool_call","final":$final$content}""",
                    ),
                    request,
                    "thread_tool_body",
                )
            }
            runCurrent()
            val after = store.current("h", "c")
            assertTrue(after.hasToolBody(after.expansionItems.single { it.id == 20L }))
            assertFalse(after.copy(bodyContents = emptyMap()).hasToolBody(after.expansionItems.single { it.id == 20L }))
            assertEquals(saved.items, after.items)
            store.applyBatch("h", "c", "e", 101, listOf(child), 0)
            val replaced = store.current("h", "c").copy(bodyContents = emptyMap())
            assertTrue(replaced.hasToolBody(replaced.expansionItems.single { it.id == 20L }))
        }

    @Test fun restoreInvariantOlderSavedParentCannotMakeDescendantBodiesCurrent() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(header(17, rev = 30, kind = "agent", facts = ",\"subtree_version\":90"))))
            f.push(f.marker())
            runCurrent()
            val store = f.repo.authoritativeThreads
            val before = store.current("h", "c")
            val root =
                ThreadItemDto.decode(
                    kotlinx.serialization.json.JsonObject(
                        header(17, kind = "agent", facts = ",\"subtree_version\":90")
                            .filterKeys { it !in setOf("headline", "detail_omitted") } +
                            mapOf("summary" to JsonPrimitive("Saved root"), "content" to CompactRootFixture.obj("{}")),
                    ),
                )
            val child =
                ThreadItemDto.decode(
                    kotlinx.serialization.json.JsonObject(
                        message(20) + mapOf("kind" to JsonPrimitive("tool_call"), "parent" to JsonPrimitive(17)),
                    ),
                )
            val saved =
                AuthoritativeThreadSnapshot(epoch = "e", items = listOf(root, child), completedVersion = 90, checkpoint = 90)
                    .toSavedAuthoritativeThread()
            store.restore("h", "c", saved.toSnapshot(), 0)
            val after = store.current("h", "c")
            assertEquals(30L, after.expansionItems.single { it.id == 17L }.rev)
            assertFalse(after.hasToolBody(after.expansionItems.single { it.id == 20L }))
            assertEquals(saved.items, after.toSavedAuthoritativeThread().items)
            assertEquals(before.checkpoint, after.checkpoint)
            assertEquals(before.coverage, after.coverage)
            assertTrue(f.details.isEmpty())
            f.repo.demandToolBodies("c", this, after, setOf(20))
            assertEquals(1, f.details.size)
        }

    @Test fun savedChildWithoutAncestorRequiresExplicitRefreshWhenFirstCompactAncestorArrives() =
        runTest {
            val f = CompactRootFixture(backgroundScope)
            val store = f.repo.authoritativeThreads
            val child =
                ThreadItemDto.decode(
                    kotlinx.serialization.json.JsonObject(
                        message(20) + mapOf("kind" to JsonPrimitive("tool_call"), "parent" to JsonPrimitive(17)),
                    ),
                )
            val saved =
                AuthoritativeThreadSnapshot(epoch = "e", items = listOf(child), completedVersion = 90, checkpoint = 90)
                    .toSavedAuthoritativeThread()
            store.restore("h", "c", saved.toSnapshot(), 0)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(headers = listOf(header(17, kind = "agent", facts = ",\"subtree_version\":90"))))
            f.push(f.marker())
            runCurrent()
            val before = store.current("h", "c")
            val held = before.expansionItems.single { it.id == 20L }
            assertFalse("an omitted saved ancestor cannot certify its saved descendant", before.hasToolBody(held))
            assertEquals(saved.items, before.toSavedAuthoritativeThread().items)
            assertTrue("closed descendants cause no detail demand", f.details.isEmpty())
            val presentation = projectDeferredAuthoritativeThread("h", "c", before)
            val row = presentation.rows.filterIsInstance<ThreadRow.Delivered>().single { it.listKey(0).endsWith(":20") }
            assertFalse(presentation.suppliesReadSight(row, setOf(row.listKey(0))))
            assertFalse(presentation.matchesReadContents(row.listKey(0), row.item, 20))
            f.repo.demandToolBodies("c", this, before, setOf(20))
            val request = f.details.single()
            for (final in listOf(false, true)) {
                val content = if (final) "" else ",\"content\":{\"output\":\"current descendant\"}"
                f.push(
                    CompactRootFixture.obj(
                        """{"conversation_id":"c","epoch":"e","version":100,"item_id":20,"rev":20,"kind":"tool_call","final":$final$content}""",
                    ),
                    request,
                    "thread_tool_body",
                )
            }
            runCurrent()
            val refreshed = store.current("h", "c")
            assertTrue(refreshed.hasToolBody(held))
            assertFalse(refreshed.copy(bodyContents = emptyMap()).hasToolBody(held))
            assertEquals(before.checkpoint, refreshed.checkpoint)
            assertEquals(before.coverage, refreshed.coverage)
            assertEquals(before.completeCheckpoint, refreshed.completeCheckpoint)
            assertEquals(before.completeCoverage, refreshed.completeCoverage)
            assertEquals(saved.items, refreshed.toSavedAuthoritativeThread().items)
        }

    @Test fun restoreInvariantCompleteHostKeepsSavedFullBodiesWithoutCompactFreshness() {
        val full = ThreadItemDto.decode(kotlinx.serialization.json.JsonObject(message(17) + ("kind" to JsonPrimitive("tool_call"))))
        val saved =
            AuthoritativeThreadSnapshot(epoch = "e", items = listOf(full), completedVersion = 90, checkpoint = 90)
                .toSavedAuthoritativeThread()
        val store = AuthoritativeThreadStore()
        store.restore("h", "c", saved.toSnapshot(), 0)
        val restored = store.current("h", "c")
        assertTrue(restored.hasToolBody(restored.expansionItems.single()))
        assertEquals(ExpansionDetailValidity(), restored.detailValidity)
        assertEquals(saved, restored.toSavedAuthoritativeThread())
    }

    @Test fun restoreInvariantCompleteCertificateSurvivesCompactFirstThroughCacheWithoutBecomingActive() =
        runTest {
            val savedStore = AuthoritativeThreadStore()
            savedStore.establishComplete("h", "c", "e", 90, listOf(ThreadItemDto.decode(message(17))), 0)
            val saved =
                savedStore
                    .current("h", "c")
                    .copy(coverage = AuthoritativeThreadCoverage(10uL, 91uL, 10uL, true))
                    .toSavedAuthoritativeThread()
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            f.push(f.batch(items = listOf(message(17, rev = 80))))
            f.push(f.marker())
            runCurrent()
            val store = f.repo.authoritativeThreads
            val before = store.current("h", "c")
            assertEquals(AuthoritativeThreadOutcome.Applied, store.restore("h", "c", saved.toSnapshot(), 0))
            val restored = store.current("h", "c")
            assertEquals(before.checkpoint, restored.checkpoint)
            assertEquals(before.coverage, restored.coverage)
            val roundTrip = restored.toSavedAuthoritativeThread().toSnapshot()
            assertEquals(saved.checkpoint, roundTrip.checkpoint)
            assertEquals(saved.coverage, roundTrip.toSavedAuthoritativeThread().coverage)
            assertEquals(before.items.single().raw, roundTrip.items.single().raw)
            assertTrue(f.details.isEmpty())
        }
}

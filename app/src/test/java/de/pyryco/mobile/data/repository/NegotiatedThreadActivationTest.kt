package de.pyryco.mobile.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HelloClientPayload
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.text
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in negotiation probes drive real connection owners, without the future screen/provider. */
@OptIn(ExperimentalCoroutinesApi::class)
class NegotiatedThreadActivationTest {
    private val oldSink = RelayLog.sink

    @get:Rule val temporary = TemporaryFolder()

    @Before fun quietLogs() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    @Test fun authoritativeSnapshotNeverBackfillsLegacyContent() =
        runTest {
            val host = host("h")
            try {
                val pump = host.connect()
                pump.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                val repo = requireNotNull(host.coordinator.currentRepository.value) as RemoteConversationRepository
                backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
                runCurrent()
                assertEquals(emptyList<String>(), pump.sent.map { it.type })
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun replacedTransportRejectsRetainedQueryCallbackBeforeCollectorsRun() =
        runTest {
            val host = host("h")
            try {
                val pump = host.connect()
                pump.open(setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD))
                runCurrent()
                val repo = requireNotNull(host.coordinator.currentRepository.value) as RemoteConversationRepository
                assertTrue(repo.openAuthoritativeThread("c"))
                host.connections.value = Transport()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                assertFalse(repo.openAuthoritativeThread("c"))
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun onlyBothAuthenticatedCapabilitiesGrantSupport() =
        runTest {
            for (capabilities in listOf(emptySet(), setOf(CAPABILITY_THREAD), setOf(CAPABILITY_INTERACTIVE), CAPABLE)) {
                val host = host("h")
                try {
                    // Held facts cannot stand in for an authenticated acknowledgement.
                    host.coordinator.authoritativeThreads.applyBatch("h", "c", "e", 10, listOf(item()), 0)
                    val pump = host.connect()
                    assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                    pump.open(capabilities)
                    runCurrent()
                    assertEquals(capabilities == CAPABLE, host.coordinator.threadSupport.value is ThreadSupport.Supported)
                    assertEquals(capabilities != CAPABLE, host.coordinator.threadMissing.value)
                    val repo = host.repo()
                    assertEquals(capabilities == CAPABLE, repo.openAuthoritativeThread("c"))
                } finally {
                    host.coordinator.close()
                }
            }
        }

    @Test fun reconnectReevaluatesVerdictAndReopensOnlyOnTheNewConnection() =
        runTest {
            val host = host("h")
            try {
                val first = host.connect()
                first.open(CAPABLE)
                runCurrent()
                val old = host.repo()
                val verdict = host.coordinator.threadSupport.value as ThreadSupport.Supported
                assertTrue(old.openAuthoritativeThread("c"))
                first.state.value = PumpState.Handshaking
                runCurrent()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                assertFalse(old.openAuthoritativeThread("c"))
                host.connections.value = null
                runCurrent()
                val second = host.connect()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                second.open(CAPABLE)
                runCurrent()
                val next = host.coordinator.threadSupport.value as ThreadSupport.Supported
                assertNotSame(verdict.connection, next.connection)
                assertSame(host.repo(), next.connection)
                assertFalse(old.openAuthoritativeThread("c"))
                assertTrue(host.repo().openAuthoritativeThread("c"))
                assertEquals(1, first.queries().size)
                assertEquals(1, second.queries().size)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun missingVerdictSurvivesDisconnectUntilACapableAckReplacesIt() =
        runTest {
            val host = host("h")
            try {
                val first = host.connect()
                first.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                val missing = host.coordinator.threadSupport.value
                assertTrue(missing is ThreadSupport.Missing)
                first.state.value = PumpState.Handshaking
                runCurrent()
                assertSame(missing, host.coordinator.threadSupport.value)
                host.connections.value = null
                runCurrent()
                assertSame(missing, host.coordinator.threadSupport.value)
                val next = host.connect()
                assertSame(missing, host.coordinator.threadSupport.value)
                next.open(CAPABLE)
                runCurrent()
                assertTrue(host.coordinator.threadSupport.value is ThreadSupport.Supported)
                assertFalse(host.coordinator.threadMissing.value)
                host.coordinator.close()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun retiredConnectionCannotRestoreItsVerdictOrSendQueries() =
        runTest {
            val host = host("h")
            try {
                val retired = host.connect()
                retired.open(CAPABLE)
                runCurrent()
                val old = host.repo()
                assertTrue(old.openAuthoritativeThread("c"))
                val query = retired.queries().single()
                val replacement = host.connect()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                retired.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                retired.open(CAPABLE)
                runCurrent()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                assertFalse(old.openAuthoritativeThread("c"))
                assertFalse(old.requestOlderAuthoritativeThread("c"))
                replacement.open(setOf(CAPABILITY_INTERACTIVE))
                runCurrent()
                val missing = host.coordinator.threadSupport.value
                retired.push(batch(query, item(text = "late")))
                retired.open(CAPABLE)
                runCurrent()
                assertSame(missing, host.coordinator.threadSupport.value)
                assertTrue(
                    host.coordinator.authoritativeThreads
                        .current("h", "c")
                        .items
                        .isEmpty(),
                )
                assertTrue(replacement.queries().isEmpty())
                host.coordinator.close()
                retired.open(emptySet())
                runCurrent()
                assertSame(missing, host.coordinator.threadSupport.value)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun missingThreadHostSendsNoContentRequests() =
        runTest {
            val missing = host("missing")
            val capable = host("capable")
            try {
                val absent = missing.connect()
                val present = capable.connect()
                // Obtain the repository synchronously before the asynchronous verdict collector runs.
                absent.open(CAPABLE)
                val early = missing.repo()
                assertSame(ThreadSupport.Unverified, missing.coordinator.threadSupport.value)
                absent.open(setOf(CAPABILITY_INTERACTIVE))
                present.open(CAPABLE)
                runCurrent()
                assertFalse(early.openAuthoritativeThread("c"))
                assertFalse(early.requestOlderAuthoritativeThread("c"))
                assertTrue(capable.repo().openAuthoritativeThread("c"))
                backgroundScope.launch { early.observeAuthoritativeThread("c").collect {} }
                backgroundScope.launch { capable.repo().observeAuthoritativeThread("c").collect {} }
                runCurrent()
                assertEquals(emptyList<String>(), absent.content())
                assertEquals(listOf("thread_catch_up"), present.content())
            } finally {
                missing.coordinator.close()
                capable.coordinator.close()
            }
        }

    @Test fun collidingHostsKeepSeparateStoresAndVerdicts() =
        runTest {
            val a = host("a")
            val b = host("b")
            try {
                val pumpA = a.connect()
                val pumpB = b.connect()
                pumpA.open(CAPABLE)
                pumpB.open(CAPABLE)
                runCurrent()
                for ((host, pump, text) in listOf(Triple(a, pumpA, "a"), Triple(b, pumpB, "b"))) {
                    host.coordinator.authoritativeThreads.reset(text, "c", "e")
                    assertTrue(host.repo().openAuthoritativeThread("c"))
                    val request = pump.queries().single()
                    pump.push(batch(request, item(text = text)))
                    pump.push(window(request))
                }
                runCurrent()
                assertEquals(
                    "a",
                    text(
                        a.coordinator.authoritativeThreads
                            .current("a", "c")
                            .items
                            .single(),
                    ),
                )
                assertEquals(
                    "b",
                    text(
                        b.coordinator.authoritativeThreads
                            .current("b", "c")
                            .items
                            .single(),
                    ),
                )
                assertTrue(
                    a.coordinator.authoritativeThreads
                        .current("b", "c")
                        .items
                        .isEmpty(),
                )
                val aVerdict = a.coordinator.threadSupport.value
                val replacement = b.connect()
                replacement.open(emptySet())
                runCurrent()
                pumpB.push(batch(pumpB.queries().single(), item(1, 9, 1, text = "stale")))
                runCurrent()
                assertSame(aVerdict, a.coordinator.threadSupport.value)
                assertTrue(b.coordinator.threadMissing.value)
                assertEquals(
                    "b",
                    text(
                        b.coordinator.authoritativeThreads
                            .current("b", "c")
                            .items
                            .single(),
                    ),
                )
            } finally {
                a.coordinator.close()
                b.coordinator.close()
            }
        }

    @Test fun pagingRequiresTheCurrentSupportedConnection() =
        runTest {
            val host = host("h")
            try {
                host.coordinator.authoritativeThreads.reset("h", "c", "e")
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                val repo = host.repo()
                assertTrue(repo.openAuthoritativeThread("c"))
                val query = pump.queries().single()
                pump.push(batch(query, item()))
                pump.push(window(query, older = true))
                runCurrent()
                assertTrue(repo.requestOlderAuthoritativeThread("c"))
                assertEquals(listOf("thread_catch_up", "thread_page"), pump.content())
                pump.state.value = PumpState.Handshaking
                runCurrent()
                assertFalse(repo.requestOlderAuthoritativeThread("c"))
                pump.open(setOf(CAPABILITY_THREAD))
                runCurrent()
                assertFalse(repo.requestOlderAuthoritativeThread("c"))
                assertEquals(2, pump.queries().size)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun unboundConstructorCannotCreateAContentFallback() =
        runTest {
            val host = host("")
            try {
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                assertSame(ThreadSupport.Unverified, host.coordinator.threadSupport.value)
                assertFalse(host.coordinator.threadMissing.value)
                assertTrue(
                    CAPABILITY_THREAD in HelloClientPayload(deviceName = "device", clientVersion = "version", token = "token").capabilities,
                )
                assertNull(host.repo().authoritativeStoreFor("h"))
                backgroundScope.launch { host.repo().observeAuthoritativeThread("c").collect {} }
                runCurrent()
                assertEquals(emptyList<String>(), pump.content())
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun negotiationAndRefusalLogsHaveOnlyStaticCodes() =
        runTest {
            val messages = mutableListOf<String>()
            val enabled = RelayLog.enabled
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> messages += message }
            val host = host("sensitive-host")
            try {
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                pump.state.value = PumpState.Handshaking
                runCurrent()
                pump.open(emptySet())
                runCurrent()
                val events =
                    messages.filter { it.startsWith("event=thread_negotiation") }
                assertEquals(
                    setOf(
                        "event=thread_negotiation verdict=supported",
                        "event=thread_negotiation verdict=unverified",
                        "event=thread_negotiation verdict=missing",
                    ),
                    events.toSet(),
                )
                assertFalse(messages.any { "sensitive-host" in it })
            } finally {
                host.coordinator.close()
                RelayLog.enabled = enabled
            }
        }

    @Test fun helloAdvertisesThreadAndOmitsTheReplayCursor() {
        val hello = HelloClientPayload(deviceName = "d", clientVersion = "1.0", token = "t")
        assertTrue(CAPABILITY_THREAD in hello.capabilities)
        assertTrue(CAPABILITY_INTERACTIVE in hello.capabilities)
        assertFalse("last_event_id" in MobileJson.encodeToString(hello))
    }

    @Test fun interruptedCatchUpAcrossReconnectKeepsNewerRevisionsAndPriorCheckpoint() =
        runTest {
            val host = host("h")
            try {
                host.coordinator.authoritativeThreads.reset("h", "c", "e")
                val route = requireNotNull(host.coordinator.negotiatedThread)
                val first = host.connect()
                first.open(CAPABLE)
                runCurrent()
                route.open("c")
                val seed = first.queries().single()
                first.push(batch(seed, 10, listOf(item(1, 5, 1, text = "newer"))))
                first.push(window(seed, 10, 1, 2, false))
                runCurrent()
                val certified = route.observe("c").first().checkpoint

                val second = host.connect()
                second.open(CAPABLE)
                runCurrent()
                route.open("c")
                val catchUp = second.queries().single()
                assertEquals(certified.toString(), field(catchUp, "version").toString())
                second.push(batch(catchUp, 20, listOf(item(1, 3, 1, text = "older"), item(2, 2, 2))))
                runCurrent()
                host.connections.value = null
                runCurrent()

                val held = route.observe("c").first()
                assertEquals(5L, held.items.single { it.id == 1L }.rev)
                assertEquals(listOf(1L, 2L), held.items.map { it.id }.sorted())
                assertEquals(certified, held.checkpoint)
                val third = host.connect()
                third.open(CAPABLE)
                runCurrent()
                route.open("c")
                assertEquals(certified.toString(), field(third.queries().single(), "version").toString())
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun batchFixturesRejectInvalidIdentitiesBeforeDelivery() {
        val request = Envelope(1, "thread_catch_up", "2026-10-17T10:00:00Z", buildJsonObject { put("conversation_id", "c") })
        for (invalid in listOf(item(2, 1), item(1, 21), item(0, 2))) {
            assertTrue(runCatching { batch(request, 20, listOf(invalid)) }.exceptionOrNull() is IllegalArgumentException)
        }
        batch(request, 20, listOf(item(2, 2)))
    }

    @Test fun admittedTurnEndRefreshesDaemonReadFactsWithoutInventingAMark() =
        runTest {
            val host = host("h")
            try {
                host.coordinator.authoritativeThreads.reset("h", "c", "e")
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                val repo = host.repo()
                assertTrue(repo.openAuthoritativeThread("c"))
                val opening = pump.queries().single()
                pump.push(batch(opening, item(1, 5, 1)))
                pump.push(window(opening))
                runCurrent()
                val before = pump.sent.count { it.type == "list_conversations" }
                val terminal =
                    JsonObject(
                        item(2, 20, 2).raw +
                            mapOf("kind" to JsonPrimitive("turn_end"), "active" to JsonPrimitive(false), "shown" to JsonPrimitive(false)),
                    )
                val reply =
                    ThreadItemDto.decodeReplacement(
                        JsonObject(
                            item(1, 19, 1, text = "completed reply").raw + ("active" to JsonPrimitive(false)),
                        ),
                    )
                pump.push(
                    Envelope(
                        199,
                        "thread_item_changed",
                        "2026-10-17T10:00:00Z",
                        buildJsonObject {
                            put("conversation_id", "c")
                            put("epoch", "e")
                            put("version", 19)
                            put("item_id", 1)
                            put("base_rev", 5)
                            put("rev", 19)
                            put("changes", JsonObject(reply.raw - setOf("id", "kind")))
                        },
                    ),
                )
                val update =
                    Envelope(
                        200,
                        "thread_item_added",
                        "2026-10-17T10:00:00Z",
                        buildJsonObject {
                            put("conversation_id", "c")
                            put("epoch", "e")
                            put("version", 20)
                            put("item", terminal)
                        },
                    )
                pump.push(update)
                runCurrent()
                assertEquals(before + 1, pump.sent.count { it.type == "list_conversations" })
                assertEquals(2, pump.queries().size)
                val refresh = pump.queries().last()
                assertEquals("10", field(refresh, "version").toString())

                fun presentation() = projectAuthoritativeThread("h", "c", host.coordinator.authoritativeThreads.current("h", "c"))
                val marks = ConversationReadMarks(0u, 20u, 19u, true)
                val uncertified = presentation()
                assertNull(uncertified.readCheckpoint(uncertified.newestReadKey, marks, uncertified.shownRowRevisions))
                pump.push(batch(refresh, 20, listOf(reply)))
                runCurrent()
                assertEquals(
                    10L,
                    host.coordinator.authoritativeThreads
                        .current("h", "c")
                        .checkpoint,
                )
                pump.push(
                    reply(
                        refresh,
                        buildJsonObject {
                            put("conversation_id", "c")
                            put("epoch", "e")
                            put("version", 20)
                            put("items", JsonArray(emptyList()))
                            put("final", true)
                            put("from_version", 10)
                        },
                    ),
                )
                runCurrent()
                val certified = presentation()
                assertEquals(19uL, certified.readCheckpoint(certified.newestReadKey, marks, certified.shownRowRevisions))
                assertNull(certified.readCheckpoint(certified.newestReadKey, marks, uncertified.shownRowRevisions.mapValues { 5L }))
                assertEquals(null, (host.coordinator.currentRepository.value as RemoteConversationRepository).currentReadMarks("c"))
                pump.push(update)
                runCurrent()
                assertEquals(before + 1, pump.sent.count { it.type == "list_conversations" })
                assertEquals(2, pump.queries().size)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun unreadPostRefreshesUnavailableSummaryAfterRecoveryOnReplacement() = unreadPostSummaryAfterRecovery(shownAfterPost = null)

    @Test fun unreadPostWithAvailableSummaryNeedsNoCertificationRefresh() = unreadPostSummaryAfterRecovery(shownAfterPost = 13)

    @Test fun certifiedRecoveryWaitsForItsPendingAvailableSummary() =
        unreadPostSummaryAfterRecovery(shownAfterPost = 13, summaryAfterCertification = true)

    @Test fun unavailableSummaryAfterCertificationRefreshesOnceOnItsOwner() =
        unreadPostSummaryAfterRecovery(shownAfterPost = null, summaryAfterCertification = true)

    @Test fun repeatedCertificationCannotRefreshTheSameLiveSummaryAgain() =
        unreadPostSummaryAfterRecovery(shownAfterPost = null, summaryAfterCertification = true, repeatCertification = true)

    private fun unreadPostSummaryAfterRecovery(
        shownAfterPost: Long?,
        summaryAfterCertification: Boolean = false,
        repeatCertification: Boolean = false,
    ) = runTest {
        val host = host("h")
        var source: HostConversationSource? = null

        fun summary(
            shown: Long?,
            read: Long = 9,
        ) = Envelope(
            300,
            "conversations",
            "2026-10-17T10:00:00Z",
            MobileJson.parseToJsonElement(
                """{"conversations":[{"id":"c","name":"Channel","is_promoted":true,"cwd":"","current_session_id":"s",
                        "last_message_ts":"2026-10-17T10:00:00Z","last_used_at":"2026-10-17T10:00:00Z",
                        "read_up_to":$read,"latest_entry_id":13${shown?.let { ",\"last_shown_version\":$it" }.orEmpty()}}]}""",
            ),
        )
        try {
            host.coordinator.authoritativeThreads.reset("h", "c", "e")
            val retiredPump = host.connect()
            retiredPump.open(CAPABLE)
            runCurrent()
            val retired = host.repo()
            retiredPump.push(summary(9))
            runCurrent()
            val pump = host.connect()
            pump.open(CAPABLE)
            runCurrent()
            val repo = host.repo()
            assertNotSame(retired, repo)
            assertNull(repo.currentReadMarks("c"))
            val connection =
                HostConversationConnection("h", null, host.coordinator.currentRepository, host.coordinator.connectionStatus)
            val hostSource =
                HostConversationSource(
                    MutableStateFlow(listOf(connection)),
                    { host.repo() },
                    StandardTestDispatcher(testScheduler),
                )
            source = hostSource
            runCurrent()
            pump.push(summary(9))
            val lease = requireNotNull(repo.acquireAuthoritativeThread("c"))
            val opening = pump.queries().single()
            pump.push(batch(opening, 12, listOf(item(9, 10, 9))))
            pump.push(window(opening, 12, 9, 10, false))
            runCurrent()
            lease.close()

            fun settled(fact: ThreadItemDto) = ThreadItemDto.decodeReplacement(JsonObject(fact.raw + ("active" to JsonPrimitive(false))))
            val post = settled(item(13, 13, 13, status = "done", text = "post"))
            val terminal = settled(item(14, 14, 14, kind = "turn_end", status = "done"))
            assertTrue("the supplied completion must pass production admission", terminal.isCompletion())
            val updates =
                listOf(post, terminal).map { fact ->
                    Envelope(
                        200,
                        "thread_item_added",
                        "2026-10-17T10:00:00Z",
                        buildJsonObject {
                            put("conversation_id", "c")
                            put("epoch", "e")
                            put("version", fact.rev)
                            put("item", fact.raw)
                        },
                    )
                }
            updates.forEach(pump::push)
            runCurrent()
            val liveSummary = pump.sent.last { it.type == "list_conversations" }
            // Metadata can become unavailable while the live summary is still pending.
            pump.push(summary(null))
            if (!summaryAfterCertification) pump.push(summary(shownAfterPost).copy(inReplyTo = liveSummary.id))
            runCurrent()
            val shownBeforeCertification = shownAfterPost.takeUnless { summaryAfterCertification }
            assertEquals(shownBeforeCertification?.toULong(), repo.currentReadMarks("c")?.unreadWatermark)
            assertEquals(
                "c",
                hostSource.snapshots.value
                    .single()
                    .channels
                    .single()
                    .id,
            )

            fun attention() = hostSource.attention.value["h"]?.get("c") ?: ConversationAttention.Idle
            val expectedAttention = if (shownBeforeCertification == null) ConversationAttention.Idle else ConversationAttention.Unread
            assertEquals(expectedAttention, attention())
            val beforeCertification = pump.sent.count { it.type == "list_conversations" }
            // Only a new request after recovery gets usable daemon facts; no unsolicited update.
            val refreshes = mutableListOf<Envelope>()
            pump.onSend = { if (it.type == "list_conversations") refreshes += it }
            val recovery = pump.queries().last()
            pump.push(batch(recovery, 14, listOf(post, terminal)))
            pump.push(
                reply(
                    recovery,
                    buildJsonObject {
                        put("conversation_id", "c")
                        put("epoch", "e")
                        put("version", 14)
                        put("items", JsonArray(emptyList()))
                        put("final", true)
                        put("from_version", 12)
                    },
                ),
            )
            runCurrent()
            assertEquals(
                "certified recovery must refresh only an unavailable daemon summary",
                beforeCertification + if (shownAfterPost == null && !summaryAfterCertification) 1 else 0,
                pump.sent.count { it.type == "list_conversations" },
            )
            assertEquals(
                "certification itself is not shown metadata",
                shownBeforeCertification?.toULong(),
                repo.currentReadMarks("c")?.unreadWatermark,
            )
            assertEquals(expectedAttention, attention())
            if (summaryAfterCertification) {
                pump.push(summary(shownAfterPost).copy(inReplyTo = liveSummary.id))
                runCurrent()
            }
            assertEquals(
                beforeCertification +
                    if (shownAfterPost ==
                        null
                    ) {
                        1
                    } else {
                        0
                    },
                pump.sent.count { it.type == "list_conversations" },
            )
            if (repeatCertification) {
                assertTrue(repo.openAuthoritativeThread("c"))
                val repeated = pump.queries().last()
                pump.push(
                    reply(
                        repeated,
                        buildJsonObject {
                            put("conversation_id", "c")
                            put("epoch", "e")
                            put("version", 14)
                            put("items", JsonArray(emptyList()))
                            put("final", true)
                            put("from_version", 14)
                        },
                    ),
                )
                runCurrent()
                assertEquals("one answered live summary permits only one follow-up", 1, refreshes.size)
            }
            if (shownAfterPost == null) {
                pump.push(summary(13).copy(inReplyTo = refreshes.single().id))
            } else {
                assertTrue(refreshes.isEmpty())
            }
            runCurrent()

            assertEquals(ConversationAttention.Unread, attention())
            pump.push(summary(13))
            pump.push(summary(9))
            runCurrent()
            assertEquals(ConversationAttention.Unread, attention())
            pump.push(summary(13, read = 13))
            runCurrent()
            assertEquals(ConversationAttention.Idle, attention())
            val afterConfirmation = pump.sent.count { it.type == "list_conversations" }
            updates.reversed().forEach(pump::push)
            retiredPump.push(summary(50, read = 50))
            runCurrent()
            assertEquals(afterConfirmation, pump.sent.count { it.type == "list_conversations" })
            assertEquals(ConversationAttention.Idle, attention())
            assertEquals(13uL, repo.currentReadMarks("c")?.readUpTo)
            assertEquals(9uL, retired.currentReadMarks("c")?.readUpTo)
            assertTrue(pump.sent.none { it.type == "mark_conversation_read" })
        } finally {
            source?.dispose()
            host.coordinator.close()
        }
    }

    @Test fun productionSendBeforeFirstBatchShowsOnlyScopedPreAcceptanceFeedback() =
        runTest {
            val host = host("h")
            val owner = ViewModelStore()
            try {
                val facade =
                    StableConversationRepository(host.coordinator.currentRepository, negotiatedThread = host.coordinator.negotiatedThread)
                val vm =
                    ThreadViewModel(
                        SavedStateHandle(mapOf("serverId" to "h", "conversationId" to "c")),
                        facade,
                        FakeConnectionStateSource(),
                        ComposerDraftStore(),
                    )
                owner.put("thread", vm)
                backgroundScope.launch { vm.state.collect {} }
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                assertEquals(1, pump.queries().size)
                vm.sendMessage("before the first batch")
                runCurrent()
                assertEquals(
                    null,
                    host.coordinator.authoritativeThreads
                        .current("h", "c")
                        .epoch,
                )
                assertEquals(1, pump.sent.count { it.type == "send_message" })
                val presentation = requireNotNull(vm.state.value.authoritativeThread)
                val sending = presentation.rows.single() as ThreadRow.Queued
                assertEquals("sending", sending.status)
                assertEquals("before the first batch", sending.text)
                assertNull(presentation.certifiedCheckpoint)
                assertTrue(presentation.shownRowRevisions.isEmpty())
                assertNull(presentation.readCheckpoint(null, ConversationReadMarks(0u, 1u, 1u, true), emptyMap()))
            } finally {
                owner.clear()
                host.coordinator.close()
            }
        }

    @Test fun completionDuringAnOlderCapturedQueryQueuesFreshCoverageOnItsOwner() =
        runTest {
            val host = host("h")
            try {
                val pump = host.connect()
                pump.open(CAPABLE)
                runCurrent()
                assertTrue(host.repo().openAuthoritativeThread("c"))
                val opening = pump.queries().single()
                pump.push(batch(opening, item(1, 5, 1)))
                pump.push(
                    Envelope(
                        201,
                        "thread_item_added",
                        "2026-10-17T10:00:00Z",
                        buildJsonObject {
                            put("conversation_id", "c")
                            put("epoch", "e")
                            put("version", 20)
                            put("item", JsonObject(item(2, 20, 2).raw + ("kind" to JsonPrimitive("turn_end"))))
                        },
                    ),
                )
                runCurrent()
                assertEquals(1, pump.queries().size)
                pump.push(window(opening))
                runCurrent()
                assertEquals(2, pump.queries().size)
                assertEquals(
                    0L,
                    host.coordinator.authoritativeThreads
                        .current("h", "c")
                        .checkpoint,
                )
                assertEquals("0", field(pump.queries().last(), "version").toString())
                val retired = host.repo()
                host.connections.value = null
                runCurrent()
                assertFalse(retired.openAuthoritativeThread("c"))
                assertEquals(2, pump.queries().size)
            } finally {
                host.coordinator.close()
            }
        }

    @Test fun productionProjectionRetiresProgressAcrossReconnect() =
        runTest {
            val host = host("h")
            val owner = ViewModelStore()
            try {
                host.coordinator.authoritativeThreads.reset("h", "c", "e")
                val facade =
                    StableConversationRepository(host.coordinator.currentRepository, negotiatedThread = host.coordinator.negotiatedThread)
                val repository =
                    CachingConversationRepository(
                        facade,
                        FileConversationCache(temporary.root, StandardTestDispatcher(testScheduler)),
                        "h",
                        processingDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val vm =
                    ThreadViewModel(
                        SavedStateHandle(mapOf("serverId" to "h", "conversationId" to "c")),
                        repository,
                        FakeConnectionStateSource(),
                        ComposerDraftStore(),
                    )
                owner.put("thread", vm)
                backgroundScope.launch { vm.state.collect {} }
                val first = host.connect()
                first.open(CAPABLE)
                runCurrent()
                val query = first.queries().single()
                first.push(
                    batch(
                        query,
                        10,
                        listOf(
                            item(
                                kind = "tool_call",
                                extra = "\"session\":\"s\",",
                                content = """{"name":"Read","tool_use_id":"tool","input_summary":"file"}""",
                            ),
                        ),
                    ),
                )
                first.push(window(query, 10, 1, 2, false))
                first.push(progress(clear = true))
                first.push(progress(seconds = 7))
                runCurrent()

                fun elapsed() =
                    vm.state.value.authoritativeThread
                        ?.items
                        ?.filterIsInstance<ThreadItem.MessageItem>()
                        ?.single()
                        ?.message
                        ?.toolCall
                        ?.elapsedSeconds
                assertEquals(7, elapsed())
                host.connections.value = null
                runCurrent()
                assertTrue(requireNotNull(vm.state.value.authoritativeThread).rows.isNotEmpty())
                assertEquals(null, elapsed())
                val replacement = host.connect()
                first.state.value = PumpState.Open("stale", CAPABLE)
                runCurrent()
                assertEquals(null, elapsed())
                replacement.open(CAPABLE)
                runCurrent()
                assertEquals(null, elapsed())
                replacement.push(progress(clear = true))
                replacement.push(progress(seconds = 9))
                runCurrent()
                assertEquals(9, elapsed())
                replacement.push(progress(clear = true, session = "next"))
                replacement.push(progress(seconds = 12))
                runCurrent()
                assertEquals(null, elapsed())
            } finally {
                owner.clear()
                host.coordinator.close()
            }
        }

    private fun progress(
        seconds: Int = 0,
        clear: Boolean = false,
        session: String = "s",
    ): Envelope =
        MobileJson.decodeFromString(
            """{"id":90,"type":"tool_progress","ts":"2026-10-17T10:00:00Z","conversation_id":"c","session_id":"$session","session_state_cleared":$clear,"payload":${if (clear) "{}" else """{"conversation_id":"c","turn_id":"turn","tool_use_id":"tool","elapsed_seconds":$seconds}"""}}""",
        )

    private fun Host.repo() = requireNotNull(coordinator.currentRepository.value) as RemoteConversationRepository

    private fun Pump.queries() = sent.filter { it.type in setOf("thread_catch_up", "thread_page") }

    private fun Pump.content() =
        sent.map { it.type }.filter {
            it in
                setOf("request_history", "backfill_since", "thread_catch_up", "thread_page")
        }

    private fun field(
        frame: Envelope,
        name: String,
    ) = (frame.payload as JsonObject).getValue(name)

    private fun batch(
        request: Envelope,
        item: ThreadItemDto,
    ) = reply(
        request,
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", 10)
            put("items", JsonArray(listOf(item.raw)))
            put("final", false)
        },
    )

    private fun window(
        request: Envelope,
        older: Boolean = false,
    ) = reply(
        request,
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", 10)
            put("items", JsonArray(emptyList()))
            put("final", true)
            put("lower_order", 1)
            put("upper_order", 2)
            put("continuation", 1)
            put("older_exists", older)
        },
    )

    private fun reply(
        request: Envelope,
        raw: JsonObject,
    ) = Envelope(1000, "thread_items", "2026-10-17T10:00:00Z", raw, inReplyTo = request.id)

    private fun batch(
        request: Envelope,
        version: Long,
        items: List<ThreadItemDto>,
    ): Envelope {
        require(items.all { it.id > 0 && it.rev >= it.id && it.rev <= version }) { "Invalid activation batch identity/revision" }
        require(items.map { it.id }.distinct().size == items.size) { "Duplicate activation batch identity" }
        return reply(
            request,
            buildJsonObject {
                put("conversation_id", field(request, "conversation_id"))
                put("epoch", "e")
                put("version", version)
                put("items", JsonArray(items.map { it.raw }))
                put("final", false)
            },
        )
    }

    private fun window(
        request: Envelope,
        version: Long,
        lower: Long,
        upper: Long,
        older: Boolean,
    ) = reply(
        request,
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", version)
            put("items", JsonArray(emptyList()))
            put("final", true)
            put("lower_order", lower)
            put("upper_order", upper)
            put("continuation", lower)
            put("older_exists", older)
        },
    )

    private companion object {
        val CAPABLE = setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD)
    }

    private fun TestScope.host(id: String): Host {
        val connections = MutableStateFlow<RelayTransport?>(null)
        val pumps = mutableListOf<Pump>()
        val coordinator =
            RelayRepositoryCoordinator(
                connections,
                MutableStateFlow(RelayLinkStatus.Connected),
                { Pump().also { pumps += it } },
                StandardTestDispatcher(testScheduler),
                authoritativeHostId = id,
            )
        coordinator.start()
        return Host(this, connections, pumps, coordinator)
    }

    private inner class Host(
        val testScope: TestScope,
        val connections: MutableStateFlow<RelayTransport?>,
        val pumps: List<Pump>,
        val coordinator: RelayRepositoryCoordinator,
    ) {
        fun connect(): Pump {
            connections.value = Transport()
            testScope.runCurrent()
            return pumps.last()
        }
    }

    private class Pump : ManagedSessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)
        val sent = mutableListOf<Envelope>()
        var onSend: (Envelope) -> Unit = {}

        override fun start() = Unit

        override fun send(envelope: Envelope): Boolean {
            if (state.value !is PumpState.Open) return false
            sent += envelope
            onSend(envelope)
            return true
        }

        // Deliberately keep accepting late input and state: coordinator retirement must fence both.
        override fun close() = Unit

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }

        fun open(capabilities: Set<String>) {
            state.value = PumpState.Open("conn", capabilities)
        }
    }

    private class Transport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2): Boolean = true

        override fun close() = Unit
    }
}

package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryPinTest {
    @get:Rule val tmp = TemporaryFolder()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, text -> logs += text }
    }

    @After fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun confirmedUpdatesReplacePinsAndKeepProjectionMetadata() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val observed = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo.observeConversations(ConversationFilter.All).collect { observed += it } }
            runCurrent()
            val seed =
                JsonObject(
                    row().toMutableMap().apply {
                        put("agent", JsonPrimitive("codex"))
                        put("archived_at", JsonPrimitive(TS))
                    },
                )
            pump.push(snapshot(seed))
            runCurrent()
            val held = single(repo)

            listOf(JsonPrimitive(PIN), JsonPrimitive(PIN), JsonNull, JsonPrimitive(REPIN), null).forEach { pin ->
                pump.push(update(row(pin)))
                runCurrent()
                val expected = if (pin == null || pin == JsonNull) null else Instant.parse(pin.content)
                assertEquals(held.copy(pinnedAt = expected), observed.last().single())
                assertEquals(held.copy(pinnedAt = expected), single(repo))
            }
        }

    @Test
    fun replyAndPushDeliveryIsIdempotentInEitherOrder() =
        runTest {
            listOf(false, true).forEach { pushFirst ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                pump.push(snapshot(row()))
                runCurrent()
                var result: Result<Unit>? = null
                backgroundScope.launch { result = runCatching { repo.setMuted("channel", false) } }
                runCurrent()
                val request = pump.sent.last { it.type == "set_conversation_muted" }
                val record = row(JsonPrimitive(PIN), muted = false)
                val deliveries = listOf(update(record, request.id), update(record))
                (if (pushFirst) deliveries.reversed() else deliveries).forEach { envelope ->
                    pump.push(envelope)
                    runCurrent()
                    assertEquals(Instant.parse(PIN), single(repo).pinnedAt)
                }
                requireNotNull(result).getOrThrow()
                pump.push(update(record))
                runCurrent()
                assertEquals(false, single(repo).muted)
                assertEquals(Instant.parse(PIN), single(repo).pinnedAt)

                // Queue different confirmed values before the command can resume. Arrival order,
                // rather than the command's older reply, must decide the final pin.
                result = null
                backgroundScope.launch { result = runCatching { repo.setMuted("channel", false) } }
                runCurrent()
                val nextRequest = pump.sent.last { it.type == "set_conversation_muted" }
                val queued = listOf(update(record, nextRequest.id), update(row(JsonNull, muted = false)))
                (if (pushFirst) queued.reversed() else queued).forEach(pump::push)
                runCurrent()
                requireNotNull(result).getOrThrow()
                assertEquals(if (pushFirst) Instant.parse(PIN) else null, single(repo).pinnedAt)
            }
        }

    @Test
    fun queuedCommandRepliesCannotOverwriteLaterConfirmedPins() =
        runTest {
            commandNames.forEach { name ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                pump.push(
                    snapshot(
                        JsonObject(
                            row().toMutableMap().apply {
                                put("agent", JsonPrimitive("codex"))
                                put("archived_at", JsonPrimitive(TS))
                            },
                        ),
                    ),
                )
                runCurrent()
                val held = single(repo)
                var result: Result<Any>? = null
                backgroundScope.launch { result = runCatching { executeCommand(repo, name) } }
                runCurrent()
                val request = pump.sent.last()
                pump.push(commandReply(request, row(JsonPrimitive(PIN), muted = false)))
                pump.push(commandReply(request, row(JsonPrimitive(REPIN), muted = false)))
                pump.push(update(row(JsonNull, muted = false)))
                runCurrent()
                val returned = requireNotNull(result).getOrThrow()
                if (returned is Conversation) {
                    assertEquals(name, held.copy(pinnedAt = Instant.parse(PIN), muted = false), returned)
                }
                assertEquals(name, held.copy(pinnedAt = null, muted = false), single(repo))
            }
        }

    @Test
    fun malformedCommandRepliesFailWithoutPublishing() =
        runTest {
            commandNames.forEach { name ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                pump.push(snapshot(row(JsonPrimitive(PIN))))
                runCurrent()
                val held = single(repo)
                var result: Result<Any>? = null
                backgroundScope.launch { result = runCatching { executeCommand(repo, name) } }
                runCurrent()
                pump.push(commandReply(pump.sent.last(), row(JsonPrimitive("yesterday"), muted = false)))
                runCurrent()
                assertTrue(name, requireNotNull(result).exceptionOrNull() is IllegalArgumentException)
                assertEquals(name, held, single(repo))
                pump.push(update(row(JsonNull)))
                runCurrent()
                assertEquals(name, held.copy(pinnedAt = null), single(repo))
            }
        }

    @Test
    fun cancelledCommandsKeepInboundConfirmedPins() =
        runTest {
            commandNames.forEach { name ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                pump.push(snapshot(row()))
                runCurrent()
                val caller = backgroundScope.launch { executeCommand(repo, name) }
                runCurrent()
                pump.push(commandReply(pump.sent.last(), row(JsonPrimitive(PIN))))
                caller.cancel()
                runCurrent()
                assertTrue(name, caller.isCancelled)
                assertEquals(name, Instant.parse(PIN), single(repo).pinnedAt)
            }
        }

    @Test
    fun malformedPinCannotPartiallyMutateTheListOrStopUpdates() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(snapshot(row(JsonPrimitive(PIN))))
            runCurrent()
            val held = single(repo)
            pump.push(snapshot(row(JsonNull), row(JsonPrimitive("yesterday"), id = "bad")))
            runCurrent()
            assertEquals(held, single(repo))
            pump.push(update(row(JsonPrimitive("yesterday"))))
            runCurrent()
            assertEquals(held, single(repo))
            pump.push(update(row(JsonNull)))
            runCurrent()
            assertEquals(held.copy(pinnedAt = null), single(repo))
            assertTrue(logs.contains("event=conversation_read_snapshot outcome=malformed"))
            assertTrue(logs.contains("event=conversation_read_update outcome=malformed"))
            assertTrue(logs.none { it.contains(PIN) || it.contains("yesterday") })
        }

    @Test
    fun equalConversationIdsRemainConnectionLocal() =
        runTest {
            val pumpA = FakeSessionPump()
            val pumpB = FakeSessionPump()
            val repoA = repo(pumpA)
            val repoB = repo(pumpB)
            pumpA.push(snapshot(row(JsonPrimitive(PIN))))
            pumpB.push(snapshot(row(JsonPrimitive(REPIN))))
            runCurrent()
            pumpA.push(update(row(JsonNull)))
            runCurrent()
            assertEquals(null, single(repoA).pinnedAt)
            assertEquals(Instant.parse(REPIN), single(repoB).pinnedAt)
        }

    @Test
    fun restoredPinsSurviveOfflineAndReconcileWithFreshSnapshots() =
        runTest {
            val root = tmp.newFolder()
            val cache = FileConversationCache(root, UnconfinedTestDispatcher(testScheduler))
            val oldPump = FakeSessionPump()
            val oldRepo = repo(oldPump)
            oldPump.push(snapshot(row(JsonPrimitive(PIN), archived = false), row(id = "other", archived = false)))
            runCurrent()
            val saved = oldRepo.observeConversations(ConversationFilter.All).first()
            cache.writeConversations("host", saved).getOrThrow()
            oldPump.close()
            runCurrent()
            val restored = FileConversationCache(root, UnconfinedTestDispatcher(testScheduler)).readConversations("host")
            assertEquals(saved, restored)
            assertEquals(Instant.parse(PIN), restored.single { it.id == "channel" }.pinnedAt)

            val repositories = MutableStateFlow<ConversationRepository?>(null)
            val host =
                HostConversationConnection(
                    "host",
                    "Host",
                    repositories,
                    MutableStateFlow(ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)),
                )
            val source =
                HostConversationSource(
                    MutableStateFlow(listOf(host)),
                    { repositories.value },
                    StandardTestDispatcher(testScheduler),
                    cache = FileConversationCache(root, UnconfinedTestDispatcher(testScheduler)),
                )
            try {
                runCurrent()
                assertEquals(
                    saved,
                    source.snapshots.value
                        .single()
                        .channels,
                )
                val pump = FakeSessionPump()
                val repo = repo(pump)
                repositories.value = repo
                runCurrent()
                assertEquals(
                    saved,
                    source.snapshots.value
                        .single()
                        .channels,
                )
                pump.push(snapshot(row(id = "other", archived = false), row(JsonNull, archived = false)))
                runCurrent()
                val refreshed = repo.observeConversations(ConversationFilter.All).first()
                assertEquals(setOf("channel", "other"), refreshed.map { it.id }.toSet())
                assertEquals(2, refreshed.size)
                assertEquals(null, refreshed.single { it.id == "channel" }.pinnedAt)
                assertEquals(
                    refreshed,
                    source.snapshots.value
                        .single()
                        .channels,
                )
                assertEquals(refreshed, FileConversationCache(root, UnconfinedTestDispatcher(testScheduler)).readConversations("host"))
                pump.push(snapshot(row(JsonPrimitive(REPIN), archived = false)))
                runCurrent()
                assertEquals(
                    Instant.parse(REPIN),
                    source.snapshots.value
                        .single()
                        .channels
                        .single()
                        .pinnedAt,
                )
                pump.push(snapshot())
                runCurrent()
                assertEquals(
                    emptyList<Conversation>(),
                    source.snapshots.value
                        .single()
                        .channels,
                )
            } finally {
                source.dispose()
            }
        }

    // #2214: the daemon refuses a missing or null `pinned`, so unpin must put an explicit false on the wire.
    @Test
    fun setPinned_sendsExactVerbAndExplicitBoolean() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(snapshot(row()))
            runCurrent()

            listOf(true to JsonPrimitive(PIN), false to JsonNull).forEach { (pinned, pin) ->
                val write = startPin(repo, pinned)
                runCurrent()
                pump.push(update(row(pin), pinFrame(pump).id))
                runCurrent()
                write.result().getOrThrow()
                assertEquals(if (pinned) Instant.parse(PIN) else null, single(repo).pinnedAt)
            }

            assertEquals(
                listOf(
                    MobileJson.parseToJsonElement("""{"conversation_id":"channel","pinned":true}"""),
                    MobileJson.parseToJsonElement("""{"conversation_id":"channel","pinned":false}"""),
                ),
                pump.sent.filter { it.type == "set_conversation_pinned" }.map { it.payload },
            )
        }

    // Invariant: only a decoded host record changes pin state; the request itself moves nothing.
    @Test
    fun setPinned_pendingRequestLeavesConfirmedStateUntilReplyArrives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val observed = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo.observeConversations(ConversationFilter.All).collect { observed += it } }
            pump.push(snapshot(richRow()))
            runCurrent()
            val held = single(repo)
            val emissions = observed.size

            val write = startPin(repo, true)
            runCurrent()
            assertTrue(write.job.isActive)
            assertEquals(emissions, observed.size)
            assertEquals(held, single(repo))

            pump.push(update(richRow(JsonPrimitive(PIN)), pinFrame(pump).id))
            runCurrent()
            write.result().getOrThrow()
            assertEquals(held.copy(pinnedAt = Instant.parse(PIN)), single(repo))
        }

    // Invariant: reply and broadcast for one write upsert one row; a repeat pin keeps the host's time.
    @Test
    fun setPinned_replyAndDuplicatePushInEitherOrderKeepOneRowAndHostTimestamp() =
        runTest {
            listOf(false, true).forEach { pushFirst ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                pump.push(snapshot(row()))
                runCurrent()

                repeat(2) { attempt ->
                    val write = startPin(repo, true)
                    runCurrent()
                    val deliveries = listOf(update(row(JsonPrimitive(PIN)), pinFrame(pump).id), update(row(JsonPrimitive(PIN))))
                    (if (pushFirst) deliveries.reversed() else deliveries).forEach { envelope ->
                        pump.push(envelope)
                        runCurrent()
                        val rows = repo.observeConversations(ConversationFilter.All).first()
                        assertEquals("pushFirst=$pushFirst attempt=$attempt", listOf("channel"), rows.map { it.id })
                        assertEquals(Instant.parse(PIN), rows.single().pinnedAt)
                    }
                    write.result().getOrThrow()
                }
            }
        }

    // Invariant: refusals and malformed replies keep the confirmed record; a later snapshot refreshes it.
    @Test
    fun setPinned_refusalsAndMalformedReplyPreserveConfirmedRecordUntilSnapshot() =
        runTest {
            listOf("protocol.unknown_type", "conversation.not_promoted", "conversation.not_found", MALFORMED).forEach { case ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                pump.push(snapshot(richRow(JsonPrimitive(PIN))))
                runCurrent()
                val held = single(repo)

                val write = startPin(repo, pinned = case == "protocol.unknown_type" || case == "conversation.not_promoted")
                runCurrent()
                val request = pinFrame(pump)
                pump.push(
                    if (case == MALFORMED) {
                        update(richRow(JsonPrimitive("yesterday")), request.id)
                    } else {
                        error(request.id, case)
                    },
                )
                runCurrent()

                val thrown = write.result().exceptionOrNull()
                when (case) {
                    "conversation.not_found", MALFORMED -> assertTrue(case, thrown is IllegalArgumentException)
                    else -> assertEquals(case, case, (thrown as RelayErrorException).code)
                }
                assertEquals(case, held, single(repo))

                pump.push(snapshot(richRow(JsonPrimitive(REPIN))))
                runCurrent()
                assertEquals(case, held.copy(pinnedAt = Instant.parse(REPIN)), single(repo))
            }
            assertTrue(logs.none { it.contains("yesterday") || it.contains(PIN) })
        }

    @Test
    fun setPinned_disconnectedSessionFailsAndKeepsConfirmedRecord() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.push(snapshot(richRow(JsonPrimitive(PIN))))
            runCurrent()
            val held = single(repo)

            pump.open = false
            assertTrue(runCatching { repo.setPinned("channel", false) }.exceptionOrNull() is IllegalStateException)
            assertTrue(pump.sent.none { it.type == "set_conversation_pinned" })
            assertEquals(held, single(repo))

            pump.open = true
            val write = startPin(repo, false)
            runCurrent()
            assertEquals(1, pump.sent.count { it.type == "set_conversation_pinned" })
            pump.close()
            runCurrent()
            assertTrue(write.result().exceptionOrNull() is IllegalStateException)
            assertEquals(held, single(repo))

            // A reconnect is a fresh repository; its snapshot carries the host's current pin.
            val nextPump = FakeSessionPump()
            val next = repo(nextPump)
            nextPump.push(snapshot(richRow(JsonPrimitive(REPIN))))
            runCurrent()
            assertEquals(held.copy(pinnedAt = Instant.parse(REPIN)), single(next))
        }

    // Host-scoped dispatch: a write goes to its own connection only, even when another host shares the id.
    @Test
    fun setPinned_onOneHostLeavesTheOtherHostsSameIdUntouched() =
        runTest {
            val pumpA = FakeSessionPump()
            val pumpB = FakeSessionPump()
            val repoA = repo(pumpA)
            val repoB = repo(pumpB)
            pumpA.push(snapshot(row()))
            pumpB.push(snapshot(row(JsonPrimitive(REPIN))))
            runCurrent()

            val write = startPin(repoA, true)
            runCurrent()
            assertTrue(pumpB.sent.none { it.type == "set_conversation_pinned" })
            pumpA.push(update(row(JsonPrimitive(PIN)), pinFrame(pumpA).id))
            pumpA.push(update(row(JsonPrimitive(PIN))))
            runCurrent()
            write.result().getOrThrow()

            assertEquals(Instant.parse(PIN), single(repoA).pinnedAt)
            assertEquals(Instant.parse(REPIN), single(repoB).pinnedAt)
        }

    private fun TestScope.repo(pump: FakeSessionPump) =
        RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

    private suspend fun single(repo: RemoteConversationRepository) = repo.observeConversations(ConversationFilter.All).first().single()

    private class PendingWrite(
        val job: Job,
        private val outcome: () -> Result<Unit>?,
    ) {
        fun result(): Result<Unit> = requireNotNull(outcome()) { "write has not completed" }
    }

    private fun TestScope.startPin(
        repo: RemoteConversationRepository,
        pinned: Boolean,
    ): PendingWrite {
        var outcome: Result<Unit>? = null
        val job = backgroundScope.launch { outcome = runCatching { repo.setPinned("channel", pinned) } }
        return PendingWrite(job) { outcome }
    }

    /** The latest pin frame; the repository also sends its own `list_conversations` requests. */
    private fun pinFrame(pump: FakeSessionPump): Envelope = pump.sent.last { it.type == "set_conversation_pinned" }

    /** A row whose unrelated fields (mute, archive stamp, label, agent) must survive every pin outcome. */
    private fun richRow(pin: JsonElement? = null) =
        JsonObject(
            row(pin).toMutableMap().apply {
                put("agent", JsonPrimitive("codex"))
                put("archived_at", JsonPrimitive(TS))
            },
        )

    private fun error(
        inReplyTo: Long,
        code: String,
    ) = Envelope(
        id = 99L,
        type = "error",
        ts = TS,
        payload = MobileJson.parseToJsonElement("""{"code":"$code","message":"fixed","retryable":false}"""),
        inReplyTo = inReplyTo,
    )

    private fun row(
        pin: JsonElement? = null,
        id: String = "channel",
        muted: Boolean = true,
        archived: Boolean = true,
    ) = buildJsonObject {
        put("id", id)
        put("name", "Channel")
        put("is_promoted", true)
        put("is_archived", archived)
        put("is_muted", muted)
        put("cwd", "/p")
        put("workspace_label", "Project")
        put("last_used_at", TS)
        put("last_message_ts", TS)
        pin?.let { put("pinned_at", it) }
    }

    private fun snapshot(vararg rows: JsonObject) =
        Envelope(
            id = 97L,
            type = "conversations",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversations":[${rows.joinToString(",")}]}"""),
        )

    private fun update(
        row: JsonObject,
        reply: Long? = null,
    ) = Envelope(id = 98L, type = "conversation_updated", ts = TS, payload = row, inReplyTo = reply)

    private fun commandReply(
        request: Envelope,
        row: JsonObject,
    ) = update(row, request.id).copy(type = if (request.type == "create_conversation") "conversation_created" else "conversation_updated")

    private suspend fun executeCommand(
        repo: RemoteConversationRepository,
        name: String,
    ): Any =
        when (name) {
            "createDiscussion" -> repo.createDiscussion(null)
            "createChannel" -> repo.createChannel("Channel", null)
            "promote" -> repo.promote("channel", "Channel", null)
            "rename" -> repo.rename("channel", "Channel")
            "archive" -> repo.archive("channel")
            "unarchive" -> repo.unarchive("channel")
            "setMuted" -> repo.setMuted("channel", false)
            "setPinned" -> repo.setPinned("channel", true)
            "setSystemPrompt" -> repo.setSystemPrompt("channel", null)
            "changeWorkspace" -> repo.changeWorkspace("channel", "/p")
            else -> error("Unknown test command")
        }

    private class FakeSessionPump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var open = true

        override fun send(envelope: Envelope): Boolean {
            if (!open) return false
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            check(channel.trySend(envelope).isSuccess)
        }

        fun close() {
            channel.close()
        }
    }

    private companion object {
        val commandNames =
            listOf(
                "createDiscussion",
                "createChannel",
                "promote",
                "rename",
                "archive",
                "unarchive",
                "setMuted",
                "setPinned",
                "setSystemPrompt",
                "changeWorkspace",
            )
        const val TS = "2026-05-08T10:00:00Z"
        const val MALFORMED = "malformed reply"
        const val PIN = "2026-05-08T10:30:00.123456789Z"
        const val REPIN = "2026-05-08T10:31:00.987654321Z"
    }
}

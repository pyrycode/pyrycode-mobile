package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.SlashCommandListPayloadsTest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-connection slash-command menu (#882): `slash_command_list` decoded in
 * [RemoteConversationRepository.onInbound] behind the `interactive` gate and read through
 * [RemoteConversationRepository.observeSlashCommandMenu]. Payload shapes are proven at the decode boundary
 * (SlashCommandListPayloadsTest); this class owns the arm — the gate, the routing, the snapshot replace, the
 * drop posture and the connection scope.
 */
class RemoteConversationRepositorySlashCommandTest {
    // A conversation with no frame reads `null`; the daemon's populated fixture then surfaces its rows in
    // order with their aliases, cuts and dropped count.
    @Test
    fun menu_nullUntilAFrameArrives_thenHoldsTheFixtureRows() =
        runTest {
            val (pump, repo) = interactiveRepo()
            val menus = collectMenu(repo, "c1")
            runCurrent()
            assertEquals(listOf<SlashCommandMenu?>(null), menus)

            pump.push(fixture(SlashCommandListPayloadsTest.SLASH_COMMAND_LIST))
            runCurrent()

            val menu = menus.last()
            assertEquals(listOf("claude-api", "clear", "config", "model", "usage"), menu?.rows?.map { it.name })
            assertEquals(listOf("reset", "new"), menu?.rows?.get(1)?.aliases)
            assertEquals(listOf("description"), menu?.rows?.first()?.truncatedFields)
            assertEquals(2, menu?.droppedCommands)
        }

    // A later frame for the same conversation replaces the menu wholesale. A shorter replacement catches a
    // merge or an append, and the frame-level count is replaced too.
    @Test
    fun menu_laterFrame_replacesWholesale() =
        runTest {
            val (pump, repo) = interactiveRepo()
            val menus = collectMenu(repo, "c1")
            runCurrent()

            pump.push(frame("c1", listOf("clear", "config", "model"), droppedCommands = 9, id = 1L))
            runCurrent()
            assertEquals(listOf("clear", "config", "model"), menus.last()?.rows?.map { it.name })

            pump.push(frame("c1", listOf("usage"), droppedCommands = 0, id = 2L))
            runCurrent()

            assertEquals(SlashCommandMenu(listOf(row("usage")), 0), menus.last())
        }

    // A frame for one conversation leaves every other conversation untouched, including one still unheard.
    @Test
    fun menu_frameForOneConversation_leavesOthersUntouched() =
        runTest {
            val (pump, repo) = interactiveRepo()
            val first = collectMenu(repo, "c1")
            val second = collectMenu(repo, "c2")
            val third = collectMenu(repo, "c3")
            runCurrent()

            pump.push(frame("c1", listOf("clear")))
            pump.push(frame("c2", listOf("model")))
            runCurrent()
            pump.push(frame("c1", listOf("usage")))
            runCurrent()

            assertEquals(listOf("usage"), first.last()?.rows?.map { it.name })
            assertEquals(listOf(null, SlashCommandMenu(listOf(row("model")), 0)), second)
            assertEquals(listOf<SlashCommandMenu?>(null), third)
        }

    // Routing is the frame's own conversation_id. Every envelope in the connect-time burst repeats one
    // envelope id, so frames sharing it must still land on their own conversations.
    @Test
    fun menu_burstSharingOneEnvelopeId_routesByConversationIdAlone() =
        runTest {
            val (pump, repo) = interactiveRepo()
            val first = collectMenu(repo, "c1")
            val second = collectMenu(repo, "c2")
            runCurrent()

            pump.push(frame("c2", listOf("model"), id = 7L))
            pump.push(frame("c1", listOf("clear"), id = 7L))
            runCurrent()

            assertEquals(listOf("clear"), first.last()?.rows?.map { it.name })
            assertEquals(listOf("model"), second.last()?.rows?.map { it.name })
        }

    // An empty `commands` is a present menu, distinct from the `null` of a conversation with no frame.
    @Test
    fun menu_emptyFixture_isPresentAndDistinctFromNull() =
        runTest {
            val (pump, repo) = interactiveRepo()
            val menus = collectMenu(repo, "c1")
            runCurrent()

            pump.push(fixture(SlashCommandListPayloadsTest.SLASH_COMMAND_LIST_EMPTY))
            runCurrent()

            assertEquals(listOf(null, SlashCommandMenu(emptyList(), 0)), menus)
        }

    // Fail-closed: a connection that did not negotiate `interactive` never retains a well-formed frame, the
    // same gate as the `model_list` arm.
    @Test
    fun menu_withoutInteractive_isNeverRetained() =
        runTest {
            for (capabilities in listOf(emptySet(), setOf("something_else"))) {
                val pump = FakeSessionPump()
                val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
                val menus = collectMenu(repo, "c1")
                runCurrent()

                pump.push(fixture(SlashCommandListPayloadsTest.SLASH_COMMAND_LIST))
                runCurrent()

                assertEquals(capabilities.toString(), listOf<SlashCommandMenu?>(null), menus)
            }
        }

    // A malformed frame, or one without a usable conversation_id, changes nothing: the prior menu stands, no
    // other conversation gains one, and the single inbound collector survives to apply a later valid frame.
    @Test
    fun menu_malformedOrUnroutableFrame_changesNothing() =
        runTest {
            val (pump, repo) = interactiveRepo()
            val menus = collectMenu(repo, "c1")
            val unrouted = collectMenu(repo, "")
            runCurrent()
            pump.push(frame("c1", listOf("clear"), id = 1L))
            runCurrent()

            // Missing conversation_id.
            pump.push(probe("""{"commands":[],"dropped_commands":0}"""))
            // conversation_id of the wrong type.
            pump.push(probe("""{"conversation_id":5,"commands":[],"dropped_commands":0}"""))
            // `commands` explicitly null.
            pump.push(probe("""{"conversation_id":"c1","commands":null,"dropped_commands":0}"""))
            // A row missing a required field drops the whole frame.
            pump.push(probe("""{"conversation_id":"c1","commands":[{"name":"x","description":"","aliases":[]}],"dropped_commands":0}"""))
            // The daemon's zero-value fixture: an empty conversation_id names nothing.
            pump.push(fixture(SlashCommandListPayloadsTest.SLASH_COMMAND_LIST_ZERO))
            runCurrent()

            assertEquals("the prior menu stands", listOf(null, SlashCommandMenu(listOf(row("clear")), 0)), menus)
            assertEquals("an empty id is never retained", listOf<SlashCommandMenu?>(null), unrouted)

            pump.push(frame("c1", listOf("usage"), id = 9L))
            runCurrent()
            assertEquals(listOf("usage"), menus.last()?.rows?.map { it.name })
        }

    // A value-identical re-snapshot and a frame for another conversation do not re-emit this conversation's
    // flow, so the connect-time burst costs a consumer nothing.
    @Test
    fun menu_identicalReSnapshotAndForeignFrames_doNotReEmit() =
        runTest {
            val (pump, repo) = interactiveRepo()
            val menus = collectMenu(repo, "c1")
            runCurrent()

            pump.push(frame("c1", listOf("clear"), id = 1L))
            pump.push(frame("c1", listOf("clear"), id = 2L))
            pump.push(frame("c2", listOf("model"), id = 3L))
            runCurrent()

            assertEquals(2, menus.size)
        }

    // The frame is a report with no request verb: subscribing asks for nothing, and a frame folds no thread
    // row and clears no stall.
    @Test
    fun menu_sendsNothingFoldsNoThreadRowAndClearsNoStall() =
        runTest {
            val (pump, repo) = interactiveRepo()
            val thread = mutableListOf<List<ThreadItem>>()
            backgroundScope.launch { repo.observeMessages("c1").collect { thread += it } }
            val stalls = mutableListOf<Boolean>()
            backgroundScope.launch { repo.observeStall("c1").collect { stalls += it } }
            collectMenu(repo, "c1")
            runCurrent()
            pump.push(Envelope(id = 1L, type = "stall", ts = TS, payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1"}""")))
            runCurrent()
            val sentBefore = pump.sent.toList()

            pump.push(fixture(SlashCommandListPayloadsTest.SLASH_COMMAND_LIST))
            runCurrent()

            assertEquals(listOf(false, true), stalls)
            assertEquals(listOf(emptyList<ThreadItem>()), thread)
            assertEquals(emptyList<Envelope>(), pump.sent.filter { it.type.contains("slash") })
            assertEquals(sentBefore, pump.sent)
        }

    // A repository lives for one connection (#351). After a reconnect the fresh repository starts empty, and
    // the connect-time snapshot, which carries no `event_id`, fills it again.
    @Test
    fun menu_freshRepositoryAfterReconnect_startsEmptyAndConnectTimeSnapshotFillsIt() =
        runTest {
            val (firstPump, firstRepo) = interactiveRepo()
            val before = collectMenu(firstRepo, "c1")
            runCurrent()
            firstPump.push(frame("c1", listOf("clear"), eventId = 41L))
            runCurrent()
            assertEquals(listOf("clear"), before.last()?.rows?.map { it.name })

            val (secondPump, secondRepo) = interactiveRepo()
            val after = collectMenu(secondRepo, "c1")
            runCurrent()
            assertEquals(listOf<SlashCommandMenu?>(null), after)

            secondPump.push(fixture(SlashCommandListPayloadsTest.SLASH_COMMAND_LIST))
            runCurrent()

            assertEquals(listOf("claude-api", "clear", "config", "model", "usage"), after.last()?.rows?.map { it.name })
        }

    private fun TestScope.interactiveRepo(): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
    }

    private fun TestScope.collectMenu(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<SlashCommandMenu?> {
        val emissions = mutableListOf<SlashCommandMenu?>()
        backgroundScope.launch { repo.observeSlashCommandMenu(conversationId).collect { emissions += it } }
        return emissions
    }

    private fun row(name: String) = SlashCommandMenuRow(name, "", "", emptyList(), null)

    /** A daemon fixture envelope, decoded as the transport would; the fixtures carry no `event_id`. */
    private fun fixture(envelope: String): Envelope = MobileJson.decodeFromString(Envelope.serializer(), envelope)

    /** A `slash_command_list` snapshot of bare rows named [names]; [eventId] set for the live-lane shape. */
    private fun frame(
        conversationId: String,
        names: List<String>,
        droppedCommands: Int = 0,
        id: Long = 1L,
        eventId: Long? = null,
    ): Envelope {
        val commands = names.joinToString(",") { """{"name":"$it","argument_hint":"","description":"","aliases":[]}""" }
        return Envelope(
            id = id,
            type = "slash_command_list",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","commands":[$commands],"dropped_commands":$droppedCommands}""",
                ),
            eventId = eventId,
        )
    }

    private fun probe(payload: String): Envelope =
        Envelope(id = 1L, type = "slash_command_list", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-23T10:00:00Z"
    }
}

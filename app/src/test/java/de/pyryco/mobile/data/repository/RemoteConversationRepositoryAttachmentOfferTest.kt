package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `attachment_offered` (#898): a file claude produced, announced to every attached client and filtered on
 * `conversation_id` here, held per conversation in arrival order for the life of the connection. Wire SSOT:
 * pyrycode `docs/protocol-mobile.md` § Attachments → `attachment_offered`.
 */
class RemoteConversationRepositoryAttachmentOfferTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun setUp() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun offers_emptyUntilAFrame_thenInArrivalOrder() =
        runTest {
            val (pump, repo) = repo()
            val offers = collect(repo.observeAttachmentOffers(CONV_A))
            runCurrent()
            assertEquals(listOf(emptyList<AttachmentOffer>()), offers)

            pump.push(offered(CONV_A, FILE_1, "report.png", id = 1L))
            runCurrent()
            pump.push(offered(CONV_A, FILE_2, "data.csv", id = 2L))
            runCurrent()

            assertEquals(
                listOf(
                    emptyList(),
                    listOf(AttachmentOffer(FILE_1, "report.png")),
                    listOf(AttachmentOffer(FILE_1, "report.png"), AttachmentOffer(FILE_2, "data.csv")),
                ),
                offers,
            )
        }

    // #983: each offer is also an assistant-side thread row, so the thread cache can keep what the wire won't replay.
    @Test
    fun offers_becomeAssistantRowsInArrivalOrder_eachCarryingTheSanitisedName() =
        runTest {
            val (pump, repo) = repo()
            val thread = collect(repo.observeMessages(CONV_A))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1, "report\u202E.png", id = 1L))
            runCurrent()
            pump.push(offered(CONV_A, FILE_2, "data.csv", id = 2L))
            runCurrent()

            val rows = thread.last().map { (it as ThreadItem.MessageItem).message }
            assertEquals(listOf("attachment-offer-$FILE_1", "attachment-offer-$FILE_2"), rows.map { it.id })
            assertEquals(listOf(Role.Assistant, Role.Assistant), rows.map { it.role })
            assertEquals(listOf("", ""), rows.map { it.content })
            assertTrue(rows.none { it.isStreaming })
            assertEquals(
                listOf(listOf(MessageAttachment(FILE_1, "report.png")), listOf(MessageAttachment(FILE_2, "data.csv"))),
                rows.map { it.attachments },
            )
        }

    @Test
    fun offers_aRepeatedAttachmentId_addsNoSecondRowAndKeepsTheFirstName() =
        runTest {
            val (pump, repo) = repo()
            val thread = collect(repo.observeMessages(CONV_A))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1, "first.png", id = 1L))
            runCurrent()
            pump.push(offered(CONV_A, FILE_1, "renamed.png", id = 2L))
            runCurrent()

            val row = (thread.last().single() as ThreadItem.MessageItem).message
            assertEquals(listOf(MessageAttachment(FILE_1, "first.png")), row.attachments)
        }

    @Test
    fun offers_rowOnlyInTheOfferedConversationsThread_andNotForADroppedFrame() =
        runTest {
            val (pump, repo) = repo()
            val threadA = collect(repo.observeMessages(CONV_A))
            val threadB = collect(repo.observeMessages(CONV_B))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1.uppercase(), "bad-id.png", id = 1L))
            pump.push(offered(CONV_B, FILE_2, "b.png", id = 2L))
            runCurrent()

            assertTrue(threadA.all { it.isEmpty() })
            assertEquals(listOf("attachment-offer-$FILE_2"), threadB.last().map { (it as ThreadItem.MessageItem).message.id })
        }

    // One entry per attachment id: a re-announcement neither moves nor renames the first arrival, and
    // re-emits nothing.
    @Test
    fun offers_repeatedAttachmentId_keepsTheFirstEntry() =
        runTest {
            val (pump, repo) = repo()
            val offers = collect(repo.observeAttachmentOffers(CONV_A))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1, "report.png", id = 1L))
            runCurrent()
            pump.push(offered(CONV_A, FILE_2, "data.csv", id = 2L))
            runCurrent()
            pump.push(offered(CONV_A, FILE_1, "renamed.png", id = 3L))
            runCurrent()

            assertEquals(
                listOf(
                    emptyList(),
                    listOf(AttachmentOffer(FILE_1, "report.png")),
                    listOf(AttachmentOffer(FILE_1, "report.png"), AttachmentOffer(FILE_2, "data.csv")),
                ),
                offers,
            )
        }

    // The daemon routes nothing: every attached client receives every offer, so the conversation filter is
    // this repository's, and another conversation's observer must see nothing — not even a re-emission.
    @Test
    fun offers_anotherConversationsObserverSeesNothing() =
        runTest {
            val (pump, repo) = repo()
            val offersA = collect(repo.observeAttachmentOffers(CONV_A))
            val offersB = collect(repo.observeAttachmentOffers(CONV_B))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1, "report.png", id = 1L))
            runCurrent()

            assertEquals(listOf(emptyList(), listOf(AttachmentOffer(FILE_1, "report.png"))), offersA)
            assertEquals(listOf(emptyList<AttachmentOffer>()), offersB)
        }

    // Each host has its own connection-scoped repository; an offer arriving on one host's connection
    // never reaches another host's.
    @Test
    fun offers_anotherHostsRepositorySeesNothing() =
        runTest {
            val (pumpH, repoH) = repo()
            val (_, repoOther) = repo()
            val offersH = collect(repoH.observeAttachmentOffers(CONV_A))
            val offersOther = collect(repoOther.observeAttachmentOffers(CONV_A))
            runCurrent()

            pumpH.push(offered(CONV_A, FILE_1, "report.png", id = 1L))
            runCurrent()

            assertEquals(listOf(AttachmentOffer(FILE_1, "report.png")), offersH.last())
            assertEquals(listOf(emptyList<AttachmentOffer>()), offersOther)
        }

    // The frame is delivered to every attached client, not only interactive ones.
    @Test
    fun offers_areNotGatedOnTheInteractiveCapability() =
        runTest {
            val (pump, repo) = repo(capabilities = emptySet())
            val offers = collect(repo.observeAttachmentOffers(CONV_A))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1, "report.png", id = 1L))
            runCurrent()

            assertEquals(listOf(AttachmentOffer(FILE_1, "report.png")), offers.last())
        }

    @Test
    fun offers_nonConformingIdsOrMalformedPayloads_droppedWithoutHarmingOtherFrames() =
        runTest {
            val (pump, repo) = repo()
            val offers = collect(repo.observeAttachmentOffers(CONV_A))
            val compacting = collect(repo.observeCompacting(CONV_A))
            runCurrent()

            // Ids that are not a lowercase UUIDv4.
            pump.push(offered(CONV_A, FILE_1.uppercase(), "a.png", id = 1L))
            pump.push(offered(CONV_A, "../../etc/passwd", "a.png", id = 2L))
            pump.push(offered(CONV_A, "", "a.png", id = 3L))
            pump.push(offered(CONV_A.uppercase(), FILE_1, "a.png", id = 4L))
            pump.push(offered("c1", FILE_1, "a.png", id = 5L))
            pump.push(offered("", FILE_1, "a.png", id = 6L))
            // Payloads that do not decode: each field missing in turn, a wrong type, not an object.
            pump.push(probe("""{"attachment_id":"$FILE_1","filename":"a.png"}""", id = 7L))
            pump.push(probe("""{"conversation_id":"$CONV_A","filename":"a.png"}""", id = 8L))
            pump.push(probe("""{"conversation_id":"$CONV_A","attachment_id":"$FILE_1"}""", id = 9L))
            pump.push(probe("""{"conversation_id":"$CONV_A","attachment_id":"$FILE_1","filename":7}""", id = 10L))
            pump.push(probe("""{"conversation_id":"$CONV_A","attachment_id":"$FILE_1","filename":null}""", id = 11L))
            pump.push(probe("[]", id = 12L))
            runCurrent()
            assertEquals(listOf(emptyList<AttachmentOffer>()), offers)

            // The collector survived: the next offer and an unrelated frame both land.
            pump.push(offered(CONV_A, FILE_2, "data.csv", id = 13L))
            pump.push(
                Envelope(
                    id = 14L,
                    type = "compacting",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"$CONV_A","active":true}"""),
                ),
            )
            runCurrent()
            assertEquals(listOf(AttachmentOffer(FILE_2, "data.csv")), offers.last())
            assertEquals(listOf(false, true), compacting)
        }

    // The name is claude-authored and the daemon does not sanitise it on the wire's behalf.
    @Test
    fun offers_hostileName_isExposedWithoutControlOrFormatCharacters() =
        runTest {
            val (pump, repo) = repo()
            val offers = collect(repo.observeAttachmentOffers(CONV_A))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1, "\u001B[2J\u001B]0;owned\u0007invoice-‮exe.pdf", id = 1L))
            pump.push(offered(CONV_A, FILE_2, "ä".repeat(300), id = 2L))
            runCurrent()

            val (escaped, long) = offers.last()
            assertEquals(AttachmentOffer(FILE_1, "[2J]0;ownedinvoice-exe.pdf"), escaped)
            assertEquals("ä".repeat(127), long.displayName)
        }

    // An offer whose name is empty after cleaning still names a fetchable file.
    @Test
    fun offers_nameEmptyAfterCleaning_isKept() =
        runTest {
            val (pump, repo) = repo()
            val offers = collect(repo.observeAttachmentOffers(CONV_A))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1, "‮\u0000", id = 1L))
            runCurrent()

            assertEquals(listOf(AttachmentOffer(FILE_1, "")), offers.last())
        }

    // Only a shape-validated attachment id is ever logged: never the name, never the conversation id, and
    // never an id that failed validation.
    @Test
    fun offers_logOnlyTheValidatedAttachmentId() =
        runTest {
            val (pump, repo) = repo()
            collect(repo.observeAttachmentOffers(CONV_A))
            runCurrent()

            pump.push(offered(CONV_A, FILE_1, "private-name.pdf", id = 1L))
            pump.push(offered(CONV_A, "not-an-id", "other-private.pdf", id = 2L))
            runCurrent()

            assertEquals(listOf("event=attachment_offered id=$FILE_1", "event=attachment_offered outcome=dropped"), logs)
            for (line in logs) {
                assertFalse(line.contains("private"))
                assertFalse(line.contains(CONV_A))
                assertFalse(line.contains("not-an-id"))
            }
        }

    private fun TestScope.repo(
        capabilities: Set<String> = setOf(CAPABILITY_INTERACTIVE),
    ): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        val repo = RemoteConversationRepository(pump = pump, scope = backgroundScope, negotiatedCapabilities = { capabilities })
        return pump to repo
    }

    private fun <T> TestScope.collect(flow: Flow<T>): MutableList<T> {
        val emissions = mutableListOf<T>()
        backgroundScope.launch { flow.collect { emissions += it } }
        return emissions
    }

    /** An `attachment_offered` envelope; the name goes through the JSON encoder so escapes survive intact. */
    private fun offered(
        conversationId: String,
        attachmentId: String,
        filename: String,
        id: Long,
    ): Envelope =
        probe(
            """{"conversation_id":"$conversationId","attachment_id":"$attachmentId","filename":${JsonPrimitive(filename)}}""",
            id,
        )

    private fun probe(
        payload: String,
        id: Long,
    ): Envelope = Envelope(id = id, type = "attachment_offered", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        override fun send(envelope: Envelope): Boolean = true

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-23T10:00:00Z"
        const val CONV_A = "9d4e7a21-8c05-4f3b-b6e2-1a7c9e30d5f4"
        const val CONV_B = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
        const val FILE_1 = "b8e0c374-2f61-4a95-8d0e-5c37a91b6e28"
        const val FILE_2 = "3f2a1c40-9b7e-4d16-a5c3-0e8f1b2d4a67"
    }
}

package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QueuedReplyRecoveryEvidenceTest {
    private val original = Any()
    private val replacement = Any()
    private val queued =
        scenarioUser(3, "bye", "queued", order = null)
            .change("active", JsonPrimitive(true))
            .withContent("message_id", "queued-send")
            .withContent("device_id", "phone")
    private val open = scenarioItem(2, text = "Hello, streamed ", rev = 2, active = true)
    private val done = scenarioItem(2, text = REPLY, rev = 4)
    private val initial = scenarioSnapshot(listOf(scenarioUser(1, "hello"), open, queued)).copy(incarnation = "document")

    private fun ThreadItemDto.withContent(
        key: String,
        value: String,
    ): ThreadItemDto =
        ThreadItemDto.decode(JsonObject(raw + ("content" to JsonObject((content as JsonObject) + (key to JsonPrimitive(value))))))

    private fun ThreadItemDto.change(
        key: String,
        value: kotlinx.serialization.json.JsonElement,
    ): ThreadItemDto = ThreadItemDto.decode(JsonObject(raw + (key to value)))

    private fun evidence(
        snapshot: AuthoritativeThreadSnapshot,
        host: String = "host",
        chat: String = "chat",
        connection: Any = replacement,
    ) = authoritativeThreadEvidence(host, chat, connection, snapshot)

    private fun finished(vararg extra: ThreadItemDto) = initial.copy(items = listOf(initial.items[0], done, queued) + extra)

    @Test fun initialMemoryDocumentSurvivesReplacementButNeverAReset() {
        val document = initial.copy(incarnation = "", generation = 0)
        val started = evidence(document, connection = original)
        val held = requireNotNull(started.heldOpenReply(started.owner, "Hello, streamed"))
        val completed = finished().copy(incarnation = "", generation = 0)
        assertNotNull(evidence(completed).settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement))
        for (reset in listOf(completed.copy(generation = 1), completed.copy(incarnation = "reset"), completed.copy(epoch = "reset"))) {
            assertNull(evidence(reset).settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement))
        }
    }

    @Test fun aLaterQueuedTurnDoesNotSplitTheCapturedReply() {
        val started = evidence(initial, connection = original)
        val held = requireNotNull(started.heldOpenReply(started.owner, "Hello, streamed"))
        val current = evidence(finished(scenarioItem(4, text = "bye", turn = "queued-turn")))
        assertNotNull(current.settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement))
    }

    @Test fun recoveryRejectsRetiredForeignReplacedAndUnadmittedDocuments() {
        val started = evidence(initial, connection = original)
        val held = requireNotNull(started.heldOpenReply(started.owner, "Hello, streamed"))
        val finished = finished()
        for (generation in listOf(initial.generation, initial.generation + 1)) {
            val current = evidence(finished.copy(generation = generation))
            assertFalse("ordinary owner matching must remain strict", current.owner.matches(started.owner))
            assertNotNull(current.settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement))
        }
        for (bad in listOf(
            evidence(finished, connection = original),
            evidence(finished, host = "other"),
            evidence(finished, chat = "other"),
            evidence(finished.copy(epoch = "replaced")),
            evidence(finished.copy(incarnation = "replaced")),
            evidence(finished.copy(incarnation = "")),
            evidence(finished.copy(generation = initial.generation - 1)),
            evidence(finished.copy(resetNeeded = true)),
            evidence(finished.copy(repairNeeded = true)),
            evidence(finished.copy(epoch = null)),
        )) {
            assertNull(bad.settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement))
        }
    }

    @Test fun recoveryRejectsChangedReplyIdentityIncompleteStaleDuplicateAndSplitReplies() {
        val started = evidence(initial, connection = original)
        val held = requireNotNull(started.heldOpenReply(started.owner, "Hello, streamed"))
        val changes =
            listOf(
                "id" to JsonPrimitive(20),
                "session" to JsonPrimitive("other"),
                "turn" to JsonPrimitive("other"),
                "agent" to JsonPrimitive("other"),
                "parent" to JsonPrimitive(99),
                "order" to JsonPrimitive(20),
                "rev" to JsonPrimitive(2),
                "active" to JsonPrimitive(true),
                "status" to JsonPrimitive("running"),
            )
        val badItems =
            changes.map { (key, value) -> listOf(initial.items[0], done.change(key, value), queued) } +
                listOf(
                    listOf(initial.items[0], done.withContent("text", "Hello, streamed wrong"), queued),
                    finished(scenarioItem(4, text = "world")).items,
                    finished(scenarioItem(4, text = REPLY, turn = "other")).items,
                )
        for (items in badItems) {
            assertNull(evidence(initial.copy(items = items)).settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement))
        }
    }

    @Test fun recoveryPreservesQueuedItemMessageDeviceAndTextAcrossDelivery() {
        val started = evidence(initial, connection = original)
        val held = requireNotNull(started.heldOpenReply(started.owner, "Hello, streamed"))
        val delivered =
            queued
                .change(
                    "status",
                    JsonPrimitive("delivered"),
                ).change("active", JsonPrimitive(false))
                .change("rev", JsonPrimitive(5))
        assertNotNull(
            evidence(initial.copy(items = listOf(initial.items[0], done, delivered)))
                .settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement),
        )
        for (bad in listOf(
            queued.change("id", JsonPrimitive(30)),
            queued.change("rev", JsonPrimitive(1)),
            queued.withContent("message_id", "other"),
            queued.withContent("device_id", "other"),
            queued.withContent("text", "other"),
        )) {
            assertNull(
                evidence(initial.copy(items = listOf(initial.items[0], done, bad)))
                    .settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement),
            )
        }
        assertNull(
            evidence(finished(queued.change("id", JsonPrimitive(30))))
                .settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement),
        )
        assertNull(
            evidence(initial.copy(items = listOf(initial.items[0], done)))
                .settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement),
        )
    }

    @Test fun replacementAfterQueuedReleaseSettlesOnlyOnCurrentAuthenticatedSource() =
        runTest {
            val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(original))
            val snapshots = MutableStateFlow(initial)
            val route =
                object : NegotiatedThread() {
                    override val host = "host"
                    override val support = support
                    override val store = AuthoritativeThreadStore()

                    override fun observe(conversationId: String) = snapshots

                    override suspend fun open(conversationId: String): Boolean = error("evidence must stay passive")

                    override fun older(conversationId: String): Boolean = error("evidence must stay passive")

                    override fun cancel(conversationId: String): Unit = error("evidence must stay passive")
                }
            val source = ThreadEvidenceSource.negotiated(route) { snapshots }
            val started = source.observe("chat").first()
            val held = requireNotNull(started.heldOpenReply(started.owner, "Hello, streamed"))
            // Queued admission precedes explicit release; retire before completion can be observed.
            assertNull(started.settledQueuedReplyAfterRecovery(started, held, queued, REPLY, original))
            support.value = ThreadSupport.Unverified
            snapshots.value = initial.copy(items = listOf(initial.items[0], done, queued), generation = initial.generation + 1)
            assertNull(source.observe("chat").first().settledQueuedReplyAfterRecovery(started, held, queued, REPLY, null))
            support.value = ThreadSupport.Supported(replacement)
            val recovered = source.observe("chat").first()
            assertNotNull(
                "completion must follow the admitted replacement and reconcile the held durable identity",
                recovered.settledQueuedReplyAfterRecovery(started, held, queued, REPLY, replacement),
            )
            assertNull(
                "retired evidence cannot certify recovery",
                recovered.settledQueuedReplyAfterRecovery(started, held, queued, REPLY, original),
            )
        }

    companion object {
        private const val REPLY = "Hello, streamed world"
    }
}

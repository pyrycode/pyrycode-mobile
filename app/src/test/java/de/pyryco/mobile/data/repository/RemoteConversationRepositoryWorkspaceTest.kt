package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workspace rename and archive on one host (#663): `rename_workspace` → correlated `workspace_updated`,
 * and the client-side archive fan-out over `archive_conversation`. Every row is seeded and every reply
 * delivered through real inbound dispatch, as #721's tests are. A sibling of
 * [RemoteConversationRepositoryTest] with its own small pump, because that class is already several
 * thousand lines long.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryWorkspaceTest {
    // ---- rename ----------------------------------------------------------------------------------

    @Test
    fun rename_sendsExactPathAndLabel_andReturnsOnlyAfterTheReplyIsApplied() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)
            val all = collect(repo, ConversationFilter.All)
            runCurrent()

            val rename = startRename(repo, "/w/alpha", "Tax filing")
            runCurrent()
            val sent = pump.sent.single { it.type == "rename_workspace" }
            assertEquals(MobileJson.parseToJsonElement("""{"path":"/w/alpha","label":"Tax filing"}"""), sent.payload)
            assertNull(rename.outcome) // still waiting for the correlated reply

            pump.push(workspaceUpdated(inReplyTo = sent.id, path = "/w/alpha", label = "Tax filing"))
            runCurrent()

            assertNull(rename.result().exceptionOrNull())
            val rows = all.last().associateBy { it.id }
            assertEquals("Tax filing", rows.getValue("a1").workspaceLabel)
            assertEquals("Tax filing", rows.getValue("a2").workspaceLabel)
            assertEquals("Tax filing", rows.getValue("aArchived").workspaceLabel)
            assertEquals("Beta label", rows.getValue("b1").workspaceLabel)
            assertNull(rows.getValue("aSlash").workspaceLabel)
        }

    // The label must already be in the projection at the instant the call resumes, not one dispatch later.
    // The caller runs unconfined, so completing its waiter resumes it synchronously inside the inbound
    // arm: completing before applying would let it read the old label.
    @Test
    fun rename_labelIsVisibleWhenTheCallReturns() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)
            var seenOnReturn: String? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repo.renameWorkspace("/w/alpha", "Tax filing")
                seenOnReturn =
                    repo
                        .observeConversations(ConversationFilter.All)
                        .first()
                        .single { it.id == "a1" }
                        .workspaceLabel
            }
            runCurrent()

            pump.push(workspaceUpdated(inReplyTo = pump.renameId(), path = "/w/alpha", label = "Tax filing"))
            runCurrent()

            assertEquals("Tax filing", seenOnReturn)
        }

    @Test
    fun rename_nullLabel_omitsTheKeyAndClearsEveryRowAtThatPath() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)
            val all = collect(repo, ConversationFilter.All)
            runCurrent()

            val rename = startRename(repo, "/w/beta", null)
            runCurrent()
            val sent = pump.sent.single { it.type == "rename_workspace" }
            assertEquals(MobileJson.parseToJsonElement("""{"path":"/w/beta"}"""), sent.payload)
            pump.push(workspaceUpdated(inReplyTo = sent.id, path = "/w/beta", label = null))
            runCurrent()

            assertNull(rename.result().exceptionOrNull())
            assertNull(all.last().single { it.id == "b1" }.workspaceLabel)
        }

    // Two hosts seeded with byte-identical ids and cwds: renaming on A leaves B untouched.
    @Test
    fun rename_onOneHost_leavesTheOtherHostUnchanged() =
        runTest {
            val hostA = FakeSessionPump()
            val hostB = FakeSessionPump()
            val repoA = seededRepo(hostA)
            val repoB = seededRepo(hostB)
            val b = collect(repoB, ConversationFilter.All)
            runCurrent()

            val rename = startRename(repoA, "/w/alpha", "Tax filing")
            runCurrent()
            hostA.push(workspaceUpdated(inReplyTo = hostA.renameId(), path = "/w/alpha", label = "Tax filing"))
            runCurrent()

            assertNull(rename.result().exceptionOrNull())
            assertTrue(hostB.sent.none { it.type == "rename_workspace" })
            assertEquals("Alpha label", b.last().single { it.id == "a1" }.workspaceLabel)
        }

    @Test
    fun rename_refusals_failWithTheirCode_andChangeNoRow() =
        runTest {
            for (code in listOf("workspace.not_found", "protocol.malformed")) {
                val pump = FakeSessionPump()
                val repo = seededRepo(pump)
                val all = collect(repo, ConversationFilter.All)
                runCurrent()
                val before = all.last()

                val rename = startRename(repo, "/w/alpha", "Tax filing")
                runCurrent()
                pump.push(error(pump.renameId(), code))
                runCurrent()

                val thrown = rename.result().exceptionOrNull()
                assertTrue("$code must not read as an unknown conversation", thrown !is IllegalArgumentException)
                assertEquals(code, (thrown as RelayErrorException).code)
                assertEquals(before, all.last())
            }
        }

    @Test
    fun rename_connectionTornDownBeforeTheReply_failsAndChangesNoRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)
            val all = collect(repo, ConversationFilter.All)
            runCurrent()
            val before = all.last()

            val rename = startRename(repo, "/w/alpha", "Tax filing")
            runCurrent()
            pump.close()
            runCurrent()

            assertTrue(rename.result().exceptionOrNull() is IllegalStateException)
            assertEquals(before, all.last())
        }

    @Test
    fun rename_notConnected_failsWithoutWaiting() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)
            pump.open = false

            val rename = startRename(repo, "/w/alpha", "Tax filing")
            runCurrent()

            assertTrue(rename.result().exceptionOrNull() is IllegalStateException)
        }

    // A malformed correlated reply fails the caller rather than hanging it, and applies nothing.
    @Test
    fun rename_malformedReply_failsAsMalformed() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)
            val all = collect(repo, ConversationFilter.All)
            runCurrent()
            val before = all.last()

            val rename = startRename(repo, "/w/alpha", "Tax filing")
            runCurrent()
            pump.push(workspaceUpdatedRaw(inReplyTo = pump.renameId(), raw = """{"label":"Tax filing"}"""))
            runCurrent()

            val thrown = rename.result().exceptionOrNull()
            assertEquals(RemoteConversationRepository.ERROR_MALFORMED_REPLY, (thrown as RelayErrorException).code)
            assertFalse(thrown.message.orEmpty().contains("Tax filing"))
            assertEquals(before, all.last())
        }

    // A reply that does not confirm the requested path cannot report success: the caller would believe a
    // label it never saw was stored.
    @Test
    fun rename_replyForAnotherPathOrOfAnotherType_failsAsMalformed() =
        runTest {
            val replies =
                listOf<(Long) -> Envelope>(
                    { id -> workspaceUpdated(inReplyTo = id, path = "/w/beta", label = "Tax filing") },
                    { id -> conversationUpdated(inReplyTo = id, id = "a1", cwd = "/w/alpha", archived = false) },
                )
            for (reply in replies) {
                val pump = FakeSessionPump()
                val repo = seededRepo(pump)

                val rename = startRename(repo, "/w/alpha", "Tax filing")
                runCurrent()
                pump.push(reply(pump.renameId()))
                runCurrent()

                val thrown = rename.result().exceptionOrNull()
                assertEquals(RemoteConversationRepository.ERROR_MALFORMED_REPLY, (thrown as RelayErrorException).code)
            }
        }

    // The unsolicited push keeps applying with no request in flight (the #721 behaviour).
    @Test
    fun unsolicitedPush_stillApplies() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)
            val all = collect(repo, ConversationFilter.All)
            runCurrent()

            pump.push(workspaceUpdated(inReplyTo = null, path = "/w/alpha", label = "From desktop"))
            runCurrent()

            assertEquals("From desktop", all.last().single { it.id == "a1" }.workspaceLabel)
        }

    // ---- archive ---------------------------------------------------------------------------------

    @Test
    fun archive_sendsOneArchivePerActiveRowAtTheExactPath_andNothingElse() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)

            val archive = startArchive(repo, "/w/alpha")
            runCurrent()
            confirmAll(pump)

            assertNull(archive.result().exceptionOrNull())
            val sent = pump.sent.filter { it.type != "list_conversations" }
            assertTrue(sent.all { it.type == "archive_conversation" })
            assertEquals(listOf("a1", "a2"), sent.map { it.conversationId() }.sorted())
        }

    @Test
    fun archive_rowsLeaveTheActiveListOneByOne_andTheCallReturnsAfterTheLast() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)
            val channels = collect(repo, ConversationFilter.Channels)
            val discussions = collect(repo, ConversationFilter.Discussions)
            val all = collect(repo, ConversationFilter.All)
            runCurrent()

            val archive = startArchive(repo, "/w/alpha")
            runCurrent()
            val first = pump.sent.first { it.type == "archive_conversation" }
            val firstId = first.conversationId()
            assertTrue(activeIds(channels, discussions).containsAll(listOf("a1", "a2")))

            pump.push(archivedReply(first.id, firstId))
            runCurrent()
            assertFalse(firstId in activeIds(channels, discussions))
            assertNull(archive.outcome)

            val second = pump.sent.last { it.type == "archive_conversation" }
            assertTrue(second.conversationId() != firstId)
            pump.push(archivedReply(second.id, second.conversationId()))
            runCurrent()

            assertNull(archive.result().exceptionOrNull())
            assertFalse(activeIds(channels, discussions).any { it == "a1" || it == "a2" })
            // The stored label stays: archiving a conversation does not un-name its folder.
            assertEquals("Alpha label", all.last().single { it.id == "a1" }.workspaceLabel)
            assertTrue(pump.sent.none { it.type == "rename_workspace" || it.type == "delete_conversation" })
        }

    @Test
    fun archive_pathWithNoActiveRows_sendsNothingAndReturns() =
        runTest {
            for (path in listOf("/w/archived-only", "/w/nowhere")) {
                val pump = FakeSessionPump()
                val repo = seededRepo(pump)

                val archive = startArchive(repo, path)
                runCurrent()

                assertNull(archive.result().exceptionOrNull())
                assertTrue(path, pump.sent.none { it.type == "archive_conversation" })
            }
        }

    // A near-identical path is a different workspace: only the byte-equal one is archived.
    @Test
    fun archive_trailingSeparatorOrSpaceSiblings_areLeftAlone() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump)

            startArchive(repo, "/w/alpha/")
            runCurrent()

            assertEquals(listOf("aSlash"), pump.sent.filter { it.type == "archive_conversation" }.map { it.conversationId() })
        }

    @Test
    fun archive_onOneHost_leavesTheOtherHostUnchanged() =
        runTest {
            val hostA = FakeSessionPump()
            val hostB = FakeSessionPump()
            val repoA = seededRepo(hostA)
            val repoB = seededRepo(hostB)
            val b = collect(repoB, ConversationFilter.Channels)
            runCurrent()
            val before = b.last()

            val archive = startArchive(repoA, "/w/alpha")
            runCurrent()
            confirmAll(hostA)

            assertNull(archive.result().exceptionOrNull())
            assertTrue(hostB.sent.none { it.type == "archive_conversation" })
            assertEquals(before, b.last())
        }

    @Test
    fun archive_oneRefusal_triesTheRest_failsWithTheFirstError_andARetrySendsOnlyTheRefusedRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump, fixture = THREE_ACTIVE_FIXTURE)
            val channels = collect(repo, ConversationFilter.Channels)
            val discussions = collect(repo, ConversationFilter.Discussions)
            runCurrent()

            val archive = startArchive(repo, "/w/gamma")
            runCurrent()
            val refusedIds = mutableListOf<String>()
            repeat(3) { index ->
                val request = pump.sent.filter { it.type == "archive_conversation" }[index]
                if (index == 1) {
                    refusedIds += request.conversationId()
                    pump.push(error(request.id, "internal.error"))
                } else {
                    pump.push(archivedReply(request.id, request.conversationId(), cwd = "/w/gamma"))
                }
                runCurrent()
            }

            assertEquals(3, pump.sent.count { it.type == "archive_conversation" })
            assertEquals("internal.error", (archive.result().exceptionOrNull() as RelayErrorException).code)
            assertEquals(refusedIds, activeIds(channels, discussions).filter { it.startsWith("g") })

            val retry = startArchive(repo, "/w/gamma")
            runCurrent()
            val retried = pump.sent.filter { it.type == "archive_conversation" }.drop(3)
            assertEquals(refusedIds, retried.map { it.conversationId() })
            pump.push(archivedReply(retried.single().id, refusedIds.single(), cwd = "/w/gamma"))
            runCurrent()

            assertNull(retry.result().exceptionOrNull())
            assertTrue(activeIds(channels, discussions).none { it.startsWith("g") })
        }

    // Only the first error is reported, even when a later row fails differently.
    @Test
    fun archive_multipleRefusals_reportTheFirst() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump, fixture = THREE_ACTIVE_FIXTURE)

            val archive = startArchive(repo, "/w/gamma")
            runCurrent()
            listOf("first.code", "second.code", "third.code").forEachIndexed { index, code ->
                pump.push(error(pump.sent.filter { it.type == "archive_conversation" }[index].id, code))
                runCurrent()
            }

            assertEquals("first.code", (archive.result().exceptionOrNull() as RelayErrorException).code)
        }

    // The connection drops mid-fan-out: the rest are still attempted (and fail fast), confirmed rows stay
    // archived, and the failure is the teardown of the first unanswered request.
    @Test
    fun archive_connectionDropsMidway_attemptsTheRestAndFailsWithTheFirstError() =
        runTest {
            val pump = FakeSessionPump()
            val repo = seededRepo(pump, fixture = THREE_ACTIVE_FIXTURE)
            val channels = collect(repo, ConversationFilter.Channels)
            val discussions = collect(repo, ConversationFilter.Discussions)
            runCurrent()

            val archive = startArchive(repo, "/w/gamma")
            runCurrent()
            val first = pump.sent.first { it.type == "archive_conversation" }
            pump.push(archivedReply(first.id, first.conversationId(), cwd = "/w/gamma"))
            runCurrent()
            pump.open = false
            pump.close()
            runCurrent()

            assertTrue(archive.result().exceptionOrNull() is IllegalStateException)
            assertEquals(3, pump.attempted.count { it.type == "archive_conversation" })
            assertFalse(first.conversationId() in activeIds(channels, discussions))
        }

    // ---- helpers ---------------------------------------------------------------------------------

    private class Pending<T> {
        var outcome: Result<T>? = null

        fun result(): Result<T> = requireNotNull(outcome) { "call has not completed" }
    }

    private fun TestScope.seededRepo(
        pump: FakeSessionPump,
        fixture: String = FIXTURE,
    ): RemoteConversationRepository {
        val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
        pump.push(Envelope(id = 1L, type = "conversations", ts = TS, payload = MobileJson.parseToJsonElement(fixture)))
        runCurrent()
        return repo
    }

    private fun TestScope.collect(
        repo: RemoteConversationRepository,
        filter: ConversationFilter,
    ): MutableList<List<Conversation>> {
        val emissions = mutableListOf<List<Conversation>>()
        backgroundScope.launch { repo.observeConversations(filter).collect { emissions += it } }
        return emissions
    }

    private fun activeIds(
        channels: List<List<Conversation>>,
        discussions: List<List<Conversation>>,
    ): List<String> = (channels.last() + discussions.last()).map { it.id }.sorted()

    private fun TestScope.startRename(
        repo: RemoteConversationRepository,
        path: String,
        label: String?,
    ): Pending<Unit> {
        val pending = Pending<Unit>()
        backgroundScope.launch { pending.outcome = runCatching { repo.renameWorkspace(path, label) } }
        return pending
    }

    private fun TestScope.startArchive(
        repo: RemoteConversationRepository,
        path: String,
    ): Pending<Unit> {
        val pending = Pending<Unit>()
        backgroundScope.launch { pending.outcome = runCatching { repo.archiveWorkspace(path) } }
        return pending
    }

    /** Answer every archive request the pump has seen so far, one at a time, as the daemon would. */
    private fun TestScope.confirmAll(pump: FakeSessionPump) {
        var answered = 0
        while (true) {
            val requests = pump.sent.filter { it.type == "archive_conversation" }
            if (answered == requests.size) return
            val request = requests[answered++]
            pump.push(archivedReply(request.id, request.conversationId()))
            runCurrent()
        }
    }

    private fun FakeSessionPump.renameId(): Long = sent.single { it.type == "rename_workspace" }.id

    private fun Envelope.conversationId(): String =
        payload.jsonObject
            .getValue("conversation_id")
            .jsonPrimitive.content

    private fun workspaceUpdated(
        inReplyTo: Long?,
        path: String,
        label: String?,
    ): Envelope {
        val labelJson = if (label == null) "null" else "\"$label\""
        return workspaceUpdatedRaw(inReplyTo, """{"path":"$path","label":$labelJson}""")
    }

    private fun workspaceUpdatedRaw(
        inReplyTo: Long?,
        raw: String,
    ) = Envelope(id = 97L, type = "workspace_updated", ts = TS, payload = MobileJson.parseToJsonElement(raw), inReplyTo = inReplyTo)

    private fun archivedReply(
        inReplyTo: Long,
        id: String,
        cwd: String = "/w/alpha",
    ) = conversationUpdated(inReplyTo, id, cwd, archived = true)

    private fun conversationUpdated(
        inReplyTo: Long,
        id: String,
        cwd: String,
        archived: Boolean,
    ) = Envelope(
        id = 98L,
        type = "conversation_updated",
        ts = TS,
        payload =
            MobileJson.parseToJsonElement(
                """{"id":"$id","name":"Row $id","is_promoted":true,"is_archived":$archived,"cwd":"$cwd","last_used_at":"2026-05-08T10:00:00Z","workspace_label":"Alpha label"}""",
            ),
        inReplyTo = inReplyTo,
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

    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        /** Frames the pump accepted — what reached the wire. */
        val sent = mutableListOf<Envelope>()

        /** Every frame the repository tried to send, accepted or not. */
        val attempted = mutableListOf<Envelope>()

        var open = true

        override fun send(envelope: Envelope): Boolean {
            attempted += envelope
            if (open) sent += envelope
            return open
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }

        /** End the inbound stream — the repository's teardown path, which fails every pending request. */
        fun close() {
            inboundChannel.close()
        }
    }

    private companion object {
        const val TS = "2026-09-23T00:00:00Z"

        private fun row(
            id: String,
            cwd: String,
            promoted: Boolean = true,
            archived: Boolean = false,
            label: String? = null,
        ): String {
            val labelJson = if (label == null) "null" else "\"$label\""
            return """{"id":"$id","name":"Row $id","is_promoted":$promoted,"cwd":"$cwd","is_archived":$archived,""" +
                """"last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z","workspace_label":$labelJson}"""
        }

        /**
         * `/w/alpha` ("Alpha label") holds an active channel, an active discussion and an archived row; `/w/alpha/` and
         * `/w/alpha ` are distinct workspaces; `/w/beta` carries its own label; `/w/archived-only` holds
         * nothing active.
         */
        val FIXTURE =
            listOf(
                row("a1", "/w/alpha", label = "Alpha label"),
                row("a2", "/w/alpha", promoted = false, label = "Alpha label"),
                row("aArchived", "/w/alpha", archived = true, label = "Alpha label"),
                row("aSlash", "/w/alpha/"),
                row("aSpace", "/w/alpha "),
                row("b1", "/w/beta", label = "Beta label"),
                row("z1", "/w/archived-only", archived = true),
            ).joinToString(prefix = """{"conversations":[""", postfix = "]}")

        val THREE_ACTIVE_FIXTURE =
            listOf(
                row("g1", "/w/gamma"),
                row("g2", "/w/gamma", promoted = false),
                row("g3", "/w/gamma"),
            ).joinToString(prefix = """{"conversations":[""", postfix = "]}")
    }
}

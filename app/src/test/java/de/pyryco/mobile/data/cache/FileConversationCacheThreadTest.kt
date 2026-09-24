package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Off-device proof for the cache's thread-row family (#797).
 *
 * Same discipline as [FileConversationCacheTest]: every persistence assertion reads through a **second**
 * [FileConversationCache] over the same root, the stand-in for a fresh process that did not write it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileConversationCacheThreadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(tmp.root, "conversation-cache")
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    private fun cache() = FileConversationCache(root, UnconfinedTestDispatcher())

    private fun message(
        id: String,
        role: Role = Role.Assistant,
        isStreaming: Boolean = false,
        toolCall: ToolCall? = null,
    ) = ThreadItem.MessageItem(
        Message(
            id = id,
            sessionId = "session-1",
            role = role,
            content = "content of $id",
            timestamp = Instant.parse("2026-09-22T10:11:12.123456789Z"),
            isStreaming = isStreaming,
            toolCall = toolCall,
        ),
    )

    private fun tool(
        id: String,
        status: ToolCallStatus,
    ) = message(id, role = Role.Tool, toolCall = ToolCall("Bash", "ls -la", "total 0", status))

    private fun boundary(
        previous: String = "session-1",
        next: String = "session-2",
        reason: BoundaryReason = BoundaryReason.Clear,
        cwd: String? = null,
        at: String = "2026-09-22T11:00:00Z",
    ) = ThreadItem.SessionBoundary(previous, next, reason, Instant.parse(at), cwd)

    private fun unrecognized(id: String) =
        ThreadItem.UnrecognizedMessage(
            id,
            UnrecognizedSite.LineType,
            "mystery",
            "{\"raw\":true}",
            false,
            Instant.parse("2026-09-22T11:00:00Z"),
        )

    private fun threadFiles(): List<File> = root.walkTopDown().filter { it.isFile && it.parentFile?.name == "threads" }.toList()

    @Test
    fun `a thread round-trips field-for-field through a fresh instance`() =
        runTest {
            val rows =
                listOf(
                    message("m1", role = Role.User),
                    tool("toolu_1", ToolCallStatus.Done),
                    tool("toolu_2", ToolCallStatus.Failed),
                    boundary(),
                    boundary("session-2", "session-3", BoundaryReason.WorkspaceChange, cwd = "/work/tree"),
                    message("m2").copy(message = message("m2").message.copy(sessionId = "session-3")),
                )
            assertTrue(cache().writeThread("server-a", "conv-1", rows).isSuccess)

            assertEquals(rows, cache().readThread("server-a", "conv-1"))
        }

    @Test
    fun `attachment references round-trip with ids names and mime hints unchanged`() =
        runTest {
            fun withAttachments(
                row: ThreadItem.MessageItem,
                content: String,
                vararg attachments: MessageAttachment,
            ) = row.copy(message = row.message.copy(content = content, attachments = attachments.toList()))
            val rows =
                listOf(
                    // Sent: every hint known, one of them an unusual but legal name.
                    withAttachments(
                        message("sent", role = Role.User),
                        "two files",
                        MessageAttachment(ID_A, "photo \"1\".jpg", "image/jpeg"),
                        MessageAttachment(ID_B, "notes.txt", "text/plain"),
                    ),
                    // Attachment-only history row: ids with no hints.
                    withAttachments(message("history", role = Role.User), "", MessageAttachment(ID_A), MessageAttachment(ID_B)),
                    // Offer row: a name, known empty, and no MIME.
                    withAttachments(message("attachment-offer-$ID_A"), "", MessageAttachment(ID_A, "", null)),
                    message("plain"),
                )
            assertTrue(cache().writeThread("server-a", "conv-1", rows).isSuccess)

            assertEquals(rows, cache().readThread("server-a", "conv-1"))
        }

    @Test
    fun `a thread document written before attachment references still loads`() =
        runTest {
            cache().writeThread("server-a", "conv-1", listOf(message("m1"))).getOrThrow()
            val document = threadFiles().single()
            // The pre-#983 record shape, verbatim: no attachments key at all.
            document.writeText(
                """{"version":1,"rows":[{"message":{"id":"m0","sessionId":"session-1","role":"User","content":"hi",""" +
                    """"timestamp":"2026-09-22T10:11:12.123456789Z"}}]}""",
            )

            val restored = cache().readThread("server-a", "conv-1")

            val expected = message("m0", role = Role.User).let { it.copy(message = it.message.copy(content = "hi")) }
            assertEquals(listOf(expected), restored)
        }

    @Test
    fun `unrecognized streaming and running rows are never stored`() =
        runTest {
            val settled = message("m1")
            cache()
                .writeThread(
                    "server-a",
                    "conv-1",
                    listOf(settled, unrecognized("u1"), message("m2", isStreaming = true), tool("toolu_1", ToolCallStatus.Running)),
                ).getOrThrow()

            assertEquals(listOf(settled), cache().readThread("server-a", "conv-1"))
            val bytes = threadFiles().single().readText()
            assertTrue("unrecognized raw leaked into the document", !bytes.contains("mystery"))
        }

    @Test
    fun `banner rows are never stored`() =
        runTest {
            val settled = message("m1")
            val banner =
                ThreadItem.Banner(
                    BannerLevel.Warning,
                    "hook said no",
                    truncated = false,
                    occurredAt = Instant.parse("2026-09-05T10:00:00Z"),
                )
            cache().writeThread("server-a", "conv-1", listOf(settled, banner)).getOrThrow()

            assertEquals(listOf(settled), cache().readThread("server-a", "conv-1"))
            assertTrue("banner text leaked into the document", !threadFiles().single().readText().contains("hook said no"))
        }

    @Test
    fun `compaction rows are never stored`() =
        runTest {
            val settled = message("m1")
            val compaction = ThreadItem.CompactionBoundary(24000, 3000, manual = true, occurredAt = Instant.parse("2026-09-05T10:00:00Z"))
            cache().writeThread("server-a", "conv-1", listOf(settled, compaction)).getOrThrow()

            assertEquals(listOf(settled), cache().readThread("server-a", "conv-1"))
        }

    @Test
    fun `model refusal rows are never stored`() =
        runTest {
            val settled = message("m1")
            val refusal =
                ThreadItem.ModelRefusal(
                    originalModel = "claude-opus-5-5",
                    fallbackModel = "claude-sonnet-5",
                    banner = "refused and retried",
                    bannerTruncated = false,
                    occurredAt = Instant.parse("2026-09-05T10:00:00Z"),
                )
            cache().writeThread("server-a", "conv-1", listOf(settled, refusal)).getOrThrow()

            assertEquals(listOf(settled), cache().readThread("server-a", "conv-1"))
            assertTrue("refusal banner leaked into the document", !threadFiles().single().readText().contains("refused and retried"))
        }

    @Test
    fun `only the newest rows are kept`() =
        runTest {
            val rows = (1..MAX_CACHED_THREAD_ROWS + 5).map { message("m$it") }
            cache().writeThread("server-a", "conv-1", rows).getOrThrow()

            assertEquals(rows.takeLast(MAX_CACHED_THREAD_ROWS), cache().readThread("server-a", "conv-1"))
        }

    @Test
    fun `threads are isolated per conversation and per host`() =
        runTest {
            cache().writeThread("server-a", "conv-1", listOf(message("a1"))).getOrThrow()
            cache().writeThread("server-a", "conv-2", listOf(message("a2"))).getOrThrow()
            cache().writeThread("server-b", "conv-1", listOf(message("b1"))).getOrThrow()

            assertEquals(listOf(message("a1")), cache().readThread("server-a", "conv-1"))
            assertEquals(listOf(message("a2")), cache().readThread("server-a", "conv-2"))
            assertEquals(listOf(message("b1")), cache().readThread("server-b", "conv-1"))
            assertEquals(emptyList<ThreadItem>(), cache().readThread("server-b", "conv-2"))
        }

    @Test
    fun `a second write replaces the whole thread`() =
        runTest {
            cache().writeThread("server-a", "conv-1", listOf(message("m1"), message("m2"))).getOrThrow()
            cache().writeThread("server-a", "conv-1", listOf(message("m3"))).getOrThrow()

            assertEquals(listOf(message("m3")), cache().readThread("server-a", "conv-1"))
        }

    @Test
    fun `unreadable thread documents read empty and are left on disk`() =
        runTest {
            val unreadable =
                listOf(
                    "{\"version\":1,\"rows\":[",
                    "not json",
                    "{\"version\":99,\"rows\":[]}",
                    // A row that is neither a message nor a boundary.
                    "{\"version\":1,\"rows\":[{}]}",
                )
            cache().writeThread("server-a", "conv-1", listOf(message("m1"))).getOrThrow()
            val document = threadFiles().single()
            unreadable.forEach { text ->
                document.writeText(text)
                assertEquals(text, emptyList<ThreadItem>(), cache().readThread("server-a", "conv-1"))
                assertEquals(text, document.readText())
            }
        }

    @Test
    fun `a document holding a duplicate key or a running tool reads empty`() =
        runTest {
            cache()
                .writeThread(
                    "server-a",
                    "conv-1",
                    listOf(
                        message("m1"),
                        boundary("session-1", "session-2"),
                        boundary("session-3", "session-4"),
                        tool("toolu_1", ToolCallStatus.Done),
                    ),
                ).getOrThrow()
            val document = threadFiles().single()
            val healthy = document.readText()

            // Tampered shapes a writer never produces: each would crash or mislead the thread's LazyColumn.
            val tampered =
                listOf(
                    healthy.replace("\"toolu_1\"", "\"m1\""),
                    // Both boundaries share an instant, so this repeats the whole identity triple.
                    healthy.replace("\"session-3\"", "\"session-1\"").replace("\"session-4\"", "\"session-2\""),
                    healthy.replace("\"Done\"", "\"Running\""),
                )
            tampered.forEach { text ->
                assertTrue("tamper did not apply", text != healthy)
                document.writeText(text)
                assertEquals(text, emptyList<ThreadItem>(), cache().readThread("server-a", "conv-1"))
            }
        }

    @Test
    fun `boundaries sharing a session pair but not an instant read back`() =
        runTest {
            // A session idle-evicted twice keeps its id, so both evictions carry the pair A->A (#775).
            val rows =
                listOf(
                    boundary("session-1", "session-1", BoundaryReason.IdleEvict, at = "2026-09-22T11:00:00Z"),
                    message("m1").copy(message = message("m1").message.copy(sessionId = "session-1")),
                    boundary("session-1", "session-1", BoundaryReason.IdleEvict, at = "2026-09-22T12:00:00Z"),
                )
            cache().writeThread("server-a", "conv-1", rows).getOrThrow()

            assertEquals(rows, cache().readThread("server-a", "conv-1"))
        }

    @Test
    fun `a boundary repeating a held session pair and instant reads empty`() =
        runTest {
            val rows =
                listOf(
                    boundary("session-1", "session-1", BoundaryReason.IdleEvict, at = "2026-09-22T11:00:00Z"),
                    boundary("session-1", "session-1", BoundaryReason.IdleEvict, at = "2026-09-22T12:00:00Z"),
                )
            cache().writeThread("server-a", "conv-1", rows).getOrThrow()
            val document = threadFiles().single()
            val tampered = document.readText().replace("2026-09-22T12:00:00Z", "2026-09-22T11:00:00Z")
            assertTrue("tamper did not apply", tampered != document.readText())
            document.writeText(tampered)

            assertEquals(emptyList<ThreadItem>(), cache().readThread("server-a", "conv-1"))
        }

    @Test
    fun `removing a conversation removes its thread and leaves siblings readable`() =
        runTest {
            cache().writeConversations("server-a", listOf(conversation("conv-1"), conversation("conv-2"))).getOrThrow()
            cache().writeThread("server-a", "conv-1", listOf(message("m1"))).getOrThrow()
            cache().writeThread("server-a", "conv-2", listOf(message("m2"))).getOrThrow()

            assertTrue(cache().removeConversation("server-a", "conv-1").isSuccess)

            assertEquals(emptyList<ThreadItem>(), cache().readThread("server-a", "conv-1"))
            assertEquals(listOf(message("m2")), cache().readThread("server-a", "conv-2"))
            assertEquals(listOf("conv-2"), cache().readConversations("server-a").map { it.id })
            assertEquals(1, threadFiles().size)
        }

    @Test
    fun `removing a conversation whose host metadata was never written still removes its thread`() =
        runTest {
            cache().writeThread("server-a", "conv-1", listOf(message("m1"))).getOrThrow()

            assertTrue(cache().removeConversation("server-a", "conv-1").isSuccess)

            assertEquals(emptyList<ThreadItem>(), cache().readThread("server-a", "conv-1"))
            assertEquals(emptyList<File>(), threadFiles())
        }

    @Test
    fun `removing a host removes its threads and no other host's`() =
        runTest {
            cache().writeThread("server-a", "conv-1", listOf(message("a1"))).getOrThrow()
            cache().writeThread("server-b", "conv-1", listOf(message("b1"))).getOrThrow()

            assertTrue(cache().removeHost("server-a").isSuccess)

            assertEquals(emptyList<ThreadItem>(), cache().readThread("server-a", "conv-1"))
            assertEquals(listOf(message("b1")), cache().readThread("server-b", "conv-1"))
        }

    @Test
    fun `no conversation id reaches a path or a log line`() =
        runTest {
            val conversationId = "../conversation-ID-CANARY"
            cache().writeThread("server-ID-CANARY", conversationId, listOf(message("m1"))).getOrThrow()
            cache().readThread("server-ID-CANARY", conversationId)
            threadFiles().single().writeText("not json")
            cache().readThread("server-ID-CANARY", conversationId)
            cache().removeConversation("server-ID-CANARY", conversationId).getOrThrow()

            val canonicalRoot = root.canonicalPath
            root.walkTopDown().forEach { file ->
                assertTrue(file.canonicalPath.startsWith(canonicalRoot))
                assertTrue("path leaked an id: ${file.path}", !file.path.contains("CANARY"))
            }
            assertTrue(logs.isNotEmpty())
            logs.forEach { line ->
                listOf("CANARY", "content of").forEach { value ->
                    assertTrue("log line leaked $value: $line", !line.contains(value))
                }
            }
        }

    @Test
    fun `a thread write failure reports a coded exception without a cause`() =
        runTest {
            root.mkdirs()
            val blocker = File(root, "blocker")
            blocker.writeText("in the way")
            val blocked = FileConversationCache(blocker, UnconfinedTestDispatcher())

            val error = blocked.writeThread("server-a", "conv-1", listOf(message("m1"))).exceptionOrNull()

            assertTrue("expected a ConversationCacheException, got $error", error is ConversationCacheException)
            assertEquals(null, error?.cause)
        }

    private fun conversation(id: String) =
        Conversation(
            id = id,
            name = "Channel $id",
            cwd = "/home/pyry/projects/$id",
            currentSessionId = "session-$id",
            sessionHistory = emptyList(),
            isPromoted = true,
            lastUsedAt = Instant.parse("2026-09-22T10:11:12Z"),
        )

    private companion object {
        const val ID_A = "0f4c8a52-3d1e-4b7a-9c6d-2e5f8a1b3c4d"
        const val ID_B = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    }
}

package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.RelayLog
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
 * Off-device proof for the app-private cache (#795).
 *
 * The implementation is handed a root directory rather than a `Context`, so the whole contract —
 * persistence included — is provable on a plain JVM temp folder. Persistence assertions always read
 * through a **second** [FileConversationCache] over the same root: a fresh instance is the stand-in
 * for the fresh process AC 1 names, and this implementation holds no in-memory state that could make
 * a same-instance read pass while the bytes on disk are wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileConversationCacheTest {
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

    private fun conversation(
        id: String,
        name: String? = "Channel $id",
        lastUsedAt: String = "2026-09-22T10:11:12.123456789Z",
    ) = Conversation(
        id = id,
        name = name,
        cwd = "/home/pyry/projects/$id",
        currentSessionId = "session-$id",
        sessionHistory = listOf("session-$id-old", "session-$id"),
        isPromoted = true,
        lastUsedAt = Instant.parse(lastUsedAt),
        isSleeping = true,
        archived = true,
        muted = true,
        workspaceLabel = "Workspace $id",
    )

    /** The single stored document, located without duplicating the implementation's path derivation. */
    private fun storedFiles(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    private fun overwriteStoredDocument(text: String) {
        storedFiles().single().writeText(text)
    }

    @Test
    fun `metadata round-trips field-for-field through a fresh instance`() =
        runTest {
            val written = conversation("alpha")
            assertTrue(cache().writeConversations("server-a", listOf(written)).isSuccess)

            assertEquals(listOf(written), cache().readConversations("server-a"))
        }

    @Test
    fun `nullable fields round-trip as null`() =
        runTest {
            val written =
                conversation("alpha", name = null).copy(
                    workspaceLabel = null,
                    sessionHistory = emptyList(),
                    isPromoted = false,
                    isSleeping = false,
                    archived = false,
                    muted = false,
                )
            cache().writeConversations("server-a", listOf(written)).getOrThrow()

            assertEquals(listOf(written), cache().readConversations("server-a"))
        }

    @Test
    fun `write order is preserved verbatim`() =
        runTest {
            val rows = listOf(conversation("c"), conversation("a"), conversation("b"))
            cache().writeConversations("server-a", rows).getOrThrow()

            assertEquals(rows, cache().readConversations("server-a"))
        }

    @Test
    fun `a host that was never written reads empty`() =
        runTest {
            assertEquals(emptyList<Conversation>(), cache().readConversations("server-unknown"))
        }

    @Test
    fun `a truncated document reads empty and is left on disk`() =
        runTest {
            cache().writeConversations("server-a", listOf(conversation("alpha"))).getOrThrow()
            val stored = storedFiles().single()
            val original = stored.readText().substring(0, 20)
            stored.writeText(original)

            assertEquals(emptyList<Conversation>(), cache().readConversations("server-a"))
            assertEquals(original, storedFiles().single().readText())
        }

    @Test
    fun `a document that is not json reads empty`() =
        runTest {
            cache().writeConversations("server-a", listOf(conversation("alpha"))).getOrThrow()
            overwriteStoredDocument("not json at all")

            assertEquals(emptyList<Conversation>(), cache().readConversations("server-a"))
        }

    @Test
    fun `an unsupported stored version reads empty`() =
        runTest {
            cache().writeConversations("server-a", listOf(conversation("alpha"))).getOrThrow()
            overwriteStoredDocument(storedFiles().single().readText().replace("\"version\":1", "\"version\":2"))

            assertEquals(emptyList<Conversation>(), cache().readConversations("server-a"))
        }

    @Test
    fun `an unparseable timestamp reads empty`() =
        runTest {
            cache().writeConversations("server-a", listOf(conversation("alpha"))).getOrThrow()
            overwriteStoredDocument(
                storedFiles().single().readText().replace("2026-09-22T10:11:12.123456789Z", "yesterday"),
            )

            assertEquals(emptyList<Conversation>(), cache().readConversations("server-a"))
        }

    @Test
    fun `a duplicate conversation id reads empty rather than picking a winner`() =
        runTest {
            val row = conversation("alpha")
            cache().writeConversations("server-a", listOf(row, row)).getOrThrow()

            assertEquals(emptyList<Conversation>(), cache().readConversations("server-a"))
        }

    @Test
    fun `removing one host leaves every other host readable`() =
        runTest {
            val cache = cache()
            cache.writeConversations("server-a", listOf(conversation("alpha"))).getOrThrow()
            cache.writeConversations("server-b", listOf(conversation("beta"))).getOrThrow()
            cache.writeConversations("server-c", listOf(conversation("gamma"))).getOrThrow()

            assertTrue(cache.removeHost("server-b").isSuccess)

            assertEquals(listOf(conversation("alpha")), cache().readConversations("server-a"))
            assertEquals(emptyList<Conversation>(), cache().readConversations("server-b"))
            assertEquals(listOf(conversation("gamma")), cache().readConversations("server-c"))
        }

    @Test
    fun `removing an unknown host succeeds and disturbs nothing`() =
        runTest {
            cache().writeConversations("server-a", listOf(conversation("alpha"))).getOrThrow()
            val before = storedFiles().single().readText()

            assertTrue(cache().removeHost("server-unknown").isSuccess)

            assertEquals(before, storedFiles().single().readText())
            assertEquals(listOf(conversation("alpha")), cache().readConversations("server-a"))
        }

    @Test
    fun `removing one conversation leaves the host's others readable in order`() =
        runTest {
            val rows = listOf(conversation("alpha"), conversation("beta"), conversation("gamma"))
            cache().writeConversations("server-a", rows).getOrThrow()

            assertTrue(cache().removeConversation("server-a", "beta").isSuccess)

            assertEquals(listOf(rows[0], rows[2]), cache().readConversations("server-a"))
        }

    @Test
    fun `removing a conversation touches no other host`() =
        runTest {
            cache().writeConversations("server-a", listOf(conversation("alpha"))).getOrThrow()
            cache().writeConversations("server-b", listOf(conversation("alpha"))).getOrThrow()

            cache().removeConversation("server-a", "alpha").getOrThrow()

            assertEquals(emptyList<Conversation>(), cache().readConversations("server-a"))
            assertEquals(listOf(conversation("alpha")), cache().readConversations("server-b"))
        }

    @Test
    fun `removing an unknown conversation succeeds and changes nothing`() =
        runTest {
            val rows = listOf(conversation("alpha"), conversation("beta"))
            cache().writeConversations("server-a", rows).getOrThrow()

            assertTrue(cache().removeConversation("server-a", "never-stored").isSuccess)
            assertTrue(cache().removeConversation("server-unknown", "alpha").isSuccess)

            assertEquals(rows, cache().readConversations("server-a"))
        }

    @Test
    fun `a second write replaces the host's whole set`() =
        runTest {
            cache().writeConversations("server-a", listOf(conversation("alpha"), conversation("beta"))).getOrThrow()
            cache().writeConversations("server-a", listOf(conversation("beta"))).getOrThrow()

            assertEquals(listOf(conversation("beta")), cache().readConversations("server-a"))
        }

    @Test
    fun `a traversal-shaped server id cannot escape the root`() =
        runTest {
            val hostile = "../../etc/passwd"
            cache().writeConversations(hostile, listOf(conversation("alpha"))).getOrThrow()

            val canonicalRoot = root.canonicalFile
            assertTrue(
                storedFiles().all { it.canonicalFile.startsWith(canonicalRoot) },
            )
            assertEquals(listOf(conversation("alpha")), cache().readConversations(hostile))
            assertEquals(emptyList<Conversation>(), cache().readConversations("../../etc/shadow"))
        }

    @Test
    fun `no identifier reaches the filesystem namespace`() =
        runTest {
            cache().writeConversations("server-ID-CANARY", listOf(conversation("conversation-ID-CANARY"))).getOrThrow()

            val paths = root.walkTopDown().map { it.relativeTo(root).path }.toList()
            assertTrue(paths.none { it.contains("server-ID-CANARY") })
            assertTrue(paths.none { it.contains("conversation-ID-CANARY") })
        }

    @Test
    fun `stored bytes carry no pairing credential`() =
        runTest {
            cache().writeConversations("server-ID-CANARY", listOf(conversation("alpha"))).getOrThrow()

            val bytes = storedFiles().joinToString("\n") { it.readText() }
            listOf("server-ID-CANARY", "token", "wss://", "relay", "pubkey", "staticPublicKey").forEach { secret ->
                assertTrue("stored document leaked $secret", !bytes.contains(secret))
            }
        }

    @Test
    fun `no identifier or cached value reaches a log`() =
        runTest {
            val cache = cache()
            cache.writeConversations("server-ID-CANARY", listOf(conversation("conversation-ID-CANARY"))).getOrThrow()
            cache.readConversations("server-ID-CANARY")
            overwriteStoredDocument("not json at all")
            cache.readConversations("server-ID-CANARY")
            cache.removeConversation("server-ID-CANARY", "conversation-ID-CANARY")
            cache.removeHost("server-ID-CANARY")
            cache.readConversations("server-unknown")

            assertTrue("no log line was emitted at all", logs.isNotEmpty())
            val forbidden =
                listOf(
                    "server-ID-CANARY",
                    "conversation-ID-CANARY",
                    "Channel ",
                    "Workspace ",
                    "/home/pyry",
                    "session-",
                )
            logs.forEach { line ->
                forbidden.forEach { value ->
                    assertTrue("log line leaked $value: $line", !line.contains(value))
                }
            }
        }

    @Test
    fun `a write failure reports a coded exception without a cause`() =
        runTest {
            // A regular file where the host directory must go: the write cannot create its parent.
            root.mkdirs()
            val blocker = File(root, "blocker")
            blocker.writeText("in the way")
            val blocked = FileConversationCache(blocker, UnconfinedTestDispatcher())

            val failure = blocked.writeConversations("server-a", listOf(conversation("alpha")))

            val error = failure.exceptionOrNull()
            assertTrue("expected a ConversationCacheException, got $error", error is ConversationCacheException)
            assertEquals(null, error?.cause)
            assertTrue("message leaked the server id: ${error?.message}", error?.message?.contains("server-a") == false)
        }
}

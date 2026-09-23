package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Off-device proof for the cache's read-position family (#877). Every persistence assertion reads
 * through a second [FileConversationCache] over the same root, the stand-in for a restarted process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileConversationCacheReadPositionTest {
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

    private val positions =
        linkedMapOf(
            "conversation-b" to ReadPosition("turn-2", null),
            "conversation-a" to ReadPosition("turn-1", "turn-1"),
        )

    @Test
    fun positionsRoundTripPerHostInOrder() =
        runTest {
            assertTrue(cache().writeReadPositions("host-a", positions).isSuccess)
            assertTrue(cache().writeReadPositions("host-b", mapOf("conversation-a" to ReadPosition("other", null))).isSuccess)

            val restored = cache().readReadPositions("host-a")
            assertEquals(positions, restored)
            assertEquals(positions.keys.toList(), restored.keys.toList())
            assertEquals(mapOf("conversation-a" to ReadPosition("other", null)), cache().readReadPositions("host-b"))
            assertEquals(emptyMap<String, ReadPosition>(), cache().readReadPositions("never-written"))
        }

    @Test
    fun removingAHostOrAConversationDropsItsPositions() =
        runTest {
            cache().writeReadPositions("host-a", positions)
            cache().writeReadPositions("host-b", positions)

            assertTrue(cache().removeConversation("host-a", "conversation-a").isSuccess)
            assertEquals(mapOf("conversation-b" to ReadPosition("turn-2", null)), cache().readReadPositions("host-a"))

            assertTrue(cache().removeHost("host-a").isSuccess)
            assertEquals(emptyMap<String, ReadPosition>(), cache().readReadPositions("host-a"))
            assertEquals(positions, cache().readReadPositions("host-b"))
        }

    @Test
    fun unreadableOrDuplicateDocumentsReadAsEmptyAndLogNoIds() =
        runTest {
            cache().writeReadPositions("host-a", positions)
            val document = root.walkTopDown().single { it.name == "read-positions.json" }
            val text = document.readText()

            document.writeText(text.replace("conversation-b", "conversation-a"))
            assertEquals(emptyMap<String, ReadPosition>(), cache().readReadPositions("host-a"))
            document.writeText("{not json")
            assertEquals(emptyMap<String, ReadPosition>(), cache().readReadPositions("host-a"))
            document.writeText(text.replace("\"version\":1", "\"version\":2"))
            assertEquals(emptyMap<String, ReadPosition>(), cache().readReadPositions("host-a"))

            assertTrue(logs.any { it.contains("operation=read_positions status=failed code=invalid_data") })
            assertTrue(logs.none { it.contains("conversation-") || it.contains("turn-") || it.contains("host-a") })
        }
}

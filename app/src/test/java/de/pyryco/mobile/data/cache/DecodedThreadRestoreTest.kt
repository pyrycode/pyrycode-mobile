package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecodedThreadRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        RelayLog.sink = oldSink
    }

    private fun row(text: String = "Saved") =
        ThreadItem.MessageItem(Message("row", "s", Role.User, text, Instant.fromEpochSeconds(1), false))

    @Test fun rowAndPositionReadersReuseOneValidatedDecode() =
        runTest {
            val cache = FileConversationCache(temporary.newFolder())
            cache.writeThread("host", "c", listOf(row())).getOrThrow()
            cache.writeHistoryPosition("host", "c", HistoryPosition("saved", false, HistoryCoverage())).getOrThrow()
            val first = cache.readThread("host", "c")
            val position = cache.readHistoryPosition("host", "c")
            assertSame(first, cache.readThread("host", "c"))
            assertSame(position, cache.readHistoryPosition("host", "c"))
        }

    @Test fun anotherInstanceSameLengthReplacementCannotReuseOldDecodedRows() =
        runTest {
            val root = temporary.newFolder()
            val reader = FileConversationCache(root)
            val writer = FileConversationCache(root)
            writer.writeThread("host", "c", listOf(row("First"))).getOrThrow()
            val first = reader.readThread("host", "c")
            val document = root.walkTopDown().single { it.isFile }
            val bytes = document.length()
            val modified = document.lastModified()
            writer.writeThread("host", "c", listOf(row("Other"))).getOrThrow()
            assertEquals(bytes, document.length())
            document.setLastModified(modified)
            val replaced = reader.readThread("host", "c")
            assertEquals(listOf(row("Other")), replaced)
            assertNotSame(first, replaced)
        }

    @Test fun collidingConversationIdsRemainHostScoped() =
        runTest {
            val cache = FileConversationCache(temporary.newFolder())
            cache.writeThread("a", "c", listOf(row("Host A"))).getOrThrow()
            cache.writeThread("b", "c", listOf(row("Host B"))).getOrThrow()
            assertEquals(listOf(row("Host A")), cache.readThread("a", "c"))
            assertEquals(listOf(row("Host B")), cache.readThread("b", "c"))
            assertEquals(listOf(row("Host A")), cache.readThread("a", "c"))
        }

    @Test fun malformedReplacementMetadataLeavesRowsReadableButCannotBorrowOldPosition() =
        runTest {
            val root = temporary.newFolder()
            val cache = FileConversationCache(root)
            cache.writeThread("host", "c", listOf(row())).getOrThrow()
            cache.writeHistoryPosition("host", "c", HistoryPosition("saved", false)).getOrThrow()
            val first = cache.readThread("host", "c")
            assertEquals("saved", cache.readHistoryPosition("host", "c")?.cursor)
            val document = root.walkTopDown().single { it.isFile }
            document.writeText(document.readText().replace("\"atStart\":false", "\"atStart\":\"bad\""))
            assertEquals(first, cache.readThread("host", "c"))
            assertNull(cache.readHistoryPosition("host", "c"))
        }

    @Test fun unreadableReplacementCannotBorrowHeldRowsOrPosition() =
        runTest {
            val root = temporary.newFolder()
            val cache = FileConversationCache(root)
            cache.writeThread("host", "c", listOf(row())).getOrThrow()
            cache.writeHistoryPosition("host", "c", HistoryPosition("saved", false)).getOrThrow()
            cache.readThread("host", "c")
            root.walkTopDown().single { it.isFile }.writeText("broken")
            assertEquals(emptyList<ThreadItem>(), cache.readThread("host", "c"))
            assertNull(cache.readHistoryPosition("host", "c"))
        }

    @Test fun metadataBeforeRowsAndUnknownFieldsKeepOptionalFailureIsolated() =
        runTest {
            val root = temporary.newFolder()
            FileConversationCache(root).writeThread("host", "c", listOf(row())).getOrThrow()
            val document = root.walkTopDown().single { it.isFile }
            val stored = MobileJson.parseToJsonElement(document.readText()).jsonObject
            for (history in listOf(JsonPrimitive("bad"), JsonObject(mapOf("atStart" to JsonPrimitive("bad"))))) {
                document.writeText(
                    JsonObject(
                        linkedMapOf(
                            "history" to history,
                            "future" to JsonObject(mapOf("nested" to JsonPrimitive(true))),
                            "rows" to stored.getValue("rows"),
                            "version" to stored.getValue("version"),
                        ),
                    ).toString(),
                )
                val reader = FileConversationCache(root)
                assertEquals(listOf(row()), reader.readThread("host", "c"))
                assertNull(reader.readHistoryPosition("host", "c"))
            }
        }

    @Test fun removedHostAndMissingDocumentCannotResurrectRetainedContent() =
        runTest {
            val root = temporary.newFolder()
            val reader = FileConversationCache(root)
            reader.writeThread("host", "c", listOf(row())).getOrThrow()
            reader.readThread("host", "c")
            FileConversationCache(root).removeHost("host").getOrThrow()
            assertEquals(emptyList<ThreadItem>(), reader.readThread("host", "c"))
            assertNull(reader.readHistoryPosition("host", "c"))
            reader.writeThread("host", "c", listOf(row())).getOrThrow()
            reader.readThread("host", "c")
            reader.removeConversation("host", "c").getOrThrow()
            assertEquals(emptyList<ThreadItem>(), reader.readThread("host", "c"))
        }
}

package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnsignedHistorySpan
import de.pyryco.mobile.data.repository.historyIdentity
import de.pyryco.mobile.data.repository.mergeIdentity
import de.pyryco.mobile.ui.conversations.thread.fragmentedHistoryFixture
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

    @Test fun fullFragmentedFixtureRestoresExactRowsAndClaimsThroughFreshCache() =
        runTest {
            val root = temporary.newFolder()
            val (coverage, rows) = fragmentedHistoryFixture((0 until 18000).toList())
            val writer = FileConversationCache(root)
            writer.writeThread("host", "c", rows).getOrThrow()
            writer.writeHistoryPosition("host", "c", HistoryPosition("saved", false, coverage)).getOrThrow()
            val reader = FileConversationCache(root)
            assertEquals(rows, reader.readThread("host", "c"))
            assertEquals(coverage, reader.readHistoryPosition("host", "c")?.coverage)
        }

    @Test fun persistedTimestampParsingRetainsCalendarValidationAndLegacyFormats() =
        runTest {
            val root = temporary.newFolder()
            FileConversationCache(root).writeThread("host", "c", listOf(row())).getOrThrow()
            val document = root.walkTopDown().single { it.isFile }
            val original = document.readText()
            val timestamps =
                listOf(
                    "0000-01-01T00:00:00Z",
                    "1969-12-31T23:59:59Z",
                    "2000-02-29T12:34:56Z",
                    "2024-02-29T00:00:00Z",
                    "9999-12-31T23:59:59Z",
                    "2026-10-10T01:02:03.123456789Z",
                    "2026-10-10T01:02:03+03:00",
                    "+10000-01-01T00:00:00Z",
                    "-0001-01-01T00:00:00Z",
                    "2026-10-10t01:02:03z",
                    "2026-10-10T01:02:03.000Z",
                    "1900-02-29T00:00:00Z",
                    "2026-02-30T00:00:00Z",
                    "2026-00-10T00:00:00Z",
                    "2026-13-10T00:00:00Z",
                    "2026-10-00T00:00:00Z",
                    "2026-10-32T00:00:00Z",
                    "2026-10-10T24:00:00Z",
                    "2026-10-10T00:60:00Z",
                    "2026-10-10T23:59:60Z",
                    "2026-10-10T23:59:61Z",
                    "2026-10-10T0x:00:00Z",
                    "20x6-10-10T00:00:00Z",
                    "2026-1x-10T00:00:00Z",
                    "2026/10/10T00:00:00Z",
                )
            for (text in timestamps) {
                document.writeText(original.replace("\"timestamp\":\"1970-01-01T00:00:01Z\"", "\"timestamp\":${JsonPrimitive(text)}"))
                val expected = runCatching { Instant.parse(text) }.getOrNull()
                val restored = FileConversationCache(root).readThread("host", "c")
                assertEquals(text, expected?.let { listOf(row().copy(message = row().message.copy(timestamp = it))) }.orEmpty(), restored)
            }
        }

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

    @Test fun missingRequiredHistoryFieldsRejectOnlyMetadataWhileNullableDefaultsRemainCompatible() =
        runTest {
            val root = temporary.newFolder()
            FileConversationCache(root).writeThread("host", "c", listOf(row())).getOrThrow()
            val document = root.walkTopDown().single { it.isFile }
            val stored = MobileJson.parseToJsonElement(document.readText()).jsonObject
            for ((history, valid) in listOf(
                "{\"cursor\":\"saved\",\"atStart\":false}" to true,
                "{\"cursor\":\"saved\",\"atStart\":false,\"coverage\":null}" to true,
                "{\"cursor\":\"saved\",\"atStart\":false,\"coverage\":{\"spans\":[],\"newestCursor\":null}}" to true,
                "{\"cursor\":\"saved\",\"atStart\":false,\"coverage\":{\"spans\":[]}}" to true,
                "{\"atStart\":false}" to false,
                "{\"cursor\":\"saved\"}" to false,
                "{\"cursor\":null,\"atStart\":false}" to false,
            )) {
                document.writeText(JsonObject(stored + ("history" to MobileJson.parseToJsonElement(history))).toString())
                val reader = FileConversationCache(root)
                assertEquals(listOf(row()), reader.readThread("host", "c"))
                assertEquals(history, if (valid) "saved" else null, reader.readHistoryPosition("host", "c")?.cursor)
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

    @Test fun fullUnsignedMetadataIsValidatedAndChangedContentLosesItsClaims() =
        runTest {
            val root = temporary.newFolder()
            val saved = row()
            val key = historyIdentity(saved.mergeIdentity())
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                    unsignedRowOrder = mapOf(key to ULong.MAX_VALUE),
                    unsignedRowEntries = mapOf(key to setOf(ULong.MAX_VALUE)),
                    proofs = mapOf(key to cachedThreadRowProof(saved)),
                )
            val writer = FileConversationCache(root)
            writer.writeThread("host", "c", listOf(saved)).getOrThrow()
            writer.writeHistoryPosition("host", "c", HistoryPosition("saved", true, coverage)).getOrThrow()
            val reader = FileConversationCache(root)
            assertEquals(listOf(saved), reader.readThread("host", "c"))
            assertEquals(coverage, reader.readHistoryPosition("host", "c")?.coverage)
            val document = root.walkTopDown().single { it.isFile }
            document.writeText(document.readText().replace("\"content\":\"Saved\"", "\"content\":\"Other\""))
            assertEquals(listOf(row("Other")), reader.readThread("host", "c"))
            assertEquals(emptyList<UnsignedHistorySpan>(), reader.readHistoryPosition("host", "c")?.coverage?.unsignedSpans)
        }

    @Test fun legacyMissingSpansAndInvalidStructuralMetadataKeepRowsIndependent() =
        runTest {
            val root = temporary.newFolder()
            FileConversationCache(root).writeThread("host", "c", listOf(row())).getOrThrow()
            val document = root.walkTopDown().single { it.isFile }
            val stored = MobileJson.parseToJsonElement(document.readText()).jsonObject
            for ((metadata, valid) in listOf(
                "{\"cursor\":\"saved\",\"atStart\":false,\"coverage\":{\"unknown\":true}}" to true,
                "{\"cursor\":\"saved\",\"atStart\":false,\"coverage\":{\"spans\":[{\"first\":2,\"last\":1}]}}" to false,
            )) {
                document.writeText(JsonObject(stored + ("history" to MobileJson.parseToJsonElement(metadata))).toString())
                val reader = FileConversationCache(root)
                assertEquals(listOf(row()), reader.readThread("host", "c"))
                if (valid) {
                    assertEquals("saved", reader.readHistoryPosition("host", "c")?.cursor)
                } else {
                    assertNull(reader.readHistoryPosition("host", "c"))
                }
            }
        }

    @Test fun replacementBeyondReadBufferCannotReuseRowsEvenWithEqualLengthAndTimestamp() =
        runTest {
            val root = temporary.newFolder()
            val firstText = "é😀".repeat(5000) + "First"
            val nextText = "é😀".repeat(5000) + "Other"
            val writer = FileConversationCache(root)
            writer.writeThread("host", "c", listOf(row(firstText))).getOrThrow()
            val reader = FileConversationCache(root)
            val first = reader.readThread("host", "c")
            assertSame(first, reader.readThread("host", "c"))
            val document = root.walkTopDown().single { it.isFile }
            val bytes = document.length()
            val modified = document.lastModified()
            writer.writeThread("host", "c", listOf(row(nextText))).getOrThrow()
            assertEquals(bytes, document.length())
            document.setLastModified(modified)
            val replaced = reader.readThread("host", "c")
            assertEquals(listOf(row(nextText)), replaced)
            assertNotSame(first, replaced)
            assertSame(replaced, reader.readThread("host", "c"))
        }

    @Test fun changedLengthAndTrailingDocumentCannotBorrowTheRetainedDecode() =
        runTest {
            val root = temporary.newFolder()
            FileConversationCache(root).writeThread("host", "c", listOf(row())).getOrThrow()
            val document = root.walkTopDown().single { it.isFile }
            val original = document.readText()
            val reader = FileConversationCache(root)
            var previous = reader.readThread("host", "c")
            for (text in listOf(original + " ".repeat(20000), original)) {
                document.writeText(text)
                val current = reader.readThread("host", "c")
                assertEquals(listOf(row()), current)
                assertNotSame(previous, current)
                previous = current
            }
            document.writeText(original + "{}")
            assertEquals(emptyList<ThreadItem>(), reader.readThread("host", "c"))
            assertNull(reader.readHistoryPosition("host", "c"))
        }
}

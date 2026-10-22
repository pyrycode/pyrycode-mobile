package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadCoverage
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AuthoritativeFileCacheTest {
    private val originalSink = RelayLog.sink

    @Before fun silenceAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = originalSink
    }

    @get:Rule val temporary = TemporaryFolder()

    @Test fun ownedDecodePreservesUnknownNumericLexemesAndSealsNestedArraysAndRetirements() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val raw = item(1, 2, 5, extra = "\"extension\":[-0,1e-400,1.234567890123456789,true,null,{\"nested\":[]}],").raw
            val saved = SavedAuthoritativeThread("e", listOf(raw), 10, 0, retirements = mapOf(2L to 3L))
            FileConversationCache(temporary.root, dispatcher).writeAuthoritativeThread("host", "c", saved).getOrThrow()
            val owned = requireNotNull(FileConversationCache(temporary.root, dispatcher).readAuthoritativeThread("host", "c"))
            assertEquals(saved, owned)
            assertEquals(
                raw,
                owned
                    .toSnapshot()
                    .items
                    .single()
                    .raw,
            )
            val values = owned.items.single()["extension"] as kotlinx.serialization.json.JsonArray

            @Suppress("UNCHECKED_CAST")
            val iterator = values.listIterator() as MutableListIterator<kotlinx.serialization.json.JsonElement>
            iterator.next()
            assertThrows(UnsupportedOperationException::class.java) { iterator.remove() }
            @Suppress("UNCHECKED_CAST")
            val retirement = owned.retirements.entries.single() as MutableMap.MutableEntry<Long, Long>
            assertThrows(UnsupportedOperationException::class.java) { retirement.setValue(9L) }
            assertEquals(saved, owned)
        }

    @Test fun unchangedAuthoritativeBytesReuseOneOwnedValidatedDecode() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cache = FileConversationCache(temporary.root, dispatcher)
            val original = SavedAuthoritativeThread("e", listOf(item(1, 2, 5, extra = "\"extension\":{\"clear\":null},").raw), 10, 10)
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val first = requireNotNull(cache.readAuthoritativeThread("host", "c"))
            val snapshot = first.toSnapshot()
            assertSame(first, cache.readAuthoritativeThread("host", "c"))
            assertSame(snapshot, first.toSnapshot())
            assertEquals(original, first)
            assertThrows(IllegalArgumentException::class.java) { first.copy(checkpoint = 11).toSnapshot() }
            assertNotSame(snapshot, first.copy().toSnapshot())
        }

    @Test fun reusedDecodeNeverHidesSameSizeReplacementForeignOwnerCorruptionOrRemoval() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cache = FileConversationCache(temporary.root, dispatcher)
            val original = SavedAuthoritativeThread("e", listOf(item(1, 2, 5).raw), 10, 10)
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val first = requireNotNull(cache.readAuthoritativeThread("host", "c"))
            val document = temporary.root.walkTopDown().single { it.isFile }
            val bytes = document.readText()
            val changed = bytes.replace("\"rev\":2", "\"rev\":3")
            assertEquals(bytes.length, changed.length)
            document.writeText(changed)
            val replacement = requireNotNull(cache.readAuthoritativeThread("host", "c"))
            assertNotSame(first, replacement)
            assertEquals(
                3L,
                replacement
                    .toSnapshot()
                    .items
                    .single()
                    .rev,
            )
            for (invalid in listOf(changed.replace("\"host\"", "\"evil\""), changed.replace("\"rev\":3", "\"rev\":0"))) {
                document.writeText(invalid)
                assertNull(cache.readAuthoritativeThread("host", "c"))
                assertEquals(invalid, document.readText())
            }
            document.writeText(changed)
            val reread = requireNotNull(cache.readAuthoritativeThread("host", "c"))
            assertNotSame(replacement, reread)
            assertTrue(document.delete())
            assertNull(cache.readAuthoritativeThread("host", "c"))
            cache.resetAuthoritativeThread("host", "c", original.copy(epoch = "replacement")).getOrThrow()
            assertEquals("replacement", cache.readAuthoritativeThread("host", "c")?.epoch)
        }

    @Test fun ownedDecodeCannotBeMutatedAndOrdinaryMutableSavedValuesStillRevalidate() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cache = FileConversationCache(temporary.root, dispatcher)
            val fields = item(1, 2, 5, extra = "\"extension\":{\"clear\":null},").raw.toMutableMap()
            val rows = mutableListOf(JsonObject(fields))
            val ordinary = SavedAuthoritativeThread("e", rows, 10, 10)
            ordinary.toSnapshot()
            fields["rev"] = JsonPrimitive(0)
            assertThrows(IllegalArgumentException::class.java) { ordinary.toSnapshot() }
            fields["rev"] = JsonPrimitive(2)
            cache.writeAuthoritativeThread("host", "c", ordinary).getOrThrow()
            val owned = requireNotNull(cache.readAuthoritativeThread("host", "c"))
            assertThrows(UnsupportedOperationException::class.java) { (owned.items as MutableList<*>).clear() }
            val extension = owned.items.single()["extension"] as JsonObject

            @Suppress("UNCHECKED_CAST")
            val entry = extension.entries.single() as MutableMap.MutableEntry<String, kotlinx.serialization.json.JsonElement>
            assertThrows(UnsupportedOperationException::class.java) { entry.setValue(JsonPrimitive("changed")) }
            assertThrows(UnsupportedOperationException::class.java) { (owned.toSnapshot().items as MutableList<*>).clear() }
            assertEquals(ordinary, cache.readAuthoritativeThread("host", "c"))
        }

    @Test fun fullUnknownFactsPatchClearsAndCertificationRoundTripSeparatelyFromLegacy() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cache = FileConversationCache(temporary.root, dispatcher)
            val raw = item(1, 20, 5, kind = "unknown", extra = "\"parent\":2,\"extension\":{\"text\":\"private body\"},").raw
            val snapshot =
                AuthoritativeThreadSnapshot(
                    "e",
                    listOf(
                        de.pyryco.mobile.data.network.ThreadItemDto
                            .decodeReplacement(raw),
                    ),
                    20,
                    10,
                    coverage = AuthoritativeThreadCoverage(5u, 11u, 5u, true),
                )
            assertTrue(cache.writeAuthoritativeThread("../../host", "../c", snapshot.toSavedAuthoritativeThread()).isSuccess)
            val saved = requireNotNull(FileConversationCache(temporary.root, dispatcher).readAuthoritativeThread("../../host", "../c"))
            assertEquals(raw, saved.items.single())
            assertEquals(snapshot.coverage, saved.toSnapshot().coverage)
            assertEquals(10L, saved.checkpoint)
            assertEquals(20L, saved.appliedVersion)
            assertTrue(cache.readThread("../../host", "../c").isEmpty())
            assertNull(cache.readAuthoritativeThread("host", "c"))
            assertFalse(saved.toString().contains("private body"))
            assertFalse(saved.toString().contains("e" + "xtension"))
            assertTrue(
                temporary.root.walkTopDown().filter { it.isFile }.all {
                    it.canonicalPath.startsWith(
                        temporary.root.canonicalPath + File.separator,
                    )
                },
            )
        }

    @Test fun malformedOwnerVersionAndItemsNeverCertifyOrRepairTheDocument() =
        runTest {
            val cache = FileConversationCache(temporary.root, StandardTestDispatcher(testScheduler))
            val saved = AuthoritativeThreadSnapshot("e", listOf(item(1, 2, 5)), 10, 10).toSavedAuthoritativeThread()
            cache.writeAuthoritativeThread("host", "c", saved)
            val document = temporary.root.walkTopDown().single { it.isFile }
            val original = document.readText()
            for (broken in listOf(
                original.replace("\"host\"", "\"another\""),
                original.replace("\"version\":1", "\"version\":99"),
                original.replace("\"checkpoint\":10", "\"checkpoint\":11"),
                original.replace("\"rev\":2", "\"rev\":99"),
                "{}",
            )) {
                document.writeText(broken)
                assertNull(cache.readAuthoritativeThread("host", "c"))
                assertEquals(broken, document.readText())
            }
        }

    @Test fun retentionDropsCertificationWhenRequiredFactsAreOmitted() =
        runTest {
            val items = (1L..MAX_CACHED_THREAD_ROWS.toLong() + 1).map { item(it, it, it) }
            val snapshot =
                AuthoritativeThreadSnapshot(
                    "e",
                    items,
                    items.last().rev,
                    items.last().rev,
                    coverage = AuthoritativeThreadCoverage(1u, (items.last().rev + 1).toULong(), 1u, false),
                )
            val cache = FileConversationCache(temporary.root, StandardTestDispatcher(testScheduler))
            assertTrue(cache.writeAuthoritativeThread("host", "c", snapshot.toSavedAuthoritativeThread()).isSuccess)
            val saved = requireNotNull(cache.readAuthoritativeThread("host", "c"))
            assertEquals(MAX_CACHED_THREAD_ROWS, saved.items.size)
            assertEquals(0L, saved.checkpoint)
            assertNull(saved.coverage)
            assertEquals(snapshot.completedVersion, saved.appliedVersion)
        }

    @Test fun failureKeepsPreviousBytesAndLogsNoCacheContent() =
        runTest {
            val logs = mutableListOf<String>()
            val previous = RelayLog.sink
            RelayLog.sink = { _, _, line -> logs += line }
            try {
                val cache = FileConversationCache(temporary.root, StandardTestDispatcher(testScheduler))
                val snapshot = AuthoritativeThreadSnapshot("private-epoch", listOf(item(1, 2, 5)), 10, 10)
                val saved = snapshot.toSavedAuthoritativeThread()
                cache.writeAuthoritativeThread("host", "c", saved)
                val document = temporary.root.walkTopDown().single { it.isFile }
                val bytes = document.readText()
                assertTrue(cache.writeAuthoritativeThread("host", "c", saved.copy(checkpoint = 11)).isFailure)
                assertEquals(bytes, document.readText())
                assertEquals(saved, cache.readAuthoritativeThread("host", "c"))
                assertTrue(logs.none { "private-epoch" in it || "start" in it || "content" in it })
            } finally {
                RelayLog.sink = previous
            }
        }
}

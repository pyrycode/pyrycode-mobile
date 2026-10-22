package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadCoverage
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun fullUnknownFactsPatchClearsAndCertificationRoundTrip() =
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
